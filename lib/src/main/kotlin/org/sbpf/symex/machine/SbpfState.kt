package org.sbpf.symex.machine

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import org.sbpf.symex.ir.Fault
import org.sbpf.symex.ir.IrFunction
import org.sbpf.symex.ir.Step
import org.usvm.PathNode
import org.usvm.UCallStack
import org.usvm.UState
import org.usvm.collections.immutable.internal.MutabilityOwnership
import org.usvm.constraints.UPathConstraints
import org.usvm.memory.UMemory
import org.usvm.model.UModelBase
import org.usvm.targets.UTarget
import org.usvm.targets.UTargetsSet

abstract class SbpfTarget(location: Step) : UTarget<Step, SbpfTarget>(location)

// Registers r0–r10 live in memory.stack frames (sbpf-ir/docs/ir.md, "call and return").
class SbpfState(
    ctx: SbpfContext,
    ownership: MutabilityOwnership,
    override val entrypoint: IrFunction,
    callStack: UCallStack<IrFunction, Step> = UCallStack(),
    pathConstraints: UPathConstraints<SbpfType> = UPathConstraints(ctx, ownership),
    memory: UMemory<SbpfType, IrFunction> = UMemory(ctx, ownership, pathConstraints.typeConstraints),
    models: List<UModelBase<SbpfType>> = listOf(),
    pathNode: PathNode<Step> = PathNode.root(),
    forkPoints: PathNode<PathNode<Step>> = PathNode.root(),
    targets: UTargetsSet<SbpfTarget, Step> = UTargetsSet.empty(),
    // How the path ended; null means the path is still running.
    var result: SbpfResult? = null,
    // Written by TraceObserver if tracing is enabled. Immutable list: the copy shares it with the original.
    var trace: PersistentList<TraceEntry> = persistentListOf(),
) : UState<SbpfType, IrFunction, Step, SbpfContext, SbpfTarget, SbpfState>(
    ctx,
    ownership,
    callStack,
    pathConstraints,
    memory,
    models,
    pathNode,
    forkPoints,
    targets,
) {
    // Both sides get a new ownership token: with the old one the original would mutate in place nodes shared with the copy.
    // The original's token is also updated in the state itself, as in JcState (SampleState lacks this): on rollback
    // of a failed fork StateForker restores state.ownership to the path constraints.
    override fun clone(newConstraints: UPathConstraints<SbpfType>?): SbpfState {
        val newThisOwnership = MutabilityOwnership()
        val cloneOwnership = MutabilityOwnership()
        val clonedConstraints = newConstraints?.also {
            this.pathConstraints.changeOwnership(newThisOwnership)
            it.changeOwnership(cloneOwnership)
        } ?: pathConstraints.clone(newThisOwnership, cloneOwnership)
        this.ownership = newThisOwnership
        return SbpfState(
            ctx,
            cloneOwnership,
            entrypoint,
            callStack.clone(),
            clonedConstraints,
            memory.clone(clonedConstraints.typeConstraints, newThisOwnership, cloneOwnership),
            models,
            pathNode,
            forkPoints,
            targets.clone(),
            result,
            trace,
        )
    }

    override val isExceptional: Boolean get() = result is SbpfResult.Failed
}

// One field for the path outcome: "finished" and "failed" are mutually exclusive, like JcMethodResult in JcState.
sealed interface SbpfResult {
    // The entry point's exit was executed. This does not mean "the path succeeded": the outcome (r0 as return code, account checks
    // by the runtime after exit) is future post-processing, once memory and accounts exist.
    data object Exited : SbpfResult

    // The path ended with a VM error: a check failed or a trap was reached.
    data class Failed(val fault: Fault) : SbpfResult
}

val SbpfState.lastStep: Step get() = pathNode.statement

fun SbpfState.newStep(step: Step) {
    pathNode += step
}
