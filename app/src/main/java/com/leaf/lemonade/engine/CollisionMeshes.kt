package com.leaf.lemonade.engine

import com.leaf.lemonade.core.SolidModeling
import com.leaf.lemonade.model.*
import com.leaf.lemonade.net.AssetFetcher
import com.leaf.lemonade.render.MeshLoader
import com.leaf.lemonade.render.UnionMeshes
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Collision shapes for unions and MeshParts, following their CollisionFidelity like Roblox:
 *  - Box (2): the part's box (what everything used before).
 *  - Hull (1): one convex shape around the geometry (a 26-sided approximation of its hull).
 *  - Default (0) / PreciseConvexDecomposition (3): the actual triangles, so you can walk through
 *    arches, stand in bowls and fall through holes.
 * World-space data is cached per part and rebuilt when the part moves, resizes or its geometry
 * changes. Parts whose geometry hasn't loaded yet use their box.
 */
object CollisionMeshes {
    const val DEFAULT = 0
    const val HULL = 1
    const val BOX = 2
    const val PRECISE = 3

    /** World-space triangles of one part with a coarse grid for lookups, plus its hull shape. */
    class Mesh(val key: Long, val pos: FloatArray, val nrm: FloatArray, val count: Int, val min: Vector3, val max: Vector3) {
        // Spatial grid over the bounds: cells -> triangle indices.
        val cell: Float
        val nx: Int; val ny: Int; val nz: Int
        val cells: Array<IntArray?>
        private val stamp = IntArray(count)
        private var stampGen = 0
        var hull: ConvexShape? = null

        init {
            val ext = max - min
            cell = (maxOf(ext.x, ext.y, ext.z) / 24f).coerceAtLeast(1f)
            nx = (ext.x / cell).toInt() + 1; ny = (ext.y / cell).toInt() + 1; nz = (ext.z / cell).toInt() + 1
            val lists = HashMap<Int, ArrayList<Int>>()
            for (t in 0 until count) {
                val o = t * 9
                val x0 = min(pos[o], min(pos[o + 3], pos[o + 6])); val x1 = max(pos[o], max(pos[o + 3], pos[o + 6]))
                val y0 = min(pos[o + 1], min(pos[o + 4], pos[o + 7])); val y1 = max(pos[o + 1], max(pos[o + 4], pos[o + 7]))
                val z0 = min(pos[o + 2], min(pos[o + 5], pos[o + 8])); val z1 = max(pos[o + 2], max(pos[o + 5], pos[o + 8]))
                for (i in ci(x0, 0)..ci(x1, 0)) for (j in ci(y0, 1)..ci(y1, 1)) for (k in ci(z0, 2)..ci(z1, 2)) lists.getOrPut(idx(i, j, k)) { ArrayList() }.add(t)
            }
            cells = arrayOfNulls(nx * ny * nz)
            for ((k, l) in lists) cells[k] = l.toIntArray()
        }

        private fun ci(v: Float, axis: Int): Int {
            val (o, n) = when (axis) { 0 -> min.x to nx; 1 -> min.y to ny; else -> min.z to nz }
            return ((v - o) / cell).toInt().coerceIn(0, n - 1)
        }
        private fun idx(i: Int, j: Int, k: Int) = (i * ny + j) * nz + k

        /** Calls [f] once for each triangle whose cell overlaps the box (lo, hi). */
        inline fun forTris(lo: Vector3, hi: Vector3, f: (Int) -> Unit) {
            if (hi.x < min.x || lo.x > max.x || hi.y < min.y || lo.y > max.y || hi.z < min.z || lo.z > max.z) return
            val g = nextStamp()
            for (i in cellIndex(lo.x, 0)..cellIndex(hi.x, 0)) for (j in cellIndex(lo.y, 1)..cellIndex(hi.y, 1)) for (k in cellIndex(lo.z, 2)..cellIndex(hi.z, 2)) {
                val list = cells[(i * ny + j) * nz + k] ?: continue
                for (t in list) if (mark(t, g)) f(t)
            }
        }

        fun cellIndex(v: Float, axis: Int) = ci(v, axis)
        fun nextStamp(): Int { stampGen++; if (stampGen == Int.MAX_VALUE) { stamp.fill(0); stampGen = 1 }; return stampGen }
        fun mark(t: Int, g: Int): Boolean { if (stamp[t] == g) return false; stamp[t] = g; return true }
    }

    private val cache = WeakHashMap<Instance, Mesh>()

    fun fidelity(p: Instance): Int = (p["CollisionFidelity"] as? EnumValue)?.value ?: DEFAULT

    /** Parts whose collision can follow their geometry (everything else is a box/wedge/ball). */
    fun hasGeometry(p: Instance) = (p.isA("PartOperation") && p.className != "NegateOperation") || p.className == "MeshPart"

    /** The part's collision mesh, or null when it collides as a box (Box fidelity, not loaded yet...). */
    fun forPart(p: Instance, forRays: Boolean = false): Mesh? {
        if (!hasGeometry(p)) return null
        if (!forRays && fidelity(p) == BOX) return null
        val cf = PartData.cframe(p)
        val size = PartData.size(p)
        // Local triangles (stride 8: position, normal, uv) and how they map into the part.
        val src: List<FloatArray>
        val center: Vector3
        val scale: Vector3
        val srcKey: Long
        fun k(a: Float, b: Float) = if (b > 1e-5f) a / b else 1f
        if (p.className == "MeshPart") {
            val id = AssetFetcher.idOf(PartData.meshContent(p)) ?: return null
            val m = MeshLoader.get(id) ?: return null
            if (m.triangles == 0) return null
            src = listOf(m.verts)
            center = Vector3(m.center(0), m.center(1), m.center(2))
            scale = Vector3(k(size.x, m.size(0)), k(size.y, m.size(1)), k(size.z, m.size(2)))
            srcKey = id
        } else {
            val r = SolidModeling.result(p)
            if (r != null) {
                src = r.tris.values.toList(); center = Vector3.ZERO; scale = SolidModeling.scaleOf(p, r); srcKey = r.sig
            } else {
                if (!UnionMeshes.isStored(p)) return null
                val d = UnionMeshes.get(p) ?: return null
                src = d.groups.map { it.second }; center = d.center
                scale = Vector3(k(size.x, d.size.x), k(size.y, d.size.y), k(size.z, d.size.z))
                srcKey = d.key.hashCode().toLong()
            }
        }
        val key = ((srcKey * 31 + cf.hashCode()) * 31 + size.hashCode()) * 31 + fidelity(p)
        synchronized(cache) {
            cache[p]?.let { if (it.key == key) return it }
            val built = build(key, src, center, scale, cf, fidelity(p) == HULL)
            cache[p] = built
            return built
        }
    }

    private fun build(key: Long, src: List<FloatArray>, center: Vector3, scale: Vector3, cf: CFrame, hull: Boolean): Mesh {
        val n = src.sumOf { it.size / 24 }
        val pos = FloatArray(n * 9)
        val nrm = FloatArray(n * 3)
        var mn = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); var mx = -mn
        var t = 0
        for (arr in src) {
            var o = 0
            while (o + 24 <= arr.size) {
                val w = Array(3) { v ->
                    val b = o + v * 8
                    cf * Vector3((arr[b] - center.x) * scale.x, (arr[b + 1] - center.y) * scale.y, (arr[b + 2] - center.z) * scale.z)
                }
                // Outward face normal; the vertex normals decide which way the winding faces.
                var fn = (w[1] - w[0]).cross(w[2] - w[0])
                val vn = cf.vectorToWorld(Vector3(arr[o + 3] + arr[o + 11] + arr[o + 19], arr[o + 4] + arr[o + 12] + arr[o + 20], arr[o + 5] + arr[o + 13] + arr[o + 21]))
                if (fn.dot(vn) < 0f) fn = -fn
                val len = fn.magnitude
                o += 24
                if (len < 1e-9f) continue
                fn /= len
                for (v in 0..2) { pos[t * 9 + v * 3] = w[v].x; pos[t * 9 + v * 3 + 1] = w[v].y; pos[t * 9 + v * 3 + 2] = w[v].z; mn = mn.min(w[v]); mx = mx.max(w[v]) }
                nrm[t * 3] = fn.x; nrm[t * 3 + 1] = fn.y; nrm[t * 3 + 2] = fn.z
                t++
            }
        }
        if (t == 0) { mn = cf.position; mx = cf.position }
        val m = Mesh(key, pos, nrm, t, mn, mx)
        if (hull && t > 0) m.hull = hullOf(pos, t, cf)
        return m
    }

    /** 13 directions (in the part's space) whose extremes outline a 26-sided hull. */
    private val DIRS = listOf(
        Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f),
        Vector3(1f, 1f, 0f), Vector3(1f, -1f, 0f), Vector3(1f, 0f, 1f), Vector3(1f, 0f, -1f), Vector3(0f, 1f, 1f), Vector3(0f, 1f, -1f),
        Vector3(1f, 1f, 1f), Vector3(1f, 1f, -1f), Vector3(1f, -1f, 1f), Vector3(-1f, 1f, 1f),
    ).map { it.unit }

    private fun hullOf(pos: FloatArray, count: Int, cf: CFrame): ConvexShape {
        val dirs = DIRS.map { cf.vectorToWorld(it) }
        val extremes = LinkedHashSet<Vector3>()
        for (d in dirs) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var a = Vector3.ZERO; var b = Vector3.ZERO
            for (i in 0 until count * 3) {
                val v = Vector3(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
                val s = v.dot(d)
                if (s < lo) { lo = s; a = v }
                if (s > hi) { hi = s; b = v }
            }
            extremes.add(a); extremes.add(b)
        }
        return ConvexShape(extremes.toList(), dirs, dirs)
    }

    // ------------------------------------------------------------------ queries

    /** How far to push [shape] (with bounds lo..hi) out of [m], and the top of what it hit; null if it doesn't touch. */
    fun push(m: Mesh, shape: ConvexShape, lo: Vector3, hi: Vector3): Pair<Vector3, Float>? {
        m.hull?.let { h -> return Geometry.sat(shape, h)?.let { it to m.max.y } }
        val c = shape.center
        var best: Vector3? = null
        var bestLen = Float.MAX_VALUE
        var top = -Float.MAX_VALUE
        val p = m.pos; val nr = m.nrm
        m.forTris(lo, hi) { t ->
            val o = t * 9
            val ax = p[o]; val ay = p[o + 1]; val az = p[o + 2]
            // Quick reject on the triangle's own bounds.
            if (max(ax, max(p[o + 3], p[o + 6])) < lo.x || min(ax, min(p[o + 3], p[o + 6])) > hi.x) return@forTris
            if (max(ay, max(p[o + 4], p[o + 7])) < lo.y || min(ay, min(p[o + 4], p[o + 7])) > hi.y) return@forTris
            if (max(az, max(p[o + 5], p[o + 8])) < lo.z || min(az, min(p[o + 5], p[o + 8])) > hi.z) return@forTris
            val n = Vector3(nr[t * 3], nr[t * 3 + 1], nr[t * 3 + 2])
            // One-sided: only faces looking at the shape push it (back faces belong to the far side).
            if (n.x * (c.x - ax) + n.y * (c.y - ay) + n.z * (c.z - az) < 0f) return@forTris
            val a = Vector3(ax, ay, az); val b = Vector3(p[o + 3], p[o + 4], p[o + 5]); val cc = Vector3(p[o + 6], p[o + 7], p[o + 8])
            if (!overlaps(shape, a, b, cc, n)) return@forTris
            // Depth of the shape's deepest point below the face.
            var deepest = Float.MAX_VALUE
            for (v in shape.verts) { val d = n.dot(v - a); if (d < deepest) deepest = d }
            val pen = -deepest
            if (pen <= 1e-5f) return@forTris
            val triTop = max(ay, max(p[o + 4], p[o + 7]))
            if (pen < bestLen) { bestLen = pen; best = n * pen }
            if (triTop > top) top = triTop
        }
        val b = best ?: return null
        return b to top
    }

    /** SAT between a convex shape and a triangle. */
    private fun overlaps(s: ConvexShape, a: Vector3, b: Vector3, c: Vector3, n: Vector3): Boolean {
        fun sep(axis: Vector3): Boolean {
            val l = axis.magnitude
            if (l < 1e-6f) return false
            val (s0, s1) = s.project(axis)
            val ta = a.dot(axis); val tb = b.dot(axis); val tc = c.dot(axis)
            return s1 < min(ta, min(tb, tc)) || s0 > max(ta, max(tb, tc))
        }
        if (sep(n)) return false
        for (ax in s.normals) if (sep(ax)) return false
        val edges = arrayOf(b - a, c - b, a - c)
        for (e in s.edges) for (te in edges) if (sep(e.cross(te))) return false
        return true
    }

    /** Ray against the part's triangles: distance and world normal of the first hit. */
    fun ray(m: Mesh, o: Vector3, d: Vector3, maxDist: Float): Pair<Float, Vector3>? {
        // Bounds of the ray segment, clipped to the mesh bounds, to visit only nearby cells.
        val end = o + d * maxDist.coerceAtMost(100000f)
        val lo = o.min(end).max(m.min); val hi = o.max(end).min(m.max)
        if (lo.x > hi.x || lo.y > hi.y || lo.z > hi.z) return null
        var best = Float.MAX_VALUE; var bestN: Vector3? = null
        val p = m.pos
        val visit = { t: Int ->
            val o9 = t * 9
            val ax = p[o9]; val ay = p[o9 + 1]; val az = p[o9 + 2]
            val e1x = p[o9 + 3] - ax; val e1y = p[o9 + 4] - ay; val e1z = p[o9 + 5] - az
            val e2x = p[o9 + 6] - ax; val e2y = p[o9 + 7] - ay; val e2z = p[o9 + 8] - az
            val px = d.y * e2z - d.z * e2y; val py = d.z * e2x - d.x * e2z; val pz = d.x * e2y - d.y * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) > 1e-9f) {
                val inv = 1f / det
                val tx = o.x - ax; val ty = o.y - ay; val tz = o.z - az
                val u = (tx * px + ty * py + tz * pz) * inv
                if (u in 0f..1f) {
                    val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
                    val v = (d.x * qx + d.y * qy + d.z * qz) * inv
                    if (v >= 0f && u + v <= 1f) {
                        val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
                        if (dist >= 0f && dist < best && dist <= maxDist) { best = dist; bestN = Vector3(m.nrm[t * 3], m.nrm[t * 3 + 1], m.nrm[t * 3 + 2]) }
                    }
                }
            }
        }
        // Long rays through a big mesh: the grid walk would visit every cell, so test everything.
        if ((hi - lo).magnitude > m.cell * 12f) for (t in 0 until m.count) visit(t) else m.forTris(lo, hi) { visit(it) }
        val n = bestN ?: return null
        return best to (if (n.dot(d) > 0f) -n else n)
    }
}
