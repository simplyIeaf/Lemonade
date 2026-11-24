package com.leaf.lemonade.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Turns classic clothing (the 585x559 Shirt/Pants template) into the textures R15 body meshes use.
 *
 * R15 body parts don't map the template directly: Roblox first composites it into one texture per
 * body group (torso 388x272, each arm/leg 264x284) and the meshes' UVs point into those. This
 * reproduces that layout: each template face is copied to its place in the composite (edges
 * clamped a couple of pixels outward so seams don't show the background), and the joint caps
 * (waist, elbows/knees, wrists/ankles) are filled from the matching row of the template.
 */
object ClothingCompositor {
    const val TPL_W = 585
    const val TPL_H = 559
    const val TORSO_W = 388
    const val TORSO_H = 272
    const val LIMB_W = 264
    const val LIMB_H = 284
    private const val PAD = 2f

    /** Template face x positions of a limb (the y rows are shared by every limb). */
    class Limb(val front: Float, val left: Float, val back: Float, val right: Float, val up: Float, val down: Float)

    /** The template's left arm / left leg (right half of the image). */
    val LEFT = Limb(front = 308f, left = 374f, back = 440f, right = 506f, up = 308f, down = 308f)
    /** The template's right arm / right leg (left half of the image). */
    val RIGHT = Limb(front = 217f, left = 19f, back = 85f, right = 151f, up = 217f, down = 217f)

    /** Draws [tpl] (any size; it's treated as 585x559) onto a torso composite. */
    fun drawTorso(c: Canvas, tpl: Bitmap) {
        val t = TplImage(tpl)
        // Front, left side, back, right side in a row; top above the front, bottom below it.
        t.face(c, 231f, 74f, 128f, 128f, 2f, 74f)   // front
        t.face(c, 361f, 74f, 64f, 128f, 130f, 74f)  // left side
        t.face(c, 427f, 74f, 128f, 128f, 194f, 74f) // back
        t.face(c, 165f, 74f, 64f, 128f, 322f, 74f)  // right side
        t.face(c, 231f, 8f, 128f, 64f, 2f, 10f)     // top
        t.face(c, 231f, 204f, 128f, 64f, 2f, 202f)  // bottom
        // Waist caps: the ring of colors around row 170 of each side, fanned into a square.
        val row = 170f
        t.cap(c, 134f, 206f, 198f, 238f, row,
            top = 555f to 427f, right = 361f to 425f, bottom = 231f to 359f, left = 229f to 165f)
        t.cap(c, 134f, 38f, 198f, 70f, row,
            top = 231f to 359f, right = 361f to 425f, bottom = 555f to 427f, left = 229f to 165f)
    }

    /** Draws one limb of [tpl] (an arm with a shirt, a leg with pants) onto a limb composite. */
    fun drawLimb(c: Canvas, tpl: Bitmap, l: Limb) {
        val t = TplImage(tpl)
        // Upper/lower part of the limb: template rows 355..467, side by side.
        t.face(c, l.right, 355f, 64f, 112f, 2f, 66f)
        t.face(c, l.front, 355f, 64f, 112f, 66f, 66f)
        t.face(c, l.left, 355f, 64f, 112f, 130f, 66f)
        t.face(c, l.back, 355f, 64f, 112f, 194f, 66f)
        // Hand/foot: the last 16 rows (467..483).
        t.face(c, l.right, 467f, 64f, 16f, 2f, 218f)
        t.face(c, l.front, 467f, 64f, 16f, 66f, 218f)
        t.face(c, l.left, 467f, 64f, 16f, 130f, 218f)
        t.face(c, l.back, 467f, 64f, 16f, 2f, 238f)
        // Bottom of the hand/foot.
        t.face(c, l.down, 485f, 64f, 64f, 198f, 218f)
        // Top of the shoulder/hip, turned a quarter: template row -> composite column.
        t.rotatedTop(c, l.up, 289f, 2f, 2f)
        // Elbow/knee (row 416) and wrist/ankle (row 467) cross-sections.
        t.cap(c, 2f, 182f, 34f, 214f, 416f,
            top = l.back + 64f to l.back, right = l.left + 64f to l.left, bottom = l.front to l.front + 64f, left = l.right to l.right + 64f)
        t.cap(c, 38f, 182f, 70f, 214f, 467f,
            top = l.front to l.front + 64f, right = l.left to l.left + 64f, bottom = l.back + 64f to l.back, left = l.right + 64f to l.right)
    }

    /** The template scaled to Roblox's 585x559 coordinates, with clamped sampling for seams. */
    private class TplImage(src: Bitmap) {
        val sx = src.width / TPL_W.toFloat()
        val sy = src.height / TPL_H.toFloat()
        val bmp = src
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        /** Copies template rect (x, y, w, h) to composite (dx, dy), padded outward by clamping its edges. */
        fun face(c: Canvas, x: Float, y: Float, w: Float, h: Float, dx: Float, dy: Float) {
            // Cut the face out so clamping repeats its own edge pixels, not the neighbor's.
            val px = (x * sx).toInt().coerceIn(0, bmp.width - 1); val py = (y * sy).toInt().coerceIn(0, bmp.height - 1)
            val pw = (w * sx).toInt().coerceIn(1, bmp.width - px); val ph = (h * sy).toInt().coerceIn(1, bmp.height - py)
            val cut = Bitmap.createBitmap(bmp, px, py, pw, ph)
            val shader = BitmapShader(cut, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            m.setScale(w / pw, h / ph)
            m.postTranslate(dx, dy)
            shader.setLocalMatrix(m)
            paint.shader = shader
            c.drawRect(RectF(dx - PAD, dy - PAD, dx + w + PAD, dy + h + PAD), paint)
            paint.shader = null
        }

        /** The 64x64 top face at template (x, y), rotated: template row b -> column, column a -> row 64 - a. */
        fun rotatedTop(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
            val px = (x * sx).toInt().coerceIn(0, bmp.width - 1); val py = (y * sy).toInt().coerceIn(0, bmp.height - 1)
            val pw = (64f * sx).toInt().coerceIn(1, bmp.width - px); val ph = (64f * sy).toInt().coerceIn(1, bmp.height - py)
            val cut = Bitmap.createBitmap(bmp, px, py, pw, ph)
            val shader = BitmapShader(cut, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val src = floatArrayOf(0f, 0f, pw.toFloat(), 0f, 0f, ph.toFloat())
            val dst = floatArrayOf(dx, dy + 64f, dx, dy, dx + 64f, dy + 64f)
            val m = Matrix()
            m.setPolyToPoly(src, 0, dst, 0, 3)
            shader.setLocalMatrix(m)
            paint.shader = shader
            c.drawRect(RectF(dx - PAD, dy - PAD, dx + 64f + PAD, dy + 64f + PAD), paint)
            paint.shader = null
        }

        /**
         * A joint cross-section: the square (l, t)-(r, b) split into four triangles from its
         * center; each edge takes the colors along template row [row] between the given x's.
         */
        fun cap(c: Canvas, l: Float, t: Float, r: Float, b: Float, row: Float,
                top: Pair<Float, Float>, right: Pair<Float, Float>, bottom: Pair<Float, Float>, left: Pair<Float, Float>) {
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            paint.shader = shader
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            val v = row * sy
            fun tri(ax: Float, ay: Float, bx: Float, by: Float, u0: Float, u1: Float) {
                val mid = (u0 + u1) / 2f
                val verts = floatArrayOf(ax, ay, bx, by, cx, cy)
                val texs = floatArrayOf(u0 * sx, v, u1 * sx, v, mid * sx, v)
                c.drawVertices(Canvas.VertexMode.TRIANGLES, 6, verts, 0, texs, 0, null, 0, null, 0, 0, paint)
            }
            tri(l, t, r, t, top.first, top.second)
            tri(r, t, r, b, right.first, right.second)
            tri(r, b, l, b, bottom.second, bottom.first)
            tri(l, b, l, t, left.second, left.first)
            paint.shader = null
        }
    }
}
