package com.leaf.lemonade.render

import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.net.AssetFetcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A decoded Roblox mesh: interleaved triangles of position(3) + normal(3) + uv(2), plus its
 * bounding box (in mesh units). UV v follows Roblox (0 = top of the texture).
 */
class MeshData(val verts: FloatArray, val min: FloatArray, val max: FloatArray) {
    val triangles get() = verts.size / 24
    fun size(i: Int) = max[i] - min[i]
    fun center(i: Int) = (max[i] + min[i]) / 2f
}

/**
 * Reads Roblox's mesh file formats: version 1.00/1.01 (text), 2.00, 3.00/3.01, 4.00/4.01,
 * 5.00 and the chunked 6.00/7.00 files (plain or Draco-compressed COREMESH, see [Draco]).
 */
object RbxMesh {
    class UnsupportedMesh(msg: String) : Exception(msg)

    fun parse(bytes: ByteArray): MeshData {
        val head = String(bytes, 0, minOf(bytes.size, 13), Charsets.ISO_8859_1)
        if (!head.startsWith("version ")) throw UnsupportedMesh("not a mesh file")
        val ver = head.substring(8, 12)
        val nl = bytes.indexOf('\n'.code.toByte()).let { if (it < 0) throw UnsupportedMesh("bad header") else it + 1 }
        return when {
            ver.startsWith("1.") -> parseV1(String(bytes, Charsets.ISO_8859_1), ver == "1.00")
            ver == "2.00" -> parseV2(bytes, nl)
            ver.startsWith("3.") -> parseV3(bytes, nl)
            ver.startsWith("4.") || ver.startsWith("5.") -> parseV4(bytes, nl)
            ver.startsWith("6.") || ver.startsWith("7.") -> parseChunked(bytes, nl)
            else -> throw UnsupportedMesh("mesh version $ver")
        }
    }

    private fun build(pos: FloatArray, nrm: FloatArray, uv: FloatArray, faces: IntArray, f0: Int, f1: Int): MeshData {
        val n = pos.size / 3
        val out = FloatArray((f1 - f0) * 24)
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var o = 0
        for (f in f0 until f1) for (k in 0 until 3) {
            val v = faces[f * 3 + k]
            if (v < 0 || v >= n) { o += 8; continue }
            for (c in 0 until 3) {
                val p = pos[v * 3 + c]
                out[o + c] = p
                if (p < mn[c]) mn[c] = p
                if (p > mx[c]) mx[c] = p
            }
            out[o + 3] = nrm[v * 3]; out[o + 4] = nrm[v * 3 + 1]; out[o + 5] = nrm[v * 3 + 2]
            out[o + 6] = uv[v * 2]; out[o + 7] = uv[v * 2 + 1]
            o += 8
        }
        var t = 0
        while (t + 24 <= o) {
            for (k in 0 until 3) {
                val b = t + k * 8
                if (out[b + 3] * out[b + 3] + out[b + 4] * out[b + 4] + out[b + 5] * out[b + 5] < 1e-6f) {
                    val ax = out[t + 8] - out[t]; val ay = out[t + 9] - out[t + 1]; val az = out[t + 10] - out[t + 2]
                    val bx = out[t + 16] - out[t]; val by = out[t + 17] - out[t + 1]; val bz = out[t + 18] - out[t + 2]
                    var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
                    val l = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
                    if (l > 1e-8f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 1f; nz = 0f }
                    out[b + 3] = nx; out[b + 4] = ny; out[b + 5] = nz
                }
            }
            t += 24
        }
        if (mn[0] > mx[0]) { mn.fill(0f); mx.fill(0f) }
        return MeshData(out, mn, mx)
    }

    private fun parseV1(text: String, scaleHalf: Boolean): MeshData {
        val lines = text.split('\n')
        val faces = lines.getOrNull(1)?.trim()?.toIntOrNull() ?: throw UnsupportedMesh("bad v1 mesh")
        val nums = Regex("""\[([^\]]*)]""").findAll(lines.drop(2).joinToString(" ")).map { m -> m.groupValues[1].split(',').map { it.trim().toFloatOrNull() ?: 0f } }.toList()
        val count = minOf(faces * 3, nums.size / 3)
        val pos = FloatArray(count * 3); val nrm = FloatArray(count * 3); val uv = FloatArray(count * 2)
        val s = if (scaleHalf) 0.5f else 1f
        for (i in 0 until count) {
            val p = nums[i * 3]; val nn = nums[i * 3 + 1]; val t = nums[i * 3 + 2]
            for (c in 0 until 3) { pos[i * 3 + c] = p.getOrElse(c) { 0f } * s; nrm[i * 3 + c] = nn.getOrElse(c) { 0f } }
            uv[i * 2] = t.getOrElse(0) { 0f }; uv[i * 2 + 1] = 1f - t.getOrElse(1) { 0f }
        }
        return build(pos, nrm, uv, IntArray(count) { it }, 0, count / 3)
    }

    private fun buf(b: ByteArray, at: Int) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).also { it.position(at) }

    /** Vertex normals averaged from the faces around each vertex (for meshes stored without normals). */
    fun smoothNormals(pos: FloatArray, faces: IntArray): FloatArray {
        val n = FloatArray(pos.size)
        val nv = pos.size / 3
        var f = 0
        while (f + 2 < faces.size) {
            val a = faces[f]; val b = faces[f + 1]; val c = faces[f + 2]
            f += 3
            if (a !in 0 until nv || b !in 0 until nv || c !in 0 until nv) continue
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { n[v * 3] += nx; n[v * 3 + 1] += ny; n[v * 3 + 2] += nz }
        }
        for (v in 0 until nv) {
            val l = kotlin.math.sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2])
            if (l > 1e-12f) { n[v * 3] /= l; n[v * 3 + 1] /= l; n[v * 3 + 2] /= l } else n[v * 3 + 1] = 1f
        }
        return n
    }

    private class Verts(val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray)

    private fun readVerts(bb: ByteBuffer, n: Int, stride: Int): Verts {
        val pos = FloatArray(n * 3); val nrm = FloatArray(n * 3); val uv = FloatArray(n * 2)
        for (i in 0 until n) {
            val start = bb.position()
            pos[i * 3] = bb.float; pos[i * 3 + 1] = bb.float; pos[i * 3 + 2] = bb.float
            nrm[i * 3] = bb.float; nrm[i * 3 + 1] = bb.float; nrm[i * 3 + 2] = bb.float
            uv[i * 2] = bb.float; uv[i * 2 + 1] = bb.float
            bb.position(start + stride)
        }
        return Verts(pos, nrm, uv)
    }

    private fun readFaces(bb: ByteBuffer, n: Int, stride: Int = 12): IntArray {
        val f = IntArray(n * 3)
        for (i in 0 until n) { val s = bb.position(); f[i * 3] = bb.int; f[i * 3 + 1] = bb.int; f[i * 3 + 2] = bb.int; bb.position(s + stride) }
        return f
    }

    private fun parseV2(b: ByteArray, at: Int): MeshData {
        val bb = buf(b, at)
        val hs = bb.short.toInt() and 0xFFFF
        val vs = bb.get().toInt() and 0xFF
        val fs = bb.get().toInt() and 0xFF
        val nv = bb.int; val nf = bb.int
        bb.position(at + hs)
        val v = readVerts(bb, nv, vs)
        val f = readFaces(bb, nf, fs)
        return build(v.pos, v.nrm, v.uv, f, 0, nf)
    }

    private fun parseV3(b: ByteArray, at: Int): MeshData {
        val bb = buf(b, at)
        val hs = bb.short.toInt() and 0xFFFF
        val vs = bb.get().toInt() and 0xFF
        val fs = bb.get().toInt() and 0xFF
        bb.short // sizeof LOD offset
        val nl = bb.short.toInt() and 0xFFFF
        val nv = bb.int; val nf = bb.int
        bb.position(at + hs)
        val v = readVerts(bb, nv, vs)
        val f = readFaces(bb, nf, fs)
        val lods = IntArray(nl) { bb.int }
        val end = if (nl >= 2) lods[1].coerceIn(1, nf) else nf
        return build(v.pos, v.nrm, v.uv, f, if (nl >= 2) lods[0].coerceIn(0, end) else 0, end)
    }

    private fun parseV4(b: ByteArray, at: Int): MeshData {
        val bb = buf(b, at)
        val hs = bb.short.toInt() and 0xFFFF
        bb.short // lod type
        val nv = bb.int; val nf = bb.int
        val nl = bb.short.toInt() and 0xFFFF
        val nBones = bb.short.toInt() and 0xFFFF
        bb.position(at + hs)
        val v = readVerts(bb, nv, 40)
        if (nBones > 0) bb.position(bb.position() + nv * 8)
        val f = readFaces(bb, nf)
        val lods = IntArray(nl) { bb.int }
        val end = if (nl >= 2) lods[1].coerceIn(1, nf) else nf
        return build(v.pos, v.nrm, v.uv, f, if (nl >= 2) lods[0].coerceIn(0, end) else 0, end)
    }

    private fun parseChunked(b: ByteArray, at: Int): MeshData {
        val bb = buf(b, at)
        var core: Verts? = null; var faces: IntArray? = null; var lods: IntArray? = null
        while (bb.remaining() >= 16) {
            val nameBytes = ByteArray(8); bb.get(nameBytes)
            val name = String(nameBytes, Charsets.ISO_8859_1).trimEnd('\u0000')
            val cver = bb.int
            val size = bb.int
            val start = bb.position()
            if (size < 0 || start + size > b.size) break
            when (name) {
                "COREMESH" -> when (cver) {
                    1 -> {
                        val nv = bb.int
                        core = readVerts(bb, nv, 40)
                        val nf = bb.int
                        faces = readFaces(bb, nf)
                    }
                    2 -> {
                        // Draco-compressed geometry (what Roblox serves for most meshes now).
                        val len = bb.int
                        val m = try { Draco.decode(b, bb.position(), minOf(len, size - 4)) } catch (e: Draco.DracoException) { throw UnsupportedMesh("Draco: ${e.message}") }
                        val n = m.numPoints
                        val pos = m.attribute(Draco.POSITION)?.takeIf { it.components == 3 }?.values ?: throw UnsupportedMesh("Draco mesh without positions")
                        val nrm = m.attribute(Draco.NORMAL)?.takeIf { it.components == 3 }?.values ?: smoothNormals(pos, m.faces)
                        val uvAtt = m.attribute(Draco.TEX_COORD)
                        val uv = when {
                            uvAtt == null || uvAtt.components < 2 -> FloatArray(n * 2)
                            uvAtt.components == 2 -> uvAtt.values
                            else -> FloatArray(n * 2) { i -> uvAtt.values[(i / 2) * uvAtt.components + i % 2] }
                        }
                        core = Verts(pos, nrm, uv)
                        faces = m.faces
                    }
                    else -> throw UnsupportedMesh("mesh chunk version $cver")
                }
                "LODS" -> {
                    bb.short; bb.get()
                    val n = bb.int
                    lods = IntArray(n) { bb.int }
                }
            }
            bb.position(start + size)
        }
        val v = core ?: throw UnsupportedMesh("mesh without geometry")
        val f = faces ?: IntArray(0)
        val nf = f.size / 3
        val l = lods
        val end = if (l != null && l.size >= 2) l[1].coerceIn(1, nf) else nf
        return build(v.pos, v.nrm, v.uv, f, if (l != null && l.size >= 2) l[0].coerceIn(0, end) else 0, end)
    }
}

/** Downloads and decodes meshes in the background; [get] never blocks. */
object MeshLoader {
    private val ready = ConcurrentHashMap<Long, MeshData>()
    private val bad = ConcurrentHashMap.newKeySet<Long>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val pool = Executors.newSingleThreadExecutor()
    val listeners = CopyOnWriteArrayList<(Long) -> Unit>()
    @Volatile var epoch = 0
        private set

    fun get(id: Long): MeshData? {
        ready[id]?.let { return it }
        if (id in bad) return null
        if (!pending.add(id)) return null
        val bytes = AssetFetcher.request(id)
        if (bytes == null) {
            // Not cached: AssetFetcher is downloading it; try again once it has.
            pending.remove(id)
            if (!waiting) {
                waiting = true
                AssetFetcher.listeners.add { done -> if (!ready.containsKey(done) && !bad.contains(done)) epoch++ }
            }
            return null
        }
        pool.execute {
            try {
                ready[id] = try { RbxMesh.parse(bytes) } catch (e: Exception) { meshInsideModel(id, bytes) ?: throw e }
            } catch (e: Exception) {
                bad.add(id)
                if (e !is RbxMesh.UnsupportedMesh || !e.message.orEmpty().startsWith("not a")) OutputLog.warn("Mesh $id: ${e.message ?: e}")
            } finally {
                pending.remove(id)
                epoch++
                listeners.forEach { it(id) }
            }
        }
        return null
    }

    @Volatile private var waiting = false

    fun isBad(id: Long) = id in bad

    /**
     * A MeshPart asset (what the Creator Store's Meshes category holds) is a model file wrapping a
     * MeshPart; a MeshId pointing at one means "that MeshPart's mesh". Follows it (one level).
     */
    private fun meshInsideModel(id: Long, bytes: ByteArray): MeshData? {
        val doc = try { com.leaf.lemonade.format.RbxFile.read(bytes) } catch (_: Exception) { return null }
        for (r in doc.roots) for (i in listOf(r) + r.descendants()) {
            val inner = AssetFetcher.idOf(com.leaf.lemonade.model.PartData.meshContent(i)) ?: continue
            if (inner == id) continue
            return try { RbxMesh.parse(AssetFetcher.load(inner)) } catch (_: Exception) { null }
        }
        return null
    }
}
