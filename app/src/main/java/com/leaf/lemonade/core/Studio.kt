package com.leaf.lemonade.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.leaf.lemonade.engine.Geometry
import com.leaf.lemonade.engine.PlaySession
import com.leaf.lemonade.format.RbxBinaryWriter
import com.leaf.lemonade.format.RbxDocument
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.lua.LuaRuntime
import com.leaf.lemonade.model.*
import com.leaf.lemonade.render.FlyCamera
import com.leaf.lemonade.render.ViewState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

enum class Tool { SELECT, MOVE, SCALE, ROTATE }

class PluginButton(val inst: Instance, val pluginName: String, val rt: LuaRuntime)
class PluginWidget(val inst: Instance, val rt: LuaRuntime)

class Studio(val app: Context) {
    val lock = Any()
    val main = Handler(Looper.getMainLooper())
    val density = app.resources.displayMetrics.density

    var editModel: DataModel = PlaceFactory.newPlace(); private set
    var playModel: DataModel? = null; private set
    val model: DataModel get() = playModel ?: editModel
    val isPlaying get() = playModel != null
    var play: PlaySession? = null; private set
    var editRuntime: LuaRuntime; private set

    val selection = ArrayList<Instance>()
    var tool = Tool.MOVE
        set(v) { field = v; listeners.forEach { it.onToolChanged() } }
    /** Taps add to / remove from the selection (like holding Ctrl or Shift in Studio). */
    var multiSelect = false
        set(v) { field = v; listeners.forEach { it.onToolChanged() } }
    /** One-finger drags in the viewport draw a selection box instead of turning the camera. */
    var boxSelect = false
        set(v) { field = v; listeners.forEach { it.onToolChanged() } }
    /** Viewport taps pick the part itself, not the Model it's grouped in (Studio's Alt+click). */
    var selectInsideModels: Boolean
        get() = Settings.selectInsideModels
        set(v) { Settings.selectInsideModels = v; listeners.forEach { it.onToolChanged() } }
    /** Ctrl/Shift held on a hardware keyboard. */
    @Volatile var modifierHeld = false
    val camera = FlyCamera()
    val view = ViewState()
    val undo = UndoManager(this)
    /** Edit-mode animation: Motor6D.Transform, joint re-seating, Animator:StepAnimations (plugins). */
    val editAnim = com.leaf.lemonade.engine.EditAnimation()
    /** Studio's Snap to Grid increments (Model/Home tab): Move studs and Rotate degrees, each with an on/off box. */
    var moveSnap: Float
        get() = Settings.moveSnap
        set(v) { Settings.moveSnap = v }
    var rotSnap: Float
        get() = Settings.rotSnap
        set(v) { Settings.rotSnap = v }
    var snapEnabled: Boolean
        get() = Settings.moveSnapOn
        set(v) { Settings.moveSnapOn = v }
    var rotSnapEnabled: Boolean
        get() = Settings.rotSnapOn
        set(v) { Settings.rotSnapOn = v }
    /** Studio's Collisions toggle: dragged parts stop at other parts instead of passing into them. */
    var collisionsEnabled: Boolean
        get() = Settings.dragCollisions
        set(v) { Settings.dragCollisions = v }

    var dirty = false; private set
    var fileUri: Uri? = null
    var fileName = "Place1.rbxl"
    var fileIsXml = false
    /** The Roblox place this file was opened from or published to (for File → Publish to Roblox). */
    var cloud: com.leaf.lemonade.net.CloudPlace? = null

    val dataStores = HashMap<String, HashMap<String, String>>()
    val pluginButtons = CopyOnWriteArrayList<PluginButton>()
    val pluginWidgets = CopyOnWriteArrayList<PluginWidget>()
    val plugins = PluginManager(this)
    private var clipboard: List<Instance> = emptyList()

    // Input from on-screen controls
    @Volatile var moveX = 0f
    @Volatile var moveY = 0f
    @Volatile var flyUp = false
    @Volatile var flyDown = false
    /** Fly camera speed multiplier (kept in Studio Settings). */
    var flySpeed: Float
        get() = Settings.flySpeed
        set(v) { Settings.flySpeed = v.coerceIn(0.05f, 200f) }
    private var editCamSaved: Triple<Vector3, Float, Float>? = null
    private var camVel = Vector3.ZERO
    private var pendingYaw = 0f
    private var pendingPitch = 0f

    /** Turns the edit camera; with smoothing on, the turn is eased in over the next few frames. */
    fun rotateEditCamera(dYaw: Float, dPitch: Float) {
        val s = Settings.cameraSensitivity
        if (Settings.cameraSmoothing) { pendingYaw += dYaw * s; pendingPitch += dPitch * s }
        else camera.rotate(dYaw * s, dPitch * s)
    }

    interface Listener {
        fun onTreeChanged() {}
        fun onSelectionChanged() {}
        fun onPropsChanged() {}
        fun onModelReplaced() {}
        fun onPlayStateChanged() {}
        fun onToolChanged() {}
        fun onPluginsChanged() {}
        fun onDirtyChanged() {}
    }

    val listeners = CopyOnWriteArrayList<Listener>()
    var openScriptHandler: ((Instance) -> Unit)? = null
    var toastHandler: ((String) -> Unit)? = null

    @Volatile private var partsDirty = true
    private val partsCache = ArrayList<Instance>()
    private var treePosted = false
    private var propsPosted = false

    /** Classes the render lists (parts, decals, sounds, adornments...) care about. */
    private val RENDER_CLASSES = setOf("Decal", "Texture", "SurfaceGui", "Shirt", "Pants", "ShirtGraphic", "Sound",
        "SelectionBox", "Highlight", "BoxHandleAdornment", "SelectionSphere", "Handles", "ArcHandles",
        "RopeConstraint", "RodConstraint", "SpringConstraint", "PointLight", "SpotLight", "SurfaceLight")
    private val LIGHT_CLASSES = setOf("PointLight", "SpotLight", "SurfaceLight")

    /**
     * Does adding/removing this subtree change what's drawn? GUI text, values and scripts being
     * created every frame used to rebuild the whole part list each time (slow on big maps).
     */
    private fun renderRelevant(i: Instance): Boolean {
        if (i.isA("BasePart") || i.className in RENDER_CLASSES || i.isService) return true
        for (c in i.children) if (renderRelevant(c)) return true
        return false
    }

    private val treeListener = object : TreeListener {
        override fun childAdded(parent: Instance, child: Instance) { if (renderRelevant(child)) partsDirty = true; postTree() }
        override fun childRemoved(parent: Instance, child: Instance) { if (renderRelevant(child)) partsDirty = true; postTree() }
        override fun propChanged(inst: Instance, name: String) {
            if (name == "Name") postTree()
            if (selection.isNotEmpty() && selection.any { it === inst }) postProps()
        }
    }

    init {
        editModel.listeners.add(treeListener)
        editRuntime = LuaRuntime(this, editModel, false)
        resetCameraFromModel()
    }

    private fun postTree() {
        if (treePosted) return
        treePosted = true
        main.postDelayed({ treePosted = false; listeners.forEach { it.onTreeChanged() } }, 120)
    }

    private fun postProps() {
        if (propsPosted) return
        propsPosted = true
        main.postDelayed({ propsPosted = false; listeners.forEach { it.onPropsChanged() } }, 200)
    }

    fun markDirty() {
        if (!dirty) { dirty = true; main.post { listeners.forEach { it.onDirtyChanged() } } }
    }

    fun toast(msg: String) = main.post { toastHandler?.invoke(msg) }

    /**
     * How big plugin GUIs are drawn in edit mode. They're made for desktop Studio (a window
     * 400-600 units tall), so on a short phone viewport they're shrunk to fit unless the user
     * picked a size in Studio Settings.
     */
    fun editGuiScale(): Float {
        val set = Settings.pluginUiScale
        if (set > 0f) return set
        return ((view.height / density) / 520f).coerceIn(0.6f, 1f)
    }

    /**
     * Device pixels per GUI pixel in the coordinates scripts see (mouse and input positions,
     * Absolute* properties, Camera.ViewportSize, WorldToScreenPoint...). In edit mode plugin GUIs
     * are drawn at [editGuiScale], so their pixels are smaller too: keeping every coordinate in
     * those units is what lets Roact plugins (Archimedes, F3X) size themselves from
     * AbsoluteSize/AbsoluteContentSize and drag with input positions without drifting.
     */
    fun guiUnit(): Float = if (play != null) density else density * editGuiScale()

    fun viewportSizeDp() = guiUnit().let { u -> Vector2(view.width / u, view.height / u) }

    /** Where the finger last was for plugins in edit mode (UserInputService:GetMouseLocation), in [guiUnit]s. */
    @Volatile var lastEditPointer: Vector2? = null

    // ------------------------------------------------------------------ frame (render thread, lock held)

    private val decalsCache = ArrayList<Instance>()
    private val constraintsCache = ArrayList<Instance>()
    private val surfaceGuisCache = ArrayList<Instance>()
    private val clothingCache = ArrayList<Instance>()
    private val adornCache = ArrayList<Instance>()
    private val soundsCache = ArrayList<Instance>()
    private val lightsCache = ArrayList<Instance>()

    /** PointLights, SpotLights and SurfaceLights in Workspace (refreshed with [renderParts]). */
    fun renderLights(): List<Instance> { renderParts(); return lightsCache }

    private val ADORNMENTS = setOf("SelectionBox", "Highlight", "BoxHandleAdornment", "SelectionSphere", "Handles", "ArcHandles")

    /** Parts inside a union are its source shapes, not parts of their own. */
    private fun insideOperation(i: Instance): Boolean {
        var p = i.parent
        while (p != null && p !is DataModel) { if (p.isA("PartOperation")) return true; p = p.parent }
        return false
    }

    /** Bumped whenever [renderParts] is rebuilt (parts added or removed). */
    var renderListVersion = 0; private set

    fun renderParts(): List<Instance> {
        if (partsDirty) {
            partsDirty = false
            renderListVersion++
            partsCache.clear()
            decalsCache.clear()
            constraintsCache.clear()
            surfaceGuisCache.clear()
            clothingCache.clear()
            adornCache.clear()
            soundsCache.clear()
            lightsCache.clear()
            val ws = model.findService("Workspace")
            ws?.descendants()?.forEach {
                if (it.isA("BasePart")) { if (it.className != "Terrain" && !insideOperation(it)) partsCache.add(it) }
                else if ((it.className == "Decal" || it.className == "Texture") && it.parent?.isA("BasePart") == true) decalsCache.add(it)
                else if (it.className == "SurfaceGui") surfaceGuisCache.add(it)
                else if (it.className == "Shirt" || it.className == "Pants" || it.className == "ShirtGraphic") clothingCache.add(it)
                else if (it.className in ADORNMENTS) adornCache.add(it)
                else if (it.className == "Sound") soundsCache.add(it)
                else if (it.className == "RopeConstraint" || it.className == "RodConstraint" || it.className == "SpringConstraint") constraintsCache.add(it)
                else if (it.className in LIGHT_CLASSES) lightsCache.add(it)
            }
            val pg = play?.player?.findFirstChild("PlayerGui")
            pg?.descendants()?.forEach {
                if (it.className == "SurfaceGui" && it["Adornee"] is Instance) surfaceGuisCache.add(it)
                else if (it.className == "Sound") soundsCache.add(it)
                else if (it.className in ADORNMENTS) adornCache.add(it)
            }
            // Tools held by the player (e.g. a building tool) keep their adornments inside the tool.
            play?.player?.findFirstChild("Backpack")?.descendants()?.forEach { if (it.className in ADORNMENTS) adornCache.add(it) }
            if (play != null) {
                model.findService("SoundService")?.descendants()?.forEach { if (it.className == "Sound") soundsCache.add(it) }
                play?.player?.findFirstChild("PlayerScripts")?.descendants()?.forEach { if (it.className == "Sound") soundsCache.add(it) }
            }
            // Plugin adornments (selection boxes and highlights a plugin puts in CoreGui).
            model.findService("CoreGui")?.descendants()?.forEach {
                if (it.className in ADORNMENTS) adornCache.add(it)
            }
        }
        return partsCache
    }

    /** Rope, rod and spring constraints in Workspace (drawn when Visible). */
    fun renderConstraints(): List<Instance> { renderParts(); return constraintsCache }

    /** Decals and Textures on parts in Workspace (refreshed with [renderParts]). */
    fun renderDecals(): List<Instance> { renderParts(); return decalsCache }

    /** SurfaceGuis in Workspace, plus adorned ones in PlayerGui. */
    fun renderSurfaceGuis(): List<Instance> { renderParts(); return surfaceGuisCache }

    /** Shirts, Pants and T-shirts on characters/models in Workspace. */
    fun renderClothing(): List<Instance> { renderParts(); return clothingCache }

    /** SelectionBoxes, Highlights and box adornments (scripts and plugins use them to outline parts). */
    fun renderAdornments(): List<Instance> { renderParts(); return adornCache }

    /** Sounds that can be heard (Workspace, SoundService and the player's GUI/scripts). */
    fun audibleSounds(): List<Instance> { renderParts(); return soundsCache }

    /** Forces the render lists to be rebuilt (e.g. after a CoreGui change). */
    fun invalidateRenderLists() { partsDirty = true }

    /** Rendering options (View tab). */
    @Volatile var shadowsEnabled = true

    val glJobs = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private var lastJobEnd = 0L

    fun frame(dt0: Float) {
        var dt = dt0
        while (true) { val j = glJobs.poll() ?: break; j(); lastJobEnd = System.nanoTime() }
        // Don't count time spent starting scripts as a giant simulation step.
        if (System.nanoTime() - lastJobEnd < 50_000_000L) dt = minOf(dt, 1f / 60f)
        val p = play
        if (p != null) {
            p.step(dt)
            try { com.leaf.lemonade.audio.AudioEngine.update(this) } catch (e: Exception) { OutputLog.warn("Audio: ${e.message}") }
        } else {
            val cam = camera
            if (pendingYaw != 0f || pendingPitch != 0f) {
                val k = 1f - kotlin.math.exp(-24f * dt)
                val y = pendingYaw * k; val p = pendingPitch * k
                cam.rotate(y, p)
                pendingYaw -= y; pendingPitch -= p
                if (kotlin.math.abs(pendingYaw) < 1e-4f) pendingYaw = 0f
                if (kotlin.math.abs(pendingPitch) < 1e-4f) pendingPitch = 0f
            }
            var mv = cam.forward * (-moveY) + cam.right * moveX
            if (flyUp) mv += Vector3.Y
            if (flyDown) mv -= Vector3.Y
            val target = if (mv.magnitude > 0.01f) mv * (40f * flySpeed) else Vector3.ZERO
            // Ease in/out like Studio's camera instead of starting and stopping instantly.
            camVel = if (Settings.cameraSmoothing) camVel + (target - camVel) * (1f - kotlin.math.exp(-9f * dt)) else target
            if (camVel.magnitude > 0.001f) cam.pos += camVel * dt else camVel = Vector3.ZERO
            // Studio runs RunService events in edit mode too; plugin UIs (Roact etc.) depend on them.
            editModel.findService("RunService")?.let { rs ->
                val d = org.luaj.vm2.LuaValue.valueOf(dt.toDouble())
                editRuntime.fire(rs, "RenderStepped", d)
                editRuntime.fire(rs, "Heartbeat", d)
            }
            editRuntime.step(dt.toDouble())
            editAnim.flush()
        }
    }

    // ------------------------------------------------------------------ play

    /** Where the next playtest character spawns instead of a SpawnLocation (Test → Play Here). */
    @Volatile var playHereAt: Vector3? = null

    companion object {
        /** Attribute marking the model a playtest should use as the character (see [playAs]). */
        const val PLAY_AS_TAG = "__MobileStudioPlayAs"
    }

    /** Studio's "Play Here": the character starts on whatever is in the middle of the view. */
    fun playHere() {
        synchronized(lock) {
            val (o, d) = view.screenRay(view.width / 2f, view.height / 2f)
            val hit = Geometry.raycast(renderParts(), o, d, 2000f) { it["CanCollide"] != false }
            playHereAt = hit?.position ?: (camera.pos + camera.forward * 12f)
        }
        startPlay()
    }

    /**
     * Playtest as a model from Workspace: the player spawns as (a copy of) it where it stands,
     * like putting it in StarterPlayer as StarterCharacter. Models without a Humanoid or
     * HumanoidRootPart get them added in the playtest copy.
     */
    fun playAs(target: Instance) {
        synchronized(lock) {
            if (isPlaying) return
            val parts = if (target.isA("BasePart")) listOf(target) else target.descendants().filter { it.isA("BasePart") }
            if (parts.isEmpty()) { toast("${target.name} has no parts to play as"); return }
            target.attributes[PLAY_AS_TAG] = true
            Geometry.boundsOf(parts)?.let { (a, b) -> playHereAt = Vector3((a.x + b.x) / 2f, a.y, (a.z + b.z) / 2f) }
        }
        try { startPlay() } finally { synchronized(lock) { target.attributes.remove(PLAY_AS_TAG) } }
    }

    fun startPlay() {
        synchronized(lock) {
            if (isPlaying) return
            undo.commit()
            selection.clear()
            editCamSaved = Triple(camera.pos, camera.yaw, camera.pitch)
            // The playtest copy reads terrain from SmoothGrid, so unsaved terrain edits go in first.
            com.leaf.lemonade.engine.TerrainStore.flush(editModel)
            val dm = editModel.cloneModel()
            playModel = dm
            dm.listeners.add(treeListener)
            partsDirty = true
            moveX = 0f; moveY = 0f
            val s = PlaySession(this, dm)
            play = s
            val audio = com.leaf.lemonade.audio.AudioEngine
            audio.onLoaded = { snd -> s.rt.fire(snd, "Loaded", org.luaj.vm2.LuaValue.valueOf(snd["SoundId"]?.toString() ?: "")) }
            audio.onEnded = { snd ->
                s.rt.fire(snd, "Ended", org.luaj.vm2.LuaValue.valueOf(snd["SoundId"]?.toString() ?: ""))
                if (snd.attributes["__oneShot"] == true || snd["PlayOnRemove"] == true && snd.parent == null) snd.destroy()
            }
            // Scripts start on the render thread so a slow script can't freeze the UI.
            glJobs.add {
                if (play === s) try { s.start() } catch (e: Exception) { OutputLog.error("Playtest failed to start: $e") }
            }
        }
        listeners.forEach { it.onSelectionChanged(); it.onModelReplaced(); it.onPlayStateChanged() }
    }

    fun stopPlay() {
        synchronized(lock) {
            val s = play ?: return
            s.stop()
            com.leaf.lemonade.audio.AudioEngine.stopAll()
            playModel?.listeners?.remove(treeListener)
            playModel = null
            play = null
            selection.clear()
            partsDirty = true
            editCamSaved?.let { (p, y, pi) -> camera.pos = p; camera.yaw = y; camera.pitch = pi }
            camera.fov = 70f
            moveX = 0f; moveY = 0f
        }
        listeners.forEach { it.onSelectionChanged(); it.onModelReplaced(); it.onPlayStateChanged() }
    }

    // ------------------------------------------------------------------ files

    fun replaceEditModel(dm: DataModel, uri: Uri?, name: String, isXml: Boolean, cloud: com.leaf.lemonade.net.CloudPlace? = null) {
        this.cloud = cloud
        if (isPlaying) stopPlay()
        synchronized(lock) {
            editModel.listeners.remove(treeListener)
            editRuntime.shutdown()
            pluginButtons.clear(); pluginWidgets.clear()
            editModel = dm
            dm.listeners.add(treeListener)
            editRuntime = LuaRuntime(this, dm, false)
            editAnim.clear()
            selection.clear()
            undo.clear()
            partsDirty = true
            fileUri = uri; fileName = name; fileIsXml = isXml
            dirty = false
            resetCameraFromModel()
        }
        listeners.forEach { it.onModelReplaced(); it.onSelectionChanged(); it.onDirtyChanged(); it.onPluginsChanged() }
        plugins.loadAll()
    }

    fun newPlace(template: PlaceFactory.Template = PlaceFactory.Template.BASEPLATE) =
        replaceEditModel(PlaceFactory.newPlace(template), null, "Place1.rbxl", false)

    private fun resetCameraFromModel() {
        val cam = editModel.workspace["CurrentCamera"] as? Instance
        val cf = cam?.get("CFrame") as? CFrame
        if (cf != null && cf.position.magnitude > 0.01f) camera.setFromCFrame(cf)
        else { camera.pos = Vector3(0f, 20f, 40f); camera.yaw = 0f; camera.pitch = -0.4f }
    }

    fun serializePlace(): ByteArray = synchronized(lock) {
        (editModel.workspace["CurrentCamera"] as? Instance)?.set("CFrame", camera.cframe(), false)
        // Terrain edits live in the voxel grid until now: write them into Terrain.SmoothGrid.
        com.leaf.lemonade.engine.TerrainStore.flush(editModel)
        RbxBinaryWriter.write(editModel.children.filter { it.archivable }, editModel.meta)
    }

    fun serializeSelection(): ByteArray? = synchronized(lock) { serializeInstances(selection.toList()) }

    /** A model file (.rbxm) holding [list] (nested picks collapse into their topmost ancestor). */
    fun serializeInstances(list: List<Instance>): ByteArray? = synchronized(lock) {
        val roots = list.filter { s -> !s.isService && list.none { o -> o !== s && s.isDescendantOf(o) } }
        if (roots.isEmpty()) null else RbxBinaryWriter.write(roots, emptyMap())
    }

    fun markSaved(uri: Uri?, name: String) {
        fileUri = uri; fileName = name; fileIsXml = false; dirty = false
        listeners.forEach { it.onDirtyChanged() }
    }

    /** After saving or publishing a Roblox place: no unsaved changes (the device file isn't touched). */
    fun markSavedToCloud() {
        dirty = false
        listeners.forEach { it.onDirtyChanged() }
    }

    /** Insert the contents of a model file (.rbxm) into Workspace in front of the camera. */
    fun insertDocument(doc: RbxDocument, parent: Instance? = null) {
        val inserted = ArrayList<Instance>()
        synchronized(lock) {
            undo.begin("Insert model")
            for (r in doc.roots) {
                val target = parent ?: when {
                    // Material packs: variants only work inside MaterialService (Studio puts them there too).
                    r.className == "MaterialVariant" || (r.className == "Folder" && r.children.isNotEmpty() && r.descendants().all { it.className == "MaterialVariant" || it.className == "Folder" }) ->
                        model.getService("MaterialService")
                    r.isA("LayerCollector") && r.className != "BillboardGui" && r.className != "SurfaceGui" -> model.getService("StarterGui")
                    r.isA("GuiObject") -> model.getService("StarterGui").let { sg ->
                        sg.children.firstOrNull { it.className == "ScreenGui" } ?: InstanceFactory.create("ScreenGui").also { g -> reparent(g, sg) }
                    }
                    else -> model.workspace
                }
                reparent(r, target)
                inserted.add(r)
            }
            val parts = inserted.flatMap { i -> (listOf(i) + i.descendants()).filter { it.isA("BasePart") } }
            Geometry.boundsOf(parts)?.let { (a, b) ->
                val size = b - a
                val center = (a + b) / 2f
                val dest = spawnPoint(size)
                val delta = dest - center
                for (p in parts) setProp(p, "CFrame", PartData.cframe(p) + delta)
            }
            undo.commit()
            selection.clear(); selection.addAll(inserted)
        }
        listeners.forEach { it.onSelectionChanged() }
    }

    // ------------------------------------------------------------------ selection

    fun select(list: List<Instance>) {
        synchronized(lock) {
            selection.clear()
            selection.addAll(list.distinct())
            fireSelectionChangedLua()
        }
        listeners.forEach { it.onSelectionChanged() }
    }

    fun toggleSelect(i: Instance) {
        synchronized(lock) {
            if (selection.any { it === i }) selection.removeAll { it === i } else selection.add(i)
            fireSelectionChangedLua()
        }
        listeners.forEach { it.onSelectionChanged() }
    }

    /**
     * Selection:Set/Add/Remove from a plugin or the command bar. Like Studio this fires
     * Selection.SelectionChanged when the selection really changed: plugins such as Archimedes
     * build their next preview in that event (its "Render All" renders one copy per event).
     */
    fun selectFromScript(list: List<Instance>) {
        val next = list.distinct()
        val same = next.size == selection.size && next.indices.all { next[it] === selection[it] }
        if (same) return
        selection.clear(); selection.addAll(next)
        fireSelectionChangedLua()
        main.post { listeners.forEach { it.onSelectionChanged() } }
    }

    private fun fireSelectionChangedLua() {
        val svc = editModel.findService("Selection") ?: return
        if (!isPlaying) editRuntime.fire(svc, "SelectionChanged")
    }

    fun pruneSelection() {
        synchronized(lock) { selection.removeAll { it.root() !== model } }
        main.post { listeners.forEach { it.onSelectionChanged(); it.onTreeChanged() } }
    }

    // ------------------------------------------------------------------ edits (undoable)

    fun setProp(inst: Instance, name: String, value: Any?) {
        synchronized(lock) {
            val had = inst.props.containsKey(name)
            val old = inst.props[name]
            if (had && old == value) return
            inst.set(name, value)
            undo.record(UndoManager.Op.Prop(inst, name, old, value, had))
            // Like Studio: picking a MaterialVariant also switches the part to the variant's base material.
            if ((name == "MaterialVariant" || name == "MaterialVariantSerialized") && inst.isA("BasePart")) {
                val vName = value?.toString().orEmpty()
                val variant = if (vName.isEmpty()) null else model.findService("MaterialService")?.descendants()?.firstOrNull { it.className == "MaterialVariant" && it.name == vName }
                val base = (variant?.get("BaseMaterial") as? EnumValue)?.value
                if (base != null && PartData.material(inst) != base) setProp(inst, "Material", EnumValue(base))
            }
        }
    }

    fun reparent(inst: Instance, newParent: Instance?) {
        synchronized(lock) {
            val old = inst.parent
            val idx = old?.children?.indexOfFirst { it === inst } ?: -1
            inst.setParent(newParent)
            undo.record(UndoManager.Op.Parent(inst, old, idx, newParent))
        }
    }

    fun spawnPoint(size: Vector3): Vector3 {
        val (o, d) = view.screenRay(view.width / 2f, view.height / 2f)
        val hit = Geometry.raycast(renderParts(), o, d, 600f) { true }
        val p = if (hit != null) hit.position + Vector3(0f, size.y / 2f, 0f) else camera.pos + camera.forward * 20f
        return if (snapEnabled && moveSnap > 0f) Vector3(snap(p.x, moveSnap), p.y, snap(p.z, moveSnap)) else p
    }

    private fun snap(v: Float, s: Float) = (v / s).roundToInt() * s

    /** Classes joined by Attachment0/Attachment1 (constraints, beams, trails). */
    private fun usesAttachments(cls: String) = ClassDb.isA(cls, "Constraint") || cls == "Beam" || cls == "Trail" || cls == "PathfindingLink"

    /**
     * Like Studio's constraint tools: inserting a constraint (or Beam/Trail) with two parts or
     * attachments selected links them with Attachments, and ropes/rods start at their real length.
     */
    private fun insertLinked(inst: Instance): Boolean {
        val picks = selection.filter { it.isA("BasePart") || it.className == "Attachment" }
        if (picks.size != 2) return false
        undo.begin("Insert ${inst.className}")
        val atts = picks.map { p ->
            if (p.className == "Attachment") p else {
                val a = InstanceFactory.create("Attachment")
                a.name = inst.className.removeSuffix("Constraint") + "Attachment"
                reparent(a, p)
                a
            }
        }
        inst.set("Attachment0", atts[0], false); inst.set("Attachment1", atts[1], false)
        fun world(a: Instance): Vector3 {
            val part = a.parent?.takeIf { it.isA("BasePart") } ?: return Vector3.ZERO
            return PartData.cframe(part) * ((a["CFrame"] as? CFrame)?.position ?: Vector3.ZERO)
        }
        val d = (world(atts[0]) - world(atts[1])).magnitude
        if (inst.className == "RopeConstraint" || inst.className == "RodConstraint") inst.set("Length", d, false)
        if (inst.className == "SpringConstraint") inst.set("FreeLength", d, false)
        reparent(inst, picks[0].takeIf { it.isA("BasePart") } ?: picks[0].parent ?: model.workspace)
        undo.commit()
        selection.clear(); selection.add(inst)
        return true
    }

    fun insertObject(cls: String, parentOverride: Instance? = null): Instance {
        val inst = InstanceFactory.create(cls)
        // Studio's inserted SpawnLocations come with the spawn decal on top.
        if (cls == "SpawnLocation") PlaceFactory.spawnDecal(inst)
        if (parentOverride == null && usesAttachments(cls)) {
            val done = synchronized(lock) { insertLinked(inst) }
            if (done) { listeners.forEach { it.onSelectionChanged() }; return inst }
        }
        synchronized(lock) {
            val sel = selection.firstOrNull()
            val parent = parentOverride ?: when {
                inst.isA("BasePart") || inst.className == "Model" -> model.workspace
                inst.isA("GuiObject") -> sel?.takeIf { it.isA("GuiBase2d") } ?: model.getService("StarterGui").let { sg ->
                    sg.children.firstOrNull { it.className == "ScreenGui" } ?: InstanceFactory.create("ScreenGui").also { g -> reparent(g, sg) }
                }
                inst.className == "ScreenGui" -> model.getService("StarterGui")
                // Variants only take effect inside MaterialService (or a folder in it), like Studio.
                inst.className == "MaterialVariant" -> sel?.takeIf { it.className == "MaterialService" || it.isDescendantOf(model.getService("MaterialService")) }
                    ?: model.getService("MaterialService")
                else -> sel ?: model.workspace
            }
            undo.begin("Insert $cls")
            if (inst.isA("BasePart")) {
                val s = PartData.size(inst)
                inst.set("CFrame", CFrame.at(spawnPoint(s)), false)
            }
            reparent(inst, parent)
            undo.commit()
            selection.clear(); selection.add(inst)
        }
        listeners.forEach { it.onSelectionChanged() }
        return inst
    }

    fun insertPart(shape: Int) {
        val cls = when (shape) { 3 -> "WedgePart"; 4 -> "CornerWedgePart"; else -> "Part" }
        val p = insertObject(cls)
        synchronized(lock) {
            when (shape) {
                0 -> { p.set("shape", EnumValue(0)); p.set("size", Vector3(2f, 2f, 2f)) }
                2 -> { p.set("shape", EnumValue(2)); p.set("size", Vector3(2f, 2f, 2f)) }
            }
            p.set("Anchored", true)
        }
    }

    private fun deletable(i: Instance) = !i.isService && i.parent != null && i !== model.workspace["CurrentCamera"]

    fun deleteSelection() {
        synchronized(lock) {
            undo.begin("Delete")
            for (i in selection.toList()) if (deletable(i)) reparent(i, null)
            undo.commit()
            selection.clear()
        }
        listeners.forEach { it.onSelectionChanged() }
    }

    fun duplicateSelection() {
        val created = ArrayList<Instance>()
        synchronized(lock) {
            undo.begin("Duplicate")
            for (i in selection.toList()) {
                if (!deletable(i)) continue
                val c = i.cloneTree()
                reparent(c, i.parent)
                created.add(c)
            }
            undo.commit()
            if (created.isNotEmpty()) { selection.clear(); selection.addAll(created) }
        }
        listeners.forEach { it.onSelectionChanged() }
    }

    fun copySelection() = synchronized(lock) { clipboard = selection.filter { deletable(it) }.map { it.cloneTree() } }
    fun cutSelection() { copySelection(); deleteSelection() }

    fun paste(into: Instance?) {
        val created = ArrayList<Instance>()
        synchronized(lock) {
            if (clipboard.isEmpty()) return
            undo.begin("Paste")
            for (c in clipboard) {
                val n = c.cloneTree()
                reparent(n, into ?: model.workspace)
                created.add(n)
            }
            undo.commit()
            selection.clear(); selection.addAll(created)
        }
        listeners.forEach { it.onSelectionChanged() }
    }

    val hasClipboard get() = clipboard.isNotEmpty()

    fun groupSelection() {
        synchronized(lock) {
            val items = selection.filter { deletable(it) }
            if (items.isEmpty()) return
            val parent = items.first().parent ?: return
            undo.begin("Group")
            val m = InstanceFactory.create("Model")
            reparent(m, parent)
            for (i in items) reparent(i, m)
            undo.commit()
            selection.clear(); selection.add(m)
        }
        listeners.forEach { it.onSelectionChanged() }
    }

    fun ungroupSelection() {
        synchronized(lock) {
            val models = selection.filter { (it.className == "Model" || it.className == "Folder") && deletable(it) }
            if (models.isEmpty()) return
            undo.begin("Ungroup")
            val freed = ArrayList<Instance>()
            for (m in models) {
                val p = m.parent
                for (c in m.children.toList()) { reparent(c, p); freed.add(c) }
                reparent(m, null)
            }
            undo.commit()
            selection.clear(); selection.addAll(freed)
        }
        listeners.forEach { it.onSelectionChanged() }
    }

    fun selectedParts(): List<Instance> {
        val out = ArrayList<Instance>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>())
        for (s in selection) {
            if (s.isA("BasePart") && seen.add(s)) out.add(s)
            if (!s.isService && (s.isA("Model") || s.className == "Folder")) for (d in s.descendants()) if (d.isA("BasePart") && seen.add(d)) out.add(d)
        }
        return out
    }

    fun setAnchored(v: Boolean) {
        synchronized(lock) {
            undo.begin("Anchor")
            for (p in selectedParts()) setProp(p, "Anchored", v)
            undo.commit()
        }
    }

    fun focusSelection() {
        synchronized(lock) {
            val b = Geometry.boundsOf(selectedParts()) ?: return
            val c = (b.first + b.second) / 2f
            camera.focusOn(c, (b.second - b.first).magnitude / 2f)
        }
    }

    fun openScript(s: Instance) { openScriptHandler?.invoke(s) }
    fun requestOpenScript(s: Instance) = openScript(s)

    // ------------------------------------------------------------------ plugins

    fun addPluginButton(b: Instance, pluginName: String, rt: LuaRuntime) {
        pluginButtons.add(PluginButton(b, pluginName, rt))
        notifyPluginButtons()
    }

    fun notifyPluginButtons() = main.post { listeners.forEach { it.onPluginsChanged() } }

    fun addPluginWidget(w: Instance, rt: LuaRuntime) { pluginWidgets.add(PluginWidget(w, rt)) }

    fun clickPluginButton(b: PluginButton) {
        synchronized(lock) {
            if (b.inst["Enabled"] == false) return
            b.rt.fire(b.inst, "Click")
        }
    }

    // ------------------------------------------------------------------ plugin activation & mouse

    /** Each plugin's PluginMouse (plugin:GetMouse()). */
    val pluginMice = java.util.IdentityHashMap<Instance, org.luaj.vm2.LuaTable>()
    /** The plugin that called plugin:Activate(); it gets the viewport's mouse input. */
    @Volatile var activePlugin: Instance? = null; private set
    var activePluginExclusive = false; private set
    private var activePluginRt: LuaRuntime? = null
    /** Shows a plugin's PluginMenu (title, items) and reports the chosen index (-1 = none). Set by the activity. */
    var pluginMenuHandler: ((String, List<String>, (Int) -> Unit) -> Unit)? = null

    fun activatePlugin(p: Instance, rt: LuaRuntime, exclusiveMouse: Boolean) {
        val old = activePlugin
        if (old != null && old !== p) deactivatePlugin(old)
        activePlugin = p; activePluginRt = rt; activePluginExclusive = exclusiveMouse
        main.post { listeners.forEach { it.onPluginsChanged() } }
    }

    fun deactivatePlugin(p: Instance?) {
        val cur = activePlugin ?: return
        if (p != null && p !== cur) return
        activePlugin = null
        activePluginRt?.fire(cur, "Deactivation")
        activePluginRt = null
        main.post { listeners.forEach { it.onPluginsChanged() } }
    }

    /**
     * Viewport touch while a plugin is active: aims its PluginMouse at (x, y) px and fires
     * [event] (Button1Down, Button1Up or Move). Returns false if no plugin mouse takes input.
     */
    fun pluginMouseEvent(event: String, xPx: Float, yPx: Float): Boolean = synchronized(lock) {
        val p = activePlugin ?: return false
        val rt = activePluginRt ?: return false
        val m = pluginMice[p] ?: return false
        val (o, dir) = view.screenRay(xPx, yPx)
        val filter = m.get("TargetFilter").takeIf { it.isuserdata() }?.touserdata() as? Instance
        val hit = Geometry.raycast(renderParts(), o, dir, 5000f) { it["Locked"] != true && (filter == null || (it !== filter && !it.isDescendantOf(filter))) }
        val hp = hit?.position ?: (o + dir * 1000f)
        val lt = com.leaf.lemonade.lua.LuaTypes
        m.set("Hit", lt.wrap(CFrame.lookAt(o, hp).withPosition(hp)))
        m.set("Target", rt.wrap(hit?.part))
        val u = guiUnit()
        lastEditPointer = Vector2(xPx / u, yPx / u)
        m.set("X", org.luaj.vm2.LuaValue.valueOf((xPx / u).toDouble())); m.set("Y", org.luaj.vm2.LuaValue.valueOf((yPx / u).toDouble()))
        m.set("UnitRay", lt.wrap(Ray(o, dir)))
        m.set("Origin", lt.wrap(CFrame.lookAt(o, o + dir)))
        m.set("ViewSizeX", org.luaj.vm2.LuaValue.valueOf((view.width / u).toDouble()))
        m.set("ViewSizeY", org.luaj.vm2.LuaValue.valueOf((view.height / u).toDouble()))
        rt.fire(m, event)
        true
    }

    private val prefs by lazy { app.getSharedPreferences("plugin_settings", Context.MODE_PRIVATE) }
    fun pluginSetting(id: String, key: String): String? = prefs.getString("$id/$key", null)
    fun setPluginSetting(id: String, key: String, json: String) = prefs.edit().putString("$id/$key", json).apply()

    // ------------------------------------------------------------------ terrain editor

    /** The Terrain Editor's active brush (null: normal selection/camera input). */
    @Volatile var terrainTool: com.leaf.lemonade.engine.TerrainOps.Brush? = null
    @Volatile var terrainBrushSize = 12f
    @Volatile var terrainBrushStrength = 0.5f
    @Volatile var terrainMaterial = com.leaf.lemonade.engine.TerrainMaterials.fromName("Grass")
    @Volatile var terrainCube = false
    /** Where the brush was last applied (drawn as a ring in the 3D view). */
    @Volatile var terrainBrushAt: Vector3? = null
    /** Flatten / Sea Level height for the current stroke (set where the stroke starts). */
    var terrainPlaneY: Float? = null
    private var strokeTouched = false

    /** Workspace.Terrain of the place being edited (created if a file had none). */
    fun editTerrain(): Instance = synchronized(lock) {
        val ws = editModel.workspace
        ws.findFirstChildOfClass("Terrain") ?: InstanceFactory.create("Terrain").also { it.name = "Terrain"; it.set("Anchored", true, false); it.attachRaw(ws) }
    }

    /** Point under the finger for the brush: terrain, then parts, then the ground plane (y = 0). */
    fun terrainBrushHit(x: Float, y: Float): Vector3? = synchronized(lock) {
        val (o, d) = view.screenRay(x, y)
        val t = editTerrain()
        val g = com.leaf.lemonade.engine.TerrainStore.of(t)
        val th = com.leaf.lemonade.engine.TerrainMeshes.raycast(g, com.leaf.lemonade.engine.TerrainMeshes.colorsFor(t), o, d, 3000f)
        val ph = Geometry.raycast(renderParts(), o, d, 3000f) { it["CanCollide"] != false && PartData.transparency(it) < 0.99f }
        val best = listOfNotNull(th?.first, ph?.t).minOrNull()
        if (best != null) return o + d * best
        if (kotlin.math.abs(d.y) < 1e-4f) return null
        val tt = -o.y / d.y
        if (tt < 0f || tt > 3000f) return null
        o + d * tt
    }

    /** One dab of the active brush at [at]. */
    fun terrainDab(at: Vector3) {
        val tool = terrainTool ?: return
        synchronized(lock) {
            val g = com.leaf.lemonade.engine.TerrainStore.of(editTerrain())
            if (terrainPlaneY == null) terrainPlaneY = at.y
            com.leaf.lemonade.engine.TerrainOps.brush(g, tool, at, terrainBrushSize / 2f, terrainBrushStrength, terrainMaterial, terrainCube, terrainPlaneY ?: at.y)
            terrainBrushAt = at
            strokeTouched = true
        }
    }

    /** Ends a brush stroke (or any terrain edit): stores the voxels in SmoothGrid as one undo step. */
    fun terrainCommit(label: String) {
        synchronized(lock) {
            terrainPlaneY = null
            if (!strokeTouched && label.startsWith("Terrain:").not()) return
            strokeTouched = false
            val t = editTerrain()
            val g = com.leaf.lemonade.engine.TerrainStore.of(t)
            if (!g.modified) return
            undo.begin(label)
            setProp(t, "SmoothGrid", com.leaf.lemonade.engine.TerrainStore.encodeFor(t))
            undo.commit()
        }
        markDirty()
    }

    fun runCommand(code: String) {
        OutputLog.add(OutputLog.Level.INFO, "> $code")
        synchronized(lock) {
            val rt = play?.rt ?: editRuntime
            rt.runCommand(code)
        }
    }

    fun loadDocumentBytes(bytes: ByteArray): RbxDocument = RbxFile.read(bytes)
}
