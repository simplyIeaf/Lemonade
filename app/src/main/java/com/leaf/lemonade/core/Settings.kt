package com.leaf.lemonade.core

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A color theme for the Studio UI.
 * Every field is an ARGB color.
 */
data class Theme(
    val name: String,
    val light: Boolean,
    val bg: Int, val panel: Int, val header: Int, val topbar: Int, val ribbon: Int, val border: Int,
    val text: Int, val dim: Int, val accent: Int, val select: Int, val hover: Int, val input: Int, val rowAlt: Int,
    val editorBg: Int, val editorGutter: Int, val editorText: Int,
    val synKeyword: Int, val synString: Int, val synNumber: Int, val synComment: Int, val synBuiltin: Int,
) {
    /** Editable color slots, in display order. */
    fun colors(): List<Pair<String, Int>> = listOf(
        "Background" to bg, "Panel" to panel, "Header" to header, "Top bar" to topbar, "Ribbon" to ribbon, "Border" to border,
        "Text" to text, "Dim text" to dim, "Accent" to accent, "Selection" to select, "Hover" to hover, "Input" to input,
        "Alternate row" to rowAlt, "Editor background" to editorBg, "Editor gutter" to editorGutter, "Editor text" to editorText,
        "Keyword" to synKeyword, "String" to synString, "Number" to synNumber, "Comment" to synComment, "Built-in" to synBuiltin,
    )

    fun withColor(slot: String, c: Int): Theme = when (slot) {
        "Background" -> copy(bg = c); "Panel" -> copy(panel = c); "Header" -> copy(header = c); "Top bar" -> copy(topbar = c)
        "Ribbon" -> copy(ribbon = c); "Border" -> copy(border = c); "Text" -> copy(text = c); "Dim text" -> copy(dim = c)
        "Accent" -> copy(accent = c); "Selection" -> copy(select = c); "Hover" -> copy(hover = c); "Input" -> copy(input = c)
        "Alternate row" -> copy(rowAlt = c); "Editor background" -> copy(editorBg = c); "Editor gutter" -> copy(editorGutter = c)
        "Editor text" -> copy(editorText = c); "Keyword" -> copy(synKeyword = c); "String" -> copy(synString = c)
        "Number" -> copy(synNumber = c); "Comment" -> copy(synComment = c); "Built-in" -> copy(synBuiltin = c)
        else -> this
    }

    companion object {
        val DARK = Theme(
            "Dark", false,
            bg = 0xFF1A1B20.toInt(), panel = 0xFF1A1B20.toInt(), header = 0xFF212227.toInt(), topbar = 0xFF1A1B20.toInt(),
            ribbon = 0xFF1A1B20.toInt(), border = 0xFF35383F.toInt(), text = 0xFFDCDDE2.toInt(), dim = 0xFF9A9BA3.toInt(),
            accent = 0xFF3966E9.toInt(), select = 0xFF0C26B1.toInt(), hover = 0xFF2D2E33.toInt(), input = 0xFF2D2E33.toInt(),
            rowAlt = 0xFF1A1B20.toInt(), editorBg = 0xFF202227.toInt(), editorGutter = 0xFF26292F.toInt(), editorText = 0xFFE0E0E0.toInt(),
            synKeyword = 0xFFF86D7C.toInt(), synString = 0xFFADF195.toInt(), synNumber = 0xFFFFC600.toInt(),
            synComment = 0xFF7A7A7A.toInt(), synBuiltin = 0xFF84D6F7.toInt(),
        )
        val LIGHT = Theme(
            "Light", true,
            bg = 0xFFF2F2F2.toInt(), panel = 0xFFFFFFFF.toInt(), header = 0xFFEDEDED.toInt(), topbar = 0xFFE3E3E3.toInt(),
            ribbon = 0xFFF7F7F7.toInt(), border = 0xFFCFCFCF.toInt(), text = 0xFF1B1B1B.toInt(), dim = 0xFF6B6B6B.toInt(),
            accent = 0xFF0B5AAF.toInt(), select = 0xFFBBD9F7.toInt(), hover = 0xFFE2E2E2.toInt(), input = 0xFFFFFFFF.toInt(),
            rowAlt = 0xFFF7F7F7.toInt(), editorBg = 0xFFFFFFFF.toInt(), editorGutter = 0xFFF3F3F3.toInt(), editorText = 0xFF1B1B1B.toInt(),
            synKeyword = 0xFF0000FF.toInt(), synString = 0xFFAD5D00.toInt(), synNumber = 0xFF0A8A6A.toInt(),
            synComment = 0xFF008000.toInt(), synBuiltin = 0xFF1A6FB5.toInt(),
        )
        val builtIn = listOf(DARK, LIGHT)
    }
}

/** Studio settings (File → Studio Settings), persisted in shared preferences. */
object Settings {
    private var prefs: SharedPreferences? = null
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("studio_settings", Context.MODE_PRIVATE)
        val sp = prefs!!
        if (sp.getInt("defaultsVersion", 0) < 1) {
            sp.edit().putString("movementMode", "Thumbstick").putString("cameraMode", "Classic").putString("renderDist", "Medium")
                .putBoolean("postFx", true).putBoolean("terrainDetail", true).putInt("defaultsVersion", 1).apply()
        }
    }

    private fun p() = prefs ?: throw IllegalStateException("Settings.init not called")
    private fun f(k: String, d: Float) = prefs?.getFloat(k, d) ?: d
    private fun b(k: String, d: Boolean) = prefs?.getBoolean(k, d) ?: d
    private fun s(k: String, d: String) = prefs?.getString(k, d) ?: d
    private fun i(k: String, d: Int) = prefs?.getInt(k, d) ?: d

    fun changed() = listeners.forEach { it() }

    // ---------------------------------------------------------------- theme
    var themeName: String
        get() = s("theme", "Dark")
        set(v) { p().edit().putString("theme", v).apply() }

    fun allThemes(): List<Theme> = Theme.builtIn

    val theme: Theme get() = allThemes().firstOrNull { it.name == themeName } ?: Theme.DARK

    // ---------------------------------------------------------------- graphics
    /** Lighting post effects (Bloom, Blur, ColorCorrection, SunRays) when a place has them. */
    var postEffects: Boolean
        get() = b("postFx", true)
        set(v) { p().edit().putBoolean("postFx", v).apply() }

    /** Surface detail on smooth terrain (a grainy texture instead of flat colours). */
    var terrainDetail: Boolean
        get() = b("terrainDetail", true)
        set(v) { p().edit().putBoolean("terrainDetail", v).apply() }

    // ---------------------------------------------------------------- camera
    /** Edit-mode fly camera speed multiplier. */
    var flySpeed: Float
        get() = f("flySpeed", 1f)
        set(v) { p().edit().putFloat("flySpeed", v).apply() }
    /** Edit-mode look sensitivity. */
    var cameraSensitivity: Float
        get() = f("camSens", 1f)
        set(v) { p().edit().putFloat("camSens", v).apply() }
    /** Smooth (eased) camera movement and turning. */
    var cameraSmoothing: Boolean
        get() = b("camSmooth", true)
        set(v) { p().edit().putBoolean("camSmooth", v).apply() }

    // ---------------------------------------------------------------- playtest (the in-game Roblox menu also edits these)
    var playCameraSensitivity: Float
        get() = f("playSens", 1f)
        set(v) { p().edit().putFloat("playSens", v).apply() }
    /** Roblox's "Shift Lock Switch" setting: shows the shift lock button on mobile. */
    var shiftLockSwitch: Boolean
        get() = b("shiftLockSwitch", true)
        set(v) { p().edit().putBoolean("shiftLockSwitch", v).apply() }
    /** "Classic" or "Follow". */
    var cameraMode: String
        get() = s("cameraMode", "Classic")
        set(v) { p().edit().putString("cameraMode", v).apply() }
    /** "Dynamic Thumbstick" or "Thumbstick". */
    var movementMode: String
        get() = s("movementMode", "Thumbstick")
        set(v) { p().edit().putString("movementMode", v).apply() }
    var graphicsQuality: Int
        get() = i("gfx", 10)
        set(v) { p().edit().putInt("gfx", v).apply() }
    var volume: Int
        get() = i("volume", 10)
        set(v) { p().edit().putInt("volume", v).apply() }
    var hideWindowsInPlaytest: Boolean
        get() = b("hidePlay", false)
        set(v) { p().edit().putBoolean("hidePlay", v).apply() }

    // ---------------------------------------------------------------- editor
    var editorFontSize: Float
        get() = f("editorFont", 13f)
        set(v) { p().edit().putFloat("editorFont", v).apply() }
    var autocomplete: Boolean
        get() = b("autocomplete", true)
        set(v) { p().edit().putBoolean("autocomplete", v).apply() }
    var autoIndent: Boolean
        get() = b("autoIndent", true)
        set(v) { p().edit().putBoolean("autoIndent", v).apply() }
    var autoCloseBrackets: Boolean
        get() = b("autoClose", true)
        set(v) { p().edit().putBoolean("autoClose", v).apply() }

    // ---------------------------------------------------------------- viewport / GUI editing
    /**
     * Part material textures: Roblox's 2022 materials (true, the default) or the older pre-2022
     * ones. A place can also ask for the old ones with MaterialService.Use2022Materials = false.
     */
    var modernMaterials: Boolean
        get() = b("modernMaterials", true)
        set(v) { p().edit().putBoolean("modernMaterials", v).apply(); changed() }
    var showFps: Boolean
        get() = b("showFps", true)
        set(v) { p().edit().putBoolean("showFps", v).apply() }
    /**
     * How far away small parts keep being drawn on big maps: "Automatic" (adapts to the frame
     * rate, so fast devices keep more loaded), "Low", "Medium", "High" or "Ultra".
     */
    var renderDistance: String
        get() = s("renderDist", "Medium")
        set(v) { p().edit().putString("renderDist", v).apply() }
    var guiSmartGuides: Boolean
        get() = b("guiGuides", true)
        set(v) { p().edit().putBoolean("guiGuides", v).apply() }
    /** Grid (in offset pixels) GUI objects snap to while dragging; 0 = off. */
    var guiGrid: Int
        get() = i("guiGrid", 0)
        set(v) { p().edit().putInt("guiGrid", v).apply() }
    var confirmDelete: Boolean
        get() = b("confirmDel", false)
        set(v) { p().edit().putBoolean("confirmDel", v).apply() }

    // ---------------------------------------------------------------- Snap to Grid (ribbon increments, kept like Studio does)
    var moveSnap: Float
        get() = f("moveSnap", 1f)
        set(v) { prefs?.edit()?.putFloat("moveSnap", v)?.apply() }
    var rotSnap: Float
        get() = f("rotSnap", 15f)
        set(v) { prefs?.edit()?.putFloat("rotSnap", v)?.apply() }
    var moveSnapOn: Boolean
        get() = b("moveSnapOn", true)
        set(v) { prefs?.edit()?.putBoolean("moveSnapOn", v)?.apply() }
    var rotSnapOn: Boolean
        get() = b("rotSnapOn", true)
        set(v) { prefs?.edit()?.putBoolean("rotSnapOn", v)?.apply() }
    var selectInsideModels: Boolean
        get() = b("selInside", false)
        set(v) { prefs?.edit()?.putBoolean("selInside", v)?.apply() }
    var dragCollisions: Boolean
        get() = b("dragCollisions", false)
        set(v) { prefs?.edit()?.putBoolean("dragCollisions", v)?.apply() }
    /** Size of plugin GUIs (they're designed for desktop screens); 0 = fit the viewport automatically. */
    var pluginUiScale: Float
        get() = f("pluginScale", 0f)
        set(v) { prefs?.edit()?.putFloat("pluginScale", v)?.apply() }
}
