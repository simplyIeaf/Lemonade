package com.leaf.lemonade.model

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

fun fmtNum(f: Double): String {
    if (f.isNaN()) return "nan"
    if (f.isInfinite()) return if (f > 0) "inf" else "-inf"
    val r = Math.round(f * 1000.0) / 1000.0
    return if (r == Math.rint(r) && abs(r) < 1e15) r.toLong().toString() else r.toString()
}

fun fmtNum(f: Float): String = fmtNum(f.toDouble())

data class Vector3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vector3) = Vector3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vector3) = Vector3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vector3(x * s, y * s, z * s)
    operator fun times(o: Vector3) = Vector3(x * o.x, y * o.y, z * o.z)
    operator fun div(s: Float) = Vector3(x / s, y / s, z / s)
    operator fun div(o: Vector3) = Vector3(x / o.x, y / o.y, z / o.z)
    operator fun unaryMinus() = Vector3(-x, -y, -z)
    fun dot(o: Vector3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vector3) = Vector3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val magnitude: Float get() = sqrt(x * x + y * y + z * z)
    val unit: Vector3 get() { val m = magnitude; return if (m < 1e-9f) ZERO else this / m }
    fun lerp(o: Vector3, t: Float) = Vector3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun abs() = Vector3(abs(x), abs(y), abs(z))
    fun min(o: Vector3) = Vector3(min(x, o.x), min(y, o.y), min(z, o.z))
    fun max(o: Vector3) = Vector3(max(x, o.x), max(y, o.y), max(z, o.z))
    operator fun get(i: Int) = when (i) { 0 -> x; 1 -> y; else -> z }
    override fun toString() = "${fmtNum(x)}, ${fmtNum(y)}, ${fmtNum(z)}"

    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
        val ONE = Vector3(1f, 1f, 1f)
        val X = Vector3(1f, 0f, 0f)
        val Y = Vector3(0f, 1f, 0f)
        val Z = Vector3(0f, 0f, 1f)
        fun axis(i: Int) = when (i) { 0 -> X; 1 -> Y; else -> Z }
    }
}

data class Vector2(val x: Float, val y: Float) {
    operator fun plus(o: Vector2) = Vector2(x + o.x, y + o.y)
    operator fun minus(o: Vector2) = Vector2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vector2(x * s, y * s)
    operator fun times(o: Vector2) = Vector2(x * o.x, y * o.y)
    operator fun div(s: Float) = Vector2(x / s, y / s)
    operator fun unaryMinus() = Vector2(-x, -y)
    val magnitude: Float get() = sqrt(x * x + y * y)
    val unit: Vector2 get() { val m = magnitude; return if (m < 1e-9f) Vector2(0f, 0f) else this / m }
    fun dot(o: Vector2) = x * o.x + y * o.y
    fun lerp(o: Vector2, t: Float) = Vector2(x + (o.x - x) * t, y + (o.y - y) * t)
    override fun toString() = "${fmtNum(x)}, ${fmtNum(y)}"
}

data class Vector3int16(val x: Int, val y: Int, val z: Int) {
    override fun toString() = "$x, $y, $z"
}

/** Rotation is stored row-major: r00 r01 r02 / r10 r11 r12 / r20 r21 r22. Columns are Right, Up, Back. */
class CFrame(
    val x: Float, val y: Float, val z: Float,
    val r00: Float = 1f, val r01: Float = 0f, val r02: Float = 0f,
    val r10: Float = 0f, val r11: Float = 1f, val r12: Float = 0f,
    val r20: Float = 0f, val r21: Float = 0f, val r22: Float = 1f,
) {
    val position get() = Vector3(x, y, z)
    val rightVector get() = Vector3(r00, r10, r20)
    val upVector get() = Vector3(r01, r11, r21)
    val backVector get() = Vector3(r02, r12, r22)
    val lookVector get() = Vector3(-r02, -r12, -r22)

    fun column(i: Int) = when (i) { 0 -> rightVector; 1 -> upVector; else -> backVector }

    operator fun times(o: CFrame): CFrame {
        val n00 = r00 * o.r00 + r01 * o.r10 + r02 * o.r20
        val n01 = r00 * o.r01 + r01 * o.r11 + r02 * o.r21
        val n02 = r00 * o.r02 + r01 * o.r12 + r02 * o.r22
        val n10 = r10 * o.r00 + r11 * o.r10 + r12 * o.r20
        val n11 = r10 * o.r01 + r11 * o.r11 + r12 * o.r21
        val n12 = r10 * o.r02 + r11 * o.r12 + r12 * o.r22
        val n20 = r20 * o.r00 + r21 * o.r10 + r22 * o.r20
        val n21 = r20 * o.r01 + r21 * o.r11 + r22 * o.r21
        val n22 = r20 * o.r02 + r21 * o.r12 + r22 * o.r22
        val p = this * o.position
        return CFrame(p.x, p.y, p.z, n00, n01, n02, n10, n11, n12, n20, n21, n22)
    }

    operator fun times(v: Vector3): Vector3 = vectorToWorld(v) + position
    operator fun plus(v: Vector3) = CFrame(x + v.x, y + v.y, z + v.z, r00, r01, r02, r10, r11, r12, r20, r21, r22)
    operator fun minus(v: Vector3) = CFrame(x - v.x, y - v.y, z - v.z, r00, r01, r02, r10, r11, r12, r20, r21, r22)

    fun vectorToWorld(v: Vector3) = Vector3(
        r00 * v.x + r01 * v.y + r02 * v.z,
        r10 * v.x + r11 * v.y + r12 * v.z,
        r20 * v.x + r21 * v.y + r22 * v.z,
    )

    fun vectorToObject(v: Vector3) = Vector3(
        r00 * v.x + r10 * v.y + r20 * v.z,
        r01 * v.x + r11 * v.y + r21 * v.z,
        r02 * v.x + r12 * v.y + r22 * v.z,
    )

    fun pointToObject(v: Vector3) = vectorToObject(v - position)
    fun pointToWorld(v: Vector3) = this * v

    fun inverse(): CFrame {
        val p = vectorToObject(-position)
        return CFrame(p.x, p.y, p.z, r00, r10, r20, r01, r11, r21, r02, r12, r22)
    }

    fun rotationOnly() = CFrame(0f, 0f, 0f, r00, r01, r02, r10, r11, r12, r20, r21, r22)
    fun withPosition(p: Vector3) = CFrame(p.x, p.y, p.z, r00, r01, r02, r10, r11, r12, r20, r21, r22)

    /** Euler angles for R = Rx * Ry * Rz (radians). */
    fun toEulerXYZ(): Vector3 {
        val ry = asin(r02.coerceIn(-1f, 1f))
        val rx: Float
        val rz: Float
        if (abs(r02) < 0.99999f) {
            rx = atan2(-r12, r22)
            rz = atan2(-r01, r00)
        } else {
            rx = atan2(r21, r11)
            rz = 0f
        }
        return Vector3(rx, ry, rz)
    }

    /** Roblox Orientation (R = Ry * Rx * Rz), radians. */
    fun toOrientation(): Vector3 {
        val rx = asin((-r12).coerceIn(-1f, 1f))
        val ry: Float
        val rz: Float
        if (abs(r12) < 0.99999f) {
            ry = atan2(r02, r22)
            rz = atan2(r10, r11)
        } else {
            ry = atan2(-r20, r00)
            rz = 0f
        }
        return Vector3(rx, ry, rz)
    }

    fun toQuaternion(): FloatArray {
        val trace = r00 + r11 + r22
        val q = FloatArray(4) // x y z w
        if (trace > 0) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = 0.25f * s; q[0] = (r21 - r12) / s; q[1] = (r02 - r20) / s; q[2] = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt(1f + r00 - r11 - r22) * 2f
            q[3] = (r21 - r12) / s; q[0] = 0.25f * s; q[1] = (r01 + r10) / s; q[2] = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = sqrt(1f + r11 - r00 - r22) * 2f
            q[3] = (r02 - r20) / s; q[0] = (r01 + r10) / s; q[1] = 0.25f * s; q[2] = (r12 + r21) / s
        } else {
            val s = sqrt(1f + r22 - r00 - r11) * 2f
            q[3] = (r10 - r01) / s; q[0] = (r02 + r20) / s; q[1] = (r12 + r21) / s; q[2] = 0.25f * s
        }
        return q
    }

    fun lerp(o: CFrame, t: Float): CFrame {
        val a = toQuaternion(); val b = o.toQuaternion()
        var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        if (d < 0) { for (i in 0..3) b[i] = -b[i]; d = -d }
        val q = FloatArray(4)
        if (d > 0.9995f) {
            for (i in 0..3) q[i] = a[i] + (b[i] - a[i]) * t
        } else {
            val th = acos(d.coerceIn(-1f, 1f)); val s = sin(th)
            val wa = sin((1 - t) * th) / s; val wb = sin(t * th) / s
            for (i in 0..3) q[i] = a[i] * wa + b[i] * wb
        }
        val p = position.lerp(o.position, t)
        return fromQuaternion(p, q[0], q[1], q[2], q[3])
    }

    fun orthonormalize(): CFrame {
        val r = rightVector.unit
        var u = upVector
        u = (u - r * r.dot(u)).unit
        val b = r.cross(u)
        return fromColumns(position, r, u, b)
    }

    fun components() = floatArrayOf(x, y, z, r00, r01, r02, r10, r11, r12, r20, r21, r22)

    override fun equals(other: Any?): Boolean = other is CFrame && components().contentEquals(other.components())
    override fun hashCode(): Int = components().contentHashCode()
    override fun toString() = components().joinToString(", ") { fmtNum(it) }

    companion object {
        val IDENTITY = CFrame(0f, 0f, 0f)
        fun at(p: Vector3) = CFrame(p.x, p.y, p.z)
        fun fromColumns(p: Vector3, r: Vector3, u: Vector3, b: Vector3) =
            CFrame(p.x, p.y, p.z, r.x, u.x, b.x, r.y, u.y, b.y, r.z, u.z, b.z)

        fun rx(a: Float): CFrame { val c = cos(a); val s = sin(a); return CFrame(0f, 0f, 0f, 1f, 0f, 0f, 0f, c, -s, 0f, s, c) }
        fun ry(a: Float): CFrame { val c = cos(a); val s = sin(a); return CFrame(0f, 0f, 0f, c, 0f, s, 0f, 1f, 0f, -s, 0f, c) }
        fun rz(a: Float): CFrame { val c = cos(a); val s = sin(a); return CFrame(0f, 0f, 0f, c, -s, 0f, s, c, 0f, 0f, 0f, 1f) }
        fun angles(x: Float, y: Float, z: Float) = rx(x) * ry(y) * rz(z)
        fun fromOrientation(x: Float, y: Float, z: Float) = ry(y) * rx(x) * rz(z)

        fun fromAxisAngle(axis: Vector3, angle: Float): CFrame {
            val a = axis.unit
            val h = angle / 2f
            val s = sin(h)
            return fromQuaternion(Vector3.ZERO, a.x * s, a.y * s, a.z * s, cos(h))
        }

        fun fromQuaternion(p: Vector3, qx: Float, qy: Float, qz: Float, qw: Float): CFrame {
            val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).let { if (it < 1e-9f) 1f else it }
            val x = qx / n; val y = qy / n; val z = qz / n; val w = qw / n
            return CFrame(
                p.x, p.y, p.z,
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
            )
        }

        fun lookAt(eye: Vector3, target: Vector3, up: Vector3 = Vector3.Y): CFrame {
            val look = (target - eye).unit
            if (look.magnitude < 1e-6f) return at(eye)
            val back = -look
            var right = up.cross(back).unit
            if (right.magnitude < 1e-6f) right = Vector3.X
            val u = back.cross(right).unit
            return fromColumns(eye, right, u, back)
        }
    }
}

data class Color3(val r: Float, val g: Float, val b: Float) {
    fun toArgb(): Int = (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
    fun lerp(o: Color3, t: Float) = Color3(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)
    fun toHex() = String.format("%02X%02X%02X", c8(r), c8(g), c8(b))
    fun toHSV(): FloatArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == r -> ((g - b) / d) % 6f
            mx == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        if (h < 0) h += 1f
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }
    override fun toString() = "${c8(r)}, ${c8(g)}, ${c8(b)}"

    companion object {
        private fun c8(f: Float) = (f.coerceIn(0f, 1f) * 255f).roundToInt()
        fun fromRGB(r: Int, g: Int, b: Int) = Color3(r / 255f, g / 255f, b / 255f)
        fun fromArgb(c: Int) = fromRGB((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        fun fromHex(h: String): Color3? {
            val s = h.trim().removePrefix("#")
            if (s.length != 6) return null
            return s.toIntOrNull(16)?.let { fromArgb(it) }
        }
        fun fromHSV(h: Float, s: Float, v: Float): Color3 {
            val i = (h * 6f).toInt()
            val f = h * 6f - i
            val p = v * (1 - s); val q = v * (1 - f * s); val t = v * (1 - (1 - f) * s)
            return when (((i % 6) + 6) % 6) {
                0 -> Color3(v, t, p); 1 -> Color3(q, v, p); 2 -> Color3(p, v, t)
                3 -> Color3(p, q, v); 4 -> Color3(t, p, v); else -> Color3(v, p, q)
            }
        }
    }
}

data class BrickColorV(val number: Int) {
    val name: String get() = BrickColors.name(number)
    val color: Color3 get() = BrickColors.color(number)
    override fun toString() = name
}

data class UDim(val scale: Float, val offset: Int) {
    override fun toString() = "${fmtNum(scale)}, $offset"
}

data class UDim2(val xs: Float, val xo: Int, val ys: Float, val yo: Int) {
    override fun toString() = "{${fmtNum(xs)}, $xo}, {${fmtNum(ys)}, $yo}"
    fun lerp(o: UDim2, t: Float) = UDim2(
        xs + (o.xs - xs) * t, (xo + (o.xo - xo) * t).roundToInt(),
        ys + (o.ys - ys) * t, (yo + (o.yo - yo) * t).roundToInt(),
    )
}

data class Ray(val origin: Vector3, val direction: Vector3) {
    override fun toString() = "{$origin}, {$direction}"
}

data class Faces(val mask: Int) {
    override fun toString(): String {
        val n = listOf("Right", "Top", "Back", "Left", "Bottom", "Front")
        return n.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ")
    }
}

data class Axes(val mask: Int) {
    override fun toString(): String = listOf("X", "Y", "Z").filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ")
}

data class EnumValue(val value: Int) {
    override fun toString() = value.toString()
}

data class NumberRange(val min: Float, val max: Float) {
    override fun toString() = "${fmtNum(min)} ${fmtNum(max)}"
}

data class NumberKey(val time: Float, val value: Float, val envelope: Float)
data class NumberSequence(val keys: List<NumberKey>) {
    override fun toString() = keys.joinToString(" ") { "${fmtNum(it.time)} ${fmtNum(it.value)} ${fmtNum(it.envelope)}" }
}

data class ColorKey(val time: Float, val color: Color3, val envelope: Float)
data class ColorSequence(val keys: List<ColorKey>) {
    override fun toString() = keys.joinToString(" ") { "${fmtNum(it.time)} (${it.color})" }
}

data class Rect(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    override fun toString() = "${fmtNum(minX)}, ${fmtNum(minY)}, ${fmtNum(maxX)}, ${fmtNum(maxY)}"
}

data class PhysicalProps(
    val flags: Int,
    val density: Float = 0.7f, val friction: Float = 0.3f, val elasticity: Float = 0.5f,
    val frictionWeight: Float = 1f, val elasticityWeight: Float = 1f, val acoustic: Float = 1f,
) {
    val custom get() = flags and 1 != 0
    override fun toString() = if (!custom) "Default" else "${fmtNum(density)}, ${fmtNum(friction)}, ${fmtNum(elasticity)}"
}

class BinaryString(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is BinaryString && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "<binary ${bytes.size} bytes>"
}

class SharedStr(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is SharedStr && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "<shared ${bytes.size} bytes>"
}

data class OptionalCFrame(val cframe: CFrame?) {
    override fun toString() = cframe?.toString() ?: "nil"
}

class UniqueId(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is UniqueId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = bytes.joinToString("") { String.format("%02x", it) }
}

data class FontValue(val family: String, val weight: Int, val style: Int, val cachedFaceId: String) {
    override fun toString() = family.substringAfterLast('/').substringBefore('.') + " ($weight)"
}

data class SecurityCaps(val value: Long)

/**
 * A property of a type this app can't decode. The whole PROP payload is kept so it can be written
 * back unchanged as long as the class's instances are still the same set, in the same order.
 */
class RawPropValue(val type: Int, val payload: ByteArray, val index: Int, val count: Int) {
    override fun toString() = "<unsupported type 0x${Integer.toHexString(type)}>"
}

/** sourceType: 0 = none, 1 = uri, 2 = object */
data class ContentValue(val sourceType: Int, val uri: String = "", val obj: Instance? = null) {
    override fun toString() = when (sourceType) { 1 -> uri; 2 -> "Object"; else -> "" }
}

object BrickColors {
    private val table: Map<Int, Pair<String, Int>> = linkedMapOf(
        1 to ("White" to 0xF2F3F3), 5 to ("Brick yellow" to 0xD7C59A), 9 to ("Light reddish violet" to 0xE8BAC8),
        11 to ("Pastel Blue" to 0x80BBDB), 18 to ("Nougat" to 0xCC8E69), 21 to ("Bright red" to 0xC4281C),
        23 to ("Bright blue" to 0x0D69AC), 24 to ("Bright yellow" to 0xF5CD30), 26 to ("Black" to 0x1B2A35),
        28 to ("Dark green" to 0x287F47), 37 to ("Bright green" to 0x4B974B), 45 to ("Light blue" to 0xB4D2E4),
        101 to ("Medium red" to 0xDA867A), 102 to ("Medium blue" to 0x6E99CA), 106 to ("Bright orange" to 0xDA8541),
        119 to ("Br. yellowish green" to 0xA4BD47), 125 to ("Light orange" to 0xEAB892), 141 to ("Earth green" to 0x27462D),
        192 to ("Reddish brown" to 0x694028), 194 to ("Medium stone grey" to 0xA3A2A5), 199 to ("Dark stone grey" to 0x635F62),
        208 to ("Light stone grey" to 0xE5E4DF), 217 to ("Brown" to 0x7C5C46), 226 to ("Cool yellow" to 0xFDEA8D),
        1001 to ("Institutional white" to 0xF8F8F8), 1002 to ("Mid gray" to 0xCDCDCD), 1003 to ("Really black" to 0x111111),
        1004 to ("Really red" to 0xFF0000), 1005 to ("Deep orange" to 0xFFB000), 1006 to ("Alder" to 0xB480FF),
        1007 to ("Dusty Rose" to 0xA34B4B), 1009 to ("New Yeller" to 0xFFFF00), 1010 to ("Really blue" to 0x0000FF),
        1011 to ("Navy blue" to 0x002060), 1012 to ("Deep blue" to 0x2154B9), 1013 to ("Cyan" to 0x04AFEC),
        1015 to ("Magenta" to 0xAA00AA), 1016 to ("Pink" to 0xFF66CC), 1019 to ("Toothpaste" to 0x00FFFF),
        1020 to ("Lime green" to 0x00FF00), 1022 to ("Grime" to 0x7F8E64), 1023 to ("Lavender" to 0x8C5B9F),
        1031 to ("Royal purple" to 0x6225D1), 1032 to ("Hot pink" to 0xFF00BF),
    )

    val all: List<Int> get() = table.keys.toList()
    fun name(n: Int) = table[n]?.first ?: "Medium stone grey"
    fun color(n: Int) = Color3.fromArgb(table[n]?.second ?: 0xA3A2A5)
    fun byName(name: String): Int? = table.entries.firstOrNull { it.value.first.equals(name, true) }?.key
    fun closest(c: Color3): Int {
        var best = 194; var bd = Float.MAX_VALUE
        for ((n, v) in table) {
            val o = Color3.fromArgb(v.second)
            val d = (o.r - c.r) * (o.r - c.r) + (o.g - c.g) * (o.g - c.g) + (o.b - c.b) * (o.b - c.b)
            if (d < bd) { bd = d; best = n }
        }
        return best
    }
}
