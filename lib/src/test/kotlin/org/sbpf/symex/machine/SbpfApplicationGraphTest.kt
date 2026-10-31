package org.sbpf.symex.machine

import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.Fault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SbpfApplicationGraphTest {
    // main: [0] два assign → branch на 3 / 5; [3] пустой, next → 5; [5] assign → call fail, возврат в 8;
    // [8] пустой, return. fail: [7] пустой, trap.
    private val s0 = assign(0, 1, imm(1uL))
    private val s1 = assign(1, 2, imm(2uL))
    private val branch = Exit.Branch(2, Expr.Cmp(CmpOp.Eq, reg(1), reg(2)), thenTarget = 3, elseTarget = 5)
    private val next3 = Exit.Next(target = 5, pc = 3)
    private val s5 = assign(5, 0, reg(1))
    private val call = Exit.Call(6, target = 7, next = 8)
    private val ret = Exit.Return(8)
    private val trap = Exit.Trap(7, Fault.UnsupportedInstruction)

    private val main = function(
        "main",
        block(0, s0, s1, exit = branch),
        block(3, exit = next3),
        block(5, s5, exit = call),
        block(8, exit = ret),
    )
    private val fail = function("fail", block(7, exit = trap))
    private val graph = SbpfApplicationGraph(module(main, fail))

    @Test
    fun successors() {
        assertEquals(listOf(s1), graph.successors(s0).toList())
        assertEquals(listOf(branch), graph.successors(s1).toList())
        // then, затем else; у пустого блока 3 первый шаг — его выход.
        assertEquals(listOf(next3, s5), graph.successors(branch).toList())
        assertEquals(listOf(s5), graph.successors(next3).toList())
        assertEquals(listOf(call), graph.successors(s5).toList())
        assertEquals(listOf(ret), graph.successors(call).toList())
        assertEquals(emptyList(), graph.successors(ret).toList())
        assertEquals(emptyList(), graph.successors(trap).toList())
    }

    @Test
    fun predecessors() {
        assertEquals(setOf(branch, next3), graph.predecessors(s5).toSet())
        assertEquals(emptyList(), graph.predecessors(s0).toList())
    }

    @Test
    fun functions() {
        assertEquals(listOf(s0), graph.entryPoints(main).toList())
        assertEquals(listOf(trap), graph.entryPoints(fail).toList())
        assertEquals(listOf(ret), graph.exitPoints(main).toList())
        assertEquals(listOf(trap), graph.exitPoints(fail).toList())
        assertEquals(main, graph.methodOf(ret))
        assertEquals(fail, graph.methodOf(trap))
        assertEquals(listOf(s0, s1, branch, next3, s5, call, ret), graph.statementsOf(main).toList())
    }

    @Test
    fun calls() {
        assertEquals(listOf(fail), graph.callees(call).toList())
        assertEquals(emptyList(), graph.callees(s5).toList())
        assertEquals(listOf(call), graph.callers(fail).toList())
        assertEquals(emptyList(), graph.callers(main).toList())
    }

    // Одинаковые statement'ы в разных местах — разные шаги.
    @Test
    fun stepsAreKeyedByIdentity() {
        val a = assign(0, 1, imm(1uL))
        val b = assign(1, 1, imm(1uL))
        val g = SbpfApplicationGraph(module(function("f", block(0, a, b, exit = Exit.Return(2)))))
        assertEquals(listOf(b), g.successors(a).toList())
    }

    @Test
    fun missingTarget() {
        assertFailsWith<IllegalArgumentException> {
            SbpfApplicationGraph(module(function("f", block(0, exit = Exit.Next(target = 9)))))
        }
    }
}
