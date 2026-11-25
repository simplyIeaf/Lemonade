package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.core.Tool
import com.leaf.lemonade.core.UndoManager
import com.leaf.lemonade.engine.Geometry
import com.leaf.lemonade.model.*
import com.leaf.lemonade.render.AdornHandles
import com.leaf.lemonade.render.Handle
import com.leaf.lemonade.render.Handles
import com.leaf.lemonade.render.SceneRenderer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class ViewportView(ctx: Context, private val studio: Studio) : FrameLayout(ctx) {
    val gl = GLSurfaceView(ctx)
    val renderer = SceneRenderer(studio)
    val gui = GuiOverlay(ctx, studio)
    val controls = TouchLayer(ctx)
    val hud = PlayHud(ctx, studio)
    private var lastOverlay = 0L
    private var lastControls = 0L
    private var hudPosted = false

    init {
        gl.setEGLContextFactory(ContextFactory())
        gl.setEGLConfigChooser(MsaaConfigChooser())
        gl.preserveEGLContextOnPause = true
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        addView(gl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(gui, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderer.onFrame = {
            val now = System.currentTimeMillis()
            if (now - lastOverlay > 33) {
                lastOverlay = now
                val core = studio.model.findService("CoreGui")?.children?.isNotEmpty() == true
                if (gui.hasContent || studio.isPlaying || studio.pluginWidgets.isNotEmpty() || gui.selectedGui != null || core) gui.postInvalidate()
                // Every invalidate redraws the whole window, so idle edit-mode controls only refresh
                // (for the FPS counter) twice a second; touches redraw them immediately anyway.
                if (studio.isPlaying || controls.busy || now - lastControls > 500) { lastControls = now; controls.postInvalidate() }
                if (studio.isPlaying && !hudPosted) { hudPosted = true; hud.post { hudPosted = false; hud.onFrame() } }
            }
        }
        renderer.surfacePainter = { g, w, h, bmp -> gui.renderSurface(g, w, h, bmp) }
    }

    fun onResume() = gl.onResume()
    fun onPause() = gl.onPause()

    /**
     * Requests 4x MSAA for smooth edges and a 24-bit depth buffer, falling back step by step.
     * EGL sorts configs by *smallest* depth first, so asking for "at least 16" used to give
     * exactly 16 bits: far-away parts then z-fought into stripes ("lines over everything when
     * zoomed out"). 24 bits is ~256x finer.
     */
    private class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            fun pick(samples: Int, depth: Int, renderable: Int): EGLConfig? {
                val attrs = mutableListOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, renderable)
                if (samples > 0) attrs += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
                attrs += EGL10.EGL_NONE
                val arr = attrs.toIntArray()
                val n = IntArray(1)
                if (!egl.eglChooseConfig(display, arr, null, 0, n) || n[0] <= 0) return null
                val configs = arrayOfNulls<EGLConfig>(n[0])
                egl.eglChooseConfig(display, arr, configs, n[0], n)
                return configs.firstOrNull { it != null }
            }
            // OpenGL ES 3.0 configs first (EGL_OPENGL_ES3_BIT = 0x40), then plain ES 2.0 ones (0x4).
            fun pickAny(samples: Int, depth: Int) = pick(samples, depth, 0x40) ?: pick(samples, depth, 0x4)
            val c = pickAny(4, 24) ?: pickAny(2, 24) ?: pickAny(0, 24) ?: pickAny(4, 16) ?: pickAny(2, 16) ?: pickAny(0, 16)
                ?: throw IllegalStateException("No suitable EGL config")
            val v = IntArray(1)
            if (egl.eglGetConfigAttrib(display, c, EGL10.EGL_DEPTH_SIZE, v)) depthBits = v[0]
            return c
        }
    }

    /** Creates an OpenGL ES 3.0 context and falls back to ES 2.0 when the device can't. */
    private class ContextFactory : GLSurfaceView.EGLContextFactory {
        override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
            for (version in intArrayOf(3, 2)) {
                val attrs = intArrayOf(0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, version, EGL10.EGL_NONE)
                val ctx = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attrs)
                if (ctx != null && ctx !== EGL10.EGL_NO_CONTEXT) { glesVersion = version; return ctx }
            }
            throw IllegalStateException("Could not create an OpenGL ES context")
        }

        override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
            egl.eglDestroyContext(display, context)
        }
    }

    companion object {
        /** OpenGL ES major version of the context that was created (3, or 2 after falling back). */
        @Volatile var glesVersion = 2

        /** Depth-buffer bits of the chosen GL config (the renderer picks its near plane from it). */
        @Volatile var depthBits = 16
    }

    inner class TouchLayer(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        private abstract inner class Ptr(val id: Int, var x: Float, var y: Float) { val t0 = System.currentTimeMillis(); val x0 = x; val y0 = y }
        private inner class Joy(id: Int, x: Float, y: Float) : Ptr(id, x, y)
        private inner class Btn(id: Int, x: Float, y: Float, val name: String) : Ptr(id, x, y)
        private inner class Cam(id: Int, x: Float, y: Float) : Ptr(id, x, y) { var moved = false; var playDown = false }
        private inner class GuiPress(id: Int, x: Float, y: Float, val hit: GuiOverlay.Hit, val scroll: GuiOverlay.Scroll?) : Ptr(id, x, y) { var cancelled = false }
        private inner class GuiScroll(id: Int, x: Float, y: Float, val scroll: GuiOverlay.Scroll) : Ptr(id, x, y)
        private inner class GuiDrag(id: Int, x: Float, y: Float, val state: GuiOverlay.DragState) : Ptr(id, x, y) { var active = false }
        private inner class Sink(id: Int, x: Float, y: Float) : Ptr(id, x, y)
        /** A finger dragging over a plugin panel in edit mode: scrolls it like a mouse wheel. */
        private inner class PluginWheel(id: Int, x: Float, y: Float) : Ptr(id, x, y) { var acc = 0f }
        /** Dragging a handle (Move/Scale/Rotate tools). */
        private inner class Drag(id: Int, x: Float, y: Float, val handle: Handle, val tool: Tool, val parts: List<Instance>,
                                 val startCf: List<CFrame>, val startSize: List<Vector3>, val pxPerStud: Float, val screenDir: FloatArray,
                                 val center: Vector3, val localAxis: Int, val t0Axis: Float?) : Ptr(id, x, y) {
            /** Already inside another part when the drag began: collisions can't apply. */
            val startedOverlapping = tool == Tool.MOVE && studio.collisionsEnabled && overlapsOthers(parts)
            /** Selected models' stored pivots, moved along with their parts (like Studio). */
            val pivots = modelPivots()
        }
        /** Dragging selected parts themselves across surfaces, like Studio's dragger. */
        private inner class PartDrag(id: Int, x: Float, y: Float, val parts: List<Instance>, val startCf: List<CFrame>,
                                     val grab: Vector3, val center: Vector3, val half: Vector3) : Ptr(id, x, y) {
            var active = false
            val pivots = modelPivots()
        }

        /** True while another finger is already moving parts (a second drag would fight it every frame). */
        private val draggingParts get() = ptrs.values.any { it is Drag || it is PartDrag }

        /**
         * Models in the selection (and models inside them) whose pivot is stored in WorldPivotData
         * rather than following a PrimaryPart. Dragging moves those pivots with the parts.
         */
        private fun modelPivots(): List<Pair<Instance, CFrame>> {
            val out = ArrayList<Pair<Instance, CFrame>>()
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>())
            fun add(m: Instance) {
                if (!m.isA("Model") || m.isService || !seen.add(m)) return
                val cf = (m["WorldPivotData"] as? OptionalCFrame)?.cframe
                if (cf != null && m["PrimaryPart"] == null) out.add(m to cf)
            }
            for (s in studio.selection) { add(s); if (!s.isService && (s.isA("Model") || s.className == "Folder")) s.descendants().forEach { add(it) } }
            return out
        }

        private fun movePivots(pivots: List<Pair<Instance, CFrame>>, f: (CFrame) -> CFrame) {
            for ((m, cf) in pivots) m.set("WorldPivotData", OptionalCFrame(f(cf)))
        }

        private fun recordPivots(pivots: List<Pair<Instance, CFrame>>) {
            for ((m, cf) in pivots) {
                val now = m["WorldPivotData"]
                if ((now as? OptionalCFrame)?.cframe != cf) studio.undo.record(UndoManager.Op.Prop(m, "WorldPivotData", OptionalCFrame(cf), now, true))
            }
        }

        /**
         * Parts a drag moves. A model moves as a whole, Locked parts included (Studio only stops
         * you from picking locked parts); a locked part selected on its own stays put.
         */
        private fun draggableParts(): List<Instance> {
            val direct = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>())
            studio.selection.forEach { if (it.isA("BasePart")) direct.add(it) }
            return studio.selectedParts().filter { it["Locked"] != true || it !in direct }
        }
        /** Drawing a selection box. */
        private inner class BoxSel(id: Int, x: Float, y: Float, val additive: Boolean) : Ptr(id, x, y)
        /** Sculpting with the Terrain Editor's brush. */
        private inner class TerrainBrush(id: Int, x: Float, y: Float) : Ptr(id, x, y) { var lastDab = 0L; var lastAt: Vector3? = null }

        private fun terrainDab(p: TerrainBrush, force: Boolean) {
            val now = System.currentTimeMillis()
            val at = studio.terrainBrushHit(p.x, p.y) ?: return
            val prev = p.lastAt
            // A dab every ~50 ms, or when the brush has moved a quarter of its size.
            if (!force && prev != null && now - p.lastDab < 50 && (at - prev).magnitude < studio.terrainBrushSize * 0.25f) return
            p.lastDab = now; p.lastAt = at
            studio.terrainDab(at)
        }
        /** A plugin with plugin:Activate() gets one-finger input as its mouse. */
        private inner class PluginPtr(id: Int, x: Float, y: Float) : Ptr(id, x, y) { var moved = false }
        /** Dragging a script's Handles / ArcHandles adornment (fires MouseDrag on it). */
        private inner class AdornDrag(id: Int, x: Float, y: Float, val adorn: Instance, val tip: AdornHandles.Tip, val axis0: Float?,
                                      val rt: com.leaf.lemonade.lua.LuaRuntime, val arg: org.luaj.vm2.LuaValue) : Ptr(id, x, y)

        private val ptrs = HashMap<Int, Ptr>()
        /** True while a finger is down or a fly button is held (the controls need live redraws). */
        val busy: Boolean get() = ptrs.isNotEmpty() || studio.flyUp || studio.flyDown
        private var joyX = 0f; private var joyY = 0f
        private var pinchDist = 0f
        private var pinchMid = floatArrayOf(0f, 0f)
        private val buttons = HashMap<String, RectF>()
        private var jumpDown = false

        private val joyR get() = 56f * d
        private val defaultJoyX get() = 40f * d + joyR
        private val defaultJoyY get() = height - 40f * d - joyR
        private var joyCx = 0f
        private var joyCy = 0f
        private val activeJoy get() = ptrs.values.any { it is Joy }
        private val fixedStick get() = studio.isPlaying && Settings.movementMode == "Thumbstick"

        override fun onDraw(canvas: Canvas) {
            buttons.clear()
            val play = studio.play
            if (!activeJoy || fixedStick) { joyCx = defaultJoyX; joyCy = defaultJoyY }
            if (play != null) drawPlayControls(canvas, play) else drawEditControls(canvas)
            if (Settings.showFps) {
                text.textSize = 11f * d
                text.textAlign = Paint.Align.RIGHT
                text.color = 0x99FFFFFF.toInt()
                text.isFakeBoldText = false
                canvas.drawText("${renderer.fps.roundToInt()} FPS", width - 8f * d, height - 6f * d, text)
                text.textAlign = Paint.Align.CENTER
                text.color = 0xFFFFFFFF.toInt()
                text.isFakeBoldText = true
            }
        }

        /** Roblox-style thumbstick: a translucent ring with a knob; faded when idle. */
        private fun drawThumbstick(canvas: Canvas) {
            val active = activeJoy
            paint.style = Paint.Style.FILL
            paint.color = if (active) 0x4D000000 else 0x26000000
            canvas.drawCircle(joyCx, joyCy, joyR, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * d
            paint.color = if (active) 0xB3FFFFFF.toInt() else 0x4DFFFFFF
            canvas.drawCircle(joyCx, joyCy, joyR, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (active) 0xD9FFFFFF.toInt() else 0x66FFFFFF
            canvas.drawCircle(joyCx + joyX * joyR, joyCy + joyY * joyR, joyR * 0.42f, paint)
        }

        private fun drawPlayControls(canvas: Canvas, play: com.leaf.lemonade.engine.PlaySession) {
            drawThumbstick(canvas)
            // Jump button (bottom right), drawn like the Roblox mobile jump button.
            val jr = 40f * d
            val jx = width - 34f * d - jr; val jy = height - 34f * d - jr
            paint.style = Paint.Style.FILL
            paint.color = if (jumpDown) 0xB3FFFFFF.toInt() else 0x4D000000
            canvas.drawCircle(jx, jy, jr, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * d; paint.color = 0xE6FFFFFF.toInt()
            canvas.drawCircle(jx, jy, jr, paint)
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.strokeWidth = 4.5f * d
            paint.color = if (jumpDown) 0xFF333333.toInt() else 0xFFFFFFFF.toInt()
            val chev = Path().apply { moveTo(jx - 13f * d, jy + 6f * d); lineTo(jx, jy - 7f * d); lineTo(jx + 13f * d, jy + 6f * d) }
            canvas.drawPath(chev, paint)
            paint.style = Paint.Style.FILL
            buttons["jump"] = RectF(jx - jr * 1.15f, jy - jr * 1.15f, jx + jr * 1.15f, jy + jr * 1.15f)

            var ay = jy - jr - 46f * d
            // Mobile shift lock toggle, above the jump button.
            if (play.shiftLockAllowed) {
                val sr = 24f * d
                val sx = jx - jr - 12f * d - sr; val sy = jy - jr - 10f * d
                paint.style = Paint.Style.FILL
                paint.color = if (play.shiftLock) 0xE600A2FF.toInt() else 0x4D000000
                canvas.drawCircle(sx, sy, sr, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * d; paint.color = 0xE6FFFFFF.toInt()
                canvas.drawCircle(sx, sy, sr, paint)
                // padlock
                paint.strokeWidth = 2.2f * d
                canvas.drawArc(sx - 5.5f * d, sy - 11f * d, sx + 5.5f * d, sy + 1f * d, 180f, 180f, false, paint)
                paint.style = Paint.Style.FILL; paint.color = 0xFFFFFFFF.toInt()
                canvas.drawRoundRect(sx - 8f * d, sy - 4f * d, sx + 8f * d, sy + 9f * d, 2.5f * d, 2.5f * d, paint)
                paint.color = if (play.shiftLock) 0xFF00A2FF.toInt() else 0xFF333333.toInt()
                canvas.drawCircle(sx, sy + 2.5f * d, 1.8f * d, paint)
                buttons["shiftlock"] = RectF(sx - sr * 1.2f, sy - sr * 1.2f, sx + sr * 1.2f, sy + sr * 1.2f)
            }
            for (a in play.actions.values) {
                circleButton(canvas, "action:" + a.name, jx, ay, 28f * d, a.title.take(6), false)
                ay -= 66f * d
            }
            play.activePrompt?.let { pr ->
                val action = (pr["ActionText"] as? String)?.ifEmpty { "Interact" } ?: "Interact"
                val obj = (pr["ObjectText"] as? String).orEmpty()
                text.textSize = 15f * d
                val w = maxOf(text.measureText(action), text.measureText(obj) * 0.8f) + 84f * d
                val r = RectF(width / 2f - w / 2, height * 0.58f, width / 2f + w / 2, height * 0.58f + 56f * d)
                paint.color = 0xB3000000.toInt(); canvas.drawRoundRect(r, 10f * d, 10f * d, paint)
                val kc = r.left + 30f * d
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * d; paint.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(kc, r.centerY(), 16f * d, paint); paint.style = Paint.Style.FILL
                text.textSize = 13f * d; canvas.drawText("Tap", kc, r.centerY() + 4.5f * d, text)
                text.textAlign = Paint.Align.LEFT
                if (obj.isNotEmpty()) {
                    text.textSize = 12f * d; text.color = 0xFFCCCCCC.toInt(); text.isFakeBoldText = false
                    canvas.drawText(obj, kc + 26f * d, r.centerY() - 6f * d, text)
                    text.textSize = 15f * d; text.color = 0xFFFFFFFF.toInt(); text.isFakeBoldText = true
                    canvas.drawText(action, kc + 26f * d, r.centerY() + 13f * d, text)
                } else {
                    text.textSize = 15f * d
                    canvas.drawText(action, kc + 26f * d, r.centerY() + 5.5f * d, text)
                }
                text.textAlign = Paint.Align.CENTER
                buttons["prompt"] = r
            }
            val tools = if (play.coreBackpack) synchronized(studio.lock) { play.backpackTools() } else emptyList()
            if (tools.isNotEmpty()) {
                val slot = 58f * d
                val total = tools.size * (slot + 6f * d) - 6f * d
                var x = width / 2f - total / 2f
                tools.forEachIndexed { i, t ->
                    val r = RectF(x, height - slot - 10f * d, x + slot, height - 10f * d)
                    paint.color = 0x99000000.toInt()
                    canvas.drawRoundRect(r, 6f * d, 6f * d, paint)
                    if (play.equipped === t) {
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * d; paint.color = 0xFF00A2FF.toInt()
                        canvas.drawRoundRect(r, 6f * d, 6f * d, paint); paint.style = Paint.Style.FILL
                    }
                    text.textSize = 11f * d; text.textAlign = Paint.Align.LEFT; text.isFakeBoldText = false
                    canvas.drawText("${i + 1}", r.left + 5f * d, r.top + 13f * d, text)
                    text.textAlign = Paint.Align.CENTER; text.isFakeBoldText = true
                    canvas.drawText(t.name.take(9), r.centerX(), r.centerY() + 8f * d, text)
                    buttons["tool:$i"] = r
                    x += slot + 6f * d
                }
            }
        }

        private fun drawEditControls(canvas: Canvas) {
            // A plugin window over the stick's corner takes those touches, so don't draw the idle stick on top of it.
            if (activeJoy || !gui.pluginUiAt(joyCx, joyCy)) drawThumbstick(canvas)
            // Selection box being drawn.
            ptrs.values.filterIsInstance<BoxSel>().firstOrNull()?.let { b ->
                val r = RectF(minOf(b.x0, b.x), minOf(b.y0, b.y), maxOf(b.x0, b.x), maxOf(b.y0, b.y))
                paint.style = Paint.Style.FILL; paint.color = 0x2E1A9FFF
                canvas.drawRect(r, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = 0xFF1A9FFF.toInt()
                canvas.drawRect(r, paint)
                paint.style = Paint.Style.FILL
            }
            // Active plugin banner (tap to stop it, like pressing its button again).
            studio.activePlugin?.let { p ->
                text.textSize = 12f * d; text.isFakeBoldText = false
                val label = "${p.name} is active  ✕"
                val w = text.measureText(label) + 24f * d
                val r = RectF(width / 2f - w / 2f, 8f * d, width / 2f + w / 2f, 36f * d)
                paint.color = 0xCC0B5AAF.toInt(); canvas.drawRoundRect(r, 14f * d, 14f * d, paint)
                text.color = 0xFFFFFFFF.toInt(); canvas.drawText(label, r.centerX(), r.centerY() + 4.5f * d, text)
                text.isFakeBoldText = true
                buttons["plugin_off"] = r
            }
        }

        /** "Select inside models": a dashed group outline with one solid part picked inside it. */
        private fun insideIconButton(c: Canvas, name: String, x: Float, y: Float, r: Float, active: Boolean) {
            circleButton(c, name, x, y, r, "", active)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f * d; paint.color = 0xFFFFFFFF.toInt()
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f * d, 2.5f * d), 0f)
            c.drawRect(x - r * 0.5f, y - r * 0.42f, x + r * 0.5f, y + r * 0.42f, paint)
            paint.pathEffect = null; paint.style = Paint.Style.FILL
            c.drawRect(x - r * 0.08f, y - r * 0.2f, x + r * 0.3f, y + r * 0.2f, paint)
            c.drawRect(x - r * 0.34f, y - r * 0.05f, x - r * 0.16f, y + r * 0.2f, paint.apply { alpha = 120 })
            paint.alpha = 255
        }

        private fun boxIconButton(c: Canvas, name: String, x: Float, y: Float, r: Float, active: Boolean) {
            circleButton(c, name, x, y, r, "", active)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * d; paint.color = 0xFFFFFFFF.toInt()
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
            c.drawRect(x - r * 0.45f, y - r * 0.38f, x + r * 0.45f, y + r * 0.38f, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
        }

        private fun multiIconButton(c: Canvas, name: String, x: Float, y: Float, r: Float, active: Boolean) {
            circleButton(c, name, x, y, r, "", active)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * d; paint.color = 0xFFFFFFFF.toInt()
            val s = r * 0.32f
            c.drawRect(x - s * 1.4f, y - s * 1.4f, x + s * 0.4f, y + s * 0.4f, paint)
            paint.style = Paint.Style.FILL
            c.drawRect(x - s * 0.4f, y - s * 0.4f, x + s * 1.4f, y + s * 1.4f, paint)
        }

        private fun arrowButton(c: Canvas, name: String, x: Float, y: Float, r: Float, up: Boolean, active: Boolean) {
            circleButton(c, name, x, y, r, "", active)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f * d; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            paint.color = 0xFFFFFFFF.toInt()
            val s = r * 0.38f
            val p = if (up) Path().apply { moveTo(x - s, y + s * 0.5f); lineTo(x, y - s * 0.6f); lineTo(x + s, y + s * 0.5f) }
            else Path().apply { moveTo(x - s, y - s * 0.5f); lineTo(x, y + s * 0.6f); lineTo(x + s, y - s * 0.5f) }
            c.drawPath(p, paint)
            paint.style = Paint.Style.FILL
        }

        private fun circleButton(c: Canvas, name: String, x: Float, y: Float, r: Float, label: String, active: Boolean) {
            paint.style = Paint.Style.FILL
            paint.color = if (active) 0xCC0B5AAF.toInt() else 0x59000000
            c.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * d; paint.color = 0xCCFFFFFF.toInt()
            c.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.FILL
            if (label.isNotEmpty()) {
                text.textSize = (r * 0.55f).coerceAtMost(18f * d)
                c.drawText(label, x, y + text.textSize * 0.36f, text)
            }
            buttons[name] = RectF(x - r, y - r, x + r, y + r)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val idx = e.actionIndex
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(idx), e.getX(idx), e.getY(idx))
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until e.pointerCount) {
                        val p = ptrs[e.getPointerId(i)] ?: continue
                        move(p, e.getX(i), e.getY(i))
                    }
                    camGesture()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(idx), e.getX(idx), e.getY(idx), false)
                MotionEvent.ACTION_CANCEL -> { for (id in ptrs.keys.toList()) up(id, 0f, 0f, true) }
            }
            invalidate()
            return true
        }

        private val additive get() = studio.multiSelect || studio.modifierHeld

        private fun down(id: Int, x: Float, y: Float) {
            val playing = studio.isPlaying
            // On-screen controls first (jump, hotbar, prompts) — they sit above game GUIs.
            buttons.entries.firstOrNull { it.value.contains(x, y) }?.let { (name, _) ->
                ptrs[id] = Btn(id, x, y, name)
                pressButton(name, true)
                return
            }
            if (playing) {
                val hit = gui.buttonAt(x, y)
                if (hit != null) {
                    ptrs[id] = GuiPress(id, x, y, hit, gui.scrollAt(x, y))
                    gui.press(hit, x, y)
                    return
                }
                gui.scrollAt(x, y)?.let { ptrs[id] = GuiScroll(id, x, y, it); return }
                tryStartAdorn(id, x, y)?.let { ptrs[id] = it; return }
            } else {
                // Plugin GUIs (dock widgets and CoreGui) get first dibs in edit mode.
                val hit = gui.buttonAt(x, y)
                if (hit != null && (hit.kind == GuiOverlay.WIDGET_CLOSE || gui.isPluginUi(hit.inst))) {
                    ptrs[id] = GuiPress(id, x, y, hit, gui.scrollAt(x, y)); gui.press(hit, x, y); return
                }
                gui.scrollAt(x, y)?.takeIf { gui.isPluginUi(it.inst) }?.let { ptrs[id] = GuiScroll(id, x, y, it); return }
                if (gui.pluginUiAt(x, y)) {
                    // Plain plugin frames get the touch as mouse input too (InputBegan/Changed/Ended).
                    gui.pluginFrameAt(x, y)?.let { f -> ptrs[id] = GuiPress(id, x, y, f, null); gui.press(f, x, y); return }
                    ptrs[id] = PluginWheel(id, x, y); return
                }
                // Terrain Editor brush: one finger sculpts (a second finger moves the camera).
                if (studio.terrainTool != null && ptrs.isEmpty() && hypot(x - defaultJoyX, y - defaultJoyY) > joyR * 1.3f) {
                    val b = TerrainBrush(id, x, y)
                    ptrs[id] = b
                    terrainDab(b, true)
                    return
                }
                // Drag or resize the selected GUI object (like Studio's 2D editor).
                gui.selectedGui?.let { sel ->
                    val h = gui.handleAt(sel, x, y)
                    if (h != null) gui.beginDrag(sel, h)?.let { st -> ptrs[id] = GuiDrag(id, x, y, st); return }
                }
                // Handles a plugin put on a part come before everything else in the 3D view.
                tryStartAdorn(id, x, y)?.let { ptrs[id] = it; return }
                // An active plugin (plugin:Activate) receives one-finger input as its mouse.
                if (studio.activePlugin != null && ptrs.values.none { it is PluginPtr } && studio.pluginMouseEvent("Button1Down", x, y)) {
                    ptrs[id] = PluginPtr(id, x, y); return
                }
                // Handles of the Move/Scale/Rotate tools, then dragging the selected parts themselves.
                tryStartDrag(id, x, y)?.let { ptrs[id] = it; return }
                if (studio.boxSelect && ptrs.isEmpty()) { ptrs[id] = BoxSel(id, x, y, additive); return }
                tryStartPartDrag(id, x, y)?.let { ptrs[id] = it; return }
            }
            // Thumbstick: dynamic (anywhere in the lower-left area) or fixed.
            val inStickArea = if (fixedStick) hypot(x - defaultJoyX, y - defaultJoyY) < joyR * 1.6f
            else x < width * 0.4f && y > height * 0.35f
            if (!activeJoy && inStickArea && !(playing && gui.blocksAt(x, y))) {
                if (!fixedStick) { joyCx = x.coerceAtLeast(joyR * 0.6f); joyCy = y.coerceAtMost(height - joyR * 0.6f) }
                ptrs[id] = Joy(id, x, y); setJoy(x, y); return
            }
            if (playing && gui.blocksAt(x, y)) { ptrs[id] = Sink(id, x, y); return }
            val cam = Cam(id, x, y)
            ptrs[id] = cam
            if (playing) {
                // Game scripts see the finger as the mouse (Button1Down / Move / Button1Up).
                cam.playDown = true
                synchronized(studio.lock) { studio.play?.pointerDown(x, y) }
            } else {
                // Long-press on the 3D view starts a selection box.
                postDelayed({
                    val p = ptrs[id]
                    if (p === cam && !cam.moved && ptrs.size == 1 && !studio.isPlaying) {
                        ptrs[id] = BoxSel(id, cam.x, cam.y, additive).also { it.x = cam.x; it.y = cam.y }
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        invalidate()
                    }
                }, 420)
            }
            resetPinch()
        }

        private fun move(p: Ptr, nx: Float, ny: Float) {
            when (p) {
                is Joy -> { p.x = nx; p.y = ny; setJoy(nx, ny) }
                is Drag -> { p.x = nx; p.y = ny; updateDrag(p) }
                is PartDrag -> {
                    p.x = nx; p.y = ny
                    if (!p.active && hypot(nx - p.x0, ny - p.y0) > 8f * d) p.active = true
                    if (p.active) updatePartDrag(p)
                }
                is BoxSel -> { p.x = nx; p.y = ny }
                is TerrainBrush -> { p.x = nx; p.y = ny; if (ptrs.size == 1) terrainDab(p, false) }
                is AdornDrag -> { p.x = nx; p.y = ny; dragAdorn(p) }
                is PluginPtr -> {
                    if (hypot(nx - p.x0, ny - p.y0) > 6f * d) p.moved = true
                    p.x = nx; p.y = ny
                    if (ptrs.size == 1) studio.pluginMouseEvent("Move", nx, ny)
                }
                is GuiDrag -> {
                    p.x = nx; p.y = ny
                    if (!p.active && hypot(nx - p.x0, ny - p.y0) > 6f * d) p.active = true
                    if (p.active) gui.dragTo(p.state, nx - p.x0, ny - p.y0)
                }
                is GuiScroll -> { gui.scrollBy(p.scroll, nx - p.x, ny - p.y); p.x = nx; p.y = ny }
                is PluginWheel -> {
                    // One wheel notch per ~18 GUI pixels of finger travel, sent where the drag began.
                    p.acc += ny - p.y
                    p.x = nx; p.y = ny
                    val step = 18f * studio.guiUnit()
                    while (kotlin.math.abs(p.acc) >= step) {
                        val z = if (p.acc > 0f) 1 else -1
                        p.acc -= z * step
                        gui.pluginWheel(p.x0, p.y0, z)
                    }
                }
                is GuiPress -> {
                    if (!p.cancelled && hypot(nx - p.x0, ny - p.y0) > 12f * d) {
                        val s = p.scroll
                        if (s != null) {
                            // Finger started on a button inside a scrolling list: turn it into a scroll.
                            p.cancelled = true
                            gui.release(p.hit, nx, ny, false)
                            val g = GuiScroll(p.id, p.x, p.y, s)
                            ptrs[p.id] = g
                            gui.scrollBy(s, nx - p.x, ny - p.y)
                            g.x = nx; g.y = ny
                            return
                        }
                        if (!studio.isPlaying && kotlin.math.abs(ny - p.y0) > kotlin.math.abs(nx - p.x0) && gui.canWheel(p.hit, p.x0, p.y0)) {
                            // A vertical swipe that began on a plugin button: scroll the panel instead.
                            p.cancelled = true
                            gui.release(p.hit, nx, ny, false)
                            ptrs[p.id] = PluginWheel(p.id, p.x0, p.y0).also { it.x = nx; it.y = ny; it.acc = ny - p.y0 }
                            return
                        }
                    }
                    p.x = nx; p.y = ny
                    // Plugin windows that drag by their title bar follow the finger as "mouse" movement.
                    if (!p.cancelled && !studio.isPlaying) gui.pluginPointerMove(p.hit, nx, ny)
                }
                is Cam -> {
                    val dx = nx - p.x; val dy = ny - p.y
                    if (hypot(nx - p.x0, ny - p.y0) > 10f * d) p.moved = true
                    p.x = nx; p.y = ny
                    if (ptrs.values.count { it is Cam } == 1 && p.moved) {
                        synchronized(studio.lock) {
                            val play = studio.play
                            if (play != null) { play.cameraDrag(dx, dy); play.pointerMove(nx, ny) } else studio.rotateEditCamera(-dx * 0.005f, -dy * 0.005f)
                        }
                    }
                }
                else -> { p.x = nx; p.y = ny }
            }
        }

        private fun up(id: Int, x: Float, y: Float, cancel: Boolean) {
            val p = ptrs.remove(id) ?: return
            when (p) {
                is Joy -> { joyX = 0f; joyY = 0f; studio.moveX = 0f; studio.moveY = 0f }
                is Btn -> pressButton(p.name, false)
                is Drag -> finishDrag(p)
                is PartDrag -> if (p.active) finishPartDrag(p) else if (!cancel) tap(x, y)
                is BoxSel -> if (!cancel) finishBox(p)
                is TerrainBrush -> studio.terrainCommit("Terrain " + (studio.terrainTool?.name?.lowercase()?.replace('_', ' ') ?: "edit"))
                is AdornDrag -> synchronized(studio.lock) { p.rt.fire(p.adorn, "MouseButton1Up", p.arg) }
                is PluginPtr -> studio.pluginMouseEvent("Button1Up", if (cancel) p.x else x, if (cancel) p.y else y)
                is GuiDrag -> {
                    gui.endDrag()
                    if (p.active) finishGuiDrag(p)
                    else if (!cancel) tap(x, y)
                }
                is GuiPress -> if (!p.cancelled) gui.release(p.hit, x, y, !cancel && p.hit.rect.contains(x, y))
                is Cam -> {
                    if (p.playDown) synchronized(studio.lock) { studio.play?.pointerUp(if (cancel) p.x else x, if (cancel) p.y else y) }
                    if (!cancel && !p.moved && System.currentTimeMillis() - p.t0 < 400 && ptrs.values.none { it is Cam }) tap(x, y)
                    resetPinch()
                }
                else -> {}
            }
        }

        private fun finishGuiDrag(p: GuiDrag) {
            val s = p.state
            synchronized(studio.lock) {
                studio.undo.begin(if (s.handle == -1) "Move GUI" else "Resize GUI")
                val pos = s.inst["Position"]; val size = s.inst["Size"]
                if (pos != s.pos) studio.undo.record(UndoManager.Op.Prop(s.inst, "Position", s.pos, pos, true))
                if (size != s.size) studio.undo.record(UndoManager.Op.Prop(s.inst, "Size", s.size, size, true))
                studio.undo.commit()
                studio.markDirty()
            }
        }

        private fun setJoy(x: Float, y: Float) {
            var dx = (x - joyCx) / joyR; var dy = (y - joyCy) / joyR
            val m = hypot(dx, dy)
            if (m > 1f) {
                // Drag the stick base along when the finger goes past the edge, like Roblox's dynamic thumbstick.
                if (!fixedStick) {
                    val over = (m - 1f) * joyR
                    joyCx += dx / m * over; joyCy += dy / m * over
                }
                dx /= m; dy /= m
            }
            joyX = dx; joyY = dy
            studio.moveX = dx; studio.moveY = dy
        }

        private fun pressButton(name: String, down: Boolean) {
            val play = studio.play
            when {
                name == "plugin_off" && down -> synchronized(studio.lock) { studio.deactivatePlugin(null) }
                name == "jump" -> { jumpDown = down; synchronized(studio.lock) { play?.setJumpHeld(down) } }
                name == "shiftlock" && down -> synchronized(studio.lock) { play?.toggleShiftLock() }
                name == "prompt" && down -> synchronized(studio.lock) { play?.triggerPrompt() }
                name.startsWith("action:") -> synchronized(studio.lock) { play?.pressAction(name.removePrefix("action:"), down) }
                name.startsWith("tool:") && down -> synchronized(studio.lock) {
                    val i = name.removePrefix("tool:").toInt()
                    play?.backpackTools()?.getOrNull(i)?.let { play.toggleTool(it) }
                }
            }
        }

        private fun resetPinch() { pinchDist = 0f }

        private fun camGesture() {
            val cams = ptrs.values.filter { it is Cam || it is PluginPtr || it is BoxSel }
            if (cams.size < 2) return
            // A second finger turns a plugin press or box into a camera gesture.
            for (c in cams) {
                if (c is PluginPtr) { ptrs[c.id] = Cam(c.id, c.x, c.y).also { it.moved = true }; studio.pluginMouseEvent("Button1Up", c.x, c.y) }
                if (c is BoxSel) ptrs[c.id] = Cam(c.id, c.x, c.y).also { it.moved = true }
            }
            val pair = ptrs.values.filterIsInstance<Cam>()
            if (pair.size < 2) return
            val a = pair[0]; val b = pair[1]
            a.moved = true; b.moved = true
            val dist = hypot(a.x - b.x, a.y - b.y)
            val mx = (a.x + b.x) / 2f; val my = (a.y + b.y) / 2f
            if (pinchDist > 0f) {
                val zoom = dist / pinchDist
                val pdx = mx - pinchMid[0]; val pdy = my - pinchMid[1]
                synchronized(studio.lock) {
                    val play = studio.play
                    if (play != null) play.cameraZoom(zoom)
                    else {
                        val cam = studio.camera
                        val speed = 0.08f * studio.flySpeed.coerceAtMost(8f)
                        cam.pos += cam.forward * ((dist - pinchDist) * speed)
                        cam.pos += cam.right * (-pdx * speed) + cam.up * (pdy * speed)
                    }
                }
            }
            pinchDist = dist
            pinchMid = floatArrayOf(mx, my)
        }

        /** The object a tap on [part] selects: its top-level Model, unless that's already selected (then the part). */
        private fun selectTarget(part: Instance): Instance {
            if (studio.selectInsideModels) return part
            var top: Instance? = null
            var a = part.parent
            val ws = studio.model.workspace
            while (a != null && a !== ws) { if (a.className == "Model" || a.isA("PartOperation")) top = a; a = a.parent }
            return if (top != null && studio.selection.none { it === top }) top else part
        }

        private fun tap(x: Float, y: Float) {
            if (gui.tap(x, y)) return
            val play = studio.play
            if (play != null) { synchronized(studio.lock) { play.tap(x, y) }; return }
            if (studio.activePlugin != null && studio.pluginMice.containsKey(studio.activePlugin)) return
            gui.pick(x, y)?.let { g -> if (additive) studio.toggleSelect(g) else studio.select(listOf(g)); return }
            val target = synchronized(studio.lock) {
                val (o, dir) = studio.view.screenRay(x, y)
                val hit = Geometry.raycast(studio.renderParts(), o, dir, 5000f) { it["Locked"] != true }
                hit?.part?.let { selectTarget(it) }
            }
            when {
                target == null -> if (!additive) studio.select(emptyList())
                additive -> studio.toggleSelect(target)
                else -> studio.select(listOf(target))
            }
        }

        // ------------------------------------------------------------- selection box

        private fun finishBox(b: BoxSel) {
            val l = minOf(b.x0, b.x); val r = maxOf(b.x0, b.x); val t = minOf(b.y0, b.y); val bt = maxOf(b.y0, b.y)
            if (r - l < 6f * d && bt - t < 6f * d) return
            val picked = ArrayList<Instance>()
            synchronized(studio.lock) {
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>())
                for (p in studio.renderParts()) {
                    if (p["Locked"] == true) continue
                    val s = studio.view.project(PartData.cframe(p).position) ?: continue
                    if (s[0] < l || s[0] > r || s[1] < t || s[1] > bt) continue
                    if (s.size > 2 && s[2] > 1f) continue
                    // Parts of a model select the whole model (Studio selects groups as a unit).
                    var top: Instance = p
                    var a = p.parent
                    val ws = studio.model.workspace
                    if (!studio.selectInsideModels) while (a != null && a !== ws) { if (a.className == "Model" || a.isA("PartOperation")) top = a; a = a.parent }
                    if (seen.add(top)) picked.add(top)
                }
            }
            if (b.additive) studio.select(studio.selection.toList() + picked.filter { n -> studio.selection.none { it === n } })
            else studio.select(picked)
            if (picked.isNotEmpty()) studio.toast("Selected ${picked.size} object${if (picked.size == 1) "" else "s"}")
        }

        // ------------------------------------------------------------- handles

        /** Where the finger ray passes closest to the line [center] + [axis]·t (null when the axis points at the camera). */
        private fun axisParam(center: Vector3, axis: Vector3, x: Float, y: Float): Float? {
            val (o, dir) = studio.view.screenRay(x, y)
            val w0 = center - o
            val b = axis.dot(dir)
            val denom = 1f - b * b
            if (denom < 0.02f) return null
            return (b * dir.dot(w0) - axis.dot(w0)) / denom
        }

        private fun tryStartDrag(id: Int, x: Float, y: Float): Drag? {
            if (draggingParts) return null
            synchronized(studio.lock) {
                val hs = Handles.compute(studio) ?: return null
                var best: Handle? = null; var bd = 40f * d
                for (h in hs.handles) {
                    val s = studio.view.project(h.tip) ?: continue
                    val dd = hypot(s[0] - x, s[1] - y)
                    if (dd < bd) { bd = dd; best = h }
                }
                val h = best ?: return null
                val parts = draggableParts()
                if (parts.isEmpty()) return null
                if (hs.tool == Tool.SCALE && parts.size != 1) return null
                val c0 = studio.view.project(hs.center) ?: return null
                val c1 = studio.view.project(hs.center + h.axis) ?: return null
                var sx = c1[0] - c0[0]; var sy = c1[1] - c0[1]
                var len = hypot(sx, sy)
                if (len < 0.5f) { sx = 0f; sy = -1f; len = 1f }
                val t0 = if (hs.tool != Tool.ROTATE) axisParam(hs.center, h.axis, x, y) else null
                return Drag(id, x, y, h, hs.tool, parts, parts.map { PartData.cframe(it) }, parts.map { PartData.size(it) },
                    len, floatArrayOf(sx / len, sy / len), hs.center, h.index % 3, t0)
            }
        }

        private fun snap(v: Float, step: Float) = if (studio.snapEnabled && step > 0f) (v / step).roundToInt() * step else v

        /** True if any of [parts] cuts into a collidable part outside the selection (touching is fine). */
        private fun overlapsOthers(parts: List<Instance>): Boolean {
            val moved = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>()).also { s -> parts.forEach { s.add(it) } }
            val all = studio.renderParts()
            for (p in parts) {
                val (a, b) = Geometry.partAabb(p)
                val shape = Geometry.shapeOf(p)
                for (o in all) {
                    if (o in moved || o["CanCollide"] == false) continue
                    val (oa, ob) = Geometry.partAabb(o)
                    if (!Geometry.aabbOverlap(a, b, oa, ob, -0.03f)) continue
                    val push = Geometry.contact(shape, a, b, o)?.first ?: continue
                    if (push.magnitude > 0.03f) return true
                }
            }
            return false
        }

        private fun updateDrag(p: Drag) {
            synchronized(studio.lock) {
                // Studs along the handle axis: exact (finger ray vs axis line) when possible, screen-space otherwise.
                val t = if (p.t0Axis != null) axisParam(p.center, p.handle.axis, p.x, p.y) else null
                val studs = if (t != null && p.t0Axis != null) (t - p.t0Axis).coerceIn(-2000f, 2000f)
                else ((p.x - p.x0) * p.screenDir[0] + (p.y - p.y0) * p.screenDir[1]) / p.pxPerStud
                when (p.tool) {
                    Tool.MOVE -> {
                        var dd = snap(studs, studio.moveSnap)
                        p.parts.forEachIndexed { i, part -> part.set("CFrame", p.startCf[i] + p.handle.axis * dd) }
                        // Collisions on (Model tab): stop at the first part in the way, like Studio.
                        if (studio.collisionsEnabled && dd != 0f && !p.startedOverlapping && overlapsOthers(p.parts)) {
                            var lo = 0f; var hi = 1f
                            repeat(10) {
                                val mid = (lo + hi) / 2f
                                p.parts.forEachIndexed { i, part -> part.set("CFrame", p.startCf[i] + p.handle.axis * (dd * mid)) }
                                if (overlapsOthers(p.parts)) hi = mid else lo = mid
                            }
                            dd *= lo
                            if (studio.snapEnabled && studio.moveSnap > 0f) dd = (kotlin.math.truncate(dd / studio.moveSnap)) * studio.moveSnap
                            p.parts.forEachIndexed { i, part -> part.set("CFrame", p.startCf[i] + p.handle.axis * dd) }
                        }
                        val off = p.handle.axis * dd
                        movePivots(p.pivots) { it + off }
                    }
                    Tool.SCALE -> {
                        val part = p.parts[0]
                        val dd = snap(studs, studio.moveSnap)
                        val s0 = p.startSize[0]
                        val comps = floatArrayOf(s0.x, s0.y, s0.z)
                        val old = comps[p.localAxis]
                        comps[p.localAxis] = (old + dd).coerceAtLeast(0.05f)
                        val applied = comps[p.localAxis] - old
                        PartData.setSize(part, Vector3(comps[0], comps[1], comps[2]))
                        part.set("CFrame", p.startCf[0] + p.handle.axis * (applied / 2f))
                    }
                    Tool.ROTATE -> {
                        val raw = ((p.x - p.x0) * p.screenDir[0] + (p.y - p.y0) * p.screenDir[1]) / p.pxPerStud * 12f
                        // Rotation has its own increment toggle (Studio's "Rotate" box in Snap to Grid).
                        val deg = if (studio.rotSnapEnabled && studio.rotSnap > 0f) (raw / studio.rotSnap).roundToInt() * studio.rotSnap else raw
                        val rot = CFrame.at(p.center) * CFrame.fromAxisAngle(p.handle.axis, Math.toRadians(deg.toDouble()).toFloat()) * CFrame.at(-p.center)
                        p.parts.forEachIndexed { i, part -> part.set("CFrame", (rot * p.startCf[i]).orthonormalize()) }
                        movePivots(p.pivots) { (rot * it).orthonormalize() }
                    }
                    else -> {}
                }
            }
        }

        private fun finishDrag(p: Drag) {
            synchronized(studio.lock) {
                studio.undo.begin(when (p.tool) { Tool.MOVE -> "Move"; Tool.SCALE -> "Resize"; else -> "Rotate" })
                p.parts.forEachIndexed { i, part ->
                    val cf = PartData.cframe(part)
                    if (cf != p.startCf[i]) studio.undo.record(UndoManager.Op.Prop(part, "CFrame", p.startCf[i], cf, true))
                    val key = if (part.props.containsKey("size")) "size" else "Size"
                    val s = PartData.size(part)
                    if (s != p.startSize[i]) studio.undo.record(UndoManager.Op.Prop(part, key, p.startSize[i], s, true))
                }
                recordPivots(p.pivots)
                studio.undo.commit()
                studio.markDirty()
            }
        }

        // ------------------------------------------------------------- script Handles / ArcHandles

        private fun runtimeFor(a: Instance): com.leaf.lemonade.lua.LuaRuntime? {
            val root = a.root() ?: return null
            val play = studio.play
            return when {
                play != null && root === play.dm -> play.rt
                root === studio.editModel -> studio.editRuntime
                else -> null
            }
        }

        private fun tryStartAdorn(id: Int, x: Float, y: Float): AdornDrag? = synchronized(studio.lock) {
            var best: Pair<Instance, AdornHandles.Tip>? = null
            var bd = 34f * d
            for (a in studio.renderAdornments()) {
                if (!AdornHandles.isHandles(a) || a.parent == null) continue
                for (t in AdornHandles.tips(a, studio.view)) {
                    val s = studio.view.project(t.tip) ?: continue
                    val dd = hypot(s[0] - x, s[1] - y)
                    if (dd < bd) { bd = dd; best = a to t }
                }
            }
            val (a, t) = best ?: return null
            val rt = runtimeFor(a) ?: return null
            val lt = com.leaf.lemonade.lua.LuaTypes
            val arg = if (a.className == "Handles") lt.wrap(com.leaf.lemonade.lua.EnumItemV("NormalId", AdornHandles.NORMAL_NAMES[t.id], t.id))
            else lt.wrap(com.leaf.lemonade.lua.EnumItemV("Axis", AdornHandles.AXIS_NAMES[t.id], t.id))
            rt.fire(a, "MouseButton1Down", arg)
            AdornDrag(id, x, y, a, t, if (a.className == "Handles") axisParam(t.base, t.axis, x, y) else null, rt, arg)
        }

        private fun dragAdorn(p: AdornDrag) {
            synchronized(studio.lock) {
                if (p.adorn.className == "Handles") {
                    val t = axisParam(p.tip.base, p.tip.axis, p.x, p.y)
                    val dist = if (t != null && p.axis0 != null) t - p.axis0
                    else {
                        val c0 = studio.view.project(p.tip.base) ?: return
                        val c1 = studio.view.project(p.tip.base + p.tip.axis) ?: return
                        val sx = c1[0] - c0[0]; val sy = c1[1] - c0[1]; val len = hypot(sx, sy).coerceAtLeast(0.5f)
                        ((p.x - p.x0) * sx + (p.y - p.y0) * sy) / (len * len)
                    }
                    p.rt.fire(p.adorn, "MouseDrag", p.arg, org.luaj.vm2.LuaValue.valueOf(dist.toDouble()))
                } else {
                    // Rotation: the angle the finger swept around the handle's center on screen.
                    val c = studio.view.project(p.tip.base) ?: return
                    val a0 = kotlin.math.atan2(p.y0 - c[1], p.x0 - c[0]); val a1 = kotlin.math.atan2(p.y - c[1], p.x - c[0])
                    var delta = a1 - a0
                    while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
                    while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
                    val sign = if (p.tip.axis.dot(studio.camera.forward) > 0f) 1f else -1f
                    p.rt.fire(p.adorn, "MouseDrag", p.arg, org.luaj.vm2.LuaValue.valueOf((delta * sign).toDouble()), org.luaj.vm2.LuaValue.valueOf(0.0))
                }
            }
        }

        // ------------------------------------------------------------- dragging parts directly

        private fun tryStartPartDrag(id: Int, x: Float, y: Float): PartDrag? {
            if (draggingParts) return null
            if (studio.tool == Tool.ROTATE && Handles.compute(studio) == null) return null
            synchronized(studio.lock) {
                val (o, dir) = studio.view.screenRay(x, y)
                val sel = studio.selection
                fun inSelection(p: Instance) = sel.any { it === p } || sel.any { s -> !s.isService && p.isDescendantOf(s) }
                // Locked parts inside a selected model can be grabbed too (they move with it).
                val hit = Geometry.raycast(studio.renderParts(), o, dir, 5000f) { it["Locked"] != true || inSelection(it) } ?: return null
                if (!inSelection(hit.part)) return null
                val parts = draggableParts()
                if (parts.isEmpty()) return null
                val (a, b) = Geometry.boundsOf(parts) ?: return null
                return PartDrag(id, x, y, parts, parts.map { PartData.cframe(it) }, hit.position, (a + b) / 2f, (b - a) / 2f)
            }
        }

        private fun updatePartDrag(p: PartDrag) {
            synchronized(studio.lock) {
                val (o, dir) = studio.view.screenRay(p.x, p.y)
                val dragged = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Instance, Boolean>()).also { s -> p.parts.forEach { s.add(it) } }
                val hit = Geometry.raycast(studio.renderParts(), o, dir, 5000f) { it !in dragged && PartData.transparency(it) < 0.99f }
                val grabOff = p.center - p.grab
                val target: Vector3 = if (hit != null) {
                    // Rest the selection on the surface under the finger (like Studio's dragger).
                    val n = hit.normal
                    val ax = n.abs()
                    val axis = if (ax.y >= ax.x && ax.y >= ax.z) 1 else if (ax.x >= ax.z) 0 else 2
                    val sign = if (n[axis] >= 0f) 1f else -1f
                    val offPlane = Vector3(if (axis == 0) 0f else grabOff.x, if (axis == 1) 0f else grabOff.y, if (axis == 2) 0f else grabOff.z)
                    val lift = Vector3(if (axis == 0) sign * p.half.x else 0f, if (axis == 1) sign * p.half.y else 0f, if (axis == 2) sign * p.half.z else 0f)
                    var c = hit.position + offPlane + lift
                    // Snap the in-plane coordinates to the grid.
                    if (studio.snapEnabled && studio.moveSnap > 0f) {
                        val s = studio.moveSnap
                        fun sn(v: Float, h: Float) = snap(v - h, s) + h
                        c = Vector3(if (axis == 0) c.x else sn(c.x, p.half.x), if (axis == 1) c.y else sn(c.y, p.half.y), if (axis == 2) c.z else sn(c.z, p.half.z))
                    }
                    c
                } else {
                    // Nothing under the finger: slide on the horizontal plane through the grab point.
                    if (abs(dir.y) < 1e-4f) return
                    val tt = (p.grab.y - o.y) / dir.y
                    if (tt < 0f) return
                    val hp = o + dir * tt
                    var c = hp + grabOff
                    if (studio.snapEnabled && studio.moveSnap > 0f) c = Vector3(snap(c.x, studio.moveSnap), c.y, snap(c.z, studio.moveSnap))
                    c
                }
                val delta = target - p.center
                p.parts.forEachIndexed { i, part -> part.set("CFrame", p.startCf[i] + delta) }
                movePivots(p.pivots) { it + delta }
            }
        }

        private fun finishPartDrag(p: PartDrag) {
            synchronized(studio.lock) {
                studio.undo.begin("Drag")
                p.parts.forEachIndexed { i, part ->
                    val cf = PartData.cframe(part)
                    if (cf != p.startCf[i]) studio.undo.record(UndoManager.Op.Prop(part, "CFrame", p.startCf[i], cf, true))
                }
                recordPivots(p.pivots)
                studio.undo.commit()
                studio.markDirty()
            }
        }
    }
}
