package org.sbpf.symex.machine

import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.IrBlock
import org.sbpf.symex.ir.IrFunction
import org.sbpf.symex.ir.IrModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Loops on hand-built block graphs. Block starts double as names; conditions do not matter here.
class LoopsTest {
    private val cond = Expr.Cmp(CmpOp.Ne, reg(1), imm(0uL))

    private fun branch(pc: Int, thenTarget: Int, elseTarget: Int) = Exit.Branch(pc, cond, thenTarget, elseTarget)

    private fun ja(pc: Int, target: Int) = Exit.Next(target, pc)

    // One function; the module graph is the function's own graph then.
    private fun fn(vararg blocks: IrBlock) = IrFunction(blocks.first().start, "f", blocks.toList())

    private fun IrFunction.exitOf(start: Int) = blocks.single { it.start == start }.exit

    private fun loopsOf(function: IrFunction) = loopsOf(module(function))

    private fun loopsOf(module: IrModule) = findLoops(module).associateBy { it.header }

    private fun findLoops(function: IrFunction) = findLoops(module(function))

    // do { … } while (c): the body is one block, its branch decides the iteration.
    @Test
    fun doWhile() {
        val f = fn(
            block(0, exit = Exit.Next(1)),
            block(1, exit = branch(1, 1, 2)),
            block(2, exit = Exit.Return(2)),
        )
        val loops = loopsOf(f)
        assertEquals(setOf(1), loops.keys)
        assertEquals(setOf(1), loops.getValue(1).body)
        assertEquals(setOf(f.exitOf(1)), loops.getValue(1).forkPoints)
    }

    // while (c) { … }: the test at the top, ja back at the bottom; the ja is not a fork point.
    @Test
    fun whileWithJaBack() {
        val f = fn(
            block(0, exit = Exit.Next(1)),
            block(1, exit = branch(1, 3, 2)),
            block(2, exit = ja(2, 1)),
            block(3, exit = Exit.Return(3)),
        )
        val loop = loopsOf(f).values.single()
        assertEquals(1, loop.header)
        assertEquals(setOf(1, 2), loop.body)
        assertEquals(setOf(f.exitOf(1)), loop.forkPoints)
    }

    // An inner loop inside an outer one: two loops, the inner body inside the outer; the inner branch does not
    // decide the outer iteration (block 4 post-dominates it).
    @Test
    fun nested() {
        val f = fn(
            block(0, exit = Exit.Next(1)),
            block(1, exit = branch(1, 5, 2)),
            block(2, exit = Exit.Next(3)),
            block(3, exit = branch(3, 3, 4)),
            block(4, exit = ja(4, 1)),
            block(5, exit = Exit.Return(5)),
        )
        val loops = loopsOf(f)
        assertEquals(setOf(1, 3), loops.keys)
        assertEquals(setOf(1, 2, 3, 4), loops.getValue(1).body)
        assertEquals(setOf(3), loops.getValue(3).body)
        assertTrue(loops.getValue(3).body.all { it in loops.getValue(1).body })
        assertEquals(setOf(f.exitOf(1)), loops.getValue(1).forkPoints)
        assertEquals(setOf(f.exitOf(3)), loops.getValue(3).forkPoints)
    }

    // for (;;) { if (a) continue; if (b) break; }: both branches decide the iteration, the continue because it
    // jumps to the header.
    @Test
    fun continueAndBreak() {
        val f = fn(
            block(0, exit = Exit.Next(1)),
            block(1, exit = branch(1, 1, 2)),
            block(2, exit = branch(2, 4, 3)),
            block(3, exit = ja(3, 1)),
            block(4, exit = Exit.Return(4)),
        )
        val loop = loopsOf(f).values.single()
        assertEquals(setOf(1, 2, 3), loop.body)
        assertEquals(setOf(f.exitOf(1), f.exitOf(2)), loop.forkPoints)
    }

    // if (a) x else y inside the body: the two sides rejoin before the loop test, so that branch is not a fork
    // point; the branch that leaves through a block with an unconditional jump out is.
    @Test
    fun diamondInsideIterationIsNotAForkPoint() {
        val f = fn(
            block(0, exit = Exit.Next(1)),
            block(1, exit = branch(1, 2, 3)),
            block(2, exit = Exit.Next(4)),
            block(3, exit = Exit.Next(4)),
            block(4, exit = branch(4, 5, 1)),
            block(5, exit = ja(5, 6)),
            block(6, exit = Exit.Return(6)),
        )
        val loop = loopsOf(f).values.single()
        assertEquals(setOf(1, 2, 3, 4), loop.body)
        assertEquals(setOf(f.exitOf(4)), loop.forkPoints)
    }

    @Test
    fun noLoops() {
        val f = fn(
            block(0, exit = branch(0, 1, 2)),
            block(1, exit = Exit.Next(2)),
            block(2, exit = Exit.Return(2)),
        )
        assertEquals(emptyList(), findLoops(f))
    }

    // A cycle entered at two different blocks has no header that dominates its tail: not a natural loop, and it
    // is not found. A known limitation: such a loop is bounded only by the step limit.
    @Test
    fun irreducibleCycleIsNotFound() {
        val f = fn(
            block(0, exit = branch(0, 1, 2)),
            block(1, exit = Exit.Next(2)),
            block(2, exit = branch(2, 1, 3)),
            block(3, exit = Exit.Return(3)),
        )
        assertEquals(emptyList(), findLoops(f))
    }

    // A jump to a block of another function leaves the loop when that block does not come back.
    @Test
    fun jumpIntoAnotherFunctionLeavesTheLoop() {
        val f = fn(
            block(0, exit = Exit.Next(1)),
            block(1, exit = branch(1, 100, 2)),
            block(2, exit = ja(2, 1)),
        )
        val g = IrFunction(100, "g", listOf(block(100, exit = Exit.Return(100))))
        val loop = loopsOf(module(f, g)).values.single()
        assertEquals(setOf(1, 2), loop.body)
        assertEquals(setOf(f.exitOf(1)), loop.forkPoints)
    }

    // A loop through two functions (f jumps into a block of g that jumps back; the lifter's function boundaries are
    // approximate): found on the module graph, with the body in both functions. The block of g must not be reachable
    // from g's own entry, otherwise the header would not dominate it (an irreducible loop).
    @Test
    fun loopThroughTwoFunctions() {
        val f = fn(
            block(0, exit = Exit.Next(1)),
            block(1, exit = branch(1, 100, 2)),
            block(2, exit = Exit.Return(2)),
        )
        val g = IrFunction(50, "g", listOf(block(50, exit = Exit.Return(50)), block(100, exit = ja(100, 1))))
        val loop = loopsOf(module(f, g)).values.single()
        assertEquals(1, loop.header)
        assertEquals(setOf(1, 100), loop.body)
        assertEquals(setOf(f.exitOf(1)), loop.forkPoints)
        // Reachable from g's entry as well: entered from two sides, not a natural loop.
        val g2 = IrFunction(50, "g", listOf(block(50, exit = Exit.Next(100)), block(100, exit = ja(100, 1))))
        assertEquals(emptyList(), findLoops(module(f, g2)))
    }
}
