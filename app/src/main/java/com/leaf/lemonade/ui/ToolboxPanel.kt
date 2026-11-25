package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.format.RbxDocument
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.*
import com.leaf.lemonade.net.AuthRequiredException
import com.leaf.lemonade.net.ImageLoader
import com.leaf.lemonade.net.RobloxApi
import java.io.File
import java.util.concurrent.Executors

@SuppressLint("ViewConstructor")
class ToolboxPanel(ctx: Context, private val studio: Studio, private val actions: MainActions) : LinearLayout(ctx) {
    private val body = FrameLayout(ctx)
    private var tab = 0
    private val tabViews = ArrayList<TextView>()
    private val tabNames = listOf("Creator Store", "Inventory", "Parts", "Objects")
    private var audioBrowser: AudioBrowser? = null
    private var gridHost: View? = null
    private var searchBox: android.widget.EditText? = null

    val libraryDir: File get() = File(context.filesDir, "toolbox").also { it.mkdirs() }

    // marketplace state
    private val net = Executors.newSingleThreadExecutor()
    private val items = ArrayList<RobloxApi.Item>()
    private var cursor: String? = null
    private var loading = false
    private var endReached = false
    private var query = ""
    private var category = RobloxApi.Category.MODELS
    private var searchGen = 0
    private var market: View? = null
    private val adapter = MarketAdapter()
    private lateinit var status: TextView
    private lateinit var progress: StudioSpinner
    private val thumbUrls = HashMap<Long, String>()

    private val meta by lazy { context.getSharedPreferences("toolbox_meta", Context.MODE_PRIVATE) }
    private var dropHighlight = false
    // Declared before init: the market UI is built (and assigns these) during init.
    private var restyleCats: (() -> Unit)? = null
    private var priceRow: View? = null
    private var price: RobloxApi.Price = RobloxApi.Price.ANY
    /** Image views waiting for an ImageLoader key (inventory thumbnails, details pictures). */
    private val invThumbViews = HashMap<String, MutableList<ImageView>>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.PANEL)
        addView(Ui.header(ctx, "Toolbox", null), Ui.lp(Ui.MATCH, Ui.WRAP))
        // Drop target: drag objects here from the Explorer to keep them in your inventory.
        setOnDragListener { _, e ->
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> e.localState is ExplorerPanel.DragPayload
                android.view.DragEvent.ACTION_DRAG_ENTERED -> { dropHighlight = true; foreground = android.graphics.drawable.GradientDrawable().apply { setColor(0x333B8BEB); setStroke(Ui.dp(ctx, 2f), Ui.ACCENT) }; true }
                android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> { dropHighlight = false; foreground = null; true }
                android.view.DragEvent.ACTION_DROP -> {
                    foreground = null
                    val payload = e.localState as? ExplorerPanel.DragPayload ?: return@setOnDragListener false
                    val bytes = studio.serializeInstances(payload.items)
                    if (bytes == null) { studio.toast("Those objects can't be saved"); false }
                    else {
                        val first = payload.items.first()
                        val name = if (payload.items.size > 1) "${first.name} (+${payload.items.size - 1})" else first.name
                        saveToLibrary(name, bytes, first.className)
                        studio.toast("Saved $name to your Toolbox inventory")
                        true
                    }
                }
                else -> true
            }
        }
        val tabs = LinearLayout(ctx).apply { orientation = HORIZONTAL; setBackgroundColor(Ui.HEADER) }
        for ((i, name) in tabNames.withIndex()) {
            val t = Ui.text(ctx, name, 12f).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 7f), Ui.dp(ctx, 10f), Ui.dp(ctx, 7f))
                setOnClickListener { tab = i; refresh() }
            }
            tabViews.add(t)
            tabs.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Ui.HEADER); addView(tabs) }, Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(body, Ui.lp(Ui.MATCH, 0, 1f))
        ImageLoader.listeners.add { key ->
            post {
                if (key.startsWith("url:")) adapter.notifyDataSetChanged()
                invThumbViews.remove(key)?.let { views -> ImageLoader.peekKey(key)?.let { b -> views.forEach { it.setImageBitmap(b) } } }
            }
        }
        refresh()
    }

    fun refresh() {
        tabViews.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) Ui.TEXT else Ui.DIM)
            t.background = if (i == tab) GradientDrawable().apply { setColor(Ui.HOVER); cornerRadius = Ui.dp(context, 6f).toFloat() } else null
        }
        body.removeAllViews()
        when (tab) {
            0 -> body.addView(market ?: buildMarket().also { market = it })
            1 -> body.addView(scroll { myModels(it) })
            2 -> body.addView(scroll { parts(it) })
            else -> body.addView(scroll { objects(it) })
        }
    }

    private fun scroll(fill: (LinearLayout) -> Unit): View {
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        fill(content)
        return ScrollView(context).apply { addView(content) }
    }

    // ------------------------------------------------------------------ Marketplace (Creator Store)

    private fun buildMarket(): View {
        val ctx = context
        price = try { RobloxApi.Price.valueOf(meta.getString("priceFilter", "ANY") ?: "ANY") } catch (_: Exception) { RobloxApi.Price.ANY }
        val root = LinearLayout(ctx).apply { orientation = VERTICAL }
        val bar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 6f), Ui.dp(ctx, 6f), Ui.dp(ctx, 2f)) }
        val search = Ui.input(ctx, "", "Search the Creator Store").apply {
            textSize = 13f
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            inputType = InputType.TYPE_CLASS_TEXT
        }
        searchBox = search
        fun go() {
            query = search.text.toString()
            if (category == RobloxApi.Category.AUDIO) audioBrowser?.search(query) else newSearch()
        }
        search.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { go(); true } else false
        }
        bar.addView(search, Ui.lp(0, Ui.WRAP, 1f))
        bar.addView(Ui.iconButton(ctx, Icons.general(ctx, "FindAll", "Go"), 34f) { go() })
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        val cats = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 2f), Ui.dp(ctx, 6f), Ui.dp(ctx, 4f)) }
        val catViews = ArrayList<TextView>()
        fun styleCats() = catViews.forEachIndexed { i, v ->
            val on = RobloxApi.Category.values()[i] == category
            v.background = GradientDrawable().apply { setColor(if (on) Ui.ACCENT else Ui.HOVER); cornerRadius = Ui.dp(ctx, 12f).toFloat() }
            v.setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.TEXT)
        }
        for (c in RobloxApi.Category.values()) {
            val v = Ui.text(ctx, if (c == RobloxApi.Category.DECALS) "Images" else c.label, 12f).apply {
                setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 4f), Ui.dp(ctx, 12f), Ui.dp(ctx, 4f))
                setOnClickListener { category = c; styleCats(); showCategory() }
            }
            catViews.add(v)
            cats.addView(v, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(ctx, 6f) })
        }
        styleCats()
        restyleCats = { styleCats() }
        val catRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        catRow.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(cats) }, Ui.lp(0, Ui.WRAP, 1f))
        catRow.addView(Ui.iconButton(ctx, Icons.general(ctx, "Account", "Acc"), 28f) { AccountUi.show(context as android.app.Activity, studio, actions) })
        root.addView(catRow, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Price filter: paid Creator Store assets can only be inserted after buying them on Roblox.
        val priceRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 6f), Ui.dp(ctx, 4f)) }
        val priceViews = ArrayList<TextView>()
        fun stylePrice() = priceViews.forEachIndexed { i, v ->
            val on = RobloxApi.Price.values()[i] == price
            v.background = GradientDrawable().apply { setColor(if (on) Ui.ACCENT else 0); setStroke(Ui.dp(ctx, 1f), if (on) Ui.ACCENT else Ui.BORDER); cornerRadius = Ui.dp(ctx, 10f).toFloat() }
            v.setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.DIM)
        }
        priceRow.addView(Ui.text(ctx, "Price:", 11f, Ui.DIM), Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(ctx, 6f) })
        for (p in RobloxApi.Price.values()) {
            val v = Ui.text(ctx, p.label, 11f).apply {
                setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 3f), Ui.dp(ctx, 10f), Ui.dp(ctx, 3f))
                setOnClickListener { price = p; meta.edit().putString("priceFilter", p.name).apply(); stylePrice(); if (category != RobloxApi.Category.AUDIO) newSearch() }
            }
            priceViews.add(v)
            priceRow.addView(v, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(ctx, 5f) })
        }
        stylePrice()
        this.priceRow = priceRow
        root.addView(priceRow, Ui.lp(Ui.MATCH, Ui.WRAP))

        val listHost = FrameLayout(ctx)
        val rv = RecyclerView(ctx)
        val glm = GridLayoutManager(ctx, 2)
        rv.layoutManager = glm
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (glm.findLastVisibleItemPosition() >= items.size - 6) loadMore()
            }
        })
        rv.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) glm.spanCount = ((r - l) / Ui.dp(ctx, 112f)).coerceIn(1, 6)
        }
        listHost.addView(rv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        progress = StudioSpinner(ctx).apply { visibility = GONE }
        listHost.addView(progress, FrameLayout.LayoutParams(Ui.dp(ctx, 36f), Ui.dp(ctx, 36f), Gravity.CENTER))
        status = Ui.text(ctx, "", 12f, Ui.DIM).apply { isSingleLine = false; gravity = Gravity.CENTER; setPadding(Ui.dp(ctx, 16f), 0, Ui.dp(ctx, 16f), 0) }
        listHost.addView(status, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER))
        val stack = FrameLayout(ctx)
        stack.addView(listHost, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        gridHost = listHost
        val act = ctx as android.app.Activity
        val ab = AudioBrowser(ctx, { item -> insertAudio(item) }) { then ->
            if (RobloxApi.hasCredentials(ctx)) then() else AccountUi.signIn(act) { then() }
        }
        ab.visibility = GONE
        audioBrowser = ab
        stack.addView(ab, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(stack, Ui.lp(Ui.MATCH, 0, 1f))
        showCategory()
        return root
    }

    /** Audio gets Studio's audio browser (Discover, filters, previews); other categories the grid. */
    private fun showCategory() {
        val audio = category == RobloxApi.Category.AUDIO
        audioBrowser?.visibility = if (audio) VISIBLE else GONE
        gridHost?.visibility = if (audio) GONE else VISIBLE
        priceRow?.visibility = if (audio) GONE else VISIBLE
        searchBox?.hint = if (audio) "Search audio" else "Search the Creator Store"
        if (audio) { if (query.isNotBlank()) audioBrowser?.search(query) } else { com.leaf.lemonade.audio.AudioEngine.stopPreview(); newSearch() }
    }


    /** Switches to the Creator Store tab showing [cat] (e.g. Plugins from the Plugins ribbon). */
    fun showMarketplace(cat: RobloxApi.Category) {
        tab = 0
        refresh()
        if (category != cat) { category = cat; restyleCats?.invoke(); showCategory() }
    }

    private fun insertAudio(item: RobloxApi.AudioItem) {
        insertSound(RobloxApi.Item(item.id, item.title.ifBlank { item.name }, item.creator, item.verified, "", 0, 0, false, false))
    }

    private fun newSearch() {
        searchGen++
        items.clear(); cursor = null; endReached = false; loading = false
        adapter.notifyDataSetChanged()
        loadMore()
    }

    private fun loadMore() {
        if (loading || endReached) return
        loading = true
        val gen = searchGen
        val q = query; val cat = category; val cur = cursor; val pr = price
        progress.visibility = if (items.isEmpty()) VISIBLE else GONE
        status.text = ""
        net.execute {
            try {
                val page = try { RobloxApi.searchPriced(q, cat, pr, cur) } catch (e: Exception) {
                    // The price-filtered search is newer; fall back to the plain one (filtered below).
                    if (pr == RobloxApi.Price.ANY) throw e else RobloxApi.search(q, cat, cur)
                }
                val details = page.ids.chunked(30).flatMap { RobloxApi.details(it) }.filter {
                    when (pr) { RobloxApi.Price.FREE -> !it.isPaid; RobloxApi.Price.PAID -> it.isPaid; else -> true }
                }
                val thumbs = try { page.ids.chunked(50).flatMap { RobloxApi.thumbnailUrls(it, "150x150").entries }.associate { it.key to it.value } } catch (_: Exception) { emptyMap() }
                post {
                    if (gen != searchGen) return@post
                    thumbUrls.putAll(thumbs.filterValues { it.isNotEmpty() })
                    val start = items.size
                    items.addAll(details)
                    adapter.notifyItemRangeInserted(start, details.size)
                    cursor = page.nextCursor
                    endReached = page.nextCursor == null || page.ids.isEmpty()
                    loading = false
                    progress.visibility = GONE
                    if (items.isEmpty()) status.text = if (q.isBlank()) "Nothing here yet." else "No results for \"$q\"."
                }
            } catch (e: Exception) {
                post {
                    if (gen != searchGen) return@post
                    loading = false
                    progress.visibility = GONE
                    if (items.isEmpty()) status.text = "Couldn't reach the Creator Store.\n${e.message ?: ""}\nCheck your internet connection."
                }
            }
        }
    }

    private fun insertAsset(item: RobloxApi.Item, cat: RobloxApi.Category = category, ownedCheck: Boolean = false) {
        val ctx = context
        val act = ctx as android.app.Activity
        when (cat) {
            RobloxApi.Category.DECALS -> { insertDecal(item); return }
            RobloxApi.Category.AUDIO -> { insertSound(item); return }
            RobloxApi.Category.MESHES -> { insertMesh(item); return }
            RobloxApi.Category.PLUGINS -> {
                if (item.isPaid && !ownedCheck) { paidDialog(item, cat); return }
                Dialogs.confirm(ctx, "Install plugin", "Install “${item.name}” by ${item.creator}?" + if (item.hasScripts) "\n\nPlugins run code in Studio; only install ones you trust." else "", "Install") {
                    if (!RobloxApi.hasCredentials(ctx)) AccountUi.signIn(act) { AccountUi.installPluginId(act, studio, actions, item.id, item.name) }
                    else AccountUi.installPluginId(act, studio, actions, item.id, item.name)
                }
                return
            }
            else -> {}
        }
        if (!RobloxApi.hasCredentials(ctx)) { AccountUi.signIn(act) { insertAsset(item, cat, ownedCheck) }; return }
        // Paid Creator Store assets only download for accounts that bought them.
        if (item.isPaid && !ownedCheck) { paidDialog(item, cat); return }
        studio.toast("Inserting ${item.name}…")
        net.execute {
            try {
                val bytes = RobloxApi.downloadAsset(ctx, item.id)
                val doc = RbxFile.read(bytes)
                if (RbxFile.isPlace(doc)) throw Exception("This asset is a place, not a model")
                if (item.hasScripts) OutputLog.warn("${item.name} contains scripts. Check them before playtesting, like you would in Studio.")
                studio.main.post { insertDownloaded(item, bytes, doc) }
            } catch (e: AuthRequiredException) {
                // Already signed in and still refused: almost always a paid (or private) asset,
                // and signing in again wouldn't help.
                studio.main.post {
                    if (item.isPaid) paidDialog(item, cat, refused = true)
                    else { studio.toast(e.message ?: "Sign in needed"); AccountUi.signIn(act) { insertAsset(item, cat) } }
                }
            } catch (e: com.leaf.lemonade.net.HttpException) {
                if (e.code == 403) studio.main.post { paidDialog(item, cat, refused = true) }
                else if (e.code == 401) studio.main.post { AccountUi.signIn(act) { insertAsset(item, cat, ownedCheck) } }
                else { OutputLog.error("Couldn't insert ${item.name}: ${e.message}"); studio.toast("Insert failed: ${e.message}") }
            } catch (e: Exception) {
                OutputLog.error("Couldn't insert ${item.name}: ${e.message}")
                studio.toast("Insert failed: ${e.message}")
            }
        }
    }

    private fun openInBrowser(url: String) {
        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (_: Exception) { studio.toast("No browser found to open $url") }
    }

    /**
     * A paid asset (or one Roblox won't let this account download): explain instead of looping
     * through sign-in, with a link to buy it on the Creator Store.
     */
    private fun paidDialog(item: RobloxApi.Item, cat: RobloxApi.Category, refused: Boolean = false) {
        val msg = buildString {
            if (item.isPaid) append("“${item.name}” costs ${item.priceText} on the Creator Store, so it can't be loaded until it's bought on Roblox with the account you're signed in with.")
            else append("Roblox didn't let your account download “${item.name}”. It may be a paid or private asset.")
            if (refused) append("\n\nRoblox refused the download for the signed-in account.")
            append("\n\nAfter buying it, tap Insert again (or find it in Inventory → My Roblox items).")
        }
        Dialogs.themed(context)
            .setTitle(if (item.isPaid) "Can't load paid model" else "Can't load this asset")
            .setMessage(msg)
            .setPositiveButton(if (item.isPaid) "Buy on Roblox (${item.priceText})" else "Open on Roblox") { _, _ -> openInBrowser(RobloxApi.storeUrl(item.id)) }
            .setNeutralButton(if (refused) "Close" else "I own it, insert") { _, _ -> if (!refused) insertAsset(item, cat, ownedCheck = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Inserts a downloaded model and keeps a copy under "Recently inserted". */
    private fun insertDownloaded(item: RobloxApi.Item, bytes: ByteArray, doc: RbxDocument) {
        studio.insertDocument(doc)
        OutputLog.info("Inserted ${item.name} (${item.id}) from Roblox")
        try {
            File(libraryDir, "recent").mkdirs()
            val rf = File(File(libraryDir, "recent"), safeName(item.name) + ".rbxm")
            rf.writeBytes(RbxFile.unwrap(bytes))
            doc.roots.firstOrNull()?.let { meta.edit().putString("recent/" + rf.name, it.className).apply() }
        } catch (_: Exception) {}
    }

    /** Decals don't need a download: they're referenced by id, like Studio does. */
    private fun insertDecal(item: RobloxApi.Item) {
        synchronized(studio.lock) {
            val target = studio.selection.firstOrNull { it.isA("BasePart") }
            val parent = target ?: run {
                val p = studio.insertObject("Part")
                p.set("size", Vector3(6f, 6f, 0.4f)); p.set("Anchored", true)
                p
            }
            val dcl = InstanceFactory.create("Decal")
            dcl.name = item.name.take(40).ifBlank { "Decal" }
            dcl.set("Texture", "rbxassetid://${item.id}")
            dcl.set("Face", EnumValue(5))
            studio.undo.begin("Insert decal")
            studio.reparent(dcl, parent)
            studio.undo.commit()
        }
        studio.toast("Added decal ${item.name}")
    }

    /** Audio is referenced by id: a Sound goes in the selected object (or Workspace). */
    private fun insertSound(item: RobloxApi.Item) {
        synchronized(studio.lock) {
            val s = InstanceFactory.create("Sound")
            s.name = item.name.take(40).ifBlank { "Sound" }
            s.set("SoundId", "rbxassetid://${item.id}")
            val parent = studio.selection.firstOrNull()?.takeIf { !it.isService || it.className == "Workspace" || it.className == "SoundService" } ?: studio.model.workspace
            studio.undo.begin("Insert sound")
            studio.reparent(s, parent)
            studio.undo.commit()
            studio.selection.clear(); studio.selection.add(s)
        }
        studio.listeners.forEach { it.onSelectionChanged() }
        studio.toast("Added sound ${item.name}")
    }

    /**
     * Creator Store "Meshes" are MeshPart assets: a model file holding a MeshPart that points at
     * the real mesh. They're inserted like models (the old code used the asset id itself as the
     * MeshId, which isn't a mesh, so it drew as a block). A raw Mesh asset id becomes a MeshPart
     * sized to the mesh, like Studio.
     */
    private fun insertMesh(item: RobloxApi.Item) {
        val ctx = context
        val act = ctx as android.app.Activity
        if (!RobloxApi.hasCredentials(ctx)) { AccountUi.signIn(act) { insertMesh(item) }; return }
        studio.toast("Inserting ${item.name}…")
        net.execute {
            try {
                val bytes = com.leaf.lemonade.net.AssetFetcher.load(item.id)
                val doc = try { RbxFile.read(bytes).takeIf { it.roots.isNotEmpty() } } catch (_: Exception) { null }
                if (doc != null) { studio.main.post { insertDownloaded(item, bytes, doc) }; return@execute }
                val m = com.leaf.lemonade.render.RbxMesh.parse(bytes)
                val s = Vector3(m.size(0), m.size(1), m.size(2)).let { if (it.x > 0.01f && it.y > 0.01f && it.z > 0.01f) it else Vector3(4f, 4f, 4f) }
                studio.main.post {
                    val p = studio.insertObject("MeshPart")
                    synchronized(studio.lock) {
                        val old = PartData.size(p)
                        p.name = item.name.take(40).ifBlank { "MeshPart" }
                        p.set("MeshId", "rbxassetid://${item.id}")
                        p.set("size", s); p.set("InitialSize", s, false)
                        p.set("Anchored", true)
                        // Keep it resting on whatever it was placed on.
                        p.set("CFrame", PartData.cframe(p) + Vector3(0f, (s.y - old.y) / 2f, 0f))
                    }
                    OutputLog.info("Inserted mesh ${item.name} (${item.id})")
                }
            } catch (e: AuthRequiredException) {
                studio.main.post { studio.toast(e.message ?: "Sign in needed"); AccountUi.signIn(act) { insertMesh(item) } }
            } catch (e: Exception) {
                OutputLog.error("Couldn't insert mesh ${item.name}: ${e.message ?: e}")
                studio.toast("Insert failed: ${e.message}")
            }
        }
    }

    /** Shows a picture for an ImageLoader key now or when it finishes loading. */
    private fun bindImage(v: ImageView, key: String) {
        val b = ImageLoader.getKey(key)
        if (b != null) v.setImageBitmap(b) else invThumbViews.getOrPut(key) { ArrayList() }.add(v)
    }

    private fun fmtDate(iso: String): String = iso.take(10).ifBlank { "—" }

    private fun fmtCount(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
        n >= 1000 -> "%.1fK".format(n / 1000f)
        else -> n.toString()
    }

    /**
     * Studio's asset details page: big thumbnail and screenshots, creator, price, ratings,
     * description, what's inside (scripts, meshes, triangles...), dates, and Insert / Open in
     * browser. Roblox has no public written-review API, so "reviews" are the like/dislike ratings,
     * and the browser button opens the full Creator Store page.
     */
    private fun showDetails(item: RobloxApi.Item, cat: RobloxApi.Category = category) {
        val ctx = context
        val box = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 8f), Ui.dp(ctx, 16f), Ui.dp(ctx, 8f)) }
        val pic = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Ui.blend(Ui.PANEL, 0xFF000000.toInt(), 0.1f)) }
        box.addView(pic, Ui.lp(Ui.MATCH, Ui.dp(ctx, 200f)))
        bindImage(pic, "asset:${item.id}")
        // Screenshots the creator added (v2 details), loaded in the background.
        val shots = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(shots) }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 4f) })
        net.execute {
            val ids = RobloxApi.previewImages(item.id)
            if (ids.isNotEmpty()) post {
                for (id in ids.take(8)) {
                    val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setOnClickListener { bindImage(pic, "asset:$id") } }
                    shots.addView(iv, Ui.lp(Ui.dp(ctx, 64f), Ui.dp(ctx, 64f)).apply { rightMargin = Ui.dp(ctx, 4f) })
                    bindImage(iv, "asset:$id")
                }
            }
        }
        fun line(t: String, size: Float = 12f, color: Int = Ui.TEXT, bold: Boolean = false) =
            box.addView(Ui.text(ctx, t, size, color, bold).apply { isSingleLine = false; setPadding(0, Ui.dp(ctx, 3f), 0, Ui.dp(ctx, 1f)) })
        line(item.name, 16f, Ui.TEXT, true)
        line("By ${item.creator}" + (if (item.verified) "  ✔ verified" else "") + (if (item.creatorIsGroup) " (group)" else ""), 12f, if (item.verified) Ui.INFO else Ui.DIM)
        line(if (item.isPaid) "Price: ${item.priceText} (buy it on Roblox to use it)" else "Price: Free", 13f, if (item.isPaid) 0xFFFFB74D.toInt() else 0xFF7CD67C.toInt(), true)
        // Ratings ("reviews"): like percentage bar and counts.
        val votes = item.upVotes + item.downVotes
        line("Ratings", 11f, Ui.DIM, true)
        if (votes > 0) {
            val pct = item.likePercent
            val bar = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            bar.addView(View(ctx).apply { setBackgroundColor(0xFF4CAF50.toInt()) }, Ui.lp(0, Ui.dp(ctx, 8f), pct.toFloat().coerceAtLeast(0.01f)))
            bar.addView(View(ctx).apply { setBackgroundColor(0xFFE05555.toInt()) }, Ui.lp(0, Ui.dp(ctx, 8f), (100 - pct).toFloat().coerceAtLeast(0.01f)))
            box.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 2f) })
            line("$pct% liked  ·  👍 ${fmtCount(item.upVotes)}  👎 ${fmtCount(item.downVotes)}  ·  ${fmtCount(votes)} ratings")
        } else line("No ratings yet", 12f, Ui.DIM)
        if (item.endorsed) line("★ Endorsed by Roblox", 12f, 0xFFFFD54F.toInt())
        if (item.description.isNotBlank()) { line("Description", 11f, Ui.DIM, true); line(item.description.take(2000)) }
        line("Details", 11f, Ui.DIM, true)
        val facts = ArrayList<String>()
        if (item.hasScripts) facts.add("⚠ Contains ${if (item.scriptCount > 0) "${item.scriptCount} script" + (if (item.scriptCount == 1) "" else "s") else "scripts"}") else facts.add("No scripts")
        if (item.meshParts > 0) facts.add("${item.meshParts} MeshParts")
        if (item.triangles > 0) facts.add("${fmtCount(item.triangles)} triangles")
        if (item.animations > 0) facts.add("${item.animations} animations")
        if (item.sounds > 0) facts.add("${item.sounds} sounds")
        line(facts.joinToString("  ·  "))
        if (item.category.isNotBlank()) line("Category: " + item.category.split("__").joinToString(" › ") { it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }, 12f, Ui.DIM)
        line("Created ${fmtDate(item.created)}  ·  Updated ${fmtDate(item.updated)}", 12f, Ui.DIM)
        line("Asset ID ${item.id}", 12f, Ui.DIM)
        val scroll = ScrollView(ctx).apply { addView(box) }
        val insertLabel = when {
            item.isPaid -> "Buy (${item.priceText})"
            cat == RobloxApi.Category.PLUGINS -> "Install"
            else -> "Insert"
        }
        Dialogs.themed(ctx)
            .setView(scroll)
            .setPositiveButton(insertLabel) { _, _ -> if (item.isPaid) paidDialog(item, cat) else insertAsset(item, cat) }
            .setNeutralButton("Open in browser") { _, _ -> openInBrowser(RobloxApi.storeUrl(item.id)) }
            .setNegativeButton("Close", null).show()
    }

    private inner class VH(val root: LinearLayout, val img: ImageView, val name: TextView, val by: TextView, val badge: TextView, val info: View) : RecyclerView.ViewHolder(root)

    private inner class MarketAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = VERTICAL
                setPadding(Ui.dp(ctx, 4f), Ui.dp(ctx, 4f), Ui.dp(ctx, 4f), Ui.dp(ctx, 6f))
                background = Ui.rippleBg(ctx, Ui.CARD, 4f)
                layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(ctx, 3f), Ui.dp(ctx, 3f), Ui.dp(ctx, 3f), Ui.dp(ctx, 3f)) }
            }
            val img = object : ImageView(ctx) {
                override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, w) }
            }.apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Ui.blend(Ui.PANEL, 0xFF000000.toInt(), 0.08f)) }
            // Thumbnail with a price tag (paid assets) and an info button (details page).
            val frame = FrameLayout(ctx)
            frame.addView(img, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            val badge = Ui.text(ctx, "", 10f, 0xFF1B1B1B.toInt(), true).apply {
                setPadding(Ui.dp(ctx, 5f), Ui.dp(ctx, 1f), Ui.dp(ctx, 5f), Ui.dp(ctx, 1f))
                background = GradientDrawable().apply { setColor(0xFFFFB74D.toInt()); cornerRadius = Ui.dp(ctx, 4f).toFloat() }
            }
            frame.addView(badge, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Ui.dp(ctx, 3f), Ui.dp(ctx, 3f), 0, 0) })
            val info = Ui.text(ctx, "ⓘ", 15f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
                contentDescription = "Details"
            }
            frame.addView(info, FrameLayout.LayoutParams(Ui.dp(ctx, 26f), Ui.dp(ctx, 26f), Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.dp(ctx, 2f), Ui.dp(ctx, 2f), 0) })
            root.addView(frame, Ui.lp(Ui.MATCH, Ui.WRAP))
            val name = Ui.text(ctx, "", 12f, Ui.TEXT).apply { maxLines = 2; isSingleLine = false; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, Ui.dp(ctx, 4f), 0, 0) }
            val by = Ui.text(ctx, "", 10f, Ui.DIM).apply { ellipsize = android.text.TextUtils.TruncateAt.END }
            root.addView(name); root.addView(by)
            return VH(root, img, name, by, badge, info)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            h.name.text = it.name
            val pct = it.likePercent
            h.by.text = it.creator + if (pct >= 0) "  ·  👍 $pct%" else ""
            h.by.setTextColor(if (it.verified) Ui.INFO else Ui.DIM)
            h.badge.visibility = if (it.isPaid) VISIBLE else GONE
            h.badge.text = it.priceText
            h.img.alpha = if (it.isPaid) 0.75f else 1f
            val url = thumbUrls[it.id]
            val bmp = url?.let { u -> ImageLoader.getKey("url:$u") }
            if (bmp != null) h.img.setImageBitmap(bmp) else h.img.setImageDrawable(null)
            h.root.setOnClickListener { _ -> if (it.isPaid) paidDialog(it, category) else insertAsset(it) }
            h.root.setOnLongClickListener { _ -> showDetails(it); true }
            h.info.setOnClickListener { _ -> showDetails(it) }
        }
    }

    // ------------------------------------------------------------------ Parts / Objects / Inventory

    private fun grid(into: LinearLayout, cells: List<Triple<android.graphics.drawable.Drawable, String, () -> Unit>>) {
        val g = GridLayout(context).apply { columnCount = 3; setPadding(Ui.dp(context, 4f), Ui.dp(context, 4f), Ui.dp(context, 4f), 0) }
        val w = Ui.dp(context, 74f)
        for ((icon, label, fn) in cells) {
            val cell = Ui.toolButton(context, icon, label) { fn() }
            cell.background = Ui.rippleBg(context, Ui.HOVER)
            (cell.getChildAt(1) as TextView).apply { textSize = 10f; ellipsize = android.text.TextUtils.TruncateAt.END }
            g.addView(cell, GridLayout.LayoutParams().apply { width = w; height = Ui.dp(context, 62f); setMargins(Ui.dp(context, 3f), Ui.dp(context, 3f), Ui.dp(context, 3f), Ui.dp(context, 3f)) })
        }
        into.addView(g)
    }

    private fun section(into: LinearLayout, title: String) {
        into.addView(Ui.text(context, title, 11f, Ui.DIM, true).apply { setPadding(Ui.dp(context, 10f), Ui.dp(context, 10f), 0, Ui.dp(context, 2f)) })
    }

    private fun parts(into: LinearLayout) {
        val c = context
        section(into, "PARTS")
        grid(into, listOf(
            Triple(Icons.general(c, "Part_Block", "Block"), "Block") { studio.insertPart(1) },
            Triple(Icons.general(c, "Part_Sphere", "Sphere"), "Sphere") { studio.insertPart(0) },
            Triple(Icons.general(c, "Part_Cylinder", "Cylinder"), "Cylinder") { studio.insertPart(2) },
            Triple(Icons.general(c, "Part_Wedge", "Wedge"), "Wedge") { studio.insertPart(3) },
            Triple(Icons.general(c, "Part_CornerWedge", "Corner"), "Corner") { studio.insertPart(4) },
            Triple(Icons.instance(c, "SpawnLocation"), "Spawn") { studio.insertObject("SpawnLocation") },
            Triple(Icons.instance(c, "Seat"), "Seat") { studio.insertObject("Seat").also { synchronized(studio.lock) { it.set("Anchored", true) } } },
            Triple(Icons.instance(c, "TrussPart"), "Truss") { studio.insertObject("TrussPart").also { synchronized(studio.lock) { it.set("Anchored", true) } } },
        ))
        section(into, "SAMPLES")
        grid(into, Samples.all.map { s -> Triple(Icons.instance(c, s.icon), s.name) { studio.insertDocument(RbxDocument(listOf(s.build()), emptyMap(), emptyList())) } })
    }

    private fun objects(into: LinearLayout) {
        grid(into, listOf(
            "Script", "LocalScript", "ModuleScript", "Model", "Folder", "ScreenGui", "Frame", "TextLabel", "TextButton",
            "TextBox", "ImageLabel", "ScrollingFrame", "UICorner", "UIListLayout", "UIStroke", "UIGradient", "PointLight", "SpotLight",
            "Sound", "ParticleEmitter", "ClickDetector", "ProximityPrompt", "Tool", "RemoteEvent", "BindableEvent", "StringValue",
            "NumberValue", "BoolValue", "Attachment", "WeldConstraint", "Humanoid", "Decal", "BillboardGui",
        ).map { cls -> Triple(Icons.instance(context, cls), cls) { studio.insertObject(cls) } })
        into.addView(Ui.iconTextButton(context, Icons.general(context, "InsertObject"), "All objects…") { Dialogs.pickClass(context, "Insert Object") { studio.insertObject(it) } },
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(context, 8f), Ui.dp(context, 8f), Ui.dp(context, 8f), Ui.dp(context, 8f)) })
    }

    // ------------------------------------------------------------------ Inventory

    /** Inventory shows items saved on this device, or the account's own Roblox inventory. */
    private var invRoblox = false
    private var invKind = "All"
    private var invSortNewest = false
    private var invRobloxCat = 0

    /** Roblox inventory categories: label, asset type ids, how a pick is inserted. */
    private class InvCat(val label: String, val types: List<Int>, val insertAs: RobloxApi.Category)
    private val robloxCats = listOf(
        InvCat("Models", listOf(10), RobloxApi.Category.MODELS),
        InvCat("Meshes", listOf(40, 4), RobloxApi.Category.MESHES),
        InvCat("Plugins", listOf(38), RobloxApi.Category.PLUGINS),
        InvCat("Packages", listOf(32), RobloxApi.Category.MODELS),
        InvCat("Decals", listOf(13), RobloxApi.Category.DECALS),
        InvCat("Audio", listOf(3), RobloxApi.Category.AUDIO),
        InvCat("Animations", listOf(24), RobloxApi.Category.MODELS),
    )

    private val deviceKinds = listOf("All", "Models", "Parts", "Meshes", "Scripts", "GUI", "Audio", "Images", "Plugins")

    /** Which device-inventory filter a saved item's root class falls under. */
    private fun kindOf(cls: String): String = when {
        cls == "Script" || cls == "LocalScript" || cls == "ModuleScript" -> "Scripts"
        cls == "MeshPart" || cls == "SpecialMesh" -> "Meshes"
        cls == "Sound" || cls == "SoundGroup" -> "Audio"
        cls == "Decal" || cls == "Texture" || cls == "SurfaceAppearance" -> "Images"
        cls.endsWith("Part") || cls.endsWith("Operation") || cls == "Seat" || cls == "VehicleSeat" || cls == "SpawnLocation" || cls == "Terrain" -> "Parts"
        cls.endsWith("Gui") || cls.startsWith("UI") || cls.startsWith("Text") || cls.startsWith("Image") || cls.endsWith("Frame") || cls == "VideoFrame" -> "GUI"
        else -> "Models"
    }

    private fun chipRow(into: LinearLayout, labels: List<String>, selected: String, onPick: (String) -> Unit) {
        val ctx = context
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 4f), Ui.dp(ctx, 6f), Ui.dp(ctx, 2f)) }
        for (l in labels) {
            val on = l == selected
            row.addView(Ui.text(ctx, l, 11f, if (on) 0xFFFFFFFF.toInt() else Ui.TEXT).apply {
                setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 4f), Ui.dp(ctx, 10f), Ui.dp(ctx, 4f))
                background = GradientDrawable().apply { setColor(if (on) Ui.ACCENT else Ui.HOVER); cornerRadius = Ui.dp(ctx, 12f).toFloat() }
                setOnClickListener { onPick(l) }
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(ctx, 5f) })
        }
        into.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun myModels(into: LinearLayout) {
        val ctx = context
        // Like Studio's Inventory dropdown: where the items come from, then what kind.
        chipRow(into, listOf("On this device", "My Roblox items"), if (invRoblox) "My Roblox items" else "On this device") { invRoblox = it == "My Roblox items"; refresh() }
        if (invRoblox) { robloxInventory(into); return }
        chipRow(into, deviceKinds, invKind) { invKind = it; refresh() }
        val sortRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 2f), Ui.dp(ctx, 6f), 0) }
        sortRow.addView(Ui.text(ctx, "Sort:", 11f, Ui.DIM))
        sortRow.addView(Ui.text(ctx, if (invSortNewest) "Newest first ▾" else "Name A–Z ▾", 11f, Ui.INFO).apply {
            setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 4f), Ui.dp(ctx, 6f), Ui.dp(ctx, 4f))
            setOnClickListener { invSortNewest = !invSortNewest; refresh() }
        })
        into.addView(sortRow)
        if (invKind == "Plugins") { installedPlugins(into); return }
        into.addView(Ui.iconTextButton(ctx, Icons.general(ctx, "Open"), "Import .rbxm / .rbxmx…") { actions.importToToolbox() },
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(ctx, 8f), Ui.dp(ctx, 4f), Ui.dp(ctx, 8f), Ui.dp(ctx, 4f)) })
        fun clsOf(f: File, recentItem: Boolean) = meta.getString((if (recentItem) "recent/" else "") + f.name, null) ?: "Model"
        fun keep(f: File, recentItem: Boolean) = invKind == "All" || kindOf(clsOf(f, recentItem)) == invKind
        fun sorted(l: List<File>) = if (invSortNewest) l.sortedByDescending { it.lastModified() } else l.sortedBy { it.name.lowercase() }
        val files = sorted(libraryDir.listFiles()?.filter { it.isFile && keep(it, false) } ?: emptyList())
        val recent = File(libraryDir, "recent").listFiles()?.filter { it.isFile && keep(it, true) }?.sortedByDescending { it.lastModified() }?.take(30) ?: emptyList()
        if (files.isEmpty() && recent.isEmpty()) {
            into.addView(Ui.text(ctx, if (invKind != "All") "Nothing of this kind in your inventory yet."
                else "Your inventory is empty.\nDrag objects here from the Explorer, import a model file, or insert something from the Creator Store.", 12f, Ui.DIM).apply {
                isSingleLine = false; setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f), 0)
            })
        }
        fun row(f: File, recentItem: Boolean) {
            val cls = clsOf(f, recentItem)
            val r = Ui.iconTextButton(ctx, Icons.instance(ctx, cls), f.nameWithoutExtension, Ui.HOVER) { insertFile(f) }
            r.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            r.setOnLongClickListener {
                val opts = listOf("Insert", "Rename…", "Delete")
                Dialogs.themed(ctx).setTitle(f.nameWithoutExtension).setItems(opts.toTypedArray()) { _, i ->
                    when (i) {
                        0 -> insertFile(f)
                        1 -> Dialogs.prompt(ctx, "Rename", f.nameWithoutExtension) { n ->
                            val target = File(f.parentFile, safeName(n) + "." + f.extension)
                            if (!target.exists() && f.renameTo(target)) {
                                val key = (if (recentItem) "recent/" else "")
                                meta.edit().putString(key + target.name, cls).remove(key + f.name).apply()
                            }
                            refresh()
                        }
                        2 -> Dialogs.confirm(ctx, "Remove from Toolbox", "Delete ${f.nameWithoutExtension} from your toolbox?", "Delete") { f.delete(); refresh() }
                    }
                }.show()
                true
            }
            into.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(ctx, 8f), Ui.dp(ctx, 2f), Ui.dp(ctx, 8f), Ui.dp(ctx, 2f)) })
        }
        if (files.isNotEmpty()) { section(into, "MY INVENTORY"); files.forEach { row(it, false) } }
        if (recent.isNotEmpty()) { section(into, "RECENTLY INSERTED"); recent.forEach { row(it, true) } }
    }

    /** Inventory → Plugins: the plugins installed in this app (on/off, uninstall). */
    private fun installedPlugins(into: LinearLayout) {
        val ctx = context
        val files = studio.plugins.list().let { l -> if (invSortNewest) l.sortedByDescending { it.lastModified() } else l }
        if (files.isEmpty()) {
            into.addView(Ui.text(ctx, "No plugins installed. Get some from the Creator Store (Plugins) or My Roblox items → Plugins.", 12f, Ui.DIM).apply {
                isSingleLine = false; setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f), 0)
            })
            return
        }
        for (f in files) {
            val on = studio.plugins.isEnabled(f)
            val r = Ui.iconTextButton(ctx, Icons.instance(ctx, "Plugin"), f.nameWithoutExtension + if (on) "" else "  (off)", Ui.HOVER) {
                val opts = listOf(if (on) "Turn off" else "Turn on", "Uninstall")
                Dialogs.themed(ctx).setTitle(f.nameWithoutExtension).setItems(opts.toTypedArray()) { _, i ->
                    if (i == 0) { studio.plugins.setEnabled(f, !on); studio.plugins.loadAll() }
                    else Dialogs.confirm(ctx, "Remove plugin", "Uninstall ${f.nameWithoutExtension}?", "Remove") { studio.plugins.remove(f); studio.plugins.loadAll() }
                    refresh()
                }.show()
            }
            r.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            into.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(ctx, 8f), Ui.dp(ctx, 2f), Ui.dp(ctx, 8f), Ui.dp(ctx, 2f)) })
        }
    }

    /** Loaded pages of the Roblox inventory per category (kept while the app runs). */
    private val invCache = HashMap<Int, Pair<ArrayList<RobloxApi.InvItem>, String?>>()
    private val invThumbs = HashMap<Long, String>()
    private var invLoading = false

    /** Inventory → My Roblox items: assets the signed-in account owns or made, by type (like Studio's Inventory). */
    private fun robloxInventory(into: LinearLayout) {
        val ctx = context
        val act = ctx as android.app.Activity
        chipRow(into, robloxCats.map { it.label }, robloxCats[invRobloxCat].label) { l -> invRobloxCat = robloxCats.indexOfFirst { it.label == l }.coerceAtLeast(0); refresh() }
        if (RobloxApi.isGuest(ctx)) {
            into.addView(Ui.text(ctx, "Sign in to see the models, meshes, plugins and other items on your Roblox account.", 12f, Ui.DIM).apply {
                isSingleLine = false; setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f), Ui.dp(ctx, 6f))
            })
            into.addView(Ui.button(ctx, "Sign in with Roblox") { AccountUi.signIn(act) { refresh() } }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(ctx, 8f), 0, Ui.dp(ctx, 8f), 0) })
            return
        }
        val cat = robloxCats[invRobloxCat]
        val key = invRobloxCat
        val cached = invCache[key]
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(ctx, 10f), 0, Ui.dp(ctx, 6f), 0) }
        head.addView(Ui.text(ctx, cached?.let { "${it.first.size} item${if (it.first.size == 1) "" else "s"}" } ?: "", 11f, Ui.DIM), Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.text(ctx, "Reload", 11f, Ui.INFO).apply {
            setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 4f), Ui.dp(ctx, 6f), Ui.dp(ctx, 4f))
            setOnClickListener { invCache.remove(key); refresh() }
        })
        into.addView(head)
        if (cached == null) {
            into.addView(StudioSpinner(ctx), Ui.lp(Ui.dp(ctx, 32f), Ui.dp(ctx, 32f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(ctx, 12f) })
            loadInventory(key, cat, null)
            return
        }
        val (items, next) = cached
        if (items.isEmpty()) {
            into.addView(Ui.text(ctx, "No ${cat.label.lowercase()} on your account.", 12f, Ui.DIM).apply { setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), 0, 0) })
            return
        }
        val list = if (invSortNewest) items else items.sortedBy { it.name.lowercase() }
        for (it in list) {
            val row = LinearLayout(ctx).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 3f), Ui.dp(ctx, 6f), Ui.dp(ctx, 3f))
                background = Ui.rippleBg(ctx, Ui.HOVER, 4f)
                setOnClickListener { _ -> insertInventoryItem(it, cat) }
                setOnLongClickListener { _ ->
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Asset ID", it.id.toString()))
                    studio.toast("Copied asset ID ${it.id}"); true
                }
            }
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Ui.blend(Ui.PANEL, 0xFF000000.toInt(), 0.08f)) }
            val url = invThumbs[it.id]
            val bmp = url?.let { u -> ImageLoader.getKey("url:$u") }
            if (bmp != null) img.setImageBitmap(bmp) else {
                img.setImageDrawable(Icons.general(ctx, if (cat.insertAs == RobloxApi.Category.PLUGINS) "ManagePlugins" else "Toolbox"))
                if (url != null) invThumbViews.getOrPut("url:$url") { ArrayList() }.add(img)
            }
            row.addView(img, Ui.lp(Ui.dp(ctx, 40f), Ui.dp(ctx, 40f)).apply { rightMargin = Ui.dp(ctx, 8f) })
            row.addView(Ui.text(ctx, it.name, 12f).apply { maxLines = 2; isSingleLine = false; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.lp(0, Ui.WRAP, 1f))
            into.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(ctx, 4f), Ui.dp(ctx, 1f), Ui.dp(ctx, 4f), Ui.dp(ctx, 1f)) })
        }
        if (next != null) into.addView(Ui.button(ctx, if (invLoading) "Loading…" else "Load more") { if (!invLoading) loadInventory(key, cat, next) },
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(ctx, 8f), Ui.dp(ctx, 6f), Ui.dp(ctx, 8f), Ui.dp(ctx, 8f)) })
    }

    private fun loadInventory(key: Int, cat: InvCat, cursor: String?) {
        if (invLoading) return
        invLoading = true
        val ctx = context
        net.execute {
            val r = try {
                val uid = RobloxApi.userId(ctx)
                // Meshes: MeshPart assets first, then raw meshes (cursor belongs to the first type).
                val pages = if (cursor != null) listOf(RobloxApi.inventory(ctx, uid, cat.types[0], cursor))
                    else cat.types.map { t -> try { RobloxApi.inventory(ctx, uid, t, null) } catch (e: Exception) { if (t == cat.types[0]) throw e else emptyList<RobloxApi.InvItem>() to null } }
                val items = pages.flatMap { it.first }.distinctBy { it.id }
                val thumbs = try { items.map { it.id }.filter { it !in invThumbs }.chunked(100).flatMap { RobloxApi.thumbnailUrls(it, "150x150").entries }.associate { it.key to it.value } } catch (_: Exception) { emptyMap() }
                Result.success(Triple(items, pages.first().second, thumbs))
            } catch (e: Exception) { Result.failure(e) }
            post {
                invLoading = false
                r.fold({ (items, next, thumbs) ->
                    invThumbs.putAll(thumbs.filterValues { it.isNotEmpty() })
                    val old = if (cursor != null) invCache[key]?.first ?: ArrayList() else ArrayList()
                    old.addAll(items.filter { n -> old.none { it.id == n.id } })
                    invCache[key] = old to next
                }) { e ->
                    invCache[key] = ArrayList<RobloxApi.InvItem>() to null
                    studio.toast("Couldn't load your ${cat.label.lowercase()}: ${e.message}")
                }
                if (tab == 1 && invRoblox) refresh()
            }
        }
    }

    private fun insertInventoryItem(it: RobloxApi.InvItem, cat: InvCat) {
        val item = RobloxApi.Item(it.id, it.name, RobloxApi.userName(context), false, "", 0, 0, false, false)
        if (cat.label == "Animations") {
            // Animations are referenced by id: an Animation object goes in the selection (or Workspace).
            synchronized(studio.lock) {
                val a = InstanceFactory.create("Animation")
                a.name = it.name.take(40).ifBlank { "Animation" }
                a.set("AnimationId", "rbxassetid://${it.id}")
                val parent = studio.selection.firstOrNull()?.takeIf { p -> !p.isService || p.className == "Workspace" } ?: studio.model.workspace
                studio.undo.begin("Insert animation")
                studio.reparent(a, parent)
                studio.undo.commit()
                studio.selection.clear(); studio.selection.add(a)
            }
            studio.listeners.forEach { l -> l.onSelectionChanged() }
            studio.toast("Added animation ${it.name}")
            return
        }
        insertAsset(item, cat.insertAs)
    }

    private fun insertFile(f: File) {
        Thread {
            try {
                val doc = RbxFile.read(f.readBytes())
                if (RbxFile.isPlace(doc)) { OutputLog.error("${f.name} is a place file; open it with File → Open"); return@Thread }
                doc.warnings.take(5).forEach { OutputLog.warn(it) }
                studio.main.post { studio.insertDocument(doc) }
            } catch (e: Exception) {
                OutputLog.error("Could not insert ${f.name}: ${e.message}")
            }
        }.start()
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "Model" }.take(60)

    fun saveToLibrary(name: String, bytes: ByteArray, cls: String? = null) {
        var safe = safeName(name)
        if (safe.endsWith(".rbxm") || safe.endsWith(".rbxmx")) safe = safe.substringBeforeLast('.')
        // Don't overwrite an existing item with the same name.
        var f = File(libraryDir, "$safe.rbxm")
        var i = 2
        while (f.exists()) { f = File(libraryDir, "$safe ($i).rbxm"); i++ }
        f.writeBytes(bytes)
        if (cls != null) meta.edit().putString(f.name, cls).apply()
        tab = 1
        refresh()
    }
}

/** Small built-in example models that show scripting working in playtests. */
object Samples {
    class Sample(val icon: String, val name: String, val build: () -> Instance)

    private fun part(name: String, size: Vector3, color: Color3, neon: Boolean = false): Instance {
        val p = InstanceFactory.create("Part")
        p.name = name
        p.set("size", size, false)
        p.set("Color3uint8", color, false)
        p.set("Anchored", true, false)
        if (neon) p.set("Material", EnumValue(288), false)
        return p
    }

    private fun script(parent: Instance, name: String, src: String, cls: String = "Script") {
        val s = InstanceFactory.create(cls)
        s.name = name
        s.set("Source", src.trimIndent() + "\n", false)
        s.attachRaw(parent)
    }

    val all = listOf(
        Sample("Part", "Kill Brick") {
            part("KillBrick", Vector3(8f, 1f, 8f), Color3(1f, 0.1f, 0.1f), neon = true).also {
                script(it, "KillScript", """
                    local part = script.Parent
                    part.Touched:Connect(function(hit)
                        local humanoid = hit.Parent:FindFirstChildOfClass("Humanoid")
                        if humanoid then
                            humanoid.Health = 0
                        end
                    end)
                """)
            }
        },
        Sample("Script", "Spinner") {
            part("Spinner", Vector3(12f, 1f, 2f), Color3.fromRGB(255, 176, 0)).also {
                script(it, "Spin", """
                    local RunService = game:GetService("RunService")
                    local part = script.Parent
                    local speed = 2 -- radians per second
                    RunService.Heartbeat:Connect(function(dt)
                        part.CFrame = part.CFrame * CFrame.Angles(0, speed * dt, 0)
                    end)
                """)
            }
        },
        Sample("SpawnLocation", "Jump Pad") {
            part("JumpPad", Vector3(6f, 0.5f, 6f), Color3.fromRGB(0, 255, 120), neon = true).also {
                script(it, "Launch", """
                    local pad = script.Parent
                    local debounce = {}
                    pad.Touched:Connect(function(hit)
                        local character = hit.Parent
                        local root = character and character:FindFirstChild("HumanoidRootPart")
                        if root and not debounce[character] then
                            debounce[character] = true
                            root.AssemblyLinearVelocity = Vector3.new(0, 120, 0)
                            task.wait(0.5)
                            debounce[character] = nil
                        end
                    end)
                """)
            }
        },
        Sample("ClickDetector", "Click Color") {
            part("ColorButton", Vector3(4f, 4f, 4f), Color3.fromRGB(13, 105, 172)).also {
                InstanceFactory.create("ClickDetector").attachRaw(it)
                script(it, "OnClick", """
                    local part = script.Parent
                    part.ClickDetector.MouseClick:Connect(function(player)
                        part.Color = Color3.fromHSV(math.random(), 0.8, 1)
                        print(player.Name .. " changed the color!")
                    end)
                """)
            }
        },
        Sample("ProximityPrompt", "Tween Door") {
            part("Door", Vector3(6f, 10f, 1f), Color3.fromRGB(124, 92, 70)).also {
                val prompt = InstanceFactory.create("ProximityPrompt")
                prompt.set("ActionText", "Open", false)
                prompt.attachRaw(it)
                script(it, "DoorScript", """
                    local TweenService = game:GetService("TweenService")
                    local door = script.Parent
                    local closed = door.CFrame
                    local open = closed * CFrame.new(0, 9, 0)
                    local isOpen = false
                    local info = TweenInfo.new(0.6, Enum.EasingStyle.Quad, Enum.EasingDirection.Out)

                    door.ProximityPrompt.Triggered:Connect(function()
                        isOpen = not isOpen
                        door.ProximityPrompt.ActionText = if isOpen then "Close" else "Open"
                        TweenService:Create(door, info, { CFrame = if isOpen then open else closed }):Play()
                    end)
                """)
            }
        },
        Sample("ScreenGui", "Click Counter GUI") {
            val gui = InstanceFactory.create("ScreenGui")
            gui.name = "ClickCounter"
            val btn = InstanceFactory.create("TextButton")
            btn.name = "Button"
            btn.set("Size", UDim2(0f, 220, 0f, 60), false)
            btn.set("Position", UDim2(0.5f, 0, 0f, 20), false)
            btn.set("AnchorPoint", Vector2(0.5f, 0f), false)
            btn.set("BackgroundColor3", Color3.fromRGB(14, 122, 206), false)
            btn.set("TextColor3", Color3(1f, 1f, 1f), false)
            btn.set("TextSize", 22f, false)
            btn.set("Font", EnumValue(19), false)
            btn.set("Text", "Clicks: 0", false)
            btn.attachRaw(gui)
            InstanceFactory.create("UICorner").attachRaw(btn)
            script(btn, "Counter", """
                local button = script.Parent
                local clicks = 0
                button.MouseButton1Click:Connect(function()
                    clicks += 1
                    button.Text = `Clicks: {clicks}`
                end)
            """, "LocalScript")
            gui
        },
        Sample("Folder", "Falling Blocks") {
            val m = InstanceFactory.create("Folder")
            m.name = "FallingBlocks"
            for (i in 0 until 6) {
                val p = part("Block$i", Vector3(2f, 2f, 2f), Color3.fromHSV(i / 6f, 0.7f, 1f))
                p.set("Anchored", false, false)
                p.set("CFrame", CFrame((i % 3) * 3f, 10f + i * 3f, (i / 3) * 3f), false)
                p.attachRaw(m)
            }
            m
        },
    )
}
