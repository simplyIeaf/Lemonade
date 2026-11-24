package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Studio-style docking: panels live in a left column, a right column, a strip under the viewport,
 * or float above everything. Drag a panel by its header to move it; drop targets (and a blue
 * preview of where it will land) appear while dragging, like Roblox Studio. Double-tap a header to
 * float/re-dock it. Sizes and placement are remembered.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DockLayout(ctx: Context, private val prefs: SharedPreferences) : FrameLayout(ctx) {
    enum class Zone { LEFT, RIGHT, BOTTOM, FLOAT }

    inner class Panel(val id: String, val title: String, val view: View, val defZone: Zone, val defOrder: Int, val defVisible: Boolean) {
        var zone = defZone
        var order = defOrder
        var weight = 1f
        var visible = defVisible
        var fx = -1; var fy = -1; var fw = 0; var fh = 0
        var frame: FloatFrame? = null
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val panels = ArrayList<Panel>()
    private val main = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val leftCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val rightCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val centerCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val bottomRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val centerHost = FrameLayout(ctx)
    private val floatLayer = FrameLayout(ctx)
    private val overlay = DropOverlay(ctx)
    private val leftSplit: Splitter
    private val rightSplit: Splitter
    private val bottomSplit: Splitter

    private val phone = resources.configuration.smallestScreenWidthDp < 600
    var leftW = dp(if (phone) 220f else 270f)
    var rightW = dp(if (phone) 250f else 320f)
    var bottomH = dp(if (phone) 140f else 190f)

    /** "Hide all windows": only the viewport / script editor stays. */
    var allHidden = false
        set(v) { field = v; rebuild(); onChanged?.invoke() }

    /** Called when panel visibility or placement changes (to refresh the View tab). */
    var onChanged: (() -> Unit)? = null

    init {
        setBackgroundColor(Ui.BG)
        leftSplit = Splitter(ctx, true, { dx -> leftW = clampW(leftW + dx.toInt()); leftCol.layoutParams = LinearLayout.LayoutParams(leftW, Ui.MATCH) }, { save() })
        rightSplit = Splitter(ctx, true, { dx -> rightW = clampW(rightW - dx.toInt()); rightCol.layoutParams = LinearLayout.LayoutParams(rightW, Ui.MATCH) }, { save() })
        bottomSplit = Splitter(ctx, false, { dy -> bottomH = clampH(bottomH - dy.toInt()); bottomRow.layoutParams = LinearLayout.LayoutParams(Ui.MATCH, bottomH) }, { save() })
        main.addView(leftCol, LinearLayout.LayoutParams(leftW, Ui.MATCH))
        main.addView(leftSplit, LinearLayout.LayoutParams(leftSplit.thickness, Ui.MATCH))
        main.addView(centerCol, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        main.addView(rightSplit, LinearLayout.LayoutParams(rightSplit.thickness, Ui.MATCH))
        main.addView(rightCol, LinearLayout.LayoutParams(rightW, Ui.MATCH))
        centerCol.addView(centerHost, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        centerCol.addView(bottomSplit, LinearLayout.LayoutParams(Ui.MATCH, bottomSplit.thickness))
        centerCol.addView(bottomRow, LinearLayout.LayoutParams(Ui.MATCH, bottomH))
        addView(main, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(floatLayer, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(overlay, LayoutParams(Ui.MATCH, Ui.MATCH))
        overlay.visibility = GONE
    }

    private fun clampW(w: Int) = w.coerceIn(dp(140f), (resources.displayMetrics.widthPixels * 0.6f).toInt())
    private fun clampH(h: Int) = h.coerceIn(dp(60f), (resources.displayMetrics.heightPixels * 0.7f).toInt())

    /** The viewport / script editor area (never moved, so the GL surface isn't recreated). */
    fun setCenter(v: View) { centerHost.addView(v, LayoutParams(Ui.MATCH, Ui.MATCH)) }

    fun addPanel(id: String, title: String, view: View, zone: Zone, order: Int, visible: Boolean = true) {
        val p = Panel(id, title, view, zone, order, visible)
        panels.add(p)
        decorateHeader(p)
    }

    fun panel(id: String) = panels.firstOrNull { it.id == id }
    fun isShown(id: String) = !allHidden && panel(id)?.visible == true

    fun setShown(id: String, show: Boolean) {
        val p = panel(id) ?: return
        if (allHidden && show) { allHidden = false }
        p.visible = show
        rebuild(); save(); onChanged?.invoke()
    }

    fun toggle(id: String) = setShown(id, !(panel(id)?.visible == true && !allHidden))

    // ------------------------------------------------------------------ persistence

    fun load() {
        val raw = prefs.getString("dock_v1", null)
        if (raw == null) {
            // Carry over panel sizes saved by the previous (fixed) layout.
            prefs.getInt("toolboxW", -1).takeIf { it > 0 }?.let { leftW = clampW(it) }
            prefs.getInt("rightW", -1).takeIf { it > 0 }?.let { rightW = clampW(it) }
            prefs.getInt("outputH", -1).takeIf { it > 0 }?.let { bottomH = clampH(it) }
        }
        if (raw != null) try {
            val o = JSONObject(raw)
            leftW = o.optInt("leftW", leftW); rightW = o.optInt("rightW", rightW); bottomH = o.optInt("bottomH", bottomH)
            val arr = o.optJSONArray("panels") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val po = arr.getJSONObject(i)
                val p = panel(po.optString("id")) ?: continue
                p.zone = try { Zone.valueOf(po.optString("zone")) } catch (_: Exception) { p.defZone }
                p.order = po.optInt("order", p.order)
                p.weight = po.optDouble("weight", 1.0).toFloat().coerceIn(0.05f, 20f)
                p.visible = po.optBoolean("visible", p.visible)
                p.fx = po.optInt("fx", -1); p.fy = po.optInt("fy", -1); p.fw = po.optInt("fw", 0); p.fh = po.optInt("fh", 0)
            }
        } catch (_: Exception) {}
        rebuild()
    }

    fun save() {
        val arr = JSONArray()
        for (p in panels) arr.put(JSONObject().apply {
            put("id", p.id); put("zone", p.zone.name); put("order", p.order); put("weight", p.weight.toDouble()); put("visible", p.visible)
            put("fx", p.fx); put("fy", p.fy); put("fw", p.fw); put("fh", p.fh)
        })
        prefs.edit().putString("dock_v1", JSONObject().apply {
            put("leftW", leftW); put("rightW", rightW); put("bottomH", bottomH); put("panels", arr)
        }.toString()).apply()
    }

    fun reset() {
        prefs.edit().remove("dock_v1").apply()
        leftW = dp(if (phone) 220f else 270f); rightW = dp(if (phone) 250f else 320f); bottomH = dp(if (phone) 140f else 190f)
        for (p in panels) { p.zone = p.defZone; p.order = p.defOrder; p.weight = 1f; p.visible = p.defVisible; p.fx = -1; p.fy = -1; p.fw = 0; p.fh = 0 }
        allHidden = false
        rebuild()
        onChanged?.invoke()
    }

    // ------------------------------------------------------------------ layout

    private fun detach(v: View) { (v.parent as? ViewGroup)?.removeView(v) }

    fun rebuild() {
        for (p in panels) detach(p.view)
        leftCol.removeAllViews(); rightCol.removeAllViews(); bottomRow.removeAllViews()
        floatLayer.removeAllViews()
        val vis = if (allHidden) emptyList() else panels.filter { it.visible }
        fun fill(col: LinearLayout, zone: Zone, horizontal: Boolean) {
            val list = vis.filter { it.zone == zone }.sortedBy { it.order }
            list.forEachIndexed { i, p ->
                if (i > 0) {
                    val a = list[i - 1]; val b = p
                    val sp = Splitter(context, horizontal, { delta -> resizePair(col, a, b, delta, horizontal) }, { save() })
                    col.addView(sp, if (horizontal) LinearLayout.LayoutParams(sp.thickness, Ui.MATCH) else LinearLayout.LayoutParams(Ui.MATCH, sp.thickness))
                }
                col.addView(p.view, if (horizontal) LinearLayout.LayoutParams(0, Ui.MATCH, p.weight) else LinearLayout.LayoutParams(Ui.MATCH, 0, p.weight))
            }
        }
        fill(leftCol, Zone.LEFT, false)
        fill(rightCol, Zone.RIGHT, false)
        fill(bottomRow, Zone.BOTTOM, true)
        val hasL = leftCol.childCount > 0; val hasR = rightCol.childCount > 0; val hasB = bottomRow.childCount > 0
        leftCol.visibility = if (hasL) VISIBLE else GONE; leftSplit.visibility = leftCol.visibility
        rightCol.visibility = if (hasR) VISIBLE else GONE; rightSplit.visibility = rightCol.visibility
        bottomRow.visibility = if (hasB) VISIBLE else GONE; bottomSplit.visibility = bottomRow.visibility
        leftCol.layoutParams = LinearLayout.LayoutParams(leftW, Ui.MATCH)
        rightCol.layoutParams = LinearLayout.LayoutParams(rightW, Ui.MATCH)
        bottomRow.layoutParams = LinearLayout.LayoutParams(Ui.MATCH, bottomH)
        for (p in vis.filter { it.zone == Zone.FLOAT }) {
            val f = p.frame ?: FloatFrame(context, p).also { p.frame = it }
            f.attach()
            if (p.fw <= 0) { p.fw = dp(300f); p.fh = dp(360f) }
            if (p.fx < 0) { p.fx = (width - p.fw) / 2; p.fy = dp(40f) }
            floatLayer.addView(f, LayoutParams(p.fw, p.fh).apply { leftMargin = p.fx; topMargin = p.fy })
        }
        post { clampFloats() }
    }

    private fun clampFloats() {
        for (p in panels) {
            val f = p.frame ?: continue
            if (f.parent !== floatLayer) continue
            val lp = f.layoutParams as LayoutParams
            val w = lp.width.coerceAtMost(width.coerceAtLeast(dp(160f))); val h = lp.height.coerceAtMost(height.coerceAtLeast(dp(120f)))
            val x = lp.leftMargin.coerceIn(0, (width - dp(60f)).coerceAtLeast(0)); val y = lp.topMargin.coerceIn(0, (height - dp(30f)).coerceAtLeast(0))
            if (w != lp.width || h != lp.height || x != lp.leftMargin || y != lp.topMargin) {
                lp.width = w; lp.height = h; lp.leftMargin = x; lp.topMargin = y; f.layoutParams = lp
                p.fx = x; p.fy = y; p.fw = w; p.fh = h
            }
        }
    }

    /** Moves the boundary between two neighbouring docked panels. */
    private fun resizePair(col: LinearLayout, a: Panel, b: Panel, delta: Float, horizontal: Boolean) {
        val sizeA = if (horizontal) a.view.width else a.view.height
        val sizeB = if (horizontal) b.view.width else b.view.height
        val total = sizeA + sizeB
        if (total <= 0) return
        val min = dp(60f)
        val newA = (sizeA + delta).coerceIn(min.toFloat(), (total - min).toFloat())
        val wSum = a.weight + b.weight
        a.weight = wSum * newA / total; b.weight = wSum - a.weight
        (a.view.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = a.weight; a.view.layoutParams = it }
        (b.view.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = b.weight; b.view.layoutParams = it }
        col.requestLayout()
    }

    // ------------------------------------------------------------------ header dragging

    private fun headerOf(p: Panel): ViewGroup? = (p.view as? ViewGroup)?.getChildAt(0) as? ViewGroup

    private fun decorateHeader(p: Panel) {
        val h = headerOf(p) ?: return
        val ctx = context
        h.addView(View(ctx), 0, LinearLayout.LayoutParams(dp(52f), dp(26f)))
        h.addView(Ui.iconButton(ctx, Icons.ui(ctx, "Float", "F"), 26f) { toggleFloat(p) })
        h.addView(Ui.iconButton(ctx, Icons.ui(ctx, "CloseWidget", "x"), 26f) { setShown(p.id, false) })
        var downX = 0f; var downY = 0f; var dragging = false; var lastTap = 0L
        var startFx = 0; var startFy = 0
        h.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                    startFx = p.fx; startFy = p.fy
                    h.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > 10f * d) { dragging = true; overlay.begin(p) }
                    if (dragging) {
                        if (p.zone == Zone.FLOAT && p.frame?.parent === floatLayer) {
                            val f = p.frame!!
                            val lp = f.layoutParams as LayoutParams
                            lp.leftMargin = (startFx + (e.rawX - downX)).toInt(); lp.topMargin = (startFy + (e.rawY - downY)).toInt().coerceAtLeast(0)
                            f.layoutParams = lp
                        }
                        val (x, y) = local(e.rawX, e.rawY)
                        overlay.update(p, x, y)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val (x, y) = local(e.rawX, e.rawY)
                        val t = overlay.update(p, x, y)
                        overlay.end()
                        if (e.actionMasked == MotionEvent.ACTION_UP) drop(p, t)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 320) { toggleFloat(p); lastTap = 0 } else lastTap = now
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun local(rawX: Float, rawY: Float): Pair<Float, Float> {
        val loc = IntArray(2); getLocationOnScreen(loc)
        return (rawX - loc[0]) to (rawY - loc[1])
    }

    private fun rectIn(v: View): RectF {
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationOnScreen(a); getLocationOnScreen(b)
        return RectF((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat(), (a[0] - b[0] + v.width).toFloat(), (a[1] - b[1] + v.height).toFloat())
    }

    private fun toggleFloat(p: Panel) {
        if (p.zone == Zone.FLOAT) {
            p.zone = if (p.defZone == Zone.FLOAT) Zone.RIGHT else p.defZone
            p.order = panels.filter { it.zone == p.zone && it !== p }.maxOfOrNull { it.order + 1 } ?: 0
        } else {
            val r = if (p.view.isShown) rectIn(p.view) else null
            p.zone = Zone.FLOAT
            p.fw = (r?.width()?.toInt() ?: dp(300f)).coerceIn(dp(220f), dp(420f))
            p.fh = (r?.height()?.toInt() ?: dp(360f)).coerceIn(dp(200f), dp(480f))
            p.fx = ((r?.left ?: 0f).toInt() + dp(24f)); p.fy = ((r?.top ?: 0f).toInt() + dp(24f))
        }
        rebuild(); save(); onChanged?.invoke()
    }

    /** Where a dragged panel would land. [index] is the insert position among that zone's visible panels. */
    class Target(val zone: Zone, val index: Int, val preview: RectF)

    private fun target(p: Panel, x: Float, y: Float): Target {
        val edge = 44f * d
        val w = width.toFloat(); val h = height.toFloat()
        val vis = panels.filter { it.visible && !allHidden && it !== p }
        fun colTarget(zone: Zone, col: LinearLayout, colRect: RectF?, horizontal: Boolean, emptyRect: RectF): Target {
            val list = vis.filter { it.zone == zone }.sortedBy { it.order }
            if (list.isEmpty() || colRect == null) return Target(zone, 0, emptyRect)
            for ((i, q) in list.withIndex()) {
                val r = rectIn(q.view)
                val inside = if (horizontal) x >= r.left && x <= r.right else y >= r.top && y <= r.bottom
                if (inside) {
                    val firstHalf = if (horizontal) x < r.centerX() else y < r.centerY()
                    val pr = if (horizontal) (if (firstHalf) RectF(r.left, r.top, r.centerX(), r.bottom) else RectF(r.centerX(), r.top, r.right, r.bottom))
                    else (if (firstHalf) RectF(r.left, r.top, r.right, r.centerY()) else RectF(r.left, r.centerY(), r.right, r.bottom))
                    return Target(zone, if (firstHalf) i else i + 1, pr)
                }
            }
            val last = rectIn(list.last().view)
            return Target(zone, list.size, if (horizontal) RectF(last.centerX(), last.top, last.right, last.bottom) else RectF(last.left, last.centerY(), last.right, last.bottom))
        }
        val lRect = if (leftCol.visibility == VISIBLE && leftCol.width > 0) rectIn(leftCol) else null
        val rRect = if (rightCol.visibility == VISIBLE && rightCol.width > 0) rectIn(rightCol) else null
        val bRect = if (bottomRow.visibility == VISIBLE && bottomRow.height > 0) rectIn(bottomRow) else null
        val emptyL = RectF(0f, 0f, leftW.toFloat().coerceAtMost(w * 0.4f), h)
        val emptyR = RectF(w - rightW.toFloat().coerceAtMost(w * 0.4f), 0f, w, h)
        val cx0 = lRect?.right ?: 0f; val cx1 = rRect?.left ?: w
        val emptyB = RectF(cx0, h - bottomH.toFloat().coerceAtMost(h * 0.5f), cx1, h)
        return when {
            x < edge || (lRect != null && lRect.contains(x, y)) -> colTarget(Zone.LEFT, leftCol, lRect, false, emptyL)
            x > w - edge || (rRect != null && rRect.contains(x, y)) -> colTarget(Zone.RIGHT, rightCol, rRect, false, emptyR)
            y > h - edge || (bRect != null && bRect.contains(x, y)) -> colTarget(Zone.BOTTOM, bottomRow, bRect, true, emptyB)
            else -> {
                val fw = (if (p.fw > 0) p.fw else dp(300f)).toFloat(); val fh = (if (p.fh > 0) p.fh else dp(360f)).toFloat()
                Target(Zone.FLOAT, 0, RectF(x - fw / 2f, y - dp(14f), x + fw / 2f, y - dp(14f) + fh))
            }
        }
    }

    private fun drop(p: Panel, t: Target) {
        if (t.zone == Zone.FLOAT) {
            if (p.zone != Zone.FLOAT) {
                p.zone = Zone.FLOAT
                p.fw = t.preview.width().toInt(); p.fh = t.preview.height().toInt()
                p.fx = t.preview.left.toInt(); p.fy = t.preview.top.toInt().coerceAtLeast(0)
            } else {
                val lp = p.frame?.layoutParams as? LayoutParams
                if (lp != null) { p.fx = lp.leftMargin; p.fy = lp.topMargin }
            }
        } else {
            val list = panels.filter { it.zone == t.zone && it.visible && it !== p }.sortedBy { it.order }.toMutableList()
            val idx = t.index.coerceIn(0, list.size)
            list.add(idx, p)
            list.forEachIndexed { i, q -> q.order = i }
            p.weight = if (list.size > 1) list.filter { it !== p }.map { it.weight }.average().toFloat() else 1f
            p.zone = t.zone
        }
        rebuild(); save(); onChanged?.invoke()
    }

    // ------------------------------------------------------------------ floating window

    @SuppressLint("ViewConstructor")
    inner class FloatFrame(ctx: Context, private val p: Panel) : FrameLayout(ctx) {
        private val grip = View(ctx)
        init {
            background = GradientDrawable().apply { setColor(Ui.PANEL); setStroke(dp(1f), Ui.ACCENT) }
            elevation = 12f * d
            setPadding(dp(1f), dp(1f), dp(1f), dp(1f))
            grip.background = object : android.graphics.drawable.Drawable() {
                private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d }
                override fun draw(c: Canvas) {
                    val b = bounds; pt.color = Ui.DIM
                    for (i in 1..3) c.drawLine(b.right - i * 5f * d, b.bottom.toFloat() - 2f * d, b.right.toFloat() - 2f * d, b.bottom - i * 5f * d, pt)
                }
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            var sx = 0f; var sy = 0f; var sw = 0; var sh = 0
            grip.setOnTouchListener { _, e ->
                val lp = layoutParams as LayoutParams
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; sw = lp.width; sh = lp.height; parent?.requestDisallowInterceptTouchEvent(true) }
                    MotionEvent.ACTION_MOVE -> {
                        lp.width = (sw + (e.rawX - sx)).toInt().coerceAtLeast(dp(180f)); lp.height = (sh + (e.rawY - sy)).toInt().coerceAtLeast(dp(140f))
                        layoutParams = lp
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { p.fw = lp.width; p.fh = lp.height; save() }
                }
                true
            }
        }
        fun attach() {
            removeAllViews()
            addView(p.view, LayoutParams(Ui.MATCH, Ui.MATCH))
            addView(grip, LayoutParams(dp(22f), dp(22f), android.view.Gravity.END or android.view.Gravity.BOTTOM))
        }
    }

    // ------------------------------------------------------------------ drop preview

    private inner class DropOverlay(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var current: Target? = null
        private var dragged: Panel? = null

        fun begin(p: Panel) { dragged = p; visibility = VISIBLE; invalidate() }
        fun update(p: Panel, x: Float, y: Float): Target { val t = target(p, x, y); current = t; invalidate(); return t }
        fun end() { visibility = GONE; current = null; dragged = null }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val e = 44f * d
            // Edge hints
            fill.color = 0x223B8BEB
            c.drawRect(0f, 0f, e, h, fill); c.drawRect(w - e, 0f, w, h, fill); c.drawRect(e, h - e, w - e, h, fill)
            val t = current ?: return
            fill.color = 0x553B8BEB
            line.color = 0xFF3B8BEB.toInt(); line.strokeWidth = 2f * d
            c.drawRect(t.preview, fill); c.drawRect(t.preview, line)
            val label = when (t.zone) { Zone.LEFT -> "Dock left"; Zone.RIGHT -> "Dock right"; Zone.BOTTOM -> "Dock bottom"; Zone.FLOAT -> "Float" }
            fill.color = 0xFFFFFFFF.toInt(); fill.textSize = 13f * d; fill.textAlign = Paint.Align.CENTER; fill.isFakeBoldText = true
            val r = Rect(); fill.getTextBounds(label, 0, label.length, r)
            val cx = t.preview.centerX(); val cy = t.preview.centerY()
            val bg = Paint().apply { color = 0xCC1F5FB0.toInt() }
            c.drawRoundRect(cx - r.width() / 2f - 10f * d, cy - 14f * d, cx + r.width() / 2f + 10f * d, cy + 10f * d, 6f * d, 6f * d, bg)
            c.drawText(label, cx, cy + 2f * d, fill)
            fill.isFakeBoldText = false
        }
    }
}
