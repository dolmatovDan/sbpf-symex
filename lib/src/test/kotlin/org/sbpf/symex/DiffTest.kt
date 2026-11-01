package org.sbpf.symex

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.sbpf.symex.machine.SbpfMachine
import org.sbpf.symex.runner.SbpfRunner
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

// Machine vs. runner on sbpf-ir examples: one test per "example × sBPF version" pair; the list is taken
// from the directory, like runTestsInDirectory in TSA. A new asm example in sbpf-ir becomes a test automatically.
class DiffTest {
    private val runner = SbpfRunner.fromSystemProperty()

    @TestFactory
    fun asm(): List<DynamicTest> {
        val examples = runner.examples.resolve("asm").listDirectoryEntries("*.json").sorted()
        require(examples.isNotEmpty()) { "no examples in ${runner.examples.resolve("asm")}" }
        return examples.flatMap { example ->
            listOf(0, 3).map { version ->
                DynamicTest.dynamicTest("${example.nameWithoutExtension} v$version") {
                    val module = runner.lift(runner.programOf(example, version))
                    val paths = SbpfMachine(module, recordTrace = true).use { it.results() }
                    compareWithRunner(paths, runner.run(example, version))
                }
            }
        }
    }
}
