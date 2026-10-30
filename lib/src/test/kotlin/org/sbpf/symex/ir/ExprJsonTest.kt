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

    // Имена из таблицы docs/ir.md, а не irName: иначе опечатка в enum разбиралась бы сама в себя.
    private val opNames: Map<String, Enum<*>> = mapOf(
        "add" to BinOp.Add,
        "sub" to BinOp.Sub,
        "mul" to BinOp.Mul,
        "and" to BinOp.And,
        "or" to BinOp.Or,
        "xor" to BinOp.Xor,
        "udiv" to BinOp.Udiv,
        "urem" to BinOp.Urem,
        "shl" to BinOp.Shl,
        "lshr" to BinOp.Lshr,
        "ashr" to BinOp.Ashr,
        "neg" to UnOp.Neg,
        "bswap" to UnOp.Bswap,
        "zext" to ExtOp.Zext,
        "sext" to ExtOp.Sext,
        "trunc" to ExtOp.Trunc,
        "eq" to CmpOp.Eq,
        "ne" to CmpOp.Ne,
        "ult" to CmpOp.Ult,
        "ule" to CmpOp.Ule,
        "ugt" to CmpOp.Ugt,
        "uge" to CmpOp.Uge,
        "slt" to CmpOp.Slt,
        "sle" to CmpOp.Sle,
        "sgt" to CmpOp.Sgt,
        "sge" to CmpOp.Sge,
    )

    private val r1 = """{"kind": "reg", "reg": 1}"""
    private val r2 = """{"kind": "reg", "reg": 2}"""

    // Узел своего вида с аргументами r1 и r2.
    private fun opJson(name: String, op: Enum<*>) = when (op) {
        is BinOp -> """{"kind": "bin", "op": "$name", "a": $r1, "b": $r2}"""
        is CmpOp -> """{"kind": "cmp", "op": "$name", "a": $r1, "b": $r2}"""
        is UnOp -> """{"kind": "un", "op": "$name", "a": $r1}"""
        is ExtOp -> """{"kind": "ext", "op": "$name", "w": 64, "a": $r1}"""
        else -> error("unknown operation kind $op")
    }

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
    fun opNamesCoverAllOps() {
        val all = BinOp.entries + UnOp.entries + ExtOp.entries + CmpOp.entries
        assertEquals(all.toSet(), opNames.values.toSet())
        assertEquals(all.size, opNames.size)
    }

    @Test
    fun everyOp() {
        for ((name, op) in opNames) {
            val e = expr(opJson(name, op))
            when (op) {
                is BinOp -> assertSame(op, assertIs<Expr.Bin>(e).op)
                is CmpOp -> assertSame(op, assertIs<Expr.Cmp>(e).op)
                is UnOp -> assertSame(op, assertIs<Expr.Un>(e).op)
                is ExtOp -> assertSame(op, assertIs<Expr.Ext>(e).op)
                else -> error("unknown operation kind $op")
            }
            // irName печатается в toString и должен совпадать с именем в IR.
            assertEquals(name, e.toString().substringBefore('(').trimEnd { it.isDigit() })
        }
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
