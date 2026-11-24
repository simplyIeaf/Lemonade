package com.leaf.lemonade.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/**
 * Locks Lua down to what Roblox scripts may use. Scripts, free models and plugins are other
 * people's code; if they could reach Java (reflection, files) they could read this app's saved
 * Roblox sign-in and send it anywhere. LuaJ's standard globals have several ways in:
 *  - `package` (its searchers load any Java class by name, e.g. the luajava bridge),
 *  - `luajava`, `io`, `dofile`, `loadfile`, most of `os`,
 *  - precompiled bytecode through `load` (Roblox doesn't allow bytecode either).
 * All of those are removed; Roblox's own `require`, `loadstring` etc. are installed afterwards.
 */
object LuaSandbox {
    private val ESC = LuaString.valueOf("\u001bLua")

    fun lockDown(g: Globals) {
        for (name in listOf("package", "require", "luajava", "io", "dofile", "loadfile", "module")) g.set(name, LuaValue.NIL)
        // os: only the clock and date functions (no exit, execute, getenv, remove, rename, tmpname...).
        val realOs = g.get("os")
        val os = LuaTable()
        if (realOs.istable()) for (k in listOf("time", "clock", "date", "difftime")) os.set(k, realOs.get(k))
        g.set("os", os)
        // string.dump hands out bytecode; Roblox has no such function. Strings share one
        // metatable for the whole process (LuaJ makes it from the first string library it
        // loads), so clean the table it points at too.
        val str = g.get("string")
        if (str.istable()) str.set("dump", LuaValue.NIL)
        val shared = LuaString.s_metatable?.get(LuaValue.INDEX)
        if (shared != null && shared.istable()) shared.set("dump", LuaValue.NIL)
        // load: source only.
        val realLoad = g.get("load")
        g.set("load", object : VarArgFunction() {
            override fun invoke(a: Varargs): Varargs {
                var chunk = a.arg1()
                if (chunk.isfunction()) {
                    // A reader function: collect its pieces so the whole chunk can be checked.
                    val sb = StringBuilder()
                    var guard = 0
                    while (guard++ < 100_000) {
                        val piece = chunk.call()
                        if (piece.isnil() || (piece.isstring() && piece.tojstring().isEmpty())) break
                        sb.append(piece.checkjstring())
                        if (sb.length > 50_000_000) return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("chunk too large"))
                    }
                    chunk = LuaValue.valueOf(sb.toString())
                }
                if (isBytecode(chunk)) return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("bytecode is not allowed"))
                // Mode "t": text chunks only, whatever the script asked for.
                return realLoad.invoke(LuaValue.varargsOf(arrayOf(chunk, a.arg(2), LuaValue.valueOf("t"), a.arg(4))))
            }
        })
    }

    /** A precompiled Lua chunk (starts with ESC "Lua"). */
    fun isBytecode(v: LuaValue): Boolean {
        if (!v.isstring()) return false
        val s = v.checkstring()
        return s.length() >= 1 && s.luaByte(0) == 0x1B || (s.length() >= ESC.length() && s.substring(0, ESC.length()).raweq(ESC))
    }
}
