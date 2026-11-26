package com.leaf.lemonade.lua

import com.leaf.lemonade.core.CollisionGroupError
import com.leaf.lemonade.core.CollisionGroupTable
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.model.Instance
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs

object CollisionGroupApi {
    val METHODS = setOf(
        "RegisterCollisionGroup", "CreateCollisionGroup", "UnregisterCollisionGroup", "RemoveCollisionGroup", "RenameCollisionGroup",
        "CollisionGroupSetCollidable", "CollisionGroupsAreCollidable", "IsCollisionGroupRegistered", "GetRegisteredCollisionGroups",
        "GetCollisionGroups", "GetCollisionGroupId", "SetPartCollisionGroup", "GetMaxCollisionGroups", "GetCollisionGroupName", "CollisionGroupContainsPart"
    )

    private val warned = HashSet<String>()

    private fun deprecated(method: String, replacement: String) {
        if (warned.add(method)) OutputLog.warn("$method is deprecated, please use $replacement instead. More info: https://devforum.roblox.com/t/updates-to-collision-groups/1990215")
    }

    private fun str(v: LuaValue): String = if (v.isnil()) "nil" else v.tojstring()

    private fun list(withId: Boolean): LuaTable {
        val t = LuaTable()
        val g = CollisionGroupTable.shared
        for ((i, n) in g.names().withIndex()) {
            t.set(i + 1, LuaTable().apply {
                set("name", n)
                if (withId) set("id", i)
                set("mask", g.bits(n).second.toDouble())
            })
        }
        return t
    }

    fun call(physicsService: Boolean, name: String, a: Varargs): Varargs {
        val g = CollisionGroupTable.shared
        try {
            return when (name) {
                "RegisterCollisionGroup" -> { g.register(str(a.arg(2))); LuaValue.NONE }
                "CreateCollisionGroup" -> {
                    deprecated("CreateCollisionGroup", "RegisterCollisionGroup")
                    g.register(str(a.arg(2)))
                    LuaValue.valueOf((g.names().size - 1).toDouble())
                }
                "UnregisterCollisionGroup", "RemoveCollisionGroup" -> {
                    CollisionGroupTable.checkName(str(a.arg(2)))
                    g.unregister(str(a.arg(2)))
                    LuaValue.NONE
                }
                "RenameCollisionGroup" -> {
                    CollisionGroupTable.checkName(str(a.arg(2)))
                    CollisionGroupTable.checkName(str(a.arg(3)))
                    g.rename(str(a.arg(2)), str(a.arg(3)))
                    LuaValue.NONE
                }
                "CollisionGroupSetCollidable" -> {
                    g.setCollidableChecked(str(a.arg(2)), str(a.arg(3)), a.arg(4).toboolean())
                    LuaValue.NONE
                }
                "CollisionGroupsAreCollidable" -> LuaValue.valueOf(g.collidable(str(a.arg(2)), str(a.arg(3))))
                "IsCollisionGroupRegistered" -> LuaValue.valueOf(g.isRegistered(str(a.arg(2))))
                "GetRegisteredCollisionGroups" -> list(false)
                "GetCollisionGroups" -> list(true)
                "GetCollisionGroupId" -> LuaValue.valueOf((g.id(str(a.arg(2))) ?: throw CollisionGroupError("Collision group not found.")).toDouble())
                "SetPartCollisionGroup" -> {
                    deprecated("SetPartCollisionGroup", "BasePart.CollisionGroup")
                    a.arg(2).ud<Instance>("Instance").set("CollisionGroup", str(a.arg(3)))
                    LuaValue.NONE
                }
                "GetMaxCollisionGroups" -> LuaValue.valueOf(CollisionGroupTable.MAX_GROUPS.toDouble())
                "GetCollisionGroupName" -> {
                    if (warned.add("GetCollisionGroupName")) OutputLog.warn("GetCollisionGroupName is deprecated. More info: https://devforum.roblox.com/t/updates-to-collision-groups/1990215")
                    val id = Math.floor(if (a.arg(2).isnumber()) a.arg(2).todouble() else -1.0).toLong()
                    if (id < 0 || id >= CollisionGroupTable.MAX_GROUPS) throw CollisionGroupError("The Collision group Id must be between 0 and ${CollisionGroupTable.MAX_GROUPS}")
                    LuaValue.valueOf(g.names().getOrNull(id.toInt()) ?: "")
                }
                "CollisionGroupContainsPart" -> LuaValue.valueOf((a.arg(3).ud<Instance>("Instance")["CollisionGroup"]?.toString() ?: "Default") == str(a.arg(2)))
                else -> LuaValue.NONE
            }
        } catch (e: CollisionGroupError) {
            throw LuaError(e.message)
        }
    }
}
