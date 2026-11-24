package com.leaf.lemonade.render

import android.opengl.GLES20
import com.leaf.lemonade.engine.TerrainChunkMesh
import com.leaf.lemonade.engine.TerrainGrid
import com.leaf.lemonade.engine.TerrainMeshes
import com.leaf.lemonade.engine.TerrainStore
import com.leaf.lemonade.model.Color3
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Which terrain chunks to draw, their GPU buffers, and background meshing (GL thread calls
 * [update] once a frame). Solid terrain uses the mesh-batch vertex layout (28 bytes: world
 * position, uv, normal, color); water is position + normal.
 */
class TerrainView {
    class Gpu(val key: Long, val stamp: Long, val vbo: Int, val count: Int, val wvbo: Int, val wcount: Int, val min: Vector3, val max: Vector3)

    private val gpu = HashMap<Long, Gpu>()
    private var grid: TerrainGrid? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "TerrainMesher").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val done = ConcurrentLinkedQueue<TerrainChunkMesh>()
    private var candidates = LongArray(0)
    private var candidatesFor = -1
    private var candidatesSize = -1
    val visible = ArrayList<Gpu>()
    @Volatile var terrain: Instance? = null; private set
    var colors: Array<Color3> = com.leaf.lemonade.engine.TerrainMaterials.DEFAULT_COLORS; private set
    private var colorsAt = 0L

    /** Forget GPU buffers (new GL context: they're already gone). */
    fun reset(delete: Boolean) {
        if (delete) for (g in gpu.values) free(g)
        gpu.clear(); visible.clear(); done.clear()
    }

    private fun free(g: Gpu) {
        val ids = intArrayOf(g.vbo, g.wvbo).filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteBuffers(ids.size, ids, 0)
    }

    /**
     * Picks visible chunks within [range] of [cam] (frustum-tested), uploads finished meshes
     * (within a small budget) and queues missing/outdated ones nearest first.
     */
    fun update(root: Instance?, cam: Vector3, frustum: Frustum, range: Float) {
        visible.clear()
        val t = TerrainStore.terrainOf(root)
        terrain = t
        if (t == null) { if (gpu.isNotEmpty()) reset(true); grid = null; return }
        val g = TerrainStore.of(t)
        if (g !== grid) { reset(true); grid = g; candidatesFor = -1 }
        val now = System.currentTimeMillis()
        if (now - colorsAt > 500) { colorsAt = now; colors = TerrainMeshes.colorsFor(t) }
        if (g.chunks.isEmpty()) { if (gpu.isNotEmpty()) reset(true); return }
        // Chunks that exist plus the ones just below them on each axis (they own the faces on
        // the terrain's low sides), recomputed when chunks come or go.
        if (candidatesFor != g.epoch || candidatesSize != g.chunks.size) {
            val set = HashSet<Long>()
            for (c in g.chunks.values) {
                set.add(TerrainGrid.key(c.cx, c.cy, c.cz))
                set.add(TerrainGrid.key(c.cx - 1, c.cy, c.cz)); set.add(TerrainGrid.key(c.cx, c.cy - 1, c.cz)); set.add(TerrainGrid.key(c.cx, c.cy, c.cz - 1))
            }
            candidates = set.toLongArray(); candidatesFor = g.epoch; candidatesSize = g.chunks.size
        }
        // Upload finished meshes (a few per frame so a big map doesn't stall).
        var uploads = 0
        while (uploads < 6) {
            val m = done.poll() ?: break
            pending.remove(m.key)
            if (TerrainMeshes.cached(g, unkx(m.key), unky(m.key), unkz(m.key))?.version != m.version) continue
            gpu.remove(m.key)?.let { free(it) }
            gpu[m.key] = upload(m)
            uploads++
        }
        val span = TerrainGrid.CS * TerrainGrid.VOXEL
        val r2 = (range + span) * (range + span)
        val want = ArrayList<Pair<Float, Long>>()
        for (k in candidates) {
            val cx = unkx(k); val cy = unky(k); val cz = unkz(k)
            val mx = (cx + 0.5f) * span; val my = (cy + 0.5f) * span; val mz = (cz + 0.5f) * span
            val dx = mx - cam.x; val dy = my - cam.y; val dz = mz - cam.z
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > r2) continue
            val have = gpu[k]
            val stamp = com.leaf.lemonade.engine.TerrainMesher.stamp(g, cx, cy, cz)
            if (have == null || have.stamp != stamp) {
                // A finished mesh in the shared cache (built by physics or earlier) uploads now.
                val c = TerrainMeshes.cached(g, cx, cy, cz)
                if (c != null && uploads < 12) { have?.let { free(it) }; gpu[k] = upload(c); uploads++ }
                else if (k !in pending) want.add(d2 to k)
            }
            val gg = gpu[k] ?: continue
            if (gg.count == 0 && gg.wcount == 0) continue
            if (!frustum.box(gg.min.x, gg.min.y, gg.min.z, gg.max.x, gg.max.y, gg.max.z)) continue
            visible.add(gg)
        }
        // Queue the nearest missing chunks (a few at a time keeps edits responsive).
        if (want.isNotEmpty() && pending.size < 4) {
            want.sortBy { it.first }
            val cols = colors
            for ((_, k) in want.take(4 - pending.size)) {
                if (!pending.add(k)) continue
                worker.execute {
                    try { done.add(TerrainMeshes.get(g, unkx(k), unky(k), unkz(k), cols)) } catch (_: Throwable) { pending.remove(k) }
                }
            }
        }
        // Free chunks far outside the range.
        if (gpu.size > 64) {
            val far = (range * 1.6f + span).let { it * it }
            val it = gpu.entries.iterator()
            while (it.hasNext()) {
                val e = it.next(); val gg = e.value
                val c = (gg.min + gg.max) / 2f
                val d = c - cam
                if (d.x * d.x + d.y * d.y + d.z * d.z > far) { free(gg); it.remove() }
            }
        }
    }

    private fun upload(m: TerrainChunkMesh): Gpu {
        var vbo = 0; var wvbo = 0
        if (m.solidVerts > 0) {
            val bb = ByteBuffer.allocateDirect(m.solid.size * 4).order(ByteOrder.nativeOrder())
            bb.asIntBuffer().put(m.solid)
            val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); vbo = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.solid.size * 4, bb, GLES20.GL_STATIC_DRAW)
        }
        if (m.waterVerts > 0) {
            val fb = ByteBuffer.allocateDirect(m.water.size * 4).order(ByteOrder.nativeOrder())
            fb.asFloatBuffer().put(m.water)
            val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); wvbo = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, wvbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.water.size * 4, fb, GLES20.GL_STATIC_DRAW)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        // Bounds include the half voxel the surface can reach past the chunk.
        val pad = Vector3(TerrainGrid.VOXEL, TerrainGrid.VOXEL, TerrainGrid.VOXEL)
        return Gpu(m.key, m.version, vbo, m.solidVerts, wvbo, m.waterVerts, m.min - pad, m.max + pad)
    }

    /** Distance from [p] to the nearest visible chunk box (for the near plane). */
    fun closest(p: Vector3): Float {
        var best = Float.MAX_VALUE
        for (g in visible) {
            val dx = maxOf(g.min.x - p.x, 0f, p.x - g.max.x); val dy = maxOf(g.min.y - p.y, 0f, p.y - g.max.y); val dz = maxOf(g.min.z - p.z, 0f, p.z - g.max.z)
            best = minOf(best, sqrt(dx * dx + dy * dy + dz * dz))
        }
        return best
    }

    companion object {
        private fun sx(v: Long, shift: Int): Int { val x = ((v ushr shift) and 0x1FFFFF).toInt(); return if (x >= 0x100000) x - 0x200000 else x }
        fun unkx(k: Long) = sx(k, 42)
        fun unky(k: Long) = sx(k, 21)
        fun unkz(k: Long) = sx(k, 0)
        @Suppress("unused") private fun f(v: Float) = floor(v)
    }
}
