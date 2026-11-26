package com.leaf.lemonade.lua

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import java.util.WeakHashMap

object MessagingApi {
    val METHODS = setOf("PublishAsync", "SubscribeAsync")

    private class Sub(val fn: LuaValue) { var connected = true }

    private val channels = WeakHashMap<LuaRuntime, HashMap<String, ArrayList<Sub>>>()

    private fun err(m: String): Nothing = throw LuaError(m)

    private fun server(rt: LuaRuntime) {
        if (rt.play == null) err("MessagingService can only be executed by game server")
    }

    private fun topic(v: LuaValue): String {
        if (v.type() != LuaValue.TSTRING) err("Unable to cast value to string")
        val t = v.tojstring()
        if (t.isEmpty()) err("MessagingService: Topic names cannot be empty.")
        if (t.length > 80) err("MessagingService: Topic names cannot be more than 80 characters.")
        return t
    }

    private fun copy(v: LuaValue, seen: java.util.IdentityHashMap<LuaValue, Boolean> = java.util.IdentityHashMap()): LuaValue {
        if (!v.istable()) return v
        if (seen.containsKey(v)) err("MessagingService:PublishAsync(): Cannot publish message, can only accept valid UTF-8 characters.")
        seen[v] = true
        val out = LuaTable()
        var k: LuaValue = LuaValue.NIL
        while (true) {
            val n = (v as LuaTable).next(k)
            k = n.arg1()
            if (k.isnil()) break
            out.set(k, copy(n.arg(2), seen))
        }
        seen.remove(v)
        return out
    }

    private fun yieldFrame(rt: LuaRuntime) {
        val co = rt.currentCoroutine() ?: return
        rt.mainQueue.add { rt.resume(co, LuaValue.NONE) }
        rt.globals.yield(LuaValue.NONE)
    }

    fun call(rt: LuaRuntime, name: String, a: Varargs): Varargs {
        val map = channels.getOrPut(rt) { HashMap() }
        if (name == "SubscribeAsync") {
            val cb = a.arg(3)
            if (!cb.isfunction()) err("Unable to cast ${if (cb.type() == LuaValue.TNUMBER) "double" else cb.typename()} to Function")
            server(rt)
            val t = topic(a.arg(2))
            yieldFrame(rt)
            val sub = Sub(cb)
            map.getOrPut(t) { ArrayList() }.add(sub)
            val c = LuaTable()
            c.set("Connected", LuaValue.TRUE)
            c.set("Disconnect", fnv {
                sub.connected = false
                c.set("Connected", LuaValue.FALSE)
                LuaValue.NONE
            })
            return c
        }
        server(rt)
        val t = topic(a.arg(2))
        val value = a.arg(3)
        val bytes = if (value.type() == LuaValue.TSTRING) value.tojstring() else try {
            Json.encode(value)
        } catch (e: LuaError) {
            err("MessagingService:PublishAsync(): Cannot publish message, can only accept valid UTF-8 characters.")
        }
        if (bytes.toByteArray(Charsets.UTF_8).size > 1024) err("MessagingService:PublishAsync(): Cannot publish, data exceeded 1kb")
        val data = copy(value)
        yieldFrame(rt)
        val subs = map[t] ?: return LuaValue.NONE
        val sent = System.currentTimeMillis() / 1000
        for (s in subs.toList()) {
            if (!s.connected) continue
            val msg = LuaTable()
            msg.set("Data", copy(data))
            msg.set("Sent", LuaValue.valueOf(sent.toDouble()))
            rt.spawn(s.fn, LuaValue.varargsOf(arrayOf(msg)))
        }
        return LuaValue.NONE
    }
}
