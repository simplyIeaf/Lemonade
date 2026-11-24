package com.leaf.lemonade

import com.leaf.lemonade.engine.EditAnimation
import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.EnumValue
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.InstanceFactory
import com.leaf.lemonade.model.PartData
import com.leaf.lemonade.model.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.LuaTable

/** Edit-mode joints and Animator:StepAnimations, as animation plugins use them. */
class EditAnimationTest {
    private class Rig(val model: Instance, val root: Instance, val arm: Instance, val hand: Instance, val motor: Instance, val animator: Instance)

    private fun part(name: String, parent: Instance, at: Vector3, anchored: Boolean = false) = InstanceFactory.create("Part").apply {
        this.name = name
        set("CFrame", CFrame.at(at))
        set("Anchored", anchored)
        setParent(parent)
    }

    private fun rig(): Rig {
        val m = InstanceFactory.create("Model").apply { name = "Rig" }
        val root = part("HumanoidRootPart", m, Vector3(0f, 5f, 0f), anchored = true)
        val arm = part("RightUpperArm", m, Vector3(1.5f, 5f, 0f))
        val hand = part("RightHand", m, Vector3(1.5f, 3f, 0f))
        val motor = InstanceFactory.create("Motor6D").apply {
            name = "RightShoulder"
            set("Part0", root); set("Part1", arm)
            set("C0", CFrame.at(Vector3(1.5f, 0.5f, 0f))); set("C1", CFrame.at(Vector3(0f, 0.5f, 0f)))
            setParent(arm)
        }
        // The hand hangs off the arm with a weld that matches where it is now.
        InstanceFactory.create("Weld").apply {
            set("Part0", arm); set("Part1", hand)
            set("C0", CFrame.at(Vector3(0f, -2f, 0f))); set("C1", CFrame.IDENTITY)
            setParent(hand)
        }
        val hum = InstanceFactory.create("Humanoid").apply { setParent(m) }
        val animator = InstanceFactory.create("Animator").apply { setParent(hum) }
        return Rig(m, root, arm, hand, motor, animator)
    }

    private fun near(a: Vector3, b: Vector3) = assertTrue("$a != $b", (a - b).magnitude < 1e-3f)

    @Test
    fun settingTransformMovesPart1AndWhatsAttached() {
        val r = rig()
        val ea = EditAnimation()
        ea.setTransform(r.motor, CFrame.at(Vector3(0f, 0f, -1f)))
        ea.flush()
        near(PartData.cframe(r.arm).position, Vector3(1.5f, 5f, -1f))
        near(PartData.cframe(r.hand).position, Vector3(1.5f, 3f, -1f))
        near(PartData.cframe(r.root).position, Vector3(0f, 5f, 0f))
        // Back to rest.
        ea.setTransform(r.motor, CFrame.IDENTITY)
        ea.flush()
        near(PartData.cframe(r.arm).position, Vector3(1.5f, 5f, 0f))
        near(PartData.cframe(r.hand).position, Vector3(1.5f, 3f, 0f))
    }

    @Test
    fun changingC0ReseatsPart1() {
        val r = rig()
        val ea = EditAnimation()
        r.motor.set("C0", CFrame.at(Vector3(1.5f, 1.5f, 0f)))
        ea.jointChanged(r.motor)
        ea.flush()
        near(PartData.cframe(r.arm).position, Vector3(1.5f, 6f, 0f))
        near(PartData.cframe(r.hand).position, Vector3(1.5f, 4f, 0f))
    }

    @Test
    fun stepAnimationsPlaysARegisteredKeyframeSequence() {
        val r = rig()
        val ea = EditAnimation()
        // A one-second clip moving the arm 2 studs forward (-Z) over the second.
        val seq = InstanceFactory.create("KeyframeSequence").apply { set("Loop", false); set("Priority", EnumValue(2)) }
        for ((t, z) in listOf(0f to 0f, 1f to -2f)) {
            val kf = InstanceFactory.create("Keyframe").apply { set("Time", t); setParent(seq) }
            val rootPose = InstanceFactory.create("Pose").apply { name = "HumanoidRootPart"; set("Weight", 0f); setParent(kf) }
            InstanceFactory.create("Pose").apply { name = "RightUpperArm"; set("CFrame", CFrame.at(Vector3(0f, 0f, z))); set("Weight", 1f); setParent(rootPose) }
        }
        val id = EditAnimation.registerTemp(seq, false)
        assertTrue(id.startsWith("rbxtemp://"))
        val anim = InstanceFactory.create("Animation").apply { set("AnimationId", id) }
        val track = ea.loadTrack(r.animator, anim, LuaTable())!!
        assertEquals(1f, track.length, 1e-4f)
        ea.play(track, 0f, 1f, 1f)
        ea.step(r.animator, 0f)
        ea.step(r.animator, 0.5f)
        ea.flush()
        near(PartData.cframe(r.arm).position, Vector3(1.5f, 5f, -1f))
        // Past the end, a one-shot track stops (and reports Stopped/Ended).
        val events = ea.step(r.animator, 1f).map { it.second }
        assertTrue(events.contains("Stopped"))
        assertTrue(!track.isPlaying)
    }
}
