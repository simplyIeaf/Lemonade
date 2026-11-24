package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.lua.LuaRuntime
import com.leaf.lemonade.model.*
import com.leaf.lemonade.net.ImageLoader
import org.luaj.vm2.LuaValue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws ScreenGuis, BillboardGuis and plugin widgets the way the Roblox client lays them out
 * (top bar inset, UIListLayout/UIGridLayout alignment, constraints, UIScale, scrolling frames,
 * images, fonts, strokes and rich text) and handles touches on GUI buttons.
 */
@SuppressLint("ViewConstructor")
class GuiOverlay(ctx: Context, private val studio: Studio) : View(ctx) {
    class Hit(val inst: Instance, val rect: RectF, val rt: LuaRuntime?, val kind: Int)
    class Scroll(val inst: Instance, val rect: RectF, val maxX: Float, val maxY: Float, val unit: Float)

    companion object {
        const val BUTTON = 0
        const val TEXTBOX = 1
        const val SINK = 2
        const val CORE_MENU = 3
        const val WIDGET_CLOSE = 4
        /** Height of the Roblox top bar that GUIs are pushed below unless they ignore the inset. */
        const val TOPBAR_INSET = 58f
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private var hits = ArrayList<Hit>()
    private var scrolls = ArrayList<Scroll>()
    private var picks = ArrayList<Pair<Instance, RectF>>()
    private var rt: LuaRuntime? = null
    private var newHits = ArrayList<Hit>()
    private var newScrolls = ArrayList<Scroll>()
    private var newPicks = ArrayList<Pair<Instance, RectF>>()
    @Volatile var hasContent = false

    /** Where a GUI object was laid out (edit mode), for dragging and resizing it. */
    class EditBox(val rect: RectF, val area: RectF, val scale: Float, val inLayout: Boolean)
    private var boxes = java.util.IdentityHashMap<Instance, EditBox>()
    private var newBoxes = java.util.IdentityHashMap<Instance, EditBox>()
    /** Smart guide lines (x1, y1, x2, y2) shown while dragging. */
    @Volatile var guides: List<FloatArray> = emptyList()

    /** In edit mode the selected GUI object follows the Studio selection (Explorer or a tap). */
    var selectedGui: Instance?
        get() = if (studio.isPlaying) null else studio.selection.firstOrNull { it.isA("GuiObject") }
        set(_) {}
    /** Button currently held down (drawn darker when AutoButtonColor is on). */
    var pressed: Instance? = null
    var onCoreMenu: ((Float, Float) -> Unit)? = null

    private var billboards: List<Instance> = emptyList()
    private var billboardScan = 0L

    init {
        ImageLoader.listeners.add { postInvalidate() }
        // Full-size sprite sheets arrive through the asset downloader.
        com.leaf.lemonade.net.AssetFetcher.listeners.add { postInvalidate() }
    }

    // ------------------------------------------------------------------ text caches

    private data class TKey(val text: String, val rich: Boolean, val tf: Typeface, val bold: Boolean, val size: Float, val width: Int,
                            val align: Layout.Alignment, val lineHeight: Float, val truncate: Boolean)

    private val layouts = object : LinkedHashMap<TKey, StaticLayout>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TKey, StaticLayout>?) = size > 500
    }

    private data class SKey(val text: String, val rich: Boolean, val tf: Typeface, val bold: Boolean, val w: Int, val h: Int, val max: Float, val min: Float, val lineHeight: Float)

    private val scaledSizes = object : LinkedHashMap<SKey, Float>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SKey, Float>?) = size > 400
    }

    private fun content(text: String, rich: Boolean): CharSequence = if (rich) RichText.parse(text) else text

    private fun layoutFor(k: TKey): StaticLayout = layouts.getOrPut(k) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = k.tf; textSize = k.size; isFakeBoldText = k.bold }
        var cs = content(k.text, k.rich)
        if (k.truncate) cs = TextUtils.ellipsize(cs, p, k.width.toFloat(), TextUtils.TruncateAt.END)
        StaticLayout.Builder.obtain(cs, 0, cs.length, p, k.width.coerceAtLeast(1))
            .setAlignment(k.align).setIncludePad(false).setLineSpacing(0f, k.lineHeight).build()
    }

    /** Largest text size (px) that fits the box, like TextScaled. */
    private fun scaledSize(k: SKey): Float = scaledSizes.getOrPut(k) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = k.tf; isFakeBoldText = k.bold }
        val cs = content(k.text, k.rich)
        val plain = cs.toString()
        val words = plain.split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun fits(s: Float): Boolean {
            p.textSize = s
            if (words.any { p.measureText(it) > k.w }) return false
            val l = StaticLayout.Builder.obtain(cs, 0, cs.length, p, k.w.coerceAtLeast(1)).setIncludePad(false).setLineSpacing(0f, k.lineHeight).build()
            return l.height <= k.h
        }
        var lo = k.min.coerceAtLeast(1f); var hi = k.max.coerceAtLeast(lo)
        if (fits(hi)) return@getOrPut hi
        repeat(12) { val mid = (lo + hi) / 2f; if (fits(mid)) lo = mid else hi = mid }
        lo
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Paints a SurfaceGui into [bmp] (called from the GL thread with the studio lock held).
     * Passing scale = 1/density makes one GUI offset unit equal one canvas pixel.
     */
    fun renderSurface(gui: Instance, cw: Float, ch: Float, bmp: Bitmap) {
        bmp.eraseColor(0)
        val c = Canvas(bmp)
        c.scale(bmp.width / cw, bmp.height / ch)
        val sh = newHits; val ss = newScrolls; val sp = newPicks; val sr = rt
        newHits = ArrayList(); newScrolls = ArrayList(); newPicks = ArrayList()
        val area = RectF(0f, 0f, cw, ch)
        // One GUI pixel is one canvas pixel here (no top bar), so Absolute* match the offsets.
        val su = absUnit; val si = absInset
        absUnit = 1f; absInset = 0f
        try { drawChildren(c, gui, area, 1f / d, area) } finally { newHits = sh; newScrolls = ss; newPicks = sp; rt = sr; absUnit = su; absInset = si }
    }

    override fun onDraw(canvas: Canvas) {
        var any = false
        synchronized(studio.lock) {
            newHits = ArrayList(); newScrolls = ArrayList(); newPicks = ArrayList(); newBoxes = java.util.IdentityHashMap()
            val w = width.toFloat(); val h = height.toFloat()
            val play = studio.play
            val roots: List<Instance> = if (play != null) play.player?.findFirstChild("PlayerGui")?.children ?: emptyList()
            else studio.model.findService("StarterGui")?.children ?: emptyList()
            rt = play?.rt ?: studio.editRuntime
            val inset = TOPBAR_INSET * d

            // Billboards first: they sit in the 3D world, behind screen GUIs.
            any = drawBillboards(canvas, play != null) or any

            // Plugin GUIs live in CoreGui and draw above the game's (in edit mode and playtests).
            val core = studio.model.findService("CoreGui")?.children ?: emptyList()
            val guis = roots.filter { it.className == "ScreenGui" && it["Enabled"] != false }
                .sortedBy { (it["DisplayOrder"] as? Number)?.toInt() ?: 0 } +
                core.filter { it.className == "ScreenGui" && it["Enabled"] != false }.sortedBy { (it["DisplayOrder"] as? Number)?.toInt() ?: 0 }
            for (g in guis) {
                any = true
                val insets = (g["ScreenInsets"] as? EnumValue)?.value
                val inCore = g.parent?.className == "CoreGui"
                val ignore = g["IgnoreGuiInset"] == true || insets == 0 || insets == 1 || (inCore && play == null)
                val area = RectF(0f, if (ignore) 0f else inset, w, h)
                val pluginRoot = inCore && play == null
                if (pluginRoot) usePluginUnits() else useGameUnits()
                setAbs(g, area)
                val saved = rt
                if (pluginRoot) rt = studio.editRuntime
                drawChildren(canvas, g, area, if (pluginRoot) pluginScale(h) else 1f, RectF(0f, 0f, w, h))
                rt = saved
            }
            useGameUnits()
            // The Roblox top bar is drawn by PlayHud (real buttons), above game GUIs.
            if (play != null) any = true
            if (play == null) any = drawPluginWidgets(canvas, w, h) or any
            selectedGui?.let { sel ->
                newPicks.firstOrNull { it.first === sel }?.let { (_, r) ->
                    stroke.color = 0xFF19A6FF.toInt(); stroke.strokeWidth = 2f * d; stroke.shader = null
                    canvas.drawRect(r, stroke)
                    // Resize handles, like Studio's.
                    for (pt in handlePoints(r)) {
                        fill.shader = null; fill.color = 0xFFFFFFFF.toInt()
                        canvas.drawRect(pt[0] - 4f * d, pt[1] - 4f * d, pt[0] + 4f * d, pt[1] + 4f * d, fill)
                        canvas.drawRect(pt[0] - 4f * d, pt[1] - 4f * d, pt[0] + 4f * d, pt[1] + 4f * d, stroke)
                    }
                    any = true
                }
            }
            val gl = guides
            if (gl.isNotEmpty()) {
                stroke.color = 0xFFFF3D9A.toInt(); stroke.strokeWidth = 1.5f * d; stroke.shader = null
                for (g in gl) canvas.drawLine(g[0], g[1], g[2], g[3], stroke)
            }
            hits = newHits; scrolls = newScrolls; picks = newPicks; boxes = newBoxes
            fireAbsChanges()
        }
        hasContent = any
    }

    // ------------------------------------------------------------------ edit-mode GUI dragging

    /** Handle centers: 0 TL, 1 T, 2 TR, 3 R, 4 BR, 5 B, 6 BL, 7 L. */
    fun handlePoints(r: RectF): List<FloatArray> = listOf(
        floatArrayOf(r.left, r.top), floatArrayOf(r.centerX(), r.top), floatArrayOf(r.right, r.top), floatArrayOf(r.right, r.centerY()),
        floatArrayOf(r.right, r.bottom), floatArrayOf(r.centerX(), r.bottom), floatArrayOf(r.left, r.bottom), floatArrayOf(r.left, r.centerY()),
    )

    fun editBox(i: Instance): EditBox? = boxes[i]

    /** Which part of the selected GUI is under the finger: a resize handle (0..7), -1 for the body, or null. */
    fun handleAt(i: Instance, x: Float, y: Float): Int? {
        val b = boxes[i] ?: return null
        val tol = 16f * d
        handlePoints(b.rect).forEachIndexed { idx, p -> if (kotlin.math.abs(p[0] - x) <= tol && kotlin.math.abs(p[1] - y) <= tol) return idx }
        // An invisible container (often screen-sized) only moves by its handles; dragging over it
        // turns the camera instead.
        return if (b.rect.contains(x, y) && !seeThrough(i)) -1 else null
    }

    /**
     * Nothing of this GUI object itself is visible: background transparency 1 (or more) and no
     * visible text or image. Such frames are layout containers; they don't take viewport drags.
     */
    fun seeThrough(c: Instance): Boolean {
        if (c["Visible"] == false) return true
        if (num(c, "BackgroundTransparency", 0f) < 0.999f) return false
        if (c.isA("TextLabel") || c.isA("TextButton") || c.className == "TextBox") {
            val text = c["Text"] as? String ?: ""
            if (text.isNotBlank() && num(c, "TextTransparency", 0f) < 0.999f) return false
        }
        if (c.className == "ImageLabel" || c.className == "ImageButton") {
            val img = c["Image"]?.toString() ?: ""
            if (img.isNotBlank() && num(c, "ImageTransparency", 0f) < 0.999f) return false
        }
        // A visible UIStroke draws an outline around it.
        if (c.children.any { it.className == "UIStroke" && it["Enabled"] != false && num(it, "Transparency", 0f) < 0.999f }) return false
        return true
    }

    class DragState(val inst: Instance, val handle: Int, val start: RectF, val area: RectF, val unit: Float,
                    val pos: UDim2, val size: UDim2, val anchor: Vector2, val inLayout: Boolean)

    fun beginDrag(i: Instance, handle: Int): DragState? = synchronized(studio.lock) {
        val b = boxes[i] ?: return null
        DragState(i, handle, RectF(b.rect), RectF(b.area), d * b.scale, udim(i["Position"]), udim(i["Size"]),
            i["AnchorPoint"] as? Vector2 ?: Vector2(0f, 0f), b.inLayout)
    }

    /** Moves/resizes the GUI by (dx, dy) px from where the drag started, snapping to guides and the grid. */
    fun dragTo(s: DragState, dx: Float, dy: Float) {
        if (s.handle == -1 && s.inLayout) return
        var l = s.start.left; var t = s.start.top; var r = s.start.right; var b = s.start.bottom
        val h = s.handle
        if (h == -1) { l += dx; r += dx; t += dy; b += dy }
        else {
            if (h == 0 || h == 6 || h == 7) l += dx
            if (h == 2 || h == 3 || h == 4) r += dx
            if (h == 0 || h == 1 || h == 2) t += dy
            if (h == 4 || h == 5 || h == 6) b += dy
            if (r - l < 1f) { if (h == 0 || h == 6 || h == 7) l = r - 1f else r = l + 1f }
            if (b - t < 1f) { if (h == 0 || h == 1 || h == 2) t = b - 1f else b = t + 1f }
        }
        val gl = ArrayList<FloatArray>()
        val area = s.area
        val moveX = h == -1 || h == 0 || h == 2 || h == 3 || h == 4 || h == 6 || h == 7
        val moveY = h == -1 || h == 0 || h == 1 || h == 2 || h == 4 || h == 5 || h == 6
        var snappedX = false; var snappedY = false
        if (Settings.guiSmartGuides) synchronized(studio.lock) {
            val xs = arrayListOf(area.left, area.centerX(), area.right)
            val ys = arrayListOf(area.top, area.centerY(), area.bottom)
            for ((k, v) in boxes) if (k !== s.inst && k.parent === s.inst.parent) {
                xs.add(v.rect.left); xs.add(v.rect.centerX()); xs.add(v.rect.right)
                ys.add(v.rect.top); ys.add(v.rect.centerY()); ys.add(v.rect.bottom)
            }
            val tol = 6f * d
            fun best(edges: List<Float>, cands: List<Float>): Pair<Float, Float>? {
                var bd = tol; var res: Pair<Float, Float>? = null
                for (e in edges) for (c in cands) { val diff = c - e; if (kotlin.math.abs(diff) < bd) { bd = kotlin.math.abs(diff); res = diff to c } }
                return res
            }
            if (moveX) {
                val edges = when { h == -1 -> listOf(l, (l + r) / 2f, r); h == 0 || h == 6 || h == 7 -> listOf(l); else -> listOf(r) }
                best(edges, xs)?.let { (diff, at) ->
                    if (h == -1) { l += diff; r += diff } else if (h == 0 || h == 6 || h == 7) l += diff else r += diff
                    gl.add(floatArrayOf(at, area.top, at, area.bottom)); snappedX = true
                }
            }
            if (moveY) {
                val edges = when { h == -1 -> listOf(t, (t + b) / 2f, b); h == 0 || h == 1 || h == 2 -> listOf(t); else -> listOf(b) }
                best(edges, ys)?.let { (diff, at) ->
                    if (h == -1) { t += diff; b += diff } else if (h == 0 || h == 1 || h == 2) t += diff else b += diff
                    gl.add(floatArrayOf(area.left, at, area.right, at)); snappedY = true
                }
            }
        }
        val grid = Settings.guiGrid
        if (grid > 0) {
            val g = grid * s.unit
            fun snapV(v: Float, origin: Float) = origin + Math.round((v - origin) / g) * g
            if (moveX && !snappedX) {
                if (h == -1) { val nl = snapV(l, area.left); r += nl - l; l = nl }
                else if (h == 0 || h == 6 || h == 7) l = snapV(l, area.left) else r = snapV(r, area.left)
            }
            if (moveY && !snappedY) {
                if (h == -1) { val nt = snapV(t, area.top); b += nt - t; t = nt }
                else if (h == 0 || h == 1 || h == 2) t = snapV(t, area.top) else b = snapV(b, area.top)
            }
        }
        guides = gl
        val w = r - l; val hh = b - t
        val aw = area.width(); val ah = area.height()
        val u = s.unit
        synchronized(studio.lock) {
            if (h != -1) {
                val ns = UDim2(s.size.xs, Math.round((w - s.size.xs * aw) / u), s.size.ys, Math.round((hh - s.size.ys * ah) / u))
                if (ns != s.inst["Size"]) s.inst.set("Size", ns)
            }
            if (!s.inLayout) {
                val np = UDim2(s.pos.xs, Math.round((l + s.anchor.x * w - area.left - s.pos.xs * aw) / u), s.pos.ys, Math.round((t + s.anchor.y * hh - area.top - s.pos.ys * ah) / u))
                if (np != s.inst["Position"]) s.inst.set("Position", np)
            }
        }
        postInvalidate()
    }

    fun endDrag() { guides = emptyList(); postInvalidate() }

    /**
     * Device pixels per GUI pixel, and the top inset (GUI pixels), for the root being drawn:
     * game GUIs use dp below Roblox's top bar; plugin GUIs in edit mode are drawn at
     * [Studio.editGuiScale] with no top bar, so their Absolute* values are in those smaller
     * units (matching their Size offsets and the input positions they get).
     */
    private var absUnit = 0f
    private var absInset = TOPBAR_INSET
    private fun useGameUnits() { absUnit = d; absInset = TOPBAR_INSET }
    private fun usePluginUnits() { absUnit = d * studio.editGuiScale(); absInset = 0f }

    /** Roblox's AbsolutePosition is measured from below the top bar. */
    private fun setAbs(c: Instance, r: RectF) {
        if (absUnit <= 0f) useGameUnits()
        val u = absUnit
        val x = r.left / u; val y = r.top / u - absInset; val w = r.width() / u; val h = r.height() / u
        val sizeChanged = kotlin.math.abs(c.absW - w) > 0.25f || kotlin.math.abs(c.absH - h) > 0.25f
        val posChanged = kotlin.math.abs(c.absX - x) > 0.25f || kotlin.math.abs(c.absY - y) > 0.25f
        c.absX = x; c.absY = y; c.absW = w; c.absH = h
        if (sizeChanged) absChanged.add(c to "AbsoluteSize")
        if (posChanged) absChanged.add(c to "AbsolutePosition")
    }

    /** A layout's AbsoluteContentSize (given in device pixels), noting changes like [setAbs]. */
    private fun setContentSize(layout: Instance, wPx: Float, hPx: Float) {
        if (absUnit <= 0f) useGameUnits()
        val w = wPx / absUnit; val h = hPx / absUnit
        if (kotlin.math.abs(layout.absW - w) > 0.25f || kotlin.math.abs(layout.absH - h) > 0.25f) absChanged.add(layout to "AbsoluteContentSize")
        layout.absW = w; layout.absH = h
    }

    /** Absolute* properties that changed while laying out (fired once drawing is done). */
    private val absChanged = ArrayList<Pair<Instance, String>>()
    private var absRounds = 0

    /**
     * Fires GetPropertyChangedSignal/Changed for Absolute* properties, like Roblox does when
     * layout changes them. Roact UIs depend on it (F3X sizes its dock from its list's
     * AbsoluteContentSize; Archimedes' scroll frames from their AbsoluteSize). Runs after
     * drawing, never in the middle of it, and redraws so changed props show next frame.
     */
    private fun fireAbsChanges() {
        if (absChanged.isEmpty()) { absRounds = 0; return }
        val list = ArrayList(absChanged)
        absChanged.clear()
        val runtimes = listOfNotNull(studio.editRuntime, studio.play?.rt).distinct()
        for ((inst, prop) in list) for (r in runtimes) {
            if (r.hasConn(inst, "Changed:$prop")) r.fire(inst, "Changed:$prop")
            if (r.hasConn(inst, "Changed")) r.fire(inst, "Changed", LuaValue.valueOf(prop))
        }
        // Redraw so the handlers' changes show; a UI that keeps resizing itself in a loop stops
        // asking for extra frames after a while (it still gets events on normal redraws).
        if (++absRounds <= 30) postInvalidate()
    }

    private fun drawTopBar(canvas: Canvas) {
        val r = 20f * d
        val cx = 12f * d + r; val cy = 8f * d + r
        fill.shader = null
        fill.color = 0x8C000000.toInt(); canvas.drawCircle(cx, cy, r, fill)
        // Roblox-style tilted square logo
        stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = 2.6f * d; stroke.shader = null
        canvas.save(); canvas.rotate(15f, cx, cy)
        val s = 8f * d
        canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 2f * d, 2f * d, stroke)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(cx - 2.2f * d, cy - 2.2f * d, cx + 2.2f * d, cy + 2.2f * d, fill)
        canvas.restore()
        newHits.add(Hit(studio.model, RectF(cx - r, cy - r, cx + r, cy + r), null, CORE_MENU))
        // chat bubble button
        val cx2 = cx + 2 * r + 8f * d
        fill.color = 0x8C000000.toInt(); canvas.drawCircle(cx2, cy, r, fill)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(cx2 - 9f * d, cy - 7f * d, cx2 + 9f * d, cy + 5f * d, 4f * d, 4f * d, fill)
        val tail = Path().apply { moveTo(cx2 - 4f * d, cy + 4f * d); lineTo(cx2 - 6f * d, cy + 10f * d); lineTo(cx2 + 1f * d, cy + 4f * d); close() }
        canvas.drawPath(tail, fill)
    }

    /**
     * How big plugin GUIs are drawn in edit mode. They're made for desktop Studio (a window
     * 400-600 units tall), so on a short phone viewport they're shrunk to fit unless the user
     * picked a size in Studio Settings.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun pluginScale(viewH: Float): Float = studio.editGuiScale()

    private fun drawPluginWidgets(canvas: Canvas, w: Float, h: Float): Boolean {
        var any = false
        var y = 12f * d
        val ps = pluginScale(h)
        usePluginUnits()
        for (pw in studio.pluginWidgets) {
            val wi = pw.inst
            if (wi["Enabled"] != true) continue
            any = true
            val ww = (((wi.attributes["__w"] as? Number)?.toFloat()) ?: 300f).times(ps).coerceAtMost(w / d * 0.6f) * d
            val wh = (((wi.attributes["__h"] as? Number)?.toFloat()) ?: 300f).times(ps).coerceAtMost(h / d * 0.8f) * d
            val x = w - ww - 12f * d
            fill.shader = null
            fill.color = 0xFF2E2E2E.toInt(); canvas.drawRect(x, y, x + ww, y + wh + 24f * d, fill)
            fill.color = Ui.HEADER; canvas.drawRect(x, y, x + ww, y + 24f * d, fill)
            textPaint.color = Ui.TEXT; textPaint.textSize = 13f * d; textPaint.typeface = Typeface.DEFAULT; textPaint.shader = null
            canvas.drawText((wi["Title"] as? String) ?: wi.name, x + 8f * d, y + 17f * d, textPaint)
            stroke.color = Ui.TEXT; stroke.strokeWidth = 1.6f * d; stroke.shader = null
            val cx = x + ww - 14f * d; val cy = y + 12f * d; val k = 4.5f * d
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, stroke); canvas.drawLine(cx + k, cy - k, cx - k, cy + k, stroke)
            newHits.add(Hit(wi, RectF(x + ww - 28f * d, y, x + ww, y + 24f * d), pw.rt, WIDGET_CLOSE))
            val content = RectF(x, y + 24f * d, x + ww, y + 24f * d + wh)
            canvas.save(); canvas.clipRect(content)
            val saved = rt; rt = pw.rt
            setAbs(wi, RectF(content))
            drawChildren(canvas, wi, content, ps, content)
            rt = saved
            canvas.restore()
            y += wh + 36f * d
        }
        useGameUnits()
        return any
    }

    // ------------------------------------------------------------------ billboards

    private fun drawBillboards(canvas: Canvas, playing: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (now - billboardScan > 500) {
            billboardScan = now
            val list = ArrayList<Instance>()
            studio.model.findService("Workspace")?.descendants()?.forEach { if (it.className == "BillboardGui") list.add(it) }
            if (playing) studio.play?.player?.findFirstChild("PlayerGui")?.descendants()?.forEach { if (it.className == "BillboardGui" && it["Adornee"] is Instance) list.add(it) }
            billboards = list
        }
        if (billboards.isEmpty()) return false
        val view = studio.view
        val cam = studio.camera
        val tanHalf = tan(Math.toRadians(cam.fov / 2.0)).toFloat()
        val items = ArrayList<Triple<Instance, FloatArray, Float>>()
        for (bb in billboards) {
            if (bb.parent == null || bb["Enabled"] == false) continue
            val ad = (bb["Adornee"] as? Instance) ?: bb.parent ?: continue
            val base: Vector3 = when {
                ad.isA("BasePart") -> PartData.cframe(ad).position + (PartData.cframe(ad).rotationOnly() * ((bb["ExtentsOffset"] as? Vector3) ?: Vector3.ZERO))
                ad.className == "Attachment" -> ad.parent?.takeIf { it.isA("BasePart") }?.let { PartData.cframe(it) * (((ad["CFrame"] as? CFrame) ?: CFrame.IDENTITY).position) } ?: continue
                ad.isA("Model") -> ((ad["PrimaryPart"] as? Instance) ?: ad.findFirstChildWhichIsA("BasePart", true))?.let { PartData.cframe(it).position } ?: continue
                else -> continue
            }
            val so = (bb["StudsOffset"] as? Vector3) ?: Vector3.ZERO
            val world = base + ((bb["StudsOffsetWorldSpace"] as? Vector3) ?: Vector3.ZERO) + cam.right * so.x + cam.up * so.y - cam.forward * so.z
            val dist = view.distTo(world)
            val maxD = (bb["MaxDistance"] as? Number)?.toFloat() ?: Float.POSITIVE_INFINITY
            if (dist > maxD || dist < 0.1f) continue
            val p = view.project(world) ?: continue
            if (p[2] > 1f) continue
            items.add(Triple(bb, p, dist))
        }
        if (items.isEmpty()) return false
        items.sortByDescending { it.third }
        for ((bb, p, dist) in items) {
            val pxPerStud = height / (2f * dist * tanHalf)
            val size = udim(bb["Size"])
            val w = size.xs * pxPerStud + size.xo * d
            val h = size.ys * pxPerStud + size.yo * d
            if (w < 1f || h < 1f) continue
            val so = (bb["SizeOffset"] as? Vector2) ?: Vector2(0f, 0f)
            val l = p[0] - w / 2f + so.x * w; val t = p[1] - h / 2f - so.y * h
            val r = RectF(l, t, l + w, t + h)
            if (!RectF.intersects(r, RectF(0f, 0f, width.toFloat(), height.toFloat()))) continue
            val clip = bb["ClipsDescendants"] == true
            if (clip) { canvas.save(); canvas.clipRect(r) }
            drawChildren(canvas, bb, r, 1f, if (clip) r else RectF(0f, 0f, width.toFloat(), height.toFloat()))
            if (clip) canvas.restore()
        }
        return true
    }

    // ------------------------------------------------------------------ layout

    private class Node(val inst: Instance, val rect: RectF, val scale: Float, val area: RectF? = null, val inLayout: Boolean = false)

    private fun udim(v: Any?): UDim2 = v as? UDim2 ?: UDim2(0f, 0, 0f, 0)
    private fun enumOf(i: Instance?, prop: String, def: Int): Int = (i?.get(prop) as? EnumValue)?.value ?: def
    private fun num(i: Instance?, prop: String, def: Float): Float = (i?.get(prop) as? Number)?.toFloat() ?: def
    private fun child(p: Instance, cls: String): Instance? = p.children.firstOrNull { it.className == cls }

    private fun isText(c: Instance) = c.className == "TextLabel" || c.className == "TextButton" || c.className == "TextBox"

    private fun textOf(c: Instance): String? = when (c.className) {
        "TextLabel", "TextButton" -> c["Text"] as? String
        "TextBox" -> (c["Text"] as? String)?.ifEmpty { c["PlaceholderText"] as? String }
        else -> null
    }

    /** Content area of an object after UIPadding. */
    private fun padded(p: Instance, r: RectF, scale: Float): RectF {
        val pad = child(p, "UIPadding") ?: return r
        fun u(n: String, size: Float) = (pad[n] as? UDim)?.let { it.scale * size + it.offset * d * scale } ?: 0f
        return RectF(r.left + u("PaddingLeft", r.width()), r.top + u("PaddingTop", r.height()),
            r.right - u("PaddingRight", r.width()), r.bottom - u("PaddingBottom", r.height()))
    }

    /** Applies UIAspectRatioConstraint / UISizeConstraint children of [c] to a size. */
    private fun constrain(c: Instance, w0: Float, h0: Float, scale: Float): FloatArray {
        var w = w0; var h = h0
        for (k in c.children) {
            when (k.className) {
                "UIAspectRatioConstraint" -> {
                    val ratio = num(k, "AspectRatio", 1f).takeIf { it > 0f } ?: 1f
                    if (enumOf(k, "AspectType", 0) == 0) {
                        if (h > 0 && w / h > ratio) w = h * ratio else h = w / ratio
                    } else {
                        if (enumOf(k, "DominantAxis", 0) == 0) h = w / ratio else w = h * ratio
                    }
                }
                "UISizeConstraint" -> {
                    val mn = k["MinSize"] as? Vector2 ?: Vector2(0f, 0f)
                    val mx = k["MaxSize"] as? Vector2 ?: Vector2(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    w = w.coerceIn(mn.x * d * scale, maxOf(mn.x, mx.x) * d * scale)
                    h = h.coerceIn(mn.y * d * scale, maxOf(mn.y, mx.y) * d * scale)
                }
            }
        }
        return floatArrayOf(w, h)
    }

    private fun sizeOf(c: Instance, area: RectF, scale: Float): FloatArray {
        val s = udim(c["Size"])
        val aw = area.width(); val ah = area.height()
        var w = s.xs * aw + s.xo * d * scale
        var h = s.ys * ah + s.yo * d * scale
        when (enumOf(c, "SizeConstraint", 0)) {
            1 -> h = s.ys * aw + s.yo * d * scale
            2 -> w = s.xs * ah + s.xo * d * scale
        }
        val auto = enumOf(c, "AutomaticSize", 0)
        if (auto != 0 && isText(c)) {
            val text = textOf(c) ?: ""
            val face = Fonts.of(c)
            textPaint.typeface = face.typeface; textPaint.textSize = num(c, "TextSize", 14f) * d * scale
            val lines = text.split('\n')
            val tw = lines.maxOf { textPaint.measureText(it) } + 2f * d
            val th = lines.size * textPaint.fontSpacing
            if (auto == 1 || auto == 3) w = maxOf(w, tw)
            if (auto == 2 || auto == 3) {
                val wrapW = maxOf(w, 1f).toInt()
                val l = layoutFor(TKey(text, c["RichText"] == true, face.typeface, face.fakeBold, textPaint.textSize, wrapW, Layout.Alignment.ALIGN_NORMAL, 1f, false))
                h = maxOf(h, if (c["TextWrapped"] == true) l.height.toFloat() else th)
            }
        }
        val cs = constrain(c, w, h, scale)
        w = cs[0]; h = cs[1]
        child(c, "UIScale")?.let { us -> val k = num(us, "Scale", 1f); w *= k; h *= k }
        if (auto != 0 && c.className != "ScrollingFrame") {
            // Grow to fit children (common for lists inside scrolling frames; text objects too).
            // Like Roblox, children sized with Scale along the automatic axis are measured against
            // the nearest non-automatic container (the area this object sits in), not against the
            // object's own (often zero) base size — e.g. a window that's a (1,0) button with
            // AutomaticSize Y holding a (1,-20) content frame becomes as tall as its window.
            // (With a UIListLayout/UIGridLayout the layout's content size decides instead.)
            val hasLayout = c.children.any { it.className == "UIListLayout" || it.className == "UIGridLayout" || it.className == "UITableLayout" || it.className == "UIPageLayout" }
            val tmp = if (hasLayout) RectF(0f, 0f, w, h)
                else RectF(0f, 0f, if (auto == 1 || auto == 3) maxOf(w, aw) else w, if (auto == 2 || auto == 3) maxOf(h, ah) else h)
            val kids = layoutChildren(c, padded(c, tmp, scale), scaleFor(c, scale))
            var mx = w; var my = h
            for (n in kids) { mx = maxOf(mx, n.rect.right); my = maxOf(my, n.rect.bottom) }
            child(c, "UIPadding")?.let { pad ->
                mx += (pad["PaddingRight"] as? UDim)?.offset?.times(d * scale) ?: 0f
                my += (pad["PaddingBottom"] as? UDim)?.offset?.times(d * scale) ?: 0f
            }
            if (auto == 1 || auto == 3) w = mx
            if (auto == 2 || auto == 3) h = my
        }
        return floatArrayOf(maxOf(w, 0f), maxOf(h, 0f))
    }

    private fun scaleFor(c: Instance, parentScale: Float): Float = parentScale * (child(c, "UIScale")?.let { num(it, "Scale", 1f) } ?: 1f)

    private fun sortKids(parent: Instance, kids: List<Instance>, layout: Instance?): List<Instance> {
        if (layout == null) return kids
        return when (enumOf(layout, "SortOrder", 2)) {
            0 -> kids.sortedBy { it.name }
            2 -> kids.sortedBy { (it["LayoutOrder"] as? Number)?.toInt() ?: 0 }
            else -> kids
        }
    }

    private fun layoutChildren(parent: Instance, area: RectF, scale: Float): List<Node> {
        val kids = parent.children.filter { it.isA("GuiObject") && it["Visible"] != false }
        if (kids.isEmpty()) return emptyList()
        val layout = parent.children.firstOrNull { it.className == "UIListLayout" || it.className == "UIGridLayout" || it.className == "UIPageLayout" || it.className == "UITableLayout" }
        val sorted = sortKids(parent, kids, layout)
        val out = ArrayList<Node>(sorted.size)
        val aw = area.width(); val ah = area.height()
        when (layout?.className) {
            "UIGridLayout" -> {
                val cs = udim(layout["CellSize"] ?: UDim2(0f, 100, 0f, 100))
                val cp = udim(layout["CellPadding"] ?: UDim2(0f, 5, 0f, 5))
                var w = cs.xs * aw + cs.xo * d * scale; var h = cs.ys * ah + cs.yo * d * scale
                child(layout, "UIAspectRatioConstraint")?.let { val r = constrain(layout, w, h, scale); w = r[0]; h = r[1] }
                val gx = cp.xs * aw + cp.xo * d * scale; val gy = cp.ys * ah + cp.yo * d * scale
                val horizontal = enumOf(layout, "FillDirection", 0) == 0
                val maxCells = (layout["FillDirectionMaxCells"] as? Number)?.toInt() ?: 0
                val n = sorted.size
                var perLine = if (horizontal) floor((aw + gx) / (w + gx)).toInt() else floor((ah + gy) / (h + gy)).toInt()
                perLine = perLine.coerceAtLeast(1)
                if (maxCells > 0) perLine = minOf(perLine, maxCells)
                val lines = ceil(n / perLine.toFloat()).toInt()
                val cols = if (horizontal) minOf(n, perLine) else lines
                val rows = if (horizontal) lines else minOf(n, perLine)
                val bw = cols * w + (cols - 1).coerceAtLeast(0) * gx
                val bh = rows * h + (rows - 1).coerceAtLeast(0) * gy
                setContentSize(layout, bw, bh)
                val x0 = when (enumOf(layout, "HorizontalAlignment", 1)) { 0 -> area.left + (aw - bw) / 2f; 2 -> area.right - bw; else -> area.left }
                val y0 = when (enumOf(layout, "VerticalAlignment", 1)) { 0 -> area.top + (ah - bh) / 2f; 2 -> area.bottom - bh; else -> area.top }
                sorted.forEachIndexed { i, c ->
                    val col = if (horizontal) i % perLine else i / perLine
                    val row = if (horizontal) i / perLine else i % perLine
                    val x = x0 + col * (w + gx); val y = y0 + row * (h + gy)
                    out.add(Node(c, RectF(x, y, x + w, y + h), scaleFor(c, scale)))
                }
            }
            "UIListLayout", "UITableLayout", "UIPageLayout" -> {
                val horizontal = enumOf(layout, "FillDirection", 1) == 0
                val list = if (layout.className == "UIPageLayout") sorted.take(1) else sorted
                val padU = layout["Padding"] as? UDim
                val pad = padU?.let { it.scale * (if (horizontal) aw else ah) + it.offset * d * scale } ?: 0f
                val sizes = list.map { sizeOf(it, area, scale) }
                val total = sizes.sumOf { (if (horizontal) it[0] else it[1]).toDouble() }.toFloat() + pad * (list.size - 1).coerceAtLeast(0)
                val cross = sizes.maxOfOrNull { if (horizontal) it[1] else it[0] } ?: 0f
                setContentSize(layout, if (horizontal) total else cross, if (horizontal) cross else total)
                val hA = enumOf(layout, "HorizontalAlignment", 1)
                val vA = enumOf(layout, "VerticalAlignment", 1)
                var cursor = if (horizontal) when (hA) { 0 -> area.left + (aw - total) / 2f; 2 -> area.right - total; else -> area.left }
                else when (vA) { 0 -> area.top + (ah - total) / 2f; 2 -> area.bottom - total; else -> area.top }
                list.forEachIndexed { i, c ->
                    val (w, h) = sizes[i].let { it[0] to it[1] }
                    val x: Float; val y: Float
                    if (horizontal) {
                        x = cursor; cursor += w + pad
                        y = when (vA) { 0 -> area.top + (ah - h) / 2f; 2 -> area.bottom - h; else -> area.top }
                    } else {
                        y = cursor; cursor += h + pad
                        x = when (hA) { 0 -> area.left + (aw - w) / 2f; 2 -> area.right - w; else -> area.left }
                    }
                    out.add(Node(c, RectF(x, y, x + w, y + h), scaleFor(c, scale)))
                }
            }
            else -> for (c in sorted) {
                val (w, h) = sizeOf(c, area, scale).let { it[0] to it[1] }
                val pos = udim(c["Position"])
                val anchor = c["AnchorPoint"] as? Vector2 ?: Vector2(0f, 0f)
                val x = area.left + pos.xs * aw + pos.xo * d * scale - anchor.x * w
                val y = area.top + pos.ys * ah + pos.yo * d * scale - anchor.y * h
                out.add(Node(c, RectF(x, y, x + w, y + h), scaleFor(c, scale)))
            }
        }
        // Siblings draw in ZIndex order; ties keep layout order.
        val inLayout = layout != null
        return out.map { Node(it.inst, it.rect, it.scale, area, inLayout) }.sortedBy { (it.inst["ZIndex"] as? Number)?.toInt() ?: 1 }
    }

    private fun drawChildren(canvas: Canvas, parent: Instance, area: RectF, scale: Float, clip: RectF) {
        val content = padded(parent, area, scale)
        for (n in layoutChildren(parent, content, scale)) drawNode(canvas, n, clip)
    }

    private fun drawNode(canvas: Canvas, n: Node, clip: RectF) {
        val c = n.inst
        val r = n.rect
        setAbs(c, r)
        if (r.width() <= 0f || r.height() <= 0f) {
            if (c.className != "ScrollingFrame" && c["ClipsDescendants"] == true) return
        }
        val rot = num(c, "Rotation", 0f)
        val groupT = if (c.className == "CanvasGroup") num(c, "GroupTransparency", 0f) else 0f
        if (groupT >= 0.999f) return
        val saveCount = canvas.save()
        if (rot != 0f) canvas.rotate(rot, r.centerX(), r.centerY())
        if (groupT > 0.001f) canvas.saveLayerAlpha(r, ((1f - groupT) * 255).toInt())
        drawObject(canvas, c, r, n.scale)
        val visible = RectF(r)
        val hitRect = RectF(r).apply { if (!intersect(clip)) setEmpty() }
        if (n.area != null && !studio.isPlaying) newBoxes[c] = EditBox(RectF(r), RectF(n.area), n.scale, n.inLayout)
        if (!hitRect.isEmpty) {
            newPicks.add(c to RectF(r))
            when {
                c.className == "TextBox" -> newHits.add(Hit(c, hitRect, rt, TEXTBOX))
                c.isA("GuiButton") -> newHits.add(Hit(c, hitRect, rt, BUTTON))
                c["Active"] == true -> newHits.add(Hit(c, hitRect, rt, SINK))
            }
        }
        if (c.className == "ScrollingFrame") {
            drawScrolling(canvas, c, r, n.scale, clip)
        } else {
            val clips = c["ClipsDescendants"] == true
            val childClip = if (clips) RectF(visible).apply { if (!intersect(clip)) setEmpty() } else clip
            if (clips) canvas.clipRect(r)
            drawChildren(canvas, c, r, n.scale, childClip)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawScrolling(canvas: Canvas, c: Instance, r: RectF, scale: Float, clip: RectF) {
        val unit = d * scale
        val cs = udim(c["CanvasSize"] ?: UDim2(0f, 0, 2f, 0))
        var cw = maxOf(cs.xs * r.width() + cs.xo * unit, r.width())
        var ch = maxOf(cs.ys * r.height() + cs.yo * unit, r.height())
        val auto = enumOf(c, "AutomaticCanvasSize", 0)
        val pos0 = c["CanvasPosition"] as? Vector2 ?: Vector2(0f, 0f)
        fun canvasRect() = RectF(r.left - pos0.x * unit, r.top - pos0.y * unit, r.left - pos0.x * unit + cw, r.top - pos0.y * unit + ch)
        var area = canvasRect()
        var nodes = layoutChildren(c, padded(c, area, scale), scale)
        if (auto != 0 && nodes.isNotEmpty()) {
            val bottom = nodes.maxOf { it.rect.bottom } - area.top + (child(c, "UIPadding")?.let { (it["PaddingBottom"] as? UDim)?.offset?.times(unit) } ?: 0f)
            val right = nodes.maxOf { it.rect.right } - area.left
            var changed = false
            if ((auto == 2 || auto == 3) && bottom > ch) { ch = bottom; changed = true }
            if ((auto == 1 || auto == 3) && right > cw) { cw = right; changed = true }
            if (changed) { area = canvasRect(); nodes = layoutChildren(c, padded(c, area, scale), scale) }
        }
        val maxX = maxOf(0f, cw - r.width()); val maxY = maxOf(0f, ch - r.height())
        val vis = RectF(r).apply { if (!intersect(clip)) setEmpty() }
        if (!vis.isEmpty && c["ScrollingEnabled"] != false && (maxX > 0f || maxY > 0f)) newScrolls.add(Scroll(c, vis, maxX / unit, maxY / unit, unit))
        canvas.save()
        canvas.clipRect(r)
        for (n in nodes) drawNode(canvas, n, vis)
        canvas.restore()
        // scroll bar
        val th = num(c, "ScrollBarThickness", 12f) * unit * 0.5f
        val bt = num(c, "ScrollBarImageTransparency", 0f)
        if (maxY > 0f && th > 0f && bt < 1f) {
            val barH = r.height() * (r.height() / ch)
            val y = r.top + (r.height() - barH) * ((pos0.y * unit) / maxY).coerceIn(0f, 1f)
            fill.shader = null
            fill.color = alpha(colorOf(c["ScrollBarImageColor3"], 0xFF000000.toInt()), 0.3f + 0.7f * bt)
            canvas.drawRoundRect(r.right - th - 2f * d, y, r.right - 2f * d, y + barH, th / 2f, th / 2f, fill)
        }
    }

    // ------------------------------------------------------------------ objects

    private fun colorOf(v: Any?, default: Int): Int = (v as? Color3)?.toArgb() ?: default

    private fun alpha(c: Int, transparency: Float): Int {
        val a = ((1f - transparency.coerceIn(0f, 1f)) * ((c ushr 24) and 0xFF)).toInt()
        return (c and 0x00FFFFFF) or (a shl 24)
    }

    private fun cornerOf(c: Instance, r: RectF, scale: Float): Float =
        child(c, "UICorner")?.let { k ->
            ((k["CornerRadius"] as? UDim)?.let { it.scale * minOf(r.width(), r.height()) + it.offset * d * scale } ?: 8f * d * scale)
                .coerceIn(0f, minOf(r.width(), r.height()) / 2f)
        } ?: 0f

    /** Shader for a UIGradient over [r] (colors combined with its Transparency sequence). */
    private fun gradientShader(g: Instance, r: RectF, baseTransparency: Float): Shader? {
        val cseq = g["Color"] as? ColorSequence
        val tseq = g["Transparency"] as? NumberSequence
        val times = (listOf(0f, 1f) + (cseq?.keys?.map { it.time } ?: emptyList()) + (tseq?.keys?.map { it.time } ?: emptyList())).distinct().sorted()
        fun colorAt(t: Float): Color3 {
            val ks = cseq?.keys ?: return Color3(1f, 1f, 1f)
            if (ks.isEmpty()) return Color3(1f, 1f, 1f)
            for (i in 0 until ks.size - 1) if (t <= ks[i + 1].time) {
                val a = ks[i]; val b = ks[i + 1]
                val f = if (b.time > a.time) (t - a.time) / (b.time - a.time) else 0f
                return a.color.lerp(b.color, f.coerceIn(0f, 1f))
            }
            return ks.last().color
        }
        fun transAt(t: Float): Float {
            val ks = tseq?.keys ?: return 0f
            if (ks.isEmpty()) return 0f
            for (i in 0 until ks.size - 1) if (t <= ks[i + 1].time) {
                val a = ks[i]; val b = ks[i + 1]
                val f = if (b.time > a.time) (t - a.time) / (b.time - a.time) else 0f
                return a.value + (b.value - a.value) * f.coerceIn(0f, 1f)
            }
            return ks.last().value
        }
        val colors = times.map { t -> alpha(colorAt(t).toArgb(), 1f - (1f - baseTransparency) * (1f - transAt(t))) }.toIntArray()
        val rot = Math.toRadians(num(g, "Rotation", 0f).toDouble())
        val dx = cos(rot).toFloat(); val dy = sin(rot).toFloat()
        val off = g["Offset"] as? Vector2 ?: Vector2(0f, 0f)
        val half = (abs(r.width() * dx) + abs(r.height() * dy)) / 2f
        val cx = r.centerX() + off.x * r.width(); val cy = r.centerY() + off.y * r.height()
        return LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, times.toFloatArray(), Shader.TileMode.CLAMP)
    }

    private fun drawObject(canvas: Canvas, c: Instance, r: RectF, scale: Float) {
        val bgT = num(c, "BackgroundTransparency", 0f)
        val corner = cornerOf(c, r, scale)
        val gradient = c.children.firstOrNull { it.className == "UIGradient" && it["Enabled"] != false }
        val uiStroke = c.children.firstOrNull { it.className == "UIStroke" && it["Enabled"] != false }
        val textStroke = uiStroke != null && isText(c) && enumOf(uiStroke, "ApplyStrokeMode", 0) == 0
        if (bgT < 1f) {
            fill.color = alpha(colorOf(c["BackgroundColor3"], 0xFFA3A2A5.toInt()), bgT)
            fill.shader = gradient?.let { gradientShader(it, r, bgT) }
            if (corner > 0) canvas.drawRoundRect(r, corner, corner, fill) else canvas.drawRect(r, fill)
            fill.shader = null
            if (pressed === c && c["AutoButtonColor"] != false) {
                fill.color = 0x40000000
                if (corner > 0) canvas.drawRoundRect(r, corner, corner, fill) else canvas.drawRect(r, fill)
            }
            val border = num(c, "BorderSizePixel", 0f) * scale
            if (border > 0 && corner == 0f && uiStroke == null) {
                stroke.shader = null
                stroke.color = alpha(colorOf(c["BorderColor3"], 0xFF1B2A35.toInt()), bgT)
                stroke.strokeWidth = border * d
                val o = when (enumOf(c, "BorderMode", 0)) { 0 -> border * d / 2f; 1 -> 0f; else -> -border * d / 2f }
                canvas.drawRect(r.left - o, r.top - o, r.right + o, r.bottom + o, stroke)
            }
        }
        if (uiStroke != null && !textStroke) {
            val t = num(uiStroke, "Thickness", 1f) * d * scale
            stroke.shader = null
            stroke.color = alpha(colorOf(uiStroke["Color"], 0xFF000000.toInt()), num(uiStroke, "Transparency", 0f))
            stroke.strokeWidth = t
            stroke.strokeJoin = Paint.Join.ROUND
            val rr = RectF(r.left - t / 2f, r.top - t / 2f, r.right + t / 2f, r.bottom + t / 2f)
            if (corner > 0) canvas.drawRoundRect(rr, corner + t / 2f, corner + t / 2f, stroke) else canvas.drawRect(rr, stroke)
        }
        if (c.className == "ImageLabel" || c.className == "ImageButton") drawImage(canvas, c, r, corner, scale)
        // Like Roblox, a UIPadding inside a text object insets its text (plugin section headers
        // pad their title past the collapse arrow).
        if (isText(c)) drawText(canvas, c, padded(c, r, scale), scale, if (textStroke) uiStroke else null, if (bgT >= 1f) gradient else null)
    }

    private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val multiplyMode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY)
    private val dstInMode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)

    /**
     * A UIGradient over [r] as two shaders: its colors (opaque, multiplied into the image) and
     * its Transparency (alpha mask). Roblox applies a UIGradient inside an ImageLabel to the image.
     */
    private fun gradientMaskShaders(g: Instance, r: RectF): Pair<Shader, Shader> {
        val cseq = g["Color"] as? ColorSequence
        val tseq = g["Transparency"] as? NumberSequence
        val times = (listOf(0f, 1f) + (cseq?.keys?.map { it.time } ?: emptyList()) + (tseq?.keys?.map { it.time } ?: emptyList()))
            .map { it.coerceIn(0f, 1f) }.distinct().sorted()
        fun colorAt(t: Float): Color3 {
            val ks = cseq?.keys.orEmpty()
            if (ks.isEmpty()) return Color3(1f, 1f, 1f)
            for (i in 0 until ks.size - 1) if (t <= ks[i + 1].time) {
                val a = ks[i]; val b = ks[i + 1]
                val f = if (b.time > a.time) (t - a.time) / (b.time - a.time) else 0f
                return a.color.lerp(b.color, f.coerceIn(0f, 1f))
            }
            return ks.last().color
        }
        fun transAt(t: Float): Float {
            val ks = tseq?.keys.orEmpty()
            if (ks.isEmpty()) return 0f
            for (i in 0 until ks.size - 1) if (t <= ks[i + 1].time) {
                val a = ks[i]; val b = ks[i + 1]
                val f = if (b.time > a.time) (t - a.time) / (b.time - a.time) else 0f
                return a.value + (b.value - a.value) * f.coerceIn(0f, 1f)
            }
            return ks.last().value
        }
        val rot = Math.toRadians(num(g, "Rotation", 0f).toDouble())
        val dx = cos(rot).toFloat(); val dy = sin(rot).toFloat()
        val off = g["Offset"] as? Vector2 ?: Vector2(0f, 0f)
        val half = (abs(r.width() * dx) + abs(r.height() * dy)) / 2f
        val cx = r.centerX() + off.x * r.width(); val cy = r.centerY() + off.y * r.height()
        val pos = times.toFloatArray()
        val colors = times.map { (colorAt(it).toArgb() and 0x00FFFFFF) or 0xFF000000.toInt() }.toIntArray()
        val alphas = times.map { (((1f - transAt(it).coerceIn(0f, 1f)) * 255f).toInt() shl 24) }.toIntArray()
        val x0 = cx - dx * half; val y0 = cy - dy * half; val x1 = cx + dx * half; val y1 = cy + dy * half
        return LinearGradient(x0, y0, x1, y1, colors, pos, Shader.TileMode.CLAMP) to
            LinearGradient(x0, y0, x1, y1, alphas, pos, Shader.TileMode.CLAMP)
    }

    private fun drawImage(canvas: Canvas, c: Instance, r: RectF, corner: Float, scale: Float) {
        val it = num(c, "ImageTransparency", 0f)
        if (it >= 1f) return
        val content = c["Image"]?.toString() ?: return
        // Sprite sheets (ImageRectOffset/Size) need the image at its real pixel size: the public
        // thumbnail is rescaled to 420x420, so offsets would land in the wrong place. When signed
        // in, the original file is used; otherwise the sheet size is guessed.
        val rectSize = c["ImageRectSize"] as? Vector2
        var exact = false
        var bmp: Bitmap? = null
        if (rectSize != null && rectSize.x > 0f && rectSize.y > 0f) {
            ImageLoader.assetId(content)?.let { id -> com.leaf.lemonade.net.AssetFetcher.image(id)?.let { b -> bmp = b; exact = true } }
        }
        val image = bmp ?: ImageLoader.get(content) ?: return
        val tint = colorOf(c["ImageColor3"], 0xFFFFFFFF.toInt())
        imgPaint.colorFilter = if (tint and 0xFFFFFF != 0xFFFFFF) LightingColorFilter(tint and 0xFFFFFF, 0) else null
        val src = if (exact) exactRect(c, image) else sourceRect(c, image)
        val gradient = c.children.firstOrNull { g -> g.className == "UIGradient" && g["Enabled"] != false }
        if (gradient != null) {
            // Draw into a layer, then multiply the gradient's colors in and mask by its transparency.
            val layer = canvas.saveLayerAlpha(r.left - 1f, r.top - 1f, r.right + 1f, r.bottom + 1f, ((1f - it) * 255).toInt())
            imgPaint.alpha = 255
            drawImageBody(canvas, c, image, src, r, corner, scale)
            val (colorSh, alphaSh) = gradientMaskShaders(gradient, r)
            gradPaint.shader = colorSh; gradPaint.xfermode = multiplyMode
            canvas.drawRect(r, gradPaint)
            gradPaint.shader = alphaSh; gradPaint.xfermode = dstInMode
            canvas.drawRect(r, gradPaint)
            gradPaint.shader = null; gradPaint.xfermode = null
            canvas.restoreToCount(layer)
            return
        }
        imgPaint.alpha = ((1f - it) * 255).toInt()
        drawImageBody(canvas, c, image, src, r, corner, scale)
    }

    private fun exactRect(c: Instance, bmp: Bitmap): Rect {
        val size = c["ImageRectSize"] as Vector2
        val off = c["ImageRectOffset"] as? Vector2 ?: Vector2(0f, 0f)
        val l = off.x.toInt().coerceIn(0, bmp.width - 1); val t = off.y.toInt().coerceIn(0, bmp.height - 1)
        return Rect(l, t, (off.x + size.x).toInt().coerceIn(l + 1, bmp.width), (off.y + size.y).toInt().coerceIn(t + 1, bmp.height))
    }

    private fun drawImageBody(canvas: Canvas, c: Instance, bmp: Bitmap, src: Rect, r: RectF, corner: Float, scale: Float) {
        canvas.save()
        if (corner > 0) canvas.clipPath(Path().apply { addRoundRect(r, corner, corner, Path.Direction.CW) })
        when (enumOf(c, "ScaleType", 0)) {
            3 -> { // Fit
                val k = minOf(r.width() / src.width(), r.height() / src.height())
                val w = src.width() * k; val h = src.height() * k
                canvas.drawBitmap(bmp, src, RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), imgPaint)
            }
            4 -> { // Crop
                val k = maxOf(r.width() / src.width(), r.height() / src.height())
                val sw = r.width() / k; val sh = r.height() / k
                val s2 = Rect((src.exactCenterX() - sw / 2).toInt(), (src.exactCenterY() - sh / 2).toInt(), (src.exactCenterX() + sw / 2).toInt(), (src.exactCenterY() + sh / 2).toInt())
                canvas.drawBitmap(bmp, s2, r, imgPaint)
            }
            2 -> { // Tile
                val ts = udim(c["TileSize"] ?: UDim2(1f, 0, 1f, 0))
                val tw = (ts.xs * r.width() + ts.xo * d * scale).coerceAtLeast(2f)
                val th = (ts.ys * r.height() + ts.yo * d * scale).coerceAtLeast(2f)
                val sub = Bitmap.createBitmap(bmp, src.left, src.top, src.width().coerceAtLeast(1), src.height().coerceAtLeast(1))
                val sh = BitmapShader(sub, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                sh.setLocalMatrix(Matrix().apply { setScale(tw / sub.width, th / sub.height); postTranslate(r.left, r.top) })
                imgPaint.shader = sh
                canvas.drawRect(r, imgPaint)
                imgPaint.shader = null
            }
            1 -> drawSliced(canvas, c, bmp, src, r, scale)
            else -> canvas.drawBitmap(bmp, src, r, imgPaint)
        }
        canvas.restore()
    }

    /**
     * Source rectangle within the loaded image. Thumbnails don't tell us the original size, so for
     * sprite sheets (ImageRectOffset/Size) assume the common power-of-two square sheet sizes.
     */
    private fun sourceRect(c: Instance, bmp: Bitmap): Rect {
        val size = c["ImageRectSize"] as? Vector2
        val off = c["ImageRectOffset"] as? Vector2 ?: Vector2(0f, 0f)
        if (size == null || size.x <= 0f || size.y <= 0f) return Rect(0, 0, bmp.width, bmp.height)
        val need = maxOf(off.x + size.x, off.y + size.y)
        var sheet = 64f
        while (sheet < need) sheet *= 2f
        // A sheet the cells fill exactly (e.g. 2x2 cells of 60 = 120) is more likely than padding
        // up to the next power of two when the cells are not power-of-two sized themselves.
        val cell = maxOf(size.x, size.y)
        val pow2Cell = cell > 0f && (cell.toInt() and (cell.toInt() - 1)) == 0
        if (!pow2Cell && need < sheet && need % cell == 0f) sheet = need
        val k = bmp.width / sheet
        return Rect((off.x * k).toInt(), (off.y * k).toInt(), ((off.x + size.x) * k).toInt().coerceAtMost(bmp.width), ((off.y + size.y) * k).toInt().coerceAtMost(bmp.height))
    }

    private fun drawSliced(canvas: Canvas, c: Instance, bmp: Bitmap, src: Rect, r: RectF, scale: Float) {
        val sc = c["SliceCenter"] as? com.leaf.lemonade.model.Rect
        if (sc == null || sc.maxX <= sc.minX || sc.maxY <= sc.minY) { canvas.drawBitmap(bmp, src, r, imgPaint); return }
        // Guess the original image size assuming symmetric slicing.
        val ow = (sc.minX + sc.maxX).coerceAtLeast(1f); val oh = (sc.minY + sc.maxY).coerceAtLeast(1f)
        val kx = src.width() / ow; val ky = src.height() / oh
        val ss = num(c, "SliceScale", 1f) * d * scale
        val sl = sc.minX * kx; val st = sc.minY * ky; val sr = (ow - sc.maxX) * kx; val sb = (oh - sc.maxY) * ky
        var dl = sc.minX * ss; var dt = sc.minY * ss; var dr = (ow - sc.maxX) * ss; var db = (oh - sc.maxY) * ss
        if (dl + dr > r.width()) { val f = r.width() / (dl + dr); dl *= f; dr *= f }
        if (dt + db > r.height()) { val f = r.height() / (dt + db); dt *= f; db *= f }
        val xs = floatArrayOf(src.left.toFloat(), src.left + sl, src.right - sr, src.right.toFloat())
        val ys = floatArrayOf(src.top.toFloat(), src.top + st, src.bottom - sb, src.bottom.toFloat())
        val xd = floatArrayOf(r.left, r.left + dl, r.right - dr, r.right)
        val yd = floatArrayOf(r.top, r.top + dt, r.bottom - db, r.bottom)
        for (i in 0 until 3) for (j in 0 until 3) {
            if (xs[i + 1] <= xs[i] || ys[j + 1] <= ys[j] || xd[i + 1] <= xd[i] || yd[j + 1] <= yd[j]) continue
            canvas.drawBitmap(bmp, Rect(xs[i].toInt(), ys[j].toInt(), xs[i + 1].toInt(), ys[j + 1].toInt()), RectF(xd[i], yd[j], xd[i + 1], yd[j + 1]), imgPaint)
        }
    }

    private fun drawText(canvas: Canvas, c: Instance, r: RectF, scale: Float, uiStroke: Instance?, gradient: Instance?) {
        var text = textOf(c) ?: return
        if (text.isEmpty()) return
        val tt = num(c, "TextTransparency", 0f)
        if (tt >= 1f) return
        val maxG = (c["MaxVisibleGraphemes"] as? Number)?.toInt() ?: -1
        if (maxG >= 0) text = text.take(maxG)
        if (text.isEmpty()) return
        val rich = c["RichText"] == true
        val placeholder = c.className == "TextBox" && (c["Text"] as? String).isNullOrEmpty()
        val color = alpha(colorOf(if (placeholder) c["PlaceholderColor3"] ?: Color3(0.7f, 0.7f, 0.7f) else c["TextColor3"], 0xFF000000.toInt()), tt)
        val face = Fonts.of(c)
        val lineH = num(c, "LineHeight", 1f).coerceIn(0.5f, 3f)
        val sizeLimit = child(c, "UITextSizeConstraint")
        var size = num(c, "TextSize", 14f) * d * scale
        val wrapped = c["TextWrapped"] == true
        if (c["TextScaled"] == true) {
            val maxS = minOf(100f, sizeLimit?.let { num(it, "MaxTextSize", 100f) } ?: 100f) * d * scale
            val minS = (sizeLimit?.let { num(it, "MinTextSize", 1f) } ?: 1f) * d * scale
            size = scaledSize(SKey(text, rich, face.typeface, face.fakeBold, r.width().toInt(), r.height().toInt(), maxS, minS, lineH))
        } else if (sizeLimit != null) {
            size = size.coerceIn(num(sizeLimit, "MinTextSize", 1f) * d * scale, num(sizeLimit, "MaxTextSize", 100f) * d * scale)
        }
        if (size < 1f) return
        val xAlign = enumOf(c, "TextXAlignment", 2)
        val yAlign = enumOf(c, "TextYAlignment", 1)
        val align = when (xAlign) { 0 -> Layout.Alignment.ALIGN_NORMAL; 1 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER }
        val wrap = wrapped || c["TextScaled"] == true
        val truncate = !wrap && enumOf(c, "TextTruncate", 0) != 0
        val rw = r.width().toInt().coerceAtLeast(1)
        val wrapW = if (wrap || truncate) rw else {
            textPaint.typeface = face.typeface; textPaint.textSize = size; textPaint.isFakeBoldText = face.fakeBold
            val plain = if (rich) RichText.parse(text).toString() else text
            maxOf(rw, plain.split('\n').maxOf { textPaint.measureText(it) }.toInt() + 2)
        }
        val sl = layoutFor(TKey(text, rich, face.typeface, face.fakeBold, size, wrapW, align, lineH, truncate))
        val tx = if (wrapW > r.width()) r.left - when (xAlign) { 0 -> 0f; 1 -> wrapW - r.width(); else -> (wrapW - r.width()) / 2f } else r.left
        val ty = when (yAlign) { 0 -> r.top; 2 -> r.bottom - sl.height; else -> r.top + (r.height() - sl.height) / 2f }
        val p = sl.paint
        canvas.save()
        if (c["ClipsDescendants"] == true) canvas.clipRect(r)
        canvas.translate(tx, ty)
        // Outline: UIStroke (contextual) or the legacy TextStroke properties.
        val strokeT = if (uiStroke != null) num(uiStroke, "Transparency", 0f) else num(c, "TextStrokeTransparency", 1f)
        if (strokeT < 1f) {
            val sc = if (uiStroke != null) colorOf(uiStroke["Color"], 0xFF000000.toInt()) else colorOf(c["TextStrokeColor3"], 0xFF000000.toInt())
            val sw = if (uiStroke != null) num(uiStroke, "Thickness", 1f) * d * scale * 2f else 1.6f * d * scale
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeJoin = Paint.Join.ROUND
            p.color = alpha(sc, 1f - (1f - strokeT) * (1f - tt))
            if (rich) drawRichStroke(canvas, sl, p) else sl.draw(canvas)
            p.style = Paint.Style.FILL
        }
        p.color = color
        p.shader = gradient?.let { g -> gradientShader(g, r, tt)?.also { s -> s.setLocalMatrix(Matrix().apply { setTranslate(-tx, -ty) }) } }
        sl.draw(canvas)
        p.shader = null
        canvas.restore()
    }

    /** Rich text spans override the paint color, so stroke passes need the spans stripped. */
    private fun drawRichStroke(canvas: Canvas, sl: StaticLayout, p: TextPaint) {
        val plain = sl.text.toString()
        StaticLayout.Builder.obtain(plain, 0, plain.length, p, sl.width).setAlignment(sl.alignment).setIncludePad(false)
            .setLineSpacing(0f, sl.spacingMultiplier).build().draw(canvas)
    }

    // ------------------------------------------------------------------ input

    /** Topmost button/textbox/core control at a point. */
    fun buttonAt(x: Float, y: Float): Hit? = hits.lastOrNull { it.kind != SINK && it.rect.contains(x, y) }

    /** GUI that belongs to a plugin (a dock widget or something it put in CoreGui). */
    fun isPluginUi(i: Instance): Boolean {
        if (studio.pluginWidgets.any { i === it.inst || i.isDescendantOf(it.inst) }) return true
        var p = i.parent
        while (p != null) { if (p.className == "CoreGui") return true; p = p.parent }
        return false
    }

    /** True if a plugin GUI covers (x, y) (edit mode: touches there don't reach the 3D view). */
    fun pluginUiAt(x: Float, y: Float): Boolean = hits.any {
        it.rect.contains(x, y) && (it.kind == WIDGET_CLOSE || (isPluginUi(it.inst) && (it.kind != SINK || !seeThrough(it.inst))))
    }

    /**
     * True if a GUI object that blocks world input covers the point: a button, a text box, or an
     * Active frame you can actually see (invisible Active containers let the camera turn).
     */
    fun blocksAt(x: Float, y: Float): Boolean = hits.any { it.rect.contains(x, y) && (it.kind != SINK || !seeThrough(it.inst)) }

    fun scrollAt(x: Float, y: Float): Scroll? = scrolls.lastOrNull { it.rect.contains(x, y) }

    fun scrollBy(s: Scroll, dxPx: Float, dyPx: Float) {
        synchronized(studio.lock) {
            val p = s.inst["CanvasPosition"] as? Vector2 ?: Vector2(0f, 0f)
            val nx = (p.x - dxPx / s.unit).coerceIn(0f, s.maxX)
            val ny = (p.y - dyPx / s.unit).coerceIn(0f, s.maxY)
            if (nx != p.x || ny != p.y) s.inst.set("CanvasPosition", Vector2(nx, ny))
        }
        invalidate()
    }

    /** Plugins are written for a mouse: in edit mode their GUIs get touches as MouseButton1 / MouseMovement. */
    private fun mouseFor(i: Instance) = studio.play == null && isPluginUi(i)

    /** Edit mode: UserInputService events for plugins (Roact UIs drag windows with InputChanged). */
    private fun uisFire(event: String, input: LuaValue) {
        val uis = studio.editModel.findService("UserInputService") ?: return
        studio.editRuntime.fire(uis, event, input, LuaValue.TRUE)
    }

    /**
     * Topmost Active plugin frame (not a button) at a point, in edit mode: plugins listen for
     * InputBegan on plain frames too (Archimedes' collapsible section headers, slider tracks).
     */
    fun pluginFrameAt(x: Float, y: Float): Hit? =
        if (studio.play != null) null else hits.lastOrNull { it.kind == SINK && it.rt != null && it.rect.contains(x, y) && isPluginUi(it.inst) }

    /**
     * Plugin frames drawn above a pressed button that also get the press's InputBegan/Changed/
     * Ended (like Roblox, input reaches every object under the mouse, not just the topmost
     * button): Archimedes' window is one big TextButton with collapsible headers on top of it.
     */
    private val pressExtras = java.util.IdentityHashMap<Hit, List<Hit>>()

    private fun extrasFor(h: Hit, x: Float, y: Float): List<Hit> {
        val i = hits.indexOf(h)
        if (i < 0) return emptyList()
        return hits.subList(i + 1, hits.size).filter { it.kind == SINK && it.rt != null && it.rect.contains(x, y) && isPluginUi(it.inst) }.asReversed()
    }

    fun press(h: Hit, x: Float, y: Float) {
        if (h.kind != BUTTON && h.kind != SINK) return
        if (h.kind == BUTTON) pressed = h.inst
        val r = h.rt ?: return
        val mouse = mouseFor(h.inst)
        synchronized(studio.lock) {
            val extras = if (mouse) extrasFor(h, x, y) else emptyList()
            if (extras.isNotEmpty()) pressExtras[h] = extras else pressExtras.remove(h)
            // A mouse is over a button before it clicks: plugins show hover state on MouseEnter /
            // InputBegan(MouseMovement) and many only refresh when the mouse leaves again.
            if (mouse) { for (e in extras) hover(e, x, y, true); hover(h, x, y, true) }
            for (e in extras) e.rt?.fire(e.inst, "InputBegan", touchInput(x, y, 0, true))
            if (h.kind == BUTTON) guiPoint(x, y).let { p -> r.fire(h.inst, "MouseButton1Down", LuaValue.valueOf(p.x.toDouble()), LuaValue.valueOf(p.y.toDouble())) }
            r.fire(h.inst, "InputBegan", touchInput(x, y, 0, mouse))
            if (mouse) uisFire("InputBegan", touchInput(x, y, 0, true))
        }
        invalidate()
    }

    /** Ends the press for the frames that got it with [h] (see [pressExtras]). */
    private fun releaseExtras(h: Hit, x: Float, y: Float) {
        val extras = pressExtras.remove(h) ?: return
        for (e in extras) { e.rt?.fire(e.inst, "InputEnded", touchInput(x, y, 2, true)); hover(e, x, y, false) }
    }

    /** The "mouse" (finger) entering or leaving a plugin GUI object. */
    private fun hover(h: Hit, x: Float, y: Float, enter: Boolean) {
        val r = h.rt ?: return
        val p = guiPoint(x, y)
        r.fire(h.inst, if (enter) "MouseEnter" else "MouseLeave", LuaValue.valueOf(p.x.toDouble()), LuaValue.valueOf(p.y.toDouble()))
        r.fire(h.inst, if (enter) "InputBegan" else "InputEnded", touchInput(x, y, if (enter) 0 else 2, true, movement = true))
    }

    /**
     * Edit mode: can a finger drag that started on [h] scroll the plugin panel instead? Only when
     * the touched object doesn't follow drags itself (a dial or slider listens to InputChanged)
     * and something above it takes mouse-wheel input (Roact scroll frames scroll on MouseWheel).
     */
    fun canWheel(h: Hit, x: Float, y: Float): Boolean {
        if (studio.play != null || !isPluginUi(h.inst)) return false
        val r = h.rt ?: return false
        return synchronized(studio.lock) {
            if (r.hasConn(h.inst, "InputChanged")) return@synchronized false
            if (pressExtras[h]?.any { it.rt?.hasConn(it.inst, "InputChanged") == true } == true) return@synchronized false
            val top = pluginObjectAt(x, y) ?: return@synchronized false
            generateSequence(top) { it.parent }.takeWhile { it.className != "CoreGui" }.any { r.hasConn(it, "InputChanged") }
        }
    }

    /** Topmost drawn plugin GUI object at a point (whether or not it takes input). */
    private fun pluginObjectAt(x: Float, y: Float): Instance? =
        if (studio.play != null) null else picks.lastOrNull { (i, r) -> r.contains(x, y) && isPluginUi(i) }?.first

    /** The Lua runtime that owns a plugin GUI object (its dock widget's plugin, else the edit runtime). */
    private fun pluginRt(i: Instance) = studio.pluginWidgets.firstOrNull { i === it.inst || i.isDescendantOf(it.inst) }?.rt ?: studio.editRuntime

    /**
     * Mouse-wheel notches ([z] = +1 per notch toward the user, like dragging content down) for
     * the plugin GUI under (x, y): every object from there up that listens to InputChanged gets it.
     */
    fun pluginWheel(x: Float, y: Float, z: Int) {
        val top = pluginObjectAt(x, y) ?: return
        val r = pluginRt(top)
        synchronized(studio.lock) {
            val lt = com.leaf.lemonade.lua.LuaTypes
            fun wheel(): LuaValue {
                val t = org.luaj.vm2.LuaTable()
                t.set("UserInputType", lt.wrap(com.leaf.lemonade.lua.EnumItemV("UserInputType", "MouseWheel", 3)))
                t.set("UserInputState", lt.wrap(com.leaf.lemonade.lua.EnumItemV("UserInputState", "Change", 1)))
                val p = guiPoint(x, y)
                t.set("Position", lt.wrap(Vector3(p.x, p.y, z.toFloat())))
                t.set("Delta", lt.wrap(Vector3(0f, 0f, z.toFloat())))
                t.set("KeyCode", lt.wrap(com.leaf.lemonade.lua.EnumItemV("KeyCode", "Unknown", 0)))
                return t
            }
            var i: Instance? = top
            while (i != null && i.className != "CoreGui") {
                if (r.hasConn(i, "InputChanged")) r.fire(i, "InputChanged", wheel())
                i = i.parent
            }
            uisFire("InputChanged", wheel())
        }
        postInvalidate()
    }

    /** A finger held on a plugin's GUI moved: the plugin sees mouse movement (InputChanged, MouseMoved). */
    fun pluginPointerMove(h: Hit, x: Float, y: Float) {
        if (!mouseFor(h.inst)) return
        synchronized(studio.lock) {
            val mv = touchInput(x, y, 1, true, movement = true)
            h.rt?.fire(h.inst, "InputChanged", mv)
            pressExtras[h]?.forEach { e -> e.rt?.fire(e.inst, "InputChanged", touchInput(x, y, 1, true, movement = true)) }
            guiPoint(x, y).let { p -> h.rt?.fire(h.inst, "MouseMoved", LuaValue.valueOf(p.x.toDouble()), LuaValue.valueOf(p.y.toDouble())) }
            uisFire("InputChanged", touchInput(x, y, 1, true, movement = true))
        }
        postInvalidate()
    }

    /** Ends a press; fires the click if the finger is still on the button. */
    fun release(h: Hit, x: Float, y: Float, click: Boolean) {
        if (pressed === h.inst) pressed = null
        invalidate()
        when (h.kind) {
            CORE_MENU -> if (click) onCoreMenu?.invoke(x, y)
            WIDGET_CLOSE -> if (click) { synchronized(studio.lock) { h.inst.set("Enabled", false) }; postInvalidate() }
            TEXTBOX -> if (click) editTextBox(h)
            SINK -> {
                val r = h.rt ?: return
                val mouse = mouseFor(h.inst)
                synchronized(studio.lock) {
                    releaseExtras(h, x, y)
                    r.fire(h.inst, "InputEnded", touchInput(x, y, 2, mouse))
                    if (mouse) uisFire("InputEnded", touchInput(x, y, 2, true))
                    if (mouse) hover(h, x, y, false)
                }
            }
            BUTTON -> {
                val r = h.rt ?: return
                val mouse = mouseFor(h.inst)
                synchronized(studio.lock) {
                    releaseExtras(h, x, y)
                    guiPoint(x, y).let { p -> r.fire(h.inst, "MouseButton1Up", LuaValue.valueOf(p.x.toDouble()), LuaValue.valueOf(p.y.toDouble())) }
                    r.fire(h.inst, "InputEnded", touchInput(x, y, 2, mouse))
                    if (mouse) uisFire("InputEnded", touchInput(x, y, 2, true))
                    if (click) {
                        r.fire(h.inst, "MouseButton1Click")
                        r.fire(h.inst, "Activated", touchInput(x, y, 2), LuaValue.valueOf(1))
                    }
                    // The finger lifted: the "mouse" is no longer over the button.
                    if (mouse) hover(h, x, y, false)
                }
            }
        }
    }

    private var lastPointer: Vector3? = null

    /**
     * A touch at (x, y) px in the GUI units scripts see (like [setAbs]): plugin GUIs in edit mode
     * use their scaled units with no top bar, game GUIs dp below the top bar.
     */
    private fun guiPoint(x: Float, y: Float): Vector2 {
        val edit = studio.play == null
        val u = if (edit) d * studio.editGuiScale() else d
        val p = Vector2(x / u, y / u - if (edit) 0f else TOPBAR_INSET)
        if (edit) studio.lastEditPointer = p
        return p
    }

    private fun touchInput(x: Float, y: Float, state: Int, mouse: Boolean = false, movement: Boolean = false): LuaValue {
        val lt = com.leaf.lemonade.lua.LuaTypes
        val t = org.luaj.vm2.LuaTable()
        val type = when { movement -> com.leaf.lemonade.lua.EnumItemV("UserInputType", "MouseMovement", 4)
            mouse -> com.leaf.lemonade.lua.EnumItemV("UserInputType", "MouseButton1", 0)
            else -> com.leaf.lemonade.lua.EnumItemV("UserInputType", "Touch", 7) }
        t.set("UserInputType", lt.wrap(type))
        val stateName = when (state) { 0 -> "Begin"; 1 -> "Change"; else -> "End" }
        t.set("UserInputState", lt.wrap(com.leaf.lemonade.lua.EnumItemV("UserInputState", stateName, state)))
        val gp = guiPoint(x, y)
        val pos = Vector3(gp.x, gp.y, 0f)
        t.set("Position", lt.wrap(pos))
        val prev = lastPointer
        t.set("Delta", lt.wrap(if (state == 1 && prev != null) pos - prev else Vector3.ZERO))
        t.set("KeyCode", lt.wrap(com.leaf.lemonade.lua.EnumItemV("KeyCode", "Unknown", 0)))
        lastPointer = pos
        return t
    }

    private fun editTextBox(h: Hit) {
        val inst = h.inst
        val r = h.rt ?: return
        val e = Ui.input(context, synchronized(studio.lock) { inst["Text"] as? String ?: "" })
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(synchronized(studio.lock) { inst["PlaceholderText"] as? String }?.ifEmpty { null } ?: "Enter text")
            .setView(e).setPositiveButton("OK") { _, _ ->
                synchronized(studio.lock) {
                    inst.set("Text", e.text.toString())
                    r.fire(inst, "FocusLost", LuaValue.TRUE, LuaValue.NIL)
                }
            }.setNegativeButton("Cancel") { _, _ -> synchronized(studio.lock) { r.fire(inst, "FocusLost", LuaValue.FALSE, LuaValue.NIL) } }.show()
        synchronized(studio.lock) { r.fire(inst, "Focused") }
    }

    /** Edit mode: plugin widget controls handle their own taps. Returns true if consumed. */
    fun tap(x: Float, y: Float): Boolean {
        val h = buttonAt(x, y) ?: return false
        if (studio.play == null && h.rt === studio.editRuntime && h.kind != WIDGET_CLOSE && !isPluginUi(h.inst)) return false
        press(h, x, y)
        release(h, x, y, true)
        return true
    }

    /** For edit mode: which GUI object is under the point, for selection. */
    fun pick(x: Float, y: Float): Instance? {
        if (studio.play != null) return null
        val sg = studio.model.findService("StarterGui") ?: return null
        // Invisible containers aren't picked by tapping the view (select them in the Explorer).
        return picks.lastOrNull { (i, r) -> r.contains(x, y) && i.isDescendantOf(sg) && !seeThrough(i) }?.first
    }
}

/** Minimal Roblox rich text: <b>, <i>, <u>, <s>, <font color/size>, <br/>, entities. */
object RichText {
    private val cache = object : LinkedHashMap<String, CharSequence>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CharSequence>?) = size > 200
    }

    fun parse(src: String): CharSequence = synchronized(cache) { cache.getOrPut(src) { build(src) } }

    private fun parseColor(v: String): Int? {
        val s = v.trim()
        if (s.startsWith("#")) return s.removePrefix("#").toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        Regex("""rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""").find(s)?.let { m ->
            val (r, g, b) = m.destructured
            return (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
        return null
    }

    private fun build(src: String): CharSequence {
        val out = SpannableStringBuilder()
        val stack = ArrayList<Pair<String, Pair<Int, Any?>>>()
        var i = 0
        val tag = Regex("""<\s*(/?)\s*([a-zA-Z]+)([^>]*)>""")
        while (i < src.length) {
            val m = tag.find(src, i)
            val end = m?.range?.first ?: src.length
            if (end > i) out.append(decode(src.substring(i, end)))
            if (m == null) break
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            if (name == "br") out.append('\n')
            else if (!closing) {
                val span: Any? = when (name) {
                    "b" -> StyleSpan(Typeface.BOLD)
                    "i" -> StyleSpan(Typeface.ITALIC)
                    "u" -> UnderlineSpan()
                    "s" -> StrikethroughSpan()
                    "font" -> Regex("""color\s*=\s*["']([^"']+)["']""").find(attrs)?.let { parseColor(it.groupValues[1]) }?.let { ForegroundColorSpan(it) }
                    "uppercase", "uc" -> null
                    else -> null
                }
                stack.add(name to (out.length to span))
            } else {
                val idx = stack.indexOfLast { it.first == name }
                if (idx >= 0) {
                    val (_, info) = stack.removeAt(idx)
                    info.second?.let { out.setSpan(it, info.first, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
            }
            i = m.range.last + 1
        }
        for ((_, info) in stack) info.second?.let { out.setSpan(it, info.first, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return out
    }

    private fun decode(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
