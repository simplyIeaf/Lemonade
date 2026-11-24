package com.leaf.lemonade

import com.leaf.lemonade.render.RbxMesh
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Developer tool: measures Roblox's R6 body meshes (R6_MESHES = Studio's content/avatar/meshes)
 * so the app's own generated R6 shapes can match their size and edge rounding. Nothing from the
 * files is kept; only the printed numbers were used.
 */
class R6MeshMeasureTest {
    @Test
    fun measure() {
        val dir = System.getenv("R6_MESHES")
        assumeTrue(dir != null)
        val sb = StringBuilder()
        for (n in listOf("torso", "leftarm", "leftleg")) {
            val m = RbxMesh.parse(File(dir, "$n.mesh").readBytes())
            val v = m.verts
            sb.appendLine("$n tris=${m.triangles} min=${m.min.joinToString()} max=${m.max.joinToString()}")
            // On each axis: how far the flat face extends before the rounded edge starts.
            for (axis in 0..2) {
                val face = m.max[axis]
                var flat = FloatArray(3)
                val profile = java.util.TreeSet<String>()
                var i = 0
                while (i < v.size) {
                    if (abs(v[i + axis] - face) < 1e-3f) for (o in 0..2) if (o != axis) flat[o] = maxOf(flat[o], abs(v[i + o] - (m.max[o] + m.min[o]) / 2f))
                    // Corner profile in the plane of the other two axes, for vertices near +max on both.
                    val a1 = (axis + 1) % 3
                    if (v[i + axis] > face - 0.25f && v[i + a1] > m.max[a1] - 0.25f) profile.add("%.3f,%.3f".format(face - v[i + axis], m.max[a1] - v[i + a1]))
                    i += 8
                }
                sb.appendLine("  face +${"XYZ"[axis]}: flat half-extents ${flat.joinToString { "%.3f".format(it) }}; corner profile ${profile.take(24)}")
            }
        }
        File(System.getProperty("java.io.tmpdir"), "r6_measure.txt").writeText(sb.toString())
        println(sb)
    }
}
