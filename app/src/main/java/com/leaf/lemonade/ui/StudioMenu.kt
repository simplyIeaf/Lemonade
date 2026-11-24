package com.leaf.lemonade.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * Studio's dropdown / context menu: a rounded dark panel with a thin border that opens under
 * (or above) the control it belongs to, rows with a soft hover highlight, and thin separators.
 */
class StudioMenu(private val ctx: Context, private val anchor: View) {
    private class Item(val label: String, val enabled: Boolean, val action: () -> Unit)

    private val items = ArrayList<Item?>()
    private var window: PopupWindow? = null

    fun add(label: String, enabled: Boolean = true, action: () -> Unit): StudioMenu {
        items.add(Item(label, enabled, action))
        return this
    }

    fun separator(): StudioMenu {
        if (items.isNotEmpty() && items.last() != null) items.add(null)
        return this
    }

    fun dismiss() { window?.dismiss(); window = null }

    fun show() {
        while (items.isNotEmpty() && items.last() == null) items.removeAt(items.size - 1)
        if (items.isEmpty()) return
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val surface = if (Ui.LIGHT) 0xFFFFFFFF.toInt() else 0xFF2D2E33.toInt()
        val border = if (Ui.LIGHT) Ui.BORDER else 0xFF40424A.toInt()
        val pressed = if (Ui.LIGHT) Ui.HOVER else 0xFF3B3D44.toInt()
        val divider = if (Ui.LIGHT) Ui.BORDER else 0xFF3E4047.toInt()
        val textColor = if (Ui.LIGHT) Ui.TEXT else 0xFFE6E7EB.toInt()
        val disabledColor = if (Ui.LIGHT) Ui.DIM else 0xFF7C7E87.toInt()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        for (item in items) {
            if (item == null) {
                column.addView(View(ctx).apply { setBackgroundColor(divider) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply { setMargins(dp(6f), dp(4f), dp(6f), dp(4f)) })
                continue
            }
            val row = TextView(ctx).apply {
                text = item.label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(if (item.enabled) textColor else disabledColor)
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                minHeight = dp(34f)
                setPadding(dp(12f), 0, dp(18f), 0)
                if (item.enabled) {
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pressed); cornerRadius = dp(6f).toFloat() })
                        addState(intArrayOf(android.R.attr.state_hovered), GradientDrawable().apply { setColor(pressed); cornerRadius = dp(6f).toFloat() })
                        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                    }
                    isClickable = true
                    setOnClickListener { _ -> dismiss(); item.action() }
                }
            }
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            background = GradientDrawable().apply { setColor(surface); setStroke(dp(1f), border); cornerRadius = dp(8f).toFloat() }
            clipToOutline = true
        }

        val root = anchor.rootView
        val bounds = Rect(0, 0, root.width, root.height)
        val margin = dp(8f)
        column.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val maxW = (bounds.width() - margin * 2).coerceAtMost(dp(360f))
        val w = column.measuredWidth.coerceIn(dp(168f), maxW.coerceAtLeast(dp(168f)))
        var h = column.measuredHeight + dp(2f)

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        var x = loc[0]
        if (x + w > bounds.right - margin) x = loc[0] + anchor.width - w
        x = x.coerceIn(margin, (bounds.right - margin - w).coerceAtLeast(margin))
        val gap = dp(4f)
        var y = loc[1] + anchor.height + gap
        if (y + h > bounds.bottom - margin) {
            val above = loc[1] - gap - h
            if (above >= margin) y = above
            else {
                // Doesn't fit either way: use the bigger side and scroll.
                val below = bounds.bottom - margin - (loc[1] + anchor.height + gap)
                val over = loc[1] - gap - margin
                if (over > below) { h = h.coerceAtMost(over); y = loc[1] - gap - h }
                else { h = h.coerceAtMost(below.coerceAtLeast(dp(80f))); y = loc[1] + anchor.height + gap }
            }
        }

        val pw = PopupWindow(scroll, w, h, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = dp(10f).toFloat()
        pw.setOnDismissListener { if (window === pw) window = null }
        window = pw
        try { pw.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y) } catch (_: Exception) { window = null }
    }
}
