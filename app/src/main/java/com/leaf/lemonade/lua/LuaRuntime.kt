package com.leaf.lemonade.lua

import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.engine.PlaySession
import com.leaf.lemonade.model.*
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class SignalRef(val owner: Any, val name: String)
class Connection(val owner: Any, val name: String, val fn: LuaValue, val once: Boolean) { var connected = true }

class Tween(val inst: Instance, val info: TweenInfoV, goals0: Map<String, Any?>) {
    var state = 0
    var elapsed = 0f
    var delayElapsed = 0f
    val goals = LinkedHashMap<String, Any?>(goals0)
    val start = HashMap<String, Any?>()
    var started = false
    var repeatsDone = 0
}

/**
 * One Lua world bound to a DataModel. Used for playtests (scripts) and for edit mode
 * (command bar and plugins). All calls happen on the render thread while holding Studio.lock.
 */
class LuaRuntime(val studio: Studio, val dm: DataModel, val isPlay: Boolean) {
    val globals: Globals = JsePlatform.standardGlobals()
    val instanceMeta = LuaTable()
    val api = InstanceApi(this)
    var play: PlaySession? = null
    var time = 0.0
    var stopped = false

    private class Waiting(val co: LuaThread, val at: Double, val start: Double)
    private class ChildWait(val parent: Instance, val name: String, val co: LuaThread, val deadline: Double, val start: Double) { var warned = false }

    private val waiting = ArrayList<Waiting>()
    private val deferred = ArrayList<Pair<LuaThread, Varargs>>()
    private val childWaits = ArrayList<ChildWait>()
    private val conns = IdentityHashMap<Any, HashMap<String, ArrayList<Connection>>>()
    private val signalWaiters = IdentityHashMap<Any, HashMap<String, ArrayList<LuaThread>>>()
    val tweens = ArrayList<Tween>()
    val tweensToAdd = ArrayList<Tween>()
    val moduleCache = IdentityHashMap<Instance, LuaValue>()
    private val wrappers = WeakHashMap<Instance, LuaValue>()
    val callbacks = IdentityHashMap<Instance, HashMap<String, LuaValue>>()
    /** Work queued from other threads (http responses etc), run on the next step. */
    val mainQueue = ConcurrentLinkedQueue<() -> Unit>()
    val sharedTable = LuaTable()
    private var resumeStart = 0L
    private val sethook: LuaValue
    private val hookFn: LuaValue
    /** debug.getinfo from the real debug library (scripts only see a reduced debug table). */
    val debugGetinfo: LuaValue

    private val directEvents = setOf("Heartbeat", "RenderStepped", "Stepped", "PreSimulation", "PostSimulation", "PreRender", "Changed", "AttributeChanged")

    val treeListener = object : TreeListener {
        override fun childAdded(parent: Instance, child: Instance) {
            if (stopped) return
            if (hasConn(parent, "ChildAdded")) fire(parent, "ChildAdded", wrap(child))
            var p: Instance? = parent
            while (p != null) {
                if (hasConn(p, "DescendantAdded")) {
                    fire(p, "DescendantAdded", wrap(child))
                    for (d in child.descendants()) fire(p, "DescendantAdded", wrap(d))
                }
                p = p.parent
            }
            notifyAncestry(child)
            if (childWaits.isNotEmpty()) checkChildWaits()
            if (isPlay) play?.onChildAdded(parent, child)
        }

        override fun childRemoved(parent: Instance, child: Instance) {
            if (stopped) return
            if (hasConn(parent, "ChildRemoved")) fire(parent, "ChildRemoved", wrap(child))
            var p: Instance? = parent
            while (p != null) {
                if (hasConn(p, "DescendantRemoving")) fire(p, "DescendantRemoving", wrap(child))
                p = p.parent
            }
            notifyAncestry(child)
            if (child.destroyed) {
                if (hasConn(child, "Destroying")) fire(child, "Destroying")
                disconnectTree(child)
            }
            if (isPlay) play?.onChildRemoved(parent, child)
        }

        override fun propChanged(inst: Instance, name: String) {
            if (stopped) return
            if (name == "Name" && childWaits.isNotEmpty()) checkChildWaits()
            if (isPlay && name == "Disabled" && inst.isA("BaseScript") && inst["Disabled"] == false) play?.maybeRunScript(inst)
            val m = conns[inst] ?: return
            val disp = PropNames.display(name)
            if (m.containsKey("Changed")) {
                if (inst.isA("ValueBase") && disp == "Value") fire(inst, "Changed", toLua(inst[name], inst, name))
                else fire(inst, "Changed", LuaValue.valueOf(disp))
            }
            if (m.containsKey("Changed:$disp")) fire(inst, "Changed:$disp")
            if (disp == "CFrame") {
                if (m.containsKey("Changed:Position")) fire(inst, "Changed:Position")
                if (m.containsKey("Changed:Orientation")) fire(inst, "Changed:Orientation")
            }
            if (disp == "Health_XML" || disp == "Health") if (m.containsKey("HealthChanged")) fire(inst, "HealthChanged", toLua(inst["Health_XML"] ?: inst["Health"]))
        }
    }

    init {
        globals.load(DebugLib())
        sethook = globals.get("debug").get("sethook")
        debugGetinfo = globals.get("debug").get("getinfo")
        hookFn = fnv { _ ->
            if (stopped) throw LuaError("Script stopped")
            // Roblox Studio allows 10 seconds before it stops a script that never yields.
            if (resumeStart != 0L && System.currentTimeMillis() - resumeStart > 10_000) throw LuaError("Script timeout: exhausted allowed execution time")
            LuaValue.NONE
        }
        val dbg = LuaTable()
        val realDebug = globals.get("debug")
        dbg.set("traceback", realDebug.get("traceback"))
        dbg.set("profilebegin", fnv { LuaValue.NONE }); dbg.set("profileend", fnv { LuaValue.NONE })
        dbg.set("info", fnv { a -> LuaValue.valueOf(a.arg(1).tojstring()) })
        for (n in listOf("dumpcodesize", "dumpheap", "dumprefs", "loadmodule", "tracerefs")) dbg.set(n, fnv { LuaValue.NONE })
        var memoryCategory = ""
        dbg.set("setmemorycategory", fnv { a -> val old = memoryCategory; memoryCategory = a.arg(1).checkjstring(); LuaValue.valueOf(old) })
        dbg.set("resetmemorycategory", fnv { memoryCategory = ""; LuaValue.NONE })
        dbg.set("getmemorycategory", fnv { LuaValue.valueOf(memoryCategory) })
        globals.set("debug", dbg)
        // No way back to Java, files or bytecode (see LuaSandbox): scripts and plugins are
        // other people's code and must never get at the saved Roblox sign-in.
        LuaSandbox.lockDown(globals)
        setHook(globals.running)
        LuaTypes.install(globals)
        RobloxGlobals.install(this)
        dm.listeners.add(treeListener)
    }

    fun setHook(co: LuaThread) {
        try { sethook.invoke(LuaValue.varargsOf(arrayOf(co, hookFn, LuaValue.valueOf(""), LuaValue.valueOf(5000)))) } catch (_: Exception) {}
    }

    fun shutdown() {
        stopped = true
        dm.listeners.remove(treeListener)
        waiting.clear(); deferred.clear(); childWaits.clear(); conns.clear(); signalWaiters.clear(); tweens.clear(); tweensToAdd.clear()
    }

    // ------------------------------------------------------------ value conversion

    fun wrap(inst: Instance?): LuaValue {
        if (inst == null) return LuaValue.NIL
        return wrappers.getOrPut(inst) { LuaUserdata(inst, instanceMeta) }
    }

    fun toLua(v: Any?, owner: Instance? = null, prop: String? = null): LuaValue = when (v) {
        null -> LuaValue.NIL
        is LuaValue -> v
        is String -> LuaValue.valueOf(v)
        is Boolean -> LuaValue.valueOf(v)
        is Int -> LuaValue.valueOf(v)
        is Long -> LuaValue.valueOf(v.toDouble())
        is Float -> LuaValue.valueOf(v.toDouble())
        is Double -> LuaValue.valueOf(v)
        is Instance -> wrap(v)
        is EnumValue -> {
            val type = if (owner != null && prop != null) Enums.typeForProp(owner.className, prop) else null
            val name = Enums.itemName(type, v.value)
            if (type != null && name != null) LuaTypes.wrap(EnumItemV(type, name, v.value)) else LuaValue.valueOf(v.value)
        }
        is BinaryString -> LuaValue.valueOf(v.bytes)
        is SharedStr -> LuaValue.valueOf(v.bytes)
        is OptionalCFrame -> v.cframe?.let { LuaTypes.wrap(it) } ?: LuaValue.NIL
        is ContentValue -> if (v.sourceType == 2) wrap(v.obj) else LuaValue.valueOf(v.uri)
        is RaycastResultV -> LuaTypes.wrap(v)
        is List<*> -> LuaTable().also { t -> v.forEach { t.insert(0, toLua(it)) } }
        else -> LuaTypes.wrap(v)
    }

    /** Convert a Lua value to a Kotlin value suitable for property `type` (if known). */
    fun fromLua(v: LuaValue, type: Int?, inst: Instance? = null, prop: String? = null): Any? {
        if (v.isnil()) return if (type == PropType.OPTCFRAME) OptionalCFrame(null) else null
        val ud = if (v.isuserdata()) v.touserdata() else null
        return when (type) {
            PropType.BOOL -> v.toboolean()
            PropType.INT -> if (ud is EnumItemV) ud.value else v.checkdouble().toInt()
            PropType.FLOAT -> v.checkdouble().toFloat()
            PropType.DOUBLE -> v.checkdouble()
            PropType.INT64 -> v.checkdouble().toLong()
            PropType.STRING -> if (v.isstring() || v.isnumber()) v.tojstring() else throw LuaError("string expected, got ${LuaTypes.typeOf(v)}")
            PropType.ENUM -> when {
                ud is EnumItemV -> EnumValue(ud.value)
                v.isnumber() -> EnumValue(v.toint())
                v.isstring() -> {
                    val t = if (inst != null && prop != null) Enums.typeForProp(inst.className, prop) else null
                    val iv = Enums.types[t]?.get(v.tojstring()) ?: throw LuaError("Invalid value \"${v.tojstring()}\" for enum ${t ?: "?"}")
                    EnumValue(iv)
                }
                else -> throw LuaError("EnumItem expected")
            }
            PropType.BRICKCOLOR -> when (ud) {
                is BrickColorV -> ud
                is Color3 -> BrickColorV(BrickColors.closest(ud))
                else -> BrickColorV(v.checkint())
            }
            PropType.COLOR3, PropType.COLOR3U8 -> when (ud) {
                is Color3 -> ud
                is BrickColorV -> ud.color
                else -> throw LuaError("Color3 expected, got ${LuaTypes.typeOf(v)}")
            }
            PropType.REF -> ud as? Instance ?: throw LuaError("Instance expected, got ${LuaTypes.typeOf(v)}")
            PropType.OPTCFRAME -> OptionalCFrame(ud as? CFrame)
            PropType.CONTENT -> if (ud is Instance) ContentValue(2, "", ud) else ContentValue(1, v.tojstring())
            PropType.VECTOR3 -> ud as? Vector3 ?: throw LuaError("Vector3 expected, got ${LuaTypes.typeOf(v)}")
            PropType.CFRAME -> ud as? CFrame ?: throw LuaError("CFrame expected, got ${LuaTypes.typeOf(v)}")
            PropType.UDIM2 -> ud as? UDim2 ?: throw LuaError("UDim2 expected, got ${LuaTypes.typeOf(v)}")
            else -> when {
                ud != null -> if (ud is EnumItemV) EnumValue(ud.value) else ud
                v.isboolean() -> v.toboolean()
                v.isnumber() -> v.todouble()
                v.isstring() -> v.tojstring()
                else -> throw LuaError("Unable to assign value of type ${LuaTypes.typeOf(v)}")
            }
        }
    }

    // ------------------------------------------------------------ signals

    fun hasConn(owner: Any, name: String) = conns[owner]?.get(name)?.isNotEmpty() == true || signalWaiters[owner]?.get(name)?.isNotEmpty() == true

    fun connect(owner: Any, name: String, fn: LuaValue, once: Boolean): Connection {
        val c = Connection(owner, name, fn, once)
        conns.getOrPut(owner) { HashMap() }.getOrPut(name) { ArrayList() }.add(c)
        return c
    }

    fun disconnect(c: Connection) {
        c.connected = false
        conns[c.owner]?.get(c.name)?.remove(c)
    }

    private fun disconnectTree(i: Instance) {
        conns.remove(i); signalWaiters.remove(i); callbacks.remove(i)
        for (c in i.children) disconnectTree(c)
    }

    fun waitSignal(owner: Any, name: String): Varargs {
        val co = currentCoroutine() ?: throw LuaError("Wait can only be called from a yieldable thread")
        signalWaiters.getOrPut(owner) { HashMap() }.getOrPut(name) { ArrayList() }.add(co)
        return globals.yield(LuaValue.NONE)
    }

    fun fire(owner: Any, name: String, vararg args: LuaValue) = fireArgs(owner, name, LuaValue.varargsOf(args))

    fun fireArgs(owner: Any, name: String, args: Varargs) {
        if (stopped) return
        val list = conns[owner]?.get(name)
        if (list != null && list.isNotEmpty()) {
            val direct = name in directEvents || name.startsWith("Changed:") || name.startsWith("Attr:")
            for (c in list.toList()) {
                if (!c.connected) continue
                if (c.once) disconnect(c)
                if (direct) callDirect(c.fn, args) else spawn(c.fn, args)
            }
        }
        val ws = signalWaiters[owner]?.remove(name)
        ws?.forEach { resume(it, args) }
    }

    // ------------------------------------------------------------ threads

    fun currentCoroutine(): LuaThread? {
        val r = globals.running
        return if (r == null || r.isMainThread) null else r
    }

    fun callDirect(fn: LuaValue, args: Varargs): Varargs? {
        val nested = resumeStart != 0L && System.currentTimeMillis() - resumeStart < 10_000
        if (!nested) resumeStart = System.currentTimeMillis()
        return try {
            fn.invoke(args)
        } catch (e: LuaError) {
            reportError(e.message ?: e.toString()); null
        } catch (e: StackOverflowError) {
            reportError("stack overflow"); null
        } catch (e: RuntimeException) {
            reportError(e.toString()); null
        } finally {
            if (!nested) resumeStart = 0L
        }
    }

    fun newThread(fn: LuaValue): LuaThread {
        val co = LuaThread(globals, fn)
        setHook(co)
        return co
    }

    fun spawn(fn: LuaValue, args: Varargs): LuaThread {
        val co = newThread(fn)
        resume(co, args)
        return co
    }

    fun resume(co: LuaThread, args: Varargs) {
        if (stopped || co.status != "suspended" || cancelled.contains(co)) return
        val outer = resumeStart
        resumeStart = System.currentTimeMillis()
        try {
            val r = co.resume(args)
            if (!r.arg1().toboolean()) {
                val msg = r.arg(2)
                reportError(if (msg.isnil()) "error" else msg.tojstring())
            }
        } catch (e: Throwable) {
            reportError(e.toString())
        } finally {
            resumeStart = outer
        }
    }

    fun reportError(msg: String) {
        if (stopped) return
        OutputLog.error(cleanError(msg))
    }

    /** LuaJ keeps the "=" chunk-name prefix in messages; strip it so errors read "Workspace.Script:3: ...". */
    fun cleanError(m: String) = m.replace(Regex("(^|\\s)=(?=[^=:\\n]+:\\d+)"), "$1")

    fun yieldFor(seconds: Double): Varargs {
        val co = currentCoroutine() ?: throw LuaError("attempt to yield across a non-yieldable boundary (use task.spawn)")
        val start = time
        waiting.add(Waiting(co, time + seconds.coerceAtLeast(0.0), start))
        return globals.yield(LuaValue.NONE)
    }

    fun delay(seconds: Double, co: LuaThread) {
        waiting.add(Waiting(co, time + seconds.coerceAtLeast(0.0), time))
    }

    fun defer(co: LuaThread, args: Varargs) { deferred.add(co to args) }

    private val timers = ArrayList<Pair<Double, () -> Unit>>()
    fun after(seconds: Double, fn: () -> Unit) { timers.add((time + seconds.coerceAtLeast(0.0)) to fn) }

    private val cancelled: MutableSet<LuaThread> = java.util.Collections.newSetFromMap(java.util.WeakHashMap<LuaThread, Boolean>())

    fun cancel(co: LuaThread) {
        cancelled.add(co)
        waiting.removeAll { it.co === co }
        deferred.removeAll { it.first === co }
    }

    fun waitForChild(parent: Instance, name: String, timeout: Double?): Varargs {
        parent.findFirstChild(name)?.let { return wrap(it) }
        val co = currentCoroutine() ?: throw LuaError("WaitForChild must be called from a yieldable thread")
        childWaits.add(ChildWait(parent, name, co, if (timeout == null) Double.MAX_VALUE else time + timeout, time))
        return globals.yield(LuaValue.NONE)
    }

    private fun checkChildWaits() {
        val done = childWaits.filter { it.parent.findFirstChild(it.name) != null }
        if (done.isEmpty()) return
        childWaits.removeAll(done)
        for (w in done) deferred.add(w.co to wrap(w.parent.findFirstChild(w.name)))
    }

    // ------------------------------------------------------------ frame

    fun step(dt: Double) {
        if (stopped) return
        time += dt
        while (true) { val job = mainQueue.poll() ?: break; job() }

        if (waiting.isNotEmpty()) {
            val due = waiting.filter { it.at <= time }
            if (due.isNotEmpty()) {
                // Like Roblox's wait() throttling: resume the longest-waiting threads first and
                // stop after a time budget; the rest run next frame (they're still due, so they
                // go first then). Games with hundreds of `while wait() do` loops stay playable.
                val sorted = if (due.size > 1) due.sortedBy { it.at } else due
                val ran = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Waiting, Boolean>())
                val t0 = System.nanoTime()
                for (w in sorted) {
                    if (ran.isNotEmpty() && System.nanoTime() - t0 > WAIT_BUDGET_NS) break
                    ran.add(w)
                    resume(w.co, LuaValue.varargsOf(LuaValue.valueOf(time - w.start), LuaValue.valueOf(time)))
                }
                waiting.removeAll(ran)
            }
        }
        if (timers.isNotEmpty()) {
            val due = timers.filter { it.first <= time }
            if (due.isNotEmpty()) {
                timers.removeAll(due.toSet())
                for ((_, f) in due) try { f() } catch (e: LuaError) { reportError(e.message ?: "error") }
            }
        }
        if (deferred.isNotEmpty()) {
            val d = deferred.toList(); deferred.clear()
            for ((co, a) in d) resume(co, a)
        }
        if (childWaits.isNotEmpty()) {
            val it = childWaits.iterator()
            val timedOut = ArrayList<ChildWait>()
            while (it.hasNext()) {
                val w = it.next()
                if (!w.warned && time - w.start > 5.0 && w.deadline == Double.MAX_VALUE) {
                    w.warned = true
                    OutputLog.warn("Infinite yield possible on '${w.parent.fullName()}:WaitForChild(\"${w.name}\")'")
                }
                if (time >= w.deadline) { it.remove(); timedOut.add(w) }
            }
            timedOut.forEach { resume(it.co, LuaValue.NIL) }
        }
        stepTweens(dt.toFloat())
    }

    private fun tweenCompleted(tw: Tween, state: Int, name: String) {
        fire(tw, "Completed", LuaTypes.wrap(EnumItemV("PlaybackState", name, state)))
    }

    private fun stepTweenOne(tw: Tween, dt: Float): Boolean {
        when (tw.state) {
            1 -> {
                tw.delayElapsed += dt
                if (tw.delayElapsed >= tw.info.delay) { tw.state = 2; tw.elapsed = 0f }
                return true
            }
            2 -> {}
            else -> return false
        }
        if (!tw.started) {
            tw.start.clear()
            for (k in tw.goals.keys) try { tw.start[k] = api.getK(tw.inst, k) } catch (_: Exception) {}
            tw.started = true
        }
        tw.elapsed += dt
        val time = tw.info.time
        val period = time * (if (tw.info.reverses) 2f else 1f)
        val repEnd = tw.elapsed >= period || time <= 0f
        val k = if (repEnd) (if (tw.info.reverses) 0f else 1f) else {
            val a = if (tw.elapsed < time) tw.elapsed / time else (period - tw.elapsed) / time
            LuaTypes.ease(tw.info.style, tw.info.direction, a)
        }
        for ((key, goal) in tw.goals.entries.toList()) {
            if (!tw.start.containsKey(key)) continue
            val sv = tw.start[key]
            val v = if (repEnd && sv !is Int && sv !is Long) (if (k == 1f) goal else sv) else LuaTypes.lerpValue(sv, goal, k)
            try { api.setK(tw.inst, key, v) } catch (_: Exception) {}
        }
        if (!repEnd) return true
        if (tw.state != 2) return tw.state == 1
        tw.repeatsDone++
        if (tw.info.repeatCount >= 0 && tw.repeatsDone > tw.info.repeatCount) {
            tw.state = 4
            tweenCompleted(tw, 4, "Completed")
            return false
        }
        tw.elapsed = 0f
        if (tw.info.delay > 0f) { tw.state = 1; tw.delayElapsed = 0f }
        return true
    }

    private fun stepTweens(dt: Float) {
        if (tweens.isEmpty() && tweensToAdd.isEmpty()) return
        val adding = tweensToAdd.toList()
        tweensToAdd.clear()
        for (n in adding) {
            val cancelled = ArrayList<Tween>()
            for (o in tweens) {
                if (o === n || o.inst !== n.inst || (o.state != 1 && o.state != 2)) continue
                val before = o.goals.size
                for (key in o.goals.keys.filter { it in n.goals }) { o.goals.remove(key); o.start.remove(key) }
                if (o.goals.isEmpty() && before > 0) { o.state = 5; cancelled.add(o) }
            }
            tweens.add(n)
            for (o in cancelled) tweenCompleted(o, 5, "Cancelled")
        }
        var i = tweens.size
        while (i > 0) {
            i--
            val tw = tweens.getOrNull(i) ?: continue
            if (!stepTweenOne(tw, dt)) {
                val idx = if (i < tweens.size && tweens[i] === tw) i else tweens.indexOf(tw)
                if (idx >= 0) {
                    tweens[idx] = tweens[tweens.size - 1]
                    tweens.removeAt(tweens.size - 1)
                }
            }
        }
    }

    fun playTween(tw: Tween) {
        when (tw.state) {
            1, 2 -> return
            3 -> tw.state = 2
            else -> {
                tw.elapsed = 0f; tw.delayElapsed = 0f; tw.repeatsDone = 0; tw.started = false
                tw.state = if (tw.info.delay > 0f) 1 else 2
            }
        }
        if (tw !in tweens && tw !in tweensToAdd) tweensToAdd.add(tw)
    }

    fun pauseTween(tw: Tween) { if (tw.state == 2) tw.state = 3 }

    fun cancelTween(tw: Tween) {
        if (tw.state == 1 || tw.state == 2 || tw.state == 3) {
            tw.state = 5
            tw.elapsed = 0f
            tweenCompleted(tw, 5, "Cancelled")
        }
    }

    // ------------------------------------------------------------ scripts

    fun compile(source: String, chunkName: String, env: LuaTable): LuaValue {
        // Compiled code is shared between runtimes: plugins are reloaded every time a place
        // opens, and big ones (F3X, Archimedes: 100+ modules) took seconds to transpile and
        // compile again each time. A Prototype is immutable bytecode, safe to reuse.
        val key = "$chunkName\u0000${source.length}:${source.hashCode()}"
        val cached = synchronized(protoCache) { protoCache[key] }?.takeIf { it.first == source }?.second
        val proto = cached ?: (fromDisk(chunkName, source) ?: compileLuau(source, chunkName)
            .also { toDisk(chunkName, source, it) })
            .also { p -> synchronized(protoCache) { protoCache[key] = source to p } }
        return globals.loader.load(proto, "=$chunkName", env)
    }

    /**
     * Luau source -> bytecode. LuaJ allows only 60 upvalues per function (Luau: 200) and crashes
     * while reporting it; such modules (React-Lua's reconciler) are compiled again with their
     * top-level locals kept in the module's environment instead.
     */
    private fun compileLuau(source: String, chunkName: String): org.luaj.vm2.Prototype = try {
        globals.compilePrototype(LuauTranspiler.transpile(source).byteInputStream(Charsets.UTF_8), "=$chunkName")
    } catch (e: Exception) {
        val limit = e is NullPointerException || (e is LuaError && (e.message?.contains("upvalue") == true || e.message?.contains("local variables") == true))
        if (!limit) throw e
        globals.compilePrototype(LuauTranspiler.transpile(source, hoistTopLocals = true).byteInputStream(Charsets.UTF_8), "=$chunkName")
    }

    /** Bytecode cache on disk, so the first plugin load after the app starts is quick too. */
    private fun diskFile(chunkName: String, source: String): java.io.File? = try {
        val dir = luacDir ?: synchronized(protoCache) {
            // One folder per install; folders from older installs are removed.
            val base = studio.app.cacheDir
            val name = "luac-" + buildStamp(studio.app)
            base.listFiles()?.filter { it.isDirectory && it.name.startsWith("luac") && it.name != name }?.forEach { it.deleteRecursively() }
            java.io.File(base, name).also { it.mkdirs(); luacDir = it }
        }
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(chunkName.toByteArray()); md.update(0); md.update(source.toByteArray())
        java.io.File(dir, md.digest().joinToString("") { "%02x".format(it) } + ".luac")
    } catch (_: Exception) { null }

    private fun fromDisk(chunkName: String, source: String): org.luaj.vm2.Prototype? {
        if (source.length < 2000) return null // small scripts compile faster than a file read
        val f = diskFile(chunkName, source)?.takeIf { it.isFile } ?: return null
        return try { f.inputStream().buffered().use { org.luaj.vm2.LoadState.undump(it, "=$chunkName") } } catch (_: Exception) { f.delete(); null }
    }

    private fun toDisk(chunkName: String, source: String, p: org.luaj.vm2.Prototype) {
        if (source.length < 2000) return
        val f = diskFile(chunkName, source) ?: return
        try {
            val tmp = java.io.File(f.path + ".tmp")
            tmp.outputStream().buffered().use { org.luaj.vm2.compiler.DumpState.dump(p, it, false) }
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    companion object {
        /** Script time per frame for resuming wait()ing threads before the rest wait a frame. */
        private const val WAIT_BUDGET_NS = 12_000_000L

        /** Compiled scripts by name + source (least recently used dropped past the limit). */
        private val protoCache = object : LinkedHashMap<String, Pair<String, org.luaj.vm2.Prototype>>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, org.luaj.vm2.Prototype>>?) = size > 3000
        }

        @Volatile private var stamp: String? = null
        @Volatile private var luacDir: java.io.File? = null
        /** Changes with every install of the app, so bytecode from an older transpiler is never reused. */
        private fun buildStamp(ctx: android.content.Context): String = stamp ?: try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        } catch (_: Exception) { "0" }.also { stamp = it }
    }

    fun envFor(script: Instance?, extra: Map<String, LuaValue> = emptyMap()): LuaTable {
        // A Globals (not a plain table) so LuaJ keeps call-stack info for the script's code:
        // getfenv(2), setfenv and loadstring need it, and so does the script timeout hook.
        val env = Globals()
        env.debuglib = globals.debuglib
        val mt = LuaTable()
        mt.set(LuaValue.INDEX, globals)
        env.setmetatable(mt)
        env.set("script", wrap(script))
        for ((k, v) in extra) env.set(k, v)
        return env
    }

    /** Start a Script/LocalScript in its own thread. */
    fun runScript(script: Instance, extra: Map<String, LuaValue> = emptyMap()) {
        val src = script["Source"] as? String ?: return
        val fn = try {
            compile(src, script.fullName(), envFor(script, extra))
        } catch (e: LuaError) {
            OutputLog.error(cleanError(e.message ?: "Syntax error in ${script.fullName()}"))
            return
        }
        spawn(fn, LuaValue.NONE)
    }

    /** Globals set in the command bar stay around for the next command (like Studio's). */
    private val commandEnv: LuaTable by lazy { envFor(null) }

    /** Run code from the command bar. */
    fun runCommand(code: String) {
        val fn = try {
            compile(code, "CommandBar", commandEnv)
        } catch (e: LuaError) {
            OutputLog.error(cleanError(e.message ?: "Syntax error")); return
        }
        spawn(fn, LuaValue.NONE)
    }

    fun require(module: Instance): LuaValue {
        moduleCache[module]?.let { return it }
        if (!module.isA("ModuleScript")) throw LuaError("Attempted to call require with invalid argument(s).")
        var top: Instance? = module
        while (top?.parent != null) top = top.parent
        val extra = if (top?.className == "Plugin") extraEnvForModules + ("plugin" to wrap(top)) else extraEnvForModules
        val env = envFor(module, extra)
        val fn = compile(module["Source"] as? String ?: "", module.fullName(), env)
        // Roblox runs a module on its own thread, so getfenv(0) inside it (even in a tail call such
        // as F3X's `return getfenv(0)`) is the module's environment. Remember it per coroutine.
        val co = globals.running
        val stack = moduleEnvs.getOrPut(co) { ArrayList() }
        stack.add(env)
        val r = try { fn.call() } finally {
            stack.removeAt(stack.size - 1)
            if (stack.isEmpty()) moduleEnvs.remove(co)
        }
        if (r.isnil()) throw LuaError("Module code did not return exactly one value")
        moduleCache[module] = r
        return r
    }

    var extraEnvForModules: Map<String, LuaValue> = emptyMap()

    /** Environments of the modules each coroutine is currently loading (innermost last). */
    private val moduleEnvs = IdentityHashMap<LuaThread, ArrayList<LuaTable>>()
    fun loadingModuleEnv(): LuaTable? = moduleEnvs[globals.running]?.lastOrNull()

    fun onAncestryChanged(i: Instance) {
        if (hasConn(i, "AncestryChanged")) fire(i, "AncestryChanged", wrap(i), wrap(i.parent))
    }

    private fun notifyAncestry(i: Instance) {
        if (conns.isEmpty()) return
        onAncestryChanged(i)
        for (d in i.descendants()) if (conns.containsKey(d)) onAncestryChanged(d)
    }
}
