package com.leaf.lemonade.engine

import android.content.Context
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.*
import com.leaf.lemonade.net.AssetFetcher
import com.leaf.lemonade.net.Http
import com.leaf.lemonade.net.RobloxApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The playtest character's look: the signed-in account's Roblox avatar, any other Roblox user's
 * avatar, a custom one, or the classic default. R15 avatars are built like Roblox builds them:
 * the account's body part and head assets (the default rounded R15 parts where none are worn),
 * joined through their rig attachments, scaled, with classic clothing composited onto the body
 * textures and accessories on their attachments. R6 avatars dress the blocky R6 rig.
 */
object Avatar {
    /** [CHARACTER]: a saved model or part the player spawns as in every game (File → Playtest Avatar). */
    enum class Mode { ACCOUNT, USER, CUSTOM, CLASSIC, CHARACTER }

    class Scales(val height: Float = 1f, val width: Float = 1f, val depth: Float = 1f, val head: Float = 1f)

    class Spec(
        val colors: Map<String, Color3>,
        val shirt: String?, val pants: String?, val tshirt: String?, val face: String?,
        /** Accessory model files (each is one asset, read again for every spawn). */
        val accessories: List<ByteArray>,
        val label: String,
        /** Built as an R15 character (the avatar's body type on Roblox). */
        val r15: Boolean = false,
        val scales: Scales = Scales(),
        /** Body part and head asset files by Roblox asset type (17 head, 27-31 body parts, 79 dynamic head). */
        val bodyParts: Map<Int, ByteArray> = emptyMap(),
        /** Classic clothing composited for the R15 body meshes, by body group ("Torso", "LeftArm"...). */
        val clothing: Map<String, android.graphics.Bitmap> = emptyMap(),
        /** A saved model file to play as instead of a dressed rig ([Mode.CHARACTER]). */
        val character: ByteArray? = null,
    ) {
        /** The same avatar on the other rig, with scales limited to a game's ranges (Game Settings → Avatar). */
        fun forRig(r15: Boolean, scales: Scales = this.scales) =
            Spec(colors, shirt, pants, tshirt, face, accessories, label, r15, scales, bodyParts, clothing, character)
    }

    /** Content strings for generated textures (composited clothing); see [texture]. */
    const val TEX_PREFIX = "mobilestudio-avatar://"
    private val generated = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    fun texture(content: String): android.graphics.Bitmap? = generated[content]
    /** Registers a generated texture (e.g. clothing composited by the renderer) under [content]. */
    fun putTexture(content: String, bmp: android.graphics.Bitmap) { generated[content] = bmp }

    private const val PREFS = "avatar"
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile var current: Spec? = null
        private set
    @Volatile var version = 0
        private set
    @Volatile var loading = false
        private set
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null

    val PARTS = listOf("Head", "Torso", "Left Arm", "Right Arm", "Left Leg", "Right Leg")
    private val colorKeys = mapOf("Head" to "head", "Torso" to "torso", "Left Arm" to "leftArm", "Right Arm" to "rightArm", "Left Leg" to "leftLeg", "Right Leg" to "rightLeg")
    val CLASSIC_COLORS = mapOf(
        "Head" to Color3.fromRGB(245, 205, 48), "Left Arm" to Color3.fromRGB(245, 205, 48), "Right Arm" to Color3.fromRGB(245, 205, 48),
        "Torso" to Color3.fromRGB(13, 105, 172), "Left Leg" to Color3.fromRGB(164, 189, 71), "Right Leg" to Color3.fromRGB(164, 189, 71),
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): Mode = try { Mode.valueOf(prefs(ctx).getString("mode", "ACCOUNT")!!) } catch (_: Exception) { Mode.ACCOUNT }
    fun otherUser(ctx: Context): Pair<Long, String> = prefs(ctx).getLong("userId", 0L) to (prefs(ctx).getString("userName", "") ?: "")
    fun custom(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("custom", "{}")!!) } catch (_: Exception) { JSONObject() }

    fun setMode(ctx: Context, m: Mode, userId: Long = 0L, userName: String = "") {
        prefs(ctx).edit().putString("mode", m.name).apply {
            if (m == Mode.USER) putLong("userId", userId).putString("userName", userName)
        }.apply()
        refresh(ctx)
    }

    fun setCustom(ctx: Context, o: JSONObject) {
        prefs(ctx).edit().putString("custom", o.toString()).putString("mode", Mode.CUSTOM.name).apply()
        refresh(ctx)
    }

    /** Description of the current choice for menus. */
    fun describe(ctx: Context): String = when (mode(ctx)) {
        Mode.ACCOUNT -> if (RobloxApi.isGuest(ctx)) "Classic (guest)" else "My avatar (@${RobloxApi.userName(ctx)})"
        Mode.USER -> "@${otherUser(ctx).second}'s avatar"
        Mode.CUSTOM -> "Custom avatar"
        Mode.CLASSIC -> "Classic"
        Mode.CHARACTER -> "Character: ${characterName(ctx).ifEmpty { "custom model" }}"
    }

    // ------------------------------------------------------------------ custom character (a saved model)

    private fun characterFile(ctx: Context) = java.io.File(ctx.applicationContext.filesDir, "playtest_character.rbxm")
    fun characterName(ctx: Context): String = prefs(ctx).getString("characterName", "") ?: ""
    fun hasCharacter(ctx: Context) = characterFile(ctx).isFile

    /** Saves [bytes] (a .rbxm of one model or part) as the character to play as, in every place. */
    fun setCharacter(ctx: Context, bytes: ByteArray, name: String) {
        val f = characterFile(ctx)
        val tmp = java.io.File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        prefs(ctx).edit().putString("characterName", name).putString("mode", Mode.CHARACTER.name).apply()
        refresh(ctx)
    }

    fun clearCharacter(ctx: Context) {
        characterFile(ctx).delete()
        prefs(ctx).edit().remove("characterName").apply()
        if (mode(ctx) == Mode.CHARACTER) setMode(ctx, Mode.ACCOUNT)
    }

    /** Re-resolves the avatar in the background (account switch, settings change, app start). */
    fun refresh(ctx: Context) {
        app = ctx.applicationContext
        val c = ctx.applicationContext
        loading = true
        pool.execute {
            val spec = try { resolve(c) } catch (e: Exception) {
                OutputLog.warn("Avatar: ${e.message ?: e}")
                classic()
            }
            current = spec
            version++
            loading = false
            listeners.forEach { it() }
        }
    }

    private fun classic() = Spec(CLASSIC_COLORS, null, null, null, null, emptyList(), "Classic")

    private fun resolve(ctx: Context): Spec = if (RobloxApi.isGuest(ctx) || RobloxApi.userId(ctx) == 0L) classic() else when (mode(ctx)) {
        Mode.CLASSIC -> classic()
        Mode.ACCOUNT -> { val id = RobloxApi.userId(ctx); if (RobloxApi.isGuest(ctx) || id == 0L) classic() else fromUser(id, "@" + RobloxApi.userName(ctx)) }
        Mode.USER -> { val (id, n) = otherUser(ctx); if (id == 0L) classic() else fromUser(id, "@$n") }
        Mode.CUSTOM -> fromCustom(custom(ctx))
        Mode.CHARACTER -> {
            val f = characterFile(ctx)
            if (f.isFile) Spec(CLASSIC_COLORS, null, null, null, null, emptyList(), characterName(ctx).ifEmpty { "Character" }, character = f.readBytes())
            else classic()
        }
    }

    private fun hex(s: String?): Color3? = s?.let { Color3.fromHex(it) }

    private fun fromUser(userId: Long, label: String): Spec {
        var colors = HashMap(CLASSIC_COLORS)
        var assets = JSONArray()
        var j: JSONObject
        try {
            j = Http.json("https://avatar.roblox.com/v2/avatar/users/$userId/avatar")
            j.optJSONObject("bodyColor3s")?.let { bc ->
                for ((part, k) in colorKeys) hex(bc.optString("${k}Color3"))?.let { colors[part] = it }
            }
            assets = j.optJSONArray("assets") ?: JSONArray()
        } catch (_: Exception) {
            j = Http.json("https://avatar.roblox.com/v1/users/$userId/avatar")
            j.optJSONObject("bodyColors")?.let { bc ->
                for ((part, k) in colorKeys) { val n = bc.optInt("${k}ColorId", -1); if (n >= 0) colors[part] = BrickColors.color(n) }
            }
            assets = j.optJSONArray("assets") ?: JSONArray()
        }
        var shirt: String? = null; var pants: String? = null; var tshirt: String? = null; var face: String? = null
        val acc = ArrayList<ByteArray>()
        val bodyParts = HashMap<Int, ByteArray>()
        val signedIn = app?.let { !RobloxApi.isGuest(it) } == true
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val id = a.optLong("id")
            val type = a.optJSONObject("assetType")?.optInt("id") ?: continue
            when (type) {
                11 -> shirt = "rbxassetid://$id"
                12 -> pants = "rbxassetid://$id"
                2 -> tshirt = "rbxassetid://$id"
                18 -> face = "rbxassetid://$id"
                8, 41, 42, 43, 44, 45, 46, 47 -> if (signedIn) try { acc.add(AssetFetcher.load(id)) } catch (e: Exception) { OutputLog.warn("Avatar accessory $id: ${e.message}") }
                17, 27, 28, 29, 30, 31, 79 -> if (signedIn) try { bodyParts[type] = AssetFetcher.load(id) } catch (e: Exception) { OutputLog.warn("Avatar body part $id: ${e.message}") }
            }
        }
        // Resolve clothing to the images they wrap (scripts read the real template ids).
        if (signedIn) {
            shirt = template(shirt, "ShirtTemplate"); pants = template(pants, "PantsTemplate"); tshirt = template(tshirt, "Graphic"); face = template(face, "Texture")
        }
        // R15 needs the body meshes, which only download when signed in; otherwise use the R6 rig.
        val r15 = signedIn && j.optString("playerAvatarType", "R6") == "R15"
        val sc = j.optJSONObject("scales")
        val scales = Scales(
            (sc?.optDouble("height", 1.0) ?: 1.0).toFloat(), (sc?.optDouble("width", 1.0) ?: 1.0).toFloat(),
            (sc?.optDouble("depth", 1.0) ?: 1.0).toFloat(), (sc?.optDouble("head", 1.0) ?: 1.0).toFloat(),
        )
        // Composited for R15 even on an R6 avatar: a game can make everyone R15 (Game Settings → Avatar).
        val clothing = if (signedIn) composeClothing(colors, shirt, pants, tshirt, bodyParts) else emptyMap()
        return Spec(colors, shirt, pants, tshirt, face, acc, label, r15, scales, bodyParts, clothing)
    }

    /** The image a Shirt/Pants/ShirtGraphic/face asset points to (or the asset itself if it can't be read). */
    private fun template(content: String?, prop: String): String? {
        val id = AssetFetcher.idOf(content) ?: return content
        return try {
            val doc = RbxFile.read(AssetFetcher.load(id))
            doc.roots.flatMap { listOf(it) + it.descendants() }.firstNotNullOfOrNull { (it[prop] ?: it["Texture"])?.toString()?.takeIf { s -> s.isNotBlank() } } ?: content
        } catch (_: Exception) { content }
    }

    private fun fromCustom(o: JSONObject): Spec {
        val colors = HashMap(CLASSIC_COLORS)
        o.optJSONObject("colors")?.let { c -> for ((part, k) in colorKeys) hex(c.optString(k))?.let { colors[part] = it } }
        fun id(k: String) = RobloxApi.parseAssetId(o.optString(k))?.let { "rbxassetid://$it" }
        val acc = ArrayList<ByteArray>()
        val list = o.optString("accessories").split(',', ' ', '\n').mapNotNull { RobloxApi.parseAssetId(it) }
        for (a in list.take(12)) try { acc.add(AssetFetcher.load(a)) } catch (e: Exception) { OutputLog.warn("Avatar accessory $a: ${e.message}") }
        var shirt = id("shirt"); var pants = id("pants"); var tshirt = id("tshirt"); var face = id("face")
        val signedIn = app?.let { !RobloxApi.isGuest(it) } == true
        if (signedIn) { shirt = template(shirt, "ShirtTemplate"); pants = template(pants, "PantsTemplate"); tshirt = template(tshirt, "Graphic"); face = template(face, "Texture") }
        return Spec(colors, shirt, pants, tshirt, face, acc, "Custom")
    }

    // ------------------------------------------------------------------ applying to a rig

    /** Standard R6 attachments (accessories line up with these by name). */
    private val r6Attachments = mapOf(
        "Head" to listOf("HatAttachment" to Vector3(0f, 0.6f, 0f), "HairAttachment" to Vector3(0f, 0.6f, 0f),
            "FaceFrontAttachment" to Vector3(0f, 0f, -0.6f), "FaceCenterAttachment" to Vector3.ZERO),
        "Torso" to listOf("NeckAttachment" to Vector3(0f, 1f, 0f), "BodyFrontAttachment" to Vector3(0f, 0f, -0.5f),
            "BodyBackAttachment" to Vector3(0f, 0f, 0.5f), "LeftCollarAttachment" to Vector3(-1f, 1f, 0f), "RightCollarAttachment" to Vector3(1f, 1f, 0f),
            "WaistFrontAttachment" to Vector3(0f, -1f, -0.5f), "WaistCenterAttachment" to Vector3(0f, -1f, 0f), "WaistBackAttachment" to Vector3(0f, -1f, 0.5f)),
        "Left Arm" to listOf("LeftShoulderAttachment" to Vector3(0f, 1f, 0f), "LeftGripAttachment" to Vector3(0f, -1f, 0f)),
        "Right Arm" to listOf("RightShoulderAttachment" to Vector3(0f, 1f, 0f), "RightGripAttachment" to Vector3(0f, -1f, 0f)),
        "Left Leg" to listOf("LeftFootAttachment" to Vector3(0f, -1f, 0f)),
        "Right Leg" to listOf("RightFootAttachment" to Vector3(0f, -1f, 0f)),
    )

    /**
     * Dresses an R6 character [model] built around its current part positions. Returns the
     * accessory handles that were added (the caller adds them to its rig).
     */
    fun applyTo(model: Instance, spec: Spec): List<Instance> {
        for (name in PARTS) {
            val p = model.findFirstChild(name) ?: continue
            spec.colors[name]?.let { PartData.setColor(p, it) }
        }
        model.findFirstChildOfClass("BodyColors")?.let { bc ->
            spec.colors["Head"]?.let { bc.set("HeadColor3", it, false) }
            spec.colors["Torso"]?.let { bc.set("TorsoColor3", it, false) }
            spec.colors["Left Arm"]?.let { bc.set("LeftArmColor3", it, false) }
            spec.colors["Right Arm"]?.let { bc.set("RightArmColor3", it, false) }
            spec.colors["Left Leg"]?.let { bc.set("LeftLegColor3", it, false) }
            spec.colors["Right Leg"]?.let { bc.set("RightLegColor3", it, false) }
        }
        model.children.filter { it.className == "Shirt" || it.className == "Pants" || it.className == "ShirtGraphic" || it.isA("Accoutrement") }.forEach { it.destroy() }
        spec.shirt?.let { InstanceFactory.create("Shirt").apply { name = "Shirt"; set("ShirtTemplate", it, false) }.setParent(model) }
        spec.pants?.let { InstanceFactory.create("Pants").apply { name = "Pants"; set("PantsTemplate", it, false) }.setParent(model) }
        spec.tshirt?.let { InstanceFactory.create("ShirtGraphic").apply { name = "Shirt Graphic"; set("Graphic", it, false) }.setParent(model) }
        spec.face?.let { f -> model.findFirstChild("Head")?.findFirstChild("face")?.set("Texture", f) }
        applyR6Package(model, spec)
        val dynHead = applyR6DynamicHead(model, spec)
        // Attachments on the body parts.
        for ((partName, list) in r6Attachments) {
            val p = model.findFirstChild(partName) ?: continue
            for ((n, off) in list) if (p.findFirstChild(n) == null) {
                InstanceFactory.create("Attachment").apply { name = n; set("CFrame", CFrame.at(off), false) }.attachRaw(p)
            }
        }
        return addAccessories(model, spec) + listOfNotNull(dynHead)
    }

    /**
     * A dynamic head worn on an R6 character: like Roblox, the avatar's own head (its face is
     * part of it) replaces the classic head and smile. The classic Head part stays (invisible)
     * for joints, attachments and name tags; the dynamic head is welded on top of the neck.
     * Returns the added part, or null when the avatar has no dynamic head (or a classic face).
     */
    private fun applyR6DynamicHead(model: Instance, spec: Spec): Instance? {
        val head = model.findFirstChild("Head") ?: return null
        if (model.children.any { it.name == "DynamicHead" && it.isA("BasePart") }) {
            // Re-dressing: bring the classic head back first.
            model.children.filter { it.name == "DynamicHead" && it.isA("BasePart") }.forEach { it.destroy() }
            head.set("Transparency", 0f, false)
            if (head.children.none { it.className == "Decal" && it.name.equals("face", true) })
                InstanceFactory.create("Decal").apply { name = "face"; set("Texture", spec.face ?: "rbxasset://textures/face.png", false); set("Face", EnumValue(5), false) }.attachRaw(head)
        }
        if (spec.face != null) return null
        val bytes = spec.bodyParts[79] ?: return null
        val src = r15PartsIn(bytes).firstOrNull { it.name == "Head" && it.className == "MeshPart" && PartData.meshContent(it) != null } ?: return null
        val dyn = src.cloneTree()
        dyn.name = "DynamicHead"
        dyn.descendants().filter { it.isA("JointInstance") || it.className == "WeldConstraint" || it.className == "Attachment" }.forEach { it.destroy() }
        if (PartData.transparency(dyn) >= 0.99f) dyn.set("Transparency", 0f, false)
        spec.colors["Head"]?.let { PartData.setColor(dyn, it) }
        // Sit the head on the neck: the R6 neck is half a stud under the classic head's centre.
        val neckY = (src.findFirstChild("NeckRigAttachment")?.get("CFrame") as? CFrame)?.position?.y ?: -0.586f
        val c0 = CFrame(0f, -0.5f - neckY, 0f)
        dyn.set("CFrame", PartData.cframe(head) * c0, false)
        dyn.set("CanCollide", false, false); dyn.set("Massless", true, false); dyn.set("Anchored", true, false)
        InstanceFactory.create("Weld").apply {
            name = "HeadWeld"
            set("Part0", head, false); set("Part1", dyn, false); set("C0", c0, false); set("C1", CFrame.IDENTITY, false)
        }.attachRaw(dyn)
        // The classic head and its smile are hidden (the dynamic head has its own face).
        head.set("Transparency", 1f, false)
        head.children.filter { it.className == "Decal" && it.name.equals("face", true) }.forEach { it.destroy() }
        dyn.attachRaw(model)
        return dyn
    }

    /**
     * R6 body packages (e.g. a Woman or Rthro body worn on an R6 avatar): Roblox puts the body
     * part assets' R6 CharacterMeshes in the character, which replace the blocky limbs, and a
     * classic head asset's SpecialMesh replaces the head shape.
     */
    private fun applyR6Package(model: Instance, spec: Spec) {
        model.children.filter { it.className == "CharacterMesh" }.forEach { it.destroy() }
        for ((type, bytes) in spec.bodyParts) {
            val doc = try { RbxFile.read(bytes) } catch (_: Exception) { continue }
            val all = doc.roots.flatMap { listOf(it) + it.descendants() }
            when (type) {
                27, 28, 29, 30, 31 -> {
                    val r6 = all.firstOrNull { it.className == "Folder" && it.name == "R6" }
                    val meshes = (r6?.descendants() ?: all).filter { it.className == "CharacterMesh" }
                    for (cm in meshes) cm.cloneTree().attachRaw(model)
                }
                17 -> {
                    // A classic head: its SpecialMesh (the asset itself, or inside an R6 folder/Head part).
                    val mesh = all.firstOrNull { it.className == "SpecialMesh" && (it.parent == null || it.parent?.name == "Head" || it.parent?.name == "R6") }
                    val head = model.findFirstChild("Head")
                    if (mesh != null && head != null && AssetFetcher.idOf(mesh["MeshId"]) != null) {
                        head.children.filter { it.className == "SpecialMesh" || it.className == "BlockMesh" }.forEach { it.destroy() }
                        mesh.cloneTree().also { it.name = "Mesh" }.attachRaw(head)
                    }
                }
            }
        }
    }

    /** Puts the avatar's accessories on [model], welded where their attachments match the body's. */
    private fun addAccessories(model: Instance, spec: Spec): List<Instance> {
        val handles = ArrayList<Instance>()
        for (bytes in spec.accessories) {
            val doc = try { RbxFile.read(bytes) } catch (_: Exception) { continue }
            for (root in doc.roots) {
                val acc = if (root.isA("Accoutrement")) root else root.descendants().firstOrNull { it.isA("Accoutrement") } ?: continue
                val handle = acc.findFirstChild("Handle")?.takeIf { it.isA("BasePart") } ?: continue
                val accAtt = handle.children.firstOrNull { it.className == "Attachment" && findBodyAttachment(model, it.name) != null }
                val weld = InstanceFactory.create("Weld"); weld.name = "AccessoryWeld"
                if (accAtt != null) {
                    val bodyAtt = findBodyAttachment(model, accAtt.name)!!
                    val bodyPart = bodyAtt.parent!!
                    val c0 = bodyAtt["CFrame"] as? CFrame ?: CFrame.IDENTITY
                    val c1 = accAtt["CFrame"] as? CFrame ?: CFrame.IDENTITY
                    weld.set("Part0", bodyPart, false); weld.set("Part1", handle, false); weld.set("C0", c0, false); weld.set("C1", c1, false)
                    handle.set("CFrame", PartData.cframe(bodyPart) * c0 * c1.inverse(), false)
                } else {
                    val head = model.findFirstChild("Head") ?: continue
                    val ap = acc["AttachmentPoint"] as? CFrame ?: CFrame.IDENTITY
                    val c0 = CFrame(0f, 0.5f, 0f)
                    weld.set("Part0", head, false); weld.set("Part1", handle, false); weld.set("C0", c0, false); weld.set("C1", ap, false)
                    handle.set("CFrame", PartData.cframe(head) * c0 * ap.inverse(), false)
                }
                handle.set("CanCollide", false, false)
                handle.set("Massless", true, false)
                handle.set("Anchored", true, false)
                weld.attachRaw(handle)
                acc.setParent(model)
                handles.add(handle)
                handles.addAll(acc.descendants().filter { it.isA("BasePart") && it !== handle })
            }
        }
        return handles
    }

    private fun findBodyAttachment(model: Instance, name: String): Instance? {
        for (p in PARTS + R15_PARTS) model.findFirstChild(p)?.findFirstChild(name)?.let { if (it.className == "Attachment") return it }
        return null
    }

    // ------------------------------------------------------------------ R15

    val R15_PARTS = listOf("Head", "UpperTorso", "LowerTorso", "LeftUpperArm", "LeftLowerArm", "LeftHand", "RightUpperArm", "RightLowerArm",
        "RightHand", "LeftUpperLeg", "LeftLowerLeg", "LeftFoot", "RightUpperLeg", "RightLowerLeg", "RightFoot")

    /** Body groups: their parts, body color and the body part asset type that replaces them. */
    private class Group(val parts: List<String>, val colorKey: String, val assetType: Int)
    private val GROUPS = linkedMapOf(
        "Torso" to Group(listOf("UpperTorso", "LowerTorso"), "Torso", 27),
        "RightArm" to Group(listOf("RightUpperArm", "RightLowerArm", "RightHand"), "Right Arm", 28),
        "LeftArm" to Group(listOf("LeftUpperArm", "LeftLowerArm", "LeftHand"), "Left Arm", 29),
        "LeftLeg" to Group(listOf("LeftUpperLeg", "LeftLowerLeg", "LeftFoot"), "Left Leg", 30),
        "RightLeg" to Group(listOf("RightUpperLeg", "RightLowerLeg", "RightFoot"), "Right Leg", 31),
    )
    private fun groupOf(part: String) = GROUPS.entries.firstOrNull { part in it.value.parts }?.key

    /** One default R15 part: size, mesh and attachments (Roblox's current default rounded body). */
    private class Tpl(val name: String, val size: Vector3, val mesh: Long, val atts: List<Pair<String, Vector3>>)
    private fun v(x: Float, y: Float, z: Float) = Vector3(x, y, z)
    private val DEFAULT_R15 = listOf(
        Tpl("LowerTorso", v(2f, 0.4f, 1f), 7430071109, listOf("RootRigAttachment" to v(0f, -0.2f, 0f), "WaistRigAttachment" to v(0f, 0.2f, 0f),
            "LeftHipRigAttachment" to v(-0.5f, -0.2f, 0f), "RightHipRigAttachment" to v(0.5f, -0.2f, 0f), "WaistCenterAttachment" to v(0f, -0.2f, 0f),
            "WaistFrontAttachment" to v(0f, -0.2f, -0.5f), "WaistBackAttachment" to v(0f, -0.2f, 0.5f))),
        Tpl("UpperTorso", v(2f, 1.6f, 1f), 7430071038, listOf("WaistRigAttachment" to v(0f, -0.8f, 0f), "NeckRigAttachment" to v(0f, 0.8f, 0f),
            "LeftShoulderRigAttachment" to v(-1f, 0.563f, 0f), "RightShoulderRigAttachment" to v(1f, 0.563f, 0f), "NeckAttachment" to v(0f, 0.8f, 0f),
            "BodyFrontAttachment" to v(0f, -0.2f, -0.5f), "BodyBackAttachment" to v(0f, -0.2f, 0.5f),
            "LeftCollarAttachment" to v(-1f, 0.8f, 0f), "RightCollarAttachment" to v(1f, 0.8f, 0f))),
        Tpl("LeftUpperArm", v(1f, 1.169f, 1f), 7430071044, listOf("LeftShoulderRigAttachment" to v(0.5f, 0.394f, 0f), "LeftElbowRigAttachment" to v(0f, -0.334f, 0f), "LeftShoulderAttachment" to v(0f, 0.584f, 0f))),
        Tpl("LeftLowerArm", v(1f, 1.052f, 1f), 7430071005, listOf("LeftElbowRigAttachment" to v(0f, 0.259f, 0f), "LeftWristRigAttachment" to v(0f, -0.501f, 0f))),
        Tpl("LeftHand", v(1f, 0.3f, 1f), 7430070991, listOf("LeftWristRigAttachment" to v(0f, 0.125f, 0f), "LeftGripAttachment" to v(0f, -0.15f, 0f))),
        Tpl("RightUpperArm", v(1f, 1.169f, 1f), 7430071041, listOf("RightShoulderRigAttachment" to v(-0.5f, 0.394f, 0f), "RightElbowRigAttachment" to v(0f, -0.334f, 0f), "RightShoulderAttachment" to v(0f, 0.584f, 0f))),
        Tpl("RightLowerArm", v(1f, 1.052f, 1f), 7430071013, listOf("RightElbowRigAttachment" to v(0f, 0.259f, 0f), "RightWristRigAttachment" to v(0f, -0.501f, 0f))),
        Tpl("RightHand", v(1f, 0.3f, 1f), 7430070997, listOf("RightWristRigAttachment" to v(0f, 0.125f, 0f), "RightGripAttachment" to v(0f, -0.15f, 0f))),
        Tpl("LeftUpperLeg", v(1f, 1.217f, 1f), 7430071065, listOf("LeftHipRigAttachment" to v(0f, 0.421f, 0f), "LeftKneeRigAttachment" to v(0f, -0.401f, 0f))),
        Tpl("LeftLowerLeg", v(1f, 1.193f, 1f), 7430071049, listOf("LeftKneeRigAttachment" to v(0f, 0.379f, 0f), "LeftAnkleRigAttachment" to v(0f, -0.547f, 0f))),
        Tpl("LeftFoot", v(1f, 0.3f, 1f), 7430071039, listOf("LeftAnkleRigAttachment" to v(0f, 0.102f, 0f), "LeftFootAttachment" to v(0f, -0.15f, 0f))),
        Tpl("RightUpperLeg", v(1f, 1.217f, 1f), 7430071119, listOf("RightHipRigAttachment" to v(0f, 0.421f, 0f), "RightKneeRigAttachment" to v(0f, -0.401f, 0f))),
        Tpl("RightLowerLeg", v(1f, 1.193f, 1f), 7430071105, listOf("RightKneeRigAttachment" to v(0f, 0.379f, 0f), "RightAnkleRigAttachment" to v(0f, -0.547f, 0f))),
        Tpl("RightFoot", v(1f, 0.3f, 1f), 7430071082, listOf("RightAnkleRigAttachment" to v(0f, 0.102f, 0f), "RightFootAttachment" to v(0f, -0.15f, 0f))),
        Tpl("Head", v(1.196f, 1.203f, 1.198f), 123480233606534, listOf("NeckRigAttachment" to v(0f, -0.586f, 0f), "HatAttachment" to v(0f, 0.6f, 0f),
            "HairAttachment" to v(0f, 0.6f, 0f), "FaceFrontAttachment" to v(0f, 0f, -0.6f), "FaceCenterAttachment" to v(0f, 0f, 0f))),
    )
    private const val DEFAULT_HEAD_TEXTURE = 111092388570647L

    private fun attachment(name: String, pos: Vector3) = InstanceFactory.create("Attachment").apply { this.name = name; set("CFrame", CFrame.at(pos), false) }

    private fun templatePart(t: Tpl): Instance {
        val p = InstanceFactory.create("MeshPart")
        p.name = t.name
        p.set("size", t.size, false)
        p.set("MeshId", "rbxassetid://${t.mesh}", false)
        p.set("TextureID", if (t.name == "Head") "rbxassetid://$DEFAULT_HEAD_TEXTURE" else "", false)
        for ((n, pos) in t.atts) attachment(n, pos).attachRaw(p)
        return p
    }

    /** The R15 body parts in a body part / head asset (its "R15ArtistIntent" version when it has one). */
    private fun r15PartsIn(bytes: ByteArray): List<Instance> {
        val doc = try { RbxFile.read(bytes) } catch (_: Exception) { return emptyList() }
        val all = doc.roots.flatMap { listOf(it) + it.descendants() }
        for (folder in listOf("R15ArtistIntent", "R15Fixed", "R15")) {
            val f = all.firstOrNull { it.className == "Folder" && it.name == folder } ?: continue
            val parts = f.children.filter { it.isA("BasePart") && it.name in R15_PARTS }
            if (parts.isNotEmpty()) return parts
        }
        return all.filter { it.isA("BasePart") && it.name in R15_PARTS && it.parent?.name != "R6" }
    }

    private fun bitmapOf(content: String?): android.graphics.Bitmap? = AssetFetcher.idOf(content)?.let { AssetFetcher.imageBlocking(it) }

    /**
     * Classic clothing on an R15 body, the way Roblox does it: the Shirt/Pants template is first
     * composited into one texture per body group (see [ClothingCompositor]; the R15 meshes' UVs
     * point into that layout, not into the raw template), over the body color and the body part's
     * own texture, with the T-shirt on the chest.
     */
    fun composeClothing(colors: Map<String, Color3>, shirt: String?, pants: String?, tshirt: String?, bodyParts: Map<Int, ByteArray>): Map<String, android.graphics.Bitmap> =
        composeClothingBitmaps(colors, bitmapOf(shirt), bitmapOf(pants), bitmapOf(tshirt)) { g ->
            bodyParts[g]?.let { b -> r15PartsIn(b).firstNotNullOfOrNull { p -> PartData.textureContent(p) } }?.let { bitmapOf(it) }
        }

    /** [composeClothing] from already-loaded images; [ownTexture] gives a body part asset type's own texture. */
    fun composeClothingBitmaps(colors: Map<String, Color3>, shirtB: android.graphics.Bitmap?, pantsB: android.graphics.Bitmap?, teeB: android.graphics.Bitmap?,
                               ownTexture: (Int) -> android.graphics.Bitmap? = { null }): Map<String, android.graphics.Bitmap> {
        if (shirtB == null && pantsB == null && teeB == null) return emptyMap()
        val out = HashMap<String, android.graphics.Bitmap>()
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val cc = ClothingCompositor
        for ((name, g) in GROUPS) {
            val torso = name == "Torso"
            val layers = when (name) { "Torso" -> listOfNotNull(pantsB, shirtB); "LeftArm", "RightArm" -> listOfNotNull(shirtB); else -> listOfNotNull(pantsB) }
            val tee = if (torso) teeB else null
            if (layers.isEmpty() && tee == null) continue
            val w = if (torso) cc.TORSO_W else cc.LIMB_W
            val h = if (torso) cc.TORSO_H else cc.LIMB_H
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val col = colors[g.colorKey] ?: Color3.fromRGB(163, 162, 165)
            c.drawColor(android.graphics.Color.rgb((col.r * 255).toInt().coerceIn(0, 255), (col.g * 255).toInt().coerceIn(0, 255), (col.b * 255).toInt().coerceIn(0, 255)))
            // The body part's own texture shares the mesh UVs, so it covers the whole composite.
            ownTexture(g.assetType)?.let { c.drawBitmap(it, null, android.graphics.Rect(0, 0, w, h), paint) }
            val limb = if (name == "LeftArm" || name == "LeftLeg") cc.LEFT else cc.RIGHT
            for (l in layers) if (torso) cc.drawTorso(c, l) else cc.drawLimb(c, l, limb)
            // The T-shirt covers the front of the torso.
            tee?.let { c.drawBitmap(it, null, android.graphics.Rect(2, 74, 130, 202), paint) }
            out[name] = bmp
        }
        return out
    }

    /** Body group ("Torso", "LeftArm"...) an R15 part belongs to, or null (head, root). */
    fun r15GroupOf(part: String): String? = groupOf(part)

    /** Body-color key ("Torso", "Left Arm"...) of an R15 body group. */
    fun r15ColorKey(group: String): String? = GROUPS[group]?.colorKey

    private var texSerial = 0

    /**
     * Builds an R15 character the way Roblox assembles one: worn body parts and head (defaults for
     * the rest), scaled, joined with Motor6Ds through matching rig attachments, colored, clothed,
     * with accessories. The HumanoidRootPart sits at the origin.
     */
    fun buildR15(name: String, spec: Spec): Instance {
        val m = InstanceFactory.create("Model")
        m.name = name
        val root = InstanceFactory.create("Part").apply {
            this.name = "HumanoidRootPart"
            set("size", Vector3(2f, 2f, 1f), false); set("Transparency", 1f, false); set("CanCollide", false, false)
        }
        attachment("RootRigAttachment", Vector3(0f, -1f, 0f)).attachRaw(root)
        attachment("RootAttachment", Vector3.ZERO).attachRaw(root)
        root.attachRaw(m)

        val parts = LinkedHashMap<String, Instance>()
        for (t in DEFAULT_R15) parts[t.name] = templatePart(t)
        // Worn body parts replace the defaults.
        for ((type, bytes) in spec.bodyParts) {
            if (type == 17 || type == 79) continue
            for (src in r15PartsIn(bytes)) if (src.name != "Head") parts[src.name] = src.cloneTree()
        }
        // A worn head (dynamic head first, then a classic head asset). A MeshPart head whose mesh
        // can't be found would draw as nothing, so the default head stays in that case.
        fun headIn(b: ByteArray?) = b?.let { r15PartsIn(it).firstOrNull { p -> p.name == "Head" && (p.className != "MeshPart" || PartData.meshContent(p) != null) } }
        val dynamicHead = headIn(spec.bodyParts[79])
        val headSrc = dynamicHead ?: headIn(spec.bodyParts[17])
        if (headSrc == null && (spec.bodyParts[79] != null || spec.bodyParts[17] != null)) OutputLog.warn("Avatar: the worn head has no mesh this app can read; using the default head")
        if (headSrc != null) parts["Head"] = headSrc.cloneTree().also { h ->
            // Some head assets ship the part hidden (the look comes from layers Roblox adds).
            if (PartData.transparency(h) >= 0.99f) h.set("Transparency", 0f, false)
            // A classic head shows the avatar's face (or Roblox's default smile) as a "face"
            // decal, like Roblox; a dynamic head's face is part of its own texture.
            if (dynamicHead == null) {
                val face = h.children.firstOrNull { it.className == "Decal" && it.name.equals("face", true) }
                    ?: InstanceFactory.create("Decal").also { d -> d.name = "face"; d.set("Face", EnumValue(5), false); d.attachRaw(h) }
                face.set("Texture", spec.face ?: (face["Texture"]?.toString()?.takeIf { it.isNotBlank() } ?: "rbxasset://textures/face.png"), false)
            } else h.children.filter { it.className == "Decal" && it.name.equals("face", true) }.forEach { it.destroy() }
        }
        else if (spec.face != null) {
            // A classic face goes on the classic head.
            parts["Head"] = InstanceFactory.create("Part").apply {
                this.name = "Head"
                set("size", Vector3(2f, 1f, 1f), false)
                InstanceFactory.create("SpecialMesh").apply { this.name = "Mesh"; set("MeshType", EnumValue(0), false); set("Scale", Vector3(1.25f, 1.25f, 1.25f), false) }.attachRaw(this)
                InstanceFactory.create("Decal").apply { this.name = "face"; set("Texture", spec.face, false); set("Face", EnumValue(5), false) }.attachRaw(this)
                for ((n, pos) in listOf("NeckRigAttachment" to v(0f, -0.5f, 0f), "HatAttachment" to v(0f, 0.6f, 0f), "HairAttachment" to v(0f, 0.6f, 0f),
                        "FaceFrontAttachment" to v(0f, 0f, -0.6f), "FaceCenterAttachment" to v(0f, 0f, 0f))) attachment(n, pos).attachRaw(this)
            }
        }

        val serial = ++texSerial
        val s = spec.scales
        for ((n, p) in parts) {
            // Joints are rebuilt below; drop any that came with the asset.
            p.descendants().filter { it.isA("JointInstance") || it.className == "WeldConstraint" }.forEach { it.destroy() }
            p.set("Anchored", false, false); p.set("CanCollide", n == "UpperTorso" || n == "LowerTorso" || n == "Head", false)
            p.set("Massless", false, false)
            val colorKey = if (n == "Head") "Head" else GROUPS[groupOf(n)]?.colorKey
            colorKey?.let { k -> spec.colors[k]?.let { PartData.setColor(p, it) } }
            groupOf(n)?.let { g -> spec.clothing[g]?.let { bmp ->
                val key = "$TEX_PREFIX$serial/$g"
                generated[key] = bmp
                p.set("TextureID", key, false)
            } }
            // Body scales (head scale for the head).
            val k = if (n == "Head") Vector3(s.head, s.head, s.head) else Vector3(s.width, s.height, s.depth)
            if (k != Vector3.ONE) {
                PartData.setSize(p, PartData.size(p) * k)
                for (a in p.children) if (a.className == "Attachment") {
                    val cf = a["CFrame"] as? CFrame ?: CFrame.IDENTITY
                    a.set("CFrame", cf.rotationOnly().withPosition(cf.position * k), false)
                }
            }
            p.attachRaw(m)
        }

        // Join the parts like Humanoid:BuildRigFromAttachments, posing them from the root outwards.
        root.set("CFrame", CFrame.IDENTITY, false)
        val all = listOf(root) + parts.values
        val placed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>())
        placed.add(root)
        val queue = ArrayDeque<Instance>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val p0 = queue.removeFirst()
            for (a0 in p0.children.filter { it.className == "Attachment" && it.name.endsWith("RigAttachment") }) {
                for (p1 in all) {
                    if (p1 === p0 || p1 in placed) continue
                    val a1 = p1.children.firstOrNull { it.className == "Attachment" && it.name == a0.name } ?: continue
                    val c0 = a0["CFrame"] as? CFrame ?: CFrame.IDENTITY
                    val c1 = a1["CFrame"] as? CFrame ?: CFrame.IDENTITY
                    InstanceFactory.create("Motor6D").apply {
                        this.name = a0.name.removeSuffix("RigAttachment")
                        set("Part0", p0, false); set("Part1", p1, false); set("C0", c0, false); set("C1", c1, false)
                        set("MaxVelocity", 0.1f, false)
                    }.attachRaw(p1)
                    p1.set("CFrame", PartData.cframe(p0) * c0 * c1.inverse(), false)
                    placed.add(p1); queue.add(p1)
                }
            }
        }
        // A part the walk didn't reach (an asset with a missing or misnamed rig attachment, seen
        // with some heads) would stay wherever it was in its file, often far off or inside the
        // body. Join it with the default rig's attachment positions instead.
        for (t in DEFAULT_R15) {
            val p1 = parts[t.name] ?: continue
            if (p1 in placed) continue
            val rigAtt = t.atts.firstOrNull { it.first.endsWith("RigAttachment") } ?: continue
            val parentTpl = DEFAULT_R15.firstOrNull { o -> o !== t && o.atts.any { it.first == rigAtt.first } }
            val p0 = if (parentTpl == null) root else parts[parentTpl.name]?.takeIf { it in placed } ?: continue
            val a0 = p0.children.firstOrNull { it.className == "Attachment" && it.name == rigAtt.first }
            val c0 = a0?.get("CFrame") as? CFrame ?: CFrame.at(parentTpl?.atts?.first { it.first == rigAtt.first }?.second ?: Vector3(0f, -1f, 0f))
            val a1 = p1.children.firstOrNull { it.className == "Attachment" && it.name == rigAtt.first }
            val c1 = a1?.get("CFrame") as? CFrame ?: CFrame.at(rigAtt.second * Vector3(1f, PartData.size(p1).y / t.size.y, 1f))
            InstanceFactory.create("Motor6D").apply {
                this.name = rigAtt.first.removeSuffix("RigAttachment")
                set("Part0", p0, false); set("Part1", p1, false); set("C0", c0, false); set("C1", c1, false)
                set("MaxVelocity", 0.1f, false)
            }.attachRaw(p1)
            p1.set("CFrame", PartData.cframe(p0) * c0 * c1.inverse(), false)
            placed.add(p1)
        }

        var minY = Float.MAX_VALUE
        for (p in parts.values) if (p in placed) minY = minOf(minY, PartData.cframe(p).y - PartData.size(p).y / 2f)
        val hum = InstanceFactory.create("Humanoid")
        hum.set("RigType", EnumValue(1), false)
        hum.set("HipHeight", if (minY < Float.MAX_VALUE) (-1f - minY).coerceAtLeast(0f) else 2f, false)
        hum.attachRaw(m)
        InstanceFactory.create("Animator").attachRaw(hum)
        val bc = InstanceFactory.create("BodyColors")
        bc.name = "Body Colors"
        for ((k, prop) in listOf("Head" to "HeadColor3", "Torso" to "TorsoColor3", "Left Arm" to "LeftArmColor3", "Right Arm" to "RightArmColor3",
                "Left Leg" to "LeftLegColor3", "Right Leg" to "RightLegColor3")) spec.colors[k]?.let { bc.set(prop, it, false) }
        bc.attachRaw(m)
        // Clothing objects for scripts (their look is already in the body textures).
        spec.shirt?.let { InstanceFactory.create("Shirt").apply { this.name = "Shirt"; set("ShirtTemplate", it, false) }.attachRaw(m) }
        spec.pants?.let { InstanceFactory.create("Pants").apply { this.name = "Pants"; set("PantsTemplate", it, false) }.attachRaw(m) }
        addAccessories(m, spec)
        return m
    }
}
