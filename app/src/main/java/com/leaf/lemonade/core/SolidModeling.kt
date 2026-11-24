package com.leaf.lemonade.core

import com.leaf.lemonade.engine.Csg
import com.leaf.lemonade.engine.Geometry
import com.leaf.lemonade.model.*
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Studio's solid modeling (Model tab): Union, Negate, Intersect and Separate.
 *
 * A union made here keeps the parts it was made from as its children, the way Studio keeps
 * them for Separate. Those parts aren't drawn or simulated on their own; the union draws the
 * CSG result instead, moved and scaled with the union. Unions made in Roblox Studio don't keep
 * their parts; they're drawn from the mesh Roblox stored for them (see UnionMeshes).
 */
object SolidModeling {
    class Style(val color: Color3, val transparency: Float)

    /**
     * CSG output (unscaled), centered on the result's own bounding box, triangles grouped by style
     * index. [offset] is that center relative to the sources' bounds center; [initialSize] is the
     * result's size when it was made.
     */
    class Result(val polys: List<Csg.Polygon>, val tris: Map<Int, FloatArray>, val styles: List<Style>, val sig: Long, val initialSize: Vector3, val offset: Vector3)

    private val cache = Collections.synchronizedMap(WeakHashMap<Instance, Result>())
    private val failed = Collections.synchronizedMap(WeakHashMap<Instance, Long>())

    fun isOperation(i: Instance) = i.isA("PartOperation") && i.className != "NegateOperation"
    fun sources(op: Instance) = op.children.filter { it.isA("BasePart") }
    fun canSeparate(op: Instance) = isOperation(op) && sources(op).isNotEmpty()

    /** Center and size of the source parts' bounds: the union's reference frame. */
    private fun reference(srcs: List<Instance>): Pair<CFrame, Vector3>? {
        val (a, b) = Geometry.boundsOf(srcs) ?: return null
        return CFrame.at((a + b) / 2f) to (b - a)
    }

    private fun signature(op: Instance, srcs: List<Instance>): Long {
        var h = op.className.hashCode().toLong() * 31 + (op["UsePartColor"] == true).hashCode()
        for (s in srcs) {
            h = h * 31 + s.className.hashCode()
            h = h * 31 + PartData.cframe(s).hashCode()
            h = h * 31 + PartData.size(s).hashCode()
            h = h * 31 + PartData.shape(s)
            h = h * 31 + PartData.color(s).hashCode()
            if (isOperation(s)) h = h * 31 + (result(s)?.sig ?: 0L)
        }
        if (op["UsePartColor"] == true) h = h * 31 + PartData.color(op).hashCode()
        return h
    }

    /** The union's geometry, computed on first use and whenever its source parts change. */
    fun result(op: Instance): Result? {
        if (!isOperation(op)) return null
        val srcs = sources(op)
        if (srcs.isEmpty()) return null
        val sig = signature(op, srcs)
        cache[op]?.let { if (it.sig == sig) return it }
        failed[op]?.let { if (it == sig) return null }
        return try {
            compute(op, srcs, sig).also { cache[op] = it }
        } catch (e: Exception) {
            failed[op] = sig
            OutputLog.warn("Couldn't build ${op.name}: ${e.message ?: e}")
            null
        }
    }

    private fun compute(op: Instance, srcs: List<Instance>, sig: Long): Result {
        val (ref, size) = reference(srcs) ?: throw IllegalStateException("no parts")
        val styles = ArrayList<Style>()
        val usePart = op["UsePartColor"] == true
        fun polysOf(s: Instance): List<Csg.Polygon> {
            val idx = styles.size
            styles.add(Style(if (usePart) PartData.color(op) else PartData.color(s), PartData.transparency(s)))
            return Csg.partPolygons(s, idx, ref) { inner -> scaledPolys(inner) }
        }
        val positive = srcs.filter { it.className != "NegateOperation" }
        val negative = srcs.filter { it.className == "NegateOperation" }
        var acc: List<Csg.Polygon> = if (positive.isEmpty()) emptyList() else polysOf(positive[0])
        for (s in positive.drop(1)) acc = if (op.className == "IntersectOperation") Csg.intersect(acc, polysOf(s)) else Csg.union(acc, polysOf(s))
        for (n in negative) acc = Csg.subtract(acc, polysOf(n))
        // Center on what's left (a negated part can cut away a side), like Studio sizes unions.
        var mn = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); var mx = -mn
        for (p in acc) for (v in p.vertices) { mn = mn.min(v.pos); mx = mx.max(v.pos) }
        if (acc.isEmpty()) { mn = -size / 2f; mx = size / 2f }
        val c = (mn + mx) / 2f
        val centered = if (c.magnitude < 1e-5f) acc else acc.map { p ->
            Csg.Polygon(p.vertices.map { Csg.Vertex(it.pos - c, it.normal) }, p.shared, Csg.Plane(p.plane.normal, p.plane.w - p.plane.normal.dot(c)))
        }
        return Result(centered, Csg.toTriangles(centered), styles, sig, (mx - mn).max(Vector3(0.01f, 0.01f, 0.01f)), c)
    }

    /** A nested union's polygons in its own space, scaled to its current size. */
    private fun scaledPolys(op: Instance): List<Csg.Polygon>? {
        val r = result(op) ?: return storedPolys(op)
        val s = scaleOf(op, r)
        if (abs(s.x - 1f) < 1e-4f && abs(s.y - 1f) < 1e-4f && abs(s.z - 1f) < 1e-4f) return r.polys
        return r.polys.mapNotNull { p ->
            val vs = p.vertices.map { Csg.Vertex(it.pos * s, (it.normal / s).unit) }
            Csg.Plane.from(vs[0].pos, vs[1].pos, vs[2].pos)?.let { Csg.Polygon(vs, p.shared, it) }
        }
    }

    /** A union made in Roblox Studio (its stored mesh), centered and scaled to its current size. */
    private fun storedPolys(op: Instance): List<Csg.Polygon>? {
        if (!com.leaf.lemonade.render.UnionMeshes.isStored(op)) return null
        val d = com.leaf.lemonade.render.UnionMeshes.get(op) ?: return null
        val now = PartData.size(op)
        fun k(a: Float, b: Float) = if (b > 1e-4f) a / b else 1f
        val s = Vector3(k(now.x, d.size.x), k(now.y, d.size.y), k(now.z, d.size.z))
        return d.triangles.mapNotNull { t ->
            val pts = (0..2).map { Vector3(t[it * 3], t[it * 3 + 1], t[it * 3 + 2]) - d.center }.map { it * s }
            Csg.Plane.from(pts[0], pts[1], pts[2])?.let { pl -> Csg.Polygon(pts.map { Csg.Vertex(it, pl.normal) }, 0, pl) }
        }
    }

    /** How much the union was resized since it was made. */
    fun scaleOf(op: Instance, r: Result): Vector3 {
        val now = PartData.size(op)
        fun k(a: Float, b: Float) = if (b > 1e-5f) a / b else 1f
        return Vector3(k(now.x, r.initialSize.x), k(now.y, r.initialSize.y), k(now.z, r.initialSize.z))
    }

    // ------------------------------------------------------------------ operations (undoable)

    /** Panels listen on the UI thread; plugins may call these from the render thread. */
    private fun notify(studio: Studio) {
        studio.main.post { studio.listeners.forEach { it.onSelectionChanged(); it.onTreeChanged() } }
    }

    private fun copyPartProps(from: Instance, to: Instance) {
        for ((k, v) in from.props) if (k != "Name") { to.props[k] = v; from.propTypes[k]?.let { to.propTypes[k] = it } }
        to.props["Name"] = from.name
    }

    /** Negate: turns parts into negative parts (or back into normal parts). */
    fun negate(studio: Studio): Int {
        val out = ArrayList<Instance>()
        var n = 0
        synchronized(studio.lock) {
            val sel = studio.selection.filter { it.isA("BasePart") && !isOperation(it) && it.parent != null }
            if (sel.isEmpty()) return 0
            studio.undo.begin("Negate")
            for (p in sel) {
                val parent = p.parent ?: continue
                val idx = parent.children.indexOfFirst { it === p }
                val replacement: Instance
                if (p.className == "NegateOperation") {
                    val cls = (p.attributes["__cls"] as? String) ?: when (PartData.shape(p)) { 3 -> "WedgePart"; 4 -> "CornerWedgePart"; else -> "Part" }
                    replacement = InstanceFactory.create(cls)
                    copyPartProps(p, replacement)
                    if (cls != "Part") replacement.props.remove("shape")
                } else {
                    replacement = InstanceFactory.create("NegateOperation")
                    copyPartProps(p, replacement)
                    replacement.attributes["__cls"] = p.className
                    val shape = PartData.shape(p)
                    replacement.props["shape"] = EnumValue(shape); replacement.propTypes["shape"] = PropType.ENUM
                }
                for (c in p.children.toList()) studio.reparent(c, replacement)
                studio.reparent(p, null)
                studio.reparent(replacement, parent)
                // keep the Explorer order
                parent.children.remove(replacement)
                parent.children.add(idx.coerceIn(0, parent.children.size), replacement)
                out.add(replacement); n++
            }
            studio.undo.commit()
            studio.selection.clear(); studio.selection.addAll(out)
        }
        notify(studio)
        return n
    }

    /** Union (or Intersect) the selected parts into one part. */
    fun combine(studio: Studio, intersect: Boolean): String? {
        val op: Instance
        synchronized(studio.lock) {
            val parts = ArrayList<Instance>()
            for (s in studio.selection) {
                if (s.isA("BasePart")) { if (parts.none { it === s }) parts.add(s) }
                else if (!s.isService && (s.isA("Model") || s.className == "Folder")) s.descendants().filter { it.isA("BasePart") && it.parent?.isA("PartOperation") != true }.forEach { d -> if (parts.none { it === d }) parts.add(d) }
            }
            if (parts.size < 2 && parts.none { it.className == "NegateOperation" }) return "Select at least two parts to ${if (intersect) "intersect" else "union"}."
            if (parts.all { it.className == "NegateOperation" }) return "Select at least one part that isn't negated."
            val (ref, size) = reference(parts) ?: return "Nothing to combine."
            val first = parts.first { it.className != "NegateOperation" }
            val parent = first.parent ?: studio.model.workspace
            op = InstanceFactory.create(if (intersect) "IntersectOperation" else "UnionOperation")
            op.name = if (intersect) "Intersection" else "Union"
            op.set("CFrame", ref, false)
            op.set("size", size, false)
            op.set("Color3uint8", PartData.color(first), false)
            op.set("Material", EnumValue(PartData.material(first)), false)
            op.set("Anchored", parts.any { it["Anchored"] == true }, false)
            op.set("CanCollide", first["CanCollide"] != false, false)
            op.set("Transparency", PartData.transparency(first), false)
            op.set("UsePartColor", false, false)
            op.set("SmoothingAngle", 0f, false)
            op.set("RenderFidelity", EnumValue(1), false)
            op.set("CollisionFidelity", EnumValue(0), false)
            for (s in listOf("TopSurface", "BottomSurface", "FrontSurface", "BackSurface", "LeftSurface", "RightSurface")) op.props.remove(s)
            studio.undo.begin(if (intersect) "Intersect" else "Union")
            studio.reparent(op, parent)
            for (p in parts) studio.reparent(p, op)
            // Fit the union's box to the result (not the parts it was made from).
            result(op)?.let { r ->
                op.set("CFrame", ref + r.offset, false)
                op.set("size", r.initialSize, false)
            }
            studio.undo.commit()
            studio.selection.clear(); studio.selection.add(op)
        }
        if (result(op) == null) OutputLog.warn("${op.name} couldn't be computed; it will show as a box.")
        notify(studio)
        return null
    }

    /** Separate: puts unions (and negated parts) back into the parts they were made from. */
    fun separate(studio: Studio): String? {
        val freed = ArrayList<Instance>()
        var importedUnion = false
        synchronized(studio.lock) {
            val ops = studio.selection.filter { (isOperation(it) || it.className == "NegateOperation") && it.parent != null }
            if (ops.isEmpty()) return "Select a union, intersection or negated part to separate."
            studio.undo.begin("Separate")
            for (op in ops) {
                if (op.className == "NegateOperation") continue
                val srcs = sources(op)
                if (srcs.isEmpty()) { importedUnion = true; continue }
                val r = result(op)
                val (ref, _) = reference(srcs) ?: continue
                val s = if (r != null) scaleOf(op, r) else Vector3(1f, 1f, 1f)
                val opCf = PartData.cframe(op)
                val parent = op.parent ?: continue
                val off = r?.offset ?: Vector3.ZERO
                for (src in srcs) {
                    val local = ref.inverse() * PartData.cframe(src)
                    val pos = opCf * ((local.position - off) * s)
                    val rot = (opCf.rotationOnly() * local.rotationOnly())
                    val ax = listOf(local.rightVector, local.upVector, local.backVector).map { (it * s).magnitude }
                    val sz = PartData.size(src)
                    studio.reparent(src, parent)
                    studio.setProp(src, "CFrame", rot.withPosition(pos))
                    val key = if (src.props.containsKey("size")) "size" else "Size"
                    studio.setProp(src, key, Vector3(sz.x * ax[0], sz.y * ax[1], sz.z * ax[2]))
                    freed.add(src)
                }
                studio.reparent(op, null)
            }
            studio.undo.commit()
        }
        // Negated parts separate back into normal parts.
        synchronized(studio.lock) {
            val negs = studio.selection.filter { it.className == "NegateOperation" && it.parent != null }
            if (negs.isNotEmpty()) { studio.selection.clear(); studio.selection.addAll(negs) }
        }
        if (synchronized(studio.lock) { studio.selection.any { it.className == "NegateOperation" } }) negate(studio)
        else synchronized(studio.lock) { if (freed.isNotEmpty()) { studio.selection.clear(); studio.selection.addAll(freed) } }
        notify(studio)
        return if (importedUnion && freed.isEmpty()) "That union was made in Roblox Studio; the parts it was made from aren't stored in the file, so it can't be separated here." else null
    }
}
