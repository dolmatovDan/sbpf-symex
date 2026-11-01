package org.sbpf.symex.machine

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.ExtOp
import org.sbpf.symex.ir.Fault
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.UnOp
import org.sbpf.symex.ir.Width
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Программы из одного блока, ожидания посчитаны руками (как в sbpf-ir, тест asm в crates/sbpf-run/tests/run.rs).
class SbpfMachineTest {
    private fun registersAfter(module: IrModule) = SbpfMachine(module).use { machine ->
        val states = machine.analyze()
        assertEquals(1, states.size, "one path without branches")
        val state = states.single()
        assertEquals(SbpfResult.Exited, state.result)
        state.registers()
    }

    // Итог и регистры каждого пути.
    private fun pathsOf(module: IrModule) = SbpfMachine(module).use { machine ->
        machine.analyze().map { it.result to it.registers() }
    }

    private fun divisorCheck(divisor: ULong) = program(
        assign(0, 1, imm(divisor)),
        check(1, Expr.Cmp(CmpOp.Ne, reg(1), imm(0uL))),
        assign(1, 2, imm(1uL)),
    )

    private fun program(vararg statements: Stmt) =
        module(function("entrypoint", block(0, *statements, exit = Exit.Return(statements.size))))

    private fun lo32(e: Expr) = Expr.Ext(ExtOp.Trunc, Width.W32, e)

    // Как в alu64.s: lddw, add с переполнением, sub, mul по модулю 2^64, neg.
    @Test
    fun alu64() {
        val registers = registersAfter(
            program(
                assign(0, 1, imm(0xfffffffffffffffeuL)),
                assign(1, 1, bin(BinOp.Add, reg(1), imm(5uL))),
                assign(2, 2, imm(10uL)),
                assign(3, 2, bin(BinOp.Add, reg(2), reg(1))),
                assign(4, 2, bin(BinOp.Sub, reg(2), imm(20uL))),
                assign(5, 4, imm(0x100000001uL)),
                assign(6, 4, bin(BinOp.Mul, reg(4), reg(4))),
                assign(7, 9, imm(5uL)),
                assign(8, 9, Expr.Un(UnOp.Neg, reg(9))),
                assign(9, 0, imm(0uL)),
            ),
        )
        assertEquals(0uL, registers[0])
        assertEquals(3uL, registers[1])
        assertEquals(0xfffffffffffffff9uL, registers[2])
        assertEquals(0x200000001uL, registers[4])
        assertEquals(0xfffffffffffffffbuL, registers[9])
    }

    // add32 расширяет результат знаком, lsh32 и mov32 — нулями; сдвиг на регистр маскируется лифтером.
    @Test
    fun alu32() {
        val registers = registersAfter(
            program(
                assign(0, 1, imm(0x7fffffffuL)),
                assign(1, 1, Expr.Ext(ExtOp.Sext, Width.W64, bin(BinOp.Add, lo32(reg(1)), imm(1uL, Width.W32)))),
                assign(2, 2, imm(0xffffffff00000003uL)),
                assign(3, 3, imm(33uL)),
                assign(
                    4,
                    2,
                    Expr.Ext(
                        ExtOp.Zext,
                        Width.W64,
                        bin(BinOp.Shl, lo32(reg(2)), bin(BinOp.And, lo32(reg(3)), imm(31uL, Width.W32))),
                    ),
                ),
                assign(5, 4, imm(0xdeadbeefcafebabeuL)),
                assign(6, 5, Expr.Ext(ExtOp.Zext, Width.W64, lo32(reg(4)))),
            ),
        )
        assertEquals(0xffffffff80000000uL, registers[1])
        assertEquals(6uL, registers[2])
        assertEquals(0xcafebabeuL, registers[5])
    }

    // mov64 и чтение только что записанного регистра.
    @Test
    fun mov() {
        val registers = registersAfter(
            program(
                assign(0, 4, imm(0xdeadbeefcafebabeuL)),
                assign(1, 6, reg(4)),
                assign(2, 7, bin(BinOp.Xor, reg(6), reg(4))),
                assign(3, 4, imm(1uL)),
            ),
        )
        assertEquals(1uL, registers[4])
        assertEquals(0xdeadbeefcafebabeuL, registers[6])
        assertEquals(0uL, registers[7])
    }

    // Регистры на входе — как у VM: их же раннер пишет в первую запись трассы.
    @Test
    fun registersOnEntry() {
        val registers = registersAfter(program())
        val expected = MutableList(REGISTER_COUNT) { 0uL }.apply {
            this[1] = 0x400000000uL
            this[2] = 0x400000010uL
            this[10] = 0x200001000uL
        }
        assertEquals(expected, registers)
    }

    // Условие check истинно: развилки нет, путь идёт дальше.
    @Test
    fun checkPasses() {
        val (result, registers) = pathsOf(divisorCheck(5uL)).single()
        assertEquals(SbpfResult.Exited, result)
        assertEquals(1uL, registers[2])
    }

    // Условие ложно: путь завершается ошибкой check, следующая инструкция не исполняется.
    @Test
    fun checkFails() {
        val (result, registers) = pathsOf(divisorCheck(0uL)).single()
        assertEquals(SbpfResult.Failed(Fault.DivideByZero), result)
        assertEquals(entryRegisters()[2], registers[2])
    }

    private fun traceOf(module: IrModule) = SbpfMachine(module, recordTrace = true).use { machine ->
        val state = machine.analyze().single()
        state.trace() to state.registers()
    }

    // Одна запись на инструкцию, состояние перед ней; lddw занимает два слота, как у раннера.
    @Test
    fun trace() {
        val ir = module(
            function(
                "entrypoint",
                block(
                    0,
                    assign(0, 1, imm(0x100000000uL)),
                    assign(2, 1, bin(BinOp.Add, reg(1), imm(1uL))),
                    exit = Exit.Return(3),
                ),
            ),
        )
        val (trace, registers) = traceOf(ir)
        assertEquals(listOf(0uL, 2uL, 3uL), trace.map { it.last() })
        assertEquals(entryRegisters() + 0uL, trace.first())
        assertEquals(0x100000000uL, trace[1][1])
        assertEquals(registers + 3uL, trace.last())
    }

    // check и его инструкция дают одну запись; у упавшего check она тоже есть.
    @Test
    fun traceOfCheck() {
        assertEquals(listOf(0uL, 1uL, 3uL), traceOf(divisorCheck(5uL)).first.map { it.last() })
        assertEquals(listOf(0uL, 1uL), traceOf(divisorCheck(0uL)).first.map { it.last() })
    }

    @Test
    fun unsupportedStep() {
        val branch = Exit.Branch(1, Expr.Cmp(CmpOp.Eq, reg(1), imm(0uL)), thenTarget = 2, elseTarget = 2)
        val ir = module(
            function(
                "entrypoint",
                block(0, assign(0, 1, imm(0uL)), exit = branch),
                block(2, exit = Exit.Return(2)),
            ),
        )
        val error = assertFailsWith<NotImplementedError> { SbpfMachine(ir).use { it.analyze() } }
        assertContains(error.message.orEmpty(), "pc 1")
    }

    // Остановка по лимиту шагов — ошибка, а не молча неполный список путей.
    @Test
    fun stepLimit() {
        val ir = program(assign(0, 1, imm(1uL)), assign(1, 2, imm(2uL)), assign(2, 3, imm(3uL)))
        val options = SbpfMachine.defaultOptions.copy(stepLimit = 1uL)
        assertFailsWith<IllegalStateException> { SbpfMachine(ir, options).use { it.analyze() } }
    }

    @Test
    fun assignOfWrongWidth() {
        val ir = program(assign(0, 1, imm(1uL, Width.W32)))
        val error = assertFailsWith<IllegalArgumentException> { SbpfMachine(ir).use { it.analyze() } }
        assertContains(error.message.orEmpty(), "pc 0")
    }
}
