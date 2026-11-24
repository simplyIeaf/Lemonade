package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.leaf.lemonade.model.BrickColors
import com.leaf.lemonade.model.Color3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A colour picker in the spirit of the macOS colour panel, in the app's style: a toolbar of
 * modes (Colour Wheel, Sliders, Palettes, Pencils), the original and new colour side by side,
 * and a strip of saved colours at the bottom.
 */
object ColorPicker {
    fun show(ctx: Context, title: String, initial: Color3, onSet: (Color3) -> Unit) {
        val view = ColorPickerView(ctx, initial)
        Dialogs.themed(ctx).setTitle(title).setView(ScrollView(ctx).apply { addView(view) })
            .setPositiveButton("Set") { _, _ -> onSet(view.color) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** macOS's crayon box (names and colours), for the Pencils page. */
    val PENCILS = listOf(
        "Cayenne" to 0x941100, "Mocha" to 0x945200, "Asparagus" to 0x929000, "Fern" to 0x4F8F00, "Clover" to 0x008F00, "Moss" to 0x009051,
        "Teal" to 0x009193, "Ocean" to 0x005493, "Midnight" to 0x011993, "Eggplant" to 0x531B93, "Plum" to 0x942193, "Maroon" to 0x941751,
        "Maraschino" to 0xFF2600, "Tangerine" to 0xFF9300, "Lemon" to 0xFFFB00, "Lime" to 0x8EFA00, "Spring" to 0x00F900, "Sea Foam" to 0x00FA92,
        "Turquoise" to 0x00FDFF, "Aqua" to 0x0096FF, "Blueberry" to 0x0433FF, "Grape" to 0x9437FF, "Magenta" to 0xFF40FF, "Strawberry" to 0xFF2F92,
        "Salmon" to 0xFF7E79, "Cantaloupe" to 0xFFD479, "Banana" to 0xFFFC79, "Honeydew" to 0xD4FB79, "Flora" to 0x73FA79, "Spindrift" to 0x73FCD6,
        "Ice" to 0x73FDFF, "Sky" to 0x76D6FF, "Orchid" to 0x7A81FF, "Lavender" to 0xD783FF, "Bubblegum" to 0xFF85FF, "Carnation" to 0xFF8AD8,
        "Licorice" to 0x000000, "Lead" to 0x212121, "Tungsten" to 0x424242, "Iron" to 0x5E5E5E, "Steel" to 0x797979, "Tin" to 0x919191,
        "Nickel" to 0x929292, "Aluminum" to 0xA9A9A9, "Magnesium" to 0xC0C0C0, "Silver" to 0xD6D6D6, "Mercury" to 0xEBEBEB, "Snow" to 0xFFFFFF,
    )
}

@SuppressLint("ViewConstructor")
class ColorPickerView(ctx: Context, initial: Color3) : LinearLayout(ctx) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val original = initial.toArgb() or 0xFF000000.toInt()
    private var argb = original
    /** Hue (0..360), saturation and brightness (0..1); kept so the hue doesn't jump on greys. */
    private val hsv = FloatArray(3).also { Color.colorToHSV(original, it) }
    private val listeners = ArrayList<(Any?) -> Unit>()
    private var mode = 0
    private val toolbar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val page = FrameLayout(ctx)
    private val newSwatch = View(ctx)
    private val hexLabel = Ui.text(ctx, "", 12f, Ui.DIM)
    private val saved = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val prefs = ctx.getSharedPreferences("color_picker", Context.MODE_PRIVATE)

    val color: Color3 get() = Color3(Color.red(argb) / 255f, Color.green(argb) / 255f, Color.blue(argb) / 255f)

    init {
        setPadding(dp(14f), dp(6f), dp(14f), dp(4f))
        mode = prefs.getInt("mode", 0).coerceIn(0, 3)
        // Original | new, like the colour well (tap the original to go back to it).
        val wells = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        val old = View(ctx).apply {
            background = GradientDrawable().apply { setColor(original); cornerRadii = floatArrayOf(dp(6f).toFloat(), dp(6f).toFloat(), 0f, 0f, 0f, 0f, dp(6f).toFloat(), dp(6f).toFloat()) }
            setOnClickListener { set(original, null) }
            contentDescription = "Original colour"
        }
        wells.addView(old, Ui.lp(0, dp(34f), 1f))
        wells.addView(newSwatch, Ui.lp(0, dp(34f), 2f).apply { leftMargin = dp(2f) })
        val savedRow = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(saved) }
        hexLabel.setPadding(0, dp(3f), 0, dp(6f))
        hexLabel.isSingleLine = false
        val cfg = resources.configuration
        if (cfg.screenHeightDp < 520 && cfg.screenWidthDp > 600) {
            // Landscape phones: controls on the left, the page (wheel, sliders…) on the right.
            orientation = HORIZONTAL
            val left = LinearLayout(ctx).apply { orientation = VERTICAL }
            left.addView(toolbar, Ui.lp(Ui.MATCH, Ui.WRAP))
            left.addView(wells, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
            left.addView(hexLabel)
            left.addView(Ui.divider(ctx), Ui.lp(Ui.MATCH, 1).apply { topMargin = dp(4f) })
            left.addView(savedRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
            addView(left, Ui.lp(0, Ui.WRAP, 1f))
            addView(page, Ui.lp(0, Ui.WRAP, 1.15f).apply { leftMargin = dp(16f) })
        } else {
            orientation = VERTICAL
            addView(toolbar, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(wells, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
            addView(hexLabel)
            addView(page, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(Ui.divider(ctx), Ui.lp(Ui.MATCH, 1).apply { topMargin = dp(8f) })
            addView(savedRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        }
        buildToolbar()
        buildSaved()
        showPage()
        refresh(null)
    }

    // ------------------------------------------------------------------ state

    private fun set(c: Int, from: Any?) {
        argb = c or 0xFF000000.toInt()
        val t = FloatArray(3); Color.colorToHSV(argb, t)
        // Keep the hue (and saturation on black) so sliders don't jump around on greys.
        if (t[1] > 0.001f && t[2] > 0.001f) hsv[0] = t[0]
        if (t[2] > 0.001f) hsv[1] = t[1]
        hsv[2] = t[2]
        refresh(from)
    }

    private fun setHsv(h: Float, s: Float, v: Float, from: Any?) {
        hsv[0] = h.coerceIn(0f, 360f); hsv[1] = s.coerceIn(0f, 1f); hsv[2] = v.coerceIn(0f, 1f)
        argb = Color.HSVToColor(hsv)
        refresh(from)
    }

    private fun refresh(from: Any?) {
        newSwatch.background = GradientDrawable().apply { setColor(argb); cornerRadii = floatArrayOf(0f, 0f, dp(6f).toFloat(), dp(6f).toFloat(), dp(6f).toFloat(), dp(6f).toFloat(), 0f, 0f) }
        val name = BrickColors.name(BrickColors.closest(color))
        hexLabel.text = "#%06X  ·  RGB %d, %d, %d  ·  nearest BrickColor: %s".format(argb and 0xFFFFFF, Color.red(argb), Color.green(argb), Color.blue(argb), name)
        for (l in listeners) l(from)
    }

    // ------------------------------------------------------------------ toolbar

    private fun buildToolbar() {
        toolbar.removeAllViews()
        val names = listOf("Wheel", "Sliders", "Palettes", "Pencils")
        for (i in names.indices) {
            val b = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                background = if (i == mode) GradientDrawable().apply { setColor(Ui.HOVER); cornerRadius = dp(6f).toFloat() } else null
                isClickable = true
                setOnClickListener { mode = i; prefs.edit().putInt("mode", i).apply(); buildToolbar(); showPage() }
            }
            b.addView(ModeIcon(context, i), Ui.lp(dp(26f), dp(26f)))
            b.addView(Ui.text(context, names[i], 10.5f, if (i == mode) Ui.TEXT else Ui.DIM).apply { gravity = Gravity.CENTER })
            toolbar.addView(b, Ui.lp(0, Ui.WRAP, 1f))
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class ModeIcon(ctx: Context, private val kind: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2; val r = minOf(w, h) / 2 - 2f
            when (kind) {
                0 -> {
                    p.shader = SweepGradient(cx, cy, intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED), null)
                    c.drawCircle(cx, cy, r, p)
                    p.shader = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
                    c.drawCircle(cx, cy, r, p); p.shader = null
                }
                1 -> {
                    p.color = Ui.TEXT; p.strokeWidth = 2.2f * resources.displayMetrics.density; p.strokeCap = Paint.Cap.ROUND
                    for ((k, y) in listOf(0.28f, 0.5f, 0.72f).withIndex()) {
                        c.drawLine(w * 0.12f, h * y, w * 0.88f, h * y, p)
                        p.color = intArrayOf(0xFFE5484D.toInt(), 0xFF46A758.toInt(), 0xFF3E8EF7.toInt())[k]
                        c.drawCircle(w * (0.3f + 0.2f * k), h * y, 3.2f * resources.displayMetrics.density, p)
                        p.color = Ui.TEXT
                    }
                }
                2 -> {
                    val cols = intArrayOf(0xFFC4281C.toInt(), 0xFFF5CD30.toInt(), 0xFF287F47.toInt(), 0xFF0D69AC.toInt(), 0xFF6B327C.toInt(), 0xFFA3A2A5.toInt(), 0xFFDA8541.toInt(), 0xFF1B2A35.toInt(), 0xFFF2F3F3.toInt())
                    val s = (w - 6f) / 3f
                    for (i in 0 until 9) { p.color = cols[i]; c.drawRoundRect(3f + (i % 3) * s, 3f + (i / 3) * s, 3f + (i % 3) * s + s - 2f, 3f + (i / 3) * s + s - 2f, 3f, 3f, p) }
                }
                else -> {
                    for ((k, col) in intArrayOf(0xFFFF2600.toInt(), 0xFF00F900.toInt(), 0xFF0433FF.toInt()).withIndex()) {
                        val x = w * (0.25f + 0.25f * k)
                        p.color = col; c.drawRect(x - w * 0.08f, h * 0.3f, x + w * 0.08f, h * 0.95f, p)
                        p.color = 0xFFE8C9A0.toInt(); c.drawPath(Path().apply { moveTo(x - w * 0.08f, h * 0.3f); lineTo(x, h * 0.05f); lineTo(x + w * 0.08f, h * 0.3f); close() }, p)
                        p.color = col; c.drawPath(Path().apply { moveTo(x - w * 0.03f, h * 0.14f); lineTo(x, h * 0.05f); lineTo(x + w * 0.03f, h * 0.14f); close() }, p)
                    }
                }
            }
        }
    }

    private fun showPage() {
        listeners.clear()
        page.removeAllViews()
        page.addView(when (mode) { 0 -> wheelPage(); 1 -> slidersPage(); 2 -> palettePage(); else -> pencilsPage() })
        refresh(null)
    }

    // ------------------------------------------------------------------ colour wheel

    private fun wheelPage(): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        val wheel = Wheel(context)
        val bar = BrightnessBar(context)
        row.addView(wheel, Ui.lp(dp(168f), dp(168f)))
        row.addView(bar, Ui.lp(dp(26f), dp(168f)).apply { leftMargin = dp(16f) })
        listeners.add { from -> if (from !== wheel) wheel.invalidate(); if (from !== bar) bar.invalidate() }
        return row
    }

    @SuppressLint("ViewConstructor")
    private inner class Wheel(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var shaderSize = -1f
        private var sweep: Shader? = null; private var radial: Shader? = null

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - dp(4f)
            if (shaderSize != r) {
                shaderSize = r
                // Hue goes counter-clockwise from red at the right (like the macOS wheel).
                val hues = IntArray(13) { Color.HSVToColor(floatArrayOf((360f - it * 30f) % 360f, 1f, 1f)) }
                sweep = SweepGradient(cx, cy, hues, null)
                radial = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            p.shader = sweep; c.drawCircle(cx, cy, r, p)
            p.shader = radial; c.drawCircle(cx, cy, r, p)
            p.shader = null
            // Brightness darkens the whole wheel.
            p.color = Color.argb(((1f - hsv[2]) * 255).toInt(), 0, 0, 0); c.drawCircle(cx, cy, r, p)
            val a = Math.toRadians(hsv[0].toDouble())
            val x = cx + (cos(a) * hsv[1] * r).toFloat(); val y = cy - (sin(a) * hsv[1] * r).toFloat()
            ring.strokeWidth = dp(3f).toFloat(); ring.color = Color.WHITE; c.drawCircle(x, y, dp(7f).toFloat(), ring)
            ring.strokeWidth = dp(1f).toFloat(); ring.color = 0x99000000.toInt(); c.drawCircle(x, y, dp(8.5f).toFloat(), ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - dp(4f)
            val dx = e.x - cx; val dy = cy - e.y
            var h = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat(); if (h < 0) h += 360f
            val s = (hypot(dx, dy) / r).coerceIn(0f, 1f)
            // Picking on a black wheel brings the brightness up, like macOS.
            setHsv(h, s, if (hsv[2] < 0.05f) 1f else hsv[2], this)
            invalidate()
            return true
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class BrightnessBar(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val top = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
            p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(5f).toFloat(), dp(5f).toFloat(), p)
            p.shader = null
            val y = (1f - hsv[2]) * height
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(3f).toFloat(); p.color = Color.WHITE
            c.drawRoundRect(-1f, y - dp(3f), width + 1f, y + dp(3f), dp(3f).toFloat(), dp(3f).toFloat(), p)
            p.style = Paint.Style.FILL
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            setHsv(hsv[0], hsv[1], 1f - (e.y / height).coerceIn(0f, 1f), this)
            invalidate()
            return true
        }
    }

    // ------------------------------------------------------------------ sliders

    private var sliderMode = 0

    private fun slidersPage(): View {
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        sliderMode = prefs.getInt("sliders", 0).coerceIn(0, 2)
        val seg = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        val names = listOf("RGB", "HSB", "Gray")
        for (i in names.indices) seg.addView(Ui.text(context, names[i], 12f, if (i == sliderMode) Ui.TEXT else Ui.DIM, i == sliderMode).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(5f), dp(14f), dp(5f))
            background = GradientDrawable().apply { setColor(if (i == sliderMode) Ui.HOVER else Ui.INPUT); cornerRadius = dp(5f).toFloat() }
            setOnClickListener { prefs.edit().putInt("sliders", i).apply(); showPage() }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = dp(4f) })
        box.addView(seg, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6f) })
        when (sliderMode) {
            0 -> {
                for (ch in 0..2) box.addView(GradSlider(context, listOf("Red", "Green", "Blue")[ch], 255,
                    get = { listOf(Color.red(argb), Color.green(argb), Color.blue(argb))[ch] },
                    put = { v, from -> val c = intArrayOf(Color.red(argb), Color.green(argb), Color.blue(argb)); c[ch] = v; set(Color.rgb(c[0], c[1], c[2]), from) },
                    ends = { val c0 = intArrayOf(Color.red(argb), Color.green(argb), Color.blue(argb)); val c1 = c0.clone(); c0[ch] = 0; c1[ch] = 255; intArrayOf(Color.rgb(c0[0], c0[1], c0[2]), Color.rgb(c1[0], c1[1], c1[2])) }))
                box.addView(hexRow())
            }
            1 -> {
                box.addView(GradSlider(context, "Hue", 360, get = { hsv[0].toInt() }, put = { v, from -> setHsv(v.toFloat(), hsv[1], hsv[2], from) },
                    ends = { IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) } }))
                box.addView(GradSlider(context, "Saturation", 100, get = { (hsv[1] * 100).toInt() }, put = { v, from -> setHsv(hsv[0], v / 100f, hsv[2], from) },
                    ends = { intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))) }))
                box.addView(GradSlider(context, "Brightness", 100, get = { (hsv[2] * 100).toInt() }, put = { v, from -> setHsv(hsv[0], hsv[1], v / 100f, from) },
                    ends = { intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))) }))
            }
            else -> box.addView(GradSlider(context, "Brightness", 100,
                get = { ((Color.red(argb) * 0.299f + Color.green(argb) * 0.587f + Color.blue(argb) * 0.114f) / 2.55f).toInt() },
                put = { v, from -> val g = (v * 2.55f).toInt().coerceIn(0, 255); set(Color.rgb(g, g, g), from) },
                ends = { intArrayOf(Color.BLACK, Color.WHITE) }))
        }
        return box
    }

    private fun hexRow(): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4f), 0, 0) }
        row.addView(Ui.text(context, "Hex", 12f, Ui.DIM), Ui.lp(dp(76f), Ui.WRAP))
        val e = Ui.input(context, "%06X".format(argb and 0xFFFFFF), "RRGGBB").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        var self = false
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (self) return
                val c = Color3.fromHex(s.toString()) ?: return
                set(c.toArgb(), e)
            }
        })
        listeners.add { from -> if (from !== e) { self = true; e.setText("%06X".format(argb and 0xFFFFFF)); self = false } }
        row.addView(e, Ui.lp(dp(120f), Ui.WRAP))
        return row
    }

    /** A labelled slider over a gradient of what it changes, with a number box (like the macOS sliders page). */
    @SuppressLint("ViewConstructor")
    private inner class GradSlider(ctx: Context, label: String, private val max: Int, private val get: () -> Int,
                                   private val put: (Int, Any?) -> Unit, private val ends: () -> IntArray) : LinearLayout(ctx) {
        private val track = object : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val h = height.toFloat(); val r = RectF(0f, h * 0.28f, width.toFloat(), h * 0.72f)
                p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, ends(), null, Shader.TileMode.CLAMP)
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, p); p.shader = null
                val x = (get().toFloat() / max) * width
                p.color = Color.WHITE; c.drawCircle(x.coerceIn(h * 0.3f, width - h * 0.3f), h / 2, h * 0.3f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f).toFloat(); p.color = 0x66000000
                c.drawCircle(x.coerceIn(h * 0.3f, width - h * 0.3f), h / 2, h * 0.3f, p); p.style = Paint.Style.FILL
            }
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                put(((e.x / width).coerceIn(0f, 1f) * max).toInt(), this@GradSlider)
                return true
            }
        }
        private val num: EditText = Ui.input(ctx, get().toString(), "", numeric = true)
        private var self = false

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, label, 12f, Ui.DIM), Ui.lp(dp(76f), Ui.WRAP))
            addView(track, Ui.lp(0, dp(28f), 1f))
            num.setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            num.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (self) return
                    val v = s.toString().toFloatOrNull()?.toInt() ?: return
                    put(v.coerceIn(0, max), num)
                }
            })
            addView(num, Ui.lp(dp(58f), Ui.WRAP).apply { leftMargin = dp(8f) })
            listeners.add { from -> track.invalidate(); if (from !== num) { self = true; num.setText(get().toString()); self = false } }
        }
    }

    // ------------------------------------------------------------------ palettes (Roblox BrickColors)

    private fun palettePage(): View {
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        box.addView(Ui.text(context, "Roblox BrickColors", 12f, Ui.DIM).apply { setPadding(0, 0, 0, dp(4f)) })
        val name = Ui.text(context, "", 12f, Ui.TEXT)
        val grid = GridLayout(context).apply { columnCount = 16 }
        for (n in BrickColors.all) {
            val c = BrickColors.color(n)
            grid.addView(View(context).apply {
                background = GradientDrawable().apply { setColor(c.toArgb()); cornerRadius = dp(3f).toFloat(); setStroke(1, 0x40000000) }
                setOnClickListener { set(c.toArgb(), null); name.text = BrickColors.name(n) }
                contentDescription = BrickColors.name(n)
            }, GridLayout.LayoutParams().apply { width = dp(19f); height = dp(19f); setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f)) })
        }
        box.addView(grid)
        box.addView(name.apply { setPadding(0, dp(4f), 0, 0) })
        return box
    }

    // ------------------------------------------------------------------ pencils

    private fun pencilsPage(): View {
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        val name = Ui.text(context, "", 12f, Ui.TEXT).apply { gravity = Gravity.CENTER }
        val grid = GridLayout(context).apply { columnCount = 12 }
        for ((n, rgb) in ColorPicker.PENCILS) {
            val col = rgb or 0xFF000000.toInt()
            grid.addView(Pencil(context, col).apply {
                setOnClickListener { set(col, null); name.text = n }
                contentDescription = n
            }, GridLayout.LayoutParams().apply { width = dp(22f); height = dp(48f); setMargins(dp(1f), dp(2f), dp(1f), dp(2f)) })
        }
        box.addView(grid, Ui.lp(Ui.WRAP, Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(name, Ui.lp(Ui.MATCH, Ui.WRAP))
        return box
    }

    @SuppressLint("ViewConstructor")
    private inner class Pencil(ctx: Context, private val col: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val l = w * 0.2f; val r = w * 0.8f
            val selected = (argb or 0xFF000000.toInt()) == col
            val top = if (selected) 0f else h * 0.08f
            // Wood cone, then the lead in the pencil's colour, then the painted body.
            p.color = 0xFFE8C9A0.toInt(); c.drawPath(Path().apply { moveTo(l, top + h * 0.3f); lineTo(w / 2, top); lineTo(r, top + h * 0.3f); close() }, p)
            p.color = col; c.drawPath(Path().apply { moveTo(w / 2 - w * 0.09f, top + h * 0.1f); lineTo(w / 2, top); lineTo(w / 2 + w * 0.09f, top + h * 0.1f); close() }, p)
            c.drawRect(l, top + h * 0.3f, r, h, p)
            p.color = 0x33000000; c.drawRect(w / 2, top + h * 0.3f, r, h, p)
        }
    }

    // ------------------------------------------------------------------ saved colours

    private fun savedColors(): MutableList<Int> =
        (prefs.getString("saved", "") ?: "").split(',').mapNotNull { it.toLongOrNull(16)?.toInt() }.toMutableList()

    private fun buildSaved() {
        saved.removeAllViews()
        saved.addView(Ui.text(context, "+", 18f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Ui.INPUT); cornerRadius = dp(5f).toFloat(); setStroke(dp(1f), Ui.DIM) }
            contentDescription = "Save this colour"
            setOnClickListener {
                val list = savedColors(); list.remove(argb); list.add(0, argb)
                prefs.edit().putString("saved", list.take(30).joinToString(",") { "%08X".format(it) }).apply()
                buildSaved()
            }
        }, Ui.lp(dp(26f), dp(26f)).apply { rightMargin = dp(6f) })
        val list = savedColors()
        if (list.isEmpty()) saved.addView(Ui.text(context, "Tap + to save the colour here. Long-press a saved colour to remove it.", 11f, Ui.DIM))
        for (c in list) saved.addView(View(context).apply {
            background = GradientDrawable().apply { setColor(c); cornerRadius = dp(5f).toFloat(); setStroke(1, 0x40000000) }
            setOnClickListener { set(c, null) }
            setOnLongClickListener {
                val l = savedColors(); l.remove(c)
                prefs.edit().putString("saved", l.joinToString(",") { "%08X".format(it) }).apply()
                buildSaved(); true
            }
        }, Ui.lp(dp(26f), dp(26f)).apply { rightMargin = dp(4f) })
    }
}
