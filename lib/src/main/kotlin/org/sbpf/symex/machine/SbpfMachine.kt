package org.sbpf.symex.machine

import io.ksmt.expr.KBitVec64Value
import org.sbpf.symex.ir.IrFunction
import org.sbpf.symex.ir.IrModule
import org.usvm.StateCollectionStrategy
import org.usvm.UMachine
import org.usvm.UMachineOptions
import org.usvm.ps.DfsPathSelector
import org.usvm.statistics.CompositeUMachineObserver
import org.usvm.statistics.StepsStatistics
import org.usvm.statistics.collectors.AllStatesCollector
import org.usvm.stopstrategies.StepLimitStopStrategy
import org.usvm.stopstrategies.StopStrategy

// Сборка по образцу SampleMachine и TvmMachine: граф → компоненты → контекст → солвер → интерпретатор.
class SbpfMachine(
    private val module: IrModule,
    override val options: UMachineOptions = defaultOptions,
) : UMachine<SbpfState>() {
    private val graph = SbpfApplicationGraph(module)
    private val ctx = SbpfContext(SbpfComponents(options))
    private val solver = ctx.solver<SbpfType>()
    private val interpreter = SbpfInterpreter(ctx, graph)

    // Сырые завершённые состояния, как TvmMachine.analyze.
    fun analyze(): List<SbpfState> {
        val pathSelector = DfsPathSelector<SbpfState>()
        pathSelector.add(listOf(interpreter.getInitialState(module)))

        val statesCollector = AllStatesCollector<SbpfState>()
        // Лимит читает счётчик наблюдателя: тот же объект обязан быть среди наблюдателей.
        val stepsStatistics = StepsStatistics<IrFunction, SbpfState>()
        val stopStrategy = options.stepLimit?.let { StepLimitStopStrategy(it, stepsStatistics) } ?: StopStrategy { false }

        run(
            interpreter = interpreter,
            pathSelector = pathSelector,
            observer = CompositeUMachineObserver(statesCollector, stepsStatistics),
            // Флаг, а не «текущий шаг — return»: иначе путь закончился бы до исполнения return.
            isStateTerminated = { it.exited },
            stopStrategy = stopStrategy,
        )
        return statesCollector.collectedStates
    }

    override fun close() {
        solver.close()
        ctx.close()
    }

    companion object {
        // Исключение в шаге — ошибка машины, а не потерянный путь.
        val defaultOptions = UMachineOptions(
            stateCollectionStrategy = StateCollectionStrategy.ALL,
            stepLimit = 100_000uL,
            throwExceptionOnStepFailure = true,
        )
    }
}

// r0–r10 в конце пути, вычисленные в модели пути.
fun SbpfState.registers(): List<ULong> {
    val model = models.first()
    return (0 until REGISTER_COUNT).map { index ->
        val value = model.eval(memory.stack.readRegister(index, ctx.bv64Sort)) as KBitVec64Value
        value.longValue.toULong()
    }
}
