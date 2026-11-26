package com.leaf.lemonade.engine

import com.leaf.lemonade.model.CFrame
import com.leaf.lemonade.model.Enums
import com.leaf.lemonade.model.EnumValue
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.PartData
import com.leaf.lemonade.model.PhysicalProps
import com.leaf.lemonade.model.Vector3
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

object PartPhysics {
    class Props(val density: Float, val friction: Float, val elasticity: Float, val frictionWeight: Float, val elasticityWeight: Float)

    private val table: Map<String, FloatArray> = mapOf(
        "Asphalt" to floatArrayOf(2.36f, 0.2f, 0.8f, 1f, 0.3f),
        "Basalt" to floatArrayOf(2.691f, 0.15f, 0.7f, 1f, 0.3f),
        "Brick" to floatArrayOf(1.922f, 0.15f, 0.8f, 1f, 0.3f),
        "Cardboard" to floatArrayOf(0.7f, 0.05f, 0.5f, 2f, 1f),
        "Carpet" to floatArrayOf(1.1f, 0.25f, 0.4f, 2f, 1f),
        "CeramicTiles" to floatArrayOf(2.4f, 0.2f, 0.51f, 1f, 1f),
        "ClayRoofTiles" to floatArrayOf(2.0f, 0.2f, 0.51f, 1f, 1f),
        "Cobblestone" to floatArrayOf(2.691f, 0.17f, 0.5f, 1f, 1f),
        "Concrete" to floatArrayOf(2.403f, 0.2f, 0.7f, 1f, 0.3f),
        "CorrodedMetal" to floatArrayOf(7.85f, 0.2f, 0.7f, 1f, 1f),
        "CrackedLava" to floatArrayOf(2.691f, 0.15f, 0.65f, 1f, 1f),
        "DiamondPlate" to floatArrayOf(7.85f, 0.25f, 0.35f, 1f, 1f),
        "Fabric" to floatArrayOf(0.7f, 0.05f, 0.35f, 1f, 1f),
        "Foil" to floatArrayOf(2.7f, 0.25f, 0.4f, 1f, 1f),
        "ForceField" to floatArrayOf(2.403f, 0.2f, 0.25f, 1f, 1f),
        "Glacier" to floatArrayOf(0.919f, 0.15f, 0.05f, 1f, 2f),
        "Glass" to floatArrayOf(2.403f, 0.2f, 0.25f, 1f, 1f),
        "Granite" to floatArrayOf(2.691f, 0.2f, 0.4f, 1f, 1f),
        "Grass" to floatArrayOf(0.9f, 0.1f, 0.4f, 1.5f, 1f),
        "Ground" to floatArrayOf(0.9f, 0.1f, 0.45f, 1f, 1f),
        "Ice" to floatArrayOf(0.919f, 0.15f, 0.02f, 1f, 3f),
        "LeafyGrass" to floatArrayOf(0.9f, 0.1f, 0.4f, 2f, 2f),
        "Leather" to floatArrayOf(0.86f, 0.25f, 0.35f, 1f, 1f),
        "Limestone" to floatArrayOf(2.691f, 0.15f, 0.5f, 1f, 1f),
        "Marble" to floatArrayOf(2.563f, 0.17f, 0.2f, 1f, 1f),
        "Metal" to floatArrayOf(7.85f, 0.25f, 0.4f, 1f, 1f),
        "Mud" to floatArrayOf(0.9f, 0.07f, 0.3f, 4f, 3f),
        "Neon" to floatArrayOf(0.7f, 0.2f, 0.3f, 1f, 1f),
        "Pavement" to floatArrayOf(2.691f, 0.17f, 0.5f, 1f, 0.3f),
        "Pebble" to floatArrayOf(2.403f, 0.17f, 0.4f, 1.5f, 1f),
        "Plaster" to floatArrayOf(0.75f, 0.2f, 0.6f, 1f, 0.3f),
        "Plastic" to floatArrayOf(0.7f, 0.5f, 0.3f, 1f, 1f),
        "Rock" to floatArrayOf(2.691f, 0.17f, 0.5f, 1f, 1f),
        "RoofShingles" to floatArrayOf(2.36f, 0.2f, 0.8f, 1f, 0.3f),
        "Rubber" to floatArrayOf(1.3f, 0.95f, 1.5f, 2f, 3f),
        "Salt" to floatArrayOf(2.165f, 0.05f, 0.5f, 1f, 1f),
        "Sand" to floatArrayOf(1.602f, 0.05f, 0.5f, 2.5f, 5f),
        "Sandstone" to floatArrayOf(2.691f, 0.15f, 0.5f, 1f, 5f),
        "SmoothPlastic" to floatArrayOf(0.7f, 0.5f, 0.2f, 1f, 1f),
        "Slate" to floatArrayOf(2.691f, 0.2f, 0.4f, 1f, 1f),
        "Snow" to floatArrayOf(0.9f, 0.03f, 0.3f, 4f, 3f),
        "Wood" to floatArrayOf(0.35f, 0.2f, 0.48f, 1f, 1f),
        "WoodPlanks" to floatArrayOf(0.35f, 0.2f, 0.48f, 1f, 1f),
    )

    fun forMaterial(name: String?): Props {
        val r = table[name ?: "Plastic"] ?: table.getValue("Plastic")
        return Props(r[0], r[2], r[1], r[4], r[3])
    }

    fun props(p: Instance): Props {
        val c = p["CustomPhysicalProperties"] as? PhysicalProps
        if (c != null && c.custom) {
            return Props(
                c.density.coerceIn(0.0001f, 100f), c.friction.coerceIn(0f, 2f), c.elasticity.coerceIn(0f, 1f),
                c.frictionWeight.coerceIn(0f, 100f), c.elasticityWeight.coerceIn(0f, 100f),
            )
        }
        return forMaterial(Enums.itemName("Material", PartData.material(p)))
    }

    class PMass(val mass: Float, val com: Vector3, val inertia: Vector3)

    private val ZERO = PMass(0f, Vector3.ZERO, Vector3.ZERO)

    private fun shapeOf(p: Instance): Int {
        when (p.className) {
            "WedgePart" -> return 3
            "CornerWedgePart" -> return 4
            "Part", "Seat", "SpawnLocation", "SkateboardPlatform", "VehicleSeat", "Platform" -> {}
            else -> return 1
        }
        return when ((p["Shape"] as? EnumValue)?.value) {
            0 -> 0
            2 -> 2
            3 -> 3
            4 -> 4
            else -> 1
        }
    }

    private fun accessoryHandleOnCharacter(p: Instance): Boolean {
        if (p.name != "Handle") return false
        val acc = p.parent ?: return false
        if (!acc.isA("Accoutrement")) return false
        val model = acc.parent ?: return false
        return model.children.any { it.className == "Humanoid" }
    }

    fun partMass(p: Instance): PMass {
        if (p.className == "Terrain") return ZERO
        if (accessoryHandleOnCharacter(p)) return ZERO
        val raw = PartData.size(p)
        val s = Vector3(max(raw.x, 0.05f), max(raw.y, 0.05f), max(raw.z, 0.05f))
        val rho = props(p).density
        fun boxI(m: Float, s: Vector3) = Vector3(s.y * s.y + s.z * s.z, s.x * s.x + s.z * s.z, s.x * s.x + s.y * s.y) * (m / 12f)
        return when (shapeOf(p)) {
            0 -> {
                val rr = min(s.x, min(s.y, s.z)) * 0.5f
                val m = rho * 4f / 3f * Math.PI.toFloat() * rr * rr * rr
                val i = 0.4f * m * rr * rr
                PMass(m, Vector3.ZERO, Vector3(i, i, i))
            }
            2 -> {
                val rr = min(s.y, s.z) * 0.5f
                val m = rho * Math.PI.toFloat() * rr * rr * s.x
                val side = m * (3f * rr * rr + s.x * s.x) / 12f
                PMass(m, Vector3.ZERO, Vector3(0.5f * m * rr * rr, side, side))
            }
            3 -> {
                val m = rho * s.x * s.y * s.z * 0.5f
                PMass(m, Vector3(0f, -s.y / 6f, s.z / 6f), boxI(m, s))
            }
            4 -> {
                val m = rho * s.x * s.y * s.z / 3f
                PMass(m, Vector3(s.x / 8f, -s.y / 4f, -s.z / 8f), boxI(m, s))
            }
            else -> {
                val m = rho * s.x * s.y * s.z
                PMass(m, Vector3.ZERO, boxI(m, s))
            }
        }
    }

    private fun anchored(p: Instance): Boolean = p["Anchored"] == true || p.className == "Terrain"

    private fun rigidJoint(c: Instance): Boolean =
        c.className == "WeldConstraint" ||
            (c.isA("JointInstance") && c.className !in setOf("Rotate", "RotateP", "RotateV", "VelocityMotor")) ||
            (c.className == "AnimationConstraint" && c["IsKinematic"] != false)

    private fun root(p: Instance): Instance {
        var r = p
        while (r.parent != null) r = r.parent!!
        return r
    }

    private fun inWorkspace(p: Instance): Boolean {
        var r: Instance? = p
        while (r != null) {
            if (r.className == "Workspace") return true
            r = r.parent
        }
        return false
    }

    class Graph {
        val rigid = IdentityHashMap<Instance, ArrayList<Instance>>()
        val joints = IdentityHashMap<Instance, ArrayList<Instance>>()
        val group = IdentityHashMap<Instance, Int>()
        val groups = ArrayList<List<Instance>>()
        val roots = ArrayList<Pair<Instance, Boolean>>()

        fun assembly(p: Instance): List<Instance> = group[p]?.let { groups[it] } ?: listOf(p)

        fun root(p: Instance): Instance {
            if (anchored(p)) return p
            return group[p]?.let { roots[it].first } ?: p
        }

        fun grounded(p: Instance): Boolean = anchored(p) || group[p]?.let { roots[it].second } == true

        fun massOf(p: Instance): Float {
            if (p["Massless"] == true && root(p) !== p && !anchored(p)) return 0f
            return partMass(p).mass
        }
    }

    private var cached: Graph? = null
    private var cachedAt = -1L
    private var cachedRoot: Instance? = null

    private fun pickRoot(members: List<Instance>): Pair<Instance, Boolean> {
        members.firstOrNull { anchored(it) }?.let { return it to true }
        var best = members[0]
        fun key(p: Instance): List<Double> {
            val s = PartData.size(p)
            return listOf(
                if (p["Massless"] == true) 0.0 else 1.0,
                ((p["RootPriority"] as? Number)?.toInt() ?: 0).toDouble(),
                if (p.name == "HumanoidRootPart") 1.0 else 0.0,
                (s.x * s.y * s.z).toDouble(),
                max(s.x, max(s.y, s.z)).toDouble(),
            )
        }
        var bk = key(best)
        for (i in 1 until members.size) {
            val k = key(members[i])
            val head = (0 until 3).map { k[it].compareTo(bk[it]) }.firstOrNull { it != 0 } ?: 0
            val better = head > 0 || head == 0 && (k[3] > bk[3] + 1e-6 || (Math.abs(k[3] - bk[3]) <= 1e-6 && k[4] > bk[4]))
            if (better) { best = members[i]; bk = k }
        }
        return best to false
    }

    fun graph(any: Instance): Graph {
        val top = root(any)
        if (cached != null && cachedAt == Instance.changeCount && cachedRoot === top) return cached!!
        val g = Graph()
        val ws = top.children.firstOrNull { it.className == "Workspace" } ?: (if (top.className == "Workspace") top else null)
        if (ws != null) {
            val edges = ArrayList<Pair<Instance, Instance>>()
            for (c in ws.descendants()) {
                val isJoint = c.isA("JointInstance") || c.className == "WeldConstraint" || c.className == "AnimationConstraint"
                val isConstraint = c.isA("Constraint")
                if (!isJoint && !isConstraint) continue
                val a: Instance?
                val b: Instance?
                if (isJoint) {
                    a = c["Part0"] as? Instance; b = c["Part1"] as? Instance
                } else {
                    a = (c["Attachment0"] as? Instance)?.parent; b = (c["Attachment1"] as? Instance)?.parent
                }
                for (p in listOf(a, b)) if (p != null && p.isA("BasePart")) g.joints.getOrPut(p) { ArrayList() }.add(c)
                if (a != null && b != null && a !== b && isJoint && rigidJoint(c) && c["Enabled"] != false &&
                    a.isA("BasePart") && b.isA("BasePart") && a.isDescendantOf(ws) && b.isDescendantOf(ws)
                ) {
                    edges.add(a to b)
                    g.rigid.getOrPut(a) { ArrayList() }.add(b)
                    g.rigid.getOrPut(b) { ArrayList() }.add(a)
                }
            }
            val seen = IdentityHashMap<Instance, Boolean>()
            for ((a, _) in edges) {
                if (seen.containsKey(a)) continue
                val comp = ArrayList<Instance>()
                comp.add(a); seen[a] = true
                var i = 0
                while (i < comp.size) {
                    val p = comp[i++]
                    for (q in g.rigid[p].orEmpty()) if (seen.put(q, true) == null) comp.add(q)
                }
                val gi = g.groups.size
                g.groups.add(comp)
                for (m in comp) g.group[m] = gi
                g.roots.add(pickRoot(comp))
            }
        }
        cached = g
        cachedAt = Instance.changeCount
        cachedRoot = top
        return g
    }

    fun effMass(p: Instance): Float = graph(p).massOf(p)

    class Assembly(val mass: Float, val com: Vector3)

    fun assembly(p: Instance): Assembly {
        val g = graph(p)
        var m = 0f
        var cx = 0f; var cy = 0f; var cz = 0f
        for (q in g.assembly(p)) {
            val em = g.massOf(q)
            val wc = (q["CFrame"] as? CFrame ?: CFrame.IDENTITY) * partMass(q).com
            m += em
            cx += wc.x * em; cy += wc.y * em; cz += wc.z * em
        }
        val com = if (m > 0f) Vector3(cx / m, cy / m, cz / m) else (p["CFrame"] as? CFrame ?: CFrame.IDENTITY).position
        return Assembly(m, com)
    }

    fun rootOf(p: Instance): Instance? = if (!inWorkspace(p)) null else graph(p).root(p)

    fun connected(p: Instance, recursive: Boolean): List<Instance> {
        val g = graph(p)
        if (recursive) return g.assembly(p)
        val out = ArrayList<Instance>()
        out.add(p)
        for (q in g.rigid[p].orEmpty()) if (out.none { it === q }) out.add(q)
        return out
    }

    fun jointsOf(p: Instance): List<Instance> = graph(p).joints[p].orEmpty()

    fun centerOfMass(p: Instance): Vector3 = (p["CFrame"] as? CFrame ?: CFrame.IDENTITY) * partMass(p).com
}
