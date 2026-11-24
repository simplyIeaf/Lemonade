package com.leaf.lemonade.render

import android.opengl.Matrix
import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.Vector3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FlyCamera {
    var pos = Vector3(0f, 20f, 40f)
    var yaw = 0f
    var pitch = -0.4f
    var fov = 70f

    val forward: Vector3 get() = Vector3(-sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))
    val right: Vector3 get() = Vector3(cos(yaw), 0f, -sin(yaw))
    val up: Vector3 get() = right.cross(forward).unit

    fun cframe(): CFrame = CFrame.lookAt(pos, pos + forward)

    fun lookAt(target: Vector3) {
        val d = (target - pos).unit
        pitch = kotlin.math.asin(d.y.coerceIn(-1f, 1f))
        yaw = atan2(-d.x, -d.z)
    }

    fun setFromCFrame(cf: CFrame) {
        pos = cf.position
        lookAt(cf.position + cf.lookVector)
    }

    fun rotate(dYaw: Float, dPitch: Float) {
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-1.55f, 1.55f)
    }

    fun focusOn(center: Vector3, radius: Float) {
        val dist = (radius * 2.2f).coerceAtLeast(6f)
        pos = center - forward * dist
    }
}

/** Snapshot of the matrices used for the last frame; shared with touch picking. */
class ViewState {
    val view = FloatArray(16)
    val proj = FloatArray(16)
    val vp = FloatArray(16)
    val invVp = FloatArray(16)
    var width = 1
    var height = 1
    var camPos = Vector3.ZERO
    var near = 0.3f

    /**
     * [near] is chosen by the renderer from how close the nearest visible part was last frame:
     * depth precision depends almost only on the near plane, so pulling it out when the camera
     * is far from everything removes z-fighting stripes on distant parts.
     */
    fun update(eye: Vector3, target: Vector3, fov: Float, w: Int, h: Int, near: Float = 0.3f) {
        width = maxOf(w, 1); height = maxOf(h, 1); camPos = eye; this.near = near
        val up = if (kotlin.math.abs((target - eye).unit.y) > 0.999f) Vector3.Z else Vector3.Y
        Matrix.setLookAtM(view, 0, eye.x, eye.y, eye.z, target.x, target.y, target.z, up.x, up.y, up.z)
        Matrix.perspectiveM(proj, 0, fov, width.toFloat() / height, near, 10000f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.invertM(invVp, 0, vp, 0)
    }

    /** Screen coordinates (px) of a world point, or null when behind the camera. */
    fun project(p: Vector3): FloatArray? {
        val v = floatArrayOf(p.x, p.y, p.z, 1f)
        val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, vp, 0, v, 0)
        if (o[3] <= 0.01f) return null
        val nx = o[0] / o[3]; val ny = o[1] / o[3]
        return floatArrayOf((nx * 0.5f + 0.5f) * width, (1f - (ny * 0.5f + 0.5f)) * height, o[2] / o[3])
    }

    /** World-space ray (origin, unit direction) through a screen point. */
    fun screenRay(x: Float, y: Float): Pair<Vector3, Vector3> {
        val nx = x / width * 2f - 1f
        val ny = 1f - y / height * 2f
        fun un(z: Float): Vector3 {
            val o = FloatArray(4)
            Matrix.multiplyMV(o, 0, invVp, 0, floatArrayOf(nx, ny, z, 1f), 0)
            return Vector3(o[0] / o[3], o[1] / o[3], o[2] / o[3])
        }
        val a = un(-1f); val b = un(1f)
        // From the eye, not the near plane: the near plane moves with the scene.
        return camPos to (b - a).unit
    }

    fun distTo(p: Vector3): Float { val d = p - camPos; return sqrt(d.dot(d)) }
}
