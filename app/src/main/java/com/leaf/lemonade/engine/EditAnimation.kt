package com.leaf.lemonade.engine

import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.PartData
import com.leaf.lemonade.net.AssetFetcher
import org.luaj.vm2.LuaTable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Animation in edit mode, the way animation plugins (Moon Animator, the Animation Editor,
 * preview tools) use it in Roblox Studio:
 * - Motor6D.Transform exists (it isn't saved to files) and joints hold their parts: setting a
 *   joint's Transform, C0 or C1 from Lua moves its Part1 (and everything attached after it).
 * - Animator:LoadAnimation works in edit mode, and Animator:StepAnimations(dt) advances the
 *   playing tracks and poses the rig (tracks don't advance on their own while editing).
 * - KeyframeSequenceProvider:RegisterKeyframeSequence gives a temporary id an Animation can use.
 * All of this runs on the edit runtime, under the Studio lock.
 */
class EditAnimation {
    /** Motor6D.Transform values set by scripts or StepAnimations. */
    private val transforms = IdentityHashMap<Instance, CFrame>()
    private val dirtyJoints = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
    private val players = IdentityHashMap<Instance, AnimPlayer>()
    private val tracks = ArrayList<EditTrack>()

    class EditTrack(val rig: Instance, val id: Long?, val tempId: String?, private val inline: AnimClip?, val table: LuaTable) {
        var track: AnimPlayer.Track? = null
        var wantPlay = false
        var fade = 0.1f; var weight = 1f; var speed = 1f
        var looped: Boolean? = null
        var priority: Int? = null
        var loopsSeen = 0
        val clip: AnimClip? get() = inline ?: tempId?.let { tempClip(it) } ?: id?.let { AnimLibrary.get(it) }
        val isPlaying get() = wantPlay
        val length get() = clip?.length ?: 0f
        val time get() = track?.time ?: 0f
        val weightNow get() = track?.weight ?: 0f
        val weightTarget get() = if (wantPlay) weight else 0f
    }

    fun transformOf(joint: Instance): CFrame = transforms[joint] ?: CFrame.IDENTITY

    fun setTransform(joint: Instance, cf: CFrame) {
        transforms[joint] = cf
        dirtyJoints.add(joint)
    }

    /** A script changed a joint's C0/C1 (or Part0/Part1): re-seat Part1 at the end of the frame. */
    fun jointChanged(joint: Instance) { dirtyJoints.add(joint) }

    fun clear() { transforms.clear(); dirtyJoints.clear(); players.clear(); tracks.clear() }

    // ------------------------------------------------------------------ tracks

    private fun rigOf(owner: Instance): Instance? {
        var r: Instance? = owner.parent
        if (owner.className == "Animator") r = r?.parent
        return r?.takeIf { it.isA("Model") || it.isA("BasePart") || it.className == "WorldModel" }
    }

    fun loadTrack(owner: Instance, anim: Instance?, table: LuaTable): EditTrack? {
        val rig = rigOf(owner) ?: return null
        val inline = anim?.descendants()?.firstOrNull { it.className == "KeyframeSequence" || it.className == "CurveAnimation" }?.let { AnimLibrary.fromInstance(it) }
        val raw = anim?.get("AnimationId")?.toString().orEmpty()
        val temp = raw.takeIf { isTempId(it) }
        val id = if (temp == null) AssetFetcher.idOf(raw) else null
        if (inline == null && id == null && temp == null) return null
        if (inline == null && id != null) AnimLibrary.get(id)
        return EditTrack(rig, id, temp, inline, table).also { tracks.add(it) }
    }

    fun play(t: EditTrack, fade: Float, weight: Float, speed: Float) {
        t.track?.let { players[t.rig]?.stop(it, fade) }
        t.track = null
        t.wantPlay = true; t.fade = fade; t.weight = weight; t.speed = speed; t.loopsSeen = 0
        start(t)
    }

    fun stop(t: EditTrack, fade: Float) {
        t.wantPlay = false
        t.track?.let { players[t.rig]?.stop(it, fade) }
        t.track = null
    }

    fun adjust(t: EditTrack, speed: Float? = null, weight: Float? = null, fade: Float? = null, looped: Boolean? = null, priority: Int? = null, time: Float? = null) {
        speed?.let { t.speed = it; t.track?.speed = it }
        weight?.let { w -> t.weight = w; t.track?.let { tr -> tr.fadeTo(w, fade ?: 0.1f) } }
        looped?.let { t.looped = it; t.track?.looped = it }
        priority?.let { t.priority = it; t.track?.priority = AnimPlayer.rank(it) }
        time?.let { tp -> t.track?.let { it.time = tp.coerceIn(0f, it.clip.length) } }
    }

    fun playingTables(owner: Instance): List<LuaTable> {
        val rig = rigOf(owner) ?: return emptyList()
        return tracks.filter { it.rig === rig && it.wantPlay }.map { it.table }
    }

    private fun start(t: EditTrack) {
        val clip = t.clip ?: return
        val p = players.getOrPut(t.rig) { AnimPlayer() }
        val tr = p.play(clip, "edit", t.fade, t.weight, t.speed, AnimPlayer.rank(t.priority ?: clip.priority))
        t.looped?.let { tr.looped = it }
        t.track = tr
    }

    /**
     * Animator:StepAnimations(dt): advances the rig's playing tracks and writes their pose into
     * the rig's Motor6D.Transform (the rig is re-posed at the end of the frame). Returns the
     * (track table, event) pairs to fire.
     */
    fun step(owner: Instance, dt: Float): List<Pair<LuaTable, String>> {
        val rig = rigOf(owner) ?: return emptyList()
        val events = ArrayList<Pair<LuaTable, String>>()
        for (t in tracks) {
            if (t.rig !== rig) continue
            if (t.wantPlay && t.track == null) start(t)
        }
        val p = players[rig] ?: return events
        p.step(dt)
        for (t in tracks) {
            if (t.rig !== rig) continue
            val tr = t.track ?: continue
            if (tr.loops > t.loopsSeen) { t.loopsSeen = tr.loops; events.add(t.table to "DidLoop") }
            if (tr.ended && !tr.looped && t.wantPlay) {
                t.wantPlay = false
                p.stop(tr, 0f)
                t.track = null
                events.add(t.table to "Stopped"); events.add(t.table to "Ended")
            }
        }
        for (m in rig.descendants()) {
            if (!m.isA("Motor")) continue
            val p1 = m["Part1"] as? Instance ?: continue
            val cf = p.transform(p1.name) ?: continue
            setTransform(m, cf)
        }
        return events
    }

    // ------------------------------------------------------------------ posing

    /** Re-seats the parts of every joint changed this frame. Call once per frame after scripts ran. */
    fun flush() {
        if (dirtyJoints.isEmpty()) return
        val pending = ArrayDeque(dirtyJoints.toList())
        dirtyJoints.clear()
        val moved = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        var guard = 0
        // Joints whose Part0 is another changed joint's Part1 go after it (parents before children).
        val ordered = order(pending)
        for (j in ordered) {
            if (guard++ > 4000) break
            seat(j, moved)
        }
    }

    private fun order(js: Collection<Instance>): List<Instance> {
        val set = js.toMutableList()
        val byP1 = IdentityHashMap<Instance, Instance>()
        for (j in set) (j["Part1"] as? Instance)?.let { byP1[it] = j }
        fun depth(j: Instance): Int {
            var d = 0; var p0 = j["Part0"] as? Instance
            val seen = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
            while (p0 != null && d < 64) { val up = byP1[p0] ?: break; if (!seen.add(up)) break; d++; p0 = up["Part0"] as? Instance }
            return d
        }
        return set.sortedBy { depth(it) }
    }

    /** Moves the joint's Part1 to Part0 * C0 * Transform * C1⁻¹, carrying the parts attached after it. */
    private fun seat(j: Instance, moved: MutableSet<Instance>) {
        val p0 = j["Part0"] as? Instance ?: return
        val p1 = j["Part1"] as? Instance ?: return
        if (p0 === p1 || p1.parent == null || p0.parent == null) return
        val c0 = j["C0"] as? CFrame ?: CFrame.IDENTITY
        val c1 = j["C1"] as? CFrame ?: CFrame.IDENTITY
        val tf = if (j.isA("Motor")) transformOf(j) else CFrame.IDENTITY
        // A joint into an anchored part (and from a free one) moves Part0 instead, like Roblox's assembly root.
        if (p1["Anchored"] == true && p0["Anchored"] != true) {
            val want0 = PartData.cframe(p1) * c1 * tf.inverse() * c0.inverse()
            moveAssembly(p0, want0, j, moved)
        } else {
            val want1 = PartData.cframe(p0) * c0 * tf * c1.inverse()
            moveAssembly(p1, want1, j, moved)
        }
    }

    /** Moves [part] to [cf] and everything joined to it on the far side of [via]. */
    private fun moveAssembly(part: Instance, cf: CFrame, via: Instance, moved: MutableSet<Instance>) {
        val old = PartData.cframe(part)
        if (old == cf) return
        val delta = cf * old.inverse()
        val attached = attachedBeyond(part, via)
        for (q in attached) {
            if (!moved.add(q) && q !== part) continue
            PartData.setCFrame(q, if (q === part) cf else delta * PartData.cframe(q))
        }
    }

    /** [start] plus every part reachable from it through joints and WeldConstraints, without crossing [via]. */
    private fun attachedBeyond(start: Instance, via: Instance): List<Instance> {
        val model = topModel(start)
        val links = IdentityHashMap<Instance, ArrayList<Instance>>()
        fun link(a: Instance, b: Instance) { links.getOrPut(a) { ArrayList() }.add(b); links.getOrPut(b) { ArrayList() }.add(a) }
        for (d in model.descendants()) {
            if (d === via) continue
            val a: Instance?; val b: Instance?
            when {
                d.isA("JointInstance") -> { a = d["Part0"] as? Instance; b = d["Part1"] as? Instance }
                d.className == "WeldConstraint" || d.className == "RigidConstraint" -> {
                    a = (d["Part0Internal"] ?: d["Part0"]) as? Instance; b = (d["Part1Internal"] ?: d["Part1"]) as? Instance
                }
                else -> continue
            }
            if (a != null && b != null && a !== b) link(a, b)
        }
        val out = ArrayList<Instance>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        val q = ArrayDeque<Instance>(); q.add(start); seen.add(start)
        // Don't walk back through the other side of [via].
        (via["Part0"] as? Instance)?.takeIf { it !== start }?.let { seen.add(it) }
        (via["Part1"] as? Instance)?.takeIf { it !== start }?.let { seen.add(it) }
        while (q.isNotEmpty() && out.size < 2000) {
            val c = q.removeFirst(); out.add(c)
            for (n in links[c].orEmpty()) if (seen.add(n) && n["Anchored"] != true) q.add(n)
        }
        return out
    }

    private fun topModel(i: Instance): Instance {
        var m: Instance = i
        var p = i.parent
        while (p != null && (p.isA("Model") || p.isA("Accessory") || p.isA("Tool")) && p.className != "Workspace") { m = p; p = p.parent }
        return m
    }

    companion object {
        private val temps = ConcurrentHashMap<String, Instance>()
        private val counter = AtomicInteger(0)

        fun isTempId(s: String) = s.startsWith("rbxtemp://") || s.startsWith("active://")

        /** KeyframeSequenceProvider:RegisterKeyframeSequence / RegisterActiveKeyframeSequence. */
        fun registerTemp(seq: Instance, active: Boolean): String {
            val id = (if (active) "active://" else "rbxtemp://") + counter.incrementAndGet()
            temps[id] = seq
            return id
        }

        /** The clip of a registered temporary id (read fresh, so edits to the sequence show). */
        fun tempClip(id: String): AnimClip? {
            val seq = temps[id] ?: return null
            return try { AnimLibrary.fromInstance(seq) } catch (e: Exception) { OutputLog.warn("Animation $id: ${e.message}"); null }
        }
    }
}
