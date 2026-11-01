package org.sbpf.symex.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sbpf.symex.ir.IrJson
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.parseIr
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

// The sbpf-ir lifter and runner (the sbpf-lift binary), run live: the reference the machine is compared against.
// sbpf-lift is built by the Gradle task buildSbpfLift; the path to sbpf-ir comes in the sbpf.ir property.
class SbpfRunner(sbpfIr: Path) {
    private val binary = sbpfIr.resolve("target/release/sbpf-lift")

    // Runner examples: tests/examples/<contract>/*.json.
    val examples: Path = sbpfIr.resolve("tests/examples")

    // Strict parsing: without fixtures this is the only place where tests notice a new lifter field.
    fun lift(program: Path): IrModule =
        parseIr(execute("lift", program.toString()), Json(IrJson) { ignoreUnknownKeys = false })

    fun run(example: Path, version: Int): Outcome = withTempDir { dir ->
        // Paths in an example are relative to its file, but the copy lives in another directory.
        val program = JsonPrimitive(programOf(example, version).toString())
        val copy = JsonObject(readExample(example) + ("program" to program))
        val file = dir.resolve(example.fileName).apply { writeText(copy.toString()) }
        OutcomeJson.decodeFromString<Outcome>(execute("run", file.toString(), "--registers", "--trace"))
    }

    // All examples point to .v0.so; the program for a given version is the sibling .v<version>.so.
    fun programOf(example: Path, version: Int): Path {
        val program = readExample(example).getValue("program").jsonPrimitive.content
        require(program.endsWith(".v0.so")) { "$example: program $program is not .v0.so" }
        return example.absolute().parent.resolve(program.removeSuffix(".v0.so") + ".v$version.so").normalize()
    }

    private fun readExample(example: Path) = Json.parseToJsonElement(example.readText()).jsonObject

    // Output goes to a file (-o), stderr to a file, stdout is discarded: no need to read the streams in parallel
    // as TSA's executeCommandWithTimeout does, and a full pipe buffer won't hang the process. The timeout keeps
    // a hung runner from hanging the tests.
    private fun execute(vararg args: String): String = withTempDir { dir ->
        val output = dir.resolve("output.json")
        val errors = dir.resolve("stderr.txt").toFile()
        val command = listOf(binary.toString(), *args, "-o", output.toString())
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(errors)
            .start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${command.joinToString(" ")}: no answer in $TIMEOUT_SECONDS s")
        }
        check(process.exitValue() == 0) {
            "${command.joinToString(" ")}: exit code ${process.exitValue()}\n${errors.readText()}"
        }
        output.readText()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun <T> withTempDir(block: (Path) -> T): T {
        val dir = createTempDirectory("sbpf-runner")
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 60L

        fun fromSystemProperty() = SbpfRunner(
            Path(requireNotNull(System.getProperty("sbpf.ir")) { "sbpf.ir is not set: run tests via Gradle" }),
        )
    }
}
