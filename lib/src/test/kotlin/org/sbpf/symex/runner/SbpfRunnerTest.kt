package org.sbpf.symex.runner

import org.sbpf.symex.machine.entryRegisters
import kotlin.test.Test
import kotlin.test.assertEquals

// Проверяется только наша обвязка: поведение самого раннера проверяют тесты sbpf-ir (asm, registers_and_trace).
class SbpfRunnerTest {
    private val runner = SbpfRunner.fromSystemProperty()
    private val alu64 = runner.examples.resolve("asm/alu64.json")

    // Пример указывает на .v0.so: под v3 лифтер должен получить другой файл.
    @Test
    fun programOfVersion() {
        for (version in listOf(0, 3)) {
            assertEquals(version, runner.lift(runner.programOf(alu64, version)).sbpfVersion)
        }
    }

    // Первая запись трассы раннера — регистры на входе: константы Entry.kt совпадают с VM.
    @Test
    fun entryRegistersMatchVm() {
        for (version in listOf(0, 3)) {
            val trace = requireNotNull(runner.run(alu64, version).trace)
            assertEquals(entryRegisters() + 0uL, trace.first(), "v$version")
        }
    }
}
