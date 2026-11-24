package com.leaf.lemonade

import com.leaf.lemonade.engine.TerrainGenerator
import com.leaf.lemonade.engine.TerrainGrid
import com.leaf.lemonade.engine.TerrainMaterials
import com.leaf.lemonade.engine.TerrainMesher
import com.leaf.lemonade.engine.TerrainOps
import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.Vector3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Terrain voxels: the SmoothGrid codec (byte-exact round trips of real grids in TERRAIN_GRIDS
 * when set), fills, the generator and the mesher.
 */
class TerrainGridTest {
    @Test
    fun realGridsRoundTripExactly() {
        val dir = System.getenv("TERRAIN_GRIDS")
        assumeTrue(dir != null)
        val files = File(dir!!).listFiles()!!.filter { it.name.startsWith("grid_") }.distinctBy { it.length() }
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val b = f.readBytes()
            val g = TerrainGrid.decode(b)
            assertArrayEquals("round trip of ${f.name}", b, g.encode())
        }
    }

    /** Re-encodes every real chunk from its voxels (not its stored bytes): must match Roblox's bytes. */
    @Test
    fun encoderMatchesRobloxChunkByChunk() {
        val dir = System.getenv("TERRAIN_GRIDS")
        assumeTrue(dir != null)
        var checked = 0
        for (f in File(dir!!).listFiles()!!.filter { it.name.startsWith("grid_") }.distinctBy { it.length() }) {
            val g = TerrainGrid.decode(f.readBytes())
            for (c in g.chunks.values) {
                val rle = c.rle!!
                val one = TerrainGrid()
                val copy = TerrainGrid.Chunk(0, 0, 0, one)
                if (c.isUniform) { copy.isUniform = true; copy.uMat = c.uMat; copy.uOcc = c.uOcc }
                else { val (m, o, l) = TerrainGrid.expandRle(rle); copy.isUniform = false; copy.mat = m; copy.occ = o; copy.liquid = l }
                copy.dirty = true
                one.chunks[TerrainGrid.key(0, 0, 0)] = copy
                val body = one.encode().copyOfRange(14, one.encode().size)
                assertArrayEquals("chunk ${c.cx},${c.cy},${c.cz} of ${f.name}", rle, body)
                checked++
            }
        }
        assertTrue(checked > 1000)
    }

    @Test
    fun emptyGridIsHeaderOnly() {
        assertArrayEquals(byteArrayOf(1, 5), TerrainGrid().encode())
        assertEquals(0, TerrainGrid.decode(byteArrayOf(1, 5)).chunks.size)
    }

    @Test
    fun fillBlockMakesSolidVoxelsThatSurviveEncoding() {
        val g = TerrainGrid()
        val grass = TerrainMaterials.fromName("Grass")
        TerrainOps.fillBlock(g, CFrame.at(Vector3(0f, -4f, 0f)), Vector3(64f, 8f, 64f), grass)
        assertEquals(grass, g.material(0, -1, 0))
        assertEquals(1f, g.occupancy(0, -1, 0), 1e-3f)
        assertEquals(0, g.material(0, 1, 0))
        val back = TerrainGrid.decode(g.encode())
        assertEquals(grass, back.material(3, -2, -3))
        assertEquals(g.countCells(), back.countCells())
        // Negative chunk coordinates (delta-coded) come back in place.
        assertEquals(grass, back.material(-8, -1, -8))
    }

    @Test
    fun generatorAndMesher() {
        val g = TerrainGrid()
        val n = TerrainGenerator.generate(g, TerrainGenerator.Options(size = 128, seed = 7))
        assertTrue("voxels written", n > 1000)
        val back = TerrainGrid.decode(g.encode())
        assertEquals(g.countCells(), back.countCells())
        // Mesh the chunk at the origin: it has a surface (ground meets air or water).
        var tris = 0
        for (cy in -2..1) {
            val m = TerrainMesher.mesh(g, 0, cy, 0, TerrainMaterials.DEFAULT_COLORS, 0)
            tris += m.solidVerts / 3
            m.collision?.let { assertEquals(m.solidVerts / 3, it.count) }
        }
        assertTrue("surface triangles", tris > 100)
    }
}
