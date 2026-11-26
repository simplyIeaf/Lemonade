package com.leaf.lemonade.engine

import com.leaf.lemonade.core.CollisionGroupTable
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.lua.EnumItemV
import com.leaf.lemonade.lua.LuaRuntime
import com.leaf.lemonade.lua.LuaTypes
import com.leaf.lemonade.lua.fn2
import com.leaf.lemonade.lua.fnv
import com.leaf.lemonade.model.*
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class PlaySession(val studio: Studio, val dm: DataModel) : TreeListener {
    val rt = LuaRuntime(studio, dm, true).also { it.play = this }
    var player: Instance? = null
    var character: Instance? = null
    private var hrp: Instance? = null
    private var humanoid: Instance? = null

    // character state
    private var pos = Vector3(0f, 10f, 0f)
    private var vel = Vector3.ZERO
    private var grounded = false
    private var facing = 0f
    private var lastDir = Vector3.ZERO
    private var lastWritten: CFrame? = null
    private var charMinY = -3f
    private var charMaxY = 2f
    private var charHalfXZ = 1f
    /**
     * Collision box half width/depth: the HumanoidRootPart's footprint (2x1 for R6 and R15),
     * turned with the character like Roblox's torso collision, instead of a square.
     */
    private var colHalfW = 1f
    private var colHalfD = 0.5f
    /** Yaw the collision box had last frame, and when the last wallhop chance opened. */
    private var colYaw = 0f
    private var wallTouch = false
    private var wallHopAt = -10.0
    /** In terrain water (Humanoid state Swimming), and when the character was last in it. */
    private var swimming = false
    private var lastWaterTime = -10.0
    private val offsets = IdentityHashMap<Instance, CFrame>()
    private var dead = false
    private var deadTimer = 0f
    private var jumpRequested = false
    private var moveToTarget: Vector3? = null
    private var scriptMoveDir: Vector3? = null
    private var time = 0.0
    /** Scripts can turn player movement off through the PlayerModule's controls. */
    var controlsEnabled = true

    // ------------------------------------------------------------------ camera (like the PlayerModule's camera)
    var camYaw = 0f
    var camPitch = -0.35f
    var camDist = 14f
    private var yawTarget = 0f
    private var pitchTarget = -0.35f
    private var distTarget = 14f
    private var occluded = 1000f
    private var subjectY = Float.NaN
    private var lastCamInput = -10.0
    /** Arrow-key turning (-1..1). */
    @Volatile var keyTurn = 0f
    var shiftLock = false
        private set
    var firstPerson = false
        private set

    // ------------------------------------------------------------------ input & UI
    @Volatile var lastTouchDp: Vector2? = null
    class ActionButton(val name: String, val fn: LuaValue, var title: String)
    val actions = LinkedHashMap<String, ActionButton>()
    @Volatile var activePrompt: Instance? = null
    private var prompts = ArrayList<Instance>()
    var equipped: Instance? = null
        private set
    private val toolOffsets = IdentityHashMap<Instance, CFrame>()
    private var mouseTable: LuaTable? = null

    // ------------------------------------------------------------------ chat & core GUI
    class ChatLine(val from: String?, val text: String, val color: Int, val system: Boolean, val time: Long = System.currentTimeMillis())
    class Bubble(val part: Instance, val text: String, var age: Float = 0f)
    val chatLines = ArrayList<ChatLine>()
    val bubbles = ArrayList<Bubble>()
    @Volatile var chatVersion = 0
    /** StarterGui:SetCoreGuiEnabled flags. */
    var coreChat = true
    var corePlayerList = true
    var coreBackpack = true
    var coreHealth = true

    // physics
    private class Body(val parts: List<Instance>, var vel: Vector3) {
        var sleeping = false; var restTime = 0f
        /** Roblox mass units (volume x material density). */
        var mass = 1f
        /** Average friction / elasticity of its parts' materials (Roblox's defaults). */
        var friction = 0.3f; var elasticity = 0.5f
        /** World bounds of its colliding parts as of its last step. */
        var min = Vector3.ZERO; var max = Vector3.ZERO; var hasBox = false
    }
    /** Constraints and body movers in Workspace (ropes, springs, BodyVelocity, LinearVelocity...). */
    private val movers = ArrayList<Instance>()
    private val bodies = ArrayList<Body>()
    private val bodyOf = IdentityHashMap<Instance, Body>()
    private val statics = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
    private val grid = HashMap<Long, ArrayList<Instance>>()
    private val cellsOf = IdentityHashMap<Instance, LongArray>()
    private val bigParts = ArrayList<Instance>()
    private var physicsDirty = true
    private var writing = false
    private val touching = IdentityHashMap<Instance, MutableSet<Instance>>()
    private val startedScripts = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
    private val CELL = 32f

    /** Constraints that join their parts into one assembly here (their rotation isn't simulated). */
    private val HOLD_TOGETHER = setOf("RigidConstraint", "HingeConstraint", "BallSocketConstraint", "CylindricalConstraint",
        "PrismaticConstraint", "UniversalConstraint", "TorsionSpringConstraint", "PlaneConstraint", "SpringConstraint")

    private val MOVER_CLASSES = setOf("BodyForce", "BodyThrust", "BodyVelocity", "BodyPosition", "RocketPropulsion", "VectorForce",
        "LineForce", "LinearVelocity", "AlignPosition", "SpringConstraint", "RopeConstraint", "RodConstraint")

    private val runService get() = dm.getService("RunService").also { it.archivable = false }
    private val gravity get() = (dm.workspace["Gravity"] as? Number)?.toFloat() ?: 196.2f

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        CollisionGroupTable.shared.reset()
        dm.listeners.add(this)
        OutputLog.info("Playtest started")
        installBridge()
        setupTextChat()
        // Server scripts first
        for (s in dm.descendants()) if (s.className == "Script") maybeRunScript(s)
        val players = dm.getService("Players")
        val p = InstanceFactory.create("Player")
        // The signed-in Roblox account plays (like Studio's playtest); guests are Player1.
        val ctx = studio.app
        val signedIn = !com.leaf.lemonade.net.RobloxApi.isGuest(ctx) && com.leaf.lemonade.net.RobloxApi.userId(ctx) != 0L
        val uname = if (signedIn) com.leaf.lemonade.net.RobloxApi.userName(ctx).ifEmpty { "Player1" } else "Player1"
        p.name = uname
        p.set("UserId", if (signedIn) com.leaf.lemonade.net.RobloxApi.userId(ctx) else 1L, false); p.propTypes["UserId"] = PropType.INT64
        p.set("DisplayName", if (signedIn) com.leaf.lemonade.net.RobloxApi.displayName(ctx).ifEmpty { uname } else uname, false)
        p.set("Character", null, false)
        p.set("Team", null, false)
        p.set("Neutral", true, false)
        p.set("RespawnLocation", null, false)
        // Like Roblox, a player joins an AutoAssignable team when the game has teams.
        dm.findService("Teams")?.children?.firstOrNull { it.className == "Team" && it["AutoAssignable"] != false }?.let { t ->
            p.set("Team", t, false); p.set("Neutral", false, false)
            (t["TeamColor"] as? BrickColorV)?.let { p.set("TeamColor", it, false) }
        }
        p.set("AccountAge", 365, false)
        val sp = dm.findService("StarterPlayer")
        p.set("CameraMaxZoomDistance", (sp?.get("CameraMaxZoomDistance") as? Number)?.toFloat() ?: 128f, false)
        p.set("CameraMinZoomDistance", (sp?.get("CameraMinZoomDistance") as? Number)?.toFloat() ?: 0.5f, false)
        p.set("CameraMode", sp?.get("CameraMode") as? EnumValue ?: EnumValue(0), false)
        InstanceFactory.create("Backpack").also { it.name = "Backpack" }.attachRaw(p)
        InstanceFactory.create("PlayerGui").also { it.name = "PlayerGui" }.attachRaw(p)
        val ps = InstanceFactory.create("PlayerScripts").also { it.name = "PlayerScripts" }
        ps.attachRaw(p)
        addPlayerModule(ps)
        player = p
        p.setParent(players)
        rt.fire(players, "PlayerAdded", rt.wrap(p))

        sp?.findFirstChild("StarterPlayerScripts")?.children?.forEach { c ->
            if (c.name != "PlayerModule") c.cloneTree().setParent(p.findFirstChild("PlayerScripts"))
        }
        resetGui(initial = true)
        dm.findService("StarterPack")?.children?.forEach { c -> c.cloneTree().setParent(p.findFirstChild("Backpack")) }
        dm.findService("ReplicatedFirst")?.descendants()?.forEach { maybeRunScript(it) }
        spawnCharacter()
        systemMessage("Chat '/?' or '/help' for a list of chat commands.", 0xFFB4B4B4.toInt())
    }

    fun stop() {
        dm.listeners.remove(this)
        rt.shutdown()
        OutputLog.info("Playtest stopped")
    }

    private fun resetGui(initial: Boolean) {
        val pg = player?.findFirstChild("PlayerGui") ?: return
        if (!initial) for (g in pg.children.toList()) if (g["ResetOnSpawn"] != false) g.destroy()
        dm.findService("StarterGui")?.children?.forEach { c ->
            if (initial || c["ResetOnSpawn"] != false) c.cloneTree().setParent(pg)
        }
    }

    /** Functions the PlayerModule stub calls into. */
    private fun installBridge() {
        val b = LuaTable()
        b.set("setControlsEnabled", fnv { a -> controlsEnabled = a.arg1().toboolean(); if (!controlsEnabled) { lastDir = Vector3.ZERO }; LuaValue.NONE })
        b.set("getMoveVector", fnv { _ -> LuaTypes.wrap(if (controlsEnabled) Vector3(studio.moveX, 0f, studio.moveY) else Vector3.ZERO) })
        b.set("setShiftLock", fnv { a -> shiftLock = a.arg1().toboolean(); LuaValue.NONE })
        b.set("isShiftLock", fnv { _ -> LuaValue.valueOf(shiftLock) })
        rt.globals.set("__MobileStudioPlayer", b)
    }

    /**
     * Roblox puts a PlayerModule in PlayerScripts; many games require it to disable controls.
     * Movement and camera are handled natively here, so this stub just exposes the usual API.
     */
    private fun addPlayerModule(ps: Instance) {
        fun module(name: String, src: String, parent: Instance): Instance {
            val m = InstanceFactory.create("ModuleScript"); m.name = name; m.set("Source", src.trimIndent() + "\n", false); m.attachRaw(parent); return m
        }
        val pm = module("PlayerModule", """
            local bridge = __MobileStudioPlayer
            local Controls = {}
            Controls.__index = Controls
            function Controls:Enable(enable) bridge.setControlsEnabled(enable ~= false) end
            function Controls:Disable() bridge.setControlsEnabled(false) end
            function Controls:GetMoveVector() return bridge.getMoveVector() end
            function Controls:GetActiveController() return self end
            function Controls:SelectTouchModule() end
            function Controls:GetIsJumping() return false end
            local controls = setmetatable({}, Controls)
            local Cameras = {}
            Cameras.__index = Cameras
            function Cameras:GetCameraControlChoice() return nil end
            function Cameras:SetIsMouseLocked(on) bridge.setShiftLock(on) end
            function Cameras:GetIsMouseLocked() return bridge.isShiftLock() end
            local cameras = setmetatable({}, Cameras)
            local PlayerModule = {}
            function PlayerModule:GetControls() return controls end
            function PlayerModule:GetCameras() return cameras end
            function PlayerModule:GetClickToMoveController() return controls end
            return PlayerModule
        """, ps)
        module("ControlModule", "local pm = require(script.Parent)\nreturn pm:GetControls()", pm)
        module("CameraModule", "local pm = require(script.Parent)\nreturn pm:GetCameras()", pm)
    }

    /** TextChatService's default channels, so scripts can post system messages. */
    private fun setupTextChat() {
        val tcs = dm.getService("TextChatService")
        val folder = tcs.findFirstChild("TextChannels") ?: InstanceFactory.create("Folder").also { it.name = "TextChannels"; it.archivable = false; it.attachRaw(tcs) }
        for (n in listOf("RBXGeneral", "RBXSystem")) if (folder.findFirstChild(n) == null) {
            InstanceFactory.create("TextChannel").also { it.name = n; it.archivable = false; it.attachRaw(folder) }
        }
    }

    // ------------------------------------------------------------------ scripts

    private fun isRunnable(s: Instance): Boolean {
        if (s["Disabled"] == true) return false
        val ctx = (s["RunContext"] as? EnumValue)?.value ?: 0
        val p = player
        fun under(cls: String) = generateSequence(s.parent) { it.parent }.any { it.className == cls }
        fun underInst(i: Instance?) = i != null && s.isDescendantOf(i)
        val clientPlaces = underInst(p) || underInst(character) || under("ReplicatedFirst")
        // Server scripts also run inside a player's Backpack (tools like Building Tools by F3X
        // keep their server half there).
        val inBackpack = p != null && generateSequence(s.parent) { it.parent }.any { it.className == "Backpack" && it.parent === p }
        return when (s.className) {
            "Script" -> when (ctx) {
                2 -> clientPlaces || under("Workspace") || under("ReplicatedStorage")
                1 -> under("Workspace") || under("ServerScriptService") || under("ReplicatedStorage") || inBackpack
                0 -> under("Workspace") || under("ServerScriptService") || inBackpack
                else -> false
            }
            "LocalScript" -> clientPlaces
            else -> false
        }
    }

    fun maybeRunScript(s: Instance) {
        if (rt.stopped || s in startedScripts || !s.isA("BaseScript")) return
        if (!isRunnable(s)) return
        startedScripts.add(s)
        rt.runScript(s)
    }

    fun onChildAdded(parent: Instance, child: Instance) {
        maybeRunScript(child)
        for (d in child.descendants()) maybeRunScript(d)
    }

    fun onChildRemoved(parent: Instance, child: Instance) {}

    // TreeListener (physics bookkeeping)
    /**
     * Only changes to parts, joints and movers in Workspace matter to physics, and anchored
     * parts are added/removed in place. Rebuilding everything for every new GUI label or effect
     * made big maps crawl in playtests.
     */
    private fun physicsRelevant(i: Instance): Boolean {
        if (i.isA("BasePart") || i.isA("JointInstance") || i.className == "WeldConstraint" || i.isA("Constraint") || i.isA("BodyMover") || i.className == "Attachment" || i.isA("Humanoid")) return true
        for (c in i.children) if (physicsRelevant(c)) return true
        return false
    }

    private fun inWorkspace(i: Instance?): Boolean { val ws = dm.workspace; return i != null && (i === ws || i.isDescendantOf(ws)) }

    override fun childAdded(parent: Instance, child: Instance) {
        if (physicsDirty || !inWorkspace(parent) || !physicsRelevant(child)) return
        val sub = listOf(child) + child.descendants()
        if (sub.any { it.isA("JointInstance") || it.className == "WeldConstraint" || it.isA("Constraint") || it.isA("BodyMover") || it.isA("Humanoid") }) { physicsDirty = true; return }
        val parts = sub.filter { it.isA("BasePart") && it.className != "Terrain" }
        // A new anchored part (map pieces, effects): just put it in the collision grid.
        if (parts.all { it["Anchored"] == true && !isCharacterPart(it) }) { for (p in parts) if (statics.add(p)) insertGrid(p); return }
        // A loose unanchored part (projectiles, dropped bricks) not inside a model: its own body.
        val inModel = generateSequence(child.parent) { it.parent }.takeWhile { it !== dm.workspace }.any { it.isA("Model") }
        if (child.isA("BasePart") && child.children.none { it.isA("BasePart") } && !inModel && child["Anchored"] != true && !isCharacterPart(child)) {
            val b = Body(listOf(child), Vector3.ZERO)
            measure(b)
            bodies.add(b); bodyOf[child] = b
            return
        }
        physicsDirty = true
    }

    override fun childRemoved(parent: Instance, child: Instance) {
        if (physicsDirty || !inWorkspace(parent) || !physicsRelevant(child)) return
        val sub = listOf(child) + child.descendants()
        val gone = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>()).also { it.addAll(sub) }
        for (p in sub) {
            if (p in statics) { statics.remove(p); removeGrid(p); continue }
            val b = bodyOf[p]
            if (b != null) {
                // A whole assembly removed (fallen off the map, destroyed by a script): drop it.
                if (b.parts.all { it in gone || it.parent == null }) { bodies.remove(b); for (q in b.parts) bodyOf.remove(q); continue }
                physicsDirty = true; return
            }
            if (p.isA("JointInstance") || p.className == "WeldConstraint") { physicsDirty = true; return }
            if (p.isA("Constraint") || p.isA("BodyMover")) movers.remove(p)
        }
    }
    override fun propChanged(inst: Instance, name: String) {
        if (writing) return
        when (name) {
            // CanCollide/CanTouch are read live; only anchoring changes what moves.
            "Anchored" -> {
                val b = bodyOf[inst]
                if (inst["Anchored"] == true && b != null && b.parts.size == 1) {
                    // A loose part got anchored (common in games): it just stops moving.
                    bodies.remove(b); bodyOf.remove(inst)
                    if (statics.add(inst)) insertGrid(inst)
                } else physicsDirty = true
            }
            "CFrame", "size", "Size" -> {
                if (inst in statics) updateGrid(inst)
                bodyOf[inst]?.let { it.sleeping = false; it.restTime = 0f }
            }
        }
    }

    // ------------------------------------------------------------------ character

    /** A joint between two character parts: Motor6D/Weld (C0/C1) or a WeldConstraint (fixed offset). */
    private class Joint(val inst: Instance, val p0: Instance, val p1: Instance, val motor: Boolean, val fixed: CFrame?) {
        val name: String get() = inst.name
    }
    private val jointOrder = ArrayList<Pair<Joint, Boolean>>()
    private val rigid = IdentityHashMap<Instance, CFrame>()
    private var isR15 = false
    /** Avatar version on the current character (-1 = none yet), and whether the rig takes avatars. */
    private var avatarApplied = -1
    private var dressable = false

    /** The avatar finished loading after the character spawned: dress it now. */
    private fun lateAvatar() {
        if (!dressable || dead) return
        val spec = Avatar.current ?: return
        if (avatarApplied == Avatar.version) return
        val model = character ?: return
        val root = hrp ?: return
        // A saved playtest character arrived after spawning: become it.
        if (spec.character != null) { spawnCharacter(); return }
        // An R15 avatar (or switching back from one) needs a different rig: build the character again.
        if (wantR15(spec) || isR15) { spawnCharacter(); return }
        avatarApplied = Avatar.version
        writing = true
        try {
            val added = Avatar.applyTo(model, spec)
            val rootCf = PartData.cframe(root)
            for (h in added) offsets[h] = rootCf.inverse() * PartData.cframe(h)
            buildJoints(model, root)
        } finally { writing = false }
        physicsDirty = true
        player?.let { rt.fire(it, "CharacterAppearanceLoaded", rt.wrap(model)) }
    }

    fun spawnCharacter() {
        val p = player ?: return
        character?.let { old ->
            rt.fire(p, "CharacterRemoving", rt.wrap(old))
            if (equipped?.isDescendantOf(old) == true) equipped = null
            old.destroy()
        }
        leaveSeat(false)
        climbing = false
        // File → Playtest Avatar → a saved character: the player spawns as it, in every place
        // (it wins over the game's StarterCharacter).
        if (playAsTemplate == null) Avatar.current?.character?.let { loadCharacterTemplate(it) }
        // A Workspace model tagged by Studio.playAs (debug builds' --es playas) becomes the character.
        if (playAsTemplate == null) dm.workspace.descendants().firstOrNull { it.attributes[com.leaf.lemonade.core.Studio.PLAY_AS_TAG] == true }?.let { makePlayAs(it) }
        val starter = playAsTemplate ?: dm.findService("StarterPlayer")?.findFirstChild("StarterCharacter")
        val custom = starter != null && starter.findFirstChild("HumanoidRootPart") != null && starter.findFirstChildOfClass("Humanoid") != null
        // Put the player's avatar on the default rig (games with a StarterCharacter keep theirs).
        avatarApplied = -1
        dressable = !custom && dm.findService("StarterPlayer")?.get("LoadCharacterAppearance") != false
        val avatar = Avatar.current?.takeIf { it.character == null }
        // Game Settings → Avatar decides the rig (R6, R15, or the player's own choice), like Roblox.
        val r15 = wantR15(avatar)
        val spec = if (dressable && avatar != null) {
            if (gameAvatarType() == AVATAR_PLAYER_CHOICE) avatar else avatar.forRig(r15, clampScales(avatar.scales))
        } else null
        val model: Instance = when {
            custom -> starter!!.cloneTree().also { it.name = p.name }
            r15 -> Avatar.buildR15(p.name, spec ?: Avatar.Spec(Avatar.CLASSIC_COLORS, null, null, null, null, emptyList(), "Default", r15 = true))
            else -> buildR6(p.name)
        }
        if (spec != null) {
            if (!r15) Avatar.applyTo(model, spec)
            avatarApplied = Avatar.version
        } else if (dressable && Avatar.current?.character != null) avatarApplied = Avatar.version
        val root = model.findFirstChild("HumanoidRootPart")!!
        val hum = model.findFirstChildOfClass("Humanoid")!!
        model.set("PrimaryPart", root, false)
        isR15 = (hum["RigType"] as? EnumValue)?.value == 1 || model.findFirstChild("UpperTorso") != null

        // offsets relative to root (rest pose)
        offsets.clear()
        val rootCf = PartData.cframe(root)
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var half = 0.5f
        for (part in model.descendants()) if (part.isA("BasePart")) {
            val off = rootCf.inverse() * PartData.cframe(part)
            offsets[part] = off
            part.set("Anchored", true, false)
            val s = PartData.size(part)
            val ext = Geometry.worldAabb(off, s)
            minY = minOf(minY, ext.first.y); maxY = maxOf(maxY, ext.second.y)
            half = maxOf(half, maxOf(-ext.first.x, ext.second.x, -ext.first.z, ext.second.z).coerceAtMost(2.5f))
        }
        charMinY = minY; charMaxY = maxY; charHalfXZ = half.coerceAtMost(1.2f)
        PartData.size(root).let { rs -> colHalfW = (rs.x / 2f).coerceIn(0.4f, 2.5f); colHalfD = (rs.z / 2f).coerceIn(0.3f, 2.5f) }
        buildJoints(model, root)

        val spawn = pickSpawn(p)
        val here = studio.playHereAt
        if (here != null) {
            // Test → Play Here: start where the camera is looking, facing the same way.
            studio.playHereAt = null
            pos = here + Vector3(0f, -charMinY + 0.1f, 0f)
            val f = studio.camera.forward
            facing = playAsYaw?.also { playAsYaw = null } ?: atan2(-f.x, -f.z)
        } else {
            pos = if (spawn != null) {
                val cf = PartData.cframe(spawn); val s = PartData.size(spawn)
                cf.position + Vector3(0f, s.y / 2f - charMinY + 0.1f, 0f)
            } else Vector3(0f, -charMinY + 5f, 0f)
            facing = if (spawn != null) { val lv = PartData.cframe(spawn).lookVector; atan2(-lv.x, -lv.z) } else 0f
        }
        pos = clearOfTerrain(pos)
        camYaw = facing; yawTarget = facing
        renderFacing = facing
        motorAngle.clear(); r15Pose.clear()
        // Rigs with motors get Roblox's default animations (custom rigs keep any they don't use).
        animator = if (jointOrder.any { it.first.motor }) DefaultAnimate(isR15) { character?.findFirstChild("Animate") } else null
        vel = Vector3.ZERO
        dead = false
        emote = null
        hrp = root; humanoid = hum; character = model
        writeCharacter(0f)
        dm.findService("StarterPlayer")?.findFirstChild("StarterCharacterScripts")?.children?.forEach { c -> c.cloneTree().attachRaw(model) }
        // Every Roblox character has an Animate script whose StringValues/Animations list its
        // animation ids; scripts wait for it and swap ids in it. A game's own Animate wins.
        if (animator != null && model.findFirstChild("Animate") == null) DefaultAnimate.buildConfig(isR15).attachRaw(model)
        model.setParent(dm.workspace)
        p.set("Character", model)
        rt.fire(p, "CharacterAdded", rt.wrap(model))
        if (!dressable || avatarApplied >= 0) rt.fire(p, "CharacterAppearanceLoaded", rt.wrap(model))
        physicsDirty = true
    }

    /**
     * Like Roblox placing a character (Model:MoveTo): if the spot is inside terrain (a spawn
     * buried under a hill), the character goes up until it's standing on top instead.
     */
    private fun clearOfTerrain(p: Vector3): Vector3 {
        val g = terrainGrid() ?: return p
        val halfY = ((charMaxY - charMinY) / 2f).coerceAtLeast(1f)
        val midOff = (charMaxY + charMinY) / 2f
        fun buried(at: Vector3): Boolean {
            val (shape, lo, hi) = charShape(Vector3(at.x, at.y + midOff, at.z), halfY, facing)
            val r = TerrainMeshes.push(g, terrainColors, shape, lo, hi) ?: return false
            return r.first.magnitude > 0.3f
        }
        if (!buried(p)) return p
        // Stand on the terrain surface above the spot.
        TerrainMeshes.raycast(g, terrainColors, Vector3(p.x, p.y + 400f, p.z), Vector3(0f, -1f, 0f), 800f)?.let { hit ->
            val top = p.y + 400f - hit.first
            if (top > p.y) return Vector3(p.x, top - charMinY + 0.1f, p.z)
        }
        var q = p
        for (i in 0 until 100) { q += Vector3(0f, 2f, 0f); if (!buried(q)) return q }
        return p
    }

    /**
     * Where a character spawns, like Roblox: the player's RespawnLocation if set, else a random
     * enabled SpawnLocation that's Neutral or has the player's TeamColor (any enabled one if none fit).
     */
    private fun pickSpawn(p: Instance): Instance? {
        (p["RespawnLocation"] as? Instance)?.takeIf { it.className == "SpawnLocation" && it.parent != null }?.let { return it }
        val all = dm.workspace.descendants().filter { it.className == "SpawnLocation" && it["Enabled"] != false }
        if (all.isEmpty()) return null
        val teamColor = (p["TeamColor"] as? BrickColorV)?.number
        val neutralPlayer = p["Neutral"] != false || p["Team"] == null
        val fit = all.filter { s -> s["Neutral"] != false || (!neutralPlayer && (s["TeamColor"] as? BrickColorV)?.number == teamColor) }
        val pool = fit.ifEmpty { all }
        return pool[(Math.random() * pool.size).toInt().coerceIn(0, pool.size - 1)]
    }

    /** The character model chosen to play as (rigged copy used for every spawn). */
    private var playAsTemplate: Instance? = null
    private var playAsYaw: Float? = null

    // ------------------------------------------------------------------ Game Settings → Avatar

    /** StarterPlayer.GameSettingsAvatar: 0 R6, 1 R15, 2 player choice (also when unset). */
    fun gameAvatarType(): Int = (dm.findService("StarterPlayer")?.get("GameSettingsAvatar") as? EnumValue)?.value ?: AVATAR_PLAYER_CHOICE

    private fun wantR15(spec: Avatar.Spec?) = when (gameAvatarType()) { 0 -> false; 1 -> true; else -> spec?.r15 == true }

    /** The avatar's body scales kept inside the game's allowed ranges (GameSettingsScaleRange*). */
    private fun clampScales(s: Avatar.Scales): Avatar.Scales {
        val sp = dm.findService("StarterPlayer") ?: return s
        fun c(v: Float, key: String): Float { val r = sp["GameSettingsScaleRange$key"] as? NumberRange ?: return v; return if (r.max >= r.min && r.max > 0f) v.coerceIn(r.min, r.max) else v }
        return Avatar.Scales(c(s.height, "Height"), c(s.width, "Width"), c(s.depth, "Width"), c(s.head, "Head"))
    }

    /** A saved playtest character (.rbxm bytes) becomes the template every spawn copies. */
    private fun loadCharacterTemplate(bytes: ByteArray) {
        val doc = try { com.leaf.lemonade.format.RbxFile.read(bytes) } catch (e: Exception) {
            OutputLog.warn("Playtest character: couldn't read the saved model (${e.message})"); return
        }
        val roots = doc.roots.filter { it.isA("Model") || it.isA("BasePart") || it.className == "Folder" }
        if (roots.isEmpty()) { OutputLog.warn("Playtest character: the saved file has no model or part"); return }
        val src = if (roots.size == 1 && roots[0].className != "Folder") roots[0] else InstanceFactory.create("Model").also { m ->
            m.name = if (roots.size == 1) roots[0].name else "Character"
            for (r in roots) if (r.className == "Folder") r.children.toList().forEach { it.attachRaw(m) } else r.attachRaw(m)
        }
        makePlayAs(src, faceModel = false)
        if (playAsTemplate != null) OutputLog.info("Playing as ${src.name} (File → Playtest Avatar)")
    }

    /**
     * Turns a model (or single part) into a character template: adds a Humanoid and an
     * invisible HumanoidRootPart facing the model's front if it has none (parts without joints
     * then ride along rigidly), and removes the original so the player "becomes" it. With
     * [faceModel] the character starts facing the way the model faced (it spawns where it stood).
     */
    private fun makePlayAs(src: Instance, faceModel: Boolean = true) {
        src.attributes.remove(com.leaf.lemonade.core.Studio.PLAY_AS_TAG)
        val model = if (src.isA("BasePart")) InstanceFactory.create("Model").also { m -> m.name = src.name; src.cloneTree().attachRaw(m) } else src.cloneTree()
        model.attributes.remove(com.leaf.lemonade.core.Studio.PLAY_AS_TAG)
        val parts = model.descendants().filter { it.isA("BasePart") }
        val bounds = Geometry.boundsOf(parts) ?: return
        // Which way the model faces: its pivot, else its PrimaryPart, else its biggest part.
        val pivot = (model["WorldPivotData"] as? OptionalCFrame)?.cframe
            ?: (model["PrimaryPart"] as? Instance)?.let { PartData.cframe(it) }
            ?: parts.maxByOrNull { PartData.size(it).let { s -> s.x * s.y * s.z } }?.let { PartData.cframe(it) }
        val lv = pivot?.lookVector ?: Vector3(0f, 0f, -1f)
        val yaw = if (abs(lv.x) + abs(lv.z) > 1e-3f) atan2(-lv.x, -lv.z) else 0f
        if (model.findFirstChild("HumanoidRootPart")?.isA("BasePart") != true) {
            val c = (bounds.first + bounds.second) / 2f
            val root = InstanceFactory.create("Part")
            root.name = "HumanoidRootPart"
            root.set("size", Vector3(2f, 2f, 1f), false)
            root.set("CFrame", CFrame.at(c) * CFrame.ry(yaw), false)
            root.set("Transparency", 1f, false); root.set("CanCollide", false, false)
            root.attachRaw(model)
        }
        if (model.findFirstChildOfClass("Humanoid") == null) {
            val hum = InstanceFactory.create("Humanoid")
            hum.set("RigType", EnumValue(if (model.findFirstChild("UpperTorso") != null) 1 else 0), false)
            InstanceFactory.create("Animator").attachRaw(hum)
            hum.attachRaw(model)
        }
        if (faceModel) playAsYaw = PartData.cframe(model.findFirstChild("HumanoidRootPart")!!).lookVector.let { atan2(-it.x, -it.z) }
        playAsTemplate = model
        src.destroy()
    }

    /** Walks the joint graph out from the root so every frame can pose parts from joint angles. */
    private fun buildJoints(model: Instance, root: Instance) {
        jointOrder.clear(); rigid.clear()
        val parts = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        model.descendants().filter { it.isA("BasePart") }.forEach { parts.add(it) }
        val joints = ArrayList<Joint>()
        for (j in model.descendants()) {
            if (j.isA("JointInstance")) {
                val a = j["Part0"] as? Instance; val b = j["Part1"] as? Instance
                if (a != null && b != null && a in parts && b in parts && a !== b) joints.add(Joint(j, a, b, j.isA("Motor"), null))
            } else if (j.className == "WeldConstraint" && j["Enabled"] != false) {
                val a = (j["Part0Internal"] ?: j["Part0"]) as? Instance; val b = (j["Part1Internal"] ?: j["Part1"]) as? Instance
                if (a != null && b != null && a in parts && b in parts && a !== b) joints.add(Joint(j, a, b, false, PartData.cframe(a).inverse() * PartData.cframe(b)))
            }
        }
        val reached = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        reached.add(root)
        val queue = ArrayDeque<Instance>(); queue.add(root)
        val used = Collections.newSetFromMap(IdentityHashMap<Joint, Boolean>())
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (j in joints) {
                if (j in used) continue
                if (j.p0 === cur && j.p1 !in reached) { used.add(j); reached.add(j.p1); jointOrder.add(j to true); queue.add(j.p1) }
                else if (j.p1 === cur && j.p0 !in reached) { used.add(j); reached.add(j.p0); jointOrder.add(j to false); queue.add(j.p0) }
            }
        }
        for ((part, off) in offsets) if (part !in reached) rigid[part] = off
    }

    private fun buildR6(name: String): Instance = buildR6Rig(name)

    companion object {
        /** GameSettingsAvatar value for "Player Choice". */
        const val AVATAR_PLAYER_CHOICE = 2

    /** Classic blocky R6 avatar, put together the way Roblox builds one (joints, body colors, face). Also Avatar → Rig Builder's R6 rig. */
    fun buildR6Rig(name: String): Instance {
        val m = InstanceFactory.create("Model")
        m.name = name
        val yellow = Color3.fromRGB(245, 205, 48); val blue = Color3.fromRGB(13, 105, 172); val green = Color3.fromRGB(164, 189, 71)
        fun part(n: String, size: Vector3, off: Vector3, color: Color3, transparent: Boolean = false): Instance {
            val p = InstanceFactory.create("Part")
            p.name = n
            p.set("size", size, false)
            p.set("CFrame", CFrame.at(off), false)
            p.set("Color3uint8", color, false)
            p.set("Material", EnumValue(256), false)
            for (s in listOf("TopSurface", "BottomSurface", "LeftSurface", "RightSurface", "FrontSurface", "BackSurface")) p.set(s, EnumValue(0), false)
            p.set("CanCollide", n == "Torso" || n == "Head", false)
            if (transparent) { p.set("Transparency", 1f, false); p.set("CanCollide", false, false) }
            p.attachRaw(m)
            return p
        }
        val root = part("HumanoidRootPart", Vector3(2f, 2f, 1f), Vector3.ZERO, blue, true)
        val torso = part("Torso", Vector3(2f, 2f, 1f), Vector3.ZERO, blue)
        val head = part("Head", Vector3(2f, 1f, 1f), Vector3(0f, 1.5f, 0f), yellow)
        InstanceFactory.create("SpecialMesh").also { it.name = "Mesh"; it.set("MeshType", EnumValue(0), false); it.set("Scale", Vector3(1.25f, 1.25f, 1.25f), false); it.attachRaw(head) }
        val face = InstanceFactory.create("Decal"); face.name = "face"; face.set("Texture", "rbxasset://textures/face.png", false); face.set("Face", EnumValue(5), false); face.attachRaw(head)
        val la = part("Left Arm", Vector3(1f, 2f, 1f), Vector3(-1.5f, 0f, 0f), yellow)
        val ra = part("Right Arm", Vector3(1f, 2f, 1f), Vector3(1.5f, 0f, 0f), yellow)
        val ll = part("Left Leg", Vector3(1f, 2f, 1f), Vector3(-0.5f, -2f, 0f), green)
        val rl = part("Right Leg", Vector3(1f, 2f, 1f), Vector3(0.5f, -2f, 0f), green)
        fun motor(n: String, p0: Instance, p1: Instance, c0: CFrame, c1: CFrame, parent: Instance) {
            val j = InstanceFactory.create("Motor6D")
            j.name = n
            j.set("Part0", p0, false); j.set("Part1", p1, false)
            j.set("C0", c0, false); j.set("C1", c1, false)
            j.set("MaxVelocity", 0.1f, false)
            j.attachRaw(parent)
        }
        val half = (Math.PI / 2).toFloat()
        val upright = CFrame.rx(half) * CFrame.ry(Math.PI.toFloat())
        motor("RootJoint", root, torso, upright, upright, root)
        motor("Neck", torso, head, CFrame.at(Vector3(0f, 1f, 0f)) * upright, CFrame.at(Vector3(0f, -0.5f, 0f)) * upright, torso)
        motor("Right Shoulder", torso, ra, CFrame.at(Vector3(1f, 0.5f, 0f)) * CFrame.ry(half), CFrame.at(Vector3(-0.5f, 0.5f, 0f)) * CFrame.ry(half), torso)
        motor("Left Shoulder", torso, la, CFrame.at(Vector3(-1f, 0.5f, 0f)) * CFrame.ry(-half), CFrame.at(Vector3(0.5f, 0.5f, 0f)) * CFrame.ry(-half), torso)
        motor("Right Hip", torso, rl, CFrame.at(Vector3(1f, -1f, 0f)) * CFrame.ry(half), CFrame.at(Vector3(0.5f, 1f, 0f)) * CFrame.ry(half), torso)
        motor("Left Hip", torso, ll, CFrame.at(Vector3(-1f, -1f, 0f)) * CFrame.ry(-half), CFrame.at(Vector3(-0.5f, 1f, 0f)) * CFrame.ry(-half), torso)
        val bc = InstanceFactory.create("BodyColors")
        bc.name = "Body Colors"
        for (k in listOf("HeadColor3", "LeftArmColor3", "RightArmColor3")) bc.set(k, yellow, false)
        bc.set("TorsoColor3", blue, false)
        for (k in listOf("LeftLegColor3", "RightLegColor3")) bc.set(k, green, false)
        bc.attachRaw(m)
        val hum = InstanceFactory.create("Humanoid")
        hum.set("RigType", EnumValue(0), false)
        hum.attachRaw(m)
        InstanceFactory.create("Animator").attachRaw(hum)
        return m
    }
    }

    fun respawn() {
        resetGui(initial = false)
        spawnCharacter()
    }

    /** "Reset Character" from the Roblox menu. */
    fun resetCharacter() {
        val hum = humanoid ?: return
        if (!dead) hum.set(if (hum.props.containsKey("Health")) "Health" else "Health_XML", 0f)
    }

    /** Held jump keeps jumping on landing, like the mobile jump button. */
    fun setJumpHeld(down: Boolean) { jumpHeld = down; if (down) jumpRequested = true }

    private fun health(): Float = (humanoid?.let { it["Health_XML"] ?: it["Health"] } as? Number)?.toFloat() ?: 100f

    // ------------------------------------------------------------------ animation

    private var walkT = 0f
    private var idleT = 0f
    private var airTime = 0f
    private var jumpHeld = false
    private var renderFacing = 0f
    private var jumpAnimTime = 0f
    private var climbing = false
    private var seated: Instance? = null
    private var seatCooldown = 0f
    private var emote: String? = null
    private var emoteTime = 0f
    /** Current Motor angles by joint name (R6 Motors rotate about their Z axis). */
    private val motorAngle = HashMap<String, Float>()
    private val r15Pose = HashMap<String, FloatArray>()
    private var lastState = "Running"

    private fun approach(cur: Float, target: Float, rate: Float, dt: Float) = cur + (target - cur) * (1f - exp(-rate * dt))

    /** Moves a Motor toward [desired] at [maxVel] radians per 60Hz step, like Motor.MaxVelocity. */
    private fun motor(name: String, desired: Float, maxVel: Float, dt: Float) {
        val cur = motorAngle[name] ?: 0f
        val step = maxVel * 60f * dt
        motorAngle[name] = if (abs(desired - cur) <= step) desired else cur + step * Math.signum(desired - cur)
    }

    private fun pose(): String = when {
        seated != null -> "Seated"
        climbing -> "Climbing"
        swimming -> "Swimming"
        !grounded && (jumpAnimTime > 0f || vel.y > 0.5f) && airTime < 0.4f -> "Jumping"
        !grounded && airTime > 0.1f -> "FreeFall"
        sqrt(vel.x * vel.x + vel.z * vel.z) > 0.5f -> "Running"
        else -> "Standing"
    }

    /** The classic Roblox R6 Animate script: sine-wave limbs driven through Motors, plus tool and emote poses. */
    private fun animateR6(dt: Float, pose: String) {
        val speed = sqrt(vel.x * vel.x + vel.z * vel.z)
        val walk = ((humanoid?.get("WalkSpeed") as? Number)?.toFloat() ?: 16f).coerceAtLeast(1f)
        var amplitude: Float; var frequency: Float; var climbFudge = 0f; var maxVel: Float
        when (pose) {
            "Jumping", "FreeFall" -> {
                motor("Right Shoulder", 3.14f, 0.5f, dt); motor("Left Shoulder", -3.14f, 0.5f, dt)
                motor("Right Hip", 0f, 0.5f, dt); motor("Left Hip", 0f, 0.5f, dt)
                motor("Neck", 0f, 0.3f, dt)
                return
            }
            "Seated" -> {
                motor("Right Shoulder", 1.57f, 0.5f, dt); motor("Left Shoulder", -1.57f, 0.5f, dt)
                motor("Right Hip", 1.57f, 0.5f, dt); motor("Left Hip", -1.57f, 0.5f, dt)
                return
            }
            "Running" -> { amplitude = 1f; frequency = 9f * (speed / walk).coerceIn(0.5f, 1.6f); maxVel = 0.15f * (speed / walk).coerceIn(1f, 1.8f) }
            "Climbing" -> { amplitude = 1f; frequency = 9f; climbFudge = 3.14f; maxVel = 0.5f }
            else -> { amplitude = 0.1f; frequency = 1f; maxVel = 0.15f }
        }
        val climbSpeed = if (pose == "Climbing") (abs(vel.y) / (walk * 0.7f)).coerceIn(0f, 1.2f) else 1f
        walkT += dt * frequency * climbSpeed
        val desired = amplitude * sin(walkT)
        var rs = desired + climbFudge; var ls = desired - climbFudge
        val rh = -desired; val lh = -desired
        when (emote?.let { if (it.startsWith("dance")) "dance" else it }) {
            "wave" -> rs = 2.6f + 0.45f * sin(emoteTime * 9f)
            "point" -> rs = 1.57f
            "cheer" -> { rs = 3.14f + 0.2f * sin(emoteTime * 12f); ls = -3.14f + 0.2f * sin(emoteTime * 12f) }
            "dance" -> { rs = 2.6f * (0.5f + 0.5f * sin(emoteTime * 5f)); ls = -2.6f * (0.5f + 0.5f * sin(emoteTime * 5f + 3.14f)) }
        }
        if (equipped != null && emote == null) rs = 1.57f
        motor("Right Shoulder", rs, if (emote != null) 0.5f else maxVel, dt)
        motor("Left Shoulder", ls, if (emote == "cheer" || emote == "dance") 0.5f else maxVel, dt)
        motor("Right Hip", rh, maxVel, dt)
        motor("Left Hip", lh, maxVel, dt)
        motor("Neck", 0f, 0.3f, dt)
    }

    private fun setR15(name: String, x: Float, y: Float = 0f, z: Float = 0f, rate: Float, dt: Float) {
        val cur = r15Pose.getOrPut(name) { floatArrayOf(0f, 0f, 0f) }
        cur[0] = approach(cur[0], x, rate, dt); cur[1] = approach(cur[1], y, rate, dt); cur[2] = approach(cur[2], z, rate, dt)
    }

    /** R15 equivalents of the default walk / idle / jump / fall / climb / sit animations. */
    private fun animateR15(dt: Float, pose: String) {
        val speed = sqrt(vel.x * vel.x + vel.z * vel.z)
        val walk = ((humanoid?.get("WalkSpeed") as? Number)?.toFloat() ?: 16f).coerceAtLeast(1f)
        idleT += dt
        val r = 14f
        fun all(rs: Float, ls: Float, re: Float, le: Float, rh: Float, lh: Float, rk: Float, lk: Float, waistX: Float = 0f, waistY: Float = 0f, rate: Float = r) {
            setR15("RightShoulder", rs, rate = rate, dt = dt); setR15("LeftShoulder", ls, rate = rate, dt = dt)
            setR15("RightElbow", re, rate = rate, dt = dt); setR15("LeftElbow", le, rate = rate, dt = dt)
            setR15("RightHip", rh, rate = rate, dt = dt); setR15("LeftHip", lh, rate = rate, dt = dt)
            setR15("RightKnee", rk, rate = rate, dt = dt); setR15("LeftKnee", lk, rate = rate, dt = dt)
            setR15("Waist", waistX, waistY, rate = rate, dt = dt)
        }
        when (pose) {
            "Jumping" -> all(2.4f, 2.4f, 0.4f, 0.4f, 0.35f, -0.1f, -0.5f, -0.2f, rate = 18f)
            "FreeFall" -> all(2.8f, 2.8f, 0.2f, 0.2f, 0.5f, -0.25f, -0.7f, -0.3f, rate = 8f)
            "Seated" -> all(0.35f, 0.35f, 0.6f, 0.6f, 1.57f, 1.57f, -1.57f, -1.57f)
            "Climbing" -> {
                walkT += dt * 8f * (abs(vel.y) / (walk * 0.7f)).coerceIn(0f, 1.2f)
                val s = sin(walkT)
                all(2.6f + 0.5f * s, 2.6f - 0.5f * s, 0.6f, 0.6f, 0.55f + 0.5f * s, 0.55f - 0.5f * s, -0.8f - 0.4f * s, -0.8f + 0.4f * s, rate = 20f)
            }
            "Running" -> {
                val k = (speed / walk).coerceIn(0.4f, 1.6f)
                walkT += dt * 9f * k
                val s = sin(walkT); val c = cos(walkT)
                val a = 0.7f * k.coerceAtMost(1.2f)
                all(-0.6f * s * k.coerceAtMost(1.2f), 0.6f * s * k.coerceAtMost(1.2f), 0.25f + 0.2f * maxOf(0f, -s), 0.25f + 0.2f * maxOf(0f, s),
                    a * s, -a * s, -0.9f * maxOf(0f, -c), -0.9f * maxOf(0f, c), 0.05f, 0.08f * s, rate = 22f)
            }
            else -> {
                val b = sin(idleT * 1.6f)
                all(0.04f * b, 0.04f * b, 0.08f, 0.08f, 0f, 0f, 0f, 0f, 0.025f * b, rate = 8f)
                walkT = 0f
            }
        }
        if (equipped != null && emote == null) setR15("RightShoulder", 1.57f, rate = 20f, dt = dt)
        when (emote?.let { if (it.startsWith("dance")) "dance" else it }) {
            "wave" -> setR15("RightShoulder", 2.4f, 0f, 0.3f * sin(emoteTime * 9f), rate = 20f, dt = dt)
            "point" -> setR15("RightShoulder", 1.57f, rate = 20f, dt = dt)
            "cheer" -> { setR15("RightShoulder", 2.9f, rate = 20f, dt = dt); setR15("LeftShoulder", 2.9f, rate = 20f, dt = dt) }
            "dance" -> { val s = sin(emoteTime * 5f); setR15("RightShoulder", 1.4f + s, rate = 20f, dt = dt); setR15("LeftShoulder", 1.4f - s, rate = 20f, dt = dt); setR15("Waist", 0f, 0.3f * s, rate = 20f, dt = dt) }
        }
    }

    private fun animate(dt: Float) {
        airTime = if (grounded || climbing || seated != null) 0f else airTime + dt
        if (jumpAnimTime > 0f) jumpAnimTime -= dt
        if (emote != null) {
            emoteTime += dt
            if (sqrt(vel.x * vel.x + vel.z * vel.z) > 0.5f || !grounded || (emote?.startsWith("dance") != true && emoteTime > 2.5f)) emote = null
        }
        val p = pose()
        if (isR15) animateR15(dt, p) else animateR6(dt, p)
        // Roblox's real default animations once they've downloaded (procedural until then).
        val hs = if (p == "Climbing") abs(vel.y) else sqrt(vel.x * vel.x + vel.z * vel.z)
        animator?.step(dt, p, hs, equipped != null, emote, jumpAnimTime)
        // Smooth turning toward the movement direction (instant while facing the camera).
        var diff = facing - renderFacing
        while (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
        while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
        if ((shiftLock || firstPerson) && !climbing && seated == null) renderFacing = facing
        else renderFacing += diff * (1f - exp(-18f * dt))
    }

    /** Joint rotation applied between C0 and C1 for animated Motor6Ds. */
    /** Plays the default Animate script's animations on the player's character. */
    private var animator: DefaultAnimate? = null

    // ------------------------------------------------------------------ script AnimationTracks

    /** A track from LoadAnimation: plays once its clip has downloaded. */
    inner class ScriptTrack(val rig: Instance, val id: Long?, private val inline: AnimClip?, val table: LuaTable) {
        var warned = false
        var track: AnimPlayer.Track? = null
        var wantPlay = false
        var fade = 0.1f; var weight = 1f; var speed = 1f
        var looped: Boolean? = null
        var priority: Int? = null
        var loopsSeen = 0
        var ending: AnimPlayer.Track? = null
        val clip: AnimClip? get() = inline ?: id?.let { AnimLibrary.get(it) }
        val isPlaying get() = wantPlay
        val length get() = clip?.length ?: 0f
        val time get() = track?.time ?: 0f
        val weightNow get() = track?.weight ?: 0f
        val weightTarget get() = if (wantPlay) weight else 0f
    }

    private val scriptTracks = ArrayList<ScriptTrack>()
    private val rigPlayers = IdentityHashMap<Instance, AnimPlayer>()
    /** When scripts last played Core/Movement tracks on the character (a custom Animate script). */
    private var customAnimateAt = -100.0

    /** The model a Humanoid / Animator / AnimationController animates. */
    private fun rigOf(owner: Instance): Instance? {
        var r: Instance? = owner.parent
        if (owner.className == "Animator") r = r?.parent
        return r?.takeIf { it.isA("Model") || it.isA("BasePart") || it.className == "WorldModel" }
    }

    fun loadTrack(owner: Instance, anim: Instance?, table: LuaTable): ScriptTrack? {
        val rig = rigOf(owner) ?: return null
        val raw = anim?.get("AnimationId")?.toString().orEmpty()
        val inline = anim?.descendants()?.firstOrNull { it.className == "KeyframeSequence" || it.className == "CurveAnimation" }?.let { AnimLibrary.fromInstance(it) }
            ?: if (EditAnimation.isTempId(raw)) EditAnimation.tempClip(raw) else null
        val id = com.leaf.lemonade.net.AssetFetcher.idOf(raw)
        if (inline == null && id == null) return null
        if (inline == null) AnimLibrary.get(id!!)
        return ScriptTrack(rig, id, inline, table).also { scriptTracks.add(it) }
    }

    fun playTrack(st: ScriptTrack, fade: Float, weight: Float, speed: Float) {
        st.track?.let { t -> rigPlayers[st.rig]?.stop(t, fade) }
        st.track = null
        st.wantPlay = true; st.fade = fade; st.weight = weight; st.speed = speed; st.loopsSeen = 0
        startTrack(st)
        rt.fire(st.table, "Played")
    }

    fun stopTrack(st: ScriptTrack, fade: Float) {
        val was = st.wantPlay
        st.wantPlay = false
        val cur = st.track
        if (cur != null) { rigPlayers[st.rig]?.stop(cur, fade); if (fade > 0.001f) st.ending = cur }
        st.track = null
        if (was) { rt.fire(st.table, "Stopped"); if (cur == null || fade <= 0.001f) rt.fire(st.table, "Ended") }
    }

    fun adjustTrack(st: ScriptTrack, speed: Float? = null, weight: Float? = null, fade: Float? = null, looped: Boolean? = null, priority: Int? = null, time: Float? = null) {
        speed?.let { st.speed = it; st.track?.speed = it }
        weight?.let { w -> st.weight = w; st.track?.let { t -> t.fadeTo(w, fade ?: 0.1f) } }
        looped?.let { st.looped = it; st.track?.looped = it }
        priority?.let { st.priority = it; st.track?.priority = AnimPlayer.rank(it) }
        time?.let { tp -> st.track?.let { it.time = tp.coerceIn(0f, it.clip.length) } }
    }

    fun playingTrackTables(owner: Instance): List<LuaTable> {
        val rig = rigOf(owner) ?: return emptyList()
        return scriptTracks.filter { it.rig === rig && it.wantPlay }.map { it.table }
    }

    private fun startTrack(st: ScriptTrack) {
        val clip = st.clip ?: return
        val p = rigPlayers.getOrPut(st.rig) { AnimPlayer() }
        val t = p.play(clip, "script", st.fade, st.weight, st.speed, AnimPlayer.rank(st.priority ?: clip.priority))
        st.looped?.let { t.looped = it }
        st.track = t
    }

    /** Advances script tracks, fires their events and poses NPC rigs (the player's rig is posed in writeCharacter). */
    private fun stepScriptTracks(dt: Float) {
        if (scriptTracks.isEmpty() && rigPlayers.isEmpty()) return
        val it = scriptTracks.iterator()
        while (it.hasNext()) {
            val st = it.next()
            if (st.rig.parent == null) { it.remove(); continue }
            if (st.wantPlay && st.track == null) startTrack(st)
            if (st.wantPlay && st.track == null && !st.warned && st.id != null && AnimLibrary.failed(st.id)) {
                st.warned = true
                val why = AnimLibrary.lastError(st.id).orEmpty()
                val hint = if (com.leaf.lemonade.net.RobloxApi.isGuest(studio.app)) " Sign in to Roblox (account button, top right) so animations can download." else ""
                OutputLog.warn("Animation ${st.id} couldn't be loaded${if (why.isNotEmpty()) ": $why" else ""}.$hint")
            }
            st.ending?.let { e ->
                if (rigPlayers[st.rig]?.tracks?.contains(e) != true) { st.ending = null; rt.fire(st.table, "Ended") }
            }
            val t = st.track ?: continue
            if (st.rig === character && t.priority <= 1 && st.wantPlay) customAnimateAt = time
            if (t.loops > st.loopsSeen) { st.loopsSeen = t.loops; rt.fire(st.table, "DidLoop") }
            if (t.ended && !t.looped && st.wantPlay) {
                // A one-shot animation finished: it fades out and reports Stopped, like Roblox.
                st.wantPlay = false
                rigPlayers[st.rig]?.stop(t, 0.3f)
                st.ending = t
                st.track = null
                rt.fire(st.table, "Stopped"); rt.fire(st.table, "DidLoop")
            }
        }
        val rit = rigPlayers.entries.iterator()
        while (rit.hasNext()) {
            val (rig, p) = rit.next()
            if (rig.parent == null) { rit.remove(); continue }
            p.step(dt)
            if (rig !== character && p.tracks.isNotEmpty()) poseRig(rig, p)
            if (p.tracks.isEmpty() && rig !== character) { rit.remove(); rigJoints.remove(rig) }
        }
    }

    /** Joint order of an NPC rig (Motor6Ds animate; welds and WeldConstraints follow). */
    private class RigJoint(val inst: Instance, val p0: Instance, val p1: Instance, val motor: Boolean, val fixed: CFrame?)
    private val rigJoints = IdentityHashMap<Instance, Pair<Instance, List<RigJoint>>>()

    private fun poseRig(rig: Instance, p: AnimPlayer) {
        val (root, order) = rigJoints.getOrPut(rig) { buildRig(rig) ?: return }
        if (root.parent == null) { rigJoints.remove(rig); return }
        val cfs = IdentityHashMap<Instance, CFrame>()
        cfs[root] = PartData.cframe(root)
        writing = true
        try {
            for (j in order) {
                val a = cfs[j.p0] ?: continue
                val cf = if (j.fixed != null) a * j.fixed else {
                    val c0 = j.inst["C0"] as? CFrame ?: CFrame.IDENTITY; val c1 = j.inst["C1"] as? CFrame ?: CFrame.IDENTITY
                    a * c0 * (if (j.motor) p.transform(j.p1.name) ?: CFrame.IDENTITY else CFrame.IDENTITY) * c1.inverse()
                }
                cfs[j.p1] = cf
                j.p1.set("CFrame", cf)
            }
        } finally { writing = false }
    }

    private fun buildRig(rig: Instance): Pair<Instance, List<RigJoint>>? {
        val parts = rig.descendants().filter { it.isA("BasePart") }.toSet()
        val joints = ArrayList<RigJoint>()
        for (j in rig.descendants()) {
            if (j.isA("JointInstance")) {
                val a = j["Part0"] as? Instance; val b = j["Part1"] as? Instance
                if (a != null && b != null && a in parts && b in parts && a !== b) joints.add(RigJoint(j, a, b, j.isA("Motor"), null))
            } else if (j.className == "WeldConstraint") {
                val a = (j["Part0Internal"] ?: j["Part0"]) as? Instance; val b = (j["Part1Internal"] ?: j["Part1"]) as? Instance
                if (a != null && b != null && a in parts && b in parts && a !== b) joints.add(RigJoint(j, a, b, false, PartData.cframe(a).inverse() * PartData.cframe(b)))
            }
        }
        val root = rig.findFirstChild("HumanoidRootPart")?.takeIf { it in parts } ?: (rig["PrimaryPart"] as? Instance)?.takeIf { it in parts }
            ?: joints.map { it.p0 }.firstOrNull { p0 -> joints.none { it.p1 === p0 } } ?: return null
        // Walk out from the root; joints met from their Part1 side are flipped.
        val order = ArrayList<RigJoint>()
        val reached = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>()); reached.add(root)
        val queue = ArrayDeque<Instance>(); queue.add(root)
        val used = Collections.newSetFromMap(IdentityHashMap<RigJoint, Boolean>())
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (j in joints) {
                if (j in used) continue
                if (j.p0 === cur && j.p1 !in reached) { used.add(j); reached.add(j.p1); order.add(j); queue.add(j.p1) }
                else if (j.p1 === cur && j.p0 !in reached && !j.motor) {
                    used.add(j); reached.add(j.p0)
                    val inv = j.fixed?.inverse() ?: ((j.inst["C1"] as? CFrame ?: CFrame.IDENTITY) * (j.inst["C0"] as? CFrame ?: CFrame.IDENTITY).inverse())
                    order.add(RigJoint(j.inst, j.p1, j.p0, false, inv)); queue.add(j.p0)
                }
            }
        }
        return root to order
    }

    private fun transformFor(j: Joint): CFrame {
        if (!j.motor) return CFrame.IDENTITY
        val scripted = rigPlayers[character]
        // A game's own Animate script (Core/Movement tracks) replaces the built-in one.
        val base = if (time - customAnimateAt < 1.0) CFrame.IDENTITY else animator?.transform(j.p1.name)
        if (scripted != null && scripted.tracks.isNotEmpty()) scripted.transformOver(base, j.p1.name)?.let { return it }
        base?.let { return it }
        if (!isR15) return motorAngle[j.name]?.let { CFrame.rz(it) } ?: CFrame.IDENTITY
        val a = r15Pose[j.name] ?: return CFrame.IDENTITY
        var cf = CFrame.rx(a[0])
        if (a[1] != 0f) cf = CFrame.ry(a[1]) * cf
        if (a[2] != 0f) cf = cf * CFrame.rz(a[2])
        return cf
    }

    private fun writeCharacter(dt: Float) {
        val root = hrp ?: return
        animate(dt)
        val seat = seated
        val base = if (seat != null) {
            val scf = PartData.cframe(seat); val ss = PartData.size(seat)
            scf * CFrame.at(Vector3(0f, ss.y / 2f + (if (isR15) -charMinY - 1.4f else 1.5f), 0f))
        } else CFrame.at(pos) * CFrame.ry(renderFacing)
        if (seat != null) pos = base.position
        writing = true
        try {
            val cfs = IdentityHashMap<Instance, CFrame>()
            cfs[root] = base
            for ((j, forward) in jointOrder) {
                val c0 = (j.inst["C0"] as? CFrame) ?: CFrame.IDENTITY
                val c1 = (j.inst["C1"] as? CFrame) ?: CFrame.IDENTITY
                if (forward) {
                    val a = cfs[j.p0] ?: continue
                    cfs[j.p1] = j.fixed?.let { a * it } ?: (a * c0 * transformFor(j) * c1.inverse())
                } else {
                    val b = cfs[j.p1] ?: continue
                    cfs[j.p0] = j.fixed?.let { b * it.inverse() } ?: (b * c1 * transformFor(j).inverse() * c0.inverse())
                }
            }
            for ((part, off) in rigid) cfs[part] = base * off
            for ((part, cf) in cfs) {
                if (part.parent == null) continue
                if (part === root) lastWritten = cf
                part.set("CFrame", cf)
            }
            equipped?.let { tool ->
                val handle = tool.findFirstChild("Handle") ?: return@let
                val arm = character?.findFirstChild("Right Arm") ?: character?.findFirstChild("RightHand") ?: return@let
                val grip = tool["Grip"] as? CFrame ?: CFrame.IDENTITY
                val hcf = PartData.cframe(arm) * CFrame(0f, if (isR15) -0.15f else -1f, 0f) * CFrame.rx(-1.5708f) * grip.inverse()
                handle.set("CFrame", hcf)
                for ((p, o) in toolOffsets) if (p !== handle) p.set("CFrame", hcf * o)
            }
        } finally { writing = false }
    }

    // ------------------------------------------------------------------ death (classic break-apart)

    private class Debris(val part: Instance, var vel: Vector3, var spin: Vector3)
    private val debris = ArrayList<Debris>()

    private fun breakApart() {
        debris.clear()
        val rnd = java.util.Random()
        for ((part, _) in offsets) {
            if (part === hrp || part.parent == null) continue
            val out = (PartData.cframe(part).position - pos).let { Vector3(it.x, 0f, it.z) }
            val push = if (out.magnitude > 0.01f) out.unit * 6f else Vector3.ZERO
            debris.add(Debris(part, vel * 0.5f + push + Vector3((rnd.nextFloat() - 0.5f) * 8f, 4f + rnd.nextFloat() * 6f, (rnd.nextFloat() - 0.5f) * 8f),
                Vector3((rnd.nextFloat() - 0.5f) * 10f, (rnd.nextFloat() - 0.5f) * 10f, (rnd.nextFloat() - 0.5f) * 10f)))
        }
        for (j in character?.descendants().orEmpty()) if (j.className == "Motor6D") j.destroy()
    }

    private fun stepDebris(dt: Float) {
        val g = gravity
        writing = true
        try {
            for (b in debris) {
                val p = b.part
                if (p.parent == null) continue
                b.vel = Vector3(b.vel.x, b.vel.y - g * dt, b.vel.z)
                var cf = PartData.cframe(p) + b.vel * dt
                val sm = b.spin.magnitude
                if (sm > 0.01f) cf = (CFrame.at(cf.position) * CFrame.fromAxisAngle(b.spin / sm, sm * dt) * cf.rotationOnly()).orthonormalize()
                p.set("CFrame", cf)
                val (a0, a1) = Geometry.partAabb(p)
                val shape = Geometry.shapeOf(p)
                var push = Vector3.ZERO
                for (s in query(a0, a1)) {
                    if (s["CanCollide"] == false || isCharacterPart(s) || blockedByGroup(s)) continue
                    val m = Geometry.contact(shape, a0, a1, s)?.first ?: continue
                    if (m.magnitude > push.magnitude) push = m
                }
                if (push.magnitude > 1e-4f) {
                    p.set("CFrame", PartData.cframe(p) + push)
                    val n = push.unit
                    val vn = b.vel.dot(n)
                    if (vn < 0) b.vel -= n * (vn * 1.3f)
                    b.vel = Vector3(b.vel.x * 0.8f, b.vel.y, b.vel.z * 0.8f)
                    b.spin = b.spin * 0.7f
                }
            }
        } finally { writing = false }
    }

    // ------------------------------------------------------------------ movement

    private fun facingVec() = Vector3(-sin(facing), 0f, -cos(facing))

    /** Parts directly in front of the character, sampled at several heights (for ladders and trusses). */
    private fun frontHits(): List<RayHit?> {
        val f = facingVec()
        val reach = charHalfXZ + 1.1f
        val out = ArrayList<RayHit?>()
        val lo = pos + Vector3(-reach - 1f, charMinY, -reach - 1f); val hi = pos + Vector3(reach + 1f, charMaxY, reach + 1f)
        val cands = query(lo, hi).filter { it["CanCollide"] != false && !isCharacterPart(it) && !blockedByGroup(it) }
        if (cands.isEmpty()) return emptyList()
        var y = charMinY + 0.3f
        while (y <= charMaxY - 0.2f) {
            out.add(Geometry.raycast(cands, Vector3(pos.x, pos.y + y, pos.z), f, reach) { true })
            y += 0.4f
        }
        return out
    }

    /**
     * Roblox lets characters climb TrussParts and ladder-like stacks of parts with gaps between
     * the rungs (a flat wall isn't climbable).
     */
    private fun canClimb(hits: List<RayHit?>, alreadyClimbing: Boolean): Boolean {
        if (hits.isEmpty() || hits.all { it == null }) return false
        if (hits.any { it?.part?.className == "TrussPart" }) return true
        if (alreadyClimbing) return true
        // Ladder: hits with gaps between them, and no gap taller than ~2.4 studs.
        val firstHit = hits.indexOfFirst { it != null }
        val lastHit = hits.indexOfLast { it != null }
        if (firstHit < 0 || lastHit - firstHit < 2) return false
        var gaps = 0; var run = 0; var maxRun = 0
        for (i in firstHit..lastHit) {
            if (hits[i] == null) { run++; maxRun = max(maxRun, run) } else { if (run > 0) gaps++; run = 0 }
        }
        val hitCount = hits.count { it != null }
        val ts = hits.filterNotNull().map { it.t }
        if (ts.max() - ts.min() > 0.7f) return false
        if (lastHit < hits.size / 2) return false
        // A wall hits everywhere; a ladder has at least one gap and enough rungs.
        return gaps >= 1 && maxRun * 0.4f <= 2.4f && hitCount >= 2 && hitCount < hits.size
    }

    private fun setState(name: String, value: Int) {
        if (name == lastState) return
        val hum = humanoid ?: return
        val old = Enums.types["HumanoidStateType"]?.get(lastState) ?: 8
        rt.fire(hum, "StateChanged", LuaTypes.wrap(EnumItemV("HumanoidStateType", lastState, old)), LuaTypes.wrap(EnumItemV("HumanoidStateType", name, value)))
        lastState = name
    }

    private fun leaveSeat(jump: Boolean) {
        val s = seated ?: return
        seated = null
        seatCooldown = 1f
        s.set("Occupant", null)
        humanoid?.let { h -> h.set("Sit", false); rt.fire(h, "Seated", LuaValue.FALSE, LuaValue.NIL) }
        // Seat welds would be destroyed here in Roblox; we only track the seat.
        if (jump) {
            val f = facingVec()
            vel = Vector3(f.x * 4f, 45f, f.z * 4f)
            pos += Vector3(0f, 0.5f, 0f)
        }
        setState("Jumping", 3)
    }

    private fun trySit(dt: Float) {
        if (seated != null || seatCooldown > 0f || dead) { seatCooldown -= dt; return }
        val (c, half) = charBox()
        for (p in query(c - half - Vector3(0.2f, 0.4f, 0.2f), c + half + Vector3(0.2f, 0.2f, 0.2f), true)) {
            if ((p.className != "Seat" && p.className != "VehicleSeat") || p["Disabled"] == true || p["Occupant"] is Instance) continue
            // Sit when landing on it or walking into it from above its top.
            val (sa, sb) = Geometry.partAabb(p)
            if (pos.y + charMaxY < sa.y || pos.y + charMinY > sb.y + 0.6f) continue
            seated = p
            vel = Vector3.ZERO
            facing = PartData.cframe(p).lookVector.let { atan2(-it.x, -it.z) }
            p.set("Occupant", humanoid)
            humanoid?.let { h -> h.set("Sit", true); rt.fire(h, "Seated", LuaValue.TRUE, rt.wrap(p)) }
            setState("Seated", 13)
            return
        }
    }

    private fun stepCharacter(dt: Float) {
        val root = hrp ?: return
        val hum = humanoid ?: return
        // Shift lock / first person: the camera turns with the finger at once and the body
        // faces it in the same frame (Roblox sets the root's rotation every frame). Easing the
        // camera, then the body after it, made shift lock trail behind.
        if (shiftLock || firstPerson) camYaw = yawTarget
        if (character?.parent == null || root.parent == null) {
            if (!dead) { dead = true; deadTimer = respawnTime() }
        }
        if (!dead && health() <= 0f) {
            dead = true
            deadTimer = respawnTime()
            leaveSeat(false)
            rt.fire(hum, "Died")
            setState("Dead", 15)
            OutputLog.info("${player?.name} died")
            if (hum["BreakJointsOnDeath"] != false) breakApart()
        }
        if (dead) {
            stepDebris(dt)
            deadTimer -= dt
            if (deadTimer <= 0f && dm.findService("Players")?.get("CharacterAutoLoads") != false) { debris.clear(); respawn() }
            return
        }
        val cf = PartData.cframe(root)
        val lw = lastWritten
        if (lw != null && cf != lw && seated == null) {
            pos = cf.position
            vel = Vector3.ZERO
            val lv = cf.lookVector
            if (abs(lv.y) < 0.99f) { facing = atan2(-lv.x, -lv.z); renderFacing = facing }
        }
        if (hum["Sit"] == false && seated != null) leaveSeat(false)

        val walk = (hum["WalkSpeed"] as? Number)?.toFloat() ?: 16f
        val g = gravity
        val jumpPower = (if (hum["UseJumpPower"] == false) (sqrt(2f * g * ((hum["JumpHeight"] as? Number)?.toFloat() ?: 7.2f)) / 1.06f).coerceIn(0f, 1000f)
        else (hum["JumpPower"] as? Number)?.toFloat() ?: 50f) * 1.06f

        val fwd = Vector3(-sin(camYaw), 0f, -cos(camYaw))
        val right = Vector3(cos(camYaw), 0f, -sin(camYaw))
        var dir = if (controlsEnabled) right * studio.moveX + fwd * (-studio.moveY) else Vector3.ZERO
        if (dir.magnitude > 1f) dir = dir.unit
        scriptMoveDir?.let { if (it.magnitude > 0.01f) dir = Vector3(it.x, 0f, it.z).let { v -> if (v.magnitude > 1f) v.unit else v } }
        moveToTarget?.let { t ->
            val d = Vector3(t.x - pos.x, 0f, t.z - pos.z)
            if (d.magnitude < 1.2f) {
                moveToTarget = null
                rt.fire(hum, "MoveToFinished", LuaValue.TRUE)
            } else if (dir.magnitude < 0.05f) dir = d.unit
        }
        lastDir = dir

        // Seated: stay on the seat until jumping.
        if (seated != null) {
            if (jumpRequested) leaveSeat(true)
            else { writeCharacter(dt); return }
        }

        // Climbing ladders and trusses.
        val hits = if (climbing || dir.magnitude > 0.1f) frontHits() else emptyList()
        val f = facingVec()
        val towardWall = dir.magnitude > 0.1f && dir.unit.dot(f) > 0.3f
        if (climbing) {
            if (jumpRequested) {
                climbing = false
                vel = Vector3(-f.x * 25f, jumpPower / 1.06f * 0.6f, -f.z * 25f)
                jumpRise = true
                jumpRequested = jumpHeld
                setState("Jumping", 3)
                rt.fire(hum, "Jumping", LuaValue.TRUE)
            } else if (!canClimb(hits, true)) {
                climbing = false
                // Reached the top: step up and over onto the ledge.
                if (vel.y > 0f) { vel = Vector3(f.x * 8f, 22f, f.z * 8f) }
                setState("Freefall", 5)
            }
        } else if (towardWall && canClimb(hits, false) && (grounded || vel.y < 5f)) {
            climbing = true
            setState("Climbing", 12)
        }

        val swimNow = !climbing && seated == null && inWater()
        if (swimNow) { lastWaterTime = time; if (!swimming) { swimming = true; setState("Swimming", 4) } }
        else if (swimming && time - lastWaterTime > 0.25) { swimming = false; setState("Freefall", 5) }
        if (swimNow) {
            // Terrain water: buoyant, full speed sideways, holding jump swims up.
            val h = dir * walk
            val targetVy = if (jumpHeld || jumpRequested) walk * 0.8f else -2.5f
            vel = Vector3(h.x, vel.y + (targetVy - vel.y) * (1f - exp(-5f * dt)), h.z)
            grounded = false
            if (dir.magnitude > 0.1f) rt.fire(hum, "Swimming", LuaValue.valueOf((dir.magnitude * walk).toDouble()))
        } else if (climbing) {
            val along = if (dir.magnitude > 0.05f) dir.dot(f) else 0f
            val climbSpeed = walk * 0.7f * along
            // Stick to the ladder: small push into it, no gravity, no sideways drift.
            vel = Vector3(f.x * 2f, climbSpeed, f.z * 2f)
            if (abs(climbSpeed) > 0.1f) rt.fire(hum, "Climbing", LuaValue.valueOf(climbSpeed.toDouble()))
            grounded = false
        } else {
            val h = dir * walk
            val hv = approachWalk(vel.x, vel.z, h.x, h.z, dt, grounded)
            vel = Vector3(hv.first, vel.y, hv.second)
            if (hum["Jump"] == true) { jumpRequested = true; hum.set("Jump", false, false) }
            // Wallhop (like Roblox): a quick turn while airborne against a wall makes the turning
            // body catch the wall, which the humanoid briefly counts as standing, so a jump goes off.
            val wallHop = !grounded && time - wallHopAt < 0.15 && vel.y < 12f
            // Just surfaced from swimming: jumping climbs out, like Roblox.
            val fromWater = !grounded && time - lastWaterTime < 0.35
            val coyote = !grounded && vel.y <= 0f && time - lastGroundedAt < 0.1
            if (jumpRequested && (grounded || coyote || wallHop || fromWater)) {
                if (fromWater) lastWaterTime = -10.0
                if (wallHop) wallHopAt = -10.0
                vel = Vector3(vel.x, jumpPower, vel.z)
                jumpRise = true
                grounded = false
                jumpAnimTime = 0.3f
                emote = null
                setState("Jumping", 3)
                rt.fire(hum, "Jumping", LuaValue.TRUE)
            }
        }
        jumpRequested = jumpHeld
        val faceCamera = shiftLock || firstPerson
        if (!climbing) {
            if (faceCamera) facing = camYaw
            else if (dir.magnitude > 0.05f && hum["AutoRotate"] != false) facing = atan2(-dir.x, -dir.z)
        }

        val travel = vel.magnitude * dt
        val steps = maxOf(ceil(travel / 0.6f).toInt(), ceil(dt * 240f - 1e-4f).toInt()).coerceIn(1, 16)
        val sdt = dt / steps
        val wasGrounded = grounded
        grounded = false
        wallTouch = false
        val floorMode = !climbing && !swimNow && seated == null
        repeat(steps) {
            pos += vel * sdt
            var spring = false
            if (floorMode) {
                if (jumpRise && vel.y <= 0f) jumpRise = false
                val ff = findFloor(hadFloor)
                hadFloor = ff != null
                if (ff != null && !jumpRise) {
                    val target = ff + restHeight()
                    if (pos.y < target) { vel = Vector3(vel.x, 39f * (target - pos.y), vel.z); spring = true }
                    if (pos.y - target <= 0.25f) grounded = true
                }
            }
            collideCharacter()
            if (floorMode && !spring) vel = Vector3(vel.x, vel.y - g * sdt, vel.z)
        }
        if (climbing) grounded = false
        // A fast turn (camera flick / sharp steer) while pressed against a wall in the air opens a wallhop.
        val turnRate = abs(wrapAngle(renderFacing - colYaw)) / dt.coerceAtLeast(1e-3f)
        colYaw = renderFacing
        if (!grounded && !climbing && wallTouch && turnRate > 4.5f) wallHopAt = time
        if (grounded) lastGroundedAt = time
        if (!wasGrounded && grounded) { setState("Landed", 7); setState("Running", 8) }
        else if (wasGrounded && !grounded && !climbing && vel.y <= 0f) { setState("Freefall", 5); rt.fire(hum, "FreeFalling", LuaValue.TRUE) }
        trySit(dt)
        val destroyY = (dm.workspace["FallenPartsDestroyHeight"] as? Number)?.toFloat() ?: -500f
        if (pos.y < destroyY) hum.set(if (hum.props.containsKey("Health")) "Health" else "Health_XML", 0f)
        writeCharacter(dt)
    }

    private fun respawnTime() = (dm.findService("Players")?.get("RespawnTime") as? Number)?.toFloat() ?: 5f

    private fun charBox(): Pair<Vector3, Vector3> {
        val c = Vector3(pos.x, pos.y + (charMinY + charMaxY) / 2f, pos.z)
        val half = Vector3(charHalfXZ, (charMaxY - charMinY) / 2f, charHalfXZ)
        return c to half
    }

    /** The character's collision box: the root's footprint turned to [yaw], full character height. */
    private fun charShape(c: Vector3, halfY: Float, yaw: Float): Triple<com.leaf.lemonade.engine.ConvexShape, Vector3, Vector3> {
        val r = Vector3(cos(yaw), 0f, -sin(yaw)); val f = Vector3(-sin(yaw), 0f, -cos(yaw))
        val verts = ArrayList<Vector3>(8)
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1))
            verts.add(c + r * (sx * colHalfW) + Vector3(0f, sy * halfY, 0f) + f * (sz * colHalfD))
        val axes = listOf(r, Vector3.Y, f)
        val ext = Vector3(abs(r.x) * colHalfW + abs(f.x) * colHalfD, halfY, abs(r.z) * colHalfW + abs(f.z) * colHalfD)
        return Triple(com.leaf.lemonade.engine.ConvexShape(verts, axes, axes), c - ext, c + ext)
    }

    private fun collideCharacter() {
        repeat(3) {
            val (c, half) = charBox()
            val (box, lo, hi) = charShape(c, half.y, renderFacing)
            var moved = false
            for (part in query(lo, hi, true)) {
                if (part["CanCollide"] == false || isCharacterPart(part) || blockedByGroup(part)) continue
                // Unions and meshes push by their real shape (see CollisionMeshes).
                val (mtv, top) = Geometry.contact(box, lo, hi, part) ?: continue
                val d = mtv.magnitude
                if (d < 1e-4f) continue
                val n = mtv / d
                if (n.y > 0.55f) {
                    pos = Vector3(pos.x, pos.y + d / n.y, pos.z)
                    if (vel.y < 0) vel = Vector3(vel.x, 0f, vel.z)
                    grounded = true
                    floorMu = (((part["CustomPhysicalProperties"] as? PhysicalProps)?.takeIf { it.custom }?.let { it.friction * it.frictionWeight + 0.3f } ?: 0.6f) / (((part["CustomPhysicalProperties"] as? PhysicalProps)?.takeIf { it.custom }?.frictionWeight ?: 1f) + 1f))
                } else if (n.y < -0.55f) {
                    pos += mtv
                    if (vel.y > 0) vel = Vector3(vel.x, -vel.y * 0.5f, vel.z)
                } else {
                    val feet = c.y - half.y
                    val step = top - feet
                    if (step > 0f && step <= 2.1f && vel.y <= 1f && !climbing) pos = Vector3(pos.x, pos.y + step + 0.01f, pos.z)
                    else { pos += Vector3(mtv.x, 0f, mtv.z); wallTouch = true }
                }
                moved = true
                break
            }
            // Smooth terrain: its surface triangles push like a mesh. Slopes up to the Humanoid's
            // MaxSlopeAngle count as ground (Roblox lets you walk up steep hills).
            if (!moved) terrainGrid()?.let { tg ->
                val (mtv, top) = TerrainMeshes.push(tg, terrainColors, box, lo, hi) ?: return@let
                val d = mtv.magnitude
                if (d < 1e-4f) return@let
                val n = mtv / d
                val maxSlope = ((humanoid?.get("MaxSlopeAngle") as? Number)?.toFloat() ?: 89f).coerceIn(0f, 89f)
                val floorY = kotlin.math.cos(Math.toRadians(maxSlope.toDouble())).toFloat().coerceIn(0.1f, 0.55f)
                if (n.y > floorY) {
                    pos = Vector3(pos.x, pos.y + d / n.y.coerceAtLeast(0.25f), pos.z)
                    if (vel.y < 0) vel = Vector3(vel.x, 0f, vel.z)
                    grounded = true
                    floorMu = 0.35f
                } else if (n.y < -0.55f) {
                    pos += mtv
                    if (vel.y > 0) vel = Vector3(vel.x, -vel.y * 0.5f, vel.z)
                } else {
                    val feet = c.y - half.y
                    val step = top - feet
                    if (step > 0f && step <= 2.1f && vel.y <= 1f && !climbing) pos = Vector3(pos.x, pos.y + step + 0.01f, pos.z)
                    else { pos += Vector3(mtv.x, 0f, mtv.z); wallTouch = true }
                }
                moved = true
            }
            if (!moved) return
        }
    }

    // ------------------------------------------------------------------ terrain

    private var terrainCache: TerrainGrid? = null
    private var terrainChecked = -1L
    var terrainColors: Array<Color3> = TerrainMaterials.DEFAULT_COLORS; private set

    /** The playtest's voxel terrain, or null if it has none (looked up again a few times a second). */
    fun terrainGrid(): TerrainGrid? {
        val now = System.currentTimeMillis() / 250
        if (now != terrainChecked) {
            terrainChecked = now
            val t = TerrainStore.terrainOf(dm)
            terrainCache = t?.let { TerrainStore.of(it) }?.takeIf { it.chunks.isNotEmpty() }
            terrainColors = TerrainMeshes.colorsFor(t)
        }
        return terrainCache
    }

    /** True while the character's middle is in terrain water. */
    private fun inWater(): Boolean {
        val g = terrainGrid() ?: return false
        val mid = Vector3(pos.x, pos.y + (charMinY + charMaxY) / 2f, pos.z)
        return TerrainMeshes.waterAt(g, mid)
    }

    private var floorMu = 0.3f
    private var jumpRise = false
    private var hadFloor = false

    private fun restHeight(): Float {
        val rs = hrp?.let { PartData.size(it) } ?: Vector3(2f, 2f, 1f)
        val hip = (humanoid?.get("HipHeight") as? Number)?.toFloat()
        return if (isR15 && hip != null) hip + rs.y / 2f else -charMinY
    }

    private fun findFloor(held: Boolean): Float? {
        val rs = (hrp?.let { PartData.size(it) } ?: Vector3(2f, 2f, 1f)).let { Vector3(maxOf(it.x, 0.05f), maxOf(it.y, 0.05f), maxOf(it.z, 0.05f)) }
        val sx = rs.x * 0.4f; val sy = rs.y * 0.4f; val sz = rs.z * 0.4f
        val hip = restHeight() - rs.y / 2f
        val len = 0.3f * (hip + rs.y) + hip * (if (held) 1.5f else 1.1f)
        val yaw = renderFacing
        val ax = Vector3(cos(yaw), 0f, -sin(yaw)); val az = Vector3(sin(yaw), 0f, cos(yaw))
        val reach = maxOf(rs.x, rs.z) + 1f
        val lo = Vector3(pos.x - reach, pos.y - sy - len - 0.5f, pos.z - reach)
        val hi = Vector3(pos.x + reach, pos.y, pos.z + reach)
        val cands = query(lo, hi).filter { it["CanCollide"] != false && !isCharacterPart(it) && !blockedByGroup(it) }
        val tg = terrainGrid()
        val down = Vector3(0f, -1f, 0f)
        fun cast(o: Vector3): Float? {
            var t: Float? = null
            val skip = ArrayList<Instance>()
            for (i in 0 until 4) {
                val h = Geometry.raycast(cands, o, down, len) { p -> skip.none { it === p } } ?: break
                if (h.t <= 1e-5f) { skip.add(h.part); continue }
                t = h.t
                break
            }
            if (tg != null) {
                val th = TerrainMeshes.raycast(tg, terrainColors, o, down, len)
                if (th != null && th.first > 1e-5f) { val cur = t; if (cur == null || th.first < cur) t = th.first }
            }
            val fin = t
            return if (fin == null) null else o.y - fin
        }
        val ys = ArrayList<Float>()
        val base = Vector3(pos.x, pos.y - sy, pos.z)
        for (k in floatArrayOf(0f, 1f, -1f, 2f, -2f)) {
            if (kotlin.math.abs(k) > 1f && ys.isEmpty()) break
            cast(base + az * (k * sz))?.let { ys.add(it) }
        }
        if (ys.isEmpty()) {
            for ((cx, cz) in listOf(1f to -1f, 1f to 1f, -1f to -1f, -1f to 1f)) {
                cast(base + ax * (cx * sx) + az * (cz * sz))?.let { ys.add(it) }
            }
        }
        if (ys.isEmpty()) return null
        return ys.sum() / ys.size
    }
    private var lastGroundedAt = -10.0

    private fun approachWalk(cx: Float, cz: Float, wx: Float, wz: Float, dt: Float, onFloor: Boolean): Pair<Float, Float> {
        val n = ceil(dt * 240f).toInt().coerceIn(1, 16)
        val h = dt / n
        var x = cx; var z = cz
        repeat(n) {
            val dx = wx - x; val dz = wz - z
            val len = sqrt(dx * dx + dz * dz)
            if (onFloor) {
                val step = (0.6f + 2270f * floorMu) * h
                if (len <= step) { x = wx; z = wz } else { x += dx / len * step; z += dz / len * step }
            } else {
                var ax = dx * 150f; var az = dz * 150f
                val al = sqrt(ax * ax + az * az)
                if (al > 143f) { ax *= 143f / al; az *= 143f / al }
                x += ax * h; z += az * h
            }
        }
        return x to z
    }

    private fun charGroup(): String = ((hrp?.get("CollisionGroup") as? String) ?: "Default")

    private fun blockedByGroup(p: Instance): Boolean {
        val g = (p["CollisionGroup"] as? String) ?: "Default"
        return !CollisionGroupTable.shared.groupsCollide(charGroup(), g)
    }

    private fun isCharacterPart(p: Instance): Boolean {
        val ch = character ?: return false
        return p.isDescendantOf(ch)
    }

    /** First person hides your own character (like LocalTransparencyModifier), but not held tools. */
    fun hiddenInFirstPerson(p: Instance): Boolean {
        if (!firstPerson) return false
        val ch = character ?: return false
        if (!p.isDescendantOf(ch)) return false
        val t = equipped
        return t == null || !p.isDescendantOf(t)
    }

    // ------------------------------------------------------------------ physics bodies & grid

    private fun cellKey(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)

    private fun cellRange(a: Vector3, b: Vector3): IntArray = intArrayOf(
        floor(a.x / CELL).toInt(), floor(a.y / CELL).toInt(), floor(a.z / CELL).toInt(),
        floor(b.x / CELL).toInt(), floor(b.y / CELL).toInt(), floor(b.z / CELL).toInt(),
    )

    private fun insertGrid(p: Instance) {
        val (a, b) = Geometry.partAabb(p)
        val r = cellRange(a, b)
        val count = (r[3] - r[0] + 1).toLong() * (r[4] - r[1] + 1) * (r[5] - r[2] + 1)
        if (count > 512) { bigParts.add(p); cellsOf[p] = LongArray(0); return }
        val keys = LongArray(count.toInt())
        var i = 0
        for (x in r[0]..r[3]) for (y in r[1]..r[4]) for (z in r[2]..r[5]) {
            val k = cellKey(x, y, z)
            grid.getOrPut(k) { ArrayList(4) }.add(p)
            keys[i++] = k
        }
        cellsOf[p] = keys
    }

    private fun removeGrid(p: Instance) {
        val keys = cellsOf.remove(p) ?: return
        if (keys.isEmpty()) bigParts.remove(p)
        for (k in keys) grid[k]?.remove(p)
    }

    private fun updateGrid(p: Instance) { removeGrid(p); insertGrid(p) }

    private fun query(a: Vector3, b: Vector3, withBodies: Boolean = false): List<Instance> {
        val r = cellRange(a, b)
        val out = ArrayList<Instance>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        for (p in bigParts) if (seen.add(p)) out.add(p)
        if ((r[3] - r[0] + 1).toLong() * (r[4] - r[1] + 1) * (r[5] - r[2] + 1) <= 4096) {
            for (x in r[0]..r[3]) for (y in r[1]..r[4]) for (z in r[2]..r[5]) {
                grid[cellKey(x, y, z)]?.let { for (p in it) if (seen.add(p)) out.add(p) }
            }
        }
        // Moving (unanchored) parts aren't in the grid; the character checks them directly
        // (only bodies whose bounds reach the query box).
        if (withBodies) for (bd in bodies) {
            if (bd.hasBox && !Geometry.aabbOverlap(a, b, bd.min, bd.max, 0.5f)) continue
            for (p in bd.parts) if (seen.add(p)) out.add(p)
        }
        return out.filter { p ->
            val (pa, pb) = Geometry.partAabb(p)
            Geometry.aabbOverlap(a, b, pa, pb, 0.05f)
        }
    }

    private fun rebuildPhysics() {
        physicsDirty = false
        val oldVel = IdentityHashMap<Instance, Vector3>()
        val oldSleep = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        for (b in bodies) for (p in b.parts) { oldVel[p] = b.vel; if (b.sleeping) oldSleep.add(p) }
        bodies.clear(); bodyOf.clear(); statics.clear(); grid.clear(); cellsOf.clear(); bigParts.clear()
        val groups = LinkedHashMap<Any, ArrayList<Instance>>()
        val ws = dm.workspace
        val all = studio.renderParts()
        val wsAll = ws.descendants()
        prompts = ArrayList(wsAll.filter { it.className == "ProximityPrompt" })
        // Assemblies are parts joined by welds/motors/snaps (like Roblox), not whole models: an
        // unwelded unanchored part in a model is its own body; anything welded to an anchored
        // part stays put.
        val parent = IdentityHashMap<Instance, Instance>()
        fun find(x: Instance): Instance { var r = x; while (true) { val p = parent[r] ?: return r; if (p === r) return r; r = p } }
        fun union(a: Instance, b: Instance) { val ra = find(a); val rb = find(b); if (ra !== rb) parent[ra] = rb }
        val simulated = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        for (p in all) {
            if (isCharacterPart(p) || p.className == "Terrain") continue
            if (equipped != null && p.isDescendantOf(equipped!!)) continue
            simulated.add(p)
        }
        for (j in wsAll) {
            val a: Instance?; val b: Instance?
            if (j.isA("JointInstance")) { a = j["Part0"] as? Instance; b = j["Part1"] as? Instance }
            else if (j.className == "WeldConstraint" && j["Enabled"] != false) { a = (j["Part0Internal"] ?: j["Part0"]) as? Instance; b = (j["Part1Internal"] ?: j["Part1"]) as? Instance }
            else if (j.className in HOLD_TOGETHER && j["Enabled"] != false) {
                // Hinges, ball sockets, sliders... need rotation, which isn't simulated: keep the
                // parts together (cars keep their wheels). Springs only when both ends move
                // (suspension); a spring hanging from something anchored is simulated.
                a = attPart(j["Attachment0"] as? Instance); b = attPart(j["Attachment1"] as? Instance)
                if (j.className == "SpringConstraint" && (a?.get("Anchored") == true || b?.get("Anchored") == true)) continue
            } else continue
            if (a != null && b != null && a in simulated && b in simulated) union(a, b)
        }
        for (p in simulated) groups.getOrPut(find(p)) { ArrayList() }.add(p)
        for ((_, parts) in groups) {
            if (parts.any { it["Anchored"] == true }) { statics.addAll(parts); continue }
            val b = Body(parts, oldVel[parts.first()] ?: Vector3.ZERO)
            measure(b)
            // Things that were asleep stay asleep (a rebuild for some new weld elsewhere used to
            // wake every vehicle on the map).
            if (parts.all { it in oldSleep }) b.sleeping = true
            bodies.add(b)
            for (p in parts) bodyOf[p] = b
        }
        for (p in statics) insertGrid(p)
        movers.clear()
        for (d in wsAll) if (d.className in MOVER_CLASSES && d["Enabled"] != false) movers.add(d)
    }

    // ------------------------------------------------------------------ materials, constraints & body movers

    /** Roblox's default density / friction / elasticity per Material (enum value). */
    private fun physProps(mat: Int): FloatArray {
        val p = PartPhysics.forMaterial(com.leaf.lemonade.model.Enums.itemName("Material", mat))
        return floatArrayOf(p.density, p.friction, p.elasticity)
    }

    /** Mass, friction and bounciness of a body from its parts (CustomPhysicalProperties win). */
    private fun measure(b: Body) {
        var mass = 0f; var f = 0f; var e = 0f; var w = 0f
        for (p in b.parts) {
            val s = PartData.size(p)
            val vol = (s.x * s.y * s.z).coerceAtLeast(0.001f)
            val d = physProps(PartData.material(p))
            val cp = p["CustomPhysicalProperties"] as? PhysicalProps
            val dens = cp?.takeIf { it.custom }?.density ?: d[0]
            val fr = cp?.takeIf { it.custom }?.friction ?: d[1]
            val el = cp?.takeIf { it.custom }?.elasticity ?: d[2]
            if (p["Massless"] != true) mass += vol * dens
            f += fr * vol; e += el * vol; w += vol
        }
        b.mass = mass.coerceAtLeast(0.01f)
        if (w > 0f) { b.friction = f / w; b.elasticity = e / w }
        // Its bounds right away (sleeping bodies may not step for a long time).
        var lo = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); var hi = -lo
        for (p in b.parts) {
            val (a0, a1) = Geometry.partAabb(p)
            lo = Vector3(minOf(lo.x, a0.x), minOf(lo.y, a0.y), minOf(lo.z, a0.z)); hi = Vector3(maxOf(hi.x, a1.x), maxOf(hi.y, a1.y), maxOf(hi.z, a1.z))
        }
        if (lo.x <= hi.x) { b.min = lo; b.max = hi; b.hasBox = true }
    }

    private fun attPart(a: Instance?): Instance? = a?.parent?.takeIf { it.isA("BasePart") }

    private fun attWorld(a: Instance?): Vector3? {
        val part = attPart(a) ?: return null
        return PartData.cframe(part) * ((a!!["CFrame"] as? CFrame)?.position ?: Vector3.ZERO)
    }

    private fun attAxis(a: Instance?): CFrame? {
        val part = attPart(a) ?: return null
        return PartData.cframe(part) * ((a!!["CFrame"] as? CFrame) ?: CFrame.IDENTITY)
    }

    /** What a constraint end is attached to: a simulated body, the player's character, or nothing that moves. */
    private inner class End(val part: Instance?) {
        val body: Body? = part?.let { bodyOf[it] }
        val isChar = body == null && part != null && isCharacterPart(part)
        val invMass: Float get() = when { body != null -> 1f / body.mass; isChar -> 1f / 15f; else -> 0f }
        var v: Vector3
            get() = body?.vel ?: if (isChar) vel else Vector3.ZERO
            set(x) { if (body != null) { body.vel = x; body.sleeping = false; body.restTime = 0f } else if (isChar) vel = x }
        fun move(d: Vector3) {
            if (body != null) { for (p in body.parts) p.set("CFrame", PartData.cframe(p) + d, false); body.sleeping = false; body.restTime = 0f }
            else if (isChar) pos += d
        }
    }

    private fun num(i: Instance, n: String, d: Float) = (i[n] as? Number)?.toFloat() ?: d
    private fun vec(i: Instance, n: String) = i[n] as? Vector3 ?: Vector3.ZERO

    /** Forces and velocity targets from body movers and force-type constraints (before integration). */
    private fun applyMovers(dt: Float) {
        if (movers.isEmpty()) return
        val g = Vector3(0f, -gravity * dt, 0f)
        for (m in movers) {
            if (m.parent == null || m["Enabled"] == false) continue
            when (m.className) {
                "BodyForce", "BodyThrust", "BodyVelocity", "BodyPosition", "BodyAngularVelocity", "BodyGyro", "RocketPropulsion" -> {
                    val e = End(m.parent?.takeIf { it.isA("BasePart") })
                    if (e.invMass == 0f) continue
                    val mass = 1f / e.invMass
                    when (m.className) {
                        "BodyForce", "BodyThrust" -> {
                            val f = if (m.className == "BodyThrust") PartData.cframe(e.part!!).vectorToWorld(vec(m, "Force")) else vec(m, "Force")
                            e.v = e.v + f * (dt / mass)
                        }
                        "BodyVelocity" -> e.v = drive(e.v, e.v + g, vec(m, "Velocity"), vec(m, "MaxForce"), mass, dt)
                        "BodyPosition" -> {
                            // Critically damped pull toward Position, limited per axis by MaxForce.
                            val cur = PartData.cframe(e.part!!).position
                            val p = num(m, "P", 10000f); val d = num(m, "D", 1250f)
                            val want = (vec(m, "Position") - cur) * (p / max(d, 1f))
                            e.v = drive(e.v, e.v + g, want, vec(m, "MaxForce"), mass, dt)
                        }
                        "RocketPropulsion" -> {
                            val tgt = (m["Target"] as? Instance)?.takeIf { it.isA("BasePart") }?.let { PartData.cframe(it).position } ?: vec(m, "TargetOffset")
                            val cur = PartData.cframe(e.part!!).position
                            val dir = (tgt - cur)
                            val want = if (dir.magnitude > 0.5f) dir.unit * num(m, "MaxSpeed", 30f) else Vector3.ZERO
                            val mf = num(m, "MaxThrust", 4000f)
                            e.v = drive(e.v, e.v + g, want, Vector3(mf, mf, mf), mass, dt)
                        }
                    }
                }
                "VectorForce" -> {
                    val a0 = m["Attachment0"] as? Instance
                    val e = End(attPart(a0)); if (e.invMass == 0f) continue
                    val rel = (m["RelativeTo"] as? EnumValue)?.value ?: 0
                    val f = vec(m, "Force").let { if (rel == 2) it else (attAxis(if (rel == 1) m["Attachment1"] as? Instance ?: a0 else a0)?.vectorToWorld(it) ?: it) }
                    e.v = e.v + f * (dt * e.invMass)
                }
                "LineForce" -> {
                    val p0 = attWorld(m["Attachment0"] as? Instance) ?: continue
                    val p1 = attWorld(m["Attachment1"] as? Instance) ?: continue
                    val e = End(attPart(m["Attachment0"] as? Instance)); if (e.invMass == 0f) continue
                    val d = p1 - p0; val len = d.magnitude; if (len < 1e-3f) continue
                    var mag = num(m, "Magnitude", 1000f)
                    if (m["InverseSquareLaw"] == true) mag /= (len * len).coerceAtLeast(1f)
                    e.v = e.v + d.unit * (mag * dt * e.invMass)
                }
                "LinearVelocity" -> {
                    val a0 = m["Attachment0"] as? Instance
                    val e = End(attPart(a0)); if (e.invMass == 0f) continue
                    val mode = (m["VelocityConstraintMode"] as? EnumValue)?.value ?: 2
                    val rel = (m["RelativeTo"] as? EnumValue)?.value ?: 2
                    val frame = if (rel == 2) null else attAxis(if (rel == 1) m["Attachment1"] as? Instance ?: a0 else a0)
                    var want = when (mode) {
                        0 -> (m["LineDirection"] as? Vector3 ?: Vector3(1f, 0f, 0f)).unit * num(m, "LineVelocity", 0f)
                        1 -> { val pv = m["PlaneVelocity"] as? Vector2 ?: Vector2(0f, 0f); Vector3(pv.x, 0f, pv.y) }
                        else -> vec(m, "VectorVelocity")
                    }
                    if (frame != null) want = frame.vectorToWorld(want)
                    val mf = if (m["ForceLimitsEnabled"] == false) Float.MAX_VALUE else num(m, "MaxForce", 1000f)
                    val pred = e.v + g
                    // Line mode only drives along its line; the other axes stay free.
                    if (mode == 0) {
                        val dir = (frame?.vectorToWorld(m["LineDirection"] as? Vector3 ?: Vector3(1f, 0f, 0f)) ?: (m["LineDirection"] as? Vector3 ?: Vector3(1f, 0f, 0f))).unit
                        val dv = want.dot(dir) - pred.dot(dir)
                        val lim = mf * dt * e.invMass
                        e.v = e.v + dir * dv.coerceIn(-lim, lim)
                    } else {
                        var dv = want - pred
                        val lim = mf * dt * e.invMass
                        if (dv.magnitude > lim) dv = dv.unit * lim
                        e.v = e.v + dv
                    }
                }
                "AlignPosition" -> {
                    val a0 = m["Attachment0"] as? Instance
                    val e = End(attPart(a0)); if (e.invMass == 0f) continue
                    val p0 = attWorld(a0) ?: continue
                    val one = ((m["Mode"] as? EnumValue)?.value ?: 1) == 0
                    val target = if (one) vec(m, "Position") else attWorld(m["Attachment1"] as? Instance) ?: continue
                    val delta = target - p0
                    if (m["RigidityEnabled"] == true) { e.move(delta); e.v = Vector3.ZERO; continue }
                    var want = delta * num(m, "Responsiveness", 10f)
                    val mv = num(m, "MaxVelocity", Float.MAX_VALUE)
                    if (want.magnitude > mv) want = want.unit * mv
                    var dv = want - (e.v + g)
                    val lim = num(m, "MaxForce", 10000f) * dt * e.invMass
                    if (dv.magnitude > lim) dv = dv.unit * lim
                    e.v = e.v + dv
                }
                "SpringConstraint" -> {
                    val a0 = m["Attachment0"] as? Instance; val a1 = m["Attachment1"] as? Instance
                    val p0 = attWorld(a0) ?: continue; val p1 = attWorld(a1) ?: continue
                    val e0 = End(attPart(a0)); val e1 = End(attPart(a1))
                    if (e0.body != null && e0.body === e1.body) continue
                    val wsum = e0.invMass + e1.invMass; if (wsum == 0f) continue
                    val d = p1 - p0; val len = d.magnitude; if (len < 1e-4f) continue
                    val n = d / len
                    val relV = (e1.v - e0.v).dot(n)
                    var f = num(m, "Stiffness", 0f) * (len - num(m, "FreeLength", 1f)) + num(m, "Damping", 0f) * relV
                    val mf = num(m, "MaxForce", Float.MAX_VALUE)
                    f = f.coerceIn(-mf, mf)
                    // Implicit-ish: never apply more than the impulse that would stop the stretch this step.
                    val imp = f * dt
                    e0.v = e0.v + n * (imp * e0.invMass)
                    e1.v = e1.v - n * (imp * e1.invMass)
                }
            }
        }
    }

    /** Moves velocity [v] (predicted [pred] after gravity) toward [want], limited per axis by [maxF]. */
    private fun drive(v: Vector3, pred: Vector3, want: Vector3, maxF: Vector3, mass: Float, dt: Float): Vector3 {
        fun ax(c: Float, p: Float, w: Float, f: Float): Float { val lim = f * dt / mass; return c + (w - p).coerceIn(-lim, lim) }
        return Vector3(ax(v.x, pred.x, want.x, maxF.x), ax(v.y, pred.y, want.y, maxF.y), ax(v.z, pred.z, want.z, maxF.z))
    }

    /** Distance limits (ropes, rods, spring limits) after the bodies moved: positions and velocities. */
    private fun solveDistanceConstraints() {
        if (movers.isEmpty()) return
        for (iter in 0 until 4) for (m in movers) {
            if (m.parent == null || m["Enabled"] == false) continue
            val rope = m.className == "RopeConstraint"; val rod = m.className == "RodConstraint"
            val spring = m.className == "SpringConstraint" && m["LimitsEnabled"] == true
            if (!rope && !rod && !spring) continue
            val a0 = m["Attachment0"] as? Instance; val a1 = m["Attachment1"] as? Instance
            val p0 = attWorld(a0) ?: continue; val p1 = attWorld(a1) ?: continue
            val e0 = End(attPart(a0)); val e1 = End(attPart(a1))
            if (e0.body != null && e0.body === e1.body) continue
            val wsum = e0.invMass + e1.invMass; if (wsum == 0f) continue
            val d = p1 - p0; val len = d.magnitude; if (len < 1e-4f) continue
            val n = d / len
            val (lo, hi) = when {
                rope -> {
                    // Winch: the rope reels toward WinchTarget at WinchSpeed.
                    if (m["WinchEnabled"] == true && iter == 0) {
                        val cur = num(m, "Length", 5f); val tgt = num(m, "WinchTarget", cur); val sp = num(m, "WinchSpeed", 2f) / 60f
                        m.set("Length", if (abs(tgt - cur) <= sp) tgt else cur + sp * Math.signum(tgt - cur), false)
                    }
                    0f to num(m, "Length", 5f)
                }
                rod -> num(m, "Length", 5f).let { it to it }
                else -> num(m, "MinLength", 0f) to num(m, "MaxLength", 5f)
            }
            val err = when { len > hi -> len - hi; len < lo -> len - lo; else -> 0f }
            if (err == 0f) continue
            // Move each end by its share of the error (anchored ends don't move).
            e0.move(n * (err * e0.invMass / wsum))
            e1.move(n * (-err * e1.invMass / wsum))
            // Remove the velocity that keeps stretching (or squashing) it; ropes can bounce (Restitution).
            val relV = (e1.v - e0.v).dot(n)
            if ((err > 0f && relV > 0f) || (err < 0f && relV < 0f)) {
                val bounce = if (rope) 1f + num(m, "Restitution", 0f) else 1f
                val imp = relV * bounce / wsum
                e0.v = e0.v + n * (imp * e0.invMass)
                e1.v = e1.v - n * (imp * e1.invMass)
            }
        }
    }

    private fun stepBodies(dt: Float) {
        val g = gravity
        val destroyY = (dm.workspace["FallenPartsDestroyHeight"] as? Number)?.toFloat() ?: -500f
        writing = true
        try {
            applyMovers(dt)
            for (b in bodies.toList()) {
                if (b.sleeping) continue
                if (b.parts.any { it.parent == null }) { bodies.remove(b); continue }
                b.vel = Vector3(b.vel.x, b.vel.y - g * dt, b.vel.z)
                val delta = b.vel * dt
                // Physics moves don't fire Changed in Roblox either (and notifying every part of
                // every vehicle each step was a big cost); the renderer sees Instance.version.
                for (p in b.parts) p.set("CFrame", PartData.cframe(p) + delta, false)
                var push = Vector3.ZERO
                var other: Instance? = null
                // Broad phase once per body (a car has hundreds of parts but touches only the
                // road), then each part only against what overlaps it.
                val boxes = ArrayList<Pair<Vector3, Vector3>>(b.parts.size)
                var bmin = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); var bmax = -bmin
                for (p in b.parts) {
                    val bx = Geometry.partAabb(p); boxes.add(bx)
                    if (p["CanCollide"] == false) continue
                    bmin = Vector3(minOf(bmin.x, bx.first.x), minOf(bmin.y, bx.first.y), minOf(bmin.z, bx.first.z))
                    bmax = Vector3(max(bmax.x, bx.second.x), max(bmax.y, bx.second.y), max(bmax.z, bx.second.z))
                }
                val near = if (bmin.x <= bmax.x) query(bmin, bmax).filter { it["CanCollide"] != false }.map { it to Geometry.partAabb(it) } else emptyList()
                if (near.isNotEmpty()) for ((i, p) in b.parts.withIndex()) {
                    if (p["CanCollide"] == false) continue
                    val (a0, a1) = boxes[i]
                    var shape: ConvexShape? = null
                    for ((s, sb) in near) {
                        if (!Geometry.aabbOverlap(a0, a1, sb.first, sb.second, 0.05f)) continue
                        val sh = shape ?: Geometry.shapeOf(p).also { shape = it }
                        val m = Geometry.contact(sh, a0, a1, s)?.first ?: continue
                        if (m.magnitude > push.magnitude) { push = m; other = s }
                        touchBegin(p, s)
                    }
                }
                // Remember where the body is (the character's collision query skips far bodies).
                b.min = bmin + (if (push.magnitude > 1e-4f) push else Vector3.ZERO); b.max = bmax + (if (push.magnitude > 1e-4f) push else Vector3.ZERO); b.hasBox = bmin.x <= bmax.x
                if (push.magnitude > 1e-4f) {
                    for (p in b.parts) p.set("CFrame", PartData.cframe(p) + push, false)
                    val n = push.unit
                    val vn = b.vel.dot(n)
                    // Roblox mixes the two surfaces' friction and elasticity (equal weights: the average).
                    val sp = other?.let { s -> (s["CustomPhysicalProperties"] as? PhysicalProps)?.takeIf { it.custom }?.let { floatArrayOf(it.density, it.friction, it.elasticity) } ?: physProps(PartData.material(s)) }
                    val fr = ((sp?.get(1) ?: 0.3f) + b.friction) / 2f
                    val el = ((sp?.get(2) ?: 0.5f) + b.elasticity) / 2f
                    if (vn < 0) {
                        // Bounce off real impacts only; a body resting on something gets one
                        // step of gravity per frame (big at low frame rates), which must not bounce.
                        val bounce = if (-vn > g * dt * 2f + 6f) el else 0f
                        b.vel -= n * (vn * (1f + bounce))
                        // Coulomb friction: the tangential speed drops by at most mu * normal impulse.
                        val vt = b.vel - n * b.vel.dot(n)
                        val st = vt.magnitude
                        if (st > 1e-4f) {
                            val drop = fr * -vn
                            b.vel -= if (drop >= st) vt else vt * (drop / st)
                        }
                    }
                    // Asleep after ~0.4 s of rest (time, not frames: slow devices settle as fast).
                    if (b.vel.magnitude < 0.5f + g * dt * 0.1f) { b.restTime += dt; if (b.restTime > 0.4f) { b.sleeping = true; b.vel = Vector3.ZERO } } else b.restTime = 0f
                } else b.restTime = 0f
                if (b.parts.all { PartData.cframe(it).y < destroyY }) { b.parts.forEach { it.destroy() }; bodies.remove(b) }
            }
            solveDistanceConstraints()
        } finally { writing = false }
    }

    // ------------------------------------------------------------------ touches

    private fun touchBegin(a: Instance, b: Instance) {
        if (a["CanTouch"] == false || b["CanTouch"] == false) return
        val set = touching.getOrPut(a) { Collections.newSetFromMap(IdentityHashMap()) }
        if (set.add(b)) {
            touching.getOrPut(b) { Collections.newSetFromMap(IdentityHashMap()) }.add(a)
            rt.fire(a, "Touched", rt.wrap(b))
            rt.fire(b, "Touched", rt.wrap(a))
        }
    }

    private fun touchEnd(a: Instance, b: Instance) {
        if (touching[a]?.remove(b) == true) {
            touching[b]?.remove(a)
            rt.fire(a, "TouchEnded", rt.wrap(b))
            rt.fire(b, "TouchEnded", rt.wrap(a))
        }
    }

    fun touchingParts(p: Instance): List<Instance> = touching[p]?.toList() ?: emptyList()

    private fun checkCharacterTouches() {
        val ch = character ?: return
        if (dead) return
        val torso = ch.findFirstChild("Torso") ?: ch.findFirstChild("UpperTorso") ?: hrp ?: return
        val leg = ch.findFirstChild("Left Leg") ?: ch.findFirstChild("LeftFoot") ?: torso
        val (c, half) = charBox()
        val pad = Vector3(0.15f, 0.15f, 0.15f)
        val box = Geometry.aabbShape(c, half + pad)
        val now = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        for (p in query(c - half - pad, c + half + pad, true)) {
            if (isCharacterPart(p) || p["CanTouch"] == false) continue
            if (Geometry.contact(box, c - half - pad, c + half + pad, p) != null) now.add(p)
        }
        val prev = touching[torso]?.toList().orEmpty() + touching[leg]?.toList().orEmpty()
        for (p in now) {
            val limb = if (Geometry.partAabb(p).second.y < c.y - half.y * 0.4f) leg else torso
            if (touching[torso]?.contains(p) != true && touching[leg]?.contains(p) != true) touchBegin(limb, p)
        }
        for (p in prev) if (p !in now) { touchEnd(torso, p); touchEnd(leg, p) }
    }

    // ------------------------------------------------------------------ frame

    private val prof = FloatArray(8)
    private var profN = 0
    private var profT = 0f

    fun step(dt0: Float) {
        val dt = dt0.coerceIn(0f, 1f / 15f)
        time += dt
        val t0 = System.nanoTime()
        if (physicsDirty) rebuildPhysics()
        val t1 = System.nanoTime()
        val rs = runService
        rt.fire(rs, "RenderStepped", LuaValue.valueOf(dt.toDouble()))
        rt.fire(rs, "PreRender", LuaValue.valueOf(dt.toDouble()))
        rt.fire(rs, "Stepped", LuaValue.valueOf(time), LuaValue.valueOf(dt.toDouble()))
        rt.fire(rs, "PreSimulation", LuaValue.valueOf(dt.toDouble()))
        val t2 = System.nanoTime()
        if (physicsDirty) rebuildPhysics()
        if (avatarApplied != Avatar.version) lateAvatar()
        stepScriptTracks(dt)
        stepCharacter(dt)
        val t3 = System.nanoTime()
        stepBodies(dt)
        val t4 = System.nanoTime()
        checkCharacterTouches()
        updatePrompts()
        stepBubbles(dt)
        val t5 = System.nanoTime()
        rt.fire(rs, "PostSimulation", LuaValue.valueOf(dt.toDouble()))
        rt.fire(rs, "Heartbeat", LuaValue.valueOf(dt.toDouble()))
        val t6 = System.nanoTime()
        rt.step(dt.toDouble())
        val t7 = System.nanoTime()
        updateCamera(dt)
        val t8 = System.nanoTime()
        // Where playtest time goes (logcat only), to find what makes big games slow.
        val ts = longArrayOf(t0, t1, t2, t3, t4, t5, t6, t7, t8)
        for (i in 0 until 8) prof[i] += (ts[i + 1] - ts[i]) / 1e6f
        profN++; profT += dt
        if (profT > 5f) {
            val n = profN.toFloat()
            android.util.Log.i("MobileStudioGfx", "play: rebuild %.1f preEvents %.1f character %.1f bodies %.1f (%d) touches %.1f postEvents %.1f scripts %.1f camera %.1f".format(
                prof[0] / n, prof[1] / n, prof[2] / n, prof[3] / n, bodies.size, prof[4] / n, prof[5] / n, prof[6] / n, prof[7] / n))
            prof.fill(0f); profN = 0; profT = 0f
        }
    }

    private fun zoomLimits(): Pair<Float, Float> {
        val p = player
        val mn = (p?.get("CameraMinZoomDistance") as? Number)?.toFloat() ?: 0.5f
        val mx = (p?.get("CameraMaxZoomDistance") as? Number)?.toFloat() ?: 128f
        return mn.coerceIn(0.5f, 400f) to maxOf(mn, mx).coerceIn(0.5f, 400f)
    }

    private fun wrapAngle(a: Float): Float {
        var d = a
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }

    /** Camera like the PlayerModule's Classic/Follow camera: eased orbit, zoom limits, popper (no clipping into walls), first person. */
    private fun updateCamera(dt: Float) {
        val camInst = dm.workspace["CurrentCamera"] as? Instance
        val cam = studio.camera
        val type = (camInst?.get("CameraType") as? EnumValue)?.value ?: 5
        if (type == 6 && camInst != null) {
            (camInst["CFrame"] as? CFrame)?.let { cam.setFromCFrame(it) }
            firstPerson = false
        } else {
            val (mn, mx) = zoomLimits()
            val lockFirst = (player?.get("CameraMode") as? EnumValue)?.value == 1
            distTarget = if (lockFirst) mn else distTarget.coerceIn(mn, mx)
            if (keyTurn != 0f) { yawTarget += keyTurn * dt * 2.6f; lastCamInput = time }
            // Follow mode: gently swing behind the character while it walks sideways.
            if (Settings.cameraMode == "Follow" && !shiftLock && !firstPerson && time - lastCamInput > 0.8 && lastDir.magnitude > 0.2f && grounded) {
                val desired = atan2(-lastDir.x, -lastDir.z)
                val diff = wrapAngle(desired - yawTarget)
                if (abs(diff) < 2.2f) yawTarget += diff * (1f - exp(-0.9f * dt))
            }
            val smooth = Settings.cameraSmoothing
            val k = if (smooth) 1f - exp(-22f * dt) else 1f
            camYaw = if (shiftLock || firstPerson) yawTarget else camYaw + (yawTarget - camYaw) * k
            camPitch += (pitchTarget - camPitch) * k
            camDist += (distTarget - camDist) * (if (smooth) 1f - exp(-14f * dt) else 1f)
            firstPerson = lockFirst || camDist < 1.0f

            val root = hrp?.let { PartData.cframe(it).position } ?: pos
            val headOffset = if (isR15) 1.5f else 1.5f
            val target = root + Vector3(0f, headOffset, 0f)
            // Roblox's camera follows the character exactly (no lag when jumping or falling).
            subjectY = target.y
            cam.yaw = camYaw
            cam.pitch = camPitch
            val focus = Vector3(target.x, subjectY, target.z) + (if (shiftLock && !firstPerson) cam.right * 1.75f else Vector3.ZERO)
            var dist = camDist
            if (!firstPerson) {
                val back = -cam.forward
                val end = focus + back * dist
                val lo = Vector3(minOf(focus.x, end.x), minOf(focus.y, end.y), minOf(focus.z, end.z)) - Vector3(0.5f, 0.5f, 0.5f)
                val hi = Vector3(maxOf(focus.x, end.x), maxOf(focus.y, end.y), maxOf(focus.z, end.z)) + Vector3(0.5f, 0.5f, 0.5f)
                val cands = query(lo, hi)
                val hit = Geometry.raycast(cands, focus, back, dist) { p -> !isCharacterPart(p) && p["CanCollide"] != false && PartData.transparency(p) < 0.25f }
                var hitT = hit?.t ?: Float.MAX_VALUE
                // Hills and cave walls block the camera too.
                terrainGrid()?.let { g -> TerrainMeshes.raycast(g, terrainColors, focus, back, dist)?.let { if (it.first < hitT) hitT = it.first } }
                val allowed = if (hitT < Float.MAX_VALUE) (hitT - 0.4f).coerceAtLeast(0.5f) else dist
                // Pull in instantly, ease back out.
                occluded = if (allowed < occluded) allowed else approach(occluded, allowed, 6f, dt)
                dist = minOf(dist, occluded)
            } else occluded = dist
            cam.pos = focus - cam.forward * dist
            camInst?.set("CFrame", cam.cframe(), false)
            camInst?.set("Focus", CFrame.at(target), false)
        }
        (camInst?.get("FieldOfView") as? Number)?.let { cam.fov = it.toFloat().coerceIn(1f, 120f) }
    }

    private fun updatePrompts() {
        val root = hrp
        if (root == null || dead || prompts.isEmpty()) { activePrompt = null; return }
        val p = PartData.cframe(root).position
        var best: Instance? = null; var bd = Float.MAX_VALUE
        for (pr in prompts) {
            if (pr.parent == null || pr["Enabled"] == false) continue
            val host = pr.parent ?: continue
            val hp = when {
                host.isA("BasePart") -> PartData.cframe(host).position
                host.className == "Attachment" -> host.parent?.let { PartData.cframe(it) * ((host["CFrame"] as? CFrame)?.position ?: Vector3.ZERO) }
                host.isA("Model") -> rt.api.modelPivot(host).position
                else -> null
            } ?: continue
            val d = (hp - p).magnitude
            val max = (pr["MaxActivationDistance"] as? Number)?.toFloat() ?: 10f
            if (d <= max && d < bd) { bd = d; best = pr }
        }
        activePrompt = best
    }

    // ------------------------------------------------------------------ input from UI (called with Studio.lock held)

    fun requestJump() { jumpRequested = true }

    fun cameraDrag(dx: Float, dy: Float) {
        val s = 0.0075f * Settings.playCameraSensitivity
        yawTarget -= dx * s
        pitchTarget = (pitchTarget - dy * s).coerceIn(-1.4f, 1.4f)
        lastCamInput = time
    }

    fun cameraZoom(factor: Float) {
        val (mn, mx) = zoomLimits()
        distTarget = (distTarget / factor).coerceIn(mn, mx)
    }

    val shiftLockAllowed: Boolean get() = Settings.shiftLockSwitch && dm.findService("StarterPlayer")?.get("EnableMouseLockOption") != false

    fun toggleShiftLock() {
        if (!shiftLockAllowed) { shiftLock = false; return }
        shiftLock = !shiftLock
    }

    /** Points the player's Mouse at screen position (x, y) px: Hit, Target (honoring TargetFilter), X/Y, UnitRay. */
    private fun aimMouse(xPx: Float, yPx: Float): RayHit? {
        val d = studio.density
        lastTouchDp = Vector2(xPx / d, yPx / d)
        val (o, dir) = studio.view.screenRay(xPx, yPx)
        val m = mouseTable
        val filter = (m?.get("TargetFilter")?.takeIf { it.isuserdata() }?.touserdata() as? Instance)
        val hit = Geometry.raycast(studio.renderParts(), o, dir, 2000f) {
            !isCharacterPart(it) && it["Transparency"] != 1f && (filter == null || (it !== filter && !it.isDescendantOf(filter)))
        }
        if (m != null) {
            val hitPos = hit?.position ?: (o + dir * 1000f)
            m.set("Hit", LuaTypes.wrap(CFrame.lookAt(o, hitPos).withPosition(hitPos)))
            m.set("Target", rt.wrap(hit?.part))
            m.set("TargetSurface", LuaTypes.wrap(EnumItemV("NormalId", "Top", 1)))
            m.set("X", LuaValue.valueOf((xPx / d).toDouble())); m.set("Y", LuaValue.valueOf((yPx / d).toDouble()))
            m.set("UnitRay", LuaTypes.wrap(Ray(o, dir)))
            m.set("Origin", LuaTypes.wrap(CFrame.lookAt(o, o + dir)))
            m.set("ViewSizeX", LuaValue.valueOf(studio.viewportSizeDp().x.toDouble()))
            m.set("ViewSizeY", LuaValue.valueOf(studio.viewportSizeDp().y.toDouble()))
        }
        return hit
    }

    private fun touchInput(xPx: Float, yPx: Float, state: String, stateValue: Int, delta: Vector2 = Vector2(0f, 0f)): LuaTable {
        val d = studio.density
        return LuaTable().apply {
            set("UserInputType", LuaTypes.wrap(EnumItemV("UserInputType", "Touch", 7)))
            set("UserInputState", LuaTypes.wrap(EnumItemV("UserInputState", state, stateValue)))
            set("KeyCode", LuaTypes.wrap(EnumItemV("KeyCode", "Unknown", 0)))
            set("Position", LuaTypes.wrap(Vector3(xPx / d, yPx / d, 0f)))
            set("Delta", LuaTypes.wrap(Vector3(delta.x / d, delta.y / d, 0f)))
        }
    }

    private val uisService get() = dm.getService("UserInputService").also { it.archivable = false }
    private var lastPointer: Vector2? = null

    /** A finger went down on the 3D view (not on a GUI or on-screen control). */
    fun pointerDown(xPx: Float, yPx: Float) {
        aimMouse(xPx, yPx)
        lastPointer = Vector2(xPx, yPx)
        mouseTable?.let { rt.fire(it, "Button1Down") }
        rt.fire(uisService, "InputBegan", touchInput(xPx, yPx, "Begin", 0), LuaValue.FALSE)
        rt.fire(uisService, "TouchStarted", touchInput(xPx, yPx, "Begin", 0), LuaValue.FALSE)
    }

    fun pointerMove(xPx: Float, yPx: Float) {
        aimMouse(xPx, yPx)
        val last = lastPointer ?: Vector2(xPx, yPx)
        lastPointer = Vector2(xPx, yPx)
        mouseTable?.let { rt.fire(it, "Move") }
        val inp = touchInput(xPx, yPx, "Change", 1, Vector2(xPx - last.x, yPx - last.y))
        rt.fire(uisService, "InputChanged", inp, LuaValue.FALSE)
        rt.fire(uisService, "TouchMoved", inp, LuaValue.FALSE)
    }

    fun pointerUp(xPx: Float, yPx: Float) {
        aimMouse(xPx, yPx)
        lastPointer = null
        mouseTable?.let { rt.fire(it, "Button1Up") }
        rt.fire(uisService, "InputEnded", touchInput(xPx, yPx, "End", 2), LuaValue.FALSE)
        rt.fire(uisService, "TouchEnded", touchInput(xPx, yPx, "End", 2), LuaValue.FALSE)
    }

    fun tap(xPx: Float, yPx: Float) {
        val d = studio.density
        val (o, _) = studio.view.screenRay(xPx, yPx)
        val hit = aimMouse(xPx, yPx)
        val uis = uisService
        rt.fire(uis, "TouchTap", LuaTable().also { it.set(1, LuaTypes.wrap(Vector2(xPx / d, yPx / d))) }, LuaValue.FALSE)
        hit?.let { h ->
            rt.fire(uis, "TouchTapInWorld", LuaTypes.wrap(Vector2(xPx / d, yPx / d)), LuaValue.FALSE)
            var a: Instance? = h.part
            while (a != null && a !== dm.workspace) {
                val cd = a.findFirstChildOfClass("ClickDetector")
                if (cd != null) {
                    val maxD = (cd["MaxActivationDistance"] as? Number)?.toFloat() ?: 32f
                    val from = hrp?.let { PartData.cframe(it).position } ?: o
                    if ((h.position - from).magnitude <= maxD) rt.fire(cd, "MouseClick", rt.wrap(player))
                    break
                }
                a = a.parent
            }
        }
        equipped?.let { rt.fire(it, "Activated") }
    }

    fun triggerPrompt() {
        val pr = activePrompt ?: return
        rt.fire(pr, "Triggered", rt.wrap(player))
        rt.fire(dm.getService("ProximityPromptService").also { it.archivable = false }, "PromptTriggered", rt.wrap(pr), rt.wrap(player))
        rt.fire(pr, "TriggerEnded", rt.wrap(player))
    }

    fun mouseValue(): LuaValue {
        mouseTable?.let { return it }
        val m = LuaTable()
        m.set("Hit", LuaTypes.wrap(CFrame.IDENTITY)); m.set("Target", LuaValue.NIL)
        m.set("X", 0); m.set("Y", 0)
        m.set("ViewSizeX", LuaValue.valueOf(studio.viewportSizeDp().x.toDouble()))
        m.set("ViewSizeY", LuaValue.valueOf(studio.viewportSizeDp().y.toDouble()))
        m.set("Icon", "")
        m.set("TargetFilter", LuaValue.NIL)
        m.set("Origin", LuaTypes.wrap(studio.camera.cframe()))
        m.set("UnitRay", LuaTypes.wrap(Ray(studio.camera.pos, studio.camera.forward)))
        m.set("ClassName", "PlayerMouse"); m.set("Name", "Instance")
        val mt = LuaTable()
        mt.set("__type", "Instance")
        mt.set(LuaValue.INDEX, fn2 { _, k ->
            when (val ks = k.tojstring()) {
                "Button1Down", "Button1Up", "Button2Down", "Button2Up", "Move", "Idle", "WheelForward", "WheelBackward", "KeyDown", "KeyUp" -> rt.api.signal(m, ks)
                "IsA" -> fnv { a -> LuaValue.valueOf(a.arg(2).tojstring() in setOf("Mouse", "PlayerMouse", "Instance")) }
                "GetPropertyChangedSignal" -> fnv { a -> rt.api.signal(m, "Changed:" + a.arg(2).tojstring()) }
                "Changed" -> rt.api.signal(m, "Changed")
                else -> LuaValue.NIL
            }
        })
        m.setmetatable(mt)
        mouseTable = m
        return m
    }

    // ------------------------------------------------------------------ chat

    /** Roblox's classic chat name colors, picked from a hash of the name. */
    fun nameColor(name: String): Int {
        val colors = intArrayOf(0xFFFD2943.toInt(), 0xFF01A2FF.toInt(), 0xFF02B757.toInt(), 0xFF6B327C.toInt(),
            0xFFDA8541.toInt(), 0xFFF5CD30.toInt(), 0xFFE8BAC8.toInt(), 0xFFD7C59A.toInt())
        var value = 0
        for ((i, ch) in name.withIndex()) {
            var c = ch.code
            var rev = name.length - i
            if (name.length % 2 == 1) rev -= 1
            if (rev % 4 >= 2) c = -c
            value += c
        }
        return colors[Math.floorMod(value, colors.size)]
    }

    private fun addLine(l: ChatLine) {
        chatLines.add(l)
        while (chatLines.size > 200) chatLines.removeAt(0)
        chatVersion++
    }

    fun systemMessage(text: String, color: Int = 0xFFE6E6E6.toInt()) = addLine(ChatLine(null, text, color, true))

    /** The local player sending a chat message (from the chat bar). */
    fun sendChat(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val p = player ?: return
        if (text.startsWith("/")) {
            val cmd = text.substring(1).trim()
            val lower = cmd.lowercase()
            when {
                lower == "?" || lower == "help" -> {
                    for (s in listOf("/e <emote> — wave, point, cheer, dance", "/clear — clear the chat", "/shiftlock — toggle shift lock")) systemMessage(s, 0xFFB4B4B4.toInt())
                    return
                }
                lower == "clear" || lower == "cls" -> { chatLines.clear(); chatVersion++; return }
                lower == "shiftlock" -> { toggleShiftLock(); return }
                lower.startsWith("e ") || lower.startsWith("emote ") -> {
                    val e = lower.substringAfter(' ').trim()
                    emote = when (e) { "wave", "point", "cheer", "laugh", "dance", "dance2", "dance3" -> e; "dance1" -> "dance"; else -> null }
                    emoteTime = 0f
                    if (emote == null) systemMessage("You can't use that emote.", 0xFFB4B4B4.toInt())
                    rt.fire(p, "Chatted", LuaValue.valueOf(text))
                    return
                }
            }
        }
        val name = (p["DisplayName"] as? String)?.ifEmpty { p.name } ?: p.name
        addLine(ChatLine(name, text, nameColor(p.name), false))
        character?.findFirstChild("Head")?.let { bubble(it, text) }
        rt.fire(p, "Chatted", LuaValue.valueOf(text))
        // TextChatService channel listeners
        val general = dm.findService("TextChatService")?.findFirstChild("TextChannels")?.findFirstChild("RBXGeneral")
        if (general != null) {
            val msg = LuaTable().apply {
                set("Text", text); set("PrefixText", "$name:"); set("Status", LuaTypes.wrap(EnumItemV("TextChatMessageStatus", "Success", 1)))
                set("TextSource", LuaTable().apply { set("UserId", 1); set("Name", p.name) })
                set("TextChannel", rt.wrap(general))
            }
            rt.fire(general, "MessageReceived", msg)
            rt.fire(dm.getService("TextChatService"), "MessageReceived", msg)
        }
    }

    /** A bubble above [part] (Chat:Chat or a player message). */
    fun bubble(part: Instance, text: String) {
        bubbles.removeAll { it.part === part && bubbles.count { b -> b.part === part } >= 3 }
        bubbles.add(Bubble(part, text.take(200)))
    }

    private fun stepBubbles(dt: Float) {
        if (bubbles.isEmpty()) return
        val it = bubbles.iterator()
        while (it.hasNext()) { val b = it.next(); b.age += dt; if (b.age > 12f || b.part.parent == null) it.remove() }
    }

    /** Stats shown on the leaderboard: name -> display value, from the player's "leaderstats" folder. */
    fun leaderstats(): List<Pair<String, String>> {
        val ls = player?.findFirstChild("leaderstats") ?: return emptyList()
        return ls.children.filter { it.isA("ValueBase") }.take(4).map { v ->
            val value = when (val x = v["Value"]) {
                is Double -> if (x == Math.floor(x) && abs(x) < 1e15) x.toLong().toString() else fmtNum(x.toFloat())
                is Float -> fmtNum(x)
                is Number -> x.toLong().toString()
                is Boolean -> if (x) "true" else "false"
                is Instance -> x.name
                null -> "-"
                else -> x.toString()
            }
            v.name to value
        }
    }

    // ------------------------------------------------------------------ tools

    fun backpackTools(): List<Instance> {
        val bp = player?.findFirstChild("Backpack") ?: return emptyList()
        val list = bp.children.filter { it.className == "Tool" || it.className == "HopperBin" }.toMutableList()
        equipped?.let { if (it !in list) list.add(0, it) }
        return list
    }

    fun equip(tool: Instance) {
        val ch = character ?: return
        if (equipped === tool) return
        unequip()
        tool.setParent(ch)
        equipped = tool
        toolOffsets.clear()
        val handle = tool.findFirstChild("Handle")
        if (handle != null) {
            val hcf = PartData.cframe(handle)
            for (p in tool.descendants()) if (p.isA("BasePart")) toolOffsets[p] = hcf.inverse() * PartData.cframe(p)
        }
        physicsDirty = true
        rt.fire(tool, "Equipped", mouseValue())
    }

    fun unequip() {
        val t = equipped ?: return
        equipped = null
        t.setParent(player?.findFirstChild("Backpack"))
        physicsDirty = true
        rt.fire(t, "Unequipped")
    }

    fun toggleTool(t: Instance) { if (equipped === t) unequip() else equip(t) }

    // ------------------------------------------------------------------ context actions

    fun bindAction(name: String, fn: LuaValue, touchButton: Boolean) {
        if (touchButton) actions[name] = ActionButton(name, fn, name)
    }
    fun unbindAction(name: String) { actions.remove(name) }
    fun unbindAll() { actions.clear() }
    fun setActionTitle(name: String, title: String) { actions[name]?.title = title }

    fun pressAction(name: String, begin: Boolean) {
        val a = actions[name] ?: return
        val state = if (begin) EnumItemV("UserInputState", "Begin", 0) else EnumItemV("UserInputState", "End", 2)
        val input = LuaTable().apply {
            set("UserInputType", LuaTypes.wrap(EnumItemV("UserInputType", "Touch", 7)))
            set("UserInputState", LuaTypes.wrap(state))
            set("KeyCode", LuaTypes.wrap(EnumItemV("KeyCode", "Unknown", 0)))
            set("Position", LuaTypes.wrap(Vector3.ZERO))
        }
        rt.spawn(a.fn, LuaValue.varargsOf(arrayOf(LuaValue.valueOf(name), LuaTypes.wrap(state), input)))
    }

    // ------------------------------------------------------------------ API used by Lua

    fun velocityOf(p: Instance): Vector3? {
        if (isCharacterPart(p)) return vel
        return bodyOf[p]?.vel
    }

    fun setVelocity(p: Instance, v: Vector3) {
        if (isCharacterPart(p)) { vel = v; if (v.y > 0) { grounded = false; climbing = false }; return }
        bodyOf[p]?.let { it.vel = v; it.sleeping = false; it.restTime = 0f }
    }

    fun moveDirection(h: Instance): Vector3 = if (h === humanoid) lastDir else Vector3.ZERO
    fun isGrounded(h: Instance) = h === humanoid && grounded
    fun jump(h: Instance) { if (h === humanoid) jumpRequested = true }
    fun moveTo(h: Instance, target: Vector3) { if (h === humanoid) moveToTarget = target }
    fun scriptMove(h: Instance, dir: Vector3) { if (h === humanoid) scriptMoveDir = dir }

    val isDead get() = dead
    fun max3(a: Float, b: Float, c: Float) = max(a, max(b, c))
}
