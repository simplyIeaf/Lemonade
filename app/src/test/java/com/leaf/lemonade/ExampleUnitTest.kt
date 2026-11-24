package com.leaf.lemonade

import com.leaf.lemonade.format.Lz4
import com.leaf.lemonade.format.RbxBinaryReader
import com.leaf.lemonade.format.RbxBinaryWriter
import com.leaf.lemonade.lua.LuauTranspiler
import com.leaf.lemonade.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform

class ExampleUnitTest {

    @Test
    fun lz4_decodesOverlappingMatch() {
        val src = byteArrayOf(0x35, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x03, 0x00)
        assertEquals("abcabcabcabc", String(Lz4.decompress(src, 0, src.size, 12)))
    }

    @Test
    fun binary_roundTrip() {
        val ws = Instance("Workspace").apply { isService = true; set("Name", "Workspace", false) }
        val part = InstanceFactory.create("Part")
        part.name = "Brick"
        part.set("CFrame", CFrame.angles(0.3f, 1.2f, -0.4f).withPosition(Vector3(1.5f, -2f, 300f)), false)
        part.set("Transparency", 0.25f, false)
        part.set("Color3uint8", Color3.fromRGB(10, 200, 30), false)
        part.attachRaw(ws)
        val script = InstanceFactory.create("Script")
        script.set("Source", "print('héllo')\n", false)
        script.attachRaw(part)
        val model = InstanceFactory.create("Model")
        model.set("PrimaryPart", part, false)
        model.set("WorldPivotData", OptionalCFrame(CFrame(1f, 2f, 3f)), false)
        model.set("Big", 1234567890123L, false)
        model.set("Seq", NumberSequence(listOf(NumberKey(0f, 1f, 0f), NumberKey(1f, 5f, 0.5f))), false)
        model.set("Gui", UDim2(0.5f, -10, 1f, 20), false)
        model.set("Id", UniqueId(ByteArray(16) { it.toByte() }), false)
        model.attachRaw(ws)

        val bytes = RbxBinaryWriter.write(listOf(ws), mapOf("ExplicitAutoJoints" to "true"))
        val doc = RbxBinaryReader(bytes).read()
        assertEquals(emptyList<String>(), doc.warnings)
        assertEquals("true", doc.meta["ExplicitAutoJoints"])
        val ws2 = doc.roots.single()
        assertTrue(ws2.isService)
        val p2 = ws2.findFirstChild("Brick")!!
        assertEquals(part["CFrame"], p2["CFrame"])
        assertEquals(0.25f, p2["Transparency"])
        assertEquals(Color3.fromRGB(10, 200, 30).toString(), p2["Color3uint8"].toString())
        assertEquals(PropType.COLOR3U8, p2.propTypes["Color3uint8"])
        assertEquals("print('héllo')\n", p2.children.single()["Source"])
        val m2 = ws2.children.first { it.className == "Model" }
        assertTrue(m2["PrimaryPart"] === p2)
        assertEquals(OptionalCFrame(CFrame(1f, 2f, 3f)), m2["WorldPivotData"])
        assertEquals(1234567890123L, m2["Big"])
        assertEquals(model["Seq"], m2["Seq"])
        assertEquals(UDim2(0.5f, -10, 1f, 20), m2["Gui"])
        assertEquals(model["Id"], m2["Id"])
    }

    private fun run(src: String): LuaValue {
        val g = JsePlatform.standardGlobals()
        g.set("__iter", object : VarArgFunction() {
            override fun invoke(a: Varargs): Varargs =
                if (a.arg1().isfunction()) a else LuaValue.varargsOf(arrayOf(g.get("next"), a.arg1(), LuaValue.NIL))
        })
        return g.load(LuauTranspiler.transpile(src), "test").call()
    }

    @Test
    fun luau_compoundAndInterpolation() {
        val r = run("""
            local x: number = 5
            x += 3
            local t = {a = 1}
            t.a *= 4
            local s = `x={x} a={t.a}`
            return s
        """.trimIndent())
        assertEquals("x=8 a=4", r.tojstring())
    }

    @Test
    fun luau_continueAndTypes() {
        val r = run("""
            type Point = { x: number, y: number }
            export type Callback = (number) -> ()
            local function sum(list: {number}, skip: number?): number
                local total = 0
                for _, v in ipairs(list) do
                    if v == skip then continue end
                    total += v
                end
                return total
            end
            return sum({1, 2, 3, 4} :: any, 3)
        """.trimIndent())
        assertEquals(7, r.toint())
    }

    @Test
    fun luau_ifExpressionAndFloorDiv() {
        val r = run("""
            local a = 7
            local kind = if a > 5 then "big" elseif a > 2 then "mid" else "small"
            local q = a // 2
            return kind .. q
        """.trimIndent())
        assertEquals("big3", r.tojstring())
    }

    @Test
    fun luau_genericsAndMethods() {
        val r = run("""
            local Class = {}
            Class.__index = Class
            function Class.new<T>(value: T)
                return setmetatable({ value = value }, Class)
            end
            function Class:get(): number
                return self.value
            end
            local n = 0
            while n < 10 do
                n += 1
                if n % 2 == 0 then continue end
            end
            return Class.new(41):get() + 1 + n
        """.trimIndent())
        assertEquals(52, r.toint())
    }

    @Test
    fun luau_generalizedIterationAndLongStrings() {
        val g = JsePlatform.standardGlobals()
        g.set("__iter", object : VarArgFunction() {
            override fun invoke(a: Varargs): Varargs =
                if (a.arg1().isfunction()) a else LuaValue.varargsOf(arrayOf(g.get("next"), a.arg1(), LuaValue.NIL))
        })
        val src = """
            local s = [[ has [[nested text ]]
            --[[ comment with [[ inside ]]
            local sum = 0
            for _, v in {1, 2, 3} do sum += v end
            for k, v in pairs({4}) do sum += v end
            for w in string.gmatch("a b", "%a") do sum += 1 end
            return sum .. #s
        """.trimIndent()
        assertEquals("1219", g.load(LuauTranspiler.transpile(src), "t").call().tojstring())
    }

    @Test
    fun luaj_yieldFromJavaFunction() {
        val g = JsePlatform.standardGlobals()
        g.set("pause", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = g.yield(LuaValue.NONE)
        })
        val fn = g.load("local log = {} ; for i = 1, 3 do log[#log+1] = pause() end ; return table.concat(log, ',')")
        val co = LuaThread(g, fn)
        co.resume(LuaValue.NONE)
        co.resume(LuaValue.valueOf("a"))
        co.resume(LuaValue.valueOf("b"))
        val last = co.resume(LuaValue.valueOf("c"))
        assertTrue(last.arg1().toboolean())
        assertEquals("a,b,c", last.arg(2).tojstring())
    }
}
