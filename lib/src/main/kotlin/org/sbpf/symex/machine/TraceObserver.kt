package org.sbpf.symex.machine

import io.ksmt.sort.KBv64Sort
import org.sbpf.symex.ir.Stmt
import org.usvm.UExpr
import org.usvm.statistics.UMachineObserver

// Регистры r0–r10 перед инструкцией с этим pc. Формулы, а не числа: вычисляются в модели в конце пути.
class TraceEntry(val pc: Int, val registers: List<UExpr<KBv64Sort>>)

// Трасса как у раннера (sbpf-ir/docs/runner.md): одна запись на инструкцию, состояние перед ней.
// Записи лежат в состоянии, а не здесь: при развилке копия наследует историю до точки развилки.
class TraceObserver : UMachineObserver<SbpfState> {
    override fun onStatePeeked(state: SbpfState) {
        val step = state.lastStep
        // next без pc — не инструкция.
        val pc = step.pc ?: return
        // Запись делается на первом шаге инструкции. check стоит перед своей инструкцией с тем же pc,
        // поэтому запись делает он, и при упавшем check инструкция, на которой упал путь, в трассе есть.
        // У корня pathNode (depth 0) шага нет.
        val previous = state.pathNode.parent?.takeIf { it.depth > 0 }?.statement
        if (previous is Stmt.Check && previous.pc == pc) return
        val registers = List(REGISTER_COUNT) { state.memory.stack.readRegister(it, state.ctx.bv64Sort) }
        state.trace = state.trace.add(TraceEntry(pc, registers))
    }
}
