package com.leaf.lemonade.model

import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

interface TreeListener {
    fun childAdded(parent: Instance, child: Instance) {}
    fun childRemoved(parent: Instance, child: Instance) {}
    fun propChanged(inst: Instance, name: String) {}
}

open class Instance(var className: String) {
    val props = LinkedHashMap<String, Any?>()
    val propTypes = HashMap<String, Int>()
    var parent: Instance? = null
        internal set
    val children = ArrayList<Instance>()
    var isService = false
    var archivable = true
    var destroyed = false
    /** Referent id from the file this instance was loaded from (-1 if created here). */
    var loadedRef = -1
    /** Runtime attributes set by scripts (SetAttribute). */
    val attributes = LinkedHashMap<String, Any?>()
    /** Layout results for GUI objects (pixels). */
    var absX = 0f; var absY = 0f; var absW = 0f; var absH = 0f
    /**
     * Bumped on every property change of this instance or one of its children, and when its
     * children change. The renderer uses it to notice edited parts without listeners.
     */
    @JvmField var version = 0
    /** Renderer bookkeeping (static batching); never copied or saved. */
    @JvmField var renderRec: Any? = null

    var name: String
        get() = props["Name"] as? String ?: className
        set(v) = set("Name", v)

    operator fun get(name: String): Any? = props[name]

    fun set(name: String, value: Any?, notify: Boolean = true) {
        val old = props[name]
        if (props.containsKey(name) && old == value) return
        props[name] = value
        version++
        // Only children that change how the part looks (meshes) touch the parent's version;
        // a playing Sound or a script's Value inside a part must not mark it as edited.
        if (affectsParentShape()) parent?.let { it.version++ }
        changeCount++
        if (!propTypes.containsKey(name)) propTypes[name] = PropType.infer(value)
        if (notify) root()?.firePropChanged(this, name)
    }

    /**
     * Mesh-like children (SpecialMesh, BlockMesh, FileMesh, SurfaceAppearance) shape their parent
     * part, and a union's source parts shape the union.
     */
    private fun affectsParentShape(): Boolean = when (className) {
        "SpecialMesh", "BlockMesh", "CylinderMesh", "FileMesh", "SurfaceAppearance", "BevelMesh" -> true
        else -> parent?.isA("PartOperation") == true && isA("BasePart")
    }

    fun root(): DataModel? {
        var p: Instance? = this
        while (p != null) {
            if (p is DataModel) return p
            p = p.parent
        }
        return null
    }

    fun isDescendantOf(other: Instance): Boolean {
        var p = parent
        while (p != null) { if (p === other) return true; p = p.parent }
        return false
    }

    fun setParent(newParent: Instance?, index: Int = -1) {
        if (newParent === parent) return
        if (newParent != null && (newParent === this || newParent.isDescendantOf(this)))
            throw IllegalArgumentException("Attempt to set parent of $name to ${newParent.name} would result in circular reference")
        val old = parent
        version++
        changeCount++
        val shape = affectsParentShape()
        if (old != null) {
            if (shape) old.version++
            old.children.remove(this)
            parent = null
            old.root()?.fireChildRemoved(old, this)
        }
        if (newParent != null) {
            if (shape) newParent.version++
            if (index in 0..newParent.children.size) newParent.children.add(index, this) else newParent.children.add(this)
            parent = newParent
            newParent.root()?.fireChildAdded(newParent, this)
        }
    }

    /** Attach without firing events (used by loaders). */
    fun attachRaw(p: Instance) {
        parent = p
        p.children.add(this)
    }

    fun destroy() {
        if (destroyed) return
        setParent(null)
        markDestroyed()
    }

    private fun markDestroyed() {
        destroyed = true
        for (c in children.toList()) c.markDestroyed()
    }

    fun isA(cls: String) = ClassDb.isA(className, cls)

    fun findFirstChild(n: String, recursive: Boolean = false): Instance? {
        for (c in children) if (c.name == n) return c
        if (recursive) for (c in children) c.findFirstChild(n, true)?.let { return it }
        return null
    }

    fun findFirstChildOfClass(cls: String): Instance? = children.firstOrNull { it.className == cls }
    fun findFirstChildWhichIsA(cls: String, recursive: Boolean = false): Instance? {
        for (c in children) if (c.isA(cls)) return c
        if (recursive) for (c in children) c.findFirstChildWhichIsA(cls, true)?.let { return it }
        return null
    }

    fun descendants(out: MutableList<Instance> = ArrayList()): MutableList<Instance> {
        for (c in children) { out.add(c); c.descendants(out) }
        return out
    }

    fun fullName(): String {
        val parts = ArrayList<String>()
        var p: Instance? = this
        while (p != null && p !is DataModel) { parts.add(p.name); p = p.parent }
        return parts.reversed().joinToString(".")
    }

    /** Deep clone of this subtree, remapping references that point inside it. */
    fun cloneTree(): Instance {
        val map = IdentityHashMap<Instance, Instance>()
        val r = copyInto(this, map, Instance(className))
        remapRefs(map)
        return r
    }

    override fun toString() = name

    companion object {
        /** Counts every property/parent change anywhere (cheap "did anything change?" check). */
        @JvmField @Volatile var changeCount = 0L

        internal fun copyInto(src: Instance, map: IdentityHashMap<Instance, Instance>, dst: Instance): Instance {
            dst.props.putAll(src.props)
            dst.propTypes.putAll(src.propTypes)
            dst.isService = src.isService
            dst.attributes.putAll(src.attributes)
            map[src] = dst
            for (ch in src.children) {
                if (!ch.archivable) continue
                val cc = copyInto(ch, map, Instance(ch.className))
                cc.attachRaw(dst)
            }
            return dst
        }

        internal fun remapRefs(map: IdentityHashMap<Instance, Instance>) {
            for (c in map.values) {
                for (e in c.props.entries) {
                    val v = e.value
                    if (v is Instance) map[v]?.let { e.setValue(it) }
                    else if (v is ContentValue && v.obj != null) map[v.obj]?.let { e.setValue(v.copy(obj = it)) }
                }
            }
        }
    }
}

class DataModel : Instance("DataModel") {
    val listeners = CopyOnWriteArrayList<TreeListener>()
    val meta = LinkedHashMap<String, String>()

    init { props["Name"] = "Game" }

    fun fireChildAdded(p: Instance, c: Instance) { for (l in listeners) l.childAdded(p, c) }
    fun fireChildRemoved(p: Instance, c: Instance) { for (l in listeners) l.childRemoved(p, c) }
    fun firePropChanged(i: Instance, n: String) { for (l in listeners) l.propChanged(i, n) }

    fun findService(cls: String): Instance? = children.firstOrNull { it.className == cls }

    fun getService(cls: String): Instance {
        findService(cls)?.let { return it }
        val s = InstanceFactory.create(cls)
        s.isService = true
        s.setParent(this)
        return s
    }

    val workspace: Instance get() = getService("Workspace")

    fun cloneModel(): DataModel {
        val map = IdentityHashMap<Instance, Instance>()
        val dm = DataModel()
        dm.meta.putAll(meta)
        copyInto(this, map, dm)
        remapRefs(map)
        return dm
    }
}

object PropType {
    const val STRING = 0x01; const val BOOL = 0x02; const val INT = 0x03; const val FLOAT = 0x04; const val DOUBLE = 0x05
    const val UDIM = 0x06; const val UDIM2 = 0x07; const val RAY = 0x08; const val FACES = 0x09; const val AXES = 0x0A
    const val BRICKCOLOR = 0x0B; const val COLOR3 = 0x0C; const val VECTOR2 = 0x0D; const val VECTOR3 = 0x0E
    const val CFRAME = 0x10; const val ENUM = 0x12; const val REF = 0x13; const val VECTOR3INT16 = 0x14
    const val NUMBERSEQ = 0x15; const val COLORSEQ = 0x16; const val NUMBERRANGE = 0x17; const val RECT = 0x18
    const val PHYSPROPS = 0x19; const val COLOR3U8 = 0x1A; const val INT64 = 0x1B; const val SHAREDSTRING = 0x1C
    const val OPTCFRAME = 0x1E; const val UNIQUEID = 0x1F; const val FONT = 0x20; const val SECCAPS = 0x21; const val CONTENT = 0x22

    fun infer(v: Any?): Int = when (v) {
        null -> REF
        is String, is BinaryString -> STRING
        is Boolean -> BOOL
        is Int -> INT
        is Float -> FLOAT
        is Double -> DOUBLE
        is Long -> INT64
        is UDim -> UDIM
        is UDim2 -> UDIM2
        is Ray -> RAY
        is Faces -> FACES
        is Axes -> AXES
        is BrickColorV -> BRICKCOLOR
        is Color3 -> COLOR3
        is Vector2 -> VECTOR2
        is Vector3 -> VECTOR3
        is CFrame -> CFRAME
        is EnumValue -> ENUM
        is Instance -> REF
        is Vector3int16 -> VECTOR3INT16
        is NumberSequence -> NUMBERSEQ
        is ColorSequence -> COLORSEQ
        is NumberRange -> NUMBERRANGE
        is Rect -> RECT
        is PhysicalProps -> PHYSPROPS
        is SharedStr -> SHAREDSTRING
        is OptionalCFrame -> OPTCFRAME
        is UniqueId -> UNIQUEID
        is FontValue -> FONT
        is SecurityCaps -> SECCAPS
        is ContentValue -> CONTENT
        is RawPropValue -> v.type
        else -> STRING
    }

    fun default(t: Int): Any? = when (t) {
        STRING -> ""
        BOOL -> false
        INT -> 0
        FLOAT -> 0f
        DOUBLE -> 0.0
        INT64 -> 0L
        UDIM -> UDim(0f, 0)
        UDIM2 -> UDim2(0f, 0, 0f, 0)
        RAY -> Ray(Vector3.ZERO, Vector3.ZERO)
        FACES -> Faces(0)
        AXES -> Axes(0)
        BRICKCOLOR -> BrickColorV(194)
        COLOR3, COLOR3U8 -> Color3(0f, 0f, 0f)
        VECTOR2 -> Vector2(0f, 0f)
        VECTOR3 -> Vector3.ZERO
        CFRAME -> CFrame.IDENTITY
        ENUM -> EnumValue(0)
        REF -> null
        VECTOR3INT16 -> Vector3int16(0, 0, 0)
        NUMBERSEQ -> NumberSequence(listOf(NumberKey(0f, 0f, 0f), NumberKey(1f, 0f, 0f)))
        COLORSEQ -> ColorSequence(listOf(ColorKey(0f, Color3(1f, 1f, 1f), 0f), ColorKey(1f, Color3(1f, 1f, 1f), 0f)))
        NUMBERRANGE -> NumberRange(0f, 0f)
        RECT -> Rect(0f, 0f, 0f, 0f)
        PHYSPROPS -> PhysicalProps(0)
        SHAREDSTRING -> SharedStr(ByteArray(0))
        OPTCFRAME -> OptionalCFrame(null)
        UNIQUEID -> UniqueId(ByteArray(16))
        FONT -> FontValue("rbxasset://fonts/families/SourceSansPro.json", 400, 0, "")
        SECCAPS -> SecurityCaps(0)
        CONTENT -> ContentValue(0)
        else -> null
    }

    /** Types that are not useful to show/edit in the Properties panel. */
    fun hidden(t: Int, v: Any?) = t == SHAREDSTRING || t == UNIQUEID || t == SECCAPS || v is BinaryString || v is RawPropValue
}

/** Serialized property names that differ from their scripting names. */
object PropNames {
    private val toDisplay = mapOf(
        "size" to "Size", "Color3uint8" to "Color", "shape" to "Shape", "formFactorRaw" to "FormFactor",
        "Health_XML" to "Health", "MaterialVariantSerialized" to "MaterialVariant",
        "xmlRead_MaxDistance_3" to "RollOffMaxDistance", "xmlRead_MinDistance_3" to "RollOffMinDistance",
        "BackgroundColor" to "BackgroundBrickColor", "Font_Serialized" to "FontFace",
        // Roblox saves MaterialService.Use2022Materials under this name.
        "Use2022MaterialsXml" to "Use2022Materials",
    )
    private val fromDisplay = toDisplay.entries.associate { it.value to it.key }
    fun display(n: String) = toDisplay[n] ?: n
    fun serialized(n: String) = fromDisplay[n] ?: n

    fun resolve(inst: Instance, displayName: String): String {
        if (inst.props.containsKey(displayName)) return displayName
        val s = fromDisplay[displayName]
        if (s != null && inst.props.containsKey(s)) return s
        return displayName
    }
}

object ClassDb {
    private val parents = HashMap<String, String>().apply {
        fun p(base: String, vararg cs: String) { for (c in cs) put(c, base) }
        p("Instance", "PVInstance", "LuaSourceContainer", "GuiBase", "Light", "ValueBase", "Humanoid", "Folder", "Configuration",
            "JointInstance", "WeldConstraint", "Constraint", "Attachment", "Sound", "ClickDetector", "DataModelMesh", "FaceInstance",
            "UIBase", "BackpackItem", "Accoutrement", "Player", "Camera", "Animator", "Animation", "Team", "BindableEvent",
            "BindableFunction", "RemoteEvent", "RemoteFunction", "ParticleEmitter", "Fire", "Smoke", "Sparkles", "Trail", "Beam",
            "Highlight", "ProximityPrompt", "Explosion", "ForceField", "Plugin", "PluginToolbar", "PluginToolbarButton",
            "Sky", "Atmosphere", "PostEffect", "Clouds", "Script Context", "DataModel")
        p("PVInstance", "BasePart", "Model")
        p("Model", "WorldRoot", "Status")
        p("WorldRoot", "Workspace", "WorldModel")
        p("BasePart", "FormFactorPart", "TriangleMeshPart", "Terrain", "TrussPart", "VehicleSeat")
        p("FormFactorPart", "Part", "WedgePart", "CornerWedgePart")
        p("Part", "SpawnLocation", "Seat", "Platform", "SkateboardPlatform")
        p("TriangleMeshPart", "MeshPart", "PartOperation")
        p("PartOperation", "UnionOperation", "NegateOperation", "IntersectOperation")
        p("LuaSourceContainer", "BaseScript", "ModuleScript")
        p("BaseScript", "Script")
        p("Script", "LocalScript")
        p("GuiBase", "GuiBase2d", "GuiBase3d")
        p("GuiBase2d", "GuiObject", "LayerCollector")
        p("LayerCollector", "ScreenGui", "BillboardGui", "SurfaceGuiBase", "PluginGui")
        p("SurfaceGuiBase", "SurfaceGui")
        p("PluginGui", "DockWidgetPluginGui", "QWidgetPluginGui")
        p("GuiObject", "Frame", "GuiLabel", "GuiButton", "TextBox", "ScrollingFrame", "ViewportFrame", "CanvasGroup", "VideoFrame")
        p("GuiLabel", "TextLabel", "ImageLabel")
        p("GuiButton", "TextButton", "ImageButton")
        p("Light", "PointLight", "SpotLight", "SurfaceLight")
        p("ValueBase", "StringValue", "IntValue", "NumberValue", "BoolValue", "ObjectValue", "Vector3Value", "CFrameValue",
            "Color3Value", "BrickColorValue", "RayValue")
        p("JointInstance", "Weld", "Snap", "Motor", "ManualWeld", "Glue")
        p("Motor", "Motor6D")
        p("DataModelMesh", "FileMesh", "BevelMesh")
        p("FileMesh", "SpecialMesh")
        p("BevelMesh", "BlockMesh", "CylinderMesh")
        p("FaceInstance", "Decal")
        p("Decal", "Texture")
        p("UIBase", "UIComponent")
        p("UIComponent", "UILayout", "UICorner", "UIStroke", "UIPadding", "UIGradient", "UIScale", "UIConstraint", "UIAspectRatioConstraint", "UISizeConstraint")
        p("UILayout", "UIGridStyleLayout")
        p("UIGridStyleLayout", "UIListLayout", "UIGridLayout", "UITableLayout", "UIPageLayout")
        p("BackpackItem", "Tool", "HopperBin")
        p("Accoutrement", "Accessory", "Hat")
        p("Constraint", "PrismaticConstraint", "CylindricalConstraint", "TorsionSpringConstraint", "PlaneConstraint", "RopeConstraint", "RodConstraint", "SpringConstraint", "HingeConstraint", "BallSocketConstraint", "AlignPosition", "AlignOrientation", "LinearVelocity", "VectorForce",
            "AngularVelocity", "LineForce", "NoCollisionConstraint", "RigidConstraint", "Torque", "UniversalConstraint", "SlidingBallConstraint")
        p("SlidingBallConstraint", "PrismaticConstraint", "CylindricalConstraint")
        p("Instance", "BodyMover", "PostEffect", "SoundEffect", "SoundGroup", "AnimationController", "HumanoidDescription", "BodyColors",
            "CharacterAppearance", "SurfaceAppearance", "Keyframe", "KeyframeSequence", "Pose", "KeyframeMarker", "PathfindingModifier",
            "PathfindingLink", "Dialog", "DialogChoice", "DragDetector", "UnreliableRemoteEvent", "Wire", "AudioPlayer", "AudioEmitter",
            "AudioListener", "AudioDeviceOutput", "AudioDeviceInput", "AudioFader", "AudioReverb", "AudioEqualizer", "AudioEcho",
            "AudioAnalyzer", "IKControl", "WrapLayer", "WrapTarget", "TextChatCommand", "TextChannel", "Actor", "StyleSheet", "StyleRule",
            "ClickDetector", "MaterialVariant", "AdvancedDragger", "BubbleChatConfiguration")
        p("Model", "Actor")
        p("Attachment", "Bone")
        p("CharacterAppearance", "Clothing", "ShirtGraphic", "CharacterMesh", "BodyColors")
        p("Clothing", "Shirt", "Pants")
        p("BodyMover", "BodyAngularVelocity", "BodyForce", "BodyGyro", "BodyPosition", "BodyThrust", "BodyVelocity", "RocketPropulsion")
        p("PostEffect", "BloomEffect", "BlurEffect", "ColorCorrectionEffect", "DepthOfFieldEffect", "SunRaysEffect")
        p("SoundEffect", "ChorusSoundEffect", "CompressorSoundEffect", "DistortionSoundEffect", "EchoSoundEffect", "EqualizerSoundEffect",
            "FlangeSoundEffect", "PitchShiftSoundEffect", "ReverbSoundEffect", "TremoloSoundEffect")
        p("GuiBase3d", "PVAdornment", "SelectionLasso", "InstanceAdornment")
        p("PVAdornment", "HandleAdornment", "SelectionBox", "SelectionSphere", "HandlesBase", "SurfaceSelection")
        p("HandleAdornment", "BoxHandleAdornment", "ConeHandleAdornment", "CylinderHandleAdornment", "SphereHandleAdornment", "LineHandleAdornment", "ImageHandleAdornment", "WireframeHandleAdornment")
        p("HandlesBase", "Handles", "ArcHandles")
        p("InstanceAdornment", "Highlight")
        p("UIConstraint", "UIAspectRatioConstraint", "UISizeConstraint", "UITextSizeConstraint")
        p("UIComponent", "UIFlexItem", "UIDragDetector")
        p("ValueBase", "IntConstrainedValue", "DoubleConstrainedValue")
    }

    fun superclass(c: String): String? = parents[c] ?: if (c == "Instance") null else if (isServiceName(c)) "Instance" else "Instance"

    fun isA(c: String, target: String): Boolean {
        if (target == "Instance") return true
        var cur: String? = c
        var guard = 0
        while (cur != null && guard++ < 20) {
            if (cur == target) return true
            if (cur == "Instance") return false
            cur = parents[cur] ?: "Instance"
        }
        return false
    }

    val serviceNames = listOf(
        "Workspace", "Players", "Lighting", "MaterialService", "ReplicatedFirst", "ReplicatedStorage", "ServerScriptService",
        "ServerStorage", "StarterGui", "StarterPack", "StarterPlayer", "Teams", "SoundService", "Chat", "TextChatService",
        "LocalizationService", "TestService",
    )
    private val otherServices = setOf(
        "RunService", "TweenService", "Debris", "HttpService", "UserInputService", "ContextActionService", "CollectionService",
        "MarketplaceService", "DataStoreService", "Selection", "ChangeHistoryService", "PathfindingService", "TextService",
        "GuiService", "BadgeService", "PhysicsService", "InsertService", "CoreGui", "StudioService", "ProximityPromptService",
        "VRService", "HapticService", "GroupService", "MessagingService", "AssetService", "SocialService", "PolicyService",
        "ScriptContext", "KeyframeSequenceProvider", "AnimationClipProvider", "TeleportService", "MemoryStoreService",
        "RbxAnalyticsService", "AnalyticsService", "ContentProvider", "LogService", "ScriptEditorService", "Stats", "DraggerService",
        "PluginGuiService", "StudioAssetService", "AvatarEditorService", "VoiceChatService", "ExperienceNotificationService",
        "LocalizationService", "NotificationService", "GamepadService", "KeyboardService", "MouseService", "AssetImportService",
    )

    fun isServiceName(n: String) = n in serviceNames || n in otherServices

    /** Classes offered by "Insert Object" (Studio's list: parts, scripts, GUI, effects, constraints, sound...). */
    val insertable = listOf(
        // 3D
        "Part", "WedgePart", "CornerWedgePart", "TrussPart", "SpawnLocation", "Seat", "VehicleSeat", "MeshPart", "Model", "Folder",
        "Actor", "WorldModel", "Configuration", "Camera", "Attachment", "Bone",
        // Scripts
        "Script", "LocalScript", "ModuleScript",
        // GUI
        "ScreenGui", "BillboardGui", "SurfaceGui", "Frame", "ScrollingFrame", "TextLabel", "TextButton", "TextBox", "ImageLabel",
        "ImageButton", "ViewportFrame", "VideoFrame", "CanvasGroup", "UICorner", "UIStroke", "UIListLayout", "UIGridLayout",
        "UIPageLayout", "UITableLayout", "UIPadding", "UIGradient", "UIScale", "UIAspectRatioConstraint", "UISizeConstraint",
        "UITextSizeConstraint", "UIFlexItem", "UIDragDetector",
        // Lights & effects
        "PointLight", "SpotLight", "SurfaceLight", "ParticleEmitter", "Fire", "Smoke", "Sparkles", "Trail", "Beam", "Explosion",
        "ForceField", "Highlight", "SelectionBox", "SelectionSphere", "BoxHandleAdornment", "ConeHandleAdornment",
        "CylinderHandleAdornment", "SphereHandleAdornment", "LineHandleAdornment", "ImageHandleAdornment",
        "Sky", "Atmosphere", "Clouds", "BloomEffect", "BlurEffect", "ColorCorrectionEffect", "DepthOfFieldEffect", "SunRaysEffect",
        // Sound
        "Sound", "SoundGroup", "ChorusSoundEffect", "CompressorSoundEffect", "DistortionSoundEffect", "EchoSoundEffect",
        "EqualizerSoundEffect", "FlangeSoundEffect", "PitchShiftSoundEffect", "ReverbSoundEffect", "TremoloSoundEffect",
        "AudioPlayer", "AudioEmitter", "AudioListener", "AudioDeviceOutput", "AudioFader", "AudioReverb", "AudioEqualizer", "Wire",
        // Constraints & movers
        "WeldConstraint", "Weld", "Motor6D", "RopeConstraint", "RodConstraint", "SpringConstraint", "HingeConstraint",
        "BallSocketConstraint", "PrismaticConstraint", "CylindricalConstraint", "TorsionSpringConstraint", "UniversalConstraint",
        "PlaneConstraint", "RigidConstraint", "NoCollisionConstraint", "AlignPosition", "AlignOrientation", "LinearVelocity",
        "AngularVelocity", "VectorForce", "LineForce", "Torque", "BodyVelocity", "BodyPosition", "BodyGyro", "BodyForce",
        "BodyAngularVelocity", "BodyThrust", "RocketPropulsion",
        // Meshes & appearance
        "SpecialMesh", "BlockMesh", "CylinderMesh", "SurfaceAppearance", "Decal", "Texture", "MaterialVariant",
        // Characters
        "Humanoid", "HumanoidDescription", "Animator", "AnimationController", "Animation", "KeyframeSequence", "IKControl",
        "Shirt", "Pants", "ShirtGraphic", "BodyColors", "Accessory", "Tool",
        // Interaction
        "ClickDetector", "ProximityPrompt", "DragDetector", "Dialog", "DialogChoice", "PathfindingModifier", "PathfindingLink",
        // Values & networking
        "RemoteEvent", "RemoteFunction", "UnreliableRemoteEvent", "BindableEvent", "BindableFunction", "StringValue", "IntValue",
        "NumberValue", "BoolValue", "ObjectValue", "Vector3Value", "CFrameValue", "Color3Value", "BrickColorValue", "RayValue",
        // Other
        "Team", "TextChatCommand",
    ).distinct().sorted()
}

object InstanceFactory {
    private val GREY = Color3.fromRGB(163, 162, 165)

    private val templates = java.util.concurrent.ConcurrentHashMap<String, Instance>()

    /**
     * Roblox properties whose default is not the type's zero value. Files written by scripts or
     * other tools often leave properties out and rely on these (e.g. a Part with no "shape" is a
     * Block, not shape 0 = Ball; a part with no CastShadow casts shadows).
     */
    private val nonZeroDefaults: Map<String, Any?> = mapOf(
        "shape" to EnumValue(1), "Shape" to EnumValue(1),
        "CanCollide" to true, "CanTouch" to true, "CanQuery" to true, "CastShadow" to true,
        "Enabled" to true, "Visible" to true, "Archivable" to true, "AutoButtonColor" to true,
        "PlaybackSpeed" to 1f, "Pitch" to 1f, "Volume" to 0.5f, "RollOffMaxDistance" to 10000f, "xmlRead_MaxDistance_3" to 10000f,
        "RollOffMinDistance" to 10f, "xmlRead_MinDistance_3" to 10f, "Scale" to Vector3.ONE,
        "Color3" to Color3(1f, 1f, 1f), "ImageColor3" to Color3(1f, 1f, 1f),
        "Brightness" to 1f, "Range" to 8f, "MaxHealth" to 100f, "Health_XML" to 100f, "WalkSpeed" to 16f, "JumpPower" to 50f,
        "JumpHeight" to 7.2f, "MaxSlopeAngle" to 89f, "UseJumpPower" to true, "AutomaticScalingEnabled" to true,
        "Weight" to 1f, "Responsiveness" to 10f, "MaxActivationDistance" to 32f, "RequiresHandle" to true, "CanBeDropped" to true,
        "ResetOnSpawn" to true, "TextSize" to 14f, "ZIndex" to 1, "StudsPerTileU" to 2f, "StudsPerTileV" to 2f,
    )

    /**
     * The value a fresh [cls] instance has for [prop], or null when it has no special default.
     * Used when saving a property column for a class: instances that never had the property
     * must keep Roblox's default rather than the type's zero (a Part's shape 0 is a Ball).
     */
    fun classDefault(cls: String, prop: String): Any? {
        val t = templates.getOrPut(cls) { create(cls) }
        if (t.props.containsKey(prop)) return t.props[prop]
        if (prop == "size" && ClassDb.isA(cls, "BasePart")) return Vector3(4f, 1f, 2f)
        if (prop == "Color" && cls == "Decal") return Color3(1f, 1f, 1f)
        return nonZeroDefaults[prop]
    }

    fun create(cls: String): Instance {
        val i = Instance(cls)
        i.set("Name", cls, false)
        if (ClassDb.isServiceName(cls)) i.isService = true
        when {
            ClassDb.isA(cls, "BasePart") -> part(i, cls)
            ClassDb.isA(cls, "LuaSourceContainer") -> {
                i.set("Source", defaultSource(cls), false)
                if (cls != "ModuleScript") i.set("Disabled", false, false)
            }
            cls == "Model" -> {
                i.set("WorldPivotData", OptionalCFrame(null), false)
                i.set("PrimaryPart", null, false)
            }
            cls == "Humanoid" -> {
                i.set("Health_XML", 100f, false); i.set("MaxHealth", 100f, false); i.set("WalkSpeed", 16f, false)
                i.set("JumpPower", 50f, false); i.set("HipHeight", 0f, false); i.set("DisplayName", "", false)
            }
            ClassDb.isA(cls, "GuiObject") -> gui(i, cls)
            cls == "ScreenGui" -> { i.set("Enabled", true, false); i.set("ResetOnSpawn", true, false); i.set("IgnoreGuiInset", false, false) }
            ClassDb.isA(cls, "Light") -> {
                i.set("Brightness", 1f, false); i.set("Color", Color3(1f, 1f, 1f), false); i.set("Range", 8f, false)
                i.set("Enabled", true, false); i.set("Shadows", false, false)
            }
            cls == "StringValue" -> i.set("Value", "", false)
            cls == "IntValue" -> i.set("Value", 0L, false)
            cls == "NumberValue" -> i.set("Value", 0.0, false)
            cls == "BoolValue" -> i.set("Value", false, false)
            cls == "ObjectValue" -> i.set("Value", null, false)
            cls == "Vector3Value" -> i.set("Value", Vector3.ZERO, false)
            cls == "CFrameValue" -> i.set("Value", CFrame.IDENTITY, false)
            cls == "Color3Value" -> i.set("Value", Color3(0f, 0f, 0f), false)
            cls == "Sound" -> { i.set("SoundId", "", false); i.set("Volume", 0.5f, false); i.set("Looped", false, false); i.set("Playing", false, false) }
            cls == "UICorner" -> i.set("CornerRadius", UDim(0f, 8), false)
            cls == "UIStroke" -> { i.set("Color", Color3(0f, 0f, 0f), false); i.set("Thickness", 1f, false); i.set("Transparency", 0f, false) }
            cls == "UIListLayout" -> { i.set("Padding", UDim(0f, 0), false); i.set("FillDirection", EnumValue(1), false); i.set("SortOrder", EnumValue(2), false) }
            cls == "UIPadding" -> for (s in listOf("PaddingTop", "PaddingBottom", "PaddingLeft", "PaddingRight")) i.set(s, UDim(0f, 0), false)
            cls == "Attachment" -> i.set("CFrame", CFrame.IDENTITY, false)
            cls == "Weld" || cls == "Motor6D" -> {
                i.set("Part0", null, false); i.set("Part1", null, false)
                i.set("C0", CFrame.IDENTITY, false); i.set("C1", CFrame.IDENTITY, false)
            }
            cls == "WeldConstraint" -> { i.set("Part0Internal", null, false); i.set("Part1Internal", null, false) }
            cls == "ClickDetector" -> i.set("MaxActivationDistance", 32f, false)
            cls == "Decal" || cls == "Texture" -> { i.set("Texture", "", false); i.set("Face", EnumValue(5), false); i.set("Transparency", 0f, false) }
            cls == "SpecialMesh" -> { i.set("MeshType", EnumValue(6), false); i.set("Scale", Vector3.ONE, false); i.set("MeshId", "", false) }
            cls == "Tool" -> { i.set("RequiresHandle", true, false); i.set("CanBeDropped", true, false); i.set("ToolTip", "", false) }
            cls == "Camera" -> { i.set("CFrame", CFrame.IDENTITY, false); i.set("FieldOfView", 70f, false); i.set("CameraType", EnumValue(0), false) }
            cls == "Lighting" -> {
                i.set("ClockTime", 14f, false); i.set("Brightness", 2f, false)
                i.set("Ambient", Color3.fromRGB(70, 70, 70), false); i.set("OutdoorAmbient", Color3.fromRGB(128, 128, 128), false)
            }
            cls == "Workspace" -> { i.set("Gravity", 196.2f, false); i.set("FallenPartsDestroyHeight", -500f, false) }
            cls == "Players" -> { i.set("MaxPlayers", 12, false); i.set("RespawnTime", 5f, false) }
            cls == "ProximityPrompt" -> { i.set("ActionText", "Interact", false); i.set("ObjectText", "", false); i.set("MaxActivationDistance", 10f, false); i.set("HoldDuration", 0f, false) }
            cls == "ParticleEmitter" -> { i.set("Rate", 20f, false); i.set("Enabled", true, false) }
            cls == "Highlight" -> { i.set("FillColor", Color3(1f, 0f, 0f), false); i.set("OutlineColor", Color3(1f, 1f, 1f), false); i.set("FillTransparency", 0.5f, false); i.set("Enabled", true, false) }
            cls == "BillboardGui" || cls == "SurfaceGui" -> { i.set("Enabled", true, false); i.set("Adornee", null, false) }
            cls == "Team" -> { i.set("TeamColor", BrickColorV(194), false); i.set("AutoAssignable", true, false) }
            cls == "Animation" -> i.set("AnimationId", "", false)
            cls == "MaterialVariant" -> {
                i.set("BaseMaterial", EnumValue(256), false)
                for (m in listOf("ColorMap", "NormalMap", "MetalnessMap", "RoughnessMap")) i.set(m, "", false)
                i.set("StudsPerTile", 10f, false)
                i.set("MaterialPattern", EnumValue(0), false)
            }
            cls == "MaterialService" -> {
                // Studio's defaults: 2022 materials, and each material uses itself (no override).
                // Saved under Roblox's serialized name; shown and scripted as Use2022Materials.
                i.set("Use2022MaterialsXml", true, false)
                for (name in Enums.types["Material"]!!.keys) if (name !in setOf("Air", "Water", "Neon", "Glass", "ForceField")) i.set(name + "Name", name, false)
            }
        }
        extraDefaults(i, cls)
        return i
    }

    /** Roblox's default property values for the rest of the Insert Object classes. */
    private fun extraDefaults(i: Instance, cls: String) {
        fun s(n: String, v: Any?) { if (!i.props.containsKey(n)) i.set(n, v, false) }
        val v0 = Vector3.ZERO
        if (ClassDb.isA(cls, "Constraint")) {
            s("Attachment0", null); s("Attachment1", null); s("Enabled", true)
            // Studio's constraint tools make the visual ones visible.
            s("Visible", cls == "RopeConstraint" || cls == "RodConstraint" || cls == "SpringConstraint")
            s("Color", BrickColorV(194))
        }
        when (cls) {
            "Bone" -> s("CFrame", CFrame.IDENTITY)
            "RopeConstraint" -> { s("Length", 5f); s("Restitution", 0f); s("Thickness", 0.1f); s("WinchEnabled", false); s("WinchSpeed", 2f); s("WinchTarget", 5f); s("WinchForce", 10000f) }
            "RodConstraint" -> { s("Length", 5f); s("Thickness", 0.1f); s("LimitsEnabled", false); s("LimitAngle0", 90f); s("LimitAngle1", 90f) }
            "SpringConstraint" -> {
                s("FreeLength", 1f); s("Stiffness", 0f); s("Damping", 0f); s("MaxForce", Float.MAX_VALUE); s("LimitsEnabled", false)
                s("MinLength", 0f); s("MaxLength", 5f); s("Coils", 8f); s("Radius", 0.4f); s("Thickness", 0.1f)
            }
            "HingeConstraint" -> { s("ActuatorType", EnumValue(0)); s("AngularVelocity", 0f); s("MotorMaxTorque", 0f); s("AngularSpeed", 0f); s("ServoMaxTorque", 0f); s("TargetAngle", 0f); s("LimitsEnabled", false); s("LowerAngle", -45f); s("UpperAngle", 45f); s("Restitution", 0f) }
            "BallSocketConstraint" -> { s("LimitsEnabled", false); s("UpperAngle", 45f); s("TwistLimitsEnabled", false); s("Restitution", 0f) }
            "PrismaticConstraint", "CylindricalConstraint" -> { s("ActuatorType", EnumValue(0)); s("LimitsEnabled", false); s("LowerLimit", 0f); s("UpperLimit", 5f); s("Velocity", 0f); s("Speed", 0f); s("TargetPosition", 0f); s("MotorMaxForce", 0f); s("ServoMaxForce", 0f) }
            "AlignPosition" -> { s("MaxForce", 10000f); s("MaxVelocity", Float.MAX_VALUE); s("Responsiveness", 10f); s("RigidityEnabled", false); s("Mode", EnumValue(1)); s("Position", v0); s("ApplyAtCenterOfMass", false) }
            "AlignOrientation" -> { s("MaxTorque", 10000f); s("MaxAngularVelocity", Float.MAX_VALUE); s("Responsiveness", 10f); s("RigidityEnabled", false); s("Mode", EnumValue(1)); s("CFrame", CFrame.IDENTITY) }
            // ActuatorRelativeTo: Attachment0 = 0, Attachment1 = 1, World = 2.
            "LinearVelocity" -> { s("MaxForce", 1000f); s("VectorVelocity", v0); s("VelocityConstraintMode", EnumValue(2)); s("RelativeTo", EnumValue(2)); s("LineVelocity", 0f); s("ForceLimitsEnabled", true) }
            "AngularVelocity" -> { s("MaxTorque", 0f); s("AngularVelocity", v0); s("RelativeTo", EnumValue(2)) }
            "VectorForce" -> { s("Force", Vector3(1000f, 0f, 0f)); s("RelativeTo", EnumValue(0)); s("ApplyAtCenterOfMass", false) }
            "LineForce" -> { s("Magnitude", 1000f); s("ApplyAtCenterOfMass", false); s("InverseSquareLaw", false); s("ReactionForceEnabled", false) }
            "Torque" -> { s("Torque", v0); s("RelativeTo", EnumValue(0)) }
            "BodyVelocity" -> { s("MaxForce", Vector3(4000f, 4000f, 4000f)); s("Velocity", Vector3(0f, 2f, 0f)); s("P", 1250f) }
            "BodyPosition" -> { s("MaxForce", Vector3(4000f, 4000f, 4000f)); s("Position", Vector3(0f, 50f, 0f)); s("D", 1250f); s("P", 10000f) }
            "BodyGyro" -> { s("MaxTorque", Vector3(400000f, 0f, 400000f)); s("CFrame", CFrame.IDENTITY); s("D", 500f); s("P", 3000f) }
            "BodyForce" -> s("Force", v0)
            "BodyAngularVelocity" -> { s("AngularVelocity", Vector3(0f, 2f, 0f)); s("MaxTorque", Vector3(4000f, 4000f, 4000f)); s("P", 1250f) }
            "BodyThrust" -> { s("Force", v0); s("Location", v0) }
            "RocketPropulsion" -> { s("Target", null); s("MaxSpeed", 30f); s("MaxThrust", 4000f); s("ThrustP", 5f); s("ThrustD", 0.001f); s("CartoonFactor", 0.7f) }
            "Trail" -> { s("Attachment0", null); s("Attachment1", null); s("Enabled", true); s("Lifetime", 2f); s("MinLength", 0.1f); s("FaceCamera", false); s("Texture", ""); s("LightEmission", 0f) }
            "Beam" -> { s("Attachment0", null); s("Attachment1", null); s("Enabled", true); s("Width0", 1f); s("Width1", 1f); s("Segments", 10); s("CurveSize0", 0f); s("CurveSize1", 0f); s("FaceCamera", false); s("Texture", ""); s("LightEmission", 0f) }
            "Explosion" -> { s("BlastRadius", 4f); s("BlastPressure", 500000f); s("Position", v0); s("Visible", true); s("DestroyJointRadiusPercent", 1f) }
            "ForceField" -> s("Visible", true)
            "Fire" -> { s("Enabled", true); s("Heat", 9f); s("Size", 5f); s("Color", Color3.fromRGB(236, 139, 70)); s("SecondaryColor", Color3.fromRGB(139, 80, 55)) }
            "Smoke" -> { s("Enabled", true); s("Opacity", 0.5f); s("RiseVelocity", 1f); s("Size", 1f); s("Color", Color3(1f, 1f, 1f)) }
            "Sparkles" -> { s("Enabled", true); s("SparkleColor", Color3.fromRGB(144, 25, 255)) }
            "SelectionBox", "SelectionSphere" -> { s("Adornee", null); s("Color3", Color3.fromRGB(13, 105, 172)); s("LineThickness", 0.15f); s("Transparency", 0f); s("Visible", true); s("SurfaceTransparency", 1f) }
            "BoxHandleAdornment", "ConeHandleAdornment", "CylinderHandleAdornment", "SphereHandleAdornment", "LineHandleAdornment", "ImageHandleAdornment" -> {
                s("Adornee", null); s("Color3", Color3.fromRGB(13, 105, 172)); s("Transparency", 0f); s("AlwaysOnTop", false); s("Visible", true)
                s("CFrame", CFrame.IDENTITY); s("ZIndex", -1)
                when (cls) { "BoxHandleAdornment" -> s("Size", Vector3(1f, 1f, 1f)); "SphereHandleAdornment" -> s("Radius", 1f); "LineHandleAdornment" -> { s("Length", 5f); s("Thickness", 1f) }
                    "ConeHandleAdornment", "CylinderHandleAdornment" -> { s("Height", 2f); s("Radius", 0.5f) }; "ImageHandleAdornment" -> { s("Image", ""); s("Size", Vector2(1f, 1f)) } }
            }
            "Sky" -> {
                for (f in listOf("Bk", "Dn", "Ft", "Lf", "Rt", "Up")) s("Skybox$f", "rbxasset://textures/sky/sky512_${f.lowercase()}.tex")
                s("CelestialBodiesShown", true); s("StarCount", 3000); s("SunAngularSize", 21f); s("MoonAngularSize", 11f); s("SkyboxOrientation", v0)
                s("SunTextureId", "rbxasset://sky/sun.jpg"); s("MoonTextureId", "rbxasset://sky/moon.jpg")
            }
            "Atmosphere" -> { s("Density", 0.395f); s("Offset", 0f); s("Color", Color3.fromRGB(199, 170, 107)); s("Decay", Color3.fromRGB(92, 60, 13)); s("Glare", 0f); s("Haze", 0f) }
            "Clouds" -> { s("Cover", 0.5f); s("Density", 0.7f); s("Color", Color3(1f, 1f, 1f)); s("Enabled", true) }
            "BloomEffect" -> { s("Enabled", true); s("Intensity", 1f); s("Size", 24f); s("Threshold", 2f) }
            "BlurEffect" -> { s("Enabled", true); s("Size", 24f) }
            "ColorCorrectionEffect" -> { s("Enabled", true); s("Brightness", 0f); s("Contrast", 0f); s("Saturation", 0f); s("TintColor", Color3(1f, 1f, 1f)) }
            "DepthOfFieldEffect" -> { s("Enabled", true); s("FarIntensity", 0.75f); s("FocusDistance", 0.05f); s("InFocusRadius", 10f); s("NearIntensity", 0.75f) }
            "SunRaysEffect" -> { s("Enabled", true); s("Intensity", 0.25f); s("Spread", 1f) }
            "SoundGroup" -> s("Volume", 0.5f)
            "ChorusSoundEffect", "CompressorSoundEffect", "DistortionSoundEffect", "EchoSoundEffect", "EqualizerSoundEffect",
            "FlangeSoundEffect", "PitchShiftSoundEffect", "ReverbSoundEffect", "TremoloSoundEffect" -> { s("Enabled", true); s("Priority", 0) }
            "AudioPlayer" -> { s("Asset", ""); s("Volume", 1f); s("Looping", false); s("PlaybackSpeed", 1f); s("AutoLoad", true) }
            "AudioEmitter", "AudioListener" -> s("AudioInteractionGroup", "")
            "AudioFader" -> s("Volume", 1f)
            "Wire" -> { s("SourceInstance", null); s("TargetInstance", null) }
            "UIScale" -> s("Scale", 1f)
            "UISizeConstraint" -> { s("MaxSize", Vector2(Float.MAX_VALUE, Float.MAX_VALUE)); s("MinSize", Vector2(0f, 0f)) }
            "UITextSizeConstraint" -> { s("MaxTextSize", 100); s("MinTextSize", 1) }
            "UIAspectRatioConstraint" -> { s("AspectRatio", 1f); s("AspectType", EnumValue(0)); s("DominantAxis", EnumValue(0)) }
            "UIGridLayout" -> { s("CellSize", UDim2(0f, 100, 0f, 100)); s("CellPadding", UDim2(0f, 5, 0f, 5)); s("SortOrder", EnumValue(2)); s("FillDirection", EnumValue(0)) }
            "UIPageLayout" -> { s("Animated", true); s("Circular", false); s("Padding", UDim(0f, 0)); s("SortOrder", EnumValue(2)) }
            "UIGradient" -> { s("Enabled", true); s("Offset", Vector2(0f, 0f)); s("Rotation", 0f) }
            "BillboardGui" -> { s("Size", UDim2(0f, 200, 0f, 50)); s("AlwaysOnTop", false); s("StudsOffset", v0); s("MaxDistance", Float.MAX_VALUE); s("LightInfluence", 0f) }
            "SurfaceGui" -> { s("Face", EnumValue(5)); s("SizingMode", EnumValue(1)); s("PixelsPerStud", 50f); s("CanvasSize", Vector2(800f, 600f)); s("LightInfluence", 1f); s("AlwaysOnTop", false) }
            "AnimationController" -> {}
            "KeyframeSequence" -> { s("Loop", true); s("Priority", EnumValue(2)) }
            "HumanoidDescription" -> { s("HeadScale", 1f); s("HeightScale", 1f); s("WidthScale", 1f); s("DepthScale", 1f); s("BodyTypeScale", 0.3f); s("ProportionScale", 1f) }
            "Shirt" -> { s("ShirtTemplate", ""); s("Color3", Color3(1f, 1f, 1f)) }
            "Pants" -> { s("PantsTemplate", ""); s("Color3", Color3(1f, 1f, 1f)) }
            "ShirtGraphic" -> { s("Graphic", ""); s("Color3", Color3(1f, 1f, 1f)) }
            "BodyColors" -> for (k in listOf("HeadColor3", "LeftArmColor3", "RightArmColor3", "LeftLegColor3", "RightLegColor3", "TorsoColor3")) s(k, Color3.fromRGB(163, 162, 165))
            "Accessory" -> s("AttachmentPoint", CFrame.IDENTITY)
            "DragDetector" -> { s("Enabled", true); s("MaxActivationDistance", 32f); s("DragStyle", EnumValue(0)); s("ResponseStyle", EnumValue(1)) }
            "Dialog" -> { s("InitialPrompt", ""); s("GoodbyeDialog", ""); s("ConversationDistance", 25f); s("InUse", false) }
            "DialogChoice" -> { s("UserDialog", ""); s("ResponseDialog", ""); s("GoodbyeDialog", "") }
            "PathfindingModifier" -> { s("Label", ""); s("PassThrough", false) }
            "PathfindingLink" -> { s("Attachment0", null); s("Attachment1", null); s("IsBidirectional", true); s("Label", "") }
            "BrickColorValue" -> s("Value", BrickColorV(194))
            "RayValue" -> s("Value", Ray(v0, v0))
            "TextChatCommand" -> { s("PrimaryAlias", ""); s("SecondaryAlias", ""); s("Enabled", true) }
            "IKControl" -> { s("Enabled", true); s("Weight", 1f); s("ChainRoot", null); s("EndEffector", null); s("Target", null) }
            "SurfaceAppearance" -> { s("ColorMap", ""); s("NormalMap", ""); s("MetalnessMap", ""); s("RoughnessMap", ""); s("AlphaMode", EnumValue(0)) }
            "BlockMesh", "CylinderMesh" -> { s("Scale", Vector3.ONE); s("Offset", v0) }
            "VideoFrame" -> { s("Video", ""); s("Looped", false); s("Playing", false); s("Volume", 1f) }
            "ViewportFrame" -> { s("CurrentCamera", null); s("ImageColor3", Color3(1f, 1f, 1f)); s("Ambient", Color3.fromRGB(200, 200, 200)) }
            "CanvasGroup" -> { s("GroupColor3", Color3(1f, 1f, 1f)); s("GroupTransparency", 0f) }
        }
    }

    private fun defaultSource(cls: String) = when (cls) {
        "ModuleScript" -> "local module = {}\n\nreturn module\n"
        else -> "print(\"Hello world!\")\n"
    }

    private fun part(i: Instance, cls: String) {
        i.set("Anchored", false, false)
        i.set("CanCollide", true, false)
        i.set("CanTouch", true, false)
        i.set("CanQuery", true, false)
        i.set("CastShadow", true, false)
        i.set("Locked", false, false)
        i.set("Massless", false, false)
        i.set("CFrame", CFrame.IDENTITY, false)
        i.set("Transparency", 0f, false)
        i.set("Reflectance", 0f, false)
        i.set("Material", EnumValue(256), false)
        i.set("Color3uint8", GREY, false); i.propTypes["Color3uint8"] = PropType.COLOR3U8
        i.set("size", Vector3(4f, 1f, 2f), false)
        for (s in listOf("TopSurface", "BottomSurface", "FrontSurface", "BackSurface", "LeftSurface", "RightSurface")) i.set(s, EnumValue(0), false)
        when (cls) {
            "Part", "SpawnLocation", "Seat" -> i.set("shape", EnumValue(1), false)
            "TrussPart" -> i.set("size", Vector3(2f, 10f, 2f), false)
            "MeshPart" -> { i.set("MeshId", "", false); i.set("TextureID", "", false) }
        }
        if (cls == "SpawnLocation") {
            i.set("size", Vector3(12f, 1f, 12f), false)
            i.set("Anchored", true, false)
            i.set("Duration", 10, false)
            i.set("Neutral", true, false)
            i.set("Enabled", true, false)
        }
    }

    private fun gui(i: Instance, cls: String) {
        i.set("Position", UDim2(0f, 0, 0f, 0), false)
        i.set("Size", UDim2(0f, 100, 0f, 100), false)
        i.set("AnchorPoint", Vector2(0f, 0f), false)
        i.set("BackgroundColor3", Color3(1f, 1f, 1f), false)
        i.set("BackgroundTransparency", 0f, false)
        i.set("BorderColor3", Color3.fromRGB(27, 42, 53), false)
        i.set("BorderSizePixel", 1, false)
        i.set("Visible", true, false)
        i.set("ZIndex", 1, false)
        i.set("LayoutOrder", 0, false)
        i.set("ClipsDescendants", false, false)
        if (ClassDb.isA(cls, "GuiButton")) { i.set("AutoButtonColor", true, false); i.set("Modal", false, false) }
        if (cls == "TextLabel" || cls == "TextButton" || cls == "TextBox") {
            i.set("Size", UDim2(0f, 200, 0f, 50), false)
            i.set("Text", if (cls == "TextBox") "" else cls, false)
            i.set("TextColor3", Color3(0f, 0f, 0f), false)
            i.set("TextSize", 14f, false)
            i.set("TextScaled", false, false)
            i.set("TextWrapped", false, false)
            i.set("TextXAlignment", EnumValue(2), false)
            i.set("TextYAlignment", EnumValue(1), false)
            i.set("TextTransparency", 0f, false)
            i.set("Font", EnumValue(3), false)
            if (cls == "TextBox") { i.set("PlaceholderText", "", false); i.set("ClearTextOnFocus", true, false) }
        }
        if (cls == "ImageLabel" || cls == "ImageButton") {
            i.set("Image", "", false); i.set("ImageColor3", Color3(1f, 1f, 1f), false); i.set("ImageTransparency", 0f, false)
        }
        if (cls == "ScrollingFrame") { i.set("CanvasSize", UDim2(0f, 0, 2f, 0), false); i.set("ScrollBarThickness", 12, false) }
    }
}
