package com.leaf.lemonade

import com.leaf.lemonade.format.CsgMesh
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.BinaryString
import com.leaf.lemonade.model.SharedStr
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decodes the union meshes found in real model files (CSG_SAMPLES folder: PartOperationAsset
 * downloads or places with inline MeshData) and checks the triangles against their normals.
 */
class CsgMeshTest {
    @Test
    fun decodeRealUnionMeshes() {
        val dir = System.getenv("CSG_SAMPLES")
        assumeTrue(dir != null)
        val report = StringBuilder()
        var bad = 0; var count = 0
        for (f in File(dir!!).listFiles()!!.filter { it.isFile }) {
            val doc = try { RbxFile.read(f.readBytes()) } catch (_: Exception) { continue }
            for (i in doc.roots.flatMap { listOf(it) + it.descendants() }) {
                val raw = when (val v = i.props["MeshData"] ?: i.props["MeshData2"]) {
                    is BinaryString -> v.bytes; is SharedStr -> v.bytes; is String -> v.toByteArray(Charsets.UTF_8); else -> null
                } ?: continue
                if (raw.size < 16) continue
                count++
                try {
                    val m = CsgMesh.decode(raw)
                    var agree = 0; var disagree = 0
                    val p = m.pos; val n = m.nrm
                    var t = 0
                    while (t + 2 < m.indices.size) {
                        val a = m.indices[t]; val b = m.indices[t + 1]; val c = m.indices[t + 2]; t += 3
                        val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
                        val wx = p[c * 3] - p[a * 3]; val wy = p[c * 3 + 1] - p[a * 3 + 1]; val wz = p[c * 3 + 2] - p[a * 3 + 2]
                        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
                        val ln = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz); if (ln < 1e-9f) continue
                        val d = (nx * (n[a * 3] + n[b * 3] + n[c * 3]) + ny * (n[a * 3 + 1] + n[b * 3 + 1] + n[c * 3 + 1]) + nz * (n[a * 3 + 2] + n[b * 3 + 2] + n[c * 3 + 2])) / ln
                        if (d > 0.2f) agree++ else if (d < -0.2f) disagree++
                    }
                    var mn = FloatArray(3) { Float.MAX_VALUE }; var mx = FloatArray(3) { -Float.MAX_VALUE }
                    for (k in 0 until m.vertexCount) for (c in 0..2) { mn[c] = minOf(mn[c], p[k * 3 + c]); mx[c] = maxOf(mx[c], p[k * 3 + c]) }
                    val frac = if (agree + disagree == 0) 1f else maxOf(agree, disagree).toFloat() / (agree + disagree)
                    val ok = frac > 0.85f && m.indices.size >= 3
                    if (!ok) bad++
                    val colors = m.colors.distinct().take(4).joinToString { Integer.toHexString(it) }
                    report.appendLine("${if (ok) "OK " else "BAD"} ${f.name.take(12)} ${i.className} verts=${m.vertexCount} tris=${m.indices.size / 3} agree=${"%.3f".format(frac)} (${if (agree >= disagree) "ccw" else "cw"}) " +
                        "size=${(0..2).joinToString("x") { "%.2f".format(mx[it] - mn[it]) }} center=${(0..2).joinToString(",") { "%.2f".format((mx[it] + mn[it]) / 2) }} colors=$colors")
                } catch (e: Exception) {
                    bad++
                    report.appendLine("FAIL ${f.name.take(12)} ${i.className}: $e")
                }
            }
        }
        report.appendLine("meshes=$count bad=$bad")
        File(System.getProperty("java.io.tmpdir"), "csg_report.txt").writeText(report.toString())
        assertTrue(report.toString(), bad == 0)
    }
}
