package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.leaf.lemonade.audio.AudioEngine
import com.leaf.lemonade.net.RobloxApi
import java.util.concurrent.Executors

/** Lays children out left to right, wrapping onto new lines (for filter chips). */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    var gap = Ui.dp(ctx, 6f)
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec).let { if (it == 0) Int.MAX_VALUE else it } - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            x += c.measuredWidth + gap
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + lineH + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}

/** Round play / stop button glyph for audio rows. */
class PlayGlyph(private var playing: Boolean, private var loading: Boolean = false) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(play: Boolean, load: Boolean) { playing = play; loading = load; invalidateSelf() }
    override fun draw(c: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        p.style = Paint.Style.FILL
        p.color = if (playing || loading) Ui.ACCENT else Ui.blend(Ui.PANEL, if (Ui.LIGHT) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.14f)
        c.drawCircle(cx, cy, r, p)
        p.color = if (playing || loading || !Ui.LIGHT) 0xFFFFFFFF.toInt() else 0xFF222222.toInt()
        if (loading) {
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f
            c.drawArc(cx - r * 0.45f, cy - r * 0.45f, cx + r * 0.45f, cy + r * 0.45f, 0f, 270f, false, p)
        } else if (playing) {
            // Pause bars: tapping a playing preview pauses it.
            val s = r * 0.34f; val w = r * 0.22f
            c.drawRect(cx - s, cy - s, cx - s + w, cy + s, p)
            c.drawRect(cx + s - w, cy - s, cx + s, cy + s, p)
        } else {
            val s = r * 0.42f
            val path = Path().apply { moveTo(cx - s * 0.6f, cy - s); lineTo(cx + s, cy); lineTo(cx - s * 0.6f, cy + s); close() }
            c.drawPath(path, p)
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The Toolbox's Audio section, like Studio's: a Discover page (top charts, genres, vibes and
 * sound effect categories), then results with Type / Sort / Length filters. Each row previews
 * the audio (tap the play button) and can be inserted as a Sound.
 */
@SuppressLint("ViewConstructor")
class AudioBrowser(ctx: Context, private val onInsert: (RobloxApi.AudioItem) -> Unit, private val needSignIn: (() -> Unit) -> Unit) : LinearLayout(ctx) {
    private val net = Executors.newSingleThreadExecutor()
    private val content = FrameLayout(ctx)
    private val filterBar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val items = ArrayList<RobloxApi.AudioItem>()
    private val adapter = Adapter()
    private var token: String? = null
    private var loading = false
    private var ended = false
    private var gen = 0
    private var useLegacy = false
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var results: View? = null

    // Query state
    var keyword = ""
        private set
    private var type: String? = null
    private var sort = "Relevance"
    private var lengthIdx = 0
    private var tag: String? = null
    private var chart = "None"
    private var discover = true

    private val sorts = listOf("Relevance" to "Relevance", "Trending" to "Trending", "Top" to "Top rated", "UpdatedTime" to "Most recent", "AudioDuration" to "Length")
    private val lengths = listOf("Any length" to (0 to null), "Under 10s" to (0 to 10), "10s – 1 min" to (10 to 60), "1 – 3 min" to (60 to 180), "Over 3 min" to (180 to null))
    private val genres = listOf("Ambient", "Classical", "Country", "Electronic", "Funk", "Hip Hop", "Jazz", "Latin", "Lo-fi", "Metal", "Orchestral", "Pop", "R&B", "Reggae", "Rock", "Synthwave", "World")
    private val vibes = listOf("Calm", "Chill", "Dark", "Epic", "Funny", "Happy", "Intense", "Mysterious", "Romantic", "Sad", "Scary", "Upbeat")
    private val sfx = listOf("UI", "Footsteps", "Explosions", "Weapons", "Impacts", "Whooshes", "Magic", "Nature", "Ambience", "Vehicles", "Animals", "Voices", "Sci-fi", "Horror", "Cartoon", "Water", "Fire", "Doors", "Coins", "Alarms")

    /** The audio whose seek bar is being dragged (its row isn't refreshed meanwhile). */
    private var draggingId: Long? = null
    private val previewListener: () -> Unit = { post { if (draggingId == null) adapter.notifyDataSetChanged() } }

    init {
        orientation = VERTICAL
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(filterBar) }, Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(content, Ui.lp(Ui.MATCH, 0, 1f))
        showDiscover()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); AudioEngine.previewListeners.add(previewListener) }
    override fun onDetachedFromWindow() { AudioEngine.previewListeners.remove(previewListener); super.onDetachedFromWindow() }

    private fun dp(v: Float) = Ui.dp(context, v)

    private fun chip(label: String, on: Boolean, dropdown: Boolean = false, onClick: (View) -> Unit): TextView =
        Ui.text(context, label + if (dropdown) "  ▾" else "", 12f, if (on) 0xFFFFFFFF.toInt() else Ui.TEXT).apply {
            setPadding(dp(11f), dp(5f), dp(11f), dp(5f))
            background = GradientDrawable().apply {
                setColor(if (on) Ui.ACCENT else Ui.blend(Ui.PANEL, if (Ui.LIGHT) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.08f))
                cornerRadius = dp(14f).toFloat()
                if (!on) setStroke(dp(1f), Ui.blend(Ui.PANEL, if (Ui.LIGHT) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.18f))
            }
            setOnClickListener(onClick)
        }

    /** Studio-style filter bar: Discover, Type, Sort, Length, and the chosen genre/vibe/category. */
    private fun buildFilters() {
        filterBar.removeAllViews()
        filterBar.setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        fun add(v: View) = filterBar.addView(v, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = dp(6f) })
        add(chip("Discover", discover) { tag = null; chart = "None"; keyword = ""; showDiscover() })
        add(chip(when (type) { "Music" -> "Music"; "SoundEffect" -> "Sound Effects"; else -> "Type" }, type != null, true) { v ->
            menu(v, listOf("All audio", "Music", "Sound Effects")) { i -> type = when (i) { 1 -> "Music"; 2 -> "SoundEffect"; else -> null }; showResults() }
        })
        add(chip("Sort: " + sorts.first { it.first == sort }.second, sort != "Relevance", true) { v ->
            menu(v, sorts.map { it.second }) { i -> sort = sorts[i].first; showResults() }
        })
        add(chip(lengths[lengthIdx].first, lengthIdx != 0, true) { v -> menu(v, lengths.map { it.first }) { i -> lengthIdx = i; showResults() } })
        tag?.let { t -> add(chip("$t  ✕", true) { tag = null; if (keyword.isBlank() && chart == "None") showDiscover() else showResults() }) }
        if (chart != "None") add(chip("Top charts: ${chartLabel(chart)}  ✕", true) { chart = "None"; if (keyword.isBlank() && tag == null) showDiscover() else showResults() })
    }

    private fun chartLabel(c: String) = when (c) { "Current" -> "Now"; "Week" -> "This week"; "Month" -> "This month"; "Year" -> "This year"; else -> c }

    private fun menu(anchor: View, labels: List<String>, pick: (Int) -> Unit) {
        val m = StudioMenu(context, anchor)
        labels.forEachIndexed { i, s -> m.add(s) { pick(i) } }
        m.show()
    }

    fun search(q: String) {
        keyword = q.trim()
        if (keyword.isEmpty() && tag == null && chart == "None" && type == null) showDiscover() else showResults()
    }

    // ------------------------------------------------------------------ Discover

    private fun showDiscover() {
        discover = true
        buildFilters()
        content.removeAllViews()
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(10f), dp(4f), dp(10f), dp(16f)) }
        fun section(title: String) = box.addView(Ui.text(context, title, 13f, Ui.TEXT, true).apply { setPadding(0, dp(12f), 0, dp(6f)) })
        fun flow(labels: List<String>, onPick: (String) -> Unit) {
            val f = FlowLayout(context)
            for (l in labels) f.addView(chip(l, false) { onPick(l) })
            box.addView(f, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        // Big entry cards, like Studio's Music / Sound Effects tiles.
        val cards = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun card(label: String, sub: String, color: Int, onClick: () -> Unit) {
            val c = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, Ui.blend(color, 0xFF000000.toInt(), 0.35f))).apply { cornerRadius = dp(8f).toFloat() }
                setOnClickListener { onClick() }
            }
            c.addView(Ui.text(context, label, 15f, 0xFFFFFFFF.toInt(), true))
            c.addView(Ui.text(context, sub, 11f, 0xDDFFFFFF.toInt()).apply { isSingleLine = false })
            cards.addView(c, Ui.lp(0, Ui.WRAP, 1f).apply { rightMargin = dp(8f) })
        }
        card("Music", "Licensed tracks for your experience", 0xFF7B3FE4.toInt()) { type = "Music"; tag = null; showResults() }
        card("Sound Effects", "UI, impacts, ambience and more", 0xFF0C8F6E.toInt()) { type = "SoundEffect"; tag = null; showResults() }
        box.addView(cards, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        section("Top charts")
        flow(listOf("Now", "This week", "This month", "This year")) { l ->
            chart = when (l) { "Now" -> "Current"; "This week" -> "Week"; "This month" -> "Month"; else -> "Year" }
            type = "Music"; tag = null; showResults()
        }
        section("Genres")
        flow(genres) { g -> tag = g; type = "Music"; showResults() }
        section("Vibes")
        flow(vibes) { v -> tag = v; type = "Music"; showResults() }
        section("Sound effects")
        flow(sfx) { s -> tag = s; type = "SoundEffect"; showResults() }
        box.addView(Ui.text(context, "Audio comes from the Roblox Creator Store. Previewing and playing audio needs a signed-in account (Roblox only serves audio files to signed-in users).", 11f, Ui.DIM).apply {
            isSingleLine = false; setPadding(0, dp(14f), 0, 0)
        })
        content.addView(ScrollView(context).apply { addView(box) })
    }

    // ------------------------------------------------------------------ results

    private fun showResults() {
        discover = false
        buildFilters()
        content.removeAllViews()
        val v = results ?: buildResults().also { results = it }
        (v.parent as? ViewGroup)?.removeView(v)
        content.addView(v)
        gen++
        items.clear(); token = null; ended = false; loading = false
        adapter.notifyDataSetChanged()
        loadMore()
    }

    private fun buildResults(): View {
        val host = FrameLayout(context)
        val rv = RecyclerView(context)
        val lm = LinearLayoutManager(context)
        rv.layoutManager = lm
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) { if (lm.findLastVisibleItemPosition() >= items.size - 5) loadMore() }
        })
        host.addView(rv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        progress = ProgressBar(context).apply { isIndeterminate = true; visibility = GONE }
        host.addView(progress, FrameLayout.LayoutParams(dp(34f), dp(34f), Gravity.CENTER))
        status = Ui.text(context, "", 12f, Ui.DIM).apply { isSingleLine = false; gravity = Gravity.CENTER; setPadding(dp(16f), 0, dp(16f), 0) }
        host.addView(status, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER))
        return host
    }

    private fun query(): RobloxApi.AudioQuery {
        val (mn, mx) = lengths[lengthIdx].second
        val words = listOfNotNull(keyword.takeIf { it.isNotBlank() }, tag).joinToString(" ")
        return RobloxApi.AudioQuery(words, type, sort, mn, mx, chart)
    }

    private fun loadMore() {
        if (loading || ended) return
        loading = true
        val g = gen
        val q = query()
        val tok = token
        progress.visibility = if (items.isEmpty()) VISIBLE else GONE
        status.text = ""
        net.execute {
            var result: RobloxApi.AudioPage? = null
            var err: Exception? = null
            try {
                result = if (useLegacy) RobloxApi.searchAudioLegacy(q.keyword, tok) else RobloxApi.searchAudio(context, q, tok)
            } catch (e: Exception) {
                err = e
                // The newer search can be unavailable; fall back to the classic marketplace search.
                if (!useLegacy) try { result = RobloxApi.searchAudioLegacy(q.keyword, tok); useLegacy = true } catch (_: Exception) {}
            }
            post {
                if (g != gen) return@post
                if (result == null) {
                    loading = false; progress.visibility = GONE
                    if (items.isEmpty()) status.text = "Couldn't reach the Creator Store.\n${err?.message ?: ""}"
                    return@post
                }
                val result = result!!
                val start = items.size
                items.addAll(result.items)
                adapter.notifyItemRangeInserted(start, result.items.size)
                token = result.nextToken
                ended = result.nextToken == null || result.items.isEmpty()
                loading = false
                progress.visibility = GONE
                if (items.isEmpty()) status.text = "No audio found." + if (lengthIdx != 0 || type != null) "\nTry removing a filter." else ""
            }
        }
    }

    private fun fmtTime(sec: Int): String = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)
    private fun fmtClock(sec: Float): String { val s = sec.toInt().coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

    private inner class VH(val row: LinearLayout, val play: ImageView, val glyph: PlayGlyph, val title: TextView, val sub: TextView,
                           val dur: TextView, val seekRow: LinearLayout, val seek: android.widget.SeekBar, val elapsed: TextView, val total: TextView,
                           val insert: TextView) : RecyclerView.ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val outer = LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP)
                setPadding(dp(8f), dp(6f), dp(8f), dp(4f))
                background = Ui.rippleBg(ctx, Ui.PANEL, 0f)
            }
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val glyph = PlayGlyph(false)
            val play = ImageView(ctx).apply { setImageDrawable(glyph); setPadding(dp(2f), dp(2f), dp(2f), dp(2f)) }
            row.addView(play, Ui.lp(dp(34f), dp(34f)).apply { rightMargin = dp(10f) })
            val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
            val title = Ui.text(ctx, "", 13f, Ui.TEXT, true).apply { ellipsize = android.text.TextUtils.TruncateAt.END }
            val sub = Ui.text(ctx, "", 11f, Ui.DIM).apply { ellipsize = android.text.TextUtils.TruncateAt.END }
            texts.addView(title); texts.addView(sub)
            row.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
            val dur = Ui.text(ctx, "", 11f, Ui.DIM).apply { setPadding(dp(6f), 0, dp(6f), 0) }
            row.addView(dur)
            val insert = Ui.button(ctx, "Insert", Ui.ACCENT) {}.apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 12f; setPadding(dp(10f), dp(4f), dp(10f), dp(4f)) }
            row.addView(insert)
            outer.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            // Seek bar for the audio being previewed: drag to jump anywhere in the track.
            val seekRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE }
            val elapsed = Ui.text(ctx, "0:00", 11f, Ui.DIM).apply { gravity = Gravity.END }
            val seek = android.widget.SeekBar(ctx).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                setPadding(dp(12f), 0, dp(12f), 0)
            }
            val total = Ui.text(ctx, "", 11f, Ui.DIM)
            seekRow.addView(elapsed, Ui.lp(dp(38f), Ui.WRAP))
            seekRow.addView(seek, Ui.lp(0, dp(30f), 1f))
            seekRow.addView(total, Ui.lp(dp(38f), Ui.WRAP))
            outer.addView(seekRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(2f); leftMargin = dp(36f) })
            return VH(outer, play, glyph, title, sub, dur, seekRow, seek, elapsed, total, insert)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            val label = it.title.ifBlank { it.name }
            h.title.text = label
            val kind = when { it.type.contains("Music", true) -> "Music"; it.type.contains("Sound", true) -> "Sound effect"; else -> "" }
            val by = it.artist.ifBlank { it.creator }
            h.sub.text = listOf(by, kind, it.album).filter { s -> s.isNotBlank() }.joinToString("  ·  ")
            h.dur.text = fmtTime(it.seconds)
            val p = AudioEngine.preview
            val mine = p != null && p.id == it.id
            val playing = mine && p!!.state == AudioEngine.PreviewState.PLAYING
            val load = mine && p!!.state == AudioEngine.PreviewState.LOADING
            h.glyph.set(playing, load)
            h.play.invalidate()
            if (mine && p!!.state != AudioEngine.PreviewState.ERROR) {
                h.seekRow.visibility = VISIBLE
                val len = if (p.length > 0f) p.length else it.seconds.toFloat()
                val ready = p.length > 0f
                h.seek.isEnabled = ready
                h.seek.max = (len * 10f).toInt().coerceAtLeast(1)
                if (draggingId != it.id) {
                    h.seek.progress = (p.position * 10f).toInt()
                    h.elapsed.text = fmtClock(p.position)
                }
                h.total.text = fmtClock(len)
                val id = it.id
                h.seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, fromUser: Boolean) { if (fromUser) h.elapsed.text = fmtClock(v / 10f) }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) { draggingId = id }
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                        draggingId = null
                        AudioEngine.seekPreview(id, sb.progress / 10f)
                    }
                })
            } else {
                h.seek.setOnSeekBarChangeListener(null)
                h.seekRow.visibility = GONE
            }
            if (mine && p!!.state == AudioEngine.PreviewState.ERROR) h.sub.text = p.error ?: "Can't play this audio"
            h.play.setOnClickListener { _ -> needSignIn { AudioEngine.togglePreview(it.id) } }
            h.insert.setOnClickListener { _ -> onInsert(it) }
            h.row.setOnClickListener { _ -> needSignIn { AudioEngine.togglePreview(it.id) } }
            h.row.setOnLongClickListener { _ ->
                Dialogs.themed(context).setTitle(label).setMessage(
                    "Artist: ${by.ifBlank { "Unknown" }}\nType: ${kind.ifBlank { "Audio" }}\nLength: ${fmtTime(it.seconds).ifEmpty { "?" }}\nCreator: ${it.creator}${if (it.verified) " (verified)" else ""}\nAsset ID: ${it.id}"
                ).setPositiveButton("Insert") { _, _ -> onInsert(it) }.setNegativeButton("Close", null).show()
                true
            }
        }
    }
}
