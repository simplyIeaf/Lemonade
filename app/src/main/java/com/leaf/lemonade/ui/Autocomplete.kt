package com.leaf.lemonade.ui

import com.leaf.lemonade.model.ClassDb
import com.leaf.lemonade.model.DataModel
import com.leaf.lemonade.model.Enums
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.PropNames

/**
 * Luau autocomplete for the script editor, like Studio's: keywords, globals, libraries, services,
 * datatypes, Enum, members of objects (resolved through the place when possible, e.g.
 * `game.Workspace.Model.` lists its real children), locals, and string arguments such as
 * GetService("…"), Instance.new("…") and WaitForChild("…").
 */
object Autocomplete {
    enum class Kind(val badge: String, val color: Int) {
        KEYWORD("kw", 0xFFF86D7C.toInt()), GLOBAL("g", 0xFF84D6F7.toInt()), LIBRARY("lib", 0xFF84D6F7.toInt()),
        FUNCTION("fn", 0xFFFDFB9D.toInt()), METHOD("m", 0xFFFDFB9D.toInt()), PROPERTY("p", 0xFF9CDCFE.toInt()),
        EVENT("ev", 0xFFFFC600.toInt()), CLASS("c", 0xFF4EC9B0.toInt()), ENUM("e", 0xFFB5CEA8.toInt()),
        SERVICE("s", 0xFF4EC9B0.toInt()), VARIABLE("v", 0xFFCCCCCC.toInt()), CHILD("ch", 0xFFDCDCAA.toInt()),
    }

    class Item(val label: String, val kind: Kind, val detail: String = "", val insert: String = label)
    class Result(val start: Int, val end: Int, val prefix: String, val items: List<Item>)

    // ------------------------------------------------------------------ static data

    private val keywords = listOf("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "if", "in", "local", "nil",
        "not", "or", "repeat", "return", "then", "true", "until", "while", "continue", "export", "type")
    private val globals = listOf("game", "workspace", "script", "plugin", "shared", "_G", "print", "warn", "error", "assert", "pcall", "xpcall",
        "typeof", "type", "tostring", "tonumber", "pairs", "ipairs", "next", "select", "unpack", "require", "setmetatable", "getmetatable",
        "rawget", "rawset", "rawequal", "rawlen", "wait", "spawn", "delay", "tick", "time", "elapsedTime", "collectgarbage", "gcinfo", "newproxy", "version", "settings", "UserSettings")
    private val libs: Map<String, List<String>> = mapOf(
        "math" to listOf("abs", "acos", "asin", "atan", "atan2", "ceil", "clamp", "cos", "cosh", "deg", "exp", "floor", "fmod", "frexp", "huge", "ldexp",
            "log", "log10", "max", "min", "modf", "noise", "pi", "pow", "rad", "random", "randomseed", "round", "sign", "sin", "sinh", "sqrt", "tan", "tanh", "lerp", "map"),
        "string" to listOf("byte", "char", "find", "format", "gmatch", "gsub", "len", "lower", "match", "rep", "reverse", "split", "sub", "upper", "pack", "packsize", "unpack"),
        "table" to listOf("clear", "clone", "concat", "create", "find", "freeze", "insert", "isfrozen", "maxn", "move", "pack", "remove", "sort", "unpack"),
        "task" to listOf("wait", "spawn", "defer", "delay", "cancel", "synchronize", "desynchronize"),
        "coroutine" to listOf("create", "resume", "running", "status", "wrap", "yield", "isyieldable", "close"),
        "os" to listOf("time", "clock", "date", "difftime"),
        "bit32" to listOf("band", "bor", "bxor", "bnot", "lshift", "rshift", "arshift", "btest", "extract", "replace", "lrotate", "rrotate", "countlz", "countrz"),
        "utf8" to listOf("char", "charpattern", "codepoint", "codes", "len", "offset"),
        "debug" to listOf("traceback", "info", "profilebegin", "profileend"),
        "buffer" to listOf("create", "fromstring", "tostring", "len", "readi8", "readu8", "readi32", "readu32", "readf32", "readf64", "writei8", "writeu8", "writei32", "writeu32", "writef32", "writef64", "readstring", "writestring", "copy", "fill"),
    )
    private val dataStatics: Map<String, List<String>> = mapOf(
        "Vector3" to listOf("new", "zero", "one", "xAxis", "yAxis", "zAxis", "FromNormalId", "FromAxis"),
        "Vector2" to listOf("new", "zero", "one", "xAxis", "yAxis"),
        "CFrame" to listOf("new", "Angles", "fromEulerAnglesXYZ", "fromEulerAnglesYXZ", "fromOrientation", "fromAxisAngle", "lookAt", "fromMatrix", "identity", "lookAlong"),
        "Color3" to listOf("new", "fromRGB", "fromHSV", "fromHex"),
        "UDim2" to listOf("new", "fromScale", "fromOffset"),
        "UDim" to listOf("new"),
        "BrickColor" to listOf("new", "random", "palette", "White", "Black", "Red", "Blue", "Green", "Yellow", "Gray", "DarkGray"),
        "Instance" to listOf("new", "fromExisting"),
        "TweenInfo" to listOf("new"), "Ray" to listOf("new"), "NumberRange" to listOf("new"), "NumberSequence" to listOf("new"),
        "ColorSequence" to listOf("new"), "NumberSequenceKeypoint" to listOf("new"), "ColorSequenceKeypoint" to listOf("new"),
        "Random" to listOf("new"), "Rect" to listOf("new"), "Region3" to listOf("new"), "RaycastParams" to listOf("new"),
        "OverlapParams" to listOf("new"), "DateTime" to listOf("now", "fromUnixTimestamp", "fromUnixTimestampMillis", "fromIsoDate", "fromLocalTime", "fromUniversalTime"),
        "Font" to listOf("new", "fromEnum", "fromName", "fromId"), "PhysicalProperties" to listOf("new"), "Axes" to listOf("new"), "Faces" to listOf("new"),
    )
    /** Members of datatype values (after .new(...)): properties, then methods after ":". */
    private val dataMembers: Map<String, Pair<List<String>, List<String>>> = mapOf(
        "Vector3" to (listOf("X", "Y", "Z", "Magnitude", "Unit") to listOf("Dot", "Cross", "Lerp", "FuzzyEq", "Abs", "Ceil", "Floor", "Sign", "Angle", "Max", "Min")),
        "Vector2" to (listOf("X", "Y", "Magnitude", "Unit") to listOf("Dot", "Cross", "Lerp", "FuzzyEq", "Abs", "Ceil", "Floor", "Sign", "Angle", "Max", "Min")),
        "CFrame" to (listOf("Position", "LookVector", "RightVector", "UpVector", "XVector", "YVector", "ZVector", "X", "Y", "Z", "Rotation") to
            listOf("Inverse", "Lerp", "Orthonormalize", "ToWorldSpace", "ToObjectSpace", "PointToWorldSpace", "PointToObjectSpace", "VectorToWorldSpace",
                "VectorToObjectSpace", "GetComponents", "ToEulerAnglesXYZ", "ToEulerAnglesYXZ", "ToOrientation", "ToAxisAngle", "FuzzyEq")),
        "Color3" to (listOf("R", "G", "B") to listOf("Lerp", "ToHSV", "ToHex")),
        "UDim2" to (listOf("X", "Y", "Width", "Height") to listOf("Lerp")),
        "UDim" to (listOf("Scale", "Offset") to emptyList()),
        "BrickColor" to (listOf("Name", "Number", "Color", "r", "g", "b") to emptyList()),
        "Random" to (emptyList<String>() to listOf("NextNumber", "NextInteger", "NextUnitVector", "Shuffle", "Clone")),
        "RaycastParams" to (listOf("FilterDescendantsInstances", "FilterType", "IgnoreWater", "CollisionGroup", "RespectCanCollide", "BruteForceAllSlow") to listOf("AddToFilter")),
        "OverlapParams" to (listOf("FilterDescendantsInstances", "FilterType", "MaxParts", "CollisionGroup", "RespectCanCollide") to listOf("AddToFilter")),
        "Ray" to (listOf("Origin", "Direction", "Unit") to listOf("ClosestPoint", "Distance")),
        "TweenInfo" to (listOf("Time", "EasingStyle", "EasingDirection", "RepeatCount", "Reverses", "DelayTime") to emptyList()),
        "RaycastResult" to (listOf("Instance", "Position", "Normal", "Material", "Distance") to emptyList()),
        "Tween" to (listOf("PlaybackState", "TweenInfo", "Instance") to listOf("Play", "Pause", "Cancel")),
        "RBXScriptSignal" to (emptyList<String>() to listOf("Connect", "Once", "Wait", "ConnectParallel")),
        "RBXScriptConnection" to (listOf("Connected") to listOf("Disconnect")),
        "AnimationTrack" to (listOf("IsPlaying", "Length", "Looped", "Priority", "Speed", "TimePosition", "WeightCurrent", "Animation") to listOf("Play", "Stop", "AdjustSpeed", "AdjustWeight", "GetMarkerReachedSignal", "GetTimeOfKeyframe")),
        "DataStore" to (emptyList<String>() to listOf("GetAsync", "SetAsync", "UpdateAsync", "RemoveAsync", "IncrementAsync", "GetSortedAsync", "ListKeysAsync")),
        "Path" to (listOf("Status") to listOf("ComputeAsync", "GetWaypoints", "CheckOcclusionAsync")),
        "Mouse" to (listOf("Hit", "Target", "X", "Y", "Origin", "UnitRay", "Icon", "TargetFilter", "ViewSizeX", "ViewSizeY") to emptyList()),
        "InputObject" to (listOf("UserInputType", "UserInputState", "KeyCode", "Position", "Delta") to listOf("IsModifierKeyDown")),
    )

    private class Members(val props: List<String>, val methods: List<String>, val events: List<String>)
    private fun m(props: String, methods: String = "", events: String = "") =
        Members(props.split(' ').filter { it.isNotBlank() }, methods.split(' ').filter { it.isNotBlank() }, events.split(' ').filter { it.isNotBlank() })

    private val classMembers: Map<String, Members> = mapOf(
        "Instance" to m("Name Parent ClassName Archivable",
            "FindFirstChild WaitForChild GetChildren GetDescendants Destroy Clone IsA FindFirstChildOfClass FindFirstChildWhichIsA FindFirstAncestor FindFirstAncestorOfClass FindFirstAncestorWhichIsA FindFirstDescendant GetAttribute SetAttribute GetAttributes GetAttributeChangedSignal GetPropertyChangedSignal ClearAllChildren GetFullName IsDescendantOf IsAncestorOf AddTag RemoveTag HasTag GetTags",
            "Changed ChildAdded ChildRemoved DescendantAdded DescendantRemoving AncestryChanged Destroying AttributeChanged"),
        "PVInstance" to m("", "GetPivot PivotTo"),
        "BasePart" to m("Position CFrame Size Orientation Rotation Anchored CanCollide CanTouch CanQuery Transparency Color BrickColor Material MaterialVariant Reflectance Locked Massless CastShadow CollisionGroup AssemblyLinearVelocity AssemblyAngularVelocity AssemblyMass AssemblyRootPart TopSurface BottomSurface FrontSurface BackSurface LeftSurface RightSurface",
            "GetMass ApplyImpulse ApplyAngularImpulse GetTouchingParts GetConnectedParts GetJoints BreakJoints SetNetworkOwner GetNetworkOwner GetRootPart GetVelocityAtPosition CanSetNetworkOwnership", "Touched TouchEnded"),
        "Part" to m("Shape"), "MeshPart" to m("MeshId TextureID DoubleSided"), "SpawnLocation" to m("Enabled Neutral TeamColor Duration AllowTeamChangeOnTouch"),
        "Seat" to m("Disabled Occupant", "Sit"), "VehicleSeat" to m("Disabled Occupant MaxSpeed Torque Throttle Steer TurnSpeed", "Sit"),
        "Model" to m("PrimaryPart WorldPivot", "MoveTo GetBoundingBox GetExtentsSize ScaleTo GetScale TranslateBy SetPrimaryPartCFrame GetPrimaryPartCFrame"),
        "Workspace" to m("Gravity CurrentCamera FallenPartsDestroyHeight DistributedGameTime Terrain StreamingEnabled",
            "Raycast Blockcast Spherecast Shapecast GetPartBoundsInBox GetPartBoundsInRadius GetPartsInPart GetServerTimeNow GetRealPhysicsFPS FindPartOnRay"),
        "Humanoid" to m("Health MaxHealth WalkSpeed JumpPower JumpHeight UseJumpPower HipHeight AutoRotate Sit Jump MoveDirection RootPart SeatPart DisplayName RigType FloorMaterial PlatformStand WalkToPoint NameDisplayDistance HealthDisplayType BreakJointsOnDeath",
            "TakeDamage MoveTo Move ChangeState GetState LoadAnimation EquipTool UnequipTools GetPlayingAnimationTracks SetStateEnabled GetStateEnabled ApplyDescription GetAppliedDescription AddAccessory GetAccessories",
            "Died HealthChanged Running Jumping StateChanged MoveToFinished FreeFalling Seated Climbing Touched"),
        "Players" to m("LocalPlayer MaxPlayers RespawnTime CharacterAutoLoads",
            "GetPlayers GetPlayerFromCharacter GetPlayerByUserId GetUserIdFromNameAsync GetNameFromUserIdAsync GetUserThumbnailAsync GetHumanoidDescriptionFromUserId", "PlayerAdded PlayerRemoving"),
        "Player" to m("Character UserId DisplayName Team TeamColor Neutral AccountAge MembershipType CameraMaxZoomDistance CameraMinZoomDistance CameraMode RespawnLocation PlayerGui Backpack PlayerScripts",
            "Kick LoadCharacter GetMouse IsInGroup GetRankInGroup GetRoleInGroup DistanceFromCharacter GetNetworkPing", "CharacterAdded CharacterRemoving CharacterAppearanceLoaded Chatted Idled"),
        "GuiObject" to m("Position Size AnchorPoint BackgroundColor3 BackgroundTransparency BorderColor3 BorderSizePixel Visible ZIndex LayoutOrder Rotation AbsolutePosition AbsoluteSize ClipsDescendants Active AutomaticSize SizeConstraint Selectable Interactable",
            "TweenPosition TweenSize TweenSizeAndPosition", "MouseEnter MouseLeave InputBegan InputEnded InputChanged"),
        "GuiButton" to m("AutoButtonColor Modal Selected", "", "MouseButton1Click MouseButton1Down MouseButton1Up MouseButton2Click Activated"),
        "TextLabel" to m("Text TextColor3 TextSize TextScaled TextWrapped Font FontFace RichText TextXAlignment TextYAlignment TextTransparency TextStrokeColor3 TextStrokeTransparency TextTruncate LineHeight MaxVisibleGraphemes TextBounds"),
        "TextButton" to m("Text TextColor3 TextSize TextScaled TextWrapped Font FontFace RichText TextXAlignment TextYAlignment TextTransparency TextStrokeColor3 TextStrokeTransparency"),
        "TextBox" to m("Text PlaceholderText PlaceholderColor3 ClearTextOnFocus MultiLine TextEditable TextColor3 TextSize TextScaled Font FontFace", "CaptureFocus ReleaseFocus IsFocused", "FocusLost Focused ReturnPressedFromOnScreenKeyboard"),
        "ImageLabel" to m("Image ImageColor3 ImageTransparency ScaleType SliceCenter TileSize ImageRectOffset ImageRectSize ResampleMode"),
        "ImageButton" to m("Image ImageColor3 ImageTransparency HoverImage PressedImage ScaleType SliceCenter"),
        "ScrollingFrame" to m("CanvasSize CanvasPosition AutomaticCanvasSize ScrollBarThickness ScrollingEnabled ScrollingDirection ScrollBarImageColor3 ElasticBehavior"),
        "ScreenGui" to m("Enabled ResetOnSpawn IgnoreGuiInset DisplayOrder ZIndexBehavior ScreenInsets"),
        "BillboardGui" to m("Adornee Size StudsOffset StudsOffsetWorldSpace MaxDistance AlwaysOnTop Enabled LightInfluence"),
        "SurfaceGui" to m("Adornee Face CanvasSize PixelsPerStud SizingMode AlwaysOnTop LightInfluence Enabled"),
        "UICorner" to m("CornerRadius"), "UIStroke" to m("Color Thickness Transparency ApplyStrokeMode LineJoinMode Enabled"),
        "UIListLayout" to m("FillDirection HorizontalAlignment VerticalAlignment Padding SortOrder Wraps"), "UIGridLayout" to m("CellSize CellPadding FillDirection SortOrder FillDirectionMaxCells"),
        "UIPadding" to m("PaddingTop PaddingBottom PaddingLeft PaddingRight"), "UIGradient" to m("Color Transparency Rotation Offset Enabled"), "UIScale" to m("Scale"),
        "RunService" to m("", "IsStudio IsServer IsClient IsRunning IsEdit IsRunMode BindToRenderStep UnbindFromRenderStep", "Heartbeat RenderStepped Stepped PreSimulation PostSimulation PreRender PreAnimation"),
        "TweenService" to m("", "Create GetValue"),
        "UserInputService" to m("TouchEnabled KeyboardEnabled MouseEnabled GamepadEnabled MouseBehavior MouseIconEnabled ModalEnabled",
            "IsKeyDown IsMouseButtonPressed GetMouseLocation GetFocusedTextBox GetKeysPressed GetLastInputType GetConnectedGamepads",
            "InputBegan InputEnded InputChanged TouchTap TouchStarted TouchEnded TouchMoved JumpRequest LastInputTypeChanged TouchTapInWorld"),
        "ContextActionService" to m("", "BindAction BindActionAtPriority UnbindAction UnbindAllActions SetTitle SetImage SetPosition SetDescription GetButton"),
        "DataStoreService" to m("", "GetDataStore GetOrderedDataStore GetGlobalDataStore"),
        "HttpService" to m("HttpEnabled", "JSONEncode JSONDecode GenerateGUID GetAsync PostAsync RequestAsync UrlEncode"),
        "MarketplaceService" to m("ProcessReceipt", "PromptPurchase PromptGamePassPurchase PromptProductPurchase UserOwnsGamePassAsync PlayerOwnsAsset GetProductInfo",
            "PromptGamePassPurchaseFinished PromptPurchaseFinished PromptProductPurchaseFinished"),
        "CollectionService" to m("", "GetTagged AddTag RemoveTag HasTag GetTags GetAllTags GetInstanceAddedSignal GetInstanceRemovedSignal"),
        "Debris" to m("", "AddItem"), "PathfindingService" to m("", "CreatePath FindPathAsync"),
        "StarterGui" to m("ShowDevelopmentGui", "SetCore GetCore SetCoreGuiEnabled GetCoreGuiEnabled"),
        "Lighting" to m("ClockTime TimeOfDay Brightness Ambient OutdoorAmbient FogEnd FogStart FogColor GlobalShadows ExposureCompensation GeographicLatitude Technology",
            "GetMinutesAfterMidnight SetMinutesAfterMidnight GetSunDirection GetMoonDirection"),
        "Camera" to m("CFrame Focus FieldOfView CameraType CameraSubject ViewportSize NearPlaneZ",
            "WorldToScreenPoint WorldToViewportPoint ScreenPointToRay ViewportPointToRay GetPartsObscuringTarget"),
        "Tool" to m("Handle RequiresHandle CanBeDropped ToolTip Grip Enabled ManualActivationOnly TextureId", "Activate Deactivate", "Activated Deactivated Equipped Unequipped"),
        "ValueBase" to m("Value", "", "Changed"),
        "Sound" to m("SoundId Volume Looped Playing PlaybackSpeed TimePosition TimeLength IsPlaying RollOffMaxDistance SoundGroup", "Play Stop Pause Resume", "Ended Played Paused Resumed Loaded DidLoop"),
        "ClickDetector" to m("MaxActivationDistance CursorIcon", "", "MouseClick RightMouseClick MouseHoverEnter MouseHoverLeave"),
        "ProximityPrompt" to m("ActionText ObjectText HoldDuration MaxActivationDistance KeyboardKeyCode GamepadKeyCode Enabled RequiresLineOfSight Style",
            "InputHoldBegin InputHoldEnd", "Triggered TriggerEnded PromptShown PromptHidden PromptButtonHoldBegan PromptButtonHoldEnded"),
        "RemoteEvent" to m("", "FireServer FireClient FireAllClients", "OnServerEvent OnClientEvent"),
        "UnreliableRemoteEvent" to m("", "FireServer FireClient FireAllClients", "OnServerEvent OnClientEvent"),
        "RemoteFunction" to m("OnServerInvoke OnClientInvoke", "InvokeServer InvokeClient"),
        "BindableEvent" to m("", "Fire", "Event"), "BindableFunction" to m("OnInvoke", "Invoke"),
        "Animator" to m("", "LoadAnimation GetPlayingAnimationTracks"), "Animation" to m("AnimationId"),
        "Teams" to m("", "GetTeams"), "Team" to m("TeamColor AutoAssignable", "GetPlayers", "PlayerAdded PlayerRemoved"),
        "TextChatService" to m("ChatVersion", "", "MessageReceived OnIncomingMessage SendingMessage"),
        "TextChannel" to m("", "SendAsync DisplaySystemMessage", "MessageReceived"),
        "Light" to m("Brightness Color Enabled Range Shadows"), "SpotLight" to m("Angle Face"),
        "ParticleEmitter" to m("Rate Enabled Lifetime Speed Color Size Transparency Texture SpreadAngle Acceleration Drag LightEmission", "Emit Clear"),
        "Attachment" to m("CFrame Position Orientation WorldCFrame WorldPosition Axis SecondaryAxis Visible"),
        "Weld" to m("Part0 Part1 C0 C1 Enabled"), "Motor6D" to m("Part0 Part1 C0 C1 Transform CurrentAngle DesiredAngle MaxVelocity"),
        "WeldConstraint" to m("Part0 Part1 Enabled"),
        "Decal" to m("Texture Color3 Transparency Face ZIndex"), "Texture" to m("StudsPerTileU StudsPerTileV OffsetStudsU OffsetStudsV"),
        "SpecialMesh" to m("MeshType MeshId TextureId Scale Offset VertexColor"),
        "Highlight" to m("Adornee FillColor FillTransparency OutlineColor OutlineTransparency DepthMode Enabled"),
        "BadgeService" to m("", "AwardBadge UserHasBadgeAsync GetBadgeInfoAsync"),
        "TeleportService" to m("", "Teleport TeleportAsync TeleportToPlaceInstance"),
        "SoundService" to m("AmbientReverb RespectFilteringEnabled", "PlayLocalSound"),
        "PlayerGui" to m("ScreenOrientation SelectionImageObject", "SetTopbarTransparency GetGuiObjectsAtPosition"),
        "Backpack" to m(""), "StarterPlayer" to m("CameraMaxZoomDistance CameraMinZoomDistance CameraMode EnableMouseLockOption CharacterWalkSpeed CharacterJumpPower CharacterUseJumpPower DevTouchMovementMode"),
        "GuiService" to m("MenuIsOpen", "GetGuiInset IsTenFootInterface"),
    )

    /** Types of well-known properties, so chains like `Players.LocalPlayer.Character.` keep resolving. */
    private val propTypes = mapOf(
        "LocalPlayer" to "Player", "Character" to "Model", "PlayerGui" to "PlayerGui", "Backpack" to "Backpack", "PlayerScripts" to "PlayerScripts",
        "CurrentCamera" to "Camera", "Terrain" to "Terrain", "PrimaryPart" to "BasePart", "RootPart" to "BasePart", "Humanoid" to "Humanoid",
        "HumanoidRootPart" to "BasePart", "Head" to "BasePart", "Torso" to "BasePart", "Handle" to "BasePart", "Adornee" to "Instance", "Team" to "Team",
        "Position" to "#Vector3", "Size" to "#Vector3", "Orientation" to "#Vector3", "CFrame" to "#CFrame", "Color" to "#Color3", "Velocity" to "#Vector3",
        "AssemblyLinearVelocity" to "#Vector3", "LookVector" to "#Vector3", "RightVector" to "#Vector3", "UpVector" to "#Vector3", "Unit" to "#Vector3",
        "BackgroundColor3" to "#Color3", "TextColor3" to "#Color3", "AbsolutePosition" to "#Vector2", "AbsoluteSize" to "#Vector2", "MoveDirection" to "#Vector3",
        "Focus" to "#CFrame", "WorldPivot" to "#CFrame", "C0" to "#CFrame", "C1" to "#CFrame", "BrickColor" to "#BrickColor", "Hit" to "#CFrame",
        "Target" to "BasePart", "Occupant" to "Humanoid", "SeatPart" to "BasePart", "Parent" to "Instance",
    )
    private val methodReturns = mapOf(
        "GetMouse" to "#Mouse", "LoadAnimation" to "#AnimationTrack", "Create" to "#Tween", "GetDataStore" to "#DataStore", "GetOrderedDataStore" to "#DataStore",
        "CreatePath" to "#Path", "Raycast" to "#RaycastResult", "GetPivot" to "#CFrame", "Clone" to "@self", "GetPlayerFromCharacter" to "Player",
        "FindFirstAncestorOfClass" to "@arg", "FindFirstChildOfClass" to "@arg", "FindFirstChildWhichIsA" to "@arg", "FindFirstAncestorWhichIsA" to "@arg",
        "GetPropertyChangedSignal" to "#RBXScriptSignal", "GetAttributeChangedSignal" to "#RBXScriptSignal", "GetInstanceAddedSignal" to "#RBXScriptSignal",
        "Connect" to "#RBXScriptConnection", "Once" to "#RBXScriptConnection",
    )
    /** Common parameter names (e.g. `function(player)`) and the type they almost always hold. */
    private val nameHints = mapOf(
        "player" to "Player", "plr" to "Player", "localplayer" to "Player", "character" to "Model", "char" to "Model", "humanoid" to "Humanoid", "hum" to "Humanoid",
        "hit" to "BasePart", "part" to "BasePart", "otherpart" to "BasePart", "root" to "BasePart", "hrp" to "BasePart", "rootpart" to "BasePart",
        "tool" to "Tool", "camera" to "Camera", "cam" to "Camera", "mouse" to "#Mouse", "input" to "#InputObject", "gui" to "ScreenGui", "button" to "TextButton",
        "frame" to "Frame", "label" to "TextLabel", "textbox" to "TextBox", "sound" to "Sound", "remote" to "RemoteEvent", "event" to "RemoteEvent",
        "animator" to "Animator", "track" to "#AnimationTrack", "tween" to "#Tween", "model" to "Model", "folder" to "Folder", "leaderstats" to "Folder",
    )

    // ------------------------------------------------------------------ types

    private sealed class T {
        class Inst(val i: Instance) : T()
        class Cls(val name: String) : T()
        class Lib(val name: String) : T()
        class DataStatic(val name: String) : T()
        class Data(val name: String) : T()
        object EnumRoot : T()
        class EnumType(val name: String) : T()
        object Signal : T()
    }

    private fun clsOf(t: T): String? = when (t) { is T.Inst -> t.i.className; is T.Cls -> t.name; else -> null }

    private fun classChain(cls: String): List<String> {
        val out = ArrayList<String>()
        var c: String? = cls
        var guard = 0
        while (c != null && guard++ < 15) { out.add(c); if (c == "Instance") break; c = ClassDb.superclass(c) }
        if ("Instance" !in out) out.add("Instance")
        return out
    }

    private fun membersFor(cls: String): Triple<Set<String>, Set<String>, Set<String>> {
        val p = LinkedHashSet<String>(); val me = LinkedHashSet<String>(); val ev = LinkedHashSet<String>()
        for (c in classChain(cls)) classMembers[c]?.let { p.addAll(it.props); me.addAll(it.methods); ev.addAll(it.events) }
        return Triple(p, me, ev)
    }

    private fun typeFromHint(s: String?, studio: com.leaf.lemonade.core.Studio): T? {
        if (s == null) return null
        return when {
            s.startsWith("#") -> T.Data(s.substring(1))
            else -> T.Cls(s)
        }
    }

    // ------------------------------------------------------------------ parsing helpers

    private fun isId(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Where we are at [pos]: 0 code, 1 short string (returns its start), 2 comment / long string. */
    private fun lexState(text: String, pos: Int): Pair<Int, Int> {
        var i = 0
        val n = minOf(pos, text.length)
        while (i < n) {
            val c = text[i]
            if (c == '-' && i + 1 < n && text[i + 1] == '-') {
                val lvl = longLevel(text, i + 2)
                if (lvl >= 0) {
                    val close = "]" + "=".repeat(lvl) + "]"
                    val e = text.indexOf(close, i + 4 + lvl)
                    if (e < 0 || e + close.length > n) return 2 to i
                    i = e + close.length; continue
                }
                val e = text.indexOf('\n', i)
                if (e < 0 || e >= n) return 2 to i
                i = e + 1; continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                var j = i + 1
                while (j < n && text[j] != c && text[j] != '\n') { if (text[j] == '\\') j++; j++ }
                if (j >= n) return 1 to i
                i = j + 1; continue
            }
            if (c == '[') {
                val lvl = longLevel(text, i)
                if (lvl >= 0) {
                    val close = "]" + "=".repeat(lvl) + "]"
                    val e = text.indexOf(close, i + 2 + lvl)
                    if (e < 0 || e + close.length > n) return 2 to i
                    i = e + close.length; continue
                }
            }
            i++
        }
        return 0 to -1
    }

    private fun longLevel(text: String, at: Int): Int {
        if (at >= text.length || text[at] != '[') return -1
        var j = at + 1; var l = 0
        while (j < text.length && text[j] == '=') { l++; j++ }
        return if (j < text.length && text[j] == '[') l else -1
    }

    /** A step of a member chain: name, accessor ('.' ':' or ' ' for the root), and a string argument if it was a call like :WaitForChild("X"). */
    private class Step(val name: String, val acc: Char, val arg: String?, val call: Boolean)

    /** Reads the expression chain that ends just before [end] (e.g. `game:GetService("Players").LocalPlayer`). */
    private fun chainBefore(text: String, end: Int): List<Step>? {
        val steps = ArrayList<Step>()
        var i = end - 1
        var guard = 0
        while (i >= 0 && guard++ < 24) {
            var arg: String? = null
            var call = false
            if (text[i] == ')') {
                // skip a balanced call; remember a single string argument
                var depth = 0; var j = i
                while (j >= 0) { if (text[j] == ')') depth++ else if (text[j] == '(') { depth--; if (depth == 0) break }; j-- }
                if (j < 0) return null
                val inside = text.substring(j + 1, i).trim()
                Regex("""^["']([^"']*)["']""").find(inside)?.let { arg = it.groupValues[1] }
                call = true
                i = j - 1
            } else if (text[i] == ']') {
                var j = i
                while (j >= 0 && text[j] != '[') j--
                if (j < 0) return null
                val inside = text.substring(j + 1, i).trim()
                val s = Regex("""^["']([^"']*)["']$""").find(inside)?.groupValues?.get(1) ?: return null
                steps.add(0, Step(s, '.', null, false))
                i = j - 1
                if (i >= 0 && isId(text[i])) continue
                if (i < 0) return null
                continue
            }
            while (i >= 0 && text[i] == ' ') i--
            if (i < 0 || !isId(text[i])) return if (steps.isEmpty()) null else steps
            val e = i + 1
            while (i >= 0 && isId(text[i])) i--
            val name = text.substring(i + 1, e)
            if (name.first().isDigit()) return null
            val acc = if (i >= 0 && (text[i] == '.' || text[i] == ':')) text[i] else ' '
            steps.add(0, Step(name, acc, arg, call))
            if (acc == ' ') return steps
            i--
        }
        return if (steps.isEmpty()) null else steps
    }

    private fun resolveRoot(name: String, studio: com.leaf.lemonade.core.Studio, script: Instance, text: String, before: Int, depth: Int): T? {
        val dm = studio.model
        return when (name) {
            "game" -> T.Inst(dm)
            "workspace", "Workspace" -> T.Inst(dm.workspace)
            "script" -> T.Inst(script)
            "Enum" -> T.EnumRoot
            "plugin" -> T.Cls("Plugin")
            else -> when {
                libs.containsKey(name) -> T.Lib(name)
                dataStatics.containsKey(name) -> T.DataStatic(name)
                else -> resolveLocal(name, studio, script, text, before, depth)
            }
        }
    }

    /** Finds `local name = expr` (the last one before [before]) and resolves expr. */
    private fun resolveLocal(name: String, studio: com.leaf.lemonade.core.Studio, script: Instance, text: String, before: Int, depth: Int): T? {
        if (depth > 3) return null
        val rx = Regex("""(?:^|[\s;(])local\s+${Regex.escape(name)}\s*(?::\s*[\w.<>]+\s*)?=\s*([^\n;]+)""")
        val m = rx.findAll(text.substring(0, minOf(before, text.length))).lastOrNull()
        if (m != null) {
            val expr = m.groupValues[1].trim().removeSuffix(",")
            val t = resolveExpr(expr, studio, script, depth + 1)
            if (t != null) return t
        }
        nameHints[name.lowercase()]?.let { return typeFromHint(it, studio) }
        // Variables named after a service (local Players = ...) or an existing service.
        if (ClassDb.isServiceName(name)) return studio.model.findService(name)?.let { T.Inst(it) } ?: T.Cls(name)
        return null
    }

    private fun resolveExpr(expr: String, studio: com.leaf.lemonade.core.Studio, script: Instance, depth: Int): T? {
        val e = expr.trim()
        Regex("""^(\w+)\.new\s*\(""").find(e)?.let { m -> val n = m.groupValues[1]; if (n == "Instance") Regex("""Instance\.new\s*\(\s*["'](\w+)["']""").find(e)?.let { return T.Cls(it.groupValues[1]) } else if (dataStatics.containsKey(n)) return T.Data(n) }
        Regex("""^(\w+)\.(fromRGB|fromHSV|fromHex|Angles|lookAt|fromOrientation|fromScale|fromOffset)\b""").find(e)?.let { return T.Data(it.groupValues[1]) }
        val steps = chainBefore(e + " ", e.length) ?: return null
        return resolveSteps(steps, studio, script, e, e.length, depth)
    }

    private fun resolveSteps(steps: List<Step>, studio: com.leaf.lemonade.core.Studio, script: Instance, text: String, before: Int, depth: Int): T? {
        if (steps.isEmpty()) return null
        var t = resolveRoot(steps[0].name, studio, script, text, before, depth) ?: return null
        for (s in steps.drop(1)) t = member(t, s, studio) ?: return null
        return t
    }

    private fun member(t: T, s: Step, studio: com.leaf.lemonade.core.Studio): T? {
        when (t) {
            is T.EnumRoot -> return T.EnumType(s.name)
            is T.DataStatic -> return if (s.name == "new" || s.call) T.Data(t.name) else T.Data(t.name)
            is T.Data -> {
                val dm = dataMembers[t.name]
                if (s.acc == ':' && s.call) return methodReturns[s.name]?.let { typeFromHint(it, studio) }
                return propTypes[s.name]?.let { typeFromHint(it, studio) } ?: if (dm != null && s.name in dm.first) null else null
            }
            else -> {}
        }
        val cls = clsOf(t) ?: return null
        val inst = (t as? T.Inst)?.i
        if (s.acc == ':' || s.call) {
            when (s.name) {
                "GetService", "FindService" -> s.arg?.let { n -> return (studio.model as DataModel).findService(n)?.let { T.Inst(it) } ?: T.Cls(n) }
                "WaitForChild", "FindFirstChild", "FindFirstDescendant" -> {
                    val n = s.arg ?: return T.Cls("Instance")
                    inst?.findFirstChild(n, s.name == "FindFirstDescendant")?.let { return T.Inst(it) }
                    propTypes[n]?.let { return typeFromHint(it, studio) }
                    return T.Cls("Instance")
                }
            }
            val r = methodReturns[s.name] ?: return null
            return when (r) {
                "@self" -> t
                "@arg" -> s.arg?.let { T.Cls(it) }
                else -> typeFromHint(r, studio)
            }
        }
        // '.' access: child, event, or property
        inst?.children?.firstOrNull { it.name == s.name }?.let { return T.Inst(it) }
        if (inst is DataModel) inst.findService(s.name)?.let { return T.Inst(it) }
        val (_, _, events) = membersFor(cls)
        if (s.name in events) return T.Signal
        if (s.name == "Parent") return inst?.parent?.let { T.Inst(it) } ?: T.Cls("Instance")
        propTypes[s.name]?.let { return typeFromHint(it, studio) }
        return null
    }

    // ------------------------------------------------------------------ completion

    private fun localNames(text: String, upTo: Int): Set<String> {
        val out = LinkedHashSet<String>()
        val src = text.substring(0, minOf(upTo, text.length))
        Regex("""\blocal\s+(?:function\s+)?([A-Za-z_][\w]*(?:\s*,\s*[A-Za-z_][\w]*)*)""").findAll(src).forEach { m -> m.groupValues[1].split(',').forEach { out.add(it.trim()) } }
        Regex("""\bfunction\s+([A-Za-z_][\w.:]*)""").findAll(src).forEach { out.add(it.groupValues[1].substringBefore('.').substringBefore(':')) }
        Regex("""\bfunction\s*[\w.:]*\s*\(([^)]*)\)""").findAll(src).forEach { m -> m.groupValues[1].split(',').forEach { p -> p.trim().substringBefore(':').trim().takeIf { it.isNotEmpty() && it != "..." }?.let { out.add(it) } } }
        Regex("""\bfor\s+([\w\s,]+?)\s+(?:in|=)""").findAll(src).forEach { m -> m.groupValues[1].split(',').forEach { out.add(it.trim()) } }
        return out
    }

    private fun rank(items: List<Item>, prefix: String): List<Item> {
        if (prefix.isEmpty()) return items.distinctBy { it.label }.take(60)
        val p = prefix.lowercase()
        return items.asSequence()
            .filter { it.label.lowercase().contains(p) && it.label != prefix }
            .distinctBy { it.label }
            .sortedWith(compareBy<Item>({ if (it.label.startsWith(prefix)) 0 else if (it.label.lowercase().startsWith(p)) 1 else 2 }, { it.label.length }, { it.label }))
            .take(40).toList()
    }

    fun complete(studio: com.leaf.lemonade.core.Studio, script: Instance, text: String, cursor: Int, explicit: Boolean = false): Result? {
        if (cursor < 0 || cursor > text.length) return null
        val (state, at) = lexState(text, cursor)
        if (state == 2) return null
        if (state == 1) return completeString(studio, script, text, cursor, at)
        var start = cursor
        while (start > 0 && isId(text[start - 1])) start--
        val prefix = text.substring(start, cursor)
        if (prefix.isNotEmpty() && prefix[0].isDigit()) return null
        val accIdx = start - 1
        val acc = if (accIdx >= 0) text[accIdx] else ' '
        if (acc == '.' && accIdx > 0 && text[accIdx - 1] == '.') return null // string concatenation ".."
        if (acc == '.' || acc == ':') {
            val steps = chainBefore(text, accIdx) ?: return null
            val t = synchronized(studio.lock) { resolveSteps(steps, studio, script, text, accIdx, 0) } ?: return null
            val items = synchronized(studio.lock) { membersOf(t, acc) }
            val ranked = rank(items, prefix)
            return if (ranked.isEmpty()) null else Result(start, cursor, prefix, ranked)
        }
        if (prefix.isEmpty() && !explicit) return null
        // Avoid popping up while typing a new local's name or a function name.
        val before = text.substring(maxOf(0, start - 16), start)
        if (Regex("""(\blocal|\bfunction|\bfor)\s+$""").containsMatchIn(before) && !explicit) return null
        val items = ArrayList<Item>()
        localNames(text, start).filter { it != prefix }.forEach { items.add(Item(it, Kind.VARIABLE, "local")) }
        keywords.forEach { items.add(Item(it, Kind.KEYWORD)) }
        globals.forEach { items.add(Item(it, Kind.GLOBAL)) }
        libs.keys.forEach { items.add(Item(it, Kind.LIBRARY, "library")) }
        dataStatics.keys.forEach { items.add(Item(it, Kind.CLASS, "datatype")) }
        items.add(Item("Enum", Kind.ENUM))
        val ranked = rank(items, prefix)
        return if (ranked.isEmpty()) null else Result(start, cursor, prefix, ranked)
    }

    private fun membersOf(t: T, acc: Char): List<Item> {
        val out = ArrayList<Item>()
        when (t) {
            is T.Lib -> libs[t.name]?.forEach { out.add(Item(it, if (it == "pi" || it == "huge" || it == "charpattern") Kind.PROPERTY else Kind.FUNCTION, "${t.name}.$it")) }
            is T.DataStatic -> dataStatics[t.name]?.forEach { out.add(Item(it, if (it.first().isUpperCase() && it != "Angles") Kind.PROPERTY else Kind.FUNCTION, "${t.name}.$it")) }
            is T.Data -> dataMembers[t.name]?.let { (props, methods) ->
                if (acc == ':') methods.forEach { out.add(Item(it, Kind.METHOD, t.name)) }
                else props.forEach { out.add(Item(it, Kind.PROPERTY, t.name)) }
            }
            is T.EnumRoot -> {
                out.add(Item("GetEnums", Kind.FUNCTION))
                (Enums.types.keys + extraEnums).sorted().forEach { out.add(Item(it, Kind.ENUM, "Enum")) }
            }
            is T.EnumType -> {
                if (acc == ':') out.add(Item("GetEnumItems", Kind.METHOD))
                else Enums.types[t.name]?.keys?.forEach { out.add(Item(it, Kind.ENUM, "Enum.${t.name}")) }
            }
            is T.Signal -> if (acc == ':') listOf("Connect", "Once", "Wait", "ConnectParallel").forEach { out.add(Item(it, Kind.METHOD, "RBXScriptSignal")) }
            is T.Inst, is T.Cls -> {
                val cls = clsOf(t)!!
                val (props, methods, events) = membersFor(cls)
                if (acc == ':') methods.forEach { out.add(Item(it, Kind.METHOD, cls)) }
                else {
                    if (t is T.Inst) {
                        t.i.children.forEach { c -> out.add(Item(c.name, Kind.CHILD, c.className, if (isSimpleName(c.name)) c.name else "")) }
                        if (t.i is DataModel) ClassDb.serviceNames.forEach { out.add(Item(it, Kind.SERVICE, "service")) }
                        // Properties actually stored on this object (display names).
                        t.i.props.keys.map { PropNames.display(it) }.filter { isSimpleName(it) && !it.startsWith("xml") }.forEach { out.add(Item(it, Kind.PROPERTY, cls)) }
                    }
                    props.forEach { out.add(Item(it, Kind.PROPERTY, cls)) }
                    events.forEach { out.add(Item(it, Kind.EVENT, cls)) }
                }
            }
        }
        // Children with odd names can't be written after "." — skip them.
        return out.filter { it.insert.isNotEmpty() }
    }

    private fun isSimpleName(s: String) = s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { isId(it) }

    private val extraEnums = listOf("KeyCode", "UserInputType", "Material", "EasingStyle", "EasingDirection", "HumanoidStateType", "PartType", "SurfaceType",
        "Font", "NormalId", "CameraType", "RaycastFilterType", "CoreGuiType", "SortOrder", "FillDirection", "HorizontalAlignment", "VerticalAlignment",
        "TextXAlignment", "TextYAlignment", "AutomaticSize", "ScaleType", "ZIndexBehavior", "PlaybackState", "AnimationPriority", "ContextActionResult")

    private val serviceList: List<String> get() = (ClassDb.serviceNames + listOf("RunService", "TweenService", "Debris", "HttpService", "UserInputService",
        "ContextActionService", "CollectionService", "MarketplaceService", "DataStoreService", "PathfindingService", "TextService", "GuiService", "BadgeService",
        "PhysicsService", "InsertService", "TeleportService", "ProximityPromptService", "MessagingService", "MemoryStoreService", "AssetService",
        "SocialService", "PolicyService", "HapticService", "VRService", "GroupService", "Selection", "ChangeHistoryService", "StudioService")).distinct().sorted()

    /** Completing inside a string: service names, class names, or children of the receiver. */
    private fun completeString(studio: com.leaf.lemonade.core.Studio, script: Instance, text: String, cursor: Int, quoteAt: Int): Result? {
        val prefix = text.substring(quoteAt + 1, cursor)
        if (prefix.contains('\n')) return null
        var i = quoteAt - 1
        while (i >= 0 && text[i] == ' ') i--
        if (i < 0 || text[i] != '(') {
            // table index: x["...
            return null
        }
        i--
        while (i >= 0 && text[i] == ' ') i--
        val e = i + 1
        while (i >= 0 && isId(text[i])) i--
        val fn = text.substring(i + 1, e)
        val acc = if (i >= 0) text[i] else ' '
        val items = ArrayList<Item>()
        when (fn) {
            "GetService", "FindService" -> serviceList.forEach { items.add(Item(it, Kind.SERVICE, "service")) }
            "new" -> if (acc == '.' && text.substring(maxOf(0, i - 8), i).endsWith("Instance")) ClassDb.insertable.forEach { items.add(Item(it, Kind.CLASS, "class")) }
            "IsA", "FindFirstChildOfClass", "FindFirstChildWhichIsA", "FindFirstAncestorOfClass", "FindFirstAncestorWhichIsA", "GetTagged" ->
                if (fn == "GetTagged") return null else (ClassDb.insertable + listOf("BasePart", "GuiObject", "LuaSourceContainer", "Player", "Humanoid", "ValueBase", "GuiButton", "Light")).distinct().sorted().forEach { items.add(Item(it, Kind.CLASS, "class")) }
            "WaitForChild", "FindFirstChild", "FindFirstDescendant", "FindFirstAncestor" -> {
                if (acc != ':') return null
                val steps = chainBefore(text, i) ?: return null
                val t = synchronized(studio.lock) { resolveSteps(steps, studio, script, text, i, 0) }
                synchronized(studio.lock) {
                    when (t) {
                        is T.Inst -> {
                            val list = if (fn == "FindFirstAncestor") generateSequence(t.i.parent) { it.parent }.toList()
                            else if (fn == "FindFirstDescendant") t.i.descendants().take(400) else t.i.children
                            list.forEach { items.add(Item(it.name, Kind.CHILD, it.className)) }
                        }
                        is T.Cls -> when (t.name) {
                            "Model" -> listOf("Humanoid", "HumanoidRootPart", "Head", "Torso", "UpperTorso").forEach { items.add(Item(it, Kind.CHILD)) }
                            "Player" -> listOf("PlayerGui", "Backpack", "PlayerScripts", "leaderstats").forEach { items.add(Item(it, Kind.CHILD)) }
                            else -> {}
                        }
                        else -> {}
                    }
                }
            }
            "GetAttribute", "SetAttribute" -> {
                if (acc != ':') return null
                val steps = chainBefore(text, i) ?: return null
                val t = synchronized(studio.lock) { resolveSteps(steps, studio, script, text, i, 0) }
                if (t is T.Inst) synchronized(studio.lock) { t.i.attributes.keys.forEach { items.add(Item(it, Kind.PROPERTY, "attribute")) } }
            }
            else -> return null
        }
        val ranked = rank(items, prefix)
        return if (ranked.isEmpty()) null else Result(quoteAt + 1, cursor, prefix, ranked)
    }
}
