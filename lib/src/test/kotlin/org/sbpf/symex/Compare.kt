package org.sbpf.symex

import org.sbpf.symex.machine.REGISTER_COUNT
import org.sbpf.symex.result.PathResult
import org.sbpf.symex.result.PathStatus
import org.sbpf.symex.runner.Outcome
import kotlin.test.assertEquals
import kotlin.test.fail

// Compares machine paths with the runner's response, modeled on compareSymbolicAndConcreteResults from TSA
// (tsa-test/.../TestUtils.kt): status and error, then registers (only for a successful path: for a failed one
// they are meaningless), then the trace. The trace is always compared: an intermediate value overwritten
// by a later write to the same register is visible only there.
// Inputs are known, so there is exactly one path.
fun compareWithRunner(paths: List<PathResult>, outcome: Outcome) {
    assertEquals(1, paths.size, "number of paths")
    val path = paths.single()
    assertEquals(outcome.status, path.status, "status")
    assertEquals(outcome.error, path.error, "error")
    val traceDifference = firstTraceDifference(requireNotNull(outcome.trace) { "runner returned no trace" }, path.trace)
    if (path.status == PathStatus.Success) {
        val registers = requireNotNull(outcome.registers) { "runner returned no registers" }
        // The trace shows where the error happened, so its mismatch goes into the same message.
        val message = "registers r0–r10; ${traceDifference ?: "trace matches"}"
        assertEquals(registers.take(REGISTER_COUNT).hex(), path.registers.hex(), message)
    }
    traceDifference?.let { fail(it) }
}

// The first mismatching trace entry, where the error starts; null if the traces match.
private fun firstTraceDifference(expected: List<List<ULong>>, actual: List<List<ULong>>): String? {
    val index = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] }
        ?: if (actual.size > expected.size) expected.size else return null
    val expectedEntry = expected.getOrNull(index)
    val actualEntry = actual.getOrNull(index)
    val difference = when {
        expectedEntry == null -> "extra entry at pc ${actualEntry!!.last()} in machine trace"
        actualEntry == null -> "machine trace ends, runner continues at pc ${expectedEntry.last()}"
        expectedEntry.size != actualEntry.size ->
            "entry of ${actualEntry.size} values, runner has ${expectedEntry.size}"
        else -> expectedEntry.indices.filter { expectedEntry[it] != actualEntry[it] }.joinToString("; ") {
            val name = if (it == REGISTER_COUNT) "pc" else "r$it"
            "$name expected ${expectedEntry[it].hex()}, actual ${actualEntry[it].hex()}"
        }
    }
    val pc = (expectedEntry ?: actualEntry!!).last()
    return "trace entry $index (pc $pc) differs: $difference"
}

private fun ULong.hex() = "0x" + toString(16)

private fun List<ULong>.hex() = map { it.hex() }
