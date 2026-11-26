package com.leaf.lemonade.lua

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import java.util.WeakHashMap

object MemoryStoreApi {
    val METHODS = setOf("GetQueue", "GetSortedMap", "GetHashMap", "GetDistributedCounter")

    private class Row(
        var value: LuaValue,
        var sortKey: LuaValue,
        var expires: Double,
        var bytes: Int,
        var priority: Double = 0.0,
        var sequence: Long = 0,
        var invisibleUntil: Double = 0.0,
        var receipt: String? = null,
    )

    private class Handle(val cls: String, val name: String, val invisibility: Double, val data: LinkedHashMap<String, Row>)

    private class State {
        val namespaces = HashMap<String, LinkedHashMap<String, Row>>()
        var serial = 0L
        var tokens = 1000.0
        var lastBudget = -1.0
        val population = ArrayList<Pair<Double, Int>>()
    }

    private val states = WeakHashMap<LuaRuntime, State>()
    private fun state(rt: LuaRuntime) = states.getOrPut(rt) { State() }

    private fun err(m: String): Nothing = throw LuaError(m)

    private fun players(rt: LuaRuntime) = if (rt.play != null) 1 else 0

    private fun copy(v: LuaValue, seen: java.util.IdentityHashMap<LuaValue, Boolean> = java.util.IdentityHashMap()): LuaValue {
        when (v.type()) {
            LuaValue.TNUMBER -> {
                val d = v.todouble()
                if (d.isNaN() || d.isInfinite()) err("InvalidRequest: Unable to convert value to json.")
                return v
            }
            LuaValue.TSTRING, LuaValue.TBOOLEAN, LuaValue.TNIL -> return v
            LuaValue.TTABLE -> {
                if (seen.containsKey(v)) err("InvalidRequest: Unable to convert value to json.")
                seen[v] = true
                val out = LuaTable()
                var k: LuaValue = LuaValue.NIL
                while (true) {
                    val n = (v as LuaTable).next(k)
                    k = n.arg1()
                    if (k.isnil()) break
                    if (k.type() != LuaValue.TSTRING && k.type() != LuaValue.TNUMBER) err("InvalidRequest: Unable to convert value to json.")
                    out.set(k, copy(n.arg(2), seen))
                }
                seen.remove(v)
                return out
            }
            else -> err("InvalidRequest: Unable to convert value to json.")
        }
    }

    private fun expiration(v: LuaValue): Double {
        if (v.type() != LuaValue.TNUMBER || v.todouble().isNaN() || v.todouble() < 0 || v.todouble() > 3888000)
            err("InvalidExpirationTime: The field 'expiration' time must be between 0 and 3,888,000.")
        return Math.floor(v.todouble())
    }

    private fun key(v: LuaValue): String {
        if (v.type() != LuaValue.TSTRING) err("Unable to cast value to string")
        val s = v.tojstring()
        if (s.isEmpty()) err("InvalidParameter: The key field is required.")
        if (s.toByteArray(Charsets.UTF_8).size > 128) err("InvalidParameter: The provided key is not valid.")
        return s
    }

    private fun count(v: LuaValue, max: Int): Int {
        if (v.type() != LuaValue.TNUMBER || v.todouble() % 1.0 != 0.0 || v.todouble() < 1 || v.todouble() > max) err("InvalidRequest: Invalid count.")
        return v.toint()
    }

    private fun sortKey(v: LuaValue): LuaValue {
        if (!v.isnil() && v.type() != LuaValue.TSTRING && (v.type() != LuaValue.TNUMBER || v.todouble().isNaN() || v.todouble().isInfinite()))
            err("InvalidRequest: Unable to convert sortKey to a valid number or string.")
        return v
    }

    private fun purge(rt: LuaRuntime, d: LinkedHashMap<String, Row>) {
        val t = rt.time
        d.entries.removeIf { it.value.expires <= t }
    }

    private fun yieldFrame(rt: LuaRuntime) {
        val co = rt.currentCoroutine() ?: return
        rt.mainQueue.add { rt.resume(co, LuaValue.NONE) }
        rt.globals.yield(LuaValue.NONE)
    }

    private fun consume(rt: LuaRuntime, st: State, n: Int) {
        val t = rt.time
        val limit = 1000.0 + 120.0 * players(rt)
        if (st.lastBudget < 0) st.lastBudget = t
        st.tokens = minOf(limit, st.tokens + maxOf(0.0, t - st.lastBudget) * limit / 60.0)
        st.lastBudget = t
        if (st.tokens < n) err("RequestThrottled: Recent MemoryStores requests hit one or more limits.")
        st.tokens -= n
    }

    private fun storageLimit(rt: LuaRuntime, st: State): Double {
        val t = rt.time
        val n = players(rt)
        val last = st.population.lastOrNull()
        if (last != null && last.second == n) st.population[st.population.size - 1] = t to n else st.population.add(t to n)
        var peak = 0
        for (i in st.population.indices.reversed()) {
            val p = st.population[i]
            if (t - p.first > 8 * 86400) st.population.removeAt(i) else peak = maxOf(peak, p.second)
        }
        return 64.0 * 1024 + 1.2 * 1024 * peak
    }

    private fun valueBytes(v: LuaValue): Int {
        val s = try { Json.encode(v) } catch (e: LuaError) { err("InvalidRequest: Unable to convert value to json.") }
        val size = s.toByteArray(Charsets.UTF_8).size
        if (size > 32768) err("ItemValueSizeTooLarge: The item value size is too large.")
        return size
    }

    private fun reserve(rt: LuaRuntime, st: State, d: LinkedHashMap<String, Row>, k: String, size: Int, cls: String) {
        var total = 0L
        for (ns in st.namespaces.values) {
            purge(rt, ns)
            for (r in ns.values) total += r.bytes
        }
        var structure = 0L
        for (r in d.values) structure += r.bytes
        val previous = d[k]?.bytes ?: 0
        val limit = Math.floor(storageLimit(rt, st)).toLong()
        if (size > previous && total - previous + size > limit)
            err("TotalMemoryOverLimit: Memory usage of ${total - previous + size} bytes would exceed the quota of $limit bytes")
        if (cls != "MemoryStoreHashMap") {
            if (!d.containsKey(k) && d.size >= 1000000) err("DataStructureItemsOverLimit: The data structure item count limit has been reached.")
            if (structure - previous + size > 100L * 1024 * 1024) err("DataStructureMemoryOverLimit: The data structure memory size limit has been reached.")
        }
    }

    private fun put(rt: LuaRuntime, st: State, h: Handle, k: String, value: LuaValue, ttl: Double, sk: LuaValue) {
        val bytes = valueBytes(value)
        reserve(rt, st, h.data, k, bytes, h.cls)
        h.data[k] = Row(copy(value), sortKey(sk), rt.time + ttl, bytes)
    }

    private fun rank(v: LuaValue) = when (v.type()) { LuaValue.TNUMBER -> 1; LuaValue.TSTRING -> 2; else -> 3 }

    private fun less(ak: String, ask: LuaValue, bk: String, bsk: LuaValue): Boolean {
        val x = rank(ask); val y = rank(bsk)
        if (x != y) return x < y
        if (!ask.eq_b(bsk) && x != 3) {
            return if (x == 1) ask.todouble() < bsk.todouble() else ask.tojstring() < bsk.tojstring()
        }
        return ak < bk
    }

    private fun open(rt: LuaRuntime, cls: String, nameV: LuaValue, invisibility: Double): LuaTable {
        if (nameV.type() != LuaValue.TSTRING) err("Unable to cast value to string")
        val st = state(rt)
        val name = nameV.tojstring()
        val data = st.namespaces.getOrPut("$cls:$name") { LinkedHashMap() }
        val h = Handle(cls, name, invisibility, data)
        return when (cls) {
            "MemoryStoreQueue" -> queue(rt, st, h)
            "MemoryStoreSortedMap" -> map(rt, st, h, true)
            "MemoryStoreHashMap" -> map(rt, st, h, false)
            else -> counter(rt, st, h)
        }
    }

    private fun counter(rt: LuaRuntime, st: State, h: Handle): LuaTable {
        val t = LuaTable()
        t.set("GetAsync", fnv {
            consume(rt, st, 1); yieldFrame(rt)
            LuaValue.valueOf((check(rt, h)["counter"]?.value?.todouble()) ?: 0.0)
        })
        t.set("IncrementAsync", fnv { a ->
            val ttl = expiration(a.arg(3))
            val dv = a.arg(2)
            if (dv.type() != LuaValue.TNUMBER || dv.todouble() % 1.0 != 0.0) err("Unable to cast value to int64")
            consume(rt, st, 1); yieldFrame(rt)
            val d = check(rt, h)
            val v = (d["counter"]?.value?.todouble() ?: 0.0) + dv.todouble()
            reserve(rt, st, d, "counter", 8, "MemoryStoreDistributedCounter")
            d["counter"] = Row(LuaValue.valueOf(v), LuaValue.NIL, rt.time + ttl, 8)
            LuaValue.valueOf(v)
        })
        return t
    }

    private fun check(rt: LuaRuntime, h: Handle): LinkedHashMap<String, Row> {
        if (h.name.isEmpty()) err("InvalidParameter: The name field is required.")
        if (h.name.toByteArray(Charsets.UTF_8).size > 128) {
            val what = when (h.cls) { "MemoryStoreHashMap" -> "hash map"; "MemoryStoreSortedMap" -> "sorted map"; else -> "queue" }
            err("InvalidParameter: The provided $what name is not valid.")
        }
        purge(rt, h.data)
        return h.data
    }

    private fun mapRow(k: String, r: Row, sorted: Boolean): LuaTable {
        val t = LuaTable()
        t.set("key", k)
        t.set("value", copy(r.value))
        if (sorted) t.set("sortKey", r.sortKey)
        return t
    }

    private fun map(rt: LuaRuntime, st: State, h: Handle, sorted: Boolean): LuaTable {
        val t = LuaTable()
        t.set("GetAsync", fnv { a ->
            val k = key(a.arg(2))
            consume(rt, st, 1); yieldFrame(rt)
            val r = check(rt, h)[k] ?: return@fnv LuaValue.NIL
            if (sorted) LuaValue.varargsOf(copy(r.value), r.sortKey) else copy(r.value)
        })
        t.set("SetAsync", fnv { a ->
            val k = key(a.arg(2))
            val ttl = expiration(a.arg(4))
            val value = copy(a.arg(3))
            val sk = if (sorted) a.arg(5) else LuaValue.NIL
            sortKey(a.arg(5))
            consume(rt, st, 1); yieldFrame(rt)
            val d = check(rt, h)
            val existed = d.containsKey(k)
            put(rt, st, h, k, value, ttl, sk)
            LuaValue.valueOf(existed)
        })
        t.set("RemoveAsync", fnv { a ->
            val k = key(a.arg(2))
            consume(rt, st, 1); yieldFrame(rt)
            check(rt, h).remove(k)
            LuaValue.NONE
        })
        t.set("UpdateAsync", fnv { a ->
            val k = key(a.arg(2))
            val ttl = expiration(a.arg(4))
            val fn = a.arg(3)
            if (!fn.isfunction()) err("Unable to cast value to Function")
            consume(rt, st, 1); yieldFrame(rt)
            while (true) {
                val d = check(rt, h)
                val before = d[k]
                val res = try {
                    fn.invoke(LuaValue.varargsOf(arrayOf(before?.let { copy(it.value) } ?: LuaValue.NIL, before?.sortKey ?: LuaValue.NIL)))
                } catch (e: LuaError) { err("TransformCallbackFailed: Failed to invoke transformation function.") }
                val v = res.arg1()
                if (v.isnil()) return@fnv LuaValue.NIL
                consume(rt, st, 1)
                if (d[k] === before) {
                    put(rt, st, h, k, v, ttl, if (sorted) res.arg(2) else LuaValue.NIL)
                    return@fnv if (sorted) LuaValue.varargsOf(copy(v), res.arg(2)) else copy(v)
                }
            }
            LuaValue.NONE
        })
        if (sorted) {
            t.set("GetRangeAsync", fnv { a ->
                val dirV = a.arg(2)
                val n = count(a.arg(3), 200)
                val lower = a.arg(4)
                val upper = a.arg(5)
                yieldFrame(rt)
                val d = check(rt, h)
                fun bound(k: String, r: Row, b: LuaValue, isLower: Boolean): Boolean {
                    if (b.isnil()) return true
                    var bk: LuaValue = LuaValue.NIL
                    var bsk: LuaValue = LuaValue.NIL
                    if (b.type() == LuaValue.TSTRING) bk = b
                    else if (b.istable()) { bk = b.get("key"); bsk = b.get("sortKey") }
                    else err("InvalidRequest: Invalid bound.")
                    if (bk.isnil()) {
                        if (r.sortKey.eq_b(bsk)) return false
                        return if (isLower) less("", bsk, k, r.sortKey) else less(k, r.sortKey, "", bsk)
                    }
                    val ek = bk.tojstring()
                    return if (isLower) less(ek, bsk, k, r.sortKey) else less(k, r.sortKey, ek, bsk)
                }
                val rows = d.entries.filter { bound(it.key, it.value, lower, true) && bound(it.key, it.value, upper, false) }.toMutableList()
                val desc = (dirV.type() == LuaValue.TSTRING && dirV.tojstring() == "Descending") ||
                    (dirV.udOrNull<EnumItemV>()?.name == "Descending")
                val cmp = Comparator<Map.Entry<String, Row>> { x, y ->
                    if (less(x.key, x.value.sortKey, y.key, y.value.sortKey)) -1
                    else if (less(y.key, y.value.sortKey, x.key, x.value.sortKey)) 1 else 0
                }
                rows.sortWith(if (desc) cmp.reversed() else cmp)
                val out = LuaTable()
                val take = rows.take(n)
                take.forEachIndexed { i, e -> out.set(i + 1, mapRow(e.key, e.value, true)) }
                consume(rt, st, maxOf(1, take.size))
                out
            })
            t.set("GetSizeAsync", fnv { a ->
                consume(rt, st, 1); yieldFrame(rt)
                val exclude = a.arg(2).toboolean()
                val now = rt.time
                LuaValue.valueOf(check(rt, h).values.count { !exclude || it.invisibleUntil <= now })
            })
        } else {
            t.set("ListItemsAsync", fnv { a ->
                val n = count(a.arg(2), 200)
                yieldFrame(rt)
                val d = check(rt, h)
                val rows = d.entries.sortedBy { it.key }.map { mapRow(it.key, it.value, false) }
                val pages = rows.chunked(n)
                consume(rt, st, 1 + minOf(n, rows.size))
                DataStoreApi.page(rows, n) { idx -> consume(rt, st, 1 + pages[idx].size) }
            })
        }
        return t
    }

    private fun queue(rt: LuaRuntime, st: State, h: Handle): LuaTable {
        val t = LuaTable()
        t.set("AddAsync", fnv { a ->
            val ttl = expiration(a.arg(3))
            val prV = a.arg(4)
            val priority = if (prV.isnil()) 0.0 else {
                if (prV.type() != LuaValue.TNUMBER || prV.todouble().isNaN() || prV.todouble().isInfinite()) err("InvalidRequest: Invalid priority.")
                prV.todouble()
            }
            val value = copy(a.arg(2))
            consume(rt, st, 1); yieldFrame(rt)
            val d = check(rt, h)
            st.serial++
            val bytes = valueBytes(value)
            reserve(rt, st, d, st.serial.toString(), bytes, "MemoryStoreQueue")
            d[st.serial.toString()] = Row(value, LuaValue.NIL, rt.time + ttl, bytes, priority, st.serial, 0.0)
            LuaValue.NONE
        })
        t.set("ReadAsync", fnv { a ->
            val n = count(a.arg(2), 100)
            val all = a.arg(3).toboolean()
            val tv = a.arg(4)
            val timeout = if (tv.isnil()) -1.0 else {
                if (tv.type() != LuaValue.TNUMBER || tv.todouble().isNaN() || tv.todouble() < -1) err("InvalidRequest: Invalid wait timeout.")
                tv.todouble()
            }
            val deadline = if (timeout < 0) Double.POSITIVE_INFINITY else rt.time + timeout
            var lastPoll = rt.time
            consume(rt, st, 1); yieldFrame(rt)
            while (true) {
                val d = check(rt, h)
                val rows = d.values.filter { it.invisibleUntil <= rt.time }
                    .sortedWith(Comparator { x, y -> if (x.priority == y.priority) x.sequence.compareTo(y.sequence) else y.priority.compareTo(x.priority) })
                if (rows.isNotEmpty() && (!all || rows.size >= n)) {
                    consume(rt, st, maxOf(0, minOf(n, rows.size) - 1))
                    st.serial++
                    val receipt = st.serial.toString()
                    val values = LuaTable()
                    for (i in 0 until minOf(n, rows.size)) {
                        val r = rows[i]
                        r.invisibleUntil = rt.time + h.invisibility
                        r.receipt = receipt
                        values.set(i + 1, copy(r.value))
                    }
                    return@fnv LuaValue.varargsOf(values, LuaValue.valueOf(receipt))
                }
                if (rt.time >= deadline) return@fnv LuaValue.varargsOf(LuaTable(), LuaValue.NIL)
                if (rt.currentCoroutine() == null) return@fnv LuaValue.varargsOf(LuaTable(), LuaValue.NIL)
                yieldFrame(rt)
                if (rt.time - lastPoll >= 2) { consume(rt, st, 1); lastPoll = rt.time }
            }
            LuaValue.NONE
        })
        t.set("RemoveAsync", fnv { a ->
            if (a.arg(2).type() != LuaValue.TSTRING) err("Unable to cast value to string")
            val receipt = a.arg(2).tojstring()
            consume(rt, st, 1); yieldFrame(rt)
            val d = check(rt, h)
            val now = rt.time
            d.entries.removeIf { it.value.receipt == receipt && it.value.invisibleUntil > now }
            LuaValue.NONE
        })
        t.set("GetSizeAsync", fnv { a ->
            consume(rt, st, 1); yieldFrame(rt)
            val exclude = a.arg(2).toboolean()
            val now = rt.time
            LuaValue.valueOf(check(rt, h).values.count { !exclude || it.invisibleUntil <= now })
        })
        return t
    }

    fun call(rt: LuaRuntime, name: String, a: Varargs): Varargs = when (name) {
        "GetQueue" -> {
            val inv = a.arg(3)
            if (!inv.isnil() && (inv.type() != LuaValue.TNUMBER || inv.todouble() < 0 || inv.todouble() > 43200 || inv.todouble().isNaN()))
                err("InvalidRequest: Invalid invisibility timeout.")
            open(rt, "MemoryStoreQueue", a.arg(2), if (inv.isnil()) 30.0 else inv.todouble())
        }
        "GetSortedMap" -> open(rt, "MemoryStoreSortedMap", a.arg(2), 30.0)
        "GetHashMap" -> open(rt, "MemoryStoreHashMap", a.arg(2), 30.0)
        else -> open(rt, "MemoryStoreDistributedCounter", a.arg(2), 30.0)
    }
}
