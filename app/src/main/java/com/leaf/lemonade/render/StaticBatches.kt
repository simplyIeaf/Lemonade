package com.leaf.lemonade.render

import android.opengl.GLES20
import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.PartData
import com.leaf.lemonade.model.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Textures a part is drawn with: GL ids of the color and normal maps, and their tiling. */
data class TexBind(val color: Int, val normal: Int, val scale: Float)

/**
 * Everything the batch shader needs about one part, resolved on the GL thread when its chunk
 * is built. [mesh] is a built-in unit shape (position + normal, 6 floats per vertex).
 * [kind]: 0 lean shader, 1 procedural materials, 2 material textures ([tex]).
 */
class BatchSpec(
    val mesh: FloatArray, val cube: Boolean, val cf: CFrame, val scale: Vector3,
    val r: Float, val g: Float, val b: Float, val emissive: Boolean, val mat: FloatArray,
    val surfA: Vector3?, val surfB: Vector3?, val kind: Int, val tex: TexBind?, val pending: Boolean,
    /** Floats per vertex in [mesh] (6: pos+normal; 8: pos+normal+uv for file meshes and unions). */
    val stride: Int = 6,
    /** File meshes (kind 3): mesh-space offset subtracted before scaling (the mesh's center). */
    val offset: Vector3 = Vector3.ZERO,
)

/** Six frustum planes (a, b, c, d) taken from a column-major view-projection matrix. */
class Frustum {
    private val p = FloatArray(24)

    fun set(m: FloatArray) {
        var k = 0
        for (i in 0..2) for (s in 0..1) {
            val sg = if (s == 0) 1f else -1f
            val a = m[3] + sg * m[i]; val b = m[7] + sg * m[4 + i]; val c = m[11] + sg * m[8 + i]; val d = m[15] + sg * m[12 + i]
            val l = sqrt(a * a + b * b + c * c).coerceAtLeast(1e-9f)
            p[k] = a / l; p[k + 1] = b / l; p[k + 2] = c / l; p[k + 3] = d / l
            k += 4
        }
    }

    fun sphere(x: Float, y: Float, z: Float, r: Float): Boolean {
        var k = 0
        while (k < 24) { if (p[k] * x + p[k + 1] * y + p[k + 2] * z + p[k + 3] < -r) return false; k += 4 }
        return true
    }

    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        var k = 0
        while (k < 24) {
            val a = p[k]; val b = p[k + 1]; val c = p[k + 2]
            val x = if (a >= 0) x1 else x0; val y = if (b >= 0) y1 else y0; val z = if (c >= 0) z1 else z0
            if (a * x + b * y + c * z + p[k + 3] < 0) return false
            k += 4
        }
        return true
    }
}

/**
 * Static batching for big places. Parts that haven't changed for a moment are merged, per
 * 64-stud chunk, into a few vertex buffers (already in world space), so a chunk with hundreds
 * of parts costs one draw call per material instead of one (plus ~15 GL calls) per part.
 *
 * Each chunk keeps three size tiers; small parts are only drawn up to a distance where they
 * would still cover a pixel or two ("loading out" detail far away, like Roblox's streaming),
 * and chunks that stay out of range long enough free their GPU memory. Parts that are edited,
 * simulated or animated drop out of their batch and are drawn one by one ([loose]) until they
 * have been still again for a second.
 */
class StaticBatches(private val host: Host) {
    interface Host {
        /** Can this part ever be batched (opaque, not a character, no hinge...)? */
        fun batchable(p: Instance): Boolean
        /**
         * Full drawing data (several pieces for a union), or null if it turned out not to be
         * batchable. A spec drawn with a stand-in (mesh or texture still loading) is marked
         * pending, and its chunk is rebuilt when [loadSignature] changes.
         */
        fun spec(p: Instance): List<BatchSpec>?
        /** Changes whenever meshes, textures or images finish loading. */
        fun loadSignature(): Int
    }

    class PartRec(val gen: Int) {
        var ver = 0
        var changedFrame = Int.MIN_VALUE / 2
        var seen = 0
        var chunk: Chunk? = null
        var known = false
        var batchable = false
    }

    /** [kind] 0-2: lit shader variants (40-byte vertices); 3: file meshes (28-byte vertices, [tex].color = texture). */
    class Batch(val vbo: Int, val count: Int, val tier: Int, val kind: Int, val tex: TexBind?, val bytes: Int) {
        val strideBytes get() = if (kind == 3) MESH_STRIDE_BYTES else STRIDE_BYTES
    }

    class Chunk(val key: Long) {
        var parts = ArrayList<Instance>()
        var dirty = true
        var built = false
        var batches: List<Batch> = emptyList()
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var maxTier = 0
        var texEpoch = -1
        var pendingTex = false
        var lastInRange = 0
        var dist = 0f
        var bytes = 0L

        fun grow(cx: Float, cy: Float, cz: Float, r: Float) {
            x0 = min(x0, cx - r); y0 = min(y0, cy - r); z0 = min(z0, cz - r)
            x1 = max(x1, cx + r); y1 = max(y1, cy + r); z1 = max(z1, cz + r)
        }

        fun distTo(p: Vector3): Float {
            val dx = max(max(x0 - p.x, 0f), p.x - x1); val dy = max(max(y0 - p.y, 0f), p.y - y1); val dz = max(max(z0 - p.z, 0f), p.z - z1)
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    val chunks = HashMap<Long, Chunk>()
    /** Parts to draw one by one this frame (not in a built batch). */
    val loose = ArrayList<Instance>()
    /** Built chunks in range of the camera or the shadow area this frame. */
    val visible = ArrayList<Chunk>()
    var totalBytes = 0L; private set
    private var gen = 1
    private var lastListVersion = -1
    private var frame = 0

    /** Forget everything (new GL context: the old buffers are already gone, so nothing is deleted). */
    fun reset(deleteBuffers: Boolean) {
        if (deleteBuffers) for (c in chunks.values) free(c)
        chunks.clear(); visible.clear(); loose.clear()
        totalBytes = 0L
        gen++
    }

    /** Largest part radius in each tier; tier 2 has no limit. */
    private fun tierOf(r: Float) = if (r < 1.2f) 0 else if (r < 5f) 1 else 2
    /**
     * How far a size tier stays drawn: where its parts shrink below ~1.5 px, but never closer
     * than a floor (so zoomed-out views still show the whole layout on slow devices).
     */
    fun tierRange(tier: Int, detail: Float): Float = when (tier) { 0 -> max(1.2f * detail, 150f); 1 -> max(5f * detail, 900f); else -> Float.MAX_VALUE }

    private fun keyOf(x: Float, y: Float, z: Float): Long {
        val ix = floor(x / CELL).toLong() and 0x1FFFFF; val iy = floor(y / CELL).toLong() and 0x1FFFFF; val iz = floor(z / CELL).toLong() and 0x1FFFFF
        return (ix shl 42) or (iy shl 21) or iz
    }

    /**
     * Once per frame (GL thread, studio lock held). [detail] converts a part radius into the
     * distance it stays visible (radius * focal px * quality / min px). [shadowAt]/[shadowR]
     * is the area the shadow map covers (chunks there are needed even off screen).
     */
    fun update(parts: List<Instance>, listVersion: Int, frame: Int, cam: Vector3, fr: Frustum, detail: Float,
               shadowAt: Vector3?, shadowR: Float, budgetNs: Long, maxBytes: Long) {
        this.frame = frame
        loose.clear(); visible.clear()
        for (p in parts) {
            var rec = p.renderRec as? PartRec
            if (rec == null || rec.gen != gen) {
                rec = PartRec(gen); p.renderRec = rec
                rec.ver = p.version
                // First sight (place just opened, playtest started): no need to wait.
                rec.changedFrame = frame - COOL
            } else if (rec.ver != p.version) {
                rec.ver = p.version
                rec.changedFrame = frame
                rec.known = false
                rec.chunk?.let { it.dirty = true; rec.chunk = null }
            }
            rec.seen = frame
            if (rec.chunk != null) continue
            if (frame - rec.changedFrame >= COOL) {
                if (!rec.known) { rec.known = true; rec.batchable = host.batchable(p) }
                if (rec.batchable) { join(p, rec); continue }
            }
            loose.add(p)
        }
        // Parts removed from the place: their chunks must drop them.
        if (listVersion != lastListVersion) {
            lastListVersion = listVersion
            for (c in chunks.values) if (!c.dirty) for (p in c.parts) {
                val r = p.renderRec as? PartRec
                if (r == null || r.seen != frame || r.chunk !== c) { c.dirty = true; break }
            }
        }
        val texEpoch = host.loadSignature()
        val toBuild = ArrayList<Chunk>()
        val shR2 = shadowR * shadowR
        val it = chunks.values.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (c.parts.isEmpty() && !c.built) { it.remove(); continue }
            if (c.built && c.pendingTex && c.texEpoch != texEpoch) c.dirty = true
            val d = c.distTo(cam)
            c.dist = d
            var need = d <= tierRange(c.maxTier, detail) && fr.box(c.x0, c.y0, c.z0, c.x1, c.y1, c.z1)
            if (!need && shadowAt != null) {
                val dx = max(max(c.x0 - shadowAt.x, 0f), shadowAt.x - c.x1); val dz = max(max(c.z0 - shadowAt.z, 0f), shadowAt.z - c.z1)
                need = dx * dx + dz * dz < shR2
            }
            if (need) c.lastInRange = frame
            // Out of range long enough: give the GPU memory back ("load out").
            else if (c.built && frame - c.lastInRange > UNLOAD_FRAMES) { free(c); c.dirty = true }
            if (c.dirty || !c.built) { if (need) toBuild.add(c) }
            else if (need) visible.add(c)
        }
        if (toBuild.isNotEmpty()) {
            toBuild.sortBy { it.dist }
            val end = System.nanoTime() + budgetNs
            var first = true
            for (c in toBuild) {
                if (first || System.nanoTime() < end) {
                    first = false
                    build(c, frame, texEpoch)
                    if (c.built) visible.add(c)
                    if (c.parts.isEmpty()) chunks.remove(c.key)
                } else {
                    // Over budget: draw this chunk's parts one by one until a later frame builds it.
                    for (p in c.parts) { val r = p.renderRec as? PartRec ?: continue; if (r.chunk === c && r.seen == frame) loose.add(p) }
                }
            }
        }
        // Over the memory budget: free the chunks that have been out of range the longest.
        if (totalBytes > maxBytes) {
            val idle = chunks.values.filter { it.built && it.lastInRange != frame }.sortedBy { it.lastInRange }
            for (c in idle) { if (totalBytes <= maxBytes) break; free(c); c.dirty = true }
        }
    }

    private fun join(p: Instance, rec: PartRec) {
        val cf = PartData.cframe(p)
        val r = PartData.size(p).magnitude * 0.5f
        val c = chunks.getOrPut(keyOf(cf.x, cf.y, cf.z)) { Chunk(keyOf(cf.x, cf.y, cf.z)) }
        c.parts.add(p)
        c.grow(cf.x, cf.y, cf.z, r)
        c.maxTier = max(c.maxTier, tierOf(r))
        c.dirty = true
        rec.chunk = c
    }

    private fun free(c: Chunk) {
        if (c.batches.isNotEmpty()) {
            val ids = IntArray(c.batches.size) { c.batches[it].vbo }
            GLES20.glDeleteBuffers(ids.size, ids, 0)
        }
        totalBytes -= c.bytes
        c.bytes = 0; c.batches = emptyList(); c.built = false
    }

    private class Group(val tier: Int, val kind: Int, val tex: TexBind?) { val specs = ArrayList<BatchSpec>(); var verts = 0 }

    private fun build(c: Chunk, frame: Int, texEpoch: Int) {
        val keep = ArrayList<Instance>(c.parts.size)
        val once = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>())
        for (p in c.parts) {
            val r = p.renderRec as? PartRec ?: continue
            if (r.chunk !== c || !once.add(p)) continue
            if (r.seen != frame) { r.chunk = null; continue }
            keep.add(p)
        }
        c.parts = keep
        free(c)
        c.x0 = Float.MAX_VALUE; c.y0 = Float.MAX_VALUE; c.z0 = Float.MAX_VALUE
        c.x1 = -Float.MAX_VALUE; c.y1 = -Float.MAX_VALUE; c.z1 = -Float.MAX_VALUE
        c.maxTier = 0
        val groups = LinkedHashMap<Any, Group>()
        var pending = false
        val stillIn = ArrayList<Instance>(keep.size)
        for (p in keep) {
            val list = try { host.spec(p) } catch (_: Exception) { null }
            val rec = p.renderRec as PartRec
            if (list.isNullOrEmpty()) { rec.chunk = null; rec.batchable = false; rec.known = true; loose.add(p); continue }
            stillIn.add(p)
            // The part's bounds and size tier (a union's pieces share its tier).
            val pr = PartData.size(p).magnitude * 0.5f
            val pc = PartData.cframe(p)
            c.grow(pc.x, pc.y, pc.z, pr)
            val tier = tierOf(pr)
            c.maxTier = max(c.maxTier, tier)
            for (s in list) {
                if (s.pending) pending = true
                val gk = GroupKey(tier, s.kind, s.tex)
                val g = groups.getOrPut(gk) { Group(tier, s.kind, s.tex) }
                g.specs.add(s); g.verts += s.mesh.size / s.stride
            }
        }
        c.parts = stillIn
        val out = ArrayList<Batch>(groups.size)
        for (g in groups.values) {
            if (g.verts == 0) continue
            val ints = g.verts * STRIDE_INTS
            if (scratch.size < ints) scratch = IntArray(max(ints, scratch.size * 2))
            var o = 0
            for (s in g.specs) o = if (g.kind == 3) packMesh(s, scratch, o) else pack(s, scratch, o)
            val bytes = o * 4
            if (direct == null || direct!!.capacity() < bytes) direct = ByteBuffer.allocateDirect(max(bytes, (direct?.capacity() ?: 0) * 2)).order(ByteOrder.nativeOrder())
            val bb = direct!!
            bb.clear()
            val ib: IntBuffer = bb.asIntBuffer()
            ib.put(scratch, 0, o)
            bb.position(0); bb.limit(bytes)
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bytes, bb, GLES20.GL_STATIC_DRAW)
            out.add(Batch(ids[0], o / (if (g.kind == 3) MESH_STRIDE_INTS else STRIDE_INTS), g.tier, g.kind, g.tex, bytes))
            c.bytes += bytes
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        totalBytes += c.bytes
        c.batches = out
        c.built = true
        c.dirty = false
        c.texEpoch = texEpoch
        c.pendingTex = pending
        c.lastInRange = frame
    }

    private data class GroupKey(val tier: Int, val kind: Int, val tex: TexBind?)

    private var scratch = IntArray(36 * STRIDE_INTS * 256)
    private var direct: ByteBuffer? = null
    private val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    private fun b4(a: Int, b: Int, c: Int, d: Int): Int =
        if (little) (a and 0xFF) or ((b and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((d and 0xFF) shl 24)
        else ((a and 0xFF) shl 24) or ((b and 0xFF) shl 16) or ((c and 0xFF) shl 8) or (d and 0xFF)

    private fun sb(v: Float) = (v.coerceIn(-1f, 1f) * 127f).let { if (it >= 0) (it + 0.5f).toInt() else (it - 0.5f).toInt() }
    private fun ub(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /**
     * Bakes one part into [out] at [o]: 10 ints per vertex = pos(3 f32) uv(2 f32, studs)
     * nrm(4 s8: xyz + surface type) col(4 u8: rgb + emissive) mat(4 u8: type, amt*1000,
     * scale*50, spec*120) tan(4 s8) bit(4 s8). Same face projection as the per-part shader.
     */
    private fun pack(s: BatchSpec, out: IntArray, start: Int): Int {
        var o = start
        val cf = s.cf; val sx = s.scale.x; val sy = s.scale.y; val sz = s.scale.z
        val ix = if (sx != 0f) 1f / sx else 0f; val iy = if (sy != 0f) 1f / sy else 0f; val iz = if (sz != 0f) 1f / sz else 0f
        val col = b4(ub(s.r), ub(s.g), ub(s.b), if (s.emissive) 255 else 0)
        val m = s.mat
        val matI = b4(m[0].toInt().coerceIn(0, 255), (m[1] * 1000f + 0.5f).toInt().coerceIn(0, 255), (m[2] * 50f + 0.5f).toInt().coerceIn(0, 255), (m[3] * 120f + 0.5f).toInt().coerceIn(0, 255))
        val mesh = s.mesh
        val st = s.stride
        val tris = mesh.size / (3 * st)
        for (t in 0 until tris) {
            val base = t * 3 * st
            // Classify the face once per triangle (smooth shapes would otherwise mix projections).
            val anx = abs(mesh[base + 3] + mesh[base + st + 3] + mesh[base + 2 * st + 3])
            val any = abs(mesh[base + 4] + mesh[base + st + 4] + mesh[base + 2 * st + 4])
            val anz = abs(mesh[base + 5] + mesh[base + st + 5] + mesh[base + 2 * st + 5])
            val yF = any > anx && any > anz
            val xF = !yF && anx > anz
            // Tangent/bitangent: world directions of the face's texture axes.
            val tlx = if (yF) 1f else if (xF) 0f else 1f; val tlz = if (yF) 0f else if (xF) 1f else 0f
            var tx = cf.r00 * tlx * sx + cf.r02 * tlz * sz; var ty = cf.r10 * tlx * sx + cf.r12 * tlz * sz; var tz = cf.r20 * tlx * sx + cf.r22 * tlz * sz
            var tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-9f); tx /= tl; ty /= tl; tz /= tl
            var bx: Float; var by: Float; var bz: Float
            if (yF) { bx = cf.r02; by = cf.r12; bz = cf.r22 } else { bx = cf.r01; by = cf.r11; bz = cf.r21 }
            tl = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-9f); bx /= tl; by /= tl; bz /= tl
            val tanI = b4(sb(tx), sb(ty), sb(tz), 0)
            val bitI = b4(sb(bx), sb(by), sb(bz), 0)
            // Surface type (studs, inlets...) for blocks.
            var surf = 0
            if (s.cube) {
                val a = s.surfA; val bb = s.surfB
                val ln = if (yF) mesh[base + 4] else if (xF) mesh[base + 3] else mesh[base + 5]
                if (a != null && bb != null) {
                    val v = if (yF) (if (ln > 0) a.y else bb.y) else if (xF) (if (ln > 0) a.x else bb.x) else (if (ln > 0) a.z else bb.z)
                    surf = v.toInt().coerceIn(0, 127)
                }
            }
            for (v in 0 until 3) {
                val k = base + v * st
                val lx = mesh[k]; val ly = mesh[k + 1]; val lz = mesh[k + 2]
                val px = lx * sx; val py = ly * sy; val pz = lz * sz
                out[o] = java.lang.Float.floatToRawIntBits(cf.r00 * px + cf.r01 * py + cf.r02 * pz + cf.x)
                out[o + 1] = java.lang.Float.floatToRawIntBits(cf.r10 * px + cf.r11 * py + cf.r12 * pz + cf.y)
                out[o + 2] = java.lang.Float.floatToRawIntBits(cf.r20 * px + cf.r21 * py + cf.r22 * pz + cf.z)
                val ux = px + sx * 0.5f; val uy = py + sy * 0.5f; val uz = pz + sz * 0.5f
                val u = if (yF) ux else if (xF) uz else ux
                val w = if (yF) uz else uy
                out[o + 3] = java.lang.Float.floatToRawIntBits(u)
                out[o + 4] = java.lang.Float.floatToRawIntBits(w)
                val nx0 = mesh[k + 3] * ix; val ny0 = mesh[k + 4] * iy; val nz0 = mesh[k + 5] * iz
                var nx = cf.r00 * nx0 + cf.r01 * ny0 + cf.r02 * nz0
                var ny = cf.r10 * nx0 + cf.r11 * ny0 + cf.r12 * nz0
                var nz = cf.r20 * nx0 + cf.r21 * ny0 + cf.r22 * nz0
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f); nx /= nl; ny /= nl; nz /= nl
                out[o + 5] = b4(sb(nx), sb(ny), sb(nz), surf)
                out[o + 6] = col
                out[o + 7] = matI
                out[o + 8] = tanI
                out[o + 9] = bitI
                o += STRIDE_INTS
            }
        }
        return o
    }

    /**
     * A file mesh (MeshPart / FileMesh) baked to world space, 7 ints per vertex: pos(3 f32)
     * uv(2 f32) nrm(4 s8) col(4 u8: rgb + emissive). Same transform as SceneRenderer.geometry:
     * world = cf * ((v - offset) * scale).
     */
    private fun packMesh(s: BatchSpec, out: IntArray, start: Int): Int {
        var o = start
        val cf = s.cf; val sx = s.scale.x; val sy = s.scale.y; val sz = s.scale.z
        val ix = if (sx != 0f) 1f / sx else 0f; val iy = if (sy != 0f) 1f / sy else 0f; val iz = if (sz != 0f) 1f / sz else 0f
        val col = b4(ub(s.r), ub(s.g), ub(s.b), if (s.emissive) 255 else 0)
        val m = s.mesh; val st = s.stride
        val ox = s.offset.x; val oy = s.offset.y; val oz = s.offset.z
        var k = 0
        while (k + st <= m.size) {
            val px = (m[k] - ox) * sx; val py = (m[k + 1] - oy) * sy; val pz = (m[k + 2] - oz) * sz
            out[o] = java.lang.Float.floatToRawIntBits(cf.r00 * px + cf.r01 * py + cf.r02 * pz + cf.x)
            out[o + 1] = java.lang.Float.floatToRawIntBits(cf.r10 * px + cf.r11 * py + cf.r12 * pz + cf.y)
            out[o + 2] = java.lang.Float.floatToRawIntBits(cf.r20 * px + cf.r21 * py + cf.r22 * pz + cf.z)
            out[o + 3] = java.lang.Float.floatToRawIntBits(if (st >= 8) m[k + 6] else 0f)
            out[o + 4] = java.lang.Float.floatToRawIntBits(if (st >= 8) m[k + 7] else 0f)
            val nx0 = m[k + 3] * ix; val ny0 = m[k + 4] * iy; val nz0 = m[k + 5] * iz
            var nx = cf.r00 * nx0 + cf.r01 * ny0 + cf.r02 * nz0
            var ny = cf.r10 * nx0 + cf.r11 * ny0 + cf.r12 * nz0
            var nz = cf.r20 * nx0 + cf.r21 * ny0 + cf.r22 * nz0
            val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f); nx /= nl; ny /= nl; nz /= nl
            out[o + 5] = b4(sb(nx), sb(ny), sb(nz), 0)
            out[o + 6] = col
            o += MESH_STRIDE_INTS
            k += st
        }
        return o
    }

    companion object {
        const val CELL = 64f
        const val STRIDE_INTS = 10
        const val STRIDE_BYTES = 40
        const val MESH_STRIDE_INTS = 7
        const val MESH_STRIDE_BYTES = 28
        /** Frames a part must stay unchanged before it's (re)batched. */
        const val COOL = 45
        /** Frames out of range before a chunk's buffers are freed (~15 s). */
        const val UNLOAD_FRAMES = 900
    }
}
