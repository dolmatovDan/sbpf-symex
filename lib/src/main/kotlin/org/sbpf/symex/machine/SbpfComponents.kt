package org.sbpf.symex.machine

import io.ksmt.solver.yices.KYicesSolver
import org.usvm.UBv32SizeExprProvider
import org.usvm.UBv32Sort
import org.usvm.UComponents
import org.usvm.UContext
import org.usvm.UMachineOptions
import org.usvm.USizeExprProvider
import org.usvm.solver.USolverBase
import org.usvm.solver.UTypeSolver
import org.usvm.types.UTypeStream
import org.usvm.types.UTypeSystem
import org.usvm.types.emptyTypeStream
import kotlin.time.Duration

// Размерный сорт usvm (длины массивов, адреса объектов). Пока не используется: его выберет модель памяти.
typealias SbpfSizeSort = UBv32Sort

// Объектов и типов в sBPF нет: система типов — заглушка, как SampleTypeSystem.
object SbpfType

class SbpfTypeSystem(override val typeOperationsTimeout: Duration) : UTypeSystem<SbpfType> {
    override fun isSupertype(supertype: SbpfType, type: SbpfType) = true

    override fun isFinal(type: SbpfType) = true

    override fun hasCommonSubtype(type: SbpfType, types: Collection<SbpfType>) = true

    override fun isInstantiable(type: SbpfType) = true

    override fun findSubtypes(type: SbpfType): Sequence<SbpfType> = emptySequence()

    override fun topTypeStream(): UTypeStream<SbpfType> = emptyTypeStream()
}

// Солвер — только Yices: options.solverType не читается, Z3 в зависимостях нет.
class SbpfComponents(private val options: UMachineOptions) : UComponents<SbpfType, SbpfSizeSort> {
    private val typeSystem = SbpfTypeSystem(options.typeOperationsTimeout)

    override val useSolverForForks: Boolean get() = options.useSolverForForks

    override fun <Context : UContext<SbpfSizeSort>> mkSolver(ctx: Context): USolverBase<SbpfType> {
        val (translator, decoder) = buildTranslatorAndLazyDecoder(ctx)
        return USolverBase(ctx, KYicesSolver(ctx), UTypeSolver(typeSystem), translator, decoder, options.solverTimeout)
    }

    override fun mkTypeSystem(ctx: UContext<SbpfSizeSort>): UTypeSystem<SbpfType> = typeSystem

    override fun <Context : UContext<SbpfSizeSort>> mkSizeExprProvider(ctx: Context): USizeExprProvider<SbpfSizeSort> =
        UBv32SizeExprProvider(ctx)
}
