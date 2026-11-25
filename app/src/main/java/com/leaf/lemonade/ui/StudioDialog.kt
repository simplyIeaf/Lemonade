package com.leaf.lemonade.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object StudioStyle {
    val surface: Int get() = if (Ui.LIGHT) 0xFFFFFFFF.toInt() else 0xFF2B2C31.toInt()
    val border: Int get() = if (Ui.LIGHT) Ui.BORDER else 0xFF3E4047.toInt()
    val primary: Int get() = Ui.ACCENT
    val secondary: Int get() = if (Ui.LIGHT) 0xFFD6D6D6.toInt() else 0xFF45464C.toInt()
    val rowPressed: Int get() = if (Ui.LIGHT) Ui.HOVER else 0xFF3B3D44.toInt()
    val text: Int get() = if (Ui.LIGHT) Ui.TEXT else 0xFFE6E7EB.toInt()
    val dim: Int get() = if (Ui.LIGHT) Ui.DIM else 0xFFA3A5AD.toInt()
    const val SCRIM = 0x99000000.toInt()

    fun panelBackground(ctx: Context, radius: Float = 8f): GradientDrawable = GradientDrawable().apply {
        setColor(surface)
        setStroke(Ui.dp(ctx, 1f), border)
        cornerRadius = Ui.dp(ctx, radius).toFloat()
    }

    fun findActivity(ctx: Context?): Activity? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

enum class StudioIcon { NONE, ERROR, WARNING }

private class PromptIconDrawable(private val kind: StudioIcon) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        if (kind == StudioIcon.ERROR) {
            p.style = Paint.Style.FILL; p.color = 0xFFE5453B.toInt()
            c.drawCircle(cx, cy, s / 2f, p)
            p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = s * 0.11f; p.strokeCap = Paint.Cap.ROUND
            val k = s * 0.19f
            c.drawLine(cx - k, cy - k, cx + k, cy + k, p)
            c.drawLine(cx + k, cy - k, cx - k, cy + k, p)
        } else if (kind == StudioIcon.WARNING) {
            p.style = Paint.Style.FILL; p.color = 0xFFF2B01E.toInt()
            p.strokeJoin = Paint.Join.ROUND
            val path = Path().apply {
                moveTo(cx, cy - s * 0.46f); lineTo(cx + s * 0.5f, cy + s * 0.42f); lineTo(cx - s * 0.5f, cy + s * 0.42f); close()
            }
            c.drawPath(path, p)
            p.color = 0xFF2B2C31.toInt()
            c.drawRoundRect(cx - s * 0.05f, cy - s * 0.2f, cx + s * 0.05f, cy + s * 0.12f, s * 0.04f, s * 0.04f, p)
            c.drawCircle(cx, cy + s * 0.25f, s * 0.06f, p)
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class BoundedScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        val mode = MeasureSpec.getMode(h)
        if (mode == MeasureSpec.EXACTLY) { super.onMeasure(w, h); return }
        val limit = if (mode == MeasureSpec.AT_MOST) minOf(maxH, MeasureSpec.getSize(h)) else maxH
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}

private class BoundedFrame(ctx: Context, private val maxH: Int) : FrameLayout(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        val mode = MeasureSpec.getMode(h)
        if (mode == MeasureSpec.EXACTLY) { super.onMeasure(w, h); return }
        val limit = if (mode == MeasureSpec.AT_MOST) minOf(maxH, MeasureSpec.getSize(h)) else maxH
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}

class StudioDialog private constructor(private val ctx: Context, private val b: Builder) : DialogInterface {
    class Builder(val ctx: Context) {
        internal var title: CharSequence? = null
        internal var message: CharSequence? = null
        internal var view: View? = null
        internal var viewWasScrolling = false
        internal var icon = StudioIcon.NONE
        internal var positive: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
        internal var negative: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
        internal var neutral: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
        internal var items: Array<String>? = null
        internal var itemListener: DialogInterface.OnClickListener? = null
        internal var checked = -1
        internal var widthDp = 420
        internal var fixedW = 0
        internal var fixedH = 0
        internal var cancelable = true
        internal var dismissListener: DialogInterface.OnDismissListener? = null

        fun setTitle(t: CharSequence?): Builder { title = t; return this }
        fun setMessage(m: CharSequence?): Builder { message = m; return this }
        fun setIcon(i: StudioIcon): Builder { icon = i; return this }
        fun setView(v: View?): Builder {
            if (v is ScrollView && v.childCount == 1) {
                val inner = v.getChildAt(0)
                v.removeView(inner)
                view = inner; viewWasScrolling = true
            } else { view = v; viewWasScrolling = false }
            return this
        }
        fun setPositiveButton(label: CharSequence, l: DialogInterface.OnClickListener?): Builder { positive = label to l; return this }
        fun setNegativeButton(label: CharSequence, l: DialogInterface.OnClickListener?): Builder { negative = label to l; return this }
        fun setNeutralButton(label: CharSequence, l: DialogInterface.OnClickListener?): Builder { neutral = label to l; return this }
        fun setItems(items: Array<String>, l: DialogInterface.OnClickListener?): Builder { this.items = items; itemListener = l; checked = -1; return this }
        fun setSingleChoiceItems(items: Array<String>, checkedItem: Int, l: DialogInterface.OnClickListener?): Builder {
            this.items = items; itemListener = l; checked = checkedItem; return this
        }
        fun setWidthDp(w: Int): Builder { widthDp = w; return this }
        fun setFixedSizeDp(w: Int, h: Int): Builder { fixedW = w; fixedH = h; return this }
        fun setCancelable(c: Boolean): Builder { cancelable = c; return this }
        fun setOnDismissListener(l: DialogInterface.OnDismissListener?): Builder { dismissListener = l; return this }
        fun create(): StudioDialog = StudioDialog(ctx, this)
        fun show(): StudioDialog = create().also { it.show() }
    }

    private val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
    private var dismissListener: DialogInterface.OnDismissListener? = b.dismissListener
    private var cancelListener: DialogInterface.OnCancelListener? = null
    private var focusField: EditText? = null
    val isShowing: Boolean get() = dialog.isShowing

    init { build() }

    private fun dp(v: Float) = Ui.dp(ctx, v)

    private fun findEdit(v: View?): EditText? {
        if (v is EditText) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findEdit(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun button(label: CharSequence, primary: Boolean, l: DialogInterface.OnClickListener?, which: Int): TextView =
        TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else StudioStyle.text)
            gravity = Gravity.CENTER
            isSingleLine = true
            minWidth = dp(72f)
            setPadding(dp(16f), dp(7f), dp(16f), dp(7f))
            background = Ui.rippleBg(ctx, if (primary) StudioStyle.primary else StudioStyle.secondary, 4f)
            isClickable = true
            setOnClickListener { dismiss(); l?.onClick(this@StudioDialog, which) }
        }

    private fun build() {
        val dm = ctx.resources.displayMetrics
        val maxPanelH = (dm.heightPixels * 0.9f).toInt()
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(StudioStyle.SCRIM)
            setOnClickListener { if (b.cancelable) cancel() }
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioStyle.panelBackground(ctx)
            elevation = dp(12f).toFloat()
            isClickable = true
        }
        val fixed = b.fixedW > 0 && b.fixedH > 0
        val panelW = if (fixed) minOf(dp(b.fixedW.toFloat()), (dm.widthPixels * 0.94f).toInt()) else minOf(dp(b.widthDp.toFloat()), (dm.widthPixels * 0.9f).toInt())
        val panelH = if (fixed) minOf(dp(b.fixedH.toFloat()), (dm.heightPixels * 0.94f).toInt()) else ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(panel, FrameLayout.LayoutParams(panelW, panelH, Gravity.CENTER))

        val hasButtons = b.positive != null || b.negative != null || b.neutral != null
        if (b.title != null) {
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16f), dp(14f), dp(16f), dp(6f)) }
            if (b.icon != StudioIcon.NONE) head.addView(android.widget.ImageView(ctx).apply { setImageDrawable(PromptIconDrawable(b.icon)) }, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { rightMargin = dp(10f) })
            head.addView(TextView(ctx).apply {
                text = b.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(StudioStyle.text)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            panel.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val boundH = maxPanelH - dp(if (hasButtons) 130f else 80f)
        val content: View? = when {
            b.items != null -> buildItems()
            b.view != null -> b.view
            b.message != null -> TextView(ctx).apply {
                text = b.message
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(StudioStyle.text)
                setLineSpacing(dp(2f).toFloat(), 1f)
                setPadding(dp(16f), dp(4f), dp(16f), dp(10f))
            }
            else -> null
        }
        if (content != null) {
            (content.parent as? ViewGroup)?.removeView(content)
            val scrolls = b.items != null || b.view == null || b.viewWasScrolling
            val holder: View = if (scrolls) BoundedScroll(ctx, boundH).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else BoundedFrame(ctx, boundH).apply {
                addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (fixed || content is ListView) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            panel.addView(holder, if (fixed) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            focusField = findEdit(content)
        }

        if (hasButtons) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(dp(12f), dp(8f), dp(12f), dp(12f)) }
            val lpBtn = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8f) } }
            b.positive?.let { (t, l) -> row.addView(button(t, true, l, DialogInterface.BUTTON_POSITIVE), lpBtn()) }
            b.neutral?.let { (t, l) -> row.addView(button(t, false, l, DialogInterface.BUTTON_NEUTRAL), lpBtn()) }
            b.negative?.let { (t, l) -> row.addView(button(t, false, l, DialogInterface.BUTTON_NEGATIVE), lpBtn()) }
            panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else if (content != null && b.title != null) {
            panel.addView(View(ctx), LinearLayout.LayoutParams(1, dp(6f)))
        }

        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setCancelable(b.cancelable)
        dialog.setOnCancelListener { cancelListener?.onCancel(this) }
        dialog.setOnDismissListener { dismissListener?.onDismiss(this) }
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                (if (focusField != null) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED))
        }
    }

    private fun buildItems(): View {
        val items = b.items ?: emptyArray()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6f), dp(2f), dp(6f), dp(8f)) }
        for ((i, label) in items.withIndex()) {
            val on = i == b.checked
            val row = TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(if (on) Ui.SELECT_TEXT else StudioStyle.text)
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                minHeight = dp(36f)
                setPadding(dp(12f), 0, dp(12f), 0)
                background = if (on) GradientDrawable().apply { setColor(Ui.SELECT); cornerRadius = dp(5f).toFloat() } else StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(StudioStyle.rowPressed); cornerRadius = dp(5f).toFloat() })
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                isClickable = true
                setOnClickListener { dismiss(); b.itemListener?.onClick(this@StudioDialog, i) }
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return col
    }

    fun setOnDismissListener(l: DialogInterface.OnDismissListener?) { dismissListener = l }
    fun setOnCancelListener(l: DialogInterface.OnCancelListener?) { cancelListener = l }

    fun show() {
        val act = StudioStyle.findActivity(ctx)
        if (act != null && (act.isFinishing || act.isDestroyed)) return
        try { dialog.show() } catch (_: Exception) { return }
        dialog.window?.let { w ->
            try {
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (_: Exception) {}
        }
        focusField?.let { f -> f.post { f.requestFocus() } }
    }

    override fun dismiss() { try { dialog.dismiss() } catch (_: Exception) {} }
    override fun cancel() { try { dialog.cancel() } catch (_: Exception) {} }
}

object StudioToast {
    private val main = Handler(Looper.getMainLooper())
    private const val TAG = "studio_toast"

    fun show(ctx: Context, msg: String, long: Boolean = false) {
        main.post {
            val act = StudioStyle.findActivity(ctx)
            val host = act?.findViewById<ViewGroup>(android.R.id.content)
            if (act == null || host == null || act.isFinishing) { android.widget.Toast.makeText(ctx.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show(); return@post }
            fun dp(v: Float) = Ui.dp(act, v)
            for (i in host.childCount - 1 downTo 0) { val c = host.getChildAt(i); if (c.tag == TAG) host.removeViewAt(i) }
            val tv = TextView(act).apply {
                tag = TAG
                text = msg
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(StudioStyle.text)
                maxWidth = (act.resources.displayMetrics.widthPixels * 0.7f).toInt()
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
                background = StudioStyle.panelBackground(act, 6f)
                elevation = dp(8f).toFloat()
                alpha = 0f
            }
            host.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40f) })
            tv.animate().alpha(1f).setDuration(120).start()
            main.postDelayed({
                tv.animate().alpha(0f).setDuration(200).withEndAction { (tv.parent as? ViewGroup)?.removeView(tv) }.start()
            }, if (long) 3500L else 2200L)
        }
    }
}
