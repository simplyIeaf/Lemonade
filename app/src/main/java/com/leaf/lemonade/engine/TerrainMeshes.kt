package com.leaf.lemonade.engine

import com.leaf.lemonade.model.Color3
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.Vector3
import kotlin.math.floor

/**
 * Terrain chunk meshes shared by the renderer (built in the background) and physics/raycasts
 * (built on demand), cached on the grid and rebuilt when their voxels or the material colors
 * change.
 */
object TerrainMeshes {
    private const val SPAN = TerrainGrid.CS * TerrainGrid.VOXEL

    fun colorsFor(terrain: Instance?): Array<Color3> = TerrainMaterials.colors(terrain)

    private fun colorKey(c: Array<Color3>): Int = c.contentHashCode()

    /** The up-to-date cached mesh, or null if it must be (re)built. */
    fun cached(g: TerrainGrid, cx: Int, cy: Int, cz: Int): TerrainChunkMesh? {
        val m = g.meshCache[TerrainGrid.key(cx, cy, cz)] ?: return null
        return if (m.version == TerrainMesher.stamp(g, cx, cy, cz)) m else null
    }

    /** Builds (or reuses) the mesh now. */
    fun get(g: TerrainGrid, cx: Int, cy: Int, cz: Int, colors: Array<Color3>): TerrainChunkMesh {
        val ck = colorKey(colors)
        if (g.colorKey != ck) { g.meshCache.clear(); g.colorKey = ck }
        cached(g, cx, cy, cz)?.let { return it }
        val stamp = TerrainMesher.stamp(g, cx, cy, cz)
        val m = TerrainMesher.mesh(g, cx, cy, cz, colors, stamp)
        g.meshCache[m.key] = m
        return m
    }

    /** Chunk coordinate range whose meshes can reach into the box (surfaces sit up to half a voxel past a chunk). */
    private inline fun forChunks(lo: Vector3, hi: Vector3, f: (Int, Int, Int) -> Unit) {
        val pad = TerrainGrid.VOXEL
        val x0 = floor((lo.x - pad) / SPAN).toInt(); val x1 = floor((hi.x + pad) / SPAN).toInt()
        val y0 = floor((lo.y - pad) / SPAN).toInt(); val y1 = floor((hi.y + pad) / SPAN).toInt()
        val z0 = floor((lo.z - pad) / SPAN).toInt(); val z1 = floor((hi.z + pad) / SPAN).toInt()
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) * (z1 - z0 + 1) > 4096) return
        for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) f(x, y, z)
    }

    /** Deepest push out of the terrain for a convex [shape] with bounds lo..hi, and the top of what was hit. */
    fun push(g: TerrainGrid, colors: Array<Color3>, shape: ConvexShape, lo: Vector3, hi: Vector3): Pair<Vector3, Float>? {
        if (g.chunks.isEmpty()) return null
        var best: Pair<Vector3, Float>? = null
        forChunks(lo, hi) { x, y, z ->
            val c = get(g, x, y, z, colors).collision ?: return@forChunks
            val r = CollisionMeshes.push(c, shape, lo, hi) ?: return@forChunks
            if (best == null || r.first.magnitude > best!!.first.magnitude) best = r
        }
        return best
    }

    /** First terrain hit along a ray: distance, normal, and the material index there. */
    fun raycast(g: TerrainGrid, colors: Array<Color3>, o: Vector3, d: Vector3, maxDist: Float): Triple<Float, Vector3, Int>? {
        if (g.chunks.isEmpty()) return null
        val len = maxDist.coerceAtMost(5000f)
        // Walk the chunks along the ray in order (Amanatides-Woo over 128-stud cells).
        var cx = floor(o.x / SPAN).toInt(); var cy = floor(o.y / SPAN).toInt(); var cz = floor(o.z / SPAN).toInt()
        val sx = if (d.x > 0) 1 else -1; val sy = if (d.y > 0) 1 else -1; val sz = if (d.z > 0) 1 else -1
        fun next(p: Float, dir: Float, c: Int, s: Int): Float = if (kotlin.math.abs(dir) < 1e-9f) Float.MAX_VALUE else (((c + (if (s > 0) 1 else 0)) * SPAN) - p) / dir
        var tx = next(o.x, d.x, cx, sx); var ty = next(o.y, d.y, cy, sy); var tz = next(o.z, d.z, cz, sz)
        val dx = if (kotlin.math.abs(d.x) < 1e-9f) Float.MAX_VALUE else SPAN / kotlin.math.abs(d.x)
        val dy = if (kotlin.math.abs(d.y) < 1e-9f) Float.MAX_VALUE else SPAN / kotlin.math.abs(d.y)
        val dz = if (kotlin.math.abs(d.z) < 1e-9f) Float.MAX_VALUE else SPAN / kotlin.math.abs(d.z)
        var t = 0f
        var steps = 0
        while (t <= len && steps++ < 256) {
            // A surface can belong to this chunk or (half a voxel over the border) any neighbor.
            var best: Pair<Float, Vector3>? = null
            for (a in -1..1) for (b in -1..1) for (c in -1..1) {
                val m = get(g, cx + a, cy + b, cz + c, colors).collision ?: continue
                val h = CollisionMeshes.ray(m, o, d, len) ?: continue
                if (best == null || h.first < best!!.first) best = h
            }
            val bt = best
            if (bt != null && bt.first <= minOf(tx, ty, tz) + TerrainGrid.VOXEL) {
                val p = o + d * (bt.first + 0.05f)
                val q = o + d * (bt.first - 0.05f)
                // The material just inside the surface.
                val vx = floor(p.x / TerrainGrid.VOXEL).toInt(); val vy = floor(p.y / TerrainGrid.VOXEL).toInt(); val vz = floor(p.z / TerrainGrid.VOXEL).toInt()
                var mat = g.material(vx, vy, vz)
                if (mat < 2) mat = nearestSolid(g, p, q)
                return Triple(bt.first, bt.second, mat)
            }
            if (tx <= ty && tx <= tz) { t = tx; tx += dx; cx += sx } else if (ty <= tz) { t = ty; ty += dy; cy += sy } else { t = tz; tz += dz; cz += sz }
        }
        return null
    }

    private fun nearestSolid(g: TerrainGrid, p: Vector3, q: Vector3): Int {
        val c = (p + q) / 2f
        val vx = floor(c.x / TerrainGrid.VOXEL).toInt(); val vy = floor(c.y / TerrainGrid.VOXEL).toInt(); val vz = floor(c.z / TerrainGrid.VOXEL).toInt()
        var best = 2; var bo = -1f
        for (a in -1..1) for (b in -1..1) for (e in -1..1) {
            val m = g.material(vx + a, vy + b, vz + e)
            if (m >= 2) { val o = g.occupancy(vx + a, vy + b, vz + e); if (o > bo) { bo = o; best = m } }
        }
        return best
    }

    /** Water at a point (swimming): the water level there, or null if dry. */
    fun waterAt(g: TerrainGrid, p: Vector3): Boolean {
        val vx = floor(p.x / TerrainGrid.VOXEL).toInt(); val vy = floor(p.y / TerrainGrid.VOXEL).toInt(); val vz = floor(p.z / TerrainGrid.VOXEL).toInt()
        return (g.material(vx, vy, vz) == TerrainMaterials.WATER && g.occupancy(vx, vy, vz) > 0.4f) || g.liquid(vx, vy, vz)
    }
}
