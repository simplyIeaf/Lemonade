package com.leaf.lemonade.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Loads images for GUIs, decals and the toolbox. Roblox asset ids (rbxassetid://123) are resolved
 * through the public thumbnails API, which works without signing in. Results are cached in memory
 * and on disk. [get] never blocks: it returns null and starts a load; listeners fire when ready.
 */
object ImageLoader {
    private var cacheDir: File? = null
    private val mem = object : LruCache<String, Bitmap>(minOf(40L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4)
    private val idQueue = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var batchScheduled = false
    val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun init(ctx: Context) {
        if (cacheDir == null) cacheDir = File(ctx.cacheDir, "img").also { it.mkdirs() }
    }

    private val idRx = listOf(
        Regex("""rbxassetid://(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""[?&]id=(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(\d+)\s*$"""),
        Regex("""rbxthumb://.*id=(\d+)""", RegexOption.IGNORE_CASE),
    )

    /** Parsed ids per content string (-1 = none): the renderer asks for every decal every frame. */
    private val idMemo = ConcurrentHashMap<String, Long>()

    /** Asset id referenced by a Content string, or null for local/unsupported content. */
    fun assetId(content: String?): Long? {
        if (content.isNullOrBlank()) return null
        idMemo[content]?.let { return if (it < 0) null else it }
        val id = parseId(content)
        if (idMemo.size > 50_000) idMemo.clear()
        idMemo[content] = id ?: -1L
        return id
    }

    private fun parseId(content: String): Long? {
        if (content.startsWith("rbxasset://", true) && !content.startsWith("rbxassetid://", true)) return null
        for (r in idRx) r.find(content)?.let { return it.groupValues[1].toLongOrNull() }
        return null
    }

    /** Cache key for a Content string (null if it can't be loaded). */
    private val keyMemo = ConcurrentHashMap<Long, String>()

    fun keyFor(content: String?): String? {
        if (content.isNullOrBlank()) return null
        assetId(content)?.let { id -> return keyMemo.getOrPut(id) { "asset:$id" } }
        if (content.startsWith("http://", true) || content.startsWith("https://", true)) return "url:$content"
        return null
    }

    /** Returns the bitmap if loaded; otherwise starts loading it and returns null. */
    fun get(content: String?): Bitmap? {
        val key = keyFor(content) ?: return null
        return getKey(key)
    }

    fun getKey(key: String): Bitmap? {
        mem.get(key)?.let { return it }
        val f = failed[key]
        if (f != null && System.currentTimeMillis() - f < 60_000) return null
        if (!pending.add(key)) return null
        pool.execute { loadFromDisk(key) }
        return null
    }

    fun peekKey(key: String): Bitmap? = mem.get(key)

    private fun diskFile(key: String): File? {
        val dir = cacheDir ?: return null
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.png")
    }

    private fun loadFromDisk(key: String) {
        val f = diskFile(key)
        if (f != null && f.isFile) {
            val b = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
            if (b != null) { done(key, b); return }
        }
        when {
            key.startsWith("url:") -> fetchUrl(key, key.removePrefix("url:"))
            key.startsWith("asset:") -> { idQueue.add(key.removePrefix("asset:").toLong()); scheduleBatch() }
            else -> fail(key)
        }
    }

    private fun scheduleBatch() {
        if (batchScheduled) return
        batchScheduled = true
        pool.execute {
            Thread.sleep(80) // gather ids requested in the same frame into one API call
            batchScheduled = false
            val ids = idQueue.toList().take(100)
            idQueue.removeAll(ids.toSet())
            if (idQueue.isNotEmpty()) scheduleBatch()
            if (ids.isEmpty()) return@execute
            try {
                val urls = RobloxApi.thumbnailUrls(ids, "420x420")
                for (id in ids) {
                    val u = urls[id]
                    when {
                        u == null -> fail("asset:$id")
                        u.isEmpty() -> { pending.remove("asset:$id"); failed["asset:$id"] = System.currentTimeMillis() - 55_000 } // still rendering: retry soon
                        else -> pool.execute { fetchUrl("asset:$id", u) }
                    }
                }
            } catch (_: Exception) {
                for (id in ids) fail("asset:$id")
            }
        }
    }

    private fun fetchUrl(key: String, url: String) {
        try {
            // Only Roblox's image servers: a place or model can't make the app contact (and so
            // reveal itself to) some other website by putting its address in an image property.
            val host = try { java.net.URL(url).host.lowercase() } catch (_: Exception) { "" }
            if (!WebPermissions.isRobloxHost(host)) throw java.io.IOException("not a Roblox image")
            val bytes = Http.get(url, maxBytes = 8 * 1024 * 1024)
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Exception("bad image")
            diskFile(key)?.let { f -> try { f.writeBytes(bytes) } catch (_: Exception) {} }
            done(key, b)
        } catch (_: Exception) { fail(key) }
    }

    /** Bumped whenever an image finishes loading (lets cached renders know to refresh). */
    @Volatile var epoch = 0
        private set

    private fun done(key: String, b: Bitmap) {
        mem.put(key, b)
        epoch++
        pending.remove(key)
        listeners.forEach { it(key) }
    }

    private fun fail(key: String) {
        pending.remove(key)
        failed[key] = System.currentTimeMillis()
    }
}
