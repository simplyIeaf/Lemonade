package com.leaf.lemonade.lua

import com.leaf.lemonade.engine.TerrainGrid
import com.leaf.lemonade.engine.TerrainMaterials
import com.leaf.lemonade.engine.TerrainOps
import com.leaf.lemonade.engine.TerrainStore
import com.leaf.lemonade.model.*
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import kotlin.math.floor

/** Workspace.Terrain's scripting API (fills, voxel reads/writes, material colors, cell math). */
object TerrainApi {
    val METHODS = setOf("FillBlock", "FillBall", "FillCylinder", "FillWedge", "FillRegion", "Clear", "ReplaceMaterial", "ReadVoxels",
        "WriteVoxels", "ReadVoxelChannels", "WriteVoxelChannels", "GetMaterialColor", "SetMaterialColor", "WorldToCell", "WorldToCellPreferSolid",
        "WorldToCellPreferEmpty", "CellCenterToWorld", "CellCornerToWorld", "CountCells", "AutowedgeCell", "AutowedgeCells", "ConvertToSmooth",
        "CopyRegion", "PasteRegion", "GetCell", "SetCell", "SetCells")

    private const val V = TerrainGrid.VOXEL

    /** A Material enum argument as a terrain material index (errors for non-terrain materials, like Roblox). */
    private fun material(v: LuaValue): Int {
        val e = v.udOrNull<EnumItemV>() ?: throw LuaError("Material expected")
        if (e.value == 1792) return TerrainMaterials.AIR
        val m = TerrainMaterials.fromEnum(e.value)
        if (m < 0) throw LuaError("Material ${e.name} is not a terrain material")
        return m
    }

    private fun matEnum(m: Int): LuaValue {
        val name = TerrainMaterials.NAMES.getOrElse(m) { "Air" }
        return LuaTypes.wrap(EnumItemV("Material", name, TerrainMaterials.ENUM.getOrElse(m) { 1792 }))
    }

    fun call(rt: LuaRuntime, t: Instance, name: String, a: Varargs): Varargs {
        val g = TerrainStore.of(t)
        val r = when (name) {
            "FillBlock" -> { TerrainOps.fillBlock(g, a.arg(2).ud("CFrame"), a.arg(3).ud("Vector3"), material(a.arg(4))); LuaValue.NONE }
            "FillBall" -> { TerrainOps.fillBall(g, a.arg(2).ud("Vector3"), a.arg(3).num(), material(a.arg(4))); LuaValue.NONE }
            "FillCylinder" -> { TerrainOps.fillCylinder(g, a.arg(2).ud("CFrame"), a.arg(3).num(), a.arg(4).num(), material(a.arg(5))); LuaValue.NONE }
            "FillWedge" -> { TerrainOps.fillWedge(g, a.arg(2).ud("CFrame"), a.arg(3).ud("Vector3"), material(a.arg(4))); LuaValue.NONE }
            "FillRegion" -> { val reg = a.arg(2).ud<Region3V>("Region3"); TerrainOps.fillRegion(g, reg.min, reg.max, material(a.arg(4))); LuaValue.NONE }
            "Clear" -> { g.clear(); LuaValue.NONE }
            "ReplaceMaterial" -> {
                val reg = a.arg(2).ud<Region3V>("Region3")
                TerrainOps.replaceMaterial(g, reg.min, reg.max, material(a.arg(4)), material(a.arg(5))); LuaValue.NONE
            }
            "ReadVoxels" -> readVoxels(g, a.arg(2).ud("Region3"), false)
            "ReadVoxelChannels" -> readVoxels(g, a.arg(2).ud("Region3"), true)
            "WriteVoxels" -> { writeVoxels(g, a.arg(2).ud("Region3"), a.arg(4).checktable(), a.arg(5).checktable()); LuaValue.NONE }
            "WriteVoxelChannels" -> {
                val ch = a.arg(4).checktable()
                writeVoxels(g, a.arg(2).ud("Region3"), ch.get("SolidMaterial").checktable(), ch.get("SolidOccupancy").checktable(), ch.get("LiquidOccupancy").opttable(null))
                LuaValue.NONE
            }
            "GetMaterialColor" -> LuaTypes.wrap(TerrainMaterials.colors(t)[material(a.arg(2)).coerceAtLeast(0)])
            "SetMaterialColor" -> {
                val m = material(a.arg(2))
                val c = TerrainMaterials.colors(t); c[m] = a.arg(3).ud("Color3")
                t.set("MaterialColors", BinaryString(TerrainMaterials.encodeColors(c)))
                LuaValue.NONE
            }
            "WorldToCell", "WorldToCellPreferSolid", "WorldToCellPreferEmpty" -> {
                val p = a.arg(2).ud<Vector3>("Vector3")
                LuaTypes.wrap(Vector3(floor(p.x / V), floor(p.y / V), floor(p.z / V)))
            }
            "CellCenterToWorld" -> LuaTypes.wrap(Vector3(a.arg(2).num() * V + V / 2, a.arg(3).num() * V + V / 2, a.arg(4).num() * V + V / 2))
            "CellCornerToWorld" -> LuaTypes.wrap(Vector3(a.arg(2).num() * V, a.arg(3).num() * V, a.arg(4).num() * V))
            "CountCells" -> LuaValue.valueOf(g.countCells().toDouble())
            "GetCell" -> {
                val m = g.material(a.arg(2).toint(), a.arg(3).toint(), a.arg(4).toint())
                LuaValue.varargsOf(arrayOf(matEnum(m), LuaValue.NIL, LuaValue.NIL))
            }
            "SetCell" -> { g.set(a.arg(2).toint(), a.arg(3).toint(), a.arg(4).toint(), material(a.arg(5)), 1f); LuaValue.NONE }
            "AutowedgeCell", "AutowedgeCells", "ConvertToSmooth" -> LuaValue.NONE
            "CopyRegion", "PasteRegion", "SetCells" -> throw LuaError("Terrain:$name isn't supported in Lemonade yet")
            else -> return LuaValue.NONE
        }
        return r
    }

    /** Voxel-aligned bounds of a Region3 (Roblox requires resolution 4 and a grid-aligned region). */
    private fun cells(reg: Region3V): Pair<IntArray, IntArray> {
        val lo = intArrayOf(floor(reg.min.x / V + 0.001f).toInt(), floor(reg.min.y / V + 0.001f).toInt(), floor(reg.min.z / V + 0.001f).toInt())
        val hi = intArrayOf(Math.ceil((reg.max.x / V - 0.001f).toDouble()).toInt(), Math.ceil((reg.max.y / V - 0.001f).toDouble()).toInt(), Math.ceil((reg.max.z / V - 0.001f).toDouble()).toInt())
        val count = (hi[0] - lo[0]).toLong() * (hi[1] - lo[1]) * (hi[2] - lo[2])
        if (count > 4_194_304L) throw LuaError("Region has too many voxels: $count (max 4194304)")
        return lo to hi
    }

    private fun readVoxels(g: TerrainGrid, reg: Region3V, channels: Boolean): Varargs {
        val (lo, hi) = cells(reg)
        val nx = hi[0] - lo[0]; val ny = hi[1] - lo[1]; val nz = hi[2] - lo[2]
        val mats = LuaTable(); val occs = LuaTable(); val liq = LuaTable()
        val size = LuaTypes.wrap(Vector3(nx.toFloat(), ny.toFloat(), nz.toFloat()))
        mats.set("Size", size); occs.set("Size", size); liq.set("Size", size)
        for (x in 0 until nx) {
            val mx = LuaTable(); val ox = LuaTable(); val lx = LuaTable()
            for (y in 0 until ny) {
                val my = LuaTable(); val oy = LuaTable(); val ly = LuaTable()
                for (z in 0 until nz) {
                    val vx = lo[0] + x; val vy = lo[1] + y; val vz = lo[2] + z
                    val m = g.material(vx, vy, vz)
                    val o = g.occupancy(vx, vy, vz)
                    if (channels) {
                        val water = m == TerrainMaterials.WATER || g.liquid(vx, vy, vz)
                        my.set(z + 1, matEnum(if (m == TerrainMaterials.WATER) 0 else m))
                        oy.set(z + 1, LuaValue.valueOf(if (m == TerrainMaterials.WATER) 0.0 else o.toDouble()))
                        ly.set(z + 1, LuaValue.valueOf(if (water) (if (m == TerrainMaterials.WATER) o else 1f).toDouble() else 0.0))
                    } else {
                        my.set(z + 1, matEnum(m))
                        oy.set(z + 1, LuaValue.valueOf(o.toDouble()))
                    }
                }
                mx.set(y + 1, my); ox.set(y + 1, oy); lx.set(y + 1, ly)
            }
            mats.set(x + 1, mx); occs.set(x + 1, ox); liq.set(x + 1, lx)
        }
        if (!channels) return LuaValue.varargsOf(mats, occs)
        val t = LuaTable()
        t.set("SolidMaterial", mats); t.set("SolidOccupancy", occs); t.set("LiquidOccupancy", liq); t.set("Size", size)
        return t
    }

    private fun writeVoxels(g: TerrainGrid, reg: Region3V, mats: LuaTable, occs: LuaTable, liquid: LuaTable? = null) {
        val (lo, hi) = cells(reg)
        val nx = hi[0] - lo[0]; val ny = hi[1] - lo[1]; val nz = hi[2] - lo[2]
        for (x in 0 until nx) {
            val mx = mats.get(x + 1); val ox = occs.get(x + 1); val lx = liquid?.get(x + 1)
            if (!mx.istable() || !ox.istable()) continue
            for (y in 0 until ny) {
                val my = mx.get(y + 1); val oy = ox.get(y + 1); val ly = lx?.get(y + 1)
                if (!my.istable() || !oy.istable()) continue
                for (z in 0 until nz) {
                    val mv = my.get(z + 1); val ov = oy.get(z + 1)
                    val m = if (mv.isnil()) 0 else material(mv)
                    val o = if (ov.isnumber()) ov.tofloat() else 0f
                    val l = if (ly != null && ly.istable()) ly.get(z + 1).optdouble(0.0).toFloat() else 0f
                    if (m == 0 && l > 0.5f) g.set(lo[0] + x, lo[1] + y, lo[2] + z, TerrainMaterials.WATER, l)
                    else g.set(lo[0] + x, lo[1] + y, lo[2] + z, m, o)
                }
            }
        }
    }
}
