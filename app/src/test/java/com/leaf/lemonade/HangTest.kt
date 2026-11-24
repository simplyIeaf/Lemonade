package com.leaf.lemonade

import com.leaf.lemonade.format.RbxBinaryReader
import com.leaf.lemonade.lua.LuauTranspiler
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HangTest {
    @Test
    fun transpilerTerminatesOnRealScripts() {
        val dir = System.getenv("RBX_SAMPLES")
        assumeTrue(dir != null)
        val report = StringBuilder()
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val g = JsePlatform.standardGlobals()
        var compileFails = 0; var total = 0
        for (f in File(dir!!).listFiles()!!.filter { it.name.endsWith(".rbxl") || it.name.endsWith(".rbxm") }) {
            val doc = RbxBinaryReader(f.readBytes()).read()
            for (s in doc.roots.flatMap { listOf(it) + it.descendants() }) {
                val src = s["Source"] as? String ?: continue
                total++
                val fut = pool.submit<String> { LuauTranspiler.transpile(src) }
                val out = try { fut.get(5, TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) {
                    report.appendLine("HANG ${f.name} ${s.fullName()}"); throw AssertionError("transpiler hang in ${s.fullName()}\n$report")
                }
                try { g.load(out, s.name) } catch (e: Exception) {
                    compileFails++
                    if (compileFails <= 25) report.appendLine("COMPILE ${f.name} ${s.fullName()}: ${e.message?.take(120)}")
                }
            }
        }
        report.appendLine("total=$total compileFails=$compileFails")
        File(System.getProperty("java.io.tmpdir"), "hang_report.txt").writeText(report.toString())
    }

    @Test
    fun countHookStopsInfiniteLoopInCoroutine() {
        val g = JsePlatform.standardGlobals()
        g.load(DebugLib())
        val sethook = g.get("debug").get("sethook")
        var calls = 0
        val hook = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs { if (++calls > 50) throw org.luaj.vm2.LuaError("timeout"); return LuaValue.NONE }
        }
        val co = LuaThread(g, g.load("while true do end"))
        sethook.invoke(LuaValue.varargsOf(arrayOf(co, hook, LuaValue.valueOf(""), LuaValue.valueOf(1000))))
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val r = pool.submit<Varargs> { co.resume(LuaValue.NONE) }.get(10, TimeUnit.SECONDS)
        assertTrue(!r.arg1().toboolean())
    }
}
