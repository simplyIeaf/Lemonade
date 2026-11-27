package com.leaf.lemonade.lua

import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.model.*
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import kotlin.math.floor

object RobloxGlobals {
    /**
     * Luau's require-by-string for Roblox instances: "./X" is a sibling of the calling script
     * (its Parent's child), each "../" goes up one more level, "@self/X" is the script's own
     * child, and the remaining "/"-separated names are children.
     */
    fun resolveRequirePath(caller: Instance, path: String): Instance {
        var cur: Instance? = caller
        val parts = path.split('/').filter { it.isNotEmpty() }.toMutableList()
        if (parts.isEmpty()) throw LuaError("require path \"$path\" is empty")
        when (parts[0]) {
            "@self" -> parts.removeAt(0)
            "." -> { cur = caller.parent; parts.removeAt(0) }
            ".." -> {
                cur = caller.parent?.parent; parts.removeAt(0)
                while (parts.firstOrNull() == "..") { cur = cur?.parent; parts.removeAt(0) }
            }
            else -> throw LuaError("require path \"$path\" must start with ./, ../ or @self (aliases aren't supported)")
        }
        for (name in parts) {
            if (name == ".") continue
            if (name == "..") { cur = cur?.parent; continue }
            val clean = name.removeSuffix(".luau").removeSuffix(".lua")
            cur = cur?.findFirstChild(clean) ?: throw LuaError("require(\"$path\"): $clean is not a child of ${cur?.fullName() ?: "nil"}")
        }
        return cur ?: throw LuaError("require(\"$path\") went above the game")
    }

    fun install(rt: LuaRuntime) {
        val g = rt.globals
        val start = System.nanoTime()

        fun joinArgs(a: Varargs): String {
            val sb = StringBuilder()
            val tostring = g.get("tostring")
            for (i in 1..a.narg()) { if (i > 1) sb.append(' '); sb.append(tostring.call(a.arg(i)).tojstring()) }
            return sb.toString()
        }

        // Luau's bit32 extras (React/jsdotlua packages need countlz for Math.clz32).
        g.get("bit32").takeIf { it.istable() }?.let { b ->
            b.set("countlz", fnv { a -> LuaValue.valueOf(Integer.numberOfLeadingZeros(a.arg(1).checklong().toInt())) })
            b.set("countrz", fnv { a -> LuaValue.valueOf(Integer.numberOfTrailingZeros(a.arg(1).checklong().toInt())) })
            b.set("byteswap", fnv { a -> LuaValue.valueOf((Integer.reverseBytes(a.arg(1).checklong().toInt()).toLong() and 0xFFFFFFFFL).toDouble()) })
        }

        g.set("print", fnv { a -> OutputLog.print(joinArgs(a)); LuaValue.NONE })
        g.set("warn", fnv { a -> OutputLog.warn(joinArgs(a)); LuaValue.NONE })
        // Legacy wait() never waits less than ~1/30 s in Roblox (wait(0) included).
        g.set("wait", fnv { a -> rt.yieldFor(a.arg(1).optdouble(0.03).coerceAtLeast(0.029)) })
        g.set("Wait", g.get("wait"))
        g.set("delay", fnv { a -> val co = rt.newThread(a.arg(2).checkfunction()); rt.delay(a.arg(1).optdouble(0.0), co); LuaValue.NONE })
        g.set("spawn", fnv { a -> val co = rt.newThread(a.arg(1).checkfunction()); rt.delay(0.0, co); LuaValue.NONE })
        g.set("Spawn", fnv { a -> g.get("spawn").invoke(a) })
        g.set("Delay", fnv { a -> g.get("delay").invoke(a) })
        g.set("tick", fnv {
            val now = System.currentTimeMillis()
            LuaValue.valueOf(now / 1000.0 + java.util.TimeZone.getDefault().getOffset(now) / 1000.0)
        })
        g.set("time", fnv { LuaValue.valueOf(rt.time) })
        val elapsed = fnv { LuaValue.valueOf((android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000.0) }
        g.set("elapsedTime", elapsed)
        g.set("ElapsedTime", fnv { elapsed.invoke(LuaValue.NONE) })
        g.set("typeof", fn1 { v -> LuaValue.valueOf(LuaTypes.typeOf(v)) })
        g.set("game", rt.wrap(rt.dm))
        g.set("Game", rt.wrap(rt.dm))
        g.set("workspace", rt.wrap(rt.dm.workspace))
        g.set("Workspace", rt.wrap(rt.dm.workspace))
        g.set("shared", rt.sharedTable)
        // Helper for Luau generalized iteration (see LuauTranspiler).
        g.set("__iter", fnv { a ->
            val x = a.arg1()
            when {
                x.isfunction() -> a
                x.istable() -> {
                    val mt = x.getmetatable()
                    val it = mt?.get("__iter")
                    if (it != null && it.isfunction()) it.invoke(x)
                    else LuaValue.varargsOf(arrayOf(g.get("next"), x, LuaValue.NIL))
                }
                x.isuserdata() && x.getmetatable()?.get(LuaValue.CALL)?.isfunction() == true -> a
                else -> throw LuaError("attempt to iterate over a ${LuaTypes.typeOf(x)} value")
            }
        })
        g.set("unpack", g.get("table").get("unpack"))
        g.set("ypcall", g.get("pcall"))

        // Lua 5.1 functions Roblox still has (LuaJ implements Lua 5.2, which dropped them).
        fun envSlot(f: LuaValue): org.luaj.vm2.UpValue? {
            val c = f as? org.luaj.vm2.LuaClosure ?: return null
            val ups = c.p.upvalues ?: return null
            for (i in ups.indices) if (ups[i].name?.tojstring() == "_ENV") return c.upValues.getOrNull(i)
            return null
        }
        fun funcAt(level: Int): LuaValue = try { rt.debugGetinfo.call(LuaValue.valueOf(level), LuaValue.valueOf("f")).get("func") } catch (_: Exception) { LuaValue.NIL }
        g.set("require", fn1 { m ->
            val inst = m.udOrNull<Instance>() ?: when {
                m.isnumber() -> throw LuaError("require(assetId) is not supported offline")
                // Luau require-by-string: "./Sibling", "../Up/Module", "@self/Child".
                m.isstring() -> {
                    val caller = ((envSlot(funcAt(1))?.value as? LuaTable)?.get("script"))?.udOrNull<Instance>()
                        ?: throw LuaError("require(\"${m.tojstring()}\") needs to be called from a script")
                    resolveRequirePath(caller, m.tojstring())
                }
                else -> throw LuaError("Attempted to call require with invalid argument(s).")
            }
            rt.require(inst)
        })
        g.set("getfenv", fnv { a ->
            val x = a.arg1()
            val f = when {
                x.isfunction() -> x
                x.isnil() -> funcAt(1)
                // Level 0 is the thread's global environment: in Roblox that's the environment of
                // the module being loaded, or of the script (or callback) the thread started from.
                // Building Tools by F3X builds its Core table from getfenv(0).
                x.isnumber() -> if (x.toint() == 0) {
                    rt.loadingModuleEnv()?.let { return@fnv it }
                    var bottom: LuaValue? = null
                    for (lvl in 1..250) { val f = funcAt(lvl); if (f.isnil()) break; envSlot(f)?.value?.let { bottom = it } }
                    return@fnv bottom ?: g
                } else funcAt(x.toint())
                else -> throw LuaError("bad argument #1 to 'getfenv' (number expected)")
            }
            envSlot(f)?.value ?: g
        })
        g.set("setfenv", fnv { a ->
            val x = a.arg1(); val t = a.arg(2).checktable()
            val f = if (x.isfunction()) x else funcAt(if (x.isnil()) 1 else x.checkint())
            envSlot(f)?.setValue(t) ?: throw LuaError("'setfenv' cannot change environment of given object")
            f
        })
        g.set("newproxy", fnv { a ->
            val x = a.arg1()
            val mt: LuaValue? = when {
                x.isuserdata() -> x.getmetatable()
                x.toboolean() -> LuaTable()
                else -> null
            }
            org.luaj.vm2.LuaUserdata(Any(), mt)
        })
        g.set("loadstring", fnv { a ->
            // Source code only, like Roblox (precompiled bytecode is refused; see LuaSandbox).
            if (LuaSandbox.isBytecode(a.arg1())) return@fnv LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("bytecode is not allowed"))
            val src = a.arg1().checkjstring()
            val name = a.arg(2).optjstring("loadstring")
            try {
                val env = (envSlot(funcAt(1))?.value as? LuaTable) ?: g
                rt.globals.load(LuauTranspiler.transpile(src), "=$name", env)
            } catch (e: LuaError) {
                LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(rt.cleanError(e.message ?: "syntax error")))
            }
        })
        val settingsRoot = StudioSettings.create(rt)
        g.set("settings", fnv { settingsRoot })
        val userSettings = InstanceFactory.create("UserSettings").also { us ->
            InstanceFactory.create("UserGameSettings").also { gs ->
                gs.name = "GameSettings"
                gs.set("ControlMode", EnumValue(0), false)
                gs.set("ComputerMovementMode", EnumValue(0), false)
                gs.set("TouchMovementMode", EnumValue(0), false)
                gs.set("MasterVolume", 1f, false)
                gs.set("SavedQualityLevel", EnumValue(0), false)
                gs.set("RotationType", EnumValue(0), false)
                gs.attachRaw(us)
            }
        }
        g.set("UserSettings", fnv { rt.wrap(userSettings) })
        g.set("version", fnv { LuaValue.valueOf("0.744.0.7441077") })
        g.set("Version", fnv { LuaValue.valueOf("0.744.0.7441077") })
        g.set("_VERSION", LuaValue.valueOf("Luau"))
        g.set("collectgarbage", fnv { a ->
            if (a.arg(1).type() != LuaValue.TSTRING) throw LuaError("bad argument #1 to 'collectgarbage' (string expected, got ${if (a.arg(1).isnil()) "no value" else LuaTypes.typeOf(a.arg(1))})")
            if (a.arg(1).tojstring() != "count") throw LuaError("collectgarbage must be called with 'count'; use gcinfo() instead")
            LuaValue.valueOf(Math.floor((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0))
        })
        g.set("printidentity", fnv { OutputLog.print("Current identity is 2"); LuaValue.NONE })
        g.set("gcinfo", fnv { LuaValue.valueOf(Math.floor((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0)) })

        // task library
        val task = LuaTable()
        task.set("wait", fnv { a -> rt.yieldFor(a.arg(1).optdouble(0.0)).arg1().let { LuaValue.varargsOf(it, LuaValue.NONE) } })
        task.set("spawn", fnv { a ->
            val f = a.arg(1)
            val co = if (f.isthread()) f as LuaThread else rt.newThread(f.checkfunction())
            rt.resume(co, a.subargs(2))
            co
        })
        task.set("defer", fnv { a ->
            val f = a.arg(1)
            val co = if (f.isthread()) f as LuaThread else rt.newThread(f.checkfunction())
            rt.defer(co, a.subargs(2))
            co
        })
        task.set("delay", fnv { a ->
            val f = a.arg(2)
            val co = if (f.isthread()) f as LuaThread else rt.newThread(f.checkfunction())
            val args = a.subargs(3)
            rt.after(a.arg(1).optdouble(0.0)) { rt.resume(co, args) }
            co
        })
        task.set("cancel", fnv { a ->
            val th = a.arg(1) as? LuaThread
            if (th != null) {
                if (th === rt.currentCoroutine() || th.status == "running" || th.status == "normal") throw LuaError("cannot cancel thread")
                rt.cancel(th)
            }
            LuaValue.NONE
        })
        task.set("synchronize", fnv { LuaValue.NONE })
        task.set("desynchronize", fnv { LuaValue.NONE })
        g.set("task", task)

        // Instance
        val instLib = LuaTable()
        instLib.set("new", fnv { a ->
            val cls = a.arg(1).checkjstring()
            if (ClassDb.isServiceName(cls) || cls in setOf("DataModel", "Player", "PlayerGui")) throw LuaError("Unable to create an Instance of type \"$cls\"")
            val i = InstanceFactory.create(cls)
            a.arg(2).udOrNull<Instance>()?.let { i.setParent(it) }
            rt.wrap(i)
        })
        instLib.set("fromExisting", fn1 { v -> rt.wrap(v.ud<Instance>("Instance").cloneTree().also { it.children.toList().forEach { c -> c.destroy() } }) })
        g.set("Instance", instLib)

        // Params objects as tables
        g.set("RaycastParams", LuaTable().apply {
            set("new", fnv { _ ->
                LuaTable().apply {
                    set("FilterDescendantsInstances", LuaTable())
                    set("FilterType", LuaTypes.wrap(EnumItemV("RaycastFilterType", "Exclude", 0)))
                    set("IgnoreWater", LuaValue.FALSE)
                    set("CollisionGroup", "Default")
                    set("RespectCanCollide", LuaValue.FALSE)
                    set("AddToFilter", fnv { a ->
                        val list = a.arg(1).get("FilterDescendantsInstances")
                        val v = a.arg(2)
                        if (v.istable()) for (i in 1..v.length()) list.set(list.length() + 1, v.get(i)) else list.set(list.length() + 1, v)
                        LuaValue.NONE
                    })
                }
            })
        })
        g.set("OverlapParams", LuaTable().apply {
            set("new", fnv { _ ->
                LuaTable().apply {
                    set("FilterDescendantsInstances", LuaTable())
                    set("FilterType", LuaTypes.wrap(EnumItemV("RaycastFilterType", "Exclude", 0)))
                    set("MaxParts", 0)
                    set("CollisionGroup", "Default")
                    set("RespectCanCollide", LuaValue.FALSE)
                }
            })
        })
        g.set("DockWidgetPluginGuiInfo", LuaTable().apply {
            set("new", fnv { a ->
                LuaTable().apply {
                    set("InitialDockState", a.arg(1)); set("InitialEnabled", LuaValue.valueOf(a.arg(2).optboolean(false)))
                    set("FloatingXSize", LuaValue.valueOf(a.arg(4).optint(300))); set("FloatingYSize", LuaValue.valueOf(a.arg(5).optint(300)))
                    set("MinWidth", LuaValue.valueOf(a.arg(6).optint(150))); set("MinHeight", LuaValue.valueOf(a.arg(7).optint(150)))
                }
            })
        })

        // Luau standard library additions
        val math = g.get("math")
        math.set("clamp", fnv { a -> val x = a.arg(1).checkdouble(); val mn = a.arg(2).checkdouble(); val mx = a.arg(3).checkdouble()
            if (mn > mx) throw LuaError("max must be greater than or equal to min"); LuaValue.valueOf(x.coerceIn(mn, mx)) })
        math.set("sign", fn1 { a -> val x = a.checkdouble(); LuaValue.valueOf(if (x > 0) 1 else if (x < 0) -1 else 0) })
        math.set("round", fn1 { a -> val x = a.checkdouble(); LuaValue.valueOf(if (x >= 0) floor(x + 0.5) else -floor(-x + 0.5)) })
        math.set("noise", fnv { a -> LuaValue.valueOf(Noise.perlin(a.arg(1).optdouble(0.0), a.arg(2).optdouble(0.0), a.arg(3).optdouble(0.0))) })
        math.set("lerp", fnv { a -> val x = a.arg(1).checkdouble(); LuaValue.valueOf(x + (a.arg(2).checkdouble() - x) * a.arg(3).checkdouble()) })
        math.set("map", fnv { a ->
            val x = a.arg(1).checkdouble(); val i0 = a.arg(2).checkdouble(); val i1 = a.arg(3).checkdouble(); val o0 = a.arg(4).checkdouble(); val o1 = a.arg(5).checkdouble()
            LuaValue.valueOf(o0 + (x - i0) * (o1 - o0) / (i1 - i0))
        })

        val string = g.get("string")
        string.set("split", fnv { a ->
            val s = a.arg(1).checkjstring(); val sep = a.arg(2).optjstring(",")
            val t = LuaTable()
            val parts = if (sep.isEmpty()) s.map { it.toString() } else s.split(sep)
            parts.forEach { t.insert(0, LuaValue.valueOf(it)) }
            t
        })

        val table = g.get("table")
        table.set("find", fnv { a ->
            val t = a.arg(1).checktable(); val v = a.arg(2); val init = a.arg(3).optint(1)
            var r: LuaValue = LuaValue.NIL
            for (i in init..t.length()) if (t.get(i).eq_b(v)) { r = LuaValue.valueOf(i); break }
            r
        })
        table.set("clear", fn1 { a ->
            val t = a.checktable()
            val keys = ArrayList<LuaValue>()
            var k = LuaValue.NIL
            while (true) { val n = t.next(k); k = n.arg1(); if (k.isnil()) break; keys.add(k) }
            keys.forEach { t.set(it, LuaValue.NIL) }
            LuaValue.NONE
        })
        table.set("create", fnv { a -> val t = LuaTable(); val n = a.arg(1).checkint(); val v = a.arg(2); if (!v.isnil()) for (i in 1..n) t.set(i, v); t })
        table.set("freeze", fn1 { it })
        table.set("isfrozen", fn1 { LuaValue.FALSE })
        table.set("clone", fn1 { a ->
            val src = a.checktable(); val t = LuaTable()
            var k = LuaValue.NIL
            while (true) { val n = src.next(k); k = n.arg1(); if (k.isnil()) break; t.set(k, n.arg(2)) }
            t
        })
        table.set("move", fnv { a ->
            val src = a.arg(1).checktable(); val f = a.arg(2).checkint(); val e = a.arg(3).checkint(); val tpos = a.arg(4).checkint()
            val dst = if (a.arg(5).istable()) a.arg(5) as LuaTable else src
            if (tpos > f) for (i in e downTo f) dst.set(tpos + i - f, src.get(i)) else for (i in f..e) dst.set(tpos + i - f, src.get(i))
            dst
        })
        table.set("getn", fn1 { a -> LuaValue.valueOf(a.length()) })

        val utf8 = LuaTable()
        utf8.set("char", fnv { a -> val sb = StringBuilder(); for (i in 1..a.narg()) sb.appendCodePoint(a.arg(i).checkint()); LuaValue.valueOf(sb.toString()) })
        utf8.set("len", fn1 { a -> val s = String(a.checkstring().m_bytes, a.checkstring().m_offset, a.checkstring().m_length, Charsets.UTF_8); LuaValue.valueOf(s.codePointCount(0, s.length)) })
        utf8.set("charpattern", "[\u0000-\u007FÂ-ý][\u0080-¿]*")
        fun extendsGrapheme(c: Int) = c in 0x0300..0x036F || c in 0x0483..0x0489 || c in 0x0591..0x05BD || c in 0x0610..0x061A || c in 0x064B..0x065F ||
            c == 0x0E31 || c in 0x0E34..0x0E3A || c in 0x0E47..0x0E4E || c in 0x1AB0..0x1AFF || c in 0x1DC0..0x1DFF ||
            c == 0x200C || c == 0x200D || c in 0x20D0..0x20FF || c in 0xFE00..0xFE0F || c in 0xFE20..0xFE2F ||
            c in 0x1F3FB..0x1F3FF || c in 0xE0020..0xE007F || c in 0xE0100..0xE01EF
        utf8.set("graphemes", fnv { a ->
            val str = a.arg(1).checkstring()
            val bytes = str.m_bytes.copyOfRange(str.m_offset, str.m_offset + str.m_length)
            val len = bytes.size.toLong()
            fun norm(v: Long) = if (v < 0) maxOf(len + v + 1, 1L) else v
            val start = maxOf(norm(a.arg(2).optlong(1L)), 1L).toInt()
            val end = minOf(norm(a.arg(3).optlong(len)), len).toInt()
            val from = minOf(start - 1, bytes.size)
            val to = maxOf(end, start - 1).coerceAtMost(bytes.size)
            val text = String(bytes, from, maxOf(to - from, 0), Charsets.UTF_8)
            val spans = ArrayList<IntArray>()
            var off = 0
            var prev = -1
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                val a0 = start + off
                val b0 = a0 + n - 1
                if (spans.isNotEmpty() && (extendsGrapheme(cp) || prev == 0x200D)) spans.last()[1] = b0 else spans.add(intArrayOf(a0, b0))
                prev = cp
                off += n
                i += Character.charCount(cp)
            }
            var idx = 0
            fnv { _ ->
                if (idx < spans.size) { val sp = spans[idx++]; LuaValue.varargsOf(LuaValue.valueOf(sp[0]), LuaValue.valueOf(sp[1])) } else LuaValue.NIL
            }
        })
        utf8.set("nfcnormalize", fn1 { v -> v.checkstring() })
        utf8.set("nfdnormalize", fn1 { v -> v.checkstring() })
        g.set("utf8", utf8)
    }
}

object PluginApi {
    fun call(rt: LuaRuntime, inst: Instance, name: String, a: Varargs): Varargs {
        val studio = rt.studio
        val a2 = a.arg(2)
        return when (name) {
            "CreateToolbar" -> {
                val tb = InstanceFactory.create("PluginToolbar")
                tb.name = a2.optjstring("Plugin")
                tb.attributes["__plugin"] = inst.name
                rt.wrap(tb)
            }
            "CreateButton" -> {
                val b = InstanceFactory.create("PluginToolbarButton")
                b.name = a2.optjstring("Button")
                val text = if (a.narg() >= 5 && a.arg(5).isstring()) a.arg(5).tojstring() else a2.optjstring("Button")
                b.set("Text", text, false)
                b.set("Tooltip", a.arg(3).optjstring(""), false)
                b.set("Icon", a.arg(4).optjstring(""), false)
                b.set("Enabled", true, false)
                b.set("ClickableWhenViewportHidden", false, false)
                // Buttons belong to the plugin that made the toolbar (not to the toolbar's name).
                studio.addPluginButton(b, inst.attributes["__plugin"] as? String ?: inst.name, rt)
                rt.wrap(b)
            }
            "SetActive" -> { inst.set("Active", a2.toboolean()); studio.notifyPluginButtons(); LuaValue.NONE }
            "GetSetting" -> studio.pluginSetting(pluginId(inst), a2.checkjstring())?.let { Json.decode(rt, it) } ?: LuaValue.NIL
            "SetSetting" -> { studio.setPluginSetting(pluginId(inst), a2.checkjstring(), Json.encode(a.arg(3))); LuaValue.NONE }
            "GetGridSize" -> LuaValue.valueOf(studio.moveSnap.toDouble())
            "IsLoaded" -> LuaValue.TRUE
            "CreateDockWidgetPluginGui", "CreateDockWidgetPluginGuiAsync", "CreateQWidgetPluginGui" -> {
                val w = InstanceFactory.create("DockWidgetPluginGui")
                w.name = a2.optjstring("Widget")
                val info = a.arg(3)
                val enabled = info.istable() && info.get("InitialEnabled").toboolean()
                w.set("Enabled", enabled, false)
                w.set("Title", w.name, false)
                w.set("ZIndexBehavior", EnumValue(1), false)
                if (info.istable()) {
                    w.attributes["__w"] = info.get("FloatingXSize").optint(300)
                    w.attributes["__h"] = info.get("FloatingYSize").optint(300)
                }
                studio.addPluginWidget(w, rt)
                rt.wrap(w)
            }
            "OpenScript" -> { a2.udOrNull<Instance>()?.let { s -> studio.main.post { studio.requestOpenScript(s) } }; LuaValue.NONE }
            "GetMouse" -> studio.pluginMice.getOrPut(inst) { pluginMouse(rt) }
            "Activate" -> { studio.activatePlugin(inst, rt, a2.toboolean()); LuaValue.NONE }
            "Deactivate" -> { studio.deactivatePlugin(inst); LuaValue.NONE }
            "IsActivated" -> LuaValue.valueOf(studio.activePlugin === inst)
            "IsActivatedWithExclusiveMouse" -> LuaValue.valueOf(studio.activePlugin === inst && studio.activePluginExclusive)
            "GetStudioUserId" -> LuaValue.valueOf(com.leaf.lemonade.net.RobloxApi.userId(studio.app).toDouble())
            "CreatePluginAction" -> action(rt, a2.optjstring("Action"), a.arg(3).optjstring(""), a.arg(4).optjstring(""))
            "CreatePluginMenu" -> menu(rt, studio, a2.optjstring("Menu"), a.arg(3).optjstring(""))
            "Union", "Negate", "Separate", "Intersect" -> {
                val list = (1..a2.length()).mapNotNull { a2.get(it).udOrNull<Instance>() }
                val saved = studio.selection.toList()
                studio.selection.clear(); studio.selection.addAll(list)
                val err = when (name) {
                    "Negate" -> { com.leaf.lemonade.core.SolidModeling.negate(studio); null }
                    "Separate" -> com.leaf.lemonade.core.SolidModeling.separate(studio)
                    else -> com.leaf.lemonade.core.SolidModeling.combine(studio, name == "Intersect")
                }
                val result = studio.selection.toList()
                studio.selection.clear(); studio.selection.addAll(saved)
                if (err != null) throw LuaError(err)
                if (name == "Union" || name == "Intersect") rt.wrap(result.firstOrNull()) else LuaTable().also { t -> result.forEach { t.insert(0, rt.wrap(it)) } }
            }
            "SelectRibbonTool" -> LuaValue.NONE
            "GetSelectedRibbonTool" -> LuaTypes.wrap(EnumItemV("RibbonTool", "Select", 1))
            "PromptImportFile", "PromptImportFiles" -> LuaValue.NIL
            "StartDrag", "OpenWikiPage", "SaveSelectedToRoblox", "SetReady" -> LuaValue.NONE
            "GetJoinMode" -> LuaTypes.wrap(EnumItemV("JointCreationMode", "All", 0))
            "BindToClose" -> { rt.callbacks.getOrPut(inst) { HashMap() }["__close"] = a2.checkfunction(); LuaValue.NONE }
            "GetRelativeMousePosition" -> LuaTypes.wrap(Vector2(0f, 0f))
            else -> LuaValue.NONE
        }
    }

    private fun pluginId(i: Instance) = i.name

    /** A PluginMouse: the viewport sends it Button1Down/Up and Move while its plugin is active. */
    fun pluginMouse(rt: LuaRuntime): LuaTable {
        val m = LuaTable()
        m.set("Hit", LuaTypes.wrap(CFrame.IDENTITY)); m.set("Target", LuaValue.NIL)
        m.set("X", 0); m.set("Y", 0); m.set("Icon", ""); m.set("TargetFilter", LuaValue.NIL)
        m.set("Origin", LuaTypes.wrap(CFrame.IDENTITY)); m.set("UnitRay", LuaTypes.wrap(Ray(Vector3.ZERO, Vector3(0f, 0f, -1f))))
        m.set("ViewSizeX", 0); m.set("ViewSizeY", 0); m.set("ClassName", "PluginMouse"); m.set("Name", "Instance")
        val mt = LuaTable()
        mt.set("__type", "Instance")
        mt.set(LuaValue.INDEX, fn2 { _, k ->
            when (val ks = k.tojstring()) {
                "Button1Down", "Button1Up", "Button2Down", "Button2Up", "Move", "Idle", "WheelForward", "WheelBackward", "KeyDown", "KeyUp", "DragEnter", "Changed" -> rt.api.signal(m, ks)
                "IsA" -> fnv { a -> LuaValue.valueOf(a.arg(2).tojstring() in setOf("Mouse", "PluginMouse", "Instance")) }
                "GetPropertyChangedSignal" -> fnv { a -> rt.api.signal(m, "Changed:" + a.arg(2).tojstring()) }
                else -> LuaValue.NIL
            }
        })
        m.setmetatable(mt)
        return m
    }

    private fun action(rt: LuaRuntime, id: String, text: String, tip: String): LuaTable {
        val t = LuaTable()
        t.set("ActionId", id); t.set("Text", text); t.set("StatusTip", tip); t.set("Enabled", LuaValue.TRUE); t.set("Checked", LuaValue.FALSE)
        t.set("Name", text.ifEmpty { id })
        val mt = LuaTable()
        mt.set("__type", "Instance")
        mt.set(LuaValue.INDEX, fn2 { _, k -> if (k.tojstring() == "Triggered") rt.api.signal(t, "Triggered") else LuaValue.NIL })
        t.setmetatable(mt)
        return t
    }

    /** PluginMenu: ShowAsync pops up a real menu and yields until the user picks something. */
    private fun menu(rt: LuaRuntime, studio: com.leaf.lemonade.core.Studio, id: String, title: String): LuaTable {
        val t = LuaTable()
        val items = ArrayList<LuaValue>() // actions, sub-menus, or NIL for separators
        t.set("Title", title); t.set("Name", id)
        t.set("AddAction", fnv { a -> items.add(a.arg(2)); LuaValue.NONE })
        t.set("AddNewAction", fnv { a -> val act = action(rt, a.arg(2).optjstring(""), a.arg(3).optjstring(""), ""); items.add(act); act })
        t.set("AddSeparator", fnv { items.add(LuaValue.NIL); LuaValue.NONE })
        t.set("AddMenu", fnv { a -> items.add(a.arg(2)); LuaValue.NONE })
        t.set("Clear", fnv { items.clear(); LuaValue.NONE })
        t.set("Destroy", fnv { items.clear(); LuaValue.NONE })
        t.set("ShowAsync", fnv { _ ->
            val co = rt.currentCoroutine() ?: return@fnv LuaValue.NIL
            val acts = items.filter { it.istable() && !it.get("ActionId").isnil() }
            val labels = acts.map { it.get("Text").optjstring("").ifEmpty { it.get("ActionId").tojstring() } }
            studio.main.post {
                val h = studio.pluginMenuHandler
                if (h == null) synchronized(studio.lock) { rt.resume(co, LuaValue.NIL) }
                else h(title, labels) { idx ->
                    synchronized(studio.lock) {
                        val chosen = if (idx in acts.indices) acts[idx] else LuaValue.NIL
                        if (!chosen.isnil()) rt.fire(chosen, "Triggered")
                        rt.resume(co, chosen)
                    }
                }
            }
            rt.globals.yield(LuaValue.NONE)
        })
        val mt = LuaTable(); mt.set("__type", "Instance"); t.setmetatable(mt)
        return t
    }
}

/**
 * settings(): Studio's settings tree as plugins read it. settings().Studio.Theme:GetColor(...) is
 * how most plugins color their UI, so it answers with Studio's dark theme palette.
 */
object StudioSettings {
    private fun rgb(r: Int, g: Int, b: Int) = Color3.fromRGB(r, g, b)

    private val palette: Map<String, Color3> = mapOf(
        "MainBackground" to rgb(46, 46, 46), "Titlebar" to rgb(53, 53, 53), "TitlebarText" to rgb(170, 170, 170),
        "Dropdown" to rgb(46, 46, 46), "Tooltip" to rgb(66, 66, 66), "Notification" to rgb(66, 66, 66),
        "ScrollBar" to rgb(56, 56, 56), "ScrollBarBackground" to rgb(41, 41, 41), "TabBar" to rgb(46, 46, 46),
        "Tab" to rgb(53, 53, 53), "FilterButtonDefault" to rgb(46, 46, 46), "FilterButtonHover" to rgb(66, 66, 66),
        "FilterButtonChecked" to rgb(30, 30, 30), "FilterButtonAccent" to rgb(53, 181, 255), "FilterButtonBorder" to rgb(26, 26, 26),
        "RibbonTab" to rgb(53, 53, 53), "RibbonTabTopBar" to rgb(53, 181, 255), "RibbonButton" to rgb(37, 37, 37),
        "Button" to rgb(60, 60, 60), "ButtonBorder" to rgb(34, 34, 34), "ButtonText" to rgb(204, 204, 204),
        "MainButton" to rgb(0, 162, 255), "DialogButton" to rgb(60, 60, 60), "DialogButtonText" to rgb(204, 204, 204),
        "DialogButtonBorder" to rgb(34, 34, 34), "DialogMainButton" to rgb(0, 162, 255), "DialogMainButtonText" to rgb(255, 255, 255),
        "InputFieldBackground" to rgb(37, 37, 37), "InputFieldBorder" to rgb(26, 26, 26), "CheckedFieldBackground" to rgb(37, 37, 37),
        "CheckedFieldBorder" to rgb(34, 34, 34), "CheckedFieldIndicator" to rgb(53, 181, 255), "HeaderSection" to rgb(53, 53, 53),
        "Midlight" to rgb(34, 34, 34), "Mid" to rgb(34, 34, 34), "Dark" to rgb(34, 34, 34), "Light" to rgb(66, 66, 66),
        "Shadow" to rgb(26, 26, 26), "Item" to rgb(46, 46, 46), "TableItem" to rgb(46, 46, 46), "Separator" to rgb(34, 34, 34),
        "Border" to rgb(26, 26, 26), "MainText" to rgb(204, 204, 204), "SubText" to rgb(170, 170, 170), "DimmedText" to rgb(102, 102, 102),
        "BrightText" to rgb(229, 229, 229), "InfoText" to rgb(170, 170, 170), "LinkText" to rgb(53, 181, 255),
        "WarningText" to rgb(255, 142, 60), "ErrorText" to rgb(255, 68, 68), "SensitiveText" to rgb(177, 138, 255),
        "ScriptBackground" to rgb(37, 37, 37), "ScriptText" to rgb(204, 204, 204), "ScriptSelectionBackground" to rgb(11, 90, 175),
        "StatusBar" to rgb(46, 46, 46), "CurrentMarker" to rgb(66, 66, 66), "ColorPickerFrame" to rgb(34, 34, 34),
        "GameSettingsTableItem" to rgb(53, 53, 53), "GameSettingsTooltip" to rgb(66, 66, 66), "EmulatorBar" to rgb(41, 41, 41),
        "EmulatorDropDown" to rgb(37, 37, 37), "ViewPortBackground" to rgb(37, 37, 37), "AttributeCog" to rgb(170, 170, 170),
    )

    private fun shade(c: Color3, k: Float) = Color3((c.r * k).coerceIn(0f, 1f), (c.g * k).coerceIn(0f, 1f), (c.b * k).coerceIn(0f, 1f))

    fun color(styleName: String, modifier: String): Color3 {
        val base = palette[styleName] ?: when {
            styleName.contains("Text") -> rgb(204, 204, 204)
            styleName.contains("Border") || styleName.contains("Separator") -> rgb(26, 26, 26)
            else -> rgb(46, 46, 46)
        }
        val text = styleName.contains("Text")
        return when (modifier) {
            "Selected" -> if (text) rgb(255, 255, 255) else if (styleName in setOf("Item", "TableItem", "Button", "RibbonButton")) rgb(11, 90, 175) else shade(base, 1.25f)
            "Hover" -> if (text) base else shade(base, 1.18f)
            "Pressed" -> if (text) base else shade(base, 0.85f)
            "Disabled" -> if (text) rgb(85, 85, 85) else shade(base, 0.9f)
            else -> base
        }
    }

    private fun enumName(v: LuaValue): String = (if (v.isuserdata()) v.touserdata() as? EnumItemV else null)?.name ?: if (v.isstring()) v.tojstring() else ""

    fun create(rt: LuaRuntime): LuaTable {
        val theme = LuaTable()
        theme.set("Name", "Dark")
        theme.set("GetColor", fnv { a -> LuaTypes.wrap(color(enumName(a.arg(2)), enumName(a.arg(3)).ifEmpty { "Default" })) })
        theme.setmetatable(LuaTable().apply { set("__type", "Instance") })
        val studio = LuaTable()
        studio.set("Theme", theme)
        studio.set("ThemeChanged", rt.api.signal(studio, "ThemeChanged"))
        studio.set("GetPropertyChangedSignal", fnv { a -> rt.api.signal(studio, "Changed:" + a.arg(2).tojstring()) })
        studio.set("Grid Size", 1)
        studio.set("Script Editor Color Preset", LuaTypes.wrap(EnumItemV("StudioScriptEditorColorPresets", "RobloxDefault", 0)))
        val root = LuaTable()
        root.set("Studio", studio)
        root.set("GetFFlag", fnv { LuaValue.FALSE })
        root.set("GetFVariable", fnv { LuaValue.valueOf("") })
        root.set("GetFInt", fnv { LuaValue.valueOf(0) })
        root.set("Rendering", LuaTable().apply { set("QualityLevel", LuaTypes.wrap(EnumItemV("QualityLevel", "Automatic", 0))) })
        // Any other settings section reads as an empty table rather than nil.
        val lazy = LuaTable()
        lazy.set(LuaValue.INDEX, fn2 { t, k -> LuaTable().also { (t as LuaTable).rawset(k, it) } })
        root.setmetatable(lazy)
        return root
    }
}

object Noise {
    private val p = IntArray(512).also { arr ->
        val perm = (0 until 256).shuffled(java.util.Random(1337))
        for (i in 0 until 512) arr[i] = perm[i and 255]
    }
    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)
    private fun grad(h: Int, x: Double, y: Double, z: Double): Double {
        val hh = h and 15
        val u = if (hh < 8) x else y
        val v = if (hh < 4) y else if (hh == 12 || hh == 14) x else z
        return (if (hh and 1 == 0) u else -u) + (if (hh and 2 == 0) v else -v)
    }

    fun perlin(x0: Double, y0: Double, z0: Double): Double {
        val X = floor(x0).toInt() and 255; val Y = floor(y0).toInt() and 255; val Z = floor(z0).toInt() and 255
        val x = x0 - floor(x0); val y = y0 - floor(y0); val z = z0 - floor(z0)
        val u = fade(x); val v = fade(y); val w = fade(z)
        val A = p[X] + Y; val AA = p[A] + Z; val AB = p[A + 1] + Z
        val B = p[X + 1] + Y; val BA = p[B] + Z; val BB = p[B + 1] + Z
        return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)),
            lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
            lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))))
    }
}
