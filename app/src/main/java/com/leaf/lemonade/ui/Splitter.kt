package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * A draggable bar between two panels. [vertical] = a vertical bar that resizes left/right.
 * [onDrag] gets the pointer movement in px since the last event; [onEnd] runs when the drag ends.
 */
@SuppressLint("ViewConstructor")
class Splitter(ctx: Context, val vertical: Boolean, private val onDrag: (Float) -> Unit, private val onEnd: () -> Unit = {}) : View(ctx) {
    private val paint = Paint()
    private var dragging = false
    private var last = 0f
    private val d = resources.displayMetrics.density

    init { setBackgroundColor(Ui.BG) }

    /** Thickness the splitter should be laid out with. */
    val thickness: Int get() = (8f * d).toInt()

    override fun onDraw(canvas: Canvas) {
        if (dragging) { paint.color = Ui.ACCENT; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); return }
        paint.color = Ui.BORDER
        val t = d.coerceAtLeast(1f)
        if (vertical) canvas.drawRect((width - t) / 2f, 0f, (width + t) / 2f, height.toFloat(), paint)
        else canvas.drawRect(0f, (height - t) / 2f, width.toFloat(), (height + t) / 2f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pos = if (vertical) e.rawX else e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; last = pos; parent?.requestDisallowInterceptTouchEvent(true); invalidate() }
            MotionEvent.ACTION_MOVE -> if (dragging) { val dlt = pos - last; last = pos; if (dlt != 0f) onDrag(dlt) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate(); onEnd() }
        }
        return true
    }
}
