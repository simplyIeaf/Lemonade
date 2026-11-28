package com.leaf.lemonade.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

class HttpException(val code: Int, msg: String) : IOException(msg)
class AuthRequiredException(msg: String) : IOException(msg)

object Http {
    private const val UA = "Lemonade/1.0 (Android)"

    class Response(val code: Int, val body: ByteArray, private val headers: Map<String?, List<String>>) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key?.equals(name, true) == true }?.value?.firstOrNull()
        fun headerAll(name: String): List<String> = headers.entries.filter { it.key?.equals(name, true) == true }.flatMap { it.value }
        val text: String get() = String(body)
        val ok get() = code in 200..299
    }

    /** Headers that carry credentials; never forwarded to a redirect outside roblox.com. */
    private val authHeaders = setOf("cookie", "x-csrf-token")

    /** Only Roblox's own servers, over HTTPS, ever see the sign-in cookie. */
    private fun isRoblox(u: String): Boolean = try {
        val url = URL(u)
        url.protocol.equals("https", true) && url.host.lowercase().let { it == "roblox.com" || it.endsWith(".roblox.com") }
    } catch (_: Exception) { false }

    fun call(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
             contentType: String? = null, maxBytes: Int = 64 * 1024 * 1024): Response {
        var u = url
        var redirects = 0
        // Credentials are dropped for anything that isn't Roblox over HTTPS (also the first URL).
        var hdrs = if (isRoblox(u)) headers else headers.filterKeys { it.lowercase() !in authHeaders }
        var m = method
        var b = body
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = if (b != null) 120000 else 30000
            c.requestMethod = m
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Encoding", "gzip")
            for ((k, v) in hdrs) c.setRequestProperty(k, v)
            if (b != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "application/json")
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            } else if (m == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val code = c.responseCode
            if (code in 300..399 && redirects < 6) {
                val loc = c.getHeaderField("Location") ?: throw HttpException(code, "Redirect without location")
                c.disconnect()
                u = URL(URL(u), loc).toString()
                if (!isRoblox(u)) hdrs = hdrs.filterKeys { it.lowercase() !in authHeaders }
                if (code == 303) { m = "GET"; b = null }
                redirects++
                continue
            }
            val stream = (if (code >= 400) c.errorStream else c.inputStream)
            val respHeaders = c.headerFields ?: emptyMap()
            if (stream == null) { c.disconnect(); return Response(code, ByteArray(0), respHeaders) }
            val raw = if ("gzip".equals(c.contentEncoding, true)) GZIPInputStream(stream) else stream
            val out = ByteArrayOutputStream()
            raw.use { ins ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("Response too large")
                }
            }
            c.disconnect()
            return Response(code, out.toByteArray(), respHeaders)
        }
    }

    fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null, maxBytes: Int = 64 * 1024 * 1024): Pair<Int, ByteArray> {
        val r = call(url, method, headers, body?.toByteArray(), if (body != null) "application/json" else null, maxBytes)
        return r.code to r.body
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        val (code, bytes) = request(url, headers = headers, maxBytes = maxBytes)
        if (code !in 200..299) throw HttpException(code, describe(code, bytes))
        return bytes
    }

    /**
     * A short, readable error for a failed response: Roblox's JSON error message when there is one,
     * never a dump of an HTML error page.
     */
    fun describe(code: Int, body: ByteArray): String {
        val text = String(body).trim()
        val reason = when (code) {
            400 -> "bad request"; 401 -> "not signed in / not authorized"; 403 -> "permission denied"; 404 -> "not found"
            409 -> "conflict"; 413 -> "file too large"; 429 -> "too many requests, try again in a minute"
            in 500..599 -> "Roblox server error"; else -> ""
        }
        val msg = when {
            text.startsWith("<") -> ""
            text.startsWith("{") -> try {
                val j = JSONObject(text)
                j.optString("message").ifEmpty { j.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "" }
                    .ifEmpty { j.optString("error") }
            } catch (_: Exception) { text.take(160) }
            else -> text.take(160)
        }
        return "HTTP $code" + (if (reason.isNotEmpty()) " ($reason)" else "") + (if (msg.isNotEmpty()) ": $msg" else "")
    }

    fun json(url: String, headers: Map<String, String> = emptyMap()): JSONObject = JSONObject(String(get(url, headers)))
}

/** Public Roblox web APIs used by the Toolbox (Creator Store) and image loading. */
object RobloxApi {
    class Item(
        val id: Long, val name: String, val creator: String, val verified: Boolean, val description: String,
        val upVotes: Int, val downVotes: Int, val hasScripts: Boolean, val endorsed: Boolean,
        /** Creator Store price in US cents (0 = free). Paid assets can't be downloaded until bought. */
        val priceCents: Long = 0, val scriptCount: Int = 0, val meshParts: Int = 0, val triangles: Int = 0,
        val animations: Int = 0, val sounds: Int = 0, val created: String = "", val updated: String = "",
        val creatorId: Long = 0, val creatorIsGroup: Boolean = false, val category: String = "",
    ) {
        val isPaid get() = priceCents > 0
        val priceText get() = if (priceCents <= 0) "Free" else "$" + "%d.%02d".format(priceCents / 100, priceCents % 100)
        val likePercent get() = if (upVotes + downVotes > 0) upVotes * 100 / (upVotes + downVotes) else -1
    }

    /** Creator Store page for an asset (where paid assets are bought). */
    fun storeUrl(id: Long) = "https://create.roblox.com/store/asset/$id"

    class Page(val ids: List<Long>, val nextCursor: String?, val total: Int)

    /** Creator Store price filter (the v2 search filters by price on the server). */
    enum class Price(val label: String) { ANY("Any price"), FREE("Free"), PAID("Paid") }

    private fun v2Category(c: Category) = when (c) {
        Category.MODELS -> "Model"; Category.DECALS -> "Decal"; Category.AUDIO -> "Audio"; Category.MESHES -> "MeshPart"; Category.PLUGINS -> "Plugin"
    }

    /** Search with a price filter (toolbox-service v2: maxPriceCents=0 = free, minPriceCents=1 = paid). */
    fun searchPriced(keyword: String, category: Category, price: Price, cursor: String?, num: Int = 30): Page {
        if (price == Price.ANY) return search(keyword, category, cursor, num)
        val sb = StringBuilder("https://apis.roblox.com/toolbox-service/v2/assets:search?searchCategoryType=${v2Category(category)}&maxPageSize=$num")
        if (keyword.isNotBlank()) sb.append("&query=").append(enc(keyword.trim()))
        sb.append(if (price == Price.FREE) "&maxPriceCents=0" else "&minPriceCents=1")
        if (cursor != null) sb.append("&pageToken=").append(enc(cursor))
        val j = Http.json(sb.toString())
        val arr = j.optJSONArray("creatorStoreAssets") ?: JSONArray()
        val ids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("asset")?.optLong("id")?.takeIf { id -> id != 0L } }
        return Page(ids, j.optString("nextPageToken").takeIf { it.isNotEmpty() && it != "null" }, j.optInt("totalResults"))
    }

    /** USD cents from {"significand": 9990000000, "exponent": -9}. */
    private fun cents(q: JSONObject?): Long {
        q ?: return 0
        val s = q.optLong("significand"); val e = q.optInt("exponent")
        if (s == 0L) return 0
        val v = s.toDouble() * Math.pow(10.0, (e + 2).toDouble())
        return Math.round(v)
    }

    /** Extra details only the v2 asset endpoint has: preview screenshot asset ids. */
    fun previewImages(id: Long): List<Long> = try {
        val j = Http.json("https://apis.roblox.com/toolbox-service/v2/assets/$id")
        val arr = j.optJSONObject("asset")?.optJSONObject("previewAssets")?.optJSONArray("imagePreviewAssets") ?: JSONArray()
        (0 until arr.length()).map { arr.optLong(it) }.filter { it != 0L }
    } catch (_: Exception) { emptyList() }

    /** Creator Store categories (ids are Roblox asset type ids). */
    enum class Category(val id: Int, val label: String) {
        MODELS(10, "Models"), DECALS(13, "Decals"), AUDIO(3, "Audio"), MESHES(40, "Meshes"), PLUGINS(38, "Plugins")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Creator Store search (the same endpoint Studio's Toolbox uses). */
    fun search(keyword: String, category: Category = Category.MODELS, cursor: String? = null, num: Int = 30): Page {
        val sb = StringBuilder("https://apis.roblox.com/toolbox-service/v1/marketplace/${category.id}?num=$num&includeOnlyVerifiedCreators=false")
        if (keyword.isNotBlank()) sb.append("&keyword=").append(enc(keyword.trim()))
        if (cursor != null) sb.append("&cursor=").append(enc(cursor))
        val j = Http.json(sb.toString())
        val data = j.optJSONArray("data") ?: JSONArray()
        val ids = (0 until data.length()).map { data.getJSONObject(it).getLong("id") }
        return Page(ids, j.optString("nextPageCursor").takeIf { it.isNotEmpty() && it != "null" }, j.optInt("totalResults"))
    }

    fun details(ids: List<Long>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        val j = Http.json("https://apis.roblox.com/toolbox-service/v1/items/details?assetIds=" + ids.joinToString(","))
        val data = j.optJSONArray("data") ?: JSONArray()
        val byId = HashMap<Long, Item>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val a = o.optJSONObject("asset") ?: continue
            val cr = o.optJSONObject("creator")
            val v = o.optJSONObject("voting")
            val tech = a.optJSONObject("modelTechnicalDetails")
            val counts = tech?.optJSONObject("instanceCounts")
            // Paid Creator Store assets: fiatProduct (v1) / creatorStoreProduct (v2) carry a USD price.
            val prod = o.optJSONObject("fiatProduct") ?: o.optJSONObject("creatorStoreProduct")
            val price = if (prod?.optBoolean("isFree") == true) 0L else cents(prod?.optJSONObject("purchasePrice")?.optJSONObject("quantity"))
            val item = Item(
                a.getLong("id"), a.optString("name"), cr?.optString("name") ?: "", cr?.optBoolean("isVerifiedCreator") == true,
                a.optString("description"), v?.optInt("upVotes") ?: 0, v?.optInt("downVotes") ?: 0,
                a.optBoolean("hasScripts") || (counts?.optInt("script") ?: 0) > 0, a.optBoolean("isEndorsed"),
                priceCents = price,
                scriptCount = a.optInt("scriptCount", counts?.optInt("script") ?: 0),
                meshParts = counts?.optInt("meshPart") ?: 0,
                triangles = tech?.optJSONObject("objectMeshSummary")?.optInt("triangles") ?: 0,
                animations = counts?.optInt("animation") ?: 0,
                sounds = counts?.optInt("audio") ?: 0,
                created = a.optString("createdUtc"), updated = a.optString("updatedUtc"),
                creatorId = cr?.optLong("id") ?: 0L, creatorIsGroup = cr?.optInt("type") == 2,
                category = a.optString("categoryPath"),
            )
            byId[item.id] = item
        }
        return ids.mapNotNull { byId[it] }
    }

    /** Thumbnail URLs per asset id. An empty string means "still generating, try again". */
    fun thumbnailUrls(ids: List<Long>, size: String = "150x150"): Map<Long, String> {
        val j = Http.json("https://thumbnails.roblox.com/v1/assets?assetIds=${ids.joinToString(",")}&size=$size&format=Png&isCircular=false")
        val out = HashMap<Long, String>()
        val data = j.optJSONArray("data") ?: return out
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val id = o.optLong("targetId")
            when (o.optString("state")) {
                "Completed" -> out[id] = o.optString("imageUrl")
                "Pending" -> out[id] = ""
            }
        }
        return out
    }

    // ------------------------------------------------------------------ authenticated download

    private const val PREFS = "roblox_auth"
    fun cookie(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString("cookie", "") ?: ""
        if (raw.isNotEmpty() && !SecureStore.isSealed(raw)) sp.edit().putString("cookie", SecureStore.seal(raw)).apply()
        return SecureStore.open(raw)
    }
    fun setCookie(ctx: Context, cookie: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cookie", SecureStore.seal(cookie.trim().removePrefix(".ROBLOSECURITY=").trim())).apply()
    }
    /** Guest mode: no Roblox account is active. */
    fun isGuest(ctx: Context) = cookie(ctx).isEmpty()
    fun hasCredentials(ctx: Context) = cookie(ctx).isNotEmpty()

    /** Signed-in account shown in the UI (cached so it appears without a network round trip). */
    fun userName(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("userName", "") ?: ""
    fun displayName(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("displayName", "") ?: ""
    fun userId(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("userId", 0L)
    fun setUser(ctx: Context, id: Long, name: String, displayName: String = "") {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("userId", id).putString("userName", name).putString("displayName", displayName).apply()
    }

    // ------------------------------------------------------------------ Creator Store audio (Studio's Audio tab)

    /** Search options matching Studio's audio filters. */
    class AudioQuery(
        val keyword: String = "",
        /** "Music", "SoundEffect" or null for both. */
        val type: String? = null,
        /** Relevance, Trending, Top, AudioDuration, CreateTime, UpdatedTime, Ratings */
        val sort: String = "Relevance",
        val minSeconds: Int = 0,
        val maxSeconds: Int? = null,
        /** Top charts: None, Current, Week, Month, Year */
        val chart: String = "None",
        val artist: String = "",
    )

    class AudioItem(
        val id: Long, val name: String, val artist: String, val title: String, val seconds: Int, val type: String,
        val creator: String, val verified: Boolean, val album: String,
    )

    class AudioPage(val items: List<AudioItem>, val nextToken: String?)

    /** Creator Store audio search (toolbox-service v2, the same service Studio's Toolbox uses). */
    fun searchAudio(ctx: Context?, q: AudioQuery, pageToken: String?, pageSize: Int = 30): AudioPage {
        val sb = StringBuilder("https://apis.roblox.com/toolbox-service/v2/assets:search?searchCategoryType=Audio&maxPageSize=$pageSize&includeOnlyVerifiedCreators=false")
        if (q.keyword.isNotBlank()) sb.append("&query=").append(enc(q.keyword.trim()))
        if (q.type != null) sb.append("&audioTypes=").append(q.type)
        if (q.sort != "Relevance") sb.append("&sortCategory=").append(q.sort).append("&sortDirection=").append(if (q.sort == "AudioDuration") "Ascending" else "Descending")
        if (q.minSeconds > 0) sb.append("&audioMinDurationSeconds=").append(q.minSeconds)
        q.maxSeconds?.let { sb.append("&audioMaxDurationSeconds=").append(it) }
        if (q.chart != "None") sb.append("&musicChartType=").append(q.chart).append("&includeTopCharts=true")
        if (q.artist.isNotBlank()) sb.append("&audioArtist=").append(enc(q.artist))
        if (pageToken != null) sb.append("&pageToken=").append(enc(pageToken))
        val c = ctx?.let { cookie(it) } ?: ""
        val headers = if (c.isNotEmpty()) mapOf("Cookie" to ".ROBLOSECURITY=$c") else emptyMap()
        val j = JSONObject(String(Http.get(sb.toString(), headers)))
        val arr = j.optJSONArray("creatorStoreAssets") ?: j.optJSONArray("assets") ?: j.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<AudioItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val a = o.optJSONObject("asset") ?: o
            val id = a.optLong("id"); if (id == 0L) continue
            val ad = a.optJSONObject("audioDetails") ?: a.optJSONObject("audio") ?: a
            val cr = o.optJSONObject("creator") ?: a.optJSONObject("creator")
            val secs = ad.optInt("durationSeconds", ad.optInt("duration", a.optInt("durationSeconds")))
            val type = ad.optString("audioType", a.optString("audioType", a.optString("assetSubType", "")))
            out.add(AudioItem(id, a.optString("name"), ad.optString("artist", a.optString("artist")), ad.optString("title", a.optString("title")),
                secs, type, cr?.optString("name") ?: "", cr?.optBoolean("verified") == true || cr?.optBoolean("isVerifiedCreator") == true,
                ad.optString("album", a.optString("album"))))
        }
        val next = j.optString("nextPageToken").takeIf { it.isNotEmpty() && it != "null" }
        return AudioPage(out, next)
    }

    /** Fallback when the v2 search isn't reachable: the older marketplace search plus item details. */
    fun searchAudioLegacy(keyword: String, cursor: String?): AudioPage {
        val page = search(keyword, Category.AUDIO, cursor)
        val details = page.ids.chunked(30).flatMap { details(it) }
        return AudioPage(details.map { AudioItem(it.id, it.name, it.creator, it.name, 0, "", it.creator, it.verified, "") }, page.nextCursor)
    }

    class InvItem(val id: Long, val name: String, val created: String)

    /**
     * One page (newest first) of a user's inventory of one asset type: what Studio's Toolbox lists
     * under Inventory (models, meshes, plugins... the account made or got). Returns the items
     * and the next page's cursor.
     */
    fun inventory(ctx: Context, userId: Long, assetType: Int, cursor: String?): Pair<List<InvItem>, String?> {
        val c = cookie(ctx)
        val headers = if (c.isNotEmpty()) mapOf("Cookie" to ".ROBLOSECURITY=$c") else emptyMap()
        val url = "https://inventory.roblox.com/v2/users/$userId/inventory/$assetType?limit=100&sortOrder=Desc" + (cursor?.let { "&cursor=" + enc(it) } ?: "")
        val r = Http.call(url, headers = headers)
        if (r.code == 401) throw AuthRequiredException("Sign in again to see your inventory.")
        if (!r.ok) throw HttpException(r.code, Http.describe(r.code, r.body))
        val j = JSONObject(r.text)
        val arr = j.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<InvItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("assetId", o.optLong("id"))
            if (id == 0L) continue
            out.add(InvItem(id, o.optString("assetName", o.optString("name", "Asset $id")), o.optString("created")))
        }
        return out to j.optString("nextPageCursor").takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Asset id from a plain number or any Roblox asset link (assetdelivery, Creator Store, library, catalog). */
    fun parseAssetId(text: String): Long? {
        val t = text.trim()
        t.toLongOrNull()?.let { return it }
        val rx = Regex("""(?:[?&]id=|/asset/|/library/|/catalog/|/store/asset/|/plugins/|/models/|rbxassetid://)(\d+)""", RegexOption.IGNORE_CASE)
        return rx.find(t)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Downloads an asset file (e.g. a model's .rbxm, or a place you own). Roblox requires
     * authentication for asset downloads, so this uses the signed-in account. [version] picks an older version of the asset.
     */
    fun downloadAsset(ctx: Context, id: Long, version: Int? = null): ByteArray {
        val cookie = cookie(ctx)
        val v = version?.let { "&version=$it" } ?: ""
        var lastError: Exception? = null
        if (cookie.isNotEmpty()) {
            try {
                var auth = mapOf("Cookie" to ".ROBLOSECURITY=$cookie")
                val v2 = "https://assetdelivery.roblox.com/v2/assetId/$id" + (version?.let { "/version/$it" } ?: "")
                var j = Http.json(v2, auth)
                if (j.optJSONArray("locations") == null && j.optJSONArray("errors")?.optJSONObject(0)?.optInt("code") == 401) {
                    try { RobloxCloud.authed(ctx, "https://users.roblox.com/v1/users/authenticated") } catch (_: Exception) {}
                    val fresh = cookie(ctx)
                    if (fresh.isNotEmpty() && fresh != cookie) {
                        auth = mapOf("Cookie" to ".ROBLOSECURITY=$fresh")
                        j = Http.json(v2, auth)
                    }
                }
                val locs = j.optJSONArray("locations")
                val loc = locs?.optJSONObject(0)?.optString("location")
                if (!loc.isNullOrEmpty()) return ungzip(Http.get(loc))
                return ungzip(Http.get("https://assetdelivery.roblox.com/v1/asset/?id=$id$v", auth))
            } catch (e: Exception) {
                lastError = e
                try { return ungzip(Http.get("https://assetdelivery.roblox.com/v1/asset/?id=$id$v", mapOf("Cookie" to ".ROBLOSECURITY=$cookie"))) } catch (_: Exception) {}
            }
        }
        if (key.isEmpty() && cookie.isEmpty()) {
            try { return ungzip(Http.get("https://assetdelivery.roblox.com/v1/asset/?id=$id$v")) } catch (e: HttpException) {
                if (e.code == 401 || e.code == 403) throw AuthRequiredException("Roblox requires sign-in to download assets.")
                throw e
            }
        }
        val e = lastError
        if (e is HttpException && (e.code == 401 || e.code == 403)) throw AuthRequiredException("Roblox rejected the saved credentials (HTTP ${e.code}). Try signing in again.")
        throw e ?: IOException("Download failed")
    }

    fun ungzip(b: ByteArray): ByteArray {
        if (b.size > 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte()) return GZIPInputStream(b.inputStream()).use { it.readBytes() }
        return b
    }
}
