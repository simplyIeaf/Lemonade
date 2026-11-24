package com.leaf.lemonade.engine

import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.EnumValue
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.net.AssetFetcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (x, y, z, w) helpers for blending joint rotations. */
object Quat {
    fun of(cf: CFrame): FloatArray {
        val m00 = cf.r00; val m01 = cf.r01; val m02 = cf.r02
        val m10 = cf.r10; val m11 = cf.r11; val m12 = cf.r12
        val m20 = cf.r20; val m21 = cf.r21; val m22 = cf.r22
        val tr = m00 + m11 + m22
        val q = FloatArray(4)
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            q[3] = 0.25f * s; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s; q[0] = 0.25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = 0.25f * s; q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = 0.25f * s
        }
        return norm(q)
    }

    private fun norm(q: FloatArray): FloatArray {
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).coerceAtLeast(1e-9f)
        for (i in 0..3) q[i] /= l
        return q
    }

    fun slerp(a: FloatArray, b0: FloatArray, t: Float): FloatArray {
        var b = b0
        var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        if (d < 0f) { b = floatArrayOf(-b[0], -b[1], -b[2], -b[3]); d = -d }
        if (d > 0.9995f) return norm(FloatArray(4) { a[it] + (b[it] - a[it]) * t })
        val th = acos(d.coerceIn(-1f, 1f)); val s = sin(th)
        val wa = sin((1f - t) * th) / s; val wb = sin(t * th) / s
        return FloatArray(4) { a[it] * wa + b[it] * wb }
    }

    fun toCFrame(q: FloatArray, x: Float, y: Float, z: Float): CFrame {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        return CFrame(x, y, z,
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy))
    }

    /** Rotation slerp + position lerp between two CFrames. */
    fun lerp(a: CFrame, b: CFrame, t: Float): CFrame {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val q = slerp(of(a), of(b), t)
        return toCFrame(q, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    }
}

/**
 * A KeyframeSequence turned into per-joint key lists. Pose names are the Part1 names of the
 * Motor6Ds they drive; a Pose's CFrame is that motor's Transform.
 */
class AnimClip(val length: Float, val looped: Boolean, val priority: Int, private val joints: Map<String, Array<Key>>) {
    class Key(val t: Float, val cf: CFrame, val style: Int, val dir: Int)

    val jointNames: Set<String> get() = joints.keys

    /** The joint's Transform at time [t] (clamped to the clip), or null if the clip doesn't animate it. */
    fun sample(joint: String, t: Float): CFrame? {
        val keys = joints[joint] ?: return null
        if (keys.size == 1 || t <= keys[0].t) return keys[0].cf
        val last = keys[keys.size - 1]
        if (t >= last.t) return last.cf
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].t <= t) i++
        val a = keys[i]; val b = keys[i + 1]
        val span = b.t - a.t
        if (span <= 1e-5f) return b.cf
        return Quat.lerp(a.cf, b.cf, ease((t - a.t) / span, a.style, a.dir))
    }

    companion object {
        /**
         * Roblox's PoseEasingStyle (Linear 0, Constant 1, Elastic 2, Cubic 3, Bounce 4, CubicV2 5)
         * and PoseEasingDirection (In 0, Out 1, InOut 2). The legacy styles have In and Out
         * swapped compared to TweenService (CubicV2 fixed that), which is kept here.
         */
        fun ease(x: Float, style: Int, dir0: Int): Float {
            if (style == 0) return x
            if (style == 1) return 0f
            val dir = if (style != 5 && dir0 != 2) 1 - dir0 else dir0
            fun inF(t: Float): Float = when (style) {
                2 -> if (t <= 0f || t >= 1f) t else -(2f.pow(10f * (t - 1f))) * sin(((t - 1.075f) * (2f * Math.PI.toFloat())) / 0.3f)
                4 -> 1f - bounceOut(1f - t)
                else -> t * t * t
            }
            return when (dir) {
                0 -> inF(x)
                1 -> 1f - inF(1f - x)
                else -> if (x < 0.5f) inF(x * 2f) / 2f else 1f - inF((1f - x) * 2f) / 2f
            }
        }

        private fun bounceOut(t: Float): Float = when {
            t < 1f / 2.75f -> 7.5625f * t * t
            t < 2f / 2.75f -> { val u = t - 1.5f / 2.75f; 7.5625f * u * u + 0.75f }
            t < 2.5f / 2.75f -> { val u = t - 2.25f / 2.75f; 7.5625f * u * u + 0.9375f }
            else -> { val u = t - 2.625f / 2.75f; 7.5625f * u * u + 0.984375f }
        }

        fun from(seq: Instance): AnimClip? {
            if (seq.className != "KeyframeSequence") return null
            val lists = HashMap<String, ArrayList<Key>>()
            var length = 0f
            for (kf in seq.children) {
                if (kf.className != "Keyframe") continue
                val t = (kf["Time"] as? Number)?.toFloat() ?: 0f
                if (t > length) length = t
                fun walk(p: Instance) {
                    for (c in p.children) {
                        if (c.className != "Pose") continue
                        val w = (c["Weight"] as? Number)?.toFloat() ?: 1f
                        if (w > 0f) {
                            val cf = c["CFrame"] as? CFrame ?: CFrame.IDENTITY
                            val st = (c["EasingStyle"] as? EnumValue)?.value ?: 0
                            val dr = (c["EasingDirection"] as? EnumValue)?.value ?: 0
                            lists.getOrPut(c.name) { ArrayList() }.add(Key(t, cf, st, dr))
                        }
                        walk(c)
                    }
                }
                walk(kf)
            }
            if (lists.isEmpty()) return null
            val joints = lists.mapValues { (_, l) -> l.sortedBy { it.t }.toTypedArray() }
            return AnimClip(length, seq["Loop"] != false, (seq["Priority"] as? EnumValue)?.value ?: 0, joints)
        }
    }
}

/**
 * CurveAnimation (what the Animation Editor publishes with curves on, and what many newer
 * animations are): a Folder per joint (named like Pose objects, nested the same way) holding a
 * "Position" Vector3Curve and a "Rotation" EulerRotationCurve, each made of FloatCurves X/Y/Z.
 * FloatCurve.ValuesAndTimes layout (read from real assets in Roblox's cache):
 * u32 version(2), u32 n, n x {u8 interpolation (0 Constant, 1 Linear, 2 Cubic), u8 flags,
 * f32 value, f32 leftTangent, f32 rightTangent}, u32 1, u32 n, n x i32 time in ticks (2400/s).
 */
object CurveAnim {
    const val TICKS_PER_SECOND = 2400f

    class FKey(val t: Float, val v: Float, val interp: Int, val lt: Float, val rt: Float)

    fun decode(b: ByteArray): List<FKey> {
        if (b.size < 16) return emptyList()
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.int // version
        val n = bb.int
        if (n <= 0 || n > 100_000) return emptyList()
        // Bytes per key: 14 in version 2; derived from the total size for anything else.
        val per = ((b.size - 16 - 4 * n) / n).takeIf { it in 5..32 } ?: return emptyList()
        val vals = FloatArray(n); val modes = IntArray(n); val lts = FloatArray(n); val rts = FloatArray(n)
        for (i in 0 until n) {
            val start = bb.position()
            modes[i] = bb.get().toInt() and 0xFF
            if (per >= 6) bb.get()
            vals[i] = bb.float
            if (per >= 14) { lts[i] = bb.float; rts[i] = bb.float }
            bb.position(start + per)
        }
        bb.int // times version
        val m = bb.int
        if (m != n || bb.remaining() < 4 * n) return emptyList()
        return List(n) { i -> FKey(bb.int / TICKS_PER_SECOND, vals[i], modes[i], lts[i], rts[i]) }
    }

    /** Value of a key list at [t] (held before the first and after the last key). */
    fun eval(keys: List<FKey>, t: Float): Float {
        if (keys.isEmpty()) return 0f
        if (t <= keys[0].t) return keys[0].v
        val last = keys[keys.size - 1]
        if (t >= last.t) return last.v
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].t <= t) i++
        val a = keys[i]; val b = keys[i + 1]
        val span = b.t - a.t
        if (span <= 1e-6f) return b.v
        val u = (t - a.t) / span
        return when (a.interp) {
            0 -> a.v
            2 -> { // Cubic Hermite with the keys' tangents (value per second).
                val u2 = u * u; val u3 = u2 * u
                (2 * u3 - 3 * u2 + 1) * a.v + (u3 - 2 * u2 + u) * span * a.rt + (-2 * u3 + 3 * u2) * b.v + (u3 - u2) * span * b.lt
            }
            else -> a.v + (b.v - a.v) * u
        }
    }

    private fun bytesOf(v: Any?): ByteArray? = when (v) {
        is com.leaf.lemonade.model.BinaryString -> v.bytes
        // The file reader keeps binary strings that happen to be valid UTF-8 as text.
        is String -> v.toByteArray(Charsets.UTF_8)
        else -> null
    }

    private fun channel(curve: Instance?, axis: String): List<FKey> =
        curve?.findFirstChild(axis)?.takeIf { it.className == "FloatCurve" }?.let { decode(bytesOf(it["ValuesAndTimes"]) ?: return emptyList()) } ?: emptyList()

    private fun rot(axis: Int, a: Float) = when (axis) { 0 -> CFrame.rx(a); 1 -> CFrame.ry(a); else -> CFrame.rz(a) }

    /** RotationOrder XYZ 0, XZY 1, YZX 2, YXZ 3, ZXY 4, ZYX 5: CFrame = R(first) * R(second) * R(third). */
    private val ORDERS = arrayOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 2, 0), intArrayOf(1, 0, 2), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))

    fun from(anim: Instance): AnimClip? {
        if (anim.className != "CurveAnimation") return null
        val joints = HashMap<String, Array<AnimClip.Key>>()
        var length = 0f
        fun walk(folder: Instance) {
            for (f in folder.children) {
                if (f.className != "Folder") continue
                val pos = f.children.firstOrNull { it.className == "Vector3Curve" }
                val rotC = f.children.firstOrNull { it.className == "EulerRotationCurve" }
                val px = channel(pos, "X"); val py = channel(pos, "Y"); val pz = channel(pos, "Z")
                val rx = channel(rotC, "X"); val ry = channel(rotC, "Y"); val rz = channel(rotC, "Z")
                val chans = listOf(px, py, pz, rx, ry, rz)
                if (chans.any { it.isNotEmpty() }) {
                    val order = ORDERS[((rotC?.get("RotationOrder") as? EnumValue)?.value ?: 0).coerceIn(0, 5)]
                    // Sample at every key time, plus 30 Hz so cubic curves keep their shape.
                    val times = java.util.TreeSet<Float>()
                    chans.forEach { c -> c.forEach { times.add(it.t) } }
                    val end = times.last()
                    var s = times.first()
                    while (s < end && times.size < 4000) { times.add(s); s += 1f / 30f }
                    if (end > length) length = end
                    joints[f.name] = times.map { t ->
                        val a = floatArrayOf(eval(rx, t), eval(ry, t), eval(rz, t))
                        val r = rot(order[0], a[order[0]]) * rot(order[1], a[order[1]]) * rot(order[2], a[order[2]])
                        AnimClip.Key(t, r.withPosition(com.leaf.lemonade.model.Vector3(eval(px, t), eval(py, t), eval(pz, t))), 0, 0)
                    }.toTypedArray()
                }
                walk(f)
            }
        }
        walk(anim)
        if (joints.isEmpty()) return null
        return AnimClip(length, anim["Loop"] != false, (anim["Priority"] as? EnumValue)?.value ?: 0, joints)
    }
}

/**
 * Downloads and caches animation assets by id: KeyframeSequences, CurveAnimations, and
 * animation packages/emotes (a model holding Animation objects that point at the real asset).
 * Roblox needs a sign-in for asset downloads; without one (and nothing cached) a clip simply
 * never arrives and callers keep their fallback. Failed downloads are retried after a while
 * (e.g. once the user has signed in).
 */
object AnimLibrary {
    private val clips = ConcurrentHashMap<Long, AnimClip>()
    private val failed = ConcurrentHashMap<Long, Long>()
    private val errors = ConcurrentHashMap<Long, String>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val pool = Executors.newFixedThreadPool(2)
    private const val RETRY_MS = 20_000L

    fun get(id: Long): AnimClip? {
        clips[id]?.let { return it }
        val f = failed[id]
        if (f != null && System.currentTimeMillis() - f < RETRY_MS) return null
        if (!pending.add(id)) return null
        pool.execute {
            try {
                val clip = load(id, 0)
                if (clip != null) { clips[id] = clip; failed.remove(id); errors.remove(id) }
                else { failed[id] = System.currentTimeMillis(); errors[id] = "the asset has no KeyframeSequence or CurveAnimation" }
            } catch (e: Exception) {
                failed[id] = System.currentTimeMillis()
                errors[id] = AssetFetcher.lastError(id) ?: (e.message ?: e.toString())
            } finally { pending.remove(id) }
        }
        return null
    }

    private fun load(id: Long, depth: Int): AnimClip? {
        val bytes = AssetFetcher.load(id)
        val doc = RbxFile.read(bytes)
        val all = doc.roots.flatMap { listOf(it) + it.descendants() }
        all.firstOrNull { it.className == "KeyframeSequence" }?.let { s -> AnimClip.from(s)?.let { return it } }
        all.firstOrNull { it.className == "CurveAnimation" }?.let { c -> CurveAnim.from(c)?.let { return it } }
        // An animation package or emote: follow the first Animation's id.
        if (depth < 2) for (a in all) if (a.className == "Animation") {
            val inner = AssetFetcher.idOf(a["AnimationId"]) ?: continue
            if (inner != id) load(inner, depth + 1)?.let { return it }
        }
        return null
    }

    fun failed(id: Long) = failed.containsKey(id)
    fun lastError(id: Long): String? = errors[id]

    /** A clip from a KeyframeSequence/CurveAnimation that's already in the place (e.g. under an Animation object). */
    fun fromInstance(seq: Instance): AnimClip? = AnimClip.from(seq) ?: CurveAnim.from(seq)
}

/** Plays and blends animation tracks on one rig (fades, priorities, speeds, looping). */
class AnimPlayer {
    companion object {
        /** AnimationPriority enum value -> blend order (Core is the lowest, then Idle, Movement, Action...). */
        fun rank(v: Int) = if (v >= 1000) -1 else v
    }

    class Track(val clip: AnimClip, val tag: String, var priority: Int) {
        var time = 0f
        var speed = 1f
        var weight = 0f
        var targetWeight = 1f
        var fadeRate = 10f
        var stopping = false
        var looped = clip.looped
        var loops = 0
        var ended = false
        val playing get() = !stopping && !ended
    }

    val tracks = ArrayList<Track>()

    fun play(clip: AnimClip, tag: String, fade: Float = 0.1f, weight: Float = 1f, speed: Float = 1f, priority: Int = clip.priority): Track {
        val t = Track(clip, tag, priority)
        t.targetWeight = weight; t.speed = speed
        t.fadeRate = if (fade <= 0.001f) 1000f else 1f / fade
        if (fade <= 0.001f) t.weight = weight
        tracks.add(t)
        return t
    }

    fun stop(t: Track, fade: Float = 0.1f) {
        t.stopping = true; t.targetWeight = 0f
        t.fadeRate = if (fade <= 0.001f) 1000f else 1f / fade
    }

    fun stopTag(tag: String, fade: Float) { for (t in tracks) if (t.tag == tag && !t.stopping) stop(t, fade) }

    fun step(dt: Float) {
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (!t.ended || t.looped) {
                t.time += dt * t.speed
                val len = t.clip.length
                if (len > 0f && t.time >= len) {
                    if (t.looped) { t.time %= len; t.loops++ } else { t.time = len; t.ended = true }
                } else if (t.time < 0f) t.time = if (t.looped && len > 0f) len + (t.time % len) else 0f
            }
            val k = (t.fadeRate * dt).coerceAtMost(1f)
            t.weight += (t.targetWeight - t.weight) * k
            if (abs(t.weight - t.targetWeight) < 0.002f) t.weight = t.targetWeight
            if (t.stopping && t.weight <= 0.001f) it.remove()
        }
    }

    /**
     * Blended Transform for a joint: priorities from low to high, each level blending over the
     * ones below by its total weight (so a full-weight Action overrides Movement and Idle).
     */
    fun transform(joint: String): CFrame? = transformOver(null, joint)

    /** Like [transform], blending over [base] (another animator's result) instead of the rest pose. */
    fun transformOver(base: CFrame?, joint: String): CFrame? {
        if (tracks.isEmpty()) return base
        var result: CFrame? = base
        val prios = tracks.map { it.priority }.distinct().sorted()
        for (p in prios) {
            var avg: CFrame? = null
            var acc = 0f
            for (t in tracks) {
                if (t.priority != p || t.weight <= 0.0005f) continue
                val cf = t.clip.sample(joint, t.time) ?: continue
                acc += t.weight
                avg = if (avg == null) cf else Quat.lerp(avg, cf, t.weight / acc)
            }
            if (avg == null) continue
            result = Quat.lerp(result ?: CFrame.IDENTITY, avg, acc.coerceAtMost(1f))
        }
        return result
    }

    fun clear() = tracks.clear()
}

/**
 * Roblox's default Animate script for the player's character: the same animation assets
 * (idle variations, walk/run blend for R15, jump, fall, climb, sit, tool hold and emotes),
 * fade times and speed scaling. Uses procedural motion until the clips have downloaded.
 */
class DefaultAnimate(private val r15: Boolean, private val config: () -> Instance? = { null }) {
    private val player = AnimPlayer()

    private data class Entry(val id: Long, val weight: Int)

    companion object {
        /** Roblox's Animate children: animation name -> (Animation object name, id, weight) list. */
        private val R15_TREE = linkedMapOf(
            "cheer" to listOf(Triple("CheerAnim", 507770677L, 0)), "climb" to listOf(Triple("ClimbAnim", 507765644L, 0)),
            "dance" to listOf(Triple("Animation1", 507771019L, 10), Triple("Animation2", 507771955L, 10), Triple("Animation3", 507772104L, 10)),
            "dance2" to listOf(Triple("Animation1", 507776043L, 10), Triple("Animation2", 507776720L, 10), Triple("Animation3", 507776879L, 10)),
            "dance3" to listOf(Triple("Animation1", 507777268L, 10), Triple("Animation2", 507777451L, 10), Triple("Animation3", 507777623L, 10)),
            "fall" to listOf(Triple("FallAnim", 507767968L, 0)),
            "idle" to listOf(Triple("Animation1", 507766666L, 9), Triple("Animation2", 507766951L, 1)),
            "jump" to listOf(Triple("JumpAnim", 507765000L, 0)), "laugh" to listOf(Triple("LaughAnim", 507770818L, 0)),
            "point" to listOf(Triple("PointAnim", 507770453L, 0)), "run" to listOf(Triple("RunAnim", 913376220L, 0)),
            "sit" to listOf(Triple("SitAnim", 2506281703L, 0)), "swim" to listOf(Triple("Swim", 913384386L, 0)),
            "swimidle" to listOf(Triple("SwimIdle", 913389285L, 0)), "toollunge" to listOf(Triple("ToolLungeAnim", 522638767L, 0)),
            "toolnone" to listOf(Triple("ToolNoneAnim", 507768375L, 0)), "toolslash" to listOf(Triple("ToolSlashAnim", 522635514L, 0)),
            "walk" to listOf(Triple("WalkAnim", 913402848L, 0)), "wave" to listOf(Triple("WaveAnim", 507770239L, 0)),
        )
        private val R6_TREE = linkedMapOf(
            "cheer" to listOf(Triple("CheerAnim", 129423030L, 0)), "climb" to listOf(Triple("ClimbAnim", 180436334L, 0)),
            "dance" to listOf(Triple("Animation1", 182435998L, 10), Triple("Animation2", 182491037L, 10), Triple("Animation3", 182491065L, 10)),
            "dance2" to listOf(Triple("Animation1", 182436842L, 10), Triple("Animation2", 182491248L, 10), Triple("Animation3", 182491277L, 10)),
            "dance3" to listOf(Triple("Animation1", 182436935L, 10), Triple("Animation2", 182491368L, 10), Triple("Animation3", 182491423L, 10)),
            "fall" to listOf(Triple("FallAnim", 180436148L, 0)),
            "idle" to listOf(Triple("Animation1", 180435571L, 9), Triple("Animation2", 180435792L, 1)),
            "jump" to listOf(Triple("JumpAnim", 125750702L, 0)), "laugh" to listOf(Triple("LaughAnim", 129423131L, 0)),
            "point" to listOf(Triple("PointAnim", 128853357L, 0)), "run" to listOf(Triple("RunAnim", 180426354L, 0)),
            "sit" to listOf(Triple("SitAnim", 178130996L, 0)), "toollunge" to listOf(Triple("ToolLungeAnim", 129967478L, 0)),
            "toolnone" to listOf(Triple("ToolNoneAnim", 182393478L, 0)), "toolslash" to listOf(Triple("ToolSlashAnim", 129967390L, 0)),
            "walk" to listOf(Triple("WalkAnim", 180426354L, 0)), "wave" to listOf(Triple("WaveAnim", 128777973L, 0)),
        )

        /**
         * The character's "Animate" LocalScript as Roblox builds it: a StringValue per animation
         * with Animation children (and Weight values). Games change these AnimationIds (or
         * disable Animate) to customise the character, so the built-in animator reads them live.
         */
        fun buildConfig(r15: Boolean): Instance {
            val s = com.leaf.lemonade.model.InstanceFactory.create("LocalScript")
            s.name = "Animate"
            s.set("Source", "-- The default character animations are built into Lemonade's playtest.\n" +
                "-- Change the AnimationIds below (or set Disabled) like Roblox's Animate script.\n", false)
            s.attributes["__builtinAnimate"] = true
            for ((name, list) in if (r15) R15_TREE else R6_TREE) {
                val sv = com.leaf.lemonade.model.InstanceFactory.create("StringValue"); sv.name = name; sv.attachRaw(s)
                for ((an, id, w) in list) {
                    val a = com.leaf.lemonade.model.InstanceFactory.create("Animation"); a.name = an
                    a.set("AnimationId", "http://www.roblox.com/asset/?id=$id", false); a.attachRaw(sv)
                    if (w > 0) com.leaf.lemonade.model.InstanceFactory.create("NumberValue").also { v -> v.name = "Weight"; v.set("Value", w.toDouble(), false); v.attachRaw(a) }
                }
            }
            if (r15) com.leaf.lemonade.model.InstanceFactory.create("NumberValue").also { it.name = "ScaleDampeningPercent"; it.set("Value", 0.4, false); it.attachRaw(s) }
            com.leaf.lemonade.model.InstanceFactory.create("BindableFunction").also { it.name = "PlayEmote"; it.attachRaw(s) }
            return s
        }
    }

    /** The ids the character's Animate configuration lists for [name] (the defaults if it has none). */
    private fun entries(name: String): List<Entry> {
        val cfg = config()?.findFirstChild(name)
        if (cfg != null) {
            val list = cfg.children.filter { it.className == "Animation" }.mapNotNull { a ->
                val id = AssetFetcher.idOf(a["AnimationId"]) ?: return@mapNotNull null
                val w = (a.findFirstChild("Weight")?.get("Value") as? Number)?.toInt() ?: 1
                Entry(id, w.coerceAtLeast(0))
            }
            if (list.isNotEmpty()) return list
        }
        return sets[name] ?: emptyList()
    }

    private val disabled: Boolean get() = config()?.get("Disabled") == true

    private val sets: Map<String, List<Entry>> = if (r15) mapOf(
        "idle" to listOf(Entry(507766666, 9), Entry(507766951, 1)),
        "walk" to listOf(Entry(913402848, 10)),
        "run" to listOf(Entry(913376220, 10)),
        "jump" to listOf(Entry(507765000, 10)),
        "fall" to listOf(Entry(507767968, 10)),
        "climb" to listOf(Entry(507765644, 10)),
        "sit" to listOf(Entry(2506281703, 10)),
        "swim" to listOf(Entry(913384386, 10)),
        "swimidle" to listOf(Entry(913389285, 10)),
        "toolnone" to listOf(Entry(507768375, 10)),
        "wave" to listOf(Entry(507770239, 10)),
        "point" to listOf(Entry(507770453, 10)),
        "dance" to listOf(Entry(507771019, 10), Entry(507771955, 10), Entry(507772104, 10)),
        "dance2" to listOf(Entry(507776043, 10), Entry(507776720, 10), Entry(507776879, 10)),
        "dance3" to listOf(Entry(507777268, 10), Entry(507777451, 10), Entry(507777623, 10)),
        "laugh" to listOf(Entry(507770818, 10)),
        "cheer" to listOf(Entry(507770677, 10)),
    ) else mapOf(
        "idle" to listOf(Entry(180435571, 9), Entry(180435792, 1)),
        "walk" to listOf(Entry(180426354, 10)),
        "run" to listOf(Entry(180426354, 10)),
        "jump" to listOf(Entry(125750702, 10)),
        "fall" to listOf(Entry(180436148, 10)),
        "climb" to listOf(Entry(180436334, 10)),
        "sit" to listOf(Entry(178130996, 10)),
        "toolnone" to listOf(Entry(182393478, 10)),
        "wave" to listOf(Entry(128777973, 10)),
        "point" to listOf(Entry(128853357, 10)),
        "dance" to listOf(Entry(182435998, 10), Entry(182491037, 10), Entry(182491065, 10)),
        "dance2" to listOf(Entry(182436842, 10), Entry(182491248, 10), Entry(182491277, 10)),
        "dance3" to listOf(Entry(182436935, 10), Entry(182491368, 10), Entry(182491423, 10)),
        "laugh" to listOf(Entry(129423131, 10)),
        "cheer" to listOf(Entry(129423030, 10)),
    )

    private val rnd = java.util.Random()
    private var current = ""
    private var currentTracks = ArrayList<AnimPlayer.Track>()
    private var toolTrack: AnimPlayer.Track? = null
    private var emoteTrack: AnimPlayer.Track? = null
    private var emoteName: String? = null
    private var idleLoops = 0
    private var reported = false

    /** Clips for every core animation are loaded (so procedural motion can stop). */
    val ready: Boolean get() = listOf("idle", "walk", "jump", "fall").all { n -> sets[n]!!.all { AnimLibrary.get(it.id) != null } }

    init { for (l in sets.values) for (e in l) AnimLibrary.get(e.id) }

    /**
     * A weighted pick among the configured animations. A custom id that hasn't downloaded (or
     * can't be) uses Roblox's default for that slot meanwhile, so the character never T-poses.
     */
    private fun pick(name: String): AnimClip? {
        val l = entries(name)
        if (l.isEmpty()) return null
        val total = l.sumOf { it.weight }
        var chosen = l.first()
        if (total > 0) {
            var r = rnd.nextInt(total)
            for (e in l) { r -= e.weight; if (r < 0) { chosen = e; break } }
        }
        AnimLibrary.get(chosen.id)?.let { return it }
        l.firstNotNullOfOrNull { AnimLibrary.get(it.id) }?.let { return it }
        val def = sets[name] ?: return null
        return def.firstNotNullOfOrNull { AnimLibrary.get(it.id) }
    }

    /** Ids configured for the current animation when it started (a change restarts it). */
    private var currentIds: List<Long> = emptyList()

    private fun playCore(name: String, fade: Float) {
        val ids = entries(name).map { it.id } + (if (r15 && name == "walk") entries("run").map { it.id } else emptyList())
        // Restart when the state changes, or a script swapped the ids (or a custom clip just arrived).
        val loadedNow = ids.count { AnimLibrary.get(it) != null }
        if (name == current && ids == currentIds && loadedNow == currentLoaded) return
        currentIds = ids; currentLoaded = loadedNow
        for (t in currentTracks) player.stop(t, fade)
        currentTracks = ArrayList()
        current = name
        if (r15 && (name == "walk")) {
            // R15 walks and runs at once and blends them by speed (setRunSpeed in Animate).
            pick("walk")?.let { currentTracks.add(player.play(it, "walk", fade, priority = 0)) }
            pick("run")?.let { currentTracks.add(player.play(it, "run", fade, weight = 0.0001f, priority = 0)) }
            return
        }
        val clip = pick(name) ?: return
        currentTracks.add(player.play(clip, name, fade, priority = 0).also { if (name == "jump" || name == "fall" || name == "sit") it.looped = name != "jump" })
    }

    /**
     * One frame. [pose] is the character state (Standing, Running, Jumping, FreeFall, Climbing,
     * Seated), [speed] its horizontal (or climbing) speed, [tool] whether a tool is held,
     * [emote] a chat emote ("wave", "dance"...) or null.
     */
    private var currentLoaded = 0

    fun step(dt: Float, pose: String, speed: Float, tool: Boolean, emote: String?, jumpAnimTime: Float) {
        if (!ready) return
        if (!reported) { reported = true; OutputLog.info("Character animations: using the Animate script's ${if (r15) "R15" else "R6"} animations") }
        // Animate.Disabled = true (cutscenes, ragdolls): the built-in animations fade out.
        if (disabled) {
            for (t in currentTracks) player.stop(t, 0.2f)
            currentTracks = ArrayList(); current = ""; currentIds = emptyList()
            toolTrack?.let { player.stop(it, 0.2f) }; toolTrack = null
            emoteTrack?.let { player.stop(it, 0.2f) }; emoteTrack = null; emoteName = null
            player.step(dt)
            return
        }
        when (pose) {
            "Jumping" -> playCore("jump", 0.1f)
            "FreeFall" -> if (jumpAnimTime <= 0f) playCore("fall", if (r15) 0.2f else 0.3f)
            "Seated" -> playCore("sit", 0.5f)
            "Climbing" -> playCore("climb", 0.1f)
            // R15 swims with its own animations; R6's Animate keeps walking/idling in water.
            "Swimming" -> if (r15) playCore(if (speed > 1f) "swim" else "swimidle", 0.4f) else if (speed > 0.75f) playCore("walk", 0.2f) else playCore("idle", 0.2f)
            "Running" -> if (speed > 0.75f) playCore("walk", 0.2f) else playCore("idle", 0.2f)
            else -> playCore("idle", 0.2f)
        }
        // Speeds, like Animate's setAnimationSpeed / setRunSpeed.
        when (current) {
            "walk" -> if (r15) {
                val run = speed / 16f * 1.25f
                val walkT = currentTracks.firstOrNull { it.tag == "walk" }; val runT = currentTracks.firstOrNull { it.tag == "run" }
                val small = 0.0001f
                var ww = small; var rw = small
                when {
                    run <= 0.5f -> ww = 1f
                    run < 1f -> { val f = (run - 0.5f) / 0.5f; ww = 1f - f; rw = f }
                    else -> rw = 1f
                }
                walkT?.let { it.targetWeight = ww; it.speed = run / 0.5f }
                runT?.let { it.targetWeight = rw; it.speed = run / 1f }
            } else currentTracks.forEach { it.speed = speed / 14.5f }
            "climb" -> currentTracks.forEach { it.speed = speed / (if (r15) 5f else 12f) }
            "idle" -> {
                // A new idle variation each time one finishes (the 9:1 weighted pick).
                val t = currentTracks.firstOrNull()
                if (t != null && t.loops > idleLoops) {
                    idleLoops = t.loops
                    val next = pick("idle")
                    if (next != null && next !== t.clip) { player.stop(t, 0.2f); currentTracks = arrayListOf(player.play(next, "idle", 0.2f, priority = 0)); idleLoops = 0 }
                }
            }
        }
        // Holding a tool: the "toolnone" arm pose (Idle priority, over Core).
        if (tool && toolTrack == null) toolTrack = pick("toolnone")?.let { player.play(it, "toolnone", 0.1f, priority = 1) }
        else if (!tool && toolTrack != null) { player.stop(toolTrack!!, 0.1f); toolTrack = null }
        // Emotes (Action priority) until the character moves.
        if (emote != emoteName) {
            emoteTrack?.let { player.stop(it, 0.1f) }
            emoteTrack = emote?.let { e -> pick(e)?.let { player.play(it, e, 0.1f, priority = 2) } }
            emoteName = emote
        }
        player.step(dt)
    }

    /** The animated Transform for the motor whose Part1 is [part1Name] (null: use the rest pose / fallback). */
    fun transform(part1Name: String): CFrame? = if (ready) player.transform(part1Name) ?: CFrame.IDENTITY else null
}
