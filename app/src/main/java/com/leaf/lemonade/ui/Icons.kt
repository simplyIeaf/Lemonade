package com.leaf.lemonade.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.leaf.lemonade.model.ClassDb

/** Studio icons, drawn by the app ([BuiltinIcons]); a letter badge is used when there is no drawn icon. */
object Icons {
    fun init(@Suppress("UNUSED_PARAMETER") ctx: Context) {}

    fun instance(ctx: Context, cls: String): Drawable = BuiltinIcons.instance(cls) ?: Badge(abbrev(cls), categoryColor(cls))

    fun general(ctx: Context, name: String, fallback: String = name): Drawable =
        BuiltinIcons.general(name)
        ?: (if (ClassDb.isServiceName(name) || name in knownClassIcons) BuiltinIcons.instance(name) else null)
        ?: Badge(abbrev(fallback), 0xFF5A6B7D.toInt())

    private val knownClassIcons = setOf("Script", "LocalScript", "ModuleScript", "Model", "Folder", "Plugin", "Humanoid", "Sound", "Decal")

    fun ui(ctx: Context, name: String, fallback: String): Drawable =
        BuiltinIcons.ui(name) ?: Badge(fallback, 0, plain = true)

    private fun abbrev(s: String): String {
        val caps = s.filter { it.isUpperCase() || it.isDigit() }
        return when {
            caps.length >= 2 -> caps.take(2)
            s.isNotEmpty() -> s.take(2).replaceFirstChar { it.uppercase() }
            else -> "?"
        }
    }

    private fun categoryColor(cls: String): Int = when {
        ClassDb.isA(cls, "LuaSourceContainer") -> 0xFF3A7BD5.toInt()
        ClassDb.isA(cls, "BasePart") -> 0xFF8C8C8C.toInt()
        ClassDb.isA(cls, "GuiBase2d") || ClassDb.isA(cls, "UIBase") -> 0xFF3FA66B.toInt()
        cls == "Model" || cls == "Folder" -> 0xFFC9A227.toInt()
        ClassDb.isA(cls, "ValueBase") -> 0xFF8E6CC7.toInt()
        ClassDb.isServiceName(cls) -> 0xFF4E6C88.toInt()
        else -> 0xFF6B6B6B.toInt()
    }

    /** Fallback icon: a rounded square with a short label. */
    class Badge(private val label: String, private val color: Int, private val plain: Boolean = false) : Drawable() {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        override fun draw(c: Canvas) {
            val b = bounds
            val r = RectF(b)
            val s = minOf(r.width(), r.height())
            r.inset((r.width() - s) / 2f + s * 0.06f, (r.height() - s) / 2f + s * 0.06f)
            if (!plain) { bg.color = color; c.drawRoundRect(r, s * 0.2f, s * 0.2f, bg) }
            tp.color = if (plain) Ui.TEXT else 0xFFFFFFFF.toInt()
            tp.textSize = s * (if (label.length > 1) 0.42f else 0.6f)
            c.drawText(label, r.centerX(), r.centerY() - (tp.descent() + tp.ascent()) / 2f, tp)
        }
        override fun setAlpha(alpha: Int) { bg.alpha = alpha; tp.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 32
        override fun getIntrinsicHeight() = 32
    }
}
