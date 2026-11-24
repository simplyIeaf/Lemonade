package com.leaf.lemonade

import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.BinaryString
import com.leaf.lemonade.model.Instance
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer tool: scans Roblox's asset cache (CURVE_SCAN = rbx-storage folder) for CurveAnimation
 * assets and writes their structure plus FloatCurve ValuesAndTimes bytes (hex) to CURVE_OUT, and
 * copies each such asset body next to it. Skipped when the variables are unset.
 */
class CurveScanTest {
    @Test
    fun scan() {
        val dir = System.getenv("CURVE_SCAN"); val out = System.getenv("CURVE_OUT")
        assumeTrue(dir != null && out != null)
        val outDir = File(out!!).also { it.mkdirs() }
        val report = StringBuilder()
        var n = 0
        for (f in File(dir!!).walkTopDown().filter { it.isFile }) {
            val b = try { f.readBytes() } catch (_: Exception) { continue }
            val i = indexOf(b, "<roblox".toByteArray(), 1024)
            if (i < 0) continue
            val body = b.copyOfRange(i, b.size)
            val doc = try { RbxFile.read(body) } catch (_: Exception) { continue }
            val all = doc.roots.flatMap { listOf(it) + it.descendants() }
            if (all.none { it.className == "CurveAnimation" }) continue
            n++
            File(outDir, "curve_$n.rbxm").writeBytes(body)
            report.appendLine("== ${f.name} -> curve_$n.rbxm")
            for (r in doc.roots) dump(r, 0, report)
            if (n >= 6) break
        }
        File(outDir, "report.txt").writeText(report.toString())
        println("found $n")
    }

    /** Decodes the CurveAnimation files in CURVE_SAMPLES (made by [scan]) into clips. */
    @Test
    fun decodeSamples() {
        val dir = System.getenv("CURVE_SAMPLES")
        assumeTrue(dir != null)
        val sb = StringBuilder()
        for (f in File(dir!!).listFiles()!!.filter { it.extension == "rbxm" }.sortedBy { it.name }) {
            val doc = RbxFile.read(f.readBytes())
            val ca = doc.roots.flatMap { listOf(it) + it.descendants() }.first { it.className == "CurveAnimation" }
            val clip = com.leaf.lemonade.engine.CurveAnim.from(ca)
            org.junit.Assert.assertNotNull(f.name, clip)
            sb.appendLine("${f.name} ${ca.name}: length=${clip!!.length}s looped=${clip.looped} joints=${clip.jointNames.sorted()}")
            val j = clip.jointNames.first { it == "RightUpperArm" || it == "LeftUpperLeg" || true }
            sb.appendLine("  $j @0=${clip.sample(j, 0f)?.toOrientation()} @mid=${clip.sample(j, clip.length / 2)?.toOrientation()}")
        }
        File(System.getProperty("java.io.tmpdir"), "curve_decode.txt").writeText(sb.toString())
        println(sb)
    }

    @Test
    fun decodesFloatCurveBytes() {
        // Two linear keys: 0 at tick 0, 1 at tick 2400 (one second).
        val bb = java.nio.ByteBuffer.allocate(16 + 2 * 14 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(2); bb.putInt(2)
        bb.put(1); bb.put(0); bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(0f)
        bb.put(1); bb.put(0); bb.putFloat(1f); bb.putFloat(0f); bb.putFloat(0f)
        bb.putInt(1); bb.putInt(2); bb.putInt(0); bb.putInt(2400)
        val keys = com.leaf.lemonade.engine.CurveAnim.decode(bb.array())
        org.junit.Assert.assertEquals(2, keys.size)
        org.junit.Assert.assertEquals(1f, keys[1].t, 1e-6f)
        org.junit.Assert.assertEquals(0.25f, com.leaf.lemonade.engine.CurveAnim.eval(keys, 0.25f), 1e-6f)
    }

    private fun dump(i: Instance, depth: Int, sb: StringBuilder) {
        sb.append("  ".repeat(depth)).append(i.className).append(" \"").append(i.name).append("\"")
        for ((k, v) in i.props) {
            if (k == "Name") continue
            val s = when (v) {
                is BinaryString -> "bin[" + v.bytes.joinToString(" ") { "%02x".format(it) } + "]"
                else -> v.toString()
            }
            sb.append(" ").append(k).append("=").append(s.take(4000))
        }
        sb.appendLine()
        if (depth < 7) for (c in i.children) dump(c, depth + 1, sb)
    }

    private fun indexOf(b: ByteArray, pat: ByteArray, limit: Int): Int {
        val end = minOf(b.size - pat.size, limit)
        outer@ for (i in 0..end) { for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer; return i }
        return -1
    }
}
