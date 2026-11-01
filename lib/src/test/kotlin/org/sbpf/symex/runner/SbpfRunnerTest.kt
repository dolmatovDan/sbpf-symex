package org.sbpf.symex.runner

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
}
