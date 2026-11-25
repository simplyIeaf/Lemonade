package com.leaf.lemonade.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.model.fmtNum

object SettingsDialog {
    val flySpeeds = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 32f, 64f)
    private val categories = listOf("Appearance", "Camera", "Script Editor", "Viewport", "Playtest")
    private var lastCategory = 0

    fun show(ctx: Context, actions: MainActions) {
        var dialog: StudioDialog? = null
        var themeChanged = false
        fun dp(v: Float) = Ui.dp(ctx, v)

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(studioDivider(ctx))
        root.addView(body, Ui.lp(Ui.MATCH, 0, 1f))

        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(if (Ui.LIGHT) Ui.HEADER else 0xFF25262A.toInt()); setPadding(0, dp(4f), 0, dp(4f)) }
        body.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(left) }, Ui.lp(dp(150f), Ui.MATCH))
        body.addView(View(ctx).apply { setBackgroundColor(StudioStyle.border) }, Ui.lp(dp(1f), Ui.MATCH))

        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(right, Ui.lp(0, Ui.MATCH, 1f))
        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        right.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))
        val desc = Ui.text(ctx, "", 11.5f, StudioStyle.dim).apply {
            isSingleLine = false; setPadding(dp(12f), dp(6f), dp(12f), dp(6f)); visibility = View.GONE
            setBackgroundColor(if (Ui.LIGHT) Ui.HEADER else 0xFF25262A.toInt())
        }
        right.addView(desc, Ui.lp(Ui.MATCH, Ui.WRAP))

        val table = Table(ctx, column, desc)

        fun fill(i: Int) {
            lastCategory = i
            column.removeAllViews()
            table.reset()
            when (categories[i]) {
                "Appearance" -> {
                    table.section("Theme")
                    val themes = Settings.allThemes()
                    table.choice("Theme", themes.map { it.name }, null, themes.indexOfFirst { it.name == Settings.themeName }, null) { n ->
                        if (themes[n].name != Settings.themeName) { Settings.themeName = themes[n].name; themeChanged = true; dialog?.dismiss() }
                    }
                }
                "Camera" -> {
                    table.section("Movement")
                    val flyIdx = flySpeeds.indexOfFirst { it == Settings.flySpeed }
                    table.choice("Fly speed", flySpeeds.map { "x" + fmtNum(it) }, if (flyIdx < 0) "x" + fmtNum(Settings.flySpeed) else null, flyIdx, null) { Settings.flySpeed = flySpeeds[it] }
                    table.slider("Look sensitivity", 0.2f, 3f, Settings.cameraSensitivity, 0.1f) { Settings.cameraSensitivity = it }
                    table.toggle("Smooth camera movement", "Eases the camera in and out instead of starting and stopping instantly.", Settings.cameraSmoothing) { Settings.cameraSmoothing = it }
                }
                "Script Editor" -> {
                    table.section("Editor")
                    table.slider("Font size", 9f, 24f, Settings.editorFontSize, 1f) { Settings.editorFontSize = it }
                    table.toggle("Autocomplete", "Suggests keywords, globals, services, members and names from your place as you type.", Settings.autocomplete) { Settings.autocomplete = it }
                    table.toggle("Auto indent", null, Settings.autoIndent) { Settings.autoIndent = it }
                    table.toggle("Auto-close brackets and quotes", null, Settings.autoCloseBrackets) { Settings.autoCloseBrackets = it }
                }
                "Viewport" -> {
                    table.section("General")
                    table.toggle("Show FPS", null, Settings.showFps) { Settings.showFps = it }
                    table.toggle("Lighting effects", "Bloom, blur, color correction and sun rays from Lighting (turn off if playtests are slow)", Settings.postEffects) { Settings.postEffects = it }
                    table.toggle("Terrain detail", "Textured-looking smooth terrain instead of flat colors (turn off on slow phones)", Settings.terrainDetail) { Settings.terrainDetail = it }
                    table.choice("Materials", listOf("2022 (Modern)", "Pre-2022"), null, if (Settings.modernMaterials) 0 else 1,
                        "Which Roblox material textures parts use. A place can also ask for the old ones (MaterialService.Use2022Materials).") { Settings.modernMaterials = it == 0 }
                    val dists = listOf("Automatic", "Low", "Medium", "High", "Ultra")
                    table.choice("Render distance", dists, null, dists.indexOf(Settings.renderDistance).coerceAtLeast(0),
                        "How far away small parts keep being drawn on big maps. Automatic adjusts to your device's frame rate (fast devices keep more loaded); Low helps slow tablets.") { Settings.renderDistance = dists[it] }
                    table.section("GUI Editing")
                    table.toggle("Smart guides when dragging GUIs", "Snaps GUI objects to the edges and centers of their parent and siblings.", Settings.guiSmartGuides) { Settings.guiSmartGuides = it }
                    val grids = listOf(0, 1, 2, 4, 5, 8, 10, 16)
                    table.choice("GUI drag grid", grids.map { if (it == 0) "Off" else "$it px" }, null, grids.indexOf(Settings.guiGrid), null) { Settings.guiGrid = grids[it] }
                    val scales = listOf(0f, 0.5f, 0.6f, 0.75f, 0.85f, 1f)
                    table.choice("Plugin UI size", scales.map { if (it == 0f) "Auto (fit screen)" else "${(it * 100).toInt()}%" }, null,
                        scales.indexOf(Settings.pluginUiScale).coerceAtLeast(0), "Plugin windows are made for desktop Studio; on a phone they're shrunk to fit.") { Settings.pluginUiScale = scales[it] }
                }
                "Playtest" -> {
                    table.section("Playtest")
                    table.toggle("Hide windows while playtesting", "Gives the game the whole screen; your layout comes back when you stop.", Settings.hideWindowsInPlaytest) { Settings.hideWindowsInPlaytest = it }
                    table.toggle("Shift Lock Switch", "Shows the shift lock button on mobile (also in the in-game Roblox menu).", Settings.shiftLockSwitch) { Settings.shiftLockSwitch = it }
                    table.choice("Camera mode", listOf("Classic", "Follow"), null, if (Settings.cameraMode == "Classic") 0 else 1, null) { Settings.cameraMode = if (it == 0) "Classic" else "Follow" }
                    table.choice("Movement mode", listOf("Dynamic Thumbstick", "Thumbstick"), null, if (Settings.movementMode == "Thumbstick") 1 else 0, null) { Settings.movementMode = if (it == 1) "Thumbstick" else "Dynamic Thumbstick" }
                    table.slider("Camera sensitivity", 0.2f, 4f, Settings.playCameraSensitivity, 0.1f) { Settings.playCameraSensitivity = it }
                }
            }
        }

        fun drawCategories() {
            left.removeAllViews()
            for ((i, name) in categories.withIndex()) {
                val on = i == lastCategory
                left.addView(Ui.text(ctx, name, 13f, if (on) 0xFFFFFFFF.toInt() else StudioStyle.text).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(34f)
                    setPadding(dp(14f), dp(6f), dp(10f), dp(6f))
                    setBackgroundColor(if (on) Ui.ACCENT else Color.TRANSPARENT)
                    isClickable = true
                    setOnClickListener { fill(i); drawCategories() }
                }, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
        }
        drawCategories()
        fill(lastCategory)

        dialog = Dialogs.themed(ctx).setTitle("Studio Settings").setView(root).setFixedSizeDp(720, 420)
            .setNegativeButton("Close", null).create()
        dialog.setOnDismissListener { Settings.changed(); if (themeChanged) actions.applyThemeChange() }
        dialog.show()
    }

    private class Table(private val ctx: Context, private val column: LinearLayout, private val desc: TextView) {
        private var group: LinearLayout? = null
        private var selected: LinearLayout? = null
        private fun dp(v: Float) = Ui.dp(ctx, v)
        private val headerBg get() = if (Ui.LIGHT) Ui.HEADER else 0xFF33343A.toInt()

        private class RowViews(val name: TextView, val value: TextView?)

        fun reset() { group = null; selected = null; desc.text = ""; desc.visibility = View.GONE }

        fun section(title: String) {
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val arrow = android.widget.ImageView(ctx)
            fun setArrow(open: Boolean) { arrow.setImageDrawable(Ui.ArrowDrawable(open, StudioStyle.text)) }
            setArrow(true)
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(headerBg); minimumHeight = dp(30f)
                setPadding(dp(4f), 0, dp(8f), 0)
                isClickable = true
                setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    setArrow(open)
                }
            }
            head.addView(arrow, Ui.lp(dp(20f), dp(24f)))
            head.addView(Ui.text(ctx, title, 13f, StudioStyle.text, true))
            column.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
            column.addView(studioDivider(ctx))
            column.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP))
            group = body
        }

        private fun select(row: LinearLayout, hint: String?) {
            selected?.let { old ->
                old.setBackgroundColor(Color.TRANSPARENT)
                (old.tag as? RowViews)?.let { it.name.setTextColor(StudioStyle.text); it.value?.setTextColor(StudioStyle.text) }
            }
            row.setBackgroundColor(Ui.SELECT)
            (row.tag as? RowViews)?.let { it.name.setTextColor(Ui.SELECT_TEXT); it.value?.setTextColor(Ui.SELECT_TEXT) }
            selected = row
            desc.text = hint ?: ""
            desc.visibility = if (hint.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        private fun addRow(label: String, hint: String?, valueView: View, valueText: TextView?, onTap: (() -> Unit)?): LinearLayout {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(32f) }
            val name = Ui.text(ctx, label, 13f, StudioStyle.text).apply {
                isSingleLine = false; setPadding(dp(24f), dp(6f), dp(8f), dp(6f))
            }
            row.addView(name, Ui.lp(0, Ui.WRAP, 1f))
            row.addView(View(ctx).apply { setBackgroundColor(StudioStyle.border) }, Ui.lp(dp(1f), Ui.MATCH))
            val cell = FrameLayout(ctx).apply { setPadding(dp(10f), dp(4f), dp(10f), dp(4f)) }
            cell.addView(valueView, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER_VERTICAL))
            row.addView(cell, Ui.lp(0, Ui.WRAP, 1f))
            row.tag = RowViews(name, valueText)
            row.isClickable = true
            row.setOnClickListener { select(row, hint); onTap?.invoke() }
            val holder = group ?: column
            holder.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            holder.addView(studioDivider(ctx))
            return row
        }

        fun toggle(label: String, hint: String?, value: Boolean, onSet: (Boolean) -> Unit) {
            val cb = StudioCheckBox(ctx, value)
            cb.setOnClickListener(null)
            cb.isClickable = false
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; addView(cb, Ui.lp(dp(22f), dp(22f))) }
            addRow(label, hint, wrap, null) { cb.isChecked = !cb.isChecked; onSet(cb.isChecked) }
        }

        fun choice(label: String, items: List<String>, customLabel: String?, current: Int, hint: String?, onSet: (Int) -> Unit) {
            var idx = current
            val value = Ui.text(ctx, customLabel ?: items.getOrNull(idx) ?: items.firstOrNull() ?: "", 13f, StudioStyle.text).apply {
                gravity = Gravity.CENTER_VERTICAL
                ellipsize = android.text.TextUtils.TruncateAt.END
                compoundDrawablePadding = dp(8f)
                val arrow = Ui.ArrowDrawable(true, StudioStyle.dim)
                arrow.setBounds(0, 0, dp(12f), dp(12f))
                setCompoundDrawables(null, null, arrow, null)
            }
            lateinit var row: LinearLayout
            row = addRow(label, hint, value, value) {
                val m = StudioMenu(ctx, value)
                for ((i, t) in items.withIndex()) m.add(t) {
                    idx = i; value.text = t; onSet(i)
                }
                m.show()
            }
        }

        fun slider(label: String, min: Float, max: Float, value: Float, step: Float, onSet: (Float) -> Unit) {
            val valueText = Ui.text(ctx, fmtNum(value), 13f, StudioStyle.dim).apply { gravity = Gravity.END; minWidth = dp(34f) }
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            lateinit var row: LinearLayout
            val sl = StudioSlider(ctx, min, max, value, step) { v, fromUser ->
                valueText.text = fmtNum(v)
                if (fromUser) { select(row, null); onSet(v) }
            }
            wrap.addView(sl, Ui.lp(0, Ui.WRAP, 1f))
            wrap.addView(valueText, Ui.lp(Ui.WRAP, Ui.WRAP).apply { leftMargin = dp(6f) })
            row = addRow(label, null, wrap, null, null)
        }
    }

    fun pickFlySpeed(ctx: Context, onSet: () -> Unit) {
        val labels = flySpeeds.map { "x" + fmtNum(it) } + "Custom…"
        val cur = flySpeeds.indexOfFirst { it == Settings.flySpeed }
        Dialogs.themed(ctx).setTitle("Fly speed").setWidthDp(320).setSingleChoiceItems(labels.toTypedArray(), cur) { d, i ->
            d.dismiss()
            if (i < flySpeeds.size) { Settings.flySpeed = flySpeeds[i]; onSet() }
            else Dialogs.prompt(ctx, "Fly speed multiplier (0.05 – 200)", fmtNum(Settings.flySpeed)) { s ->
                s.trim().toFloatOrNull()?.let { Settings.flySpeed = it.coerceIn(0.05f, 200f); onSet() }
            }
        }.setNegativeButton("Cancel", null).show()
    }
}
