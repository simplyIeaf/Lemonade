package com.leaf.lemonade

import com.leaf.lemonade.format.RbxBinaryReader
import com.leaf.lemonade.format.RbxBinaryWriter
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.Instance
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Parses real .rbxl/.rbxm files listed in the RBX_SAMPLES environment variable (a folder),
 * then writes and re-reads them to check round-trip fidelity. Skipped when the variable is unset.
 */
class RealFilesTest {
    @Test
    fun parseAndRoundTripRealFiles() {
        val dir = System.getenv("RBX_SAMPLES")
        assumeTrue(dir != null)
        val files = File(dir!!).walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("rbxl", "rbxm") && !it.path.contains("__MACOSX") }
            .take(40).toList()
        val report = StringBuilder()
        var failures = 0
        for (f in files) {
            val bytes = f.readBytes()
            if (!RbxFile.isBinary(bytes)) { report.appendLine("SKIP (not binary) ${f.name}"); continue }
            try {
                val doc = RbxBinaryReader(bytes).read()
                val all = doc.roots.flatMap { listOf(it) + it.descendants() }
                val props = all.sumOf { it.props.size }
                val out = RbxBinaryWriter.write(doc.roots, doc.meta)
                val doc2 = RbxBinaryReader(out).read()
                val all2 = doc2.roots.flatMap { listOf(it) + it.descendants() }
                val diffs = compare(all, all2)
                report.appendLine("OK ${f.name}: ${all.size} instances, $props props, ${doc.warnings.size} warnings" +
                    (if (doc.warnings.isNotEmpty()) " [${doc.warnings.distinct().take(3).joinToString("; ")}]" else "") +
                    ", roundtrip diffs=${diffs.size}" + (if (diffs.isNotEmpty()) " ${diffs.take(3)}" else ""))
                if (diffs.isNotEmpty()) failures++
            } catch (e: Throwable) {
                failures++
                report.appendLine("FAIL ${f.name}: $e")
            }
        }
        File(System.getProperty("java.io.tmpdir"), "rbx_report.txt").writeText(report.toString())
        println(report)
    }

    private fun compare(a: List<Instance>, b: List<Instance>): List<String> {
        val out = ArrayList<String>()
        if (a.size != b.size) { out.add("count ${a.size} vs ${b.size}"); return out }
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.className != y.className) { out.add("class@$i"); continue }
            for ((k, v) in x.props) {
                val w = y.props[k]
                val same = when {
                    v is Instance || w is Instance -> (v == null) == (w == null) && (v as? Instance)?.className == (w as? Instance)?.className
                    v is Float && w is Float -> v == w || (v.isNaN() && w.isNaN())
                    v is com.leaf.lemonade.model.RawPropValue -> w is com.leaf.lemonade.model.RawPropValue &&
                        v.type == w.type && v.payload.contentEquals(w.payload)
                    else -> v == w
                }
                if (!same) out.add("${x.className}.$k: $v -> $w")
            }
        }
        return out
    }
}
