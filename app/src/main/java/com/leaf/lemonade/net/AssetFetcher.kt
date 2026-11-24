package com.leaf.lemonade.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.leaf.lemonade.format.RbxFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Raw asset files (meshes, audio, clothing templates, accessory models) downloaded through
 * assetdelivery with the active sign-in or API key, cached on disk. Roblox requires
 * authentication for these downloads, so in guest mode only files already cached are available.
 * [request] never blocks; listeners get the asset id when its bytes are ready.
 */
object AssetFetcher {
    private var app: Context? = null
    private var dir: File? = null
    private val pool = Executors.newFixedThreadPool(3)
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val failed = ConcurrentHashMap<Long, Long>()
    private val errors = ConcurrentHashMap<Long, String>()
    val listeners = CopyOnWriteArrayList<(Long) -> Unit>()

    fun init(ctx: Context) {
        if (app == null) { app = ctx.applicationContext; dir = File(ctx.cacheDir, "assets").also { it.mkdirs() } }
    }

    private fun file(id: Long) = dir?.let { File(it, "$id.bin") }

    /** Cached bytes, or null (and a download is started). */
    fun request(id: Long): ByteArray? {
        file(id)?.takeIf { it.isFile }?.let { f -> return try { f.readBytes() } catch (_: Exception) { null } }
        val f = failed[id]
        if (f != null && System.currentTimeMillis() - f < 90_000) return null
        if (!pending.add(id)) return null
        pool.execute {
            try {
                load(id)
                listeners.forEach { it(id) }
            } catch (e: Exception) {
                failed[id] = System.currentTimeMillis()
                errors[id] = e.message ?: e.toString()
            } finally { pending.remove(id) }
        }
        return null
    }

    fun isCached(id: Long) = file(id)?.isFile == true
    /** Path of the downloaded file, if it's cached. */
    fun cacheFile(id: Long): String? = file(id)?.takeIf { it.isFile }?.path
    fun lastError(id: Long): String? = errors[id]

    /** Downloads (or reads from the cache) synchronously. Call off the UI thread. */
    fun load(id: Long): ByteArray {
        val f = file(id)
        if (f != null && f.isFile) return f.readBytes()
        val ctx = app ?: throw IllegalStateException("AssetFetcher.init not called")
        val bytes = RobloxApi.downloadAsset(ctx, id)
        if (f != null) try { val tmp = File(f.path + ".tmp"); tmp.writeBytes(bytes); tmp.renameTo(f) } catch (_: Exception) {}
        failed.remove(id); errors.remove(id)
        return bytes
    }

    // ------------------------------------------------------------------ images at full resolution

    private val bitmaps = object : LruCache<Long, Bitmap>(minOf(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val imagePending = ConcurrentHashMap.newKeySet<Long>()
    private val imageFailed = ConcurrentHashMap<Long, Long>()
    @Volatile var imageEpoch = 0
        private set

    /**
     * The image behind an asset id at its real size (clothing templates must not be squeezed into a
     * square thumbnail). Decal/Shirt/Pants/Face asset ids are followed to the image they wrap.
     */
    fun image(id: Long): Bitmap? {
        bitmaps.get(id)?.let { return it }
        val f = imageFailed[id]
        if (f != null && System.currentTimeMillis() - f < 90_000) return null
        if (!imagePending.add(id)) return null
        pool.execute {
            try {
                val b = loadImage(id, 0)
                if (b != null) { bitmaps.put(id, b); imageEpoch++; listeners.forEach { it(id) } } else imageFailed[id] = System.currentTimeMillis()
            } catch (_: Exception) { imageFailed[id] = System.currentTimeMillis() } finally { imagePending.remove(id) }
        }
        return null
    }

    /** True when the full-size image couldn't be downloaded (callers then use the public thumbnail). */
    fun imageFailed(id: Long): Boolean = imageFailed.containsKey(id) && bitmaps.get(id) == null

    /** [image], but loaded on the calling thread (call off the UI thread). Null if it can't be had. */
    fun imageBlocking(id: Long): Bitmap? {
        bitmaps.get(id)?.let { return it }
        return try { loadImage(id, 0)?.also { bitmaps.put(id, it); imageEpoch++ } } catch (_: Exception) { null }
    }

    private fun loadImage(id: Long, depth: Int): Bitmap? {
        val bytes = load(id)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        if (depth > 1) return null
        // A Decal/Shirt/Pants/ShirtGraphic model: follow its image property.
        val doc = try { RbxFile.read(bytes) } catch (_: Exception) { return null }
        for (r in doc.roots) for (i in listOf(r) + r.descendants()) {
            for (k in listOf("ShirtTemplate", "PantsTemplate", "Graphic", "Texture", "TextureId", "Image", "ColorMap")) {
                val inner = ImageLoader.assetId(i[k]?.toString()) ?: continue
                if (inner != id) return loadImage(inner, depth + 1)
            }
        }
        return null
    }

    /** The asset id in a content string such as rbxassetid://123 or http://www.roblox.com/asset/?id=123. */
    fun idOf(content: Any?): Long? = ImageLoader.assetId(content?.toString())
}
