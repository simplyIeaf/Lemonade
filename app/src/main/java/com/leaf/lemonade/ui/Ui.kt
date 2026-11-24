package com.leaf.lemonade.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.leaf.lemonade.model.Instance

/** Colors (from the active Studio theme) and small view helpers. */
object Ui {
    var BG = 0xFF1A1B20.toInt(); private set
    var PANEL = 0xFF1A1B20.toInt(); private set
    var HEADER = 0xFF212227.toInt(); private set
    var TOPBAR = 0xFF1A1B20.toInt(); private set
    var RIBBON = 0xFF1A1B20.toInt(); private set
    var BORDER = 0xFF35383F.toInt(); private set
    var TEXT = 0xFFDCDDE2.toInt(); private set
    var DIM = 0xFF9A9BA3.toInt(); private set
    var ACCENT = 0xFF3966E9.toInt(); private set
    var SELECT = 0xFF0C26B1.toInt(); private set
    var HOVER = 0xFF2D2E33.toInt(); private set
    var INPUT = 0xFF2D2E33.toInt(); private set
    var ROW_ALT = 0xFF1A1B20.toInt(); private set
    var EDITOR_BG = 0xFF202227.toInt(); private set
    var EDITOR_GUTTER = 0xFF26292F.toInt(); private set
    var EDITOR_TEXT = 0xFFE0E0E0.toInt(); private set
    var CARD = 0xFF2C2D32.toInt(); private set
    var LIGHT = false; private set
    var theme: com.leaf.lemonade.core.Theme = com.leaf.lemonade.core.Theme.DARK; private set
    const val WARN = 0xFFFFA500.toInt()
    const val ERROR = 0xFFFF5555.toInt()
    const val INFO = 0xFF6CB6FF.toInt()
    const val PLAY = 0xFF3966E9.toInt()
    const val STOP = 0xFF8E1B2A.toInt()

    /** Switches every color above to [t]. Views built afterwards pick it up; the activity is recreated to restyle existing ones. */
    fun applyTheme(t: com.leaf.lemonade.core.Theme) {
        theme = t; LIGHT = t.light
        BG = t.bg; PANEL = t.panel; HEADER = t.header; TOPBAR = t.topbar; RIBBON = t.ribbon; BORDER = t.border
        TEXT = t.text; DIM = t.dim; ACCENT = t.accent; SELECT = t.select; HOVER = t.hover; INPUT = t.input; ROW_ALT = t.rowAlt
        EDITOR_BG = t.editorBg; EDITOR_GUTTER = t.editorGutter; EDITOR_TEXT = t.editorText
        CARD = if (t.light) blend(t.panel, 0xFF000000.toInt(), 0.06f) else 0xFF2C2D32.toInt()
    }

    /** Text color that reads well on top of [SELECT]. */
    val SELECT_TEXT: Int get() = if (luminance(SELECT) > 0.55f) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()

    fun luminance(c: Int): Float = (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f

    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() and 0xFF) shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** AlertDialog style matching the theme. */
    val dialogTheme: Int get() = if (LIGHT) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert

    /** Context for PopupMenus so they follow the light/dark theme. */
    fun popupContext(ctx: Context): Context =
        android.view.ContextThemeWrapper(ctx, if (LIGHT) android.R.style.Theme_Material_Light else android.R.style.Theme_Material)

    fun dp(ctx: Context, v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
    fun dp(v: View, d: Float) = dp(v.context, d)

    fun text(ctx: Context, s: String = "", size: Float = 13f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true
        }

    fun rippleBg(ctx: Context, base: Int, radius: Float = 6f): RippleDrawable {
        val shape = GradientDrawable().apply { setColor(base); cornerRadius = dp(ctx, radius).toFloat() }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(ctx, radius).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(if (LIGHT) 0x22000000 else 0x33FFFFFF), shape, mask)
    }

    fun button(ctx: Context, label: String, base: Int = HOVER, onClick: (View) -> Unit): TextView =
        text(ctx, label, 13f).apply {
            gravity = Gravity.CENTER
            val p = dp(ctx, 10f)
            setPadding(p, dp(ctx, 6f), p, dp(ctx, 6f))
            background = rippleBg(ctx, base)
            isClickable = true
            setOnClickListener(onClick)
        }

    /** Button with an icon on the left and a label (top bar Play button, toolbox actions...). */
    fun iconTextButton(ctx: Context, icon: Drawable?, label: String, base: Int = HOVER, onClick: (View) -> Unit): TextView =
        button(ctx, label, base, onClick).apply {
            if (icon != null) {
                val s = dp(ctx, 18f)
                icon.setBounds(0, 0, s, s)
                setCompoundDrawables(icon, null, null, null)
                compoundDrawablePadding = dp(ctx, 6f)
            }
        }

    /** Toolbar button: icon above a label, like Studio's toolbar. */
    fun toolButton(ctx: Context, icon: Drawable, label: String, onClick: (View) -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = dp(ctx, 7f)
            setPadding(p, dp(ctx, 5f), p, dp(ctx, 4f))
            minimumWidth = dp(ctx, 52f)
            addView(ImageView(ctx).apply { setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(ctx, 28f), dp(ctx, 28f)))
            addView(text(ctx, label, 11.5f, if (LIGHT) TEXT else 0xFFB2B2BA.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(ctx, 3f), 0, 0) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { setColor(HOVER); cornerRadius = dp(ctx, 6f).toFloat() })
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            foreground = rippleBg(ctx, Color.TRANSPARENT)
            isClickable = true
            setOnClickListener(onClick)
        }

    fun input(ctx: Context, value: String = "", hint: String = "", numeric: Boolean = false, multiLine: Boolean = false): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setTextColor(TEXT)
            setHintTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply { setColor(INPUT); if (LIGHT) setStroke(dp(ctx, 1f), 0xFFC4C4C4.toInt()); cornerRadius = dp(ctx, 6f).toFloat() }
            val p = dp(ctx, 8f)
            setPadding(p, p, p, p)
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (!multiLine) isSingleLine = true
        }

    /** Panel title bar: centered title; the dock adds the pop-out and close buttons on the right. */
    fun header(ctx: Context, title: String, @Suppress("UNUSED_PARAMETER") icon: Drawable? = null): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(HEADER)
        minimumHeight = dp(ctx, 30f)
        setPadding(dp(ctx, 4f), dp(ctx, 2f), dp(ctx, 4f), dp(ctx, 2f))
        addView(text(ctx, title, 12.5f, if (LIGHT) TEXT else 0xFFC3C3CB.toInt()).apply { gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun smallIconButton(ctx: Context, glyph: String, onClick: (View) -> Unit): TextView = text(ctx, glyph, 13f).apply {
        gravity = Gravity.CENTER
        val p = dp(ctx, 6f)
        setPadding(p, dp(ctx, 3f), p, dp(ctx, 3f))
        background = rippleBg(ctx, Color.TRANSPARENT)
        isClickable = true
        setOnClickListener(onClick)
    }

    /** Small square icon button (panel headers, top bar). */
    fun iconButton(ctx: Context, icon: Drawable, size: Float = 30f, onClick: (View) -> Unit): ImageView = ImageView(ctx).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp(ctx, (size - 18f) / 2f)
        setPadding(p, p, p, p)
        background = rippleBg(ctx, Color.TRANSPARENT)
        isClickable = true
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size))
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun divider(ctx: Context, vertical: Boolean = false): View = View(ctx).apply {
        setBackgroundColor(BORDER)
        layoutParams = if (vertical) LinearLayout.LayoutParams(dp(ctx, 1f), MATCH) else LinearLayout.LayoutParams(MATCH, dp(ctx, 1f))
    }

    /** Explorer icon for an instance. */
    fun icon(ctx: Context, i: Instance): Drawable = Icons.instance(ctx, i.className)
    fun iconFor(ctx: Context, cls: String): Drawable = Icons.instance(ctx, cls)

    /** Studio-style checkbox for boolean properties (drawn, so no font glyphs needed). */
    class CheckBoxDrawable(private val checked: Boolean, private val density: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            val l = b.left.toFloat(); val t = b.top + (b.height() - s) / 2f
            p.style = Paint.Style.FILL
            p.color = if (checked) ACCENT else INPUT
            c.drawRoundRect(l, t, l + s, t + s, 2f * density, 2f * density, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * density
            p.color = if (checked) ACCENT else 0xFF8A8A8A.toInt()
            c.drawRoundRect(l, t, l + s, t + s, 2f * density, 2f * density, p)
            if (checked) {
                p.color = Color.WHITE; p.strokeWidth = 1.8f * density; p.strokeCap = Paint.Cap.ROUND
                val path = Path().apply { moveTo(l + s * 0.22f, t + s * 0.52f); lineTo(l + s * 0.42f, t + s * 0.72f); lineTo(l + s * 0.78f, t + s * 0.3f) }
                c.drawPath(path, p)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Small disclosure arrow for tree rows / categories. */
    class ArrowDrawable(private val expanded: Boolean, private val color: Int = DIM) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        override fun draw(c: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()) * 0.36f
            val cx = b.exactCenterX(); val cy = b.exactCenterY()
            p.color = color
            val path = Path()
            if (expanded) { path.moveTo(cx - s, cy - s * 0.5f); path.lineTo(cx + s, cy - s * 0.5f); path.lineTo(cx, cy + s * 0.6f) }
            else { path.moveTo(cx - s * 0.5f, cy - s); path.lineTo(cx + s * 0.6f, cy); path.lineTo(cx - s * 0.5f, cy + s) }
            path.close()
            c.drawPath(path, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
