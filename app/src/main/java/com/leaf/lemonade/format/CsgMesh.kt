package com.leaf.lemonade.format

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The render mesh of a union made in Roblox Studio: the "CSGMDL" data kept in a PartOperation's
 * MeshData (or in the PartOperationAsset its AssetId points to). Versions 2 and 4 are a plain
 * vertex/index list scrambled with a fixed XOR key; version 5 scrambles only the header and packs
 * the vertex attributes in separate arrays with delta-coded indices.
 */
object CsgMesh {
    class CsgException(msg: String) : Exception(msg)

    /** Positions/normals (3 per vertex), colors (ARGB per vertex) and triangle indices (first LOD). */
    class Decoded(val pos: FloatArray, val nrm: FloatArray, val colors: IntArray, val indices: IntArray) {
        val vertexCount get() = pos.size / 3
    }

    private val KEY = intArrayOf(86, 46, 110, 88, 49, 32, 48, 4, 52, 105, 12, 119, 12, 1, 94, 0, 26, 96, 55, 105, 29, 82, 43, 7, 79, 36, 89, 101, 83, 4, 122)
    private val MAGIC = "CSGMDL".toByteArray(Charsets.ISO_8859_1)

    private fun xorByte(b: ByteArray, i: Int) = (b[i].toInt() xor KEY[i % KEY.size]).toByte()

    private fun startsWithMagic(b: ByteArray, scrambled: Boolean): Boolean {
        if (b.size < 10) return false
        for (i in MAGIC.indices) if ((if (scrambled) xorByte(b, i) else b[i]) != MAGIC[i]) return false
        return true
    }

    fun isCsgMesh(b: ByteArray) = startsWithMagic(b, true) || startsWithMagic(b, false)

    fun decode(raw: ByteArray): Decoded {
        val scrambled = when {
            startsWithMagic(raw, false) -> false
            startsWithMagic(raw, true) -> true
            else -> throw CsgException("not a union mesh")
        }
        val header = ByteArray(10) { if (scrambled) xorByte(raw, it) else raw[it] }
        val version = ByteBuffer.wrap(header, 6, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return when (version) {
            2, 3, 4 -> {
                val b = if (scrambled) ByteArray(raw.size) { xorByte(raw, it) } else raw
                decodeList(b, version)
            }
            5, 6 -> decodePacked(raw)
            else -> throw CsgException("union mesh version $version isn't supported")
        }
    }

    private fun le(b: ByteArray, at: Int) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).also { it.position(at) }

    private fun checkIndices(idx: IntArray, nv: Int) {
        for (i in idx) if (i < 0 || i >= nv) throw CsgException("index out of range")
    }

    private fun firstLod(all: IntArray, lods: IntArray?): IntArray {
        if (lods == null || lods.size < 2) return all
        val a = lods[0].coerceIn(0, all.size); val e = lods[1].coerceIn(a, all.size)
        if (e - a < 3) return all
        return all.copyOfRange(a, e - (e - a) % 3)
    }

    /** Versions 2-4: header, hash, then vertex records and a plain index list. */
    private fun decodeList(b: ByteArray, version: Int): Decoded {
        val bb = le(b, 42)
        val nv = bb.int
        val stride = bb.int
        if (nv < 0 || stride < 40 || 50L + nv.toLong() * stride > b.size) throw CsgException("bad vertex data")
        val pos = FloatArray(nv * 3); val nrm = FloatArray(nv * 3); val col = IntArray(nv)
        for (i in 0 until nv) {
            val s = 50 + i * stride
            bb.position(s)
            pos[i * 3] = bb.float; pos[i * 3 + 1] = bb.float; pos[i * 3 + 2] = bb.float
            nrm[i * 3] = bb.float; nrm[i * 3 + 1] = bb.float; nrm[i * 3 + 2] = bb.float
            val r = b[s + 24].toInt() and 0xFF; val g = b[s + 25].toInt() and 0xFF; val bl = b[s + 26].toInt() and 0xFF; val a = b[s + 27].toInt() and 0xFF
            col[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        }
        bb.position(50 + nv * stride)
        if (bb.remaining() < 4) throw CsgException("missing indices")
        val ni = bb.int
        if (ni < 0 || ni.toLong() * 4 > bb.remaining()) throw CsgException("bad index count")
        val idx = IntArray(ni) { bb.int }
        checkIndices(idx, nv)
        var lods: IntArray? = null
        if (version >= 4 && bb.remaining() >= 4) {
            val n = bb.int
            if (n in 1..64 && bb.remaining() >= n * 4) lods = IntArray(n) { bb.int }
        }
        return Decoded(pos, nrm, col, firstLod(idx, lods))
    }

    /** Version 5: separate attribute arrays, each prefixed by its vertex count. */
    private fun decodePacked(b: ByteArray): Decoded {
        val bb = le(b, 10)
        fun count(expect: Int): Int {
            val n = bb.short.toInt() and 0xFFFF
            if (expect >= 0 && n != expect) throw CsgException("inconsistent vertex count")
            return n
        }
        val nv = count(-1)
        if (bb.remaining() < nv * 12) throw CsgException("bad positions")
        val pos = FloatArray(nv * 3) { bb.float }
        count(nv); val nSize = bb.int
        if (nSize != nv * 6 || bb.remaining() < nSize) throw CsgException("bad normals")
        val nrm = FloatArray(nv * 3) { (bb.short.toInt() and 0xFFFF) / 32767f - 1f }
        count(nv)
        val col = IntArray(nv) {
            val r = bb.get().toInt() and 0xFF; val g = bb.get().toInt() and 0xFF; val bl = bb.get().toInt() and 0xFF; val a = bb.get().toInt() and 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or bl
        }
        count(nv); bb.position(bb.position() + nv)            // face ids
        count(nv); bb.position(bb.position() + nv * 8)        // texture coordinates
        count(nv); val tSize = bb.int; bb.position(bb.position() + tSize) // tangents
        val ni = bb.int
        val iSize = bb.int
        if (ni < 0 || iSize < 0 || iSize > bb.remaining()) throw CsgException("bad index data")
        val idx = decodeDeltaIndices(b, bb.position(), iSize, ni)
        checkIndices(idx, nv)
        bb.position(bb.position() + iSize)
        var lods: IntArray? = null
        if (bb.remaining() >= 4) {
            val n = bb.int
            if (n in 1..64 && bb.remaining() >= n * 4) lods = IntArray(n) { bb.int }
        }
        return Decoded(pos, nrm, col, firstLod(idx, lods))
    }

    /**
     * Indices as running deltas: one byte holds a 7-bit signed delta; a byte with the high bit set
     * starts a 3-byte big-endian 23-bit signed delta.
     */
    fun decodeDeltaIndices(b: ByteArray, start: Int, size: Int, count: Int): IntArray {
        val out = IntArray(count)
        var p = start
        val end = start + size
        var cur = 0
        for (i in 0 until count) {
            if (p >= end) throw CsgException("index data too short")
            val x = b[p++].toInt() and 0xFF
            val d = if (x < 0x80) {
                if (x >= 0x40) x - 0x80 else x
            } else {
                if (p + 2 > end) throw CsgException("index data too short")
                val v = ((x and 0x7F) shl 16) or ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
                p += 2
                if (v >= 0x400000) v - 0x800000 else v
            }
            cur += d
            out[i] = cur
        }
        if (p != end) throw CsgException("index data has ${end - p} extra bytes")
        return out
    }
}
