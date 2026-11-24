package com.leaf.lemonade.engine

import com.leaf.lemonade.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class RayHit(val part: Instance, val t: Float, val position: Vector3, val normal: Vector3)

open class ConvexShape(val verts: List<Vector3>, val normals: List<Vector3>, val edges: List<Vector3>) {
    val center: Vector3 = verts.fold(Vector3.ZERO) { a, b -> a + b } / verts.size.toFloat()

    open fun project(axis: Vector3): Pair<Float, Float> {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in verts) { val d = v.dot(axis); if (d < mn) mn = d; if (d > mx) mx = d }
        return mn to mx
    }
}

/**
 * A ball for the separating-axis test: its projection is exact on any axis; the candidate axes
 * are 13 directions around it (plus the other shape's own), which separates it from boxes well.
 */
class SphereShape(c: Vector3, val r: Float, axes: List<Vector3>) : ConvexShape(listOf(c), axes, emptyList()) {
    override fun project(axis: Vector3): Pair<Float, Float> {
        val d = center.dot(axis); val l = axis.magnitude
        return (d - r * l) to (d + r * l)
    }
}

object Geometry {
    private val H = 0.5f

    /** Local-space planes (normal, offset) of a part's collision shape, scaled. */
    private fun planes(shape: Int, s: Vector3): List<Pair<Vector3, Float>> {
        val hx = s.x * H; val hy = s.y * H; val hz = s.z * H
        val box = listOf(
            Vector3.X to hx, -Vector3.X to hx, Vector3.Y to hy, -Vector3.Y to hy, Vector3.Z to hz, -Vector3.Z to hz,
        )
        return when (shape) {
            3 -> {
                val n = Vector3(0f, s.z, -s.y).unit
                listOf(Vector3.X to hx, -Vector3.X to hx, -Vector3.Y to hy, Vector3.Z to hz, n to 0f)
            }
            else -> box
        }
    }

    /** Ray vs part. Returns distance along the ray and the world normal. */
    fun rayPart(o: Vector3, d: Vector3, part: Instance): Pair<Float, Vector3>? {
        // Unions and meshes: their real triangles (unless CollisionFidelity is Box).
        if (CollisionMeshes.hasGeometry(part)) CollisionMeshes.forPart(part)?.let { m -> return CollisionMeshes.ray(m, o, d, 1e6f) }
        val cf = PartData.cframe(part)
        val size = PartData.size(part)
        val shape = PartData.shape(part)
        val lo = cf.pointToObject(o)
        val ld = cf.vectorToObject(d)
        if (shape == 0) {
            val r = min(size.x, min(size.y, size.z)) * H
            val b = lo.dot(ld); val c = lo.dot(lo) - r * r
            val disc = b * b - c
            if (disc < 0) return null
            val sq = sqrt(disc)
            var t = -b - sq
            if (t < 0) t = -b + sq
            if (t < 0) return null
            val hitLocal = lo + ld * t
            return t to cf.vectorToWorld(hitLocal.unit)
        }
        var tEnter = -Float.MAX_VALUE; var tExit = Float.MAX_VALUE
        var enterN = Vector3.Y
        for ((n, off) in planes(shape, size)) {
            val denom = n.dot(ld)
            val dist = off - n.dot(lo)
            if (abs(denom) < 1e-8f) {
                if (dist < 0) return null
                continue
            }
            val t = dist / denom
            if (denom < 0) { if (t > tEnter) { tEnter = t; enterN = n } } else { if (t < tExit) tExit = t }
            if (tEnter > tExit) return null
        }
        if (tExit < 0) return null
        val t = if (tEnter >= 0) tEnter else 0f
        return t to cf.vectorToWorld(enterN)
    }

    fun raycast(parts: List<Instance>, o: Vector3, d: Vector3, maxDist: Float, filter: (Instance) -> Boolean): RayHit? {
        var best: RayHit? = null
        for (p in parts) {
            if (!filter(p)) continue
            val r = rayPart(o, d, p) ?: continue
            if (r.first > maxDist) continue
            if (best == null || r.first < best.t) best = RayHit(p, r.first, o + d * r.first, r.second)
        }
        return best
    }

    fun corners(cf: CFrame, size: Vector3): List<Vector3> {
        val h = size * H
        return List(8) { i ->
            cf * Vector3(if (i and 1 != 0) h.x else -h.x, if (i and 2 != 0) h.y else -h.y, if (i and 4 != 0) h.z else -h.z)
        }
    }

    fun worldAabb(cf: CFrame, size: Vector3): Pair<Vector3, Vector3> {
        val h = size * H
        val ex = Vector3(
            abs(cf.r00) * h.x + abs(cf.r01) * h.y + abs(cf.r02) * h.z,
            abs(cf.r10) * h.x + abs(cf.r11) * h.y + abs(cf.r12) * h.z,
            abs(cf.r20) * h.x + abs(cf.r21) * h.y + abs(cf.r22) * h.z,
        )
        val p = cf.position
        return (p - ex) to (p + ex)
    }

    fun partAabb(p: Instance) = worldAabb(PartData.cframe(p), PartData.size(p))

    fun boundsOf(parts: List<Instance>): Pair<Vector3, Vector3>? {
        if (parts.isEmpty()) return null
        var mn = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var mx = -mn
        for (p in parts) { val (a, b) = partAabb(p); mn = mn.min(a); mx = mx.max(b) }
        return mn to mx
    }

    /** 13 directions (box faces, edges and corners) used as extra separating axes for round shapes. */
    private val ROUND_AXES = listOf(
        Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f),
        Vector3(1f, 1f, 0f), Vector3(1f, -1f, 0f), Vector3(1f, 0f, 1f), Vector3(1f, 0f, -1f), Vector3(0f, 1f, 1f), Vector3(0f, 1f, -1f),
        Vector3(1f, 1f, 1f), Vector3(1f, 1f, -1f), Vector3(1f, -1f, 1f), Vector3(-1f, 1f, 1f),
    ).map { it.unit }

    /**
     * Collision contact between [shape] (bounds lo..hi) and [part]: the push that moves the shape
     * out of the part, and the top (world y) of what was hit (for stepping up). Unions and
     * MeshParts use their geometry per CollisionFidelity; other parts their primitive shape.
     */
    fun contact(shape: ConvexShape, lo: Vector3, hi: Vector3, part: Instance): Pair<Vector3, Float>? {
        if (CollisionMeshes.hasGeometry(part)) CollisionMeshes.forPart(part)?.let { return CollisionMeshes.push(it, shape, lo, hi) }
        val m = sat(shape, shapeOf(part)) ?: return null
        return m to partAabb(part).second.y
    }

    fun shapeOf(part: Instance, cfOverride: CFrame? = null): ConvexShape {
        val cf = cfOverride ?: PartData.cframe(part)
        val s = PartData.size(part)
        val h = s * H
        val ax = listOf(cf.rightVector, cf.upVector, cf.backVector)
        // The Shape property itself (a SpecialMesh changes the look, not the collision).
        when (((part["shape"] ?: part["Shape"]) as? EnumValue)?.value) {
            0 -> {
                // Balls are round.
                return SphereShape(cf.position, min(s.x, min(s.y, s.z)) * H, ROUND_AXES.map { cf.vectorToWorld(it) })
            }
            2 -> {
                // Cylinders (along X): a 12-sided prism.
                val r = min(s.y, s.z) * H
                val ring = (0 until 12).map { k -> val a = Math.PI * 2 * k / 12; Vector3(0f, (kotlin.math.cos(a) * r).toFloat(), (kotlin.math.sin(a) * r).toFloat()) }
                val verts = ring.flatMap { listOf(cf * Vector3(-h.x, it.y, it.z), cf * Vector3(h.x, it.y, it.z)) }
                val sideN = (0 until 6).map { k -> val a = Math.PI * 2 * (k + 0.5) / 12; cf.vectorToWorld(Vector3(0f, kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())) }
                val sideE = (0 until 6).map { k -> cf.vectorToWorld(ring[(k + 1) % 12] - ring[k]).unit }
                return ConvexShape(verts, listOf(cf.rightVector) + sideN, listOf(cf.rightVector) + sideE)
            }
        }
        if (PartData.shape(part) == 3) {
            val local = listOf(
                Vector3(-h.x, -h.y, -h.z), Vector3(h.x, -h.y, -h.z), Vector3(-h.x, -h.y, h.z), Vector3(h.x, -h.y, h.z),
                Vector3(-h.x, h.y, h.z), Vector3(h.x, h.y, h.z),
            )
            val slopeN = cf.vectorToWorld(Vector3(0f, s.z, -s.y).unit)
            val slopeD = cf.vectorToWorld(Vector3(0f, s.y, s.z).unit)
            return ConvexShape(local.map { cf * it }, ax + slopeN, ax + slopeD)
        }
        return ConvexShape(corners(cf, s), ax, ax)
    }

    fun aabbShape(center: Vector3, half: Vector3): ConvexShape {
        val v = List(8) { i ->
            center + Vector3(if (i and 1 != 0) half.x else -half.x, if (i and 2 != 0) half.y else -half.y, if (i and 4 != 0) half.z else -half.z)
        }
        val ax = listOf(Vector3.X, Vector3.Y, Vector3.Z)
        return ConvexShape(v, ax, ax)
    }

    /** Minimum translation to push A out of B (pointing from B towards A), or null if not overlapping. */
    fun sat(a: ConvexShape, b: ConvexShape): Vector3? {
        val axes = ArrayList<Vector3>(a.normals.size + b.normals.size + 16)
        axes.addAll(a.normals); axes.addAll(b.normals)
        for (ea in a.edges) for (eb in b.edges) {
            val c = ea.cross(eb)
            if (c.magnitude > 1e-4f) axes.add(c.unit)
        }
        var best = Float.MAX_VALUE
        var bestAxis = Vector3.Y
        for (ax in axes) {
            val (a0, a1) = a.project(ax)
            val (b0, b1) = b.project(ax)
            val o = min(a1 - b0, b1 - a0)
            if (o <= 0f) return null
            if (o < best) { best = o; bestAxis = ax }
        }
        if ((a.center - b.center).dot(bestAxis) < 0) bestAxis = -bestAxis
        return bestAxis * best
    }

    fun aabbOverlap(a0: Vector3, a1: Vector3, b0: Vector3, b1: Vector3, pad: Float = 0f) =
        a0.x <= b1.x + pad && a1.x >= b0.x - pad && a0.y <= b1.y + pad && a1.y >= b0.y - pad && a0.z <= b1.z + pad && a1.z >= b0.z - pad

    fun maxOf3(v: Vector3) = max(v.x, max(v.y, v.z))
}
