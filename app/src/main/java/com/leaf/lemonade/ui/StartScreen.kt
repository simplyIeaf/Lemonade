package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.leaf.lemonade.core.PlaceFactory
import com.leaf.lemonade.core.Recents
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.net.Accounts
import com.leaf.lemonade.net.AuthRequiredException
import com.leaf.lemonade.net.ImageLoader
import com.leaf.lemonade.net.RobloxApi
import com.leaf.lemonade.net.RobloxCloud
import java.util.concurrent.Executors

/**
 * The page Lemonade opens on, laid out like Roblox Studio's start page: a sidebar (New Experience,
 * Recents, Home, Experiences, Templates) next to the page. Home shows your recent experiences and
 * templates; Experiences has My Experiences, Shared With Me and Group Experiences.
 */
@SuppressLint("ViewConstructor")
class StartScreen(private val act: Activity, private val studio: Studio, private val actions: MainActions, private val host: Host) : LinearLayout(act) {
    interface Host {
        fun newFromTemplate(t: PlaceFactory.Template)
        fun openFileFromStart()
        fun openRecentFile(e: Recents.Entry)
        fun closeStart()
        fun hasOpenPlace(): Boolean
        fun showStudioSettings()
    }

    private val net = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val content = FrameLayout(act)
    private val nav = LinearLayout(act)
    private var page = HOME
    private var expKind = 0
    private var tplFilter = 0
    private val accountChip = LinearLayout(act)
    private val phone = act.resources.configuration.smallestScreenWidthDp < 600
    private var gen = 0
    private var selectedGroup: Long = 0L
    private val iconViews = HashMap<String, MutableList<ImageView>>()
    private val imgListener: (String) -> Unit = { k ->
        if (k.startsWith("url:")) main.post { iconViews[k]?.forEach { v -> ImageLoader.peekKey(k)?.let { b -> showBitmap(v, b) } } }
    }
    private val accountListener: () -> Unit = { main.post { refreshAccount(); if (page != RECENT) show(page) } }
    /** Card grids on the current page and the search text that filters them. */
    private val grids = ArrayList<FlowLayout>()
    private var query = ""

    private val bgColor get() = if (Ui.LIGHT) Ui.BG else 0xFF1F2024.toInt()
    private val cardColor get() = if (Ui.LIGHT) Ui.CARD else 0xFF2C2D32.toInt()
    private val selColor get() = if (Ui.LIGHT) Ui.blend(Ui.BG, Ui.ACCENT, 0.18f) else 0xFF000000.toInt()
    private val fgColor get() = if (Ui.LIGHT) Ui.TEXT else 0xFFFFFFFF.toInt()
    private val subColor get() = if (Ui.LIGHT) Ui.DIM else 0xFFB8B9BD.toInt()
    private val buttonColor get() = if (Ui.LIGHT) Ui.HOVER else 0xFF121214.toInt()
    private val linkColor get() = if (Ui.LIGHT) Ui.ACCENT else Ui.blend(Ui.ACCENT, 0xFFFFFFFF.toInt(), 0.45f)

    private val looks = mapOf(
        PlaceFactory.Template.BASEPLATE to (0xFF4C7BD9.toInt() to "Part_Block"),
        PlaceFactory.Template.CLASSIC to (0xFF4E9A52.toInt() to "Surface"),
        PlaceFactory.Template.FLAT_TERRAIN to (0xFF5E8C3A.toInt() to "Terrain"),
        PlaceFactory.Template.EMPTY to (0xFF8A6FD1.toInt() to "Place"),
        PlaceFactory.Template.OBBY to (0xFFD9534F.toInt() to "Spawn"),
        PlaceFactory.Template.ARENA to (0xFFE08A2E.toInt() to "Team"),
        PlaceFactory.Template.ISLAND to (0xFF2F8FB5.toInt() to "Terrain"),
        PlaceFactory.Template.VILLAGE to (0xFF8C6A45.toInt() to "Model"),
    )

    init {
        orientation = HORIZONTAL
        setBackgroundColor(bgColor)
        isClickable = true

        nav.orientation = VERTICAL
        nav.setPadding(dp(12f), dp(16f), dp(12f), dp(14f))
        addView(ScrollView(act).apply { isFillViewport = true; addView(nav, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)) }, Ui.lp(dp(if (phone) 188f else 244f), Ui.MATCH))
        addView(View(act).apply { setBackgroundColor(if (Ui.LIGHT) Ui.BORDER else 0xFF2F3034.toInt()) }, Ui.lp(dp(1f), Ui.MATCH))

        val right = LinearLayout(act).apply { orientation = VERTICAL }
        val top = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(dp(12f), dp(8f), dp(12f), dp(0f)) }
        if (host.hasOpenPlace()) top.addView(Ui.text(act, "Back to place", 13f, fgColor).apply {
            gravity = Gravity.CENTER; setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            background = Ui.rippleBg(act, buttonColor, 8f); isClickable = true
            setOnClickListener { host.closeStart() }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = dp(8f) })
        top.addView(Ui.iconButton(act, mono(Icons.general(act, "Settings"), fgColor), 34f) { host.showStudioSettings() }.apply { contentDescription = "Studio settings" })
        accountChip.apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { AccountUi.show(act, studio, actions) }
        }
        top.addView(accountChip, Ui.lp(Ui.WRAP, Ui.WRAP).apply { leftMargin = dp(6f) })
        right.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))
        right.addView(content, Ui.lp(Ui.MATCH, 0, 1f))
        addView(right, Ui.lp(0, Ui.MATCH, 1f))
        refreshAccount()
        show(HOME)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ImageLoader.listeners.add(imgListener); Accounts.listeners.add(accountListener)
    }
    override fun onDetachedFromWindow() {
        ImageLoader.listeners.remove(imgListener); Accounts.listeners.remove(accountListener)
        gen++
        net.shutdown()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float) = Ui.dp(act, v)

    private fun mono(d: android.graphics.drawable.Drawable, color: Int): android.graphics.drawable.Drawable {
        d.colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        return d
    }

    // ------------------------------------------------------------------ navigation

    private fun buildNav() {
        nav.removeAllViews()
        val newRow = LinearLayout(act).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = Ui.rippleBg(act, android.graphics.Color.TRANSPARENT, 10f)
            isClickable = true
            setOnClickListener { show(TEMPLATES) }
            contentDescription = "New Experience"
        }
        newRow.addView(Ui.text(act, "+", 18f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFF3966E9.toInt()); cornerRadius = dp(6f).toFloat() }
        }, Ui.lp(dp(24f), dp(24f)).apply { rightMargin = dp(12f) })
        newRow.addView(Ui.text(act, "New Experience", 14.5f, fgColor, true))
        nav.addView(newRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(10f) })
        fun item(label: String, icon: String, selected: Boolean, onClick: () -> Unit) {
            val v = LinearLayout(act).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
                background = Ui.rippleBg(act, if (selected) selColor else android.graphics.Color.TRANSPARENT, 10f)
                isClickable = true
                setOnClickListener { onClick() }
                contentDescription = label
            }
            v.addView(ImageView(act).apply { setImageDrawable(mono(Icons.general(act, icon), fgColor)) }, Ui.lp(dp(22f), dp(22f)).apply { rightMargin = dp(12f) })
            v.addView(Ui.text(act, label, 14.5f, fgColor, selected))
            nav.addView(v, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(2f) })
        }
        item("Recents", "History", page == RECENT) { show(RECENT) }
        item("Home", "Home", page == HOME) { show(HOME) }
        item("Experiences", "Cloud", page == EXPERIENCES) { show(EXPERIENCES) }
        item("Templates", "SwitchWindows", page == TEMPLATES) { show(TEMPLATES) }
        nav.addView(View(act), Ui.lp(Ui.MATCH, dp(14f)))
        item("Open File…", "Open", false) { host.openFileFromStart() }
    }

    private fun refreshAccount() {
        accountChip.removeAllViews()
        val img = ImageView(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cardColor) }
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        accountChip.addView(img, Ui.lp(dp(32f), dp(32f)))
        val a = Accounts.active(act)
        if (a != null) AccountUi.loadHeadshot(img, a.userId) else { img.setPadding(dp(5f), dp(5f), dp(5f), dp(5f)); img.setImageDrawable(mono(Icons.general(act, "Account"), fgColor)) }
        accountChip.contentDescription = if (a != null) "Roblox account @${a.name}" else "Not signed in"
    }

    private fun show(p: String) {
        page = p
        gen++
        grids.clear()
        query = ""
        buildNav()
        content.removeAllViews()
        iconViews.clear()
        when (p) {
            HOME -> home()
            RECENT -> recent()
            EXPERIENCES -> experiences()
            TEMPLATES -> templates()
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun scrollColumn(): LinearLayout {
        val col = LinearLayout(act).apply { orientation = VERTICAL; setPadding(dp(if (phone) 16f else 28f), dp(6f), dp(if (phone) 16f else 28f), dp(28f)) }
        content.addView(ScrollView(act).apply { addView(col) }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return col
    }

    /** A section title, with an optional "See All" button to [seeAll] and a line of text under it. */
    private fun heading(col: LinearLayout, text: String, seeAll: String? = null, sub: String? = null) {
        val r = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18f), 0, if (sub == null) dp(12f) else dp(2f)) }
        r.addView(Ui.text(act, text, 22f, fgColor, true), Ui.lp(0, Ui.WRAP, 1f))
        if (seeAll != null) r.addView(Ui.text(act, "See All", 13f, fgColor, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = Ui.rippleBg(act, buttonColor, 8f)
            isClickable = true
            setOnClickListener { show(seeAll) }
        })
        col.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP))
        if (sub != null) col.addView(Ui.text(act, sub, 13f, subColor).apply { isSingleLine = false; setPadding(0, 0, 0, dp(12f)) }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    /** A row of pill-shaped tabs at the top of a page (Templates: All / Blank / Gameplay / Themed). */
    private fun chips(col: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for ((i, l) in labels.withIndex()) row.addView(Ui.text(act, l, 13.5f, fgColor, i == selected).apply {
            gravity = Gravity.CENTER; setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = Ui.rippleBg(act, if (i == selected) selColor else android.graphics.Color.TRANSPARENT, 18f)
            isClickable = true
            setOnClickListener { onPick(i) }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = dp(4f) })
        col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
    }

    /** Title, subtitle, refresh and (optionally) a search box at the top of a list page. */
    private fun pageHeader(col: LinearLayout, title: String, sub: String, search: Boolean, onRefresh: (() -> Unit)?) {
        val r = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14f), 0, dp(4f)) }
        val t = LinearLayout(act).apply { orientation = VERTICAL }
        t.addView(Ui.text(act, title, 26f, fgColor, true))
        t.addView(Ui.text(act, sub, 12.5f, subColor).apply { isSingleLine = false; setPadding(0, dp(2f), 0, 0) })
        r.addView(t, Ui.lp(0, Ui.WRAP, 1f))
        if (onRefresh != null) r.addView(Ui.iconButton(act, mono(Icons.general(act, "PluginsFolder"), fgColor), 36f) { onRefresh() }.apply { contentDescription = "Refresh" })
        col.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP))
        if (!search) return
        val box = Ui.input(act, "", "Search").apply {
            val ic = mono(Icons.general(act, "FindAll"), subColor); ic.setBounds(0, 0, dp(16f), dp(16f))
            setCompoundDrawables(ic, null, null, null); compoundDrawablePadding = dp(8f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(10f).toFloat() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); applyFilter() }
            })
        }
        col.addView(box, Ui.lp(if (phone) Ui.MATCH else dp(340f), Ui.WRAP).apply { topMargin = dp(10f); bottomMargin = dp(10f) })
    }

    private fun applyFilter() {
        val q = query.lowercase()
        for (g in grids) for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            val name = (c.tag as? String)?.lowercase() ?: continue
            c.visibility = if (q.isEmpty() || q in name) View.VISIBLE else View.GONE
        }
    }

    private fun message(col: LinearLayout, text: String, button: String? = null, icon: String = "Account", onClick: (() -> Unit)? = null) {
        val card = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(10f).toFloat() }
        }
        card.addView(Ui.text(act, text, 13.5f, fgColor).apply { isSingleLine = false; setLineSpacing(dp(2f).toFloat(), 1f) })
        if (button != null && onClick != null) card.addView(Ui.iconTextButton(act, Icons.general(act, icon), button, Ui.ACCENT) { onClick() }.apply { setTextColor(0xFFFFFFFF.toInt()) },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(10f) })
        col.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f); bottomMargin = dp(6f) })
    }

    private fun showBitmap(img: ImageView, b: android.graphics.Bitmap) {
        img.setPadding(0, 0, 0, 0)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.setImageBitmap(b)
    }

    /** Shows the image at [url] in [img] once it's loaded, a centered [fallback] icon until then. */
    private fun bindIcon(img: ImageView, url: String?, fallback: String) {
        val pad = dp(if (phone) 30f else 36f)
        fun placeholder() { img.scaleType = ImageView.ScaleType.FIT_CENTER; img.setPadding(pad, pad, pad, pad); img.setImageDrawable(Icons.general(act, fallback)) }
        if (url == null) { placeholder(); return }
        val key = "url:$url"
        val bmp = ImageLoader.getKey(key)
        if (bmp != null) showBitmap(img, bmp) else { placeholder(); iconViews.getOrPut(key) { ArrayList() }.add(img) }
    }

    /**
     * A Studio start page card: a rounded thumbnail, an optional pill and "⋮" menu row, the name
     * and a detail line under it.
     */
    private fun card(title: String, sub: String, iconUrl: String?, fallback: String, onClick: () -> Unit, onLong: (() -> Unit)? = null, tint: Int? = null,
                     iconDrawable: android.graphics.drawable.Drawable? = null, badge: String? = null, big: Boolean = false): LinearLayout {
        val w = dp(if (big) (if (phone) 150f else 190f) else (if (phone) 136f else 160f))
        val inner = w - dp(16f)
        val c = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(10f))
            background = Ui.rippleBg(act, cardColor, 10f)
            isClickable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
            tag = title
            contentDescription = title
        }
        val base = tint ?: Ui.blend(cardColor, 0xFF000000.toInt(), if (Ui.LIGHT) 0.06f else 0.22f)
        val thumb = FrameLayout(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(if (tint != null) Ui.blend(tint, 0xFFFFFFFF.toInt(), 0.18f) else base, if (tint != null) Ui.blend(tint, 0xFF000000.toInt(), 0.22f) else base)).apply {
                cornerRadius = dp(8f).toFloat()
            }
            clipToOutline = true
        }
        val img = ImageView(act)
        thumb.addView(img, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        bindIcon(img, iconUrl, fallback)
        if (iconDrawable != null) img.setImageDrawable(iconDrawable)
        c.addView(thumb, Ui.lp(inner, inner))
        if (badge != null || onLong != null) {
            val meta = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (badge != null) meta.addView(Ui.text(act, badge, 10f, 0xFFE8E8E8.toInt(), true).apply {
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                background = GradientDrawable().apply { setColor(if (Ui.LIGHT) Ui.HOVER else 0xFF000000.toInt()); cornerRadius = dp(10f).toFloat() }
                if (Ui.LIGHT) setTextColor(Ui.TEXT)
            })
            meta.addView(View(act), Ui.lp(0, 1, 1f))
            if (onLong != null) meta.addView(Ui.text(act, "⋮", 18f, fgColor, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(8f), 0, dp(2f), 0)
                setOnClickListener { onLong() }
            })
            c.addView(meta, Ui.lp(inner, Ui.WRAP).apply { topMargin = dp(8f) })
        }
        c.addView(Ui.text(act, title, 13f, fgColor, true).apply { ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2f), if (badge != null || onLong != null) dp(2f) else dp(8f), 0, 0) }, Ui.lp(inner, Ui.WRAP))
        if (sub.isNotEmpty()) c.addView(Ui.text(act, sub, 10.5f, subColor).apply {
            isSingleLine = false; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(2f), dp(2f), 0, 0)
        }, Ui.lp(inner, Ui.WRAP))
        c.layoutParams = LayoutParams(w, Ui.WRAP)
        return c
    }

    private fun grid(col: LinearLayout): FlowLayout {
        val f = FlowLayout(act).apply { gap = dp(12f) }
        col.addView(f, Ui.lp(Ui.MATCH, Ui.WRAP))
        grids.add(f)
        return f
    }

    /** A sideways-scrolling row of cards (Home). */
    private fun row(col: LinearLayout): LinearLayout {
        val r = LinearLayout(act).apply { orientation = HORIZONTAL }
        col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(r) }, Ui.lp(Ui.MATCH, Ui.WRAP))
        return r
    }
    private fun addToRow(r: LinearLayout, v: View) = r.addView(v, LayoutParams(v.layoutParams?.width ?: Ui.WRAP, Ui.WRAP).apply { rightMargin = dp(12f) })

    private fun loading(col: LinearLayout): View {
        val p = StudioSpinner(act)
        col.addView(p, Ui.lp(dp(36f), dp(36f)).apply { topMargin = dp(20f); gravity = Gravity.CENTER_HORIZONTAL })
        return p
    }

    // ------------------------------------------------------------------ Home

    private fun templateCard(t: PlaceFactory.Template, big: Boolean): LinearLayout {
        val (tint, icon) = looks[t] ?: (0xFF6B6B6B.toInt() to "Place")
        val d = if (icon == "Terrain" || icon == "Team" || icon == "Model") Icons.instance(act, icon) else null
        return card(t.title, "", null, icon, { host.newFromTemplate(t) }, tint = tint, iconDrawable = d, big = big)
    }

    private fun home() {
        val col = scrollColumn()
        heading(col, "My Recent Experiences", RECENT)
        val recents = recentEntries(12)
        if (recents.isEmpty()) message(col, "Places and files you open show up here, so you can jump right back in.")
        else {
            val rr = row(col)
            for ((_, v) in recentCards(recents)) addToRow(rr, v)
        }
        heading(col, "Templates", TEMPLATES)
        val tr = row(col)
        for (t in PlaceFactory.Template.values()) addToRow(tr, templateCard(t, false))
    }

    // ------------------------------------------------------------------ Templates

    private fun templates() {
        val col = scrollColumn()
        val labels = listOf("All", "Blank", "Gameplay", "Themed")
        chips(col, labels, tplFilter) { tplFilter = it; show(TEMPLATES) }
        col.addView(Ui.text(act, if (tplFilter == 0) "All Templates" else labels[tplFilter] + " Templates", 28f, fgColor, true).apply { setPadding(0, dp(14f), 0, dp(14f)) })
        val g = grid(col)
        for (t in PlaceFactory.Template.values()) {
            val ok = when (tplFilter) { 1 -> t.category == "All Templates"; 2 -> t.category == "Gameplay"; 3 -> t.category == "Theme"; else -> true }
            if (ok) g.addView(templateCard(t, true))
        }
    }

    // ------------------------------------------------------------------ Experiences

    private fun experiences() {
        val col = scrollColumn()
        chips(col, listOf("My Experiences", "Shared With Me", "Group Experiences"), expKind) { expKind = it; show(EXPERIENCES) }
        when (expKind) {
            0 -> games(col, Kind.MINE)
            1 -> games(col, Kind.SHARED)
            else -> groupGames(col)
        }
    }

    // ------------------------------------------------------------------ Recent

    private fun recentEntries(limit: Int): List<Recents.Entry> {
        val myId = RobloxApi.userId(act)
        return Recents.list(act).filter { !it.isCloud || it.userId == 0L || it.userId == myId || RobloxApi.isGuest(act) }.take(limit)
    }

    private fun recent() {
        val col = scrollColumn()
        pageHeader(col, "Recents", "Places and files you opened lately. Use ⋮ to remove one.", true, null)
        val list = recentEntries(60)
        if (list.isEmpty()) { message(col, "Places and files you open show up here."); return }
        val g = grid(col)
        recentCards(list).forEach { g.addView(it.second) }
    }

    /** Cards for recent places; Roblox places get their game icons once they're known. */
    private fun recentCards(list: List<Recents.Entry>): List<Pair<Recents.Entry, LinearLayout>> {
        val dateFmt = java.text.SimpleDateFormat("MM/dd/yyyy h:mm a", java.util.Locale.US)
        val cards = list.map { e ->
            val sub = "Modified " + dateFmt.format(java.util.Date(e.time)) + "\n" + (if (e.isCloud) "Roblox place" else "On this device")
            e to card(e.name.removeSuffix(".rbxl").removeSuffix(".rbxlx"), sub, StartCache.icons[e.universeId].takeIf { e.isCloud }, if (e.isCloud) "Place" else "Open", { openRecent(e) }, {
                Dialogs.themed(act).setTitle(e.name).setItems(arrayOf("Open", "Remove from Recent")) { _, i ->
                    if (i == 0) openRecent(e) else { Recents.remove(act, e); show(page) }
                }.show()
            }, badge = if (e.isCloud) "Roblox" else "Local")
        }
        val ids = list.filter { it.isCloud && it.universeId != 0L && it.universeId !in StartCache.icons }.map { it.universeId }.distinct()
        if (ids.isNotEmpty()) {
            val myGen = gen
            net.execute {
                val icons = try { RobloxCloud.gameIcons(ids) } catch (_: Exception) { emptyMap() }
                StartCache.icons.putAll(icons)
                main.post {
                    if (myGen != gen) return@post
                    for ((e, v) in cards) {
                        val url = icons[e.universeId] ?: continue
                        bindIcon(((v.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView), url, "Place")
                    }
                }
            }
        }
        return cards
    }

    private fun openRecent(e: Recents.Entry) {
        if (e.isCloud) {
            if (RobloxApi.isGuest(act)) { AccountUi.signIn(act) { openRecent(e) }; return }
            AccountUi.openPlace(act, actions, null, RobloxCloud.Place(e.placeId, e.name, e.universeId), null)
        } else host.openRecentFile(e)
    }

    // ------------------------------------------------------------------ My Games / Shared With Me

    private enum class Kind(val key: String) { MINE("games"), SHARED("shared") }

    private fun loadGames(kind: Kind): List<RobloxCloud.Game> {
        val user = RobloxCloud.cachedUser(act) ?: RobloxCloud.currentUser(act)
        val list = if (kind == Kind.MINE) RobloxCloud.userGames(act, user) else RobloxCloud.sharedGames(act, user)
        StartCache.icons.putAll(RobloxCloud.gameIcons(list.map { it.universeId }.filter { it !in StartCache.icons }))
        StartCache.putGames(act, RobloxApi.userId(act), list, kind.key)
        return list
    }

    private fun gameCard(game: RobloxCloud.Game, showOwner: Boolean = false): LinearLayout {
        val modified = if (game.updated.isNotEmpty()) "Modified " + AccountUi.prettyDate(game.updated).substringBefore("  ") else ""
        val by = when {
            showOwner && game.owner.isNotEmpty() -> "@" + game.owner
            game.updated.isEmpty() -> game.owner
            else -> ""
        }
        val sub = listOf(modified, by).filter { it.isNotEmpty() }.joinToString("\n")
        return card(game.name, sub, StartCache.icons[game.universeId], "Place", {
            // Like Studio: opening a game opens its start place (pick one if there are several).
            AccountUi.places(act, studio, actions, game) { gm, p -> AccountUi.openPlace(act, actions, gm, p, null) }
        }, { AccountUi.places(act, studio, actions, game, null) })
    }

    private fun games(col: LinearLayout, kind: Kind) {
        val mine = kind == Kind.MINE
        pageHeader(col, if (mine) "My Experiences" else "Shared With Me",
            if (mine) "Experiences you own. Tap one to edit it; use ⋮ for downloads and version history."
            else "Experiences other creators added you to as a collaborator.", true) { show(page) }
        if (RobloxApi.isGuest(act)) {
            message(col, "You're using Lemonade as a guest. Sign in to see " + (if (mine) "the experiences you own." else "the experiences shared with you."), "Sign in with Roblox") { AccountUi.signIn(act) }
            return
        }
        val myGen = gen
        val uid = RobloxApi.userId(act)
        // Show the last list right away (no spinner wait), then refresh it.
        val cached = StartCache.games(act, uid, kind.key)
        val holder = LinearLayout(act).apply { orientation = VERTICAL }
        col.addView(holder, Ui.lp(Ui.MATCH, Ui.WRAP))
        val empty = if (mine) "You don't own any experiences yet. Create one on create.roblox.com (or File → Publish to Roblox As → Create new game) and it will show up here."
            else "Nothing has been shared with you yet. When someone adds you as a collaborator on their experience (Game Settings → Permissions / Collaborators), it shows up here."
        if (cached != null) showGames(holder, cached, empty, !mine)
        val spinner = if (cached == null) loading(col) else null
        net.execute {
            val r = try { Result.success(loadGames(kind)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                if (myGen != gen) return@post
                spinner?.let { col.removeView(it) }
                r.fold({ list ->
                    holder.removeAllViews()
                    grids.removeAll { it.parent == null || it.parent === holder }
                    showGames(holder, list, empty, !mine)
                    applyFilter()
                }) { e -> if (cached == null) error(col, e) }
            }
        }
    }

    private fun error(col: LinearLayout, e: Throwable) {
        if (e is AuthRequiredException) message(col, e.message ?: "Sign in again.", "Sign in") { AccountUi.signIn(act) }
        else message(col, "Couldn't load this from Roblox.\n${e.message ?: e}", "Try again", "PluginsFolder") { show(page) }
    }

    private fun showGames(col: LinearLayout, list: List<RobloxCloud.Game>, empty: String, showOwner: Boolean = false) {
        if (list.isEmpty()) { message(col, empty); return }
        val g = grid(col)
        for (game in list) g.addView(gameCard(game, showOwner))
    }

    // ------------------------------------------------------------------ Group Games

    private fun groupGames(col: LinearLayout) {
        pageHeader(col, "Group Experiences", "Experiences in your groups that you can edit.", true) { show(page) }
        if (RobloxApi.isGuest(act)) {
            message(col, "You're using Lemonade as a guest. Sign in to see the group experiences you can edit.", "Sign in with Roblox") { AccountUi.signIn(act) }
            return
        }
        val myGen = gen
        val user = RobloxCloud.cachedUser(act)
        val uid = RobloxApi.userId(act)
        val view = GroupsView(col)
        val cached = StartCache.groups(act, uid)
        if (cached != null) view.set(cached.second, cached.first, done = false)
        else view.status("Loading your groups…", spin = true)
        net.execute {
            try {
                val gl = RobloxCloud.allGroups(act, user ?: RobloxCloud.currentUser(act))
                val groupList = gl.groups.take(150)
                // Group icons for the chips, while the games load.
                net.execute {
                    val icons = try { RobloxCloud.groupIcons(groupList.map { it.id }.filter { it !in StartCache.groupIcons }.take(100)) } catch (_: Exception) { emptyMap() }
                    StartCache.groupIcons.putAll(icons)
                    if (icons.isNotEmpty()) main.post { if (myGen == gen) view.redrawChips() }
                }
                // Each group is loaded and checked on its own, a few at once (Roblox rate-limits
                // bigger bursts), and shows up as soon as it's ready.
                val found = java.util.concurrent.ConcurrentHashMap<Long, List<RobloxCloud.Game>>()
                val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
                val checked = java.util.concurrent.atomic.AtomicInteger()
                val authError = java.util.concurrent.atomic.AtomicReference<Exception?>()
                val pool = Executors.newFixedThreadPool(4)
                val previous = cached?.second.orEmpty().associate { it.first.id to it.second }
                fun snapshot() = groupList.mapNotNull { gp ->
                    // A group that couldn't be checked this time keeps what it showed before.
                    (found[gp.id] ?: previous[gp.id]?.takeIf { gp.id in failed })?.takeIf { it.isNotEmpty() }?.let { gp to it }
                }
                for (gp in groupList) pool.execute {
                    if (authError.get() != null || myGen != gen) return@execute
                    try {
                        found[gp.id] = RobloxCloud.editableGroupGames(act, gp, gp.id in gl.studioListed)
                    } catch (e: AuthRequiredException) { authError.set(e) } catch (_: Exception) { failed.add(gp.id) }
                    val n = checked.incrementAndGet()
                    val snap = snapshot()
                    main.post {
                        if (myGen != gen) return@post
                        // With a cached list on screen, keep it until the refresh is complete.
                        if (cached == null) view.set(snap, groupList.size, done = false)
                        if (n < groupList.size) view.status("Checked $n of ${groupList.size} groups…", spin = true)
                    }
                }
                pool.shutdown()
                pool.awaitTermination(4, java.util.concurrent.TimeUnit.MINUTES)
                authError.get()?.let { throw it }
                val result = snapshot()
                StartCache.putGroups(act, uid, groupList.size, result)
                val missing = (groupList.indices).count { groupList[it].id in failed && found[groupList[it].id] == null }
                main.post {
                    if (myGen != gen) return@post
                    view.set(result, groupList.size, done = true)
                    if (missing > 0) view.status("Couldn't check $missing group${if (missing == 1) "" else "s"} — Roblox was busy. Tap to try again.", spin = false) { show(page) }
                }
            } catch (e: Exception) {
                main.post { if (myGen == gen) { view.status(null, false); if (cached == null) error(col, e) } }
            }
        }
    }

    /**
     * Groups with at least one experience this account can edit. Groups are added as they finish
     * loading; the selected group's games stay put while more groups come in.
     */
    private inner class GroupsView(col: LinearLayout) {
        private val statusRow = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, dp(6f)) }
        private val statusText = Ui.text(act, "", 12f, subColor).apply { isSingleLine = false }
        private val statusSpin = StudioSpinner(act)
        private val chips = LinearLayout(act).apply { orientation = HORIZONTAL }
        private val area = LinearLayout(act).apply { orientation = VERTICAL }
        private var groups: List<RobloxCloud.Group> = emptyList()
        private var gamesOf: Map<Long, List<RobloxCloud.Game>> = emptyMap()
        private var shownGroup = 0L
        private var shownGames: List<Long> = emptyList()

        init {
            statusRow.addView(statusSpin, Ui.lp(dp(16f), dp(16f)).apply { rightMargin = dp(8f) })
            statusRow.addView(statusText, Ui.lp(0, Ui.WRAP, 1f))
            col.addView(statusRow, Ui.lp(Ui.MATCH, Ui.WRAP))
            col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(4f) })
            col.addView(area, Ui.lp(Ui.MATCH, Ui.WRAP))
            status(null, false)
        }

        fun status(text: String?, spin: Boolean, onTap: (() -> Unit)? = null) {
            statusRow.visibility = if (text == null) View.GONE else View.VISIBLE
            statusText.text = text ?: ""
            statusText.setTextColor(if (onTap != null) linkColor else subColor)
            if (onTap != null) statusRow.setOnClickListener { onTap() } else { statusRow.setOnClickListener(null); statusRow.isClickable = false }
            statusSpin.visibility = if (spin) View.VISIBLE else View.GONE
        }

        fun set(editable: List<Pair<RobloxCloud.Group, List<RobloxCloud.Game>>>, inGroups: Int, done: Boolean) {
            groups = editable.map { it.first }
            gamesOf = editable.associate { it.first.id to it.second }
            if (done) status(null, false)
            if (groups.isEmpty()) {
                chips.removeAllViews()
                if (done) {
                    area.removeAllViews()
                    message(area, if (inGroups == 0) "You aren't in any groups yet." else "None of your groups have experiences you can edit. Ask a group owner to give your role permission to edit group experiences.")
                }
                return
            }
            if (selectedGroup == 0L || groups.none { it.id == selectedGroup }) selectedGroup = groups.first().id
            drawChips()
            val now = gamesOf[selectedGroup].orEmpty().map { it.universeId }
            if (shownGroup != selectedGroup || now != shownGames) select(groups.first { it.id == selectedGroup })
        }

        fun redrawChips() { if (groups.isNotEmpty()) drawChips() }

        private fun drawChips() {
            chips.removeAllViews()
            for (x in groups) {
                val on = x.id == selectedGroup
                val chip = LinearLayout(act).apply {
                    orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(5f), dp(5f), dp(14f), dp(5f))
                    background = Ui.rippleBg(act, if (on) selColor else cardColor, 18f)
                    isClickable = true
                    setOnClickListener { select(x) }
                }
                val ic = ImageView(act).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.HOVER) }
                    clipToOutline = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                val url = StartCache.groupIcons[x.id]
                val bmp = url?.let { ImageLoader.getKey("url:$it") }
                if (bmp != null) ic.setImageBitmap(bmp) else {
                    ic.setImageDrawable(Icons.general(act, "Group")); ic.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                    if (url != null) iconViews.getOrPut("url:$url") { ArrayList() }.add(ic)
                }
                chip.addView(ic, Ui.lp(dp(26f), dp(26f)).apply { rightMargin = dp(8f) })
                chip.addView(Ui.text(act, x.name, 13f, fgColor, on))
                chips.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = dp(8f); bottomMargin = dp(6f) })
            }
        }

        fun select(gp: RobloxCloud.Group) {
            selectedGroup = gp.id
            shownGroup = gp.id
            val mine = gamesOf[gp.id].orEmpty()
            shownGames = mine.map { it.universeId }
            drawChips()
            area.removeAllViews()
            grids.clear()
            area.addView(Ui.text(act, (if (gp.role.isNotEmpty()) "Your role: ${gp.role}  ·  " else "") + "${mine.size} experience${if (mine.size == 1) "" else "s"}", 12f, subColor).apply {
                setPadding(0, dp(6f), 0, dp(10f))
            })
            val holder = LinearLayout(act).apply { orientation = VERTICAL }
            area.addView(holder, Ui.lp(Ui.MATCH, Ui.WRAP))
            // Draw at once with the icons we already have; fetch the rest and redraw.
            showGames(holder, mine, "This group has no experiences you can edit.")
            applyFilter()
            val missing = mine.map { it.universeId }.filter { it !in StartCache.icons }
            if (missing.isEmpty()) return
            val myGen = gen
            net.execute {
                val icons = try { RobloxCloud.gameIcons(missing) } catch (_: Exception) { emptyMap() }
                StartCache.icons.putAll(icons)
                main.post {
                    if (myGen != gen || selectedGroup != gp.id || icons.isEmpty()) return@post
                    holder.removeAllViews()
                    grids.clear()
                    showGames(holder, mine, "This group has no experiences you can edit.")
                    applyFilter()
                }
            }
        }
    }

    companion object {
        const val HOME = "Home"
        const val RECENT = "Recent"
        const val EXPERIENCES = "Experiences"
        const val TEMPLATES = "Templates"
    }
}

/**
 * The start page's last lists of games, kept in memory and on disk so My Games / Shared With Me /
 * Group Games show immediately (then refresh) instead of waiting on Roblox every time.
 */
object StartCache {
    val icons: MutableMap<Long, String> = java.util.concurrent.ConcurrentHashMap()
    val groupIcons: MutableMap<Long, String> = java.util.concurrent.ConcurrentHashMap()
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("start_cache", android.content.Context.MODE_PRIVATE)

    /** Drops the saved lists (a game was created, so they're out of date). */
    fun forget(ctx: android.content.Context) = prefs(ctx).edit().clear().apply()

    private fun gameJson(g: RobloxCloud.Game) = org.json.JSONObject().put("u", g.universeId).put("n", g.name).put("r", g.rootPlaceId)
        .put("t", g.updated).put("o", g.owner).put("g", g.groupId)
    private fun gameOf(o: org.json.JSONObject) = RobloxCloud.Game(o.optLong("u"), o.optString("n"), o.optLong("r"), o.optString("t"), o.optString("o"), o.optLong("g"))

    /** A saved list ([kind] "games" = owned, "shared" = shared with you), or null if never loaded. */
    fun games(ctx: android.content.Context, uid: Long, kind: String = "games"): List<RobloxCloud.Game>? = try {
        val a = org.json.JSONArray(prefs(ctx).getString("${kind}_$uid", null) ?: return null)
        (0 until a.length()).map { gameOf(a.getJSONObject(it)) }
    } catch (_: Exception) { null }

    fun putGames(ctx: android.content.Context, uid: Long, list: List<RobloxCloud.Game>, kind: String = "games") {
        val a = org.json.JSONArray(); list.forEach { a.put(gameJson(it)) }
        prefs(ctx).edit().putString("${kind}_$uid", a.toString()).apply()
    }

    /** (groups the user is in, editable groups with their games), or null if never loaded. */
    fun groups(ctx: android.content.Context, uid: Long): Pair<Int, List<Pair<RobloxCloud.Group, List<RobloxCloud.Game>>>>? = try {
        val o = org.json.JSONObject(prefs(ctx).getString("groups_$uid", null) ?: return null)
        val a = o.getJSONArray("list")
        o.optInt("in") to (0 until a.length()).map { i ->
            val g = a.getJSONObject(i)
            val games = g.getJSONArray("games")
            RobloxCloud.Group(g.optLong("id"), g.optString("name"), g.optString("role"), g.optInt("rank")) to (0 until games.length()).map { gameOf(games.getJSONObject(it)) }
        }
    } catch (_: Exception) { null }

    fun putGroups(ctx: android.content.Context, uid: Long, inGroups: Int, list: List<Pair<RobloxCloud.Group, List<RobloxCloud.Game>>>) {
        val a = org.json.JSONArray()
        for ((g, games) in list) a.put(org.json.JSONObject().put("id", g.id).put("name", g.name).put("role", g.role).put("rank", g.rank)
            .put("games", org.json.JSONArray().apply { games.forEach { put(gameJson(it)) } }))
        prefs(ctx).edit().putString("groups_$uid", org.json.JSONObject().put("in", inGroups).put("list", a).toString()).apply()
    }
}
