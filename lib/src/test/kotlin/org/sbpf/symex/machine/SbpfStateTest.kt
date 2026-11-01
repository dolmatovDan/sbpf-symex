package org.sbpf.symex.machine

import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Step
import org.usvm.UMachineOptions
import org.usvm.collections.immutable.internal.MutabilityOwnership
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame

class SbpfStateTest {
    private val ctx = SbpfContext(SbpfComponents(UMachineOptions()))

    @AfterTest
    fun close() = ctx.close()

    // После clone ветки независимы: запись регистра в одной не видна в другой.
    @Test
    fun cloneSeparatesRegisters() = with(ctx) {
        val entry = function("main", block(0, exit = Exit.Return(0)))
        val original = SbpfState(ctx, MutabilityOwnership(), entry)
        original.memory.stack.push(11)
        original.memory.stack.writeRegister(1, mkBv(1L))

        val copy = original.clone()
        copy.memory.stack.writeRegister(1, mkBv(2L))
        original.memory.stack.writeRegister(2, mkBv(3L))

        assertEquals(mkBv(1L), original.memory.stack.readRegister(1, bv64Sort))
        assertEquals(mkBv(2L), copy.memory.stack.readRegister(1, bv64Sort))
        assertEquals(mkBv(3L), original.memory.stack.readRegister(2, bv64Sort))
        // В копии r2 не записан: чтение даёт символ-вход, а не 3.
        assertNotEquals(mkBv(3L), copy.memory.stack.readRegister(2, bv64Sort))
        assertEquals(mkRegisterReading(2, bv64Sort), copy.memory.stack.readRegister(2, bv64Sort))
    }

    // Оригинал после clone держит новый жетон: откат неудачного fork в StateForker вернёт условиям пути его,
    // а не старый, общий с копией.
    @Test
    fun cloneRenewsOriginalOwnership() {
        val entry = function("main", block(0, exit = Exit.Return(0)))
        val state = SbpfState(ctx, MutabilityOwnership(), entry)
        val before = state.ownership
        val copy = state.clone()
        assertNotSame(before, state.ownership)
        assertNotSame(state.ownership, copy.ownership)
    }

    private fun stateAt(step: Step): SbpfState {
        val entry = function("main", block(0, exit = Exit.Return(0)))
        return SbpfState(ctx, MutabilityOwnership(), entry).apply {
            memory.stack.push(REGISTER_COUNT)
            newStep(step)
        }
    }

    // next без pc — не инструкция, записи нет; ja (next с pc) — инструкция.
    @Test
    fun traceSkipsNextWithoutPc() {
        val fallthrough = stateAt(Exit.Next(target = 1))
        TraceObserver().onStatePeeked(fallthrough)
        assertEquals(0, fallthrough.trace.size)

        val ja = stateAt(Exit.Next(target = 1, pc = 4))
        TraceObserver().onStatePeeked(ja)
        assertEquals(listOf(4), ja.trace.map { it.pc })
    }

    // Копия наследует трассу до clone, дальше записи расходятся.
    @Test
    fun cloneSharesTraceHistory() {
        val original = stateAt(Exit.Return(7))
        val observer = TraceObserver()
        observer.onStatePeeked(original)
        val copy = original.clone()
        observer.onStatePeeked(copy)
        assertEquals(listOf(7), original.trace.map { it.pc })
        assertEquals(listOf(7, 7), copy.trace.map { it.pc })
    }
}
