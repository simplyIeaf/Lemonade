package com.leaf.lemonade

import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.BinaryString
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Prints scripts from RBX_PLACE whose full name contains SCRIPT_MATCH (for debugging game scripts). */
class ScriptDumpTest {
    @Test
    fun dump() {
        val path = System.getenv("RBX_PLACE"); val match = System.getenv("SCRIPT_MATCH")
        assumeTrue(path != null && match != null)
        val doc = RbxFile.read(File(path!!).readBytes())
        val sb = StringBuilder()
        var n = 0
        for (r in doc.roots) for (i in listOf(r) + r.descendants()) {
            if (!i.isA("LuaSourceContainer")) continue
            val full = generateSequence(i) { it.parent }.toList().reversed().joinToString(".") { it.name }
            if (!full.contains(match!!)) continue
            val src = when (val s = i["Source"]) { is BinaryString -> String(s.bytes); else -> s?.toString() ?: "" }
            sb.append("==== $full (${i.className}) disabled=${i["Disabled"]} siblings=${i.parent?.children?.joinToString { it.name + ":" + it.className }}\n").append(src.take(4000)).append("\n")
            if (++n >= 3) break
        }
        File(System.getProperty("java.io.tmpdir"), "script_dump.txt").writeText(sb.toString())
    }
}
