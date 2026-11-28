package com.leaf.lemonade.format

import com.leaf.lemonade.model.*
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.IdentityHashMap

class RbxDocument(val roots: List<Instance>, val meta: Map<String, String>, val warnings: List<String>)

class RbxFormatException(msg: String) : Exception(msg)

object RbxFile {
    fun isBinary(b: ByteArray) = b.size > 14 && String(b, 0, 8, Charsets.ISO_8859_1) == "<roblox!"
    fun isXml(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 200), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\n', '\r', '\t', 'ï', '»', '¿')
        return head.startsWith("<roblox") || head.startsWith("<?xml")
    }

    /** Files saved straight from assetdelivery (e.g. "asset.bin") may still be gzip-compressed. */
    fun unwrap(b: ByteArray): ByteArray =
        if (b.size > 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte()) java.util.zip.GZIPInputStream(b.inputStream()).use { it.readBytes() } else b

    fun read(b0: ByteArray): RbxDocument {
        val b = unwrap(b0)
        val doc = when {
            isBinary(b) -> RbxBinaryReader(b).read()
            isXml(b) -> RbxXmlReader.read(b)
            else -> throw RbxFormatException("Not a Roblox place or model file")
        }
        normalize(doc)
        return doc
    }

    /**
     * MaterialService.Use2022Materials is saved by Roblox as "Use2022MaterialsXml"; older builds
     * of this app wrote the plain name. Keep exactly one (the file's value wins) so the
     * Properties panel shows a single Use2022Materials that the renderer follows.
     */
    private fun normalize(doc: RbxDocument) {
        for (r in doc.roots) {
            if (r.className != "MaterialService") continue
            val plain = r.props["Use2022Materials"]
            if (r.props.containsKey("Use2022Materials")) {
                r.props.remove("Use2022Materials"); r.propTypes.remove("Use2022Materials")
                if (!r.props.containsKey("Use2022MaterialsXml") && plain is Boolean) {
                    r.props["Use2022MaterialsXml"] = plain; r.propTypes["Use2022MaterialsXml"] = PropType.BOOL
                }
            }
        }
    }

    fun isPlace(doc: RbxDocument) = doc.roots.any { it.className == "Workspace" }
}

// ---------------------------------------------------------------------------------------------
// Low level helpers

internal class ByteReader(val buf: ByteArray, var pos: Int = 0, val end: Int = buf.size) {
    fun remaining() = end - pos
    private fun need(n: Int) { if (pos + n > end) throw RbxFormatException("Unexpected end of chunk") }
    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }
    fun u16(): Int { need(2); val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
    fun i32(): Int {
        need(4)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4; return v
    }
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
    fun i64(): Long { val lo = i32().toLong() and 0xFFFFFFFFL; val hi = i32().toLong(); return (hi shl 32) or lo }
    fun f64(): Double = java.lang.Double.longBitsToDouble(i64())
    fun bytes(n: Int): ByteArray { need(n); val r = buf.copyOfRange(pos, pos + n); pos += n; return r }
    fun stringBytes(): ByteArray { val n = i32(); if (n < 0) throw RbxFormatException("Bad string length"); return bytes(n) }
    fun string(): String = String(stringBytes(), Charsets.UTF_8)

    /** De-interleave n values of `size` bytes each; returns big-endian value bytes laid out contiguously. */
    fun interleaved(n: Int, size: Int): ByteArray {
        need(n * size)
        val out = ByteArray(n * size)
        for (b in 0 until size) for (i in 0 until n) out[i * size + b] = buf[pos + b * n + i]
        pos += n * size
        return out
    }

    fun u32Array(n: Int): IntArray {
        val d = interleaved(n, 4)
        return IntArray(n) { i ->
            ((d[i * 4].toInt() and 0xFF) shl 24) or ((d[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((d[i * 4 + 2].toInt() and 0xFF) shl 8) or (d[i * 4 + 3].toInt() and 0xFF)
        }
    }

    fun i32Array(n: Int): IntArray = u32Array(n).also { a -> for (i in a.indices) { val v = a[i]; a[i] = (v ushr 1) xor -(v and 1) } }
    fun f32Array(n: Int): FloatArray {
        val a = u32Array(n)
        return FloatArray(n) { i -> val v = a[i]; java.lang.Float.intBitsToFloat((v ushr 1) or (v shl 31)) }
    }
    fun refArray(n: Int): IntArray {
        val a = i32Array(n)
        for (i in 1 until n) a[i] += a[i - 1]
        return a
    }
    fun i64Array(n: Int): LongArray {
        val d = interleaved(n, 8)
        return LongArray(n) { i ->
            var v = 0L
            for (b in 0 until 8) v = (v shl 8) or (d[i * 8 + b].toLong() and 0xFF)
            (v ushr 1) xor -(v and 1)
        }
    }
}

internal class ByteWriter {
    val out = ByteArrayOutputStream()
    fun u8(v: Int) = out.write(v and 0xFF)
    fun u16(v: Int) { u8(v); u8(v shr 8) }
    fun i32(v: Int) { u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24) }
    fun f32(v: Float) = i32(java.lang.Float.floatToRawIntBits(v))
    fun i64(v: Long) { i32(v.toInt()); i32((v ushr 32).toInt()) }
    fun f64(v: Double) = i64(java.lang.Double.doubleToRawLongBits(v))
    fun bytes(b: ByteArray) = out.write(b)
    fun string(b: ByteArray) { i32(b.size); bytes(b) }
    fun string(s: String) = string(s.toByteArray(Charsets.UTF_8))

    /** Input: n values of `size` big-endian bytes contiguous. */
    fun interleaved(d: ByteArray, n: Int, size: Int) {
        val o = ByteArray(n * size)
        for (b in 0 until size) for (i in 0 until n) o[b * n + i] = d[i * size + b]
        out.write(o)
    }

    fun u32Array(a: IntArray) {
        val d = ByteArray(a.size * 4)
        for (i in a.indices) {
            val v = a[i]
            d[i * 4] = (v ushr 24).toByte(); d[i * 4 + 1] = (v ushr 16).toByte(); d[i * 4 + 2] = (v ushr 8).toByte(); d[i * 4 + 3] = v.toByte()
        }
        interleaved(d, a.size, 4)
    }
    fun i32Array(a: IntArray) = u32Array(IntArray(a.size) { (a[it] shl 1) xor (a[it] shr 31) })
    fun f32Array(a: FloatArray) = u32Array(IntArray(a.size) { val b = java.lang.Float.floatToRawIntBits(a[it]); (b shl 1) or (b ushr 31) })
    fun refArray(a: IntArray) {
        val d = IntArray(a.size)
        var prev = 0
        for (i in a.indices) { d[i] = a[i] - prev; prev = a[i] }
        i32Array(d)
    }
    fun i64Array(a: LongArray) {
        val d = ByteArray(a.size * 8)
        for (i in a.indices) {
            val v = (a[i] shl 1) xor (a[i] shr 63)
            for (b in 0 until 8) d[i * 8 + b] = (v ushr (56 - 8 * b)).toByte()
        }
        interleaved(d, a.size, 8)
    }
    fun toByteArray(): ByteArray = out.toByteArray()
}

object Lz4 {
    fun decompress(src: ByteArray, off: Int, len: Int, dstLen: Int): ByteArray {
        val dst = ByteArray(dstLen)
        var s = off
        val end = off + len
        var d = 0
        while (s < end) {
            val token = src[s++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) while (true) { val b = src[s++].toInt() and 0xFF; lit += b; if (b != 255) break }
            System.arraycopy(src, s, dst, d, lit)
            s += lit; d += lit
            if (s >= end) break
            val offset = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            var ml = token and 15
            if (ml == 15) while (true) { val b = src[s++].toInt() and 0xFF; ml += b; if (b != 255) break }
            ml += 4
            var m = d - offset
            if (m < 0) throw RbxFormatException("Corrupt LZ4 data")
            repeat(ml) { dst[d++] = dst[m++] }
        }
        return dst
    }
}

private fun bytesToStringValue(b: ByteArray): Any {
    return try {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        dec.decode(java.nio.ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        BinaryString(b)
    }
}

// Special CFrame rotation ids: id-1 = 6*right + up (NormalId indices).
private val NORMALS = arrayOf(Vector3.X, Vector3.Y, Vector3.Z, -Vector3.X, -Vector3.Y, -Vector3.Z)

private fun rotationFromId(id: Int): CFrame {
    val v = id - 1
    val r = NORMALS[v / 6]; val u = NORMALS[v % 6]
    return CFrame.fromColumns(Vector3.ZERO, r, u, r.cross(u))
}

// ---------------------------------------------------------------------------------------------

class RbxBinaryReader(private val data: ByteArray) {
    private class Chunk(val name: String, val data: ByteArray)
    private class ClassInfo(val name: String, val refs: IntArray)

    private val warnings = ArrayList<String>()
    private val instances = HashMap<Int, Instance>()
    private val classes = HashMap<Int, ClassInfo>()
    private val sharedStrings = ArrayList<ByteArray>()
    private val pendingRefs = ArrayList<Triple<Instance, String, Int>>()
    private val meta = LinkedHashMap<String, String>()

    fun read(): RbxDocument {
        val r = ByteReader(data)
        r.pos = 14
        val version = r.u16()
        if (version != 0) warnings.add("Unknown file version $version")
        r.i32(); r.i32(); r.bytes(8)
        val chunks = ArrayList<Chunk>()
        while (r.remaining() >= 16) {
            val name = String(r.bytes(4), Charsets.ISO_8859_1).trimEnd('\u0000')
            val comp = r.i32(); val uncomp = r.i32(); r.i32()
            val body = if (comp == 0) r.bytes(uncomp) else {
                val start = r.pos
                r.bytes(comp)
                decompress(data, start, comp, uncomp)
            }
            chunks.add(Chunk(name, body))
            if (name == "END") break
        }
        for (c in chunks) if (c.name == "META") readMeta(c.data)
        for (c in chunks) if (c.name == "SSTR") readSstr(c.data)
        for (c in chunks) if (c.name == "INST") readInst(c.data)
        for (c in chunks) if (c.name == "PROP") {
            try { readProp(c.data) } catch (e: Exception) { warnings.add("Skipped a property: ${e.message}") }
        }
        for ((inst, prop, ref) in pendingRefs) inst.props[prop] = if (ref < 0) null else instances[ref]
        val roots = ArrayList<Instance>()
        for (c in chunks) if (c.name == "PRNT") readPrnt(c.data, roots)
        for (c in chunks) if (c.name !in setOf("META", "SSTR", "INST", "PROP", "PRNT", "END", "SIGN"))
            warnings.add("Ignored unknown chunk ${c.name}")
        return RbxDocument(roots, meta, warnings)
    }

    private fun decompress(src: ByteArray, off: Int, len: Int, out: Int): ByteArray {
        val isZstd = len >= 4 && src[off] == 0x28.toByte() && src[off + 1] == 0xB5.toByte() &&
            src[off + 2] == 0x2F.toByte() && src[off + 3] == 0xFD.toByte()
        return if (isZstd) {
            try {
                com.github.luben.zstd.Zstd.decompress(src.copyOfRange(off, off + len), out)
            } catch (e: Throwable) {
                throw RbxFormatException("Could not decompress ZSTD chunk: ${e.message}")
            }
        } else Lz4.decompress(src, off, len, out)
    }

    private fun readMeta(d: ByteArray) {
        val r = ByteReader(d)
        val n = r.i32()
        repeat(n) { meta[r.string()] = r.string() }
    }

    private fun readSstr(d: ByteArray) {
        val r = ByteReader(d)
        r.i32()
        val n = r.i32()
        repeat(n) { r.bytes(16); sharedStrings.add(r.stringBytes()) }
    }

    private fun readInst(d: ByteArray) {
        val r = ByteReader(d)
        val classId = r.i32()
        val cls = r.string()
        val format = r.u8()
        val n = r.i32()
        val refs = r.refArray(n)
        val isService = format == 1
        if (isService) r.bytes(n)
        classes[classId] = ClassInfo(cls, refs)
        for (ref in refs) {
            val inst = Instance(cls)
            inst.isService = isService
            inst.loadedRef = ref
            instances[ref] = inst
        }
    }

    private fun readPrnt(d: ByteArray, roots: MutableList<Instance>) {
        val r = ByteReader(d)
        r.u8()
        val n = r.i32()
        val kids = r.refArray(n)
        val parents = r.refArray(n)
        for (i in 0 until n) {
            val c = instances[kids[i]] ?: continue
            val p = if (parents[i] < 0) null else instances[parents[i]]
            if (p == null) roots.add(c) else c.attachRaw(p)
        }
    }

    private fun readProp(d: ByteArray) {
        val r = ByteReader(d)
        val classId = r.i32()
        val name = r.string()
        val type = r.u8()
        val ci = classes[classId] ?: return
        val insts = ci.refs.map { instances[it]!! }
        val n = insts.size
        val payloadStart = r.pos
        val values: List<Any?> = try {
            readValues(r, type, n, insts, name)
        } catch (e: Exception) {
            // Keep the raw bytes so saving doesn't lose data we can't decode.
            val payload = d.copyOfRange(payloadStart, d.size)
            warnings.add("Preserved ${ci.name}.$name (unsupported type 0x${Integer.toHexString(type)})")
            for (i in 0 until n) { insts[i].props[name] = RawPropValue(type, payload, i, n); insts[i].propTypes[name] = type }
            return
        }
        for (i in 0 until n) {
            if (type != PropType.REF) insts[i].props[name] = values[i]
            insts[i].propTypes[name] = type
        }
    }

    private fun readCFrames(r: ByteReader, n: Int): List<CFrame> {
        val rots = ArrayList<CFrame>(n)
        repeat(n) {
            val id = r.u8()
            if (id == 0) {
                val m = FloatArray(9) { r.f32() }
                rots.add(CFrame(0f, 0f, 0f, m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]))
            } else rots.add(rotationFromId(id))
        }
        val xs = r.f32Array(n); val ys = r.f32Array(n); val zs = r.f32Array(n)
        return List(n) { i -> rots[i].withPosition(Vector3(xs[i], ys[i], zs[i])) }
    }

    private fun readValues(r: ByteReader, type: Int, n: Int, insts: List<Instance>, name: String): List<Any?> = when (type) {
        PropType.STRING -> List(n) { bytesToStringValue(r.stringBytes()) }
        PropType.BOOL -> List(n) { r.u8() != 0 }
        PropType.INT -> r.i32Array(n).toList()
        PropType.FLOAT -> r.f32Array(n).toList()
        PropType.DOUBLE -> List(n) { r.f64() }
        PropType.UDIM -> { val s = r.f32Array(n); val o = r.i32Array(n); List(n) { UDim(s[it], o[it]) } }
        PropType.UDIM2 -> {
            val xs = r.f32Array(n); val ys = r.f32Array(n); val xo = r.i32Array(n); val yo = r.i32Array(n)
            List(n) { UDim2(xs[it], xo[it], ys[it], yo[it]) }
        }
        PropType.RAY -> List(n) { Ray(Vector3(r.f32(), r.f32(), r.f32()), Vector3(r.f32(), r.f32(), r.f32())) }
        PropType.FACES -> List(n) { Faces(r.u8()) }
        PropType.AXES -> List(n) { Axes(r.u8()) }
        PropType.BRICKCOLOR -> r.u32Array(n).map { BrickColorV(it) }
        PropType.COLOR3 -> { val a = r.f32Array(n); val b = r.f32Array(n); val c = r.f32Array(n); List(n) { Color3(a[it], b[it], c[it]) } }
        PropType.VECTOR2 -> { val a = r.f32Array(n); val b = r.f32Array(n); List(n) { Vector2(a[it], b[it]) } }
        PropType.VECTOR3 -> { val a = r.f32Array(n); val b = r.f32Array(n); val c = r.f32Array(n); List(n) { Vector3(a[it], b[it], c[it]) } }
        PropType.CFRAME -> readCFrames(r, n)
        PropType.ENUM -> r.u32Array(n).map { EnumValue(it) }
        PropType.REF -> {
            val refs = r.refArray(n)
            for (i in 0 until n) pendingRefs.add(Triple(insts[i], name, refs[i]))
            List(n) { null }
        }
        PropType.VECTOR3INT16 -> List(n) {
            fun s16(): Int { val v = r.u16(); return if (v >= 0x8000) v - 0x10000 else v }
            Vector3int16(s16(), s16(), s16())
        }
        PropType.NUMBERSEQ -> List(n) { NumberSequence(List(r.i32()) { NumberKey(r.f32(), r.f32(), r.f32()) }) }
        PropType.COLORSEQ -> List(n) {
            ColorSequence(List(r.i32()) { val t = r.f32(); val c = Color3(r.f32(), r.f32(), r.f32()); ColorKey(t, c, r.f32()) })
        }
        PropType.NUMBERRANGE -> List(n) { NumberRange(r.f32(), r.f32()) }
        PropType.RECT -> {
            val a = r.f32Array(n); val b = r.f32Array(n); val c = r.f32Array(n); val e = r.f32Array(n)
            List(n) { Rect(a[it], b[it], c[it], e[it]) }
        }
        PropType.PHYSPROPS -> List(n) {
            val flags = r.u8()
            if (flags and 1 != 0) {
                val p = PhysicalProps(flags, r.f32(), r.f32(), r.f32(), r.f32(), r.f32())
                if (flags and 2 != 0) p.copy(acoustic = r.f32()) else p
            } else PhysicalProps(flags)
        }
        PropType.COLOR3U8 -> {
            val a = r.bytes(n); val b = r.bytes(n); val c = r.bytes(n)
            List(n) { Color3.fromRGB(a[it].toInt() and 0xFF, b[it].toInt() and 0xFF, c[it].toInt() and 0xFF) }
        }
        PropType.INT64 -> r.i64Array(n).toList()
        PropType.SHAREDSTRING -> r.u32Array(n).map { SharedStr(sharedStrings.getOrElse(it) { ByteArray(0) }) }
        PropType.OPTCFRAME -> {
            r.u8()
            val cfs = readCFrames(r, n)
            r.u8()
            val has = List(n) { r.u8() != 0 }
            List(n) { OptionalCFrame(if (has[it]) cfs[it] else null) }
        }
        PropType.UNIQUEID -> { val d = r.interleaved(n, 16); List(n) { UniqueId(d.copyOfRange(it * 16, it * 16 + 16)) } }
        PropType.FONT -> List(n) { FontValue(r.string(), r.u16(), r.u8(), r.string()) }
        PropType.SECCAPS -> {
            val d = r.interleaved(n, 8)
            List(n) { i -> var v = 0L; for (b in 0 until 8) v = (v shl 8) or (d[i * 8 + b].toLong() and 0xFF); SecurityCaps(v) }
        }
        PropType.CONTENT -> {
            val types = r.i32Array(n)
            val uris = List(r.i32()) { r.string() }
            val objCount = r.i32()
            val objs = r.refArray(objCount)
            val ext = r.i32()
            if (ext != 0) r.refArray(ext)
            var ui = 0; var oi = 0
            List(n) {
                when (types[it]) {
                    1 -> ContentValue(1, uris.getOrElse(ui++) { "" })
                    2 -> { val ref = objs.getOrElse(oi++) { -1 }; ContentValue(2, "", if (ref < 0) null else instances[ref]) }
                    else -> ContentValue(0)
                }
            }
        }
        else -> throw RbxFormatException("unsupported type 0x${Integer.toHexString(type)} for '$name'")
    }
}

// ---------------------------------------------------------------------------------------------

object RbxBinaryWriter {
    fun typeByName(name: String): Int? = when (name) {
        "String" -> PropType.STRING; "Bool" -> PropType.BOOL; "Int32" -> PropType.INT; "Float32" -> PropType.FLOAT
        "Float64" -> PropType.DOUBLE; "Int64" -> PropType.INT64; "BrickColor" -> PropType.BRICKCOLOR; "Enum" -> PropType.ENUM
        else -> null
    }

    fun write(roots: List<Instance>, meta: Map<String, String>, overrides: Map<String, Int> = emptyMap()): ByteArray {
        val all = ArrayList<Instance>()
        fun collect(i: Instance) {
            if (!i.archivable) return
            all.add(i)
            for (c in i.children) collect(c)
        }
        roots.forEach { collect(it) }
        // Keep referents from the source file where possible so preserved raw data stays valid.
        val ref = IdentityHashMap<Instance, Int>()
        val used = HashSet<Int>()
        for (i in all) if (i.loadedRef >= 0 && used.add(i.loadedRef)) ref[i] = i.loadedRef
        var next = (used.maxOrNull() ?: -1) + 1
        for (i in all) if (!ref.containsKey(i)) ref[i] = next++

        val seenGuids = HashSet<String>()
        for (i in all) if (ClassDb.isA(i.className, "LuaSourceContainer")) {
            val g = i.props["ScriptGuid"] as? String
            if (g.isNullOrEmpty() || !seenGuids.add(g)) {
                val fresh = "{" + java.util.UUID.randomUUID().toString().uppercase() + "}"
                seenGuids.add(fresh)
                i.props["ScriptGuid"] = fresh
            }
        }
        val seenIds = HashSet<String>()
        val dupIds = HashSet<String>()
        for (i in all) (i.props["UniqueId"] as? UniqueId)?.let { u ->
            val k = java.util.Base64.getEncoder().encodeToString(u.bytes)
            if (!seenIds.add(k)) dupIds.add(k)
        }
        val byClass = all.groupBy { it.className }.toSortedMap()
        val classNames = byClass.keys.toList()

        val sstr = LinkedHashMap<String, Pair<Int, ByteArray>>()
        for (i in all) for (v in i.props.values) if (v is SharedStr) {
            val key = java.util.Base64.getEncoder().encodeToString(v.bytes)
            if (key !in sstr) sstr[key] = sstr.size to v.bytes
        }

        val file = ByteWriter()
        file.bytes("<roblox!".toByteArray(Charsets.ISO_8859_1))
        file.bytes(byteArrayOf(0x89.toByte(), 0xFF.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        file.u16(0)
        file.i32(classNames.size)
        file.i32(all.size)
        file.bytes(ByteArray(8))

        fun chunk(name: String, body: ByteArray) {
            val nb = name.toByteArray(Charsets.ISO_8859_1).copyOf(4)
            file.bytes(nb)
            file.i32(0)
            file.i32(body.size)
            file.i32(0)
            file.bytes(body)
        }

        if (meta.isNotEmpty()) {
            val w = ByteWriter()
            w.i32(meta.size)
            for ((k, v) in meta) { w.string(k); w.string(v) }
            chunk("META", w.toByteArray())
        }
        if (sstr.isNotEmpty()) {
            val w = ByteWriter()
            w.i32(0); w.i32(sstr.size)
            for ((_, p) in sstr) {
                w.bytes(java.security.MessageDigest.getInstance("MD5").digest(p.second))
                w.string(p.second)
            }
            chunk("SSTR", w.toByteArray())
        }

        classNames.forEachIndexed { cid, cls ->
            val insts = byClass[cls]!!
            val w = ByteWriter()
            w.i32(cid)
            w.string(cls)
            val service = insts.any { it.isService }
            w.u8(if (service) 1 else 0)
            w.i32(insts.size)
            w.refArray(IntArray(insts.size) { ref[insts[it]]!! })
            if (service) repeat(insts.size) { w.u8(1) }
            chunk("INST", w.toByteArray())
        }

        classNames.forEachIndexed { cid, cls ->
            val insts = byClass[cls]!!
            val propNames = LinkedHashSet<String>()
            propNames.add("Name")
            for (i in insts) propNames.addAll(i.props.keys)
            for (pn in propNames) {
                if (pn == "UniqueId" && insts.any { val u = it.props[pn] as? UniqueId; u != null && (u.bytes.all { b -> b.toInt() == 0 } || java.util.Base64.getEncoder().encodeToString(u.bytes) in dupIds) }) continue
                val raw = insts.firstNotNullOfOrNull { it.props[pn] as? RawPropValue }
                if (raw != null) {
                    // Only write back undecodable data if this class's instances are unchanged.
                    val intact = raw.count == insts.size && insts.withIndex().all { (i, inst) ->
                        val v = inst.props[pn] as? RawPropValue
                        v != null && v.payload === raw.payload && v.index == i
                    }
                    if (intact) {
                        val w = ByteWriter()
                        w.i32(cid); w.string(pn); w.u8(raw.type); w.bytes(raw.payload)
                        chunk("PROP", w.toByteArray())
                    }
                    continue
                }
                val type = overrides["$cls.$pn"] ?: insts.firstNotNullOfOrNull { it.propTypes[pn] }
                    ?: insts.firstOrNull { it.props.containsKey(pn) }?.let { PropType.infer(it.props[pn]) } ?: PropType.STRING
                // Instances that never had this property keep Roblox's default for their class
                // (a Part with no "shape" is a Block; the enum's zero would turn it into a Ball).
                val fallback by lazy {
                    InstanceFactory.classDefault(cls, pn)?.takeIf { compatible(type, it) } ?: PropType.default(type)
                }
                val values = insts.map { i ->
                    if (pn == "Name") i.name
                    else if (i.props.containsKey(pn)) i.props[pn] else fallback
                }
                val w = ByteWriter()
                w.i32(cid)
                w.string(pn)
                w.u8(type)
                writeValues(w, type, values, ref, sstr)
                chunk("PROP", w.toByteArray())
            }
        }

        run {
            val w = ByteWriter()
            w.u8(0)
            w.i32(all.size)
            w.refArray(IntArray(all.size) { ref[all[it]]!! })
            w.refArray(IntArray(all.size) { idx -> all[idx].parent?.let { ref[it] } ?: -1 })
            chunk("PRNT", w.toByteArray())
        }
        chunk("END", "</roblox>".toByteArray(Charsets.ISO_8859_1))
        return file.toByteArray()
    }

    /** Whether a default value can be written into a column of [type]. */
    private fun compatible(type: Int, v: Any?): Boolean = when (type) {
        PropType.INT, PropType.FLOAT, PropType.DOUBLE, PropType.INT64 -> v is Number
        PropType.COLOR3, PropType.COLOR3U8 -> v is Color3
        PropType.STRING -> v is String || v is BinaryString
        PropType.REF -> v == null || v is Instance
        else -> v != null && PropType.infer(v) == type
    }

    private fun f(v: Any?): Float = (v as? Number)?.toFloat() ?: 0f
    private fun i(v: Any?): Int = when (v) { is Number -> v.toInt(); is EnumValue -> v.value; is BrickColorV -> v.number; else -> 0 }

    private fun writeCFrames(w: ByteWriter, cfs: List<CFrame>) {
        for (c in cfs) {
            w.u8(0)
            w.f32(c.r00); w.f32(c.r01); w.f32(c.r02)
            w.f32(c.r10); w.f32(c.r11); w.f32(c.r12)
            w.f32(c.r20); w.f32(c.r21); w.f32(c.r22)
        }
        w.f32Array(FloatArray(cfs.size) { cfs[it].x })
        w.f32Array(FloatArray(cfs.size) { cfs[it].y })
        w.f32Array(FloatArray(cfs.size) { cfs[it].z })
    }

    private fun writeValues(w: ByteWriter, type: Int, vals: List<Any?>, ref: IdentityHashMap<Instance, Int>, sstr: Map<String, Pair<Int, ByteArray>>) {
        val n = vals.size
        when (type) {
            PropType.STRING -> for (v in vals) when (v) {
                is BinaryString -> w.string(v.bytes)
                is ContentValue -> w.string(v.uri)
                else -> w.string(v?.toString() ?: "")
            }
            PropType.BOOL -> for (v in vals) w.u8(if (v == true) 1 else 0)
            PropType.INT -> w.i32Array(IntArray(n) { i(vals[it]) })
            PropType.FLOAT -> w.f32Array(FloatArray(n) { f(vals[it]) })
            PropType.DOUBLE -> for (v in vals) w.f64((v as? Number)?.toDouble() ?: 0.0)
            PropType.INT64 -> w.i64Array(LongArray(n) { (vals[it] as? Number)?.toLong() ?: 0L })
            PropType.UDIM -> {
                val u = vals.map { it as? UDim ?: UDim(0f, 0) }
                w.f32Array(FloatArray(n) { u[it].scale }); w.i32Array(IntArray(n) { u[it].offset })
            }
            PropType.UDIM2 -> {
                val u = vals.map { it as? UDim2 ?: UDim2(0f, 0, 0f, 0) }
                w.f32Array(FloatArray(n) { u[it].xs }); w.f32Array(FloatArray(n) { u[it].ys })
                w.i32Array(IntArray(n) { u[it].xo }); w.i32Array(IntArray(n) { u[it].yo })
            }
            PropType.RAY -> for (v in vals) {
                val r = v as? Ray ?: Ray(Vector3.ZERO, Vector3.ZERO)
                w.f32(r.origin.x); w.f32(r.origin.y); w.f32(r.origin.z); w.f32(r.direction.x); w.f32(r.direction.y); w.f32(r.direction.z)
            }
            PropType.FACES -> for (v in vals) w.u8((v as? Faces)?.mask ?: 0)
            PropType.AXES -> for (v in vals) w.u8((v as? Axes)?.mask ?: 0)
            PropType.BRICKCOLOR -> w.u32Array(IntArray(n) { (vals[it] as? BrickColorV)?.number ?: i(vals[it]).let { x -> if (x == 0) 194 else x } })
            PropType.COLOR3 -> {
                val c = vals.map { it as? Color3 ?: Color3(0f, 0f, 0f) }
                w.f32Array(FloatArray(n) { c[it].r }); w.f32Array(FloatArray(n) { c[it].g }); w.f32Array(FloatArray(n) { c[it].b })
            }
            PropType.VECTOR2 -> {
                val c = vals.map { it as? Vector2 ?: Vector2(0f, 0f) }
                w.f32Array(FloatArray(n) { c[it].x }); w.f32Array(FloatArray(n) { c[it].y })
            }
            PropType.VECTOR3 -> {
                val c = vals.map { it as? Vector3 ?: Vector3.ZERO }
                w.f32Array(FloatArray(n) { c[it].x }); w.f32Array(FloatArray(n) { c[it].y }); w.f32Array(FloatArray(n) { c[it].z })
            }
            PropType.CFRAME -> writeCFrames(w, vals.map { it as? CFrame ?: CFrame.IDENTITY })
            PropType.ENUM -> w.u32Array(IntArray(n) { i(vals[it]) })
            PropType.REF -> w.refArray(IntArray(n) { (vals[it] as? Instance)?.let { x -> ref[x] } ?: -1 })
            PropType.VECTOR3INT16 -> for (v in vals) {
                val x = v as? Vector3int16 ?: Vector3int16(0, 0, 0)
                w.u16(x.x); w.u16(x.y); w.u16(x.z)
            }
            PropType.NUMBERSEQ -> for (v in vals) {
                val s = v as? NumberSequence ?: PropType.default(type) as NumberSequence
                w.i32(s.keys.size); for (k in s.keys) { w.f32(k.time); w.f32(k.value); w.f32(k.envelope) }
            }
            PropType.COLORSEQ -> for (v in vals) {
                val s = v as? ColorSequence ?: PropType.default(type) as ColorSequence
                w.i32(s.keys.size); for (k in s.keys) { w.f32(k.time); w.f32(k.color.r); w.f32(k.color.g); w.f32(k.color.b); w.f32(k.envelope) }
            }
            PropType.NUMBERRANGE -> for (v in vals) { val x = v as? NumberRange ?: NumberRange(0f, 0f); w.f32(x.min); w.f32(x.max) }
            PropType.RECT -> {
                val c = vals.map { it as? Rect ?: Rect(0f, 0f, 0f, 0f) }
                w.f32Array(FloatArray(n) { c[it].minX }); w.f32Array(FloatArray(n) { c[it].minY })
                w.f32Array(FloatArray(n) { c[it].maxX }); w.f32Array(FloatArray(n) { c[it].maxY })
            }
            PropType.PHYSPROPS -> for (v in vals) {
                val p = v as? PhysicalProps ?: PhysicalProps(0)
                w.u8(p.flags)
                if (p.flags and 1 != 0) {
                    w.f32(p.density); w.f32(p.friction); w.f32(p.elasticity); w.f32(p.frictionWeight); w.f32(p.elasticityWeight)
                    if (p.flags and 2 != 0) w.f32(p.acoustic)
                }
            }
            PropType.COLOR3U8 -> {
                val c = vals.map { it as? Color3 ?: Color3(0f, 0f, 0f) }
                fun b(x: Float) = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                w.bytes(ByteArray(n) { b(c[it].r) }); w.bytes(ByteArray(n) { b(c[it].g) }); w.bytes(ByteArray(n) { b(c[it].b) })
            }
            PropType.SHAREDSTRING -> w.u32Array(IntArray(n) {
                val s = vals[it] as? SharedStr
                if (s == null) 0 else sstr[java.util.Base64.getEncoder().encodeToString(s.bytes)]?.first ?: 0
            })
            PropType.OPTCFRAME -> {
                val o = vals.map { it as? OptionalCFrame ?: OptionalCFrame(null) }
                w.u8(PropType.CFRAME)
                writeCFrames(w, o.map { it.cframe ?: CFrame.IDENTITY })
                w.u8(PropType.BOOL)
                for (x in o) w.u8(if (x.cframe != null) 1 else 0)
            }
            PropType.UNIQUEID -> {
                val d = ByteArray(n * 16)
                vals.forEachIndexed { idx, v -> (v as? UniqueId)?.bytes?.copyInto(d, idx * 16, 0, 16) }
                w.interleaved(d, n, 16)
            }
            PropType.FONT -> for (v in vals) {
                val f = v as? FontValue ?: PropType.default(type) as FontValue
                w.string(f.family); w.u16(f.weight); w.u8(f.style); w.string(f.cachedFaceId)
            }
            PropType.SECCAPS -> {
                val d = ByteArray(n * 8)
                vals.forEachIndexed { idx, v -> val x = (v as? SecurityCaps)?.value ?: 0L; for (b in 0 until 8) d[idx * 8 + b] = (x ushr (56 - 8 * b)).toByte() }
                w.interleaved(d, n, 8)
            }
            PropType.CONTENT -> {
                val c = vals.map { it as? ContentValue ?: (it as? String)?.let { s -> if (s.isEmpty()) ContentValue(0) else ContentValue(1, s) } ?: ContentValue(0) }
                w.i32Array(IntArray(n) { if (c[it].sourceType == 2 && c[it].obj == null) 0 else c[it].sourceType })
                val uris = c.filter { it.sourceType == 1 }
                w.i32(uris.size); uris.forEach { w.string(it.uri) }
                val objs = c.filter { it.sourceType == 2 && it.obj != null }
                w.i32(objs.size); w.refArray(IntArray(objs.size) { ref[objs[it].obj!!] ?: -1 })
                w.i32(0)
            }
            else -> for (v in vals) w.string(v?.toString() ?: "")
        }
    }
}
