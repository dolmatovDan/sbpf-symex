package org.sbpf.symex.runner

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        assertEquals(listOf(0, 3), runner.versionsOf(alu64))
    }

    // An example on .v3.so runs only on v3. It is written here so the test does not depend on sbpf-ir having one.
    @Test
    fun programOfV3Only() {
        val program = runner.programOf(alu64, 3)
        val example = createTempFile(suffix = ".json")
        try {
            example.writeText("""{"program": "$program"}""")
            assertEquals(listOf(3), runner.versionsOf(example))
            assertEquals(program, runner.programOf(example, 3))
            assertFailsWith<IllegalArgumentException> { runner.programOf(example, 0) }
        } finally {
            example.deleteExisting()
        }
    }
}
