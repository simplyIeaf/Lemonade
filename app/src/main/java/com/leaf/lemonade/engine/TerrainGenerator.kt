package com.leaf.lemonade.engine

import com.leaf.lemonade.model.Vector3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Studio's Terrain Editor "Generate": biomes blended over the map (water, plains, hills, dunes,
 * canyons, mountains, marsh, arctic, lavascape), optional caves, deterministic per seed.
 */
object TerrainGenerator {
    enum class Biome(val label: String) {
        WATER("Water"), MARSH("Marsh"), PLAINS("Plains"), HILLS("Hills"), DUNES("Dunes"),
        CANYONS("Canyons"), MOUNTAINS("Mountains"), LAVASCAPE("Lavascape"), ARCTIC("Arctic")
    }

    class Options(
        /** Map width/depth in studs (Small 512, Medium 1024, Large 2048, Massive 4096). */
        val size: Int = 512,
        val seed: Int = 618033988,
        val biomes: Set<Biome> = setOf(Biome.WATER, Biome.PLAINS, Biome.HILLS, Biome.MOUNTAINS),
        /** Studs across one biome region. */
        val biomeSize: Int = 100,
        val caves: Boolean = true,
        /** Center of the map (studs). */
        val center: Vector3 = Vector3.ZERO,
    )

    private const val V = TerrainGrid.VOXEL
    private const val FLOOR = -32f
    private const val SEA = 0f

    // ------------------------------------------------------------------ noise
    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 144269504
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }
    private fun hash3(x: Int, y: Int, z: Int, seed: Int): Float = hash(x * 31 + z * 1000003, y, seed)
    private fun smooth(t: Float) = t * t * (3 - 2 * t)
    private fun value(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = smooth(x - x0); val fy = smooth(y - y0)
        val a = hash(x0, y0, seed); val b = hash(x0 + 1, y0, seed); val c = hash(x0, y0 + 1, seed); val d = hash(x0 + 1, y0 + 1, seed)
        val top = a + (b - a) * fx; val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }
    private fun value3(x: Float, y: Float, z: Float, seed: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt(); val z0 = floor(z).toInt()
        val fx = smooth(x - x0); val fy = smooth(y - y0); val fz = smooth(z - z0)
        fun l(a: Float, b: Float, t: Float) = a + (b - a) * t
        val c00 = l(hash3(x0, y0, z0, seed), hash3(x0 + 1, y0, z0, seed), fx); val c10 = l(hash3(x0, y0 + 1, z0, seed), hash3(x0 + 1, y0 + 1, z0, seed), fx)
        val c01 = l(hash3(x0, y0, z0 + 1, seed), hash3(x0 + 1, y0, z0 + 1, seed), fx); val c11 = l(hash3(x0, y0 + 1, z0 + 1, seed), hash3(x0 + 1, y0 + 1, z0 + 1, seed), fx)
        return l(l(c00, c10, fy), l(c01, c11, fy), fz)
    }
    /** Fractal noise in 0..1. */
    private fun fbm(x: Float, y: Float, seed: Int, octaves: Int = 5): Float {
        var s = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        for (o in 0 until octaves) { s += value(x * f, y * f, seed + o * 1013) * amp; norm += amp; amp *= 0.5f; f *= 2.03f }
        return s / norm
    }
    private fun ridged(x: Float, y: Float, seed: Int): Float {
        var s = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        for (o in 0 until 5) { val n = 1f - abs(value(x * f, y * f, seed + o * 7919) * 2f - 1f); s += n * n * amp; norm += amp; amp *= 0.5f; f *= 2.1f }
        return s / norm
    }

    /** Surface height (studs) of one biome at (x, z). */
    private fun height(b: Biome, x: Float, z: Float, seed: Int): Float = when (b) {
        Biome.WATER -> -28f + fbm(x / 160f, z / 160f, seed + 1) * 16f
        Biome.MARSH -> -6f + fbm(x / 60f, z / 60f, seed + 2) * 9f
        Biome.PLAINS -> 4f + fbm(x / 140f, z / 140f, seed + 3) * 10f
        Biome.HILLS -> 6f + fbm(x / 90f, z / 90f, seed + 4) * 46f
        Biome.DUNES -> {
            val r = abs(kotlin.math.sin((x * 0.8f + z * 0.6f) / 26f + fbm(x / 70f, z / 70f, seed + 5) * 4f))
            4f + r * 18f + fbm(x / 50f, z / 50f, seed + 6) * 6f
        }
        Biome.CANYONS -> {
            val n = fbm(x / 120f, z / 120f, seed + 7)
            val cut = ((n - 0.42f) / 0.16f).coerceIn(0f, 1f)
            // Terraced walls between the canyon floor and the plateau.
            val h = 6f + (58f - 6f) * cut
            floor(h / 8f) * 8f + (h % 8f) * 0.25f
        }
        Biome.MOUNTAINS -> 10f + ridged(x / 180f, z / 180f, seed + 8) * 150f
        Biome.LAVASCAPE -> 8f + ridged(x / 110f, z / 110f, seed + 9) * 50f
        Biome.ARCTIC -> 6f + fbm(x / 110f, z / 110f, seed + 10) * 40f
    }

    private fun m(n: String) = TerrainMaterials.fromName(n)

    private fun surfaceMaterial(b: Biome, y: Float, slope: Float): Int = when (b) {
        Biome.WATER -> if (y < -10f) m("Sand") else m("Mud")
        Biome.MARSH -> if (slope > 0.6f || y < SEA + 1f) m("Mud") else m("LeafyGrass")
        Biome.PLAINS -> if (y < SEA + 2f) m("Sand") else if (slope > 0.9f) m("Ground") else m("Grass")
        Biome.HILLS -> if (y < SEA + 2f) m("Sand") else if (slope > 1.1f) m("Rock") else if (slope > 0.6f) m("Ground") else m("Grass")
        Biome.DUNES -> if (slope > 1.2f) m("Sandstone") else m("Sand")
        Biome.CANYONS -> if (slope > 0.8f) m("Sandstone") else if (y > 40f) m("Ground") else m("Sand")
        Biome.MOUNTAINS -> if (y > 110f) m("Snow") else if (slope > 0.9f) m("Rock") else if (y > 70f) m("Slate") else m("Grass")
        Biome.LAVASCAPE -> if (slope > 0.7f) m("Basalt") else m("CrackedLava")
        Biome.ARCTIC -> if (slope > 1.0f) m("Glacier") else if (y < SEA + 2f) m("Ice") else m("Snow")
    }

    private fun under(b: Biome, depth: Float): Int = when (b) {
        Biome.DUNES, Biome.CANYONS -> m("Sandstone")
        Biome.LAVASCAPE -> m("Basalt")
        Biome.ARCTIC -> if (depth < 12f) m("Snow") else m("Glacier")
        Biome.MOUNTAINS -> m("Rock")
        else -> if (depth < 10f) m("Ground") else m("Rock")
    }

    /** Fills [g] (cleared first) and returns how many voxels were written. [progress] gets 0..1. */
    fun generate(g: TerrainGrid, o: Options, progress: (Float) -> Unit = {}): Long {
        val enabled = o.biomes.filter { it != Biome.WATER }.ifEmpty { listOf(Biome.PLAINS) }
        val water = Biome.WATER in o.biomes
        val half = o.size / 2f
        val n = (o.size / V).toInt()
        val x0 = floor((o.center.x - half) / V).toInt(); val z0 = floor((o.center.z - half) / V).toInt()
        val bs = o.biomeSize.coerceIn(30, 2000).toFloat()
        g.clear()
        var written = 0L
        // Column heights first (slopes need neighbors).
        val w = n + 2
        val heights = FloatArray(w * w)
        val biomeOf = IntArray(w * w)
        for (j in -1..n) for (i in -1..n) {
            val wx = (x0 + i) * V + V / 2; val wz = (z0 + j) * V + V / 2
            // Each enabled biome gets a smooth weight field; the strongest wins, edges blend.
            var total = 0f; var h = 0f; var best = 0; var bw = -1f
            for ((bi, b) in enabled.withIndex()) {
                var wt = value(wx / bs + bi * 17.3f, wz / bs - bi * 9.1f, o.seed + 97 * bi)
                wt = wt * wt * wt * wt
                total += wt; h += height(b, wx, wz, o.seed) * wt
                if (wt > bw) { bw = wt; best = bi }
            }
            var hh = h / total.coerceAtLeast(1e-6f)
            // Water carves basins where its own field is strong.
            if (water) {
                val wv = value(wx / (bs * 1.3f) + 51f, wz / (bs * 1.3f) - 33f, o.seed + 4242)
                if (wv > 0.62f) hh += (height(Biome.WATER, wx, wz, o.seed) - hh) * ((wv - 0.62f) / 0.12f).coerceIn(0f, 1f)
            }
            // Fade to the floor at the map's edge so it doesn't end in a cliff.
            val edge = minOf(i, j, n - 1 - i, n - 1 - j).toFloat()
            if (edge < 6f) hh = FLOOR + (hh - FLOOR) * (edge.coerceAtLeast(0f) / 6f)
            heights[(i + 1) + (j + 1) * w] = hh
            biomeOf[(i + 1) + (j + 1) * w] = best
        }
        val yFloor = floor(FLOOR / V).toInt()
        val seaTop = floor(SEA / V).toInt() - 1
        for (j in 0 until n) {
            for (i in 0 until n) {
                val hi = (i + 1) + (j + 1) * w
                val h = heights[hi]
                val b = enabled[biomeOf[hi]]
                val sx = (heights[hi + 1] - heights[hi - 1]) / (2 * V); val sz = (heights[hi + w] - heights[hi - w]) / (2 * V)
                val slope = sqrt(sx * sx + sz * sz)
                val vx = x0 + i; val vz = z0 + j
                val wx = vx * V + V / 2; val wz = vz * V + V / 2
                val top = surfaceMaterial(b, h, slope)
                val yTop = floor(h / V).toInt()
                for (vy in yFloor..yTop) {
                    val cy = vy * V + V / 2
                    // Occupancy from how far the surface reaches into the voxel.
                    val occ = ((h - vy * V) / V).coerceIn(0f, 1f)
                    if (occ <= 0.02f) continue
                    val depth = h - cy
                    var mat = if (depth < V * 1.5f) top else under(b, depth)
                    if (o.caves && depth > 10f && cy > FLOOR + 8f && value3(wx / 34f, cy / 22f, wz / 34f, o.seed + 777) > 0.72f) continue
                    if (vy == yFloor) mat = m("Rock")
                    g.set(vx, vy, vz, mat, occ); written++
                }
                // Water up to sea level over low ground (and inside the partly filled top voxel).
                if (water && h < SEA) {
                    for (vy in yTop.coerceAtLeast(yFloor)..seaTop) {
                        if (g.occupancy(vx, vy, vz) >= 0.99f) continue
                        if (g.material(vx, vy, vz) == 0) { g.set(vx, vy, vz, TerrainMaterials.WATER, 1f); written++ }
                        else {
                            val c = g.chunk(Math.floorDiv(vx, TerrainGrid.CS), Math.floorDiv(vy, TerrainGrid.CS), Math.floorDiv(vz, TerrainGrid.CS)) ?: continue
                            c.expand()
                            val bits = c.liquid ?: java.util.BitSet(TerrainGrid.VOLUME).also { c.liquid = it }
                            bits.set(TerrainGrid.idx(Math.floorMod(vx, TerrainGrid.CS), Math.floorMod(vy, TerrainGrid.CS), Math.floorMod(vz, TerrainGrid.CS)))
                        }
                    }
                }
            }
            if (j % 8 == 0) progress(j / n.toFloat())
        }
        g.tidy()
        g.version++
        g.modified = true
        progress(1f)
        return written
    }
}
