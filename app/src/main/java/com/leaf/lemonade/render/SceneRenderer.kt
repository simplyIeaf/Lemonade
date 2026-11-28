package com.leaf.lemonade.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.core.Tool
import com.leaf.lemonade.engine.Geometry
import com.leaf.lemonade.model.*
import com.leaf.lemonade.net.ImageLoader
import com.leaf.lemonade.net.AssetFetcher
import com.leaf.lemonade.net.RobloxApi
import com.leaf.lemonade.core.SolidModeling
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class Handle(val index: Int, val axis: Vector3, val base: Vector3, val tip: Vector3, val radius: Float)

class HandleSet(val tool: Tool, val center: Vector3, val handles: List<Handle>, val local: CFrame?)

object Handles {
    fun compute(studio: Studio): HandleSet? {
        if (studio.isPlaying || studio.tool == Tool.SELECT) return null
        val parts = studio.selectedParts()
        if (parts.isEmpty()) return null
        val single = parts.size == 1
        val useLocal = studio.tool != Tool.MOVE && single
        val cf: CFrame
        val half: Vector3
        if (useLocal) {
            cf = PartData.cframe(parts[0]).rotationOnly().withPosition(PartData.cframe(parts[0]).position)
            half = PartData.size(parts[0]) / 2f
        } else {
            val (a, b) = Geometry.boundsOf(parts) ?: return null
            cf = CFrame.at((a + b) / 2f)
            half = (b - a) / 2f
        }
        val center = cf.position
        val dist = studio.view.distTo(center).coerceAtLeast(1f)
        val gap = dist * 0.07f
        val r = dist * 0.02f
        val list = ArrayList<Handle>()
        for (i in 0 until 6) {
            val ax = i % 3
            val sign = if (i < 3) 1f else -1f
            val dir = cf.column(ax) * sign
            val base = center + dir * half[ax]
            list.add(Handle(i, dir, base, base + dir * gap, r))
        }
        return HandleSet(studio.tool, center, list, if (useLocal) cf else null)
    }

    fun color(i: Int): FloatArray = when (i % 3) {
        0 -> floatArrayOf(0.95f, 0.25f, 0.25f, 1f)
        1 -> floatArrayOf(0.3f, 0.9f, 0.3f, 1f)
        else -> floatArrayOf(0.25f, 0.5f, 1f, 1f)
    }
}

/**
 * Renders the place with a Roblox-like look: sky with sun and clouds, sun/ambient lighting from
 * the Lighting service, soft shadow maps, procedural material textures, studs, head meshes,
 * decals (including the classic face) and fog/atmosphere.
 */
class SceneRenderer(private val studio: Studio) : GLSurfaceView.Renderer {
    private var lit = 0
    private var line = 0
    private var depthProg = 0
    private var skyProg = 0
    private var decalProg = 0
    private lateinit var cube: Mesh
    private lateinit var sphere: Mesh
    private lateinit var cylinder: Mesh
    private lateinit var cylinderY: Mesh
    private lateinit var wedge: Mesh
    private lateinit var corner: Mesh
    private lateinit var head: Mesh
    private lateinit var headFace: Mesh
    /** R6 character torso and limbs: boxes with Roblox's slight bevel on every edge. */
    private lateinit var r6Torso: Mesh
    private lateinit var r6Limb: Mesh
    private lateinit var quad: Mesh
    private lateinit var screen: Mesh
    private lateinit var boxLines: Mesh
    private var lastNs = 0L
    @Volatile var fps = 0f
    private var fpsAcc = 0f
    private var fpsFrames = 0
    private var time = 0f
    var onFrame: (() -> Unit)? = null

    private val model = FloatArray(16)
    private val normal = FloatArray(9)

    // lighting state (computed per frame from the Lighting service)
    private var sunDir = Vector3(0.45f, 0.8f, 0.35f).unit
    private val zenith = FloatArray(3)
    private val horizon = FloatArray(3)
    private val sunColor = FloatArray(3)
    private val fogColor = FloatArray(3)
    private var fogStart = 0f
    private var fogEnd = 1e9f
    private var fogMode = 0f
    private var ambient = 0.55f
    private var exposure = 1f
    private var shadowsOn = true
    private var dayFactor = 1f

    // shadow map
    private var shadowFbo = 0
    private var shadowTex = 0
    private var shadowSize = 2048
    private var shadowOk = false
    private val lightVP = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)

    // textures for decals (key -> GL id)
    private val textures = HashMap<String, Int>()
    private var faceTex = 0

    /** Paints a SurfaceGui into a bitmap (set by the viewport; shares the screen GUI renderer). */
    var surfacePainter: ((Instance, Float, Float, Bitmap) -> Unit)? = null
    private class SurfaceTex(var tex: Int, var w: Int, var h: Int, var sig: Long, var checked: Float, var used: Float)
    private val surfaceTex = java.util.IdentityHashMap<Instance, SurfaceTex>()
    private var scratch: Bitmap? = null
    private val matCache = HashMap<Int, FloatArray>()

    // uniform locations
    private var uVP = 0; private var uModel = 0; private var uNrm = 0; private var uColor = 0; private var uLight = 0
    private var uCam = 0; private var uEmissive = 0; private var uGrid = 0; private var uSize = 0; private var uHorizon = 0
    private var uZenith = 0; private var uSun = 0; private var uAmbient = 0; private var uFogColor = 0; private var uFog = 0
    private var uMat = 0; private var uSurfA = 0; private var uSurfB = 0; private var uShadow = 0; private var uShadowOn = 0; private var uShadowTexel = 0
    private var uShadowVP = 0; private var uExposure = 0; private var uTime = 0; private var uTruss = 0
    private var aPos = 0; private var aNrm = 0
    private var lVP = 0; private var lModel = 0; private var lColor = 0; private var laPos = 0
    private var dVP = 0; private var dModel = 0; private var daPos = 0
    private var sInvVP = 0; private var sCam = 0; private var sSun = 0; private var sZenith = 0; private var sHorizon = 0
    private var sSunColor = 0; private var sTime = 0; private var sDay = 0; private var saPos = 0
    private var sCloud = 0; private var sCloudCol = 0
    private var cVP = 0; private var cModel = 0; private var cTex = 0; private var cTint = 0; private var cNormal = 0
    private var cLight = 0; private var cSun = 0; private var cAmbient = 0; private var cUv = 0; private var caPos = 0; private var caUv = 0
    private var decalMeshProg = 0
    private var dmVP = 0; private var dmModel = 0; private var dmDecal = 0; private var dmNrmM = 0; private var dmTex = 0; private var dmTint = 0
    private var dmNormal = 0; private var dmLight = 0; private var dmSun = 0; private var dmAmbient = 0; private var dmaPos = 0; private var dmaNrm = 0
    private var dmUv = 0
    private val decalMat = FloatArray(16)
    private val nrmMat = FloatArray(9)

    /** Uniform/attribute locations of one lit-shader variant (lean or full-material). */
    private class LitLocs(val prog: Int, val locs: IntArray)
    private var litBasic: LitLocs? = null
    private var litAdv: LitLocs? = null
    private var frames = 0
    private var activeLit: LitLocs? = null

    private fun litLocs(prog: Int): LitLocs {
        fun u(n: String) = GLES20.glGetUniformLocation(prog, n)
        return LitLocs(prog, intArrayOf(
            u("uVP"), u("uModel"), u("uNrm"), u("uColor"), u("uLight"), u("uCam"), u("uEmissive"), u("uGrid"), u("uSize"),
            u("uHorizon"), u("uZenith"), u("uSun"), u("uAmbient"), u("uFogColor"), u("uFog"), u("uMat"), u("uSurfA"), u("uSurfB"),
            u("uShadow"), u("uShadowOn"), u("uShadowTexel"), u("uShadowVP"), u("uExposure"), u("uTime"), u("uTruss"),
            GLES20.glGetAttribLocation(prog, "aPos"), GLES20.glGetAttribLocation(prog, "aNrm"),
            u("uMatTex"), u("uMatNrm"), u("uTexScale"), u("uHasNrm"),
            u("uSurfTex"), u("uHasSurfTex"),
            GLES20.glGetAttribLocation(prog, "aUv"), GLES20.glGetAttribLocation(prog, "aCol"), GLES20.glGetAttribLocation(prog, "aMat"),
            GLES20.glGetAttribLocation(prog, "aTan"), GLES20.glGetAttribLocation(prog, "aBit"),
        ))
    }

    /** Makes [l] current and points the u* fields at its locations. */
    private fun useLit(l: LitLocs) {
        activeLit = l
        lit = l.prog
        GLES20.glUseProgram(l.prog)
        val a = l.locs
        uVP = a[0]; uModel = a[1]; uNrm = a[2]; uColor = a[3]; uLight = a[4]; uCam = a[5]; uEmissive = a[6]; uGrid = a[7]; uSize = a[8]
        uHorizon = a[9]; uZenith = a[10]; uSun = a[11]; uAmbient = a[12]; uFogColor = a[13]; uFog = a[14]; uMat = a[15]; uSurfA = a[16]; uSurfB = a[17]
        uShadow = a[18]; uShadowOn = a[19]; uShadowTexel = a[20]; uShadowVP = a[21]; uExposure = a[22]; uTime = a[23]; uTruss = a[24]
        aPos = a[25]; aNrm = a[26]
        uMatTex = a[27]; uMatNrm = a[28]; uTexScale = a[29]; uHasNrm = a[30]
        uSurfTex = a[31]; uHasSurfTex = a[32]
        aUvB = a[33]; aCol = a[34]; aMatB = a[35]; aTan = a[36]; aBit = a[37]
    }

    private var uMatTex = -1; private var uMatNrm = -1; private var uTexScale = -1; private var uHasNrm = -1
    private var uSurfTex = -1; private var uHasSurfTex = -1
    private var aUvB = -1; private var aCol = -1; private var aMatB = -1; private var aTan = -1; private var aBit = -1
    private var litTex: LitLocs? = null

    // ------------------------------------------------------------------ static batches (big places)

    /** Batch variants of the lit shader: lean, procedural materials, material textures. */
    private val batchLit = arrayOfNulls<LitLocs>(3)

    private fun batchProgram(kind: Int): LitLocs {
        batchLit[kind]?.let { return it }
        val def = "#define BATCH 1\n" + when (kind) { 1 -> "#define ADVANCED 1\n"; 2 -> "#define TEXTURED 1\n"; else -> "" }
        return litLocs(program(def + LIT_VS, def + LIT_FS)).also { batchLit[kind] = it }
    }

    private val frustum = Frustum()
    /** Smooth terrain chunks (Workspace.Terrain). */
    private val terrainView = TerrainView()
    /** Lighting post effects (bloom, blur, color correction, sun rays). */
    private val post = PostFx()
    /** Converts a part's radius into how far away it stays drawn (focal px * quality / 1.5 px). */
    private var detailScale = 400f
    /** Render quality (draw distance of small parts); adapts to the frame rate when Automatic. */
    @Volatile var quality = 1f; private set
    private var qualityTimer = 0f
    private var lastModel: Any? = null
    /** Closest thing drawn last frame (sets the near plane). */
    private var closest = 1f
    private var nearPlane = 0.2f
    /** The near plane moves in fixed steps (0.1, 0.2, 0.4 ... 12.8): a continuously changing one makes coplanar surfaces shimmer. */
    private var nearLevel = 1
    private var nearRaise = 0

    private val batches = StaticBatches(object : StaticBatches.Host {
        override fun batchable(p: Instance): Boolean = isBatchable(p)
        override fun spec(p: Instance): List<BatchSpec>? = batchSpec(p)
        override fun loadSignature(): Int = MaterialTextures.epoch * 31 + MeshLoader.epoch * 17 + UnionMeshes.epoch * 13 + AssetFetcher.imageEpoch * 7 + ImageLoader.epoch
    })

    private val BATCH_CLASSES = setOf("Part", "WedgePart", "CornerWedgePart", "SpawnLocation", "Seat", "VehicleSeat", "Platform", "SkateboardPlatform",
        "MeshPart", "UnionOperation", "IntersectOperation")
    private val SURFACES = arrayOf("TopSurface", "BottomSurface", "FrontSurface", "BackSurface", "LeftSurface", "RightSurface")

    /** Opaque parts that don't move; glass, force fields, hinges/motors and characters are drawn on their own. */
    private fun isBatchable(p: Instance): Boolean {
        if (p.className !in BATCH_CLASSES) return false
        if (PartData.transparency(p) > 0.001f || p["CastShadow"] == false) return false
        val mat = PartData.material(p)
        if (mat == 1568 || mat == 1584) return false
        // Hinge, Motor and SteppingMotor draw a joint marker (SmoothNoOutlines/Unjoinable are fine).
        for (s in SURFACES) if (((p[s] as? EnumValue)?.value ?: 0) in 6..8) return false
        // Characters move and hide parts in first person.
        var a = p.parent
        while (a != null && a !is DataModel) { if (a.className == "Model" && a.findFirstChildOfClass("Humanoid") != null) return false; a = a.parent }
        return true
    }

    private fun spec(p: Instance, data: FloatArray, stride: Int, isCube: Boolean, g: Geo, color: Color3, pending: Boolean): BatchSpec {
        val mat = PartData.material(p)
        val mp = material(mat)
        val params = floatArrayOf(mp[0], mp[1], mp[2], mp[3] + ((p["Reflectance"] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f))
        val tb = texturesFor(p, mat)
        val kind = if (tb != null) 2 else if (needsAdvanced(mat)) 1 else 0
        return BatchSpec(data, isCube, g.cf, g.scale, color.r, color.g, color.b, mat == 288, params,
            if (isCube) Vector3(surf(p, "RightSurface"), surf(p, "TopSurface"), surf(p, "BackSurface")) else null,
            if (isCube) Vector3(surf(p, "LeftSurface"), surf(p, "BottomSurface"), surf(p, "FrontSurface")) else null,
            kind, tb, pending || (tb == null && MaterialTextures.hasTexture(mat)), stride)
    }

    private fun batchSpec(p: Instance): List<BatchSpec>? {
        if (variantFor(p) != null) return null
        val g = geometry(p)
        val color = PartData.color(p)
        when (g.kind) {
            2 -> {
                // A file mesh: its own texture (or none), drawn like drawFileMesh.
                if (g.tint != null && (g.tint.x != 1f || g.tint.y != 1f || g.tint.z != 1f)) return null
                val id = AssetFetcher.idOf(if (p.className == "MeshPart") PartData.meshContent(p) else p.children.firstOrNull { it.className == "SpecialMesh" || it.className == "FileMesh" }?.let { PartData.meshContent(it) }) ?: return null
                val data = MeshLoader.get(id) ?: return null
                val tex = g.tex?.let { assetTexture(it, true) } ?: 0
                val pending = g.tex != null && tex == 0
                return listOf(BatchSpec(data.verts, false, g.cf, g.scale, color.r, color.g, color.b, PartData.material(p) == 288, FLOAT4_ZERO,
                    null, null, 3, TexBind(tex, 0, 0f), pending, 8))
            }
            3 -> {
                // A union: one piece per source color, in stud units (like drawPart's subs).
                val usePart = p["UsePartColor"] == true
                val r = SolidModeling.result(p)
                if (r != null) return r.tris.filter { it.value.isNotEmpty() }.map { (style, arr) ->
                    spec(p, arr, 8, false, g, if (usePart) color else r.styles.getOrNull(style)?.color ?: color, false)
                }
                val d = UnionMeshes.get(p) ?: return null
                return d.groups.map { (argb, arr) -> spec(p, arr, 8, false, g, if (usePart) color else Color3.fromRGB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF), false) }
            }
            0 -> {
                val m = g.mesh
                if (m !== cube && m !== wedge && m !== corner && m !== cylinder && m !== cylinderY) return null
                val data = m.cpu ?: return null
                // A MeshPart or union still loading is drawn as its box until it arrives.
                val loading = when {
                    p.className == "MeshPart" -> AssetFetcher.idOf(PartData.meshContent(p))?.let { !MeshLoader.isBad(it) } ?: false
                    p.isA("PartOperation") -> true
                    else -> false
                }
                return listOf(spec(p, data, 6, m === cube && p.className != "MeshPart" && !p.isA("PartOperation"), g, color, loading))
            }
            else -> return null
        }
    }

    private val FLOAT4_ZERO = floatArrayOf(0f, 0f, 1f, 0f)

    // Mesh batch shader (file meshes): uniform/attribute locations by name.
    private var meshBatchProg = 0
    private val mb = HashMap<String, Int>()

    private fun meshBatchProgram(): Int {
        if (meshBatchProg != 0) return meshBatchProg
        val p = program("#define BATCH 1\n" + MESH_VS, "#define BATCH 1\n" + MESH_FS)
        for (n in listOf("uVP", "uShadowVP", "uLight", "uSun", "uAmbient", "uCam", "uFogColor", "uFog", "uExposure", "uShadowOn", "uShadowTexel", "uHorizon", "uZenith", "uShadow", "uTex", "uHasTex", "uDetail"))
            mb[n] = GLES20.glGetUniformLocation(p, n)
        for (n in listOf("aPos", "aNrm", "aUv", "aCol")) mb[n] = GLES20.glGetAttribLocation(p, n)
        meshBatchProg = p
        return p
    }

    private fun useMeshBatch() {
        GLES20.glUseProgram(meshBatchProgram())
        activeLit = null
        fun u(n: String) = mb[n] ?: -1
        GLES20.glUniformMatrix4fv(u("uVP"), 1, false, studio.view.vp, 0)
        GLES20.glUniformMatrix4fv(u("uShadowVP"), 1, false, lightVP, 0)
        GLES20.glUniform3f(u("uLight"), sunDir.x, sunDir.y, sunDir.z)
        GLES20.glUniform3fv(u("uSun"), 1, sunColor, 0)
        GLES20.glUniform1f(u("uAmbient"), ambient)
        val cp = studio.view.camPos
        GLES20.glUniform3f(u("uCam"), cp.x, cp.y, cp.z)
        GLES20.glUniform3fv(u("uFogColor"), 1, fogColor, 0)
        GLES20.glUniform3f(u("uFog"), fogStart, fogEnd, fogMode)
        GLES20.glUniform1f(u("uExposure"), exposure)
        GLES20.glUniform1f(u("uShadowOn"), if (frameShadows) 1f else 0f)
        GLES20.glUniform2f(u("uShadowTexel"), shadowTexel(), shadowTexel())
        GLES20.glUniform3fv(u("uHorizon"), 1, horizon, 0)
        GLES20.glUniform3fv(u("uZenith"), 1, zenith, 0)
        GLES20.glUniform1i(u("uShadow"), 0)
        GLES20.glUniform1i(u("uTex"), 1)
        GLES20.glUniform1f(u("uDetail"), 0f)
        applyLights(meshBatchProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
    }

    private fun drawMeshBatch(b: StaticBatches.Batch) {
        val st = StaticBatches.MESH_STRIDE_BYTES
        fun a(n: String) = mb[n] ?: -1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex?.color ?: 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(mb["uHasTex"] ?: -1, if ((b.tex?.color ?: 0) != 0) 1f else 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b.vbo)
        GLES20.glEnableVertexAttribArray(a("aPos")); GLES20.glVertexAttribPointer(a("aPos"), 3, GLES20.GL_FLOAT, false, st, 0)
        if (a("aUv") >= 0) { GLES20.glEnableVertexAttribArray(a("aUv")); GLES20.glVertexAttribPointer(a("aUv"), 2, GLES20.GL_FLOAT, false, st, 12) }
        if (a("aNrm") >= 0) { GLES20.glEnableVertexAttribArray(a("aNrm")); GLES20.glVertexAttribPointer(a("aNrm"), 4, GLES20.GL_BYTE, true, st, 20) }
        if (a("aCol") >= 0) { GLES20.glEnableVertexAttribArray(a("aCol")); GLES20.glVertexAttribPointer(a("aCol"), 4, GLES20.GL_UNSIGNED_BYTE, true, st, 24) }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, b.count)
    }

    /** Solid terrain: world-space chunk meshes through the mesh-batch shader (lit, shadowed, fogged). */
    private fun drawTerrain() {
        val list = terrainView.visible
        if (list.isEmpty()) return
        useMeshBatch()
        val st = StaticBatches.MESH_STRIDE_BYTES
        fun a(n: String) = mb[n] ?: -1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(mb["uHasTex"] ?: -1, 0f)
        // Surface detail (Settings → Terrain detail can turn it off on slow phones).
        GLES20.glUniform1f(mb["uDetail"] ?: -1, if (com.leaf.lemonade.core.Settings.terrainDetail) 1f else 0f)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        val cp = studio.view.camPos
        for (t in list) {
            if (t.count == 0) continue
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, t.vbo)
            GLES20.glEnableVertexAttribArray(a("aPos")); GLES20.glVertexAttribPointer(a("aPos"), 3, GLES20.GL_FLOAT, false, st, 0)
            if (a("aUv") >= 0) { GLES20.glEnableVertexAttribArray(a("aUv")); GLES20.glVertexAttribPointer(a("aUv"), 2, GLES20.GL_FLOAT, false, st, 12) }
            if (a("aNrm") >= 0) { GLES20.glEnableVertexAttribArray(a("aNrm")); GLES20.glVertexAttribPointer(a("aNrm"), 4, GLES20.GL_BYTE, true, st, 20) }
            if (a("aCol") >= 0) { GLES20.glEnableVertexAttribArray(a("aCol")); GLES20.glVertexAttribPointer(a("aCol"), 4, GLES20.GL_UNSIGNED_BYTE, true, st, 24) }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, t.count)
        }
        for (n in listOf("aUv", "aCol")) mb[n]?.let { if (it >= 0) GLES20.glDisableVertexAttribArray(it) }
        GLES20.glUniform1f(mb["uDetail"] ?: -1, 0f)
        activeLit = null
        val near = terrainView.closest(cp)
        if (near < closest) closest = near
    }

    private var waterProg = 0
    private val wl = HashMap<String, Int>()

    /** Terrain water: translucent, rippling, with a sky reflection at grazing angles (Terrain.Water* properties). */
    private fun drawWater() {
        val list = terrainView.visible.filter { it.wcount > 0 }
        if (list.isEmpty()) return
        if (waterProg == 0) {
            waterProg = program(WATER_VS, WATER_FS)
            wl.clear()
            for (n in listOf("uVP", "uColor", "uAlpha", "uLight", "uSun", "uAmbient", "uCam", "uTime", "uSky", "uFogColor", "uFog", "uWave", "uWaveP"))
                wl[n] = GLES20.glGetUniformLocation(waterProg, n)
            wl["aPos"] = GLES20.glGetAttribLocation(waterProg, "aPos"); wl["aNrm"] = GLES20.glGetAttribLocation(waterProg, "aNrm")
        }
        val t = terrainView.terrain
        val wc = t?.get("WaterColor") as? Color3 ?: Color3.fromRGB(12, 84, 92)
        val wt = ((t?.get("WaterTransparency") as? Number)?.toFloat() ?: 0.3f).coerceIn(0f, 1f)
        val wave = ((t?.get("WaterWaveSize") as? Number)?.toFloat() ?: 0.15f).coerceIn(0f, 1f)
        val speed = ((t?.get("WaterWaveSpeed") as? Number)?.toFloat() ?: 10f).coerceIn(0f, 100f)
        GLES20.glUseProgram(waterProg)
        activeLit = null
        fun u(n: String) = wl[n] ?: -1
        GLES20.glUniformMatrix4fv(u("uVP"), 1, false, studio.view.vp, 0)
        GLES20.glUniform3f(u("uColor"), wc.r, wc.g, wc.b)
        // Roblox water stays fairly opaque even at high transparency: 0.3 -> ~0.78.
        GLES20.glUniform1f(u("uAlpha"), (1f - wt * 0.75f).coerceIn(0.15f, 1f))
        GLES20.glUniform3f(u("uLight"), sunDir.x, sunDir.y, sunDir.z)
        GLES20.glUniform3fv(u("uSun"), 1, sunColor, 0)
        GLES20.glUniform1f(u("uAmbient"), ambient)
        val cp = studio.view.camPos
        GLES20.glUniform3f(u("uCam"), cp.x, cp.y, cp.z)
        GLES20.glUniform1f(u("uTime"), time * (speed / 10f))
        GLES20.glUniform3fv(u("uSky"), 1, zenith, 0)
        GLES20.glUniform3fv(u("uFogColor"), 1, fogColor, 0)
        GLES20.glUniform3f(u("uFog"), fogStart, fogEnd, fogMode)
        GLES20.glUniform1f(u("uWave"), wave)
        GLES20.glUniform4f(u("uWaveP"), 0.2f, time * speed * 0.1f, wave, 1f / 800f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        val pos = u("aPos"); val nrm = u("aNrm")
        GLES20.glEnableVertexAttribArray(pos)
        if (nrm >= 0) GLES20.glEnableVertexAttribArray(nrm)
        for (w in list) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, w.wvbo)
            GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 24, 0)
            if (nrm >= 0) GLES20.glVertexAttribPointer(nrm, 3, GLES20.GL_FLOAT, false, 24, 12)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, w.wcount)
        }
        if (nrm >= 0) GLES20.glDisableVertexAttribArray(nrm)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawBatch(b: StaticBatches.Batch) {
        val st = StaticBatches.STRIDE_BYTES
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b.vbo)
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, st, 0)
        if (aUvB >= 0) { GLES20.glEnableVertexAttribArray(aUvB); GLES20.glVertexAttribPointer(aUvB, 2, GLES20.GL_FLOAT, false, st, 12) }
        if (aNrm >= 0) { GLES20.glEnableVertexAttribArray(aNrm); GLES20.glVertexAttribPointer(aNrm, 4, GLES20.GL_BYTE, true, st, 20) }
        if (aCol >= 0) { GLES20.glEnableVertexAttribArray(aCol); GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, st, 24) }
        if (aMatB >= 0) { GLES20.glEnableVertexAttribArray(aMatB); GLES20.glVertexAttribPointer(aMatB, 4, GLES20.GL_UNSIGNED_BYTE, false, st, 28) }
        if (aTan >= 0) { GLES20.glEnableVertexAttribArray(aTan); GLES20.glVertexAttribPointer(aTan, 3, GLES20.GL_BYTE, true, st, 32) }
        if (aBit >= 0) { GLES20.glEnableVertexAttribArray(aBit); GLES20.glVertexAttribPointer(aBit, 3, GLES20.GL_BYTE, true, st, 36) }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, b.count)
    }

    /** Turns off the batch-only vertex arrays so later programs never read past a smaller buffer. */
    private fun endBatches() {
        for (l in batchLit) {
            val a = l?.locs ?: continue
            for (i in 33..37) if (a[i] >= 0) GLES20.glDisableVertexAttribArray(a[i])
        }
        for (n in listOf("aUv", "aCol")) mb[n]?.let { if (it >= 0) GLES20.glDisableVertexAttribArray(it) }
    }

    /** Draws the chunks in range, grouped by shader and textures. */
    private fun drawStaticBatches() {
        if (batches.visible.isEmpty()) return
        val list = ArrayList<StaticBatches.Batch>()
        for (c in batches.visible) for (b in c.batches) {
            if (c.dist > batches.tierRange(b.tier, detailScale)) continue
            list.add(b)
        }
        if (list.isEmpty()) return
        list.sortWith(compareBy<StaticBatches.Batch>({ it.kind }, { it.tex?.color ?: 0 }, { it.tex?.normal ?: 0 }))
        var kind = -1
        var bound: TexBind? = null
        for (b in list) {
            if (b.kind != kind) {
                if (kind == 3) GLES20.glEnable(GLES20.GL_CULL_FACE)
                kind = b.kind
                activeLit = null
                if (kind == 3) {
                    // File meshes: their own shader, two-sided like drawFileMesh.
                    useMeshBatch()
                    GLES20.glDisable(GLES20.GL_CULL_FACE)
                } else switchLit(batchProgram(kind))
                bound = null
            }
            if (kind == 3) { drawMeshBatch(b); continue }
            val tb = b.tex
            if (tb != null && tb != bound) { bindTextures(tb); bound = tb }
            drawBatch(b)
        }
        if (kind == 3) GLES20.glEnable(GLES20.GL_CULL_FACE)
        endBatches()
        activeLit = null
    }

    /** Per-frame batching work: which parts are batched, chunk (re)builds, and render quality. */
    private fun updateBatches(dt: Float, shadowAt: Vector3?, shadowR: Float) {
        val m = studio.model
        if (m !== lastModel) { lastModel = m; batches.reset(true) }
        val v = studio.view
        frustum.set(v.vp)
        // Pixels per stud at distance 1 (vertical), times quality, over the 1.5 px cut-off.
        val focal = v.height / (2f * kotlin.math.tan(Math.toRadians(studio.camera.fov / 2.0)).toFloat())
        val q = when (com.leaf.lemonade.core.Settings.renderDistance) {
            "Low" -> 0.5f; "Medium" -> 1f; "High" -> 1.8f; "Ultra" -> 3f
            else -> {
                // Automatic: back off when the frame rate drops, push further when there's headroom.
                qualityTimer += dt
                if (qualityTimer > 1.5f && frames > 60) {
                    qualityTimer = 0f
                    if (fps in 1f..24f) quality = (quality * 0.8f).coerceAtLeast(0.5f)
                    else if (fps > 45f) quality = (quality * 1.12f).coerceAtMost(2.2f)
                }
                quality
            }
        }
        detailScale = focal * q / 1.5f
        // Smooth terrain: chunks in range, meshed in the background.
        // Terrain is big and cheap per area, so it's drawn much farther than small parts (like Roblox).
        try { terrainView.update(studio.model, v.camPos, frustum, (1600f * q).coerceIn(900f, 3200f)) } catch (e: Exception) { OutputLog.warn("Terrain: ${e.message ?: e}") }
        // Static batches need the full shaders, which compile after the first second.
        // (A "debug_nobatch" file in the app's files dir turns batching off, for A/B timing.)
        if (frames < 31 || noBatch) { batches.loose.clear(); batches.loose.addAll(studio.renderParts()); batches.visible.clear(); return }
        val budgetMs = if (frames < 200) 10L else 5L
        val maxBytes = (160L shl 20)
        batches.update(studio.renderParts(), studio.renderListVersion, frames, v.camPos, frustum, detailScale, shadowAt, shadowR, budgetMs * 1_000_000L, maxBytes)
    }

    /** The textured (Roblox material textures) shader, compiled lazily like the full-material one. */
    private fun texturedLit(): LitLocs? {
        litTex?.let { return it }
        if (frames < 30) return null
        val p = program("#define TEXTURED 1\n" + LIT_VS, "#define TEXTURED 1\n" + LIT_FS)
        return litLocs(p).also { litTex = it }
    }

    private val matTexGpu = java.util.IdentityHashMap<MaterialTextures.MatTex, TexBind>()
    private var modernMaterialsNow = true

    private fun materialBind(mat: Int): TexBind? {
        if (!MaterialTextures.hasTexture(mat)) return null
        val t = MaterialTextures.get(mat, modernMaterialsNow) ?: return null
        return matTexGpu.getOrPut(t) { TexBind(t.color?.let { upload(it, true) } ?: whiteTex(), t.normal?.let { upload(it, true) } ?: 0, 1f / t.studsPerTile) }
    }

    /** Resolved MaterialVariant textures (cleared with the variant index, so edits show up). */
    private val variantBinds = java.util.IdentityHashMap<Instance, TexBind>()

    /** A part's textures: its MaterialVariant's maps when it has one, else its material's. */
    private fun texturesFor(p: Instance, mat: Int): TexBind? {
        val v = variantFor(p) ?: return materialBind(mat)
        variantBinds[v]?.let { return it }
        val colorContent = PartData.content(v, "ColorMap", "ColorMapContent").orEmpty()
        val normalContent = PartData.content(v, "NormalMap", "NormalMapContent").orEmpty()
        val spt = ((v["StudsPerTile"] as? Number)?.toFloat() ?: 10f).coerceAtLeast(0.05f)
        val base = materialBind(mat)
        val color = if (colorContent.isNotBlank()) assetTexture(colorContent, true) else 0
        val normal = if (normalContent.isNotBlank()) assetTexture(normalContent, true) else 0
        val stillLoading = (colorContent.isNotBlank() && color == 0) || (normalContent.isNotBlank() && normal == 0)
        val bind = when {
            color != 0 -> TexBind(color, normal, 1f / spt)
            colorContent.isBlank() && normal != 0 -> TexBind(base?.color ?: whiteTex(), normal, 1f / spt)
            else -> base
        }
        if (bind != null && !stillLoading) variantBinds[v] = bind
        return bind
    }

    private var whiteTexId = 0
    /** A neutral color map (grey 236: the textured shader's 1.08 gain brings it back to exactly 1). */
    private fun whiteTex(): Int {
        if (whiteTexId == 0) whiteTexId = upload(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFECECEC.toInt()) }, true)
        return whiteTexId
    }

    /** Roblox's surface atlas (studs, inlets, welds) on texture unit 3; 0 when not bundled. */
    private var surfTex = 0
    /** Max anisotropic filtering (0 when the GPU doesn't support it): keeps far, slanted textures sharp. */
    private var aniso = 0f

    private fun bindTextures(t: TexBind) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.color)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (t.normal != 0) t.normal else t.color)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(uTexScale, t.scale)
        GLES20.glUniform1f(uHasNrm, if (t.normal != 0) 1f else 0f)
    }

    /**
     * The full-material shader is big; it's compiled the first time a part needs it (and not during
     * the first second, so startup stays quick). Until then those parts use the lean shader.
     */
    private fun advancedLit(): LitLocs? {
        litAdv?.let { return it }
        if (frames < 30) return null
        val p = program(LIT_VS, "#define ADVANCED 1\n" + LIT_FS)
        return litLocs(p).also { litAdv = it }
    }

    private fun needsAdvanced(mat: Int) = material(mat)[0] > 1.5f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        litBasic = litLocs(program(LIT_VS, LIT_FS)).also { useLit(it) }
        litAdv = null
        litTex = null
        matTexGpu.clear()
        variantBinds.clear()
        whiteTexId = 0
        frames = 0
        line = program(LINE_VS, LINE_FS)
        depthProg = program(DEPTH_VS, DEPTH_FS)
        skyProg = program(SKY_VS, SKY_FS)
        decalProg = program(DECAL_VS, DECAL_FS)
        fun u(p: Int, n: String) = GLES20.glGetUniformLocation(p, n)
        lVP = u(line, "uVP"); lModel = u(line, "uModel"); lColor = u(line, "uColor"); laPos = GLES20.glGetAttribLocation(line, "aPos")
        dVP = u(depthProg, "uVP"); dModel = u(depthProg, "uModel"); daPos = GLES20.glGetAttribLocation(depthProg, "aPos")
        sInvVP = u(skyProg, "uInvVP"); sCam = u(skyProg, "uCam"); sSun = u(skyProg, "uSunDir"); sZenith = u(skyProg, "uZenith")
        sHorizon = u(skyProg, "uHorizon"); sSunColor = u(skyProg, "uSunColor"); sTime = u(skyProg, "uTime"); sDay = u(skyProg, "uDay")
        sCloud = u(skyProg, "uCloud"); sCloudCol = u(skyProg, "uCloudCol")
        saPos = GLES20.glGetAttribLocation(skyProg, "aPos")
        cVP = u(decalProg, "uVP"); cModel = u(decalProg, "uModel"); cTex = u(decalProg, "uTex"); cTint = u(decalProg, "uTint")
        cNormal = u(decalProg, "uNormal"); cLight = u(decalProg, "uLight"); cSun = u(decalProg, "uSun"); cAmbient = u(decalProg, "uAmbient")
        cUv = u(decalProg, "uUv"); caPos = GLES20.glGetAttribLocation(decalProg, "aPos"); caUv = GLES20.glGetAttribLocation(decalProg, "aUv")
        decalMeshProg = program(DECAL_MESH_VS, DECAL_MESH_FS)
        dmVP = u(decalMeshProg, "uVP"); dmModel = u(decalMeshProg, "uModel"); dmDecal = u(decalMeshProg, "uDecal"); dmNrmM = u(decalMeshProg, "uNrmM")
        dmTex = u(decalMeshProg, "uTex"); dmTint = u(decalMeshProg, "uTint"); dmNormal = u(decalMeshProg, "uNormal"); dmLight = u(decalMeshProg, "uLight")
        dmSun = u(decalMeshProg, "uSun"); dmAmbient = u(decalMeshProg, "uAmbient"); dmUv = u(decalMeshProg, "uUv")
        dmaPos = GLES20.glGetAttribLocation(decalMeshProg, "aPos"); dmaNrm = GLES20.glGetAttribLocation(decalMeshProg, "aNrm")

        cube = Meshes.cube(); sphere = Meshes.sphere(); cylinder = Meshes.cylinder(); cylinderY = Meshes.cylinderY()
        wedge = Meshes.wedge(); corner = Meshes.cornerWedge(); head = Meshes.head(); headFace = Meshes.headFace()
        r6Torso = Meshes.chamferBox(2f, 2f, 1f, R6_BEVEL); r6Limb = Meshes.chamferBox(1f, 2f, 1f, R6_BEVEL)
        terrainView.reset(false)
        waterProg = 0
        lightLocs.clear()
        post.reset()
        quad = Meshes.quad(); screen = Meshes.screenQuad(); boxLines = Meshes.boxLines()
        ringLines = null
        setupMeshProgram()
        setupSkybox()
        // A new GL context: buffers from the old one are gone.
        gpuMeshes.clear(); trussGpu.clear(); unionGpu.clear(); storedUnionGpu.clear(); meshFrame = -1
        batches.reset(false); batchLit.fill(null); shadowValid = false
        noBatch = java.io.File(studio.app.filesDir, "debug_nobatch").exists()
        textures.clear()
        surfaceTex.clear()
        android.util.Log.i("LemonadeGfx", "OpenGL ES context ${com.leaf.lemonade.ui.ViewportView.glesVersion}.x: ${GLES20.glGetString(GLES20.GL_VERSION)}")
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        aniso = if (ext.contains("GL_EXT_texture_filter_anisotropic")) {
            val f = FloatArray(1); GLES20.glGetFloatv(GL_MAX_ANISOTROPY, f, 0); if (f[0] > 1f) f[0].coerceAtMost(8f) else 0f
        } else 0f
        surfTex = MaterialTextures.surfaces()?.let { upload(it, true) } ?: 0
        gridTex = 0; spawnTex = 0
        faceTex = upload(faceBitmap(), false)
        setupShadowMap()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        lastNs = System.nanoTime()
    }

    private fun setupShadowMap() {
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        shadowSize = if (max[0] >= 2048) 2048 else 1024
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); shadowTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, shadowSize, shadowSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenRenderbuffers(1, ids, 0); val depth = ids[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth)
        val ext24 = (GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "").contains("GL_OES_depth24")
        val depth24 = com.leaf.lemonade.ui.ViewportView.glesVersion >= 3 || ext24
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, if (depth24) 0x81A6 else GLES20.GL_DEPTH_COMPONENT16, shadowSize, shadowSize)
        GLES20.glGenFramebuffers(1, ids, 0); shadowFbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, shadowTex, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depth)
        shadowOk = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        if (!shadowOk && depth24) {
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth)
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, shadowSize, shadowSize)
            shadowOk = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (!shadowOk) OutputLog.warn("Shadows are not supported on this device's GPU")
    }

    private var surfW = 1
    private var surfH = 1

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfW = width; surfH = height
        GLES20.glViewport(0, 0, width, height)
        synchronized(studio.lock) { studio.view.width = width; studio.view.height = height }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        time += dt
        fpsAcc += dt; fpsFrames++
        if (fpsAcc >= 0.5f) { fps = fpsFrames / fpsAcc; fpsAcc = 0f; fpsFrames = 0 }

        synchronized(studio.lock) {
            try {
                studio.frame(dt)
            } catch (e: Exception) {
                OutputLog.error("Simulation error: $e")
            }
            val cam = studio.camera
            studio.view.update(cam.pos, cam.pos + cam.forward, cam.fov, studio.view.width, studio.view.height, nearPlane)
            updateLighting()
            val shadows = shadowsOn && shadowOk && studio.shadowsEnabled && dayFactor > 0.05f
            val focus = if (shadows) shadowFocus() else null
            val t0 = System.nanoTime()
            try {
                updateBatches(dt, focus?.first, (focus?.second ?: 0f) * 1.6f)
            } catch (e: Exception) {
                // Never lose the picture over batching: draw everything one by one this frame.
                OutputLog.warn("Renderer: ${e.message ?: e}")
                batches.reset(true)
                batches.loose.addAll(studio.renderParts())
            }
            val t1 = System.nanoTime()
            closest = Float.MAX_VALUE
            if (focus != null) renderShadowMap(focus.first, focus.second)
            val t2 = System.nanoTime()
            // Bloom / Blur / ColorCorrection / SunRays effects: draw offscreen when any is on.
            post.read(studio.model.findService("Lighting"), studio.model.workspace["CurrentCamera"] as? Instance)
            val postOn = try { post.begin(surfW, surfH, com.leaf.lemonade.core.Settings.postEffects) } catch (e: Exception) { post.failed = true; false }
            if (!postOn) GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfW, surfH)
            GLES20.glClearColor(horizon[0], horizon[1], horizon[2], 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            drawSky()
            drawParts(shadows)
            drawSelection()
            if (postOn) {
                val camF = studio.camera.forward
                val facing = (camF.dot(sunDir)).coerceIn(0f, 1f)
                val sp = studio.view.project(studio.view.camPos + sunDir * 2000f)
                val sx = if (sp != null) sp[0] / surfW.coerceAtLeast(1) else 0.5f
                val sy = if (sp != null) 1f - sp[1] / surfH.coerceAtLeast(1) else 1.2f
                post.finish(sx, sy, if (sp != null) facing * dayFactor else 0f)
            }
            drawHandles()
            val t3 = System.nanoTime()
            tUpd += (t1 - t0) / 1e6f; tShadow += (t2 - t1) / 1e6f; tDraw += (t3 - t2) / 1e6f; tSim += (t0 - now) / 1e6f
            // Depth precision depends on the near plane: push it out when everything is far
            // away (zoomed out), keep it close for first person and close-ups.
            val want = (closest * 0.35f).coerceIn(0.1f, 20f)
            var target = 0
            while (target < 7 && 0.1f * (1 shl (target + 1)) <= want) target++
            if (target < nearLevel) { nearLevel = target; nearRaise = 0 }
            else if (target > nearLevel) { if (++nearRaise > 40) { nearLevel++; nearRaise = 0 } }
            else nearRaise = 0
            nearPlane = 0.1f * (1 shl nearLevel)
        }
        onFrame?.invoke()
        cpuAcc += (System.nanoTime() - now) / 1e6f; cpuN++
        statTimer += dt
        if (statTimer > 5f) {
            statTimer = 0f
            val n = cpuN.coerceAtLeast(1)
            android.util.Log.i("MobileStudioGfx", "draw: batches %.1f loose %.1f (%d) decals %.1f [clothing %.1f surfaceGuis %.1f, %d repaints] transparent %.1f (%d)".format(
                tB / n, tL / n, nLoose / n, tDec / n, tClo / n, tSg / n, nSgRender, tTr / n, nTr / n))
            tB = 0f; tL = 0f; tDec = 0f; tTr = 0f; nTr = 0; nLoose = 0; tClo = 0f; tSg = 0f; nSgRender = 0
            android.util.Log.i("MobileStudioGfx", "fps=%.1f cpuMs=%.1f (sim %.1f upd %.1f shadow %.1f draw %.1f) parts=%d loose=%d chunks=%d visibleChunks=%d gpuMB=%.1f quality=%.2f near=%.2f depth=%d".format(
                fps, cpuAcc / n, tSim / n, tUpd / n, tShadow / n, tDraw / n, studio.renderParts().size, batches.loose.size, batches.chunks.size, batches.visible.size, batches.totalBytes / 1048576f, quality, nearPlane,
                com.leaf.lemonade.ui.ViewportView.depthBits))
            cpuAcc = 0f; cpuN = 0; tSim = 0f; tUpd = 0f; tShadow = 0f; tDraw = 0f
        }
        // On slow GPUs (low-end phones, software-rendered emulators) continuous 3D rendering can
        // starve the UI's own renderer until Android reports "not responding". When frames are
        // slow, finish this frame's GPU work and pause briefly so the UI gets a turn.
        slowAvg = slowAvg * 0.9f + dt * 0.1f
        if (slowAvg > 0.045f) {
            GLES20.glFinish()
            try { Thread.sleep((slowAvg * 1000f * 0.35f).toLong().coerceIn(8L, 40L)) } catch (_: InterruptedException) {}
        }
    }

    private var slowAvg = 0f
    private var statTimer = 0f
    private var cpuAcc = 0f
    private var cpuN = 0
    private var tSim = 0f; private var tUpd = 0f; private var tShadow = 0f; private var tDraw = 0f
    private var noBatch = false

    // ------------------------------------------------------------------ lighting

    private fun c3(v: Any?): Color3? = v as? Color3
    private fun luma(c: Color3) = c.r * 0.3f + c.g * 0.59f + c.b * 0.11f
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun mix(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) { for (i in 0..2) out[i] = a[i] + (b[i] - a[i]) * t }

    private fun updateLighting() {
        val l = studio.model.findService("Lighting")
        val clock = (l?.get("ClockTime") as? Number)?.toFloat()
            ?: (l?.get("TimeOfDay") as? String)?.split(":")?.let { p -> (p.getOrNull(0)?.toFloatOrNull() ?: 14f) + (p.getOrNull(1)?.toFloatOrNull() ?: 0f) / 60f }
            ?: 14f
        val lat = Math.toRadians(((l?.get("GeographicLatitude") as? Number)?.toDouble() ?: 41.733)).toFloat()
        val t = ((clock - 6f) / 12f * Math.PI).toFloat()
        val sunUp = sin(t)
        var dir = Vector3(-cos(t), sunUp * cos(lat), sunUp * sin(lat) + 0.0001f).unit
        dayFactor = smooth(-0.08f, 0.18f, dir.y)
        if (dir.y < 0f) dir = Vector3(-dir.x, -dir.y, -dir.z) // moon
        sunDir = Vector3(dir.x, max(dir.y, 0.08f), dir.z).unit

        val dayZ = floatArrayOf(0.30f, 0.55f, 0.93f); val dayH = floatArrayOf(0.74f, 0.85f, 0.97f)
        val nightZ = floatArrayOf(0.015f, 0.025f, 0.07f); val nightH = floatArrayOf(0.06f, 0.08f, 0.16f)
        val dusk = floatArrayOf(0.98f, 0.62f, 0.38f)
        mix(nightZ, dayZ, dayFactor, zenith)
        mix(nightH, dayH, dayFactor, horizon)
        val sunset = smooth(0.35f, 0.0f, kotlin.math.abs(sunUp)) * smooth(-0.2f, 0.05f, sunUp)
        mix(horizon, dusk, sunset * 0.65f, horizon)

        val brightness = ((l?.get("Brightness") as? Number)?.toFloat() ?: 2f).coerceIn(0f, 6f)
        val sunStrength = (0.36f + 0.14f * brightness).coerceAtMost(1.1f) * (0.12f + 0.88f * dayFactor)
        val warm = floatArrayOf(1f, 0.96f - 0.25f * sunset, 0.9f - 0.45f * sunset)
        for (i in 0..2) sunColor[i] = warm[i] * sunStrength
        if (dayFactor < 0.05f) { sunColor[0] = 0.12f; sunColor[1] = 0.14f; sunColor[2] = 0.22f }

        val oa = c3(l?.get("OutdoorAmbient")) ?: Color3.fromRGB(128, 128, 128)
        val am = c3(l?.get("Ambient")) ?: Color3.fromRGB(0, 0, 0)
        ambient = (0.30f + 0.42f * luma(oa) + 0.3f * luma(am)).coerceIn(0.25f, 0.9f) * (0.35f + 0.65f * dayFactor)
        exposure = Math.pow(2.0, ((l?.get("ExposureCompensation") as? Number)?.toDouble() ?: 0.0).coerceIn(-3.0, 3.0)).toFloat()
        shadowsOn = l?.get("GlobalShadows") != false
        shadowSoftness = ((l?.get("ShadowSoftness") as? Number)?.toFloat() ?: 0.2f).coerceIn(0f, 1f)

        val atmo = l?.children?.firstOrNull { it.className == "Atmosphere" }
        val fe = (l?.get("FogEnd") as? Number)?.toFloat() ?: 100000f
        if (atmo != null) {
            val density = ((atmo["Density"] as? Number)?.toFloat() ?: 0.3f).coerceIn(0f, 1f)
            val ac = c3(atmo["Color"]) ?: Color3.fromRGB(199, 170, 107)
            val haze = ((atmo["Haze"] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 10f)
            fogMode = 2f
            fogStart = density * (0.004f + haze * 0.0006f) // exponential density per stud
            fogEnd = 0.75f
            val mixC = floatArrayOf(ac.r, ac.g, ac.b)
            mix(horizon, mixC, 0.35f, fogColor)
            mix(horizon, fogColor, 0.5f, horizon)
        } else if (fe < 20000f) {
            fogMode = 1f
            fogStart = (l?.get("FogStart") as? Number)?.toFloat() ?: 0f
            fogEnd = max(fe, fogStart + 1f)
            val fc = c3(l?.get("FogColor")) ?: Color3.fromRGB(191, 191, 191)
            fogColor[0] = fc.r; fogColor[1] = fc.g; fogColor[2] = fc.b
            // Thick fog also tints the sky near the horizon.
            if (fe < 2000f) mix(horizon, fogColor, 0.7f, horizon)
        } else {
            fogMode = 0f
            fogColor[0] = horizon[0]; fogColor[1] = horizon[1]; fogColor[2] = horizon[2]
        }
    }

    // ------------------------------------------------------------------ geometry

    /**
     * What to draw for a part. kind: 0 = primitive, 1 = classic head, 2 = file mesh (MeshPart /
     * SpecialMesh FileMesh, drawn with the textured program), 3 = union (several meshes, one per
     * source color). [tex] is the mesh's texture content, [tint] SpecialMesh.VertexColor.
     */
    private class Geo(val mesh: Mesh, val cf: CFrame, val scale: Vector3, val kind: Int, val tex: String? = null,
                      val subs: List<Pair<Mesh, Color3>>? = null, val tint: Vector3? = null)

    private val trussGpu = HashMap<String, Mesh>()

    private fun trussGeo(p: Instance, cf: CFrame, size: Vector3): Geo? {
        val style = (p["Style"] as? EnumValue)?.value ?: (p["Style"] as? Number)?.toInt() ?: 0
        val key = "${java.lang.Float.floatToIntBits(size.x)}:${java.lang.Float.floatToIntBits(size.y)}:${java.lang.Float.floatToIntBits(size.z)}:$style"
        val mesh = trussGpu[key] ?: run {
            val arr = com.leaf.lemonade.engine.TrussMesh.generate(size, style)
            if (arr.isEmpty()) return null
            val v6 = FloatArray(arr.size / 8 * 6)
            for (i in 0 until arr.size / 8) for (k in 0 until 6) v6[i * 6 + k] = arr[i * 8 + k]
            Meshes.upload(v6, 6, GLES20.GL_TRIANGLES, v6).also { trussGpu[key] = it }
        }
        return Geo(mesh, cf, size, 0)
    }

    private fun meshFor(shape: Int) = when (shape) { 0 -> sphere; 2 -> cylinder; 3 -> wedge; 4 -> corner; else -> cube }

    /** A decoded mesh on the GPU: its vertex buffer and bounding box (mesh units). */
    private class GpuMesh(val mesh: Mesh, val center: Vector3, val size: Vector3)
    private val gpuMeshes = HashMap<Long, GpuMesh>()
    private var meshEpoch = -1

    /** Uploads a downloaded mesh the first time it's needed (GL thread). Null while it loads. */
    private fun fileMesh(content: Any?): GpuMesh? {
        val id = AssetFetcher.idOf(content) ?: return null
        gpuMeshes[id]?.let { return it }
        val data = MeshLoader.get(id) ?: return null
        if (data.triangles == 0) return null
        val m = Meshes.upload(data.verts, 8, GLES20.GL_TRIANGLES)
        val g = GpuMesh(m, Vector3(data.center(0), data.center(1), data.center(2)), Vector3(data.size(0), data.size(1), data.size(2)))
        gpuMeshes[id] = g
        return g
    }

    /** GPU copies of union results, rebuilt when the union's sources change. */
    private class UnionGpu(val sig: Long, val meshes: List<Pair<Mesh, Int>>, val styles: List<SolidModeling.Style>)
    private val unionGpu = java.util.IdentityHashMap<Instance, UnionGpu>()

    /** GPU copies of unions made in Roblox Studio (shared by every union using the same mesh). */
    private val storedUnionGpu = HashMap<String, List<Pair<Mesh, Int>>>()

    private fun storedUnionGeo(p: Instance, cf: CFrame): Geo? {
        val d = UnionMeshes.get(p) ?: return null
        val meshes = storedUnionGpu.getOrPut(d.key) { d.groups.map { (argb, arr) -> Meshes.upload(arr, 8, GLES20.GL_TRIANGLES) to argb } }
        if (meshes.isEmpty()) return null
        val size = PartData.size(p)
        fun k(a: Float, b: Float) = if (b > 1e-4f) a / b else 1f
        val sc = Vector3(k(size.x, d.size.x), k(size.y, d.size.y), k(size.z, d.size.z))
        val usePart = p["UsePartColor"] == true
        val subs = meshes.map { (m, argb) -> m to (if (usePart) PartData.color(p) else Color3.fromRGB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)) }
        return Geo(subs[0].first, cf * CFrame.at(-(d.center * sc)), sc, 3, subs = subs)
    }

    private fun unionGeo(p: Instance, cf: CFrame): Geo? {
        val r = SolidModeling.result(p) ?: return if (UnionMeshes.isStored(p)) storedUnionGeo(p, cf) else null
        var g = unionGpu[p]
        if (g == null || g.sig != r.sig) {
            g?.meshes?.forEach { GLES20.glDeleteBuffers(1, intArrayOf(it.first.vbo), 0) }
            g = UnionGpu(r.sig, r.tris.filter { it.value.isNotEmpty() }.map { (style, arr) -> Meshes.upload(arr, 8, GLES20.GL_TRIANGLES) to style }, r.styles)
            unionGpu[p] = g
        }
        if (g.meshes.isEmpty()) return null
        val usePart = p["UsePartColor"] == true
        val subs = g.meshes.map { (m, s) -> m to (if (usePart) PartData.color(p) else g.styles.getOrNull(s)?.color ?: PartData.color(p)) }
        return Geo(subs[0].first, cf, SolidModeling.scaleOf(p, r), 3, subs = subs)
    }

    /** Mesh, transform and scale for a part, honoring SpecialMesh/BlockMesh/CylinderMesh children. */
    private fun geometry(p: Instance): Geo {
        val cf = PartData.cframe(p)
        val size = PartData.size(p)
        if (p.className == "MeshPart") {
            fileMesh(PartData.meshContent(p))?.let { fm ->
                fun k(a: Float, b: Float) = if (b > 1e-5f) a / b else 1f
                val sc = Vector3(k(size.x, fm.size.x), k(size.y, fm.size.y), k(size.z, fm.size.z))
                // Dynamic heads and most modern hair/accessories keep their texture in a
                // SurfaceAppearance (ColorMap) instead of TextureID.
                val tex = partTexOverride[p]
                    ?: PartData.textureContent(p)
                    ?: p.children.firstOrNull { it.className == "SurfaceAppearance" }?.let { sa -> PartData.content(sa, "ColorMap", "ColorMapContent") }
                return Geo(fm.mesh, cf * CFrame.at(-(fm.center * sc)), sc, 2, tex)
            }
            // A character's head whose mesh is still downloading (or can't be read): Roblox's
            // head shape in the skin colour, so the character never looks headless.
            if (p.name == "Head" && p.parent?.findFirstChildOfClass("Humanoid") != null) {
                val dia = minOf(size.x, size.z)
                return Geo(head, cf, Vector3(dia, size.y, dia), 1)
            }
        } else if (p.className == "TrussPart") {
            trussGeo(p, cf, size)?.let { return it }
        } else if (p.isA("PartOperation") && p.className != "NegateOperation") {
            unionGeo(p, cf)?.let { return it }
        }
        var dm: Instance? = null
        for (c in p.children) if (c.className == "SpecialMesh" || c.className == "BlockMesh" || c.className == "CylinderMesh" || c.className == "FileMesh") { dm = c; break }
        if (dm != null) {
            val sc = dm["Scale"] as? Vector3 ?: Vector3(1f, 1f, 1f)
            val off = dm["Offset"] as? Vector3 ?: Vector3.ZERO
            val ocf = if (off != Vector3.ZERO) cf * CFrame.at(off) else cf
            val meshRef = PartData.meshContent(dm)
            val declared = if (dm.className == "SpecialMesh") (dm["MeshType"] as? EnumValue)?.value else null
            val type = if (dm.className == "SpecialMesh") (if (!meshRef.isNullOrBlank() && (declared == null || declared == 0)) 5 else declared ?: 0) else if (dm.className == "CylinderMesh") 4 else if (dm.className == "FileMesh") 5 else 6
            when (type) {
                0 -> { val dia = minOf(size.x, size.z); return Geo(head, ocf, Vector3(dia * sc.x, size.y * sc.y, dia * sc.z), 1) }
                2 -> return Geo(wedge, ocf, size * sc, 0)
                3 -> return Geo(sphere, ocf, size * sc, 0)
                4 -> return Geo(cylinderY, ocf, size * sc, 0)
                5 -> fileMesh(meshRef)?.let { fm ->
                    // FileMesh: the mesh at its own size, times Scale (it doesn't stretch to the part).
                    val tex = PartData.textureContent(dm)?.takeIf { it.isNotBlank() }
                    return Geo(fm.mesh, ocf, sc, 2, tex, tint = dm["VertexColor"] as? Vector3)
                }
                1, 6 -> return Geo(cube, ocf, size * sc, 0)
            }
        }
        // R6 characters: a body package's CharacterMesh replaces the limb, otherwise Roblox's
        // slightly bevelled limb shape (not a sharp block).
        val bodyPart = r6BodyPart(p)
        if (bodyPart > 0) {
            val rig = p.parent!!
            rig.children.firstOrNull { it.className == "CharacterMesh" && ((it["BodyPart"] as? EnumValue)?.value ?: -1) == bodyPart }?.let { cm ->
                fileMesh(cm["MeshId"]?.toString())?.let { fm -> return Geo(fm.mesh, cf, Vector3.ONE, 2, packageTexture(rig, bodyPart, cm)) }
            }
            return Geo(if (bodyPart == 1) r6Torso else r6Limb, cf, size, 0)
        }
        val shape = PartData.shape(p)
        val s = when (shape) {
            0 -> { val d = minOf(size.x, size.y, size.z); Vector3(d, d, d) }
            2 -> { val d = minOf(size.y, size.z); Vector3(size.x, d, d) }
            else -> size
        }
        return Geo(meshFor(shape), cf, s, 0)
    }

    /**
     * Enum.BodyPart of an R6 character's torso/limb (Torso 1, LeftArm 2, RightArm 3, LeftLeg 4,
     * RightLeg 5), or 0 for anything else. Only plain block Parts directly in a Humanoid model.
     */
    private fun r6BodyPart(p: Instance): Int {
        val idx = when (p.name) { "Torso" -> 1; "Left Arm" -> 2; "Right Arm" -> 3; "Left Leg" -> 4; "Right Leg" -> 5; else -> return 0 }
        if (p.className != "Part" || PartData.shape(p) != 1) return 0
        val rig = p.parent ?: return 0
        if (!rig.isA("Model") || rig.findFirstChildOfClass("Humanoid") == null) return 0
        return idx
    }

    /** Composited torso textures for body packages (pants under shirt, template layout), by templates. */
    private val packageTex = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Texture of a body-package mesh: its UVs use the classic clothing template, so arms wear the
     * shirt, legs the pants, and the torso both (pants first); without clothing, the package's own
     * texture. Like Roblox, where no texture covers, the body color shows.
     */
    private fun packageTexture(rig: Instance, bodyPart: Int, cm: Instance): String? {
        val shirt = rig.children.firstOrNull { it.className == "Shirt" }?.get("ShirtTemplate")?.toString()?.takeIf { it.isNotBlank() }
        val pants = rig.children.firstOrNull { it.className == "Pants" }?.get("PantsTemplate")?.toString()?.takeIf { it.isNotBlank() }
        val own = listOf("OverlayTextureId", "BaseTextureId").firstNotNullOfOrNull { k -> (cm[k] as? Number)?.toLong()?.takeIf { it > 0 }?.let { "rbxassetid://$it" } }
        return when (bodyPart) {
            2, 3 -> shirt ?: own
            4, 5 -> pants ?: own
            else -> if (shirt != null && pants != null) {
                val key = "$pants|$shirt"
                packageTex[key] ?: run {
                    if (r15Pending.add("pkg:$key")) r15Pool.execute {
                        try {
                            val (ok1, pb) = clothingImage(pants); val (ok2, sb) = clothingImage(shirt)
                            if (ok1 && ok2 && pb != null && sb != null) {
                                val out = Bitmap.createBitmap(TW.toInt(), TH.toInt(), Bitmap.Config.ARGB_8888)
                                val c = Canvas(out); val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                                val dst = android.graphics.Rect(0, 0, out.width, out.height)
                                c.drawBitmap(pb, null, dst, paint); c.drawBitmap(sb, null, dst, paint)
                                val content = "${com.leaf.lemonade.engine.Avatar.TEX_PREFIX}pkg/${Integer.toHexString(key.hashCode())}"
                                com.leaf.lemonade.engine.Avatar.putTexture(content, out)
                                packageTex[key] = content
                            }
                        } catch (_: Exception) {} finally { r15Pending.remove("pkg:$key") }
                    }
                    shirt
                }
            } else shirt ?: pants ?: own
        }
    }

    private fun bind(m: Mesh, posLoc: Int, nrmLoc: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, m.stride * 4, 0)
        if (nrmLoc >= 0) {
            GLES20.glEnableVertexAttribArray(nrmLoc)
            GLES20.glVertexAttribPointer(nrmLoc, 3, GLES20.GL_FLOAT, false, m.stride * 4, 12)
        }
    }

    private fun setModel(cf: CFrame, s: Vector3) {
        model[0] = cf.r00 * s.x; model[1] = cf.r10 * s.x; model[2] = cf.r20 * s.x; model[3] = 0f
        model[4] = cf.r01 * s.y; model[5] = cf.r11 * s.y; model[6] = cf.r21 * s.y; model[7] = 0f
        model[8] = cf.r02 * s.z; model[9] = cf.r12 * s.z; model[10] = cf.r22 * s.z; model[11] = 0f
        model[12] = cf.x; model[13] = cf.y; model[14] = cf.z; model[15] = 1f
        val ix = if (s.x != 0f) 1f / s.x else 0f; val iy = if (s.y != 0f) 1f / s.y else 0f; val iz = if (s.z != 0f) 1f / s.z else 0f
        normal[0] = cf.r00 * ix; normal[1] = cf.r10 * ix; normal[2] = cf.r20 * ix
        normal[3] = cf.r01 * iy; normal[4] = cf.r11 * iy; normal[5] = cf.r21 * iy
        normal[6] = cf.r02 * iz; normal[7] = cf.r12 * iz; normal[8] = cf.r22 * iz
    }

    /** Rough visibility test: skip parts fully behind the camera or tiny and far away. */
    private fun visible(cf: CFrame, size: Vector3, camPos: Vector3, @Suppress("UNUSED_PARAMETER") fwd: Vector3): Boolean = inView(cf, size, camPos)

    /**
     * In the view frustum and big enough on screen to matter (radius * pixels-per-stud at its
     * distance >= ~1.5 px, scaled by the render quality). Also tracks the closest part drawn.
     */
    private fun inView(cf: CFrame, size: Vector3, camPos: Vector3): Boolean {
        val r = size.magnitude * 0.5f
        if (!frustum.sphere(cf.x, cf.y, cf.z, r)) return false
        val dx = cf.x - camPos.x; val dy = cf.y - camPos.y; val dz = cf.z - camPos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (r < 5f && d > r * detailScale && d > (if (r < 1.2f) 150f else 900f)) return false
        val near = d - r
        if (near < closest) closest = near.coerceAtLeast(0f)
        return true
    }

    // ------------------------------------------------------------------ shadows

    /** Where the shadow map is centered and how far it reaches this frame. */
    private fun shadowFocus(): Pair<Vector3, Float> {
        val cam = studio.camera
        val play = studio.play
        val focus = play?.let { p -> p.character?.findFirstChild("HumanoidRootPart")?.let { PartData.cframe(it).position } }
            ?: (cam.pos + cam.forward * 45f)
        val radius = if (play != null) 110f else (40f + cam.pos.y.coerceAtLeast(0f) * 0.8f).coerceIn(80f, 260f)
        return focus to radius
    }

    private var shadowSoftness = 0.2f
    private var shadowRadius = 110f
    private fun shadowTexel(): Float {
        val studsPerTexel = 2f * shadowRadius / shadowSize
        val blur = shadowSoftness * 1.71f + 0.05f
        return (1f / shadowSize) * (blur / studsPerTexel).coerceIn(1f, 4f)
    }

    private var shadowValid = false
    private val lastLightVP = FloatArray(16)
    private var lastShadowChange = -1L
    private var lastShadowMeshEpoch = -1
    private var shadowAge = 0

    private fun renderShadowMap(focus: Vector3, radius: Float) {
        val play = studio.play
        shadowRadius = radius
        // Snap the focus to shadow-map texels so shadows don't shimmer as the camera moves.
        val up = if (kotlin.math.abs(sunDir.y) >= 0.7f) Vector3.Z else Vector3.Y
        val fwdL = (-sunDir).unit
        val rightL = fwdL.cross(up).unit
        val upL = rightL.cross(fwdL).unit
        val texel = radius * 2f / shadowSize
        val fx = floor(focus.dot(rightL) / texel) * texel
        val fy = floor(focus.dot(upL) / texel) * texel
        val fz = focus.dot(fwdL)
        val center = rightL * fx + upL * fy + fwdL * fz
        val eye = center + sunDir * 600f
        Matrix.setLookAtM(lightView, 0, eye.x, eye.y, eye.z, center.x, center.y, center.z, up.x, up.y, up.z)
        Matrix.orthoM(lightProj, 0, -radius, radius, -radius, radius, 1f, 1300f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
        // Nothing moved and the light didn't change: the shadow map from before is still right
        // (in edit mode this skips drawing the whole place a second time every frame).
        val change = Instance.changeCount
        shadowAge++
        // Slow device in a playtest: refresh shadows every other frame (keeping last frame's
        // light matrix so they stay lined up with the map they were drawn into).
        if (shadowValid && studio.isPlaying && fps < 20f && frames % 2 == 1) { lastLightVP.copyInto(lightVP); return }
        if (shadowValid && change == lastShadowChange && MeshLoader.epoch == lastShadowMeshEpoch && shadowAge < 45 && lightVP.contentEquals(lastLightVP)) return
        shadowValid = true; lastShadowChange = change; lastShadowMeshEpoch = MeshLoader.epoch; shadowAge = 0
        lightVP.copyInto(lastLightVP)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFbo)
        GLES20.glViewport(0, 0, shadowSize, shadowSize)
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(depthProg)
        GLES20.glUniformMatrix4fv(dVP, 1, false, lightVP, 0)
        GLES20.glCullFace(GLES20.GL_FRONT)
        val r2 = (radius * 1.6f) * (radius * 1.6f)
        // Batched chunks around the focus (already in world space).
        Matrix.setIdentityM(model, 0)
        GLES20.glUniformMatrix4fv(dModel, 1, false, model, 0)
        GLES20.glEnableVertexAttribArray(daPos)
        for (c in batches.visible) {
            val dx = kotlin.math.max(kotlin.math.max(c.x0 - center.x, 0f), center.x - c.x1)
            val dz = kotlin.math.max(kotlin.math.max(c.z0 - center.z, 0f), center.z - c.z1)
            if (dx * dx + dz * dz > r2) continue
            for (b in c.batches) {
                if (b.tier < 2 && c.dist > batches.tierRange(b.tier, detailScale)) continue
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b.vbo)
                GLES20.glVertexAttribPointer(daPos, 3, GLES20.GL_FLOAT, false, b.strideBytes, 0)
                // File meshes are two-sided (thin hair/cloth/leaves): they cast from both sides.
                if (b.kind == 3) GLES20.glDisable(GLES20.GL_CULL_FACE)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, b.count)
                if (b.kind == 3) GLES20.glEnable(GLES20.GL_CULL_FACE)
            }
        }
        // Terrain casts shadows too (its vertices are in world space like the batches).
        for (t in terrainView.visible) {
            if (t.count == 0) continue
            val dx = kotlin.math.max(kotlin.math.max(t.min.x - center.x, 0f), center.x - t.max.x)
            val dz = kotlin.math.max(kotlin.math.max(t.min.z - center.z, 0f), center.z - t.max.z)
            if (dx * dx + dz * dz > r2) continue
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, t.vbo)
            GLES20.glVertexAttribPointer(daPos, 3, GLES20.GL_FLOAT, false, StaticBatches.MESH_STRIDE_BYTES, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, t.count)
        }
        var current: Mesh? = null
        for (p in batches.loose) {
            if (PartData.transparency(p) > 0.5f || p["CastShadow"] == false || p.className == "NegateOperation") continue
            if (play?.hiddenInFirstPerson(p) == true) continue
            val g = geometry(p)
            val dx = g.cf.x - center.x; val dz = g.cf.z - center.z
            val ext = g.scale.magnitude * 0.5f
            if (dx * dx + dz * dz > r2 + ext * ext * 4f && ext < radius) continue
            setModel(g.cf, g.scale)
            GLES20.glUniformMatrix4fv(dModel, 1, false, model, 0)
            val meshes = g.subs?.map { it.first } ?: listOf(g.mesh)
            for (m in meshes) {
                if (m !== current) { bind(m, daPos, -1); current = m }
                GLES20.glDrawArrays(m.mode, 0, m.count)
            }
        }
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ------------------------------------------------------------------ sky

    private var skyboxProg = 0
    private var kVP = 0; private var kCam = 0; private var kRot = 0; private var kTex = 0; private var kTint = 0; private var kaPos = 0; private var kaUv = 0
    private lateinit var skyboxMesh: Mesh

    /** Sky property names in the order of [skyboxMesh]'s faces. */
    private val SKY_FACES = arrayOf("SkyboxFt", "SkyboxBk", "SkyboxRt", "SkyboxLf", "SkyboxUp", "SkyboxDn")

    private fun setupSkybox() {
        skyboxProg = program(SKYBOX_VS, SKYBOX_FS)
        fun u(n: String) = GLES20.glGetUniformLocation(skyboxProg, n)
        kVP = u("uVP"); kCam = u("uCam"); kRot = u("uRot"); kTex = u("uTex"); kTint = u("uTint")
        kaPos = GLES20.glGetAttribLocation(skyboxProg, "aPos"); kaUv = GLES20.glGetAttribLocation(skyboxProg, "aUv")
        // One quad per face, seen from inside the cube: (face direction, image right, image up).
        // Roblox's layout differs from the usual skybox cross: facing Front (-Z), the *Lf* image
        // is on your right (+X) and Rt on your left, and Up/Dn are turned 90 degrees. Measured
        // by matching the edges of Studio's own sky512_* faces (seam error 1.7 vs 29.7 for the
        // textbook layout), so custom skies made for Roblox line up here too.
        val faces = listOf(
            Triple(Vector3(0f, 0f, -1f), Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f)),   // Ft
            Triple(Vector3(0f, 0f, 1f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f)),   // Bk
            Triple(Vector3(-1f, 0f, 0f), Vector3(0f, 0f, -1f), Vector3(0f, 1f, 0f)),  // Rt
            Triple(Vector3(1f, 0f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 1f, 0f)),    // Lf
            Triple(Vector3(0f, 1f, 0f), Vector3(0f, 0f, -1f), Vector3(1f, 0f, 0f)),   // Up
            Triple(Vector3(0f, -1f, 0f), Vector3(0f, 0f, -1f), Vector3(-1f, 0f, 0f)), // Dn
        )
        val out = FloatArray(6 * 6 * 5)
        var o = 0
        for ((n, r, up) in faces) {
            // Corners (sx, sy) in -1..1; v = 0 is the top of the image.
            for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to -1f, 1f to 1f, -1f to 1f)) {
                val p = n + r * sx + up * sy
                out[o++] = p.x; out[o++] = p.y; out[o++] = p.z
                out[o++] = (sx + 1f) / 2f; out[o++] = (1f - sy) / 2f
            }
        }
        skyboxMesh = Meshes.upload(out, 5, GLES20.GL_TRIANGLES)
    }

    /** Draws the place's Sky skybox if it has one with all six images loaded; false otherwise. */
    private fun drawSkybox(): Boolean {
        val sky = studio.model.findService("Lighting")?.children?.firstOrNull { it.className == "Sky" } ?: return false
        val tex = IntArray(6)
        for (i in 0 until 6) {
            val content = sky[SKY_FACES[i]]?.toString()?.trim().orEmpty()
            // Empty or rbxasset:// (Roblox's built-in default sky files): keep the default sky.
            if (content.isEmpty() || AssetFetcher.idOf(content) == null) return false
            tex[i] = assetTexture(content, false)
        }
        if (tex.any { it == 0 }) return false
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(skyboxProg)
        GLES20.glUniformMatrix4fv(kVP, 1, false, studio.view.vp, 0)
        val cp = studio.view.camPos
        GLES20.glUniform3f(kCam, cp.x, cp.y, cp.z)
        val o = sky["SkyboxOrientation"] as? Vector3 ?: Vector3.ZERO
        val rot = if (o == Vector3.ZERO) CFrame.IDENTITY else CFrame.fromOrientation(Math.toRadians(o.x.toDouble()).toFloat(), Math.toRadians(o.y.toDouble()).toFloat(), Math.toRadians(o.z.toDouble()).toFloat())
        val m = floatArrayOf(rot.r00, rot.r10, rot.r20, rot.r01, rot.r11, rot.r21, rot.r02, rot.r12, rot.r22)
        GLES20.glUniformMatrix3fv(kRot, 1, false, m, 0)
        // Night dims the sky a little, like Roblox's skyboxes under a dark ClockTime.
        val k = 0.35f + 0.65f * dayFactor
        GLES20.glUniform3f(kTint, k, k, k)
        GLES20.glUniform1i(kTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyboxMesh.vbo)
        GLES20.glEnableVertexAttribArray(kaPos)
        GLES20.glVertexAttribPointer(kaPos, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(kaUv)
        GLES20.glVertexAttribPointer(kaUv, 2, GLES20.GL_FLOAT, false, 20, 12)
        for (i in 0 until 6) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, i * 6, 6)
        }
        GLES20.glDisableVertexAttribArray(kaUv)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        return true
    }

    private fun drawSky() {
        if (drawSkybox()) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(skyProg)
        GLES20.glUniformMatrix4fv(sInvVP, 1, false, studio.view.invVp, 0)
        val cp = studio.view.camPos
        GLES20.glUniform3f(sCam, cp.x, cp.y, cp.z)
        GLES20.glUniform3f(sSun, sunDir.x, sunDir.y, sunDir.z)
        GLES20.glUniform3fv(sZenith, 1, zenith, 0)
        GLES20.glUniform3fv(sHorizon, 1, horizon, 0)
        GLES20.glUniform3f(sSunColor, 1f, 0.95f, 0.85f)
        GLES20.glUniform1f(sTime, time)
        GLES20.glUniform1f(sDay, dayFactor)
        val clouds = studio.model.workspace.children.firstOrNull { it.className == "Terrain" }?.children?.firstOrNull { it.className == "Clouds" && it["Enabled"] != false }
        if (clouds != null) {
            val cover = ((clouds["Cover"] as? Number)?.toFloat() ?: 0.5f).coerceIn(0f, 1f)
            val density = ((clouds["Density"] as? Number)?.toFloat() ?: 0.7f).coerceIn(0f, 1f)
            val col = clouds["Color"] as? Color3 ?: Color3(1f, 1f, 1f)
            GLES20.glUniform3f(sCloud, cover, density, 1f)
            GLES20.glUniform3f(sCloudCol, col.r, col.g, col.b)
        } else {
            GLES20.glUniform3f(sCloud, 0f, 0f, 0f)
            GLES20.glUniform3f(sCloudCol, 1f, 1f, 1f)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, screen.vbo)
        GLES20.glEnableVertexAttribArray(saPos)
        GLES20.glVertexAttribPointer(saPos, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, screen.count)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ------------------------------------------------------------------ parts

    private fun material(m: Int): FloatArray = matCache.getOrPut(m) { materialParams(m) }

    /**
     * (pattern type, amount, scale, specular) for a Roblox Material enum value. Patterns are drawn
     * procedurally in the shader (Roblox's own textures aren't used), tinted by the part color.
     */
    private fun materialParams(m: Int): FloatArray = when (m) {
        272 -> floatArrayOf(0f, 0f, 1f, 0.22f)          // SmoothPlastic
        288 -> floatArrayOf(0f, 0f, 1f, 0f)             // Neon (emissive)
        512 -> floatArrayOf(2f, 0.14f, 1f, 0.06f)       // Wood
        528 -> floatArrayOf(3f, 0.12f, 1f, 0.06f)       // WoodPlanks
        784 -> floatArrayOf(11f, 0.1f, 0.25f, 0.45f)    // Marble
        788 -> floatArrayOf(13f, 0.16f, 0.9f, 0.08f)    // Basalt
        800 -> floatArrayOf(12f, 0.16f, 0.35f, 0.12f)   // Slate
        804 -> floatArrayOf(14f, 0.22f, 0.7f, 0.1f)     // CrackedLava
        816 -> floatArrayOf(23f, 0.1f, 1.4f, 0.04f)     // Concrete
        820 -> floatArrayOf(5f, 0.09f, 0.5f, 0.05f)     // Limestone
        832 -> floatArrayOf(24f, 0.13f, 2.5f, 0.2f)     // Granite
        836 -> floatArrayOf(16f, 0.08f, 4f, 0.04f)      // Pavement
        848 -> floatArrayOf(4f, 0.12f, 1f, 0.04f)       // Brick
        864 -> floatArrayOf(13f, 0.2f, 2.2f, 0.12f)     // Pebble
        880 -> floatArrayOf(13f, 0.24f, 0.8f, 0.08f)    // Cobblestone
        896 -> floatArrayOf(5f, 0.17f, 0.4f, 0.08f)     // Rock
        912 -> floatArrayOf(12f, 0.1f, 0.8f, 0.05f)     // Sandstone
        1040 -> floatArrayOf(15f, 0.2f, 0.6f, 0.3f)     // CorrodedMetal
        1056 -> floatArrayOf(6f, 0.14f, 1f, 0.7f)       // DiamondPlate
        1072 -> floatArrayOf(7f, 0.07f, 2f, 0.9f)       // Foil
        1088 -> floatArrayOf(7f, 0.06f, 1.5f, 0.7f)     // Metal
        1280 -> floatArrayOf(8f, 0.16f, 1.5f, 0.04f)    // Grass
        1284 -> floatArrayOf(8f, 0.2f, 1.2f, 0.04f)     // LeafyGrass
        1296 -> floatArrayOf(25f, 0.08f, 3f, 0.04f)     // Sand
        1312 -> floatArrayOf(9f, 0.08f, 4f, 0.02f)      // Fabric
        1328 -> floatArrayOf(25f, 0.05f, 1f, 0.3f)      // Snow
        1344 -> floatArrayOf(5f, 0.14f, 0.6f, 0.15f)    // Mud
        1360 -> floatArrayOf(5f, 0.15f, 0.8f, 0.03f)    // Ground
        1376 -> floatArrayOf(22f, 0.09f, 4f, 0.05f)     // Asphalt
        1392 -> floatArrayOf(25f, 0.07f, 2f, 0.15f)     // Salt
        1536 -> floatArrayOf(10f, 0.05f, 0.3f, 0.9f)    // Ice
        1552 -> floatArrayOf(10f, 0.08f, 0.3f, 0.8f)    // Glacier
        1568 -> floatArrayOf(0f, 0f, 1f, 1f)            // Glass
        1584 -> floatArrayOf(19f, 0f, 1f, 0f)           // ForceField (animated)
        2304 -> floatArrayOf(18f, 0.06f, 1f, 0.03f)     // Cardboard
        2305 -> floatArrayOf(21f, 0.12f, 1f, 0.01f)     // Carpet
        2306 -> floatArrayOf(16f, 0.06f, 2f, 0.5f)      // CeramicTiles
        2307 -> floatArrayOf(17f, 0.12f, 1f, 0.1f)      // ClayRoofTiles
        2308 -> floatArrayOf(17f, 0.16f, 0.7f, 0.05f)   // RoofShingles
        2309 -> floatArrayOf(20f, 0.1f, 1f, 0.15f)      // Leather
        2310 -> floatArrayOf(5f, 0.05f, 0.4f, 0.04f)    // Plaster
        2311 -> floatArrayOf(1f, 0.02f, 3f, 0.08f)      // Rubber
        // Plastic is a flat color like Roblox's (its fine grain comes from the plastic normal map
        // when material textures are on). The old value-noise grain looked blurry and blotchy and
        // showed grid-shaped artifacts on big parts.
        else -> floatArrayOf(0f, 0f, 1f, 0.28f)         // Plastic & everything else
    }

    private fun surf(p: Instance, name: String): Float = ((p[name] as? EnumValue)?.value ?: 0).toFloat()

    private class Draw(val part: Instance, val dist: Float)

    private var frameShadows = false

    /** Switches lit-shader variant and sets its per-frame uniforms. */
    private fun switchLit(l: LitLocs) {
        if (activeLit === l) return
        useLit(l)
        setLitFrameUniforms(frameShadows)
    }

    private fun drawParts(shadows: Boolean) {
        val parts = batches.loose
        frames++
        frameShadows = shadows
        gatherLights()
        useLit(litBasic ?: return)
        setLitFrameUniforms(shadows)
        val cp = studio.view.camPos
        val transparent = ArrayList<Draw>()
        val advanced = ArrayList<Instance>()
        val textured = ArrayList<Triple<Instance, Geo, TexBind>>()
        val fileMeshes = ArrayList<Pair<Instance, Geo>>()
        var current: Mesh? = null
        val play = studio.play
        if (meshEpoch != MeshLoader.epoch) meshEpoch = MeshLoader.epoch
        refreshVariants()
        val texProg = texturedLit()
        // Batched chunks first (front-most geometry, few draw calls).
        for (c in batches.visible) if (c.dist < closest && frustum.box(c.x0, c.y0, c.z0, c.x1, c.y1, c.z1)) closest = c.dist
        val m0 = System.nanoTime()
        drawStaticBatches()
        drawTerrain()
        val m1 = System.nanoTime(); tB += (m1 - m0) / 1e6f
        useLit(litBasic ?: return)
        setLitFrameUniforms(shadows)
        for (p in parts) {
            val t = PartData.transparency(p)
            if (t >= 0.999f) continue
            if (play?.hiddenInFirstPerson(p) == true) continue
            val cf = PartData.cframe(p)
            val size = PartData.size(p)
            if (!inView(cf, size, cp)) continue
            val mat = PartData.material(p)
            if (t > 0.01f || mat == 1568 || mat == 1584 || p.className == "NegateOperation") {
                transparent.add(Draw(p, (cf.position - cp).magnitude)); continue
            }
            val g = geometry(p)
            if (g.kind == 2) { fileMeshes.add(p to g); continue }
            if (texProg != null) texturesFor(p, mat)?.let { textured.add(Triple(p, g, it)); continue }
            if (needsAdvanced(mat)) { advanced.add(p); continue }
            current = drawPart(p, 1f, current, g)
        }
        if (advanced.isNotEmpty()) {
            advancedLit()?.let { switchLit(it); current = null }
            for (p in advanced) current = drawPart(p, 1f, current)
        }
        if (textured.isNotEmpty() && texProg != null) {
            switchLit(texProg); current = null
            // Group parts sharing textures so each set is bound once.
            textured.sortBy { it.third.color }
            var bound: TexBind? = null
            for ((p, g, tb) in textured) {
                if (bound == null || bound.color != tb.color || bound.normal != tb.normal || bound.scale != tb.scale) { bindTextures(tb); bound = tb }
                current = drawPart(p, 1f, current, g)
            }
        }
        if (fileMeshes.isNotEmpty()) {
            for ((p, g) in fileMeshes) drawFileMesh(p, g, 1f)
            activeLit = null
            useLit(litBasic ?: return); setLitFrameUniforms(shadows)
        }
        val m2 = System.nanoTime(); tL += (m2 - m1) / 1e6f
        drawDecals()
        drawConstraints()
        drawAdornments()
        val m3 = System.nanoTime(); tDec += (m3 - m2) / 1e6f
        activeLit = null
        current = null
        if (transparent.isNotEmpty()) {
            transparent.sortByDescending { it.dist }
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            for (d in transparent) {
                var a = 1f - PartData.transparency(d.part)
                when (PartData.material(d.part)) { 1568 -> a = minOf(a, 0.45f); 1584 -> a = minOf(a, 0.5f) }
                val negate = d.part.className == "NegateOperation"
                if (negate) a = minOf(a, 0.42f)
                val g = geometry(d.part)
                if (g.kind == 2) { drawFileMesh(d.part, g, a); activeLit = null; current = null; continue }
                val tb = if (texProg != null && !negate) texturesFor(d.part, PartData.material(d.part)) else null
                val adv = if (needsAdvanced(PartData.material(d.part))) litAdv else null
                val want = if (tb != null) texProg!! else adv ?: litBasic!!
                if (activeLit !== want) { switchLit(want); current = null }
                if (tb != null) bindTextures(tb)
                current = drawPart(d.part, a, current, g, if (negate) NEGATE_COLOR else null)
            }
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        drawWater()
        tTr += (System.nanoTime() - m3) / 1e6f
        nTr += transparent.size; nLoose += textured.size + advanced.size + fileMeshes.size
        // Handles and other overlays draw with the lean shader.
        litBasic?.let { useLit(it) }
    }

    private var tB = 0f; private var tL = 0f; private var tDec = 0f; private var tTr = 0f; private var nTr = 0; private var nLoose = 0
    private var tClo = 0f; private var tSg = 0f; private var nSgRender = 0

    private fun setLitFrameUniforms(shadows: Boolean) {
        GLES20.glUniformMatrix4fv(uVP, 1, false, studio.view.vp, 0)
        GLES20.glUniformMatrix4fv(uShadowVP, 1, false, lightVP, 0)
        GLES20.glUniform3f(uLight, sunDir.x, sunDir.y, sunDir.z)
        val cp = studio.view.camPos
        GLES20.glUniform3f(uCam, cp.x, cp.y, cp.z)
        GLES20.glUniform3fv(uHorizon, 1, horizon, 0)
        GLES20.glUniform3fv(uZenith, 1, zenith, 0)
        GLES20.glUniform3fv(uSun, 1, sunColor, 0)
        GLES20.glUniform1f(uAmbient, ambient)
        GLES20.glUniform3fv(uFogColor, 1, fogColor, 0)
        GLES20.glUniform3f(uFog, fogStart, fogEnd, fogMode)
        GLES20.glUniform1f(uExposure, exposure)
        GLES20.glUniform1f(uShadowOn, if (shadows) 1f else 0f)
        GLES20.glUniform2f(uShadowTexel, shadowTexel(), shadowTexel())
        GLES20.glUniform1f(uTime, time)
        applyLights(lit)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
        GLES20.glUniform1i(uShadow, 0)
        // Material textures (textured variant only; -1 locations are ignored by GL).
        GLES20.glUniform1i(uMatTex, 1)
        GLES20.glUniform1i(uMatNrm, 2)
        // Roblox's surface atlas (studs, inlets...) on unit 3; nothing else uses that unit.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, surfTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uSurfTex, 3)
        GLES20.glUniform1f(uHasSurfTex, if (surfTex != 0) 1f else 0f)
    }

    private fun drawPart(p: Instance, alpha: Float, current: Mesh?, g: Geo = geometry(p), colorOverride: Color3? = null): Mesh {
        if (g.mesh !== current) bind(g.mesh, aPos, aNrm)
        setModel(g.cf, g.scale)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniformMatrix3fv(uNrm, 1, false, normal, 0)
        val c = colorOverride ?: PartData.color(p)
        GLES20.glUniform4f(uColor, c.r, c.g, c.b, alpha)
        val mat = PartData.material(p)
        GLES20.glUniform1f(uEmissive, if (mat == 288 || mat == 1584) 1f else 0f)
        val m = material(mat)
        GLES20.glUniform4f(uMat, m[0], m[1], m[2], m[3] + ((p["Reflectance"] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f))
        // Roblox draws no automatic grid on parts (the Baseplate template's grid is a Texture on it).
        GLES20.glUniform1f(uGrid, 0f)
        // Unions are in stud units already, so their pattern scale is just the resize factor.
        GLES20.glUniform3f(uSize, g.scale.x, g.scale.y, g.scale.z)
        // Surfaces on every face of blocks: A = Right/Top/Back (+X/+Y/+Z), B = Left/Bottom/Front.
        if (g.mesh === cube && g.kind == 0) {
            GLES20.glUniform3f(uSurfA, surf(p, "RightSurface"), surf(p, "TopSurface"), surf(p, "BackSurface"))
            GLES20.glUniform3f(uSurfB, surf(p, "LeftSurface"), surf(p, "BottomSurface"), surf(p, "FrontSurface"))
        } else {
            GLES20.glUniform3f(uSurfA, 0f, 0f, 0f); GLES20.glUniform3f(uSurfB, 0f, 0f, 0f)
        }
        // Trusses render as an open lattice (you can see through them, like Roblox).
        val truss = p.className == "TrussPart"
        GLES20.glUniform1f(uTruss, if (truss && g.mesh === cube) 1f else 0f)
        if (truss) GLES20.glDisable(GLES20.GL_CULL_FACE)
        val subs = g.subs
        if (subs != null) {
            // A union: one mesh per source color.
            var last = g.mesh
            for ((sm, col) in subs) {
                if (sm !== last) { bind(sm, aPos, aNrm); last = sm }
                val cc = colorOverride ?: col
                GLES20.glUniform4f(uColor, cc.r, cc.g, cc.b, alpha)
                GLES20.glDrawArrays(sm.mode, 0, sm.count)
            }
            if (truss) GLES20.glEnable(GLES20.GL_CULL_FACE)
            return last
        }
        GLES20.glDrawArrays(g.mesh.mode, 0, g.mesh.count)
        if (truss) GLES20.glEnable(GLES20.GL_CULL_FACE)
        return g.mesh
    }

    // ------------------------------------------------------------------ file meshes (textured)

    private var meshProg = 0
    private var mVP = 0; private var mModel = 0; private var mNrm = 0; private var mColor = 0; private var mLight = 0; private var mSun = 0
    private var mAmbient = 0; private var mTex = 0; private var mHasTex = 0; private var mShadow = 0; private var mShadowVP = 0; private var mShadowOn = 0
    private var mShadowTexel = 0; private var mCam = 0; private var mFogColor = 0; private var mFog = 0; private var mExposure = 0; private var mTint = 0
    private var mEmissive = 0; private var mHorizon = 0; private var mZenith = 0
    private var maPos = 0; private var maNrm = 0; private var maUv = 0
    private var meshFrame = -1

    private fun setupMeshProgram() {
        meshProg = program(MESH_VS, MESH_FS)
        fun u(n: String) = GLES20.glGetUniformLocation(meshProg, n)
        mVP = u("uVP"); mModel = u("uModel"); mNrm = u("uNrm"); mColor = u("uColor"); mLight = u("uLight"); mSun = u("uSun")
        mAmbient = u("uAmbient"); mTex = u("uTex"); mHasTex = u("uHasTex"); mShadow = u("uShadow"); mShadowVP = u("uShadowVP")
        mShadowOn = u("uShadowOn"); mShadowTexel = u("uShadowTexel"); mCam = u("uCam"); mFogColor = u("uFogColor"); mFog = u("uFog")
        mExposure = u("uExposure"); mTint = u("uTint"); mEmissive = u("uEmissive"); mHorizon = u("uHorizon"); mZenith = u("uZenith")
        maPos = GLES20.glGetAttribLocation(meshProg, "aPos"); maNrm = GLES20.glGetAttribLocation(meshProg, "aNrm"); maUv = GLES20.glGetAttribLocation(meshProg, "aUv")
    }

    /** Texture for a mesh/clothing/MaterialVariant image: the real image when signed in, else the public thumbnail. */
    private fun assetTexture(content: String?, repeat: Boolean): Int {
        // Textures the avatar builder generated (classic clothing composited for R15 bodies).
        if (content != null && content.startsWith(com.leaf.lemonade.engine.Avatar.TEX_PREFIX)) {
            textures[content]?.let { return it }
            val bmp = com.leaf.lemonade.engine.Avatar.texture(content) ?: return 0
            return upload(bmp, repeat).also { textures[content] = it }
        }
        val id = AssetFetcher.idOf(content) ?: return textureFor(content, repeat)
        val key = (if (repeat) rawKeysR else rawKeys).getOrPut(id) { "raw:$id" + if (repeat) "#r" else "" }
        textures[key]?.let { return it }
        if (canDownload()) {
            val bmp = AssetFetcher.image(id)
            if (bmp != null) { val t = upload(bmp, repeat); textures[key] = t; return t }
            // Some images can't be downloaded (permissions): the public thumbnail is close enough.
            if (AssetFetcher.imageFailed(id)) return textureFor(content, repeat)
            return 0
        }
        return textureFor(content, repeat)
    }

    private val rawKeys = HashMap<Long, String>()
    private val rawKeysR = HashMap<Long, String>()
    private var canDownloadFrame = -1
    private var canDownloadNow = false

    /** Signed in (checked once per frame; it reads preferences). */
    private fun canDownload(): Boolean {
        if (canDownloadFrame != frames) {
            canDownloadFrame = frames
            val ctx = studio.app
            canDownloadNow = !RobloxApi.isGuest(ctx)
        }
        return canDownloadNow
    }

    private fun drawFileMesh(p: Instance, g: Geo, alpha: Float) {
        GLES20.glUseProgram(meshProg)
        activeLit = null
        if (meshFrame != frames) {
            meshFrame = frames
            GLES20.glUniformMatrix4fv(mVP, 1, false, studio.view.vp, 0)
            GLES20.glUniformMatrix4fv(mShadowVP, 1, false, lightVP, 0)
            GLES20.glUniform3f(mLight, sunDir.x, sunDir.y, sunDir.z)
            GLES20.glUniform3fv(mSun, 1, sunColor, 0)
            GLES20.glUniform1f(mAmbient, ambient)
            val cp = studio.view.camPos
            GLES20.glUniform3f(mCam, cp.x, cp.y, cp.z)
            GLES20.glUniform3fv(mFogColor, 1, fogColor, 0)
            GLES20.glUniform3f(mFog, fogStart, fogEnd, fogMode)
            GLES20.glUniform1f(mExposure, exposure)
            GLES20.glUniform1f(mShadowOn, if (frameShadows) 1f else 0f)
            GLES20.glUniform2f(mShadowTexel, shadowTexel(), shadowTexel())
            GLES20.glUniform3fv(mHorizon, 1, horizon, 0)
            GLES20.glUniform3fv(mZenith, 1, zenith, 0)
            GLES20.glUniform1i(mShadow, 0)
            GLES20.glUniform1i(mTex, 1)
            applyLights(meshProg)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
        val tex = g.tex?.let { assetTexture(it, true) } ?: 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(mHasTex, if (tex != 0) 1f else 0f)
        val c = PartData.color(p)
        GLES20.glUniform4f(mColor, c.r, c.g, c.b, alpha)
        val t = g.tint ?: Vector3(1f, 1f, 1f)
        GLES20.glUniform3f(mTint, t.x, t.y, t.z)
        val mat = PartData.material(p)
        GLES20.glUniform1f(mEmissive, if (mat == 288) 1f else 0f)
        setModel(g.cf, g.scale)
        GLES20.glUniformMatrix4fv(mModel, 1, false, model, 0)
        GLES20.glUniformMatrix3fv(mNrm, 1, false, normal, 0)
        val m = g.mesh
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
        GLES20.glEnableVertexAttribArray(maPos)
        GLES20.glVertexAttribPointer(maPos, 3, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glEnableVertexAttribArray(maNrm)
        GLES20.glVertexAttribPointer(maNrm, 3, GLES20.GL_FLOAT, false, 32, 12)
        GLES20.glEnableVertexAttribArray(maUv)
        GLES20.glVertexAttribPointer(maUv, 2, GLES20.GL_FLOAT, false, 32, 24)
        // Roblox meshes are often single-sided art made to be seen from both sides (hair, cloth).
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDrawArrays(m.mode, 0, m.count)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDisableVertexAttribArray(maUv)
    }

    // ------------------------------------------------------------------ constraints (ropes, rods, springs)

    private fun attachmentWorld(a: Instance?): Vector3? {
        val part = a?.parent?.takeIf { it.isA("BasePart") } ?: return null
        return PartData.cframe(part) * ((a["CFrame"] as? CFrame)?.position ?: Vector3.ZERO)
    }

    /** One straight piece of a constraint, drawn as a thin lit box. */
    private fun segment(a: Vector3, b: Vector3, thick: Float) {
        val d = b - a
        val len = d.magnitude
        if (len < 1e-4f) return
        val up = if (kotlin.math.abs(d.y / len) > 0.95f) Vector3.Z else Vector3.Y
        setModel(CFrame.lookAt((a + b) / 2f, b, up), Vector3(thick, thick, len + thick * 0.5f))
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniformMatrix3fv(uNrm, 1, false, normal, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cube.count)
    }

    /**
     * Visible RopeConstraints sag (a parabola as long as the rope), RodConstraints are straight
     * and SpringConstraints are coils, like Studio draws them.
     */
    private fun drawConstraints() {
        val list = studio.renderConstraints()
        if (list.isEmpty()) return
        val l = litBasic ?: return
        useLit(l); setLitFrameUniforms(frameShadows)
        bind(cube, aPos, aNrm)
        GLES20.glUniform1f(uEmissive, 0f); GLES20.glUniform1f(uGrid, 0f); GLES20.glUniform1f(uTruss, 0f)
        GLES20.glUniform4f(uMat, 0f, 0f, 1f, 0.1f)
        GLES20.glUniform3f(uSurfA, 0f, 0f, 0f); GLES20.glUniform3f(uSurfB, 0f, 0f, 0f)
        GLES20.glUniform3f(uSize, 1f, 1f, 1f)
        val cp = studio.view.camPos
        for (c in list) {
            if (c.parent == null || c["Visible"] != true || c["Enabled"] == false) continue
            val p0 = attachmentWorld(c["Attachment0"] as? Instance) ?: continue
            val p1 = attachmentWorld(c["Attachment1"] as? Instance) ?: continue
            val mid = (p0 + p1) / 2f
            val r = (p1 - p0).magnitude / 2f + 2f
            if (!frustum.sphere(mid.x, mid.y, mid.z, r)) continue
            val col = (c["Color"] as? BrickColorV)?.color ?: Color3.fromRGB(163, 162, 165)
            GLES20.glUniform4f(uColor, col.r, col.g, col.b, 1f)
            val thick = ((c["Thickness"] as? Number)?.toFloat() ?: 0.1f).coerceIn(0.02f, 5f)
            val dist = (p1 - p0).magnitude
            when (c.className) {
                "RopeConstraint" -> {
                    val len = ((c["Length"] as? Number)?.toFloat() ?: dist).coerceAtLeast(dist)
                    // Sag so the curve is about as long as the rope (small-sag parabola).
                    val sag = if (len > dist + 0.01f) sqrt((len * len - dist * dist) * 3f / 16f).coerceAtMost(len / 2f) else 0f
                    val n = if (sag > 0.05f) 14 else 1
                    var prev = p0
                    for (i in 1..n) {
                        val t = i / n.toFloat()
                        val pt = p0 + (p1 - p0) * t - Vector3(0f, sag * 4f * t * (1f - t), 0f)
                        segment(prev, pt, thick); prev = pt
                    }
                }
                "SpringConstraint" -> {
                    val coils = ((c["Coils"] as? Number)?.toFloat() ?: 8f).coerceIn(0f, 64f)
                    val rad = ((c["Radius"] as? Number)?.toFloat() ?: 0.4f).coerceIn(0f, 20f)
                    val axis = (p1 - p0)
                    if (axis.magnitude < 1e-3f) continue
                    val f = axis.unit
                    val side = (if (kotlin.math.abs(f.y) > 0.9f) Vector3.X else Vector3.Y).cross(f).unit
                    val side2 = f.cross(side).unit
                    val steps = (coils * 12f).toInt().coerceIn(8, 600)
                    var prev = p0
                    for (i in 1..steps) {
                        val t = i / steps.toFloat()
                        val ang = t * coils * 2f * Math.PI.toFloat()
                        val ring = if (t < 0.04f || t > 0.96f) 0f else rad
                        val pt = p0 + axis * t + side * (cos(ang) * ring) + side2 * (sin(ang) * ring)
                        segment(prev, pt, thick); prev = pt
                    }
                }
                else -> segment(p0, p1, thick)
            }
            if ((mid - cp).magnitude < closest) closest = ((mid - cp).magnitude - r).coerceAtLeast(0f)
        }
    }

    // ------------------------------------------------------------------ adornments (SelectionBox, Highlight...)

    private fun adorneeParts(a: Instance): List<Instance> {
        val ad = (a["Adornee"] as? Instance) ?: if (a.className == "Highlight") a.parent else null
        ad ?: return emptyList()
        return when {
            ad.isA("BasePart") -> listOf(ad)
            ad.isA("Model") || ad.className == "Folder" -> ad.descendants().filter { it.isA("BasePart") && it.parent?.isA("PartOperation") != true }
            else -> emptyList()
        }
    }

    private fun drawAdornments() {
        val list = studio.renderAdornments()
        if (list.isEmpty()) return
        GLES20.glUseProgram(line)
        GLES20.glUniformMatrix4fv(lVP, 1, false, studio.view.vp, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for (a in list) {
            if (a.parent == null || a["Visible"] == false || a["Enabled"] == false) continue
            when (a.className) {
                "SelectionBox", "SelectionSphere" -> {
                    val c = a["Color3"] as? Color3 ?: Color3.fromRGB(13, 105, 172)
                    val alpha = 1f - ((a["Transparency"] as? Number)?.toFloat() ?: 0f)
                    val w = ((a["LineThickness"] as? Number)?.toFloat() ?: 0.15f)
                    GLES20.glLineWidth((w * 20f).coerceIn(1.5f, 6f))
                    bind(boxLines, laPos, -1)
                    for (p in adorneeParts(a)) {
                        setModel(PartData.cframe(p), PartData.size(p) + Vector3(w, w, w))
                        GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                        GLES20.glUniform4f(lColor, c.r, c.g, c.b, alpha)
                        GLES20.glDrawArrays(GLES20.GL_LINES, 0, boxLines.count)
                    }
                }
                "Highlight" -> {
                    val parts = adorneeParts(a)
                    val onTop = ((a["DepthMode"] as? EnumValue)?.value ?: 0) == 0
                    if (onTop) GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                    val fill = a["FillColor"] as? Color3 ?: Color3(1f, 0f, 0f)
                    val fa = 1f - ((a["FillTransparency"] as? Number)?.toFloat() ?: 0.5f)
                    if (fa > 0.01f) {
                        GLES20.glDepthMask(false)
                        for (p in parts) {
                            val g = geometry(p)
                            setModel(g.cf, g.scale)
                            GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                            GLES20.glUniform4f(lColor, fill.r, fill.g, fill.b, fa * 0.6f)
                            for (m in g.subs?.map { it.first } ?: listOf(g.mesh)) { bind(m, laPos, -1); GLES20.glDrawArrays(m.mode, 0, m.count) }
                        }
                        GLES20.glDepthMask(true)
                    }
                    val oc = a["OutlineColor"] as? Color3 ?: Color3(1f, 1f, 1f)
                    val oa = 1f - ((a["OutlineTransparency"] as? Number)?.toFloat() ?: 0f)
                    if (oa > 0.01f) {
                        GLES20.glLineWidth(2.5f)
                        bind(boxLines, laPos, -1)
                        for (p in parts) {
                            setModel(PartData.cframe(p), PartData.size(p) * 1.01f)
                            GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                            GLES20.glUniform4f(lColor, oc.r, oc.g, oc.b, oa)
                            GLES20.glDrawArrays(GLES20.GL_LINES, 0, boxLines.count)
                        }
                    }
                    if (onTop) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                }
                "Handles", "ArcHandles" -> {
                    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                    for (t in AdornHandles.tips(a, studio.view)) {
                        val shaft = t.tip - t.base
                        val len = shaft.magnitude
                        GLES20.glUniform4f(lColor, t.color.r, t.color.g, t.color.b, 1f)
                        if (len > 0.001f) {
                            bind(cube, laPos, -1)
                            val up = if (kotlin.math.abs(t.axis.y) > 0.9f) Vector3.Z else Vector3.Y
                            setModel(CFrame.lookAt((t.base + t.tip) / 2f, t.tip, up), Vector3(t.radius * 0.35f, t.radius * 0.35f, len))
                            GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cube.count)
                        }
                        val tipMesh = if (a.className == "Handles" && ((a["Style"] as? EnumValue)?.value ?: 0) == 0) sphere else cube
                        bind(tipMesh, laPos, -1)
                        val dd = t.radius * 2f
                        setModel(CFrame.at(t.tip), Vector3(dd, dd, dd))
                        GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tipMesh.count)
                    }
                    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                }
                "BoxHandleAdornment" -> {
                    val ad = (a["Adornee"] as? Instance)?.takeIf { it.isA("BasePart") } ?: continue
                    val c = a["Color3"] as? Color3 ?: Color3(1f, 1f, 1f)
                    val alpha = 1f - ((a["Transparency"] as? Number)?.toFloat() ?: 0f)
                    val s = a["Size"] as? Vector3 ?: Vector3(1f, 1f, 1f)
                    val off = a["CFrame"] as? CFrame ?: CFrame.IDENTITY
                    val onTop = a["AlwaysOnTop"] == true
                    if (onTop) GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                    bind(cube, laPos, -1)
                    setModel(PartData.cframe(ad) * off, s)
                    GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                    GLES20.glUniform4f(lColor, c.r, c.g, c.b, alpha)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cube.count)
                    if (onTop) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                }
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glLineWidth(1f)
    }

    // ------------------------------------------------------------------ decals

    private fun upload(bmp: Bitmap, repeat: Boolean): Int {
        val src = if (repeat) {
            fun pot(n: Int): Int { var v = 1; while (v < n && v < 1024) v *= 2; return v }
            val w = pot(bmp.width); val h = pot(bmp.height)
            if (w != bmp.width || h != bmp.height) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        } else bmp
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)
        val wrap = if (repeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        if (repeat) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            // Tiled textures are seen at grazing angles (floors, baseplates): anisotropic filtering
            // keeps them sharp instead of smearing into a blur.
            if (aniso > 1f) GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY, aniso)
        } else GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        return ids[0]
    }

    private val repeatKeys = HashMap<String, String>()

    private fun textureFor(content: String?, repeat: Boolean): Int {
        val key = ImageLoader.keyFor(content) ?: return 0
        val k2 = if (repeat) repeatKeys.getOrPut(key) { "$key#r" } else key
        textures[k2]?.let { return it }
        val bmp = ImageLoader.getKey(key) ?: return 0
        val id = upload(bmp, repeat)
        textures[k2] = id
        return id
    }

    /** Placement of a flat quad on one face of a part: local transform, width, height, world normal. */
    private class FaceQuad(val local: CFrame, val w: Float, val h: Float, val normal: Vector3)

    private fun faceQuad(g: Geo, faceId: Int, lift: Float): FaceQuad {
        val s = g.scale
        val (nrm, up) = when (faceId) {
            0 -> Vector3.X to Vector3.Y
            1 -> Vector3.Y to Vector3(0f, 0f, -1f)
            2 -> Vector3.Z to Vector3.Y
            3 -> Vector3(-1f, 0f, 0f) to Vector3.Y
            4 -> Vector3(0f, -1f, 0f) to Vector3.Z
            else -> Vector3(0f, 0f, -1f) to Vector3.Y
        }
        val right = up.cross(nrm)
        fun extent(v: Vector3) = kotlin.math.abs(v.x) * s.x + kotlin.math.abs(v.y) * s.y + kotlin.math.abs(v.z) * s.z
        val w = extent(right); val h = extent(up); val depth = extent(nrm)
        return FaceQuad(CFrame.fromColumns(nrm * (depth / 2f + lift), right, up, nrm), w, h, g.cf.rotationOnly() * nrm)
    }

    /** Cheap change detector for a SurfaceGui's contents. */
    private fun signature(sg: Instance): Long {
        var h = 17L
        var images = false
        for (d in sg.descendants()) {
            h = h * 31 + d.className.hashCode()
            for (k in SIG_PROPS) h = h * 31 + (d.props[k]?.hashCode() ?: 0)
            if (d.props.containsKey("Image")) images = true
            if (h == 0L) h = 1
        }
        // Only GUIs showing images need repainting when some image finishes loading (re-painting
        // hundreds of text-only road signs on every image load stalled big maps).
        if (images) h = h * 31 + ImageLoader.epoch
        return h
    }

    private fun drawSurfaceGuis() {
        val painter = surfacePainter ?: return
        val list = studio.renderSurfaceGuis()
        if (list.isEmpty() && surfaceTex.isEmpty()) return
        val cp = studio.view.camPos
        val fwd = studio.camera.forward
        var renders = 0
        for (sg in list) {
            if (sg.parent == null || sg["Enabled"] == false) continue
            val part = (sg["Adornee"] as? Instance)?.takeIf { it.isA("BasePart") } ?: sg.parent?.takeIf { it.isA("BasePart") } ?: continue
            val g = geometry(part)
            if (!visible(g.cf, g.scale, cp, fwd)) continue
            val dist = (g.cf.position - cp).magnitude
            val maxD = ((sg["MaxDistance"] as? Number)?.toFloat() ?: 0f).let { if (it <= 0f) 450f else minOf(it, 450f) }
            if (dist > maxD + g.scale.magnitude) continue
            val fq = faceQuad(g, (sg["Face"] as? EnumValue)?.value ?: 5, 0.004f)
            if (fq.w <= 0.01f || fq.h <= 0.01f) continue
            val pps = ((sg["PixelsPerStud"] as? Number)?.toFloat() ?: 50f).coerceAtLeast(1f)
            val cs = sg["CanvasSize"] as? Vector2 ?: Vector2(800f, 600f)
            val fixed = ((sg["SizingMode"] as? EnumValue)?.value ?: 0) == 0 && sg.props.containsKey("CanvasSize")
            val cw = if (fixed) cs.x.coerceAtLeast(1f) else fq.w * pps
            val ch = if (fixed) cs.y.coerceAtLeast(1f) else fq.h * pps
            val k = minOf(1f, 384f / maxOf(cw, ch))
            val bw = (cw * k).toInt().coerceIn(8, 1024); val bh = (ch * k).toInt().coerceIn(8, 1024)
            var e = surfaceTex[sg]
            if (e == null || (time - e.checked > 0.5f && renders < 4)) {
                val sig = signature(sg) * 31 + bw * 7 + bh
                if (e == null || sig != e.sig) {
                    if (renders >= 4 && e != null) { e.checked = time } else {
                        // One scratch bitmap per size; pixels live on the GPU, not the Java heap.
                        val bmp = scratch.takeIf { it != null && it.width == bw && it.height == bh } ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { scratch?.recycle(); scratch = it }
                        try { painter(sg, cw, ch, bmp) } catch (ex: Exception) { OutputLog.warn("SurfaceGui ${sg.name}: ${ex.message}") }
                        renders++; nSgRender++
                        val tex = e?.tex ?: IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                        if (e == null) { e = SurfaceTex(tex, bw, bh, sig, time, time); surfaceTex[sg] = e } else { e.w = bw; e.h = bh; e.sig = sig; e.checked = time }
                    }
                } else e.checked = time
            }
            val entry = e ?: continue
            entry.used = time
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, entry.tex)
            val influence = ((sg["LightInfluence"] as? Number)?.toFloat() ?: 1f).coerceIn(0f, 1f)
            val bright = ((sg["Brightness"] as? Number)?.toFloat() ?: 1f).coerceIn(0f, 5f)
            GLES20.glUniform1f(cAmbient, (ambient * influence + (1f - influence)) * bright)
            GLES20.glUniform3f(cSun, sunColor[0] * influence * bright, sunColor[1] * influence * bright, sunColor[2] * influence * bright)
            GLES20.glUniform4f(cTint, 1f, 1f, 1f, 1f)
            setModel(g.cf * fq.local, Vector3(fq.w, fq.h, 1f))
            GLES20.glUniformMatrix4fv(cModel, 1, false, model, 0)
            GLES20.glUniform3f(cNormal, fq.normal.x, fq.normal.y, fq.normal.z)
            GLES20.glUniform4f(cUv, 1f, 1f, 0f, 0f)
            val onTop = sg["AlwaysOnTop"] == true
            if (onTop) GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            drawUvMesh(quad)
            if (onTop) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
        // Free textures for SurfaceGuis that haven't been on screen for a while.
        if (surfaceTex.isNotEmpty()) {
            val it = surfaceTex.entries.iterator()
            while (it.hasNext()) {
                val (k, v) = it.next()
                if (time - v.used > 6f || k.parent == null) { GLES20.glDeleteTextures(1, intArrayOf(v.tex), 0); it.remove() }
            }
        }
        GLES20.glUniform3fv(cSun, 1, sunColor, 0)
        GLES20.glUniform1f(cAmbient, ambient)
    }

    // ------------------------------------------------------------------ clothing

    /** Roblox's classic clothing template (585x559): pixel rects of each body face. */
    private class Region(val x: Float, val y: Float, val w: Float, val h: Float, val flip: Boolean = false)
    private val TW = 585f; private val TH = 559f
    // Face ids follow NormalId: 0 Right(+X) 1 Top 2 Back(+Z) 3 Left(-X) 4 Bottom 5 Front(-Z).
    private val torsoRegions = mapOf(
        5 to Region(231f, 74f, 128f, 128f), 2 to Region(427f, 74f, 128f, 128f), 0 to Region(165f, 74f, 64f, 128f),
        3 to Region(361f, 74f, 64f, 128f), 1 to Region(231f, 8f, 128f, 64f, true), 4 to Region(231f, 204f, 128f, 64f, true),
    )
    private val rightLimbRegions = mapOf(
        5 to Region(217f, 355f, 64f, 128f), 0 to Region(151f, 355f, 64f, 128f), 2 to Region(85f, 355f, 64f, 128f),
        3 to Region(19f, 355f, 64f, 128f), 1 to Region(217f, 289f, 64f, 64f, true), 4 to Region(217f, 485f, 64f, 64f, true),
    )
    private val leftLimbRegions = mapOf(
        5 to Region(308f, 355f, 64f, 128f), 3 to Region(374f, 355f, 64f, 128f), 2 to Region(440f, 355f, 64f, 128f),
        0 to Region(506f, 355f, 64f, 128f), 1 to Region(308f, 289f, 64f, 64f, true), 4 to Region(308f, 485f, 64f, 64f, true),
    )

    private fun drawTemplateFace(part: Instance, g: Geo, face: Int, r: Region, tex: Int, tint: Color3) {
        // Half-pixel inset so neighbouring regions don't bleed in.
        val x = (r.x + 0.5f) / TW; val y = (r.y + 0.5f) / TH; val w = (r.w - 1f) / TW; val h = (r.h - 1f) / TH
        if (g.mesh === r6Torso || g.mesh === r6Limb) {
            // Bevelled R6 limbs: the template is projected onto the shape, wrapping its edges.
            if (r.flip) drawProjectedDecal(part, g, face, tex, tint, 1f, -w, -h, x + w, y + h)
            else drawProjectedDecal(part, g, face, tex, tint, 1f, w, h, x, y)
            return
        }
        val fq = faceQuad(g, face, 0.003f)
        setModel(g.cf * fq.local, Vector3(fq.w, fq.h, 1f))
        GLES20.glUniformMatrix4fv(cModel, 1, false, model, 0)
        GLES20.glUniform3f(cNormal, fq.normal.x, fq.normal.y, fq.normal.z)
        if (r.flip) GLES20.glUniform4f(cUv, -w, -h, x + w, y + h) else GLES20.glUniform4f(cUv, w, h, x, y)
        drawUvMesh(quad)
    }

    // ------------------------------------------------------------------ R15 clothing in places

    /** Composited clothing textures per (templates + body colors): body group -> texture content. */
    private val r15Tex = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    private val r15Pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val r15Pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** This frame's clothing textures for R15 body MeshParts (used by [geometry] next frame). */
    @Volatile private var partTexOverride: Map<Instance, String> = emptyMap()
    private var nextOverride = java.util.IdentityHashMap<Instance, String>()

    /** An image for compositing: the full asset when signed in, else the public thumbnail. */
    private fun clothingImage(content: String?): Pair<Boolean, Bitmap?> {
        if (content.isNullOrBlank()) return true to null
        val id = AssetFetcher.idOf(content) ?: return true to null
        val ctx = studio.app
        if (!RobloxApi.isGuest(ctx)) {
            AssetFetcher.imageBlocking(id)?.let { return true to it }
        }
        val b = ImageLoader.get(content)
        return (b != null) to b
    }

    /**
     * Shirts/Pants on an R15 character in the place (NPCs, rigs, characters saved in a game):
     * Roblox composites the template for the R15 meshes (see ClothingCompositor). Built in the
     * background; until then the body shows its plain colors.
     */
    private fun r15Clothing(rig: Instance, items: List<Instance>) {
        val av = com.leaf.lemonade.engine.Avatar
        val shirt = items.firstOrNull { it.className == "Shirt" }?.get("ShirtTemplate")?.toString()?.takeIf { it.isNotBlank() }
        val pants = items.firstOrNull { it.className == "Pants" }?.get("PantsTemplate")?.toString()?.takeIf { it.isNotBlank() }
        val tee = items.firstOrNull { it.className == "ShirtGraphic" }?.get("Graphic")?.toString()?.takeIf { it.isNotBlank() }
        if (shirt == null && pants == null && tee == null) return
        val parts = rig.children.filter { it.isA("BasePart") }
        val colors = HashMap<String, Color3>()
        for (p in parts) av.r15GroupOf(p.name)?.let { g -> av.r15ColorKey(g)?.let { k -> colors.putIfAbsent(k, PartData.color(p)) } }
        val key = "$shirt|$pants|$tee|" + colors.entries.sortedBy { it.key }.joinToString(",") { it.key + "=" + it.value.toHex() }
        val tex = r15Tex[key]
        if (tex == null) {
            if (r15Pending.add(key)) r15Pool.execute {
                try {
                    val (s1, sb) = clothingImage(shirt); val (s2, pb) = clothingImage(pants); val (s3, tb) = clothingImage(tee)
                    if (s1 && s2 && s3) {
                        val tag = Integer.toHexString(key.hashCode())
                        val made = av.composeClothingBitmaps(colors, sb, pb, tb).mapValues { (g, bmp) ->
                            val content = "${av.TEX_PREFIX}r15/$tag/$g"
                            av.putTexture(content, bmp)
                            content
                        }
                        r15Tex[key] = made
                    }
                } catch (e: Exception) {
                    OutputLog.warn("Clothing on ${rig.name}: ${e.message ?: e}")
                    r15Tex[key] = emptyMap()
                } finally {
                    // Images still loading: try again on a later frame.
                    r15Pending.remove(key)
                }
            }
            return
        }
        for (p in parts) { val g = av.r15GroupOf(p.name) ?: continue; tex[g]?.let { nextOverride[p] = it } }
    }

    private fun drawClothing() {
        val list = studio.renderClothing()
        if (list.isEmpty()) { if (partTexOverride.isNotEmpty()) partTexOverride = emptyMap(); return }
        // R15 bodies (MeshParts with UpperTorso) get composited textures instead of R6 template faces.
        nextOverride = java.util.IdentityHashMap()
        for ((rig, items) in list.groupBy { it.parent }) {
            if (rig == null) continue
            val upper = rig.findFirstChild("UpperTorso")?.takeIf { it.className == "MeshPart" } ?: continue
            // The playtest avatar already wears its clothing in its body textures.
            if ((upper["TextureID"]?.toString() ?: "").startsWith(com.leaf.lemonade.engine.Avatar.TEX_PREFIX)) continue
            r15Clothing(rig, items)
        }
        partTexOverride = nextOverride
        val cp = studio.view.camPos
        val fwd = studio.camera.forward
        val play = studio.play
        // Pants first, then the shirt over them, then the T-shirt.
        for (c in list.sortedBy { when (it.className) { "Pants" -> 0; "Shirt" -> 1; else -> 2 } }) {
            val rig = c.parent ?: continue
            val torso = rig.findFirstChild("Torso")?.takeIf { it.isA("BasePart") } ?: continue
            if (PartData.transparency(torso) >= 0.99f) continue
            if (play?.hiddenInFirstPerson(torso) == true) continue
            val tg = geometry(torso)
            if (!visible(tg.cf, tg.scale * 3f, cp, fwd)) continue
            val content = (c["ShirtTemplate"] ?: c["PantsTemplate"] ?: c["Graphic"])?.toString()
            val tex = assetTexture(content, false)
            if (tex == 0) continue
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            val tint = c["Color3"] as? Color3 ?: Color3(1f, 1f, 1f)
            GLES20.glUniform4f(cTint, tint.r, tint.g, tint.b, 1f)
            when (c.className) {
                "ShirtGraphic" -> {
                    if (tg.kind == 2 && tg.mesh.stride == 8) {
                        // A body-package torso: the T-shirt is projected onto its front.
                        val sz = PartData.size(torso); val k = (minOf(sz.x, sz.y) * 0.98f).let { s -> floatArrayOf(sz.x / s, sz.y / s) }
                        drawProjectedDecal(torso, tg, 5, tex, tint, 1f, k[0], k[1], (1f - k[0]) / 2f, (1f - k[1]) / 2f)
                        continue
                    }
                    if (tg.mesh === r6Torso) {
                        val sz = PartData.size(torso); val k = (minOf(sz.x, sz.y) * 0.98f).let { s -> floatArrayOf(sz.x / s, sz.y / s) }
                        drawProjectedDecal(torso, tg, 5, tex, tint, 1f, k[0], k[1], (1f - k[0]) / 2f, (1f - k[1]) / 2f)
                        continue
                    }
                    val fq = faceQuad(tg, 5, 0.006f)
                    val s = minOf(fq.w, fq.h) * 0.98f
                    setModel(tg.cf * fq.local, Vector3(s, s, 1f))
                    GLES20.glUniformMatrix4fv(cModel, 1, false, model, 0)
                    GLES20.glUniform3f(cNormal, fq.normal.x, fq.normal.y, fq.normal.z)
                    GLES20.glUniform4f(cUv, 1f, 1f, 0f, 0f)
                    drawUvMesh(quad)
                }
                else -> {
                    val shirt = c.className == "Shirt"
                    // Body-package meshes (kind 2) wear the clothing through their own UVs.
                    if (tg.kind == 0) for ((f, r) in torsoRegions) drawTemplateFace(torso, tg, f, r, tex, tint)
                    val limbs = if (shirt) listOf("Right Arm" to rightLimbRegions, "Left Arm" to leftLimbRegions)
                    else listOf("Right Leg" to rightLimbRegions, "Left Leg" to leftLimbRegions)
                    for ((name, regions) in limbs) {
                        val limb = rig.findFirstChild(name)?.takeIf { it.isA("BasePart") && PartData.transparency(it) < 0.99f } ?: continue
                        val lg = geometry(limb)
                        if (lg.kind != 0 || (lg.mesh !== cube && lg.mesh !== r6Limb && lg.mesh !== r6Torso)) continue
                        for ((f, r) in regions) drawTemplateFace(limb, lg, f, r, tex, tint)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ MaterialVariants

    // MaterialVariants in MaterialService, indexed by name (rebuilt twice a second).
    private var variantIndexAt = 0L
    private var variantIndex: Map<String, List<Instance>> = emptyMap()
    private var variantService: Instance? = null

    private fun refreshVariants() {
        val now = System.currentTimeMillis()
        if (now - variantIndexAt < 500) return
        variantIndexAt = now
        variantBinds.clear()
        val ms = studio.model.findService("MaterialService")
        variantService = ms
        variantIndex = ms?.descendants()?.filter { it.className == "MaterialVariant" }?.groupBy { it.name } ?: emptyMap()
        // A place can ask for the pre-2022 materials (MaterialService.Use2022Materials = false).
        // Files store it as Use2022MaterialsXml (older builds of this app wrote Use2022Materials).
        val use2022 = ms?.let { it["Use2022MaterialsXml"] ?: it["Use2022Materials"] }
        val modern = com.leaf.lemonade.core.Settings.modernMaterials && use2022 != false
        if (modern != modernMaterialsNow) { modernMaterialsNow = modern; batches.reset(true) }
    }

    /**
     * MaterialVariant for a part: the variant named by its MaterialVariant property, or the
     * MaterialService override for its material ("<Material>Name"), if its base material matches.
     */
    private fun variantFor(p: Instance): Instance? {
        if (variantIndex.isEmpty()) return null
        val mat = PartData.material(p)
        val name = (p["MaterialVariantSerialized"] ?: p["MaterialVariant"])?.toString()?.takeIf { it.isNotEmpty() }
            ?: Enums.itemName("Material", mat)?.let { variantService?.get(it + "Name") }?.toString()?.takeIf { it.isNotEmpty() }
            ?: return null
        return variantIndex[name]?.firstOrNull { ((it["BaseMaterial"] as? EnumValue)?.value ?: mat) == mat }
    }

    private fun drawDecals() {
        val decals = studio.renderDecals()
        val hasSurface = studio.renderSurfaceGuis().isNotEmpty() || surfaceTex.isNotEmpty()
        if (decals.isEmpty() && !hasSurface && studio.renderClothing().isEmpty()) {
            if (partTexOverride.isNotEmpty()) partTexOverride = emptyMap()
            return
        }
        GLES20.glUseProgram(decalProg)
        GLES20.glUniformMatrix4fv(cVP, 1, false, studio.view.vp, 0)
        GLES20.glUniform3f(cLight, sunDir.x, sunDir.y, sunDir.z)
        GLES20.glUniform3fv(cSun, 1, sunColor, 0)
        GLES20.glUniform1f(cAmbient, ambient)
        GLES20.glUniform1i(cTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(-1f, -2f)
        val cp = studio.view.camPos
        val fwd = studio.camera.forward
        val play = studio.play
        for (dcl in decals) {
            val part = dcl.parent ?: continue
            if (!part.isA("BasePart") || PartData.transparency(part) >= 0.999f) continue
            // First person hides your own character, its face included.
            if (play?.hiddenInFirstPerson(part) == true) continue
            val a = 1f - ((dcl["Transparency"] as? Number)?.toFloat() ?: 0f)
            if (a <= 0.001f) continue
            val g = geometry(part)
            if (!visible(g.cf, g.scale, cp, fwd)) continue
            val content = PartData.content(dcl, "Texture", "TextureContent", "ColorMap", "ColorMapContent")
            val isFace = dcl.name == "face" && part.name == "Head" && (content.isNullOrEmpty() || content.startsWith("rbxasset://", true) || ImageLoader.keyFor(content) == null)
            val tiling = dcl.className == "Texture"
            // Roblox's baseplate grid: its thumbnail loses the alpha channel (comes back solid
            // white), so it's drawn from a local copy of the same simple grid.
            val tex = if (isFace) faceTex else if (AssetFetcher.idOf(content) == BASEPLATE_GRID_ID) gridTexture()
                else if (content.equals(SPAWN_DECAL, true)) spawnTexture() else assetTexture(content, tiling)
            if (tex == 0) continue
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            val tint = dcl["Color3"] as? Color3 ?: Color3(1f, 1f, 1f)
            GLES20.glUniform4f(cTint, tint.r, tint.g, tint.b, a)
            val faceId = (dcl["Face"] as? EnumValue)?.value ?: 5
            if (g.kind == 2 && dcl.className == "Decal" && g.mesh.stride == 8) {
                drawProjectedDecal(part, g, faceId, tex, tint, a)
                continue
            }
            if (g.mesh === head && faceId == 5) {
                setModel(g.cf, g.scale)
                GLES20.glUniformMatrix4fv(cModel, 1, false, model, 0)
                val n = g.cf.lookVector
                GLES20.glUniform3f(cNormal, n.x, n.y, n.z)
                GLES20.glUniform4f(cUv, 1f, 1f, 0f, 0f)
                drawUvMesh(headFace)
                continue
            }
            val fq = faceQuad(g, faceId, 0.002f)
            val w = fq.w; val h = fq.h
            setModel(g.cf * fq.local, Vector3(w, h, 1f))
            GLES20.glUniformMatrix4fv(cModel, 1, false, model, 0)
            GLES20.glUniform3f(cNormal, fq.normal.x, fq.normal.y, fq.normal.z)
            if (tiling) {
                val su = ((dcl["StudsPerTileU"] as? Number)?.toFloat() ?: 2f).coerceAtLeast(0.01f)
                val sv = ((dcl["StudsPerTileV"] as? Number)?.toFloat() ?: 2f).coerceAtLeast(0.01f)
                val ou = (dcl["OffsetStudsU"] as? Number)?.toFloat() ?: 0f
                val ov = (dcl["OffsetStudsV"] as? Number)?.toFloat() ?: 0f
                GLES20.glUniform4f(cUv, w / su, h / sv, ou / su, ov / sv)
            } else GLES20.glUniform4f(cUv, 1f, 1f, 0f, 0f)
            drawUvMesh(quad)
        }
        val q0 = System.nanoTime()
        drawClothing()
        val q1 = System.nanoTime()
        drawSurfaceGuis()
        tClo += (q1 - q0) / 1e6f; tSg += (System.nanoTime() - q1) / 1e6f
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
    }

    /**
     * Draws [part]'s mesh again with the decal projected along its face's normal over the part's
     * box (Roblox's decal projection on meshes), lit like the surface it lands on.
     */
    private fun drawProjectedDecal(part: Instance, g: Geo, faceId: Int, tex: Int, tint: Color3, alpha: Float,
                                   us: Float = 1f, vs: Float = 1f, uo: Float = 0f, vo: Float = 0f) {
        val pcf = PartData.cframe(part)
        val size = PartData.size(part)
        val (nrm, up) = when (faceId) {
            0 -> Vector3.X to Vector3.Y
            1 -> Vector3.Y to Vector3(0f, 0f, -1f)
            2 -> Vector3.Z to Vector3.Y
            3 -> Vector3(-1f, 0f, 0f) to Vector3.Y
            4 -> Vector3(0f, -1f, 0f) to Vector3.Z
            else -> Vector3(0f, 0f, -1f) to Vector3.Y
        }
        val right = up.cross(nrm)
        fun extent(v: Vector3) = kotlin.math.abs(v.x) * size.x + kotlin.math.abs(v.y) * size.y + kotlin.math.abs(v.z) * size.z
        val w = extent(right).coerceAtLeast(1e-3f); val h = extent(up).coerceAtLeast(1e-3f)
        val rW = pcf.vectorToWorld(right); val uW = pcf.vectorToWorld(up); val nW = pcf.vectorToWorld(nrm)
        val t = pcf.position
        // Rows: u = (p - t)·rW / w + 0.5, v = 0.5 - (p - t)·uW / h, depth = (p - t)·nW. Column-major.
        val m = decalMat
        m[0] = rW.x / w; m[4] = rW.y / w; m[8] = rW.z / w; m[12] = 0.5f - t.dot(rW) / w
        m[1] = -uW.x / h; m[5] = -uW.y / h; m[9] = -uW.z / h; m[13] = 0.5f + t.dot(uW) / h
        m[2] = nW.x; m[6] = nW.y; m[10] = nW.z; m[14] = -t.dot(nW)
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        // Mesh normals to world: rotation times the inverse of the (non-uniform) scale.
        val cf = g.cf; val s = g.scale
        fun inv(v: Float) = if (kotlin.math.abs(v) > 1e-6f) 1f / v else 0f
        val ix = inv(s.x); val iy = inv(s.y); val iz = inv(s.z)
        nrmMat[0] = cf.r00 * ix; nrmMat[1] = cf.r10 * ix; nrmMat[2] = cf.r20 * ix
        nrmMat[3] = cf.r01 * iy; nrmMat[4] = cf.r11 * iy; nrmMat[5] = cf.r21 * iy
        nrmMat[6] = cf.r02 * iz; nrmMat[7] = cf.r12 * iz; nrmMat[8] = cf.r22 * iz
        GLES20.glUseProgram(decalMeshProg)
        GLES20.glUniformMatrix4fv(dmVP, 1, false, studio.view.vp, 0)
        setModel(cf, s)
        GLES20.glUniformMatrix4fv(dmModel, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(dmDecal, 1, false, m, 0)
        GLES20.glUniformMatrix3fv(dmNrmM, 1, false, nrmMat, 0)
        GLES20.glUniform1i(dmTex, 0)
        GLES20.glUniform4f(dmTint, tint.r, tint.g, tint.b, alpha)
        GLES20.glUniform4f(dmUv, us, vs, uo, vo)
        GLES20.glUniform3f(dmNormal, nW.x, nW.y, nW.z)
        GLES20.glUniform3f(dmLight, sunDir.x, sunDir.y, sunDir.z)
        GLES20.glUniform3fv(dmSun, 1, sunColor, 0)
        GLES20.glUniform1f(dmAmbient, ambient)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val mesh = g.mesh
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo)
        GLES20.glEnableVertexAttribArray(dmaPos)
        GLES20.glVertexAttribPointer(dmaPos, 3, GLES20.GL_FLOAT, false, mesh.stride * 4, 0)
        GLES20.glEnableVertexAttribArray(dmaNrm)
        GLES20.glVertexAttribPointer(dmaNrm, 3, GLES20.GL_FLOAT, false, mesh.stride * 4, 12)
        GLES20.glDrawArrays(mesh.mode, 0, mesh.count)
        GLES20.glDisableVertexAttribArray(dmaNrm)
        // Back to the flat-decal program for the rest of the pass.
        GLES20.glUseProgram(decalProg)
    }

    private fun drawUvMesh(m: Mesh) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
        GLES20.glEnableVertexAttribArray(caPos)
        GLES20.glVertexAttribPointer(caPos, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(caUv)
        GLES20.glVertexAttribPointer(caUv, 2, GLES20.GL_FLOAT, false, 20, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.count)
        GLES20.glDisableVertexAttribArray(caUv)
    }

    private var gridTex = 0

    /** One 8-stud tile of the baseplate grid: white border lines on transparent (tiles seamlessly). */
    private fun gridTexture(): Int {
        if (gridTex == 0) {
            val s = 256
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; style = Paint.Style.STROKE; strokeWidth = 6f }
            // Half of each line is in this tile, the other half in the neighbor.
            Canvas(b).drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
            gridTex = upload(b, true)
        }
        return gridTex
    }

    private var spawnTex = 0

    /**
     * A SpawnLocation's decal (rbxasset://textures/SpawnLocation.png is part of Roblox's own
     * install, so it's drawn here): a light emblem, a ring with a figure standing in it.
     */
    private fun spawnTexture(): Int {
        if (spawnTex == 0) {
            val s = 256f
            val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD8FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = s * 0.055f }
            c.drawCircle(s / 2, s / 2, s * 0.40f, p)
            p.strokeWidth = s * 0.02f; p.color = 0x90FFFFFF.toInt()
            c.drawCircle(s / 2, s / 2, s * 0.32f, p)
            // A figure (head, body, arms, legs), like a player standing on the spawn.
            p.style = Paint.Style.FILL; p.color = 0xE6FFFFFF.toInt()
            c.drawCircle(s / 2, s * 0.31f, s * 0.06f, p)
            c.drawRoundRect(s * 0.41f, s * 0.39f, s * 0.59f, s * 0.60f, s * 0.03f, s * 0.03f, p)
            c.drawRoundRect(s * 0.33f, s * 0.40f, s * 0.395f, s * 0.57f, s * 0.03f, s * 0.03f, p)
            c.drawRoundRect(s * 0.605f, s * 0.40f, s * 0.67f, s * 0.57f, s * 0.03f, s * 0.03f, p)
            c.drawRoundRect(s * 0.415f, s * 0.61f, s * 0.49f, s * 0.75f, s * 0.03f, s * 0.03f, p)
            c.drawRoundRect(s * 0.51f, s * 0.61f, s * 0.585f, s * 0.75f, s * 0.03f, s * 0.03f, p)
            spawnTex = upload(b, false)
        }
        return spawnTex
    }

    /** The classic Roblox smile, drawn procedurally. */
    private fun faceBitmap(): Bitmap {
        val s = 256
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        c.drawOval(s * 0.335f, s * 0.30f, s * 0.405f, s * 0.46f, p)
        c.drawOval(s * 0.595f, s * 0.30f, s * 0.665f, s * 0.46f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.038f; p.strokeCap = Paint.Cap.ROUND
        val smile = Path().apply { moveTo(s * 0.29f, s * 0.57f); cubicTo(s * 0.36f, s * 0.75f, s * 0.64f, s * 0.75f, s * 0.71f, s * 0.57f) }
        c.drawPath(smile, p)
        return b
    }

    // ------------------------------------------------------------------ editor overlays

    private var ringLines: Mesh? = null

    /** The Terrain Editor brush: three rings (sphere) or a box where it was last applied. */
    private fun drawTerrainBrush() {
        val at = studio.terrainBrushAt ?: return
        if (studio.terrainTool == null || studio.isPlaying) return
        val ring = ringLines ?: run {
            val n = 48; val arr = FloatArray(n * 6)
            for (i in 0 until n) {
                val a0 = Math.PI * 2 * i / n; val a1 = Math.PI * 2 * (i + 1) / n
                arr[i * 6] = (kotlin.math.cos(a0) * 0.5).toFloat(); arr[i * 6 + 2] = (kotlin.math.sin(a0) * 0.5).toFloat()
                arr[i * 6 + 3] = (kotlin.math.cos(a1) * 0.5).toFloat(); arr[i * 6 + 5] = (kotlin.math.sin(a1) * 0.5).toFloat()
            }
            Meshes.upload(arr, 3, GLES20.GL_LINES).also { ringLines = it }
        }
        GLES20.glUseProgram(line)
        GLES20.glUniformMatrix4fv(lVP, 1, false, studio.view.vp, 0)
        GLES20.glLineWidth(2.5f)
        val s = studio.terrainBrushSize
        GLES20.glUniform4fv(lColor, 1, floatArrayOf(0.35f, 0.8f, 1f, 1f), 0)
        if (studio.terrainCube) {
            bind(boxLines, laPos, -1)
            setModel(CFrame.at(at), Vector3(s, s, s))
            GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, boxLines.count)
        } else {
            bind(ring, laPos, -1)
            for (r in listOf(CFrame.IDENTITY, CFrame.rx((Math.PI / 2).toFloat()), CFrame.rz((Math.PI / 2).toFloat()))) {
                setModel(CFrame.at(at) * r, Vector3(s, s, s))
                GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, ring.count)
            }
        }
    }

    private fun drawSelection() {
        drawTerrainBrush()
        if (studio.selection.isEmpty()) return
        GLES20.glUseProgram(line)
        GLES20.glUniformMatrix4fv(lVP, 1, false, studio.view.vp, 0)
        bind(boxLines, laPos, -1)
        GLES20.glLineWidth(3f)
        val blue = floatArrayOf(0.1f, 0.65f, 1f, 1f)
        for (s in studio.selection) {
            if (s.isA("BasePart")) {
                setModel(PartData.cframe(s), PartData.size(s) * 1.004f + Vector3(0.04f, 0.04f, 0.04f))
                GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                GLES20.glUniform4fv(lColor, 1, blue, 0)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, boxLines.count)
            } else if (!s.isService && (s.isA("Model") || s.className == "Folder")) {
                val parts = s.descendants().filter { it.isA("BasePart") }
                val (a, b) = Geometry.boundsOf(parts) ?: continue
                setModel(CFrame.at((a + b) / 2f), (b - a) + Vector3(0.1f, 0.1f, 0.1f))
                GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                GLES20.glUniform4fv(lColor, 1, floatArrayOf(0.1f, 0.65f, 1f, 1f), 0)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, boxLines.count)
                GLES20.glUniform4fv(lColor, 1, floatArrayOf(0.1f, 0.65f, 1f, 0.6f), 0)
                for (p in parts) {
                    setModel(PartData.cframe(p), PartData.size(p) * 1.004f)
                    GLES20.glUniformMatrix4fv(lModel, 1, false, model, 0)
                    GLES20.glDrawArrays(GLES20.GL_LINES, 0, boxLines.count)
                }
            }
        }
    }

    private fun drawHandles() {
        val hs = Handles.compute(studio) ?: return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(lit)
        GLES20.glUniform1f(uGrid, 0f)
        GLES20.glUniform1f(uEmissive, 1f)
        GLES20.glUniform4f(uMat, 0f, 0f, 1f, 0f)
        GLES20.glUniform3f(uSurfA, 0f, 0f, 0f); GLES20.glUniform3f(uSurfB, 0f, 0f, 0f)
        GLES20.glUniform1f(uTruss, 0f)
        GLES20.glUniform3f(uFog, 0f, 1e9f, 0f)
        for (h in hs.handles) {
            val col = Handles.color(h.index)
            val shaft = h.tip - h.base
            val len = shaft.magnitude
            if (len > 0.001f) {
                bind(cube, aPos, aNrm)
                val cf = CFrame.lookAt((h.base + h.tip) / 2f, h.tip, if (kotlin.math.abs(h.axis.y) > 0.9f) Vector3.Z else Vector3.Y)
                setModel(cf, Vector3(h.radius * 0.3f, h.radius * 0.3f, len))
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
                GLES20.glUniformMatrix3fv(uNrm, 1, false, normal, 0)
                GLES20.glUniform4f(uColor, col[0], col[1], col[2], 1f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cube.count)
            }
            val tipMesh = if (hs.tool == Tool.SCALE) cube else sphere
            bind(tipMesh, aPos, aNrm)
            val d = h.radius * 2f
            val cf = CFrame.lookAt(h.tip, h.tip + h.axis, if (kotlin.math.abs(h.axis.y) > 0.9f) Vector3.Z else Vector3.Y)
            setModel(cf, Vector3(d, d, d))
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
            GLES20.glUniformMatrix3fv(uNrm, 1, false, normal, 0)
            GLES20.glUniform4f(uColor, col[0], col[1], col[2], 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tipMesh.count)
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) OutputLog.error("Shader error: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            // GPUs with few uniform slots can't fit the 8 local lights: build it without them.
            if (fs.contains("localLights") && !fs.startsWith("#define NO_LIGHTS")) {
                GLES20.glDeleteProgram(p)
                return program(vs, "#define NO_LIGHTS 1\n$fs")
            }
            OutputLog.error("Shader link error: " + GLES20.glGetProgramInfoLog(p))
        }
        return p
    }

    // ------------------------------------------------------------------ local lights

    private val lightPos = FloatArray(32); private val lightCol = FloatArray(32); private val lightDir = FloatArray(32)
    private var lightCount = 0
    private val lightLocs = HashMap<Int, IntArray>()

    /** Picks the (up to) 8 enabled lights nearest the camera for this frame. */
    private fun gatherLights() {
        lightCount = 0
        val list = studio.renderLights()
        if (list.isEmpty()) return
        val cp = studio.view.camPos
        class L(val pos: Vector3, val range: Float, val col: Color3, val bright: Float, val spot: Boolean, val dir: Vector3, val cosHalf: Float, val d: Float)
        val found = ArrayList<L>()
        for (l in list) {
            if (l["Enabled"] == false) continue
            val bright = ((l["Brightness"] as? Number)?.toFloat() ?: 1f).coerceIn(0f, 40f)
            val range = ((l["Range"] as? Number)?.toFloat() ?: if (l.className == "PointLight") 8f else 16f).coerceIn(0f, 120f)
            if (bright <= 0f || range <= 0f) continue
            val host = l.parent ?: continue
            val (cf, size) = when {
                host.isA("BasePart") -> PartData.cframe(host) to PartData.size(host)
                host.className == "Attachment" -> {
                    val part = host.parent?.takeIf { it.isA("BasePart") } ?: continue
                    (PartData.cframe(part) * ((host["CFrame"] as? CFrame) ?: CFrame.IDENTITY)) to Vector3.ZERO
                }
                else -> continue
            }
            if (play?.hiddenInFirstPerson(host) == true) continue
            val spot = l.className != "PointLight"
            var pos = cf.position
            var dir = Vector3(0f, -1f, 0f)
            var cosHalf = 0f
            if (spot) {
                val face = ((l["Face"] as? EnumValue)?.value ?: 5)
                val local = when (face) { 0 -> Vector3.X; 1 -> Vector3.Y; 2 -> Vector3.Z; 3 -> -Vector3.X; 4 -> -Vector3.Y; else -> -Vector3.Z }
                dir = cf.vectorToWorld(local)
                // A SurfaceLight shines from the face itself; a SpotLight from the part's middle.
                if (l.className == "SurfaceLight") pos += dir * (kotlin.math.abs(local.dot(size)) / 2f)
                val angle = ((l["Angle"] as? Number)?.toFloat() ?: 90f).coerceIn(0f, 180f)
                cosHalf = kotlin.math.cos(Math.toRadians(angle / 2.0)).toFloat()
            }
            val d = (pos - cp).magnitude - range
            found.add(L(pos, range, l["Color"] as? Color3 ?: Color3(1f, 1f, 1f), bright, spot, dir, cosHalf, d))
        }
        found.sortBy { it.d }
        for (l in found.take(8)) {
            val i = lightCount * 4
            lightPos[i] = l.pos.x; lightPos[i + 1] = l.pos.y; lightPos[i + 2] = l.pos.z; lightPos[i + 3] = l.range
            // Roblox lights are fairly strong: brightness 1 at full color.
            lightCol[i] = l.col.r * l.bright; lightCol[i + 1] = l.col.g * l.bright; lightCol[i + 2] = l.col.b * l.bright; lightCol[i + 3] = if (l.spot) 1f else 0f
            lightDir[i] = l.dir.x; lightDir[i + 1] = l.dir.y; lightDir[i + 2] = l.dir.z; lightDir[i + 3] = l.cosHalf
            lightCount++
        }
    }

    private val play get() = studio.play

    /** Sends this frame's lights to [prog] (lit, mesh and batch programs all light the same way). */
    private fun applyLights(prog: Int) {
        val locs = lightLocs.getOrPut(prog) {
            intArrayOf(GLES20.glGetUniformLocation(prog, "uLCount"), GLES20.glGetUniformLocation(prog, "uLPos"),
                GLES20.glGetUniformLocation(prog, "uLCol"), GLES20.glGetUniformLocation(prog, "uLDir"))
        }
        if (locs[0] < 0) return
        GLES20.glUniform1f(locs[0], lightCount.toFloat())
        if (lightCount > 0) {
            GLES20.glUniform4fv(locs[1], 8, lightPos, 0)
            GLES20.glUniform4fv(locs[2], 8, lightCol, 0)
            GLES20.glUniform4fv(locs[3], 8, lightDir, 0)
        }
    }

    companion object {
        /**
         * PointLight / SpotLight / SurfaceLight (the 8 nearest the camera). uLPos: xyz position,
         * w range; uLCol: rgb color x brightness, w 1 for spot/surface lights; uLDir: xyz
         * direction, w cos(half angle). Roblox-like falloff: smooth to zero at the range.
         */
        const val LIGHTS_GLSL = """
#ifdef NO_LIGHTS
vec3 localLights(vec3 n, vec3 w) { return vec3(0.0); }
#else
uniform vec4 uLPos[8]; uniform vec4 uLCol[8]; uniform vec4 uLDir[8]; uniform float uLCount;
vec3 localLights(vec3 n, vec3 w) {
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 8; i++) {
    if (float(i) >= uLCount) break;
    vec3 d = uLPos[i].xyz - w;
    float dist = length(d);
    float r = uLPos[i].w;
    if (dist >= r) continue;
    vec3 L = d / max(dist, 0.001);
    float att = 1.0 - dist / r;
    att = att * att;
    if (uLCol[i].w > 0.5) att *= smoothstep(uLDir[i].w, mix(uLDir[i].w, 1.0, 0.25), dot(-L, uLDir[i].xyz));
    acc += uLCol[i].rgb * (max(dot(n, L), 0.0) * 0.85 + 0.15) * att;
  }
  return acc;
}
#endif
"""
        /** Bevel on R6 character limbs, in studs (measured from Roblox's own R6 body shapes). */
        private const val R6_BEVEL = 0.065f
        /** Negated parts show as translucent pink-red, like Studio. */
        private val NEGATE_COLOR = Color3(0.93f, 0.33f, 0.45f)
        /** GL_EXT_texture_filter_anisotropic. */
        /** The grid Texture on the Baseplate template's baseplate (rbxassetid://6372755229). */
        const val BASEPLATE_GRID_ID = 6372755229L
        /** The decal Studio puts on a new SpawnLocation (Roblox draws its own image; ours is a look-alike). */
        const val SPAWN_DECAL = "rbxasset://textures/SpawnLocation.png"
        private const val GL_TEXTURE_MAX_ANISOTROPY = 0x84FE
        private const val GL_MAX_ANISOTROPY = 0x84FF
        private const val SKYBOX_VS = """
uniform mat4 uVP; uniform vec3 uCam; uniform mat3 uRot;
attribute vec3 aPos; attribute vec2 aUv; varying vec2 vUv;
void main() {
  vUv = aUv;
  vec4 p = uVP * vec4(uCam + uRot * aPos * 400.0, 1.0);
  gl_Position = vec4(p.xy, p.w * 0.9999, p.w);
}"""
        private const val SKYBOX_FS = """
precision mediump float;
varying vec2 vUv; uniform sampler2D uTex; uniform vec3 uTint;
void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb * uTint, 1.0); }"""
        private const val MESH_VS = """
uniform mat4 uVP; uniform mat4 uModel; uniform mat3 uNrm; uniform mat4 uShadowVP;
varying vec3 vN; varying vec3 vW; varying vec2 vUv; varying vec4 vS;
#ifdef BATCH
attribute vec3 aPos; attribute vec4 aNrm; attribute vec2 aUv; attribute vec4 aCol;
varying vec4 vCol;
void main() {
  vW = aPos; vN = aNrm.xyz; vUv = aUv; vS = uShadowVP * vec4(aPos, 1.0); vCol = aCol;
  gl_Position = uVP * vec4(aPos, 1.0);
}
#else
attribute vec3 aPos; attribute vec3 aNrm; attribute vec2 aUv;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vW = w.xyz; vN = uNrm * aNrm; vUv = aUv; vS = uShadowVP * w;
  gl_Position = uVP * w;
}
#endif"""
        private const val MESH_FS = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec3 vN; varying vec3 vW; varying vec2 vUv; varying vec4 vS;
uniform vec4 uColor; uniform vec3 uTint; uniform vec3 uLight; uniform vec3 uSun; uniform float uAmbient; uniform vec3 uCam;
uniform sampler2D uTex; uniform float uHasTex; uniform sampler2D uShadow; uniform float uShadowOn; uniform vec2 uShadowTexel;
uniform vec3 uFogColor; uniform vec3 uFog; uniform float uExposure; uniform float uEmissive; uniform vec3 uHorizon; uniform vec3 uZenith;
""" + LIGHTS_GLSL + """
float unpackDepth(vec4 c) { return dot(c, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0)); }
float shadowAt(float ndl) {
  if (uShadowOn < 0.5 || ndl <= 0.0) return 1.0;
  vec3 p = vS.xyz / vS.w * 0.5 + 0.5;
  if (p.x <= 0.001 || p.x >= 0.999 || p.y <= 0.001 || p.y >= 0.999 || p.z >= 1.0) return 1.0;
  float z = p.z - (0.0015 + 0.004 * (1.0 - ndl));
  vec2 t = uShadowTexel;
  float s = (z > unpackDepth(texture2D(uShadow, p.xy)) ? 0.0 : 2.0);
  s += (z > unpackDepth(texture2D(uShadow, p.xy + vec2(t.x, t.y))) ? 0.0 : 1.0);
  s += (z > unpackDepth(texture2D(uShadow, p.xy - vec2(t.x, t.y))) ? 0.0 : 1.0);
  return s / 4.0;
}
#ifdef BATCH
varying vec4 vCol;
uniform float uDetail;
// Terrain detail: value noise in world space (Roblox terrain is textured; flat vertex colours look smeared).
float h3(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.x + p.y) * p.z); }
float vn(vec3 x) {
  vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
  float a = mix(mix(h3(i), h3(i + vec3(1.0, 0.0, 0.0)), f.x), mix(h3(i + vec3(0.0, 1.0, 0.0)), h3(i + vec3(1.0, 1.0, 0.0)), f.x), f.y);
  float b = mix(mix(h3(i + vec3(0.0, 0.0, 1.0)), h3(i + vec3(1.0, 0.0, 1.0)), f.x), mix(h3(i + vec3(0.0, 1.0, 1.0)), h3(i + vec3(1.0, 1.0, 1.0)), f.x), f.y);
  return mix(a, b, f.z);
}
float terrainDetail(vec3 w, vec3 n) {
  vec3 p = mod(w, 1024.0);
  float d = vn(p * 0.32) * 0.45 + vn(p * 1.25) * 0.35 + vn(p * 4.0) * 0.20;
  float level = smoothstep(0.45, 0.85, abs(n.y));
  float k = mix(0.34, 0.14, smoothstep(60.0, 420.0, length(w - uCam)));
  return (1.0 - k * 0.5 + k * d) * mix(0.84, 1.0, level);
}
#endif
void main() {
  vec3 n = normalize(vN);
  if (!gl_FrontFacing) n = -n;
#ifdef BATCH
  vec3 c = vCol.rgb; float a = 1.0; float emis = vCol.a; vec3 tint = vec3(1.0);
#else
  vec3 c = uColor.rgb; float a = uColor.a; float emis = uEmissive; vec3 tint = uTint;
#endif
  if (uHasTex > 0.5) {
    vec4 t = texture2D(uTex, vUv);
    // Texture over the part color (where the texture is see-through the color shows).
    c = mix(c, t.rgb / max(t.a, 0.001), t.a);
  }
  c *= tint;
#ifdef BATCH
  if (uDetail > 0.5) c *= terrainDetail(vW, n);
#endif
  vec3 col;
  if (emis > 0.5) col = c * 1.15;
  else {
    float ndl = dot(n, uLight);
    float sh = shadowAt(ndl);
    vec3 sky = mix(uHorizon, uZenith, 0.5);
    vec3 amb = uAmbient * mix(vec3(0.82), mix(vec3(1.0), sky * 1.2, 0.35), 0.5 + 0.5 * n.y);
    col = c * (amb + uSun * max(ndl, 0.0) * sh + localLights(n, vW));
  }
  col *= uExposure;
  float dist = length(uCam - vW);
  float fog;
  if (uFog.z > 1.5) fog = (1.0 - exp(-dist * uFog.x)) * uFog.y;
  else if (uFog.z > 0.5) fog = clamp((dist - uFog.x) / max(uFog.y - uFog.x, 1.0), 0.0, 1.0);
  else fog = smoothstep(900.0, 5000.0, dist) * 0.7;
  gl_FragColor = vec4(mix(col, uFogColor, fog), a);
}"""
        private val SIG_PROPS = arrayOf("Text", "Visible", "BackgroundColor3", "BackgroundTransparency", "TextColor3", "Image", "Size", "Position", "TextTransparency", "ImageTransparency", "Enabled")
        private const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""
        private const val LIT_VS = """
uniform mat4 uVP; uniform mat4 uModel; uniform mat3 uNrm; uniform mat4 uShadowVP;
#ifdef BATCH
// Static batches: vertices are already in world space and carry the part's look.
attribute vec3 aPos; attribute vec4 aNrm; attribute vec2 aUv; attribute vec4 aCol; attribute vec4 aMat;
varying vec3 vN; varying vec3 vW; varying vec4 vS; varying vec3 vA; varying vec4 vCol; varying vec4 vMat;
#ifdef TEXTURED
attribute vec3 aTan; attribute vec3 aBit;
varying vec3 vT; varying vec3 vB;
#endif
void main() {
  vW = aPos; vN = aNrm.xyz;
  vS = uShadowVP * vec4(aPos, 1.0);
  vA = vec3(aUv, floor(aNrm.w * 127.0 + 0.5));
  vCol = aCol;
  vMat = vec4(aMat.x, aMat.y / 1000.0, aMat.z / 50.0, aMat.w / 120.0);
#ifdef TEXTURED
  vT = aTan; vB = aBit;
#endif
  gl_Position = uVP * vec4(aPos, 1.0);
}
#else
attribute vec3 aPos; attribute vec3 aNrm;
varying vec3 vN; varying vec3 vW; varying vec3 vL; varying vec3 vLN; varying vec4 vS;
#ifdef TEXTURED
varying vec3 vT; varying vec3 vB;
#endif
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vW = w.xyz; vN = uNrm * aNrm; vL = aPos; vLN = aNrm;
  vS = uShadowVP * w;
#ifdef TEXTURED
  // World directions of the face's texture axes (same box projection the fragment shader uses).
  vec3 an = abs(aNrm);
  bool yF = an.y > an.x && an.y > an.z; bool xF = !yF && an.x > an.z;
  vT = (uModel * vec4(yF ? vec3(1.0, 0.0, 0.0) : (xF ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0)), 0.0)).xyz;
  vB = (uModel * vec4(yF ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0), 0.0)).xyz;
#endif
  gl_Position = uVP * w;
}
#endif"""
        private const val LIT_FS = PRECISION + """
#ifdef BATCH
varying vec3 vN; varying vec3 vW; varying vec4 vS; varying vec3 vA; varying vec4 vCol; varying vec4 vMat;
#else
varying vec3 vN; varying vec3 vW; varying vec3 vL; varying vec3 vLN; varying vec4 vS;
#endif
uniform vec4 uColor; uniform vec3 uLight; uniform vec3 uCam; uniform float uEmissive;
uniform float uGrid; uniform vec3 uSize; uniform vec3 uHorizon; uniform vec3 uZenith; uniform vec3 uSun;
uniform float uAmbient; uniform vec3 uFogColor; uniform vec3 uFog; uniform vec4 uMat; uniform vec3 uSurfA; uniform vec3 uSurfB;
uniform sampler2D uShadow; uniform float uShadowOn; uniform vec2 uShadowTexel; uniform float uExposure; uniform float uTime; uniform float uTruss;
uniform sampler2D uSurfTex; uniform float uHasSurfTex;
""" + LIGHTS_GLSL + """
#ifdef TEXTURED
varying vec3 vT; varying vec3 vB;
uniform sampler2D uMatTex; uniform sampler2D uMatNrm; uniform float uTexScale; uniform float uHasNrm;
#endif

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
vec2 hash2(vec2 p) { return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453); }
float noise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p) { return noise(p) * 0.5 + noise(p * 2.03 + 7.1) * 0.3 + noise(p * 4.1 + 3.3) * 0.2; }
#ifdef ADVANCED
// Cellular noise: x = distance to the nearest cell point, y = distance to the cell edge (approx).
vec3 voro(vec2 x) {
  vec2 n = floor(x); vec2 f = fract(x);
  float d1 = 8.0; float d2 = 8.0; vec2 id = n;
  for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
    vec2 g = vec2(float(i), float(j));
    vec2 r = g + hash2(n + g) - f;
    float d = dot(r, r);
    if (d < d1) { d2 = d1; d1 = d; id = n + g; } else if (d < d2) { d2 = d; }
  }
  return vec3(sqrt(d1), sqrt(d2) - sqrt(d1), hash(id));
}
#endif
// Roblox surface types on one face (sp = stud coordinates on the face, fsz = face size).
// Roblox's stud look: a raised disc lit from the upper left with a soft shadow to the lower right.
float studShade(vec2 f, float R) {
  vec2 L = vec2(-0.62, 0.78);
  float r = length(f);
  float inside = smoothstep(R + 0.012, R - 0.012, r);
  float rim = smoothstep(R - 0.09, R, r) * inside;
  float side = dot(f / max(r, 0.001), L);
  float shadow = smoothstep(R + 0.1, R, length(f + L * 0.05)) * (1.0 - inside);
  return 1.0 + inside * 0.05 + rim * side * 0.22 - shadow * 0.2;
}
vec3 surface(vec3 c, float s, vec2 sp, vec2 fsz) {
  if (s < 0.5 || s > 9.5) return c;
  if (uHasSurfTex > 0.5 && s < 5.5) {
    // Roblox's own surface textures: one 2x8-stud tile per type, shaded around grey 128
    // (R = studs, G = weld/glue, B = inlet). Sampled with repeat (no fract) so mipmapping
    // never sees a seam. s is the same for a whole face, so this branch is uniform per triangle.
    vec2 st = vec2(sp.x * 0.5, -sp.y * 0.125);
    vec3 t = texture2D(uSurfTex, st).rgb;
    float k;
    if (s < 2.5) k = t.g;
    else if (s < 3.5) k = t.r;
    else if (s < 4.5) k = t.b;
    else { vec2 cell = floor(fract(st) * vec2(2.0, 8.0)); k = mod(cell.x + cell.y, 2.0) < 0.5 ? t.r : t.b; }
    return c * (k * 2.0);
  }
  vec2 f = fract(sp) - 0.5; float r = length(f);
  if (s > 2.5 && s < 3.5) { // Studs
    c *= studShade(f, 0.29);
  } else if (s > 3.5 && s < 4.5) { // Inlet: a sunken hole, shaded the opposite way
    vec2 L = vec2(-0.62, 0.78);
    float inside = smoothstep(0.302, 0.278, r);
    float rim = smoothstep(0.2, 0.29, r) * inside;
    c *= 1.0 - inside * 0.16 - rim * dot(f / max(r, 0.001), L) * 0.2 + smoothstep(0.36, 0.3, r) * (1.0 - inside) * 0.05;
  } else if (s > 4.5 && s < 5.5) { // Universal: a stud with a hole in it
    c *= studShade(f, 0.29) * (1.0 - smoothstep(0.14, 0.12, r) * 0.18);
  } else if (s > 1.5 && s < 2.5) { // Weld: diagonal hatching
    float l = fract((sp.x + sp.y) * 1.25);
    c *= mix(1.0, 0.84, smoothstep(0.82, 0.88, l) * smoothstep(1.0, 0.94, l));
  } else if (s > 0.5 && s < 1.5) { // Glue: grid of small crosses
    vec2 g = abs(fract(sp * 2.0) - 0.5);
    float cr = max(step(g.x, 0.05) * step(g.y, 0.22), step(g.y, 0.05) * step(g.x, 0.22));
    c *= 1.0 - 0.14 * cr;
  } else { // Hinge / Motor / SteppingMotor: a yellow joint in the middle of the face
    float rr = length(sp - fsz * 0.5);
    vec3 yellow = vec3(0.96, 0.8, 0.16);
    if (s > 6.5) {
      if (rr < 0.75) c = mix(c, vec3(0.45, 0.45, 0.47), smoothstep(0.75, 0.7, rr));
      if (s > 7.5 && rr < 0.75 && rr > 0.5) { float a = atan(sp.y - fsz.y * 0.5, sp.x - fsz.x * 0.5); c *= 0.75 + 0.25 * step(0.5, fract(a * 1.273)); }
    }
    if (rr < 0.45) c = mix(c, yellow, smoothstep(0.45, 0.4, rr));
  }
  return c;
}
float unpackDepth(vec4 c) { return dot(c, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0)); }
float sampleShadow(vec2 uv, float z) { return z > unpackDepth(texture2D(uShadow, uv)) ? 0.0 : 1.0; }
float shadowAt(float ndl) {
  if (uShadowOn < 0.5 || ndl <= 0.0) return 1.0;
  vec3 p = vS.xyz / vS.w * 0.5 + 0.5;
  if (p.x <= 0.001 || p.x >= 0.999 || p.y <= 0.001 || p.y >= 0.999 || p.z >= 1.0) return 1.0;
  float z = p.z - (0.0012 + 0.0035 * (1.0 - ndl));
  vec2 t = uShadowTexel;
  float s = sampleShadow(p.xy, z) * 2.0;
  s += sampleShadow(p.xy + vec2(-t.x, -t.y), z);
  s += sampleShadow(p.xy + vec2(t.x, -t.y), z);
  s += sampleShadow(p.xy + vec2(-t.x, t.y), z);
  s += sampleShadow(p.xy + vec2(t.x, t.y), z);
  s /= 6.0;
  vec2 e = min(p.xy, 1.0 - p.xy);
  float fade = clamp(min(e.x, e.y) * 12.0, 0.0, 1.0);
  return mix(1.0, s, fade);
}
void main() {
  vec3 n = normalize(vN);
  if (!gl_FrontFacing) n = -n;
#ifdef BATCH
  vec3 c = vCol.rgb; vec4 mat = vMat; float emis = vCol.a; float alpha = 1.0;
  vec2 uv = vA.xy; vec2 fsz = vec2(1.0); float sf = vA.z;
#else
  vec3 c = uColor.rgb; vec4 mat = uMat; float emis = uEmissive; float alpha = uColor.a;
  vec3 a = abs(vLN);
  vec3 p = vL * uSize + uSize * 0.5;
  bool yFace = a.y > a.x && a.y > a.z; bool xFace = !yFace && a.x > a.z;
  vec2 uv = yFace ? p.xz : (xFace ? p.zy : p.xy);
  vec2 fsz = yFace ? uSize.xz : (xFace ? uSize.zy : uSize.xy);
  // Surface types (studs, inlets, welds, hinges...) on whichever face this is.
  float sf = yFace ? (vLN.y > 0.0 ? uSurfA.y : uSurfB.y) : (xFace ? (vLN.x > 0.0 ? uSurfA.x : uSurfB.x) : (vLN.z > 0.0 ? uSurfA.z : uSurfB.z));
  if (uTruss > 0.5) {
    // Lattice: frame and diagonals per 2-stud cell; everything else is see-through.
    vec2 cell = fract(uv / 2.0);
    float e = min(min(cell.x, cell.y), min(1.0 - cell.x, 1.0 - cell.y));
    float dg = min(abs(cell.x - cell.y), abs(cell.x + cell.y - 1.0));
    if (e > 0.11 && dg > 0.07) discard;
  }
#endif
  vec3 glow = vec3(0.0);
  float type = mat.x;
#ifdef TEXTURED
  // Roblox material textures: the color map tints the part color; the normal map bends the light.
  vec2 tuv = vec2(uv.x, -uv.y) * uTexScale;
  c *= texture2D(uMatTex, tuv).rgb * 1.08;
  if (uHasNrm > 0.5) {
    vec3 tn = texture2D(uMatNrm, tuv).xyz * 2.0 - 1.0;
    n = normalize(normalize(vT) * tn.x + normalize(vB) * tn.y + n * max(tn.z, 0.25));
  }
  type = 0.0;
#endif
  if (type > 0.5) {
    float amt = mat.y; float sc = mat.z;
#ifdef ADVANCED
    if (type > 10.5) {
      if (type < 11.5) { // Marble veins
        float v = abs(sin((uv.x * 0.6 + uv.y * 0.25 + fbm(uv * 0.35) * 5.0) * 1.7));
        c *= mix(0.8, 1.05, pow(v, 0.35)) + (noise(uv * 3.0) - 0.5) * 0.04;
      } else if (type < 12.5) { // Slate / sandstone: layered plates
        float layer = floor(uv.y * sc * 2.0 + fbm(uv * 0.3) * 2.5);
        c *= 0.9 + hash(vec2(layer, 3.0)) * 0.16 + (noise(uv * 5.0) - 0.5) * amt;
      } else if (type < 13.5) { // Cobblestone / pebble / basalt: cells with dark gaps
        vec3 v = voro(uv * sc);
        c *= mix(0.62, 0.92 + v.z * 0.16, smoothstep(0.03, 0.12, v.y)) * (1.0 - v.x * 0.12);
      } else if (type < 14.5) { // Cracked lava: dark rock with glowing cracks
        vec3 v = voro(uv * sc);
        c *= 0.55 + fbm(uv * 1.5) * 0.25;
        glow = vec3(1.0, 0.36, 0.05) * smoothstep(0.08, 0.0, v.y) * (0.7 + 0.3 * sin(uTime * 1.5 + v.z * 6.0));
      } else if (type < 15.5) { // Corroded metal: rust patches
        float rust = smoothstep(0.42, 0.66, fbm(uv * sc));
        c = mix(c * (0.95 + (noise(uv * vec2(18.0, 0.8)) - 0.5) * 0.08), c * vec3(0.7, 0.42, 0.24) + vec3(0.14, 0.05, 0.0), rust * 0.8);
      } else if (type < 16.5) { // Tiles (ceramic / pavement)
        vec2 tf = fract(uv / sc); float grout = step(0.035, tf.x) * step(0.035, tf.y);
        c *= mix(0.72, 1.0 + (hash(floor(uv / sc)) - 0.5) * amt * 2.0 + (noise(uv * 3.0) - 0.5) * 0.04, grout);
      } else if (type < 17.5) { // Roof tiles / shingles: overlapping rows
        float row = floor(uv.y / (0.8 * sc));
        vec2 q = vec2(uv.x / sc + mod(row, 2.0) * 0.5, fract(uv.y / (0.8 * sc)));
        float edge = smoothstep(0.0, 0.08, fract(q.x)) * smoothstep(1.0, 0.92, fract(q.x));
        c *= (0.8 + 0.25 * q.y) * mix(0.82, 1.0, edge) * (0.94 + hash(vec2(floor(q.x), row)) * 0.12);
      } else if (type < 18.5) { // Cardboard corrugation
        c *= 0.95 + 0.05 * sin(uv.x * 22.0) + (noise(uv * 8.0) - 0.5) * 0.06;
      } else if (type < 19.5) { // ForceField: moving bands
        float s = sin((uv.x + uv.y) * 2.5 - uTime * 2.2) * 0.5 + 0.5;
        c = c * (0.55 + 0.45 * s) + vec3(0.12) * s;
      } else if (type < 20.5) { // Leather: small creased cells
        vec3 v = voro(uv * 6.0);
        c *= 0.9 + 0.1 * smoothstep(0.0, 0.12, v.y) + (v.z - 0.5) * 0.04;
      } else if (type < 21.5) { // Carpet: dense fibres
        c *= 0.9 + hash(floor(uv * 28.0)) * 0.12 + (noise(uv * 6.0) - 0.5) * 0.1;
      } else if (type < 22.5) { // Asphalt: speckled
        c *= 0.9 + (hash(floor(uv * 16.0)) - 0.5) * 0.25 + (noise(uv * 2.0) - 0.5) * 0.08;
      } else if (type < 23.5) { // Concrete: blotchy with pores
        c *= 1.0 + (fbm(uv * sc) - 0.5) * amt * 2.4 - step(0.985, hash(floor(uv * 20.0))) * 0.18;
      } else if (type < 24.5) { // Granite: mixed grains
        float g = hash(floor(uv * 12.0));
        c *= 0.88 + g * 0.22 - step(0.9, g) * 0.3 + (fbm(uv * 1.5) - 0.5) * 0.1;
      } else { // Sand / snow / salt: fine grain with sparkle
        float g = hash(floor(uv * 30.0));
        c *= 1.0 + (g - 0.5) * amt * 2.0 + (fbm(uv * sc) - 0.5) * 0.1;
        c += vec3(step(0.996, g) * 0.15);
      }
    } else
    if (type < 1.5) { c *= 1.0 + (noise(uv * 2.2) - 0.5) * amt * 2.0; }
    else if (type < 2.5) {
      float g = sin((uv.x * 1.3 + fbm(uv * vec2(0.35, 2.2)) * 4.0) * 5.0);
      c *= 1.0 + g * amt * 0.6 - amt * 0.2;
    } else if (type < 3.5) {
      float plank = floor(uv.y);
      float seamY = smoothstep(0.0, 0.05, fract(uv.y)) * smoothstep(1.0, 0.95, fract(uv.y));
      float off = hash(vec2(plank, 3.0)) * 8.0;
      float seamX = smoothstep(0.0, 0.04, fract((uv.x + off) / 6.0) * 6.0);
      float g = sin((uv.x * 1.6 + fbm(vec2(uv.x * 0.3, uv.y * 3.0 + plank)) * 3.0) * 6.0);
      c *= (0.9 + hash(vec2(plank, 1.0)) * 0.18) * (1.0 + g * amt * 0.35) * mix(0.72, 1.0, seamY * seamX);
    } else if (type < 4.5) {
      // Brick: running bond with light grey mortar, like Roblox's brick material.
      vec2 b = vec2(uv.x / 1.6, uv.y / 0.66);
      float row = floor(b.y);
      b.x += mod(row, 2.0) * 0.5;
      vec2 f = fract(b);
      float mortar = smoothstep(0.05, 0.09, f.y) * smoothstep(0.025, 0.05, f.x) * smoothstep(1.0, 0.985, f.x);
      vec3 brick = c * (0.9 + (hash(floor(b)) - 0.5) * amt * 2.2 + (noise(uv * 7.0) - 0.5) * 0.08);
      vec3 grout = mix(vec3(dot(c, vec3(0.3, 0.59, 0.11))), vec3(0.82), 0.55);
      c = mix(grout, brick, mortar);
    } else if (type < 5.5) { c *= 1.0 + (fbm(uv * sc) - 0.5) * amt * 2.6; }
    else if (type < 6.5) {
      vec2 q = vec2(uv.x + uv.y, uv.x - uv.y) * 1.4;
      vec2 f = fract(q) - 0.5;
      float bump = smoothstep(0.18, 0.05, abs(f.x) * 0.35 + abs(f.y));
      c *= 0.92 + bump * 0.22 + (noise(uv * 8.0) - 0.5) * 0.05;
    } else if (type < 7.5) { c *= 1.0 + (noise(uv * vec2(24.0, 0.6)) - 0.5) * amt * 2.0; }
    else if (type < 8.5) {
      float f = fbm(uv * sc);
      c *= 1.0 + (f - 0.5) * amt * 2.4 + (noise(uv * 9.0) - 0.5) * 0.05;
      c.r += (noise(uv * 0.25) - 0.5) * 0.04;
    } else if (type < 9.5) { c *= 1.0 + sin(uv.x * 30.0) * sin(uv.y * 30.0) * amt; }
    else { c *= 1.0 + (fbm(uv * sc) - 0.5) * amt * 2.0; }
#else
    // Lean variant: plastic grain only (other materials use the ADVANCED program).
    c *= 1.0 + (noise(uv * 2.2) - 0.5) * amt * 2.0;
#endif
  }
  c = surface(c, sf, uv, fsz);
#ifndef BATCH
  if (uGrid > 0.5) {
    vec2 f = abs(fract(uv / 4.0 + 0.5) - 0.5) * 4.0;
    float d = min(f.x, f.y);
    c *= mix(0.84, 1.0, smoothstep(0.04, 0.09, d));
  }
#endif
  vec3 col;
  if (emis > 0.5) {
    col = c * 1.15 + vec3(0.06);
  } else {
    float ndl = dot(n, uLight);
    float diff = max(ndl, 0.0);
    float sh = shadowAt(ndl);
    vec3 sky = mix(uHorizon, uZenith, 0.5);
    vec3 amb = uAmbient * mix(vec3(0.82), mix(vec3(1.0), sky * 1.2, 0.35), 0.5 + 0.5 * n.y);
    col = c * (amb + uSun * diff * sh + localLights(n, vW));
    vec3 v = normalize(uCam - vW);
    vec3 h = normalize(uLight + v);
    float spec = pow(max(dot(n, h), 0.0), 16.0 + mat.w * 80.0) * mat.w * sh;
    col += uSun * spec * 0.6;
    float fres = pow(1.0 - max(dot(n, v), 0.0), 4.0) * mat.w * 0.25;
    col = mix(col, uZenith, fres);
  }
  col += glow;
  col *= uExposure;
  float dist = length(uCam - vW);
  float fog;
  if (uFog.z > 1.5) fog = (1.0 - exp(-dist * uFog.x)) * uFog.y;
  else if (uFog.z > 0.5) fog = clamp((dist - uFog.x) / max(uFog.y - uFog.x, 1.0), 0.0, 1.0);
  else fog = smoothstep(900.0, 5000.0, dist) * 0.7;
  col = mix(col, uFogColor, fog);
  gl_FragColor = vec4(col, alpha);
}"""
        private const val DEPTH_VS = """
uniform mat4 uVP; uniform mat4 uModel;
attribute vec3 aPos;
void main() { gl_Position = uVP * uModel * vec4(aPos, 1.0); }"""
        private const val DEPTH_FS = PRECISION + """
void main() {
  vec4 enc = vec4(1.0, 255.0, 65025.0, 16581375.0) * gl_FragCoord.z;
  enc = fract(enc);
  enc -= enc.yzww * vec4(1.0 / 255.0, 1.0 / 255.0, 1.0 / 255.0, 0.0);
  gl_FragColor = enc;
}"""
        private const val SKY_VS = """
attribute vec2 aPos; varying vec2 vNdc;
void main() { vNdc = aPos; gl_Position = vec4(aPos, 0.9999, 1.0); }"""
        private const val SKY_FS = PRECISION + """
varying vec2 vNdc;
uniform mat4 uInvVP; uniform vec3 uCam; uniform vec3 uSunDir; uniform vec3 uZenith; uniform vec3 uHorizon;
uniform vec3 uSunColor; uniform float uTime; uniform float uDay; uniform vec3 uCloud; uniform vec3 uCloudCol;
float hash(vec2 p) { vec3 q = fract(vec3(p.xyx) * 0.1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }
float noise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p) { float v = 0.0; float a = 0.5; for (int i = 0; i < 3; i++) { v += noise(p) * a; p = p * 2.02 + 3.7; a *= 0.5; } return v / 0.875; }
void main() {
  vec4 far = uInvVP * vec4(vNdc, 1.0, 1.0);
  vec3 dir = normalize(far.xyz / far.w - uCam);
  float y = dir.y;
  vec3 col = mix(uHorizon, uZenith, pow(clamp(y, 0.0, 1.0), 0.55));
  if (y < 0.0) col = mix(uHorizon, uHorizon * 0.8, clamp(-y * 3.0, 0.0, 1.0));
  float sd = max(dot(dir, uSunDir), 0.0);
  col += uSunColor * (pow(sd, 900.0) * 3.0 + pow(sd, 60.0) * 0.18 + pow(sd, 6.0) * 0.08) * uDay;
  if (uCloud.z > 0.5 && y > 0.02) {
    vec2 uv = dir.xz / (y + 0.12) * 1.6 + vec2(uTime * 0.004, uTime * 0.002);
    float e0 = 0.78 - uCloud.x * 0.5;
    float cl = smoothstep(e0, e0 + 0.26, fbm(uv)) * clamp(uCloud.y / 0.7, 0.0, 1.4);
    float fade = smoothstep(0.0, 0.25, y);
    vec3 cloudCol = mix(vec3(0.55, 0.6, 0.7), vec3(1.0), 0.35 + 0.65 * uDay) * uCloudCol;
    col = mix(col, cloudCol * (0.85 + 0.15 * sd), clamp(cl, 0.0, 1.0) * fade * 0.85 * (0.3 + 0.7 * uDay));
  }
  if (uDay < 0.5 && y > 0.0) {
    float st = step(0.997, hash(floor(dir.xz / (y + 0.3) * 300.0)));
    col += vec3(st) * (1.0 - uDay * 2.0) * smoothstep(0.0, 0.3, y);
  }
  gl_FragColor = vec4(col, 1.0);
}"""
        private const val DECAL_VS = """
uniform mat4 uVP; uniform mat4 uModel; uniform vec4 uUv;
attribute vec3 aPos; attribute vec2 aUv;
varying vec2 vUv;
void main() { vUv = aUv * uUv.xy + uUv.zw; gl_Position = uVP * uModel * vec4(aPos, 1.0); }"""
        private const val DECAL_FS = PRECISION + """
varying vec2 vUv;
uniform sampler2D uTex; uniform vec4 uTint; uniform vec3 uNormal; uniform vec3 uLight; uniform vec3 uSun; uniform float uAmbient;
void main() {
  vec4 t = texture2D(uTex, vUv);
  float diff = max(dot(normalize(uNormal), uLight), 0.0);
  vec3 light = vec3(uAmbient) + uSun * diff;
  gl_FragColor = vec4(t.rgb * uTint.rgb * light, t.a) * uTint.a;
}"""
        /**
         * A decal on a mesh (R15 heads, MeshParts): the mesh is drawn again with the decal
         * box-projected onto it from its face, like Roblox, instead of a flat quad floating in
         * front of a rounded surface. uDecal maps world positions to (u, v, depth).
         */
        private const val DECAL_MESH_VS = """
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uDecal; uniform mat3 uNrmM;
attribute vec3 aPos; attribute vec3 aNrm;
varying vec2 vUv; varying vec3 vN;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vUv = (uDecal * w).xy; vN = uNrmM * aNrm;
  gl_Position = uVP * w;
}"""
        private const val DECAL_MESH_FS = PRECISION + """
varying vec2 vUv; varying vec3 vN;
uniform sampler2D uTex; uniform vec4 uTint; uniform vec3 uNormal; uniform vec3 uLight; uniform vec3 uSun; uniform float uAmbient;
uniform vec4 uUv;
void main() {
  if (vUv.x < 0.0 || vUv.x > 1.0 || vUv.y < 0.0 || vUv.y > 1.0) discard;
  vec3 n = normalize(vN);
  float facing = dot(n, uNormal);
  if (facing < 0.08) discard;
  vec2 tu = vUv * uUv.xy + uUv.zw;
  if (tu.x < 0.0 || tu.x > 1.0 || tu.y < 0.0 || tu.y > 1.0) discard;
  vec4 t = texture2D(uTex, tu);
  float diff = max(dot(n, uLight), 0.0);
  vec3 light = vec3(uAmbient) + uSun * diff;
  // Textures are uploaded premultiplied: scale color and alpha by the same factor.
  float k = uTint.a * smoothstep(0.08, 0.3, facing);
  gl_FragColor = vec4(t.rgb * uTint.rgb * light, t.a) * k;
}"""
        private const val WATER_VS = """
uniform mat4 uVP; uniform vec4 uWaveP;
attribute vec3 aPos; attribute vec3 aNrm;
varying vec3 vW; varying vec3 vN;
void main() {
  vec3 p = aPos;
  if (aNrm.y > 0.5) {
    float fade = clamp(1.0 - (uVP * vec4(aPos, 1.0)).w * uWaveP.w, 0.0, 1.0);
    p.y += (sin((aPos.z - aPos.x) * uWaveP.x - uWaveP.y) + sin((aPos.z + aPos.x) * uWaveP.x + uWaveP.y)) * uWaveP.z * fade;
  }
  vW = p; vN = aNrm; gl_Position = uVP * vec4(p, 1.0);
}"""
        private const val WATER_FS = PRECISION + """
varying vec3 vW; varying vec3 vN;
uniform vec3 uColor; uniform float uAlpha; uniform vec3 uLight; uniform vec3 uSun; uniform float uAmbient; uniform vec3 uCam;
uniform float uTime; uniform vec3 uSky; uniform vec3 uFogColor; uniform vec3 uFog; uniform float uWave;
void main() {
  vec3 n = normalize(vN);
  // Ripples on surfaces facing up (two crossing wave trains).
  float up = max(n.y, 0.0);
  float a1 = vW.x * 0.31 + vW.z * 0.12 + uTime * 1.7;
  float a2 = vW.z * 0.27 - vW.x * 0.09 - uTime * 1.3;
  vec3 r = vec3(cos(a1) * 0.31 - sin(a2) * 0.09, 0.0, cos(a1) * 0.12 + cos(a2) * 0.27) * (0.35 + uWave * 2.0) * up;
  n = normalize(n + r * 0.35);
  vec3 v = normalize(uCam - vW);
  float facing = abs(dot(n, v));
  float fres = pow(1.0 - facing, 4.0);
  float diff = max(dot(n, uLight), 0.0);
  vec3 h = normalize(uLight + v);
  float spec = pow(max(dot(n, h), 0.0), 96.0);
  vec3 c = uColor * (uAmbient * 1.2 + uSun * diff * 0.55) + uSky * fres * 0.6 + uSun * spec * 0.9;
  float a = clamp(uAlpha + fres * 0.35 + spec * 0.5, 0.0, 1.0);
  // Same fog as the lit shaders: z = 2 exponential (x density, y max), z = 1 linear (x start, y end).
  float d = length(uCam - vW);
  float f = 0.0;
  if (uFog.z > 1.5) f = (1.0 - exp(-d * uFog.x)) * uFog.y;
  else if (uFog.z > 0.5) f = clamp((d - uFog.x) / max(uFog.y - uFog.x, 1.0), 0.0, 1.0);
  gl_FragColor = vec4(mix(c, uFogColor, f), a);
}"""
        private const val LINE_VS = """
uniform mat4 uVP; uniform mat4 uModel;
attribute vec3 aPos;
void main() { gl_Position = uVP * uModel * vec4(aPos, 1.0); }"""
        private const val LINE_FS = """
precision mediump float;
uniform vec4 uColor;
void main() { gl_FragColor = uColor; }"""
    }
}
