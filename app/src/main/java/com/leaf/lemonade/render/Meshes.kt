package com.leaf.lemonade.render

import android.opengl.GLES20
import com.leaf.lemonade.model.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A vertex buffer; [stride] is the number of 4-byte slots per vertex. [cpu] keeps the
 * position+normal data of the built-in shapes so static batches can bake them into world space.
 */
class Mesh(val vbo: Int, val count: Int, val mode: Int, val stride: Int = 6, val cpu: FloatArray? = null)

/** Builds interleaved position+normal triangle lists, oriented outward from a center point. */
class MeshBuilder(private val center: Vector3 = Vector3.ZERO) {
    val data = ArrayList<Float>()

    private fun put(p: Vector3, n: Vector3) { data.add(p.x); data.add(p.y); data.add(p.z); data.add(n.x); data.add(n.y); data.add(n.z) }

    fun tri(a: Vector3, b: Vector3, c: Vector3) {
        var n = (b - a).cross(c - a).unit
        val mid = (a + b + c) / 3f
        if (n.dot(mid - center) < 0) { put(a, -n); put(c, -n); put(b, -n) } else { put(a, n); put(b, n); put(c, n) }
    }

    fun quad(a: Vector3, b: Vector3, c: Vector3, d: Vector3) { tri(a, b, c); tri(a, c, d) }

    fun smoothTri(a: Vector3, na: Vector3, b: Vector3, nb: Vector3, c: Vector3, nc: Vector3) {
        val fn = (b - a).cross(c - a)
        val mid = (a + b + c) / 3f
        if (fn.dot(mid - center) < 0) { put(a, na); put(c, nc); put(b, nb) } else { put(a, na); put(b, nb); put(c, nc) }
    }

    fun upload(): Mesh { val arr = data.toFloatArray(); return Meshes.upload(arr, 6, GLES20.GL_TRIANGLES, arr) }
}

object Meshes {
    fun upload(arr: FloatArray, stride: Int, mode: Int, cpu: FloatArray? = null): Mesh {
        val buf = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(arr).position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, arr.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return Mesh(ids[0], arr.size / stride, mode, stride, cpu)
    }

    private fun v(x: Float, y: Float, z: Float) = Vector3(x, y, z)
    private const val H = 0.5f

    fun cube(): Mesh {
        val b = MeshBuilder()
        val c = Array(8) { i -> v(if (i and 1 != 0) H else -H, if (i and 2 != 0) H else -H, if (i and 4 != 0) H else -H) }
        b.quad(c[0], c[1], c[3], c[2]) // -Z
        b.quad(c[4], c[5], c[7], c[6]) // +Z
        b.quad(c[0], c[1], c[5], c[4]) // -Y
        b.quad(c[2], c[3], c[7], c[6]) // +Y
        b.quad(c[0], c[2], c[6], c[4]) // -X
        b.quad(c[1], c[3], c[7], c[5]) // +X
        return b.upload()
    }

    /**
     * A box of nominal size [sx]x[sy]x[sz] studs with every edge cut at 45° by [c] studs, in unit
     * space like [cube]. Roblox draws R6 character limbs this way (2x2x1 torso, 1x2x1 limbs with
     * ~0.065-stud bevels) instead of as sharp blocks. Each vertex keeps the normal of the face it
     * belongs to, so lighting rounds the bevels off.
     */
    fun chamferBox(sx: Float, sy: Float, sz: Float, c: Float): Mesh {
        val b = MeshBuilder()
        val h = floatArrayOf(sx / 2f, sy / 2f, sz / 2f)
        val i = FloatArray(3) { (h[it] - c).coerceAtLeast(0f) }
        val size = floatArrayOf(sx, sy, sz)
        fun p(a: FloatArray) = v(a[0] / size[0], a[1] / size[1], a[2] / size[2])
        fun axis(a: Int, s: Float) = v(if (a == 0) s else 0f, if (a == 1) s else 0f, if (a == 2) s else 0f)
        fun pt(vararg xyz: Float) = xyz.copyOf()
        // Faces.
        for (a in 0..2) for (s in floatArrayOf(-1f, 1f)) {
            val b1 = (a + 1) % 3; val b2 = (a + 2) % 3
            fun corner(u: Float, w: Float): FloatArray { val q = FloatArray(3); q[a] = s * h[a]; q[b1] = u * i[b1]; q[b2] = w * i[b2]; return q }
            val n = axis(a, s)
            val q0 = p(corner(-1f, -1f)); val q1 = p(corner(1f, -1f)); val q2 = p(corner(1f, 1f)); val q3 = p(corner(-1f, 1f))
            b.smoothTri(q0, n, q1, n, q2, n); b.smoothTri(q0, n, q2, n, q3, n)
        }
        // Edges: a strip between the faces of axes a and b, running along k.
        for (a in 0..2) for (bb in a + 1..2) for (sa in floatArrayOf(-1f, 1f)) for (sb in floatArrayOf(-1f, 1f)) {
            val k = 3 - a - bb
            fun e(onA: Boolean, t: Float): FloatArray {
                val q = FloatArray(3)
                q[a] = sa * (if (onA) h[a] else i[a]); q[bb] = sb * (if (onA) i[bb] else h[bb]); q[k] = t * i[k]
                return q
            }
            val na = axis(a, sa); val nb = axis(bb, sb)
            val a0 = p(e(true, -1f)); val a1 = p(e(true, 1f)); val b0 = p(e(false, -1f)); val b1 = p(e(false, 1f))
            b.smoothTri(a0, na, a1, na, b1, nb); b.smoothTri(a0, na, b1, nb, b0, nb)
        }
        // Corners: one triangle joining the three faces.
        for (x in floatArrayOf(-1f, 1f)) for (y in floatArrayOf(-1f, 1f)) for (z in floatArrayOf(-1f, 1f)) {
            val pa = p(pt(x * h[0], y * i[1], z * i[2])); val pb = p(pt(x * i[0], y * h[1], z * i[2])); val pc = p(pt(x * i[0], y * i[1], z * h[2]))
            b.smoothTri(pa, axis(0, x), pb, axis(1, y), pc, axis(2, z))
        }
        return b.upload()
    }

    fun wedge(): Mesh {
        val b = MeshBuilder(v(0f, -0.2f, 0.2f))
        val bl = v(-H, -H, -H); val br = v(H, -H, -H); val blb = v(-H, -H, H); val brb = v(H, -H, H)
        val tl = v(-H, H, H); val tr = v(H, H, H)
        b.quad(bl, br, brb, blb)     // bottom
        b.quad(blb, brb, tr, tl)     // back
        b.quad(bl, br, tr, tl)       // slope
        b.tri(bl, blb, tl)           // left
        b.tri(br, brb, tr)           // right
        return b.upload()
    }

    fun cornerWedge(): Mesh {
        val b = MeshBuilder(v(0.2f, -0.25f, -0.2f))
        val a = v(H, H, -H)
        val p0 = v(-H, -H, -H); val p1 = v(H, -H, -H); val p2 = v(H, -H, H); val p3 = v(-H, -H, H)
        b.quad(p0, p1, p2, p3)
        b.tri(p1, p2, a)
        b.tri(p0, p1, a)
        b.tri(p0, p3, a)
        b.tri(p3, p2, a)
        return b.upload()
    }

    fun sphere(rings: Int = 14, segs: Int = 24): Mesh {
        val b = MeshBuilder()
        fun pt(r: Int, s: Int): Vector3 {
            val th = PI * r / rings; val ph = 2 * PI * s / segs
            return v((sin(th) * cos(ph)).toFloat() * H, cos(th).toFloat() * H, (sin(th) * sin(ph)).toFloat() * H)
        }
        for (r in 0 until rings) for (s in 0 until segs) {
            val a = pt(r, s); val c = pt(r + 1, s); val d = pt(r + 1, s + 1); val e = pt(r, s + 1)
            if (r != 0) b.smoothTri(a, a.unit, c, c.unit, e, e.unit)
            if (r != rings - 1) b.smoothTri(c, c.unit, d, d.unit, e, e.unit)
        }
        return b.upload()
    }

    /** Cylinder along X, like Roblox's cylinder part. */
    fun cylinder(segs: Int = 24): Mesh {
        val b = MeshBuilder()
        fun ring(i: Int): Pair<Float, Float> { val a = 2 * PI * i / segs; return (cos(a) * H).toFloat() to (sin(a) * H).toFloat() }
        for (i in 0 until segs) {
            val (y0, z0) = ring(i); val (y1, z1) = ring(i + 1)
            val n0 = v(0f, y0, z0).unit; val n1 = v(0f, y1, z1).unit
            val a = v(-H, y0, z0); val c = v(H, y0, z0); val d = v(H, y1, z1); val e = v(-H, y1, z1)
            b.smoothTri(a, n0, c, n0, d, n1)
            b.smoothTri(a, n0, d, n1, e, n1)
            b.tri(v(H, 0f, 0f), v(H, y0, z0), v(H, y1, z1))
            b.tri(v(-H, 0f, 0f), v(-H, y1, z1), v(-H, y0, z0))
        }
        return b.upload()
    }

    /** Profile point for a surface of revolution around Y: radius, height, and 2D normal. */
    private class Prof(val r: Float, val y: Float, val nr: Float, val ny: Float)

    private fun revolve(segments: List<Pair<Prof, Prof>>, segs: Int): Mesh {
        val b = MeshBuilder()
        fun p3(p: Prof, a: Double) = v((cos(a) * p.r).toFloat(), p.y, (sin(a) * p.r).toFloat())
        fun n3(p: Prof, a: Double) = v((cos(a) * p.nr).toFloat(), p.ny, (sin(a) * p.nr).toFloat()).unit
        for ((p, q) in segments) for (i in 0 until segs) {
            val a0 = 2 * PI * i / segs; val a1 = 2 * PI * (i + 1) / segs
            val pa = p3(p, a0); val pb = p3(p, a1); val qa = p3(q, a0); val qb = p3(q, a1)
            val npa = n3(p, a0); val npb = n3(p, a1); val nqa = n3(q, a0); val nqb = n3(q, a1)
            if (p.r > 1e-4f) b.smoothTri(pa, npa, pb, npb, qb, nqb)
            if (q.r > 1e-4f) b.smoothTri(pa, npa, qb, nqb, qa, nqa)
        }
        return b.upload()
    }

    /** Roblox's classic head: a cylinder along Y with rounded top and bottom edges (unit size). */
    fun head(segs: Int = 28): Mesh {
        val bev = 0.2f
        val pts = ArrayList<Prof>()
        pts.add(Prof(0f, -H, 0f, -1f))
        val steps = 6
        for (i in 0..steps) {
            val a = -PI / 2 + (PI / 2) * i / steps
            pts.add(Prof(H - bev + (cos(a) * bev).toFloat(), -H + bev + (sin(a) * bev).toFloat(), cos(a).toFloat(), sin(a).toFloat()))
        }
        for (i in 0..steps) {
            val a = (PI / 2) * i / steps
            pts.add(Prof(H - bev + (cos(a) * bev).toFloat(), H - bev + (sin(a) * bev).toFloat(), cos(a).toFloat(), sin(a).toFloat()))
        }
        pts.add(Prof(0f, H, 0f, 1f))
        return revolve(pts.zipWithNext(), segs)
    }

    /** Cylinder along Y (CylinderMesh / SpecialMesh cylinder). */
    fun cylinderY(segs: Int = 24): Mesh = revolve(listOf(
        Prof(0f, -H, 0f, -1f) to Prof(H, -H, 0f, -1f),
        Prof(H, -H, 1f, 0f) to Prof(H, H, 1f, 0f),
        Prof(H, H, 0f, 1f) to Prof(0f, H, 0f, 1f),
    ), segs)

    /**
     * Textured patch wrapped around the front (-Z) of the head mesh, for the face decal.
     * Layout: position(3) + uv(2).
     */
    fun headFace(): Mesh {
        val cols = 16; val rows = 8
        val arc = Math.toRadians(150.0)
        val r = H * 1.012f
        val yTop = H - 0.2f + 0.06f; val yBot = -H + 0.2f - 0.06f
        val data = ArrayList<Float>()
        fun put(i: Int, j: Int) {
            val u = i / cols.toFloat(); val vv = j / rows.toFloat()
            val a = -PI / 2 - arc / 2 + arc * u // centered on -Z
            data.add((cos(a) * r).toFloat()); data.add(yTop + (yBot - yTop) * vv); data.add((sin(a) * r).toFloat())
            // texture: map the visible band onto the middle of the face image
            data.add(1f - u); data.add(0.12f + 0.76f * vv)
        }
        for (i in 0 until cols) for (j in 0 until rows) {
            put(i, j); put(i, j + 1); put(i + 1, j + 1)
            put(i, j); put(i + 1, j + 1); put(i + 1, j)
        }
        return upload(data.toFloatArray(), 5, GLES20.GL_TRIANGLES)
    }

    /** Unit quad in the XY plane facing +Z with UVs. Layout: position(3) + uv(2). */
    fun quad(): Mesh = upload(floatArrayOf(
        -H, -H, 0f, 0f, 1f, H, -H, 0f, 1f, 1f, H, H, 0f, 1f, 0f,
        -H, -H, 0f, 0f, 1f, H, H, 0f, 1f, 0f, -H, H, 0f, 0f, 0f,
    ), 5, GLES20.GL_TRIANGLES)

    /** Full-screen triangle pair in clip space. Layout: position(2). */
    fun screenQuad(): Mesh = upload(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f), 2, GLES20.GL_TRIANGLES)

    fun boxLines(): Mesh {
        val c = Array(8) { i -> floatArrayOf(if (i and 1 != 0) H else -H, if (i and 2 != 0) H else -H, if (i and 4 != 0) H else -H) }
        val edges = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)
        val arr = FloatArray(edges.size * 3)
        edges.forEachIndexed { i, e -> c[e].copyInto(arr, i * 3) }
        return upload(arr, 3, GLES20.GL_LINES)
    }
}
