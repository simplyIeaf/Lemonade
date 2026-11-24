package com.leaf.lemonade

import com.leaf.lemonade.format.RbxFile
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer tool: writes every script of the plugin files in PLUGIN_DUMP (a folder of .rbxm) to
 * PLUGIN_DUMP_OUT, one .lua file per script named by its full path, plus a tree listing. Used to
 * see what a plugin does when it doesn't work. Skipped when the variables are unset.
 */
class PluginDumpTest {
    @Test
    fun dumpPluginScripts() {
        val dir = System.getenv("PLUGIN_DUMP"); val out = System.getenv("PLUGIN_DUMP_OUT")
        assumeTrue(dir != null && out != null)
        for (f in File(dir!!).listFiles()!!.filter { it.isFile }) {
            val doc = RbxFile.read(f.readBytes())
            val target = File(out!!, f.nameWithoutExtension).also { it.mkdirs() }
            val tree = StringBuilder()
            for (r in doc.roots) for (i in listOf(r) + r.descendants()) {
                var depth = 0; var p = i.parent; while (p != null) { depth++; p = p.parent }
                tree.appendLine("  ".repeat(depth) + "${i.className} \"${i.name}\"")
                val src = i["Source"] as? String ?: continue
                val path = generateSequence(i) { it.parent }.toList().reversed().joinToString(".") { it.name }.replace(Regex("[^A-Za-z0-9._-]"), "_")
                File(target, "$path.lua").writeText(src)
            }
            File(target, "_tree.txt").writeText(tree.toString())
        }
    }
}
