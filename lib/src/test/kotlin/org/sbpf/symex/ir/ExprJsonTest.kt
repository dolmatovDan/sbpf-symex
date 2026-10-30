package org.sbpf.symex.ir

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class ExprJsonTest {
    // Строгий режим: новое поле от лифтера должно ронять тест, а не теряться молча.
    private val strict = Json(IrJson) { ignoreUnknownKeys = false }

    private fun expr(text: String, json: Json = strict) = json.decodeFromString<Expr>(text)

    private val r1 = """{"kind": "reg", "reg": 1}"""
    private val r2 = """{"kind": "reg", "reg": 2}"""

    @Test
    fun leaves() {
        assertEquals(3, assertIs<Expr.Reg>(expr("""{"kind": "reg", "reg": 3}""")).index)

        val max = assertIs<Expr.Const>(expr("""{"kind": "const", "value": "0xffffffffffffffff", "w": 64}"""))
        assertEquals(ULong.MAX_VALUE, max.value)
        assertSame(Width.W64, max.width)
        assertEquals("0xffffffffffffffff", max.toString())

        val small = assertIs<Expr.Const>(expr("""{"kind": "const", "value": "0x5", "w": 32}"""))
        assertEquals(5uL, small.value)
        assertSame(Width.W32, small.width)
        assertEquals("5:32", small.toString())
    }

    @Test
    fun nested() {
        val e = expr(
            """{"kind": "ext", "op": "sext", "w": 64, "a":
                {"kind": "bin", "op": "add",
                    "a": {"kind": "ext", "op": "trunc", "w": 32, "a": $r1},
                    "b": {"kind": "const", "value": "0x1", "w": 32}}}""",
        )
        assertEquals("sext64(add(trunc32(r1), 1:32))", e.toString())
    }

    @Test
    fun badExpr() {
        for (bad in listOf(
            """{}""",
            """{"reg": 1}""",
            """{"kind": "vec", "reg": 1}""",
            """{"kind": "reg"}""",
            """{"kind": "const", "value": "5", "w": 64}""",
            """{"kind": "const", "value": "0x5", "w": 7}""",
            """{"kind": "const", "value": "0x5"}""",
            """{"kind": "const", "value": "0x+5", "w": 64}""",
            """{"kind": "const", "value": "0x", "w": 64}""",
            """{"kind": "const", "value": "0x٣", "w": 64}""",
            """{"kind": "const", "value": "0x10000000000000000", "w": 64}""",
            """{"kind": "bin", "op": "add", "a": $r1}""",
            """{"kind": "ext", "op": "zext", "a": $r1}""",
            """{"kind": "bin", "op": "sdiv", "a": $r1, "b": $r2}""",
            // Операция чужого вида.
            """{"kind": "bin", "op": "eq", "a": $r1, "b": $r2}""",
            """{"kind": "un", "op": "zext", "a": $r1}""",
        )) {
            // Структура выражения проверяется и в основном режиме, не только в строгом.
            assertFailsWith<SerializationException>(bad) { expr(bad, IrJson) }
        }
    }

    @Test
    fun unknownKey() {
        val text = """{"kind": "reg", "reg": 1, "comment": "x"}"""
        assertFailsWith<SerializationException> { expr(text) }
        assertEquals(1, assertIs<Expr.Reg>(expr(text, IrJson)).index)
    }
}
