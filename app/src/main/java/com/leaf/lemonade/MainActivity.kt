package com.leaf.lemonade

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.PlaceFactory
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.core.SolidModeling
import com.leaf.lemonade.core.Recents
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.core.Tool
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.EnumValue
import com.leaf.lemonade.model.Enums
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.fmtNum
import com.leaf.lemonade.net.CloudPlace
import com.leaf.lemonade.net.RobloxApi
import com.leaf.lemonade.ui.*
import java.util.IdentityHashMap

class MainActivity : AppCompatActivity(), MainActions, Studio.Listener, StartScreen.Host {
    private lateinit var studio: Studio
    private lateinit var viewport: ViewportView
    private lateinit var explorer: ExplorerPanel
    private lateinit var properties: PropertiesPanel
    private lateinit var output: OutputPanel
    private lateinit var toolbox: ToolboxPanel
    private lateinit var terrainPanel: TerrainPanel
    private lateinit var dock: DockLayout
    private lateinit var ribbon: LinearLayout
    private lateinit var ribbonScroll: HorizontalScrollView
    private lateinit var ribbonDivider: View
    private lateinit var tabRow: LinearLayout
    private lateinit var playBtn: android.widget.ImageView
    private lateinit var stopBtn: android.widget.ImageView
    private lateinit var testBtn: TextView
    private lateinit var hideBtn: android.widget.ImageView
    private lateinit var titleView: TextView
    private lateinit var accountChip: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private var startScreen: StartScreen? = null
    private val layoutPrefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }
    private val accountListener: () -> Unit = { runOnUiThread { refreshAccountChip() } }
    private lateinit var scriptTabs: LinearLayout
    private lateinit var scriptTabsScroll: HorizontalScrollView
    private lateinit var editorHost: FrameLayout
    private val ribbonTabs = listOf("Home", "Model", "Avatar", "UI", "Test", "View", "Plugins")
    private var ribbonTab = "Home"
    private val openEditors = IdentityHashMap<Instance, ScriptEditor>()
    private val editorOrder = ArrayList<Instance>()
    private var activeScript: Instance? = null
    private val keysDown = HashSet<Int>()
    /** Hidden state to restore when a playtest that auto-hid the windows stops. */
    private var hiddenBeforePlay: Boolean? = null

    private enum class OpenMode { PLACE, TOOLBOX, PLUGIN }
    private var openMode = OpenMode.PLACE
    private var pendingSave: (() -> ByteArray?)? = null
    private var pendingSaveIsPlace = false

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onFilePicked(uri) }
    private val createLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) writeTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        Ui.applyTheme(Settings.theme)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        studio = (application as? StudioApp)?.studio ?: Studio(applicationContext)
        studio.toastHandler = { StudioToast.show(this, it) }
        studio.openScriptHandler = { s -> runOnUiThread { openScript(s) } }
        Icons.init(applicationContext)
        Fonts.init(assets)
        com.leaf.lemonade.net.ImageLoader.init(applicationContext)
        com.leaf.lemonade.net.AssetFetcher.init(applicationContext)
        com.leaf.lemonade.render.MaterialTextures.init(applicationContext)
        com.leaf.lemonade.net.Accounts.listeners.add(accountListener)
        buildUi()
        studio.listeners.add(this)
        studio.listeners.add(explorer)
        studio.listeners.add(properties)
        studio.pluginMenuHandler = { title, labels, pick -> showPluginMenu(title, labels, pick) }
        com.leaf.lemonade.net.WebPermissions.asker = webAsker
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    startScreen != null -> if (hasOpenPlace()) closeStart() else finish()
                    studio.isPlaying && viewport.hud.handleBack() -> {}
                    activeScript != null -> showPlace()
                    studio.isPlaying -> studio.stopPlay()
                    dock.allHidden -> setAllHidden(false)
                    else -> confirmDiscard { finish() }
                }
            }
        })
        val app = application as? StudioApp
        var firstLaunch = false
        if (app != null && !app.started) {
            app.started = true
            firstLaunch = true
            OutputLog.info("Lemonade ready. Open a .rbxl/.rbxm file or one of your Roblox games, or start building!")
            studio.plugins.loadAll()
            com.leaf.lemonade.engine.Avatar.refresh(applicationContext)
        }
        if (savedInstanceState == null) handleIntent(intent)
        // Like Studio, open on the start page (unless a file was opened with the app).
        if (firstLaunch && intent?.action != Intent.ACTION_VIEW) showStart()
    }

    // ------------------------------------------------------------------ start page

    fun showStart() {
        if (startScreen != null) return
        val s = StartScreen(this, studio, this, this)
        startScreen = s
        rootFrame.addView(s, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun closeStart() {
        startScreen?.let { rootFrame.removeView(it) }
        startScreen = null
    }

    override fun hasOpenPlace(): Boolean = (application as? StudioApp)?.placeOpened == true

    override fun newFromTemplate(t: PlaceFactory.Template) {
        confirmDiscard {
            closeAllScripts()
            studio.newPlace(t)
            (application as? StudioApp)?.placeOpened = true
            closeStart()
        }
    }

    override fun openFileFromStart() { openMode = OpenMode.PLACE; openLauncher.launch(arrayOf("*/*")) }

    override fun openRecentFile(e: com.leaf.lemonade.core.Recents.Entry) {
        val uri = e.uri?.let { Uri.parse(it) } ?: return
        openMode = OpenMode.PLACE
        onFilePicked(uri)
    }

    override fun showStudioSettings() = showSettings()

    private fun refreshAccountChip() {
        if (!::accountChip.isInitialized) return
        accountChip.removeAllViews()
        val img = android.widget.ImageView(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.HOVER) }
            clipToOutline = true
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        accountChip.addView(img, Ui.lp(dp(28f), dp(28f)))
        val a = com.leaf.lemonade.net.Accounts.active(this)
        if (a != null) AccountUi.loadHeadshot(img, a.userId) else { img.setPadding(dp(4f), dp(4f), dp(4f), dp(4f)); img.setImageDrawable(Icons.general(this, "Account")) }
        accountChip.contentDescription = if (a != null) "Roblox account @${a.name}" else "Not signed in"
    }

    /**
     * HttpService wants to reach a website for the first time (Studio asks plugins the same way).
     * Allow/Block are remembered (Studio Settings → Privacy resets them); closing the dialog
     * refuses just this request.
     */
    private fun askWebPermission(host: String, who: String, answer: (Boolean?) -> Unit) {
        if (isFinishing || isDestroyed) { answer(null); return }
        var answered = false
        fun reply(v: Boolean?) { if (!answered) { answered = true; answer(v) } }
        val d = Dialogs.themed(this).setTitle("Allow access to $host?")
            .setMessage("$who wants to connect to $host over the internet.\n\n" +
                "It could send that website things from the place you have open. Your Roblox sign-in is never shared with it.\n\n" +
                "Only allow websites you trust.")
            .setPositiveButton("Allow") { _, _ -> reply(true); OutputLog.info("Allowed scripts and plugins to connect to $host") }
            .setNegativeButton("Block") { _, _ -> reply(false); OutputLog.warn("Blocked scripts and plugins from connecting to $host") }
            .create()
        d.setOnDismissListener { reply(null) }
        d.show()
    }

    /** A plugin's PluginMenu:ShowAsync(). */
    private fun showPluginMenu(title: String, labels: List<String>, pick: (Int) -> Unit) {
        if (labels.isEmpty()) { pick(-1); return }
        var picked = false
        val d = Dialogs.themed(this).setTitle(title.ifEmpty { "Plugin" }).setItems(labels.toTypedArray()) { _, i -> picked = true; pick(i) }.create()
        d.setOnDismissListener { if (!picked) pick(-1) }
        d.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        // Debug builds only: `adb shell am start ... --es lua "<code>"` runs a command-bar line
        // (automated testing of plugins, camera and playtests). Release builds ignore it.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        i?.getStringExtra("lua")?.let { code -> if (debuggable) studio.glJobs.add { studio.runCommand(code) } }
        if (debuggable && i?.getBooleanExtra("play", false) == true) studio.main.post { studio.startPlay() }
        if (debuggable && i?.getBooleanExtra("stop", false) == true) studio.main.post { studio.stopPlay() }
        i?.getStringExtra("playas")?.let { n -> if (debuggable) studio.main.post { synchronized(studio.lock) { studio.model.workspace.findFirstChild(n) }?.let { studio.playAs(it) } } }
        val uri = i?.data ?: return
        if (i.action == Intent.ACTION_VIEW) { openMode = OpenMode.PLACE; onFilePicked(uri) }
    }

    override fun onResume() { super.onResume(); viewport.onResume() }
    override fun onPause() {
        super.onPause()
        viewport.onPause()
        openEditors.values.forEach { it.commit() }
    }

    private val webAsker: (String, String, (Boolean?) -> Unit) -> Unit = { host, who, answer -> runOnUiThread { askWebPermission(host, who, answer) } }

    override fun onDestroy() {
        if (com.leaf.lemonade.net.WebPermissions.asker === webAsker) com.leaf.lemonade.net.WebPermissions.asker = null
        studio.listeners.remove(this); studio.listeners.remove(explorer); studio.listeners.remove(properties)
        com.leaf.lemonade.net.Accounts.listeners.remove(accountListener)
        if (studio.pluginMenuHandler != null) studio.pluginMenuHandler = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        val phone = resources.configuration.smallestScreenWidthDp < 600
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }

        // Mezzanine: File, Test dropdown with Play / Stop, tabs in the middle, account on the right.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.TOPBAR)
            minimumHeight = dp(42f); setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
        }
        top.addView(Ui.text(this, "File", 13f, Ui.TEXT).apply {
            gravity = Gravity.CENTER; setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = Ui.rippleBg(this@MainActivity, Ui.HOVER, 6f); isClickable = true
            setOnClickListener { showFileMenu(it) }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = dp(6f) })
        testBtn = Ui.text(this, "Test", 13f, 0xFFF6F7F9.toInt().let { if (Ui.LIGHT) Ui.TEXT else it }).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(8f), dp(6f))
            compoundDrawablePadding = dp(18f)
            val chev = g("MezChevron"); chev.setBounds(0, 0, dp(14f), dp(14f))
            setCompoundDrawables(null, null, chev, null)
            background = Ui.rippleBg(this@MainActivity, Ui.HOVER, 6f); isClickable = true
            setOnClickListener { showTestMenu(it) }
        }
        top.addView(testBtn, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = dp(6f) })
        playBtn = Ui.iconButton(this, g("MezPlay"), 32f) { if (!studio.isPlaying) togglePlay() }.apply { contentDescription = "Play" }
        top.addView(playBtn)
        stopBtn = Ui.iconButton(this, g("MezStop"), 32f) { if (studio.isPlaying) togglePlay() }.apply { contentDescription = "Stop" }
        top.addView(stopBtn)
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        top.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(tabRow, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP)) },
            Ui.lp(0, Ui.WRAP, 1f).apply { setMargins(dp(8f), 0, dp(8f), 0) })
        titleView = Ui.text(this, "", 12f, Ui.DIM).apply { setPadding(dp(8f), 0, dp(8f), 0) }
        if (!phone) top.addView(titleView)
        hideBtn = Ui.iconButton(this, g("HideWindows")) { setAllHidden(!dock.allHidden) }
        top.addView(hideBtn)
        top.addView(Ui.iconButton(this, g("Undo")) { studio.undo.undo() })
        top.addView(Ui.iconButton(this, g("Redo")) { studio.undo.redo() })
        accountChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { AccountUi.show(this@MainActivity, studio, this@MainActivity) }
        }
        top.addView(accountChip, Ui.lp(Ui.WRAP, Ui.WRAP).apply { setMargins(dp(6f), 0, dp(2f), 0) })
        refreshAccountChip()
        root.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))

        ribbon = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6f), dp(3f), dp(6f), dp(3f)) }
        ribbonScroll = HorizontalScrollView(this).apply { setBackgroundColor(Ui.RIBBON); isHorizontalScrollBarEnabled = false; addView(ribbon) }
        root.addView(ribbonScroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        ribbonDivider = Ui.divider(this)
        root.addView(ribbonDivider)

        // Center: script tabs + viewport / script editor. Everything else is a dockable panel.
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scriptTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.TOPBAR) }
        scriptTabsScroll = HorizontalScrollView(this).apply { addView(scriptTabs); visibility = View.GONE }
        center.addView(scriptTabsScroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        val stack = FrameLayout(this)
        viewport = ViewportView(this, studio)
        stack.addView(viewport, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        editorHost = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Ui.EDITOR_BG) }
        stack.addView(editorHost, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        center.addView(stack, Ui.lp(Ui.MATCH, 0, 1f))

        toolbox = ToolboxPanel(this, studio, this)
        output = OutputPanel(this, studio)
        explorer = ExplorerPanel(this, studio, this)
        properties = PropertiesPanel(this, studio)
        dock = DockLayout(this, layoutPrefs)
        dock.setCenter(center)
        dock.addPanel("explorer", "Explorer", explorer, DockLayout.Zone.RIGHT, 0)
        dock.addPanel("properties", "Properties", properties, DockLayout.Zone.RIGHT, 1)
        dock.addPanel("toolbox", "Toolbox", toolbox, DockLayout.Zone.LEFT, 0, !phone)
        dock.addPanel("output", "Output", output, DockLayout.Zone.BOTTOM, 0, !phone)
        terrainPanel = TerrainPanel(this, studio)
        dock.addPanel("terrain", "Terrain Editor", terrainPanel, DockLayout.Zone.LEFT, 1, false)
        dock.onChanged = { if (ribbonTab == "View") buildRibbon(); updateHideButton() }
        root.addView(dock, Ui.lp(Ui.MATCH, 0, 1f))
        dock.load()

        rootFrame = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        rootFrame.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        buildTabs()
        buildRibbon()
        updateTitle()
        updateHideButton()
        if (studio.isPlaying) onPlayStateChanged()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    /** "Hide all windows": just the viewport / script editor (plus the top bar to bring them back). */
    private fun setAllHidden(hidden: Boolean) {
        dock.allHidden = hidden
        updateHideButton()
    }

    private fun updateHideButton() {
        val hidden = dock.allHidden
        hideBtn.setImageDrawable(g(if (hidden) "ShowWindows" else "HideWindows"))
        hideBtn.contentDescription = if (hidden) "Show windows" else "Hide all windows"
        ribbonScroll.visibility = if (hidden) View.GONE else View.VISIBLE
        ribbonDivider.visibility = ribbonScroll.visibility
    }

    override fun applyThemeChange() {
        Ui.applyTheme(Settings.theme)
        openEditors.values.forEach { it.commit() }
        recreate()
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        for (t in ribbonTabs) {
            val on = t == ribbonTab
            val tv = Ui.text(this, t, 13.5f, if (on) (if (Ui.LIGHT) Ui.TEXT else 0xFFFCFCFE.toInt()) else (if (Ui.LIGHT) Ui.DIM else 0xFFC2C2CA.toInt())).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
                setOnClickListener { ribbonTab = t; buildTabs(); buildRibbon() }
                background = Ui.rippleBg(this@MainActivity, if (on) (if (Ui.LIGHT) Ui.HOVER else 0xFF34353A.toInt()) else android.graphics.Color.TRANSPARENT, 6f)
            }
            tabRow.addView(tv, Ui.lp(Ui.WRAP, Ui.WRAP).apply { setMargins(dp(2f), 0, dp(2f), 0) })
        }
    }

    private fun sep() = ribbon.addView(View(this).apply { setBackgroundColor(if (Ui.LIGHT) Ui.BORDER else 0xFF303136.toInt()) }, LinearLayout.LayoutParams(dp(1f), dp(44f)).apply { setMargins(dp(6f), 0, dp(6f), 0) })

    private fun g(name: String, fallback: String = name) = Icons.general(this, name, fallback)
    private fun inst(cls: String) = Icons.instance(this, cls)

    /** The button row of the ribbon group being built (null: straight into the ribbon). */
    private var groupRow: LinearLayout? = null

    /** A toolbar group; groups are told apart by a thin vertical divider, like Studio's toolbar. */
    private fun group(@Suppress("UNUSED_PARAMETER") caption: String, fill: () -> Unit) {
        if (ribbon.childCount > 0) sep()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ribbon.addView(row, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        groupRow = row
        try { fill() } finally { groupRow = null }
    }

    private fun tool(icon: android.graphics.drawable.Drawable, label: String, selected: Boolean = false, fn: () -> Unit) {
        val b = Ui.toolButton(this, icon, label) { fn() }
        b.isSelected = selected
        (groupRow ?: ribbon).addView(b)
    }

    /** Studio's small stacked buttons (e.g. Copy / Cut / Duplicate under Paste). */
    private fun stack(vararg items: Triple<android.graphics.drawable.Drawable, String, () -> Unit>) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        for ((icon, label, fn) in items) {
            col.addView(Ui.iconTextButton(this, icon, label, android.graphics.Color.TRANSPARENT) { fn() }.apply {
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(6f), dp(1f), dp(8f), dp(1f))
                minHeight = 0; minimumHeight = 0
                compoundDrawables[0]?.setBounds(0, 0, dp(14f), dp(14f))
                setCompoundDrawables(compoundDrawables[0], null, null, null)
            }, Ui.lp(Ui.WRAP, dp(17f)))
        }
        (groupRow ?: ribbon).addView(col)
    }

    private fun toolsGroup() = group("Tools") {
        tool(g("Select"), "Select", studio.tool == Tool.SELECT) { studio.tool = Tool.SELECT }
        tool(g("Move"), "Move", studio.tool == Tool.MOVE) { studio.tool = Tool.MOVE }
        tool(g("Scale"), "Scale", studio.tool == Tool.SCALE) { studio.tool = Tool.SCALE }
        tool(g("Rotate"), "Rotate", studio.tool == Tool.ROTATE) { studio.tool = Tool.ROTATE }
        tool(g("SelectAll", "Multi"), "Multi-select", studio.multiSelect) { studio.multiSelect = !studio.multiSelect; buildRibbon() }
        tool(inst("Model"), "Select Inside", studio.selectInsideModels) { studio.selectInsideModels = !studio.selectInsideModels; buildRibbon() }
        tool(g("BoxSelect", "Box"), "Box Select", studio.boxSelect) { studio.boxSelect = !studio.boxSelect; buildRibbon() }
    }

    private fun selectedPartsLocked() = synchronized(studio.lock) { studio.selectedParts().let { it.isNotEmpty() && it.all { p -> p["Locked"] == true } } }

    private fun setLockedOnSelection(v: Boolean) {
        synchronized(studio.lock) {
            val parts = studio.selectedParts()
            if (parts.isEmpty()) return
            studio.undo.begin(if (v) "Lock" else "Unlock")
            for (p in parts) studio.setProp(p, "Locked", v)
            studio.undo.commit()
        }
        studio.markDirty()
        toast(if (v) "Locked: these parts can't be picked in the 3D view (select them in the Explorer)" else "Unlocked")
    }

    /** Puts [cls] inside the selected part/attachment (effects, lights), or in Workspace. */
    private fun insertInto(cls: String) {
        val target = synchronized(studio.lock) { studio.selection.firstOrNull { it.isA("BasePart") || it.className == "Attachment" } }
        if (target == null && cls != "Model") toast("Select a part to put the ${cls} in (added to Workspace)")
        studio.insertObject(cls, target ?: studio.model.workspace)
        studio.markDirty()
    }

    /** Model → Constraints: joins the first two selected parts. */
    private fun insertConstraint(cls: String) {
        val parts = synchronized(studio.lock) { studio.selectedParts() }
        if (parts.size < 2) { toast("Select the two parts to join"); return }
        synchronized(studio.lock) {
            val a = parts[0]; val b = parts[1]
            val c = com.leaf.lemonade.model.InstanceFactory.create(cls)
            c.set("Part0", a, false); c.set("Part1", b, false)
            if (cls == "Weld") {
                c.set("C0", com.leaf.lemonade.model.CFrame.IDENTITY, false)
                c.set("C1", com.leaf.lemonade.model.PartData.cframe(b).inverse() * com.leaf.lemonade.model.PartData.cframe(a), false)
            }
            studio.undo.begin("Insert $cls")
            studio.reparent(c, a)
            studio.undo.commit()
            studio.selection.clear(); studio.selection.add(c)
        }
        studio.listeners.forEach { it.onSelectionChanged() }
        studio.markDirty()
        toast("Joined ${parts[0].name} and ${parts[1].name} with a $cls")
    }

    private fun buildRibbon() {
        ribbon.removeAllViews()
        when (ribbonTab) {
            "Home" -> {
                group("Clipboard") {
                    tool(g("Paste", "Pst"), "Paste") { studio.paste(null); studio.markDirty() }
                    stack(
                        Triple(g("Copy", "Cp"), "Copy") { studio.copySelection() },
                        Triple(g("Cut", "Cut"), "Cut") { studio.cutSelection(); studio.markDirty() },
                        Triple(g("Duplicate"), "Duplicate") { studio.duplicateSelection(); studio.markDirty() },
                    )
                }
                toolsGroup()
                snapGroup()
                group("Insert") {
                    tool(g("Part_Block", "Part"), "Part") { pickPartShape() }
                    tool(inst("Script"), "Script") { studio.insertObject("Script", studio.selection.firstOrNull()?.takeIf { !it.isService || it.className == "ServerScriptService" } ?: studio.model.getService("ServerScriptService")) }
                    tool(inst("ScreenGui"), "UI") { ribbonTab = "UI"; buildTabs(); buildRibbon() }
                    tool(g("InsertObject", "Insert"), "Object…") { explorer.showInsertDialog(studio.selection.firstOrNull()) }
                    tool(g("Toolbox"), "Toolbox", dock.isShown("toolbox")) { dock.toggle("toolbox") }
                }
                // Studio's Terrain group: the Terrain Editor (generate, sculpt, paint).
                group("Terrain") {
                    tool(inst("Terrain"), "Editor", dock.isShown("terrain")) {
                        dock.toggle("terrain")
                        if (!dock.isShown("terrain")) studio.terrainTool = null
                        buildRibbon()
                    }
                }
                group("Edit") {
                    tool(g("Color"), "Color") { pickColorForSelection() }
                    tool(g("Material"), "Material") { pickMaterialForSelection() }
                    tool(g("Group"), "Group") { studio.groupSelection() }
                    tool(g("Lock"), "Lock", selectedPartsLocked()) { setLockedOnSelection(!selectedPartsLocked()); buildRibbon() }
                    tool(g("Anchor"), "Anchor") { val anchored = synchronized(studio.lock) { studio.selectedParts().all { it["Anchored"] == true } }; studio.setAnchored(!anchored) }
                    stack(
                        Triple(g("Ungroup"), "Ungroup") { studio.ungroupSelection() },
                        Triple(g("Delete"), "Delete") { studio.deleteSelection() },
                        Triple(g("ZoomTo", "Focus"), "Focus") { studio.focusSelection() },
                    )
                }
                group("Test") {
                    tool(if (studio.isPlaying) g("Stop") else g("Play"), if (studio.isPlaying) "Stop" else "Play") { togglePlay() }
                    if (!studio.isPlaying) tool(g("Play", "Here"), "Play Here") { showPlace(); openEditors.values.forEach { it.commit() }; studio.playHere() }
                }
            }
            "Model" -> {
                toolsGroup()
                snapGroup()
                group("Parts") {
                    tool(g("Part_Block", "Block"), "Block") { studio.insertPart(1) }
                    tool(g("Part_Sphere", "Sphere"), "Sphere") { studio.insertPart(0) }
                    tool(g("Part_Cylinder", "Cylinder"), "Cylinder") { studio.insertPart(2) }
                    tool(g("Part_Wedge", "Wedge"), "Wedge") { studio.insertPart(3) }
                    tool(g("Part_CornerWedge", "Corner"), "Corner") { studio.insertPart(4) }
                    tool(inst("TrussPart"), "Truss") { studio.insertObject("TrussPart").also { synchronized(studio.lock) { it.set("Anchored", true) } } }
                    tool(g("Spawn"), "Spawn") { studio.insertObject("SpawnLocation") }
                }
                group("Appearance") {
                    tool(g("Color"), "Color") { pickColorForSelection() }
                    tool(g("Material"), "Material") { pickMaterialForSelection() }
                    tool(g("Surface"), "Surface") { pickSurfaceForSelection() }
                }
                group("Solid Modeling") {
                    tool(g("Union", "Uni"), "Union") { solid { SolidModeling.combine(studio, false) } }
                    tool(g("Intersect", "Int"), "Intersect") { solid { SolidModeling.combine(studio, true) } }
                    tool(g("Negate", "Neg"), "Negate") { solid { if (SolidModeling.negate(studio) == 0) "Select the parts to negate." else null } }
                    tool(g("Separate", "Sep"), "Separate") { solid { SolidModeling.separate(studio) } }
                }
                group("Constraints") {
                    tool(inst("WeldConstraint"), "Weld") { insertConstraint("WeldConstraint") }
                    tool(inst("NoCollisionConstraint"), "No Collide") { insertConstraint("NoCollisionConstraint") }
                    tool(inst("Attachment"), "Attachment") { insertInto("Attachment") }
                }
                group("Effects") {
                    tool(inst("ParticleEmitter"), "Particles") { insertInto("ParticleEmitter") }
                    tool(inst("Fire"), "Fire") { insertInto("Fire") }
                    tool(inst("Smoke"), "Smoke") { insertInto("Smoke") }
                    tool(inst("Sparkles"), "Sparkles") { insertInto("Sparkles") }
                    tool(inst("PointLight"), "Light") { insertInto("PointLight") }
                    tool(inst("Highlight"), "Highlight") { insertInto("Highlight") }
                }
                group("Advanced") {
                    tool(inst("Script"), "Script") { studio.insertObject("Script", studio.selection.firstOrNull()?.takeIf { !it.isService || it.className == "ServerScriptService" } ?: studio.model.getService("ServerScriptService")) }
                    tool(inst("LocalScript"), "LocalScript") { studio.insertObject("LocalScript", studio.selection.firstOrNull()?.takeIf { !it.isService || it.className.startsWith("Starter") } ?: studio.model.getService("StarterPlayer").let { sp -> sp.findFirstChild("StarterPlayerScripts") ?: sp }) }
                    tool(inst("ModuleScript"), "Module") { studio.insertObject("ModuleScript", studio.selection.firstOrNull()?.takeIf { !it.isService || it.className == "ReplicatedStorage" } ?: studio.model.getService("ReplicatedStorage")) }
                }
            }
            "Avatar" -> {
                // Like Studio's Avatar tab: game avatar settings, rigs, and who you play as.
                group("Game") {
                    tool(g("AvatarGame"), "Avatar") { AvatarUi.gameSettings(this, studio) }
                }
                group("Rig Builder") {
                    tool(g("AvatarSetup"), "Rig Builder") { AvatarUi.rigBuilder(this, studio) }
                }
            }
            "UI" -> {
                group("Containers") {
                    tool(inst("ScreenGui"), "ScreenGui") { studio.insertObject("ScreenGui") }
                    tool(inst("Frame"), "Frame") { studio.insertObject("Frame") }
                    tool(inst("ScrollingFrame"), "Scrolling") { studio.insertObject("ScrollingFrame") }
                    tool(inst("CanvasGroup"), "Canvas") { studio.insertObject("CanvasGroup") }
                }
                group("Text & Images") {
                    tool(inst("TextLabel"), "TextLabel") { studio.insertObject("TextLabel") }
                    tool(inst("TextButton"), "TextButton") { studio.insertObject("TextButton") }
                    tool(inst("TextBox"), "TextBox") { studio.insertObject("TextBox") }
                    tool(inst("ImageLabel"), "Image") { studio.insertObject("ImageLabel") }
                    tool(inst("ImageButton"), "ImageButton") { studio.insertObject("ImageButton") }
                }
                group("Style") {
                    tool(inst("UICorner"), "Corner") { studio.insertObject("UICorner") }
                    tool(inst("UIStroke"), "Stroke") { studio.insertObject("UIStroke") }
                    tool(inst("UIGradient"), "Gradient") { studio.insertObject("UIGradient") }
                    tool(inst("UIPadding"), "Padding") { studio.insertObject("UIPadding") }
                }
                group("Layout") {
                    tool(inst("UIListLayout"), "List") { studio.insertObject("UIListLayout") }
                    tool(inst("UIGridLayout"), "Grid") { studio.insertObject("UIGridLayout") }
                    tool(inst("UIAspectRatioConstraint"), "Aspect") { studio.insertObject("UIAspectRatioConstraint") }
                    tool(inst("UIScale"), "Scale") { studio.insertObject("UIScale") }
                }
                group("3D UI") {
                    tool(inst("BillboardGui"), "Billboard") { insertInto("BillboardGui") }
                    tool(inst("SurfaceGui"), "SurfaceGui") { insertInto("SurfaceGui") }
                }
            }
            "Test" -> {
                group("Play") {
                    tool(if (studio.isPlaying) g("Stop") else g("Play"), if (studio.isPlaying) "Stop" else "Play") { togglePlay() }
                    if (!studio.isPlaying) tool(g("Play", "Here"), "Play Here") { showPlace(); openEditors.values.forEach { it.commit() }; studio.playHere() }
                }
                group("Output") {
                    tool(g("Cleanup", "Clear"), "Clear Output") { OutputLog.clear() }
                    tool(g("CommandBar", "Cmd"), "Command") { if (!dock.isShown("output")) dock.setShown("output", true) }
                }
            }
            "View" -> {
                group("Windows") {
                    tool(g("Explorer"), "Explorer", dock.isShown("explorer")) { dock.toggle("explorer") }
                    tool(g("Properties"), "Properties", dock.isShown("properties")) { dock.toggle("properties") }
                    tool(g("Toolbox"), "Toolbox", dock.isShown("toolbox")) { dock.toggle("toolbox") }
                    tool(g("Output"), "Output", dock.isShown("output")) { dock.toggle("output") }
                    tool(inst("Terrain"), "Terrain", dock.isShown("terrain")) { dock.toggle("terrain"); if (!dock.isShown("terrain")) studio.terrainTool = null }
                    tool(g("HideWindows"), "Hide All", dock.allHidden) { setAllHidden(!dock.allHidden) }
                    tool(g("SwitchWindows", "Lay"), "Reset Layout") { dock.reset() }
                }
                group("Viewport") {
                    tool(g("Effects", "Shd"), "Shadows", studio.shadowsEnabled) { studio.shadowsEnabled = !studio.shadowsEnabled; buildRibbon() }
                    tool(g("ViewSelector", "Cam"), "Reset Cam") { synchronized(studio.lock) { studio.camera.pos = com.leaf.lemonade.model.Vector3(0f, 20f, 40f); studio.camera.yaw = 0f; studio.camera.pitch = -0.4f } }
                    tool(g("Fly"), "Fly x${fmtNum(studio.flySpeed)}") { SettingsDialog.pickFlySpeed(this) { buildRibbon() } }
                }
                group("Studio") { tool(g("Settings"), "Settings") { showSettings() } }
            }
            "Plugins" -> {
                group("Plugins") {
                    tool(g("ManagePlugins", "Mgr"), "Manage") { managePlugins() }
                    tool(g("Download"), "From Roblox") { AccountUi.installPluginFromRoblox(this, studio, this) }
                    tool(g("Open"), "From File") { pickPluginFile() }
                    tool(g("Toolbox"), "Store") { dock.setShown("toolbox", true); toolbox.showMarketplace(RobloxApi.Category.PLUGINS) }
                    tool(g("PluginsFolder", "Rld"), "Reload") { studio.plugins.loadAll(); OutputLog.info("Plugins reloaded") }
                }
                val byPlugin = studio.pluginButtons.groupBy { it.pluginName }
                for ((name, buttons) in byPlugin) {
                    // Each plugin's toolbar is its own group, named like Studio's (the toolbar name).
                    val caption = (buttons.firstOrNull()?.inst?.parent?.name?.takeIf { it.isNotBlank() && it != "Plugin" } ?: name).take(22)
                    group(caption) {
                        for (b in buttons) {
                            val label = (b.inst["Text"] as? String)?.ifEmpty { b.inst.name } ?: b.inst.name
                            tool(pluginIcon(b), label.take(14), b.inst["Active"] == true) { studio.clickPluginButton(b) }
                        }
                    }
                }
            }
        }
    }

    /** Home → Part: the part shapes, like Studio's Part dropdown. */
    private fun pickPartShape() {
        val shapes = listOf("Block" to 1, "Sphere" to 0, "Wedge" to 3, "Corner Wedge" to 4, "Cylinder" to 2)
        Dialogs.themed(this).setTitle("Part").setItems(shapes.map { it.first }.toTypedArray()) { _, i -> studio.insertPart(shapes[i].second); studio.markDirty() }.show()
    }

    /**
     * Studio's snap fields: a checkbox, a rounded field with the Rotate (degrees) or Move (studs)
     * increment typed right into the toolbar, and a small up/down spinner.
     */
    private fun snapGroup() = group("Snap to Grid") {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), 0, dp(4f), 0) }
        col.addView(snapRow("Move", studio.snapEnabled, studio.moveSnap, "studs", { studio.snapEnabled = it }) { studio.moveSnap = it.coerceIn(0.001f, 2048f) })
        col.addView(snapRow("Rotate", studio.rotSnapEnabled, studio.rotSnap, "°", { studio.rotSnapEnabled = it }) { studio.rotSnap = it.coerceIn(0.01f, 360f) })
        groupRow?.addView(col)
        tool(g("Collisions", "Col"), "Collisions", studio.collisionsEnabled) {
            studio.collisionsEnabled = !studio.collisionsEnabled
            toast(if (studio.collisionsEnabled) "Collisions on: moved parts stop at other parts" else "Collisions off")
            buildRibbon()
        }
    }

    private fun snapRow(icon: String, on: Boolean, value: Float, unit: String, toggle: (Boolean) -> Unit, set: (Float) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2f), 0, dp(2f)) }
        val box = android.widget.ImageView(this).apply {
            setImageDrawable(Ui.CheckBoxDrawable(on, resources.displayMetrics.density))
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { toggle(!on); buildRibbon() }
        }
        row.addView(box, Ui.lp(dp(24f), dp(24f)))
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(2f), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.INPUT); cornerRadius = dp(6f).toFloat() }
            alpha = if (on) 1f else 0.5f
        }
        pill.addView(android.widget.ImageView(this).apply { setImageDrawable(g(icon)) }, Ui.lp(dp(16f), dp(16f)))
        val field = Ui.input(this, fmtNum(value), "", numeric = true).apply {
            textSize = 12.5f
            background = null
            setPadding(dp(5f), dp(3f), dp(2f), dp(3f))
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            isEnabled = on
            fun commit() {
                val v = text.toString().replace(',', '.').trim().toFloatOrNull()
                if (v != null && v > 0f) set(v) else setText(fmtNum(value))
            }
            setOnEditorActionListener { v, _, _ ->
                commit(); v.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
            setOnFocusChangeListener { _, has -> if (!has) commit() }
        }
        pill.addView(field, Ui.lp(dp(40f), dp(28f)))
        pill.addView(Ui.text(this, unit, 12.5f, Ui.TEXT), Ui.lp(Ui.WRAP, Ui.WRAP))
        val spin = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        fun arrow(glyph: String, delta: Float) = Ui.text(this, glyph, 8f, Ui.TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
            setOnClickListener { if (on) { set((value + delta).coerceAtLeast(0.001f)); buildRibbon() } }
        }
        spin.addView(arrow("▲", 1f)); spin.addView(arrow("▼", -1f))
        pill.addView(spin)
        row.addView(pill, Ui.lp(Ui.WRAP, dp(30f)))
        return row
    }

    /** Plugin toolbar buttons can have their own icon asset (rbxassetid); fall back to the plugin icon. */
    private fun pluginIcon(b: com.leaf.lemonade.core.PluginButton): android.graphics.drawable.Drawable {
        val content = (b.inst["Icon"] ?: b.inst["Image"])?.toString()
        val bmp = com.leaf.lemonade.net.ImageLoader.get(content)
        return if (bmp != null) android.graphics.drawable.BitmapDrawable(resources, bmp) else inst("Plugin")
    }

    /** Runs a solid modeling operation; a returned message is shown (errors, or why it can't be done). */
    private fun solid(op: () -> String?) {
        openEditors.values.forEach { it.commit() }
        val msg = try { op() } catch (e: Exception) { "Couldn't do that: ${e.message ?: e}" }
        if (msg != null) toast(msg) else studio.markDirty()
    }

    private fun pickColorForSelection() {
        val parts = synchronized(studio.lock) { studio.selectedParts() }
        if (parts.isEmpty()) { toast("Select a part first"); return }
        val c0 = synchronized(studio.lock) { com.leaf.lemonade.model.PartData.color(parts[0]) }
        Dialogs.colorPicker(this, "Color", c0) { c ->
            synchronized(studio.lock) {
                studio.undo.begin("Color")
                for (p in parts) {
                    val key = if (p.props.containsKey("Color3uint8") || !p.props.containsKey("Color")) "Color3uint8" else "Color"
                    studio.setProp(p, key, c)
                    p.propTypes.putIfAbsent(key, com.leaf.lemonade.model.PropType.COLOR3U8)
                }
                studio.undo.commit()
            }
        }
    }

    private fun pickMaterialForSelection() {
        val parts = synchronized(studio.lock) { studio.selectedParts() }
        if (parts.isEmpty()) { toast("Select a part first"); return }
        val items = Enums.types["Material"]!!.entries.filter { it.key != "Air" && it.key != "Water" }
        Dialogs.themed(this).setTitle("Material").setItems(items.map { it.key }.toTypedArray()) { _, i ->
            synchronized(studio.lock) {
                studio.undo.begin("Material")
                for (p in parts) studio.setProp(p, "Material", EnumValue(items[i].value))
                studio.undo.commit()
            }
        }.show()
    }

    /** Sets the surface type of one face (or every face) of the selected parts. */
    private fun pickSurfaceForSelection() {
        val parts = synchronized(studio.lock) { studio.selectedParts() }
        if (parts.isEmpty()) { toast("Select a part first"); return }
        val faces = listOf("All faces" to null, "Top" to "TopSurface", "Bottom" to "BottomSurface", "Front" to "FrontSurface",
            "Back" to "BackSurface", "Left" to "LeftSurface", "Right" to "RightSurface")
        Dialogs.themed(this).setTitle("Surface — which face?").setItems(faces.map { it.first }.toTypedArray()) { _, fi ->
            val types = Enums.types["SurfaceType"]!!.entries.toList()
            Dialogs.themed(this).setTitle("${faces[fi].first}: surface type").setItems(types.map { it.key }.toTypedArray()) { _, ti ->
                val props = faces[fi].second?.let { listOf(it) } ?: faces.mapNotNull { it.second }
                synchronized(studio.lock) {
                    studio.undo.begin("Surface")
                    for (p in parts) for (prop in props) studio.setProp(p, prop, EnumValue(types[ti].value))
                    studio.undo.commit()
                }
            }.show()
        }.show()
    }

    // ------------------------------------------------------------------ play

    private fun togglePlay() {
        openEditors.values.forEach { it.commit() }
        if (studio.isPlaying) studio.stopPlay() else {
            showPlace()
            studio.startPlay()
        }
    }

    /** The mezzanine's Test dropdown: how to run the place. */
    private fun showTestMenu(anchor: View) {
        val m = StudioMenu(this, anchor)
        if (studio.isPlaying) m.add("Stop") { togglePlay() }
        else {
            m.add("Play") { togglePlay() }
            m.add("Play Here") { showPlace(); openEditors.values.forEach { e -> e.commit() }; studio.playHere() }
        }
        m.show()
    }

    override fun onPlayStateChanged() {
        playBtn.alpha = if (studio.isPlaying) 0.4f else 1f
        stopBtn.setImageDrawable(g(if (studio.isPlaying) "MezStopOn" else "MezStop"))
        if (studio.isPlaying && Settings.hideWindowsInPlaytest && hiddenBeforePlay == null) {
            hiddenBeforePlay = dock.allHidden
            setAllHidden(true)
        } else if (!studio.isPlaying) {
            hiddenBeforePlay?.let { setAllHidden(it) }
            hiddenBeforePlay = null
        }
        viewport.hud.onPlayStateChanged()
        buildRibbon()
        updateTitle()
    }

    override fun onToolChanged() {
        // Rebuilding the ribbon would drop the keyboard from a Snap to Grid field being typed in.
        if ((ribbonTab == "Home" || ribbonTab == "Model") && currentFocus !is EditText) buildRibbon()
        viewport.controls.invalidate()
    }
    override fun onPluginsChanged() { if (ribbonTab == "Plugins") buildRibbon(); viewport.controls.invalidate() }
    /** The Home tab's Lock button shows whether the selection is locked. */
    override fun onSelectionChanged() { if (ribbonTab == "Home" && currentFocus !is EditText) buildRibbon() }
    override fun onDirtyChanged() = updateTitle()

    override fun onModelReplaced() {
        for (s in editorOrder.toList()) {
            val r = s.root()
            if (r == null || (r !== studio.editModel && r !== studio.model)) closeScript(s)
        }
        // A place was opened or created (from the start page or the File menu).
        if (!studio.isPlaying) {
            (application as? StudioApp)?.placeOpened = true
            if (startScreen != null) closeStart()
        }
        updateTitle()
    }

    private fun updateTitle() {
        val c = studio.cloud
        val name = if (c != null) "${c.name}  (Roblox place)" else studio.fileName
        titleView.text = (if (studio.dirty) "● " else "") + name + if (studio.isPlaying) "  (Playing)" else ""
    }

    // ------------------------------------------------------------------ scripts

    private fun openScript(s: Instance) {
        if (!s.isA("LuaSourceContainer")) return
        val ed = openEditors.getOrPut(s) { editorOrder.add(s); ScriptEditor(this, studio, s) }
        activeScript = s
        editorHost.removeAllViews()
        editorHost.addView(ed, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        editorHost.visibility = View.VISIBLE
        rebuildScriptTabs()
    }

    private fun showPlace() {
        activeScript?.let { openEditors[it]?.commit() }
        activeScript = null
        editorHost.removeAllViews()
        editorHost.visibility = View.GONE
        rebuildScriptTabs()
    }

    private fun closeScript(s: Instance) {
        openEditors.remove(s)?.commit()
        editorOrder.remove(s)
        if (activeScript === s) { activeScript = null; editorHost.removeAllViews(); editorHost.visibility = View.GONE }
        rebuildScriptTabs()
    }

    private fun rebuildScriptTabs() {
        scriptTabs.removeAllViews()
        scriptTabsScroll.visibility = if (editorOrder.isEmpty()) View.GONE else View.VISIBLE
        if (editorOrder.isEmpty()) return
        fun tab(icon: android.graphics.drawable.Drawable, label: String, active: Boolean, onClick: () -> Unit, onClose: (() -> Unit)?) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (active) Ui.EDITOR_BG else Ui.TOPBAR)
                setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
                setOnClickListener { onClick() }
            }
            row.addView(android.widget.ImageView(this).apply { setImageDrawable(icon) }, Ui.lp(dp(16f), dp(16f)).apply { rightMargin = dp(6f) })
            row.addView(Ui.text(this, label, 12.5f, if (active) Ui.TEXT else Ui.DIM))
            if (onClose != null) row.addView(Ui.iconButton(this, Icons.ui(this, "CloseWidget", "x"), 26f) { onClose() })
            else row.addView(View(this), Ui.lp(dp(8f), dp(26f)))
            scriptTabs.addView(row)
            scriptTabs.addView(Ui.divider(this, true))
        }
        tab(g("Place"), studio.fileName.substringBeforeLast('.'), activeScript == null, { showPlace() }, null)
        for (s in editorOrder) tab(Ui.icon(this, s), s.name, s === activeScript, { openScript(s) }, { closeScript(s) })
    }

    // ------------------------------------------------------------------ files

    private fun showFileMenu(anchor: View) {
        val m = StudioMenu(this, anchor)
        val items = listOf<Pair<String, () -> Unit>>(
            "New" to { newBaseplate() },
            "Open from file…" to { openMode = OpenMode.PLACE; openLauncher.launch(arrayOf("*/*")) },
            "Open from Roblox…" to { AccountUi.myGames(this, studio, this) },
            "Close place" to { closePlace() },
            "Save to Roblox" to { AccountUi.saveToRobloxNow(this, studio, this) },
            "Save to Roblox as…" to { AccountUi.publishAs(this, studio, this, false) },
            "Publish to Roblox" to { AccountUi.publish(this, studio, this) },
            "Publish to Roblox as…" to { AccountUi.publishAs(this, studio, this, true) },
            "Exit" to { exitApp() },
        )
        val separatorBefore = setOf(3, 4, 8)
        items.forEachIndexed { i, (t, action) -> if (i in separatorBefore) m.separator(); m.add(t, true, action) }
        m.show()
    }

    private fun newBaseplate() = confirmDiscard {
        closeAllScripts()
        studio.newPlace()
        (application as? StudioApp)?.placeOpened = true
        closeStart()
    }

    private fun closePlace() = confirmDiscard {
        closeAllScripts()
        studio.newPlace()
        (application as? StudioApp)?.placeOpened = false
        showStart()
    }

    private fun exitApp() = confirmDiscard {
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun showSettings() = SettingsDialog.show(this, this)

    private fun about() = AboutDialog.show(this)

    private fun confirmDiscard(then: () -> Unit) {
        if (!studio.dirty) { then(); return }
        val c = studio.cloud
        Dialogs.themed(this).setTitle("Unsaved changes").setMessage(if (c != null) "Save changes to “${c.name}” on Roblox?" else "Save changes to ${studio.fileName}?")
            .setPositiveButton("Save") { _, _ -> if (c != null) save(then) else { save(); then() } }
            .setNegativeButton("Discard") { _, _ -> then() }
            .setNeutralButton("Cancel", null).show()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun onFilePicked(uri: Uri) {
        val mode = openMode
        val name = displayName(uri)
        if (mode == OpenMode.PLACE) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
        }
        toast("Loading $name…")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("Could not read file")
                when (mode) {
                    OpenMode.PLUGIN -> {
                        val f = studio.plugins.install(name, bytes)
                        runOnUiThread { studio.plugins.load(f); studio.notifyPluginButtons(); pluginsChanged(); toast("Installed ${f.nameWithoutExtension}") }
                    }
                    OpenMode.TOOLBOX -> {
                        val doc = RbxFile.read(bytes)
                        if (RbxFile.isPlace(doc)) throw Exception("This is a place file. Use File → Open to open places; the Toolbox holds models (.rbxm).")
                        val cls = doc.roots.firstOrNull()?.className
                        runOnUiThread { toolbox.saveToLibrary(name, RbxFile.unwrap(bytes), cls); dock.setShown("toolbox", true); toast("Added $name to Toolbox") }
                    }
                    OpenMode.PLACE -> {
                        val doc = RbxFile.read(bytes)
                        val isXml = !RbxFile.isBinary(RbxFile.unwrap(bytes))
                        doc.warnings.take(20).forEach { OutputLog.warn(it) }
                        if (doc.warnings.size > 20) OutputLog.warn("…and ${doc.warnings.size - 20} more warnings")
                        if (RbxFile.isPlace(doc)) {
                            val dm = PlaceFactory.fromDocument(doc)
                            val count = dm.descendants().size
                            runOnUiThread {
                                confirmDiscard {
                                    closeAllScripts()
                                    studio.replaceEditModel(dm, uri, name, isXml)
                                    Recents.add(this, Recents.Entry(name, uri = uri.toString()))
                                    OutputLog.info("Opened $name ($count instances)")
                                }
                            }
                        } else {
                            runOnUiThread {
                                studio.insertDocument(doc)
                                OutputLog.info("Inserted ${doc.roots.size} object(s) from $name")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                OutputLog.error("Failed to open $name: ${e.message}")
                runOnUiThread { Dialogs.themed(this).setTitle("Couldn't open file").setMessage("$name\n\n${e.message}").setPositiveButton("OK", null).show() }
            }
        }.start()
    }

    private fun closeAllScripts() { for (s in editorOrder.toList()) closeScript(s) }

    /**
     * Save: a place opened from Roblox is saved back to Roblox (a new saved version, like Studio),
     * a file is saved to the device.
     */
    private fun save(then: (() -> Unit)? = null) {
        openEditors.values.forEach { it.commit() }
        if (studio.isPlaying) { toast("Stop the playtest before saving"); return }
        if (studio.cloud != null) { AccountUi.saveToRoblox(this, studio, then); return }
        val uri = studio.fileUri
        if (uri == null || studio.fileIsXml || !studio.fileName.lowercase().endsWith(".rbxl")) { saveAs(); return }
        pendingSave = { studio.serializePlace() }
        pendingSaveIsPlace = true
        writeTo(uri)
    }

    private fun saveAs() {
        openEditors.values.forEach { it.commit() }
        pendingSave = { studio.serializePlace() }
        pendingSaveIsPlace = true
        val base = studio.fileName.substringBeforeLast('.')
        createLauncher.launch("$base.rbxl")
    }

    override fun exportSelection() {
        if (synchronized(studio.lock) { studio.selection.isEmpty() }) { toast("Select something to export"); return }
        pendingSave = { studio.serializeSelection() }
        pendingSaveIsPlace = false
        val n = synchronized(studio.lock) { studio.selection.first().name }
        createLauncher.launch("$n.rbxm")
    }

    override fun addSelectionToToolbox() {
        val bytes = studio.serializeSelection() ?: run { toast("Nothing selected"); return }
        val (n, cls) = synchronized(studio.lock) { studio.selection.first().let { it.name to it.className } }
        toolbox.saveToLibrary(n, bytes, cls)
        dock.setShown("toolbox", true)
        toast("Saved $n to Toolbox")
    }

    override fun importToToolbox() {
        openMode = OpenMode.TOOLBOX
        openLauncher.launch(arrayOf("*/*"))
    }

    override fun saveBytesAs(fileName: String, producer: () -> ByteArray?) {
        pendingSave = producer
        pendingSaveIsPlace = false
        createLauncher.launch(fileName)
    }

    override fun openPlaceBytes(name: String, bytes: ByteArray, cloud: CloudPlace?) {
        Thread {
            try {
                val doc = RbxFile.read(bytes)
                doc.warnings.take(10).forEach { OutputLog.warn(it) }
                val dm = PlaceFactory.fromDocument(doc)
                val count = dm.descendants().size
                runOnUiThread {
                    confirmDiscard {
                        closeAllScripts()
                        studio.replaceEditModel(dm, null, name, false, cloud)
                        OutputLog.info("Opened ${cloud?.name ?: name} from Roblox ($count instances)")
                        // The game's Avatar settings (R6/R15...) live on Roblox, not in the file.
                        cloud?.let { AvatarUi.syncFromCloud(this, studio, it) }
                        updateTitle()
                    }
                }
            } catch (e: Exception) {
                OutputLog.error("Failed to open $name: ${e.message}")
                runOnUiThread { Dialogs.themed(this).setTitle("Couldn't open place").setMessage(e.message).setPositiveButton("OK", null).show() }
            }
        }.start()
    }

    override fun pickPluginFile() {
        openMode = OpenMode.PLUGIN
        openLauncher.launch(arrayOf("*/*"))
    }

    override fun pluginsChanged() {
        ribbonTab = "Plugins"; buildTabs(); buildRibbon()
    }

    private fun writeTo(uri: Uri) {
        val producer = pendingSave ?: return
        val isPlace = pendingSaveIsPlace
        pendingSave = null
        val name = displayName(uri)
        Thread {
            try {
                val bytes = producer() ?: throw Exception("Nothing to save")
                val os = try { contentResolver.openOutputStream(uri, "wt") } catch (e: Exception) { contentResolver.openOutputStream(uri, "w") }
                (os ?: throw Exception("Could not open file for writing")).use { it.write(bytes) }
                runOnUiThread {
                    if (isPlace) {
                        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                        studio.markSaved(uri, name)
                        if (studio.cloud == null) Recents.add(this, Recents.Entry(name, uri = uri.toString()))
                        rebuildScriptTabs()
                    }
                    toast("Saved $name")
                    OutputLog.info("Saved $name (${bytes.size / 1024} KB)")
                }
            } catch (e: Exception) {
                OutputLog.error("Save failed: ${e.message}")
                runOnUiThread {
                    Dialogs.themed(this).setTitle("Save failed").setMessage("${e.message}" + if (isPlace) "\n\nTry File → Save As… to pick a new location." else "")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun managePlugins() {
        val files = studio.plugins.list()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(6f), dp(16f), 0) }
        var dlg: StudioDialog? = null
        if (files.isEmpty()) box.addView(Ui.text(this, "No plugins installed.\n\nInstall one from the Creator Store (Toolbox → Plugins), from a Roblox asset ID or link " +
            "(like an assetdelivery.roblox.com URL), or from a file — .rbxm, .rbxmx, a .bin downloaded from Roblox, or .lua.", 13f).apply { isSingleLine = false })
        for (f in files) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4f), 0, dp(4f)) }
            row.addView(android.widget.ImageView(this).apply { setImageDrawable(inst("Plugin")) }, Ui.lp(dp(20f), dp(20f)).apply { rightMargin = dp(8f) })
            row.addView(Ui.text(this, f.nameWithoutExtension, 14f), Ui.lp(0, Ui.WRAP, 1f))
            row.addView(StudioCheckBox(this, studio.plugins.isEnabled(f)) { on -> studio.plugins.setEnabled(f, on); studio.plugins.loadAll() }, Ui.lp(dp(24f), dp(24f)))
            row.addView(Ui.iconButton(this, g("Delete")) {
                Dialogs.confirm(this, "Remove plugin", "Uninstall ${f.nameWithoutExtension}?", "Remove") {
                    studio.plugins.remove(f); studio.plugins.loadAll(); buildRibbon(); dlg?.dismiss(); managePlugins()
                }
            })
            box.addView(row)
        }
        dlg = Dialogs.themed(this).setTitle("Plugins").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("From Roblox…") { _, _ -> AccountUi.installPluginFromRoblox(this, studio, this) }
            .setNeutralButton("From file…") { _, _ -> pickPluginFile() }
            .setNegativeButton("Close", null).show()
    }

    private fun toast(s: String) = StudioToast.show(this, s)

    // ------------------------------------------------------------------ hardware keyboard

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Ctrl/Shift + tap adds to the selection, like Ctrl/Shift + click in Studio.
        val mod = event.isCtrlPressed || event.isShiftPressed
        if (mod != studio.modifierHeld) { studio.modifierHeld = mod; viewport.controls.invalidate() }
        if (currentFocus is EditText) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val play = studio.isPlaying
        if (event.action == KeyEvent.ACTION_DOWN) {
            keysDown.add(code)
            if (event.isCtrlPressed) {
                when (code) {
                    KeyEvent.KEYCODE_S -> { if (event.isShiftPressed) saveAs() else save(); return true }
                    KeyEvent.KEYCODE_Z -> { studio.undo.undo(); return true }
                    KeyEvent.KEYCODE_Y -> { studio.undo.redo(); return true }
                    KeyEvent.KEYCODE_D -> { studio.duplicateSelection(); return true }
                    KeyEvent.KEYCODE_C -> { studio.copySelection(); return true }
                    KeyEvent.KEYCODE_X -> { studio.cutSelection(); return true }
                    KeyEvent.KEYCODE_V -> { studio.paste(null); return true }
                    KeyEvent.KEYCODE_G -> { if (event.isShiftPressed) studio.ungroupSelection() else studio.groupSelection(); return true }
                    KeyEvent.KEYCODE_O -> { openMode = OpenMode.PLACE; openLauncher.launch(arrayOf("*/*")); return true }
                    KeyEvent.KEYCODE_1 -> { studio.tool = Tool.SELECT; return true }
                    KeyEvent.KEYCODE_2 -> { studio.tool = Tool.MOVE; return true }
                    KeyEvent.KEYCODE_3 -> { studio.tool = Tool.SCALE; return true }
                    KeyEvent.KEYCODE_4 -> { studio.tool = Tool.ROTATE; return true }
                }
            }
            if (play && event.repeatCount == 0) {
                // Roblox player keys
                when (code) {
                    KeyEvent.KEYCODE_ESCAPE -> { viewport.hud.toggleMenu(); return true }
                    KeyEvent.KEYCODE_SLASH -> { viewport.hud.focusChat(); return true }
                    KeyEvent.KEYCODE_SHIFT_LEFT -> { synchronized(studio.lock) { studio.play?.toggleShiftLock() }; return true }
                    KeyEvent.KEYCODE_I -> { synchronized(studio.lock) { studio.play?.cameraZoom(1.25f) }; return true }
                    KeyEvent.KEYCODE_O -> { synchronized(studio.lock) { studio.play?.cameraZoom(0.8f) }; return true }
                    KeyEvent.KEYCODE_TAB -> { viewport.hud.togglePlayerList(); return true }
                }
                if (code in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    synchronized(studio.lock) { val p = studio.play; p?.backpackTools()?.getOrNull(code - KeyEvent.KEYCODE_1)?.let { p.toggleTool(it) } }
                    return true
                }
            }
            when (code) {
                KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> if (!play) { studio.deleteSelection(); return true }
                KeyEvent.KEYCODE_F5 -> { if (event.isShiftPressed) studio.stopPlay() else if (!play) togglePlay(); return true }
                KeyEvent.KEYCODE_F -> if (!play) { studio.focusSelection(); return true }
                KeyEvent.KEYCODE_SPACE -> if (play) { synchronized(studio.lock) { studio.play?.setJumpHeld(true) }; return true }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            keysDown.remove(code)
            if (code == KeyEvent.KEYCODE_SPACE && play) { synchronized(studio.lock) { studio.play?.setJumpHeld(false) }; return true }
        }
        if (play && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            studio.play?.keyTurn = (if (KeyEvent.KEYCODE_DPAD_LEFT in keysDown) 1f else 0f) - (if (KeyEvent.KEYCODE_DPAD_RIGHT in keysDown) 1f else 0f)
            return true
        }
        val handled = code in setOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (handled && !event.isCtrlPressed) {
            fun k(c: Int) = if (c in keysDown) 1f else 0f
            studio.moveX = k(KeyEvent.KEYCODE_D) - k(KeyEvent.KEYCODE_A)
            studio.moveY = (k(KeyEvent.KEYCODE_S) + k(KeyEvent.KEYCODE_DPAD_DOWN)).coerceAtMost(1f) - (k(KeyEvent.KEYCODE_W) + k(KeyEvent.KEYCODE_DPAD_UP)).coerceAtMost(1f)
            studio.flyUp = KeyEvent.KEYCODE_E in keysDown
            studio.flyDown = KeyEvent.KEYCODE_Q in keysDown
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
