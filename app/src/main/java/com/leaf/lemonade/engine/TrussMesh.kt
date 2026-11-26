package com.leaf.lemonade.engine

import com.leaf.lemonade.model.Vector3

object TrussMesh {
    fun segments(length: Float): Int = (length * 0.5f + 0.005f).coerceAtLeast(0f).toInt()

    fun generate(size: Vector3, style: Int): FloatArray {
        val sx = size.x; val sy = size.y; val sz = size.z
        val axis = if (sy < sx) { if (sz < sx) 0 else 2 } else if (sy <= sz) 2 else 1
        val minE = minOf(sx, sy, sz)
        val scale = if (minE >= 0.0001f) minE * 0.5f else 1f
        val sizeAt = floatArrayOf(sx, sy, sz)
        val length = sizeAt[axis] / scale
        val count = segments(length)
        val out = ArrayList<Float>()
        if (count == 0) return FloatArray(0)
        val along = when (axis) { 0 -> Vector3.X; 1 -> Vector3.Y; else -> Vector3.Z }
        val a: Vector3
        val b: Vector3
        when (axis) {
            0 -> { a = -Vector3.Z; b = Vector3.Y }
            1 -> { a = Vector3.X; b = Vector3.Z }
            else -> { a = Vector3.X; b = -Vector3.Y }
        }
        val start = along * (-length * 0.5f)

        fun quad(origin0: Vector3, u: Vector3, w0: Float, v: Vector3, h0: Float) {
            val normal = u.cross(v)
            val origin = origin0 + u * minOf(w0, 0f) + v * minOf(h0, 0f)
            val w = Math.abs(w0)
            val h = Math.abs(h0)
            val xs = floatArrayOf(0f, 1f, 1f, 0f, 1f, 0f)
            val ys = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
            for (k in 0 until 6) {
                val x = xs[k]; val y = ys[k]
                val p = origin + u * (x * w) + v * (y * h)
                out.add(p.x * scale / sx); out.add(p.y * scale / sy); out.add(p.z * scale / sz)
                out.add(normal.x * sx); out.add(normal.y * sy); out.add(normal.z * sz)
                out.add(x * w); out.add(y * h)
            }
        }

        val faces = listOf(
            Triple(start - a - b, a, b),
            Triple(start - a + b, -b, a),
            Triple(start + a + b, -a, -b),
            Triple(start + a - b, b, -a)
        )
        val cnt = count.toFloat()
        for ((face, f) in faces.withIndex()) {
            val origin = f.first; val u = f.second; val outv = f.third
            quad(origin, -u, -0.25f, along, cnt * 2f)
            quad(origin, outv, 0.25f, along, cnt * 2f)
            quad(origin + u * 0.25f, -outv, -0.25f, along, cnt * 2f)
            quad(origin + outv * 0.25f, u, 0.25f, along, cnt * 2f)
            for (i in 0..count) {
                val y = if (i == 0) 0f else i * 2f - 0.25f
                val h = if (i == 0 || i == count) 0.25f else 0.5f
                val lo = if (i == 0) 0f else 0.25f
                val hi = if (i == count) 0f else 0.25f
                val o = origin + along * y
                quad(o + u * 0.25f, -u, -1.5f, along, h)
                quad(o + u * 0.25f + outv * 0.25f, u, 1.5f, along, h)
                quad(o + u * lo, u, 1.75f - lo, outv, 0.25f)
                quad(o + u * hi + along * h, -u, -(1.75f - hi), outv, 0.25f)
            }
            if (count > 1 && (style == 0 || (style == 1 && face % 2 == 1))) {
                for (i in 0 until count) {
                    val flip = if (style == 0) i % 2 == 1 else if (face == 1) i >= count / 2 else i < count / 2
                    val direction = if (flip) -along else along
                    val sign = if (flip) -1f else 1f
                    val across = (direction - u) * (sign * 0.707106f)
                    val diagonal = (direction + u) * 0.707106f
                    val o = origin + along * (i * 2f + (if (flip) 2f else 0f)) + (direction + u) * 0.125f + outv * 0.0625f - across * 0.125f
                    quad(o, across, 0.25f, diagonal, 2.474873f)
                    quad(o + outv * 0.125f, -across, -0.25f, diagonal, 2.474873f)
                    quad(o, -outv, -0.125f, diagonal, 2.474873f)
                    quad(o + outv * 0.125f + across * 0.25f, outv, -0.125f, diagonal, 2.474873f)
                }
            }
        }
        return out.toFloatArray()
    }
}
