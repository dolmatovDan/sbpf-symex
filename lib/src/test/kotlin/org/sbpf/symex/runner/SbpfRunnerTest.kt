package org.sbpf.symex.runner

import kotlin.test.Test
import kotlin.test.assertEquals

// Only our wrapper is tested here: the runner's own behavior is covered by sbpf-ir tests (asm, registers_and_trace).
class SbpfRunnerTest {
    private val runner = SbpfRunner.fromSystemProperty()
    private val alu64 = runner.examples.resolve("asm/alu64.json")

    // The example points to .v0.so: for v3 the lifter must receive a different file.
    @Test
    fun programOfVersion() {
        for (version in listOf(0, 3)) {
            assertEquals(version, runner.lift(runner.programOf(alu64, version)).sbpfVersion)
        }
    }
}
