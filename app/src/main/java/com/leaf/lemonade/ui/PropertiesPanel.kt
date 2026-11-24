package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.leaf.lemonade.audio.AudioEngine
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.model.*
import com.leaf.lemonade.net.AssetFetcher
import kotlin.math.PI

/**
 * Properties window laid out like Roblox Studio's: the same categories in the same order
 * (Appearance, Data, Behavior, Transform, Pivot, Collision, Part, Surface, ...), properties sorted
 * alphabetically inside each, and every standard property of a part shown even when the file
 * didn't store it.
 */
@SuppressLint("ViewConstructor")
class PropertiesPanel(ctx: Context, private val studio: Studio) : LinearLayout(ctx), Studio.Listener {
    /** A row is either a category header (prop == null) or a property. */
    private class Row(val title: String, val prop: String?, val display: String, val value: Any?, val type: Int, val editable: Boolean, val action: (() -> Unit)? = null)

    private var rows = listOf<Row>()
    private val list = RecyclerView(ctx)
    private val adapter = Adapter()
    private val title: TextView
    private val collapsed = HashSet<String>()
    private val previewListener: () -> Unit = { post { adapter.notifyDataSetChanged() } }

    companion object {
        private const val DEG = (180.0 / PI).toFloat()
        private const val RAD = (PI / 180.0).toFloat()

        /** Studio's category order. */
        private val ORDER = listOf(
            "Appearance", "Data", "Text", "Image", "Behavior", "Transform", "Pivot", "Collision", "Part", "Surface", "Assembly",
            "Playback", "Emitter", "Character", "Health", "Movement", "Rigging", "Camera", "Controls", "Mobile", "Shape", "Emission",
            "Motion", "Particles", "Light", "Scripting", "Model", "Physics", "Attachments", "Constraints", "Input", "Layout",
            "State", "Teams", "Game", "Other",
        )

        /** Category of a property by its display name (Studio's reflection categories for common classes). */
        private val CATEGORY: Map<String, String> = HashMap<String, String>().apply {
            fun c(cat: String, vararg names: String) { for (n in names) put(n, cat) }
            c("Appearance", "BrickColor", "CastShadow", "Color", "Material", "MaterialVariant", "Reflectance", "Transparency",
                "BackgroundColor3", "BackgroundTransparency", "BorderColor3", "BorderMode", "BorderSizePixel", "Visible", "Texture",
                "Face", "Color3", "ZIndex", "TextureID", "DoubleSided", "RenderFidelity", "SmoothingAngle", "UsePartColor",
                "FillColor", "FillTransparency", "OutlineColor", "OutlineTransparency", "DepthMode", "LineThickness", "SurfaceColor3",
                "SurfaceTransparency", "StudsPerTileU", "StudsPerTileV", "OffsetStudsU", "OffsetStudsV", "LightInfluence", "Brightness",
                "Shadows", "Range", "Angle", "Thickness", "LineJoinMode", "CornerRadius", "Rotation", "Offset", "VertexColor", "Scale", "MeshType", "TextureId")
            c("Data", "ClassName", "Name", "Parent", "Source", "LinkedSource", "SoundId", "MeshId", "AnimationId", "Value", "Adornee",
                "PrimaryPart", "Part0", "Part1", "Attachment0", "Attachment1", "LayoutOrder", "SoundGroup", "TimeLength", "DisplayName",
                "Tags", "PlaceholderText", "Title", "Graphic", "ShirtTemplate", "PantsTemplate", "Tool", "ToolTip")
            c("Text", "Font", "FontFace", "LineHeight", "MaxVisibleGraphemes", "RichText", "Text", "TextColor3", "TextScaled", "TextSize",
                "TextStrokeColor3", "TextStrokeTransparency", "TextTransparency", "TextTruncate", "TextWrapped", "TextXAlignment",
                "TextYAlignment", "TextEditable", "ClearTextOnFocus", "MultiLine", "PlaceholderColor3", "ShowNativeInput")
            c("Image", "Image", "ImageColor3", "ImageRectOffset", "ImageRectSize", "ImageTransparency", "ResampleMode", "ScaleType",
                "SliceCenter", "SliceScale", "TileSize", "HoverImage", "PressedImage")
            c("Behavior", "Archivable", "Locked", "Active", "AutoButtonColor", "ClipsDescendants", "Draggable", "Interactable", "Selectable",
                "Disabled", "Enabled", "RunContext", "ResetOnSpawn", "IgnoreGuiInset", "ZIndexBehavior", "DisplayOrder", "AlwaysOnTop",
                "Modal", "Selected", "AutoLocalize", "ScreenInsets", "SafeAreaCompatibility", "CanBeDropped", "ManualActivationOnly",
                "RequiresHandle", "MaxActivationDistance", "HoldDuration", "KeyboardKeyCode", "ActionText", "ObjectText", "RequiresLineOfSight", "Exclusivity", "Style")
            c("Transform", "Size", "Position", "Orientation", "CFrame", "AnchorPoint", "SizeConstraint", "AutomaticSize")
            c("Pivot", "PivotOffset", "WorldPivot", "Pivot Offset", "Origin")
            c("Collision", "CanCollide", "CanQuery", "CanTouch", "CollisionGroup", "CollisionGroupId", "CollisionFidelity", "AudioCanCollide")
            c("Part", "Anchored", "CustomPhysicalProperties", "Massless", "RootPriority", "Shape", "EnableFluidForces", "FormFactor")
            c("Surface", "BackSurface", "BottomSurface", "FrontSurface", "LeftSurface", "RightSurface", "TopSurface",
                "BackSurfaceInput", "BottomSurfaceInput", "FrontSurfaceInput", "LeftSurfaceInput", "RightSurfaceInput", "TopSurfaceInput")
            c("Assembly", "AssemblyLinearVelocity", "AssemblyAngularVelocity", "Velocity", "RotVelocity")
            c("Playback", "Looped", "PlaybackSpeed", "Playing", "TimePosition", "Volume", "PlayOnRemove", "PlaybackRegionsEnabled", "Pitch")
            c("Emitter", "RollOffMaxDistance", "RollOffMinDistance", "RollOffMode", "EmitterSize", "MaxDistance", "MinDistance")
            c("Health", "Health", "MaxHealth", "BreakJointsOnDeath", "HealthDisplayDistance", "HealthDisplayType")
            c("Movement", "WalkSpeed", "HipHeight", "MaxSlopeAngle", "AutoRotate", "AutoJumpEnabled", "JumpPower", "JumpHeight", "UseJumpPower", "PlatformStand", "Sit")
            c("Rigging", "RigType", "RequiresNeck", "AutomaticScalingEnabled")
            c("Camera", "CameraType", "FieldOfView", "FieldOfViewMode", "CameraSubject", "Focus", "HeadLocked", "HeadScale", "DiagonalFieldOfView", "MaxAxisFieldOfView")
            c("Emission", "Rate", "Lifetime", "Speed", "SpreadAngle", "EmissionDirection", "Drag", "Acceleration", "LockedToPart", "VelocityInheritance", "ZOffset", "Squash", "TimeScale")
            c("Light", "Ambient", "OutdoorAmbient", "ClockTime", "GeographicLatitude", "TimeOfDay", "ExposureCompensation", "GlobalShadows",
                "FogColor", "FogEnd", "FogStart", "EnvironmentDiffuseScale", "EnvironmentSpecularScale", "ColorShift_Bottom", "ColorShift_Top", "Technology", "ShadowSoftness")
            c("Game", "Gravity", "FallenPartsDestroyHeight", "StreamingEnabled", "RespawnTime", "MaxPlayers", "CharacterAutoLoads")
            c("Teams", "TeamColor", "AutoAssignable", "Neutral", "AllowTeamChangeOnTouch", "Duration")
            c("Layout", "Padding", "FillDirection", "HorizontalAlignment", "VerticalAlignment", "SortOrder", "CellSize", "CellPadding",
                "PaddingTop", "PaddingBottom", "PaddingLeft", "PaddingRight", "AspectRatio", "AspectType", "DominantAxis", "MinSize", "MaxSize", "Wraps")
            c("Model", "LevelOfDetail", "ModelStreamingMode", "WorldPivotData")
        }

        private val surfaceProps = listOf("TopSurface", "BottomSurface", "FrontSurface", "BackSurface", "LeftSurface", "RightSurface")
        private val skip = setOf("AttributesSerialize", "Tags", "SourceAssetId", "HistoryId", "UniqueId", "ScriptGuid", "Capabilities",
            "DefinesCapabilities", "MeshContent", "TextureContent", "PhysicsData", "PhysicalConfigData", "ChildData", "MeshData", "InitialSize",
            "ModelMeshData", "ModelMeshSize", "ModelMeshCFrame", "LevelOfDetail")
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.PANEL)
        val h = Ui.header(ctx, "Properties", null)
        title = h.getChildAt(0) as TextView
        addView(h, Ui.lp(Ui.MATCH, Ui.WRAP))
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.itemAnimator = null
        addView(list, Ui.lp(Ui.MATCH, 0, 1f))
        rebuild()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); AudioEngine.previewListeners.add(previewListener) }
    override fun onDetachedFromWindow() { AudioEngine.previewListeners.remove(previewListener); super.onDetachedFromWindow() }

    override fun onSelectionChanged() = rebuild()
    override fun onPropsChanged() = rebuild()
    override fun onModelReplaced() = rebuild()

    private fun categoryOf(inst: Instance, disp: String, t: Int): String {
        CATEGORY[disp]?.let { cat ->
            // A few names live in different categories depending on the class.
            if (disp == "Size" || disp == "Position" || disp == "Rotation") return if (inst.isA("GuiObject") || inst.isA("BasePart")) "Transform" else "Data"
            if (disp == "Brightness" && inst.className == "Lighting") return "Light"
            if (disp == "Color" && inst.isA("Light")) return "Appearance"
            if (disp == "Enabled" && inst.isA("Light")) return "Appearance"
            if (disp == "Texture" && inst.className == "Decal") return "Appearance"
            return cat
        }
        if (inst.isA("ParticleEmitter") || inst.className == "Trail" || inst.className == "Beam") return "Appearance"
        return when (t) {
            PropType.REF, PropType.STRING, PropType.CONTENT -> "Data"
            PropType.BOOL -> "Behavior"
            else -> "Data"
        }
    }

    fun rebuild() {
        val out = ArrayList<Row>()
        synchronized(studio.lock) {
            val inst = studio.selection.firstOrNull()
            if (inst == null) {
                title.text = "Properties"
            } else {
                title.text = if (studio.selection.size > 1) "Properties - ${studio.selection.size} selected" else "Properties - ${inst.className} \"${inst.name}\""
                val cats = LinkedHashMap<String, ArrayList<Row>>()
                fun add(cat: String, r: Row) = cats.getOrPut(cat) { ArrayList() }.add(r)
                val shown = HashSet<String>()
                val isPart = inst.isA("BasePart")
                if (inst.className == "Sound") {
                    val id = AssetFetcher.idOf(inst["SoundId"])
                    if (id != null) {
                        val p = AudioEngine.preview
                        val playing = p != null && p.id == id && (p.state == AudioEngine.PreviewState.PLAYING || p.state == AudioEngine.PreviewState.LOADING)
                        add("Data", Row("", "@Preview", "Preview", if (playing) "■ Stop" else "▶ Play", PropType.STRING, true) {
                            AudioEngine.togglePreview(id, ((inst["Volume"] as? Number)?.toFloat() ?: 0.5f))
                        })
                    }
                }
                add("Data", Row("", "Name", "Name", inst.name, PropType.STRING, !inst.isService)); shown += "Name"
                add("Data", Row("", "ClassName", "ClassName", inst.className, PropType.STRING, false))
                add("Data", Row("", "Parent", "Parent", inst.parent, PropType.REF, false))
                add("Behavior", Row("", "@Archivable", "Archivable", inst.archivable, PropType.BOOL, !inst.isService))
                if (inst.isA("LuaSourceContainer")) { add("Data", Row("", "@Source", "Source", "Open in editor…", PropType.STRING, true)); shown += "Source" }
                if (isPart) {
                    val cf = PartData.cframe(inst)
                    add("Transform", Row("", "@Position", "Position", cf.position, PropType.VECTOR3, true))
                    add("Transform", Row("", "@Orientation", "Orientation", cf.toOrientation() * DEG, PropType.VECTOR3, true))
                    add("Transform", Row("", if (inst.props.containsKey("size")) "size" else "Size", "Size", PartData.size(inst), PropType.VECTOR3, true))
                    shown += listOf("CFrame", "size", "Size", "Position", "Orientation")
                    // The standard part properties, always listed (files often omit defaults).
                    val color = PartData.color(inst)
                    add("Appearance", Row("", "@BrickColor", "BrickColor", inst["BrickColor"] as? BrickColorV ?: BrickColorV(BrickColors.closest(color)), PropType.BRICKCOLOR, true))
                    add("Appearance", Row("", if (inst.props.containsKey("Color")) "Color" else "Color3uint8", "Color", color, PropType.COLOR3U8, true))
                    add("Appearance", Row("", "Material", "Material", inst["Material"] ?: EnumValue(256), PropType.ENUM, true))
                    val mvKey = if (inst.props.containsKey("MaterialVariantSerialized")) "MaterialVariantSerialized" else "MaterialVariant"
                    add("Appearance", Row("", mvKey, "MaterialVariant", inst[mvKey] ?: "", PropType.STRING, true))
                    add("Appearance", Row("", "Reflectance", "Reflectance", inst["Reflectance"] ?: 0f, PropType.FLOAT, true))
                    add("Appearance", Row("", "Transparency", "Transparency", inst["Transparency"] ?: 0f, PropType.FLOAT, true))
                    add("Appearance", Row("", "CastShadow", "CastShadow", inst["CastShadow"] ?: true, PropType.BOOL, true))
                    shown += listOf("BrickColor", "Color", "Color3uint8", "Material", "MaterialVariant", "MaterialVariantSerialized", "Reflectance", "Transparency", "CastShadow")
                    add("Behavior", Row("", "Locked", "Locked", inst["Locked"] ?: false, PropType.BOOL, true))
                    add("Collision", Row("", "CanCollide", "CanCollide", inst["CanCollide"] ?: true, PropType.BOOL, true))
                    add("Collision", Row("", "CanQuery", "CanQuery", inst["CanQuery"] ?: true, PropType.BOOL, true))
                    add("Collision", Row("", "CanTouch", "CanTouch", inst["CanTouch"] ?: true, PropType.BOOL, true))
                    add("Collision", Row("", "CollisionGroup", "CollisionGroup", inst["CollisionGroup"] ?: "Default", PropType.STRING, true))
                    add("Part", Row("", "Anchored", "Anchored", inst["Anchored"] ?: false, PropType.BOOL, true))
                    add("Part", Row("", "Massless", "Massless", inst["Massless"] ?: false, PropType.BOOL, true))
                    add("Part", Row("", "RootPriority", "RootPriority", inst["RootPriority"] ?: 0, PropType.INT, true))
                    if (inst.className == "Part" || inst.className == "SpawnLocation" || inst.className == "Seat")
                        add("Part", Row("", if (inst.props.containsKey("Shape")) "Shape" else "shape", "Shape", inst["shape"] ?: inst["Shape"] ?: EnumValue(1), PropType.ENUM, true))
                    shown += listOf("Locked", "CanCollide", "CanQuery", "CanTouch", "CollisionGroup", "Anchored", "Massless", "RootPriority", "shape", "Shape")
                    // Unions and MeshParts: how they collide and how detailed they draw (Studio shows these).
                    if ((inst.isA("PartOperation") && inst.className != "NegateOperation") || inst.className == "MeshPart") {
                        add("Collision", Row("", "CollisionFidelity", "CollisionFidelity", inst["CollisionFidelity"] ?: EnumValue(0), PropType.ENUM, true))
                        add("Appearance", Row("", "RenderFidelity", "RenderFidelity", inst["RenderFidelity"] ?: EnumValue(0), PropType.ENUM, true))
                        shown += listOf("CollisionFidelity", "RenderFidelity")
                    }
                    // Every face's surface, even when the file only stored some of them.
                    if (!inst.isA("PartOperation") && inst.className != "MeshPart" && inst.className != "Terrain")
                        for (s in surfaceProps) add("Surface", Row("", s, s, inst[s] ?: EnumValue(0), PropType.ENUM, true))
                    shown += surfaceProps
                    add("Pivot", Row("", "PivotOffset", "Pivot Offset", (inst["PivotOffset"] as? CFrame)?.position ?: Vector3.ZERO, PropType.VECTOR3, true))
                    shown += "PivotOffset"
                }
                for ((k, v) in inst.props) {
                    if (k in shown || k in skip) continue
                    if (k.startsWith("xmlRead") && v == null) continue
                    val t = inst.propTypes[k] ?: PropType.infer(v)
                    if (PropType.hidden(t, v)) continue
                    val disp = PropNames.display(k)
                    if (disp in shown) continue
                    add(categoryOf(inst, disp, t), Row("", k, disp, v, t, true))
                }
                for (cat in ORDER + cats.keys.filter { it !in ORDER }) {
                    val items = cats[cat] ?: continue
                    if (items.isEmpty()) continue
                    out.add(Row(cat, null, cat, null, 0, false))
                    if (cat in collapsed) continue
                    // Studio lists properties alphabetically (the Preview action stays on top).
                    out.addAll(items.sortedWith(compareBy({ if (it.prop == "@Preview") 0 else 1 }, { it.display.lowercase() })))
                }
                if (inst.attributes.isNotEmpty() || inst.props.containsKey("AttributesSerialize")) {
                    com.leaf.lemonade.lua.Attributes.load(inst)
                    if (inst.attributes.isNotEmpty()) {
                        out.add(Row("Attributes", null, "Attributes", null, 0, false))
                        if ("Attributes" !in collapsed) for ((k, v) in inst.attributes.entries.sortedBy { it.key.lowercase() }) {
                            if (k.startsWith("__")) continue
                            out.add(Row("", "@attr:$k", k, v, PropType.infer(v), false))
                        }
                    }
                }
            }
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    private fun valueText(r: Row, inst: Instance?): String {
        val v = r.value
        return when {
            v == null -> if (r.type == PropType.REF) "" else "nil"
            v is Instance -> v.name
            v is EnumValue -> Enums.itemName(Enums.typeForProp(inst?.className ?: "", r.prop ?: ""), v.value)
                ?: (if (r.display == "Material") Enums.itemName("Material", v.value) else null)
                ?: (if (r.display.endsWith("Surface")) Enums.itemName("SurfaceType", v.value) else null)
                ?: (if (r.display == "Shape") Enums.itemName("PartType", v.value) else null)
                ?: v.value.toString()
            v is BrickColorV -> v.name
            v is Color3 -> "[${(v.r * 255).toInt()}, ${(v.g * 255).toInt()}, ${(v.b * 255).toInt()}]"
            v is Float -> fmtNum(v)
            v is Double -> fmtNum(v)
            v is Vector3 -> "${fmtNum(v.x)}, ${fmtNum(v.y)}, ${fmtNum(v.z)}"
            v is CFrame -> v.position.toString()
            v is String -> v.replace('\n', ' ').take(120)
            else -> v.toString()
        }
    }

    private fun applyEdit(r: Row, newValue: Any?) {
        val prop = r.prop ?: return
        synchronized(studio.lock) {
            studio.undo.begin("Change ${r.display}")
            for (inst in studio.selection.toList()) {
                when (prop) {
                    "@Position" -> if (inst.isA("BasePart")) studio.setProp(inst, "CFrame", PartData.cframe(inst).withPosition(newValue as Vector3))
                    "@Orientation" -> if (inst.isA("BasePart")) {
                        val o = (newValue as Vector3) * RAD
                        studio.setProp(inst, "CFrame", CFrame.fromOrientation(o.x, o.y, o.z).withPosition(PartData.cframe(inst).position))
                    }
                    "@Archivable" -> if (!inst.isService) inst.archivable = newValue == true
                    "@BrickColor" -> if (inst.isA("BasePart")) {
                        val b = newValue as BrickColorV
                        val key = if (inst.props.containsKey("Color")) "Color" else "Color3uint8"
                        studio.setProp(inst, key, b.color)
                        inst.propTypes.putIfAbsent(key, PropType.COLOR3U8)
                        if (inst.props.containsKey("BrickColor")) studio.setProp(inst, "BrickColor", b)
                    }
                    "PivotOffset" -> if (inst.isA("BasePart")) studio.setProp(inst, "PivotOffset", ((inst["PivotOffset"] as? CFrame) ?: CFrame.IDENTITY).withPosition(newValue as Vector3))
                    "Name" -> if (!inst.isService) studio.setProp(inst, "Name", newValue)
                    "size", "Size" -> if (inst.isA("BasePart")) {
                        val key = if (inst.props.containsKey("size")) "size" else "Size"
                        studio.setProp(inst, key, newValue)
                    } else if (inst.props.containsKey(prop)) studio.setProp(inst, prop, newValue)
                    else -> if (inst.props.containsKey(prop) || inst === studio.selection.first() || inst.isA("BasePart")) {
                        studio.setProp(inst, prop, newValue)
                        if (r.type == PropType.COLOR3U8 && newValue is Color3) inst.propTypes.putIfAbsent(prop, PropType.COLOR3U8)
                        if (r.type == PropType.ENUM && newValue is EnumValue) inst.propTypes.putIfAbsent(prop, PropType.ENUM)
                        if ((prop == "Color3uint8" || prop == "Color") && inst.props.containsKey("BrickColor") && newValue is Color3) studio.setProp(inst, "BrickColor", BrickColorV(BrickColors.closest(newValue)))
                    }
                }
            }
            studio.undo.commit()
            studio.markDirty()
        }
        rebuild()
    }

    private fun edit(r: Row) {
        r.action?.let { it(); rebuild(); return }
        val inst = studio.selection.firstOrNull() ?: return
        if (r.prop == "@Source") { studio.openScript(inst); return }
        if (r.type == PropType.REF) { (r.value as? Instance)?.let { studio.select(listOf(it)) }; return }
        if (!r.editable) return
        if (r.type == PropType.BOOL) { applyEdit(r, r.value != true); return }
        val enumType = when {
            r.display == "Material" -> "Material"
            r.display.endsWith("Surface") && inst.isA("BasePart") -> "SurfaceType"
            r.display == "Shape" && inst.isA("BasePart") -> "PartType"
            else -> Enums.typeForProp(inst.className, r.prop ?: "")
        }
        Dialogs.editValue(context, r.display, r.value, r.type, enumType) { applyEdit(r, it) }
    }

    private inner class VH(val row: LinearLayout, val name: TextView, val swatch: android.view.View, val value: TextView) : RecyclerView.ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(ctx, 28f)
                layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP)
            }
            val name = Ui.text(ctx, "", 12f, Ui.TEXT).apply { setPadding(Ui.dp(ctx, 22f), 0, Ui.dp(ctx, 4f), 0); ellipsize = android.text.TextUtils.TruncateAt.END }
            val sw = android.view.View(ctx)
            val value = Ui.text(ctx, "", 12f, Ui.TEXT).apply { setPadding(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 6f), 0); ellipsize = android.text.TextUtils.TruncateAt.END }
            row.addView(name, LayoutParams(0, Ui.WRAP, 0.45f))
            row.addView(Ui.divider(ctx, true))
            row.addView(sw, LayoutParams(Ui.dp(ctx, 14f), Ui.dp(ctx, 14f)).apply { leftMargin = Ui.dp(ctx, 6f) })
            row.addView(value, LayoutParams(0, Ui.WRAP, 0.55f))
            return VH(row, name, sw, value)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val ctx = h.row.context
            val dsz = Ui.dp(ctx, 14f)
            if (r.prop == null) {
                h.name.text = r.title
                h.name.setPadding(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 4f), 0)
                h.name.setCompoundDrawables(Ui.ArrowDrawable(r.title !in collapsed, Ui.TEXT).apply { setBounds(0, 0, dsz, dsz) }, null, null, null)
                h.name.compoundDrawablePadding = Ui.dp(ctx, 4f)
                h.value.setCompoundDrawables(null, null, null, null)
                h.name.setTextColor(Ui.TEXT)
                h.name.setTypeface(null, android.graphics.Typeface.BOLD)
                h.name.textSize = 12.5f
                h.name.layoutParams = LayoutParams(0, Ui.WRAP, 1f)
                h.value.visibility = GONE; h.swatch.visibility = GONE
                h.row.getChildAt(1).visibility = GONE
                h.row.setBackgroundColor(Ui.HEADER)
                h.row.setOnClickListener { if (!collapsed.add(r.title)) collapsed.remove(r.title); rebuild() }
                return
            }
            h.row.getChildAt(1).visibility = VISIBLE
            h.name.setPadding(Ui.dp(ctx, 22f), 0, Ui.dp(ctx, 4f), 0)
            h.name.layoutParams = LayoutParams(0, Ui.WRAP, 0.45f)
            h.name.setTypeface(null, android.graphics.Typeface.NORMAL)
            h.name.textSize = 12.5f
            h.name.setCompoundDrawables(null, null, null, null)
            h.name.text = r.display
            h.name.setTextColor(if (r.editable || r.action != null) Ui.TEXT else Ui.blend(Ui.TEXT, Ui.PANEL, 0.62f))
            h.value.visibility = VISIBLE
            h.row.setBackgroundColor(Ui.PANEL)
            val inst = studio.selection.firstOrNull()
            val color = when (val v = r.value) { is Color3 -> v; is BrickColorV -> v.color; else -> null }
            if (color != null) {
                h.swatch.visibility = VISIBLE
                h.swatch.background = GradientDrawable().apply { setColor(color.toArgb()); setStroke(Ui.dp(ctx, 1f), 0xFF888888.toInt()) }
            } else h.swatch.visibility = GONE
            if (r.type == PropType.BOOL) {
                h.value.text = ""
                h.value.setCompoundDrawables(Ui.CheckBoxDrawable(r.value == true, ctx.resources.displayMetrics.density).apply { setBounds(0, 0, dsz, dsz) }, null, null, null)
                h.value.compoundDrawablePadding = Ui.dp(ctx, 6f)
            } else {
                h.value.setCompoundDrawables(null, null, null, null)
                h.value.text = valueText(r, inst)
            }
            h.value.setTextColor(when { r.action != null -> Ui.TEXT; r.type == PropType.REF || !r.editable -> Ui.blend(Ui.TEXT, Ui.PANEL, 0.62f); else -> Ui.TEXT })
            h.row.setOnClickListener { edit(r) }
        }
    }
}
