package com.leaf.lemonade.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Roblox accounts saved on this device. One is active at a time (its sign-in is what
 * [RobloxApi.cookie] returns); "guest" means none is active. Sign-ins stay in the app's private
 * storage, like the single sign-in did before.
 */
object Accounts {
    class Account(val userId: Long, val name: String, val displayName: String, val cookie: String)

    private const val PREFS = "roblox_auth"
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Account> {
        val raw = prefs(ctx).getString("accounts", null)
        val out = ArrayList<Account>()
        if (raw != null) try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Account(o.optLong("id"), o.optString("name"), o.optString("displayName"), o.optString("cookie")))
            }
        } catch (_: Exception) {}
        // Older versions kept a single sign-in; keep it as the first saved account.
        val legacy = RobloxApi.cookie(ctx)
        val legacyId = RobloxApi.userId(ctx)
        if (legacy.isNotEmpty() && legacyId != 0L && out.none { it.userId == legacyId }) {
            out.add(0, Account(legacyId, RobloxApi.userName(ctx), RobloxApi.displayName(ctx), legacy))
            save(ctx, out)
        }
        return out
    }

    private fun save(ctx: Context, list: List<Account>) {
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("id", x.userId).put("name", x.name).put("displayName", x.displayName).put("cookie", x.cookie))
        prefs(ctx).edit().putString("accounts", a.toString()).apply()
    }

    /** Adds or refreshes an account (called after a sign-in is confirmed). */
    fun upsert(ctx: Context, acc: Account) {
        val l = list(ctx).filter { it.userId != acc.userId }.toMutableList()
        l.add(0, acc)
        save(ctx, l)
        notifyChanged()
    }

    fun remove(ctx: Context, userId: Long) {
        save(ctx, list(ctx).filter { it.userId != userId })
        notifyChanged()
    }

    /** The account that's signed in right now, or null for guest. */
    fun active(ctx: Context): Account? {
        val c = RobloxApi.cookie(ctx)
        if (c.isEmpty()) return null
        val id = RobloxApi.userId(ctx)
        return list(ctx).firstOrNull { it.userId == id } ?: Account(id, RobloxApi.userName(ctx), RobloxApi.displayName(ctx), c)
    }

    fun switchTo(ctx: Context, acc: Account) {
        RobloxApi.setCookie(ctx, acc.cookie)
        RobloxApi.setUser(ctx, acc.userId, acc.name, acc.displayName)
        RobloxCloud.resetSession()
        notifyChanged()
    }

    /** Use the app without an account (nothing is deleted; switch back any time). */
    fun useGuest(ctx: Context) {
        RobloxApi.setCookie(ctx, "")
        RobloxApi.setUser(ctx, 0L, "", "")
        RobloxCloud.resetSession()
        notifyChanged()
    }

    fun notifyChanged() = listeners.forEach { it() }
}
