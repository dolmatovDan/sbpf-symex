package org.sbpf.symex.machine

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.Width
import org.sbpf.symex.machine.memory.InputSpec
import org.sbpf.symex.machine.memory.IxMode
import org.sbpf.symex.result.PathStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The loop limiter: paths with more symbolic iterations than the limit are dropped, concrete loops run to the end.
class LoopLimitTest {
    private fun options(limit: Int?) = SbpfMachine.defaultOptions.copy(loopIterationLimit = limit)

    // r1 = bound; r3 = 0; while (r3 < r1) r3++. The bound comes from the first statement.
    private fun countTo(bound: Stmt) = module(
        function(
            "entrypoint",
            block(0, bound, assign(1, 3, imm(0uL)), exit = Exit.Next(2)),
            block(2, exit = Exit.Branch(2, Expr.Cmp(CmpOp.Ult, reg(3), reg(1)), 3, 4)),
            block(3, assign(3, 3, bin(BinOp.Add, reg(3), imm(1uL))), exit = Exit.Next(2, pc = 4)),
            block(4, exit = Exit.Return(5)),
        ),
    )

    private val symbolicByte = InputSpec(ByteArray(32), IxMode.Symbolic(1))

    // A loop bounded by the first byte of the instruction data: with limit L exactly the paths with 0..L
    // iterations finish, one per bound value.
    @Test
    fun symbolicLoopIsCut() {
        val ir = countTo(load(0, 1, Width.W8, reg(2)))
        for (limit in listOf(0, 3)) {
            val paths = SbpfMachine(ir, options(limit), input = symbolicByte).use { it.results() }
            assertEquals(limit + 1, paths.size, "paths with limit $limit")
            assertTrue(paths.all { it.status == PathStatus.Success })
            // Each path exits with r3 == bound after r3 iterations.
            assertEquals((0..limit).map { it.toULong() }.toSet(), paths.map { it.registers[3] }.toSet())
            assertTrue(paths.all { it.registers[1] == it.registers[3] })
        }
    }

    // A loop with a constant bound forks nowhere, so its iterations are not counted: 100 of them pass a limit of 2.
    @Test
    fun concreteLoopRunsToTheEnd() {
        val path = SbpfMachine(countTo(assign(0, 1, imm(100uL))), options(2)).use { it.results() }.single()
        assertEquals(PathStatus.Success, path.status)
        assertEquals(100uL, path.registers[3])
    }

    // No limit: a loop with a byte bound has 256 outcomes.
    @Test
    fun noLimit() {
        val paths = SbpfMachine(countTo(load(0, 1, Width.W8, reg(2))), options(null), input = symbolicByte)
            .use { it.results() }
        assertEquals(256, paths.size)
    }
}
