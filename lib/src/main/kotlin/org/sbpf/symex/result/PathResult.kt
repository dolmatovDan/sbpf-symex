package org.sbpf.symex.result

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.sbpf.symex.machine.SbpfResult
import org.sbpf.symex.machine.SbpfState
import org.sbpf.symex.machine.registers
import org.sbpf.symex.machine.traceValues

// Значения — как status в ответе раннера: тесты читают его JSON прямо в этот enum.
@Serializable
enum class PathStatus {
    @SerialName("success") Success,
    @SerialName("failure") Failure,
}

// Итог завершённого пути в терминах раннера (sbpf-ir/docs/runner.md): с ним сравнивается ответ Mollusk.
class PathResult(
    val status: PathStatus,
    // Вид ошибки при Failure.
    val error: String?,
    // r0–r10 в конце пути.
    val registers: List<ULong>,
    // r0–r10 и pc перед каждой инструкцией; пусто, если трасса не записывалась.
    val trace: List<List<ULong>>,
)

// Ошибка VM пока называется именем Fault: перевод в InstructionError раннера появится вместе
// с примерами, которые падают. Ненулевой r0 — код ошибки программы, его разбор тоже позже.
fun SbpfState.toPathResult(): PathResult {
    val (status, error) = when (val result = requireNotNull(result) { "path is not finished" }) {
        is SbpfResult.Failed -> PathStatus.Failure to result.fault.name
        SbpfResult.Exited -> {
            val code = registers()[0]
            if (code != 0uL) throw NotImplementedError("exit with r0 = $code is not supported yet")
            PathStatus.Success to null
        }
    }
    return PathResult(status, error, registers(), traceValues())
}
