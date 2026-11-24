package com.leaf.lemonade.net

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which websites scripts and plugins may talk to through HttpService. Like Roblox Studio's
 * plugin permission prompt: the first request to a site asks the user, and the answer is
 * remembered (Studio Settings → Privacy can reset them). The app's own Roblox sign-in is never
 * attached to these requests, and Roblox's own sites can't be reached from scripts at all.
 */
object WebPermissions {
    private const val PREFS = "web_permissions"

    /**
     * Asks the user: (host, who is asking, answer callback). The answer is true (allow, remembered),
     * false (block, remembered) or null (dismissed: refused this time only). Set by the activity;
     * with no UI to ask, requests are refused.
     */
    @Volatile var asker: ((String, String, (Boolean?) -> Unit) -> Unit)? = null
    private val pending = ConcurrentHashMap<String, CountDownLatch>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Roblox's own sites (sign-in, APIs, CDN): scripts can't make requests there, as in Roblox. */
    fun isRobloxHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return listOf("roblox.com", "rbxcdn.com", "robloxlabs.com", "rbx.com", "roblox.cn", "roblox.qq.com", "robloxdev.com")
            .any { h == it || h.endsWith(".$it") }
    }

    /** Remembered answer for [host]: true allowed, false blocked, null not asked yet. */
    fun decision(ctx: Context, host: String): Boolean? {
        val p = prefs(ctx)
        return if (p.contains(host)) p.getBoolean(host, false) else null
    }

    fun remembered(ctx: Context): Map<String, Boolean> = prefs(ctx).all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap()

    fun forgetAll(ctx: Context) = prefs(ctx).edit().clear().apply()

    /**
     * True if a script/plugin may contact [host]. Blocks the calling (background) thread while
     * the user decides; nobody answering within two minutes counts as "no".
     */
    fun allow(ctx: Context, host: String, who: String): Boolean {
        if (isRobloxHost(host)) return false
        decision(ctx, host)?.let { return it }
        val ask = asker ?: return false
        var mine = false
        val latch = pending.computeIfAbsent(host) { mine = true; CountDownLatch(1) }
        if (mine) {
            try {
                ask(host, who) { ok -> if (ok != null) prefs(ctx).edit().putBoolean(host, ok).commit(); latch.countDown() }
            } catch (_: Exception) { latch.countDown() }
        }
        latch.await(2, TimeUnit.MINUTES)
        if (mine) pending.remove(host)
        return decision(ctx, host) == true
    }
}
