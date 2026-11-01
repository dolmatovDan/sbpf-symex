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
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

// Лифтер и раннер sbpf-ir (бинарь sbpf-lift), запускаемые вживую: эталон для сравнения с машиной.
// Собирает sbpf-lift Gradle-задача buildSbpfLift, путь к sbpf-ir приходит в свойстве sbpf.ir.
class SbpfRunner(sbpfIr: Path) {
    private val binary = sbpfIr.resolve("target/release/sbpf-lift")

    // Примеры раннера: tests/examples/<контракт>/*.json.
    val examples: Path = sbpfIr.resolve("tests/examples")

    // Строгий разбор: без фикстур это единственное место, где тесты заметят новое поле лифтера.
    fun lift(program: Path): IrModule =
        parseIr(execute("lift", program.toString()), Json(IrJson) { ignoreUnknownKeys = false })

    fun run(example: Path, version: Int): Outcome = withTempDir { dir ->
        // Пути в примере — относительно его файла, а копия лежит в другой папке.
        val copy = JsonObject(readExample(example) + ("program" to JsonPrimitive(programOf(example, version).toString())))
        val file = dir.resolve(example.fileName).apply { writeText(copy.toString()) }
        OutcomeJson.decodeFromString<Outcome>(execute("run", file.toString(), "--registers", "--trace"))
    }

    // Все примеры указывают на .v0.so; программа под версию — соседний .v<version>.so.
    fun programOf(example: Path, version: Int): Path {
        val program = readExample(example).getValue("program").jsonPrimitive.content
        require(program.endsWith(".v0.so")) { "$example: program $program is not .v0.so" }
        return example.absolute().parent.resolve(program.removeSuffix(".v0.so") + ".v$version.so").normalize()
    }

    private fun readExample(example: Path) = Json.parseToJsonElement(example.readText()).jsonObject

    // Вывод — в файл (-o), stderr — в файл: не нужно читать два потока параллельно, как в TSA
    // executeCommandWithTimeout. Таймаут — чтобы зависший раннер не вешал тесты.
    private fun execute(vararg args: String): String = withTempDir { dir ->
        val output = dir.resolve("output.json")
        val errors = dir.resolve("stderr.txt").toFile()
        val command = listOf(binary.toString(), *args, "-o", output.toString())
        val process = ProcessBuilder(command).redirectError(errors).start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${command.joinToString(" ")}: no answer in $TIMEOUT_SECONDS s")
        }
        check(process.exitValue() == 0) {
            "${command.joinToString(" ")}: exit code ${process.exitValue()}\n${errors.readText()}"
        }
        output.readText()
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
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
