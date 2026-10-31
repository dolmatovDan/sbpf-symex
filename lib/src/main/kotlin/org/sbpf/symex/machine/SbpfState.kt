package org.sbpf.symex.machine

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

// Регистры r0–r10 — в кадрах memory.stack (sbpf-ir/docs/ir.md, «call и return»).
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
    // Исполнен exit точки входа. Это не «путь успешен»: итог (r0 как код возврата, проверки аккаунтов
    // рантаймом после exit) — будущая постобработка, когда появятся память и аккаунты.
    var exited: Boolean = false,
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
    // Новый жетон получают обе стороны: со старым оригинал менял бы на месте узлы, общие с копией.
    override fun clone(newConstraints: UPathConstraints<SbpfType>?): SbpfState {
        val newThisOwnership = MutabilityOwnership()
        val cloneOwnership = MutabilityOwnership()
        val clonedConstraints = newConstraints?.also {
            this.pathConstraints.changeOwnership(newThisOwnership)
            it.changeOwnership(cloneOwnership)
        } ?: pathConstraints.clone(newThisOwnership, cloneOwnership)
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
            targets,
            exited,
        )
    }

    // Ошибок пока нет: check, trap и память появятся в следующих блоках.
    override val isExceptional: Boolean get() = false
}

val SbpfState.lastStep: Step get() = pathNode.statement

fun SbpfState.newStep(step: Step) {
    pathNode += step
}
