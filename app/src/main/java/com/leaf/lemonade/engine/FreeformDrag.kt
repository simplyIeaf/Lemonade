package com.leaf.lemonade.engine

import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.Vector3
import kotlin.math.abs
import kotlin.math.floor

object FreeformDrag {
    class Frame(val pos: Vector3, val x: Vector3, val y: Vector3, val z: Vector3) {
        fun toLocal(w: Vector3) = Vector3(w.dot(x), w.dot(y), w.dot(z))
        fun toWorldDir(l: Vector3) = x * l.x + y * l.y + z * l.z
    }

    class Surf(val hit: Vector3, val m: Frame)

    class State(val basis: Frame, val offset: Vector3, val size: Vector3, val dragged: Vector3, val grid: Float?) {
        var lastTarget: Frame? = null
    }

    class Transform(val pos: Vector3, val x: Vector3, val y: Vector3, val z: Vector3) {
        fun apply(cf: CFrame): CFrame {
            val c0 = cf.rightVector; val c1 = cf.upVector; val c2 = cf.backVector
            val n0 = rot(c0); val n1 = rot(c1); val n2 = rot(c2)
            val p = rot(cf.position) + pos
            return CFrame(p.x, p.y, p.z, n0.x, n1.x, n2.x, n0.y, n1.y, n2.y, n0.z, n1.z, n2.z)
        }

        fun rot(v: Vector3) = x * v.x + y * v.y + z * v.z
    }

    private fun roundGrid(v: Float, g: Float) = floor(v / g + 0.5f) * g

    private fun frameOf(cf: CFrame) = Frame(cf.position, cf.rightVector, cf.upVector, cf.backVector)

    private fun closestBasis(n: Vector3): Pair<Vector3, Vector3> {
        val axes = listOf(Vector3.X, Vector3.Y, Vector3.Z, -Vector3.X, -Vector3.Y, -Vector3.Z)
        var best = Vector3.X
        var bestD = Float.POSITIVE_INFINITY
        for (a in axes) {
            val d = abs(a.dot(n))
            if (d < bestD) { bestD = d; best = a }
        }
        var z = best.cross(n)
        if (z.magnitude < 1e-5f) z = if (abs(n.x) > 0.9f) Vector3.Y else Vector3.X
        z = z.unit
        return (-(best.cross(z))) to z
    }

    private val EDGES = listOf(0 to 1, 2 to 3, 0 to 2, 1 to 3, 4 to 5, 6 to 7, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)

    fun surfaceFrame(cf: CFrame, size: Vector3, hit: Vector3, n: Vector3): Frame {
        val h = size * 0.5f
        val r = cf.rightVector; val u = cf.upVector; val b = cf.backVector
        val p = cf.position
        fun v(x: Float, y: Float, z: Float) = p + r * (x * h.x) + u * (y * h.y) + b * (z * h.z)
        val verts = listOf(
            v(1f, 1f, 1f), v(1f, 1f, -1f), v(-1f, 1f, 1f), v(-1f, 1f, -1f),
            v(1f, -1f, 1f), v(1f, -1f, -1f), v(-1f, -1f, 1f), v(-1f, -1f, -1f),
        )
        val facePts = listOf(verts[0] to r, verts[2] to -r, verts[0] to u, verts[4] to -u, verts[0] to b, verts[1] to -b)
        var face: Vector3? = null
        var bestD = Float.POSITIVE_INFINITY
        for ((pt, fn) in facePts) {
            val d = abs((hit - pt).dot(fn))
            if (d < bestD) { bestD = d; face = pt }
        }
        var bestEdge: Triple<Int, Int, Float>? = null
        if (face != null) {
            for ((a, c) in EDGES) {
                val pa = verts[a]; val pb = verts[c]
                if ((pa - face).dot(n) < 0.001f && (pb - face).dot(n) < 0.001f) {
                    val dir = (pb - pa).unit
                    val closest = pa + dir * (hit - pa).dot(dir)
                    val d = (hit - closest).magnitude
                    if (bestEdge == null || d < bestEdge.third) bestEdge = Triple(a, c, d)
                }
            }
        }
        if (bestEdge != null) {
            val pa = verts[bestEdge.first]; val pb = verts[bestEdge.second]
            val origin = if ((pa - hit).magnitude < (pb - hit).magnitude) pa else pb
            val edge = (pb - pa).unit
            val x = n.cross(edge)
            if (x.magnitude > 1e-5f) {
                val xu = x.unit
                return Frame(origin, xu, n, xu.cross(n))
            }
        }
        val (x, z) = closestBasis(n)
        return Frame(hit, x, n, z)
    }

    fun begin(parts: List<Pair<CFrame, Vector3>>, objects: Int, basisCf: CFrame, click: Vector3, clicked: Frame?, grid: Float?): State {
        val basis0 = frameOf(basisCf)
        var lo = Vector3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        var hi = Vector3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for ((cf, size) in parts) {
            val c = basis0.toLocal(cf.position - basis0.pos)
            val c0 = basis0.toLocal(cf.rightVector).abs()
            val c1 = basis0.toLocal(cf.upVector).abs()
            val c2 = basis0.toLocal(cf.backVector).abs()
            val half = (c0 * size.x + c1 * size.y + c2 * size.z) * 0.5f
            lo = lo.min(c - half)
            hi = hi.max(c + half)
        }
        var basis = basis0
        var offset = (lo + hi) * 0.5f
        val size = hi - lo
        if (objects != 1) {
            basis = Frame(basis0.pos + basis0.toWorldDir(offset), basis0.x, basis0.y, basis0.z)
            offset = Vector3.ZERO
        }
        var grab = click
        if (clicked != null && grid != null) {
            val l = clicked.toLocal(click - clicked.pos)
            grab = clicked.pos + clicked.x * roundGrid(l.x, grid) + clicked.y * l.y + clicked.z * roundGrid(l.z, grid)
        }
        val dragged = basis.toLocal(grab - basis.pos)
        return State(basis, offset, size, dragged, grid)
    }

    fun surface(st: State, o: Vector3, d: Vector3, hit: Vector3?, n: Vector3?, cf: CFrame?, size: Vector3?): Surf? {
        if (hit != null && n != null && cf != null && size != null) return Surf(hit, surfaceFrame(cf, size, hit, n))
        val m = st.lastTarget ?: return null
        val dn = d.dot(m.y)
        if (abs(dn) < 1e-9f) return null
        val t = (m.pos - o).dot(m.y) / dn
        if (!t.isFinite() || t <= 0f) return null
        return Surf(o + d * t, m)
    }

    private fun roundIfExact(x: Vector3, y: Vector3, z: Vector3): Triple<Vector3, Vector3, Vector3> {
        fun close(v: Vector3) = maxOf(abs(v.x - Math.round(v.x)), abs(v.y - Math.round(v.y)), abs(v.z - Math.round(v.z))) < 1e-6f
        fun r(v: Vector3) = Vector3(Math.round(v.x).toFloat(), Math.round(v.y).toFloat(), Math.round(v.z).toFloat())
        return if (close(x) && close(y) && close(z)) Triple(r(x), r(y), r(z)) else Triple(x, y, z)
    }

    fun target(st: State, s: Surf): Transform {
        val m = s.m
        st.lastTarget = m
        val b = st.basis
        val rt0 = m.toLocal(b.x); val rt1 = m.toLocal(b.y); val rt2 = m.toLocal(b.z)
        val size = rt0.abs() * st.size.x + rt1.abs() * st.size.y + rt2.abs() * st.size.z
        val l = m.toLocal(s.hit - m.pos)
        val gx = st.grid?.let { roundGrid(l.x, it) - l.x } ?: 0f
        val gz = st.grid?.let { roundGrid(l.z, it) - l.z } ?: 0f
        val x = l.x + gx
        val z = l.z + gz
        val base = m.pos + m.x * x + m.z * z
        val od = st.offset - st.dragged
        val rtOd = rt0 * od.x + rt1 * od.y + rt2 * od.z
        val lift = 0.5f * size.y - rtOd.y
        fun mr(v: Vector3) = m.x * v.x + m.y * v.y + m.z * v.z
        val (rx, ry, rz) = roundIfExact(mr(rt0), mr(rt1), mr(rt2))
        val pos = base + m.y * lift - (rx * st.dragged.x + ry * st.dragged.y + rz * st.dragged.z)
        val tx = Vector3(rx.x * b.x.x + ry.x * b.y.x + rz.x * b.z.x, rx.y * b.x.x + ry.y * b.y.x + rz.y * b.z.x, rx.z * b.x.x + ry.z * b.y.x + rz.z * b.z.x)
        val ty = Vector3(rx.x * b.x.y + ry.x * b.y.y + rz.x * b.z.y, rx.y * b.x.y + ry.y * b.y.y + rz.y * b.z.y, rx.z * b.x.y + ry.z * b.y.y + rz.z * b.z.y)
        val tz = Vector3(rx.x * b.x.z + ry.x * b.y.z + rz.x * b.z.z, rx.y * b.x.z + ry.y * b.y.z + rz.y * b.z.z, rx.z * b.x.z + ry.z * b.y.z + rz.z * b.z.z)
        val bpos = tx * b.pos.x + ty * b.pos.y + tz * b.pos.z
        return Transform(pos - bpos, tx, ty, tz)
    }
}
