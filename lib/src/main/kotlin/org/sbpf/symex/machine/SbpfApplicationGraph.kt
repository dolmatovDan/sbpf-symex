package org.sbpf.symex.machine

import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.IrBlock
import org.sbpf.symex.ir.IrFunction
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Step
import org.usvm.statistics.ApplicationGraph

// A step is a statement or a block exit. A statement's successor is the next step of its block; an exit's successors are
// the first steps of the blocks it leads to. Steps are plain classes, so keys are by identity.
class SbpfApplicationGraph(module: IrModule) : ApplicationGraph<IrFunction, Step> {
    private val blocks = module.functions.flatMap { it.blocks }.associateBy { it.start }
    private val functions = module.functions.associateBy { it.entry }

    private val stepFunction = HashMap<Step, IrFunction>()
    private val successors = HashMap<Step, List<Step>>()
    private val callees = HashMap<Step, IrFunction>()

    init {
        for (function in module.functions) {
            for (block in function.blocks) {
                val steps = steps(block)
                for ((step, next) in steps.zipWithNext()) successors[step] = listOf(next)
                successors[block.exit] = exitTargets(block.exit).map { firstStep(block(it)) }
                for (step in steps) stepFunction[step] = function
                (block.exit as? Exit.Call)?.let { callees[it] = function(it.target) }
            }
        }
    }

    private val predecessors = successors.entries
        .flatMap { (step, next) -> next.map { it to step } }
        .groupBy({ it.first }, { it.second })

    private val callers = callees.entries.groupBy({ it.value }, { it.key })

    override fun predecessors(node: Step) = predecessors[node].orEmpty().asSequence()

    override fun successors(node: Step) = successors.getValue(node).asSequence()

    override fun callees(node: Step) = listOfNotNull(callees[node]).asSequence()

    override fun callers(method: IrFunction) = callers[method].orEmpty().asSequence()

    // A function's first block starts at entry.
    override fun entryPoints(method: IrFunction) = sequenceOf(firstStep(block(method.entry)))

    override fun exitPoints(method: IrFunction) =
        method.blocks.asSequence().map { it.exit }.filter { it is Exit.Return || it is Exit.Trap }

    override fun methodOf(node: Step) = stepFunction.getValue(node)

    override fun statementsOf(method: IrFunction) = method.blocks.asSequence().flatMap { steps(it) }

    private fun steps(block: IrBlock): List<Step> = block.statements + block.exit

    // For an empty block, the first step is the exit itself.
    private fun firstStep(block: IrBlock): Step = block.statements.firstOrNull() ?: block.exit

    // Calls continue at the return site: callees tells where the called function goes.
    private fun exitTargets(exit: Exit): List<Int> = when (exit) {
        is Exit.Next -> listOf(exit.target)
        is Exit.Branch -> listOf(exit.thenTarget, exit.elseTarget)
        is Exit.Call -> listOfNotNull(exit.next)
        is Exit.Callx -> listOfNotNull(exit.next)
        is Exit.Syscall -> listOfNotNull(exit.next)
        is Exit.Return, is Exit.Trap -> emptyList()
    }

    private fun block(start: Int) = requireNotNull(blocks[start]) { "no block at pc $start" }

    private fun function(entry: Int) = requireNotNull(functions[entry]) { "no function at pc $entry" }
}
