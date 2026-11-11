package org.sbpf.symex.machine

import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Step
import org.sbpf.symex.ir.Stmt
import org.usvm.StepResult
import org.usvm.StepScope
import org.usvm.UBvSort
import org.usvm.UInterpreter
import org.usvm.collections.immutable.internal.MutabilityOwnership
import org.usvm.forkblacklists.UForkBlackList

typealias SbpfStepScope = StepScope<SbpfState, SbpfType, Step, SbpfContext>

// r0–r10.
const val REGISTER_COUNT = 11

// Registers and control flow within the program are executed; memory (load, store), calls and syscalls are not yet.
class SbpfInterpreter(
    private val ctx: SbpfContext,
    private val graph: SbpfApplicationGraph,
    private val forkBlackList: UForkBlackList<SbpfState, Step> = UForkBlackList.createDefault(),
) : UInterpreter<SbpfState>() {
    fun getInitialState(module: IrModule): SbpfState {
        val entry = module.functions.single { it.entry == module.entrypoint }
        return SbpfState(ctx, MutabilityOwnership(), entry).apply {
            memory.stack.push(REGISTER_COUNT)
            // Entry registers are known, so they are constants rather than input symbols.
            entryRegisters().forEachIndexed { index, value ->
                memory.stack.writeRegister(index, ctx.mkBv(value.toLong(), ctx.bv64Sort))
            }
            callStack.push(entry, returnSite = null)
            newStep(graph.entryPoints(entry).single())
            models = listOf(ctx.solver<SbpfType>().emptyModel())
        }
    }

    override fun step(state: SbpfState): StepResult<SbpfState> {
        val scope = SbpfStepScope(state, forkBlackList)
        when (val step = state.lastStep) {
            is Stmt.Assign -> visitAssign(scope, step)
            is Stmt.Check -> visitCheck(scope, step)
            is Exit.Next -> scope.doWithState { newStep(graph.blockEntry(step.target)) }
            is Exit.Branch -> visitBranch(scope, step)
            // There are no calls yet, so return is always from the entry point. The path is alive: it finished, it is not infeasible.
            is Exit.Return -> scope.doWithState { result = SbpfResult.Exited }
            // Like return, the path is alive: it ended with an error, and the error is a result.
            is Exit.Trap -> scope.doWithState { result = SbpfResult.Failed(step.error) }
            is Stmt.Load, is Stmt.Store, is Exit.Call, is Exit.Callx, is Exit.Syscall,
            -> throw NotImplementedError("pc ${step.pc ?: "-"}: ${step::class.simpleName} is not supported yet")
        }
        return scope.stepResult()
    }

    private fun visitAssign(scope: SbpfStepScope, stmt: Stmt.Assign) {
        val value = scope.calcOnState { translator().bv(stmt.value) }
        // writeRegister does not check the sort: without this check the error would surface on read, at an unrelated pc.
        require(value.sort == ctx.bv64Sort) { "pc ${stmt.pc}: assign of ${value.bits} bits to r${stmt.dst}" }
        val next = graph.successors(stmt).single()
        scope.doWithState {
            memory.stack.writeRegister(stmt.dst, value)
            newStep(next)
        }
    }

    // fork, not assert: the path where the condition is false is also a result; it ends with an error.
    // With known inputs the condition is a constant, and there is no fork.
    private fun visitCheck(scope: SbpfStepScope, stmt: Stmt.Check) {
        val condition = scope.calcOnState { translator().bool(stmt.cond) }
        val next = graph.successors(stmt).single()
        scope.fork(
            condition,
            blockOnTrueState = { newStep(next) },
            blockOnFalseState = { result = SbpfResult.Failed(stmt.error) },
        )
    }

    // Each side moves to its own block inside its fork block: code after fork does not know which side the original took.
    // With known inputs the condition is a constant, and only one side is taken.
    private fun visitBranch(scope: SbpfStepScope, exit: Exit.Branch) {
        val thenStep = graph.blockEntry(exit.thenTarget)
        // Both sides lead to the same block: no fork, otherwise a symbolic condition would split one path into two
        // identical ones.
        if (exit.thenTarget == exit.elseTarget) {
            scope.doWithState { newStep(thenStep) }
            return
        }
        val condition = scope.calcOnState { translator().bool(exit.cond) }
        val elseStep = graph.blockEntry(exit.elseTarget)
        scope.fork(
            condition,
            blockOnTrueState = { newStep(thenStep) },
            blockOnFalseState = { newStep(elseStep) },
        )
    }

    // Expression registers are read from the state's current frame.
    private fun SbpfState.translator(): ExprTranslator {
        val bv64: UBvSort = ctx.bv64Sort
        return ExprTranslator(ctx) { memory.stack.readRegister(it, bv64) }
    }
}
