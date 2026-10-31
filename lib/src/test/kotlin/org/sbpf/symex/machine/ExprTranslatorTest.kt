package org.sbpf.symex.machine

import io.ksmt.expr.KBitVecValue
import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.ExtOp
import org.sbpf.symex.ir.UnOp
import org.sbpf.symex.ir.Width
import org.usvm.UMachineOptions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

// Все аргументы — константы: KSMT сворачивает выражение в число, его и сравниваем.
// Значения подобраны так, чтобы перепутанные соседние операции давали разный результат.
class ExprTranslatorTest {
    private val ctx = SbpfContext(SbpfComponents(UMachineOptions()))

    // r1 = 0x7fffffff, остальные регистры не нужны.
    private val translator = ExprTranslator(ctx) { index ->
        require(index == 1) { "unexpected r$index" }
        ctx.mkBv(0x7fffffffL, 64u)
    }

    @AfterTest
    fun close() = ctx.close()

    private fun value(expr: Expr): ULong {
        val result = assertIs<KBitVecValue<*>>(translator.bv(expr), "$expr")
        return result.stringValue.toULong(2)
    }

    private fun bits(expr: Expr) = translator.bv(expr).bits

    @Test
    fun bin() {
        val a = 0xfffffffffffffff0uL
        val b = 0x13uL
        val shifted = 0x8000000000000010uL
        val cases = listOf(
            Triple(BinOp.Add, a to b, 0x3uL),
            Triple(BinOp.Sub, a to b, 0xffffffffffffffdduL),
            Triple(BinOp.Mul, a to b, 0xfffffffffffffed0uL),
            Triple(BinOp.And, a to b, 0x10uL),
            Triple(BinOp.Or, a to b, 0xfffffffffffffff3uL),
            Triple(BinOp.Xor, a to b, 0xffffffffffffffe3uL),
            Triple(BinOp.Udiv, 100uL to 7uL, 14uL),
            Triple(BinOp.Urem, 100uL to 7uL, 2uL),
            Triple(BinOp.Shl, shifted to 4uL, 0x100uL),
            Triple(BinOp.Lshr, shifted to 4uL, 0x0800000000000001uL),
            Triple(BinOp.Ashr, shifted to 4uL, 0xf800000000000001uL),
        )
        assertEquals(BinOp.entries.toSet(), cases.map { it.first }.toSet())
        for ((op, args, expected) in cases) {
            assertEquals(expected, value(bin(op, imm(args.first), imm(args.second))), "$op")
        }
    }

    @Test
    fun cmp() {
        // На этих четырёх парах у всех десяти сравнений разные ответы: перепутанная операция не пройдёт.
        val minusOne = ULong.MAX_VALUE
        val pairs = listOf(5uL to 5uL, minusOne to 1uL, 1uL to minusOne, 1uL to 2uL)
        val cases = mapOf(
            CmpOp.Eq to listOf(true, false, false, false),
            CmpOp.Ne to listOf(false, true, true, true),
            CmpOp.Ult to listOf(false, false, true, true),
            CmpOp.Ule to listOf(true, false, true, true),
            CmpOp.Ugt to listOf(false, true, false, false),
            CmpOp.Uge to listOf(true, true, false, false),
            CmpOp.Slt to listOf(false, true, false, true),
            CmpOp.Sle to listOf(true, true, false, true),
            CmpOp.Sgt to listOf(false, false, true, false),
            CmpOp.Sge to listOf(true, false, true, false),
        )
        assertEquals(CmpOp.entries.toSet(), cases.keys)
        assertEquals(cases.size, cases.values.toSet().size)
        for ((op, expected) in cases) {
            for ((pair, result) in pairs.zip(expected)) {
                val cmp = Expr.Cmp(op, imm(pair.first), imm(pair.second))
                assertEquals(ctx.mkBool(result), translator.bool(cmp), "$op on $pair")
            }
        }
    }

    @Test
    fun un() {
        assertEquals(0xfffffffffffffffbuL, value(Expr.Un(UnOp.Neg, imm(5uL))))
        assertEquals(0x8877665544332211uL, value(Expr.Un(UnOp.Bswap, imm(0x1122334455667788uL))))
        assertEquals(0x44332211uL, value(Expr.Un(UnOp.Bswap, imm(0x11223344uL, Width.W32))))
        assertEquals(0x2211uL, value(Expr.Un(UnOp.Bswap, imm(0x1122uL, Width.W16))))
        assertFailsWith<IllegalArgumentException> { translator.bv(Expr.Un(UnOp.Bswap, imm(1uL, Width.W8))) }
    }

    @Test
    fun ext() {
        val byte = imm(0x80uL, Width.W8)
        assertEquals(0x80uL, value(Expr.Ext(ExtOp.Zext, Width.W64, byte)))
        assertEquals(0xffffffffffffff80uL, value(Expr.Ext(ExtOp.Sext, Width.W64, byte)))
        assertEquals(0xdef0uL, value(Expr.Ext(ExtOp.Trunc, Width.W16, imm(0x123456789abcdef0uL))))
        assertEquals(64, bits(Expr.Ext(ExtOp.Zext, Width.W64, byte)))
        assertEquals(16, bits(Expr.Ext(ExtOp.Trunc, Width.W16, imm(1uL))))
        // Расширение до той же ширины — без изменений.
        assertEquals(5uL, value(Expr.Ext(ExtOp.Sext, Width.W64, imm(5uL))))
        assertFailsWith<IllegalArgumentException> { translator.bv(Expr.Ext(ExtOp.Zext, Width.W8, imm(1uL))) }
        assertFailsWith<IllegalArgumentException> { translator.bv(Expr.Ext(ExtOp.Trunc, Width.W64, byte)) }
    }

    // Семантика SMT, а не переводчика: сдвиг на ≥ N даёт 0. Лифтер маскирует сдвиг на регистр сам.
    @Test
    fun shiftByWidthIsZero() {
        assertEquals(0uL, value(bin(BinOp.Lshr, imm(0x8000000000000000uL), imm(64uL))))
    }

    // add32 r1, 1 при r1 = 0x7fffffff: sext64(add(lo32(r1), 1:32)).
    @Test
    fun add32() {
        val lo32 = Expr.Ext(ExtOp.Trunc, Width.W32, reg(1))
        val sum = bin(BinOp.Add, lo32, imm(1uL, Width.W32))
        assertEquals(0xffffffff80000000uL, value(Expr.Ext(ExtOp.Sext, Width.W64, sum)))
    }

    @Test
    fun widthMismatch() {
        assertFailsWith<IllegalArgumentException> { translator.bv(bin(BinOp.Add, imm(1uL), imm(1uL, Width.W32))) }
        val mixedCmp = Expr.Cmp(CmpOp.Eq, imm(1uL), imm(1uL, Width.W32))
        assertFailsWith<IllegalArgumentException> { translator.bool(mixedCmp) }
        assertFailsWith<IllegalArgumentException> { translator.bv(Expr.Cmp(CmpOp.Eq, imm(1uL), imm(1uL))) }
    }
}
