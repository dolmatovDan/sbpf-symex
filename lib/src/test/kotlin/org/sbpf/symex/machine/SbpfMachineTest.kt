package org.sbpf.symex.machine

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.Fault
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.Width
import org.sbpf.symex.result.PathStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Механика машины на программах из одного блока. Семантику инструкций проверяет DiffTest против раннера.
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

    private fun traceOf(module: IrModule) = SbpfMachine(module, recordTrace = true).use { machine ->
        val state = machine.analyze().single()
        state.traceValues() to state.registers()
    }

    private fun divisorCheck(divisor: ULong) = program(
        assign(0, 1, imm(divisor)),
        check(1, Expr.Cmp(CmpOp.Ne, reg(1), imm(0uL))),
        assign(1, 2, imm(1uL)),
    )

    private fun program(vararg statements: Stmt) =
        module(function("entrypoint", block(0, *statements, exit = Exit.Return(statements.size))))

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

    // Итог пути в терминах раннера: упавший check — failure с видом ошибки.
    @Test
    fun failedCheckResult() {
        val path = SbpfMachine(divisorCheck(0uL)).use { it.run() }.single()
        assertEquals(PathStatus.Failure, path.status)
        assertEquals("DivideByZero", path.error)
    }

    // Ненулевой r0 на выходе — код ошибки программы, его разбор ещё не сделан.
    @Test
    fun exitCodeNotSupported() {
        val ir = program(assign(0, 0, imm(7uL)))
        assertFailsWith<NotImplementedError> { SbpfMachine(ir).use { it.run() } }
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
