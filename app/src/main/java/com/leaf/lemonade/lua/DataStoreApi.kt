package com.leaf.lemonade.lua

import com.leaf.lemonade.core.OutputLog
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import java.util.WeakHashMap

object DataStoreApi {
    val METHODS = setOf("GetDataStore", "GetGlobalDataStore", "GetOrderedDataStore", "GetRequestBudgetForRequestType", "SetRateLimitForRequestType")

    private class Record(var version: String, val created: Long, var updated: Long, var ids: LuaTable, var metadata: LuaTable)

    private class Store(val name: String) {
        val data = LinkedHashMap<String, LuaValue>()
        val records = HashMap<String, Record>()
        val listeners = HashMap<String, ArrayList<Listener>>()
        val ordered get() = name.startsWith("ordered:")
    }

    private class Listener(val fn: LuaValue) { var connected = true }

    private class State {
        val stores = HashMap<String, LuaTable>()
        val backing = HashMap<String, Store>()
        var warned = false
        var versionN = 0
        val tokens = floatArrayOf(100f, 100f, 0f, 10f, 50f, 0f, 10f, 10f, 10f, 100f, 100f, 10f, 100f, 100f, 50f, 10f, 50f)
        val base = floatArrayOf(60f, 60f, 0f, 5f, 30f, 0f, 5f, 5f, 5f, 60f, 60f, 5f, 60f, 60f, 30f, 5f, 30f)
        val per = floatArrayOf(40f, 40f, 0f, 2f, 5f, 0f, 2f, 2f, 2f, 40f, 40f, 2f, 40f, 40f, 5f, 2f, 5f)
        val configured = BooleanArray(17)
        var tick = 0L
    }

    private val states = WeakHashMap<LuaRuntime, State>()

    private fun state(rt: LuaRuntime) = states.getOrPut(rt) { State() }

    private fun copy(v: LuaValue, depth: Int = 0): LuaValue {
        if (!v.istable() || depth > 32) return v
        val out = LuaTable()
        var k = LuaValue.NIL
        while (true) {
            val n = (v as LuaTable).next(k)
            k = n.arg1()
            if (k.isnil()) break
            out.set(k, copy(n.arg(2), depth + 1))
        }
        return out
    }

    private fun nowMs() = System.currentTimeMillis()

    private fun keyInfo(rec: Record?): LuaValue {
        if (rec == null) return LuaValue.NIL
        val t = LuaTable()
        t.set("Version", rec.version)
        t.set("CreatedTime", LuaValue.valueOf(rec.created.toDouble()))
        t.set("UpdatedTime", LuaValue.valueOf(rec.updated.toDouble()))
        val meta = copy(rec.metadata)
        val ids = copy(rec.ids)
        t.set("GetMetadata", fnv { meta.let { copy(it) } })
        t.set("GetUserIds", fnv { ids.let { copy(it) } })
        return t
    }

    private fun write(st: State, s: Store, key: String, value: LuaValue, ids: LuaValue, meta: LuaValue): Record {
        val t = nowMs()
        st.versionN++
        val version = String.format("%016X.%010d.%016X.01", t, st.versionN, t)
        var rec = s.records[key]
        if (rec != null) {
            rec.version = version
            rec.updated = t
        } else {
            rec = Record(version, t, t, LuaTable(), LuaTable())
            s.records[key] = rec
        }
        rec.ids = if (ids.istable()) copy(ids) as LuaTable else LuaTable()
        rec.metadata = if (meta.istable()) copy(meta) as LuaTable else LuaTable()
        s.data[key] = copy(value)
        return rec
    }

    private fun notify(rt: LuaRuntime, s: Store, key: String, value: LuaValue) {
        val ls = s.listeners[key] ?: return
        for (l in ls.toList()) if (l.connected) rt.spawn(l.fn, LuaValue.varargsOf(arrayOf(copy(value))))
    }

    private fun keyArg(a: Varargs): String {
        val k = a.arg(2)
        if (k.isnil()) throw LuaError("Argument 1 missing or nil")
        return k.tojstring()
    }

    internal fun page(rows: List<LuaTable>, size: Int, onAdvance: ((Int) -> Unit)? = null): LuaTable {
        val pages = rows.chunked(size.coerceAtLeast(1))
        var idx = 0
        val p = LuaTable()
        p.set("IsFinished", LuaValue.valueOf(pages.size <= 1))
        p.set("GetCurrentPage", fnv {
            val t = LuaTable()
            val cur = pages.getOrNull(idx).orEmpty()
            cur.forEachIndexed { i, r -> t.set(i + 1, copy(r)) }
            t
        })
        p.set("AdvanceToNextPageAsync", fnv {
            if (idx >= pages.size - 1) throw LuaError("No pages to advance to")
            idx++
            onAdvance?.invoke(idx)
            p.set("IsFinished", LuaValue.valueOf(idx >= pages.size - 1))
            LuaValue.NONE
        })
        return p
    }

    private fun makeStore(rt: LuaRuntime, st: State, s: Store): LuaTable {
        val t = LuaTable()
        t.set("GetAsync", fnv { a ->
            val key = keyArg(a)
            val v = s.data[key]?.let { copy(it) } ?: LuaValue.NIL
            if (s.ordered || v.isnil()) v else LuaValue.varargsOf(v, keyInfo(s.records[key]))
        })
        t.set("SetAsync", fnv { a ->
            val key = keyArg(a)
            if (s.ordered) {
                s.data[key] = copy(a.arg(3))
                notify(rt, s, key, a.arg(3))
                return@fnv LuaValue.NONE
            }
            val rec = write(st, s, key, a.arg(3), a.arg(4), LuaValue.NIL)
            notify(rt, s, key, a.arg(3))
            LuaValue.valueOf(rec.version)
        })
        t.set("OnUpdate", fnv { a ->
            val key = keyArg(a)
            val l = Listener(a.arg(3))
            s.listeners.getOrPut(key) { ArrayList() }.add(l)
            val existing = s.data[key]
            if (existing != null) {
                val v = copy(existing)
                rt.mainQueue.add { if (l.connected) rt.spawn(l.fn, LuaValue.varargsOf(arrayOf(v))) }
            }
            val c = LuaTable()
            c.set("Connected", LuaValue.TRUE)
            c.set("Disconnect", fnv { l.connected = false; c.set("Connected", LuaValue.FALSE); LuaValue.NONE })
            c
        })
        t.set("BatchGetAsync", fnv { a ->
            if (!s.ordered) throw LuaError("{\"errorType\":\"BatchGetUnsupportedOperation\",\"errorMessage\":\"BatchGetAsync is not supported.\",\"callbackMessage\":\"BatchGetAsync is not supported.\"}")
            val keys = a.arg(2)
            if (!keys.istable() || keys.length() == 0) throw LuaError("OrderedDataStore BatchGetAsync keys array cannot be empty.")
            if (keys.length() > 100) throw LuaError("OrderedDataStore BatchGetAsync exceeded the 100 key limit.")
            val out = LuaTable()
            for (i in 1..keys.length()) {
                val k = keys.get(i)
                if (!k.isstring()) throw LuaError("513: Attribute keys format is invalid")
                val v = s.data[k.tojstring()]
                if (v != null) {
                    val e = LuaTable(); e.set("value", copy(v)); out.set(k, e)
                }
            }
            out
        })
        t.set("UpdateAsync", fnv { a ->
            val key = keyArg(a)
            val fn = a.arg(3)
            val current = s.data[key]?.let { copy(it) } ?: LuaValue.NIL
            if (s.ordered) {
                val nv = fn.invoke(LuaValue.varargsOf(arrayOf(current))).arg1()
                if (!nv.isnil()) {
                    s.data[key] = copy(nv)
                    notify(rt, s, key, nv)
                }
                return@fnv nv
            }
            val r = fn.invoke(LuaValue.varargsOf(arrayOf(current, if (current.isnil()) LuaValue.NIL else keyInfo(s.records[key]))))
            val nv = r.arg1()
            if (nv.isnil()) return@fnv LuaValue.NIL
            val rec = write(st, s, key, nv, r.arg(2), r.arg(3))
            notify(rt, s, key, nv)
            LuaValue.varargsOf(copy(nv), keyInfo(rec))
        })
        t.set("IncrementAsync", fnv { a ->
            val key = keyArg(a)
            val cur = s.data[key]?.takeIf { it.isnumber() || it.isstring() }?.todouble() ?: 0.0
            val d = if (a.arg(3).isnil()) 1.0 else a.arg(3).optdouble(1.0)
            val nv = LuaValue.valueOf(cur + d)
            s.data[key] = nv
            notify(rt, s, key, nv)
            nv
        })
        t.set("RemoveAsync", fnv { a ->
            val key = keyArg(a)
            val old = s.data[key]
            val rec = if (!s.ordered) s.records[key] else null
            s.data.remove(key)
            s.records.remove(key)
            notify(rt, s, key, LuaValue.NIL)
            when {
                old == null -> LuaValue.NIL
                s.ordered -> old
                else -> LuaValue.varargsOf(old, keyInfo(rec))
            }
        })
        t.set("GetSortedAsync", fnv { a ->
            val asc = a.arg(2).toboolean()
            val size = if (a.arg(3).isnil()) 50 else a.arg(3).toint()
            val rows = s.data.entries.map { (k, v) -> val r = LuaTable(); r.set("key", k); r.set("value", v); r }
            val num = { r: LuaTable -> r.get("value").let { if (it.isnumber() || it.isstring()) it.todouble() else 0.0 } }
            val sorted = if (asc) rows.sortedBy(num) else rows.sortedByDescending(num)
            page(sorted, size)
        })
        return t
    }

    private fun getStore(rt: LuaRuntime, name: String, scope: String): LuaTable {
        val st = state(rt)
        if (!st.warned) {
            st.warned = true
            OutputLog.warn("DataStores in Lemonade keep data in memory for this play session only")
        }
        val key = "$name/$scope"
        return st.stores.getOrPut(key) {
            val s = st.backing.getOrPut(key) { Store(name) }
            makeStore(rt, st, s)
        }
    }

    private fun refresh(rt: LuaRuntime, st: State) {
        val tick = (rt.time).coerceAtLeast(0.0).toLong()
        val ticks = (tick - st.tick).coerceIn(0L, 1_000_000L)
        for (n in 0 until ticks) {
            for (i in 0 until 17) {
                if (i == 2 || i == 5) continue
                val rate = st.base[i] + st.per[i] * playerCount(rt)
                st.tokens[i] = (st.tokens[i] + rate / 60f).coerceAtMost(rate * 100f).coerceAtLeast(0f)
            }
        }
        st.tick = tick
    }

    private fun playerCount(rt: LuaRuntime): Int = if (rt.play != null) 1 else 0

    private val requestTypes = listOf("GetAsync", "SetIncrementAsync", "UpdateAsync", "GetSortedAsync", "SetIncrementSortedAsync", "OnUpdate")

    fun call(rt: LuaRuntime, name: String, a: Varargs): Varargs {
        when (name) {
            "GetDataStore" -> {
                val n = a.arg(2)
                if (n.isnil()) throw LuaError("Argument 1 missing or nil")
                return getStore(rt, n.tojstring(), if (a.arg(3).isnil()) "global" else a.arg(3).tojstring())
            }
            "GetGlobalDataStore" -> return getStore(rt, "__global", "global")
            "GetOrderedDataStore" -> {
                val n = a.arg(2)
                if (n.isnil()) throw LuaError("Argument 1 missing or nil")
                return getStore(rt, "ordered:" + n.tojstring(), if (a.arg(3).isnil()) "global" else a.arg(3).tojstring())
            }
        }
        val st = state(rt)
        val v = a.arg(2)
        if (v.isnil()) throw LuaError("Argument 1 missing or nil")
        val item = v.udOrNull<EnumItemV>()
        val i = item?.value ?: v.toint()
        val label = item?.name ?: requestTypes.getOrNull(i) ?: i.toString()
        if (i < 0 || i > 16) throw LuaError("$name: Unknown request type.")
        refresh(rt, st)
        val players = playerCount(rt)
        if (name == "GetRequestBudgetForRequestType") {
            val value = when (i) {
                2 -> minOf(st.tokens[0], st.tokens[1]).coerceAtLeast(0f).toInt()
                5 -> (((30 + 5 * players) * 30 / 60)).coerceAtLeast(0)
                else -> st.tokens[i].coerceAtLeast(0f).toInt()
            }
            return LuaValue.valueOf(value)
        }
        if (a.arg(3).isnil()) throw LuaError("Argument 2 missing or nil")
        if (a.arg(4).isnil()) throw LuaError("Argument 3 missing or nil")
        val base = a.arg(3).tofloat()
        val per = a.arg(4).tofloat()
        if (i == 2 || i == 5) throw LuaError("SetRateLimitForRequestType: Does not apply to DataStoreRequestType.$label.")
        val maxBase = if (i >= 9) 10000f else st.base[i]
        val maxPer = if (i >= 9) 200f else st.per[i]
        for ((lab, value, max) in listOf(Triple("base", base, maxBase), Triple("per-player", per, maxPer))) {
            if (value.isNaN() || value.isInfinite() || value < 0f || value > max) {
                throw LuaError("SetRateLimitForRequestType: proposed $lab limit ${fmt(value)} for DataStoreRequestType.$label is out of range [0, ${fmt(max)}].")
            }
        }
        val repeat = st.configured[i]
        st.configured[i] = true
        st.base[i] = base
        st.per[i] = per
        if (repeat) OutputLog.warn("SetRateLimitForRequestType: Rate limit for DataStoreRequestType.$label has already been set. Please ensure this is intended behavior.")
        return LuaValue.NONE
    }

    private fun fmt(f: Float): String = if (f == Math.floor(f.toDouble()).toFloat() && !f.isInfinite()) f.toLong().toString() else f.toString()
}
