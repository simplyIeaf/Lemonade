package com.leaf.lemonade

import com.leaf.lemonade.lua.LuaSandbox
import org.junit.Assert.assertEquals
import org.junit.Test
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform

/**
 * Scripts and plugins are other people's code: they must not get at Java (reflection, files),
 * because from there they could read the saved Roblox sign-in and send it away.
 */
class LuaSandboxTest {
    /** Globals set up the way LuaRuntime does. */
    private fun globals(): Globals = JsePlatform.standardGlobals().also { LuaSandbox.lockDown(it) }

    private fun run(g: Globals, code: String): String = try {
        g.load(code, "=test").call().tojstring()
    } catch (e: Exception) { "error: ${e.message}" }

    @Test fun noWayBackToJava() {
        val g = globals()
        // The Java class loader behind Lua's package library (would give back luajava).
        assertEquals("nil", run(g, "return tostring(package)"))
        assertEquals("nil", run(g, "return tostring(rawget(_G, 'package'))"))
        assertEquals("nil", run(g, "return tostring(luajava)"))
        assertEquals("nil", run(g, "return tostring(io)"))
        assertEquals("nil", run(g, "return tostring(dofile)"))
        assertEquals("nil", run(g, "return tostring(loadfile)"))
        assertEquals("nil", run(g, "return tostring(os.execute)"))
        assertEquals("nil", run(g, "return tostring(os.getenv)"))
        assertEquals("nil", run(g, "return tostring(os.remove)"))
    }

    /** The real attack: Lua's package searchers loading the luajava bridge to reach Java. */
    @Test fun javaClassLoaderAttackFails() {
        val g = globals()
        val out = run(g, """
            local searchers = package.searchers or package.loaders
            local loader = searchers[3]('org.luaj.vm2.lib.jse.LuajavaLib')
            local env = { package = { loaded = {} } }
            loader('luajava', env)
            return 'ESCAPED ' .. tostring(env.luajava.bindClass('java.lang.System'):getProperty('java.version'))
        """.trimIndent())
        assertEquals(true, out.startsWith("error:"))
        // Other routes to the same globals.
        assertEquals("nil", run(g, "return tostring(getmetatable('').__index.dump)"))
        assertEquals("nil", run(g, "return tostring(_G.package)"))
    }

    @Test fun noPrecompiledBytecode() {
        val g = globals()
        // A compiled chunk (starts with ESC "Lua") is refused; source still loads.
        assertEquals("true", run(g, "local f, e = load('\\27Lua' .. string.rep('x', 20)); return tostring(f == nil and e ~= nil)"))
        assertEquals("3", run(g, "return load('return 1 + 2')()"))
    }

    @Test fun normalLuaStillWorks() {
        val g = globals()
        assertEquals("6", run(g, "local t = {1, 2, 3}; local s = 0; for _, v in ipairs(t) do s = s + v end; return s"))
        assertEquals("HELLO", run(g, "return string.upper('hello')"))
        assertEquals("true", run(g, "return tostring(type(os.time()) == 'number')"))
        assertEquals("2", run(g, "return math.floor(2.5)"))
        assertEquals(LuaValue.valueOf("ok").tojstring(), run(g, "return select(2, pcall(function() return 'ok' end))"))
    }
}
