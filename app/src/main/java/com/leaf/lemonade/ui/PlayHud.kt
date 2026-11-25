package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.engine.PlaySession
import com.leaf.lemonade.model.PartData
import com.leaf.lemonade.model.Vector3

/**
 * The in-game Roblox core UI drawn over a playtest: top bar (menu + chat buttons), chat window
 * with chat bar, bubble chat, leaderboard (player list with leaderstats), health bar, the shift
 * lock cursor, and the Roblox menu (People / Settings, Reset Character, Leave, Resume).
 */
@SuppressLint("ViewConstructor")
class PlayHud(ctx: Context, private val studio: Studio) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val bold: Typeface = Fonts.forFamily("BuilderSans", 700, false).typeface
    private val regular: Typeface = Fonts.forFamily("BuilderSans", 400, false).typeface

    private val layer = Layer(ctx)
    private val menuBtn = TopButton(ctx, 0)
    private val chatBtn = TopButton(ctx, 1)
    private val listBtn = TopButton(ctx, 2)
    private val moreBtn = TopButton(ctx, 3)
    private val emoteBtn = TopButton(ctx, 4)
    /** Roblox's unibar: one pill with the menu, chat and "more" (leaderboard, emotes). */
    private val unibar = LinearLayout(ctx)
    private var unibarOpen = false
    private val chatBox = LinearLayout(ctx)
    private val chatLog = ChatLog(ctx)
    private val chatBar: EditText
    private var menu: View? = null
    private var chatOpen = true
    private var playerListOpen = true
    private var lastChatActivity = 0L
    private var seenChat = -1

    init {
        visibility = GONE
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val top = dp(8f)
        // Roblox's top bar: a dark capsule with the Roblox button, a divider, then chat and "more".
        unibar.orientation = LinearLayout.HORIZONTAL
        unibar.gravity = Gravity.CENTER_VERTICAL
        unibar.setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        unibar.background = GradientDrawable().apply { setColor(0xBF111216.toInt()); cornerRadius = dp(24f).toFloat() }
        unibar.addView(menuBtn, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        unibar.addView(View(ctx).apply { setBackgroundColor(0x40FFFFFF) }, LinearLayout.LayoutParams(dp(1f), dp(22f)).apply { leftMargin = dp(3f); rightMargin = dp(3f) })
        for (b in listOf(chatBtn, listBtn, emoteBtn, moreBtn)) unibar.addView(b, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        addView(unibar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12f); topMargin = top })
        listBtn.visibility = GONE; emoteBtn.visibility = GONE
        moreBtn.active = false
        menuBtn.setOnClickListener { toggleMenu() }
        chatBtn.setOnClickListener { chatOpen = !chatOpen; if (!chatOpen) hideKeyboard(); updateChatVisibility(); lastChatActivity = System.currentTimeMillis() }
        listBtn.setOnClickListener { togglePlayerList() }
        moreBtn.setOnClickListener { unibarOpen = !unibarOpen; updateUnibar() }
        emoteBtn.setOnClickListener { showEmotes() }
        // Phones start with the player list closed, like the Roblox app.
        playerListOpen = resources.configuration.smallestScreenWidthDp >= 600

        chatBox.orientation = LinearLayout.VERTICAL
        chatBox.background = GradientDrawable().apply { setColor(0x4D000000); cornerRadius = dp(8f).toFloat() }
        chatBox.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        chatBox.addView(chatLog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        chatBar = EditText(ctx).apply {
            hint = "Tap here to chat"
            setHintTextColor(0xB3FFFFFF.toInt()); setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f; typeface = regular
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = GradientDrawable().apply { setColor(0x66000000); cornerRadius = dp(6f).toFloat() }
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { send(); true } else false
            }
            setOnFocusChangeListener { _, f -> if (f) lastChatActivity = System.currentTimeMillis() }
        }
        chatBox.addView(chatBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
        val phone = resources.configuration.smallestScreenWidthDp < 600
        addView(chatBox, LayoutParams(dp(if (phone) 270f else 330f), dp(if (phone) 130f else 165f)).apply { leftMargin = dp(12f); topMargin = dp(62f) })
    }

    private fun send() {
        val text = chatBar.text.toString()
        chatBar.setText("")
        if (text.isNotBlank()) synchronized(studio.lock) { studio.play?.sendChat(text) }
        hideKeyboard()
        chatBar.clearFocus()
        lastChatActivity = System.currentTimeMillis()
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(chatBar.windowToken, 0)
    }

    fun onPlayStateChanged() {
        val playing = studio.isPlaying
        visibility = if (playing) VISIBLE else GONE
        if (!playing) { closeMenu(); hideKeyboard(); chatBar.setText("") }
        else { lastChatActivity = System.currentTimeMillis(); seenChat = -1 }
        updateChatVisibility()
    }

    private fun updateChatVisibility() {
        val p = studio.play
        val enabled = p?.coreChat != false
        chatBtn.visibility = if (enabled) VISIBLE else GONE
        chatBox.visibility = if (enabled && chatOpen) VISIBLE else GONE
        chatBtn.active = chatOpen
        chatBtn.invalidate()
    }

    /** Called every frame-ish from the renderer (on the UI thread via post). */
    fun onFrame() {
        if (visibility != VISIBLE) return
        val p = studio.play ?: return
        val v = p.chatVersion
        if (v != seenChat) { seenChat = v; lastChatActivity = System.currentTimeMillis(); chatLog.reload() }
        // The chat window fades when idle, like Roblox's.
        val idle = System.currentTimeMillis() - lastChatActivity > 12_000 && !chatBar.hasFocus()
        chatBox.alpha = if (idle) 0.35f else 1f
        val wantList = if (p.corePlayerList && unibarOpen) VISIBLE else GONE
        if (listBtn.visibility != wantList) updateUnibar()
        if (chatBtn.visibility != (if (p.coreChat) VISIBLE else GONE)) updateChatVisibility()
        layer.invalidate()
    }

    /** Shows the unibar's extra buttons when "more" is open (leaderboard, emotes). */
    private fun updateUnibar() {
        val p = studio.play
        listBtn.visibility = if (unibarOpen && p?.corePlayerList != false) VISIBLE else GONE
        emoteBtn.visibility = if (unibarOpen && p?.character != null) VISIBLE else GONE
        moreBtn.active = unibarOpen
        listBtn.active = playerListOpen
        moreBtn.invalidate(); listBtn.invalidate()
    }

    /** Roblox's emote menu: the default emotes (they stop when you move). */
    private fun showEmotes() {
        val emotes = listOf("Wave" to "wave", "Point" to "point", "Cheer" to "cheer", "Laugh" to "laugh", "Dance" to "dance", "Dance 2" to "dance2", "Dance 3" to "dance3")
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply { setColor(0xE6121315.toInt()); cornerRadius = dp(12f).toFloat() }
            isClickable = true
        }
        val shade = FrameLayout(context).apply { isClickable = true; setOnClickListener { removeView(this) } }
        for ((label, id) in emotes) box.addView(label(label, 15f, b = true).apply {
            setPadding(dp(16f), dp(9f), dp(28f), dp(9f))
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, GradientDrawable().apply { setColor(-1); cornerRadius = dp(8f).toFloat() })
            setOnClickListener { synchronized(studio.lock) { studio.play?.sendChat("/e $id") }; removeView(shade); unibarOpen = false; updateUnibar() }
        })
        shade.addView(box, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12f) + unibar.width - dp(80f); topMargin = dp(54f) })
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun togglePlayerList() { playerListOpen = !playerListOpen; listBtn.active = playerListOpen; listBtn.invalidate(); layer.invalidate() }

    fun focusChat() {
        if (studio.play?.coreChat == false) return
        chatOpen = true; updateChatVisibility()
        chatBar.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(chatBar, 0)
    }

    /** Back button: close the menu first, then the chat keyboard. */
    fun handleBack(): Boolean {
        if (menu != null) { closeMenu(); return true }
        if (chatBar.hasFocus()) { hideKeyboard(); chatBar.clearFocus(); return true }
        return false
    }

    // ------------------------------------------------------------------ top bar buttons

    @SuppressLint("ViewConstructor")
    private inner class TopButton(ctx: Context, val kind: Int) : View(ctx) {
        var active = true
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isClickable = true; contentDescription = when (kind) { 0 -> "Roblox menu"; 1 -> "Chat"; 2 -> "Leaderboard"; 3 -> "More"; else -> "Emotes" } }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            // Inside the unibar pill: a soft highlight when pressed or switched on (more).
            p.style = Paint.Style.FILL
            if (isPressed || (kind == 3 && active)) { p.color = 0x33FFFFFF; c.drawCircle(cx, cy, minOf(width, height) / 2f - dp(2f), p) }
            p.color = 0xFFFFFFFF.toInt()
            p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            when (kind) {
                3 -> { // more: three dots (an X while the bar is expanded)
                    if (active) {
                        p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d
                        val s = 5.5f * d
                        c.drawLine(cx - s, cy - s, cx + s, cy + s, p); c.drawLine(cx + s, cy - s, cx - s, cy + s, p)
                    } else for (i in -1..1) c.drawCircle(cx + i * 6f * d, cy, 2.2f * d, p)
                    return
                }
                4 -> { // emotes: a smiling face
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d
                    c.drawCircle(cx, cy, 9f * d, p)
                    c.drawArc(cx - 5f * d, cy - 3f * d, cx + 5f * d, cy + 5f * d, 20f, 140f, false, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(cx - 3.3f * d, cy - 2.5f * d, 1.4f * d, p); c.drawCircle(cx + 3.3f * d, cy - 2.5f * d, 1.4f * d, p)
                    return
                }
            }
            when (kind) {
                0 -> { // the Roblox logo: a tilted rounded square with a square hole
                    c.save(); c.rotate(15f, cx, cy)
                    val s = 8.5f * d; val h = 2.4f * d
                    val logo = Path().apply {
                        fillType = Path.FillType.EVEN_ODD
                        addRoundRect(RectF(cx - s, cy - s, cx + s, cy + s), 2.4f * d, 2.4f * d, Path.Direction.CW)
                        addRect(cx - h, cy - h, cx + h, cy + h, Path.Direction.CW)
                    }
                    p.style = Paint.Style.FILL
                    c.drawPath(logo, p)
                    c.restore()
                }
                1 -> { // chat: an outlined speech bubble
                    if (!active) p.alpha = 150
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2.1f * d
                    val bubble = Path().apply {
                        addRoundRect(RectF(cx - 9f * d, cy - 7.5f * d, cx + 9f * d, cy + 4.5f * d), 4f * d, 4f * d, Path.Direction.CW)
                    }
                    c.drawPath(bubble, p)
                    p.style = Paint.Style.FILL
                    c.drawPath(Path().apply { moveTo(cx - 5f * d, cy + 3.5f * d); lineTo(cx - 6.5f * d, cy + 9.5f * d); lineTo(cx + 0.5f * d, cy + 3.5f * d); close() }, p)
                    p.alpha = 255
                }
                else -> { // leaderboard: outlined people
                    if (!active) p.alpha = 150
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
                    c.drawCircle(cx - 3f * d, cy - 4f * d, 3.4f * d, p)
                    c.drawArc(cx - 10f * d, cy + 1.5f * d, cx + 4f * d, cy + 15f * d, 200f, 140f, false, p)
                    c.drawCircle(cx + 5.5f * d, cy - 3f * d, 2.6f * d, p)
                    c.drawArc(cx + 1.5f * d, cy + 2.5f * d, cx + 10.5f * d, cy + 11.5f * d, 215f, 110f, false, p)
                    p.alpha = 255
                }
            }
        }
        override fun dispatchSetPressed(pressed: Boolean) { invalidate() }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean { val r = super.onTouchEvent(e); invalidate(); return r }
    }

    // ------------------------------------------------------------------ chat log

    @SuppressLint("ViewConstructor")
    private inner class ChatLog(ctx: Context) : View(ctx) {
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 14f * d; typeface = regular }
        private val shadow = TextPaint(tp).apply { color = 0xCC000000.toInt() }
        private var layouts = listOf<StaticLayout>()
        private var lines = listOf<PlaySession.ChatLine>()
        private var lastW = 0

        fun reload() {
            lines = synchronized(studio.lock) { studio.play?.chatLines?.takeLast(60)?.toList() } ?: emptyList()
            build()
            invalidate()
        }

        private fun build() {
            val w = width - dp(4f)
            if (w <= 0) { layouts = emptyList(); return }
            lastW = width
            layouts = lines.map { l ->
                val sb = SpannableStringBuilder()
                if (l.from != null) {
                    val s = sb.length
                    sb.append("[${l.from}]: ")
                    sb.setSpan(ForegroundColorSpan(l.color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(l.text)
                } else {
                    sb.append(l.text)
                    sb.setSpan(ForegroundColorSpan(l.color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                StaticLayout.Builder.obtain(sb, 0, sb.length, tp, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            }
        }

        override fun onDraw(c: Canvas) {
            if (width != lastW) build()
            var y = height.toFloat()
            c.save(); c.clipRect(0, 0, width, height)
            for (l in layouts.asReversed()) {
                y -= l.height + dp(2f)
                if (y + l.height < 0) break
                c.save(); c.translate(dp(2f).toFloat(), y); l.draw(c); c.restore()
            }
            c.restore()
        }
    }

    // ------------------------------------------------------------------ bubbles, leaderboard, health, cursor

    @SuppressLint("ViewConstructor")
    private inner class Layer(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * d; typeface = regular }
        private val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d; typeface = bold; color = 0xFFFFFFFF.toInt() }

        override fun onDraw(c: Canvas) {
            synchronized(studio.lock) {
                val p = studio.play ?: return
                drawBubbles(c, p)
                if (p.corePlayerList && playerListOpen) drawPlayerList(c, p)
                if (p.coreHealth) drawHealth(c, p)
                if (p.shiftLock && !p.firstPerson) drawCursor(c)
                else if (p.firstPerson) drawDot(c)
            }
        }

        private fun drawBubbles(c: Canvas, p: PlaySession) {
            if (p.bubbles.isEmpty()) return
            val maxW = dp(220f)
            val byPart = p.bubbles.groupBy { it.part }
            for ((part, list) in byPart) {
                val world = PartData.cframe(part).position + Vector3(0f, PartData.size(part).y / 2f + 1.6f, 0f)
                val s = studio.view.project(world) ?: continue
                if (s[2] > 1f) continue
                val dist = studio.view.distTo(world)
                if (dist > 100f) continue
                var bottom = s[1]
                for ((i, b) in list.asReversed().withIndex()) {
                    val alpha = (if (b.age > 11f) 12f - b.age else 1f).coerceIn(0f, 1f) * (if (i == 0) 1f else 0.8f)
                    tp.color = (((alpha * 255).toInt()) shl 24) or 0x393B3D
                    val layout = StaticLayout.Builder.obtain(b.text, 0, b.text.length, tp, maxW).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
                    var w = 0f
                    for (ln in 0 until layout.lineCount) w = maxOf(w, layout.getLineWidth(ln))
                    val padX = dp(10f); val padY = dp(6f)
                    val bw = w + padX * 2; val bh = layout.height + padY * 2f
                    val r = RectF(s[0] - bw / 2f, bottom - bh - dp(10f), s[0] + bw / 2f, bottom - dp(10f))
                    fill.color = (((alpha * 0.92f * 255).toInt()) shl 24) or 0xFFFFFF
                    c.drawRoundRect(r, dp(12f).toFloat(), dp(12f).toFloat(), fill)
                    if (i == 0) c.drawPath(Path().apply { moveTo(s[0] - dp(7f), r.bottom - 1f); lineTo(s[0], r.bottom + dp(8f)); lineTo(s[0] + dp(7f), r.bottom - 1f); close() }, fill)
                    c.save(); c.translate(s[0] - maxW / 2f, r.top + padY); layout.draw(c); c.restore()
                    bottom = r.top - dp(2f)
                }
            }
        }

        private fun drawPlayerList(c: Canvas, p: PlaySession) {
            val pl = p.player ?: return
            val stats = p.leaderstats()
            val colW = dp(76f).toFloat()
            val nameW = dp(150f).toFloat()
            val w = nameW + stats.size * colW + dp(16f)
            val rowH = dp(32f).toFloat()
            val x0 = width - w - dp(12f); var y = dp(56f).toFloat()
            fill.color = 0x80000000.toInt()
            val rows = 1 + (if (stats.isNotEmpty()) 1 else 0)
            c.drawRoundRect(x0, y, x0 + w, y + rows * rowH + dp(8f), dp(8f).toFloat(), dp(8f).toFloat(), fill)
            y += dp(4f)
            bp.textAlign = Paint.Align.LEFT
            if (stats.isNotEmpty()) {
                bp.color = 0xFFC8C8C8.toInt(); bp.textSize = 12f * d
                c.drawText("People", x0 + dp(10f), y + rowH * 0.62f, bp)
                bp.textAlign = Paint.Align.CENTER
                stats.forEachIndexed { i, (n, _) -> c.drawText(n.take(10), x0 + nameW + colW * i + colW / 2f, y + rowH * 0.62f, bp) }
                y += rowH
            }
            // player row
            fill.color = 0x33FFFFFF
            c.drawRoundRect(x0 + dp(4f), y, x0 + w - dp(4f), y + rowH, dp(6f).toFloat(), dp(6f).toFloat(), fill)
            fill.color = p.nameColor(pl.name)
            c.drawCircle(x0 + dp(20f), y + rowH / 2f, dp(10f).toFloat(), fill)
            bp.color = 0xFFFFFFFF.toInt(); bp.textSize = 14f * d; bp.textAlign = Paint.Align.LEFT
            val dn = (pl["DisplayName"] as? String)?.ifEmpty { pl.name } ?: pl.name
            c.drawText(dn.take(16), x0 + dp(36f), y + rowH * 0.65f, bp)
            bp.textAlign = Paint.Align.CENTER
            stats.forEachIndexed { i, (_, v) -> c.drawText(v.take(10), x0 + nameW + colW * i + colW / 2f, y + rowH * 0.65f, bp) }
        }

        private fun drawHealth(c: Canvas, p: PlaySession) {
            val hum = p.character?.findFirstChildOfClass("Humanoid") ?: return
            val hp = ((hum["Health_XML"] ?: hum["Health"]) as? Number)?.toFloat() ?: return
            val max = (hum["MaxHealth"] as? Number)?.toFloat()?.takeIf { it > 0f } ?: 100f
            if (hp >= max) return
            val f = (hp / max).coerceIn(0f, 1f)
            val w = dp(120f).toFloat(); val h = dp(8f).toFloat()
            val x = width - w - dp(64f); val y = dp(24f).toFloat()
            fill.color = 0x80000000.toInt(); c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, fill)
            fill.color = when { f > 0.5f -> 0xFF2BD15B.toInt(); f > 0.25f -> 0xFFF5C518.toInt(); else -> 0xFFE5484D.toInt() }
            if (f > 0f) c.drawRoundRect(x, y, x + w * f, y + h, h / 2, h / 2, fill)
        }

        private fun drawCursor(c: Canvas) {
            val cx = width / 2f + 0f; val cy = height / 2f
            fill.style = Paint.Style.STROKE; fill.strokeWidth = 2f * d; fill.color = 0xE6FFFFFF.toInt()
            c.drawCircle(cx, cy, 9f * d, fill)
            fill.style = Paint.Style.FILL
            c.drawCircle(cx, cy, 2f * d, fill)
        }

        private fun drawDot(c: Canvas) {
            fill.style = Paint.Style.FILL; fill.color = 0xCCFFFFFF.toInt()
            c.drawCircle(width / 2f, height / 2f, 2.5f * d, fill)
        }
    }

    // ------------------------------------------------------------------ Roblox menu

    fun toggleMenu() { if (menu != null) closeMenu() else openMenu() }

    private fun closeMenu() { menu?.let { removeView(it) }; menu = null }

    private fun label(s: String, size: Float, color: Int = 0xFFFFFFFF.toInt(), b: Boolean = false): TextView = TextView(context).apply {
        text = s; textSize = size; setTextColor(color); typeface = if (b) bold else regular
    }

    /** Roblox's menu buttons: white for the main action (Resume), dark grey for the others. */
    private fun pill(s: String, primary: Boolean, icon: Int = -1, onClick: () -> Unit): TextView = label(s, 15f, if (primary) 0xFF121215.toInt() else 0xFFFFFFFF.toInt(), true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        val bg = GradientDrawable().apply { setColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF393B3D.toInt()); cornerRadius = dp(8f).toFloat() }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if (primary) 0x33000000 else 0x33FFFFFF), bg, null)
        if (icon >= 0) {
            val dr = MenuIcon(icon, if (primary) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            dr.setBounds(0, 0, dp(18f), dp(18f))
            setCompoundDrawables(dr, null, null, null)
            compoundDrawablePadding = dp(7f)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Small glyphs for the menu: 0 people, 1 settings, 2 report, 3 help, 4 reset, 5 leave, 6 resume. */
    private inner class MenuIcon(private val kind: Int, private val color: Int) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun draw(c: Canvas) {
            val b = bounds; val s = b.width() / 24f
            c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(s, s)
            p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f
            when (kind) {
                0 -> { c.drawCircle(9f, 8f, 3.5f, p); c.drawArc(2f, 13f, 16f, 27f, 200f, 140f, false, p); c.drawCircle(17f, 9f, 2.8f, p); c.drawArc(13f, 14f, 23f, 24f, 220f, 110f, false, p) }
                1 -> { c.drawCircle(12f, 12f, 3.2f, p); for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); c.drawLine(12f + 6f * kotlin.math.cos(a).toFloat(), 12f + 6f * kotlin.math.sin(a).toFloat(), 12f + 9f * kotlin.math.cos(a).toFloat(), 12f + 9f * kotlin.math.sin(a).toFloat(), p) }; c.drawCircle(12f, 12f, 6.5f, p) }
                2 -> { c.drawLine(5f, 3f, 5f, 21f, p); c.drawPath(Path().apply { moveTo(5f, 4f); lineTo(19f, 4f); lineTo(15f, 8.5f); lineTo(19f, 13f); lineTo(5f, 13f) }, p) }
                3 -> { c.drawCircle(12f, 12f, 9.5f, p); c.drawArc(8.5f, 6f, 15.5f, 13f, 180f, 230f, false, p); p.style = Paint.Style.FILL; c.drawCircle(12f, 17f, 1.4f, p) }
                4 -> { c.drawArc(4f, 4f, 20f, 20f, -60f, 300f, false, p); c.drawPath(Path().apply { moveTo(20f, 3f); lineTo(20f, 9f); lineTo(14f, 9f) }, p) }
                5 -> { c.drawPath(Path().apply { moveTo(14f, 4f); lineTo(5f, 4f); lineTo(5f, 20f); lineTo(14f, 20f) }, p); c.drawLine(10f, 12f, 21f, 12f, p); c.drawPath(Path().apply { moveTo(17f, 8f); lineTo(21f, 12f); lineTo(17f, 16f) }, p) }
                else -> { p.style = Paint.Style.FILL; c.drawPath(Path().apply { moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close() }, p) }
            }
            c.restore()
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun openMenu() {
        hideKeyboard()
        // Roblox's in-experience menu: the game dimmed behind, tabs across the top with a white
        // underline on the open one, the page in the middle, and Reset / Leave / Resume at the bottom.
        val root = FrameLayout(context).apply { setBackgroundColor(0xC7000000.toInt()); isClickable = true }
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(10f), dp(16f), dp(14f)) }
        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val content = FrameLayout(context)
        val tabNames = listOf("People", "Settings", "Report", "Help")
        val tabViews = ArrayList<TextView>()
        val bars = ArrayList<View>()
        fun select(i: Int) {
            tabViews.forEachIndexed { j, t ->
                t.setTextColor(if (i == j) 0xFFFFFFFF.toInt() else 0xFFA4A6A8.toInt())
                t.compoundDrawables[0]?.alpha = if (i == j) 255 else 165
            }
            bars.forEachIndexed { j, b -> b.visibility = if (i == j) VISIBLE else INVISIBLE }
            content.removeAllViews()
            content.addView(when (i) { 0 -> peoplePage(); 1 -> settingsPage(); 2 -> reportPage(); else -> helpPage() })
        }
        tabNames.forEachIndexed { i, n ->
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; setOnClickListener { select(i) } }
            val t = label(n, 15f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                val icon = MenuIcon(i, 0xFFFFFFFF.toInt()); icon.setBounds(0, 0, dp(20f), dp(20f))
                setCompoundDrawables(icon, null, null, null); compoundDrawablePadding = dp(7f)
            }
            col.addView(t)
            val bar = View(context).apply { background = GradientDrawable().apply { setColor(-1); cornerRadius = dp(2f).toFloat() } }
            col.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3f)).apply { leftMargin = dp(8f); rightMargin = dp(8f) })
            tabViews.add(t); bars.add(bar)
            tabs.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(View(context).apply { setBackgroundColor(0x26FFFFFF) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply { bottomMargin = dp(10f) })
        panel.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bottom.addView(pill("Reset Character", false, 4) { confirmPage(root, "Reset Character?", "Are you sure you want to reset your character?", "Reset") {
            synchronized(studio.lock) { studio.play?.resetCharacter() }; closeMenu()
        } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8f) })
        bottom.addView(pill("Leave", false, 5) { confirmPage(root, "Leave Experience?", "Are you sure you want to leave the experience?", "Leave") {
            closeMenu(); studio.stopPlay()
        } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8f) })
        bottom.addView(pill("Resume", true, 6) { closeMenu() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        val maxW = dp(720f)
        // Below the top bar (it stays visible above the menu, like Roblox).
        root.addView(panel, LayoutParams(minOf(maxW, (width * 0.94f).toInt().coerceAtLeast(dp(280f))), (height - dp(62f)).coerceAtLeast(dp(200f)), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(58f) })
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        menu = root
        unibar.bringToFront()
        select(0)
    }

    private fun confirmPage(root: FrameLayout, title: String, msg: String, ok: String, onOk: () -> Unit) {
        val shade = FrameLayout(context).apply { setBackgroundColor(0xCC000000.toInt()); isClickable = true }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
            background = GradientDrawable().apply { setColor(0xFF232527.toInt()); cornerRadius = dp(12f).toFloat() }
        }
        box.addView(label(title, 20f, b = true).apply { gravity = Gravity.CENTER })
        box.addView(label(msg, 15f, 0xFFD0D0D0.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(16f)) })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill(ok, true) { root.removeView(shade); onOk() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8f) })
        row.addView(pill("Don't ${ok}", false) { root.removeView(shade) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        shade.addView(box, LayoutParams(minOf(dp(420f), (width * 0.9f).toInt()), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun peoplePage(): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val p = studio.play
        val pl = p?.player
        list.addView(label("In this experience (${if (pl != null) 1 else 0})", 14f, 0xFFA4A6A8.toInt(), true).apply { setPadding(dp(4f), 0, 0, dp(8f)) })
        if (pl != null) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(8f), dp(12f), dp(8f))
                background = GradientDrawable().apply { setColor(0xFF232527.toInt()); cornerRadius = dp(8f).toFloat() }
            }
            val display = (pl["DisplayName"] as? String)?.ifEmpty { pl.name } ?: pl.name
            // A round avatar spot with the player's initial (headshots need the web).
            row.addView(label(display.take(1).uppercase(), 18f, b = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.nameColor(pl.name)) }
            }, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(12f) })
            val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(display, 16f, b = true))
            names.addView(label("@${pl.name}", 13f, 0xFFA4A6A8.toInt()))
            row.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label("You", 13f, 0xFFFFFFFF.toInt(), true).apply {
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                background = GradientDrawable().apply { setColor(0xFF393B3D.toInt()); cornerRadius = dp(6f).toFloat() }
            })
            list.addView(row)
        }
        list.addView(label("Playtest in Lemonade: only you are in this server.", 13f, 0x99FFFFFF.toInt()).apply { setPadding(dp(4f), dp(12f), dp(4f), 0) })
        return ScrollView(context).apply { addView(list) }
    }

    private fun settingsPage(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun row(name: String, control: View) {
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6f), dp(8f), dp(6f), dp(8f)) }
            r.addView(label(name, 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(control)
            box.addView(r)
            box.addView(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
        fun selector(options: List<String>, current: Int, onSet: (Int) -> Unit): View {
            var idx = current
            val l = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val t = label(options[idx], 14f, b = true).apply { gravity = Gravity.CENTER; minWidth = dp(140f) }
            // Roblox's selector: ◀ value ▶
            fun arrow(s: String, delta: Int) = label(s, 13f, b = true).apply {
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f)); isClickable = true
                setOnClickListener { idx = Math.floorMod(idx + delta, options.size); t.text = options[idx]; onSet(idx) }
            }
            l.addView(arrow("◀", -1)); l.addView(t); l.addView(arrow("▶", 1))
            return l
        }
        /** Roblox's 10-step bar (Volume, Graphics Quality): tap a block to set the level. */
        fun steps(value: Int, onSet: (Int) -> Unit): View {
            val l = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val blocks = ArrayList<View>()
            fun paint(v: Int) = blocks.forEachIndexed { i, b -> (b.background as GradientDrawable).setColor(if (i < v) 0xFFFFFFFF.toInt() else 0x40FFFFFF) }
            for (i in 1..10) {
                val b = View(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(2f).toFloat() }
                    isClickable = true
                    setOnClickListener { paint(i); onSet(i) }
                }
                blocks.add(b)
                l.addView(b, LinearLayout.LayoutParams(dp(15f), dp(22f)).apply { leftMargin = dp(3f) })
            }
            paint(value)
            return l
        }
        fun slider(value: Float, min: Float, max: Float, onSet: (Float) -> Unit): View = StudioSlider(context, min, max, value, (max - min) / 100f) { v, _ -> onSet(v) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(170f), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val allowed = studio.play?.shiftLockAllowed != false || !Settings.shiftLockSwitch
        if (studio.model.findService("StarterPlayer")?.get("EnableMouseLockOption") != false) {
            row("Shift Lock Switch", selector(listOf("Off", "On"), if (Settings.shiftLockSwitch) 1 else 0) { Settings.shiftLockSwitch = it == 1; if (it == 0) synchronized(studio.lock) { studio.play?.let { p -> if (p.shiftLock) p.toggleShiftLock() } } })
        }
        if (!allowed) box.addView(label("This experience turned shift lock off.", 12f, 0x99FFFFFF.toInt()))
        row("Camera Mode", selector(listOf("Classic", "Follow"), if (Settings.cameraMode == "Classic") 0 else 1) { Settings.cameraMode = if (it == 0) "Classic" else "Follow" })
        row("Movement Mode", selector(listOf("Dynamic Thumbstick", "Thumbstick"), if (Settings.movementMode == "Thumbstick") 1 else 0) { Settings.movementMode = if (it == 1) "Thumbstick" else "Dynamic Thumbstick" })
        row("Camera Sensitivity", slider(Settings.playCameraSensitivity, 0.2f, 4f) { Settings.playCameraSensitivity = it })
        row("Volume", steps(Settings.volume.coerceIn(0, 10)) { Settings.volume = it })
        row("Graphics Quality", steps(Settings.graphicsQuality.coerceIn(1, 10)) {
            Settings.graphicsQuality = it.coerceIn(1, 10)
            studio.shadowsEnabled = Settings.graphicsQuality >= 4
        })
        row("Performance Stats", selector(listOf("Off", "On"), if (Settings.showFps) 1 else 0) { Settings.showFps = it == 1 })
        return ScrollView(context).apply { addView(box) }
    }

    /** Roblox's Report tab; in a playtest there's no one to report, so it explains that. */
    private fun reportPage(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6f), dp(4f), dp(6f), dp(4f)) }
        box.addView(label("Report", 17f, b = true))
        box.addView(label("In a live game you can report a player or the experience here. This is a playtest in Lemonade, so there's nobody else to report.", 13f, 0xB3FFFFFF.toInt()).apply { setPadding(0, dp(6f), 0, dp(10f)) })
        box.addView(label("Things to check before publishing:", 14f, b = true))
        for (s in listOf("Scripts don't show errors in the Output", "Spawn points are where players should start", "Text is readable on a phone screen")) box.addView(label("•  $s", 13f, 0xB3FFFFFF.toInt()).apply { setPadding(0, dp(4f), 0, 0) })
        return ScrollView(context).apply { addView(box) }
    }

    private fun helpPage(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6f), dp(4f), dp(6f), dp(4f)) }
        for ((k, v) in listOf(
            "Move" to "Drag the thumbstick (bottom left). Keyboard: WASD or arrow keys",
            "Look around" to "Drag anywhere else on the screen. Keyboard: ← → to turn",
            "Zoom" to "Pinch with two fingers. Keyboard: I / O",
            "Jump" to "Jump button (bottom right). Keyboard: Space",
            "Shift lock" to "Lock button above jump. Keyboard: Shift",
            "Chat" to "Chat button (top left). Keyboard: /",
            "Tools" to "Tap a slot at the bottom. Keyboard: 1–9",
            "Menu" to "Roblox button (top left). Keyboard: Esc",
        )) {
            box.addView(label(k, 15f, b = true).apply { setPadding(0, dp(6f), 0, 0) })
            box.addView(label(v, 13f, 0xB3FFFFFF.toInt()))
        }
        return ScrollView(context).apply { addView(box) }
    }
}
