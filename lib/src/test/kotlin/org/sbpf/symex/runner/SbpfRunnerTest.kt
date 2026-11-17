package org.sbpf.symex.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.sbpf.symex.machine.memory.IxMode
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
            example.writeText(JsonObject(mapOf("program" to JsonPrimitive(program.toString()))).toString())
            assertEquals(listOf(3), runner.versionsOf(example))
            assertEquals(program, runner.programOf(example, 3))
            assertFailsWith<IllegalArgumentException> { runner.programOf(example, 0) }
        } finally {
            example.deleteExisting()
        }
    }

    // The input the machine gets is the example's: instruction data from hex, the program id from base58;
    // an example with accounts is rejected until accounts are modeled.
    @Test
    fun inputOfExample() {
        val memory = runner.inputOf(runner.examples.resolve("asm/memory.json"))
        val ix = memory.ix
        assertIs<IxMode.Concrete>(ix)
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte()), ix.bytes)
        assertEquals(0x02, memory.programId[0].toInt())
        assertEquals(0, runner.inputOf(alu64).ix.length)
        val withAccounts = createTempFile(suffix = ".json")
        try {
            withAccounts.writeText(
                """{"program": "x.v0.so", "program_id": "Asm1111111111111111111111111111111111111111",
                   "instruction_data": "", "accounts": []}""",
            )
            assertFailsWith<IllegalArgumentException> { runner.inputOf(withAccounts) }
        } finally {
            withAccounts.deleteExisting()
        }
    }
}
