package org.sbpf.symex.machine

import io.ksmt.expr.KBitVec64Value
import io.ksmt.sort.KBv64Sort
import org.sbpf.symex.ir.IrFunction
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.result.PathResult
import org.sbpf.symex.result.toPathResult
import org.usvm.StateCollectionStrategy
import org.usvm.UExpr
import org.usvm.UMachine
import org.usvm.UMachineOptions
import org.usvm.ps.DfsPathSelector
import org.usvm.statistics.CompositeUMachineObserver
import org.usvm.statistics.StepsStatistics
import org.usvm.statistics.collectors.AllStatesCollector
import org.usvm.stopstrategies.StepLimitStopStrategy
import org.usvm.stopstrategies.StopStrategy

// Assembled like SampleMachine and TvmMachine: graph -> components -> context -> solver -> interpreter.
class SbpfMachine(
    private val module: IrModule,
    override val options: UMachineOptions = defaultOptions,
    // Record the per-instruction register trace (SbpfState.trace).
    private val recordTrace: Boolean = false,
) : UMachine<SbpfState>() {
    private val graph = SbpfApplicationGraph(module)
    private val ctx = SbpfContext(SbpfComponents(options))
    private val solver = ctx.solver<SbpfType>()
    private val interpreter = SbpfInterpreter(ctx, graph)

    // Raw finished states, like TvmMachine.analyze.
    fun analyze(): List<SbpfState> {
        val pathSelector = DfsPathSelector<SbpfState>()
        pathSelector.add(listOf(interpreter.getInitialState(module)))

        val statesCollector = AllStatesCollector<SbpfState>()
        // The limit reads the observer's counter: the same object must be among the observers.
        val stepsStatistics = StepsStatistics<IrFunction, SbpfState>()
        val stopStrategy = options.stepLimit?.let { StepLimitStopStrategy(it, stepsStatistics) }
            ?: StopStrategy { false }

        run(
            interpreter = interpreter,
            pathSelector = pathSelector,
            observer = CompositeUMachineObserver(
                listOfNotNull(statesCollector, stepsStatistics, TraceObserver().takeIf { recordTrace }),
            ),
            // The outcome is in the state, not "the current step is return": otherwise the path would end before return executes.
            isStateTerminated = { it.result != null },
            stopStrategy = stopStrategy,
        )
        // Stopping on the step limit loses unfinished paths: this is an error, not a partial result.
        check(pathSelector.isEmpty()) { "step limit ${options.stepLimit} reached, paths are incomplete" }
        return statesCollector.collectedStates
    }

    // Path outcomes in runner terms.
    fun results(): List<PathResult> = analyze().map { it.toPathResult() }

    override fun close() {
        solver.close()
        ctx.close()
    }

    companion object {
        // An exception in a step is a machine error, not a lost path.
        val defaultOptions = UMachineOptions(
            stateCollectionStrategy = StateCollectionStrategy.ALL,
            stepLimit = 100_000uL,
            throwExceptionOnStepFailure = true,
        )
    }
}

// r0–r10 at the end of the path, evaluated in the path's model.
fun SbpfState.registers(): List<ULong> =
    List(REGISTER_COUNT) { valueOf(memory.stack.readRegister(it, ctx.bv64Sort)) }

// Trace in runner format: r0–r10 and pc per entry.
fun SbpfState.traceValues(): List<List<ULong>> =
    trace.map { entry -> entry.registers.map { valueOf(it) } + entry.pc.toULong() }

private fun SbpfState.valueOf(expr: UExpr<KBv64Sort>): ULong {
    val model = requireNotNull(models.firstOrNull()) { "path has no model" }
    return (model.eval(expr) as KBitVec64Value).longValue.toULong()
}
