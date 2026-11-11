package org.sbpf.symex.machine

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.Fault
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.Width
import org.sbpf.symex.result.PathResult
import org.sbpf.symex.result.PathStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Machine mechanics on hand-built IR. Instruction semantics are checked by DiffTest against the runner.
class SbpfMachineTest {
    // Inputs are known, so every condition is a constant and there is exactly one path; the trace is always recorded.
    private fun pathOf(module: IrModule) = SbpfMachine(module, recordTrace = true).use { it.results() }.single()

    private fun divisorCheck(divisor: ULong) = program(
        assign(0, 1, imm(divisor)),
        check(1, Expr.Cmp(CmpOp.Ne, reg(1), imm(0uL))),
        assign(1, 2, imm(1uL)),
    )

    private fun program(vararg statements: Stmt) =
        module(function("entrypoint", block(0, *statements, exit = Exit.Return(statements.size))))

    // Entry registers match the VM's: the runner writes the same values into the first trace entry.
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

    // The check condition is true: no fork, the path continues.
    @Test
    fun checkPasses() {
        val path = pathOf(divisorCheck(5uL))
        assertEquals(PathStatus.Success, path.status)
        assertEquals(1uL, path.registers[2])
    }

    // The condition is false: the path ends with a check error, the next instruction is not executed.
    @Test
    fun checkFails() {
        val path = pathOf(divisorCheck(0uL))
        assertEquals(PathStatus.Failure, path.status)
        assertEquals("DivideByZero", path.error)
        assertEquals(entryRegisters()[2], path.registers[2])
    }

    // One entry per instruction, holding the state before it; lddw takes two slots, as in the runner.
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

    // A check and its instruction produce one entry; a failed check has one too.
    @Test
    fun traceOfCheck() {
        assertEquals(listOf(0uL, 1uL, 3uL), pathOf(divisorCheck(5uL)).trace.map { it.last() })
        assertEquals(listOf(0uL, 1uL), pathOf(divisorCheck(0uL)).trace.map { it.last() })
    }

    // A nonzero r0 at exit is the program's error code; decoding it is not implemented yet.
    @Test
    fun exitCodeNotSupported() {
        val ir = program(assign(0, 0, imm(7uL)))
        assertFailsWith<NotImplementedError> { pathOf(ir) }
    }

    private fun PathResult.pcs() = trace.map { it.last() }

    // Falling through to the next block is not an instruction: no trace entry, no pc.
    @Test
    fun nextWithoutPc() {
        val ir = module(
            function(
                "entrypoint",
                block(0, assign(0, 1, imm(1uL)), exit = Exit.Next(target = 1)),
                block(1, assign(1, 2, reg(1)), exit = Exit.Return(2)),
            ),
        )
        val path = pathOf(ir)
        assertEquals(PathStatus.Success, path.status)
        assertEquals(1uL, path.registers[2])
        assertEquals(listOf(0uL, 1uL, 2uL), path.pcs())
    }

    // next with pc is ja: an instruction with its own entry; the skipped block is not executed.
    @Test
    fun ja() {
        val ir = module(
            function(
                "entrypoint",
                block(0, assign(0, 1, imm(1uL)), exit = Exit.Next(target = 3, pc = 1)),
                block(2, assign(2, 1, imm(99uL)), exit = Exit.Next(target = 3)),
                block(3, exit = Exit.Return(3)),
            ),
        )
        val path = pathOf(ir)
        assertEquals(1uL, path.registers[1])
        assertEquals(listOf(0uL, 1uL, 3uL), path.pcs())
    }

    // if (r1 == 0) r2 = 10 else r2 = 20; the then side jumps over the else block with ja.
    private fun ifElse(r1: ULong) = module(
        function(
            "entrypoint",
            block(
                0,
                assign(0, 1, imm(r1)),
                exit = Exit.Branch(1, Expr.Cmp(CmpOp.Eq, reg(1), imm(0uL)), thenTarget = 2, elseTarget = 4),
            ),
            block(2, assign(2, 2, imm(10uL)), exit = Exit.Next(target = 5, pc = 3)),
            block(4, assign(4, 2, imm(20uL)), exit = Exit.Next(target = 5)),
            block(5, exit = Exit.Return(5)),
        ),
    )

    @Test
    fun branchTaken() {
        val path = pathOf(ifElse(0uL))
        assertEquals(PathStatus.Success, path.status)
        assertEquals(10uL, path.registers[2])
        assertEquals(listOf(0uL, 1uL, 2uL, 3uL, 5uL), path.pcs())
    }

    @Test
    fun branchNotTaken() {
        val path = pathOf(ifElse(7uL))
        assertEquals(20uL, path.registers[2])
        assertEquals(listOf(0uL, 1uL, 4uL, 5uL), path.pcs())
    }

    // Both sides lead to the same block: still one path.
    @Test
    fun branchToSameBlock() {
        val ir = module(
            function(
                "entrypoint",
                block(0, exit = Exit.Branch(0, Expr.Cmp(CmpOp.Eq, reg(1), reg(1)), thenTarget = 1, elseTarget = 1)),
                block(1, exit = Exit.Return(1)),
            ),
        )
        assertEquals(listOf(0uL, 1uL), pathOf(ir).pcs())
    }

    // r1 = 0; r2 = 0; do { r1 += 1; r2 += r1 } while (r1 < 10): the back edge is a branch to the loop's own block.
    @Test
    fun loop() {
        val ir = module(
            function(
                "entrypoint",
                block(0, assign(0, 1, imm(0uL)), assign(1, 2, imm(0uL)), exit = Exit.Next(target = 2)),
                block(
                    2,
                    assign(2, 1, bin(BinOp.Add, reg(1), imm(1uL))),
                    assign(3, 2, bin(BinOp.Add, reg(2), reg(1))),
                    exit = Exit.Branch(4, Expr.Cmp(CmpOp.Ult, reg(1), imm(10uL)), thenTarget = 2, elseTarget = 5),
                ),
                block(5, exit = Exit.Return(5)),
            ),
        )
        val path = pathOf(ir)
        assertEquals(10uL, path.registers[1])
        assertEquals(55uL, path.registers[2])
        // Two setup instructions, three per iteration, exit.
        assertEquals(2 + 3 * 10 + 1, path.trace.size)
    }

    // A trap ends the path with its error; the VM records the instruction before failing on it.
    @Test
    fun trapUnsupportedInstruction() {
        val ir = module(
            function(
                "entrypoint",
                block(0, assign(0, 1, imm(1uL)), exit = Exit.Trap(1, Fault.UnsupportedInstruction)),
            ),
        )
        val path = pathOf(ir)
        assertEquals(PathStatus.Failure, path.status)
        assertEquals("UnsupportedInstruction", path.error)
        assertEquals(listOf(0uL, 1uL), path.pcs())
    }

    // Execution falls off the end of the code: there is no instruction at the trap's pc, so no entry for it.
    @Test
    fun trapExecutionOverrun() {
        val ir = module(
            function(
                "entrypoint",
                block(0, assign(0, 1, imm(1uL)), exit = Exit.Next(target = 1)),
                block(1, exit = Exit.Trap(1, Fault.ExecutionOverrun)),
            ),
        )
        val path = pathOf(ir)
        assertEquals(PathStatus.Failure, path.status)
        assertEquals("ExecutionOverrun", path.error)
        assertEquals(listOf(0uL), path.pcs())
    }

    @Test
    fun unsupportedStep() {
        val ir = module(
            function(
                "entrypoint",
                block(0, assign(0, 1, imm(0uL)), exit = Exit.Call(1, target = 3, next = 2)),
                block(2, exit = Exit.Return(2)),
            ),
            function("callee", block(3, exit = Exit.Return(3))),
        )
        val error = assertFailsWith<NotImplementedError> { SbpfMachine(ir).use { it.analyze() } }
        assertContains(error.message.orEmpty(), "pc 1")
    }

    // Stopping on the step limit is an error, not a silently incomplete list of paths.
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
