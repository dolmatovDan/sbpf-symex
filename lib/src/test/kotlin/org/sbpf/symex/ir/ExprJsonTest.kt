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

    private fun opJson(name: String, arity: Int, w: Boolean): String {
        val args = List(arity) { """{"reg": ${it + 1}}""" }.joinToString()
        return """{"op": "$name", ${if (w) """"w": 64, """ else ""}"args": [$args]}"""
    }

    private fun arity(op: Enum<*>) = when (op) {
        is BinOp, is CmpOp -> 2
        else -> 1
    }

    @Test
    fun leaves() {
        assertEquals(3, assertIs<Expr.Reg>(expr("""{"reg": 3}""")).index)

        val max = assertIs<Expr.Const>(expr("""{"const": "0xffffffffffffffff", "w": 64}"""))
        assertEquals(ULong.MAX_VALUE, max.value)
        assertSame(Width.W64, max.width)
        assertEquals("0xffffffffffffffff", max.toString())

        val small = assertIs<Expr.Const>(expr("""{"const": "0x5", "w": 32}"""))
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
            val e = expr(opJson(name, arity(op), w = op is ExtOp))
            when (op) {
                is BinOp -> assertSame(op, assertIs<Expr.Bin>(e).op)
                is CmpOp -> assertSame(op, assertIs<Expr.Cmp>(e).op)
                is UnOp -> assertSame(op, assertIs<Expr.Un>(e).op)
                is ExtOp -> assertSame(op, assertIs<Expr.Ext>(e).op)
                else -> error("неизвестный вид операции $op")
            }
        }
    }

    @Test
    fun wrongArity() {
        for ((name, op) in opNames) {
            for (n in listOf(arity(op) - 1, arity(op) + 1)) {
                assertFailsWith<SerializationException>("$name/$n") { expr(opJson(name, n, w = op is ExtOp)) }
            }
        }
    }

    @Test
    fun nested() {
        val e = expr(
            """{"op": "sext", "w": 64, "args": [
                {"op": "add", "args": [
                    {"op": "trunc", "w": 32, "args": [{"reg": 1}]},
                    {"const": "0x1", "w": 32}
                ]}
            ]}""",
        )
        assertEquals("sext64(add(trunc32(r1), 1:32))", e.toString())
    }

    @Test
    fun badExpr() {
        for (bad in listOf(
            """{}""",
            """{"const": "5", "w": 64}""",
            """{"const": "0x5", "w": 7}""",
            """{"const": "0x5"}""",
            """{"op": "add", "args": [{"reg": 1}]}""",
            """{"op": "zext", "args": [{"reg": 1}]}""",
            """{"op": "sdiv", "args": [{"reg": 1}, {"reg": 2}]}""",
            """{"reg": 1, "const": "0x5", "w": 64}""",
            """{"reg": 1, "op": "add"}""",
            """{"op": "add", "w": 8, "args": [{"reg": 1}, {"reg": 2}]}""",
            """{"const": "0x+5", "w": 64}""",
            """{"const": "0x", "w": 64}""",
            """{"const": "0x100", "w": 8}""",
        )) {
            // Структура выражения проверяется и в основном режиме, не только в строгом.
            assertFailsWith<SerializationException>(bad) { expr(bad, IrJson) }
        }
    }

    // Как Option в RawExpr из ir/json.rs: null равнозначен отсутствию поля.
    @Test
    fun nullMeansAbsent() {
        assertEquals("5", expr("""{"reg": null, "const": "0x5", "w": 64}""").toString())
        assertEquals("add(r1, r2)", expr("""{"op": "add", "w": null, "args": [{"reg": 1}, {"reg": 2}]}""").toString())
    }

    @Test
    fun unknownKey() {
        val text = """{"reg": 1, "comment": "x"}"""
        assertFailsWith<SerializationException> { expr(text) }
        assertEquals(1, assertIs<Expr.Reg>(expr(text, IrJson)).index)
    }
}
