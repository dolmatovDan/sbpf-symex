package org.sbpf.symex.machine

import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Step
import org.usvm.ps.StateLoopTracker

// Feeds usvm's loop limiter (LoopLimiterPs) with the loops of the program: a state enters a loop when its step is the
// first step of the header block, and an iteration counts as symbolic when the path forked at one of the loop's
// fork points during it.
class SbpfLoopTracker(module: IrModule, graph: SbpfApplicationGraph) : StateLoopTracker<Loop, Step, SbpfState> {
    private val loopsByEntry: Map<Step, Loop> = findLoops(module).associateBy { graph.blockEntry(it.header) }

    override fun findLoopEntrance(statement: Step): Loop? = loopsByEntry[statement]

    override fun isLoopIterationFork(loop: Loop, forkPoint: Step): Boolean = forkPoint in loop.forkPoints
}
