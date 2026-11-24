package com.leaf.lemonade.render

import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.format.CsgMesh
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.BinaryString
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.SharedStr
import com.leaf.lemonade.model.Vector3
import com.leaf.lemonade.net.AssetFetcher
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Geometry of unions made in Roblox Studio (loaded from files or the Toolbox). Their mesh is either
 * stored inline (MeshData / MeshData2) or in a PartOperationAsset that AssetId points to, which is
 * downloaded like any other asset. [get] never blocks.
 */
object UnionMeshes {
    /**
     * Triangles grouped by vertex color (ARGB): interleaved position(3) + normal(3) + uv(2), in the
     * mesh's own units. [center]/[size] are its bounds.
     */
    class Data(val key: String, val groups: List<Pair<Int, FloatArray>>, val center: Vector3, val size: Vector3, val triangles: List<FloatArray>)

    private val ready = ConcurrentHashMap<String, Data>()
    private val bad = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor()
    private val inlineKeys = Collections.synchronizedMap(WeakHashMap<Any, String>())
    @Volatile var epoch = 0
        private set
    @Volatile private var listening = false

    private fun bytesOf(v: Any?): ByteArray? = when (v) {
        is BinaryString -> v.bytes
        is SharedStr -> v.bytes
        is String -> if (v.length > 16) v.toByteArray(Charsets.UTF_8) else null
        else -> null
    }?.takeIf { it.size > 16 }

    /** True for unions whose shape comes from Roblox's stored mesh rather than child parts. */
    fun isStored(p: Instance) = p.isA("PartOperation") && p.className != "NegateOperation" && p.children.none { it.isA("BasePart") }

    fun get(p: Instance): Data? {
        val inlineVal = p.props["MeshData2"]?.takeIf { bytesOf(it) != null } ?: p.props["MeshData"]?.takeIf { bytesOf(it) != null }
        if (inlineVal != null) {
            val key = inlineKeys[inlineVal] ?: run {
                val b = bytesOf(inlineVal)!!
                ("inline:" + b.size + ":" + b.contentHashCode()).also { inlineKeys[inlineVal] = it }
            }
            ready[key]?.let { return it }
            if (key in bad || !pending.add(key)) return null
            val b = bytesOf(inlineVal)!!
            pool.execute { build(key, p.name) { b } }
            return null
        }
        val id = AssetFetcher.idOf(p.props["AssetId"]) ?: return null
        val key = "asset:$id"
        ready[key]?.let { return it }
        if (key in bad || key in pending) return null
        val bytes = AssetFetcher.request(id)
        if (bytes == null) {
            if (!listening) {
                listening = true
                AssetFetcher.listeners.add { done -> if (!ready.containsKey("asset:$done")) epoch++ }
            }
            return null
        }
        if (!pending.add(key)) return null
        pool.execute { build(key, p.name) { meshDataFromAsset(bytes) } }
        return null
    }

    private fun meshDataFromAsset(bytes: ByteArray): ByteArray {
        if (CsgMesh.isCsgMesh(bytes)) return bytes
        val doc = RbxFile.read(bytes)
        for (r in doc.roots) for (i in listOf(r) + r.descendants()) {
            bytesOf(i.props["MeshData"])?.let { return it }
            bytesOf(i.props["MeshData2"])?.let { return it }
        }
        throw CsgMesh.CsgException("the union asset has no mesh")
    }

    private fun build(key: String, name: String, source: () -> ByteArray) {
        try {
            val m = CsgMesh.decode(source())
            ready[key] = toData(key, m)
        } catch (e: Exception) {
            bad.add(key)
            OutputLog.warn("Union '$name' can't be shown (${e.message ?: e}); it is drawn as a box.")
        } finally {
            pending.remove(key)
            epoch++
        }
    }

    private fun toData(key: String, m: CsgMesh.Decoded): Data {
        val p = m.pos; val n = m.nrm
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in m.indices) for (c in 0..2) { val v = p[i * 3 + c]; if (v < mn[c]) mn[c] = v; if (v > mx[c]) mx[c] = v }
        if (mn[0] > mx[0]) { mn.fill(0f); mx.fill(0f) }
        val groups = LinkedHashMap<Int, ArrayList<Float>>()
        val all = ArrayList<FloatArray>()
        var t = 0
        while (t + 2 < m.indices.size) {
            val a = m.indices[t]; val b = m.indices[t + 1]; val c = m.indices[t + 2]
            t += 3
            val color = m.colors[a] or 0xFF000000.toInt()
            val list = groups.getOrPut(color) { ArrayList() }
            val tri = FloatArray(9)
            var k = 0
            for (v in intArrayOf(a, b, c)) {
                for (q in 0..2) { list.add(p[v * 3 + q]); tri[k++] = p[v * 3 + q] }
                var nx = n[v * 3]; var ny = n[v * 3 + 1]; var nz = n[v * 3 + 2]
                val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (l > 1e-6f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 1f; nz = 0f }
                list.add(nx); list.add(ny); list.add(nz)
                list.add(0f); list.add(0f)
            }
            all.add(tri)
        }
        return Data(key, groups.map { (col, l) -> col to l.toFloatArray() },
            Vector3((mn[0] + mx[0]) / 2f, (mn[1] + mx[1]) / 2f, (mn[2] + mx[2]) / 2f),
            Vector3(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]), all)
    }
}
