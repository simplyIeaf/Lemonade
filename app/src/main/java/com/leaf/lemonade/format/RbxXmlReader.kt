package com.leaf.lemonade.format

import android.util.Base64
import android.util.Xml
import com.leaf.lemonade.model.*
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

object RbxXmlReader {
    private class XNode(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<XNode>()
        val text = StringBuilder()
        fun child(n: String) = children.firstOrNull { it.name.equals(n, true) }
        fun t(n: String) = child(n)?.text?.toString()?.trim() ?: ""
        fun f(n: String) = t(n).toFloatOrNull() ?: 0f
    }

    fun read(bytes: ByteArray): RbxDocument {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(bytes), null)
        val root = parseTree(p) ?: throw RbxFormatException("Empty XML")
        val warnings = ArrayList<String>()
        val refs = HashMap<String, Instance>()
        val pending = ArrayList<Triple<Instance, String, String>>()
        val shared = HashMap<String, ByteArray>()
        val pendingShared = ArrayList<Triple<Instance, String, String>>()
        val meta = LinkedHashMap<String, String>()
        root.children.firstOrNull { it.name == "SharedStrings" }?.children?.forEach {
            shared[it.attrs["md5"] ?: ""] = decodeB64(it.text.toString())
        }
        val roots = ArrayList<Instance>()

        fun build(node: XNode, parent: Instance?) {
            val inst = Instance(node.attrs["class"] ?: "Folder")
            if (ClassDb.isServiceName(inst.className) && parent == null) inst.isService = true
            node.attrs["referent"]?.let { refs[it] = inst }
            node.child("Properties")?.children?.forEach { pn ->
                val name = pn.attrs["name"] ?: return@forEach
                try {
                    when (pn.name) {
                        "Ref" -> { pending.add(Triple(inst, name, pn.text.toString().trim())); inst.propTypes[name] = PropType.REF }
                        "SharedString" -> { pendingShared.add(Triple(inst, name, pn.text.toString().trim())); inst.propTypes[name] = PropType.SHAREDSTRING }
                        else -> {
                            val (type, v) = convert(pn)
                            if (type == -1) warnings.add("Unknown XML property type ${pn.name} ($name)")
                            else { inst.props[name] = v; inst.propTypes[name] = type }
                        }
                    }
                } catch (e: Exception) {
                    warnings.add("Bad value for $name: ${e.message}")
                }
            }
            if (parent == null) roots.add(inst) else inst.attachRaw(parent)
            node.children.filter { it.name == "Item" }.forEach { build(it, inst) }
        }

        for (c in root.children) {
            when (c.name) {
                "Item" -> build(c, null)
                "Meta" -> meta[c.attrs["name"] ?: ""] = c.text.toString()
            }
        }
        for ((i, n, r) in pending) i.props[n] = if (r == "null" || r.isEmpty()) null else refs[r]
        for ((i, n, k) in pendingShared) i.props[n] = SharedStr(shared[k] ?: ByteArray(0))
        return RbxDocument(roots, meta, warnings)
    }

    private fun decodeB64(s: String): ByteArray = try {
        Base64.decode(s.replace(Regex("\\s"), ""), Base64.DEFAULT)
    } catch (e: Exception) { ByteArray(0) }

    private fun parseTree(p: XmlPullParser): XNode? {
        val stack = ArrayList<XNode>()
        var root: XNode? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val attrs = HashMap<String, String>()
                    for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                    val n = XNode(p.name, attrs)
                    if (stack.isEmpty()) root = n else stack.last().children.add(n)
                    stack.add(n)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (stack.isNotEmpty()) stack.last().text.append(p.text)
                XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            ev = p.nextToken()
        }
        return root
    }

    private fun cframe(n: XNode): CFrame = CFrame(
        n.f("X"), n.f("Y"), n.f("Z"),
        n.f("R00"), n.f("R01"), n.f("R02"), n.f("R10"), n.f("R11"), n.f("R12"), n.f("R20"), n.f("R21"), n.f("R22"),
    )

    private fun content(n: XNode): String {
        val u = n.child("url") ?: return n.text.toString().trim()
        return u.text.toString().trim()
    }

    private fun convert(n: XNode): Pair<Int, Any?> {
        val txt = n.text.toString()
        val t = txt.trim()
        return when (n.name) {
            "string" -> PropType.STRING to txt
            "ProtectedString" -> PropType.STRING to txt
            "Content", "ContentId" -> PropType.STRING to content(n)
            "BinaryString" -> PropType.STRING to BinaryString(decodeB64(t))
            "bool" -> PropType.BOOL to (t == "true")
            "int" -> PropType.INT to (t.toLongOrNull()?.toInt() ?: 0)
            "float" -> PropType.FLOAT to (t.toFloatOrNull() ?: parseSpecial(t))
            "double" -> PropType.DOUBLE to (t.toDoubleOrNull() ?: parseSpecial(t).toDouble())
            "int64" -> PropType.INT64 to (t.toLongOrNull() ?: 0L)
            "token" -> PropType.ENUM to EnumValue(t.toLongOrNull()?.toInt() ?: 0)
            "BrickColor" -> PropType.BRICKCOLOR to BrickColorV(t.toIntOrNull() ?: 194)
            "Color3" -> PropType.COLOR3 to (if (n.child("R") != null) Color3(n.f("R"), n.f("G"), n.f("B"))
                else Color3.fromArgb((t.toLongOrNull() ?: 0L).toInt()))
            "Color3uint8" -> PropType.COLOR3U8 to Color3.fromArgb((t.toLongOrNull() ?: 0L).toInt())
            "Vector3" -> PropType.VECTOR3 to Vector3(n.f("X"), n.f("Y"), n.f("Z"))
            "Vector2" -> PropType.VECTOR2 to Vector2(n.f("X"), n.f("Y"))
            "Vector3int16" -> PropType.VECTOR3INT16 to Vector3int16(n.f("X").toInt(), n.f("Y").toInt(), n.f("Z").toInt())
            "CoordinateFrame", "CFrame" -> PropType.CFRAME to cframe(n)
            "OptionalCoordinateFrame" -> PropType.OPTCFRAME to OptionalCFrame(n.child("CFrame")?.let { cframe(it) })
            "UDim" -> PropType.UDIM to UDim(n.f("S"), n.f("O").toInt())
            "UDim2" -> PropType.UDIM2 to UDim2(n.f("XS"), n.f("XO").toInt(), n.f("YS"), n.f("YO").toInt())
            "Rect2D" -> {
                val mn = n.child("min"); val mx = n.child("max")
                PropType.RECT to Rect(mn?.f("X") ?: 0f, mn?.f("Y") ?: 0f, mx?.f("X") ?: 0f, mx?.f("Y") ?: 0f)
            }
            "NumberRange" -> {
                val p = t.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                PropType.NUMBERRANGE to NumberRange(p.getOrElse(0) { 0f }, p.getOrElse(1) { 0f })
            }
            "NumberSequence" -> {
                val p = t.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                PropType.NUMBERSEQ to NumberSequence(p.chunked(3).filter { it.size == 3 }.map { NumberKey(it[0], it[1], it[2]) })
            }
            "ColorSequence" -> {
                val p = t.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                PropType.COLORSEQ to ColorSequence(p.chunked(5).filter { it.size == 5 }.map { ColorKey(it[0], Color3(it[1], it[2], it[3]), it[4]) })
            }
            "PhysicalProperties" -> {
                val custom = n.t("CustomPhysics") == "true"
                PropType.PHYSPROPS to if (!custom) PhysicalProps(0) else PhysicalProps(
                    1, n.f("Density"), n.f("Friction"), n.f("Elasticity"), n.f("FrictionWeight"), n.f("ElasticityWeight"),
                )
            }
            "Faces" -> PropType.FACES to Faces(n.t("faces").toIntOrNull() ?: 0)
            "Axes" -> PropType.AXES to Axes(n.t("axes").toIntOrNull() ?: 0)
            "Ray" -> {
                val o = n.child("origin"); val d = n.child("direction")
                PropType.RAY to Ray(Vector3(o?.f("X") ?: 0f, o?.f("Y") ?: 0f, o?.f("Z") ?: 0f), Vector3(d?.f("X") ?: 0f, d?.f("Y") ?: 0f, d?.f("Z") ?: 0f))
            }
            "UniqueId" -> {
                val b = ByteArray(16)
                t.chunked(2).take(16).forEachIndexed { i, h -> b[i] = (h.toIntOrNull(16) ?: 0).toByte() }
                PropType.UNIQUEID to UniqueId(b)
            }
            "Font" -> PropType.FONT to FontValue(
                n.child("Family")?.let { content(it) } ?: "", n.t("Weight").toIntOrNull() ?: 400,
                if (n.t("Style") == "Italic") 1 else 0, n.child("CachedFaceId")?.let { content(it) } ?: "",
            )
            "SecurityCapabilities" -> PropType.SECCAPS to SecurityCaps(t.toLongOrNull() ?: 0L)
            else -> -1 to null
        }
    }

    private fun parseSpecial(t: String) = when (t.uppercase()) {
        "INF" -> Float.POSITIVE_INFINITY
        "-INF" -> Float.NEGATIVE_INFINITY
        "NAN" -> Float.NaN
        else -> 0f
    }
}
