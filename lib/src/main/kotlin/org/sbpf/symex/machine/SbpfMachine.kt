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

// Сборка по образцу SampleMachine и TvmMachine: граф → компоненты → контекст → солвер → интерпретатор.
class SbpfMachine(
    private val module: IrModule,
    override val options: UMachineOptions = defaultOptions,
    // Записывать трассу регистров по инструкциям (SbpfState.trace).
    private val recordTrace: Boolean = false,
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
        val stopStrategy = options.stepLimit?.let { StepLimitStopStrategy(it, stepsStatistics) }
            ?: StopStrategy { false }

        run(
            interpreter = interpreter,
            pathSelector = pathSelector,
            observer = CompositeUMachineObserver(
                listOfNotNull(statesCollector, stepsStatistics, TraceObserver().takeIf { recordTrace }),
            ),
            // Итог в состоянии, а не «текущий шаг — return»: иначе путь закончился бы до исполнения return.
            isStateTerminated = { it.result != null },
            stopStrategy = stopStrategy,
        )
        // Остановка по лимиту шагов теряет незавершённые пути: это ошибка, а не неполный результат.
        check(pathSelector.isEmpty()) { "step limit ${options.stepLimit} reached, paths are incomplete" }
        return statesCollector.collectedStates
    }

    // Итоги путей в терминах раннера.
    fun run(): List<PathResult> = analyze().map { it.toPathResult() }

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
fun SbpfState.registers(): List<ULong> =
    List(REGISTER_COUNT) { valueOf(memory.stack.readRegister(it, ctx.bv64Sort)) }

// Трасса в формате раннера: r0–r10 и pc на каждую запись.
fun SbpfState.traceValues(): List<List<ULong>> =
    trace.map { entry -> entry.registers.map { valueOf(it) } + entry.pc.toULong() }

private fun SbpfState.valueOf(expr: UExpr<KBv64Sort>): ULong {
    val model = requireNotNull(models.firstOrNull()) { "path has no model" }
    return (model.eval(expr) as KBitVec64Value).longValue.toULong()
}
