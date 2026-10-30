package org.sbpf.symex

import io.ksmt.KContext
import io.ksmt.expr.KBitVec64Value
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.yices.KYicesSolver
import io.ksmt.utils.getValue
import org.usvm.memory.URegistersStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

// Ошибка версий KSMT/usvm видна только во время работы (NoSuchMethodError), поэтому зависимости проверяются вызовами.
class DependenciesTest {
    @Test
    fun yicesSolvesBitVectorFormula() = with(KContext()) {
        val x by bv64Sort
        KYicesSolver(this).use { solver ->
            solver.assert(mkEq(mkBvAddExpr(x, mkBv(1L)), mkBv(0L)))
            assertEquals(KSolverStatus.SAT, solver.check())
            val value = solver.model().eval(x) as KBitVec64Value
            assertEquals(ULong.MAX_VALUE, value.longValue.toULong())
        }
    }

    @Test
    fun usvmRegistersStackKeepsWrittenValue() = with(KContext()) {
        val stack = URegistersStack()
        stack.push(11)
        val value = mkBv(7L)
        stack.writeRegister(1, value)
        assertSame(value, stack.readRegister(1, bv64Sort))
    }
}
