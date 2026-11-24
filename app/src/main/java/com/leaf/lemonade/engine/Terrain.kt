package com.leaf.lemonade.engine

import com.leaf.lemonade.model.BinaryString
import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.Color3
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.PropType
import com.leaf.lemonade.model.Vector3
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Roblox smooth terrain materials in SmoothGrid order (the index stored in each voxel), their
 * Enum.Material values and Studio's default colors (Terrain.MaterialColors).
 */
object TerrainMaterials {
    const val AIR = 0
    const val WATER = 1
    val NAMES = arrayOf("Air", "Water", "Grass", "Slate", "Concrete", "Brick", "Sand", "WoodPlanks", "Rock", "Glacier", "Snow",
        "Sandstone", "Mud", "Basalt", "Ground", "CrackedLava", "Asphalt", "Cobblestone", "Ice", "LeafyGrass", "Salt", "Limestone", "Pavement")
    val ENUM = intArrayOf(1792, 2048, 1280, 800, 816, 848, 1296, 528, 896, 1552, 1328, 912, 1344, 788, 1360, 804, 1376, 880, 1536, 1284, 1392, 820, 836)
    val DEFAULT_COLORS: Array<Color3> = arrayOf(
        Color3(0f, 0f, 0f), Color3.fromRGB(12, 84, 92), Color3.fromRGB(106, 127, 63), Color3.fromRGB(63, 127, 107),
        Color3.fromRGB(127, 102, 63), Color3.fromRGB(138, 86, 62), Color3.fromRGB(143, 126, 95), Color3.fromRGB(139, 109, 79),
        Color3.fromRGB(102, 108, 111), Color3.fromRGB(101, 176, 234), Color3.fromRGB(195, 199, 218), Color3.fromRGB(137, 90, 71),
        Color3.fromRGB(58, 46, 36), Color3.fromRGB(30, 30, 37), Color3.fromRGB(102, 92, 59), Color3.fromRGB(232, 156, 74),
        Color3.fromRGB(115, 123, 107), Color3.fromRGB(132, 123, 90), Color3.fromRGB(129, 194, 224), Color3.fromRGB(115, 132, 74),
        Color3.fromRGB(198, 189, 181), Color3.fromRGB(206, 173, 148), Color3.fromRGB(148, 148, 140),
    )
    /** Materials offered in the Terrain Editor (everything but Air and Water first). */
    val SOLID = (2 until NAMES.size).toList()

    fun fromEnum(v: Int): Int = ENUM.indexOf(v).takeIf { it >= 0 } ?: -1
    fun fromName(n: String): Int = NAMES.indexOfFirst { it.equals(n, true) }

    /**
     * Terrain.MaterialColors: 69 bytes, RGB per material in SmoothGrid order (Air and Water are
     * zero placeholders). Missing or malformed data gives the defaults.
     */
    fun colors(terrain: Instance?): Array<Color3> {
        val out = DEFAULT_COLORS.copyOf()
        val raw = when (val v = terrain?.get("MaterialColors")) { is BinaryString -> v.bytes; is String -> v.toByteArray(Charsets.UTF_8); else -> null }
        if (raw != null && raw.size >= NAMES.size * 3) for (m in 2 until NAMES.size) {
            val o = m * 3
            out[m] = Color3.fromRGB(raw[o].toInt() and 0xFF, raw[o + 1].toInt() and 0xFF, raw[o + 2].toInt() and 0xFF)
        }
        return out
    }

    fun encodeColors(c: Array<Color3>): ByteArray {
        val b = ByteArray(NAMES.size * 3)
        for (m in 2 until NAMES.size) {
            fun u(x: Float) = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            b[m * 3] = u(c[m].r); b[m * 3 + 1] = u(c[m].g); b[m * 3 + 2] = u(c[m].b)
        }
        return b
    }
}

/**
 * Smooth terrain voxels (4-stud cells) in 32^3 chunks, with Roblox's SmoothGrid format:
 * u8 version (1), u8 chunk size exponent (5); then per non-empty chunk: its coordinates as a
 * delta from the previous chunk (three i32, big-endian, byte-interleaved), and run-length voxels
 * (index = x + 32z + 1024y): flag = material (bits 0-5) | 0x40 occupancy byte follows |
 * 0x80 run length - 1 follows. A lone 0xFF after a voxel marks shoreline water in it.
 * Verified byte-for-byte against grids from real places (see TerrainGridTest).
 */
class TerrainGrid {
    companion object {
        const val CS = 32
        const val VOLUME = CS * CS * CS
        const val VOXEL = 4f
        fun key(x: Int, y: Int, z: Int): Long = ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)
        fun idx(x: Int, y: Int, z: Int) = x + CS * z + CS * CS * y

        /**
         * Reads a SmoothGrid. Chunks keep their run-length bytes and are only expanded into
         * arrays when something reads their voxels (big maps have thousands of chunks).
         */
        fun decode(b: ByteArray): TerrainGrid {
            val g = TerrainGrid()
            if (b.size < 2) return g
            var p = 2; var cx = 0; var cy = 0; var cz = 0
            while (p + 12 <= b.size) {
                val c = IntArray(3)
                for (k in 0 until 3) { var v = 0; for (byte in 0 until 4) v = (v shl 8) or (b[p + byte * 3 + k].toInt() and 0xFF); c[k] = v }
                p += 12; cx += c[0]; cy += c[1]; cz += c[2]
                val start = p
                // Walk the runs to find the chunk's end and whether it's one value throughout.
                var n = 0
                var uniform = true; var m0 = -1; var o0 = -1
                while (n < VOLUME && p < b.size) {
                    val f = b[p++].toInt() and 0xFF
                    if (f == 0xFF) { uniform = false; continue }
                    val m = f and 0x3F
                    var o = if (m == 0) 0 else 255
                    if (f and 0x40 != 0) o = b[p++].toInt() and 0xFF
                    var cnt = 1
                    if (f and 0x80 != 0) cnt = (b[p++].toInt() and 0xFF) + 1
                    if (m0 < 0) { m0 = m; o0 = o } else if (m != m0 || o != o0) uniform = false
                    n += cnt
                }
                // Shoreline marker right at the end of a chunk.
                while (p < b.size && (b[p].toInt() and 0xFF) == 0xFF) { uniform = false; p++ }
                val ch = Chunk(cx, cy, cz, g)
                ch.rle = b.copyOfRange(start, p)
                if (uniform) { ch.isUniform = true; ch.uMat = m0.coerceAtLeast(0); ch.uOcc = o0.coerceAtLeast(0) } else ch.isUniform = false
                g.chunks[key(cx, cy, cz)] = ch
            }
            return g
        }

        /** Expands one chunk's run-length bytes into material/occupancy arrays (+ shoreline bits). */
        internal fun expandRle(rle: ByteArray): Triple<ByteArray, ByteArray, java.util.BitSet?> {
            val mat = ByteArray(VOLUME); val occ = ByteArray(VOLUME)
            var liquid: java.util.BitSet? = null
            var p = 0; var n = 0; var last = -1
            while (p < rle.size) {
                val f = rle[p++].toInt() and 0xFF
                if (f == 0xFF) { if (last >= 0) (liquid ?: java.util.BitSet(VOLUME).also { liquid = it }).set(last); continue }
                if (n >= VOLUME) break
                val m = f and 0x3F
                var o = if (m == 0) 0 else 255
                if (f and 0x40 != 0) o = rle[p++].toInt() and 0xFF
                var cnt = 1
                if (f and 0x80 != 0) cnt = (rle[p++].toInt() and 0xFF) + 1
                val end = min(VOLUME, n + cnt)
                java.util.Arrays.fill(mat, n, end, m.toByte()); java.util.Arrays.fill(occ, n, end, o.toByte())
                last = end - 1
                n = end
            }
            return Triple(mat, occ, liquid)
        }

        /** Chunks kept expanded at once; older clean ones go back to their bytes. */
        const val MAX_EXPANDED = 600

        fun fromInstance(t: Instance): TerrainGrid {
            val bytes = when (val v = t["SmoothGrid"]) { is BinaryString -> v.bytes; is String -> v.toByteArray(Charsets.UTF_8); else -> null }
            return if (bytes == null) TerrainGrid() else try { decode(bytes) } catch (_: Exception) { TerrainGrid() }
        }
    }

    /**
     * 32^3 voxels. A chunk is uniform (one value, no arrays), or has arrays, or only its
     * run-length bytes from the file ([rle]) until something reads it. Unedited chunks re-encode
     * from [rle] exactly; [dirty] ones from their arrays.
     */
    class Chunk(val cx: Int, val cy: Int, val cz: Int, private val grid: TerrainGrid) {
        @Volatile var rle: ByteArray? = null
        @Volatile var mat: ByteArray? = null
        @Volatile var occ: ByteArray? = null
        @Volatile var liquid: java.util.BitSet? = null
        @Volatile var isUniform = true
        var uMat = 0; var uOcc = 0
        @Volatile var version = 0
        @Volatile var dirty = false
        internal var stamp = 0L

        fun matAt(i: Int): Int { if (isUniform) return uMat; val m = mat ?: load().first; return m[i].toInt() and 0xFF }
        fun occAt(i: Int): Int { if (isUniform) return uOcc; val o = occ ?: load().second; return o[i].toInt() and 0xFF }
        fun liquidAt(i: Int): Boolean { if (isUniform) return false; if (mat == null) load(); return liquid?.get(i) == true }
        val uniform get() = isUniform
        val empty get() = isUniform && uMat == 0

        /** The chunk's arrays, expanded from [rle] if needed. */
        @Synchronized fun load(): Triple<ByteArray, ByteArray, java.util.BitSet?> {
            val m = mat; val o = occ
            if (m != null && o != null) return Triple(m, o, liquid)
            val (a, b, l) = rle?.let { expandRle(it) } ?: Triple(ByteArray(VOLUME), ByteArray(VOLUME), null)
            liquid = l; occ = b; mat = a
            grid.noteExpanded(this)
            return Triple(a, b, l)
        }

        /** Makes the arrays writable (a uniform chunk gets filled arrays). Marks the chunk edited. */
        @Synchronized fun expand() {
            if (isUniform) {
                val m = ByteArray(VOLUME); val o = ByteArray(VOLUME)
                if (uMat != 0) { m.fill(uMat.toByte()); o.fill(uOcc.toByte()) }
                liquid = null; occ = o; mat = m
                isUniform = false
                grid.noteExpanded(this)
            } else if (mat == null) load()
            dirty = true
        }

        /** Becomes uniform again when every voxel is the same (saves memory on big maps). */
        @Synchronized fun compact() {
            if (isUniform) return
            val m = mat ?: return; val o = occ ?: return
            if (liquid != null && !liquid!!.isEmpty) return
            val m0 = m[0]; val o0 = o[0]
            for (i in 1 until VOLUME) if (m[i] != m0 || o[i] != o0) return
            uMat = m0.toInt() and 0xFF; uOcc = o0.toInt() and 0xFF
            isUniform = true
            liquid = null; mat = null; occ = null
            rle = null; dirty = true
        }

        /** Frees the arrays of an unedited chunk (its bytes still hold the voxels). */
        @Synchronized fun unload(): Boolean {
            if (dirty || isUniform || rle == null || mat == null) return false
            mat = null; occ = null; liquid = null
            return true
        }
    }

    private val expanded = java.util.ArrayDeque<Chunk>()
    private var stampGen = 0L

    /** Keeps at most [MAX_EXPANDED] unedited chunks expanded (least recently expanded go first). */
    internal fun noteExpanded(c: Chunk) {
        synchronized(expanded) {
            c.stamp = ++stampGen
            expanded.addLast(c)
            var guard = expanded.size
            while (expanded.size > MAX_EXPANDED && guard-- > 0) {
                val old = expanded.pollFirst() ?: break
                if (old === c || !old.unload()) { if (old.mat != null) expanded.addLast(old) }
            }
        }
    }

    val chunks = ConcurrentHashMap<Long, Chunk>()
    /** Bumped on every change (renderers/colliders compare it). */
    @Volatile var version = 0
    /** Bumped when the whole grid is replaced (Clear, Generate); part of every mesh stamp. */
    @Volatile var epoch = 0
    /** Chunk meshes (render + collision), shared by the renderer and physics; see [TerrainMeshes]. */
    val meshCache = ConcurrentHashMap<Long, TerrainChunkMesh>()
    /** Material colors the cached meshes were built with. */
    @Volatile var colorKey = 0
    /** Edited since it was decoded (the Terrain's SmoothGrid must be rewritten before saving). */
    @Volatile var modified = false

    fun chunk(cx: Int, cy: Int, cz: Int): Chunk? = chunks[key(cx, cy, cz)]

    /** Voxel material (SmoothGrid index) at voxel coordinates. */
    fun material(vx: Int, vy: Int, vz: Int): Int {
        val c = chunks[key(Math.floorDiv(vx, CS), Math.floorDiv(vy, CS), Math.floorDiv(vz, CS))] ?: return 0
        return c.matAt(idx(Math.floorMod(vx, CS), Math.floorMod(vy, CS), Math.floorMod(vz, CS)))
    }

    /** Occupancy 0..1 of the voxel's material (0 for air). */
    fun occupancy(vx: Int, vy: Int, vz: Int): Float {
        val c = chunks[key(Math.floorDiv(vx, CS), Math.floorDiv(vy, CS), Math.floorDiv(vz, CS))] ?: return 0f
        val i = idx(Math.floorMod(vx, CS), Math.floorMod(vy, CS), Math.floorMod(vz, CS))
        return if (c.matAt(i) == 0) 0f else c.occAt(i) / 255f
    }

    fun liquid(vx: Int, vy: Int, vz: Int): Boolean {
        val c = chunks[key(Math.floorDiv(vx, CS), Math.floorDiv(vy, CS), Math.floorDiv(vz, CS))] ?: return false
        return c.liquidAt(idx(Math.floorMod(vx, CS), Math.floorMod(vy, CS), Math.floorMod(vz, CS)))
    }

    /** Sets one voxel (material index, occupancy 0..1). Air is stored with occupancy 0. */
    fun set(vx: Int, vy: Int, vz: Int, material: Int, occupancy: Float) {
        val cx = Math.floorDiv(vx, CS); val cy = Math.floorDiv(vy, CS); val cz = Math.floorDiv(vz, CS)
        val m = if (occupancy <= 0.004f) 0 else material
        val o = if (m == 0) 0 else (occupancy.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(1, 255)
        val k = key(cx, cy, cz)
        var c = chunks[k]
        if (c == null) { if (m == 0) return; c = Chunk(cx, cy, cz, this); chunks[k] = c }
        val i = idx(Math.floorMod(vx, CS), Math.floorMod(vy, CS), Math.floorMod(vz, CS))
        if (c.matAt(i) == m && c.occAt(i) == o && !c.liquidAt(i)) return
        c.expand()
        c.mat!![i] = m.toByte(); c.occ!![i] = o.toByte(); c.liquid?.clear(i)
        c.version++
        version++
        modified = true
    }

    /** Marks edits finished: chunks that became uniform drop their arrays, empty ones go away. */
    fun tidy() {
        val it = chunks.entries.iterator()
        while (it.hasNext()) { val c = it.next().value; c.compact(); if (c.empty) it.remove() }
    }

    fun clear() { chunks.clear(); epoch++; version++; modified = true }

    /** Non-air voxel count (Terrain:CountCells). */
    fun countCells(): Long {
        var n = 0L
        for (c in chunks.values) {
            if (c.uniform) { if (c.uMat != 0) n += VOLUME; continue }
            val m = c.load().first
            for (b in m) if (b.toInt() != 0) n++
        }
        return n
    }

    /** SmoothGrid bytes for this grid (what Roblox reads back). */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(1); out.write(5)
        // Every chunk in the map: Roblox keeps all-air chunks of erased terrain too, and keeping
        // them makes an unedited grid encode back to exactly its original bytes. [tidy] drops
        // empty chunks after edits.
        val list = chunks.values.sortedWith(compareBy<Chunk>({ it.cx }, { it.cy }, { it.cz }))
        var px = 0; var py = 0; var pz = 0
        val coord = ByteArray(12)
        for (c in list) {
            val d = intArrayOf(c.cx - px, c.cy - py, c.cz - pz)
            px = c.cx; py = c.cy; pz = c.cz
            for (k in 0 until 3) for (byte in 0 until 4) coord[byte * 3 + k] = (d[k] ushr (24 - 8 * byte)).toByte()
            out.write(coord)
            // Unedited chunk: its original bytes.
            val raw = c.rle
            if (!c.dirty && raw != null) { out.write(raw); continue }
            var i = 0
            while (i < VOLUME) {
                val m = c.matAt(i); val o = c.occAt(i)
                if (c.liquidAt(i)) {
                    // Shoreline voxel: material, occupancy, a run of one, then the water marker.
                    out.write(m or 0xC0); out.write(o); out.write(0); out.write(0xFF)
                    i++; continue
                }
                var run = 1
                while (run < 256 && i + run < VOLUME && c.matAt(i + run) == m && c.occAt(i + run) == o && !c.liquidAt(i + run)) run++
                val withOcc = m != 0 && o != 255
                var f = m
                if (withOcc) f = f or 0x40
                if (run > 1) f = f or 0x80
                out.write(f)
                if (withOcc) out.write(o)
                if (run > 1) out.write(run - 1)
                i += run
            }
        }
        return out.toByteArray()
    }

    /** Bounds of the non-empty chunks in voxels (min inclusive, max exclusive), or null. */
    fun voxelBounds(): Pair<IntArray, IntArray>? {
        if (chunks.isEmpty()) return null
        val lo = IntArray(3) { Int.MAX_VALUE }; val hi = IntArray(3) { Int.MIN_VALUE }
        for (c in chunks.values) {
            if (c.empty) continue
            lo[0] = min(lo[0], c.cx * CS); lo[1] = min(lo[1], c.cy * CS); lo[2] = min(lo[2], c.cz * CS)
            hi[0] = max(hi[0], c.cx * CS + CS); hi[1] = max(hi[1], c.cy * CS + CS); hi[2] = max(hi[2], c.cz * CS + CS)
        }
        return if (lo[0] == Int.MAX_VALUE) null else lo to hi
    }
}

/**
 * The voxel grid behind each Terrain instance (decoded from SmoothGrid on first use). Edits
 * keep the grid; [flush] writes SmoothGrid back before saving/publishing. Setting SmoothGrid
 * (undo, a file load) makes the grid reload.
 */
object TerrainStore {
    private class Entry(val grid: TerrainGrid, var source: Any?)
    private val map = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Instance, Entry>())

    fun of(t: Instance): TerrainGrid {
        val src = t.props["SmoothGrid"]
        val e = map[t]
        // Same SmoothGrid value as when decoded (or last encoded): keep the live grid and its edits.
        if (e != null && e.source === src) return e.grid
        // First use, or SmoothGrid was replaced (undo/redo, a script, a loaded file): decode again.
        val g = TerrainGrid.fromInstance(t)
        if (e != null) g.version = e.grid.version + 1
        map[t] = Entry(g, src)
        return g
    }

    fun terrainOf(root: Instance?): Instance? = root?.let { r ->
        val ws = if (r.className == "Workspace") r else (r as? com.leaf.lemonade.model.DataModel)?.findService("Workspace") ?: r.children.firstOrNull { it.className == "Workspace" }
        ws?.findFirstChildOfClass("Terrain")
    }

    /** The bytes to store after editing; the caller sets them (so undo can record the change). */
    fun encodeFor(t: Instance): BinaryString {
        val g = of(t)
        g.tidy()
        val b = BinaryString(g.encode())
        map[t]?.source = b
        g.modified = false
        return b
    }

    /** Writes edited grids back into SmoothGrid (call before serializing a place). */
    fun flush(root: Instance) {
        val t = terrainOf(root) ?: return
        val e = map[t] ?: return
        if (!e.grid.modified) return
        val b = encodeFor(t)
        t.props["SmoothGrid"] = b
        t.propTypes["SmoothGrid"] = PropType.STRING
    }

    /** True if [t]'s grid has voxels. */
    fun hasVoxels(t: Instance): Boolean = of(t).chunks.values.any { !it.empty }
}

/** Shapes and edits on a [TerrainGrid] in world units (studs), like Terrain:Fill* and the Terrain Editor brushes. */
object TerrainOps {
    private const val V = TerrainGrid.VOXEL

    private inline fun forVoxels(lo: Vector3, hi: Vector3, f: (Int, Int, Int, Vector3) -> Unit) {
        val x0 = floor(lo.x / V).toInt(); val x1 = floor(hi.x / V).toInt()
        val y0 = floor(lo.y / V).toInt(); val y1 = floor(hi.y / V).toInt()
        val z0 = floor(lo.z / V).toInt(); val z1 = floor(hi.z / V).toInt()
        if ((x1 - x0).toLong() * (y1 - y0) * (z1 - z0) > 60_000_000L) throw IllegalArgumentException("region is too large")
        for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) f(x, y, z, Vector3(x * V + V / 2, y * V + V / 2, z * V + V / 2))
    }

    /** Puts [material] where [amount] (0..1) says, keeping whatever is fuller (Fill* add, never thin out). */
    private fun add(g: TerrainGrid, x: Int, y: Int, z: Int, material: Int, amount: Float) {
        if (amount <= 0f) return
        if (material == TerrainMaterials.AIR) { if (amount >= 0.5f) g.set(x, y, z, 0, 0f); return }
        val cur = g.occupancy(x, y, z); val curM = g.material(x, y, z)
        if (curM == material) { if (amount > cur) g.set(x, y, z, material, amount); return }
        // Replace the material where the new fill dominates.
        if (amount >= cur || curM == 0) g.set(x, y, z, material, max(amount, cur))
    }

    fun fillBlock(g: TerrainGrid, cf: CFrame, size: Vector3, material: Int) {
        val h = size / 2f
        val corners = com.leaf.lemonade.engine.Geometry.corners(cf, size)
        val lo = corners.reduce { a, b -> a.min(b) } - Vector3(V, V, V); val hi = corners.reduce { a, b -> a.max(b) } + Vector3(V, V, V)
        forVoxels(lo, hi) { x, y, z, c ->
            val l = cf.pointToObject(c)
            // Signed distance to the box (negative inside), turned into how much of the voxel is inside.
            val dx = abs(l.x) - h.x; val dy = abs(l.y) - h.y; val dz = abs(l.z) - h.z
            val d = max(dx, max(dy, dz))
            add(g, x, y, z, material, (0.5f - d / V).coerceIn(0f, 1f))
        }
    }

    fun fillBall(g: TerrainGrid, center: Vector3, radius: Float, material: Int) {
        val r = Vector3(radius + V, radius + V, radius + V)
        forVoxels(center - r, center + r) { x, y, z, c ->
            val d = (c - center).magnitude - radius
            add(g, x, y, z, material, (0.5f - d / V).coerceIn(0f, 1f))
        }
    }

    /** A cylinder along the CFrame's Y axis. */
    fun fillCylinder(g: TerrainGrid, cf: CFrame, height: Float, radius: Float, material: Int) {
        val ext = Vector3(radius, height / 2f, radius)
        val corners = com.leaf.lemonade.engine.Geometry.corners(cf, ext * 2f)
        val lo = corners.reduce { a, b -> a.min(b) } - Vector3(V, V, V); val hi = corners.reduce { a, b -> a.max(b) } + Vector3(V, V, V)
        forVoxels(lo, hi) { x, y, z, c ->
            val l = cf.pointToObject(c)
            val dr = sqrt(l.x * l.x + l.z * l.z) - radius
            val d = max(dr, abs(l.y) - height / 2f)
            add(g, x, y, z, material, (0.5f - d / V).coerceIn(0f, 1f))
        }
    }

    /** A wedge like WedgePart: full at the bottom/back, sloping up toward the front (-Z). */
    fun fillWedge(g: TerrainGrid, cf: CFrame, size: Vector3, material: Int) {
        val h = size / 2f
        val corners = com.leaf.lemonade.engine.Geometry.corners(cf, size)
        val lo = corners.reduce { a, b -> a.min(b) } - Vector3(V, V, V); val hi = corners.reduce { a, b -> a.max(b) } + Vector3(V, V, V)
        val n = Vector3(0f, size.z, -size.y).unit
        forVoxels(lo, hi) { x, y, z, c ->
            val l = cf.pointToObject(c)
            val box = max(abs(l.x) - h.x, max(abs(l.y) - h.y, abs(l.z) - h.z))
            val slope = n.dot(l)
            add(g, x, y, z, material, (0.5f - max(box, slope) / V).coerceIn(0f, 1f))
        }
    }

    /** Whole voxels in an axis-aligned region (Terrain:FillRegion). */
    fun fillRegion(g: TerrainGrid, lo: Vector3, hi: Vector3, material: Int) {
        val x0 = floor(lo.x / V + 0.001f).toInt(); val x1 = Math.ceil((hi.x / V - 0.001f).toDouble()).toInt() - 1
        val y0 = floor(lo.y / V + 0.001f).toInt(); val y1 = Math.ceil((hi.y / V - 0.001f).toDouble()).toInt() - 1
        val z0 = floor(lo.z / V + 0.001f).toInt(); val z1 = Math.ceil((hi.z / V - 0.001f).toDouble()).toInt() - 1
        for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) if (material == 0) g.set(x, y, z, 0, 0f) else g.set(x, y, z, material, 1f)
    }

    fun replaceMaterial(g: TerrainGrid, lo: Vector3, hi: Vector3, from: Int, to: Int) {
        forVoxels(lo, hi - Vector3(0.01f, 0.01f, 0.01f)) { x, y, z, _ ->
            if (g.material(x, y, z) == from) g.set(x, y, z, to, if (to == 0) 0f else g.occupancy(x, y, z).coerceAtLeast(if (from == 0) 1f else 0f))
        }
    }

    // ------------------------------------------------------------------ editor brushes

    enum class Brush { ADD, SUBTRACT, GROW, ERODE, SMOOTH, FLATTEN, PAINT, SEA_LEVEL }

    /**
     * One brush dab like Studio's Terrain Editor: [center] in studs, [radius] in studs, [strength]
     * 0..1, sphere or cube. Flatten levels to [planeY]; Paint only recolors solid voxels.
     */
    fun brush(g: TerrainGrid, kind: Brush, center: Vector3, radius: Float, strength: Float, material: Int, cube: Boolean, planeY: Float = center.y) {
        val r = Vector3(radius + V, radius + V, radius + V)
        val s = strength.coerceIn(0.05f, 1f)
        val edits = ArrayList<Triple<IntArray, Int, Float>>()
        forVoxels(center - r, center + r) { x, y, z, c ->
            val dv = c - center
            val dist = if (cube) max(abs(dv.x), max(abs(dv.y), abs(dv.z))) else dv.magnitude
            val inside = (0.5f - (dist - radius) / V).coerceIn(0f, 1f)
            if (inside <= 0f) return@forVoxels
            val curM = g.material(x, y, z); val cur = g.occupancy(x, y, z)
            when (kind) {
                Brush.ADD -> { val n = max(cur, inside * s + cur * (1 - s)); if (n > cur + 0.001f) edits.add(Triple(intArrayOf(x, y, z), if (curM == 0 || curM == 1) material else curM, n)) }
                Brush.SUBTRACT -> if (curM != 0) { val n = min(cur, 1f - inside * s); edits.add(Triple(intArrayOf(x, y, z), curM, n)) }
                Brush.GROW -> if (cur < 1f) {
                    val nb = neighborAverage(g, x, y, z)
                    val n = (cur + inside * s * 0.5f * nb).coerceAtMost(1f)
                    if (n > cur + 0.001f) edits.add(Triple(intArrayOf(x, y, z), if (curM == 0 || curM == 1) dominantNeighbor(g, x, y, z, material) else curM, n))
                }
                Brush.ERODE -> if (cur > 0f && curM != 1) {
                    val nb = 1f - neighborAverage(g, x, y, z)
                    edits.add(Triple(intArrayOf(x, y, z), curM, (cur - inside * s * 0.5f * (0.2f + nb)).coerceAtLeast(0f)))
                }
                Brush.SMOOTH -> {
                    val avg = neighborAverage(g, x, y, z)
                    val n = cur + (avg - cur) * inside * s * 0.6f
                    if (abs(n - cur) > 0.002f) edits.add(Triple(intArrayOf(x, y, z), if (curM == 0 || curM == 1) dominantNeighbor(g, x, y, z, material) else curM, n))
                }
                Brush.FLATTEN -> {
                    // Solid below the plane, air above it (blending by strength).
                    val target = (0.5f - (c.y - planeY) / V).coerceIn(0f, 1f)
                    val n = cur + (target - cur) * inside * s
                    if (abs(n - cur) > 0.002f) edits.add(Triple(intArrayOf(x, y, z), if (curM == 0 || curM == 1) material else curM, n))
                }
                Brush.PAINT -> if (curM != 0 && curM != 1 && curM != material) edits.add(Triple(intArrayOf(x, y, z), material, cur))
                Brush.SEA_LEVEL -> if (curM == 0 && c.y <= planeY) edits.add(Triple(intArrayOf(x, y, z), TerrainMaterials.WATER, 1f))
            }
        }
        for ((p, m, o) in edits) g.set(p[0], p[1], p[2], m, o)
    }

    private fun neighborAverage(g: TerrainGrid, x: Int, y: Int, z: Int): Float {
        var s = 0f
        for (d in NB) s += if (g.material(x + d[0], y + d[1], z + d[2]) == 1) 0f else g.occupancy(x + d[0], y + d[1], z + d[2])
        return s / NB.size
    }

    private fun dominantNeighbor(g: TerrainGrid, x: Int, y: Int, z: Int, fallback: Int): Int {
        val counts = IntArray(TerrainMaterials.NAMES.size)
        for (d in NB) { val m = g.material(x + d[0], y + d[1], z + d[2]); if (m >= 2 && m < counts.size) counts[m]++ }
        val best = counts.indices.maxByOrNull { counts[it] } ?: return fallback
        return if (counts[best] == 0) fallback else best
    }

    private val NB = arrayOf(intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1))
}
