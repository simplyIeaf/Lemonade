package com.leaf.lemonade.render

import android.opengl.GLES20
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.model.Color3
import com.leaf.lemonade.model.Instance

/**
 * Roblox's post-processing effects (in Lighting or the Camera): BloomEffect, BlurEffect,
 * ColorCorrectionEffect and SunRaysEffect. When any is enabled, the scene is drawn into an
 * offscreen buffer and composited with the effects; otherwise nothing changes (no cost).
 */
class PostFx {
    class Params(
        var bloom: Float = 0f, var bloomSize: Float = 24f, var bloomThreshold: Float = 2f,
        var blur: Float = 0f,
        var brightness: Float = 0f, var contrast: Float = 0f, var saturation: Float = 0f, var tint: Color3 = Color3(1f, 1f, 1f),
        var rays: Float = 0f, var raysSpread: Float = 1f,
    ) {
        val any get() = bloom > 0f || blur > 0f || rays > 0f || brightness != 0f || contrast != 0f || saturation != 0f || tint.r < 0.999f || tint.g < 0.999f || tint.b < 0.999f
        val needsBright get() = bloom > 0f || rays > 0f || blur > 0f
    }

    private var w = 0; private var h = 0
    private var sceneFbo = 0; private var sceneTex = 0; private var sceneDepth = 0
    private val smallFbo = IntArray(2); private val smallTex = IntArray(2)
    private var sw = 0; private var sh = 0
    private var brightProg = 0; private var blurProg = 0; private var compProg = 0
    private var quad = 0
    @Volatile var failed = false
    val params = Params()
    var active = false; private set

    /** Reads the enabled effects from Lighting and the Camera (later ones add up, like Roblox). */
    fun read(lighting: Instance?, camera: Instance?) {
        val p = params
        p.bloom = 0f; p.blur = 0f; p.rays = 0f; p.brightness = 0f; p.contrast = 0f; p.saturation = 0f; p.tint = Color3(1f, 1f, 1f)
        fun num(i: Instance, k: String, d: Float) = (i[k] as? Number)?.toFloat() ?: d
        for (host in listOfNotNull(lighting, camera)) for (e in host.children) {
            if (e["Enabled"] == false) continue
            when (e.className) {
                "BloomEffect" -> { p.bloom = maxOf(p.bloom, num(e, "Intensity", 1f)); p.bloomSize = num(e, "Size", 24f); p.bloomThreshold = num(e, "Threshold", 2f) }
                "BlurEffect" -> p.blur = maxOf(p.blur, num(e, "Size", 24f))
                "ColorCorrectionEffect" -> {
                    p.brightness += num(e, "Brightness", 0f); p.contrast += num(e, "Contrast", 0f); p.saturation += num(e, "Saturation", 0f)
                    val t = e["TintColor"] as? Color3 ?: Color3(1f, 1f, 1f)
                    p.tint = Color3(p.tint.r * t.r, p.tint.g * t.g, p.tint.b * t.b)
                }
                "SunRaysEffect" -> { p.rays = maxOf(p.rays, num(e, "Intensity", 0.25f)); p.raysSpread = num(e, "Spread", 1f) }
            }
        }
    }

    /** Starts drawing the frame offscreen if effects are on (call before clearing). */
    fun begin(width: Int, height: Int, enabled: Boolean): Boolean {
        active = enabled && !failed && params.any && width > 0 && height > 0
        if (!active) return false
        if (width != w || height != h || sceneFbo == 0) { release(); if (!create(width, height)) { failed = true; active = false; return false } }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo)
        GLES20.glViewport(0, 0, w, h)
        return true
    }

    /**
     * Applies the effects and draws the result to the screen. [sunX]/[sunY] is the sun on screen
     * (0..1, y up) and [sunVisible] how much it faces the camera (rays fade out behind you).
     */
    fun finish(sunX: Float, sunY: Float, sunVisible: Float) {
        if (!active) return
        val p = params
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (p.needsBright) {
            // Bright pass at quarter size, then a two-pass blur.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo[0])
            GLES20.glViewport(0, 0, sw, sh)
            GLES20.glUseProgram(brightProg)
            bindTex(0, sceneTex)
            GLES20.glUniform1i(loc(brightProg, "uTex"), 0)
            // Roblox's Threshold is in HDR units (2 = only very bright); our colors are 0..1.
            val th = if (p.bloom > 0f) (0.45f + (p.bloomThreshold - 1f) * 0.18f).coerceIn(0.2f, 0.95f) else 0.82f
            GLES20.glUniform1f(loc(brightProg, "uThreshold"), if (p.blur > 0f && p.bloom <= 0f && p.rays <= 0f) -1f else th)
            drawQuad(brightProg)
            val radius = if (p.blur > 0f && p.bloom <= 0f) (p.blur / 12f).coerceIn(0.5f, 4f) else (p.bloomSize / 24f).coerceIn(0.4f, 3f)
            for (pass in 0 until 2) {
                blurPass(0, 1, 1f / sw * radius, 0f)
                blurPass(1, 0, 0f, 1f / sh * radius)
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(compProg)
        bindTex(0, sceneTex); bindTex(1, smallTex[0])
        GLES20.glUniform1i(loc(compProg, "uScene"), 0)
        GLES20.glUniform1i(loc(compProg, "uBright"), 1)
        GLES20.glUniform1f(loc(compProg, "uBloom"), (p.bloom * 0.6f).coerceIn(0f, 3f))
        GLES20.glUniform1f(loc(compProg, "uBlur"), (p.blur / 20f).coerceIn(0f, 1f))
        GLES20.glUniform4f(loc(compProg, "uCC"), p.brightness, p.contrast, p.saturation, 0f)
        GLES20.glUniform3f(loc(compProg, "uTint"), p.tint.r, p.tint.g, p.tint.b)
        GLES20.glUniform4f(loc(compProg, "uRays"), sunX, sunY, (p.rays * 2.2f * sunVisible).coerceIn(0f, 2f), p.raysSpread.coerceIn(0.05f, 1f))
        drawQuad(compProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun blurPass(src: Int, dst: Int, dx: Float, dy: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo[dst])
        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glUseProgram(blurProg)
        bindTex(0, smallTex[src])
        GLES20.glUniform1i(loc(blurProg, "uTex"), 0)
        GLES20.glUniform2f(loc(blurProg, "uDir"), dx, dy)
        drawQuad(blurProg)
    }

    private val locs = HashMap<String, Int>()
    private fun loc(p: Int, n: String) = locs.getOrPut("$p:$n") { GLES20.glGetUniformLocation(p, n) }

    private fun bindTex(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    }

    private fun drawQuad(prog: Int) {
        val a = GLES20.glGetAttribLocation(prog, "aPos")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quad)
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    private fun texture(width: Int, height: Int): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun create(width: Int, height: Int): Boolean {
        w = width; h = height
        sw = (w / 4).coerceAtLeast(16); sh = (h / 4).coerceAtLeast(16)
        val f = IntArray(1)
        sceneTex = texture(w, h)
        GLES20.glGenRenderbuffers(1, f, 0); sceneDepth = f[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, sceneDepth)
        // 24-bit depth where available (16-bit z-fights on big maps).
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        val depth24 = com.leaf.lemonade.ui.ViewportView.glesVersion >= 3 || ext.contains("GL_OES_depth24")
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, if (depth24) 0x81A6 else GLES20.GL_DEPTH_COMPONENT16, w, h)
        GLES20.glGenFramebuffers(1, f, 0); sceneFbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, sceneTex, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, sceneDepth)
        var ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        if (!ok && depth24) {
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, sceneDepth)
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
            ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        }
        for (i in 0..1) {
            smallTex[i] = texture(sw, sh)
            GLES20.glGenFramebuffers(1, f, 0); smallFbo[i] = f[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, smallTex[i], 0)
            ok = ok && GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (brightProg == 0) {
            brightProg = build(VS, BRIGHT_FS); blurProg = build(VS, BLUR_FS); compProg = build(VS, COMP_FS)
            val b = java.nio.ByteBuffer.allocateDirect(48).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            b.put(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)).position(0)
            GLES20.glGenBuffers(1, f, 0); quad = f[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quad)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 48, b, GLES20.GL_STATIC_DRAW)
        }
        if (!ok) OutputLog.warn("Post effects (bloom, blur, color correction, sun rays) aren't supported on this device's GPU")
        return ok && brightProg != 0 && blurProg != 0 && compProg != 0
    }

    private fun build(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int { val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { OutputLog.warn("Post effect shader: " + GLES20.glGetProgramInfoLog(p)); return 0 }
        return p
    }

    fun release() {
        if (sceneFbo != 0) {
            GLES20.glDeleteFramebuffers(3, intArrayOf(sceneFbo, smallFbo[0], smallFbo[1]), 0)
            GLES20.glDeleteTextures(3, intArrayOf(sceneTex, smallTex[0], smallTex[1]), 0)
            GLES20.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
        }
        sceneFbo = 0; sceneTex = 0; sceneDepth = 0; w = 0; h = 0
    }

    /** A new GL context: everything is already gone. */
    fun reset() {
        sceneFbo = 0; sceneTex = 0; sceneDepth = 0; w = 0; h = 0
        brightProg = 0; blurProg = 0; compProg = 0; quad = 0; locs.clear(); failed = false
    }

    companion object {
        private const val VS = """
attribute vec2 aPos; varying vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }"""
        private const val BRIGHT_FS = """
precision mediump float;
varying vec2 vUv; uniform sampler2D uTex; uniform float uThreshold;
void main() {
  vec3 c = texture2D(uTex, vUv).rgb;
  if (uThreshold < 0.0) { gl_FragColor = vec4(c, 1.0); return; }
  // Half brightest channel, half luminance: saturated colours (neon) glow without every red part blooming.
  float l = mix(dot(c, vec3(0.299, 0.587, 0.114)), max(c.r, max(c.g, c.b)), 0.5);
  float k = smoothstep(uThreshold, uThreshold + 0.25, l);
  gl_FragColor = vec4(c * k, 1.0);
}"""
        private const val BLUR_FS = """
precision mediump float;
varying vec2 vUv; uniform sampler2D uTex; uniform vec2 uDir;
void main() {
  vec3 c = texture2D(uTex, vUv).rgb * 0.227;
  c += texture2D(uTex, vUv + uDir * 1.385).rgb * 0.316;
  c += texture2D(uTex, vUv - uDir * 1.385).rgb * 0.316;
  c += texture2D(uTex, vUv + uDir * 3.231).rgb * 0.070;
  c += texture2D(uTex, vUv - uDir * 3.231).rgb * 0.070;
  gl_FragColor = vec4(c, 1.0);
}"""
        private const val COMP_FS = """
precision mediump float;
varying vec2 vUv; uniform sampler2D uScene; uniform sampler2D uBright;
uniform float uBloom; uniform float uBlur; uniform vec4 uCC; uniform vec3 uTint; uniform vec4 uRays;
void main() {
  vec3 c = texture2D(uScene, vUv).rgb;
  vec3 soft = texture2D(uBright, vUv).rgb;
  // BlurEffect: blend toward the blurred copy.
  if (uBlur > 0.0) c = mix(c, soft, uBlur);
  // Screen blend: glow brightens without blowing large neon areas out to yellow/white.
  else c = 1.0 - (1.0 - c) * (1.0 - clamp(soft * uBloom * 0.6, 0.0, 1.0));
  // SunRaysEffect: march toward the sun through the bright pass.
  if (uRays.z > 0.001) {
    vec2 d = (uRays.xy - vUv) * (uRays.w / 16.0);
    vec2 p = vUv; float decay = 1.0; vec3 acc = vec3(0.0);
    for (int i = 0; i < 16; i++) { p += d; acc += texture2D(uBright, p).rgb * decay; decay *= 0.9; }
    c += acc * (uRays.z / 16.0);
  }
  // ColorCorrectionEffect.
  c += uCC.x;
  c = (c - 0.5) * (1.0 + uCC.y) + 0.5;
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(l), c, 1.0 + uCC.z);
  c *= uTint;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""
    }
}
