package com.leaf.lemonade.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.leaf.lemonade.model.ClassDb

/**
 * Icons drawn in code (original artwork, so they can ship with the app). Drawn on a 24×24 grid and
 * scaled to the drawable's bounds.
 */
class VectorIcon(private val painter: IconPainter.() -> Unit) : Drawable() {
    private var alphaV = 255
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Icons are rasterized once per size/theme and drawn as bitmaps: redrawing dozens of vector
     * paths on every UI frame is slow on weak GPUs.
     */
    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width(); val h = b.height()
        if (w <= 0 || h <= 0) return
        val key = "${System.identityHashCode(painter)}:$w:$h:${Ui.TEXT}:${Ui.PANEL}:${Ui.DIM}"
        val bmp = synchronized(cache) { cache.get(key) } ?: run {
            val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val bc = Canvas(bm)
            val s = minOf(w, h) / 24f
            bc.translate((w - 24f * s) / 2f, (h - 24f * s) / 2f)
            bc.scale(s, s)
            IconPainter(bc).painter()
            synchronized(cache) { cache.put(key, bm) }
            bm
        }
        bmpPaint.alpha = alphaV
        bmpPaint.colorFilter = if (hasOverride) overrideCf else null
        c.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), bmpPaint)
    }

    companion object {
        private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
        }
    }
    override fun setAlpha(alpha: Int) { alphaV = alpha }
    /** An explicit filter replaces the icon theme's (used for icon theme previews). */
    private var overrideCf: ColorFilter? = null
    private var hasOverride = false
    override fun setColorFilter(cf: ColorFilter?) { overrideCf = cf; hasOverride = true; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 48
    override fun getIntrinsicHeight() = 48
}

class IconPainter(val c: Canvas) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** Foreground color for monochrome glyphs (follows the theme). */
    val fg: Int get() = Ui.TEXT

    fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, rad: Float = 0f) {
        p.style = Paint.Style.FILL; p.color = color; p.shader = null
        if (rad > 0f) c.drawRoundRect(l, t, r, b, rad, rad, p) else c.drawRect(l, t, r, b, p)
    }
    fun srect(l: Float, t: Float, r: Float, b: Float, color: Int, w: Float = 1.5f, rad: Float = 0f) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.shader = null
        if (rad > 0f) c.drawRoundRect(l, t, r, b, rad, rad, p) else c.drawRect(l, t, r, b, p)
    }
    fun box(l: Float, t: Float, r: Float, b: Float, fillC: Int, strokeC: Int, w: Float = 1.3f, rad: Float = 1.5f) {
        rect(l, t, r, b, fillC, rad); srect(l, t, r, b, strokeC, w, rad)
    }
    fun circle(cx: Float, cy: Float, r: Float, color: Int) { p.style = Paint.Style.FILL; p.color = color; p.shader = null; c.drawCircle(cx, cy, r, p) }
    fun ring(cx: Float, cy: Float, r: Float, color: Int, w: Float = 1.5f) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.shader = null; c.drawCircle(cx, cy, r, p)
    }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, w: Float = 1.6f) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.shader = null; c.drawLine(x1, y1, x2, y2, p)
    }
    private fun path(xy: FloatArray, close: Boolean): Path = Path().apply {
        moveTo(xy[0], xy[1]); var i = 2
        while (i + 1 < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
        if (close) close()
    }
    fun poly(color: Int, vararg xy: Float) { p.style = Paint.Style.FILL; p.color = color; p.shader = null; c.drawPath(path(xy, true), p) }
    fun spoly(color: Int, w: Float, vararg xy: Float) { p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.shader = null; c.drawPath(path(xy, true), p) }
    fun pline(color: Int, w: Float, vararg xy: Float) { p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.shader = null; c.drawPath(path(xy, false), p) }
    fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, color: Int, w: Float = 1.6f) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.shader = null; c.drawArc(l, t, r, b, start, sweep, false, p)
    }
    fun pie(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, color: Int) {
        p.style = Paint.Style.FILL; p.color = color; p.shader = null; c.drawArc(l, t, r, b, start, sweep, true, p)
    }
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true) {
        p.style = Paint.Style.FILL; p.color = color; p.shader = null; p.textSize = size; p.textAlign = Paint.Align.CENTER
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(s, x, y - (p.descent() + p.ascent()) / 2f, p)
    }
    fun gradientRect(l: Float, t: Float, r: Float, b: Float, c0: Int, c1: Int, rad: Float = 1.5f) {
        p.style = Paint.Style.FILL; p.shader = LinearGradient(l, t, r, t, c0, c1, Shader.TileMode.CLAMP)
        c.drawRoundRect(l, t, r, b, rad, rad, p); p.shader = null
    }

    // ------------------------------------------------------------ motifs

    fun cube(cx: Float, cy: Float, s: Float, top: Int = GRAY_T, left: Int = GRAY_L, right: Int = GRAY_R) {
        val k = s * 0.866f
        poly(top, cx, cy - s, cx + k, cy - s / 2f, cx, cy, cx - k, cy - s / 2f)
        poly(left, cx - k, cy - s / 2f, cx, cy, cx, cy + s, cx - k, cy + s / 2f)
        poly(right, cx, cy, cx + k, cy - s / 2f, cx + k, cy + s / 2f, cx, cy + s)
    }

    fun page(band: Int, paper: Int = PAPER) {
        poly(paper, 5f, 2f, 15f, 2f, 19f, 6f, 19f, 22f, 5f, 22f)
        poly(0xFFB9BEC6.toInt(), 15f, 2f, 19f, 6f, 15f, 6f)
        spoly(0xFF8D939C.toInt(), 1f, 5f, 2f, 15f, 2f, 19f, 6f, 19f, 22f, 5f, 22f)
        rect(5f, 2f, 7.5f, 22f, band)
    }

    fun pageLines(color: Int) {
        line(9.5f, 9f, 16.5f, 9f, color, 1.4f); line(9.5f, 12.5f, 15f, 12.5f, color, 1.4f)
        line(9.5f, 16f, 16.5f, 16f, color, 1.4f); line(9.5f, 19.2f, 13.5f, 19.2f, color, 1.4f)
    }

    fun person(cx: Float, cy: Float, s: Float, color: Int) {
        circle(cx, cy - s * 0.45f, s * 0.32f, color)
        val path = Path().apply {
            moveTo(cx - s * 0.62f, cy + s * 0.75f)
            cubicTo(cx - s * 0.62f, cy + s * 0.05f, cx + s * 0.62f, cy + s * 0.05f, cx + s * 0.62f, cy + s * 0.75f); close()
        }
        p.style = Paint.Style.FILL; p.color = color; p.shader = null; c.drawPath(path, p)
    }

    fun bolt(cx: Float, cy: Float, s: Float, color: Int) =
        poly(color, cx + 0.15f * s, cy - s, cx - 0.55f * s, cy + 0.12f * s, cx - 0.02f * s, cy + 0.12f * s,
            cx - 0.2f * s, cy + s, cx + 0.55f * s, cy - 0.15f * s, cx + 0.02f * s, cy - 0.15f * s)

    fun tag(color: Int, letter: String) {
        poly(color, 3f, 7f, 15f, 7f, 21f, 12f, 15f, 17f, 3f, 17f)
        circle(16.5f, 12f, 1.2f, 0xFFFFFFFF.toInt())
        text(letter, 8.8f, 12f, 7f, 0xFFFFFFFF.toInt())
    }

    fun cloud(cx: Float, cy: Float, s: Float, color: Int) {
        circle(cx - 0.45f * s, cy + 0.1f * s, 0.42f * s, color)
        circle(cx + 0.05f * s, cy - 0.2f * s, 0.55f * s, color)
        circle(cx + 0.55f * s, cy + 0.15f * s, 0.38f * s, color)
        rect(cx - 0.45f * s, cy + 0.1f * s, cx + 0.55f * s, cy + 0.52f * s, color)
    }

    fun speaker(color: Int) {
        poly(color, 3f, 9f, 7f, 9f, 12f, 4.5f, 12f, 19.5f, 7f, 15f, 3f, 15f)
        arc(10f, 7f, 18f, 17f, -50f, 100f, color, 1.7f)
        arc(9f, 3.5f, 22f, 20.5f, -50f, 100f, color, 1.7f)
    }

    fun gear(cx: Float, cy: Float, r: Float, color: Int, hole: Int) {
        for (i in 0 until 8) {
            c.save(); c.rotate(i * 45f, cx, cy)
            rect(cx - r * 0.2f, cy - r * 1.15f, cx + r * 0.2f, cy - r * 0.6f, color, r * 0.08f)
            c.restore()
        }
        circle(cx, cy, r * 0.82f, color)
        circle(cx, cy, r * 0.36f, hole)
    }

    fun bulb(cx: Float, cy: Float, color: Int) {
        circle(cx, cy - 2f, 6.2f, color)
        rect(cx - 3f, cy + 2.5f, cx + 3f, cy + 7f, 0xFF9AA0A8.toInt(), 1f)
        line(cx - 2.6f, cy + 4.7f, cx + 2.6f, cy + 4.7f, 0xFF6E747C.toInt(), 0.8f)
    }

    fun magnifier(color: Int) {
        ring(10f, 10f, 6f, color, 2.2f)
        line(14.5f, 14.5f, 20.5f, 20.5f, color, 3f)
    }

    fun monitor(screen: Int) {
        rect(2.5f, 4f, 21.5f, 17f, 0xFF5A6270.toInt(), 1.5f)
        rect(4f, 5.5f, 20f, 15.5f, screen, 0.5f)
        rect(10f, 17f, 14f, 20f, 0xFF5A6270.toInt())
        rect(7f, 19.5f, 17f, 21f, 0xFF5A6270.toInt(), 0.7f)
    }

    fun frame(fill: Int, stroke: Int) = box(3f, 5f, 21f, 19f, fill, stroke, 1.4f, 1.5f)

    fun arrowHead(x: Float, y: Float, angleDeg: Float, s: Float, color: Int) {
        c.save(); c.rotate(angleDeg, x, y)
        poly(color, x, y - s, x + s * 0.9f, y + s * 0.6f, x - s * 0.9f, y + s * 0.6f)
        c.restore()
    }

    fun chain(color: Int) {
        c.save(); c.rotate(-40f, 12f, 12f)
        srect(3.5f, 9f, 13.5f, 15f, color, 2f, 3f)
        srect(10.5f, 9f, 20.5f, 15f, color, 2f, 3f)
        c.restore()
    }

    fun picture(l: Float, t: Float, r: Float, b: Float) {
        rect(l, t, r, b, 0xFF8EC9F5.toInt(), 1f)
        poly(0xFF4CA65A.toInt(), l, b, l + (r - l) * 0.38f, t + (b - t) * 0.45f, l + (r - l) * 0.62f, b)
        poly(0xFF2F8A44.toInt(), l + (r - l) * 0.42f, b, l + (r - l) * 0.7f, t + (b - t) * 0.55f, r, b)
        circle(l + (r - l) * 0.75f, t + (b - t) * 0.3f, (r - l) * 0.1f, 0xFFFFD34D.toInt())
    }

    companion object {
        const val GRAY_T = 0xFFDCDCDE.toInt()
        const val GRAY_L = 0xFFA6A5A9.toInt()
        const val GRAY_R = 0xFF7D7C81.toInt()
        const val BLUE = 0xFF3B8BEB.toInt()
        const val DBLUE = 0xFF1F5FB0.toInt()
        const val LBLUE = 0xFF8EC9F5.toInt()
        const val GREEN = 0xFF4CB050.toInt()
        const val DGREEN = 0xFF2E8B57.toInt()
        const val YELLOW = 0xFFF2C230.toInt()
        const val ORANGE = 0xFFF08A24.toInt()
        const val RED = 0xFFE5484D.toInt()
        const val PURPLE = 0xFF9B6BE0.toInt()
        const val TEAL = 0xFF2BB3A6.toInt()
        const val PAPER = 0xFFF1F3F6.toInt()
        const val DARK = 0xFF4A4F57.toInt()
        const val BROWN = 0xFFB07A3E.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GUI = 0xFF3FA66B.toInt()
    }
}

private typealias D = IconPainter.() -> Unit

object BuiltinIcons {
    private const val W = IconPainter.WHITE
    private const val ORANGE_ICON = 0xFFE8791C.toInt()

    private val script: D = { page(IconPainter.BLUE); pageLines(0xFF5C8FD6.toInt()) }
    private val localScript: D = { page(IconPainter.TEAL); pageLines(0xFF7FAFC0.toInt()); person(15f, 16.5f, 6f, IconPainter.TEAL) }
    private val moduleScript: D = {
        page(IconPainter.PURPLE)
        pline(IconPainter.PURPLE, 1.6f, 12f, 8f, 10f, 10f, 10f, 12f, 9f, 13f, 10f, 14f, 10f, 16f, 12f, 18f)
        pline(IconPainter.PURPLE, 1.6f, 14.5f, 8f, 16.5f, 10f, 16.5f, 12f, 17.5f, 13f, 16.5f, 14f, 16.5f, 16f, 14.5f, 18f)
    }
    private val part: D = { cube(12f, 12f, 9f) }
    private val model: D = {
        cube(7.5f, 8.5f, 5f); cube(16.5f, 8.5f, 5f)
        cube(12f, 16.5f, 5f)
    }
    private val folder: D = {
        poly(0xFFD9A520.toInt(), 2f, 6f, 9f, 6f, 11f, 8f, 22f, 8f, 22f, 20f, 2f, 20f)
        rect(2f, 9.5f, 22f, 20f, IconPainter.YELLOW, 1f)
    }
    private val person: D = { person(12f, 13f, 10f, IconPainter.BLUE) }
    private val humanoid: D = { person(12f, 13f, 10f, IconPainter.GREEN) }
    private val gui: D = { rect(3f, 4f, 21f, 20f, 0xFF5A6270.toInt(), 1.5f); rect(4.5f, 8f, 19.5f, 18.5f, IconPainter.LBLUE, 0.5f); rect(4.5f, 5.3f, 8f, 6.8f, IconPainter.RED, 0.5f) }
    private val valueTag: (Int, String) -> D = { col, l -> { tag(col, l) } }
    private val constraint: D = { chain(IconPainter.ORANGE) }
    private val weld: D = { chain(0xFF8D939C.toInt()) }
    private val light: D = { bulb(12f, 11f, IconPainter.YELLOW) }

    private val instanceIcons: Map<String, D> = HashMap<String, D>().apply {
        put("Part", part)
        put("BasePart", part)
        put("FormFactorPart", part)
        put("Platform", part)
        put("WedgePart", { poly(IconPainter.GRAY_L, 3f, 20f, 21f, 20f, 21f, 5f); poly(IconPainter.GRAY_T, 3f, 20f, 21f, 5f, 21f, 8.5f, 6.5f, 20f) })
        put("CornerWedgePart", { poly(IconPainter.GRAY_L, 3f, 20f, 12f, 3f, 12f, 20f); poly(IconPainter.GRAY_R, 12f, 3f, 21f, 20f, 12f, 20f) })
        put("TrussPart", {
            val g = 0xFF8D939C.toInt()
            srect(5f, 2f, 19f, 22f, g, 2f)
            for (i in 0 until 3) { val y = 2f + i * 6.66f; line(5f, y, 19f, y + 6.66f, g, 1.5f); line(19f, y, 5f, y + 6.66f, g, 1.5f) }
        })
        put("SpawnLocation", {
            poly(IconPainter.GRAY_T, 12f, 10f, 22f, 14.5f, 12f, 19f, 2f, 14.5f)
            poly(IconPainter.GRAY_L, 2f, 14.5f, 12f, 19f, 12f, 21.5f, 2f, 17f)
            poly(IconPainter.GRAY_R, 12f, 19f, 22f, 14.5f, 22f, 17f, 12f, 21.5f)
            pline(IconPainter.GREEN, 2.4f, 8f, 8f, 12f, 4f, 16f, 8f); line(12f, 4f, 12f, 12f, IconPainter.GREEN, 2.4f)
        })
        put("Seat", {
            rect(5f, 3f, 9f, 15f, IconPainter.BROWN, 1f); rect(5f, 12f, 19f, 15.5f, IconPainter.BROWN, 1f)
            line(6.5f, 15f, 6.5f, 21f, 0xFF7A5228.toInt(), 2f); line(17.5f, 15f, 17.5f, 21f, 0xFF7A5228.toInt(), 2f)
        })
        put("MeshPart", {
            poly(0xFF7FB6F0.toInt(), 12f, 3f, 21f, 19f, 3f, 19f)
            spoly(IconPainter.DBLUE, 1.3f, 12f, 3f, 21f, 19f, 3f, 19f)
            pline(IconPainter.DBLUE, 1.1f, 7.5f, 11f, 12f, 19f, 16.5f, 11f, 7.5f, 11f)
        })
        put("UnionOperation", { rect(3f, 9f, 14f, 20f, 0xFF7CC97F.toInt(), 1.5f); circle(15f, 9.5f, 6.5f, IconPainter.GREEN) })
        put("PartOperation", { rect(3f, 9f, 14f, 20f, 0xFF7CC97F.toInt(), 1.5f); circle(15f, 9.5f, 6.5f, IconPainter.GREEN) })
        put("IntersectOperation", { srect(3f, 9f, 14f, 20f, IconPainter.GREEN, 1.4f, 1.5f); ring(15f, 9.5f, 6.5f, IconPainter.GREEN, 1.4f); pie(8.5f, 3f, 21.5f, 16f, 90f, 90f, IconPainter.GREEN) })
        put("NegateOperation", { srect(3f, 9f, 14f, 20f, IconPainter.RED, 1.4f, 1.5f); circle(15f, 9.5f, 6.5f, 0xFFF08A8D.toInt()) })
        put("Terrain", { poly(0xFF8A6A45.toInt(), 1f, 20f, 9f, 6f, 15f, 15f, 18f, 10f, 23f, 20f); poly(IconPainter.GREEN, 1f, 20f, 5f, 13f, 9f, 16f, 13f, 13f, 18f, 17f, 23f, 20f) })
        put("Model", model)
        put("WorldModel", model)
        put("Status", model)
        put("Folder", folder)
        put("StarterPlayerScripts", folder)
        put("StarterCharacterScripts", folder)
        put("Configuration", { gear(12f, 12f, 8f, 0xFF8D939C.toInt(), Ui.PANEL) })
        put("Workspace", {
            circle(12f, 12f, 9.5f, IconPainter.BLUE)
            poly(IconPainter.GREEN, 7f, 6f, 11f, 5f, 12.5f, 8f, 10f, 11f, 11f, 14f, 8f, 16f, 6f, 12f, 5f, 9f)
            poly(IconPainter.GREEN, 15f, 11f, 19f, 10f, 20f, 14f, 17f, 18f, 15f, 15f)
            ring(12f, 12f, 9.5f, IconPainter.DBLUE, 1.2f)
        })
        put("Camera", {
            rect(3f, 7f, 21f, 19f, IconPainter.DARK, 2f); rect(8f, 4.5f, 13f, 7.5f, IconPainter.DARK, 1f)
            circle(12f, 13f, 4.3f, 0xFF9FC6F0.toInt()); ring(12f, 13f, 4.3f, 0xFFD0D4DA.toInt(), 1.3f)
        })
        put("Players", person)
        put("Player", person)
        put("StarterPlayer", { person(10f, 13f, 9f, IconPainter.BLUE); poly(IconPainter.GREEN, 16f, 13f, 22f, 16.5f, 16f, 20f) })
        put("Lighting", light)
        put("PointLight", { circle(12f, 11f, 9f, 0x33F2C230); bulb(12f, 11f, IconPainter.YELLOW) })
        put("SpotLight", { poly(0x66F2C230, 9f, 6f, 15f, 6f, 21f, 22f, 3f, 22f); rect(8f, 2f, 16f, 7f, 0xFF8D939C.toInt(), 1f) })
        put("SurfaceLight", { rect(4f, 3f, 20f, 7f, 0xFF8D939C.toInt(), 1f); for (x in listOf(7f, 12f, 17f)) line(x, 9f, x, 20f, IconPainter.YELLOW, 1.8f) })
        put("MaterialService", { circle(12f, 12f, 9f, 0xFFB0B0B5.toInt()); pie(3f, 3f, 21f, 21f, -45f, 180f, 0xFF7D7C81.toInt()); circle(9f, 9f, 2f, 0x66FFFFFF) })
        put("ReplicatedFirst", { rect(3f, 8f, 17f, 20f, IconPainter.TEAL, 1.5f); rect(3f, 8f, 17f, 11f, 0xFF1F8C82.toInt(), 1f); pline(IconPainter.BLUE, 2.2f, 14f, 3.5f, 21f, 3.5f); arrowHead(21f, 3.5f, 90f, 2.2f, IconPainter.BLUE) })
        put("ReplicatedStorage", { rect(3f, 7f, 21f, 20f, IconPainter.TEAL, 1.5f); rect(2f, 5f, 22f, 9f, 0xFF1F8C82.toInt(), 1f); rect(9f, 11f, 15f, 13f, W, 0.8f) })
        put("ServerStorage", {
            for (i in 0 until 3) { val y = 3.5f + i * 6.2f; rect(3f, y, 21f, y + 5f, IconPainter.DARK, 1.2f); circle(18f, y + 2.5f, 1f, IconPainter.GREEN); line(6f, y + 2.5f, 12f, y + 2.5f, 0xFF8D939C.toInt(), 1.2f) }
        })
        put("ServerScriptService", {
            for (i in 0 until 2) { val y = 3.5f + i * 6.2f; rect(2f, y, 15f, y + 5f, IconPainter.DARK, 1.2f); circle(12.5f, y + 2.5f, 1f, IconPainter.GREEN) }
            rect(11f, 10f, 22f, 22f, IconPainter.PAPER, 1f); rect(11f, 10f, 13f, 22f, IconPainter.BLUE); line(15f, 14f, 20f, 14f, IconPainter.BLUE, 1.2f); line(15f, 17.5f, 19f, 17.5f, IconPainter.BLUE, 1.2f)
        })
        put("StarterGui", gui)
        put("PlayerGui", gui)
        put("CoreGui", gui)
        put("StarterPack", {
            rect(5f, 7f, 19f, 21f, IconPainter.BROWN, 3f); arc(8f, 2.5f, 16f, 10f, 180f, 180f, 0xFF7A5228.toInt(), 1.8f)
            rect(5f, 11f, 19f, 15f, 0xFF8E5F2C.toInt(), 1f); rect(10.5f, 12f, 13.5f, 14f, IconPainter.YELLOW, 0.5f)
        })
        put("Backpack", {
            rect(5f, 7f, 19f, 21f, IconPainter.BROWN, 3f); arc(8f, 2.5f, 16f, 10f, 180f, 180f, 0xFF7A5228.toInt(), 1.8f)
            rect(5f, 11f, 19f, 15f, 0xFF8E5F2C.toInt(), 1f)
        })
        put("PlayerScripts", localScript)
        put("Teams", { line(5f, 3f, 5f, 21f, IconPainter.DARK, 1.8f); poly(IconPainter.RED, 5.5f, 4f, 19f, 7f, 5.5f, 11f); poly(IconPainter.BLUE, 5.5f, 11f, 19f, 14f, 5.5f, 17f) })
        put("Team", { line(5f, 3f, 5f, 21f, IconPainter.DARK, 1.8f); poly(IconPainter.BLUE, 5.5f, 4f, 20f, 8.5f, 5.5f, 13f) })
        put("SoundService", { speaker(IconPainter.BLUE) })
        put("Sound", { speaker(IconPainter.BLUE) })
        put("SoundGroup", { speaker(IconPainter.TEAL) })
        put("Chat", { rect(2.5f, 4f, 21.5f, 16f, IconPainter.BLUE, 4f); poly(IconPainter.BLUE, 6f, 15f, 5f, 21f, 11f, 15.5f); for (x in listOf(8f, 12f, 16f)) circle(x, 10f, 1.4f, W) })
        put("TextChatService", { rect(2.5f, 4f, 21.5f, 16f, IconPainter.BLUE, 4f); poly(IconPainter.BLUE, 6f, 15f, 5f, 21f, 11f, 15.5f); for (x in listOf(8f, 12f, 16f)) circle(x, 10f, 1.4f, W) })
        put("LocalizationService", { circle(12f, 12f, 9.5f, IconPainter.TEAL); text("A", 12f, 12f, 11f, W) })
        put("TestService", { circle(12f, 12f, 9.5f, IconPainter.GREEN); pline(W, 2.4f, 7.5f, 12.5f, 10.8f, 15.8f, 16.8f, 8.5f) })
        put("Script", script)
        put("LocalScript", localScript)
        put("ModuleScript", moduleScript)
        put("LuaSourceContainer", script)
        put("BaseScript", script)
        put("ScreenGui", { monitor(IconPainter.LBLUE) })
        put("BillboardGui", { rect(3f, 3f, 21f, 13f, IconPainter.GUI, 1.5f); line(12f, 13f, 12f, 21f, IconPainter.DARK, 2f); line(6f, 7f, 18f, 7f, W, 1.3f); line(6f, 10f, 14f, 10f, W, 1.3f) })
        put("SurfaceGui", { poly(IconPainter.GUI, 4f, 7f, 20f, 3f, 20f, 17f, 4f, 21f); line(7f, 10f, 17f, 7.5f, W, 1.3f); line(7f, 13.5f, 14f, 12f, W, 1.3f) })
        put("Frame", { frame(0xFFB8E2C8.toInt(), IconPainter.GUI) })
        put("TextLabel", { frame(0xFFB8E2C8.toInt(), IconPainter.GUI); text("T", 12f, 12f, 11f, IconPainter.DGREEN) })
        put("TextButton", { rect(2.5f, 6f, 21.5f, 18f, IconPainter.GUI, 4f); text("T", 12f, 12f, 10f, W) })
        put("TextBox", { frame(0xFFFFFFFF.toInt(), IconPainter.GUI); line(12f, 8f, 12f, 16f, IconPainter.DARK, 1.5f); line(10f, 8f, 14f, 8f, IconPainter.DARK, 1.2f); line(10f, 16f, 14f, 16f, IconPainter.DARK, 1.2f) })
        put("ImageLabel", { srect(3f, 5f, 21f, 19f, IconPainter.GUI, 1.4f, 1.5f); picture(4.5f, 6.5f, 19.5f, 17.5f) })
        put("ImageButton", { rect(2.5f, 4.5f, 21.5f, 19.5f, IconPainter.GUI, 4f); picture(5f, 7f, 19f, 17f) })
        put("ScrollingFrame", { frame(0xFFB8E2C8.toInt(), IconPainter.GUI); rect(17f, 7f, 19.5f, 12f, IconPainter.DGREEN, 1f) })
        put("ViewportFrame", { frame(0xFFB8E2C8.toInt(), IconPainter.GUI); cube(12f, 11.5f, 5f) })
        put("CanvasGroup", { box(7f, 3f, 21f, 15f, 0xFFB8E2C8.toInt(), IconPainter.GUI); box(3f, 9f, 17f, 21f, 0xFFD6EFDF.toInt(), IconPainter.GUI) })
        put("VideoFrame", { frame(IconPainter.DARK, IconPainter.GUI); poly(W, 10f, 8.5f, 16f, 12f, 10f, 15.5f) })
        put("UICorner", { pline(IconPainter.GUI, 2.6f, 5f, 20f, 5f, 12f); arc(5f, 5f, 19f, 19f, 180f, 90f, IconPainter.GUI, 2.6f); line(12f, 5f, 20f, 5f, IconPainter.GUI, 2.6f) })
        put("UIStroke", { srect(4f, 6f, 20f, 18f, IconPainter.GUI, 3f, 2f) })
        put("UIGradient", { gradientRect(3f, 6f, 21f, 18f, 0xFF1F5FB0.toInt(), 0xFFE5484D.toInt()) })
        put("UIPadding", { srect(3f, 4f, 21f, 20f, IconPainter.GUI, 1.4f, 1f); rect(7f, 8f, 17f, 16f, 0xFFB8E2C8.toInt(), 1f) })
        put("UIListLayout", { for (i in 0 until 3) rect(4f, 4.5f + i * 5.5f, 20f, 8.5f + i * 5.5f, IconPainter.GUI, 1f) })
        put("UIGridLayout", { for (i in 0 until 2) for (j in 0 until 2) rect(4f + i * 8.5f, 4f + j * 8.5f, 11.5f + i * 8.5f, 11.5f + j * 8.5f, IconPainter.GUI, 1f) })
        put("UITableLayout", { srect(3f, 4f, 21f, 20f, IconPainter.GUI, 1.4f); line(3f, 9.5f, 21f, 9.5f, IconPainter.GUI, 1.2f); line(3f, 15f, 21f, 15f, IconPainter.GUI, 1.2f); line(10f, 4f, 10f, 20f, IconPainter.GUI, 1.2f) })
        put("UIPageLayout", { box(7f, 3f, 20f, 17f, 0xFFD6EFDF.toInt(), IconPainter.GUI); box(4f, 7f, 17f, 21f, 0xFFB8E2C8.toInt(), IconPainter.GUI) })
        put("UIScale", { srect(3f, 3f, 21f, 21f, IconPainter.GUI, 1.4f, 1f); line(7f, 17f, 17f, 7f, IconPainter.DGREEN, 2f); arrowHead(17f, 7f, 45f, 2.5f, IconPainter.DGREEN); arrowHead(7f, 17f, 225f, 2.5f, IconPainter.DGREEN) })
        put("UIAspectRatioConstraint", { srect(3f, 6f, 21f, 18f, IconPainter.GUI, 1.6f, 1f); text("16:9", 12f, 12f, 6.5f, IconPainter.DGREEN) })
        put("UISizeConstraint", { srect(3f, 6f, 21f, 18f, IconPainter.GUI, 1.6f, 1f); line(6f, 12f, 18f, 12f, IconPainter.DGREEN, 1.6f); arrowHead(6f, 12f, 270f, 2.3f, IconPainter.DGREEN); arrowHead(18f, 12f, 90f, 2.3f, IconPainter.DGREEN) })
        put("UITextSizeConstraint", { srect(3f, 6f, 21f, 18f, IconPainter.GUI, 1.6f, 1f); text("Tt", 12f, 12f, 8f, IconPainter.DGREEN) })
        put("Decal", { poly(0xFFB0B0B5.toInt(), 3f, 5f, 21f, 3f, 21f, 21f, 3f, 19f); picture(5f, 6f, 19f, 18f) })
        put("Texture", { for (i in 0 until 3) for (j in 0 until 3) rect(3f + i * 6f, 3f + j * 6f, 9f + i * 6f, 9f + j * 6f, if ((i + j) % 2 == 0) IconPainter.DARK else IconPainter.GRAY_T) })
        put("SpecialMesh", { spoly(IconPainter.DBLUE, 1.4f, 12f, 3f, 21f, 19f, 3f, 19f); pline(IconPainter.DBLUE, 1.1f, 7.5f, 11f, 12f, 19f, 16.5f, 11f, 7.5f, 11f) })
        put("BlockMesh", { spoly(IconPainter.DBLUE, 1.4f, 12f, 3f, 20f, 7.5f, 20f, 16.5f, 12f, 21f, 4f, 16.5f, 4f, 7.5f); pline(IconPainter.DBLUE, 1.1f, 4f, 7.5f, 12f, 12f, 20f, 7.5f); line(12f, 12f, 12f, 21f, IconPainter.DBLUE, 1.1f) })
        put("CylinderMesh", { arc(5f, 3f, 19f, 9f, 0f, 360f, IconPainter.DBLUE, 1.4f); line(5f, 6f, 5f, 18f, IconPainter.DBLUE, 1.4f); line(19f, 6f, 19f, 18f, IconPainter.DBLUE, 1.4f); arc(5f, 15f, 19f, 21f, 0f, 180f, IconPainter.DBLUE, 1.4f) })
        put("Humanoid", humanoid)
        put("HumanoidDescription", humanoid)
        put("Animator", { circle(14f, 4.5f, 2.3f, IconPainter.PURPLE); pline(IconPainter.PURPLE, 2.2f, 13f, 8f, 11f, 14f, 6f, 18f); pline(IconPainter.PURPLE, 2.2f, 11f, 14f, 15f, 17f, 15f, 22f); pline(IconPainter.PURPLE, 2.2f, 7f, 10f, 12.5f, 8f, 18f, 11f) })
        put("AnimationController", { circle(14f, 4.5f, 2.3f, IconPainter.PURPLE); pline(IconPainter.PURPLE, 2.2f, 13f, 8f, 11f, 14f, 6f, 18f); pline(IconPainter.PURPLE, 2.2f, 11f, 14f, 15f, 17f, 15f, 22f) })
        put("Animation", { rect(3f, 5f, 21f, 19f, IconPainter.PURPLE, 1.5f); for (x in listOf(5f, 9f, 13f, 17f)) { rect(x, 6f, x + 2f, 8f, W, 0.4f); rect(x, 16f, x + 2f, 18f, W, 0.4f) } })
        put("KeyframeSequence", { rect(3f, 5f, 21f, 19f, IconPainter.PURPLE, 1.5f); for (x in listOf(7f, 12f, 17f)) poly(W, x, 9f, x + 2.5f, 12f, x, 15f, x - 2.5f, 12f) })
        put("Tool", { rect(4f, 3f, 16f, 9f, 0xFF8D939C.toInt(), 1.2f); line(10f, 9f, 10f, 21f, IconPainter.BROWN, 3f) })
        put("HopperBin", { rect(4f, 3f, 16f, 9f, 0xFF8D939C.toInt(), 1.2f); line(10f, 9f, 10f, 21f, IconPainter.BROWN, 3f) })
        put("Accessory", { rect(2f, 15f, 22f, 18f, IconPainter.DARK, 1.5f); rect(6f, 5f, 18f, 16f, IconPainter.DARK, 1.5f); rect(6f, 12f, 18f, 14f, IconPainter.RED) })
        put("Hat", { rect(2f, 15f, 22f, 18f, IconPainter.DARK, 1.5f); rect(6f, 5f, 18f, 16f, IconPainter.DARK, 1.5f) })
        put("Shirt", { poly(IconPainter.BLUE, 8f, 3f, 16f, 3f, 22f, 8f, 19f, 11f, 17f, 9f, 17f, 21f, 7f, 21f, 7f, 9f, 5f, 11f, 2f, 8f) })
        put("ShirtGraphic", { poly(IconPainter.TEAL, 8f, 3f, 16f, 3f, 22f, 8f, 19f, 11f, 17f, 9f, 17f, 21f, 7f, 21f, 7f, 9f, 5f, 11f, 2f, 8f); circle(12f, 12f, 2.5f, W) })
        put("Pants", { poly(0xFF4A5A80.toInt(), 6f, 3f, 18f, 3f, 20f, 21f, 14f, 21f, 12f, 9f, 10f, 21f, 4f, 21f) })
        put("BodyColors", { circle(12f, 12f, 9.5f, 0xFFE8D5B0.toInt()); circle(8f, 9f, 2f, IconPainter.RED); circle(13f, 7f, 2f, IconPainter.YELLOW); circle(17f, 11f, 2f, IconPainter.BLUE); circle(9f, 15f, 2f, IconPainter.GREEN) })
        put("Attachment", { ring(12f, 12f, 7f, IconPainter.GREEN, 2.2f); circle(12f, 12f, 2.6f, IconPainter.GREEN) })
        put("Bone", { line(6f, 18f, 18f, 6f, 0xFFE8D5B0.toInt(), 3f); circle(6f, 18f, 2.5f, 0xFFE8D5B0.toInt()); circle(18f, 6f, 2.5f, 0xFFE8D5B0.toInt()) })
        put("Weld", weld)
        put("ManualWeld", weld)
        put("Snap", weld)
        put("Glue", weld)
        put("WeldConstraint", weld)
        put("NoCollisionConstraint", { chain(IconPainter.RED) })
        put("Motor", { circle(7f, 12f, 4f, 0xFF8D939C.toInt()); circle(17f, 12f, 4f, 0xFF8D939C.toInt()); line(7f, 12f, 17f, 12f, IconPainter.DARK, 2.5f); circle(17f, 12f, 1.5f, IconPainter.YELLOW) })
        put("Motor6D", { circle(7f, 12f, 4f, 0xFF8D939C.toInt()); circle(17f, 12f, 4f, 0xFF8D939C.toInt()); line(7f, 12f, 17f, 12f, IconPainter.DARK, 2.5f); circle(17f, 12f, 1.5f, IconPainter.YELLOW) })
        put("Constraint", constraint)
        put("ClickDetector", { poly(IconPainter.DARK, 7f, 4f, 7f, 19f, 10.5f, 15.5f, 13f, 21f, 15.5f, 20f, 13f, 14.5f, 18f, 14.5f); line(13f, 3f, 14f, 6f, IconPainter.ORANGE, 1.6f); line(17f, 6f, 15f, 8f, IconPainter.ORANGE, 1.6f); line(18f, 10.5f, 15.5f, 10.5f, IconPainter.ORANGE, 1.6f) })
        put("ProximityPrompt", { rect(3f, 3f, 21f, 21f, IconPainter.DARK, 4f); srect(5.5f, 5.5f, 18.5f, 18.5f, W, 1.4f, 3f); text("E", 12f, 12f, 9f, W) })
        put("ParticleEmitter", { for ((x, y, r) in listOf(Triple(6f, 7f, 2f), Triple(14f, 5f, 1.4f), Triple(18f, 12f, 2.4f), Triple(9f, 15f, 2.6f), Triple(15f, 19f, 1.6f))) circle(x, y, r, IconPainter.ORANGE) })
        put("Fire", { poly(IconPainter.ORANGE, 12f, 2f, 18f, 11f, 18f, 16f, 15f, 21f, 9f, 21f, 6f, 16f, 6f, 11f, 9f, 13f); poly(IconPainter.YELLOW, 12f, 9f, 15f, 15f, 14f, 19f, 10f, 19f, 9f, 15f) })
        put("Smoke", { cloud(12f, 12f, 8f, 0xFF9AA0A8.toInt()) })
        put("Sparkles", { poly(IconPainter.YELLOW, 12f, 2f, 14f, 10f, 22f, 12f, 14f, 14f, 12f, 22f, 10f, 14f, 2f, 12f, 10f, 10f) })
        put("Trail", { arc(2f, 6f, 14f, 18f, 180f, 180f, IconPainter.PURPLE, 2.4f); arc(12f, 6f, 22f, 18f, 0f, 180f, IconPainter.PURPLE, 2.4f) })
        put("Beam", { circle(4f, 18f, 2.5f, IconPainter.TEAL); circle(20f, 6f, 2.5f, IconPainter.TEAL); pline(0xFF7FDCD3.toInt(), 3f, 4f, 18f, 10f, 9f, 14f, 15f, 20f, 6f) })
        put("Explosion", { poly(IconPainter.ORANGE, 12f, 1f, 14.5f, 8f, 22f, 6f, 16.5f, 12f, 22f, 18f, 14.5f, 16f, 12f, 23f, 9.5f, 16f, 2f, 18f, 7.5f, 12f, 2f, 6f, 9.5f, 8f); circle(12f, 12f, 3.5f, IconPainter.YELLOW) })
        put("Highlight", { rect(5f, 5f, 19f, 19f, 0x55E5484D, 2f); srect(5f, 5f, 19f, 19f, IconPainter.RED, 2f, 2f) })
        put("ForceField", { spoly(IconPainter.BLUE, 2f, 12f, 2f, 20.5f, 7f, 20.5f, 17f, 12f, 22f, 3.5f, 17f, 3.5f, 7f); poly(0x443B8BEB, 12f, 2f, 20.5f, 7f, 20.5f, 17f, 12f, 22f, 3.5f, 17f, 3.5f, 7f) })
        put("SelectionBox", { srect(4f, 4f, 20f, 20f, IconPainter.BLUE, 1.8f) })
        put("Sky", { circle(16f, 8f, 4.5f, IconPainter.YELLOW); cloud(10f, 14f, 7f, W); cloud(10f, 14f, 6f, 0xFFDCE7F5.toInt()) })
        put("Clouds", { cloud(12f, 13f, 8f, 0xFFB8C7DA.toInt()) })
        put("Atmosphere", { arc(2f, 8f, 22f, 28f, 180f, 180f, IconPainter.LBLUE, 2.4f); arc(5f, 11f, 19f, 25f, 180f, 180f, IconPainter.BLUE, 2.4f); arc(8f, 14f, 16f, 22f, 180f, 180f, IconPainter.DBLUE, 2.4f) })
        put("BloomEffect", { circle(12f, 12f, 8f, 0x55FFD34D); circle(12f, 12f, 4.5f, IconPainter.YELLOW) })
        put("BlurEffect", { circle(12f, 12f, 8f, 0x443B8BEB); circle(12f, 12f, 5f, 0x883B8BEB.toInt()) })
        put("ColorCorrectionEffect", { pie(3f, 3f, 21f, 21f, 0f, 120f, IconPainter.RED); pie(3f, 3f, 21f, 21f, 120f, 120f, IconPainter.GREEN); pie(3f, 3f, 21f, 21f, 240f, 120f, IconPainter.BLUE) })
        put("SunRaysEffect", { circle(12f, 12f, 4f, IconPainter.YELLOW); for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); line(12f + 6f * Math.cos(a).toFloat(), 12f + 6f * Math.sin(a).toFloat(), 12f + 9.5f * Math.cos(a).toFloat(), 12f + 9.5f * Math.sin(a).toFloat(), IconPainter.YELLOW, 1.6f) } })
        put("DepthOfFieldEffect", { ring(12f, 12f, 8f, IconPainter.DARK, 2f); ring(12f, 12f, 4f, IconPainter.BLUE, 2f) })
        put("StringValue", valueTag(IconPainter.PURPLE, "S"))
        put("IntValue", valueTag(IconPainter.PURPLE, "#"))
        put("NumberValue", valueTag(IconPainter.PURPLE, "#"))
        put("BoolValue", valueTag(IconPainter.PURPLE, "B"))
        put("ObjectValue", valueTag(IconPainter.PURPLE, "O"))
        put("Vector3Value", valueTag(IconPainter.PURPLE, "V"))
        put("CFrameValue", valueTag(IconPainter.PURPLE, "C"))
        put("Color3Value", valueTag(IconPainter.RED, "C"))
        put("BrickColorValue", valueTag(IconPainter.RED, "B"))
        put("RayValue", valueTag(IconPainter.PURPLE, "R"))
        put("ValueBase", valueTag(IconPainter.PURPLE, "V"))
        put("RemoteEvent", { rect(3f, 3f, 21f, 21f, IconPainter.ORANGE, 4f); bolt(12f, 12f, 7f, W) })
        put("UnreliableRemoteEvent", { rect(3f, 3f, 21f, 21f, 0xFFC97A2A.toInt(), 4f); bolt(12f, 12f, 7f, W) })
        put("RemoteFunction", { rect(3f, 3f, 21f, 21f, IconPainter.PURPLE, 4f); text("f", 12f, 12f, 13f, W) })
        put("BindableEvent", { rect(3f, 3f, 21f, 21f, IconPainter.GREEN, 4f); bolt(12f, 12f, 7f, W) })
        put("BindableFunction", { rect(3f, 3f, 21f, 21f, IconPainter.GREEN, 4f); text("f", 12f, 12f, 13f, W) })
        put("Plugin", { rect(4f, 7f, 18f, 21f, IconPainter.BLUE, 2f); circle(11f, 6.5f, 3f, IconPainter.BLUE); circle(18.5f, 14f, 3f, IconPainter.BLUE) })
        put("Dialog", { rect(2f, 3f, 15f, 12f, IconPainter.BLUE, 3f); rect(9f, 10f, 22f, 19f, IconPainter.TEAL, 3f) })
        put("DialogChoice", { rect(4f, 6f, 20f, 18f, IconPainter.TEAL, 3f) })
        put("Selection", { poly(IconPainter.DARK, 7f, 4f, 7f, 19f, 10.5f, 15.5f, 13f, 21f, 15.5f, 20f, 13f, 14.5f, 18f, 14.5f) })
        put("DataModel", { circle(12f, 12f, 9.5f, IconPainter.BLUE); ring(12f, 12f, 9.5f, IconPainter.DBLUE, 1.2f) })
        put("Message", { rect(3f, 6f, 21f, 18f, IconPainter.DARK, 2f) })
        put("Hint", { rect(3f, 9f, 21f, 15f, IconPainter.DARK, 1f) })
        put("VehicleSeat", { rect(5f, 3f, 9f, 15f, IconPainter.DARK, 1f); rect(5f, 12f, 19f, 15.5f, IconPainter.DARK, 1f); circle(7f, 19f, 2.5f, IconPainter.DARK); circle(17f, 19f, 2.5f, IconPainter.DARK) })
    }

    /** Icon for an instance class, walking up the hierarchy; null if there's no drawn icon. */
    fun instance(cls: String): Drawable? {
        var c: String? = cls
        var guard = 0
        while (c != null && guard++ < 12) {
            instanceIcons[c]?.let { return VectorIcon(it) }
            if (c == "Instance") break
            if (c.endsWith("Constraint") && ClassDb.isA(c, "Constraint")) return VectorIcon(constraint)
            c = ClassDb.superclass(c)
        }
        return null
    }

    private val generalIcons: Map<String, D> = HashMap<String, D>().apply {
        put("Select", { poly(fg, 6f, 2.5f, 6f, 19.5f, 10f, 15.5f, 13f, 22f, 16f, 20.8f, 13f, 14.5f, 18.5f, 14.5f); spoly(Ui.PANEL, 1f, 6f, 2.5f, 6f, 19.5f, 10f, 15.5f, 13f, 22f, 16f, 20.8f, 13f, 14.5f, 18.5f, 14.5f) })
        put("Move", {
            line(12f, 4f, 12f, 20f, fg, 1.8f); line(4f, 12f, 20f, 12f, fg, 1.8f)
            arrowHead(12f, 3f, 0f, 3.2f, fg); arrowHead(12f, 21f, 180f, 3.2f, fg)
            arrowHead(3f, 12f, 270f, 3.2f, fg); arrowHead(21f, 12f, 90f, 3.2f, fg)
            circle(12f, 12f, 2.2f, fg)
        })
        put("Scale", { srect(3.5f, 9.5f, 14.5f, 20.5f, fg, 1.8f, 1.5f); line(9.5f, 14.5f, 19.5f, 4.5f, fg, 1.8f); arrowHead(20.5f, 3.5f, 45f, 3.2f, fg) })
        put("Rotate", { arc(4f, 4f, 20f, 20f, 200f, 280f, fg, 1.9f); arrowHead(5f, 9.5f, 200f, 3.2f, fg); circle(12f, 12f, 2.4f, fg) })
        put("MezPlay", { poly(0xFF3966E9.toInt(), 6f, 3.5f, 20f, 12f, 6f, 20.5f) })
        put("MezStop", { rect(5f, 5f, 19f, 19f, 0xFF8E1B2A.toInt(), 2f) })
        put("MezStopOn", { rect(5f, 5f, 19f, 19f, 0xFFE5484D.toInt(), 2f) })
        put("MezChevron", { pline(fg, 2f, 6f, 9f, 12f, 15f, 18f, 9f) })
        put("AvatarGame", { person(10f, 11f, 10f, ORANGE_ICON); gear(18f, 18f, 4.2f, fg, Ui.PANEL) })
        put("AvatarCharacter", { line(5f, 5f, 13f, 13f, ORANGE_ICON, 3f); circle(5f, 5f, 3f, ORANGE_ICON); circle(13f, 13f, 3f, ORANGE_ICON); line(13f, 13f, 20f, 20f, ORANGE_ICON, 3f); circle(20f, 20f, 2.2f, ORANGE_ICON) })
        put("AvatarSetup", { person(10f, 11f, 10f, ORANGE_ICON); line(14f, 20f, 21f, 13f, fg, 2.2f); circle(21f, 13f, 2.2f, fg) })
        put("AvatarAnimation", { pline(fg, 2f, 11f, 6f, 5f, 12f, 11f, 18f); pline(fg, 2f, 16f, 6f, 10f, 12f, 16f, 18f); spoly(ORANGE_ICON, 2.2f, 16f, 6f, 22f, 12f, 16f, 18f, 12f, 12f) })
        put("AvatarAccessory", { for ((x, y) in listOf(12f to 4f, 6f to 9f, 18f to 9f, 8f to 15f, 16f to 15f, 12f to 20f)) circle(x, y, 2.3f, ORANGE_ICON); pline(ORANGE_ICON, 1.4f, 12f, 4f, 6f, 9f, 8f, 15f, 12f, 20f, 16f, 15f, 18f, 9f, 12f, 4f) })
        put("Part_Block", part)
        put("Part", part)
        put("Part_Sphere", { circle(12f, 12f, 9f, IconPainter.GRAY_L); circle(9.5f, 9f, 4.5f, IconPainter.GRAY_T); circle(13.5f, 14.5f, 4.5f, 0x22000000) })
        put("Part_Cylinder", { rect(5f, 7f, 19f, 18f, IconPainter.GRAY_L); arc(5f, 15f, 19f, 21f, 0f, 180f, IconPainter.GRAY_R, 0.1f); pie(5f, 15f, 19f, 21f, 0f, 180f, IconPainter.GRAY_L); pie(5f, 4f, 19f, 10f, 0f, 360f, IconPainter.GRAY_T) })
        put("Part_Wedge", { poly(IconPainter.GRAY_L, 3f, 20f, 21f, 20f, 21f, 5f); poly(IconPainter.GRAY_T, 3f, 20f, 21f, 5f, 21f, 8.5f, 6.5f, 20f) })
        put("Part_CornerWedge", { poly(IconPainter.GRAY_L, 3f, 20f, 12f, 3f, 12f, 20f); poly(IconPainter.GRAY_R, 12f, 3f, 21f, 20f, 12f, 20f) })
        put("Spawn", instanceIcons["SpawnLocation"]!!)
        put("InsertObject", { srect(3f, 3f, 21f, 21f, fg, 1.6f, 3f); line(12f, 7f, 12f, 17f, IconPainter.GREEN, 2.4f); line(7f, 12f, 17f, 12f, IconPainter.GREEN, 2.4f) })
        put("Anchor", { ring(12f, 5f, 2.2f, fg, 1.8f); line(12f, 7f, 12f, 20f, fg, 2f); line(8f, 10f, 16f, 10f, fg, 2f); arc(4f, 10f, 20f, 21f, 20f, 140f, fg, 2f) })
        put("Group", { srect(2.5f, 2.5f, 21.5f, 21.5f, IconPainter.BLUE, 1.4f, 2f); cube(8.5f, 10f, 4.5f); cube(15.5f, 13.5f, 4.5f) })
        // Solid modeling (Model tab): two shapes merged / overlapping / cut / pulled apart.
        put("Union", { rect(3f, 7f, 15f, 19f, IconPainter.BLUE, 1.5f); circle(15f, 10f, 6.5f, IconPainter.BLUE); circle(15f, 10f, 4.6f, IconPainter.LBLUE); rect(5f, 9f, 13f, 17f, IconPainter.LBLUE, 1f); rect(9f, 9f, 15f, 15f, IconPainter.LBLUE) })
        put("Intersect", { srect(3f, 7f, 15f, 19f, IconPainter.GRAY_L, 1.4f, 1.5f); srect(9f, 3f, 21f, 15f, IconPainter.GRAY_L, 1.4f, 1.5f); rect(9f, 7f, 15f, 15f, IconPainter.BLUE, 0.5f) })
        put("Negate", { rect(3f, 7f, 15f, 19f, IconPainter.GRAY_L, 1.5f); circle(15f, 10f, 6.5f, 0xFFE76A8C.toInt()); circle(15f, 10f, 4.6f, 0xFFF4A3B8.toInt()); line(12f, 10f, 18f, 10f, IconPainter.WHITE, 1.8f) })
        put("Separate", { rect(2.5f, 9f, 11f, 18f, IconPainter.BLUE, 1.5f); circle(17f, 8f, 5f, IconPainter.BLUE); line(9f, 6f, 14f, 13f, IconPainter.GRAY_L, 1.4f); pline(IconPainter.GRAY_L, 1.4f, 4f, 21f, 12f, 21f) })
        put("BoxSelect", { srect(3f, 5f, 21f, 19f, IconPainter.BLUE, 1.5f, 1f); rect(7f, 9f, 13f, 15f, IconPainter.GRAY_L, 1f); pline(IconPainter.WHITE, 1.6f, 14f, 13f, 19f, 18f) })
        put("Ungroup", { cube(7f, 9f, 4.5f); cube(17f, 15f, 4.5f); line(4f, 20f, 20f, 4f, IconPainter.RED, 1.6f) })
        put("Duplicate", { box(3f, 3f, 15f, 15f, IconPainter.GRAY_T, IconPainter.GRAY_R); box(9f, 9f, 21f, 21f, IconPainter.LBLUE, IconPainter.DBLUE) })
        put("Delete", { rect(6f, 7f, 18f, 21f, IconPainter.RED, 1.5f); rect(4f, 4.5f, 20f, 7f, IconPainter.RED, 1f); rect(9.5f, 2.5f, 14.5f, 4.5f, IconPainter.RED, 0.8f); for (x in listOf(9.5f, 12f, 14.5f)) line(x, 10f, x, 18f, W, 1.2f) })
        put("ZoomTo", { magnifier(fg); circle(10f, 10f, 2f, IconPainter.BLUE) })
        put("FindAll", { magnifier(fg) })
        put("Move_P", { for (i in 0..3) { line(4f + i * 5.33f, 3f, 4f + i * 5.33f, 21f, Ui.DIM, 1f); line(3f, 4f + i * 5.33f, 21f, 4f + i * 5.33f, Ui.DIM, 1f) }; circle(14.7f, 9.3f, 2.6f, IconPainter.BLUE) })
        put("Rotate_P", { line(4f, 20f, 20f, 20f, fg, 1.8f); line(4f, 20f, 16f, 6f, fg, 1.8f); arc(-4f, 12f, 12f, 28f, -50f, 50f, IconPainter.BLUE, 2f) })
        put("Color", { circle(12f, 12f, 9.5f, 0xFFE8D5B0.toInt()); circle(8f, 8f, 2.2f, IconPainter.RED); circle(13.5f, 6.5f, 2.2f, IconPainter.YELLOW); circle(17.5f, 11f, 2.2f, IconPainter.BLUE); circle(8f, 14f, 2.2f, IconPainter.GREEN); circle(14f, 16.5f, 2.5f, Ui.PANEL) })
        put("Material", { circle(12f, 12f, 9.5f, 0xFF9C7A54.toInt()); for (i in 0 until 4) arc(3f + i * 1.5f, 6f + i * 3f, 21f - i * 1.5f, 30f, 225f, 90f, 0xFF7A5A38.toInt(), 1.3f); circle(8.5f, 8.5f, 2.5f, 0x44FFFFFF) })
        put("Surface", { cube(12f, 12f, 9f); for ((x, y) in listOf(12f to 6.5f, 8.5f to 8.3f, 15.5f to 8.3f, 12f to 10f)) circle(x, y, 1.1f, IconPainter.GRAY_R) })
        put("Cleanup", { poly(IconPainter.RED, 4f, 15f, 13f, 6f, 20f, 13f, 11f, 22f); poly(0xFFF5B7B9.toInt(), 4f, 15f, 8f, 11f, 15f, 18f, 11f, 22f); line(2f, 22f, 22f, 22f, fg, 1.4f) })
        put("CommandBar", { rect(2f, 4f, 22f, 20f, IconPainter.DARK, 2f); pline(IconPainter.GREEN, 1.8f, 6f, 9f, 9f, 12f, 6f, 15f); line(11f, 15f, 16f, 15f, W, 1.8f) })
        put("Explorer", { rect(3f, 3f, 9f, 8f, IconPainter.YELLOW, 1f); line(6f, 8f, 6f, 18.5f, fg, 1.4f); line(6f, 12.5f, 11f, 12.5f, fg, 1.4f); line(6f, 18.5f, 11f, 18.5f, fg, 1.4f); rect(11f, 10f, 20f, 15f, IconPainter.BLUE, 1f); rect(11f, 16f, 20f, 21f, IconPainter.GRAY_L, 1f) })
        put("Properties", { for (i in 0 until 4) { val y = 4.5f + i * 5f; line(3f, y, 9f, y, fg, 1.5f); rect(11f, y - 1.6f, 21f, y + 1.6f, if (i == 1) IconPainter.BLUE else IconPainter.GRAY_L, 1f) } })
        put("Toolbox", { rect(2f, 8f, 22f, 20f, IconPainter.RED, 2f); arc(8f, 3f, 16f, 11f, 180f, 180f, fg, 2f); rect(2f, 12f, 22f, 13.5f, 0xFFB5383C.toInt()); rect(10f, 11f, 14f, 15f, IconPainter.YELLOW, 0.8f) })
        put("Output", { rect(2f, 3f, 22f, 21f, IconPainter.DARK, 2f); line(5f, 8f, 15f, 8f, W, 1.4f); line(5f, 12f, 18f, 12f, IconPainter.YELLOW, 1.4f); line(5f, 16f, 12f, 16f, 0xFFFF7A7A.toInt(), 1.4f) })
        put("SwitchWindows", { srect(2.5f, 3.5f, 21.5f, 20.5f, fg, 1.5f, 1.5f); line(9f, 3.5f, 9f, 20.5f, fg, 1.2f); line(9f, 12f, 21.5f, 12f, fg, 1.2f); rect(2.5f, 3.5f, 9f, 20.5f, 0x553B8BEB, 1.5f) })
        put("Effects", { circle(12f, 12f, 4.5f, IconPainter.YELLOW); for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); line(12f + 7f * Math.cos(a).toFloat(), 12f + 7f * Math.sin(a).toFloat(), 12f + 9.8f * Math.cos(a).toFloat(), 12f + 9.8f * Math.sin(a).toFloat(), IconPainter.YELLOW, 1.8f) } })
        put("ViewSelector", { cube(12f, 12f, 8f, IconPainter.LBLUE, IconPainter.BLUE, IconPainter.DBLUE) })
        put("Fwd", { poly(IconPainter.BLUE, 3f, 5f, 12f, 12f, 3f, 19f); poly(IconPainter.BLUE, 12f, 5f, 21f, 12f, 12f, 19f) })
        put("Fly", { arc(3f, 5f, 21f, 23f, 180f, 180f, fg, 2.2f); line(12f, 14f, 17f, 8f, IconPainter.RED, 2.2f); circle(12f, 14f, 1.8f, fg) })
        put("ManagePlugins", { rect(4f, 7f, 18f, 21f, IconPainter.BLUE, 2f); circle(11f, 6.5f, 3f, IconPainter.BLUE); circle(18.5f, 14f, 3f, IconPainter.BLUE) })
        put("PluginsFolder", { arc(4f, 4f, 20f, 20f, 30f, 300f, fg, 2.2f); arrowHead(18.8f, 8f, 160f, 3f, fg) })
        put("Place", { rect(3f, 3f, 21f, 21f, IconPainter.GREEN, 2f); poly(0xFF2E8B57.toInt(), 5f, 19f, 10f, 9f, 14f, 15f, 16f, 12f, 19f, 19f); circle(16f, 7f, 2f, IconPainter.YELLOW) })
        put("Settings", { gear(12f, 12f, 8f, fg, Ui.PANEL) })
        put("Open", { poly(0xFFD9A520.toInt(), 2f, 5f, 9f, 5f, 11f, 7f, 20f, 7f, 20f, 10f, 2f, 10f); poly(IconPainter.YELLOW, 2f, 20f, 5f, 10f, 23f, 10f, 20f, 20f) })
        put("Save", { rect(3f, 3f, 21f, 21f, IconPainter.BLUE, 2f); rect(7f, 3f, 17f, 9f, W, 0.5f); rect(13.5f, 4f, 15.5f, 8f, IconPainter.BLUE); rect(6f, 13f, 18f, 21f, IconPainter.PAPER, 0.5f) })
        put("Back", { line(20f, 12f, 5f, 12f, fg, 2.2f); arrowHead(4f, 12f, 270f, 3.5f, fg) })
        put("History", { arc(3f, 3f, 21f, 21f, 120f, 300f, fg, 2f); arrowHead(4.5f, 16f, 200f, 3f, fg); pline(fg, 1.8f, 12f, 7f, 12f, 12f, 15.5f, 14f) })
        put("Download", { line(12f, 3f, 12f, 15f, IconPainter.BLUE, 2.4f); arrowHead(12f, 16f, 180f, 4f, IconPainter.BLUE); pline(fg, 2f, 3f, 15f, 3f, 21f, 21f, 21f, 21f, 15f) })
        put("Publish", { cloud(12f, 13f, 8.5f, IconPainter.BLUE); line(12f, 19f, 12f, 10f, W, 2.2f); arrowHead(12f, 9f, 0f, 3.5f, W) })
        put("Cloud", { cloud(12f, 13f, 8.5f, IconPainter.BLUE) })
        put("Account", { circle(12f, 12f, 10f, IconPainter.BLUE); person(12f, 13.5f, 9f, W) })
        // Start page: home, experiences shared with you (two people), about, a video link.
        put("Home", { poly(IconPainter.BLUE, 2.5f, 11.5f, 12f, 3f, 21.5f, 11.5f); rect(5f, 11f, 19f, 21f, IconPainter.LBLUE, 1f); rect(10f, 14.5f, 14f, 21f, IconPainter.DBLUE, 0.6f) })
        put("Shared", { circle(8.5f, 12f, 7f, IconPainter.TEAL); person(8.5f, 13f, 6.5f, W); circle(15.5f, 12f, 7f, IconPainter.BLUE); person(15.5f, 13f, 6.5f, W) })
        put("Info", { circle(12f, 12f, 10f, IconPainter.BLUE); circle(12f, 7.2f, 1.5f, W); rect(10.8f, 10f, 13.2f, 17.5f, W, 0.8f) })
        put("Video", { rect(2f, 5f, 22f, 19f, IconPainter.RED, 5f); poly(W, 10f, 8.5f, 16f, 12f, 10f, 15.5f) })
        put("Theme", { circle(12f, 12f, 9f, fg); pie(3f, 3f, 21f, 21f, -90f, 180f, Ui.PANEL); ring(12f, 12f, 9f, fg, 1.4f) })
        put("HideWindows", { srect(3f, 3f, 21f, 21f, fg, 1.4f, 2f); pline(IconPainter.BLUE, 2f, 6f, 10f, 6f, 6f, 10f, 6f); pline(IconPainter.BLUE, 2f, 14f, 6f, 18f, 6f, 18f, 10f); pline(IconPainter.BLUE, 2f, 6f, 14f, 6f, 18f, 10f, 18f); pline(IconPainter.BLUE, 2f, 14f, 18f, 18f, 18f, 18f, 14f) })
        put("ShowWindows", { srect(3f, 3f, 21f, 21f, fg, 1.4f, 2f); rect(3f, 3f, 8f, 21f, 0x663B8BEB, 2f); rect(16f, 3f, 21f, 21f, 0x663B8BEB, 2f); rect(8f, 15f, 16f, 21f, 0x663B8BEB) })
        put("RunScript", { poly(IconPainter.GREEN, 6f, 4f, 20f, 12f, 6f, 20f) })
        put("Play", { poly(IconPainter.GREEN, 6f, 3.5f, 20f, 12f, 6f, 20.5f) })
        put("Stop", { rect(5f, 5f, 19f, 19f, IconPainter.RED, 2f) })
        put("Undo", { arc(5f, 6f, 20f, 21f, 180f, 230f, fg, 2.2f); arrowHead(5.5f, 11.5f, 0f, 3.2f, fg) })
        put("Redo", { arc(4f, 6f, 19f, 21f, 130f, 230f, fg, 2.2f); arrowHead(18.5f, 11.5f, 0f, 3.2f, fg) })
        put("SelectAll", { srect(3f, 3f, 13f, 13f, fg, 1.5f, 1.5f); srect(11f, 11f, 21f, 21f, IconPainter.BLUE, 1.5f, 1.5f); pline(IconPainter.BLUE, 1.6f, 13.5f, 16f, 15.5f, 18f, 18.5f, 14f) })
        put("CollapseAll", { srect(3f, 3f, 21f, 21f, fg, 1.5f, 2f); line(7f, 12f, 17f, 12f, fg, 2.2f) })
        put("ScriptAnalysis", { page(IconPainter.GREEN); pline(IconPainter.GREEN, 2.2f, 9.5f, 13f, 12f, 16f, 17f, 9.5f) })
        put("Audio", { speaker(IconPainter.BLUE) })
        put("Mesh", instanceIcons["MeshPart"]!!)
        put("Decal", instanceIcons["Decal"]!!)
        put("Model", model)
        put("Plugin", instanceIcons["Plugin"]!!)
        put("Link", { chain(fg) })
        put("Pin", { circle(12f, 8f, 5f, IconPainter.RED); line(12f, 12f, 12f, 21f, fg, 1.8f) })
        // Clipboard (Home tab): two sheets, scissors, a clipboard.
        put("Copy", { srect(3f, 3f, 14f, 16f, fg, 1.5f, 1.5f); rect(9f, 8f, 21f, 21f, IconPainter.LBLUE, 1.5f); srect(9f, 8f, 21f, 21f, IconPainter.BLUE, 1.4f, 1.5f) })
        put("Cut", { ring(7f, 17.5f, 3.2f, fg, 1.8f); ring(17f, 17.5f, 3.2f, fg, 1.8f); line(9f, 15f, 17f, 3f, fg, 1.8f); line(15f, 15f, 7f, 3f, fg, 1.8f) })
        put("Paste", {
            rect(4f, 4f, 20f, 22f, 0xFFB98A4E.toInt(), 2f); rect(8.5f, 2.5f, 15.5f, 6.5f, IconPainter.GRAY_L, 1f)
            rect(7f, 8f, 17f, 20f, IconPainter.PAPER, 1f); line(9f, 12f, 15f, 12f, IconPainter.GRAY_R, 1.2f); line(9f, 15.5f, 15f, 15.5f, IconPainter.GRAY_R, 1.2f)
        })
        put("Lock", { arc(7f, 3f, 17f, 15f, 180f, 180f, fg, 2.2f); line(7f, 9f, 7f, 11f, fg, 2.2f); line(17f, 9f, 17f, 11f, fg, 2.2f); rect(4.5f, 10.5f, 19.5f, 21f, IconPainter.YELLOW, 2f); circle(12f, 15.5f, 1.8f, IconPainter.DARK) })
        // Collisions toggle: two blocks touching with a spark where they meet.
        put("Collisions", { box(2.5f, 9f, 11.5f, 18f, IconPainter.GRAY_T, IconPainter.GRAY_R); box(12.5f, 6f, 21.5f, 15f, IconPainter.LBLUE, IconPainter.DBLUE); poly(IconPainter.YELLOW, 12f, 2f, 13.5f, 6.5f, 10.5f, 6.5f) })
    }

    fun general(name: String): Drawable? = generalIcons[name]?.let { VectorIcon(it) }

    private val uiIcons: Map<String, D> = HashMap<String, D>().apply {
        put("CloseWidget", { line(6f, 6f, 18f, 18f, fg, 2f); line(18f, 6f, 6f, 18f, fg, 2f) })
        put("Float", { srect(4f, 8f, 16f, 20f, fg, 1.5f, 1.5f); line(11f, 13f, 20f, 4f, fg, 1.6f); pline(fg, 1.6f, 13f, 4f, 20f, 4f, 20f, 11f) })
        put("Grip", { for (x in listOf(9f, 15f)) for (y in listOf(6f, 12f, 18f)) circle(x, y, 1.6f, Ui.DIM) })
    }

    fun ui(name: String): Drawable? = uiIcons[name]?.let { VectorIcon(it) }
}
