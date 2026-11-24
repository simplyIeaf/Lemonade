package com.leaf.lemonade.engine

import com.leaf.lemonade.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Constructive solid geometry on polygon soups with BSP trees (the algorithm from Evan
 * Wallace's csg.js). Used by Studio's solid modeling: Union, Negate, Intersect and Separate.
 */
object Csg {
    private const val EPS = 1e-4f

    class Vertex(val pos: Vector3, val normal: Vector3) {
        fun flipped() = Vertex(pos, -normal)
        fun lerp(o: Vertex, t: Float) = Vertex(pos.lerp(o.pos, t), normal.lerp(o.normal, t))
    }

    class Plane(val normal: Vector3, val w: Float) {
        fun flipped() = Plane(-normal, -w)

        fun split(p: Polygon, coplanarFront: MutableList<Polygon>, coplanarBack: MutableList<Polygon>, front: MutableList<Polygon>, back: MutableList<Polygon>) {
            val COPLANAR = 0; val FRONT = 1; val BACK = 2; val SPANNING = 3
            var type = 0
            val types = IntArray(p.vertices.size)
            for ((i, v) in p.vertices.withIndex()) {
                val t = normal.dot(v.pos) - w
                val ty = if (t < -EPS) BACK else if (t > EPS) FRONT else COPLANAR
                type = type or ty
                types[i] = ty
            }
            when (type) {
                COPLANAR -> (if (normal.dot(p.plane.normal) > 0) coplanarFront else coplanarBack).add(p)
                FRONT -> front.add(p)
                BACK -> back.add(p)
                else -> {
                    val f = ArrayList<Vertex>(); val b = ArrayList<Vertex>()
                    val n = p.vertices.size
                    for (i in 0 until n) {
                        val j = (i + 1) % n
                        val ti = types[i]; val tj = types[j]
                        val vi = p.vertices[i]; val vj = p.vertices[j]
                        if (ti != BACK) f.add(vi)
                        if (ti != FRONT) b.add(if (ti != BACK) vi else vi)
                        if ((ti or tj) == SPANNING) {
                            val t = (w - normal.dot(vi.pos)) / normal.dot(vj.pos - vi.pos)
                            val v = vi.lerp(vj, t)
                            f.add(v); b.add(v)
                        }
                    }
                    if (f.size >= 3) front.add(Polygon(f, p.shared, p.plane))
                    if (b.size >= 3) back.add(Polygon(b, p.shared, p.plane))
                }
            }
        }

        companion object {
            fun from(a: Vector3, b: Vector3, c: Vector3): Plane? {
                val n = (b - a).cross(c - a)
                if (n.magnitude < 1e-9f) return null
                val u = n.unit
                return Plane(u, u.dot(a))
            }
        }
    }

    /** A convex polygon; [shared] identifies the source part's look (index into a style table). */
    class Polygon(val vertices: List<Vertex>, val shared: Int, val plane: Plane) {
        fun flipped() = Polygon(vertices.reversed().map { it.flipped() }, shared, plane.flipped())
    }

    class Node(polys: List<Polygon>? = null) {
        var plane: Plane? = null
        var front: Node? = null
        var back: Node? = null
        var polygons = ArrayList<Polygon>()

        init { if (polys != null) build(polys) }

        fun clone(): Node {
            val n = Node()
            n.plane = plane; n.front = front?.clone(); n.back = back?.clone(); n.polygons = ArrayList(polygons)
            return n
        }

        fun invert() {
            for (i in polygons.indices) polygons[i] = polygons[i].flipped()
            plane = plane?.flipped()
            front?.invert(); back?.invert()
            val t = front; front = back; back = t
        }

        fun clipPolygons(polys: List<Polygon>): List<Polygon> {
            val pl = plane ?: return ArrayList(polys)
            var f = ArrayList<Polygon>(); var b = ArrayList<Polygon>()
            for (p in polys) pl.split(p, f, b, f, b)
            f = ArrayList(front?.clipPolygons(f) ?: f)
            b = if (back != null) ArrayList(back!!.clipPolygons(b)) else ArrayList()
            f.addAll(b)
            return f
        }

        fun clipTo(bsp: Node) {
            polygons = ArrayList(bsp.clipPolygons(polygons))
            front?.clipTo(bsp); back?.clipTo(bsp)
        }

        fun allPolygons(): List<Polygon> {
            val out = ArrayList<Polygon>(polygons)
            front?.let { out.addAll(it.allPolygons()) }
            back?.let { out.addAll(it.allPolygons()) }
            return out
        }

        fun build(polys: List<Polygon>) {
            if (polys.isEmpty()) return
            // Iterative over depth to keep the stack small for large meshes.
            var node = this
            var work: List<Polygon> = polys
            while (true) {
                if (work.isEmpty()) return
                if (node.plane == null) node.plane = work[0].plane
                val f = ArrayList<Polygon>(); val b = ArrayList<Polygon>()
                for (p in work) node.plane!!.split(p, node.polygons, node.polygons, f, b)
                if (b.isNotEmpty()) { if (node.back == null) node.back = Node(); node.back!!.build(b) }
                if (f.isEmpty()) return
                if (node.front == null) node.front = Node()
                node = node.front!!
                work = f
            }
        }
    }

    fun union(a: List<Polygon>, b: List<Polygon>): List<Polygon> {
        val na = Node(a); val nb = Node(b)
        na.clipTo(nb); nb.clipTo(na); nb.invert(); nb.clipTo(na); nb.invert()
        na.build(nb.allPolygons())
        return na.allPolygons()
    }

    fun subtract(a: List<Polygon>, b: List<Polygon>): List<Polygon> {
        val na = Node(a); val nb = Node(b)
        na.invert(); na.clipTo(nb); nb.clipTo(na); nb.invert(); nb.clipTo(na); nb.invert()
        na.build(nb.allPolygons()); na.invert()
        return na.allPolygons()
    }

    fun intersect(a: List<Polygon>, b: List<Polygon>): List<Polygon> {
        val na = Node(a); val nb = Node(b)
        na.invert(); nb.clipTo(na); nb.invert(); na.clipTo(nb); nb.clipTo(na)
        na.build(nb.allPolygons()); na.invert()
        return na.allPolygons()
    }

    // ------------------------------------------------------------------ part shapes

    private fun poly(pts: List<Vector3>, shared: Int, smooth: List<Vector3>? = null): Polygon? {
        val pl = Plane.from(pts[0], pts[1], pts[2]) ?: return null
        return Polygon(pts.mapIndexed { i, p -> Vertex(p, smooth?.get(i) ?: pl.normal) }, shared, pl)
    }

    /** Polygons of a part's shape in world space (or in the space of [toLocal] if given). */
    fun partPolygons(p: Instance, shared: Int, toLocal: CFrame? = null, cache: (Instance) -> List<Polygon>? = { null }): List<Polygon> {
        val cf0 = PartData.cframe(p)
        val cf = toLocal?.let { it.inverse() * cf0 } ?: cf0
        if (p.isA("PartOperation") && p.className != "NegateOperation") {
            // A union inside a union: reuse its result, moved and scaled to where it is now.
            val inner = cache(p) ?: return emptyList()
            return inner.map { pg -> transform(pg, cf, shared) }
        }
        val s = PartData.size(p)
        val h = s * 0.5f
        val out = ArrayList<Polygon>()
        fun add(vararg pts: Vector3) { poly(pts.map { cf * it }, shared)?.let { out.add(it) } }
        when (PartData.shape(p)) {
            0 -> { // ball
                val r = minOf(s.x, s.y, s.z) * 0.5f
                val rings = 10; val segs = 16
                fun pt(i: Int, j: Int): Vector3 {
                    val th = PI * i / rings; val ph = 2 * PI * j / segs
                    return Vector3((sin(th) * cos(ph)).toFloat() * r, cos(th).toFloat() * r, (sin(th) * sin(ph)).toFloat() * r)
                }
                for (i in 0 until rings) for (j in 0 until segs) {
                    val a = pt(i, j); val b = pt(i, j + 1); val c = pt(i + 1, j + 1); val d = pt(i + 1, j)
                    val pts = if (i == 0) listOf(a, c, d) else if (i == rings - 1) listOf(a, b, c) else listOf(a, b, c, d)
                    val world = pts.map { cf * it }
                    val nrms = pts.map { cf.vectorToWorld(it.unit) }
                    val pl = Plane.from(world[0], world[1], world[2]) ?: continue
                    // Outward winding: flip if needed.
                    if (pl.normal.dot(nrms[0]) < 0) {
                        val rw = world.reversed(); val rn = nrms.reversed()
                        Plane.from(rw[0], rw[1], rw[2])?.let { out.add(Polygon(rw.mapIndexed { k, q -> Vertex(q, rn[k]) }, shared, it)) }
                    } else out.add(Polygon(world.mapIndexed { k, q -> Vertex(q, nrms[k]) }, shared, pl))
                }
            }
            2 -> { // cylinder along X
                val r = minOf(s.y, s.z) * 0.5f
                val segs = 20
                val ring = (0 until segs).map { k -> val a = 2 * PI * k / segs; Vector3(0f, (cos(a) * r).toFloat(), (sin(a) * r).toFloat()) }
                for (k in 0 until segs) {
                    val a = ring[k]; val b = ring[(k + 1) % segs]
                    val pts = listOf(Vector3(-h.x, a.y, a.z), Vector3(h.x, a.y, a.z), Vector3(h.x, b.y, b.z), Vector3(-h.x, b.y, b.z))
                    val nrms = listOf(Vector3(0f, a.y, a.z).unit, Vector3(0f, a.y, a.z).unit, Vector3(0f, b.y, b.z).unit, Vector3(0f, b.y, b.z).unit)
                    val world = pts.map { cf * it }
                    val wn = nrms.map { cf.vectorToWorld(it) }
                    var pl = Plane.from(world[0], world[1], world[2]) ?: continue
                    if (pl.normal.dot(wn[0]) < 0) {
                        val rw = world.reversed(); val rn = wn.reversed()
                        pl = Plane.from(rw[0], rw[1], rw[2]) ?: continue
                        out.add(Polygon(rw.mapIndexed { i, q -> Vertex(q, rn[i]) }, shared, pl))
                    } else out.add(Polygon(world.mapIndexed { i, q -> Vertex(q, wn[i]) }, shared, pl))
                }
                val capR = ring.map { Vector3(h.x, it.y, it.z) }
                val capL = ring.map { Vector3(-h.x, it.y, it.z) }.reversed()
                poly(capR.map { cf * it }, shared)?.let { out.add(orient(it, cf.vectorToWorld(Vector3.X))) }
                poly(capL.map { cf * it }, shared)?.let { out.add(orient(it, cf.vectorToWorld(-Vector3.X))) }
            }
            3 -> { // wedge: slope rises from the front-bottom edge to the back-top edge
                val bl = Vector3(-h.x, -h.y, -h.z); val br = Vector3(h.x, -h.y, -h.z); val blb = Vector3(-h.x, -h.y, h.z); val brb = Vector3(h.x, -h.y, h.z)
                val tl = Vector3(-h.x, h.y, h.z); val tr = Vector3(h.x, h.y, h.z)
                addOriented(out, listOf(bl, br, brb, blb), cf, shared, Vector3(0f, -1f, 0f))
                addOriented(out, listOf(blb, brb, tr, tl), cf, shared, Vector3.Z)
                addOriented(out, listOf(bl, br, tr, tl), cf, shared, Vector3(0f, s.z, -s.y).unit)
                addOriented(out, listOf(bl, blb, tl), cf, shared, -Vector3.X)
                addOriented(out, listOf(br, brb, tr), cf, shared, Vector3.X)
            }
            4 -> { // corner wedge
                val a = Vector3(h.x, h.y, -h.z)
                val p0 = Vector3(-h.x, -h.y, -h.z); val p1 = Vector3(h.x, -h.y, -h.z); val p2 = Vector3(h.x, -h.y, h.z); val p3 = Vector3(-h.x, -h.y, h.z)
                val c = Vector3(h.x * 0.4f, -h.y * 0.5f, -h.z * 0.4f)
                for (f in listOf(listOf(p0, p1, p2, p3), listOf(p1, p2, a), listOf(p0, p1, a), listOf(p0, p3, a), listOf(p3, p2, a))) {
                    val mid = f.fold(Vector3.ZERO) { x, y -> x + y } / f.size.toFloat()
                    addOriented(out, f, cf, shared, (mid - c).unit, approximate = true)
                }
            }
            else -> {
                val c = Array(8) { i -> Vector3(if (i and 1 != 0) h.x else -h.x, if (i and 2 != 0) h.y else -h.y, if (i and 4 != 0) h.z else -h.z) }
                addOriented(out, listOf(c[0], c[2], c[3], c[1]), cf, shared, -Vector3.Z)
                addOriented(out, listOf(c[4], c[5], c[7], c[6]), cf, shared, Vector3.Z)
                addOriented(out, listOf(c[0], c[1], c[5], c[4]), cf, shared, -Vector3.Y)
                addOriented(out, listOf(c[2], c[6], c[7], c[3]), cf, shared, Vector3.Y)
                addOriented(out, listOf(c[0], c[4], c[6], c[2]), cf, shared, -Vector3.X)
                addOriented(out, listOf(c[1], c[3], c[7], c[5]), cf, shared, Vector3.X)
            }
        }
        return out
    }

    private fun orient(p: Polygon, outward: Vector3): Polygon = if (p.plane.normal.dot(outward) < 0) p.flipped() else p

    private fun addOriented(out: MutableList<Polygon>, local: List<Vector3>, cf: CFrame, shared: Int, outwardLocal: Vector3, approximate: Boolean = false) {
        val p = poly(local.map { cf * it }, shared) ?: return
        val o = cf.vectorToWorld(outwardLocal)
        out.add(if (p.plane.normal.dot(o) < 0 || (approximate && p.plane.normal.dot(o) < 0)) p.flipped() else p)
    }

    /** Moves (and scales, via [cf] built with scale baked in) a polygon. */
    private fun transform(p: Polygon, cf: CFrame, shared: Int): Polygon {
        val vs = p.vertices.map { Vertex(cf * it.pos, cf.vectorToWorld(it.normal).unit) }
        val pl = Plane.from(vs[0].pos, vs[1].pos, vs[2].pos) ?: p.plane
        return Polygon(vs, shared, pl)
    }

    /** Triangulates polygons (fan) into position+normal+uv floats, grouped by [Polygon.shared]. */
    fun toTriangles(polys: List<Polygon>): Map<Int, FloatArray> {
        val groups = HashMap<Int, ArrayList<Float>>()
        for (p in polys) {
            val l = groups.getOrPut(p.shared) { ArrayList() }
            val v = p.vertices
            val n = p.plane.normal
            val flat = abs(v.sumOf { it.normal.dot(n).toDouble() } / v.size - 1.0) < 1e-3
            for (i in 1 until v.size - 1) for (q in listOf(v[0], v[i], v[i + 1])) {
                val nn = if (flat) n else q.normal
                l.add(q.pos.x); l.add(q.pos.y); l.add(q.pos.z); l.add(nn.x); l.add(nn.y); l.add(nn.z)
                // Planar UVs so materials still show a pattern on unions.
                val a = n.abs()
                if (a.y >= a.x && a.y >= a.z) { l.add(q.pos.x); l.add(q.pos.z) }
                else if (a.x >= a.z) { l.add(q.pos.z); l.add(q.pos.y) }
                else { l.add(q.pos.x); l.add(q.pos.y) }
            }
        }
        return groups.mapValues { it.value.toFloatArray() }
    }
}
