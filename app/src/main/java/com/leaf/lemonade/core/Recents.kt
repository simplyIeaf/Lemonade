package com.leaf.lemonade.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently opened places for the start page: Roblox places (by place id, per account) and
 * files on the device (by their content Uri).
 */
object Recents {
    class Entry(
        val name: String,
        val placeId: Long = 0L,
        val universeId: Long = 0L,
        val uri: String? = null,
        val time: Long = System.currentTimeMillis(),
        /** Roblox account the place was opened with (0 for files). */
        val userId: Long = 0L,
    ) {
        val isCloud get() = placeId != 0L
        fun key() = if (isCloud) "p:$placeId" else "f:$uri"
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString("list", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(o.optString("name"), o.optLong("placeId"), o.optLong("universeId"), o.optString("uri").ifEmpty { null }, o.optLong("time"), o.optLong("userId"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun add(ctx: Context, e: Entry) {
        val l = list(ctx).filter { it.key() != e.key() }.toMutableList()
        l.add(0, e)
        val a = JSONArray()
        for (x in l.take(40)) a.put(JSONObject().put("name", x.name).put("placeId", x.placeId).put("universeId", x.universeId)
            .put("uri", x.uri ?: "").put("time", x.time).put("userId", x.userId))
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    fun remove(ctx: Context, e: Entry) {
        val l = list(ctx).filter { it.key() != e.key() }
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("name", x.name).put("placeId", x.placeId).put("universeId", x.universeId)
            .put("uri", x.uri ?: "").put("time", x.time).put("userId", x.userId))
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }
}
