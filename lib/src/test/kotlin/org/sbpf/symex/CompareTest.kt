package org.sbpf.symex

import org.sbpf.symex.result.PathResult
import org.sbpf.symex.result.PathStatus
import org.sbpf.symex.runner.Outcome
import org.sbpf.symex.runner.Status
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

// Сама функция сравнения: на настоящих примерах машина и раннер совпадают, и ветки расхождений не исполняются.
class CompareTest {
    private val registers = List(11) { it.toULong() }
    private val trace = listOf(registers + 0uL, registers + 1uL)

    private fun path(
        status: PathStatus = PathStatus.Success,
        trace: List<List<ULong>> = this.trace,
    ) = PathResult(status, null, registers, trace)

    private val outcome = Outcome(Status.Success, registers = registers + 1uL, trace = trace)

    @Test
    fun same() = compareWithRunner(listOf(path()), outcome)

    @Test
    fun twoPaths() {
        assertFailsWith<AssertionError> { compareWithRunner(listOf(path(), path()), outcome) }
    }

    @Test
    fun differentStatus() {
        assertFailsWith<AssertionError> { compareWithRunner(listOf(path(PathStatus.Failure)), outcome) }
    }

    // Итоговые регистры совпадают, расходится промежуточная запись: сообщение называет её pc и регистр.
    @Test
    fun traceDiffers() {
        val wrong = listOf(trace[0].toMutableList().apply { this[3] = 7uL }, trace[1])
        val error = assertFailsWith<AssertionError> { compareWithRunner(listOf(path(trace = wrong)), outcome) }
        assertContains(error.message.orEmpty(), "entry 0 (pc 0)")
        assertContains(error.message.orEmpty(), "r3 expected 0x3, actual 0x7")
    }

    @Test
    fun traceShorter() {
        val error = assertFailsWith<AssertionError> {
            compareWithRunner(listOf(path(trace = trace.take(1))), outcome)
        }
        assertContains(error.message.orEmpty(), "entry 1 (pc 1)")
    }
}
