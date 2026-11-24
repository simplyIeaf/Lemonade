package com.leaf.lemonade

import com.leaf.lemonade.format.RbxBinaryReader
import com.leaf.lemonade.format.RbxBinaryWriter
import com.leaf.lemonade.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Place files made by scripts/tools (e.g. Sprachsprung.rbxlx) leave "shape" and CastShadow out
 * of most parts. Saving/publishing used to fill the column with the type's zero, so those parts
 * became Balls (shape 0) without shadows. They must keep Roblox's class defaults.
 */
class PublishDefaultsTest {
    private fun part(withShape: Int?, castShadow: Boolean?): Instance {
        val p = Instance("Part")
        p.props["Name"] = "Floor"
        p.props["size"] = Vector3(46f, 1f, 46f); p.propTypes["size"] = PropType.VECTOR3
        p.props["CFrame"] = CFrame.IDENTITY; p.propTypes["CFrame"] = PropType.CFRAME
        if (withShape != null) { p.props["shape"] = EnumValue(withShape); p.propTypes["shape"] = PropType.ENUM }
        if (castShadow != null) { p.props["CastShadow"] = castShadow; p.propTypes["CastShadow"] = PropType.BOOL }
        return p
    }

    @Test
    fun missingPropertiesKeepClassDefaults() {
        val ws = Instance("Workspace").apply { isService = true; props["Name"] = "Workspace" }
        val plain = part(null, null)
        val cylinder = part(2, false)
        plain.attachRaw(ws); cylinder.attachRaw(ws)

        val doc = RbxBinaryReader(RbxBinaryWriter.write(listOf(ws), emptyMap())).read()
        val parts = doc.roots.first().children
        assertEquals("part without shape stays a Block", 1, (parts[0]["shape"] as EnumValue).value)
        assertEquals(true, parts[0]["CastShadow"])
        assertEquals("explicit shape is kept", 2, (parts[1]["shape"] as EnumValue).value)
        assertEquals(false, parts[1]["CastShadow"])
    }
}
