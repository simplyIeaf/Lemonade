package com.leaf.lemonade

import com.leaf.lemonade.engine.Csg
import com.leaf.lemonade.model.*
import com.leaf.lemonade.render.RbxMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SolidModelingTest {
    private fun block(size: Vector3, at: Vector3): Instance {
        val p = InstanceFactory.create("Part")
        p.set("size", size, false)
        p.set("CFrame", CFrame.at(at), false)
        return p
    }

    /** Volume of a closed polygon soup (divergence theorem over fan triangles). */
    private fun volume(polys: List<Csg.Polygon>): Float {
        var v = 0.0
        for (p in polys) for (i in 1 until p.vertices.size - 1) {
            val a = p.vertices[0].pos; val b = p.vertices[i].pos; val c = p.vertices[i + 1].pos
            v += a.dot(b.cross(c)) / 6.0
        }
        return v.toFloat()
    }

    private fun bounds(polys: List<Csg.Polygon>): Pair<Vector3, Vector3> {
        var mn = Vector3(1e9f, 1e9f, 1e9f); var mx = -mn
        for (p in polys) for (v in p.vertices) { mn = mn.min(v.pos); mx = mx.max(v.pos) }
        return mn to mx
    }

    @Test fun unionOfOverlappingBlocks() {
        val a = Csg.partPolygons(block(Vector3(4f, 4f, 4f), Vector3.ZERO), 0)
        val b = Csg.partPolygons(block(Vector3(4f, 4f, 4f), Vector3(2f, 0f, 0f)), 1)
        assertEquals(64f, volume(a), 0.01f)
        val u = Csg.union(a, b)
        // 4x4x4 + 4x4x4 overlapping by 2x4x4 = 96
        assertEquals(96f, volume(u), 0.05f)
        val (mn, mx) = bounds(u)
        assertEquals(-2f, mn.x, 1e-3f); assertEquals(4f, mx.x, 1e-3f)
    }

    @Test fun subtractAndIntersect() {
        val a = Csg.partPolygons(block(Vector3(4f, 4f, 4f), Vector3.ZERO), 0)
        val b = Csg.partPolygons(block(Vector3(2f, 8f, 2f), Vector3.ZERO), 1)
        // A 2x4x2 hole through the block: 64 - 16 = 48
        assertEquals(48f, volume(Csg.subtract(a, b)), 0.05f)
        assertEquals(16f, volume(Csg.intersect(a, b)), 0.05f)
    }

    @Test fun wedgeAndCylinderAreClosed() {
        val w = InstanceFactory.create("WedgePart"); w.set("size", Vector3(2f, 2f, 2f), false); w.set("CFrame", CFrame.IDENTITY, false)
        assertEquals(4f, volume(Csg.partPolygons(w, 0)), 0.01f)
        val c = block(Vector3(4f, 2f, 2f), Vector3.ZERO); c.set("shape", EnumValue(2), false)
        // pi r^2 h with a 20-sided polygon (slightly less than the true circle)
        val v = volume(Csg.partPolygons(c, 0))
        assertTrue("cylinder volume $v", abs(v - Math.PI.toFloat() * 4f) < 0.25f)
    }

    @Test fun parsesVersion2Mesh() {
        val header = "version 2.00\n".toByteArray()
        val bb = ByteBuffer.allocate(12 + 3 * 36 + 12).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(12); bb.put(36); bb.put(12); bb.putInt(3); bb.putInt(1)
        val pts = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(2f, 0f, 0f), floatArrayOf(0f, 4f, 0f))
        for (p in pts) { bb.putFloat(p[0]); bb.putFloat(p[1]); bb.putFloat(p[2]); bb.putFloat(0f); bb.putFloat(0f); bb.putFloat(1f); bb.putFloat(0.5f); bb.putFloat(0.25f); bb.putFloat(0f) }
        bb.putInt(0); bb.putInt(1); bb.putInt(2)
        val m = RbxMesh.parse(header + bb.array())
        assertEquals(1, m.triangles)
        assertEquals(2f, m.size(0), 1e-6f); assertEquals(4f, m.size(1), 1e-6f)
        assertEquals(0.25f, m.verts[7], 1e-6f)
    }

    @Test fun parsesVersion1Mesh() {
        val text = "version 1.00\n1\n[0,0,0][0,0,1][0,0,0][2,0,0][0,0,1][1,0,0][0,2,0][0,0,1][0,1,0]"
        val m = RbxMesh.parse(text.toByteArray())
        assertEquals(1, m.triangles)
        assertEquals(1f, m.size(0), 1e-6f) // v1.00 meshes are scaled by 0.5
    }
}
