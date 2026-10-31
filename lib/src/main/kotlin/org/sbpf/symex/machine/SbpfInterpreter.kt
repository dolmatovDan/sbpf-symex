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

// Пока исполняются только assign и return: программы из одного блока без памяти и вызовов.
class SbpfInterpreter(
    private val ctx: SbpfContext,
    private val graph: SbpfApplicationGraph,
    private val forkBlackList: UForkBlackList<SbpfState, Step> = UForkBlackList.createDefault(),
) : UInterpreter<SbpfState>() {
    fun getInitialState(module: IrModule): SbpfState {
        val entry = module.functions.single { it.entry == module.entrypoint }
        return SbpfState(ctx, MutabilityOwnership(), entry).apply {
            memory.stack.push(REGISTER_COUNT)
            callStack.push(entry, returnSite = null)
            newStep(graph.entryPoints(entry).single())
            models = listOf(ctx.solver<SbpfType>().emptyModel())
        }
    }

    override fun step(state: SbpfState): StepResult<SbpfState> {
        val scope = SbpfStepScope(state, forkBlackList)
        when (val step = state.lastStep) {
            is Stmt.Assign -> visitAssign(scope, step)
            // Вызовов пока нет, поэтому return всегда из точки входа. Путь жив: он закончился, а не невозможен.
            is Exit.Return -> scope.doWithState { exited = true }
            is Stmt.Load, is Stmt.Store, is Stmt.Check,
            is Exit.Next, is Exit.Branch, is Exit.Call, is Exit.Callx, is Exit.Syscall, is Exit.Trap,
            -> throw NotImplementedError("${step::class.simpleName} at pc ${step.pc} is not supported yet")
        }
        return scope.stepResult()
    }

    private fun visitAssign(scope: SbpfStepScope, stmt: Stmt.Assign) {
        val value = scope.calcOnState {
            val bv64: UBvSort = ctx.bv64Sort
            ExprTranslator(ctx) { memory.stack.readRegister(it, bv64) }.bv(stmt.value)
        }
        // writeRegister сорт не проверяет: без проверки ошибка всплыла бы при чтении, на чужом pc.
        require(value.sort == ctx.bv64Sort) { "pc ${stmt.pc}: assign of ${value.bits} bits to r${stmt.dst}" }
        val next = graph.successors(stmt).single()
        scope.doWithState {
            memory.stack.writeRegister(stmt.dst, value)
            newStep(next)
        }
    }
}
