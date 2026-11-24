package com.leaf.lemonade.engine

import com.leaf.lemonade.model.Color3
import com.leaf.lemonade.model.Vector3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometry of one terrain chunk. [solid]: packed vertices for the mesh-batch shader (7 ints:
 * world position, uv, normal bytes, color bytes); [water]: position + normal floats;
 * [collision]: the solid surface as world triangles (null if none).
 */
class TerrainChunkMesh(
    val key: Long, val version: Long,
    val solid: IntArray, val solidVerts: Int,
    val water: FloatArray, val waterVerts: Int,
    val collision: CollisionMeshes.Mesh?,
    val min: Vector3, val max: Vector3,
) {
    val empty get() = solidVerts == 0 && waterVerts == 0
}

/**
 * Smooth terrain surfaces with surface nets: each 4-stud cell between voxel centers that the
 * 50% occupancy level crosses gets one vertex (the average of its crossing points), and each
 * crossed voxel edge becomes a quad. This gives Roblox-like rounded terrain from occupancies.
 */
object TerrainMesher {
    private const val CS = TerrainGrid.CS
    /** Samples per axis: the chunk's 32 voxels plus one on each side. */
    private const val S = CS + 2
    /** Cells per axis (between samples 0..33). */
    private const val CN = S - 1
    private const val V = TerrainGrid.VOXEL

    private val CORNER = arrayOf(intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(1, 1, 0),
        intArrayOf(0, 0, 1), intArrayOf(1, 0, 1), intArrayOf(0, 1, 1), intArrayOf(1, 1, 1))
    private val EDGES = arrayOf(intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7), intArrayOf(0, 2), intArrayOf(1, 3),
        intArrayOf(4, 6), intArrayOf(5, 7), intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7))

    private fun si(i: Int, j: Int, k: Int) = i + S * (k + S * j)
    private fun ci(i: Int, j: Int, k: Int) = i + CN * (k + CN * j)

    /** 0 air, 1 solid, 2 water, -1 mixed: what a whole chunk is, for skipping chunks with no surface. */
    private fun kind(c: TerrainGrid.Chunk?): Int {
        if (c == null) return 0
        if (!c.uniform) return -1
        return when { c.uMat == 0 -> 0; c.uMat == TerrainMaterials.WATER -> if (c.uOcc >= 128) 2 else 0; else -> if (c.uOcc >= 128) 1 else 0 }
    }

    /** Changes whenever a voxel this chunk's mesh samples changes (its chunk or a neighbor). */
    fun stamp(g: TerrainGrid, cx: Int, cy: Int, cz: Int): Long {
        var s = g.epoch.toLong() * 1_000_003L
        var k = 1L
        for (a in -1..1) for (b in -1..1) for (c in -1..1) {
            val ch = g.chunk(cx + a, cy + b, cz + c)
            s = s * 31 + (if (ch == null) 0L else (ch.version + 1L) * k)
            k += 2
        }
        return s
    }

    fun mesh(g: TerrainGrid, cx: Int, cy: Int, cz: Int, colors: Array<Color3>, version: Long): TerrainChunkMesh {
        val key = TerrainGrid.key(cx, cy, cz)
        val ox = cx * CS - 1; val oy = cy * CS - 1; val oz = cz * CS - 1
        val lo0 = Vector3(cx * CS * V, cy * CS * V, cz * CS * V)
        // Neighbor chunks (-1..1 on each axis) so sampling doesn't hash every voxel.
        val nb = arrayOfNulls<TerrainGrid.Chunk>(27)
        for (a in -1..1) for (b in -1..1) for (c in -1..1) nb[(a + 1) + 3 * (b + 1) + 9 * (c + 1)] = g.chunk(cx + a, cy + b, cz + c)
        // All one kind (open air, deep underground, open water inside): nothing to draw.
        val k0 = kind(nb[0])
        if (k0 >= 0 && nb.all { kind(it) == k0 }) return TerrainChunkMesh(key, version, IntArray(0), 0, FloatArray(0), 0, null, lo0, lo0 + Vector3(CS * V, CS * V, CS * V))
        val solid = FloatArray(S * S * S); val water = FloatArray(S * S * S); val mats = ByteArray(S * S * S)
        var sMin = 1f; var sMax = 0f; var wMax = 0f
        for (j in 0 until S) for (k in 0 until S) for (i in 0 until S) {
            val lx = i - 1; val ly = j - 1; val lz = k - 1
            val ax = Math.floorDiv(lx, CS); val ay = Math.floorDiv(ly, CS); val az = Math.floorDiv(lz, CS)
            val ch = nb[(ax + 1) + 3 * (ay + 1) + 9 * (az + 1)] ?: continue
            val vi = TerrainGrid.idx(Math.floorMod(lx, CS), Math.floorMod(ly, CS), Math.floorMod(lz, CS))
            val m = ch.matAt(vi)
            if (m == 0) continue
            val o = ch.occAt(vi) / 255f
            val s = si(i, j, k)
            mats[s] = m.toByte()
            if (m == TerrainMaterials.WATER) water[s] = o
            else { solid[s] = o; if (ch.liquidAt(vi)) water[s] = 1f }
        }
        for (v in solid) { if (v < sMin) sMin = v; if (v > sMax) sMax = v }
        for (v in water) if (v > wMax) wMax = v
        val lo = Vector3(cx * CS * V, cy * CS * V, cz * CS * V); val hi = lo + Vector3(CS * V, CS * V, CS * V)
        val hasSolid = sMax >= 0.5f && sMin < 0.5f
        val hasWater = wMax >= 0.5f
        if (!hasSolid && !hasWater) return TerrainChunkMesh(key, version, IntArray(0), 0, FloatArray(0), 0, null, lo, hi)

        // ---------------------------------------------------------------- solid surface
        val packed = IntArrayList()
        val colPos = FloatArrayList(); val colNrm = FloatArrayList()
        if (hasSolid) {
            val cv = surfaceVertices(solid, ox, oy, oz)
            val (vpos, vnrm, vidx) = cv
            val vcol = IntArray(vpos.size / 3)
            // Each vertex takes the color of the most solid material around its cell.
            for (j in 0 until CN) for (k in 0 until CN) for (i in 0 until CN) {
                val v = vidx[ci(i, j, k)]; if (v < 0) continue
                var best = 2; var bo = -1f
                for (c in CORNER) { val s = si(i + c[0], j + c[1], k + c[2]); val m = mats[s].toInt(); if (m >= 2 && solid[s] > bo) { bo = solid[s]; best = m } }
                val col = colors.getOrElse(best) { colors[2] }
                // A little variation so large areas don't look flat (stable per position).
                val px = vpos[v * 3]; val py = vpos[v * 3 + 1]; val pz = vpos[v * 3 + 2]
                var h = (floor4(px) * 73856093) xor (floor4(py) * 19349663) xor (floor4(pz) * 83492791)
                h = h xor (h ushr 13)
                val f = 0.93f + ((h and 255) / 255f) * 0.12f
                vcol[v] = rgb(col.r * f, col.g * f, col.b * f)
            }
            emitFaces(solid, null, vidx, vpos) { a, b, c, nx, ny, nz ->
                for (v in intArrayOf(a, b, c)) {
                    packed.add(java.lang.Float.floatToRawIntBits(vpos[v * 3])); packed.add(java.lang.Float.floatToRawIntBits(vpos[v * 3 + 1])); packed.add(java.lang.Float.floatToRawIntBits(vpos[v * 3 + 2]))
                    packed.add(0); packed.add(0)
                    packed.add(b4(sb(vnrm[v * 3]), sb(vnrm[v * 3 + 1]), sb(vnrm[v * 3 + 2]), 0))
                    packed.add(vcol[v])
                    colPos.add(vpos[v * 3]); colPos.add(vpos[v * 3 + 1]); colPos.add(vpos[v * 3 + 2])
                }
                colNrm.add(nx); colNrm.add(ny); colNrm.add(nz)
            }
        }

        // ---------------------------------------------------------------- water surface
        val wat = FloatArrayList()
        if (hasWater) {
            // Solid counts as "water" here, so the water surface only exists against air and
            // meets the shore without hidden faces against the ground.
            val wf = FloatArray(solid.size) { max(water[it], solid[it]) }
            val (vpos, vnrm, vidx) = surfaceVertices(wf, ox, oy, oz)
            emitFaces(wf, water.let { w -> FloatArray(w.size) { if (w[it] >= solid[it] && w[it] > 0f) 1f else 0f } }, vidx, vpos) { a, b, c, _, _, _ ->
                for (v in intArrayOf(a, b, c)) {
                    wat.add(vpos[v * 3]); wat.add(vpos[v * 3 + 1]); wat.add(vpos[v * 3 + 2])
                    wat.add(vnrm[v * 3]); wat.add(vnrm[v * 3 + 1]); wat.add(vnrm[v * 3 + 2])
                }
            }
        }

        val triCount = colNrm.size / 3
        val collision = if (triCount > 0) {
            val p = colPos.toArray(); val n = colNrm.toArray()
            var mn = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); var mx = Vector3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var q = 0
            while (q < p.size) { val v = Vector3(p[q], p[q + 1], p[q + 2]); mn = mn.min(v); mx = mx.max(v); q += 3 }
            CollisionMeshes.Mesh(key, p, n, triCount, mn, mx)
        } else null
        return TerrainChunkMesh(key, version, packed.toArray(), packed.size / 7, wat.toArray(), wat.size / 6, collision, lo, hi)
    }

    /** One vertex per crossed cell: positions/normals (world) and the cell -> vertex index. */
    private fun surfaceVertices(f: FloatArray, ox: Int, oy: Int, oz: Int): Triple<FloatArray, FloatArray, IntArray> {
        val vidx = IntArray(CN * CN * CN) { -1 }
        val pos = FloatArrayList(); val nrm = FloatArrayList()
        val d = FloatArray(8)
        for (j in 0 until CN) for (k in 0 until CN) for (i in 0 until CN) {
            var mask = 0
            for (c in 0 until 8) { val cc = CORNER[c]; d[c] = f[si(i + cc[0], j + cc[1], k + cc[2])] - 0.5f; if (d[c] >= 0f) mask = mask or (1 shl c) }
            if (mask == 0 || mask == 255) continue
            var sx = 0f; var sy = 0f; var sz = 0f; var n = 0
            for (e in EDGES) {
                val a = e[0]; val b = e[1]
                if ((d[a] >= 0f) == (d[b] >= 0f)) continue
                val t = d[a] / (d[a] - d[b])
                val ca = CORNER[a]; val cb = CORNER[b]
                sx += ca[0] + (cb[0] - ca[0]) * t; sy += ca[1] + (cb[1] - ca[1]) * t; sz += ca[2] + (cb[2] - ca[2]) * t
                n++
            }
            sx /= n; sy /= n; sz /= n
            // Sample s sits at the center of voxel (origin + s): world = (voxel + 0.5) * 4.
            pos.add((ox + i + sx + 0.5f) * V); pos.add((oy + j + sy + 0.5f) * V); pos.add((oz + k + sz + 0.5f) * V)
            // Normal: away from the solid side (minus the density gradient).
            var gx = (d[1] + d[3] + d[5] + d[7]) - (d[0] + d[2] + d[4] + d[6])
            var gy = (d[2] + d[3] + d[6] + d[7]) - (d[0] + d[1] + d[4] + d[5])
            var gz = (d[4] + d[5] + d[6] + d[7]) - (d[0] + d[1] + d[2] + d[3])
            val l = sqrt(gx * gx + gy * gy + gz * gz)
            if (l > 1e-6f) { gx /= -l; gy /= -l; gz /= -l } else { gx = 0f; gy = 1f; gz = 0f }
            nrm.add(gx); nrm.add(gy); nrm.add(gz)
            vidx[ci(i, j, k)] = pos.size / 3 - 1
        }
        return Triple(pos.toArray(), nrm.toArray(), vidx)
    }

    /**
     * Quads for the voxel edges this chunk owns (start voxel inside the chunk) where [f] crosses
     * 0.5, as two triangles each facing out of the filled side; [insideFilter] (if given) must be
     * 1 on the filled side for the quad to be kept. [out] gets vertex indices and the face normal.
     */
    private inline fun emitFaces(f: FloatArray, insideFilter: FloatArray?, vidx: IntArray, vpos: FloatArray,
                                 out: (Int, Int, Int, Float, Float, Float) -> Unit) {
        for (j in 1..CS) for (k in 1..CS) for (i in 1..CS) {
            val p = si(i, j, k)
            val inP = f[p] >= 0.5f
            for (axis in 0 until 3) {
                val q = when (axis) { 0 -> si(i + 1, j, k); 1 -> si(i, j + 1, k); else -> si(i, j, k + 1) }
                val inQ = f[q] >= 0.5f
                if (inP == inQ) continue
                if (insideFilter != null && insideFilter[if (inP) p else q] < 0.5f) continue
                // The four cells sharing this edge, in order around it.
                val c = when (axis) {
                    0 -> intArrayOf(ci(i, j - 1, k - 1), ci(i, j, k - 1), ci(i, j, k), ci(i, j - 1, k))
                    1 -> intArrayOf(ci(i - 1, j, k - 1), ci(i, j, k - 1), ci(i, j, k), ci(i - 1, j, k))
                    else -> intArrayOf(ci(i - 1, j - 1, k), ci(i, j - 1, k), ci(i, j, k), ci(i - 1, j, k))
                }
                val v0 = vidx[c[0]]; val v1 = vidx[c[1]]; val v2 = vidx[c[2]]; val v3 = vidx[c[3]]
                if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0) continue
                // Outward direction: from the filled sample toward the empty one.
                val dir = if (inP) 1f else -1f
                val ex = if (axis == 0) dir else 0f; val ey = if (axis == 1) dir else 0f; val ez = if (axis == 2) dir else 0f
                tri(vpos, v0, v1, v2, ex, ey, ez, out)
                tri(vpos, v0, v2, v3, ex, ey, ez, out)
            }
        }
    }

    /**
     * Emits a triangle wound to face the outward edge direction (ex, ey, ez), with its true
     * geometric normal (collision pushes along it). Degenerate triangles are skipped.
     */
    private inline fun tri(p: FloatArray, a: Int, b: Int, c: Int, ex: Float, ey: Float, ez: Float, out: (Int, Int, Int, Float, Float, Float) -> Unit) {
        val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
        val wx = p[c * 3] - p[a * 3]; val wy = p[c * 3 + 1] - p[a * 3 + 1]; val wz = p[c * 3 + 2] - p[a * 3 + 2]
        var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-6f) return
        nx /= l; ny /= l; nz /= l
        if (nx * ex + ny * ey + nz * ez >= 0f) out(a, b, c, nx, ny, nz) else out(a, c, b, -nx, -ny, -nz)
    }

    private fun floor4(v: Float) = kotlin.math.floor(v * 0.5f).toInt()
    private fun ub(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    private fun sb(v: Float) = (v.coerceIn(-1f, 1f) * 127f).toInt() and 0xFF
    private fun b4(a: Int, b: Int, c: Int, d: Int) = (a and 0xFF) or ((b and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((d and 0xFF) shl 24)
    private fun rgb(r: Float, g: Float, b: Float) = b4(ub(r), ub(g), ub(b), 0)

    private class IntArrayList { var a = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
    private class FloatArrayList { var a = FloatArray(1024); var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
}
