package org.sbpf.symex.machine

import io.ksmt.sort.KBv64Sort
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Fault
import org.sbpf.symex.ir.Stmt
import org.usvm.UExpr
import org.usvm.statistics.UMachineObserver

// Registers r0–r10 before the instruction at this pc. Formulas, not numbers: evaluated in the model at the end of the path.
class TraceEntry(val pc: Int, val registers: List<UExpr<KBv64Sort>>)

// Trace as in the runner (sbpf-ir/docs/runner.md): one entry per instruction, holding the state before it.
// Entries live in the state, not here: on a fork the copy inherits the history up to the fork point.
class TraceObserver : UMachineObserver<SbpfState> {
    override fun onStatePeeked(state: SbpfState) {
        val step = state.lastStep
        // next without pc is not an instruction.
        val pc = step.pc ?: return
        // The VM throws ExecutionOverrun before recording the trace (solana-sbpf 0.23.0, interpreter.rs:192-201):
        // there is no instruction at this pc. UnsupportedInstruction is thrown after recording, so it gets an entry.
        if (step is Exit.Trap && step.error == Fault.ExecutionOverrun) return
        // The entry is made on the instruction's first step. A check precedes its instruction with the same pc,
        // so the check makes the entry, and when a check fails the instruction the path failed on is in the trace.
        // The pathNode root (depth 0) has no step.
        val previous = state.pathNode.parent?.takeIf { it.depth > 0 }?.statement
        if (previous is Stmt.Check && previous.pc == pc) return
        val registers = List(REGISTER_COUNT) { state.memory.stack.readRegister(it, state.ctx.bv64Sort) }
        state.trace = state.trace.add(TraceEntry(pc, registers))
    }
}
