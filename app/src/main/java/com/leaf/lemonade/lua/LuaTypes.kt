package com.leaf.lemonade.lua

import com.leaf.lemonade.model.*
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sign

data class EnumItemV(val type: String, val name: String, val value: Int)
class EnumTypeV(val name: String)
data class TweenInfoV(val time: Float, val style: Int, val direction: Int, val repeatCount: Int, val reverses: Boolean, val delay: Float)
class RandomV(seed: Long) { val rng = java.util.Random(seed) }
class RaycastParamsV { var filter: List<Instance> = emptyList(); var filterType = 0; var ignoreWater = false; var collisionGroup = "Default"; var respectCanCollide = false }
class RaycastResultV(val hit: RayHitInfo)
/** Region3: an axis-aligned box (stored as its center frame and size, like Roblox exposes it). */
class Region3V(val min: Vector3, val max: Vector3) {
    val cframe get() = CFrame.at((min + max) / 2f)
    val size get() = max - min
    override fun toString() = "${cframe.position}; $size"
}
class Region3int16V(val min: Vector3int16, val max: Vector3int16)
class RayHitInfo(val inst: Instance, val position: Vector3, val normal: Vector3, val distance: Float, val material: Int)

fun fn1(f: (LuaValue) -> LuaValue) = object : OneArgFunction() { override fun call(a: LuaValue) = f(a) }
fun fn2(f: (LuaValue, LuaValue) -> LuaValue) = object : TwoArgFunction() { override fun call(a: LuaValue, b: LuaValue) = f(a, b) }
fun fnv(f: (Varargs) -> Varargs) = object : VarArgFunction() { override fun invoke(args: Varargs): Varargs = f(args) }

fun LuaValue.num(): Float = this.checkdouble().toFloat()
fun LuaValue.optNum(d: Float): Float = if (this.isnil()) d else this.checkdouble().toFloat()
inline fun <reified T> LuaValue.ud(what: String): T =
    (if (this.isuserdata()) this.touserdata() else null) as? T ?: throw LuaError("$what expected, got ${LuaTypes.typeOf(this)}")
inline fun <reified T> LuaValue.udOrNull(): T? = (if (this.isuserdata()) this.touserdata() else null) as? T

object LuaTypes {
    private val metas = HashMap<Class<*>, LuaTable>()

    fun typeOf(v: LuaValue): String {
        if (v.isuserdata()) {
            val mt = v.getmetatable()
            val t = mt?.get("__type")
            if (t != null && t.isstring()) return t.tojstring()
            return "userdata"
        }
        return v.typename()
    }

    fun wrap(o: Any): LuaValue {
        val mt = metas[o.javaClass] ?: generic(o.javaClass.simpleName)
        return LuaUserdata(o, mt)
    }

    private fun generic(name: String): LuaTable = LuaTable().apply {
        set("__type", name)
        set(LuaValue.TOSTRING, fn1 { LuaValue.valueOf(it.touserdata().toString()) })
    }

    private fun meta(cls: Class<*>, typeName: String, index: (Any, String) -> LuaValue?, extra: LuaTable.() -> Unit = {}) {
        val mt = LuaTable()
        mt.set("__type", typeName)
        mt.set(LuaValue.INDEX, fn2 { self, key ->
            val k = key.tojstring()
            index(self.touserdata(), k) ?: throw LuaError("$k is not a valid member of $typeName")
        })
        mt.set(LuaValue.NEWINDEX, fn2 { _, key -> throw LuaError("${key.tojstring()} cannot be assigned to") })
        mt.set(LuaValue.TOSTRING, fn1 { LuaValue.valueOf(it.touserdata().toString()) })
        mt.extra()
        metas[cls] = mt
    }

    private fun method(f: (Varargs) -> Varargs): LuaValue = fnv(f)
    private fun n(v: Float): LuaValue = LuaValue.valueOf(v.toDouble())

    fun v3(v: Vector3) = wrap(v)

    private fun arg3(a: Varargs, i: Int): Vector3 = a.arg(i).ud("Vector3")

    init {
        meta(Vector3::class.java, "Vector3", { o, k ->
            val v = o as Vector3
            when (k) {
                "X", "x" -> n(v.x); "Y", "y" -> n(v.y); "Z", "z" -> n(v.z)
                "Magnitude", "magnitude" -> n(v.magnitude)
                "Unit", "unit" -> wrap(v.unit)
                "Dot" -> method { a -> n(arg3(a, 1).dot(arg3(a, 2))) }
                "Cross" -> method { a -> wrap(arg3(a, 1).cross(arg3(a, 2))) }
                "Lerp", "lerp" -> method { a -> wrap(arg3(a, 1).lerp(arg3(a, 2), a.arg(3).num())) }
                "Abs" -> method { a -> wrap(arg3(a, 1).abs()) }
                "Floor" -> method { a -> val x = arg3(a, 1); wrap(Vector3(floor(x.x), floor(x.y), floor(x.z))) }
                "Ceil" -> method { a -> val x = arg3(a, 1); wrap(Vector3(ceil(x.x), ceil(x.y), ceil(x.z))) }
                "Sign" -> method { a -> val x = arg3(a, 1); wrap(Vector3(sign(x.x), sign(x.y), sign(x.z))) }
                "Min" -> method { a -> wrap(arg3(a, 1).min(arg3(a, 2))) }
                "Max" -> method { a -> wrap(arg3(a, 1).max(arg3(a, 2))) }
                "Angle" -> method { a -> val x = arg3(a, 1).unit; val y = arg3(a, 2).unit; n(kotlin.math.acos(x.dot(y).coerceIn(-1f, 1f))) }
                "FuzzyEq" -> method { a -> val e = a.arg(3).optNum(1e-5f); LuaValue.valueOf((arg3(a, 1) - arg3(a, 2)).magnitude <= e) }
                else -> null
            }
        }) {
            set(LuaValue.ADD, fn2 { a, b -> wrap(a.ud<Vector3>("Vector3") + b.ud<Vector3>("Vector3")) })
            set(LuaValue.SUB, fn2 { a, b -> wrap(a.ud<Vector3>("Vector3") - b.ud<Vector3>("Vector3")) })
            set(LuaValue.MUL, fn2 { a, b ->
                when {
                    a.isnumber() -> wrap(b.ud<Vector3>("Vector3") * a.num())
                    b.isnumber() -> wrap(a.ud<Vector3>("Vector3") * b.num())
                    else -> wrap(a.ud<Vector3>("Vector3") * b.ud<Vector3>("Vector3"))
                }
            })
            set(LuaValue.DIV, fn2 { a, b ->
                when {
                    b.isnumber() -> wrap(a.ud<Vector3>("Vector3") / b.num())
                    a.isnumber() -> { val v = b.ud<Vector3>("Vector3"); wrap(Vector3(a.num() / v.x, a.num() / v.y, a.num() / v.z)) }
                    else -> wrap(a.ud<Vector3>("Vector3") / b.ud<Vector3>("Vector3"))
                }
            })
            set(LuaValue.UNM, fn1 { a -> wrap(-a.ud<Vector3>("Vector3")) })
        }

        meta(Vector2::class.java, "Vector2", { o, k ->
            val v = o as Vector2
            when (k) {
                "X", "x" -> n(v.x); "Y", "y" -> n(v.y)
                "Magnitude", "magnitude" -> n(v.magnitude)
                "Unit", "unit" -> wrap(v.unit)
                "Dot" -> method { a -> n(a.arg(1).ud<Vector2>("Vector2").dot(a.arg(2).ud("Vector2"))) }
                "Lerp" -> method { a -> wrap(a.arg(1).ud<Vector2>("Vector2").lerp(a.arg(2).ud("Vector2"), a.arg(3).num())) }
                else -> null
            }
        }) {
            set(LuaValue.ADD, fn2 { a, b -> wrap(a.ud<Vector2>("Vector2") + b.ud<Vector2>("Vector2")) })
            set(LuaValue.SUB, fn2 { a, b -> wrap(a.ud<Vector2>("Vector2") - b.ud<Vector2>("Vector2")) })
            set(LuaValue.MUL, fn2 { a, b ->
                when {
                    a.isnumber() -> wrap(b.ud<Vector2>("Vector2") * a.num())
                    b.isnumber() -> wrap(a.ud<Vector2>("Vector2") * b.num())
                    else -> wrap(a.ud<Vector2>("Vector2") * b.ud<Vector2>("Vector2"))
                }
            })
            set(LuaValue.DIV, fn2 { a, b -> wrap(a.ud<Vector2>("Vector2") / b.num()) })
            set(LuaValue.UNM, fn1 { a -> wrap(-a.ud<Vector2>("Vector2")) })
        }

        meta(CFrame::class.java, "CFrame", { o, k ->
            val c = o as CFrame
            when (k) {
                "Position", "p" -> wrap(c.position)
                "X", "x" -> n(c.x); "Y", "y" -> n(c.y); "Z", "z" -> n(c.z)
                "LookVector", "lookVector" -> wrap(c.lookVector)
                "RightVector", "rightVector" -> wrap(c.rightVector)
                "UpVector", "upVector" -> wrap(c.upVector)
                "XVector" -> wrap(Vector3(c.r00, c.r10, c.r20)); "YVector" -> wrap(Vector3(c.r01, c.r11, c.r21)); "ZVector" -> wrap(Vector3(c.r02, c.r12, c.r22))
                "Rotation" -> wrap(c.rotationOnly())
                "Inverse", "inverse" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame").inverse()) }
                "Lerp", "lerp" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame").lerp(a.arg(2).ud("CFrame"), a.arg(3).num())) }
                "ToWorldSpace", "toWorldSpace" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame") * a.arg(2).ud<CFrame>("CFrame")) }
                "ToObjectSpace", "toObjectSpace" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame").inverse() * a.arg(2).ud<CFrame>("CFrame")) }
                "PointToWorldSpace", "pointToWorldSpace" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame") * a.arg(2).ud<Vector3>("Vector3")) }
                "PointToObjectSpace", "pointToObjectSpace" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame").pointToObject(a.arg(2).ud("Vector3"))) }
                "VectorToWorldSpace", "vectorToWorldSpace" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame").vectorToWorld(a.arg(2).ud("Vector3"))) }
                "VectorToObjectSpace", "vectorToObjectSpace" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame").vectorToObject(a.arg(2).ud("Vector3"))) }
                "ToEulerAnglesXYZ", "toEulerAnglesXYZ" -> method { a -> val e = a.arg(1).ud<CFrame>("CFrame").toEulerXYZ(); LuaValue.varargsOf(arrayOf(n(e.x), n(e.y), n(e.z))) }
                "ToEulerAnglesYXZ", "ToOrientation", "toEulerAnglesYXZ" -> method { a -> val e = a.arg(1).ud<CFrame>("CFrame").toOrientation(); LuaValue.varargsOf(arrayOf(n(e.x), n(e.y), n(e.z))) }
                "GetComponents", "components" -> method { a -> LuaValue.varargsOf(a.arg(1).ud<CFrame>("CFrame").components().map { n(it) }.toTypedArray()) }
                "Orthonormalize" -> method { a -> wrap(a.arg(1).ud<CFrame>("CFrame").orthonormalize()) }
                "ToAxisAngle" -> method { a ->
                    val q = a.arg(1).ud<CFrame>("CFrame").toQuaternion()
                    val ang = 2f * kotlin.math.acos(q[3].coerceIn(-1f, 1f))
                    val s = kotlin.math.sqrt((1f - q[3] * q[3]).coerceAtLeast(0f))
                    val axis = if (s < 1e-5f) Vector3.X else Vector3(q[0] / s, q[1] / s, q[2] / s)
                    LuaValue.varargsOf(wrap(axis), n(ang))
                }
                else -> null
            }
        }) {
            set(LuaValue.MUL, fn2 { a, b ->
                val c = a.ud<CFrame>("CFrame")
                val o = b.touserdata()
                when (o) {
                    is CFrame -> wrap(c * o)
                    is Vector3 -> wrap(c * o)
                    else -> throw LuaError("invalid argument to CFrame multiplication")
                }
            })
            set(LuaValue.ADD, fn2 { a, b -> wrap(a.ud<CFrame>("CFrame") + b.ud<Vector3>("Vector3")) })
            set(LuaValue.SUB, fn2 { a, b -> wrap(a.ud<CFrame>("CFrame") - b.ud<Vector3>("Vector3")) })
        }

        meta(Color3::class.java, "Color3", { o, k ->
            val c = o as Color3
            when (k) {
                "R", "r" -> n(c.r); "G", "g" -> n(c.g); "B", "b" -> n(c.b)
                "Lerp", "lerp" -> method { a -> wrap(a.arg(1).ud<Color3>("Color3").lerp(a.arg(2).ud("Color3"), a.arg(3).num())) }
                "ToHSV" -> method { a -> val h = a.arg(1).ud<Color3>("Color3").toHSV(); LuaValue.varargsOf(arrayOf(n(h[0]), n(h[1]), n(h[2]))) }
                "ToHex" -> method { a -> LuaValue.valueOf(a.arg(1).ud<Color3>("Color3").toHex().lowercase()) }
                else -> null
            }
        })

        meta(BrickColorV::class.java, "BrickColor", { o, k ->
            val b = o as BrickColorV
            when (k) {
                "Name" -> LuaValue.valueOf(b.name)
                "Number" -> LuaValue.valueOf(b.number)
                "Color" -> wrap(b.color)
                "r" -> n(b.color.r); "g" -> n(b.color.g); "b" -> n(b.color.b)
                else -> null
            }
        })

        meta(UDim::class.java, "UDim", { o, k ->
            val u = o as UDim
            when (k) { "Scale" -> n(u.scale); "Offset" -> LuaValue.valueOf(u.offset); else -> null }
        }) {
            set(LuaValue.ADD, fn2 { a, b -> val x = a.ud<UDim>("UDim"); val y = b.ud<UDim>("UDim"); wrap(UDim(x.scale + y.scale, x.offset + y.offset)) })
            set(LuaValue.SUB, fn2 { a, b -> val x = a.ud<UDim>("UDim"); val y = b.ud<UDim>("UDim"); wrap(UDim(x.scale - y.scale, x.offset - y.offset)) })
        }

        meta(UDim2::class.java, "UDim2", { o, k ->
            val u = o as UDim2
            when (k) {
                "X", "Width" -> wrap(UDim(u.xs, u.xo))
                "Y", "Height" -> wrap(UDim(u.ys, u.yo))
                "Lerp" -> method { a -> wrap(a.arg(1).ud<UDim2>("UDim2").lerp(a.arg(2).ud("UDim2"), a.arg(3).num())) }
                else -> null
            }
        }) {
            set(LuaValue.ADD, fn2 { a, b -> val x = a.ud<UDim2>("UDim2"); val y = b.ud<UDim2>("UDim2"); wrap(UDim2(x.xs + y.xs, x.xo + y.xo, x.ys + y.ys, x.yo + y.yo)) })
            set(LuaValue.SUB, fn2 { a, b -> val x = a.ud<UDim2>("UDim2"); val y = b.ud<UDim2>("UDim2"); wrap(UDim2(x.xs - y.xs, x.xo - y.xo, x.ys - y.ys, x.yo - y.yo)) })
            set(LuaValue.UNM, fn1 { a -> val x = a.ud<UDim2>("UDim2"); wrap(UDim2(-x.xs, -x.xo, -x.ys, -x.yo)) })
        }

        meta(EnumItemV::class.java, "EnumItem", { o, k ->
            val e = o as EnumItemV
            when (k) {
                "Name" -> LuaValue.valueOf(e.name)
                "Value" -> LuaValue.valueOf(e.value)
                "EnumType" -> wrap(EnumTypeV(e.type))
                "IsA" -> method { a -> LuaValue.valueOf(a.arg(2).tojstring() == e.type) }
                else -> null
            }
        }) {
            set(LuaValue.TOSTRING, fn1 { val e = it.touserdata() as EnumItemV; LuaValue.valueOf("Enum.${e.type}.${e.name}") })
        }

        meta(EnumTypeV::class.java, "Enum", { o, k ->
            val t = o as EnumTypeV
            when (k) {
                "GetEnumItems" -> method { _ ->
                    val tbl = LuaTable()
                    Enums.types[t.name]?.forEach { (nm, v) -> tbl.insert(0, wrap(EnumItemV(t.name, nm, v))) }
                    tbl
                }
                "FromName" -> method { a -> enumItem(t.name, a.arg(2).tojstring()) ?: LuaValue.NIL }
                "FromValue" -> method { a -> Enums.itemName(t.name, a.arg(2).toint())?.let { wrap(EnumItemV(t.name, it, a.arg(2).toint())) } ?: LuaValue.NIL }
                else -> enumItem(t.name, k)
            }
        }) {
            set(LuaValue.TOSTRING, fn1 { LuaValue.valueOf((it.touserdata() as EnumTypeV).name) })
        }

        meta(TweenInfoV::class.java, "TweenInfo", { o, k ->
            val t = o as TweenInfoV
            when (k) {
                "Time" -> n(t.time); "DelayTime" -> n(t.delay); "RepeatCount" -> LuaValue.valueOf(t.repeatCount)
                "Reverses" -> LuaValue.valueOf(t.reverses)
                "EasingStyle" -> wrap(EnumItemV("EasingStyle", Enums.itemName("EasingStyle", t.style) ?: "Quad", t.style))
                "EasingDirection" -> wrap(EnumItemV("EasingDirection", Enums.itemName("EasingDirection", t.direction) ?: "Out", t.direction))
                else -> null
            }
        })

        meta(NumberRange::class.java, "NumberRange", { o, k ->
            val r = o as NumberRange
            when (k) { "Min" -> n(r.min); "Max" -> n(r.max); else -> null }
        })

        meta(Ray::class.java, "Ray", { o, k ->
            val r = o as Ray
            when (k) {
                "Origin" -> wrap(r.origin); "Direction" -> wrap(r.direction); "Unit" -> wrap(Ray(r.origin, r.direction.unit))
                "ClosestPoint" -> method { a ->
                    val p = a.arg(2).ud<Vector3>("Vector3"); val d = r.direction.unit
                    wrap(r.origin + d * ((p - r.origin).dot(d)).coerceAtLeast(0f))
                }
                else -> null
            }
        })

        meta(Region3V::class.java, "Region3", { o, k ->
            val r = o as Region3V
            when (k) {
                "CFrame" -> wrap(r.cframe); "Size" -> wrap(r.size)
                "ExpandToGrid" -> method { a ->
                    val res = a.arg(2).num().coerceAtLeast(1e-3f)
                    fun lo(x: Float) = floor(x / res) * res
                    fun hi(x: Float) = ceil(x / res) * res
                    wrap(Region3V(Vector3(lo(r.min.x), lo(r.min.y), lo(r.min.z)), Vector3(hi(r.max.x), hi(r.max.y), hi(r.max.z))))
                }
                else -> null
            }
        })
        meta(Region3int16V::class.java, "Region3int16", { o, k ->
            val r = o as Region3int16V
            when (k) { "Min" -> wrap(r.min); "Max" -> wrap(r.max); else -> null }
        })

        meta(RandomV::class.java, "Random", { o, k ->
            val r = (o as RandomV).rng
            when (k) {
                "NextNumber" -> method { a ->
                    val mn = a.arg(2).optNum(0f).toDouble(); val mx = a.arg(3).optNum(1f).toDouble()
                    LuaValue.valueOf(mn + r.nextDouble() * (mx - mn))
                }
                "NextInteger" -> method { a ->
                    val mn = a.arg(2).checkint(); val mx = a.arg(3).checkint()
                    LuaValue.valueOf(mn + r.nextInt(maxOf(1, mx - mn + 1)))
                }
                "NextUnitVector" -> method { _ -> wrap(Vector3(r.nextGaussian().toFloat(), r.nextGaussian().toFloat(), r.nextGaussian().toFloat()).unit) }
                else -> null
            }
        })

        meta(RaycastResultV::class.java, "RaycastResult", { o, k ->
            val h = (o as RaycastResultV).hit
            when (k) {
                "Position" -> wrap(h.position); "Normal" -> wrap(h.normal); "Distance" -> n(h.distance)
                "Material" -> wrap(EnumItemV("Material", Enums.itemName("Material", h.material) ?: "Plastic", h.material))
                else -> null // Instance is resolved by the runtime wrapper
            }
        })

        meta(NumberSequence::class.java, "NumberSequence", { o, k ->
            val s = o as NumberSequence
            when (k) { "Keypoints" -> LuaTable().also { t -> s.keys.forEach { kp -> t.insert(0, wrap(kp)) } }; else -> null }
        })
        meta(NumberKey::class.java, "NumberSequenceKeypoint", { o, k ->
            val kp = o as NumberKey
            when (k) { "Time" -> n(kp.time); "Value" -> n(kp.value); "Envelope" -> n(kp.envelope); else -> null }
        })
        meta(ColorSequence::class.java, "ColorSequence", { o, k ->
            val s = o as ColorSequence
            when (k) { "Keypoints" -> LuaTable().also { t -> s.keys.forEach { kp -> t.insert(0, wrap(kp)) } }; else -> null }
        })
        meta(ColorKey::class.java, "ColorSequenceKeypoint", { o, k ->
            val kp = o as ColorKey
            when (k) { "Time" -> n(kp.time); "Value" -> wrap(kp.color); else -> null }
        })
        meta(FontValue::class.java, "Font", { o, k ->
            val f = o as FontValue
            when (k) {
                "Family" -> LuaValue.valueOf(f.family)
                "Weight" -> wrap(EnumItemV("FontWeight", f.weight.toString(), f.weight))
                "Style" -> wrap(EnumItemV("FontStyle", if (f.style == 1) "Italic" else "Normal", f.style))
                "Bold" -> LuaValue.valueOf(f.weight >= 700)
                else -> null
            }
        })
        meta(Rect::class.java, "Rect", { o, k ->
            val r = o as Rect
            when (k) {
                "Min" -> wrap(Vector2(r.minX, r.minY)); "Max" -> wrap(Vector2(r.maxX, r.maxY))
                "Width" -> n(r.maxX - r.minX); "Height" -> n(r.maxY - r.minY)
                else -> null
            }
        })
        meta(PhysicalProps::class.java, "PhysicalProperties", { o, k ->
            val p = o as PhysicalProps
            when (k) {
                "Density" -> n(p.density); "Friction" -> n(p.friction); "Elasticity" -> n(p.elasticity)
                "FrictionWeight" -> n(p.frictionWeight); "ElasticityWeight" -> n(p.elasticityWeight)
                else -> null
            }
        })
    }

    fun enumItem(type: String, name: String): LuaValue? {
        val t = Enums.types[type]
        val v = t?.get(name)
        if (v != null) return wrap(EnumItemV(type, name, v))
        // Unknown enums (and items our partial enum list lacks) still resolve so scripts keep running.
        if (name.isEmpty() || !name[0].isLetter()) return null
        return wrap(EnumItemV(type, name, (name.hashCode() and 0xFFFF) or 0x10000))
    }

    fun enumLib(): LuaValue {
        val mt = LuaTable()
        mt.set(LuaValue.INDEX, fn2 { _, k -> wrap(EnumTypeV(k.tojstring())) })
        val t = LuaTable()
        t.set("GetEnums", fnv { _ -> LuaTable().also { tbl -> Enums.types.keys.forEach { tbl.insert(0, wrap(EnumTypeV(it))) } } })
        t.setmetatable(mt)
        return t
    }

    // ---------------------------------------------------------------- constructors

    fun install(g: LuaTable) {
        fun lib(name: String, build: LuaTable.() -> Unit) { g.set(name, LuaTable().apply(build)) }

        lib("Vector3") {
            set("new", fnv { a -> wrap(Vector3(a.arg(1).optNum(0f), a.arg(2).optNum(0f), a.arg(3).optNum(0f))) })
            set("zero", wrap(Vector3.ZERO)); set("one", wrap(Vector3.ONE))
            set("xAxis", wrap(Vector3.X)); set("yAxis", wrap(Vector3.Y)); set("zAxis", wrap(Vector3.Z))
            set("FromNormalId", fn1 { a ->
                val i = a.udOrNull<EnumItemV>()?.value ?: a.toint()
                wrap(listOf(Vector3.X, Vector3.Y, Vector3.Z, -Vector3.X, -Vector3.Y, -Vector3.Z)[i.coerceIn(0, 5)])
            })
            set("FromAxis", fn1 { a -> wrap(Vector3.axis(a.udOrNull<EnumItemV>()?.value ?: a.toint())) })
        }
        lib("Vector2") {
            set("new", fnv { a -> wrap(Vector2(a.arg(1).optNum(0f), a.arg(2).optNum(0f))) })
            set("zero", wrap(Vector2(0f, 0f))); set("one", wrap(Vector2(1f, 1f)))
            set("xAxis", wrap(Vector2(1f, 0f))); set("yAxis", wrap(Vector2(0f, 1f)))
        }
        lib("Vector3int16") { set("new", fnv { a -> wrap(Vector3int16(a.arg(1).optint(0), a.arg(2).optint(0), a.arg(3).optint(0))) }) }
        // Roblox's Content datatype (ImageContent, TextureContent...): carried here as its URI
        // string, which every Content-typed property here accepts.
        lib("Content") {
            set("none", LuaValue.valueOf(""))
            set("fromUri", fn1 { a -> LuaValue.valueOf(a.optjstring("")) })
            set("fromAssetId", fn1 { a -> LuaValue.valueOf("rbxassetid://" + a.optlong(0L)) })
            set("fromObject", fn1 { a -> a })
        }
        lib("CFrame") {
            set("new", fnv { a ->
                val n = a.narg()
                when {
                    n == 0 -> wrap(CFrame.IDENTITY)
                    n == 1 -> wrap(CFrame.at(a.arg(1).ud("Vector3")))
                    n == 2 && a.arg(1).isuserdata() -> wrap(CFrame.lookAt(a.arg(1).ud("Vector3"), a.arg(2).ud("Vector3")))
                    n == 3 -> wrap(CFrame(a.arg(1).num(), a.arg(2).num(), a.arg(3).num()))
                    n == 7 -> wrap(CFrame.fromQuaternion(Vector3(a.arg(1).num(), a.arg(2).num(), a.arg(3).num()), a.arg(4).num(), a.arg(5).num(), a.arg(6).num(), a.arg(7).num()))
                    n >= 12 -> wrap(CFrame(a.arg(1).num(), a.arg(2).num(), a.arg(3).num(), a.arg(4).num(), a.arg(5).num(), a.arg(6).num(),
                        a.arg(7).num(), a.arg(8).num(), a.arg(9).num(), a.arg(10).num(), a.arg(11).num(), a.arg(12).num()))
                    else -> throw LuaError("Invalid number of arguments: $n")
                }
            })
            val angles = fnv { a -> wrap(CFrame.angles(a.arg(1).optNum(0f), a.arg(2).optNum(0f), a.arg(3).optNum(0f))) }
            set("Angles", angles); set("fromEulerAnglesXYZ", angles)
            val orient = fnv { a -> wrap(CFrame.fromOrientation(a.arg(1).optNum(0f), a.arg(2).optNum(0f), a.arg(3).optNum(0f))) }
            set("fromOrientation", orient); set("fromEulerAnglesYXZ", orient)
            set("fromEulerAngles", fnv { a -> wrap(CFrame.angles(a.arg(1).optNum(0f), a.arg(2).optNum(0f), a.arg(3).optNum(0f))) })
            set("lookAt", fnv { a -> wrap(CFrame.lookAt(a.arg(1).ud("Vector3"), a.arg(2).ud("Vector3"), a.arg(3).udOrNull<Vector3>() ?: Vector3.Y)) })
            set("lookAlong", fnv { a -> val p = a.arg(1).ud<Vector3>("Vector3"); wrap(CFrame.lookAt(p, p + a.arg(2).ud<Vector3>("Vector3"), a.arg(3).udOrNull<Vector3>() ?: Vector3.Y)) })
            set("fromAxisAngle", fnv { a -> wrap(CFrame.fromAxisAngle(a.arg(1).ud("Vector3"), a.arg(2).num())) })
            set("fromMatrix", fnv { a ->
                val p = a.arg(1).ud<Vector3>("Vector3"); val x = a.arg(2).ud<Vector3>("Vector3"); val y = a.arg(3).ud<Vector3>("Vector3")
                val z = a.arg(4).udOrNull<Vector3>() ?: x.cross(y).unit
                wrap(CFrame.fromColumns(p, x, y, z))
            })
            set("identity", wrap(CFrame.IDENTITY))
        }
        lib("Color3") {
            set("new", fnv { a -> wrap(Color3(a.arg(1).optNum(0f), a.arg(2).optNum(0f), a.arg(3).optNum(0f))) })
            set("fromRGB", fnv { a -> wrap(Color3.fromRGB(a.arg(1).optint(0), a.arg(2).optint(0), a.arg(3).optint(0))) })
            set("fromHSV", fnv { a -> wrap(Color3.fromHSV(a.arg(1).num(), a.arg(2).num(), a.arg(3).num())) })
            set("fromHex", fn1 { a -> wrap(Color3.fromHex(a.tojstring()) ?: throw LuaError("Unable to convert characters to hex value")) })
            set("toHSV", fn1 { a -> val h = a.ud<Color3>("Color3").toHSV(); LuaValue.varargsOf(arrayOf(n(h[0]), n(h[1]), n(h[2]))).arg1() })
        }
        lib("BrickColor") {
            set("new", fnv { a ->
                val x = a.arg(1)
                when {
                    x.isnumber() && a.narg() >= 3 -> wrap(BrickColorV(BrickColors.closest(Color3(a.arg(1).num(), a.arg(2).num(), a.arg(3).num()))))
                    x.isnumber() -> wrap(BrickColorV(x.toint()))
                    x.isstring() -> wrap(BrickColorV(BrickColors.byName(x.tojstring()) ?: 194))
                    x.isuserdata() -> wrap(BrickColorV(BrickColors.closest(x.ud("Color3"))))
                    else -> wrap(BrickColorV(194))
                }
            })
            set("random", fnv { _ -> wrap(BrickColorV(BrickColors.all.random())) })
            set("Random", fnv { _ -> wrap(BrickColorV(BrickColors.all.random())) })
            for ((k, v) in mapOf("White" to 1, "Black" to 26, "Red" to 21, "Blue" to 23, "Green" to 28, "Yellow" to 24, "Gray" to 194, "DarkGray" to 199))
                set(k, fnv { _ -> wrap(BrickColorV(v)) })
        }
        lib("UDim") { set("new", fnv { a -> wrap(UDim(a.arg(1).optNum(0f), a.arg(2).optint(0))) }) }
        lib("UDim2") {
            set("new", fnv { a ->
                if (a.arg(1).isuserdata()) {
                    val x = a.arg(1).ud<UDim>("UDim"); val y = a.arg(2).ud<UDim>("UDim")
                    wrap(UDim2(x.scale, x.offset, y.scale, y.offset))
                } else wrap(UDim2(a.arg(1).optNum(0f), a.arg(2).optNum(0f).toInt(), a.arg(3).optNum(0f), a.arg(4).optNum(0f).toInt()))
            })
            set("fromScale", fnv { a -> wrap(UDim2(a.arg(1).optNum(0f), 0, a.arg(2).optNum(0f), 0)) })
            set("fromOffset", fnv { a -> wrap(UDim2(0f, a.arg(1).optNum(0f).toInt(), 0f, a.arg(2).optNum(0f).toInt())) })
        }
        lib("TweenInfo") {
            set("new", fnv { a ->
                wrap(TweenInfoV(
                    a.arg(1).optNum(1f), a.arg(2).udOrNull<EnumItemV>()?.value ?: 3, a.arg(3).udOrNull<EnumItemV>()?.value ?: 1,
                    a.arg(4).optint(0), a.arg(5).optboolean(false), a.arg(6).optNum(0f),
                ))
            })
        }
        lib("Ray") { set("new", fnv { a -> wrap(Ray(a.arg(1).ud("Vector3"), a.arg(2).ud("Vector3"))) }) }
        lib("Region3") {
            set("new", fnv { a ->
                val p = a.arg(1).udOrNull<Vector3>() ?: Vector3.ZERO; val q = a.arg(2).udOrNull<Vector3>() ?: Vector3.ZERO
                wrap(Region3V(p.min(q), p.max(q)))
            })
        }
        lib("Region3int16") {
            set("new", fnv { a ->
                wrap(Region3int16V(a.arg(1).udOrNull<Vector3int16>() ?: Vector3int16(0, 0, 0), a.arg(2).udOrNull<Vector3int16>() ?: Vector3int16(0, 0, 0)))
            })
        }
        lib("NumberRange") { set("new", fnv { a -> val mn = a.arg(1).num(); wrap(NumberRange(mn, a.arg(2).optNum(mn))) }) }
        lib("NumberSequenceKeypoint") { set("new", fnv { a -> wrap(NumberKey(a.arg(1).num(), a.arg(2).num(), a.arg(3).optNum(0f))) }) }
        lib("ColorSequenceKeypoint") { set("new", fnv { a -> wrap(ColorKey(a.arg(1).num(), a.arg(2).ud("Color3"), 0f)) }) }
        lib("NumberSequence") {
            set("new", fnv { a ->
                val x = a.arg(1)
                when {
                    x.istable() -> wrap(NumberSequence((1..x.length()).map { x.get(it).ud<NumberKey>("NumberSequenceKeypoint") }))
                    a.narg() >= 2 -> wrap(NumberSequence(listOf(NumberKey(0f, x.num(), 0f), NumberKey(1f, a.arg(2).num(), 0f))))
                    else -> wrap(NumberSequence(listOf(NumberKey(0f, x.num(), 0f), NumberKey(1f, x.num(), 0f))))
                }
            })
        }
        lib("ColorSequence") {
            set("new", fnv { a ->
                val x = a.arg(1)
                when {
                    x.istable() -> wrap(ColorSequence((1..x.length()).map { x.get(it).ud<ColorKey>("ColorSequenceKeypoint") }))
                    a.narg() >= 2 -> wrap(ColorSequence(listOf(ColorKey(0f, x.ud("Color3"), 0f), ColorKey(1f, a.arg(2).ud("Color3"), 0f))))
                    else -> { val c = x.ud<Color3>("Color3"); wrap(ColorSequence(listOf(ColorKey(0f, c, 0f), ColorKey(1f, c, 0f)))) }
                }
            })
        }
        lib("Random") { set("new", fnv { a -> wrap(RandomV(if (a.arg(1).isnil()) System.nanoTime() else a.arg(1).tolong())) }) }
        lib("Rect") { set("new", fnv { a -> wrap(Rect(a.arg(1).optNum(0f), a.arg(2).optNum(0f), a.arg(3).optNum(0f), a.arg(4).optNum(0f))) }) }
        lib("PhysicalProperties") {
            set("new", fnv { a ->
                if (a.arg(1).isuserdata()) wrap(PhysicalProps(0))
                else wrap(PhysicalProps(1, a.arg(1).optNum(0.7f), a.arg(2).optNum(0.3f), a.arg(3).optNum(0.5f), a.arg(4).optNum(1f), a.arg(5).optNum(1f)))
            })
        }
        lib("Font") {
            set("new", fnv { a -> wrap(FontValue(a.arg(1).tojstring(), a.arg(2).udOrNull<EnumItemV>()?.value ?: 400, a.arg(3).udOrNull<EnumItemV>()?.value ?: 0, "")) })
            set("fromEnum", fnv { a -> wrap(FontValue("rbxasset://fonts/families/${a.arg(1).udOrNull<EnumItemV>()?.name ?: "SourceSansPro"}.json", 400, 0, "")) })
            set("fromName", fnv { a -> wrap(FontValue("rbxasset://fonts/families/${a.arg(1).tojstring()}.json", 400, 0, "")) })
        }
        g.set("Enum", enumLib())
    }

    // ---------------------------------------------------------------- easing

    fun ease(style: Int, dir: Int, t0: Float): Float {
        val t = t0.coerceIn(0f, 1f)
        fun easeIn(x: Float): Float = when (style) {
            0 -> x
            1 -> 1f - kotlin.math.cos(x * PI.toFloat() / 2f)
            2 -> { val s = 1.70158f; x * x * ((s + 1) * x - s) }
            3 -> x * x
            4 -> x * x * x * x
            5 -> x * x * x * x * x
            6 -> 1f - bounceOut(1f - x)
            7 -> if (x == 0f || x == 1f) x else -Math.pow(2.0, 10.0 * x - 10).toFloat() * kotlin.math.sin((x * 10f - 10.75f) * (2f * PI.toFloat() / 3f))
            8 -> if (x == 0f) 0f else Math.pow(2.0, 10.0 * x - 10).toFloat()
            9 -> 1f - kotlin.math.sqrt(1f - x * x)
            10 -> x * x * x
            else -> x * x
        }
        return when (dir) {
            0 -> easeIn(t)
            1 -> 1f - easeIn(1f - t)
            else -> if (t < 0.5f) easeIn(t * 2f) / 2f else 1f - easeIn((1f - t) * 2f) / 2f
        }
    }

    private fun bounceOut(x: Float): Float {
        val n1 = 7.5625f; val d1 = 2.75f
        return when {
            x < 1 / d1 -> n1 * x * x
            x < 2 / d1 -> { val y = x - 1.5f / d1; n1 * y * y + 0.75f }
            x < 2.5 / d1 -> { val y = x - 2.25f / d1; n1 * y * y + 0.9375f }
            else -> { val y = x - 2.625f / d1; n1 * y * y + 0.984375f }
        }
    }

    fun lerpValue(a: Any?, b: Any?, t: Float): Any? = when {
        a is Float && b is Number -> a + (b.toFloat() - a) * t
        a is Double && b is Number -> a + (b.toDouble() - a) * t
        a is Int && b is Number -> (a + (b.toDouble() - a) * t).toInt()
        a is Long && b is Number -> (a + (b.toDouble() - a) * t).toLong()
        a is Vector3 && b is Vector3 -> a.lerp(b, t)
        a is Vector2 && b is Vector2 -> a.lerp(b, t)
        a is CFrame && b is CFrame -> a.lerp(b, t)
        a is Color3 && b is Color3 -> a.lerp(b, t)
        a is UDim2 && b is UDim2 -> a.lerp(b, t)
        a is UDim && b is UDim -> UDim(a.scale + (b.scale - a.scale) * t, (a.offset + (b.offset - a.offset) * t).toInt())
        a is Number && b is Number -> a.toDouble() + (b.toDouble() - a.toDouble()) * t
        else -> if (t >= 1f) b else a
    }

    fun absf(x: Float) = abs(x)
}
