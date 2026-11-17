package org.sbpf.symex.machine

import io.ksmt.expr.KBitVec64Value
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Step
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.Width
import org.sbpf.symex.machine.memory.DEFAULT_HEAP_SIZE
import org.sbpf.symex.machine.memory.InputSpec
import org.sbpf.symex.machine.memory.accessCondition
import org.sbpf.symex.machine.memory.initializeMemory
import org.sbpf.symex.machine.memory.loadConst
import org.sbpf.symex.machine.memory.loadSymbolic
import org.sbpf.symex.machine.memory.memoryModel
import org.sbpf.symex.machine.memory.storeConst
import org.sbpf.symex.machine.memory.storeSymbolic
import org.usvm.StepResult
import org.usvm.StepScope
import org.usvm.UBvSort
import org.usvm.UExpr
import org.usvm.UInterpreter
import org.usvm.collections.immutable.internal.MutabilityOwnership
import org.usvm.forkblacklists.UForkBlackList
import org.usvm.solver.USatResult

typealias SbpfStepScope = StepScope<SbpfState, SbpfType, Step, SbpfContext>

// r0–r10.
const val REGISTER_COUNT = 11

// Registers, control flow and memory are executed; calls and syscalls are not yet.
class SbpfInterpreter(
    private val ctx: SbpfContext,
    private val graph: SbpfApplicationGraph,
    private val forkBlackList: UForkBlackList<SbpfState, Step> = UForkBlackList.createDefault(),
) : UInterpreter<SbpfState>() {
    fun getInitialState(
        module: IrModule,
        input: InputSpec = InputSpec.EMPTY,
        heapSize: ULong = DEFAULT_HEAP_SIZE,
    ): SbpfState {
        val entry = module.functions.single { it.entry == module.entrypoint }
        return SbpfState(ctx, MutabilityOwnership(), entry).apply {
            memory.stack.push(REGISTER_COUNT)
            // Entry registers are known, so they are constants rather than input symbols.
            entryRegisters(input).forEachIndexed { index, value ->
                memory.stack.writeRegister(index, ctx.mkBv(value.toLong(), ctx.bv64Sort))
            }
            initializeMemory(module, input, heapSize)
            callStack.push(entry, returnSite = null)
            newStep(graph.entryPoints(entry).single())
            // The instruction data constraints (if any) must be in the model: the trace is evaluated in it.
            val result = ctx.solver<SbpfType>().check(pathConstraints)
            check(result is USatResult) { "initial state is unsatisfiable: $result" }
            models = listOf(result.model)
        }
    }

    override fun step(state: SbpfState): StepResult<SbpfState> {
        val scope = SbpfStepScope(state, forkBlackList)
        when (val step = state.lastStep) {
            is Stmt.Assign -> visitAssign(scope, step)
            is Stmt.Check -> visitCheck(scope, step)
            is Stmt.Load -> visitLoad(scope, step)
            is Stmt.Store -> visitStore(scope, step)
            is Exit.Next -> scope.doWithState { newStep(graph.blockEntry(step.target)) }
            is Exit.Branch -> visitBranch(scope, step)
            // There are no calls yet, so return is always from the entry point. The path is alive: it finished, it is not infeasible.
            is Exit.Return -> scope.doWithState { result = SbpfResult.Exited }
            // Like return, the path is alive: it ended with an error, and the error is a result.
            is Exit.Trap -> scope.doWithState { result = SbpfResult.Failed(step.error) }
            is Exit.Call, is Exit.Callx, is Exit.Syscall,
            -> throw NotImplementedError("pc ${step.pc ?: "-"}: ${step::class.simpleName} is not supported yet")
        }
        return scope.stepResult()
    }

    private fun visitLoad(scope: SbpfStepScope, stmt: Stmt.Load) {
        val addr = scope.calcOnState { address(stmt.addr, stmt.pc) }
        val next = graph.successors(stmt).single()
        memoryAccess(
            scope, addr, stmt.width, store = false,
            onConst = { constAddr ->
                val value = loadConst(constAddr, stmt.width) ?: return@memoryAccess false
                memory.stack.writeRegister(stmt.dst, value)
                newStep(next)
                true
            },
            onSymbolic = {
                memory.stack.writeRegister(stmt.dst, loadSymbolic(addr, stmt.width))
                newStep(next)
            },
        )
    }

    private fun visitStore(scope: SbpfStepScope, stmt: Stmt.Store) {
        val (addr, value) = scope.calcOnState { address(stmt.addr, stmt.pc) to translator().bv(stmt.value) }
        require(value.bits == stmt.width.bits) { "pc ${stmt.pc}: store of ${value.bits} bits as ${stmt.width}" }
        val next = graph.successors(stmt).single()
        memoryAccess(
            scope, addr, stmt.width, store = true,
            onConst = { constAddr -> storeConst(constAddr, value).also { if (it) newStep(next) } },
            onSymbolic = {
                storeSymbolic(addr, value)
                newStep(next)
            },
        )
    }

    // A failing access ends the path with AccessViolation, like a trap: the path is alive, the error is its result.
    // A constant address is checked in Kotlin (onConst returns false on a violation) and takes one side; a symbolic
    // one forks on the bounds condition, and the access itself happens inside the fork block, on the valid side.
    private fun memoryAccess(
        scope: SbpfStepScope,
        addr: UExpr<UBvSort>,
        width: Width,
        store: Boolean,
        onConst: SbpfState.(ULong) -> Boolean,
        onSymbolic: SbpfState.() -> Unit,
    ) {
        val constAddr = addr.constValue()
        if (constAddr != null) {
            scope.doWithState { if (!onConst(constAddr)) result = SbpfResult.AccessViolation }
            return
        }
        val inBounds = scope.calcOnState {
            memoryModel.layout.accessCondition(ctx, addr, width.bits / 8, store)
        }
        scope.fork(
            inBounds,
            blockOnTrueState = { onSymbolic() },
            blockOnFalseState = { result = SbpfResult.AccessViolation },
        )
    }

    private fun SbpfState.address(expr: Expr, pc: Int): UExpr<UBvSort> {
        val addr = translator().bv(expr)
        require(addr.bits == 64) { "pc $pc: address of ${addr.bits} bits" }
        return addr
    }

    private fun UExpr<UBvSort>.constValue(): ULong? = (this as? KBitVec64Value)?.longValue?.toULong()

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
