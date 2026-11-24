package com.leaf.lemonade

import com.leaf.lemonade.engine.Avatar
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Worn heads (dynamic heads especially) end up on the R15 character, on top of the torso. */
class DynamicHeadTest {
    private fun spec(head: ByteArray) = Avatar.Spec(Avatar.CLASSIC_COLORS, null, null, null, null, emptyList(), "t", r15 = true, bodyParts = mapOf(79 to head))

    /** A head the way newer uploads store it: empty MeshId, the mesh in MeshContent, no neck attachment. */
    private fun syntheticHead(): ByteArray {
        val h = InstanceFactory.create("MeshPart")
        h.name = "Head"
        h.set("size", Vector3(1.2f, 1.2f, 1.2f), false)
        h.set("MeshId", "", false)
        h.set("MeshContent", ContentValue(1, "rbxassetid://424242"), false)
        h.set("CFrame", CFrame.at(Vector3(40f, 80f, -30f)), false)
        InstanceFactory.create("SurfaceAppearance").apply { set("ColorMap", "", false); set("ColorMapContent", ContentValue(1, "rbxassetid://99")) }.attachRaw(h)
        return com.leaf.lemonade.format.RbxBinaryWriter.write(listOf(h), emptyMap())
    }

    @Test
    fun headWithContentMeshAndNoNeckIsPlaced() {
        val m = Avatar.buildR15("P", spec(syntheticHead()))
        val head = m.findFirstChild("Head")!!
        assertEquals("rbxassetid://424242", PartData.meshContent(head))
        assertEquals("rbxassetid://99", PartData.content(head.findFirstChild("SurfaceAppearance")!!, "ColorMap", "ColorMapContent"))
        val pos = PartData.cframe(head).position
        // Sits on the upper torso like the default head (0, ~1.59, 0), not where the file had it.
        assertTrue("head at $pos", (pos - Vector3(0f, 1.59f, 0f)).magnitude < 0.1f)
        assertTrue(m.descendants().any { it.className == "Motor6D" && it["Part1"] === head })
    }

    /** On R6 the dynamic head (with its face) replaces the classic head and smile, sitting on the neck. */
    @Test
    fun dynamicHeadShowsOnR6() {
        val m = com.leaf.lemonade.engine.PlaySession.buildR6Rig("P")
        val r6spec = Avatar.Spec(Avatar.CLASSIC_COLORS, null, null, null, null, emptyList(), "t", r15 = false, bodyParts = mapOf(79 to syntheticHead()))
        val added = Avatar.applyTo(m, r6spec)
        val head = m.findFirstChild("Head")!!
        val dyn = m.findFirstChild("DynamicHead")!!
        assertTrue(dyn in added)
        assertEquals("rbxassetid://424242", PartData.meshContent(dyn))
        assertEquals(1f, PartData.transparency(head), 1e-4f)
        assertTrue(head.children.none { it.className == "Decal" && it.name == "face" })
        // On top of the neck: about 0.09 studs above the classic head's centre.
        val dy = PartData.cframe(dyn).position.y - PartData.cframe(head).position.y
        assertTrue("offset $dy", dy in 0.0f..0.2f)
        assertTrue(dyn.children.any { it.className == "Weld" && it["Part0"] === head && it["Part1"] === dyn })
        // Re-dressing without a dynamic head brings the classic head and smile back.
        Avatar.applyTo(m, Avatar.Spec(Avatar.CLASSIC_COLORS, null, null, null, null, emptyList(), "t", r15 = false))
        assertTrue(m.findFirstChild("DynamicHead") == null)
        assertEquals(0f, PartData.transparency(head), 1e-4f)
        assertTrue(head.children.any { it.className == "Decal" && it.name == "face" })
    }

    /** HEAD_SAMPLES: a folder of head assets (raw or RBXH-wrapped from rbx-storage). Skipped when unset. */
    @Test
    fun realHeadsBuild() {
        val dir = System.getenv("HEAD_SAMPLES")
        assumeTrue(dir != null)
        for (f in File(dir!!).listFiles()!!.filter { it.isFile }) {
            val raw = f.readBytes()
            val i = String(raw, Charsets.ISO_8859_1).indexOf("<roblox")
            val bytes = if (i > 0) raw.copyOfRange(i, raw.size) else raw
            val doc = try { RbxFile.read(bytes) } catch (_: Exception) { continue }
            if (doc.roots.none { r -> (listOf(r) + r.descendants()).any { it.isA("BasePart") && it.name == "Head" } }) continue
            val head = Avatar.buildR15("P", spec(bytes)).findFirstChild("Head")!!
            val pos = PartData.cframe(head).position
            assertTrue("${f.name}: head at $pos", kotlin.math.abs(pos.x) < 0.1f && pos.y in 1.3f..1.9f && kotlin.math.abs(pos.z) < 0.1f)
            assertTrue("${f.name}: head has no mesh", PartData.meshContent(head) != null)
        }
    }
}
