package com.leaf.lemonade

import com.leaf.lemonade.format.RbxBinaryReader
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.BinaryString
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer tool: lists places in TERRAIN_SCAN (a folder) whose Terrain has voxel data
 * (SmoothGrid), and writes the biggest few grids to TERRAIN_OUT for format checks.
 */
class TerrainScanTest {
    @Test
    fun scan() {
        val dir = System.getenv("TERRAIN_SCAN"); val out = System.getenv("TERRAIN_OUT")
        assumeTrue(dir != null && out != null)
        val sb = StringBuilder()
        val found = ArrayList<Pair<String, ByteArray>>()
        val seen = HashSet<Long>()
        for (f in File(dir!!).walkTopDown().filter { it.isFile && it.extension == "rbxl" }) {
            if (!seen.add(f.length()) || f.length() > 25_000_000L) continue
            val b = try { f.readBytes() } catch (_: Exception) { continue }
            if (!RbxFile.isBinary(b)) continue
            val doc = try { RbxBinaryReader(b).read() } catch (_: Throwable) { System.gc(); continue }
            val t = doc.roots.firstOrNull { it.className == "Workspace" }?.children?.firstOrNull { it.className == "Terrain" } ?: continue
            val g = t["SmoothGrid"]
            val bytes = when (g) { is BinaryString -> g.bytes; is String -> g.toByteArray(Charsets.UTF_8); else -> null } ?: continue
            sb.appendLine("${f.name}: SmoothGrid ${bytes.size} bytes, head=${bytes.take(24).joinToString(" ") { "%02x".format(it) }}; props=${t.props.keys}")
            if (bytes.size > 40) found.add(f.name to bytes)
        }
        val o = File(out!!).also { it.mkdirs() }
        found.sortedBy { it.second.size }.forEachIndexed { i, (n, bytes) -> File(o, "grid_${i}_${bytes.size}.bin").writeBytes(bytes); sb.appendLine("grid_$i = $n") }
        File(o, "report.txt").writeText(sb.toString())
        println(sb)
    }
}
