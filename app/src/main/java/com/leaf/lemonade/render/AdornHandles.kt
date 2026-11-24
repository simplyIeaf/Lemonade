package com.leaf.lemonade.render

import com.leaf.lemonade.model.*

/**
 * Roblox Handles / ArcHandles adornments (the arrows and rings scripts and plugins such as
 * Building Tools by F3X attach to parts): where each handle is, for drawing and for touches.
 */
object AdornHandles {
    /** [id]: NormalId (Handles) or Axis 0..2 (ArcHandles); [axis]: world direction. */
    class Tip(val id: Int, val axis: Vector3, val base: Vector3, val tip: Vector3, val radius: Float, val color: Color3)

    private val normals = listOf(Vector3.X, Vector3.Y, Vector3.Z, -Vector3.X, -Vector3.Y, -Vector3.Z) // Right Top Back Left Bottom Front
    val NORMAL_NAMES = listOf("Right", "Top", "Back", "Left", "Bottom", "Front")
    val AXIS_NAMES = listOf("X", "Y", "Z")

    fun isHandles(a: Instance) = a.className == "Handles" || a.className == "ArcHandles"

    private fun adornee(a: Instance): Instance? = (a["Adornee"] as? Instance)?.takeIf { it.isA("BasePart") }

    fun tips(a: Instance, view: ViewState): List<Tip> {
        if (a["Visible"] == false) return emptyList()
        val p = adornee(a) ?: return emptyList()
        val cf = PartData.cframe(p)
        val size = PartData.size(p)
        val dist = view.distTo(cf.position).coerceAtLeast(1f)
        val gap = dist * 0.06f
        val r = dist * 0.018f
        val out = ArrayList<Tip>()
        if (a.className == "Handles") {
            val mask = (a["Faces"] as? Faces)?.mask ?: 0x3F
            val c = a["Color3"] as? Color3
            for (i in 0 until 6) {
                if (mask and (1 shl i) == 0) continue
                val ln = normals[i]
                val dir = cf.vectorToWorld(ln)
                val half = kotlin.math.abs(ln.x) * size.x / 2f + kotlin.math.abs(ln.y) * size.y / 2f + kotlin.math.abs(ln.z) * size.z / 2f
                val base = cf.position + dir * half
                val col = c ?: when (i % 3) { 0 -> Color3(0.95f, 0.25f, 0.25f); 1 -> Color3(0.3f, 0.9f, 0.3f); else -> Color3(0.25f, 0.5f, 1f) }
                out.add(Tip(i, dir, base, base + dir * gap, r, col))
            }
        } else {
            // ArcHandles: a grab point on each axis ring (shown at both ends of the axis).
            val mask = (a["Axes"] as? Axes)?.mask ?: 0x7
            val ext = maxOf(size.x, size.y, size.z) / 2f + gap
            for (ax in 0 until 3) {
                if (mask and (1 shl ax) == 0) continue
                val dir = cf.vectorToWorld(normals[ax])
                val col = when (ax) { 0 -> Color3(0.95f, 0.25f, 0.25f); 1 -> Color3(0.3f, 0.9f, 0.3f); else -> Color3(0.25f, 0.5f, 1f) }
                // The grab point sits on the ring, perpendicular to the axis.
                val perp = cf.vectorToWorld(normals[(ax + 1) % 3])
                out.add(Tip(ax, dir, cf.position, cf.position + perp * ext, r * 1.1f, col))
            }
        }
        return out
    }
}
