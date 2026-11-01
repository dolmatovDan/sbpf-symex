package org.sbpf.symex

import org.sbpf.symex.machine.REGISTER_COUNT
import org.sbpf.symex.result.PathResult
import org.sbpf.symex.result.PathStatus
import org.sbpf.symex.runner.Outcome
import org.sbpf.symex.runner.Status
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

// Сама функция сравнения: на настоящих примерах машина и раннер совпадают, и ветки расхождений не исполняются.
class CompareTest {
    private val registers = List(REGISTER_COUNT) { it.toULong() }
    private val trace = listOf(registers + 0uL, registers + 1uL)
    private val outcome = Outcome(Status.Success, registers = registers + 1uL, trace = trace)

    private fun path(
        status: PathStatus = PathStatus.Success,
        error: String? = null,
        trace: List<List<ULong>> = this.trace,
    ) = PathResult(status, error, registers, trace)

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

    // Лишняя запись машины — например, вторая запись на инструкцию после check.
    @Test
    fun traceLonger() {
        val error = assertFailsWith<AssertionError> {
            compareWithRunner(listOf(path(trace = trace + listOf(registers + 2uL))), outcome)
        }
        assertContains(error.message.orEmpty(), "entry 2 (pc 2)")
        assertContains(error.message.orEmpty(), "extra entry at pc 2")
    }

    @Test
    fun differentError() {
        val failure = Outcome(Status.Failure, error = "ProgramFailedToComplete", trace = trace)
        assertFailsWith<AssertionError> {
            compareWithRunner(listOf(path(PathStatus.Failure, error = "DivideByZero")), failure)
        }
    }
}
