package com.leaf.lemonade

import com.leaf.lemonade.core.PlaceFactory
import com.leaf.lemonade.engine.ConvexShape
import com.leaf.lemonade.engine.TerrainMeshes
import com.leaf.lemonade.engine.TerrainStore
import com.leaf.lemonade.model.PartData
import com.leaf.lemonade.model.Vector3
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every template's spawns have Studio's decal, and terrain templates have solid ground around the spawn. */
class TemplateSpawnTest {
    @Test
    fun spawnsHaveTheDecal() {
        for (t in PlaceFactory.Template.values()) {
            val dm = PlaceFactory.newPlace(t)
            for (s in dm.workspace.descendants().filter { it.className == "SpawnLocation" }) {
                val d = s.children.firstOrNull { it.className == "Decal" }
                assertNotNull("${t.name}: ${s.name} has no decal", d)
                assertTrue(d!!["Texture"].toString() == "rbxasset://textures/SpawnLocation.png")
            }
        }
    }

    private fun box(c: Vector3, h: Vector3): Triple<ConvexShape, Vector3, Vector3> {
        val v = ArrayList<Vector3>()
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) v.add(c + Vector3(sx * h.x, sy * h.y, sz * h.z))
        val axes = listOf(Vector3.X, Vector3.Y, Vector3.Z)
        return Triple(ConvexShape(v, axes, axes), c - h, c + h)
    }

    @Test
    fun islandHasGroundAroundTheSpawn() {
        val dm = PlaceFactory.newPlace(PlaceFactory.Template.ISLAND)
        val spawn = dm.workspace.descendants().first { it.className == "SpawnLocation" }
        val sp = PartData.cframe(spawn).position
        val t = TerrainStore.terrainOf(dm)!!
        val g = TerrainStore.of(t)
        val colors = TerrainMeshes.colorsFor(t)
        val report = StringBuilder("spawn at $sp\n")
        var holes = 0
        // Walk out from the spawn in every direction, up to 24 studs.
        for (r in listOf(0f, 7f, 10f, 14f, 18f, 24f)) for (k in 0 until 8) {
            val a = k * Math.PI.toFloat() / 4
            val x = sp.x + kotlin.math.cos(a) * r; val z = sp.z + kotlin.math.sin(a) * r
            val hit = TerrainMeshes.raycast(g, colors, Vector3(x, 60f, z), Vector3(0f, -1f, 0f), 200f)
            val top = hit?.let { 60f - it.first }
            // A character-sized box sunk 0.5 studs into that ground must be pushed out upward.
            val pushed = top?.let { y ->
                val (s, lo, hi) = box(Vector3(x, y + 2.5f - 0.5f, z), Vector3(1f, 2.5f, 0.5f))
                TerrainMeshes.push(g, colors, s, lo, hi)
            }
            var ok = top != null && top > -2f && pushed != null && pushed.first.y > 0.2f
            if (!ok) {
                // Water is fine (you swim); a hole is not.
                val col = (-30..20).reversed().map { vy -> g.material(kotlin.math.floor(x / 4f).toInt(), vy, kotlin.math.floor(z / 4f).toInt()) }
                report.appendLine("  column ${col.joinToString(",")}")
                val waterAbove = (top ?: -999f) < 0f && col.take(20).any { it == com.leaf.lemonade.engine.TerrainMaterials.WATER }
                if (waterAbove) ok = true
            }
            if (!ok) holes++
            report.appendLine("r=$r a=$k top=$top push=${pushed?.first} ${if (ok) "" else "<-- no ground"}")
        }
        assertTrue("ground missing near the island spawn ($holes spots)\n$report", holes == 0)
        // The spawn sits on the ground, not floating or buried.
        val under = TerrainMeshes.raycast(g, colors, Vector3(sp.x, 60f, sp.z), Vector3(0f, -1f, 0f), 200f)!!
        val groundY = 60f - under.first
        assertTrue("spawn at ${sp.y}, ground at $groundY\n$report", sp.y - 0.5f in (groundY - 1.5f)..(groundY + 1.5f))
    }
}
