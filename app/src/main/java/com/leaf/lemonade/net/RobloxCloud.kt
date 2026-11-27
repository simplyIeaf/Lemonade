package com.leaf.lemonade.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** A place on Roblox the open file is linked to (opened from, or published to, the account). */
data class CloudPlace(val universeId: Long, val placeId: Long, val name: String)

/** Publishing failed; [needsApiKey] means Roblox wants an Open Cloud API key for this upload. */
class PublishException(msg: String, val needsApiKey: Boolean) : IOException(msg)

/**
 * The signed-in account's games: listing experiences and places, version history, downloading
 * place files (current or older versions) and publishing. Only talks to roblox.com hosts.
 */
object RobloxCloud {
    class User(val id: Long, val name: String, val displayName: String)
    /** [groupId] is 0 for games the user owns. */
    class Game(val universeId: Long, val name: String, val rootPlaceId: Long, val updated: String, val owner: String, val groupId: Long = 0L)
    class Group(val id: Long, val name: String, val role: String, val rank: Int)
    class Place(val id: Long, val name: String, val universeId: Long)
    class Version(val number: Int, val created: String, var published: Boolean)

    @Volatile private var csrf: String? = null

    /** Forget the CSRF token (it belongs to one sign-in). */
    fun resetSession() { csrf = null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun cookieHeader(ctx: Context): Map<String, String> {
        val c = RobloxApi.cookie(ctx)
        if (c.isEmpty()) throw AuthRequiredException("Sign in to your Roblox account first (tap the account button at the top right).")
        return mapOf("Cookie" to ".ROBLOSECURITY=$c")
    }

    private fun rotate(ctx: Context, r: Http.Response) {
        for (line in r.headerAll("set-cookie")) {
            val t = line.trim()
            if (!t.startsWith(".ROBLOSECURITY=")) continue
            val v = t.removePrefix(".ROBLOSECURITY=").substringBefore(';').trim()
            if (v.isEmpty() || v.equals("deleted", true) || v == RobloxApi.cookie(ctx)) continue
            RobloxApi.setCookie(ctx, v)
            val id = RobloxApi.userId(ctx)
            if (id != 0L) Accounts.list(ctx).firstOrNull { it.userId == id }?.let { Accounts.upsert(ctx, Accounts.Account(it.userId, it.name, it.displayName, v)) }
        }
    }

    /** Request with the account cookie, handling Roblox's CSRF token handshake for writes. */
    fun authed(ctx: Context, url: String, method: String = "GET", body: ByteArray? = null, contentType: String? = null): Http.Response {
        val base = cookieHeader(ctx)
        val writes = method.uppercase() !in setOf("GET", "HEAD", "OPTIONS", "TRACE")
        var sent = csrf
        var r = Http.call(url, method, base + (sent?.let { mapOf("X-CSRF-TOKEN" to it) } ?: emptyMap()), body, contentType)
        rotate(ctx, r)
        var tries = 0
        while (writes && r.code == 403 && tries < 3) {
            val tok = r.header("x-csrf-token")
            if (tok.isNullOrEmpty() || tok == sent) break
            csrf = tok
            sent = tok
            r = Http.call(url, method, base + ("X-CSRF-TOKEN" to tok), body, contentType)
            rotate(ctx, r)
            tries++
        }
        if (r.code == 401) throw AuthRequiredException("Your Roblox sign-in has expired. Sign in again (account button, top right).")
        return r
    }

    /**
     * GET with the account cookie as JSON. Roblox rate-limits bursts (HTTP 429, sometimes 503),
     * which used to make whole groups disappear from Group Games; those are retried with backoff.
     */
    private fun json(ctx: Context, url: String): JSONObject {
        var r = authed(ctx, url)
        var attempt = 0
        while ((r.code == 429 || r.code == 503) && attempt < 4) {
            val wait = r.header("retry-after")?.toLongOrNull()?.coerceIn(1, 8)?.times(1000) ?: (600L shl attempt)
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            attempt++
            r = authed(ctx, url)
        }
        if (!r.ok) throw HttpException(r.code, Http.describe(r.code, r.body))
        return JSONObject(r.text)
    }

    /** A JSON field by any of [keys] (Roblox's newer APIs use camelCase, older ones PascalCase). */
    private fun JSONObject.str(vararg keys: String): String { for (k in keys) optString(k).takeIf { it.isNotEmpty() && it != "null" }?.let { return it }; return "" }
    private fun JSONObject.long(vararg keys: String): Long { for (k in keys) optLong(k).takeIf { it != 0L }?.let { return it }; return 0L }

    /**
     * Experiences from the search the Studio start page itself uses: [creatorType] "User" (yours),
     * "Team" (shared with you through collaboration, with [targetId] = your user id) or "Group".
     */
    fun searchUniverses(ctx: Context, creatorType: String, targetId: Long, owner: String = "", groupId: Long = 0L, pages: Int = 10): List<Game> {
        val out = LinkedHashMap<Long, Game>()
        val size = 50
        for (page in 0 until pages) {
            val j = json(ctx, "https://apis.roblox.com/universes/v1/search?isTemplates=false&isArchived=false&creatorTargetId=$targetId&searchKey=&sortOrder=Desc" +
                "&search=&creatorType=$creatorType&pageSize=$size&sortParam=LastUpdated&surface=StudioStartPage&PageIndex=$page")
            val arr = j.optJSONArray("data") ?: j.optJSONArray("Data") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.long("id", "Id", "universeId")
                if (id == 0L || o.optBoolean("isArchived") || o.optBoolean("IsArchived")) continue
                val gid = if (groupId != 0L) groupId else if (o.str("creatorType", "CreatorType").equals("Group", true)) o.long("creatorTargetId", "CreatorTargetId") else 0L
                out[id] = Game(id, o.str("name", "Name"), o.long("rootPlaceId", "RootPlaceId"), o.str("updated", "Updated"),
                    o.str("creatorName", "CreatorName").ifEmpty { owner }, gid)
            }
            if (arr.length() < size) break
        }
        return out.values.toList()
    }

    /** Experiences other creators shared with you (Team Create collaborator access), like Studio's "Shared With Me". */
    fun sharedGames(ctx: Context, user: User): List<Game> = searchUniverses(ctx, "Team", user.id)

    private fun nextCursor(j: JSONObject): String? = j.optString("nextPageCursor").takeIf { it.isNotEmpty() && it != "null" }

    fun currentUser(ctx: Context): User {
        val j = json(ctx, "https://users.roblox.com/v1/users/authenticated")
        val u = User(j.getLong("id"), j.optString("name"), j.optString("displayName"))
        RobloxApi.setUser(ctx, u.id, u.name, u.displayName)
        Accounts.upsert(ctx, Accounts.Account(u.id, u.name, u.displayName, RobloxApi.cookie(ctx)))
        return u
    }

    /** The signed-in user from the cached sign-in (no network). */
    fun cachedUser(ctx: Context): User? {
        if (RobloxApi.cookie(ctx).isEmpty() || RobloxApi.userId(ctx) == 0L) return null
        return User(RobloxApi.userId(ctx), RobloxApi.userName(ctx), RobloxApi.displayName(ctx))
    }

    private fun parseUniverses(arr: JSONArray, owner: String, groupId: Long, out: LinkedHashMap<Long, Game>) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optLong("id")
            if (id == 0L || o.optBoolean("isArchived")) continue
            val root = o.optLong("rootPlaceId").takeIf { it != 0L } ?: o.optJSONObject("rootPlace")?.optLong("id") ?: 0L
            out[id] = Game(id, o.optString("name"), root, o.optString("updated", o.optString("updatedAt")), owner, groupId)
        }
    }

    /** Experiences the user owns. */
    fun userGames(ctx: Context, user: User): List<Game> {
        val out = LinkedHashMap<Long, Game>()
        var err: Exception? = null
        try {
            for (g in searchUniverses(ctx, "User", user.id, user.name)) out[g.universeId] = g
        } catch (e: AuthRequiredException) { throw e } catch (e: Exception) { err = e }
        if (out.isEmpty()) try {
            var cursor: String? = null
            for (page in 0 until 10) {
                val j = json(ctx, "https://develop.roblox.com/v1/user/universes?isArchived=false&limit=50&sortOrder=Desc" + (cursor?.let { "&cursor=" + enc(it) } ?: ""))
                parseUniverses(j.optJSONArray("data") ?: JSONArray(), user.name, 0L, out)
                cursor = nextCursor(j) ?: break
            }
        } catch (e: AuthRequiredException) { throw e } catch (e: Exception) { err = e }
        if (out.isEmpty()) try {
            // Public games API; as the owner this also lists private experiences.
            val j = json(ctx, "https://games.roblox.com/v2/users/${user.id}/games?accessFilter=1&limit=50&sortOrder=Desc")
            parseUniverses(j.optJSONArray("data") ?: JSONArray(), user.name, 0L, out)
        } catch (e: AuthRequiredException) { throw e } catch (e: Exception) { if (err == null) err = e }
        if (out.isEmpty() && err != null) throw err
        return out.values.toList()
    }

    /** Groups the user is in, highest rank first. */
    fun groups(ctx: Context, user: User): List<Group> {
        val g = json(ctx, "https://groups.roblox.com/v1/users/${user.id}/groups/roles?includeLocked=true")
        val arr = g.optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val grp = o.optJSONObject("group") ?: return@mapNotNull null
            val role = o.optJSONObject("role")
            Group(grp.optLong("id"), grp.optString("name"), role?.optString("name") ?: "", role?.optInt("rank") ?: 0)
        }.sortedByDescending { it.rank }
    }

    /**
     * Groups whose experiences the Studio start page offers this account (the same list Studio's
     * group selector shows), plus groups it can manage games in. Null if Roblox wouldn't say.
     */
    fun studioGroups(ctx: Context): List<Group>? {
        val out = LinkedHashMap<Long, Group>()
        var any = false
        fun read(url: String) {
            try {
                val j = json(ctx, url)
                val arr = j.optJSONArray("data") ?: j.optJSONArray("groups") ?: return
                any = true
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.long("id", "Id", "groupId")
                    if (id != 0L && id !in out) out[id] = Group(id, o.str("name", "Name"), "", 0)
                }
            } catch (e: AuthRequiredException) { throw e } catch (_: Exception) {}
        }
        read("https://apis.roblox.com/creator-home-api/v1/groups?surface=StudioStartPage")
        read("https://develop.roblox.com/v1/user/groups/canmanage")
        return if (any) out.values.toList() else null
    }

    /**
     * Every group to look in: Studio's list first (complete, and only groups with something to
     * edit), then any other group the account is in, with role names from the groups API.
     */
    fun allGroups(ctx: Context, user: User): GroupList {
        var rolesError: Exception? = null
        val roles = try { groups(ctx, user) } catch (e: AuthRequiredException) { throw e } catch (e: Exception) { rolesError = e; emptyList() }
        val studio = studioGroups(ctx)
        rolesError?.let { if (studio == null) throw it }
        val byId = roles.associateBy { it.id }
        val out = LinkedHashMap<Long, Group>()
        studio?.forEach { s -> out[s.id] = byId[s.id]?.let { Group(it.id, it.name.ifEmpty { s.name }, it.role, it.rank) } ?: s }
        roles.forEach { if (it.id !in out) out[it.id] = it }
        return GroupList(out.values.toList(), studio?.map { it.id }?.toSet() ?: emptySet())
    }

    /** [groups] to look in; [studioListed] are the ones Studio's own group list has. */
    class GroupList(val groups: List<Group>, val studioListed: Set<Long>)

    /**
     * A group's experiences this account can edit. [trusted] groups come from Studio's own group
     * list, so what Studio's search returns for them is shown as is (like Studio); other groups
     * go through the permission check.
     */
    fun editableGroupGames(ctx: Context, group: Group, trusted: Boolean): List<Game> {
        val viaSearch = try { searchUniverses(ctx, "Group", group.id, group.name, group.id, pages = 6) } catch (e: AuthRequiredException) { throw e } catch (_: Exception) { null }
        if (viaSearch != null && viaSearch.isNotEmpty()) return if (trusted) viaSearch else filterEditable(ctx, viaSearch)
        return filterEditable(ctx, groupGames(ctx, group))
    }

    /** A group's experiences, without the permission check (see [editable]). */
    fun groupGames(ctx: Context, group: Group): List<Game> {
        val out = LinkedHashMap<Long, Game>()
        try {
            var cursor: String? = null
            for (page in 0 until 6) {
                val j = json(ctx, "https://develop.roblox.com/v1/groups/${group.id}/universes?isArchived=false&limit=50&sortOrder=Desc" + (cursor?.let { "&cursor=" + enc(it) } ?: ""))
                parseUniverses(j.optJSONArray("data") ?: JSONArray(), group.name, group.id, out)
                cursor = nextCursor(j) ?: break
            }
        } catch (e: AuthRequiredException) { throw e } catch (_: Exception) {
            try {
                val j = json(ctx, "https://games.roblox.com/v2/groups/${group.id}/gamesV2?accessFilter=1&limit=50&sortOrder=Desc")
                parseUniverses(j.optJSONArray("data") ?: JSONArray(), group.name, group.id, out)
            } catch (e: AuthRequiredException) { throw e } catch (_: Exception) {}
        }
        return out.values.toList()
    }

    /**
     * Which of [universeIds] the signed-in user may edit (manage or Team Create edit). Universes
     * the permission service doesn't mention are treated as not editable.
     */
    fun editable(ctx: Context, universeIds: List<Long>): Set<Long> {
        val out = HashSet<Long>()
        var understood = false
        for (chunk in universeIds.distinct().chunked(50)) {
            if (chunk.isEmpty()) continue
            val url = "https://develop.roblox.com/v1/universes/multiget/permissions?" + chunk.joinToString("&") { "ids=$it" }
            val j = json(ctx, url)
            val arr = j.optJSONArray("data") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.has("canManage") || o.has("canCloudEdit")) understood = true
                if (o.optBoolean("canManage") || o.optBoolean("canCloudEdit")) out.add(o.optLong("universeId"))
            }
        }
        // An answer without the fields we know (Roblox changed it) mustn't hide every game.
        if (!understood) throw IOException("Unrecognized permissions response")
        return out
    }

    /** Owned games, games shared with the user, and group games they can edit (groups load a few at a time). */
    fun games(ctx: Context, user: User, includeGroups: Boolean = true): List<Game> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val groupsF = if (includeGroups) pool.submit<GroupList> { try { allGroups(ctx, user) } catch (e: AuthRequiredException) { throw e } catch (_: Exception) { GroupList(emptyList(), emptySet()) } } else null
            val sharedF = pool.submit<List<Game>> { try { sharedGames(ctx, user) } catch (_: Exception) { emptyList() } }
            val out = ArrayList(userGames(ctx, user))
            val gl = try { groupsF?.get() } catch (e: java.util.concurrent.ExecutionException) { (e.cause as? AuthRequiredException)?.let { throw it }; null }
            val futures = gl?.groups.orEmpty().map { g ->
                pool.submit<List<Game>> { try { editableGroupGames(ctx, g, g.id in gl!!.studioListed) } catch (_: Exception) { emptyList() } }
            }
            out.addAll(try { sharedF.get() } catch (_: Exception) { emptyList() })
            for (f in futures) out.addAll(try { f.get() } catch (_: Exception) { emptyList() })
            return out.distinctBy { it.universeId }
        } finally { pool.shutdown() }
    }

    /**
     * Creates a new experience (Studio's Publish As → "Create new game"): Roblox makes it from a
     * template place, then it's renamed. Returns the new game and its start place. [groupId] 0
     * creates it on the user's own account.
     */
    fun createGame(ctx: Context, name: String, groupId: Long = 0L, description: String = ""): Pair<Game, Place> {
        val url = "https://apis.roblox.com/universes/v1/universes/create" + if (groupId != 0L) "?groupId=$groupId" else ""
        val body = JSONObject().put("templatePlaceId", BASEPLATE_TEMPLATE_PLACE).toString().toByteArray()
        val r = authed(ctx, url, "POST", body, "application/json")
        if (!r.ok) throw HttpException(r.code, "Couldn't create the experience: " + Http.describe(r.code, r.body))
        val j = try { JSONObject(r.text) } catch (_: Exception) { JSONObject() }
        fun find(o: JSONObject, vararg keys: String): Long { for (k in keys) { val v = o.optLong(k); if (v != 0L) return v }; return 0L }
        val universeId = find(j, "universeId", "UniverseId", "id")
        if (universeId == 0L) throw IOException("Roblox didn't say which experience it created.")
        var placeId = find(j, "rootPlaceId", "RootPlaceId", "placeId", "rootPlace")
        if (placeId == 0L) placeId = try { json(ctx, "https://develop.roblox.com/v1/universes/$universeId").optLong("rootPlaceId") } catch (_: Exception) { 0L }
        if (placeId == 0L) throw IOException("The experience was created but its start place wasn't found.")
        // Name it (and its start place) like Studio does; a failure here doesn't undo the creation.
        try {
            val cfg = JSONObject().put("name", name).apply { if (description.isNotEmpty()) put("description", description) }
            authed(ctx, "https://develop.roblox.com/v2/universes/$universeId/configuration", "PATCH", cfg.toString().toByteArray(), "application/json")
        } catch (e: AuthRequiredException) { throw e } catch (_: Exception) {}
        try {
            authed(ctx, "https://develop.roblox.com/v2/places/$placeId", "PATCH", JSONObject().put("name", name).toString().toByteArray(), "application/json")
        } catch (e: AuthRequiredException) { throw e } catch (_: Exception) {}
        return Game(universeId, name, placeId, "", if (groupId != 0L) "Group" else RobloxApi.userName(ctx), groupId) to Place(placeId, name, universeId)
    }

    /**
     * A game's Game Settings → Avatar on Roblox. [type]: 0 R6, 1 R15, 2 player choice; [collision]:
     * 0 outer box, 1 inner box; scale limits by "height", "width", "head", "bodyType", "proportion".
     */
    class AvatarSettings(val type: Int, val collision: Int?, val min: Map<String, Float>, val max: Map<String, Float>)

    private val avatarTypeNames = mapOf("MorphToR6" to 0, "MorphToR15" to 1, "PlayerChoice" to 2)

    fun avatarSettings(ctx: Context, universeId: Long): AvatarSettings? {
        val j = json(ctx, "https://develop.roblox.com/v1/universes/$universeId/configuration")
        val type = avatarTypeNames[j.optString("universeAvatarType")] ?: return null
        val collision = when (j.optString("universeCollisionType")) { "OuterBox" -> 0; "InnerBox" -> 1; else -> null }
        fun scales(o: JSONObject?): Map<String, Float> {
            if (o == null) return emptyMap()
            val out = HashMap<String, Float>()
            for (k in listOf("height", "width", "head", "bodyType", "proportion")) if (o.has(k)) out[k] = o.optDouble(k).toFloat()
            return out
        }
        return AvatarSettings(type, collision, scales(j.optJSONObject("universeAvatarMinScales")), scales(j.optJSONObject("universeAvatarMaxScales")))
    }

    /** Saves Game Settings → Avatar to the game on Roblox (what Studio does when you press Save). */
    fun setAvatarSettings(ctx: Context, universeId: Long, s: AvatarSettings) {
        val body = JSONObject().put("universeAvatarType", avatarTypeNames.entries.first { it.value == s.type }.key)
        s.collision?.let { body.put("universeCollisionType", if (it == 1) "InnerBox" else "OuterBox") }
        fun scales(m: Map<String, Float>) = JSONObject().apply { for ((k, v) in m) put(k, v.toDouble()); if (has("width")) put("depth", getDouble("width")) }
        if (s.min.isNotEmpty()) body.put("universeAvatarMinScales", scales(s.min))
        if (s.max.isNotEmpty()) body.put("universeAvatarMaxScales", scales(s.max))
        val r = authed(ctx, "https://develop.roblox.com/v2/universes/$universeId/configuration", "PATCH", body.toString().toByteArray(), "application/json")
        if (!r.ok) throw HttpException(r.code, Http.describe(r.code, r.body))
    }

    /** Roblox's "Baseplate" template place, the one Studio's New → Baseplate starts from. */
    const val BASEPLATE_TEMPLATE_PLACE = 95206881L

    /** Keeps only games the user has edit access to (if the permission check itself fails, keeps all). */
    fun filterEditable(ctx: Context, list: List<Game>): List<Game> {
        if (list.isEmpty()) return list
        val ok = try { editable(ctx, list.map { it.universeId }) } catch (e: AuthRequiredException) { throw e } catch (_: Exception) { return list }
        return list.filter { it.universeId in ok }
    }

    fun places(ctx: Context, game: Game): List<Place> {
        val out = ArrayList<Place>()
        try {
            var cursor: String? = null
            for (page in 0 until 5) {
                val j = json(ctx, "https://develop.roblox.com/v1/universes/${game.universeId}/places?isUniverseCreation=false&limit=100&sortOrder=Asc" + (cursor?.let { "&cursor=" + enc(it) } ?: ""))
                val arr = j.optJSONArray("data") ?: JSONArray()
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); out.add(Place(o.getLong("id"), o.optString("name"), game.universeId)) }
                cursor = nextCursor(j) ?: break
            }
        } catch (e: AuthRequiredException) { throw e } catch (_: Exception) {}
        if (out.isEmpty() && game.rootPlaceId != 0L) out.add(Place(game.rootPlaceId, game.name, game.universeId))
        // Start place first, like Studio's Asset Manager.
        return out.sortedBy { if (it.id == game.rootPlaceId) 0 else 1 }
    }

    /** The experience a place belongs to (public endpoint). */
    fun universeOf(placeId: Long): Long = try {
        Http.json("https://apis.roblox.com/universes/v1/places/$placeId/universe").optLong("universeId")
    } catch (_: Exception) { 0L }

    /** Saved versions of a place, newest first; published ones are flagged. */
    fun versions(ctx: Context, placeId: Long): List<Version> {
        val byNum = LinkedHashMap<Int, Version>()
        fun read(kind: String, published: Boolean) {
            var cursor: String? = null
            for (page in 0 until 6) {
                val j = json(ctx, "https://develop.roblox.com/v1/assets/$placeId/$kind?limit=100&sortOrder=Desc" + (cursor?.let { "&cursor=" + enc(it) } ?: ""))
                val arr = j.optJSONArray("data") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val n = o.optInt("assetVersionNumber", o.optInt("versionNumber"))
                    if (n <= 0) continue
                    val v = byNum.getOrPut(n) { Version(n, o.optString("created"), published) }
                    if (published) v.published = true
                }
                cursor = nextCursor(j) ?: break
            }
        }
        var err: Exception? = null
        try { read("saved-versions", false) } catch (e: AuthRequiredException) { throw e } catch (e: Exception) { err = e }
        try { read("published-versions", true) } catch (e: AuthRequiredException) { throw e } catch (e: Exception) { if (byNum.isEmpty()) err = err ?: e }
        if (byNum.isEmpty() && err != null) throw err
        return byNum.values.sortedByDescending { it.number }
    }

    /** Downloads a place file (the latest version, or [version]) without opening it. */
    fun downloadPlace(ctx: Context, placeId: Long, version: Int? = null): ByteArray = RobloxApi.downloadAsset(ctx, placeId, version)

    /**
     * Uploads [bytes] (a binary .rbxl) as a new version of a place: published, or just saved
     * (Studio's "Save to Roblox"). Roblox retired the old cookie upload endpoint
     * (data.roblox.com/Data/Upload.ashx now answers 404), so uploads go through the Open Cloud
     * place publishing API. That API wants an Open Cloud API key; the signed-in session is tried
     * too, in case Roblox accepts it for this account.
     */
    private const val ASSETS_PATH = "/assets/user-auth/v1/assets"
    private const val OPERATIONS_PATH = "/assets/user-auth/v1/operations"
    private const val APIS = "https://apis.roblox.com"
    private const val MULTIPART_THRESHOLD = 7_340_032
    private const val PLACE_SIZE_LIMIT = 102_760_448

    private fun multipart(request: JSONObject, filename: String, mime: String, bytes: ByteArray): Pair<String, ByteArray> {
        var boundary = "----lemonade-asset-upload-7c1f"
        val hay = String(bytes, Charsets.ISO_8859_1)
        while (hay.contains(boundary)) boundary += "x"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n$request\r\n--$boundary\r\nContent-Disposition: form-data; name=\"fileContent\"; filename=\"$filename\"\r\nContent-Type: $mime\r\n\r\n"
        val out = java.io.ByteArrayOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return "multipart/form-data; boundary=$boundary" to out.toByteArray()
    }

    private fun placeError(detail: String): IOException = IOException("Place Publishing: " + detail.take(300))

    private fun checked(r: Http.Response): String {
        if (r.ok) return r.text
        val msg = try { JSONObject(r.text).let { it.optString("message").ifEmpty { it.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty() } } } catch (_: Exception) { "" }
        throw placeError("HTTP ${r.code}: ${msg.ifEmpty { r.text }.trim()}")
    }

    private fun waitForOperation(ctx: Context, initial: String): JSONObject {
        var op = try { JSONObject(initial) } catch (e: Exception) { throw placeError("Invalid upload operation reply: ${e.message}") }
        var pollUrl: String? = null
        for (attempt in 0..60) {
            val failure = op.opt("error")
            if (failure != null && failure != JSONObject.NULL) throw placeError("Upload operation failed: $failure")
            if (op.optBoolean("done", false)) return op
            if (attempt == 60) break
            if (pollUrl == null) {
                val path = op.optString("path").ifEmpty { op.optString("operationId") }
                if (path.isEmpty()) throw placeError("Upload reply is missing its operation ID")
                val id = path.removePrefix("operations/").removePrefix("$OPERATIONS_PATH/").removePrefix("$APIS$OPERATIONS_PATH/")
                if (id.isEmpty() || !id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) throw placeError("Upload reply contains an invalid operation path")
                pollUrl = "$APIS$OPERATIONS_PATH/$id"
            }
            Thread.sleep(1000)
            op = try { JSONObject(checked(authed(ctx, pollUrl))) } catch (e: org.json.JSONException) { throw placeError("Invalid upload operation reply: ${e.message}") }
        }
        throw placeError("Upload operation did not finish after 60 polls")
    }

    private fun chunkedStart(ctx: Context, startUrl: String, request: JSONObject, mime: String, bytes: ByteArray): String {
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        val plan = JSONArray()
        var off = 0
        while (off < bytes.size) { val n = minOf(MULTIPART_THRESHOLD, bytes.size - off); plan.put(n); off += n }
        val start = JSONObject().put("asset", request).put("file", JSONObject().put("contentType", mime).put("filesize", bytes.size).put("md5Checksum", md5).put("chunkPlan", plan))
        fun call(method: String, url: String, body: ByteArray) = checked(authed(ctx, url, method, body, "application/json"))
        val reply = try { JSONObject(call("PATCH", startUrl, start.toString().toByteArray())) } catch (e: org.json.JSONException) { throw placeError("Invalid multipart upload reply: ${e.message}") }
        val op = reply.optString("operationPath").removePrefix("operations/")
        if (op.isEmpty() || !op.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) throw placeError("Multipart upload reply has no valid operationPath")
        val base = "$APIS$OPERATIONS_PATH/$op"
        val urls = reply.optJSONArray("uploadUrls")
        if (urls == null || urls.length() == 0) throw placeError("Multipart upload reply has no uploadUrls")
        for (i in 0 until urls.length()) {
            val part = urls.getJSONObject(i)
            val chunk = part.optInt("chunkNum", -1).takeIf { it >= 0 } ?: part.optString("chunkNum").toIntOrNull() ?: throw placeError("Multipart upload URL has no chunkNum")
            val st = part.optString("contentStart").toIntOrNull() ?: part.optInt("contentStart", 0)
            val len = part.optString("contentLength").toIntOrNull() ?: part.optInt("contentLength", -1)
            if (len < 0 || st < 0 || st + len > bytes.size) throw placeError("Multipart chunk $chunk is outside the file")
            val url = part.optString("url")
            if (!url.startsWith("https://")) throw placeError("Multipart chunk $chunk has no https upload URL")
            val verb = part.optString("httpVerb", "PUT")
            if (verb != "PUT") throw placeError("Multipart chunk $chunk wants HTTP $verb")
            val piece = bytes.copyOfRange(st, st + len)
            var etag: String? = null
            for (t in 0 until 3) {
                val r = try { Http.call(url, "PUT", emptyMap(), piece, mime) } catch (_: IOException) { null }
                if (r != null && r.ok) { etag = r.header("etag"); if (etag != null) break } else Thread.sleep(1000)
            }
            if (etag == null) throw placeError("Uploading chunk $chunk to storage failed")
            call("POST", "$base:multipartUploadChunkComplete", JSONObject().put("chunkNum", chunk).put("etag", etag).toString().toByteArray())
        }
        return call("POST", "$base:multipartUploadComplete", "{}".toByteArray())
    }

    private fun sessionUpload(ctx: Context, placeId: Long, bytes: ByteArray, xml: Boolean, publish: Boolean): String {
        if (bytes.size > PLACE_SIZE_LIMIT) throw placeError("the place is %.1f MB; Roblox accepts places up to %d MB".format(bytes.size / 1048576.0, PLACE_SIZE_LIMIT / 1048576))
        val request = JSONObject().put("assetType", "Place").put("assetId", placeId).put("published", publish).put("creationContext", JSONObject())
        val filename = if (xml) "place.rbxlx" else "place.rbxl"
        val mime = if (xml) "application/xml" else "application/octet-stream"
        val url = "$APIS$ASSETS_PATH/$placeId"
        val initial = if (bytes.size > MULTIPART_THRESHOLD) chunkedStart(ctx, "$url:multipartUpload", request, mime, bytes) else {
            val (ct, body) = multipart(request, filename, mime, bytes)
            checked(authed(ctx, url, "PATCH", body, ct))
        }
        val op = waitForOperation(ctx, initial)
        val resp = op.optJSONObject("response")
        val rev = resp?.optString("revisionId")?.toLongOrNull() ?: resp?.optLong("revisionId") ?: 0L
        if (rev <= 0L) throw placeError("completed operation has no valid revisionId")
        if (!resp!!.has("isPublished") || resp.optBoolean("isPublished") != publish) throw placeError("revision $rev did not confirm isPublished=$publish")
        return "version $rev"
    }

    fun publish(ctx: Context, universeId0: Long, placeId: Long, bytes: ByteArray, publish: Boolean): String {
        val errors = ArrayList<String>()
        if (RobloxApi.cookie(ctx).isNotEmpty()) {
            try { return sessionUpload(ctx, placeId, bytes, bytes.size > 8 && String(bytes, 0, 8, Charsets.ISO_8859_1) != "<roblox!", publish) }
            catch (e: AuthRequiredException) { errors.add("Signed-in upload: ${e.message}") }
            catch (e: IOException) { errors.add(e.message ?: "Signed-in upload failed") }
        }
        val key = RobloxApi.apiKey(ctx)
        if (key.isNotEmpty()) {
            val universeId = if (universeId0 != 0L) universeId0 else universeOf(placeId)
            if (universeId != 0L) {
                val url = "$APIS/universes/v1/$universeId/places/$placeId/versions?versionType=${if (publish) "Published" else "Saved"}"
                val r = Http.call(url, "POST", mapOf("x-api-key" to key), bytes, "application/octet-stream")
                if (r.ok) {
                    val v = try { JSONObject(r.text).optInt("versionNumber") } catch (_: Exception) { 0 }
                    return if (v > 0) "version $v" else "done"
                }
                errors.add("API key: " + Http.describe(r.code, r.body))
            }
        }
        if (errors.isEmpty()) throw PublishException("Sign in to your Roblox account to publish.", false)
        throw PublishException(errors.joinToString("\n"), key.isEmpty() && RobloxApi.cookie(ctx).isEmpty())
    }

    fun gameIcons(universeIds: List<Long>): Map<Long, String> {
        if (universeIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        for (chunk in universeIds.chunked(50)) try {
            val j = Http.json("https://thumbnails.roblox.com/v1/games/icons?universeIds=${chunk.joinToString(",")}&returnPolicy=PlaceHolder&size=150x150&format=Png&isCircular=false")
            val arr = j.optJSONArray("data") ?: continue
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val u = o.optString("imageUrl"); if (u.isNotEmpty()) out[o.optLong("targetId")] = u }
        } catch (_: Exception) {}
        return out
    }

    fun groupIcons(groupIds: List<Long>): Map<Long, String> {
        if (groupIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        try {
            val j = Http.json("https://thumbnails.roblox.com/v1/groups/icons?groupIds=${groupIds.joinToString(",")}&size=150x150&format=Png&isCircular=false")
            val arr = j.optJSONArray("data") ?: return out
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val u = o.optString("imageUrl"); if (u.isNotEmpty()) out[o.optLong("targetId")] = u }
        } catch (_: Exception) {}
        return out
    }

    fun headshot(userId: Long): String? = try {
        val j = Http.json("https://thumbnails.roblox.com/v1/users/avatar-headshot?userIds=$userId&size=150x150&format=Png&isCircular=true")
        j.optJSONArray("data")?.optJSONObject(0)?.optString("imageUrl")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /** Looks up a user by username (public endpoint). */
    fun userByName(name: String): User? {
        val body = JSONObject().put("usernames", JSONArray().put(name.trim())).put("excludeBannedUsers", true).toString()
        val r = Http.call("https://users.roblox.com/v1/usernames/users", "POST", emptyMap(), body.toByteArray(), "application/json")
        if (!r.ok) throw HttpException(r.code, Http.describe(r.code, r.body))
        val o = JSONObject(r.text).optJSONArray("data")?.optJSONObject(0) ?: return null
        return User(o.optLong("id"), o.optString("name"), o.optString("displayName"))
    }

    /** Ends the session on Roblox's side too, so the stored cookie stops working. */
    fun logout(ctx: Context) {
        try { authed(ctx, "https://auth.roblox.com/v2/logout", "POST") } catch (_: Exception) {}
        csrf = null
    }
}
