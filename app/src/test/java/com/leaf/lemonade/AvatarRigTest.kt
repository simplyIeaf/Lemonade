package com.leaf.lemonade

import com.leaf.lemonade.engine.Avatar
import com.leaf.lemonade.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The R15 builder poses the default body exactly like Roblox's default R15 rig. */
class AvatarRigTest {
    private fun spec(bodyParts: Map<Int, ByteArray> = emptyMap()) = Avatar.Spec(
        Avatar.CLASSIC_COLORS, null, null, null, null, emptyList(), "test", r15 = true, bodyParts = bodyParts,
    )

    @Test
    fun defaultRigMatchesRoblox() {
        val m = Avatar.buildR15("Player", spec())
        // Positions from Roblox Studio's default R15 character (HumanoidRootPart at the origin).
        val expected = mapOf(
            "LowerTorso" to Vector3(0f, -0.8f, 0f), "UpperTorso" to Vector3(0f, 0.2f, 0f), "Head" to Vector3(0f, 1.586f, 0f),
            "LeftUpperArm" to Vector3(-1.5f, 0.369f, 0f), "LeftLowerArm" to Vector3(-1.5f, -0.224f, 0f), "LeftHand" to Vector3(-1.5f, -0.85f, 0f),
            "RightUpperArm" to Vector3(1.5f, 0.369f, 0f), "RightHand" to Vector3(1.5f, -0.85f, 0f),
            "LeftUpperLeg" to Vector3(-0.5f, -1.421f, 0f), "LeftLowerLeg" to Vector3(-0.5f, -2.201f, 0f), "LeftFoot" to Vector3(-0.5f, -2.85f, 0f),
            "RightFoot" to Vector3(0.5f, -2.85f, 0f),
        )
        for ((n, pos) in expected) {
            val p = m.findFirstChild(n) ?: throw AssertionError("missing $n")
            val got = PartData.cframe(p).position
            assertTrue("$n at $got, expected $pos", (got - pos).magnitude < 0.01f)
        }
        val joints = m.descendants().filter { it.className == "Motor6D" }.map { it.name }.toSet()
        for (j in listOf("Root", "Waist", "Neck", "LeftShoulder", "LeftElbow", "LeftWrist", "RightShoulder", "RightHip", "RightKnee", "RightAnkle"))
            assertTrue("missing joint $j in $joints", j in joints)
        val hum = m.findFirstChildOfClass("Humanoid")!!
        assertEquals(2f, (hum["HipHeight"] as Number).toFloat(), 0.01f)
    }

    @Test
    fun wornBodyPartReplacesDefault() {
        val f = System.getenv("BODYPART_FILE")?.let { File(it) }
        org.junit.Assume.assumeTrue(f != null && f.isFile)
        val bytes = f!!.readBytes()
        val m = Avatar.buildR15("Player", spec(mapOf(29 to bytes, 28 to bytes)))
        val sb = StringBuilder()
        for (p in m.children.filter { it.isA("BasePart") }) sb.appendLine("${p.name} ${PartData.cframe(p).position} size=${PartData.size(p)} mesh=${p["MeshId"] ?: p["MeshContent"]}")
        File(System.getProperty("java.io.tmpdir"), "avatar_rig.txt").writeText(sb.toString())
        // Every part is connected to the root (nothing left at the origin unposed).
        val placed = m.descendants().filter { it.className == "Motor6D" }.map { (it["Part1"] as Instance).name }.toSet()
        for (p in m.children.filter { it.isA("BasePart") && it.name != "HumanoidRootPart" }) assertTrue("${p.name} not joined", p.name in placed)
    }
}
