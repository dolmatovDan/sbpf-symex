package org.sbpf.symex

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.sbpf.symex.machine.SbpfMachine
import org.sbpf.symex.runner.SbpfRunner
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

// Машина против раннера на примерах sbpf-ir: тест на каждую пару «пример × версия sBPF», список берётся
// из папки, как runTestsInDirectory в TSA. Новый asm-пример в sbpf-ir сам становится тестом.
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
