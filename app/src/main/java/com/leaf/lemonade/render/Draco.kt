package com.leaf.lemonade.render

/**
 * Decoder for Google's Draco geometry format, as used by Roblox's version 7 mesh files (their
 * COREMESH chunk version 2). It covers what those files contain: Draco 2.x bitstreams with the
 * sequential mesh encoding, rANS-coded integer attributes with delta prediction, quantized
 * positions / texture coordinates and octahedral normals. Edgebreaker connectivity (which Roblox
 * doesn't use) is rejected.
 */
object Draco {
    const val POSITION = 0
    const val NORMAL = 1
    const val COLOR = 2
    const val TEX_COORD = 3
    const val GENERIC = 4

    class DracoException(msg: String) : Exception(msg)

    class Attribute(val type: Int, val dataType: Int, val components: Int, val normalized: Boolean, val uniqueId: Long) {
        /** Decoded values, [components] per point. */
        var values: FloatArray = FloatArray(0)
    }

    class Mesh(val numPoints: Int, val faces: IntArray, val attributes: List<Attribute>) {
        fun attribute(type: Int) = attributes.firstOrNull { it.type == type }
    }

    fun isDraco(b: ByteArray, off: Int) =
        b.size >= off + 5 && b[off] == 'D'.code.toByte() && b[off + 1] == 'R'.code.toByte() && b[off + 2] == 'A'.code.toByte() &&
            b[off + 3] == 'C'.code.toByte() && b[off + 4] == 'O'.code.toByte()

    // ------------------------------------------------------------------ byte / bit readers

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        val remaining get() = end - pos
        fun need(n: Int) { if (n < 0 || pos + n > end) throw DracoException("unexpected end of data") }
        fun u8(): Int { need(1); return b[pos++].toInt() and 0xFF }
        fun i8(): Int { need(1); return b[pos++].toInt() }
        fun u16(): Int { need(2); val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
        fun i32(): Int {
            need(4)
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
        /** Unsigned LEB128 varint. */
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val v = u8()
                result = result or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw DracoException("bad varint")
            }
        }
        fun varintInt(): Int {
            val v = varint()
            if (v < 0 || v > Int.MAX_VALUE) throw DracoException("value out of range")
            return v.toInt()
        }
        fun skip(n: Int) { need(n); pos += n }
    }

    /** Draco's BitDecoder: least significant bit first. */
    private class BitReader(val b: ByteArray, val start: Int, val end: Int) {
        var bit = 0L
        fun get(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                val byte = start + (bit ushr 3).toInt()
                val x = if (byte < end) (b[byte].toInt() ushr (bit and 7).toInt()) and 1 else 0
                bit++
                v = v or (x shl i)
            }
            return v
        }
        fun bytesUsed() = ((bit + 7) / 8).toInt()
    }

    // ------------------------------------------------------------------ rANS entropy coding

    private class RAns(precisionBits: Int, probs: IntArray) {
        private val precision = 1 shl precisionBits
        private val lBase = precision.toLong() * 4
        private val lut = IntArray(precision)
        private val prob = probs.copyOf()
        private val cum = IntArray(probs.size)
        private var buf = ByteArray(0)
        private var start = 0
        private var offset = 0
        private var state = 0L

        init {
            var c = 0L
            var act = 0
            for (i in probs.indices) {
                cum[i] = c.toInt()
                c += probs[i].toLong() and 0xFFFFFFFFL
                if (c > precision) throw DracoException("bad probability table")
                for (j in act until c.toInt()) lut[j] = i
                act = c.toInt()
            }
            if (c != precision.toLong()) throw DracoException("bad probability table")
        }

        fun init(b: ByteArray, s: Int, len: Int) {
            if (len < 1) throw DracoException("empty rANS data")
            buf = b; start = s
            val last = b[s + len - 1].toInt() and 0xFF
            fun le(at: Int, n: Int): Long { var v = 0L; for (k in 0 until n) v = v or ((b[s + at + k].toLong() and 0xFF) shl (8 * k)); return v }
            when (last ushr 6) {
                0 -> { offset = len - 1; state = (last and 0x3F).toLong() }
                1 -> { if (len < 2) throw DracoException("bad rANS data"); offset = len - 2; state = le(len - 2, 2) and 0x3FFF }
                2 -> { if (len < 3) throw DracoException("bad rANS data"); offset = len - 3; state = le(len - 3, 3) and 0x3FFFFF }
                else -> { if (len < 4) throw DracoException("bad rANS data"); offset = len - 4; state = le(len - 4, 4) and 0x3FFFFFFF }
            }
            state += lBase
            if (state >= lBase * 256) throw DracoException("bad rANS state")
        }

        fun read(): Int {
            while (state < lBase && offset > 0) state = state * 256 + (buf[start + --offset].toLong() and 0xFF)
            val quo = state / precision
            val rem = (state % precision).toInt()
            val sym = lut[rem]
            state = quo * (prob[sym].toLong() and 0xFFFFFFFFL) + rem - cum[sym]
            return sym
        }
    }

    private class SymbolDecoder(val numSymbols: Int, val ans: RAns?) {
        fun start(r: Reader) {
            val bytes = r.varint()
            if (bytes < 0 || bytes > r.remaining) throw DracoException("bad rANS size")
            val n = bytes.toInt()
            (ans ?: throw DracoException("no symbols")).init(r.b, r.pos, n)
            r.pos += n
        }
        fun read() = ans!!.read()
    }

    private fun precisionFor(bits: Int): Int = ((3 * bits) / 2).coerceIn(12, 20)

    private fun createSymbolDecoder(r: Reader, uniqueSymbolBits: Int): SymbolDecoder {
        val num = r.varintInt()
        if (num / 64 > r.remaining) throw DracoException("too many symbols")
        if (num == 0) return SymbolDecoder(0, null)
        val probs = IntArray(num)
        var i = 0
        while (i < num) {
            val data = r.u8()
            val token = data and 3
            if (token == 3) {
                val run = data ushr 2
                if (i + run >= num) throw DracoException("bad probability run")
                i += run + 1 // those entries stay zero
            } else {
                var p = data ushr 2
                for (k in 0 until token) p = p or (r.u8() shl (8 * (k + 1) - 2))
                probs[i] = p
                i++
            }
        }
        return SymbolDecoder(num, RAns(precisionFor(uniqueSymbolBits), probs))
    }

    /** Draco's DecodeSymbols: [n] unsigned values (stored in Int bits). */
    private fun decodeSymbols(n: Int, comps: Int, r: Reader): IntArray {
        val out = IntArray(n)
        if (n == 0) return out
        when (r.u8()) {
            0 -> { // tagged: an rANS-coded bit length per entry, then raw bits
                val tags = createSymbolDecoder(r, 5)
                tags.start(r)
                if (tags.numSymbols == 0) throw DracoException("no tag symbols")
                val bits = BitReader(r.b, r.pos, r.end)
                var id = 0
                var i = 0
                while (i < n) {
                    val len = tags.read()
                    if (len > 32) throw DracoException("bad tag")
                    for (j in 0 until comps) { if (id >= n) break; out[id++] = bits.get(len) }
                    i += comps
                }
                r.pos += bits.bytesUsed()
                if (r.pos > r.end) throw DracoException("unexpected end of data")
            }
            1 -> { // raw rANS symbols
                val maxBits = r.u8()
                if (maxBits < 1 || maxBits > 18) throw DracoException("bad symbol bit length $maxBits")
                val dec = createSymbolDecoder(r, maxBits)
                if (dec.numSymbols == 0) throw DracoException("no symbols")
                dec.start(r)
                for (i in 0 until n) out[i] = dec.read()
            }
            else -> throw DracoException("unknown symbol coding")
        }
        return out
    }

    // ------------------------------------------------------------------ octahedral normals

    private class Octa(bits: Int) {
        val maxQ: Int
        val maxValue: Int
        val center: Int
        val scale: Float

        init {
            if (bits < 2 || bits > 30) throw DracoException("bad normal quantization")
            maxQ = (1 shl bits) - 1
            maxValue = maxQ - 1
            center = maxValue / 2
            scale = 2f / maxValue
        }

        fun isInDiamond(s: Int, t: Int) = (kotlin.math.abs(s).toLong() + kotlin.math.abs(t).toLong()) <= center

        /** Mirrors a point across the nearest diamond edge (in place, centered coordinates). */
        fun invertDiamond(p: IntArray) {
            var s = p[0]; var t = p[1]
            val signS: Int; val signT: Int
            if (s >= 0 && t >= 0) { signS = 1; signT = 1 }
            else if (s <= 0 && t <= 0) { signS = -1; signT = -1 }
            else { signS = if (s > 0) 1 else -1; signT = if (t > 0) 1 else -1 }
            val cs = signS * center; val ct = signT * center
            var us = s + s - cs
            var ut = t + t - ct
            if (signS * signT >= 0) { val tmp = us; us = -ut; ut = -tmp } else { val tmp = us; us = ut; ut = tmp }
            us += cs; ut += ct
            s = us / 2; t = ut / 2
            p[0] = s; p[1] = t
        }

        fun modMax(x: Int): Int = if (x > center) x - maxQ else if (x < -center) x + maxQ else x

        fun toUnit(si: Int, ti: Int, out: FloatArray, o: Int) {
            var y = si * scale - 1f
            var z = ti * scale - 1f
            val x = 1f - kotlin.math.abs(y) - kotlin.math.abs(z)
            val xo = if (-x < 0f) 0f else -x
            y += if (y < 0f) xo else -xo
            z += if (z < 0f) xo else -xo
            val n2 = x * x + y * y + z * z
            if (n2 < 1e-6f) { out[o] = 0f; out[o + 1] = 0f; out[o + 2] = 0f }
            else { val d = 1f / kotlin.math.sqrt(n2); out[o] = x * d; out[o + 1] = y * d; out[o + 2] = z * d }
        }
    }

    private fun rotationCount(x: Int, y: Int): Int = when {
        x == 0 -> if (y == 0) 0 else if (y > 0) 3 else 1
        x > 0 -> if (y >= 0) 2 else 1
        else -> if (y <= 0) 0 else 3
    }

    private fun rotate(p: IntArray, count: Int) {
        val x = p[0]; val y = p[1]
        when (count) {
            1 -> { p[0] = y; p[1] = -x }
            2 -> { p[0] = -x; p[1] = -y }
            3 -> { p[0] = -y; p[1] = x }
        }
    }

    // ------------------------------------------------------------------ attributes

    private const val KIND_GENERIC = 0
    private const val KIND_INTEGER = 1
    private const val KIND_QUANTIZED = 2
    private const val KIND_NORMALS = 3

    private class AttDecoder(val att: Attribute, val kind: Int) {
        var portable = IntArray(0)
        var minValues = FloatArray(0)
        var range = 0f
        var qBits = 0
        var octBits = 0
    }

    private fun typeLength(dt: Int) = when (dt) { 1, 2, 11 -> 1; 3, 4 -> 2; 5, 6, 9 -> 4; 7, 8, 10 -> 8; else -> throw DracoException("bad data type $dt") }

    private fun readTyped(r: Reader, dt: Int): Float = when (dt) {
        1 -> r.i8().toFloat()
        2, 11 -> r.u8().toFloat()
        3 -> r.u16().toShort().toFloat()
        4 -> r.u16().toFloat()
        5 -> r.i32().toFloat()
        6 -> (r.i32().toLong() and 0xFFFFFFFFL).toFloat()
        7, 8 -> { val lo = r.i32().toLong() and 0xFFFFFFFFL; val hi = r.i32().toLong(); ((hi shl 32) or lo).toFloat() }
        9 -> r.f32()
        10 -> { val lo = r.i32().toLong() and 0xFFFFFFFFL; val hi = r.i32().toLong(); java.lang.Double.longBitsToDouble((hi shl 32) or lo).toFloat() }
        else -> throw DracoException("bad data type $dt")
    }

    private fun decodePortable(d: AttDecoder, n: Int, r: Reader) {
        val a = d.att
        if (d.kind == KIND_GENERIC) {
            val v = FloatArray(n * a.components)
            for (i in v.indices) v[i] = readTyped(r, a.dataType)
            a.values = v
            return
        }
        val comps = if (d.kind == KIND_NORMALS) 2 else a.components
        val method = r.i8()
        var transform = -1
        if (method != -2) {
            if (method < -2 || method > 6) throw DracoException("bad prediction method $method")
            transform = r.i8()
            if (transform < -1 || transform > 3) throw DracoException("bad prediction transform $transform")
        }
        // Integer data predicts with the wrap transform; normals with an octahedron transform.
        // Any other combination means "no prediction" (Draco does the same).
        val predicted = method != -2 && (if (d.kind == KIND_NORMALS) transform == 2 || transform == 3 else transform == 1)
        val total = n * comps
        val values: IntArray
        if (r.u8() > 0) values = decodeSymbols(total, comps, r)
        else {
            val nb = r.u8()
            if (nb < 1 || nb > 4) throw DracoException("bad raw integer size")
            r.need(nb * total)
            values = IntArray(total)
            for (i in 0 until total) { var v = 0; for (k in 0 until nb) v = v or (r.u8() shl (8 * k)); values[i] = v }
        }
        val positive = predicted && d.kind == KIND_NORMALS
        if (!positive) for (i in values.indices) { val u = values[i]; values[i] = (u ushr 1) xor -(u and 1) }
        if (predicted) {
            if (d.kind == KIND_NORMALS) {
                val maxQ = r.i32()
                if (transform == 3) r.i32() // center value (unused)
                if (maxQ % 2 == 0 || maxQ <= 0) throw DracoException("bad normal transform data")
                val bits = 32 - Integer.numberOfLeadingZeros(maxQ)
                val oct = Octa(bits)
                val pred = IntArray(2)
                val orig = IntArray(2)
                for (i in 0 until total step 2) {
                    if (i == 0) { pred[0] = 0; pred[1] = 0 } else { pred[0] = values[i - 2]; pred[1] = values[i - 1] }
                    pred[0] -= oct.center; pred[1] -= oct.center
                    val inDiamond = oct.isInDiamond(pred[0], pred[1])
                    if (!inDiamond) oct.invertDiamond(pred)
                    if (transform == 3) {
                        val bottomLeft = (pred[0] == 0 && pred[1] == 0) || (pred[0] < 0 && pred[1] <= 0)
                        val rot = rotationCount(pred[0], pred[1])
                        if (!bottomLeft) rotate(pred, rot)
                        orig[0] = oct.modMax(pred[0] + values[i]); orig[1] = oct.modMax(pred[1] + values[i + 1])
                        if (!bottomLeft) rotate(orig, (4 - rot) % 4)
                    } else {
                        orig[0] = oct.modMax(pred[0] + values[i]); orig[1] = oct.modMax(pred[1] + values[i + 1])
                    }
                    if (!inDiamond) oct.invertDiamond(orig)
                    values[i] = orig[0] + oct.center; values[i + 1] = orig[1] + oct.center
                }
            } else {
                val mn = r.i32(); val mx = r.i32()
                if (mn > mx) throw DracoException("bad wrap transform data")
                val dif = mx.toLong() - mn.toLong()
                if (dif >= Int.MAX_VALUE) throw DracoException("bad wrap transform data")
                val maxDif = (1 + dif).toInt()
                for (i in 0 until total) {
                    var p = if (i < comps) 0 else values[i - comps]
                    if (p > mx) p = mx else if (p < mn) p = mn
                    var o = p + values[i]
                    if (o > mx) o -= maxDif else if (o < mn) o += maxDif
                    values[i] = o
                }
            }
        }
        d.portable = values
    }

    private fun decodeTransformData(d: AttDecoder, r: Reader) {
        when (d.kind) {
            KIND_QUANTIZED -> {
                d.minValues = FloatArray(d.att.components) { r.f32() }
                d.range = r.f32()
                d.qBits = r.u8()
                if (d.qBits < 1 || d.qBits > 30) throw DracoException("bad quantization")
            }
            KIND_NORMALS -> d.octBits = r.u8()
        }
    }

    private fun finish(d: AttDecoder, n: Int) {
        val a = d.att
        when (d.kind) {
            KIND_INTEGER -> a.values = FloatArray(d.portable.size) { d.portable[it].toFloat() }
            KIND_QUANTIZED -> {
                val maxQ = (1 shl d.qBits) - 1
                val delta = d.range / maxQ
                val c = a.components
                a.values = FloatArray(n * c) { i -> d.portable[i] * delta + d.minValues[i % c] }
            }
            KIND_NORMALS -> {
                val oct = Octa(d.octBits)
                val out = FloatArray(n * 3)
                for (i in 0 until n) oct.toUnit(d.portable[i * 2], d.portable[i * 2 + 1], out, i * 3)
                a.values = out
            }
        }
    }

    // ------------------------------------------------------------------ metadata (skipped)

    private fun skipMetadata(r: Reader, depth: Int = 0) {
        if (depth > 32) throw DracoException("metadata too deep")
        val entries = r.varintInt()
        repeat(entries) { r.skip(r.u8()); r.skip(r.varintInt()) }
        val subs = r.varintInt()
        repeat(subs) { r.skip(r.u8()); skipMetadata(r, depth + 1) }
    }

    // ------------------------------------------------------------------ decoding

    fun decode(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): Mesh {
        val r = Reader(bytes, off, off + len)
        if (!isDraco(bytes, off)) throw DracoException("not Draco data")
        r.skip(5)
        val major = r.u8(); val minor = r.u8()
        val version = major * 256 + minor
        if (major != 2) throw DracoException("Draco version $major.$minor isn't supported")
        if (r.u8() != 1) throw DracoException("not a triangle mesh")
        val method = r.u8()
        if (method != 0) throw DracoException("Draco edgebreaker meshes aren't supported")
        val flags = r.u16()
        if (flags and 0x8000 != 0) {
            val attMeta = r.varintInt()
            repeat(attMeta) { r.varint(); skipMetadata(r) }
            skipMetadata(r)
        }
        // Connectivity (sequential).
        val numFaces: Int; val numPoints: Int
        if (version < 0x202) { numFaces = r.i32(); numPoints = r.i32() } else { numFaces = r.varintInt(); numPoints = r.varintInt() }
        if (numFaces < 0 || numFaces > 20_000_000) throw DracoException("bad face count")
        if (numPoints < 0) throw DracoException("bad point count")
        val faces = IntArray(numFaces * 3)
        if (r.u8() == 0) {
            val enc = decodeSymbols(numFaces * 3, 1, r)
            var last = 0
            for (i in enc.indices) {
                val e = enc[i]
                var diff = e ushr 1
                if (e and 1 != 0) { if (diff > last) throw DracoException("bad index"); diff = -diff }
                last += diff
                faces[i] = last
            }
        } else {
            for (i in faces.indices) faces[i] = when {
                numPoints < 256 -> r.u8()
                numPoints < 65536 -> r.u16()
                numPoints < (1 shl 21) && version >= 0x202 -> r.varintInt()
                else -> r.i32()
            }
        }
        for (f in faces) if (f < 0 || f >= numPoints) throw DracoException("face index out of range")
        // Attribute descriptors.
        val groups = ArrayList<List<AttDecoder>>()
        val nDec = r.u8()
        for (g in 0 until nDec) {
            val nAtt = if (version < 0x200) r.i32() else r.varintInt()
            if (nAtt <= 0 || nAtt > 5 * r.remaining) throw DracoException("bad attribute count")
            val atts = ArrayList<Attribute>()
            for (k in 0 until nAtt) {
                val type = r.u8(); val dt = r.u8(); val comps = r.u8(); val norm = r.u8()
                if (dt == 0 || dt > 11 || comps == 0) throw DracoException("bad attribute")
                val uid = if (version < 0x103) r.u16().toLong() else r.varint()
                atts.add(Attribute(type, dt, comps, norm > 0, uid))
            }
            val decs = atts.map { a ->
                val kind = r.u8()
                if (kind > 3) throw DracoException("unknown attribute decoder $kind")
                if ((kind == KIND_QUANTIZED || kind == KIND_NORMALS) && a.dataType != 9) throw DracoException("bad attribute type")
                if (kind == KIND_NORMALS && a.components != 3) throw DracoException("bad normal attribute")
                AttDecoder(a, kind)
            }
            groups.add(decs)
        }
        // Attribute values.
        for (decs in groups) {
            for (d in decs) decodePortable(d, numPoints, r)
            for (d in decs) decodeTransformData(d, r)
            for (d in decs) finish(d, numPoints)
        }
        return Mesh(numPoints, faces, groups.flatten().map { it.att })
    }
}
