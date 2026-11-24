package com.leaf.lemonade.lua

import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object RobloxServices {

    fun call(api: InstanceApi, rt: LuaRuntime, inst: Instance, name: String, a: Varargs): Varargs {
        val a2 = a.arg(2)
        val cls = inst.className
        if (cls == "Terrain" && name in TerrainApi.METHODS) return TerrainApi.call(rt, inst, name, a)
        if (inst.isA("BasePart")) return api.partCall(inst, name, a)
        if (inst.isA("Model") && cls != "Workspace") return api.modelCall(inst, name, a)
        if (cls == "Workspace") {
            return if (name in setOf("GetPivot", "PivotTo", "MoveTo", "GetBoundingBox", "GetExtentsSize", "TranslateBy")) api.modelCall(inst, name, a)
            else api.workspaceCall(name, a)
        }
        if (cls == "Humanoid") return api.humanoidCall(inst, name, a)
        if (cls == "Animator" || cls == "AnimationController") return api.animatorCall(inst, name, a)
        if (cls == "KeyframeSequenceProvider") return keyframeProvider(rt, name, a)
        if (cls == "CollectionService") return api.collectionCall(name, a)
        if (cls == "Plugin" || cls == "PluginToolbar" || cls == "PluginToolbarButton" || inst.isA("PluginGui")) return PluginApi.call(rt, inst, name, a)
        val studio = rt.studio
        val play = rt.play
        return when (name) {
            // ------------------------------------------------ DataModel
            "BindToClose" -> { rt.connect(inst, "Close", a2.checkfunction(), true); LuaValue.NONE }
            "IsLoaded" -> LuaValue.TRUE
            "GetJobsInfo" -> LuaTable()
            "SetPlaceId", "GetObjects" -> LuaValue.NONE
            // ------------------------------------------------ RunService
            "IsStudio" -> LuaValue.TRUE
            "IsRunning", "IsRunMode" -> LuaValue.valueOf(rt.isPlay)
            // Studio's edit mode counts as both (plugins such as F3X rely on IsServer there), and
            // a playtest here runs client and server code in one place.
            "IsServer", "IsClient" -> LuaValue.TRUE
            "IsEdit" -> LuaValue.valueOf(!rt.isPlay)
            "BindToRenderStep" -> {
                val c = rt.connect(inst, "RenderStepped", a.arg(4).checkfunction(), false)
                rt.callbacks.getOrPut(inst) { HashMap() }["__bind_" + a2.tojstring()] = LuaValue.userdataOf(c)
                LuaValue.NONE
            }
            "UnbindFromRenderStep" -> {
                (rt.callbacks[inst]?.remove("__bind_" + a2.tojstring())?.touserdata() as? Connection)?.let { rt.disconnect(it) }
                LuaValue.NONE
            }
            // ------------------------------------------------ TweenService
            "Create" -> {
                val target = a2.ud<Instance>("Instance")
                val info = a.arg(3).ud<TweenInfoV>("TweenInfo")
                val goals = LinkedHashMap<String, Any?>()
                val gt = a.arg(4).checktable()
                var k = LuaValue.NIL
                while (true) {
                    val n = gt.next(k)
                    k = n.arg1()
                    if (k.isnil()) break
                    val prop = k.tojstring()
                    val cur = api.getK(target, prop)
                    goals[prop] = rt.fromLua(n.arg(2), cur?.let { PropType.infer(it) }, target, PropNames.resolve(target, prop))
                }
                tweenValue(rt, Tween(target, info, goals))
            }
            "GetValue" -> LuaValue.valueOf(LuaTypes.ease(a.arg(3).udOrNull<EnumItemV>()?.value ?: 3, a.arg(4).udOrNull<EnumItemV>()?.value ?: 1, a2.num()).toDouble())
            // ------------------------------------------------ Debris
            "AddItem", "addItem" -> {
                val item = a2.ud<Instance>("Instance")
                val t = a.arg(3).optNum(10f).toDouble()
                rt.after(t) { if (!item.destroyed) item.destroy() }
                LuaValue.NONE
            }
            // ------------------------------------------------ HttpService
            "JSONEncode" -> LuaValue.valueOf(Json.encode(a2))
            "JSONDecode" -> try { Json.decode(rt, a2.checkjstring()) } catch (e: Exception) { throw LuaError("Can't parse JSON") }
            "GenerateGUID" -> { val g = UUID.randomUUID().toString().uppercase(); LuaValue.valueOf(if (a2.isnil() || a2.toboolean()) "{$g}" else g) }
            "UrlEncode" -> LuaValue.valueOf(java.net.URLEncoder.encode(a2.checkjstring(), "UTF-8").replace("+", "%20"))
            "GetAsync", "PostAsync", "RequestAsync" -> http(rt, inst, name, a)
            // ------------------------------------------------ Players
            "GetPlayers" -> LuaTable().also { t -> play?.player?.let { t.insert(0, rt.wrap(it)) } }
            "GetPlayerFromCharacter" -> rt.wrap(if (play != null && a2.udOrNull<Instance>() === play.character) play.player else null)
            "GetPlayerByUserId" -> rt.wrap(play?.player?.takeIf { (it["UserId"] as? Number)?.toLong() == a2.tolong() })
            "GetUserIdFromNameAsync" -> LuaValue.valueOf(1)
            "GetNameFromUserIdAsync" -> LuaValue.valueOf("Player1")
            "GetUserThumbnailAsync" -> LuaValue.varargsOf(LuaValue.valueOf("rbxasset://textures/ui/GuiImagePlaceholder.png"), LuaValue.TRUE)
            "GetHumanoidDescriptionFromUserId" -> rt.wrap(InstanceFactory.create("HumanoidDescription"))
            // ------------------------------------------------ Player
            "Kick" -> {
                OutputLog.warn("Player kicked: ${if (a2.isnil()) "" else a2.tojstring()}")
                studio.main.post { studio.stopPlay() }
                LuaValue.NONE
            }
            "LoadCharacter" -> { play?.respawn(); LuaValue.NONE }
            "GetMouse" -> play?.mouseValue() ?: LuaValue.NIL
            "IsInGroup", "IsFriendsWith", "HasAppearanceLoaded" -> LuaValue.valueOf(name == "HasAppearanceLoaded")
            "GetRankInGroup" -> LuaValue.valueOf(0)
            "GetRoleInGroup" -> LuaValue.valueOf("Guest")
            "DistanceFromCharacter" -> {
                val hrp = play?.character?.findFirstChild("HumanoidRootPart")
                LuaValue.valueOf(if (hrp == null) 0.0 else (PartData.cframe(hrp).position - a2.ud<Vector3>("Vector3")).magnitude.toDouble())
            }
            "GetJoinData" -> LuaTable()
            "GetNetworkPing" -> LuaValue.valueOf(0)
            "ClearCharacterAppearance" -> LuaValue.NONE
            // ------------------------------------------------ Selection / ChangeHistory (plugins)
            "Get" -> LuaTable().also { t -> studio.selection.forEach { t.insert(0, rt.wrap(it)) } }
            "Set" -> { studio.selectFromScript((1..a2.length()).mapNotNull { a2.get(it).udOrNull<Instance>() }); LuaValue.NONE }
            "Add" -> { studio.selectFromScript(studio.selection + (1..a2.length()).mapNotNull { a2.get(it).udOrNull<Instance>() }); LuaValue.NONE }
            "Remove" -> { val r = (1..a2.length()).mapNotNull { a2.get(it).udOrNull<Instance>() }.toSet(); studio.selectFromScript(studio.selection.filter { it !in r }); LuaValue.NONE }
            "SetWaypoint", "ResetWaypoints", "SetEnabled", "FinishRecording" -> LuaValue.NONE
            "TryBeginRecording" -> LuaValue.valueOf(UUID.randomUUID().toString())
            "Undo" -> { studio.main.post { studio.undo.undo() }; LuaValue.NONE }
            "Redo" -> { studio.main.post { studio.undo.redo() }; LuaValue.NONE }
            "GetCanUndo", "GetCanRedo" -> LuaValue.TRUE
            // ------------------------------------------------ DataStores (in-memory)
            "GetDataStore", "GetGlobalDataStore", "GetOrderedDataStore" -> dataStore(rt, if (a2.isnil()) "global" else a2.tojstring())
            // ------------------------------------------------ Marketplace
            "PromptPurchase", "PromptGamePassPurchase", "PromptProductPurchase" -> { OutputLog.info("Purchase prompts are not available in Studio playtests"); LuaValue.NONE }
            "UserOwnsGamePassAsync", "PlayerOwnsAsset", "UserHasBadgeAsync" -> LuaValue.FALSE
            "GetProductInfo" -> productInfo(rt, a2.tolong(), a.arg(3).udOrNull<EnumItemV>()?.name ?: "Asset")
            "GetBadgeInfoAsync" -> LuaTable().apply { set("Name", "Product"); set("PriceInRobux", 0); set("Description", "") }
            "AwardBadge" -> { OutputLog.info("Badge award (simulated)"); LuaValue.TRUE }
            // ------------------------------------------------ UserInputService
            "GetMouseLocation" -> LuaTypes.wrap(play?.lastTouchDp ?: studio.lastEditPointer ?: Vector2(0f, 0f))
            "IsKeyDown", "IsMouseButtonPressed" -> LuaValue.FALSE
            "GetFocusedTextBox" -> LuaValue.NIL
            "GetKeysPressed", "GetConnectedGamepads" -> LuaTable()
            "GetLastInputType" -> LuaTypes.wrap(EnumItemV("UserInputType", "Touch", 7))
            // ------------------------------------------------ ContextActionService
            "BindAction", "BindActionAtPriority" -> {
                val fnIdx = 3
                val touch = a.arg(4).toboolean()
                play?.bindAction(a2.checkjstring(), a.arg(fnIdx).checkfunction(), touch)
                LuaValue.NONE
            }
            "UnbindAction" -> { play?.unbindAction(a2.checkjstring()); LuaValue.NONE }
            "UnbindAllActions" -> { play?.unbindAll(); LuaValue.NONE }
            "SetTitle" -> { play?.setActionTitle(a2.checkjstring(), a.arg(3).tojstring()); LuaValue.NONE }
            "SetImage", "SetPosition", "SetDescription" -> LuaValue.NONE
            "GetButton" -> LuaValue.NIL
            // ------------------------------------------------ StarterGui
            "SetCore" -> {
                val what = a2.tojstring()
                if (what == "SendNotification" && a.arg(3).istable()) {
                    val t = a.arg(3)
                    studio.toast("${t.get("Title").optjstring("")}: ${t.get("Text").optjstring("")}")
                } else if (what == "ChatMakeSystemMessage" && a.arg(3).istable()) {
                    val t = a.arg(3)
                    val c = t.get("Color").udOrNull<Color3>()
                    play?.systemMessage(t.get("Text").optjstring(""), c?.toArgb()?.or(0xFF shl 24) ?: 0xFFE6E6E6.toInt())
                } else if (what == "ResetButtonCallback" || what == "TopbarEnabled") { /* accepted */ }
                LuaValue.NONE
            }
            "GetCore" -> LuaValue.NIL
            "SetCoreGuiEnabled" -> {
                val type = a2.udOrNull<EnumItemV>()?.name ?: a2.tojstring()
                val on = a.arg(3).toboolean()
                play?.let { p ->
                    when (type) {
                        "PlayerList" -> p.corePlayerList = on
                        "Chat" -> p.coreChat = on
                        "Backpack" -> p.coreBackpack = on
                        "Health" -> p.coreHealth = on
                        "All" -> { p.corePlayerList = on; p.coreChat = on; p.coreBackpack = on; p.coreHealth = on }
                    }
                }
                LuaValue.NONE
            }
            "GetCoreGuiEnabled" -> {
                val p = play
                LuaValue.valueOf(when (a2.udOrNull<EnumItemV>()?.name ?: a2.tojstring()) {
                    "PlayerList" -> p?.corePlayerList ?: true
                    "Chat" -> p?.coreChat ?: true
                    "Backpack" -> p?.coreBackpack ?: true
                    "Health" -> p?.coreHealth ?: true
                    else -> true
                })
            }
            // ------------------------------------------------ TextChatService channels
            "DisplaySystemMessage" -> { play?.systemMessage(a2.tojstring()); LuaValue.NONE }
            "SendAsync" -> { play?.sendChat(a2.tojstring()); LuaValue.NONE }
            // ------------------------------------------------ Bindables & remotes (single process)
            "Fire" -> { rt.fireArgs(inst, "Event", a.subargs(2)); LuaValue.NONE }
            "Invoke" -> awaitCallback(rt, inst, "OnInvoke").invoke(a.subargs(2))
            "FireServer" -> { rt.fireArgs(inst, "OnServerEvent", LuaValue.varargsOf(rt.wrap(play?.player), a.subargs(2))); LuaValue.NONE }
            "FireClient" -> { rt.fireArgs(inst, "OnClientEvent", a.subargs(3)); LuaValue.NONE }
            "FireAllClients" -> { rt.fireArgs(inst, "OnClientEvent", a.subargs(2)); LuaValue.NONE }
            "InvokeServer" -> awaitCallback(rt, inst, "OnServerInvoke").invoke(LuaValue.varargsOf(rt.wrap(play?.player), a.subargs(2)))
            "InvokeClient" -> awaitCallback(rt, inst, "OnClientInvoke").invoke(a.subargs(3))
            // ------------------------------------------------ Sound
            "Play" -> {
                if (inst["Playing"] == true) inst.attributes["__playSeq"] = ((inst.attributes["__playSeq"] as? Int) ?: 0) + 1
                inst.set("Playing", true)
                rt.fire(inst, "Played", LuaValue.valueOf(inst["SoundId"]?.toString() ?: "")); LuaValue.NONE
            }
            "Stop" -> { inst.set("TimePosition", 0.0, false); inst.set("Playing", false); rt.fire(inst, "Stopped", LuaValue.valueOf(inst["SoundId"]?.toString() ?: "")); LuaValue.NONE }
            "Pause" -> { inst.set("Playing", false); rt.fire(inst, "Paused"); LuaValue.NONE }
            "Resume" -> { inst.set("Playing", true); rt.fire(inst, "Resumed"); LuaValue.NONE }
            "PlayLocalSound" -> {
                // Plays a copy in SoundService that cleans itself up when it ends.
                val src = a2.udOrNull<Instance>()
                if (src != null && rt.isPlay) {
                    val c = src.cloneTree()
                    c.set("Looped", false, false)
                    c.attributes["__oneShot"] = true
                    c.setParent(rt.dm.getService("SoundService"))
                    c.set("Playing", true)
                }
                LuaValue.NONE
            }
            // ------------------------------------------------ GUI
            "TweenPosition", "TweenSize", "TweenSizeAndPosition" -> {
                val goals = LinkedHashMap<String, Any?>()
                var idx = 2
                if (name == "TweenSizeAndPosition") { goals["Size"] = a.arg(2).ud<UDim2>("UDim2"); goals["Position"] = a.arg(3).ud<UDim2>("UDim2"); idx = 3 }
                else goals[if (name == "TweenPosition") "Position" else "Size"] = a2.ud<UDim2>("UDim2")
                val dir = a.arg(idx + 1).udOrNull<EnumItemV>()?.value ?: 1
                val style = a.arg(idx + 2).udOrNull<EnumItemV>()?.value ?: 3
                val time = a.arg(idx + 3).optNum(1f)
                val cb = a.arg(idx + 5)
                val tw = Tween(inst, TweenInfoV(time, style, dir, 0, false, 0f), goals)
                if (cb.isfunction()) rt.connect(tw, "Completed", cb, true)
                rt.playTween(tw)
                LuaValue.TRUE
            }
            "CaptureFocus", "ReleaseFocus" -> LuaValue.NONE
            "IsFocused" -> LuaValue.FALSE
            "GetTextSize" -> { val s = a.arg(3).num(); LuaTypes.wrap(Vector2(a2.tojstring().length * s * 0.5f, s)) }
            "FilterStringAsync", "FilterStringForBroadcast" -> {
                val text = a2.tojstring()
                val t = LuaTable()
                for (f in listOf("GetNonChatStringForBroadcastAsync", "GetNonChatStringForUserAsync", "GetChatForUserAsync")) t.set(f, fnv { LuaValue.valueOf(text) })
                if (name == "FilterStringForBroadcast") LuaValue.valueOf(text) else t
            }
            "SetTopbarTransparency" -> LuaValue.NONE
            "GetGuiObjectsAtPosition" -> LuaTable()
            "GetGuiInset" -> LuaValue.varargsOf(LuaTypes.wrap(Vector2(0f, 0f)), LuaTypes.wrap(Vector2(0f, 0f)))
            "IsTenFootInterface" -> LuaValue.FALSE
            // ------------------------------------------------ Camera
            "WorldToScreenPoint", "WorldToViewportPoint" -> {
                val p = a2.ud<Vector3>("Vector3")
                val s = studio.view.project(p)
                val d = studio.guiUnit()
                if (s == null) LuaValue.varargsOf(LuaTypes.wrap(Vector3(0f, 0f, -1f)), LuaValue.FALSE)
                else {
                    val depth = studio.view.distTo(p)
                    val on = s[0] >= 0 && s[1] >= 0 && s[0] <= studio.view.width && s[1] <= studio.view.height
                    LuaValue.varargsOf(LuaTypes.wrap(Vector3(s[0] / d, s[1] / d, depth)), LuaValue.valueOf(on))
                }
            }
            "ScreenPointToRay", "ViewportPointToRay" -> {
                val d = studio.guiUnit()
                val (o, dir) = studio.view.screenRay(a2.num() * d, a.arg(3).num() * d)
                LuaTypes.wrap(Ray(o, dir * a.arg(4).optNum(1f)))
            }
            "GetPartsObscuringTarget" -> LuaTable()
            // ------------------------------------------------ Lighting
            "GetMinutesAfterMidnight" -> LuaValue.valueOf((((inst["ClockTime"] as? Number)?.toDouble()) ?: 14.0) * 60.0)
            "SetMinutesAfterMidnight" -> { inst.set("ClockTime", (a2.num() / 60f) % 24f); LuaValue.NONE }
            "GetSunDirection", "GetMoonDirection" -> LuaTypes.wrap(Vector3(0.4f, 0.8f, 0.3f).unit)
            // ------------------------------------------------ Pathfinding
            "CreatePath" -> pathObject(rt)
            "FindPathAsync" -> pathObject(rt).also { p -> p.get("ComputeAsync").invoke(LuaValue.varargsOf(arrayOf(p, a2, a.arg(3)))) }
            // ------------------------------------------------ misc stubs
            "Teleport", "TeleportAsync", "TeleportToPlaceInstance" -> { OutputLog.warn("Teleporting is not supported in Studio"); LuaValue.NONE }
            "LoadAsset" -> throw LuaError("InsertService:LoadAsset is not available offline")
            "GetTeams" -> LuaTable().also { t -> inst.children.filter { it.className == "Team" }.forEach { t.insert(0, rt.wrap(it)) } }
            "GetPlayers" -> LuaTable()
            "RegisterCollisionGroup", "CollisionGroupSetCollidable", "SetPartCollisionGroup", "CreateCollisionGroup" -> LuaValue.NONE
            "CollisionGroupsAreCollidable" -> LuaValue.TRUE
            "GetRegisteredCollisionGroups" -> LuaTable()
            "Activate", "Deactivate" -> if (inst.className == "Plugin") LuaValue.NONE else { rt.fire(inst, if (name == "Activate") "Activated" else "Deactivated"); LuaValue.NONE }
            "InputHoldBegin", "InputHoldEnd", "Emit", "Clear" -> LuaValue.NONE
            "PublishAsync" -> LuaValue.NONE
            "SubscribeAsync" -> LuaTable().apply { set("Disconnect", fnv { LuaValue.NONE }) }
            "Chat" -> {
                // Chat:Chat(partOrCharacter, message) shows a bubble.
                val target = a2.udOrNull<Instance>()
                val part = when {
                    target == null -> null
                    target.isA("BasePart") -> target
                    else -> target.findFirstChild("Head") ?: target.findFirstChildWhichIsA("BasePart", true)
                }
                if (part != null) play?.bubble(part, a.arg(3).tojstring())
                LuaValue.NONE
            }
            "GetTranslatorForPlayerAsync" -> LuaTable().apply { set("Translate", fnv { a2x -> a2x.arg(3) }); set("FormatByKey", fnv { a2x -> a2x.arg(2) }) }
            "GetCountryRegionForPlayerAsync" -> LuaValue.valueOf("US")
            "GetPolicyInfoForPlayerAsync" -> LuaTable().apply { set("AreAdsAllowed", LuaValue.FALSE); set("ArePaidRandomItemsRestricted", LuaValue.FALSE) }
            "GetUserId", "GetStudioUserId" -> LuaValue.valueOf(0)
            // RbxAnalyticsService: nothing is ever reported anywhere (plugins' telemetry calls just succeed).
            "GetSessionId", "GetClientId", "GetPlaySessionId" -> LuaValue.valueOf("00000000-0000-0000-0000-000000000000")
            "ReportStats", "SendEventDeferred", "SendEventImmediately", "ReportCounter", "ReportInfluxSeries", "TrackEvent", "FireLogEvent", "SetRBXEvent" -> LuaValue.NONE
            // ------------------------------------------------ Plugins
            "CreateToolbar", "GetSetting", "SetSetting", "CreateDockWidgetPluginGui", "OpenScript", "GetMouse", "IsActivated",
            "CreatePluginAction", "CreatePluginMenu", "CreateButton", "SetActive", "SelectRibbonTool", "GetSelectedRibbonTool", "PromptImportFile" ->
                PluginApi.call(rt, inst, name, a)
            else -> LuaValue.NONE
        }
    }

    /**
     * MarketplaceService:GetProductInfo for assets: the public asset details from Roblox (name,
     * description, creator, price...). It yields like the real call; outside a yieldable thread, or
     * for other info types, it answers with a placeholder.
     */
    private fun productInfo(rt: LuaRuntime, id: Long, infoType: String): LuaValue {
        fun placeholder() = LuaTable().apply {
            set("Name", "Product"); set("Description", ""); set("PriceInRobux", 0); set("AssetId", id.toDouble()); set("IsForSale", LuaValue.FALSE)
        }
        val co = rt.currentCoroutine() ?: return placeholder()
        if (infoType != "Asset" || id <= 0) return placeholder()
        Thread {
            val json = try { String(com.leaf.lemonade.net.Http.get("https://economy.roblox.com/v2/assets/$id/details")) } catch (_: Exception) { null }
            rt.mainQueue.add {
                val v = json?.let { try { Json.decode(rt, it) } catch (_: Exception) { null } }
                rt.resume(co, if (v != null && v.istable()) v else placeholder())
            }
        }.start()
        return rt.globals.yield(LuaValue.NONE).arg1()
    }

    /**
     * Like Roblox, invoking a function whose callback isn't set yet waits for it (the other
     * side's script may still be starting) instead of failing straight away.
     */
    private fun awaitCallback(rt: LuaRuntime, inst: Instance, name: String): LuaValue {
        rt.callbacks[inst]?.get(name)?.let { return it }
        if (rt.currentCoroutine() == null) throw LuaError("$name callback is not set")
        var waited = 0.0
        while (waited < 30.0) {
            rt.yieldFor(0.05)
            waited += 0.05
            rt.callbacks[inst]?.get(name)?.let { return it }
            if (inst.destroyed) break
            if (waited > 5.0 && waited < 5.06) OutputLog.warn("Waiting for ${inst.fullName()}.$name to be set…")
        }
        throw LuaError("$name callback is not set")
    }

    private fun tweenValue(rt: LuaRuntime, tw: Tween): LuaValue {
        val t = LuaTable()
        t.set("Play", fnv { rt.playTween(tw); LuaValue.NONE })
        t.set("Pause", fnv { if (tw.state == 2) tw.state = 3; LuaValue.NONE })
        t.set("Cancel", fnv { tw.state = 5; rt.tweens.remove(tw); LuaValue.NONE })
        t.set("Destroy", fnv { tw.state = 5; rt.tweens.remove(tw); LuaValue.NONE })
        t.set("Instance", rt.wrap(tw.inst))
        t.set("TweenInfo", LuaTypes.wrap(tw.info))
        val mt = LuaTable()
        mt.set("__type", "Tween")
        mt.set(LuaValue.INDEX, fn2 { _, k ->
            when (k.tojstring()) {
                "Completed" -> rt.api.signal(tw, "Completed")
                "PlaybackState" -> {
                    val names = listOf("Begin", "Delayed", "Playing", "Paused", "Completed", "Cancelled")
                    LuaTypes.wrap(EnumItemV("PlaybackState", names[tw.state], tw.state))
                }
                else -> LuaValue.NIL
            }
        })
        t.setmetatable(mt)
        return t
    }

    private fun pathObject(rt: LuaRuntime): LuaTable {
        val p = LuaTable()
        var pts = listOf<Vector3>()
        p.set("Status", LuaTypes.wrap(EnumItemV("PathStatus", "Success", 0)))
        p.set("ComputeAsync", fnv { a -> pts = listOf(a.arg(2).ud("Vector3"), a.arg(3).ud("Vector3")); LuaValue.NONE })
        p.set("GetWaypoints", fnv { _ ->
            val t = LuaTable()
            for (v in pts) {
                val w = LuaTable()
                w.set("Position", LuaTypes.wrap(v))
                w.set("Action", LuaTypes.wrap(EnumItemV("PathWaypointAction", "Walk", 0)))
                t.insert(0, w)
            }
            t
        })
        p.set("Destroy", fnv { LuaValue.NONE })
        val mt = LuaTable()
        mt.set(LuaValue.INDEX, fn2 { _, k -> if (k.tojstring() == "Blocked" || k.tojstring() == "Unblocked") rt.api.signal(p, k.tojstring()) else LuaValue.NIL })
        p.setmetatable(mt)
        return p
    }

    private fun dataStore(rt: LuaRuntime, name: String): LuaTable {
        val store = rt.studio.dataStores.getOrPut(name) { HashMap() }
        val t = LuaTable()
        fun get(k: String): LuaValue = store[k]?.let { Json.decode(rt, it) } ?: LuaValue.NIL
        t.set("GetAsync", fnv { a -> get(a.arg(2).tojstring()) })
        t.set("SetAsync", fnv { a -> store[a.arg(2).tojstring()] = Json.encode(a.arg(3)); LuaValue.NONE })
        t.set("RemoveAsync", fnv { a -> val k = a.arg(2).tojstring(); val old = get(k); store.remove(k); old })
        t.set("IncrementAsync", fnv { a ->
            val k = a.arg(2).tojstring()
            val nv = (get(k).optdouble(0.0)) + a.arg(3).optdouble(1.0)
            store[k] = Json.encode(LuaValue.valueOf(nv)); LuaValue.valueOf(nv)
        })
        t.set("UpdateAsync", fnv { a ->
            val k = a.arg(2).tojstring()
            val r = a.arg(3).call(get(k))
            if (!r.isnil()) store[k] = Json.encode(r)
            r
        })
        t.set("GetSortedAsync", fnv { _ ->
            val page = LuaTable()
            val items = LuaTable()
            store.entries.forEach { (k, v) -> val e = LuaTable(); e.set("key", k); e.set("value", Json.decode(rt, v)); items.insert(0, e) }
            page.set("GetCurrentPage", fnv { items })
            page.set("AdvanceToNextPageAsync", fnv { LuaValue.NONE })
            page.set("IsFinished", LuaValue.TRUE)
            page
        })
        return t
    }

    /**
     * KeyframeSequenceProvider, used by animation plugins: temporary ids for KeyframeSequences
     * being edited (an Animation with that id plays them), and downloading an animation's
     * KeyframeSequence to edit it.
     */
    private fun keyframeProvider(rt: LuaRuntime, name: String, a: Varargs): Varargs = when (name) {
        "RegisterKeyframeSequence", "RegisterActiveKeyframeSequence" -> {
            val seq = a.arg(2).udOrNull<Instance>() ?: throw LuaError("Argument 1 missing or nil")
            if (seq.className != "KeyframeSequence") throw LuaError("Unable to cast Instance to KeyframeSequence")
            LuaValue.valueOf(com.leaf.lemonade.engine.EditAnimation.registerTemp(seq, name == "RegisterActiveKeyframeSequence"))
        }
        "GetKeyframeSequenceAsync", "GetKeyframeSequence", "GetKeyframeSequenceById" -> {
            val raw = a.arg(2).tojstring()
            val id = com.leaf.lemonade.net.AssetFetcher.idOf(raw) ?: raw.toLongOrNull() ?: throw LuaError("Invalid animation id: $raw")
            val co: LuaThread = rt.currentCoroutine() ?: throw LuaError("GetKeyframeSequenceAsync must be called from a yieldable thread")
            Thread {
                var seq: Instance? = null; var err: String? = null
                try {
                    val doc = com.leaf.lemonade.format.RbxFile.read(com.leaf.lemonade.net.AssetFetcher.load(id))
                    seq = doc.roots.flatMap { listOf(it) + it.descendants() }.firstOrNull { it.className == "KeyframeSequence" }
                    if (seq == null) err = "Asset $id is not a KeyframeSequence (CurveAnimations can't be edited as keyframes)"
                    seq?.setParent(null)
                } catch (e: Exception) {
                    err = com.leaf.lemonade.net.AssetFetcher.lastError(id) ?: (e.message ?: e.toString())
                }
                rt.mainQueue.add {
                    rt.resume(co, if (seq != null) LuaValue.varargsOf(LuaValue.TRUE, rt.wrap(seq)) else LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf("Failed to load animation $id: $err")))
                }
            }.start()
            val r = rt.globals.yield(LuaValue.NONE)
            if (!r.arg1().toboolean()) throw LuaError(r.arg(2).tojstring())
            r.arg(2)
        }
        "GetAnimations" -> {
            // The user's animation inventory: an empty, finished page set.
            val pages = LuaTable()
            pages.set("IsFinished", LuaValue.TRUE)
            pages.set("GetCurrentPage", object : org.luaj.vm2.lib.VarArgFunction() { override fun invoke(args: Varargs): Varargs = LuaTable() })
            pages.set("AdvanceToNextPageAsync", object : org.luaj.vm2.lib.VarArgFunction() { override fun invoke(args: Varargs): Varargs = LuaValue.NONE })
            pages
        }
        "GetMemStats" -> LuaTable()
        else -> LuaValue.NONE
    }

    private fun http(rt: LuaRuntime, svc: Instance, name: String, a: Varargs): Varargs {
        if (svc["HttpEnabled"] != true) throw LuaError("Http requests are not enabled. Enable via game settings (set HttpService.HttpEnabled = true)")
        val co: LuaThread = rt.currentCoroutine() ?: throw LuaError("HTTP requests must be made from a yieldable thread")
        val req = a.arg(2)
        val url: String; val method: String; val body: String?; val headers = HashMap<String, String>()
        if (name == "RequestAsync") {
            url = req.get("Url").checkjstring()
            method = req.get("Method").optjstring("GET")
            body = if (req.get("Body").isnil()) null else req.get("Body").tojstring()
            val h = req.get("Headers")
            if (h.istable()) { var k = LuaValue.NIL; while (true) { val n = h.next(k); k = n.arg1(); if (k.isnil()) break; headers[k.tojstring()] = n.arg(2).tojstring() } }
        } else {
            url = req.checkjstring()
            method = if (name == "PostAsync") "POST" else "GET"
            body = if (name == "PostAsync") a.arg(3).tojstring() else null
            if (name == "PostAsync") headers["Content-Type"] = "application/json"
        }
        val who = if (rt.play != null) "A script in this game" else "A plugin"
        Thread {
            var code = 0; var text = ""; var err: String? = null
            try {
                // Scripts and plugins are other people's code. Nothing of the user's sign-in is
                // ever attached (no cookies, no keys); Roblox's own sites are off limits (as in
                // Roblox); and each new website needs the user's OK (as in Studio). Redirects are
                // followed by hand so they go through the same checks.
                var target = url
                var hops = 0
                var m = method; var b = body
                while (true) {
                    val u = URL(target)
                    val scheme = u.protocol.lowercase()
                    if (scheme != "https" && scheme != "http") throw java.io.IOException("Only http and https URLs are supported")
                    val host = u.host?.lowercase().orEmpty()
                    if (host.isEmpty()) throw java.io.IOException("Invalid URL")
                    if (com.leaf.lemonade.net.WebPermissions.isRobloxHost(host)) throw java.io.IOException("Http requests to Roblox domains are not allowed")
                    if (!com.leaf.lemonade.net.WebPermissions.allow(rt.studio.app, host, who)) throw java.io.IOException("Access to $host was not allowed (Studio Settings → Privacy)")
                    val c = u.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.useCaches = false
                    c.requestMethod = m
                    c.connectTimeout = 15000; c.readTimeout = 20000
                    headers.forEach { (k, v) -> if (!k.equals("Cookie", true)) c.setRequestProperty(k, v) }
                    if (b != null) { c.doOutput = true; c.outputStream.use { it.write(b.toByteArray()) } }
                    code = c.responseCode
                    val loc = c.getHeaderField("Location")
                    if (code in 300..399 && loc != null && hops++ < 5) {
                        c.disconnect()
                        target = URL(u, loc).toString()
                        if (code == 303 || ((code == 301 || code == 302) && m == "POST")) { m = "GET"; b = null }
                        continue
                    }
                    text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: ""
                    c.disconnect()
                    break
                }
            } catch (e: Exception) { err = e.message ?: e.toString() }
            rt.mainQueue.add {
                val result: Varargs = if (err != null) LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf("HttpError: $err"))
                else if (name == "RequestAsync") {
                    val r = LuaTable()
                    r.set("Success", LuaValue.valueOf(code in 200..299)); r.set("StatusCode", code); r.set("StatusMessage", "")
                    r.set("Body", text); r.set("Headers", LuaTable())
                    LuaValue.varargsOf(LuaValue.TRUE, r)
                } else if (code !in 200..299) LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf("HTTP $code"))
                else LuaValue.varargsOf(LuaValue.TRUE, LuaValue.valueOf(text))
                rt.resume(co, result)
            }
        }.start()
        val r = rt.globals.yield(LuaValue.NONE)
        if (!r.arg1().toboolean()) throw LuaError(r.arg(2).tojstring())
        return r.arg(2)
    }
}

object Json {
    fun encode(v: LuaValue): String = toJson(v, 0).toString()

    private fun toJson(v: LuaValue, depth: Int): Any? {
        if (depth > 100) throw LuaError("Cannot convert recursive table to JSON")
        return when {
            v.isnil() -> JSONObject.NULL
            v.isboolean() -> v.toboolean()
            v.isnumber() -> { val d = v.todouble(); if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong() else d }
            v.isstring() -> v.tojstring()
            v.istable() -> {
                val n = v.length()
                var count = 0
                var k = LuaValue.NIL
                while (true) { val nx = v.next(k); k = nx.arg1(); if (k.isnil()) break; count++ }
                if (n > 0 && n == count) JSONArray().also { arr -> for (i in 1..n) arr.put(toJson(v.get(i), depth + 1)) }
                else if (count == 0) JSONArray()
                else JSONObject().also { o ->
                    k = LuaValue.NIL
                    while (true) { val nx = v.next(k); k = nx.arg1(); if (k.isnil()) break; o.put(k.tojstring(), toJson(nx.arg(2), depth + 1)) }
                }
            }
            else -> JSONObject.NULL
        }
    }

    fun decode(rt: LuaRuntime, s: String): LuaValue {
        val t = s.trim()
        val parsed: Any = when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            t == "null" -> return LuaValue.NIL
            t == "true" -> return LuaValue.TRUE
            t == "false" -> return LuaValue.FALSE
            t.startsWith("\"") -> return LuaValue.valueOf(JSONArray("[$t]").getString(0))
            else -> return t.toDoubleOrNull()?.let { LuaValue.valueOf(it) } ?: throw LuaError("Can't parse JSON")
        }
        return fromJson(parsed)
    }

    private fun fromJson(o: Any?): LuaValue = when (o) {
        null, JSONObject.NULL -> LuaValue.NIL
        is Boolean -> LuaValue.valueOf(o)
        is Int -> LuaValue.valueOf(o)
        is Long -> LuaValue.valueOf(o.toDouble())
        is Number -> LuaValue.valueOf(o.toDouble())
        is String -> LuaValue.valueOf(o)
        is JSONArray -> LuaTable().also { t -> for (i in 0 until o.length()) t.set(i + 1, fromJson(o.opt(i))) }
        is JSONObject -> LuaTable().also { t -> o.keys().forEach { k -> t.set(k, fromJson(o.opt(k))) } }
        else -> LuaValue.valueOf(o.toString())
    }
}
