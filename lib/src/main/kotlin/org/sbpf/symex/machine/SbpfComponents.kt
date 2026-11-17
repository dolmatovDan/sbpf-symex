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
import org.usvm.types.USingleTypeStream
import org.usvm.types.UTypeStream
import org.usvm.types.UTypeSystem
import kotlin.time.Duration

// usvm size sort: the index of the byte memory array, i.e. the key of machine/memory/MemoryLayout.kt.
typealias SbpfSizeSort = UBv32Sort

// sBPF has no objects or types: the type system is a stub, like SampleTypeSystem.
object SbpfType

class SbpfTypeSystem(override val typeOperationsTimeout: Duration) : UTypeSystem<SbpfType> {
    override fun isSupertype(supertype: SbpfType, type: SbpfType) = true

    override fun isFinal(type: SbpfType) = true

    override fun hasCommonSubtype(type: SbpfType, types: Collection<SbpfType>) = true

    override fun isInstantiable(type: SbpfType) = true

    override fun findSubtypes(type: SbpfType): Sequence<SbpfType> = emptySequence()

    // The single type, as TvmTypeSystem does: the type solver checks every static reference (the input array of the
    // memory model) against this stream, and an empty one makes every state unsatisfiable.
    private val topTypeStream = USingleTypeStream(this, SbpfType)

    override fun topTypeStream(): UTypeStream<SbpfType> = topTypeStream
}

// The solver is Yices only: options.solverType is not read, and Z3 is not among the dependencies.
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
