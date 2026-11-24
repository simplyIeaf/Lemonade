package com.leaf.lemonade.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Roblox's part material textures (color + normal map) from app/src/main/assets/materials, in
 * two sets: "modern" (the 2022 materials, the default) and "pre2022". They're decoded in the
 * background the first time a material is drawn; [bitmaps] never blocks.
 *
 * These are Roblox's own textures, so they aren't included in this repository; without the
 * assets/materials folder the renderer falls back to its procedural materials and studs.
 * Don't distribute an APK built with them (see the README).
 */
object MaterialTextures {
    /** [color] is null for materials that only have a normal map (Plastic: flat color, fine grain). */
    class MatTex(val color: Bitmap?, val normal: Bitmap?, val studsPerTile: Float)

    /** Material enum value -> texture name. */
    private val names = mapOf(
        256 to "plastic",
        512 to "wood", 528 to "woodplanks", 784 to "marble", 788 to "basalt", 800 to "slate", 804 to "crackedlava",
        816 to "concrete", 820 to "limestone", 832 to "granite", 836 to "pavement", 848 to "brick", 864 to "pebble",
        880 to "cobblestone", 896 to "rock", 912 to "sandstone", 1040 to "corrodedmetal", 1056 to "diamondplate",
        1072 to "foil", 1088 to "metal", 1280 to "grass", 1284 to "leafygrass", 1296 to "sand", 1312 to "fabric",
        1328 to "snow", 1344 to "mud", 1360 to "ground", 1376 to "asphalt", 1392 to "salt", 1536 to "ice",
        1552 to "glacier", 1568 to "glass", 2306 to "ceramictiles", 2307 to "clayrooftiles", 2308 to "roofshingles",
        2311 to "rubber",
    )

    /** Pre-2022 textures that cover twice the area (their texture_pack tiling is 2). */
    private val doubleTile = setOf("brick", "cobblestone", "grass", "marble", "woodplanks")

    private var app: Context? = null
    private val available = HashMap<String, Boolean>()
    private val ready = ConcurrentHashMap<String, MatTex>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile var epoch = 0
        private set

    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    fun hasTexture(material: Int) = names.containsKey(material)

    private fun exists(path: String): Boolean = synchronized(available) {
        available.getOrPut(path) {
            val ctx = app ?: return false
            val dir = path.substringBeforeLast('/')
            try { ctx.assets.list(dir)?.contains(path.substringAfterLast('/')) == true } catch (_: Exception) { false }
        }
    }

    /** Which set and texture name a material uses (falls back to the other set when one lacks it). */
    private fun pick(material: Int, modern: Boolean): Pair<String, String>? {
        val n = names[material] ?: return null
        // Plastic only has the modern normal map; pre-2022 plastic is flat.
        if (n == "plastic") return if (modern && exists("materials/modern/plastic_n.jpg")) "modern" to n else null
        val first = if (modern) "modern" else "pre2022"
        val second = if (modern) "pre2022" else "modern"
        if (exists("materials/$first/${n}_c.jpg")) return first to n
        if (exists("materials/$second/${n}_c.jpg")) return second to n
        return null
    }

    private var surfaces: Bitmap? = null
    private var surfacesTried = false

    /**
     * Roblox's surface texture atlas packed into one small RGB image (assets/materials/surfaces.png):
     * R = Studs, G = Weld/Glue, B = Inlet, each a 2x8-stud tile shaded around grey 128. Universal
     * is the Studs/Inlet checkerboard. Null when the file isn't bundled (procedural studs then).
     */
    fun surfaces(): Bitmap? {
        if (surfacesTried) return surfaces
        surfacesTried = true
        val ctx = app ?: return null
        surfaces = try {
            ctx.assets.open("materials/surfaces.png").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }) }
        } catch (_: Exception) { null }
        return surfaces
    }

    /** The material's textures, or null while they load (or if the material has none). */
    fun get(material: Int, modern: Boolean): MatTex? {
        val (set, n) = pick(material, modern) ?: return null
        val key = "$set/$n"
        ready[key]?.let { return it }
        if (key in failed || !pending.add(key)) return null
        val ctx = app ?: return null
        pool.execute {
            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val color = if (n == "plastic") null
                    else ctx.assets.open("materials/${key}_c.jpg").use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IllegalStateException("bad image")
                val normal = try { ctx.assets.open("materials/${key}_n.jpg").use { BitmapFactory.decodeStream(it, null, opts) } } catch (_: Exception) { null }
                if (color == null && normal == null) throw IllegalStateException("no maps")
                val spt = if (n == "plastic") 4f else if (set == "pre2022" && n in doubleTile) 16f else 8f
                ready[key] = MatTex(color, normal, spt)
            } catch (_: Exception) {
                failed.add(key)
            } finally {
                pending.remove(key)
                epoch++
            }
        }
        return null
    }
}
