package com.leaf.lemonade.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class StudioCheckBox(ctx: Context, checked: Boolean = false, private val onChange: ((Boolean) -> Unit)? = null) : ImageView(ctx) {
    var isChecked: Boolean = checked
        set(v) { field = v; refresh() }

    init {
        val p = Ui.dp(ctx, 2f)
        setPadding(p, p, p, p)
        scaleType = ScaleType.FIT_CENTER
        refresh()
        setOnClickListener { isChecked = !isChecked; onChange?.invoke(isChecked) }
    }

    private fun refresh() { setImageDrawable(Ui.CheckBoxDrawable(isChecked, resources.displayMetrics.density)) }
}

class StudioSlider(ctx: Context, private val min: Float, private val max: Float, value: Float, private val step: Float,
                   private val onChange: (Float, Boolean) -> Unit) : View(ctx) {
    var value: Float = value.coerceIn(min, max)
        set(v) { field = snap(v); invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun snap(v: Float): Float {
        val n = Math.round((v.coerceIn(min, max) - min) / step)
        return (min + n * step).coerceIn(min, max)
    }

    override fun onMeasure(w: Int, h: Int) {
        val wantW = (150 * d).toInt()
        val width = when (MeasureSpec.getMode(w)) { MeasureSpec.EXACTLY -> MeasureSpec.getSize(w); MeasureSpec.AT_MOST -> minOf(wantW, MeasureSpec.getSize(w)); else -> wantW }
        setMeasuredDimension(width, (28 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = 9f * d
        val cy = height / 2f
        val frac = if (max > min) (value - min) / (max - min) else 0f
        val x = pad + (width - 2 * pad) * frac
        paint.style = Paint.Style.FILL
        paint.color = Ui.INPUT
        rect.set(pad, cy - 2f * d, width - pad, cy + 2f * d)
        c.drawRoundRect(rect, 2f * d, 2f * d, paint)
        paint.color = Ui.ACCENT
        rect.set(pad, cy - 2f * d, x, cy + 2f * d)
        c.drawRoundRect(rect, 2f * d, 2f * d, paint)
        paint.color = if (Ui.LIGHT) 0xFFFFFFFF.toInt() else 0xFFE6E7EB.toInt()
        c.drawCircle(x, cy, 7f * d, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Ui.ACCENT
        c.drawCircle(x, cy, 7f * d, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); update(e.x) }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun update(x: Float) {
        val pad = 9f * d
        val frac = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val v = snap(min + (max - min) * frac)
        if (v != value) { value = v; onChange(v, true) }
    }
}

class StudioSpinner(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Ui.ACCENT }
    private val rect = RectF()
    private var angle = 0f
    private var anim: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; if (isShown) invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val sw = (s * 0.11f).coerceAtLeast(2f)
        paint.strokeWidth = sw
        paint.color = Ui.INPUT
        rect.set(sw, sw, s - sw, s - sw)
        rect.offset((width - s) / 2f, (height - s) / 2f)
        c.drawArc(rect, 0f, 360f, false, paint)
        paint.color = Ui.ACCENT
        c.drawArc(rect, angle, 100f, false, paint)
    }
}

class StudioProgressBar(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var anim: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; if (isShown) invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        paint.color = Ui.INPUT
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val w = width * 0.3f
        val x = -w + (width + w) * t
        paint.color = Ui.ACCENT
        c.drawRect(x, 0f, x + w, height.toFloat(), paint)
    }
}

class StudioDropdown(ctx: Context, private val items: List<String>, selected: Int, private val customLabel: String? = null,
                     private val onPick: (Int) -> Unit) : TextView(ctx) {
    var selectedIndex: Int = selected
        private set

    init {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(StudioStyle.text)
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        val p = Ui.dp(ctx, 10f)
        setPadding(p, Ui.dp(ctx, 6f), Ui.dp(ctx, 8f), Ui.dp(ctx, 6f))
        background = Ui.rippleBg(ctx, Ui.INPUT, 5f)
        compoundDrawablePadding = Ui.dp(ctx, 8f)
        val arrow = Ui.ArrowDrawable(true, StudioStyle.dim)
        arrow.setBounds(0, 0, Ui.dp(ctx, 12f), Ui.dp(ctx, 12f))
        setCompoundDrawables(null, null, arrow, null)
        isClickable = true
        refresh()
        setOnClickListener {
            val m = StudioMenu(ctx, this)
            for ((i, label) in items.withIndex()) m.add(label) { selectedIndex = i; refresh(); onPick(i) }
            m.show()
        }
    }

    private fun refresh() { text = items.getOrNull(selectedIndex) ?: customLabel ?: items.firstOrNull() ?: "" }
}

fun studioButton(ctx: Context, label: String, primary: Boolean = false, onClick: (View) -> Unit): TextView = Ui.text(ctx, label, 13f, if (primary) 0xFFFFFFFF.toInt() else StudioStyle.text).apply {
    gravity = Gravity.CENTER
    setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 7f), Ui.dp(ctx, 14f), Ui.dp(ctx, 7f))
    background = Ui.rippleBg(ctx, if (primary) StudioStyle.primary else StudioStyle.secondary, 4f)
    isClickable = true
    setOnClickListener(onClick)
}

fun studioDivider(ctx: Context): View = View(ctx).apply {
    background = GradientDrawable().apply { setColor(StudioStyle.border) }
    layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(ctx, 1f))
}
