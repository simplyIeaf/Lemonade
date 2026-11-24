package com.leaf.lemonade.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.engine.Avatar
import com.leaf.lemonade.engine.Geometry
import com.leaf.lemonade.engine.PlaySession
import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.EnumValue
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.NumberRange
import com.leaf.lemonade.model.PartData
import com.leaf.lemonade.model.Vector3
import com.leaf.lemonade.model.fmtNum
import com.leaf.lemonade.net.CloudPlace
import com.leaf.lemonade.net.RobloxApi
import com.leaf.lemonade.net.RobloxCloud
import java.util.concurrent.Executors
import kotlin.math.atan2

/**
 * Avatar features that mirror Roblox Studio: the playtest avatar (who you play as, including any
 * saved model), Game Settings → Avatar (R6 / R15 / Player Choice, collision, body scale limits,
 * stored in the place and synced with the game on Roblox), and the Rig Builder.
 */
object AvatarUi {
    private val net = Executors.newSingleThreadExecutor()

    private fun toast(act: Activity, s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
    private fun dp(act: Activity, v: Float) = Ui.dp(act, v)

    /** A tappable option row: icon, title, detail, and a check when it's the current choice. */
    private fun optionRow(act: Activity, icon: String, title: String, sub: String, current: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 10f), dp(act, 9f), dp(act, 12f), dp(act, 9f))
            background = Ui.rippleBg(act, if (current) Ui.blend(Ui.PANEL, Ui.ACCENT, if (Ui.LIGHT) 0.14f else 0.28f) else Ui.CARD, 10f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(act).apply { setImageDrawable(Icons.general(act, icon)) }, Ui.lp(dp(act, 28f), dp(act, 28f)).apply { rightMargin = dp(act, 12f) })
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Ui.text(act, title, 14f, Ui.TEXT, true))
        t.addView(Ui.text(act, sub, 11.5f, Ui.DIM).apply { isSingleLine = false })
        row.addView(t, Ui.lp(0, Ui.WRAP, 1f))
        if (current) row.addView(Ui.text(act, "✓", 17f, if (Ui.LIGHT) Ui.ACCENT else Ui.blend(Ui.ACCENT, 0xFFFFFFFF.toInt(), 0.4f), true))
        return row
    }

    // ------------------------------------------------------------------ playtest avatar

    /** File → Playtest Avatar: who the playtest character is. */
    fun playtestAvatar(act: Activity, studio: Studio?) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(act, 16f), dp(act, 8f), dp(act, 16f), dp(act, 4f)) }
        var dlg: AlertDialog? = null
        val mode = Avatar.mode(act)
        fun add(icon: String, title: String, sub: String, current: Boolean, fn: () -> Unit) =
            box.addView(optionRow(act, icon, title, sub, current) { dlg?.dismiss(); fn() }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(act, 6f) })
        val guest = RobloxApi.isGuest(act)
        add("Account", "My Roblox avatar", if (guest) "Sign in first to wear your avatar" else "@${RobloxApi.userName(act)}, as it looks on Roblox", mode == Avatar.Mode.ACCOUNT) {
            if (guest) AccountUi.signIn(act) { Avatar.setMode(act, Avatar.Mode.ACCOUNT) } else { Avatar.setMode(act, Avatar.Mode.ACCOUNT); toast(act, "Using your avatar") }
        }
        val other = Avatar.otherUser(act).second
        add("Shared", "Another user's avatar…", if (other.isNotEmpty()) "Now: @$other" else "Wear any Roblox user's avatar", mode == Avatar.Mode.USER) {
            Dialogs.prompt(act, "Roblox username", other) { name ->
                AccountUi.background(act, "Looking up @$name…", { RobloxCloud.userByName(name) ?: throw Exception("No Roblox user named \"$name\".") }) { u ->
                    Avatar.setMode(act, Avatar.Mode.USER, u.id, u.name); toast(act, "Wearing @${u.name}'s avatar")
                }
            }
        }
        add("Color", "Create my own…", "Body colors, clothing, face and accessories", mode == Avatar.Mode.CUSTOM) { AccountUi.customAvatar(act) }
        add("Part_Block", "Classic", "The yellow, blue and green blocky avatar", mode == Avatar.Mode.CLASSIC) { Avatar.setMode(act, Avatar.Mode.CLASSIC); toast(act, "Using the classic avatar") }
        val charName = Avatar.characterName(act)
        add("Model", "Custom character (model or part)…",
            if (Avatar.hasCharacter(act)) "“${charName.ifEmpty { "Saved model" }}” — you spawn as it in every place" else "Play as any model or part, in every place you edit",
            mode == Avatar.Mode.CHARACTER) { characterChooser(act, studio) }
        box.addView(Ui.text(act, "Whether your avatar is R6 or R15 in a playtest follows each place's Game Settings → Avatar, like on Roblox.", 11.5f, Ui.DIM).apply {
            isSingleLine = false; setPadding(dp(act, 2f), dp(act, 6f), 0, dp(act, 4f))
        })
        val b = Dialogs.themed(act).setTitle("Playtest Avatar").setView(ScrollView(act).apply { addView(box) }).setNegativeButton("Close", null)
        if (studio != null) b.setNeutralButton("Game Settings…") { _, _ -> gameSettings(act, studio) }
        dlg = b.show()
    }

    /** Picks the model or part to play as; it's saved, so it's used in every place until changed. */
    fun characterChooser(act: Activity, studio: Studio?) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val selected = studio?.let { s -> synchronized(s.lock) { s.selection.firstOrNull { (it.isA("Model") || it.isA("BasePart")) && !it.isService } } }
        if (studio != null && selected != null) items.add("Use the selection (${selected.name})" to { saveCharacter(act, studio, selected) })
        if (studio != null) items.add("Pick from Workspace…" to { pickFromWorkspace(act, studio) })
        if (Avatar.hasCharacter(act)) {
            if (Avatar.mode(act) != Avatar.Mode.CHARACTER) items.add("Use “${Avatar.characterName(act).ifEmpty { "saved model" }}” again" to {
                Avatar.setMode(act, Avatar.Mode.CHARACTER); toast(act, "Playing as ${Avatar.characterName(act)}")
            })
            items.add("Stop using “${Avatar.characterName(act).ifEmpty { "saved model" }}”" to { Avatar.clearCharacter(act); toast(act, "Back to your avatar") })
        }
        if (items.isEmpty()) { toast(act, "Open a place and select a model or part first"); return }
        Dialogs.themed(act).setTitle("Custom character")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickFromWorkspace(act: Activity, studio: Studio) {
        val list = synchronized(studio.lock) {
            val ws = studio.model.workspace
            val out = ArrayList<Instance>()
            fun visit(parent: Instance, depth: Int) {
                for (c in parent.children) {
                    if (out.size >= 200) return
                    when {
                        c.className == "Terrain" || c.className == "Camera" -> {}
                        c.isA("Model") || c.isA("BasePart") -> out.add(c)
                        c.className == "Folder" && depth < 2 -> visit(c, depth + 1)
                    }
                }
            }
            visit(ws, 0)
            out
        }
        if (list.isEmpty()) { toast(act, "Workspace has no models or parts"); return }
        Dialogs.themed(act).setTitle("Play as…").setItems(list.map { (if (it.isA("BasePart")) "▫ " else "▣ ") + it.name }.toTypedArray()) { _, i ->
            saveCharacter(act, studio, list[i])
        }.setNegativeButton("Cancel", null).show()
    }

    private fun saveCharacter(act: Activity, studio: Studio, inst: Instance) {
        val hasParts = synchronized(studio.lock) { inst.isA("BasePart") || inst.descendants().any { it.isA("BasePart") } }
        if (!hasParts) { toast(act, "${inst.name} has no parts to play as"); return }
        val bytes = studio.serializeInstances(listOf(inst)) ?: run { toast(act, "Couldn't save ${inst.name}"); return }
        Avatar.setCharacter(act, bytes, inst.name)
        OutputLog.info("Playtest avatar: you'll spawn as ${inst.name} in every place (File → Playtest Avatar to change)")
        toast(act, "You'll play as ${inst.name} in every place")
    }

    // ------------------------------------------------------------------ Game Settings → Avatar

    private val scaleKeys = listOf("Height" to "height", "Width" to "width", "Head" to "head", "BodyType" to "bodyType", "Proportion" to "proportion")
    private val scaleLabels = mapOf("Height" to "Height", "Width" to "Width", "Head" to "Head", "BodyType" to "Body type", "Proportion" to "Proportions")
    private val scaleDefaults = mapOf("Height" to NumberRange(0.9f, 1.05f), "Width" to NumberRange(0.7f, 1f), "Head" to NumberRange(0.95f, 1f),
        "BodyType" to NumberRange(0f, 1f), "Proportion" to NumberRange(0f, 1f))

    /** Segmented buttons (like Studio's radio groups); returns a getter for the chosen index. */
    private fun segmented(act: Activity, parent: LinearLayout, options: List<String>, initial: Int, onChange: (Int) -> Unit = {}): () -> Int {
        var sel = initial
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<android.widget.TextView>()
        fun paint() = views.forEachIndexed { i, v ->
            val on = i == sel
            v.setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.TEXT)
            v.background = Ui.rippleBg(act, if (on) Ui.ACCENT else Ui.CARD, 16f)
        }
        options.forEachIndexed { i, label ->
            val v = Ui.text(act, label, 13f, Ui.TEXT, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(act, 12f), dp(act, 8f), dp(act, 12f), dp(act, 8f))
                setOnClickListener { sel = i; paint(); onChange(i) }
            }
            views.add(v)
            row.addView(v, Ui.lp(0, Ui.WRAP, 1f).apply { rightMargin = if (i < options.size - 1) dp(act, 6f) else 0 })
        }
        paint()
        parent.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        return { sel }
    }

    /** Studio's Game Settings → Avatar for the open place (saved in StarterPlayer, and to Roblox for Roblox places). */
    fun gameSettings(act: Activity, studio: Studio) {
        if (studio.isPlaying) { toast(act, "Stop the playtest to change Game Settings"); return }
        val sp = synchronized(studio.lock) { studio.model.getService("StarterPlayer") }
        val cur = synchronized(studio.lock) { (sp["GameSettingsAvatar"] as? EnumValue)?.value ?: 2 }
        val curCollision = synchronized(studio.lock) { (sp["GameSettingsR15Collision"] as? EnumValue)?.value ?: 0 }
        val curLoad = synchronized(studio.lock) { sp["LoadCharacterAppearance"] != false }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(act, 20f), dp(act, 8f), dp(act, 20f), dp(act, 4f)) }
        fun section(t: String, sub: String? = null) {
            box.addView(Ui.text(act, t, 14f, Ui.TEXT, true).apply { setPadding(0, dp(act, 14f), 0, dp(act, if (sub != null) 2f else 8f)) })
            if (sub != null) box.addView(Ui.text(act, sub, 11.5f, Ui.DIM).apply { isSingleLine = false; setPadding(0, 0, 0, dp(act, 8f)) })
        }
        section("Avatar Type", "Which body players get in this place. Player Choice uses each player's own setting.")
        val collisionBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val typeOf = segmented(act, box, listOf("R6", "R15", "Player Choice"), cur) { collisionBox.visibility = if (it == 0) View.GONE else View.VISIBLE }
        box.addView(collisionBox)
        collisionBox.addView(Ui.text(act, "Collision", 14f, Ui.TEXT, true).apply { setPadding(0, dp(act, 14f), 0, dp(act, 8f)) })
        val collisionOf = segmented(act, collisionBox, listOf("Outer Box", "Inner Box"), curCollision)
        collisionBox.visibility = if (cur == 0) View.GONE else View.VISIBLE

        section("Player appearance")
        val loadRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        loadRow.addView(Ui.text(act, "Load each player's avatar (clothing, accessories, body)", 13f).apply { isSingleLine = false }, Ui.lp(0, Ui.WRAP, 1f))
        val loadSwitch = android.widget.Switch(act).apply { isChecked = curLoad }
        loadRow.addView(loadSwitch)
        box.addView(loadRow)

        section("Body scales (R15)", "Players' body scales are kept between these values.")
        val fields = HashMap<String, Pair<android.widget.EditText, android.widget.EditText>>()
        for ((key, _) in scaleKeys) {
            val r = synchronized(studio.lock) { sp["GameSettingsScaleRange$key"] as? NumberRange } ?: scaleDefaults[key]!!
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(act, 2f), 0, dp(act, 2f)) }
            row.addView(Ui.text(act, scaleLabels[key]!!, 13f), Ui.lp(0, Ui.WRAP, 1f))
            val lo = Ui.input(act, fmtNum(r.min), "min", numeric = true)
            val hi = Ui.input(act, fmtNum(r.max), "max", numeric = true)
            row.addView(lo, Ui.lp(dp(act, 72f), Ui.WRAP))
            row.addView(Ui.text(act, "–", 13f, Ui.DIM).apply { setPadding(dp(act, 6f), 0, dp(act, 6f), 0) })
            row.addView(hi, Ui.lp(dp(act, 72f), Ui.WRAP))
            box.addView(row)
            fields[key] = lo to hi
        }

        val cloud = studio.cloud
        val pushBox = android.widget.CheckBox(act).apply {
            text = "Also save to “${cloud?.name}” on Roblox"; setTextColor(Ui.TEXT); isChecked = true
            visibility = if (cloud != null && !RobloxApi.isGuest(act)) View.VISIBLE else View.GONE
        }
        box.addView(pushBox, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(act, 12f) })
        box.addView(Ui.text(act, "Saved in the place (StarterPlayer), so playtests here use it." +
            if (cloud != null) " Roblox games keep these settings on the game itself, so they're synced when you open it." else "", 11.5f, Ui.DIM).apply {
            isSingleLine = false; setPadding(0, dp(act, 6f), 0, dp(act, 4f))
        })

        Dialogs.themed(act).setTitle("Game Settings — Avatar").setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val type = typeOf()
                val collision = collisionOf()
                val ranges = LinkedHashMap<String, NumberRange>()
                for ((key, _) in scaleKeys) {
                    val (lo, hi) = fields[key]!!
                    val d = scaleDefaults[key]!!
                    val a = lo.text.toString().replace(',', '.').toFloatOrNull() ?: d.min
                    val b = hi.text.toString().replace(',', '.').toFloatOrNull() ?: d.max
                    ranges[key] = NumberRange(minOf(a, b).coerceIn(0f, 5f), maxOf(a, b).coerceIn(0f, 5f))
                }
                synchronized(studio.lock) {
                    studio.undo.begin("Game Settings: Avatar")
                    studio.setProp(sp, "GameSettingsAvatar", EnumValue(type))
                    studio.setProp(sp, "GameSettingsR15Collision", EnumValue(collision))
                    studio.setProp(sp, "LoadCharacterAppearance", loadSwitch.isChecked)
                    for ((k, r) in ranges) studio.setProp(sp, "GameSettingsScaleRange$k", r)
                    studio.undo.commit()
                }
                studio.markDirty()
                val label = listOf("R6", "R15", "Player Choice")[type]
                OutputLog.info("Game Settings: avatar type $label")
                if (cloud != null && pushBox.isChecked && pushBox.visibility == View.VISIBLE) {
                    val s = RobloxCloud.AvatarSettings(type, collision, ranges.mapKeys { k -> scaleKeys.first { it.first == k.key }.second }.mapValues { it.value.min },
                        ranges.mapKeys { k -> scaleKeys.first { it.first == k.key }.second }.mapValues { it.value.max })
                    AccountUi.background(act, "Saving to Roblox…", { RobloxCloud.setAvatarSettings(act, cloud.universeId, s) }, {
                        toast(act, "Avatar settings saved ($label)")
                    }) { e ->
                        OutputLog.warn("Couldn't save avatar settings to Roblox: ${e.message}")
                        Dialogs.themed(act).setTitle("Saved in the place only").setMessage("The place now uses $label, but Roblox didn't accept the change for the game:\n\n${e.message}")
                            .setPositiveButton("OK", null).show()
                        true
                    }
                } else toast(act, "Avatar type: $label")
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * A Roblox place was opened: its game's avatar settings live on the game, not in the file, so
     * read them and put them in StarterPlayer (without marking the place as changed).
     */
    fun syncFromCloud(act: Activity, studio: Studio, cloud: CloudPlace) {
        if (cloud.universeId == 0L || RobloxApi.isGuest(act)) return
        val app = act.applicationContext
        net.execute {
            val s = try { RobloxCloud.avatarSettings(app, cloud.universeId) } catch (_: Exception) { null } ?: return@execute
            studio.main.post {
                if (studio.cloud?.universeId != cloud.universeId || studio.isPlaying) return@post
                synchronized(studio.lock) {
                    val sp = studio.model.getService("StarterPlayer")
                    sp.set("GameSettingsAvatar", EnumValue(s.type), false)
                    s.collision?.let { sp.set("GameSettingsR15Collision", EnumValue(it), false) }
                    for ((key, k) in scaleKeys) {
                        val lo = s.min[k]; val hi = s.max[k]
                        if (lo != null && hi != null) sp.set("GameSettingsScaleRange$key", NumberRange(lo, hi), false)
                    }
                }
                OutputLog.info("Game Settings: avatar type ${listOf("R6", "R15", "Player Choice")[s.type]} (from the game on Roblox)")
            }
        }
    }

    // ------------------------------------------------------------------ Rig Builder

    /** Avatar → Rig Builder: inserts an R6 or R15 rig (or your avatar) in front of the camera. */
    fun rigBuilder(act: Activity, studio: Studio) {
        if (studio.isPlaying) { toast(act, "Stop the playtest to insert a rig"); return }
        val spec = Avatar.current?.takeIf { it.character == null }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("R6 — Block Rig" to { insertRig(act, studio, PlaySession.buildR6Rig("Rig")) })
        items.add("R15 — Block Rig" to {
            if (RobloxApi.isGuest(act)) toast(act, "R15 body meshes download from Roblox; sign in to see them (they show as boxes until then)")
            insertRig(act, studio, Avatar.buildR15("Rig", Avatar.Spec(Avatar.CLASSIC_COLORS, null, null, null, null, emptyList(), "Rig", r15 = true)))
        })
        if (spec != null) {
            items.add("R6 — My Avatar (${spec.label})" to {
                val m = PlaySession.buildR6Rig(spec.label.removePrefix("@")); Avatar.applyTo(m, spec.forRig(false)); insertRig(act, studio, m)
            })
            items.add("R15 — My Avatar (${spec.label})" to { insertRig(act, studio, Avatar.buildR15(spec.label.removePrefix("@"), spec.forRig(true))) })
        }
        Dialogs.themed(act).setTitle("Rig Builder").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun insertRig(act: Activity, studio: Studio, m: Instance) {
        synchronized(studio.lock) {
            val parts = m.descendants().filter { it.isA("BasePart") }
            val bounds = Geometry.boundsOf(parts) ?: return
            val hit = studio.spawnPoint(Vector3(4f, 0f, 2f))
            // Face the camera, feet on whatever is in the middle of the view.
            val f = studio.camera.forward
            val yaw = atan2(f.x, f.z)
            val place = CFrame.at(Vector3(hit.x, hit.y - bounds.first.y, hit.z)) * CFrame.ry(yaw)
            for (p in parts) p.set("CFrame", place * PartData.cframe(p), false)
            // Like Studio's rigs: the root is anchored so the rig stands still in a playtest.
            m.findFirstChild("HumanoidRootPart")?.set("Anchored", true, false)
            studio.undo.begin("Insert Rig")
            studio.reparent(m, studio.model.workspace)
            studio.undo.commit()
            studio.selection.clear(); studio.selection.add(m)
        }
        studio.listeners.forEach { it.onSelectionChanged() }
        studio.markDirty()
        toast(act, "Inserted ${m.name}")
    }
}
