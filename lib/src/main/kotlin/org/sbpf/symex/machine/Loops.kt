package org.sbpf.symex.machine

import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Step

// A natural loop on the graph of the module's blocks (identified by start pc). The body includes the header.
// forkPoints are the branch exits inside the body whose outcome decides whether the iteration ends: the limiter
// (SbpfLoopTracker) counts an iteration as symbolic only if the path forked at one of them.
class Loop(val header: Int, val body: Set<Int>, val forkPoints: Set<Step>) {
    override fun toString() = "loop at $header, body $body"
}

// A virtual root with an edge to every function entry: functions are reached by calls, which the block graph does
// not contain (a call continues at its return site). Not a block start (those are pcs, non-negative).
private const val ROOT = -2

// Natural loops: a back edge u -> h with h dominating u; the body is h plus everything that reaches u without
// passing through h; loops with the same header are merged. The graph is the whole module's: blocks are unique
// across functions, and a jump into another function (ir.md allows it) is an ordinary edge, so a loop through two
// functions is found too. Edges are the exits' targets (Exit.targets). Irreducible loops (entered in the middle)
// have no back edge in this sense and are not found: there only the step limit holds. This includes a loop inside
// one function whose body (not header) is jumped into from another function: on the module graph the header no
// longer dominates the body, whereas a per-function search would have found it. Accepted: such jumps are rare and
// the step limit still bounds the path.
fun findLoops(module: IrModule): List<Loop> {
    val blocks = module.functions.flatMap { it.blocks }.associateBy { it.start }
    val successors = blocks.mapValues { (_, block) -> block.exit.targets().filter { it in blocks } } +
        (ROOT to module.functions.map { it.entry })
    val reachable = reachableFrom(ROOT, successors)
    val predecessors = reachable.associateWith { mutableListOf<Int>() }
    for (node in reachable) for (next in successors.getValue(node)) predecessors.getValue(next) += node
    val dominators = dominators(reachable, ROOT, predecessors)

    val bodies = linkedMapOf<Int, MutableSet<Int>>()
    for (tail in reachable - ROOT) {
        for (header in successors.getValue(tail)) {
            if (header !in dominators.getValue(tail)) continue
            val body = bodies.getOrPut(header) { mutableSetOf(header) }
            // Everything that reaches the tail without passing through the header.
            val work = ArrayDeque(listOf(tail))
            while (work.isNotEmpty()) {
                val node = work.removeFirst()
                if (!body.add(node)) continue
                work += predecessors.getValue(node)
            }
        }
    }
    val exits = blocks.mapValues { it.value.exit }
    return bodies.map { (header, body) -> Loop(header, body, forkPoints(header, body, exits)) }
}

// A node standing for the end of an iteration: not a block start (those are pcs, non-negative).
private const val ITERATION_EXIT = -1

// Branches that can end the iteration (JcLoopTracker's LoopExecutionPathAnalyzer): in the graph of one iteration,
// where the back edges and the exits from the body all lead to the single ITERATION_EXIT node, a branch block is a
// fork point unless some other block post-dominates it, i.e. both outcomes rejoin before the iteration ends.
private fun forkPoints(header: Int, body: Set<Int>, exits: Map<Int, Exit>): Set<Step> {
    val nodes = body + ITERATION_EXIT
    val successors = nodes.associateWith { node ->
        if (node == ITERATION_EXIT) {
            emptyList()
        } else {
            exits.getValue(node).targets().map { if (it in body && it != header) it else ITERATION_EXIT }.distinct()
        }
    }
    // Post-dominators are dominators of the reversed graph, rooted at the iteration exit.
    val postDominators = dominators(nodes, ITERATION_EXIT, successors)
    return body
        .filter { exits.getValue(it) is Exit.Branch }
        .filter { block -> postDominators.getValue(block).all { it == block || it == ITERATION_EXIT } }
        .map { exits.getValue(it) }
        .toSet()
}

private fun reachableFrom(entry: Int, successors: Map<Int, List<Int>>): Set<Int> {
    val seen = linkedSetOf<Int>()
    val work = ArrayDeque(listOf(entry))
    while (work.isNotEmpty()) {
        val node = work.removeFirst()
        if (!seen.add(node)) continue
        work += successors.getValue(node)
    }
    return seen
}

// Iterative dominators: dom(root) = {root}, dom(n) = {n} ∪ ∩ dom(p) over the predecessors p, to a fixpoint.
// Every node must be reachable from the root, otherwise an empty intersection would make it dominated by everything.
private fun dominators(nodes: Set<Int>, root: Int, predecessors: Map<Int, List<Int>>): Map<Int, Set<Int>> {
    val dominators = nodes.associateWith { if (it == root) setOf(root) else nodes }.toMutableMap()
    var changed = true
    while (changed) {
        changed = false
        for (node in nodes) {
            if (node == root) continue
            val meet = predecessors.getValue(node).map { dominators.getValue(it) }.reduce { a, b -> a intersect b }
            val updated = meet + node
            if (updated != dominators.getValue(node)) {
                dominators[node] = updated
                changed = true
            }
        }
    }
    return dominators
}
