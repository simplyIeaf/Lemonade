package com.leaf.lemonade

import com.leaf.lemonade.render.RbxMesh
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decodes real Roblox mesh files (MESH_SAMPLES folder, e.g. Draco-compressed version 7 meshes) and
 * checks the result is sane: finite data, unit normals, and vertex normals agreeing with the
 * triangles' own orientation. Skipped when the variable is unset.
 */
class MeshDecodeTest {
    @Test
    fun decodeRealMeshes() {
        val dir = System.getenv("MESH_SAMPLES")
        assumeTrue(dir != null)
        val files = File(dir!!).listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        val report = StringBuilder()
        var bad = 0
        for (f in files) {
            val bytes = f.readBytes()
            val head = String(bytes, 0, minOf(12, bytes.size), Charsets.ISO_8859_1)
            try {
                val m = RbxMesh.parse(bytes)
                val v = m.verts
                var agree = 0; var disagree = 0; var nonUnit = 0
                for (t in 0 until m.triangles) {
                    val o = t * 24
                    for (i in 0 until 24) if (!v[o + i].isFinite()) throw AssertionError("non-finite value")
                    val ax = v[o]; val ay = v[o + 1]; val az = v[o + 2]
                    val ux = v[o + 8] - ax; val uy = v[o + 9] - ay; val uz = v[o + 10] - az
                    val wx = v[o + 16] - ax; val wy = v[o + 17] - ay; val wz = v[o + 18] - az
                    val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
                    val ln = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                    if (ln < 1e-9f) continue
                    var sx = 0f; var sy = 0f; var sz = 0f
                    for (k in 0 until 3) {
                        val q = o + k * 8 + 3
                        val l = kotlin.math.sqrt(v[q] * v[q] + v[q + 1] * v[q + 1] + v[q + 2] * v[q + 2])
                        if (kotlin.math.abs(l - 1f) > 0.02f) nonUnit++
                        sx += v[q]; sy += v[q + 1]; sz += v[q + 2]
                    }
                    val d = (nx * sx + ny * sy + nz * sz) / ln
                    if (d > 0.2f) agree++ else if (d < -0.2f) disagree++
                }
                val total = agree + disagree
                val frac = if (total == 0) 1f else maxOf(agree, disagree).toFloat() / total
                val size = (0..2).joinToString("x") { "%.2f".format(m.size(it)) }
                // Old formats can hold unnormalized normals or double-sided sheets; judge the chunked ones.
                val chunked = head.startsWith("version 6") || head.startsWith("version 7")
                val ok = !chunked || (frac > 0.8f && nonUnit < m.triangles)
                if (!ok) bad++
                report.appendLine("${if (ok) "OK " else "BAD"} ${f.name.take(12)} [$head] tris=${m.triangles} size=$size normalAgreement=${"%.3f".format(frac)} nonUnit=$nonUnit")
            } catch (e: Throwable) {
                bad++
                report.appendLine("FAIL ${f.name.take(12)} [$head]: $e")
            }
        }
        report.appendLine("files=${files.size} bad=$bad")
        File(System.getProperty("java.io.tmpdir"), "mesh_report.txt").writeText(report.toString())
        assertTrue("bad meshes: $bad\n$report", bad == 0)
    }
}
