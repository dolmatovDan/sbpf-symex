package org.sbpf.symex

import org.sbpf.symex.machine.REGISTER_COUNT
import org.sbpf.symex.result.PathResult
import org.sbpf.symex.result.PathStatus
import org.sbpf.symex.runner.Outcome
import kotlin.test.assertEquals
import kotlin.test.fail

// Сравнение путей машины с ответом раннера, по образцу compareSymbolicAndConcreteResults из TSA
// (tsa-test/.../TestUtils.kt): статус и ошибка, затем регистры (только у успешного пути: у упавшего
// они бессмысленны), затем трасса. Трасса сравнивается всегда: промежуточное значение, перекрытое
// следующей записью в тот же регистр, видно только в ней.
// Входы известны, поэтому путь ровно один.
fun compareWithRunner(paths: List<PathResult>, outcome: Outcome) {
    assertEquals(1, paths.size, "number of paths")
    val path = paths.single()
    assertEquals(outcome.status, path.status, "status")
    assertEquals(outcome.error, path.error, "error")
    val traceDifference = firstTraceDifference(requireNotNull(outcome.trace) { "runner returned no trace" }, path.trace)
    if (path.status == PathStatus.Success) {
        val registers = requireNotNull(outcome.registers) { "runner returned no registers" }
        // Место ошибки показывает трасса, поэтому её расхождение — в том же сообщении.
        val message = "registers r0–r10; ${traceDifference ?: "trace matches"}"
        assertEquals(registers.take(REGISTER_COUNT).hex(), path.registers.hex(), message)
    }
    traceDifference?.let { fail(it) }
}

// Первая расходящаяся запись трассы — с неё начинается ошибка; null, если трассы совпадают.
private fun firstTraceDifference(expected: List<List<ULong>>, actual: List<List<ULong>>): String? {
    val index = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] }
        ?: if (actual.size > expected.size) expected.size else return null
    val expectedEntry = expected.getOrNull(index)
    val actualEntry = actual.getOrNull(index)
    val difference = when {
        expectedEntry == null -> "extra entry at pc ${actualEntry!!.last()} in machine trace"
        actualEntry == null -> "machine trace ends, runner continues at pc ${expectedEntry.last()}"
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
