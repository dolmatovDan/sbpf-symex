package org.sbpf.symex.machine

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
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
    // Путь без ветвлений ровно один; трасса пишется всегда.
    private fun pathOf(module: IrModule) = SbpfMachine(module, recordTrace = true).use { it.results() }.single()

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
        val path = pathOf(program())
        assertEquals(PathStatus.Success, path.status)
        val expected = MutableList(REGISTER_COUNT) { 0uL }.apply {
            this[1] = 0x400000000uL
            this[2] = 0x400000010uL
            this[10] = 0x200001000uL
        }
        assertEquals(expected, path.registers)
    }

    // Условие check истинно: развилки нет, путь идёт дальше.
    @Test
    fun checkPasses() {
        val path = pathOf(divisorCheck(5uL))
        assertEquals(PathStatus.Success, path.status)
        assertEquals(1uL, path.registers[2])
    }

    // Условие ложно: путь завершается ошибкой check, следующая инструкция не исполняется.
    @Test
    fun checkFails() {
        val path = pathOf(divisorCheck(0uL))
        assertEquals(PathStatus.Failure, path.status)
        assertEquals("DivideByZero", path.error)
        assertEquals(entryRegisters()[2], path.registers[2])
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
        val path = pathOf(ir)
        assertEquals(listOf(0uL, 2uL, 3uL), path.trace.map { it.last() })
        assertEquals(entryRegisters() + 0uL, path.trace.first())
        assertEquals(0x100000000uL, path.trace[1][1])
        assertEquals(path.registers + 3uL, path.trace.last())
    }

    // check и его инструкция дают одну запись; у упавшего check она тоже есть.
    @Test
    fun traceOfCheck() {
        assertEquals(listOf(0uL, 1uL, 3uL), pathOf(divisorCheck(5uL)).trace.map { it.last() })
        assertEquals(listOf(0uL, 1uL), pathOf(divisorCheck(0uL)).trace.map { it.last() })
    }

    // Ненулевой r0 на выходе — код ошибки программы, его разбор ещё не сделан.
    @Test
    fun exitCodeNotSupported() {
        val ir = program(assign(0, 0, imm(7uL)))
        assertFailsWith<NotImplementedError> { pathOf(ir) }
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
