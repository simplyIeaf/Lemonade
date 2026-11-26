package com.leaf.lemonade.lua

import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.engine.Geometry
import com.leaf.lemonade.engine.PartPhysics
import com.leaf.lemonade.model.*
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import kotlin.math.PI

private const val DEG = (180.0 / PI).toFloat()
private const val RAD = (PI / 180.0).toFloat()

class InstanceApi(val rt: LuaRuntime) {
    private val methodCache = HashMap<String, LuaValue>()
    private val warnedMissing = HashSet<String>()
    val signalMeta = LuaTable()
    private val connMeta = LuaTable()

    private val commonMethods = setOf(
        "FindFirstChild", "FindFirstChildOfClass", "FindFirstChildWhichIsA", "FindFirstAncestor", "FindFirstAncestorOfClass",
        "FindFirstAncestorWhichIsA", "FindFirstDescendant", "WaitForChild", "GetChildren", "GetDescendants", "IsA", "IsDescendantOf",
        "IsAncestorOf", "Destroy", "Remove", "Clone", "ClearAllChildren", "GetFullName", "GetAttribute", "SetAttribute", "GetAttributes",
        "GetAttributeChangedSignal", "GetPropertyChangedSignal", "GetService", "FindService", "GetTags", "HasTag", "AddTag", "RemoveTag",
        "GetActor", "GetDebugId", "IsPropertyModified", "ResetPropertyToDefault",
    )

    private val classMethods: Map<String, Set<String>> = mapOf(
        "BasePart" to setOf("GetMass", "ApplyImpulse", "ApplyAngularImpulse", "GetConnectedParts", "GetTouchingParts", "GetRootPart",
            "SetNetworkOwner", "GetNetworkOwner", "CanSetNetworkOwnership", "BreakJoints", "MakeJoints", "Resize", "GetVelocityAtPosition",
            "GetPivot", "PivotTo", "IsGrounded", "GetJoints"),
        "Model" to setOf("GetPivot", "PivotTo", "MoveTo", "SetPrimaryPartCFrame", "GetPrimaryPartCFrame", "GetBoundingBox", "GetExtentsSize",
            "BreakJoints", "MakeJoints", "TranslateBy", "GetModelCFrame", "GetModelSize", "ScaleTo", "GetScale"),
        "Humanoid" to setOf("TakeDamage", "MoveTo", "ChangeState", "GetState", "LoadAnimation", "EquipTool", "UnequipTools",
            "GetPlayingAnimationTracks", "SetStateEnabled", "GetStateEnabled", "Move", "ApplyDescription", "GetAppliedDescription", "AddAccessory", "GetAccessories"),
        "Animator" to setOf("LoadAnimation", "GetPlayingAnimationTracks", "StepAnimations", "ApplyJointVelocities"),
        "KeyframeSequenceProvider" to setOf("RegisterKeyframeSequence", "RegisterActiveKeyframeSequence", "GetKeyframeSequenceAsync", "GetKeyframeSequence",
            "GetKeyframeSequenceById", "GetAnimations", "GetMemStats"),
        "Terrain" to TerrainApi.METHODS,
        "AnimationController" to setOf("LoadAnimation", "GetPlayingAnimationTracks"),
        "Workspace" to setOf("Raycast", "FindPartOnRay", "FindPartOnRayWithIgnoreList", "FindPartOnRayWithWhitelist", "GetPartBoundsInBox",
            "GetPartBoundsInRadius", "GetPartsInPart", "GetServerTimeNow", "GetRealPhysicsFPS", "Spherecast", "Blockcast", "FindPartsInRegion3",
            "FindPartsInRegion3WithIgnoreList", "FindPartsInRegion3WithWhiteList", "IsRegion3Empty", "IsRegion3EmptyWithIgnoreList",
            "JoinToOutsiders", "UnjoinFromOutsiders", "BulkMoveTo", "ArePartsTouchingOthers", "GetNumAwakeParts", "PGSIsEnabled",
            "SetInsertPoint", "ZoomToExtents", "CalculateJumpPower", "CalculateJumpHeight", "GetPhysicsThrottling", "Shapecast"),
        "Players" to setOf("GetPlayers", "GetPlayerFromCharacter", "GetPlayerByUserId", "GetUserIdFromNameAsync", "GetNameFromUserIdAsync",
            "GetUserThumbnailAsync", "GetHumanoidDescriptionFromUserId"),
        "Player" to setOf("Kick", "LoadCharacter", "GetMouse", "IsInGroup", "GetRankInGroup", "GetRoleInGroup", "DistanceFromCharacter",
            "IsFriendsWith", "HasAppearanceLoaded", "GetJoinData", "ClearCharacterAppearance", "GetNetworkPing"),
        "RunService" to setOf("IsStudio", "IsRunning", "IsServer", "IsClient", "IsEdit", "IsRunMode", "BindToRenderStep", "UnbindFromRenderStep"),
        "TweenService" to setOf("Create", "GetValue"),
        "Debris" to setOf("AddItem", "addItem"),
        "HttpService" to setOf("JSONEncode", "JSONDecode", "GenerateGUID", "UrlEncode", "GetAsync", "PostAsync", "RequestAsync"),
        "CollectionService" to setOf("GetTagged", "AddTag", "RemoveTag", "HasTag", "GetTags", "GetInstanceAddedSignal", "GetInstanceRemovedSignal", "GetAllTags"),
        "Selection" to setOf("Get", "Set", "Add", "Remove"),
        "ChangeHistoryService" to setOf("SetWaypoint", "TryBeginRecording", "FinishRecording", "Undo", "Redo", "ResetWaypoints", "SetEnabled", "GetCanUndo", "GetCanRedo"),
        "DataStoreService" to setOf("GetDataStore", "GetGlobalDataStore", "GetOrderedDataStore"),
        "MarketplaceService" to setOf("PromptPurchase", "PromptGamePassPurchase", "PromptProductPurchase", "UserOwnsGamePassAsync", "GetProductInfo", "PlayerOwnsAsset"),
        "UserInputService" to setOf("GetMouseLocation", "IsKeyDown", "GetFocusedTextBox", "IsMouseButtonPressed", "GetKeysPressed", "GetLastInputType", "GetConnectedGamepads"),
        "ContextActionService" to setOf("BindAction", "UnbindAction", "BindActionAtPriority", "SetTitle", "SetImage", "SetPosition", "SetDescription", "GetButton", "UnbindAllActions"),
        "StarterGui" to setOf("SetCore", "GetCore", "SetCoreGuiEnabled", "GetCoreGuiEnabled"),
        "BindableEvent" to setOf("Fire"),
        "BindableFunction" to setOf("Invoke"),
        "RemoteEvent" to setOf("FireServer", "FireClient", "FireAllClients"),
        "UnreliableRemoteEvent" to setOf("FireServer", "FireClient", "FireAllClients"),
        "RemoteFunction" to setOf("InvokeServer", "InvokeClient"),
        "Sound" to setOf("Play", "Stop", "Pause", "Resume"),
        "GuiObject" to setOf("TweenPosition", "TweenSize", "TweenSizeAndPosition"),
        "TextBox" to setOf("CaptureFocus", "ReleaseFocus", "IsFocused"),
        "TextService" to setOf("GetTextSize", "FilterStringAsync"),
        "Plugin" to setOf("CreateToolbar", "GetSetting", "SetSetting", "CreateDockWidgetPluginGui", "CreateDockWidgetPluginGuiAsync", "CreateQWidgetPluginGui",
            "OpenScript", "GetMouse", "Activate",
            "Deactivate", "IsActivated", "IsActivatedWithExclusiveMouse", "GetStudioUserId", "CreatePluginAction", "CreatePluginMenu",
            "SelectRibbonTool", "GetSelectedRibbonTool", "Union", "Negate", "Separate", "Intersect", "StartDrag", "OpenWikiPage",
            "SaveSelectedToRoblox", "GetJoinMode", "PromptForExistingAssetId", "ImportFbxRig", "GetItem", "SetItem",
            "GetGridSize", "IsLoaded", "PromptSaveSelection"),
        "PluginGui" to setOf("BindToClose", "GetRelativeMousePosition"),
        "PluginToolbar" to setOf("CreateButton"),
        "PluginToolbarButton" to setOf("SetActive"),
        "ParticleEmitter" to setOf("Emit", "Clear"),
        "Camera" to setOf("WorldToScreenPoint", "WorldToViewportPoint", "ScreenPointToRay", "ViewportPointToRay", "GetPartsObscuringTarget"),
        "Lighting" to setOf("GetMinutesAfterMidnight", "SetMinutesAfterMidnight", "GetSunDirection", "GetMoonDirection", "GetMoonPhase"),
        "SoundService" to setOf("PlayLocalSound"),
        "PathfindingService" to setOf("CreatePath", "FindPathAsync"),
        "BadgeService" to setOf("AwardBadge", "UserHasBadgeAsync", "GetBadgeInfoAsync"),
        "TeleportService" to setOf("Teleport", "TeleportAsync", "TeleportToPlaceInstance"),
        "InsertService" to setOf("LoadAsset"),
        "Teams" to setOf("GetTeams"),
        "Team" to setOf("GetPlayers"),
        "PhysicsService" to setOf("RegisterCollisionGroup", "CollisionGroupSetCollidable", "SetPartCollisionGroup", "CreateCollisionGroup", "CollisionGroupsAreCollidable", "GetRegisteredCollisionGroups"),
        "Tool" to setOf("Activate", "Deactivate"),
        "ProximityPrompt" to setOf("InputHoldBegin", "InputHoldEnd"),
        "PlayerGui" to setOf("SetTopbarTransparency", "GetGuiObjectsAtPosition"),
        "MessagingService" to setOf("PublishAsync", "SubscribeAsync"),
        "Chat" to setOf("Chat", "FilterStringForBroadcast"),
        "TextChannel" to setOf("SendAsync", "DisplaySystemMessage"),
        "GuiService" to setOf("GetGuiInset", "IsTenFootInterface"),
        "LocalizationService" to setOf("GetTranslatorForPlayerAsync", "GetCountryRegionForPlayerAsync"),
        "PolicyService" to setOf("GetPolicyInfoForPlayerAsync"),
        "StudioService" to setOf("GetUserId", "PromptImportFile"),
        "RbxAnalyticsService" to setOf("GetSessionId", "GetClientId", "GetPlaySessionId", "ReportStats", "SendEventDeferred", "SendEventImmediately",
            "ReportCounter", "ReportInfluxSeries", "TrackEvent", "FireLogEvent", "SetRBXEvent"),
        "DataModel" to setOf("BindToClose", "IsLoaded", "GetJobsInfo", "SetPlaceId", "GetObjects"),
    )

    val events = setOf(
        "Touched", "TouchEnded", "ChildAdded", "ChildRemoved", "DescendantAdded", "DescendantRemoving", "Changed", "AncestryChanged",
        "Destroying", "AttributeChanged", "Heartbeat", "RenderStepped", "Stepped", "PreSimulation", "PostSimulation", "PreRender",
        "PlayerAdded", "PlayerRemoving", "CharacterAdded", "CharacterRemoving", "CharacterAppearanceLoaded", "Died", "HealthChanged", "Running", "Jumping",
        "StateChanged", "MoveToFinished", "FreeFalling", "Seated", "MouseButton1Click", "MouseButton1Down", "MouseButton1Up", "MouseButton2Click",
        "Activated", "MouseEnter", "MouseLeave", "FocusLost", "Focused", "Event", "OnServerEvent", "OnClientEvent", "MouseClick",
        "RightMouseClick", "MouseHoverEnter", "MouseHoverLeave", "Triggered", "TriggerEnded", "PromptShown", "PromptHidden", "PromptTriggered",
        "Completed", "Click", "SelectionChanged", "InputBegan", "InputEnded", "InputChanged", "TouchTapInWorld", "TouchTap", "TouchStarted",
        "TouchEnded", "TouchMoved", "Equipped", "Unequipped", "Deactivated", "Played", "Ended", "Paused", "Resumed", "Loaded", "Chatted",
        "Idled", "Button1Down", "Button1Up", "Move", "Blocked", "PromptPurchaseFinished", "PromptGamePassPurchaseFinished",
        "ServerScriptServiceChanged", "WindowFocused", "WindowFocusReleased", "Unloading", "Close", "BindToClose", "Deactivation",
        "ChildAddedToCharacter", "ReturnPressedFromOnScreenKeyboard", "TextChanged", "MessageReceived", "OnIncomingMessage",
        "LastInputTypeChanged", "JumpRequest", "MouseDrag", "Unloading", "Deactivation", "Loaded", "PromptProductPurchaseFinished", "PromptPurchaseFinished", "PromptGamePassPurchaseFinished", "PromptBundlePurchaseFinished", "Loaded", "GraphicsQualityChangeRequest", "OnTeleport", "ChildRemoving", "ClockTimeChanged", "PlaybackStateChanged", "DidLoop", "Stopped", "KeyframeReached",
        // Studio / plugin events
        "OnUndo", "OnRedo", "OnRecordingStarted", "OnRecordingFinished", "WaypointSet", "Activated", "PromptDismissed",
        "DragBegin", "DragContinue", "DragEnd", "MouseButton2Down", "MouseButton2Up", "MouseWheelForward", "MouseWheelBackward",
        "WheelBackward", "WheelForward", "Button2Down", "Button2Up", "KeyDown", "KeyUp", "SelectionGained", "SelectionLost",
    )

    private val sequenceClasses = setOf("UIGradient", "ParticleEmitter", "Beam", "Trail")

    /** Events every Instance has (these always win over children with the same name). */
    private val universalEvents = setOf("ChildAdded", "ChildRemoved", "DescendantAdded", "DescendantRemoving", "Changed", "AncestryChanged", "Destroying", "AttributeChanged")

    init {
        val m = rt.instanceMeta
        m.set("__type", "Instance")
        m.set(LuaValue.INDEX, fn2 { self, key -> index(self.touserdata() as Instance, key.tojstring()) })
        m.set(LuaValue.NEWINDEX, object : org.luaj.vm2.lib.ThreeArgFunction() {
            override fun call(self: LuaValue, key: LuaValue, v: LuaValue): LuaValue {
                newIndex(self.touserdata() as Instance, key.tojstring(), v); return LuaValue.NONE
            }
        })
        m.set(LuaValue.TOSTRING, fn1 { LuaValue.valueOf((it.touserdata() as Instance).name) })

        signalMeta.set("__type", "RBXScriptSignal")
        signalMeta.set(LuaValue.INDEX, fn2 { self, key ->
            val s = self.touserdata() as SignalRef
            when (key.tojstring()) {
                "Connect", "connect", "ConnectParallel" -> fnv { a -> connectionValue(rt.connect(s.owner, s.name, a.arg(2).checkfunction(), false)) }
                "Once" -> fnv { a -> connectionValue(rt.connect(s.owner, s.name, a.arg(2).checkfunction(), true)) }
                "Wait", "wait" -> fnv { _ -> rt.waitSignal(s.owner, s.name) }
                else -> throw LuaError("${key.tojstring()} is not a valid member of RBXScriptSignal")
            }
        })
        signalMeta.set(LuaValue.TOSTRING, fn1 { LuaValue.valueOf("Signal " + (it.touserdata() as SignalRef).name) })

        connMeta.set("__type", "RBXScriptConnection")
        connMeta.set(LuaValue.INDEX, fn2 { self, key ->
            val c = self.touserdata() as Connection
            when (key.tojstring()) {
                "Disconnect", "disconnect" -> fnv { _ -> rt.disconnect(c); LuaValue.NONE }
                "Connected" -> LuaValue.valueOf(c.connected)
                else -> LuaValue.NIL
            }
        })
    }

    fun signal(owner: Any, name: String): LuaValue = LuaUserdata(SignalRef(owner, name), signalMeta)
    private fun connectionValue(c: Connection): LuaValue = LuaUserdata(c, connMeta)

    private fun methodAllowed(inst: Instance, name: String): Boolean {
        if (name in commonMethods) return true
        for ((cls, set) in classMethods) if (name in set && inst.isA(cls)) return true
        return false
    }

    // ------------------------------------------------------------------ get/set (Kotlin level)

    private fun isPart(i: Instance) = i.isA("BasePart")

    fun getK(inst: Instance, name: String): Any? {
        when (name) {
            "Name" -> return inst.name
            "ClassName" -> return inst.className
            "Parent" -> return inst.parent
            "Archivable" -> return inst.archivable
        }
        if (isPart(inst)) when (name) {
            "Position" -> return PartData.cframe(inst).position
            "Orientation" -> return PartData.cframe(inst).toOrientation() * DEG
            "Rotation" -> return PartData.cframe(inst).toEulerXYZ() * DEG
            "Size" -> return PartData.size(inst)
            "Color" -> return PartData.color(inst)
            "BrickColor" -> return inst["BrickColor"] ?: BrickColorV(BrickColors.closest(PartData.color(inst)))
            "CFrame" -> return PartData.cframe(inst)
            "AssemblyLinearVelocity", "Velocity" -> return rt.play?.velocityOf(inst) ?: Vector3.ZERO
            "AssemblyAngularVelocity", "RotVelocity" -> return Vector3.ZERO
            "Mass" -> return PartPhysics.effMass(inst)
            "AssemblyMass" -> return PartPhysics.assembly(inst).mass
            "AssemblyCenterOfMass" -> return PartPhysics.assembly(inst).com
            "CenterOfMass" -> return PartPhysics.centerOfMass(inst)
            "SpecificGravity" -> return PartPhysics.props(inst).density
            "PivotOffset" -> return inst["PivotOffset"] ?: CFrame.IDENTITY
            "AssemblyRootPart" -> return PartPhysics.rootOf(inst)
        }
        if (inst.isA("Model")) when (name) {
            "WorldPivot" -> return modelPivot(inst)
        }
        if (inst.isA("GuiBase2d")) when (name) {
            "AbsolutePosition" -> return Vector2(inst.absX, inst.absY)
            "AbsoluteSize" -> return Vector2(inst.absW, inst.absH)
            "AbsoluteRotation" -> return 0f
        }
        // Layouts: the size of what they arranged, computed when the GUI is laid out.
        if (inst.isA("UIGridStyleLayout") && name == "AbsoluteContentSize") return Vector2(inst.absW, inst.absH)
        if (inst.className == "UIGridLayout" && name == "AbsoluteCellSize") return (inst["CellSize"] as? UDim2)?.let { Vector2(it.xo.toFloat(), it.yo.toFloat()) } ?: Vector2(100f, 100f)
        if (inst.isA("BaseScript")) when (name) {
            "Enabled" -> return inst["Disabled"] != true
        }
        if (inst.isA("Constraint")) when (name) {
            "CurrentPosition", "CurrentAngle", "CurrentDistance", "CurrentLength" -> return inst[name] ?: 0f
        }
        when (inst.className) {
            "Players" -> when (name) {
                "LocalPlayer" -> return rt.play?.player
                "MaxPlayers" -> return 1
            }
            "Humanoid" -> when (name) {
                "Health" -> return inst["Health_XML"] ?: inst["Health"] ?: 100f
                "RootPart" -> return inst.parent?.findFirstChild("HumanoidRootPart")
                "MoveDirection" -> return rt.play?.moveDirection(inst) ?: Vector3.ZERO
                "FloorMaterial" -> return EnumValue(if (rt.play?.isGrounded(inst) == true) 256 else 1792)
                "Jump" -> return false
                "SeatPart" -> return null
            }
            "Workspace" -> when (name) {
                "DistributedGameTime" -> return rt.time
                "CurrentCamera" -> return inst["CurrentCamera"] ?: inst.findFirstChildOfClass("Camera")
                "Gravity" -> return inst["Gravity"] ?: 196.2f
            }
            "ImageLabel", "ImageButton" -> if (name == "IsLoaded") return true
            "DataModel" -> when (name) {
                "PlaceId" -> return rt.studio.cloud?.placeId ?: 0L
                "GameId" -> return rt.studio.cloud?.universeId ?: 0L
                "CreatorId" -> return 0L
                "CreatorType" -> return EnumValue(0)
                "PlaceVersion" -> return 0
                "JobId", "PrivateServerId" -> return ""
                "PrivateServerOwnerId" -> return 0L
            }
            // Studio's snapping and dragger settings, which plugin draggers (ReDupe, F3X...) read.
            "StudioService" -> when (name) {
                "GridSize" -> return if (rt.studio.snapEnabled) rt.studio.moveSnap else 0f
                "RotateIncrement" -> return if (rt.studio.rotSnapEnabled) rt.studio.rotSnap else 0f
                "UseLocalSpace" -> return false
                "DraggerSolveConstraints", "ShowConstraintDetails" -> return false
                "ActiveScript" -> return null
                "StudioLocaleId" -> return "en-us"
                "Theme" -> return null
            }
            "DraggerService" -> when (name) {
                "HoverThickness" -> return 0.04f
                "AlignDraggedObjects", "ShowHover", "AnimateHover", "PivotSnapToGeometry", "ShowPivotIndicator" -> return true
                "JointsEnabled" -> return false
                "CollisionsEnabled" -> return rt.studio.collisionsEnabled
                "AngleSnapEnabled" -> return rt.studio.rotSnapEnabled
                "AngleSnapIncrement" -> return rt.studio.rotSnap
                "LinearSnapEnabled" -> return rt.studio.snapEnabled
                "LinearSnapIncrement" -> return rt.studio.moveSnap
                "HoverAnimateFrequency" -> return 0.25f
                "GeometrySnapColor" -> return Color3.fromRGB(255, 165, 0)
            }
            "Camera" -> when (name) {
                "ViewportSize" -> return rt.studio.viewportSizeDp()
                // In edit mode the workspace camera is Studio's own view (plugins read it).
                "CFrame" -> return if (rt.play == null && isEditCamera(inst)) rt.studio.camera.cframe() else inst["CFrame"] ?: CFrame.IDENTITY
                "Focus" -> return inst["Focus"] ?: CFrame.IDENTITY
            }
            "Lighting" -> when (name) {
                "TimeOfDay" -> { val ct = (inst["ClockTime"] as? Number)?.toFloat() ?: 14f; val h = ct.toInt(); val m = ((ct - h) * 60).toInt(); return String.format("%02d:%02d:00", h, m) }
            }
            "UserInputService" -> when (name) {
                "TouchEnabled" -> return true
                "KeyboardEnabled", "MouseEnabled", "GamepadEnabled", "VREnabled", "AccelerometerEnabled", "GyroscopeEnabled" -> return false
                "MouseBehavior", "MouseIconEnabled" -> return inst[name] ?: EnumValue(0)
            }
            "HttpService" -> when (name) { "HttpEnabled" -> return inst["HttpEnabled"] ?: false }
            "RunService" -> when (name) { "ClientGitHash" -> return "mobile" }
            "Sound" -> when (name) {
                "TimeLength" -> return inst["TimeLength"] ?: 0.0
                "TimePosition" -> return inst["TimePosition"] ?: 0.0
                "IsPlaying" -> return inst["Playing"] == true
                "IsLoaded" -> return true
                "PlaybackLoudness" -> return 0.0
                "PlaybackSpeed" -> return inst["PlaybackSpeed"] ?: 1f
            }
            "Player" -> when (name) {
                "PlayerGui" -> return inst.findFirstChildOfClass("PlayerGui")
                "Backpack" -> return inst.findFirstChildOfClass("Backpack")
            }
            "TextBox", "TextLabel", "TextButton" -> when (name) {
                "TextBounds" -> { val s = (inst["TextSize"] as? Number)?.toFloat() ?: 14f; return Vector2(((inst["Text"] as? String)?.length ?: 0) * s * 0.5f, s) }
                "ContentText" -> return inst["Text"]
                "TextFits" -> return true
            }
        }
        val key = PropNames.resolve(inst, name)
        return inst.props[key]
    }

    fun hasK(inst: Instance, name: String): Boolean {
        if (name in BASE_PROPS) return true
        if (isPart(inst) && name in PART_PROPS) return true
        if (inst.isA("Model") && name == "WorldPivot") return true
        if (inst.isA("GuiBase2d") && name.startsWith("Absolute")) return true
        if (inst.isA("UIGridStyleLayout") && (name == "AbsoluteContentSize" || (name == "AbsoluteCellSize" && inst.className == "UIGridLayout"))) return true
        if (inst.isA("BaseScript") && name == "Enabled") return true
        if (inst.isA("Constraint") && name in CONSTRAINT_READOUTS) return true
        if (SPECIAL_PROPS[inst.className]?.contains(name) == true) return true
        return inst.props.containsKey(PropNames.resolve(inst, name))
    }

    private val CONSTRAINT_READOUTS = setOf("CurrentPosition", "CurrentAngle", "CurrentDistance", "CurrentLength")
    private val BASE_PROPS = setOf("Name", "ClassName", "Parent", "Archivable")
    private val PART_PROPS = setOf("Position", "Orientation", "Rotation", "Size", "Color", "BrickColor", "CFrame", "AssemblyLinearVelocity",
        "Velocity", "AssemblyAngularVelocity", "RotVelocity", "Mass", "AssemblyMass", "AssemblyCenterOfMass", "CenterOfMass", "SpecificGravity", "PivotOffset", "AssemblyRootPart", "Anchored", "CanCollide",
        "Transparency", "Material", "Shape", "CanTouch", "CanQuery", "Locked", "Massless", "Reflectance", "CastShadow")

    /** Computed properties per class (built once: hasK runs on every property read from Lua). */
    private val SPECIAL_PROPS: Map<String, Set<String>> = mapOf(
        "DataModel" to setOf("PlaceId", "GameId", "CreatorId", "CreatorType", "PlaceVersion", "JobId", "PrivateServerId", "PrivateServerOwnerId"),
        "ImageLabel" to setOf("IsLoaded"), "ImageButton" to setOf("IsLoaded"),
            "StudioService" to setOf("GridSize", "RotateIncrement", "UseLocalSpace", "DraggerSolveConstraints", "ShowConstraintDetails", "ActiveScript", "StudioLocaleId", "Theme"),
            "DraggerService" to setOf("HoverThickness", "AlignDraggedObjects", "ShowHover", "AnimateHover", "PivotSnapToGeometry", "ShowPivotIndicator", "JointsEnabled",
                "CollisionsEnabled", "AngleSnapEnabled", "AngleSnapIncrement", "LinearSnapEnabled", "LinearSnapIncrement", "HoverAnimateFrequency", "GeometrySnapColor"),
            "Players" to setOf("LocalPlayer", "MaxPlayers"),
            "Humanoid" to setOf("Health", "RootPart", "MoveDirection", "FloorMaterial", "Jump", "SeatPart", "Sit", "PlatformStand", "AutoRotate", "UseJumpPower", "JumpHeight", "RigType"),
            "Workspace" to setOf("DistributedGameTime", "CurrentCamera", "Gravity", "StreamingEnabled"),
            "Camera" to setOf("ViewportSize", "CFrame", "Focus", "CameraSubject", "CameraType", "FieldOfView"),
            "Lighting" to setOf("TimeOfDay", "ClockTime", "Brightness", "Ambient", "OutdoorAmbient", "FogEnd", "FogStart", "FogColor", "GlobalShadows"),
            "UserInputService" to setOf("TouchEnabled", "KeyboardEnabled", "MouseEnabled", "GamepadEnabled", "VREnabled", "MouseBehavior", "MouseIconEnabled", "AccelerometerEnabled", "GyroscopeEnabled", "ModalEnabled"),
            "HttpService" to setOf("HttpEnabled"),
            "RunService" to setOf("ClientGitHash"),
            "Player" to setOf("PlayerGui", "Backpack", "Character", "UserId", "DisplayName", "Team", "TeamColor", "Neutral", "AccountAge", "MembershipType", "RespawnLocation", "CameraMode", "CameraMaxZoomDistance", "CameraMinZoomDistance", "DevTouchMovementMode"),
            "TextBox" to setOf("TextBounds", "ContentText", "TextFits"), "TextLabel" to setOf("TextBounds", "ContentText", "TextFits"), "TextButton" to setOf("TextBounds", "ContentText", "TextFits"),
            "StarterGui" to setOf("ShowDevelopmentGui", "ResetPlayerGuiOnSpawn"),
            "Sound" to setOf("TimePosition", "TimeLength", "IsPlaying", "IsLoaded", "PlaybackSpeed", "PlaybackLoudness"),
        )

    fun setK(inst: Instance, name: String, value: Any?) {
        when (name) {
            "Name" -> { inst.name = value?.toString() ?: "nil"; return }
            "Parent" -> {
                if (inst.destroyed) throw LuaError("The Parent property of ${inst.name} is locked, current parent: NULL, new parent ${(value as? Instance)?.name}")
                if (inst.isService || inst is DataModel) throw LuaError("The Parent property of ${inst.name} is locked")
                try { inst.setParent(value as Instance?) } catch (e: IllegalArgumentException) { throw LuaError(e.message) }
                return
            }
            "Archivable" -> { inst.archivable = value == true; return }
            "ClassName" -> throw LuaError("Unable to assign property ClassName. Property is read only")
        }
        if (isPart(inst)) when (name) {
            "Position" -> { PartData.setCFrame(inst, PartData.cframe(inst).withPosition(value as Vector3)); return }
            "Orientation" -> { val o = (value as Vector3) * RAD; PartData.setCFrame(inst, CFrame.fromOrientation(o.x, o.y, o.z).withPosition(PartData.cframe(inst).position)); return }
            "Rotation" -> { val o = (value as Vector3) * RAD; PartData.setCFrame(inst, CFrame.angles(o.x, o.y, o.z).withPosition(PartData.cframe(inst).position)); return }
            "Size" -> { val v = value as Vector3; PartData.setSize(inst, Vector3(v.x.coerceAtLeast(0.001f), v.y.coerceAtLeast(0.001f), v.z.coerceAtLeast(0.001f))); return }
            "Color" -> { PartData.setColor(inst, value as Color3); return }
            "BrickColor" -> {
                val b = value as BrickColorV
                PartData.setColor(inst, b.color)
                if (inst.props.containsKey("BrickColor")) inst.set("BrickColor", b)
                return
            }
            "AssemblyLinearVelocity", "Velocity" -> { rt.play?.setVelocity(inst, value as Vector3); return }
            "AssemblyAngularVelocity", "RotVelocity" -> return
            "CFrame" -> { PartData.setCFrame(inst, value as CFrame); return }
        }
        if (inst.isA("Model") && name == "WorldPivot") { pivotTo(inst, value as CFrame); return }
        if (inst.isA("BaseScript") && name == "Enabled") { inst.set("Disabled", value != true); return }
        if (inst.className == "Humanoid") when (name) {
            "Health" -> {
                val max = (inst["MaxHealth"] as? Number)?.toFloat() ?: 100f
                inst.set(if (inst.props.containsKey("Health")) "Health" else "Health_XML", (value as Number).toFloat().coerceIn(0f, max))
                return
            }
            "Jump" -> { if (value == true) rt.play?.jump(inst); return }
            "Sit" -> { inst.set("Sit", value == true); return }
        }
        if (inst.className == "Camera" && name == "CFrame") {
            inst.set("CFrame", value)
            // Plugins move Studio's view by setting workspace.CurrentCamera.CFrame.
            if (rt.play == null && isEditCamera(inst) && value is CFrame) rt.studio.camera.setFromCFrame(value)
            return
        }
        if (inst.className == "Lighting" && name == "TimeOfDay") {
            val p = value.toString().split(":"); val h = p.getOrNull(0)?.toFloatOrNull() ?: 14f; val m = p.getOrNull(1)?.toFloatOrNull() ?: 0f
            inst.set("ClockTime", h + m / 60f); return
        }
        if (inst.className == "Sound" && name == "Playing") { inst.set("Playing", value == true); if (value == true) rt.fire(inst, "Played", LuaValue.valueOf(inst["SoundId"]?.toString() ?: "")); return }
        val key = PropNames.resolve(inst, name)
        val type = inst.propTypes[key]
        val v = if (type == PropType.FLOAT && value is Number) value.toFloat()
        else if (type == PropType.DOUBLE && value is Number) value.toDouble()
        else if (type == PropType.INT && value is Number) value.toInt()
        else if (type == PropType.INT64 && value is Number) value.toLong()
        else value
        inst.set(key, v)
    }

    private fun typeHint(inst: Instance, name: String): Int? {
        when (name) {
            "Name" -> return PropType.STRING
            "Parent" -> return PropType.REF
            "Archivable", "Enabled" -> return PropType.BOOL
        }
        if (isPart(inst)) when (name) {
            "Position", "Orientation", "Rotation", "Size", "Velocity", "AssemblyLinearVelocity", "AssemblyAngularVelocity", "RotVelocity" -> return PropType.VECTOR3
            "Color" -> return PropType.COLOR3
            "BrickColor" -> return PropType.BRICKCOLOR
            "CFrame" -> return PropType.CFRAME
        }
        if (name == "WorldPivot") return PropType.CFRAME
        if (inst.className == "Humanoid" && name == "Health") return PropType.FLOAT
        val key = PropNames.resolve(inst, name)
        inst.propTypes[key]?.let { return it }
        // Sequence-valued properties that share names with plain number/color ones elsewhere.
        if (inst.className in sequenceClasses) when (name) {
            "Transparency", "Size", "Squash" -> return PropType.NUMBERSEQ
            "Color" -> return PropType.COLORSEQ
            "Offset" -> if (inst.className == "UIGradient") return PropType.VECTOR2
        }
        return when (name) {
            "Anchored", "CanCollide", "CanTouch", "CanQuery", "Locked", "Visible", "Disabled", "Massless", "Looped", "Playing", "TextScaled",
            "TextWrapped", "ClipsDescendants", "Active", "AutoButtonColor", "ResetOnSpawn", "IgnoreGuiInset", "RichText" -> PropType.BOOL
            "Transparency", "BackgroundTransparency", "TextTransparency", "ImageTransparency", "TextSize", "Volume", "Brightness", "Range",
            "WalkSpeed", "JumpPower", "JumpHeight", "MaxHealth", "HipHeight", "FieldOfView", "Reflectance", "ClockTime", "Rotation",
            "TextStrokeTransparency", "Thickness", "FogEnd", "FogStart", "PlaybackSpeed", "Angle" -> PropType.FLOAT
            "ZIndex", "LayoutOrder", "BorderSizePixel", "DisplayOrder" -> PropType.INT
            "Text", "Image", "SoundId", "Title", "PlaceholderText", "Texture", "MeshId", "TextureID", "AnimationId" -> PropType.STRING
            "Material", "Shape", "TextXAlignment", "TextYAlignment", "Font", "CameraType", "SortOrder", "FillDirection", "ScaleType", "AutomaticSize" -> PropType.ENUM
            "BackgroundColor3", "TextColor3", "BorderColor3", "ImageColor3", "Ambient", "OutdoorAmbient", "FogColor", "TextStrokeColor3" -> PropType.COLOR3
            "Position", "Size" -> if (inst.isA("GuiObject")) PropType.UDIM2 else null
            "AnchorPoint" -> PropType.VECTOR2
            "PrimaryPart", "Adornee", "CameraSubject", "Part0", "Part1", "Value" -> if (name == "Value" && inst.className != "ObjectValue") null else PropType.REF
            else -> null
        }
    }

    // ------------------------------------------------------------------ Lua index

    fun index(inst: Instance, key0: String): LuaValue {
        var key = key0
        if (key.isNotEmpty() && key[0].isLowerCase()) {
            val cap = key[0].uppercaseChar() + key.substring(1)
            if (methodAllowed(inst, cap)) key = cap
        }
        when (key) {
            "Name" -> return LuaValue.valueOf(inst.name)
            "ClassName" -> return LuaValue.valueOf(inst.className)
            "Parent" -> return rt.wrap(inst.parent)
        }
        if (methodAllowed(inst, key)) return methodCache.getOrPut(key) { val k = key; fnv { a -> callMethod(k, a) } }
        // Motor6D.Transform: the animated offset (not saved in files). Animation plugins read and set it.
        if (key == "Transform" && inst.isA("Motor")) return LuaTypes.wrap(if (rt.play == null) rt.studio.editAnim.transformOf(inst) else CFrame.IDENTITY)
        if (inst.isA("BaseScript") || inst.isA("ModuleScript")) {
            if (key == "Source") return LuaValue.valueOf(inst["Source"] as? String ?: "")
        }
        if (hasK(inst, key)) {
            val v = getK(inst, key)
            return rt.toLua(v, inst, PropNames.resolve(inst, key))
        }
        if (key in events) {
            // Our event list isn't per class, so a child with the same name (Roact's
            // PropMarkers.Event, a Folder called "Changed"...) wins over class-specific event names.
            if (key !in universalEvents) inst.findFirstChild(key)?.let { return rt.wrap(it) }
            return signal(inst, key)
        }
        rt.callbacks[inst]?.get(key)?.let { return it }
        inst.findFirstChild(key)?.let { return rt.wrap(it) }
        contentAlias(inst, key)?.let { a -> return if (hasK(inst, a)) rt.toLua(getK(inst, a), inst, a) else LuaValue.valueOf("") }
        // Properties that only some classes have: Roblox errors when another class is asked for
        // them, and plugins rely on that (`pcall(function() return obj.TextScaled end)` to test
        // "is this a text object?").
        MEMBER_OWNERS[key]?.let { owners -> if (owners.none { inst.isA(it) }) throw LuaError("$key is not a valid member of ${inst.className} \"${inst.fullName()}\"") }
        // Our class/property database is incomplete, so unknown members read as nil instead of erroring.
        if (warnedMissing.add(inst.className + "." + key)) OutputLog.warn("$key is not a known member of ${inst.className} (treated as nil)")
        return LuaValue.NIL
    }

    fun newIndex(inst: Instance, key: String, v: LuaValue) {
        // Content-typed twins of older string properties (Content values are URI strings here).
        contentAlias(inst, key)?.let { alias -> if (v.isstring() || v.isnil()) { newIndex(inst, alias, if (v.isnil()) LuaValue.valueOf("") else v); return } }
        if (key == "OnServerInvoke" || key == "OnClientInvoke" || key == "OnInvoke" || (v.isfunction() && !inst.props.containsKey(key))) {
            rt.callbacks.getOrPut(inst) { HashMap() }[key] = v
            return
        }
        if (key == "Source" && (inst.isA("LuaSourceContainer"))) {
            if (rt.isPlay) throw LuaError("The current thread cannot write 'Source' (lacking capability PluginOrOpenCloud)")
            inst.set("Source", v.tojstring()); return
        }
        if (key == "Transform" && inst.isA("Motor")) {
            val cf = v.udOrNull<CFrame>() ?: throw LuaError("Unable to assign property Transform. CFrame expected, got ${LuaTypes.typeOf(v)}")
            // In edit mode the joint moves its Part1 like Studio does (animation plugins pose rigs this way).
            if (rt.play == null) rt.studio.editAnim.setTransform(inst, cf)
            return
        }
        val type = typeHint(inst, key)
        val kv = try {
            rt.fromLua(v, type, inst, PropNames.resolve(inst, key))
        } catch (e: LuaError) {
            val want = when (type) {
                PropType.FLOAT, PropType.DOUBLE, PropType.INT, PropType.INT64 -> "number"
                PropType.BOOL -> "boolean"; PropType.STRING -> "string"; PropType.ENUM -> "EnumItem"
                else -> null
            }
            throw LuaError("Unable to assign property $key. " + (if (want != null) "$want expected, got ${LuaTypes.typeOf(v)}" else (e.message ?: "")))
        }
        try {
            setK(inst, key, kv)
        } catch (e: ClassCastException) {
            throw LuaError("Unable to assign property $key. ${LuaTypes.typeOf(v)} is not the correct type")
        }
        // Studio keeps joints together while editing: a plugin changing C0/C1 moves Part1.
        if (rt.play == null && (key == "C0" || key == "C1" || key == "Part0" || key == "Part1") && inst.isA("JointInstance")) rt.studio.editAnim.jointChanged(inst)
    }

    // ------------------------------------------------------------------ helpers

    fun partsIn(i: Instance): List<Instance> {
        val out = ArrayList<Instance>()
        if (i.isA("BasePart")) out.add(i)
        for (d in i.descendants()) if (d.isA("BasePart")) out.add(d)
        return out
    }

    /** ImageContent -> Image, TextureContent -> TextureID/Texture, MeshContent -> MeshId (unless the instance stores the Content name itself). */
    private fun contentAlias(inst: Instance, key: String): String? {
        if (inst.props.containsKey(key)) return null
        return when (key) {
            "ImageContent" -> "Image"
            "TextureContent" -> if (inst.className == "MeshPart") "TextureID" else if (inst.isA("Decal")) "Texture" else null
            "MeshContent" -> "MeshId"
            "ColorMapContent" -> "ColorMap"
            "NormalMapContent" -> "NormalMap"
            else -> null
        }
    }

    fun modelPivot(m: Instance): CFrame {
        (m["PrimaryPart"] as? Instance)?.let { return PartData.cframe(it) }
        (m["WorldPivotData"] as? OptionalCFrame)?.cframe?.let { return it }
        val b = Geometry.boundsOf(partsIn(m)) ?: return CFrame.IDENTITY
        return CFrame.at((b.first + b.second) / 2f)
    }

    fun pivotTo(target: Instance, cf: CFrame) {
        if (target.isA("BasePart")) {
            PartData.setCFrame(target, cf)
            return
        }
        val old = modelPivot(target)
        val delta = cf * old.inverse()
        for (p in partsIn(target)) PartData.setCFrame(p, delta * PartData.cframe(p))
        if (target.props.containsKey("WorldPivotData") && target["PrimaryPart"] == null) target.set("WorldPivotData", OptionalCFrame(cf))
    }

    private fun tags(i: Instance): MutableList<String> {
        val raw = when (val t = i["Tags"]) {
            is BinaryString -> String(t.bytes, Charsets.UTF_8)
            is String -> t
            else -> ""
        }
        return raw.split('\u0000').filter { it.isNotEmpty() }.toMutableList()
    }

    private fun setTags(i: Instance, t: List<String>) {
        i.set("Tags", BinaryString(t.joinToString("\u0000").toByteArray(Charsets.UTF_8)))
        i.propTypes["Tags"] = PropType.STRING
    }

    private fun listToLua(l: List<Instance>): LuaTable {
        val t = LuaTable()
        for (x in l) t.insert(0, rt.wrap(x))
        return t
    }

    private fun instList(v: LuaValue): List<Instance> {
        if (!v.istable()) return emptyList()
        val out = ArrayList<Instance>()
        for (k in 1..v.length()) (v.get(k).udOrNull<Instance>())?.let { out.add(it) }
        return out
    }

    private fun workspaceParts(): List<Instance> {
        val ws = rt.dm.findService("Workspace") ?: return emptyList()
        return if (rt.dm === rt.studio.model) rt.studio.renderParts() else partsIn(ws)
    }

    private fun filterFrom(params: LuaValue): (Instance) -> Boolean {
        if (!params.istable()) return { it["CanQuery"] != false }
        val list = instList(params.get("FilterDescendantsInstances"))
        val include = params.get("FilterType").udOrNull<EnumItemV>()?.value == 1
        val respectCollide = params.get("RespectCanCollide").toboolean()
        val group = params.get("CollisionGroup").let { if (it.isstring()) it.tojstring() else "Default" }
        val groups = com.leaf.lemonade.core.CollisionGroupTable.shared
        return { p ->
            val inList = list.any { f -> p === f || p.isDescendantOf(f) }
            (if (include) inList else !inList) && p["CanQuery"] != false && (!respectCollide || p["CanCollide"] != false) &&
                groups.groupsCollide(group, (p["CollisionGroup"] as? String) ?: "Default")
        }
    }

    private fun raycastResult(o: Vector3, d: Vector3, filter: (Instance) -> Boolean): LuaValue {
        val len = d.magnitude
        if (len < 1e-6f) return LuaValue.NIL
        val partHit = Geometry.raycast(workspaceParts(), o, d / len, len, filter)
        // Smooth terrain (unless the filter leaves Workspace.Terrain out).
        val terrain = com.leaf.lemonade.engine.TerrainStore.terrainOf(rt.dm)
        if (terrain != null && filter(terrain)) {
            val g = com.leaf.lemonade.engine.TerrainStore.of(terrain)
            val th = com.leaf.lemonade.engine.TerrainMeshes.raycast(g, com.leaf.lemonade.engine.TerrainMeshes.colorsFor(terrain), o, d / len, len)
            if (th != null && (partHit == null || th.first < partHit.t)) {
                val t = LuaTable()
                t.set("Instance", rt.wrap(terrain))
                t.set("Position", LuaTypes.wrap(o + d / len * th.first))
                t.set("Normal", LuaTypes.wrap(th.second))
                t.set("Distance", LuaValue.valueOf(th.first.toDouble()))
                val mat = com.leaf.lemonade.engine.TerrainMaterials.ENUM.getOrElse(th.third) { 1280 }
                t.set("Material", LuaTypes.wrap(EnumItemV("Material", Enums.itemName("Material", mat) ?: "Grass", mat)))
                return t
            }
        }
        val hit = partHit ?: return LuaValue.NIL
        val t = LuaTable()
        t.set("Instance", rt.wrap(hit.part))
        t.set("Position", LuaTypes.wrap(hit.position))
        t.set("Normal", LuaTypes.wrap(hit.normal))
        t.set("Distance", LuaValue.valueOf(hit.t.toDouble()))
        val mat = PartData.material(hit.part)
        t.set("Material", LuaTypes.wrap(EnumItemV("Material", Enums.itemName("Material", mat) ?: "Plastic", mat)))
        return t
    }

    /**
     * Class-specific properties scripts commonly probe for: name -> classes that have it. Asking
     * any other class errors like Roblox (everything else unknown still reads as nil).
     */
    private val MEMBER_OWNERS: Map<String, List<String>> = HashMap<String, List<String>>().apply {
        val text = listOf("TextLabel", "TextButton", "TextBox", "Message", "Hint")
        for (k in listOf("Text", "TextScaled", "TextSize", "TextBounds", "TextColor3", "TextWrapped", "TextXAlignment", "TextYAlignment",
            "Font", "FontFace", "TextTransparency", "TextStrokeTransparency", "TextStrokeColor3", "RichText", "LineHeight",
            "MaxVisibleGraphemes", "TextFits", "ContentText", "TextTruncate", "TextDirection", "TextWrap")) put(k, text)
        for (k in listOf("PlaceholderText", "PlaceholderColor3", "ClearTextOnFocus", "MultiLine", "CursorPosition", "SelectionStart", "TextEditable", "ShowNativeInput"))
            put(k, listOf("TextBox"))
        for (k in listOf("ScaleType", "SliceCenter", "SliceScale", "ImageRectOffset", "ImageRectSize", "TileSize", "ResampleMode"))
            put(k, listOf("ImageLabel", "ImageButton"))
        put("Image", listOf("ImageLabel", "ImageButton", "ImageHandleAdornment"))
        for (k in listOf("ImageColor3", "ImageTransparency")) put(k, listOf("ImageLabel", "ImageButton", "ViewportFrame"))
        for (k in listOf("BackgroundColor3", "BackgroundTransparency", "BorderColor3", "BorderSizePixel", "BorderMode", "AnchorPoint", "AutomaticSize",
            "ClipsDescendants", "LayoutOrder", "SizeConstraint", "Selectable", "Interactable")) put(k, listOf("GuiObject"))
        for (k in listOf("AutoButtonColor", "Modal", "Selected")) put(k, listOf("GuiButton"))
        for (k in listOf("Anchored", "CanCollide", "CanTouch", "CanQuery", "CastShadow", "Massless", "Reflectance", "Material", "MaterialVariant",
            "AssemblyLinearVelocity", "AssemblyAngularVelocity", "AssemblyMass", "AssemblyRootPart", "AssemblyCenterOfMass", "CenterOfMass", "SpecificGravity", "CollisionGroup", "CustomPhysicalProperties",
            "TopSurface", "BottomSurface", "FrontSurface", "BackSurface", "LeftSurface", "RightSurface", "BrickColor", "Locked", "RootPriority"))
            put(k, listOf("BasePart"))
        put("Value", listOf("ValueBase", "IntConstrainedValue", "DoubleConstrainedValue"))
        put("PrimaryPart", listOf("Model"))
        put("WorldPivot", listOf("PVInstance"))
        put("Source", listOf("LuaSourceContainer"))
        put("Disabled", listOf("BaseScript"))
        for (k in listOf("SoundId", "IsPaused", "RollOffMode", "SoundGroup")) put(k, listOf("Sound"))
        put("Volume", listOf("Sound", "SoundGroup", "AudioPlayer", "AudioFader", "VideoFrame"))
        for (k in listOf("TimePosition", "TimeLength", "IsPlaying")) put(k, listOf("Sound", "AudioPlayer", "VideoFrame"))
        put("Looped", listOf("Sound", "VideoFrame"))
        put("PlaybackSpeed", listOf("Sound", "AudioPlayer"))
        for (k in listOf("WalkSpeed", "JumpPower", "JumpHeight", "Health", "MaxHealth", "HipHeight", "RigType", "UseJumpPower")) put(k, listOf("Humanoid"))
        put("DisplayName", listOf("Humanoid", "Player"))
    }

    /** The edit model's Workspace camera (the one Studio's viewport shows). */
    private fun isEditCamera(cam: Instance): Boolean {
        val ws = rt.studio.editModel.workspace
        return cam.parent === ws && (ws["CurrentCamera"] === cam || ws["CurrentCamera"] == null)
    }

    private val PRIORITIES = listOf("Idle" to 0, "Movement" to 1, "Action" to 2, "Action2" to 3, "Action3" to 4, "Action4" to 5, "Core" to 1000)

    private fun priorityItem(v: Int) = EnumItemV("AnimationPriority", PRIORITIES.firstOrNull { it.second == v }?.first ?: "Core", v)

    /**
     * An AnimationTrack. In a playtest it really plays the animation (downloaded by AnimationId,
     * or a KeyframeSequence inside the Animation) on the rig that owns [owner]; elsewhere it
     * only keeps its state so plugin code works.
     */
    private fun animationTrack(owner: Instance, anim: Instance?): LuaTable =
        if (rt.play == null && !rt.isPlay) editAnimationTrack(owner, anim) else playAnimationTrack(owner, anim)

    /** An AnimationTrack while editing: it poses the rig when the plugin calls Animator:StepAnimations. */
    private fun editAnimationTrack(owner: Instance, anim: Instance?): LuaTable {
        val t = LuaTable()
        val ea = rt.studio.editAnim
        val st = ea.loadTrack(owner, anim, t)
        var speed = 1f
        t.set("Play", fnv { a ->
            val fade = a.arg(2).optdouble(0.1).toFloat(); val w = a.arg(3).optdouble(1.0).toFloat(); speed = a.arg(4).optdouble(speed.toDouble()).toFloat()
            st?.let { ea.play(it, fade, w, speed); rt.fire(t, "Played") }
            LuaValue.NONE
        })
        t.set("Stop", fnv { a ->
            val was = st?.isPlaying == true
            st?.let { ea.stop(it, a.arg(2).optdouble(0.1).toFloat()) }
            if (was) { rt.fire(t, "Stopped"); rt.fire(t, "Ended") }
            LuaValue.NONE
        })
        t.set("AdjustSpeed", fnv { a -> speed = a.arg(2).optdouble(1.0).toFloat(); st?.let { ea.adjust(it, speed = speed) }; LuaValue.NONE })
        t.set("AdjustWeight", fnv { a -> st?.let { ea.adjust(it, weight = a.arg(2).optdouble(1.0).toFloat(), fade = a.arg(3).optdouble(0.1).toFloat()) }; LuaValue.NONE })
        t.set("GetMarkerReachedSignal", fnv { a -> signal(t, "Marker:" + a.arg(2).tojstring()) })
        t.set("GetTimeOfKeyframe", fnv { LuaValue.valueOf(0) })
        t.set("Destroy", fnv { _ -> st?.let { ea.stop(it, 0f) }; LuaValue.NONE })
        t.set("Animation", rt.wrap(anim))
        val mt = LuaTable()
        mt.set(LuaValue.INDEX, fn2 { _, k ->
            when (val ks = k.tojstring()) {
                "IsPlaying" -> LuaValue.valueOf(st?.isPlaying ?: false)
                "Length" -> LuaValue.valueOf((st?.length ?: 0f).toDouble())
                "TimePosition" -> LuaValue.valueOf((st?.time ?: 0f).toDouble())
                "Speed" -> LuaValue.valueOf(speed.toDouble())
                "Looped" -> LuaValue.valueOf(st?.track?.looped ?: st?.looped ?: st?.clip?.looped ?: false)
                "Priority" -> LuaTypes.wrap(priorityItem(st?.priority ?: st?.clip?.priority ?: 1000))
                "WeightCurrent" -> LuaValue.valueOf((st?.weightNow ?: 0f).toDouble())
                "WeightTarget" -> LuaValue.valueOf((st?.weightTarget ?: 0f).toDouble())
                "Name" -> LuaValue.valueOf(anim?.name ?: "Animation")
                "Stopped", "Ended", "DidLoop", "KeyframeReached", "Played" -> signal(t, ks)
                else -> LuaValue.NIL
            }
        })
        mt.set(LuaValue.NEWINDEX, object : org.luaj.vm2.lib.ThreeArgFunction() {
            override fun call(self: LuaValue, k: LuaValue, v: LuaValue): LuaValue {
                when (k.tojstring()) {
                    "Looped" -> st?.let { ea.adjust(it, looped = v.toboolean()) }
                    "Priority" -> st?.let { ea.adjust(it, priority = v.udOrNull<EnumItemV>()?.value ?: v.optint(1000)) }
                    "TimePosition" -> st?.let { ea.adjust(it, time = v.optdouble(0.0).toFloat()) }
                    else -> (self as LuaTable).rawset(k, v)
                }
                return LuaValue.NONE
            }
        })
        t.setmetatable(mt)
        return t
    }

    /** Animator / AnimationController methods (Animator:LoadAnimation is how most scripts play animations). */
    fun animatorCall(inst: Instance, name: String, a: Varargs): Varargs = when (name) {
        "LoadAnimation" -> animationTrack(inst, a.arg(2).udOrNull<Instance>())
        "GetPlayingAnimationTracks" -> {
            val l = if (rt.play == null && !rt.isPlay) rt.studio.editAnim.playingTables(inst) else rt.play?.playingTrackTables(inst) ?: emptyList()
            LuaTable().also { tb -> l.forEachIndexed { i, x -> tb.set(i + 1, x) } }
        }
        "StepAnimations" -> {
            if (rt.play == null && !rt.isPlay) for ((tb, ev) in rt.studio.editAnim.step(inst, a.arg(2).optdouble(0.0).toFloat())) rt.fire(tb, ev)
            LuaValue.NONE
        }
        else -> LuaValue.NONE
    }

    private fun playAnimationTrack(owner: Instance, anim: Instance?): LuaTable {
        val t = LuaTable()
        val play = rt.play
        val st = play?.loadTrack(owner, anim, t)
        var playing = false
        var speed = 1f
        var looped: Boolean? = null
        var priority: Int? = null
        t.set("Play", fnv { a ->
            val fade = a.arg(2).optdouble(0.1).toFloat(); val w = a.arg(3).optdouble(1.0).toFloat(); val sp = a.arg(4).optdouble(speed.toDouble()).toFloat()
            speed = sp
            if (st != null) play?.playTrack(st, fade, w, sp) else playing = true
            LuaValue.NONE
        })
        t.set("Stop", fnv { a ->
            val fade = a.arg(2).optdouble(0.1).toFloat()
            if (st != null) play?.stopTrack(st, fade) else if (playing) { playing = false; rt.fire(t, "Stopped"); rt.fire(t, "Ended") }
            LuaValue.NONE
        })
        t.set("AdjustSpeed", fnv { a -> speed = a.arg(2).optdouble(1.0).toFloat(); st?.let { play?.adjustTrack(it, speed = speed) }; LuaValue.NONE })
        t.set("AdjustWeight", fnv { a -> st?.let { play?.adjustTrack(it, weight = a.arg(2).optdouble(1.0).toFloat(), fade = a.arg(3).optdouble(0.1).toFloat()) }; LuaValue.NONE })
        t.set("GetMarkerReachedSignal", fnv { a -> signal(t, "Marker:" + a.arg(2).tojstring()) })
        t.set("GetTimeOfKeyframe", fnv { LuaValue.valueOf(0) })
        t.set("Destroy", fnv { _ -> st?.let { play?.stopTrack(it, 0f) }; LuaValue.NONE })
        t.set("Animation", rt.wrap(anim))
        val mt = LuaTable()
        mt.set(LuaValue.INDEX, fn2 { _, k ->
            when (val ks = k.tojstring()) {
                "IsPlaying" -> LuaValue.valueOf(st?.isPlaying ?: playing)
                "Length" -> LuaValue.valueOf((st?.length ?: 0f).toDouble())
                "TimePosition" -> LuaValue.valueOf((st?.time ?: 0f).toDouble())
                "Speed" -> LuaValue.valueOf(speed.toDouble())
                "Looped" -> LuaValue.valueOf(st?.looped ?: looped ?: false)
                "Priority" -> LuaTypes.wrap(priorityItem(st?.priority ?: priority ?: 1000))
                "WeightCurrent" -> LuaValue.valueOf((st?.weightNow ?: if (playing) 1f else 0f).toDouble())
                "WeightTarget" -> LuaValue.valueOf((st?.weightTarget ?: if (playing) 1f else 0f).toDouble())
                "Name" -> LuaValue.valueOf(anim?.name ?: "Animation")
                "Stopped", "Ended", "DidLoop", "KeyframeReached", "Played" -> signal(t, ks)
                else -> LuaValue.NIL
            }
        })
        mt.set(LuaValue.NEWINDEX, object : org.luaj.vm2.lib.ThreeArgFunction() {
            override fun call(self: LuaValue, k: LuaValue, v: LuaValue): LuaValue {
                when (k.tojstring()) {
                    "Looped" -> { looped = v.toboolean(); st?.let { play?.adjustTrack(it, looped = looped) } }
                    "Priority" -> { priority = v.udOrNull<EnumItemV>()?.value ?: v.optint(1000); st?.let { play?.adjustTrack(it, priority = priority) } }
                    "TimePosition" -> st?.let { play?.adjustTrack(it, time = v.optdouble(0.0).toFloat()) }
                    else -> (self as LuaTable).rawset(k, v)
                }
                return LuaValue.NONE
            }
        })
        t.setmetatable(mt)
        return t
    }

    // ------------------------------------------------------------------ methods

    private fun self(a: Varargs, name: String): Instance =
        a.arg(1).udOrNull<Instance>() ?: throw LuaError("Expected ':' not '.' calling member function $name")

    fun callMethod(name: String, a: Varargs): Varargs {
        val inst = self(a, name)
        val a2 = a.arg(2)
        if (inst.className == "CollectionService" && name in setOf("AddTag", "RemoveTag", "HasTag", "GetTags")) return collectionCall(name, a)
        return when (name) {
            "FindFirstChild" -> rt.wrap(inst.findFirstChild(a2.checkjstring(), a.arg(3).toboolean()))
            "FindFirstChildOfClass" -> rt.wrap(inst.findFirstChildOfClass(a2.checkjstring()))
            "FindFirstChildWhichIsA" -> rt.wrap(inst.findFirstChildWhichIsA(a2.checkjstring(), a.arg(3).toboolean()))
            "FindFirstDescendant" -> rt.wrap(inst.findFirstChild(a2.checkjstring(), true))
            "FindFirstAncestor" -> { var p = inst.parent; while (p != null && p.name != a2.tojstring()) p = p.parent; rt.wrap(p) }
            "FindFirstAncestorOfClass" -> { var p = inst.parent; while (p != null && p.className != a2.tojstring()) p = p.parent; rt.wrap(p) }
            "FindFirstAncestorWhichIsA" -> { var p = inst.parent; while (p != null && !p.isA(a2.tojstring())) p = p.parent; rt.wrap(p) }
            "WaitForChild" -> rt.waitForChild(inst, a2.checkjstring(), if (a.arg(3).isnumber()) a.arg(3).todouble() else null)
            "GetChildren", "children" -> listToLua(inst.children.toList())
            "GetDescendants" -> listToLua(inst.descendants())
            "IsA" -> LuaValue.valueOf(inst.isA(a2.checkjstring()))
            "IsDescendantOf" -> LuaValue.valueOf(a2.udOrNull<Instance>()?.let { inst.isDescendantOf(it) } ?: false)
            "IsAncestorOf" -> LuaValue.valueOf(a2.udOrNull<Instance>()?.isDescendantOf(inst) ?: false)
            "Destroy", "Remove" -> {
                if (inst.isService || inst is DataModel) throw LuaError("Cannot destroy ${inst.name}")
                if (name == "Destroy") inst.destroy() else inst.setParent(null)
                LuaValue.NONE
            }
            "Clone" -> if (!inst.archivable) LuaValue.NIL else rt.wrap(inst.cloneTree())
            "ClearAllChildren" -> { inst.children.toList().forEach { if (!it.isService) it.destroy() }; LuaValue.NONE }
            "GetFullName" -> LuaValue.valueOf(inst.fullName())
            "GetDebugId" -> LuaValue.valueOf(Integer.toHexString(System.identityHashCode(inst)))
            "GetActor" -> LuaValue.NIL
            "IsPropertyModified" -> LuaValue.FALSE
            "ResetPropertyToDefault" -> LuaValue.NONE
            "GetAttribute" -> { Attributes.load(inst); rt.toLua(inst.attributes[a2.checkjstring()]) }
            "GetAttributes" -> {
                Attributes.load(inst)
                val t = LuaTable(); for ((k, v) in inst.attributes) t.set(k, rt.toLua(v)); t
            }
            "SetAttribute" -> {
                Attributes.load(inst)
                val k = a2.checkjstring()
                val v = a.arg(3)
                if (v.isnil()) inst.attributes.remove(k) else inst.attributes[k] = rt.fromLua(v, null)
                rt.fire(inst, "AttributeChanged", LuaValue.valueOf(k))
                rt.fire(inst, "Attr:$k")
                LuaValue.NONE
            }
            "GetAttributeChangedSignal" -> signal(inst, "Attr:" + a2.checkjstring())
            "GetPropertyChangedSignal" -> signal(inst, "Changed:" + a2.checkjstring())
            "GetService", "FindService" -> {
                val n = a2.checkjstring()
                if (inst.className == "UserSettings") return inst.children.firstOrNull { it.className == n || it.name == n }?.let { rt.wrap(it) } ?: LuaValue.NIL
                val dm = inst as? DataModel ?: rt.dm
                if (name == "FindService") rt.wrap(dm.findService(n))
                else rt.wrap(dm.findService(n) ?: dm.getService(n).also { if (n !in ClassDb.serviceNames) it.archivable = false })
            }
            "GetTags" -> { val t = LuaTable(); tags(inst).forEach { t.insert(0, LuaValue.valueOf(it)) }; t }
            "HasTag" -> LuaValue.valueOf(a2.checkjstring() in tags(inst))
            "AddTag" -> { val t = tags(inst); val tg = a2.checkjstring(); if (tg !in t) { t.add(tg); setTags(inst, t); rt.fire(rt.dm.getService("CollectionService"), "TagAdded:$tg", rt.wrap(inst)) }; LuaValue.NONE }
            "RemoveTag" -> { val t = tags(inst); val tg = a2.checkjstring(); if (t.remove(tg)) { setTags(inst, t); rt.fire(rt.dm.getService("CollectionService"), "TagRemoved:$tg", rt.wrap(inst)) }; LuaValue.NONE }
            else -> RobloxServices.call(this, rt, inst, name, a)
        }
    }

    fun collectionCall(name: String, a: Varargs): Varargs {
        val a2 = a.arg(2)
        return when (name) {
            "GetTagged" -> listToLua(rt.dm.descendants().filter { a2.checkjstring() in tags(it) })
            "AddTag", "RemoveTag", "HasTag", "GetTags" -> {
                val target = a2.udOrNull<Instance>() ?: throw LuaError("Instance expected")
                callMethod(name, LuaValue.varargsOf(arrayOf(rt.wrap(target), a.arg(3))))
            }
            "GetInstanceAddedSignal" -> signal(rt.dm.getService("CollectionService"), "TagAdded:" + a2.checkjstring())
            "GetInstanceRemovedSignal" -> signal(rt.dm.getService("CollectionService"), "TagRemoved:" + a2.checkjstring())
            "GetAllTags" -> { val s = LinkedHashSet<String>(); rt.dm.descendants().forEach { s.addAll(tags(it)) }; LuaTable().also { t -> s.forEach { t.insert(0, LuaValue.valueOf(it)) } } }
            else -> LuaValue.NONE
        }
    }

    fun workspaceCall(name: String, a: Varargs): Varargs {
        return when (name) {
            "Raycast" -> raycastResult(a.arg(2).ud("Vector3"), a.arg(3).ud("Vector3"), filterFrom(a.arg(4)))
            "Spherecast", "Blockcast" -> {
                val o = if (name == "Blockcast") a.arg(2).ud<CFrame>("CFrame").position else a.arg(2).ud<Vector3>("Vector3")
                raycastResult(o, a.arg(4).ud("Vector3"), filterFrom(a.arg(5)))
            }
            "FindPartOnRay", "FindPartOnRayWithIgnoreList", "FindPartOnRayWithWhitelist" -> {
                val ray = a.arg(2).ud<Ray>("Ray")
                val list = when {
                    a.arg(3).istable() -> instList(a.arg(3))
                    a.arg(3).isuserdata() -> listOfNotNull(a.arg(3).udOrNull<Instance>())
                    else -> emptyList()
                }
                val white = name == "FindPartOnRayWithWhitelist"
                val len = ray.direction.magnitude
                val hit = if (len < 1e-6f) null else Geometry.raycast(workspaceParts(), ray.origin, ray.direction / len, len) { p ->
                    val inList = list.any { f -> p === f || p.isDescendantOf(f) }
                    if (white) inList else !inList
                }
                if (hit == null) LuaValue.varargsOf(LuaValue.NIL, LuaTypes.wrap(ray.origin + ray.direction))
                else LuaValue.varargsOf(arrayOf(rt.wrap(hit.part), LuaTypes.wrap(hit.position), LuaTypes.wrap(hit.normal),
                    LuaTypes.wrap(EnumItemV("Material", Enums.itemName("Material", PartData.material(hit.part)) ?: "Plastic", PartData.material(hit.part)))))
            }
            "GetPartBoundsInBox" -> {
                val cf = a.arg(2).ud<CFrame>("CFrame"); val size = a.arg(3).ud<Vector3>("Vector3")
                val (b0, b1) = Geometry.worldAabb(cf, size)
                val f = filterFrom(a.arg(4))
                listToLua(workspaceParts().filter { f(it) && Geometry.partAabb(it).let { (x0, x1) -> Geometry.aabbOverlap(x0, x1, b0, b1) } })
            }
            "GetPartBoundsInRadius" -> {
                val c = a.arg(2).ud<Vector3>("Vector3"); val r = a.arg(3).num()
                val f = filterFrom(a.arg(4))
                listToLua(workspaceParts().filter { p ->
                    if (!f(p)) return@filter false
                    val (x0, x1) = Geometry.partAabb(p)
                    val cl = c.max(x0).min(x1)
                    (cl - c).magnitude <= r
                })
            }
            "GetPartsInPart" -> {
                val part = a.arg(2).ud<Instance>("Instance")
                val (b0, b1) = Geometry.partAabb(part)
                val f = filterFrom(a.arg(3))
                listToLua(workspaceParts().filter { it !== part && f(it) && Geometry.partAabb(it).let { (x0, x1) -> Geometry.aabbOverlap(x0, x1, b0, b1) } })
            }
            "FindPartsInRegion3", "FindPartsInRegion3WithIgnoreList", "FindPartsInRegion3WithWhiteList", "IsRegion3Empty", "IsRegion3EmptyWithIgnoreList" -> {
                val r = a.arg(2).ud<Region3V>("Region3")
                val listArg = a.arg(3)
                val list = when {
                    listArg.istable() -> instList(listArg)
                    listArg.isuserdata() -> listOfNotNull(listArg.udOrNull<Instance>())
                    else -> emptyList()
                }
                val white = name == "FindPartsInRegion3WithWhiteList"
                val max = if (name.startsWith("Is")) 1 else a.arg(4).optint(20).coerceAtLeast(1)
                val out = ArrayList<Instance>()
                for (p in workspaceParts()) {
                    val inList = list.any { f -> p === f || p.isDescendantOf(f) }
                    if (if (white) !inList else inList) continue
                    val (x0, x1) = Geometry.partAabb(p)
                    if (Geometry.aabbOverlap(x0, x1, r.min, r.max)) { out.add(p); if (out.size >= max) break }
                }
                if (name.startsWith("Is")) LuaValue.valueOf(out.isEmpty()) else listToLua(out)
            }
            "GetServerTimeNow" -> LuaValue.valueOf(System.currentTimeMillis() / 1000.0)
            "GetRealPhysicsFPS" -> LuaValue.valueOf(60)
            // Surface joints are a Studio dragger detail here (parts don't auto-weld): nothing to do.
            "JoinToOutsiders", "UnjoinFromOutsiders", "SetInsertPoint", "ZoomToExtents" -> LuaValue.NONE
            "BulkMoveTo" -> {
                val parts = instList(a.arg(2))
                val cfs = a.arg(3).checktable()
                for ((i, p) in parts.withIndex()) {
                    val cf = cfs.get(i + 1).udOrNull<CFrame>() ?: continue
                    if (p.isA("BasePart")) setK(p, "CFrame", cf)
                }
                LuaValue.NONE
            }
            "ArePartsTouchingOthers" -> {
                val parts = instList(a.arg(2))
                val pad = a.arg(3).optdouble(0.0).toFloat()
                val set = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>()).also { it.addAll(parts) }
                val others = workspaceParts().filter { it !in set }
                LuaValue.valueOf(parts.any { p -> val (a0, a1) = Geometry.partAabb(p); others.any { o -> Geometry.partAabb(o).let { (b0, b1) -> Geometry.aabbOverlap(a0, a1, b0, b1, -pad) } } })
            }
            "GetNumAwakeParts" -> LuaValue.valueOf(0)
            "PGSIsEnabled" -> LuaValue.TRUE
            "GetPhysicsThrottling" -> LuaValue.valueOf(100)
            "CalculateJumpPower" -> LuaValue.valueOf(kotlin.math.sqrt(2.0 * a.arg(2).optdouble(196.2) * a.arg(3).optdouble(7.2)))
            "CalculateJumpHeight" -> { val g = a.arg(2).optdouble(196.2); val jp = a.arg(3).optdouble(50.0); LuaValue.valueOf(jp * jp / (2.0 * g)) }
            "Shapecast" -> raycastResult(PartData.cframe(a.arg(2).ud("Instance")).position, a.arg(3).ud("Vector3"), filterFrom(a.arg(4)))
            else -> LuaValue.NONE
        }
    }

    fun modelCall(inst: Instance, name: String, a: Varargs): Varargs {
        return when (name) {
            "GetPivot", "GetModelCFrame", "GetPrimaryPartCFrame" -> LuaTypes.wrap(if (inst.isA("BasePart")) PartData.cframe(inst) else modelPivot(inst))
            "PivotTo", "SetPrimaryPartCFrame" -> { pivotTo(inst, a.arg(2).ud("CFrame")); LuaValue.NONE }
            "MoveTo" -> {
                val target = a.arg(2).ud<Vector3>("Vector3")
                val piv = modelPivot(inst)
                pivotTo(inst, piv.withPosition(target))
                LuaValue.NONE
            }
            "TranslateBy" -> { val d = a.arg(2).ud<Vector3>("Vector3"); pivotTo(inst, modelPivot(inst) + d); LuaValue.NONE }
            "GetBoundingBox" -> {
                val b = Geometry.boundsOf(partsIn(inst)) ?: (Vector3.ZERO to Vector3.ZERO)
                LuaValue.varargsOf(LuaTypes.wrap(CFrame.at((b.first + b.second) / 2f)), LuaTypes.wrap(b.second - b.first))
            }
            "GetExtentsSize", "GetModelSize" -> { val b = Geometry.boundsOf(partsIn(inst)) ?: (Vector3.ZERO to Vector3.ZERO); LuaTypes.wrap(b.second - b.first) }
            "ScaleTo" -> {
                val s = a.arg(2).tofloat()
                if (s.isNaN()) {
                    com.leaf.lemonade.core.OutputLog.warn("Ignoring ScaleTo(NaN) call, check your code for divide by zero errors")
                    return LuaValue.NONE
                }
                if (s <= 0f) throw LuaError("Only positive non-zero scale values are allowed.")
                val old = (inst.attributes["__scale"] as? Number)?.toFloat() ?: 1f
                val f = s / old; val piv = modelPivot(inst)
                fun scaleCf(cf: CFrame) = cf.withPosition(cf.position * f)
                for (d in inst.descendants()) {
                    when {
                        d.isA("BasePart") -> {
                            val cf = PartData.cframe(d)
                            val sz = PartData.size(d) * f
                            PartData.setSize(d, Vector3(sz.x.coerceIn(0.001f, 2048f), sz.y.coerceIn(0.001f, 2048f), sz.z.coerceIn(0.001f, 2048f)))
                            PartData.setCFrame(d, cf.withPosition(piv.position + (cf.position - piv.position) * f))
                        }
                        d.className == "Attachment" || d.className == "Bone" -> (d["CFrame"] as? CFrame)?.let { d.set("CFrame", scaleCf(it), true) }
                        d.isA("JointInstance") -> for (k in listOf("C0", "C1")) (d[k] as? CFrame)?.let { d.set(k, scaleCf(it), true) }
                        d.className == "SpecialMesh" -> for (k in listOf("Scale", "Offset")) (d[k] as? Vector3)?.let { d.set(k, it * f, true) }
                        else -> {
                            val keys = when (d.className) {
                                "RopeConstraint", "RodConstraint" -> listOf("Length", "Thickness")
                                "SpringConstraint" -> listOf("FreeLength", "MinLength", "MaxLength", "Radius", "Thickness")
                                "PrismaticConstraint", "CylindricalConstraint" -> listOf("LowerLimit", "UpperLimit", "Size")
                                "Humanoid" -> listOf("HipHeight")
                                "Beam" -> listOf("Width0", "Width1", "CurveSize0", "CurveSize1")
                                "PointLight", "SpotLight", "SurfaceLight" -> listOf("Range")
                                else -> emptyList()
                            }
                            for (k in keys) (d[k] as? Number)?.let { d.set(k, it.toFloat() * f, true) }
                        }
                    }
                }
                inst.attributes["__scale"] = s
                LuaValue.NONE
            }
            "GetScale" -> LuaValue.valueOf(((inst.attributes["__scale"] as? Number)?.toDouble()) ?: 1.0)
            else -> LuaValue.NONE
        }
    }

    fun partCall(inst: Instance, name: String, a: Varargs): Varargs {
        return when (name) {
            "GetMass" -> LuaValue.valueOf(PartPhysics.effMass(inst).toDouble())
            "ApplyImpulse" -> {
                val imp = a.arg(2).ud<Vector3>("Vector3"); val am = PartPhysics.assembly(inst).mass
                if (am > 0f && !PartPhysics.graph(inst).grounded(inst)) rt.play?.let { it.setVelocity(inst, (it.velocityOf(inst) ?: Vector3.ZERO) + imp / am) }
                LuaValue.NONE
            }
            "GetTouchingParts" -> listToLua(rt.play?.touchingParts(inst) ?: emptyList())
            "GetConnectedParts" -> listToLua(PartPhysics.connected(inst, a.arg(2).toboolean()))
            "GetJoints" -> listToLua(PartPhysics.jointsOf(inst))
            "GetRootPart" -> rt.wrap(PartPhysics.graph(inst).root(inst))
            "GetNetworkOwner" -> rt.wrap(rt.play?.player)
            "CanSetNetworkOwnership" -> LuaValue.TRUE
            "IsGrounded" -> LuaValue.valueOf(PartPhysics.graph(inst).grounded(inst))
            "GetVelocityAtPosition" -> LuaTypes.wrap(rt.play?.velocityOf(inst) ?: Vector3.ZERO)
            "Resize" -> LuaValue.TRUE
            "GetPivot", "PivotTo" -> modelCall(inst, name, a)
            else -> LuaValue.NONE
        }
    }

    fun humanoidCall(inst: Instance, name: String, a: Varargs): Varargs {
        val play = rt.play
        return when (name) {
            "TakeDamage" -> {
                val hasFF = inst.parent?.findFirstChildOfClass("ForceField") != null
                if (!hasFF) setK(inst, "Health", ((getK(inst, "Health") as? Number)?.toFloat() ?: 100f) - a.arg(2).num())
                LuaValue.NONE
            }
            "MoveTo" -> { play?.moveTo(inst, a.arg(2).ud("Vector3")); LuaValue.NONE }
            "Move" -> { play?.scriptMove(inst, a.arg(2).ud("Vector3")); LuaValue.NONE }
            "ChangeState" -> {
                val st = a.arg(2).udOrNull<EnumItemV>()?.value ?: a.arg(2).optint(8)
                if (st == 3) play?.jump(inst)
                if (st == 15) setK(inst, "Health", 0f)
                LuaValue.NONE
            }
            "GetState" -> {
                val g = play?.isGrounded(inst) ?: true
                LuaTypes.wrap(if (g) EnumItemV("HumanoidStateType", "Running", 8) else EnumItemV("HumanoidStateType", "Freefall", 5))
            }
            "LoadAnimation" -> animationTrack(inst, a.arg(2).udOrNull<Instance>())
            "GetPlayingAnimationTracks" -> animatorCall(inst, name, a)
            "EquipTool" -> { a.arg(2).udOrNull<Instance>()?.let { play?.equip(it) }; LuaValue.NONE }
            "UnequipTools" -> { play?.unequip(); LuaValue.NONE }
            "SetStateEnabled" -> LuaValue.NONE
            "GetStateEnabled" -> LuaValue.TRUE
            "ApplyDescription", "AddAccessory" -> LuaValue.NONE
            "GetAppliedDescription" -> LuaValue.NIL
            "GetAccessories" -> listToLua(inst.parent?.children?.filter { it.isA("Accessory") } ?: emptyList())
            else -> LuaValue.NONE
        }
    }
}

/** Decodes the AttributesSerialize blob lazily into Instance.attributes. */
object Attributes {
    private val loaded = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Instance, Boolean>())

    fun load(i: Instance) {
        if (!loaded.add(i)) return
        val blob = when (val v = i["AttributesSerialize"]) {
            is BinaryString -> v.bytes
            is String -> v.toByteArray(Charsets.ISO_8859_1)
            else -> return
        }
        if (blob.size < 4) return
        try {
            val bb = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            fun str(): String { val n = bb.int; val b = ByteArray(n); bb.get(b); return String(b, Charsets.UTF_8) }
            val count = bb.int
            repeat(count) {
                val key = str()
                val v: Any? = when (bb.get().toInt() and 0xFF) {
                    0x02 -> str()
                    0x03 -> bb.get().toInt() != 0
                    0x05 -> bb.float
                    0x06 -> bb.double
                    0x09 -> UDim(bb.float, bb.int)
                    0x0A -> UDim2(bb.float, bb.int, bb.float, bb.int)
                    0x0E -> BrickColorV(bb.int)
                    0x0F -> Color3(bb.float, bb.float, bb.float)
                    0x10 -> Vector2(bb.float, bb.float)
                    0x11 -> Vector3(bb.float, bb.float, bb.float)
                    0x1B -> NumberRange(bb.float, bb.float)
                    0x1C -> Rect(bb.float, bb.float, bb.float, bb.float)
                    else -> return
                }
                if (!i.attributes.containsKey(key)) i.attributes[key] = v
            }
        } catch (e: Exception) {
            OutputLog.warn("Could not read attributes of ${i.fullName()}")
        }
    }
}
