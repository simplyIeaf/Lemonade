package com.leaf.lemonade

import com.leaf.lemonade.lua.LuauTranspiler
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File

/**
 * Transpiles every .lua file in LUAU_DIR (e.g. a PluginDumpTest output folder) and compiles the
 * result with LuaJ; reports files that fail, with the failing transpiled lines.
 */
class TranspileFileTest {
    @Test
    fun transpileAll() {
        val dir = System.getenv("LUAU_DIR")
        assumeTrue(dir != null)
        val g = JsePlatform.standardGlobals()
        val sb = StringBuilder()
        var ok = 0; var bad = 0
        for (f in File(dir!!).walkTopDown().filter { it.isFile && it.extension == "lua" }) {
            val src = f.readText()
            LuauTranspiler.lastFailure = null
            val out = try { LuauTranspiler.transpile(src) } catch (e: Throwable) { sb.append("TRANSPILE FAIL ${f.name}: $e\n"); bad++; continue }
            LuauTranspiler.lastFailure?.let { sb.append("PARSE STOP ${f.name}: $it\n") }
            val loaded = try { g.load(out, "=x"); true } catch (e: Throwable) {
                // Same fallback as LuaRuntime.compileLuau for LuaJ's upvalue limit.
                try { g.load(LuauTranspiler.transpile(src, hoistTopLocals = true), "=x"); sb.append("HOISTED ${f.name}\n"); true } catch (_: Throwable) { false }
            }
            if (loaded) ok++ else try { g.load(out, "=x"); ok++ } catch (e: Throwable) {
                bad++
                val m = Regex(":(\\d+):").find(e.message ?: "")
                val line = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val lines = out.lines()
                val srcLines = src.lines()
                sb.append("COMPILE FAIL ${f.name}: ${e.message}\n")
                for (i in (line - 3).coerceAtLeast(1)..(line + 1).coerceAtMost(lines.size)) {
                    sb.append("  out $i: ${lines[i - 1]}\n")
                    if (i <= srcLines.size) sb.append("  src $i: ${srcLines[i - 1]}\n")
                }
            }
        }
        sb.insert(0, "ok=$ok bad=$bad\n")
        File(System.getProperty("java.io.tmpdir"), "transpile_report.txt").writeText(sb.toString())
    }
}
