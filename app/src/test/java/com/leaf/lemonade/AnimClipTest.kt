package com.leaf.lemonade

import com.leaf.lemonade.engine.AnimClip
import com.leaf.lemonade.engine.AnimPlayer
import com.leaf.lemonade.engine.Quat
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.CFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Quaternion/CFrame interpolation checks, plus parsing real KeyframeSequences from the folder in
 * ANIM_SAMPLES (animations extracted from Roblox's cache) when it's set.
 */
class AnimClipTest {
    private fun close(a: CFrame, b: CFrame, eps: Float = 1e-3f) {
        val av = floatArrayOf(a.x, a.y, a.z, a.r00, a.r01, a.r02, a.r10, a.r11, a.r12, a.r20, a.r21, a.r22)
        val bv = floatArrayOf(b.x, b.y, b.z, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22)
        for (i in av.indices) assertTrue("component $i: ${av[i]} vs ${bv[i]}", abs(av[i] - bv[i]) < eps)
    }

    @Test
    fun quaternionRoundTrip() {
        for (cf in listOf(CFrame.IDENTITY, CFrame.rx(0.7f), CFrame.ry(2.9f), CFrame.rz(-1.2f), CFrame.rx(1f) * CFrame.ry(-2f) * CFrame.rz(0.4f), CFrame.ry(Math.PI.toFloat()))) {
            close(Quat.toCFrame(Quat.of(cf), 0f, 0f, 0f), cf)
        }
    }

    @Test
    fun lerpHalfway() {
        val a = CFrame.IDENTITY; val b = CFrame.at(com.leaf.lemonade.model.Vector3(2f, 0f, 0f)) * CFrame.ry(1f)
        close(Quat.lerp(a, b, 0.5f), CFrame.at(com.leaf.lemonade.model.Vector3(1f, 0f, 0f)) * CFrame.ry(0.5f))
    }

    @Test
    fun easing() {
        assertEquals(0.5f, AnimClip.ease(0.5f, 0, 0), 1e-6f)
        assertEquals(0f, AnimClip.ease(0.7f, 1, 0), 1e-6f)
        assertEquals(0.5f, AnimClip.ease(0.5f, 5, 2), 1e-4f)
    }

    @Test
    fun realKeyframeSequences() {
        val dir = System.getenv("ANIM_SAMPLES")
        assumeTrue(dir != null)
        val report = StringBuilder()
        var ok = 0
        for (f in File(dir!!).listFiles()!!.filter { it.isFile }) {
            try {
                val doc = RbxFile.read(f.readBytes())
                val seq = doc.roots.flatMap { listOf(it) + it.descendants() }.firstOrNull { it.className == "KeyframeSequence" } ?: continue
                val clip = AnimClip.from(seq)
                if (clip == null) { report.appendLine("EMPTY ${f.name}"); continue }
                // Sample every joint across the clip; every result must be a proper rotation.
                val p = AnimPlayer(); p.play(clip, "t", 0f)
                for (step in 0..20) {
                    p.step(clip.length / 20f)
                    for (j in clip.jointNames) {
                        val cf = p.transform(j) ?: continue
                        val det = cf.r00 * (cf.r11 * cf.r22 - cf.r12 * cf.r21) - cf.r01 * (cf.r10 * cf.r22 - cf.r12 * cf.r20) + cf.r02 * (cf.r10 * cf.r21 - cf.r11 * cf.r20)
                        assertTrue("det $det in ${f.name}/$j", abs(det - 1f) < 0.02f)
                    }
                }
                ok++
                report.appendLine("OK ${f.name}: ${"%.2f".format(clip.length)}s loop=${clip.looped} prio=${clip.priority} joints=${clip.jointNames.sorted().joinToString(",")}")
            } catch (e: Throwable) {
                report.appendLine("FAIL ${f.name}: $e")
            }
        }
        println(report)
        File(System.getProperty("java.io.tmpdir"), "anim_report.txt").writeText(report.toString())
        assertTrue(ok > 0)
    }
}
