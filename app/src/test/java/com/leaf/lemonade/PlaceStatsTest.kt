package com.leaf.lemonade

import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.EnumValue
import com.leaf.lemonade.model.PartData
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Prints what kinds of parts a place (RBX_PLACE) is made of, to tune the renderer's batching. */
class PlaceStatsTest {
    @Test
    fun stats() {
        val path = System.getenv("RBX_PLACE")
        assumeTrue(path != null)
        val doc = RbxFile.read(File(path!!).readBytes())
        val ws = doc.roots.firstOrNull { it.className == "Workspace" } ?: return
        val parts = ws.descendants().filter { it.isA("BasePart") }
        val sb = StringBuilder("parts=${parts.size}\n")
        parts.groupingBy { it.className }.eachCount().entries.sortedByDescending { it.value }.forEach { sb.append("  ${it.key}: ${it.value}\n") }
        val transparent = parts.count { PartData.transparency(it) > 0.001f }
        val noShadow = parts.count { it["CastShadow"] == false }
        val meshChild = parts.count { p -> p.children.any { it.className == "SpecialMesh" || it.className == "FileMesh" } }
        val fileMesh = parts.count { p -> p.children.any { it.className == "FileMesh" || (it.className == "SpecialMesh" && ((it["MeshType"] as? EnumValue)?.value ?: 0) == 5) } }
        val inHumanoid = parts.count { p -> generateSequence(p.parent) { it.parent }.any { it.className == "Model" && it.findFirstChildOfClass("Humanoid") != null } }
        val hinge = parts.count { p -> listOf("TopSurface", "BottomSurface", "FrontSurface", "BackSurface", "LeftSurface", "RightSurface").any { ((p[it] as? EnumValue)?.value ?: 0) >= 6 } }
        val unanch = parts.count { it["Anchored"] != true }
        val mats = parts.groupingBy { PartData.material(it) }.eachCount().entries.sortedByDescending { it.value }.take(8)
        sb.append("transparent=$transparent noShadow=$noShadow meshChild=$meshChild fileMesh=$fileMesh inHumanoid=$inHumanoid hingeSurf=$hinge unanchored=$unanch\n")
        sb.append("materials=$mats\n")
        val all = ws.descendants()
        val decals = all.filter { (it.className == "Decal" || it.className == "Texture") && it.parent?.isA("BasePart") == true }
        sb.append("decals=${decals.count { it.className == "Decal" }} textures=${decals.count { it.className == "Texture" }} uniqueImages=${decals.map { (it["Texture"] ?: it["ColorMap"]).toString() }.distinct().size}\n")
        val sgs = all.filter { it.className == "SurfaceGui" }
        sb.append("surfaceGuis=${sgs.size} sgDescendants=${sgs.sumOf { it.descendants().size }} clothing=${all.count { it.className == "Shirt" || it.className == "Pants" || it.className == "ShirtGraphic" }}\n")
        sb.append("billboards=${all.count { it.className == "BillboardGui" }} lights=${all.count { it.isA("Light") }} particles=${all.count { it.className == "ParticleEmitter" }} sounds=${all.count { it.className == "Sound" }} scripts=${all.count { it.isA("BaseScript") }}\n")
        println(sb)
        File(System.getProperty("java.io.tmpdir"), "place_stats.txt").writeText(sb.toString())
    }
}
