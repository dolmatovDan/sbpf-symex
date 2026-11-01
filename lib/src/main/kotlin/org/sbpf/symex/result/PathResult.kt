package org.sbpf.symex.result

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.sbpf.symex.machine.SbpfResult
import org.sbpf.symex.machine.SbpfState
import org.sbpf.symex.machine.registers
import org.sbpf.symex.machine.traceValues

// Values match status in the runner's response: tests parse its JSON directly into this enum.
@Serializable
enum class PathStatus {
    @SerialName("success") Success,
    @SerialName("failure") Failure,
}

// Outcome of a finished path in runner terms (sbpf-ir/docs/runner.md): compared against Mollusk's response.
class PathResult(
    val status: PathStatus,
    // Error kind on Failure.
    val error: String?,
    // r0–r10 at the end of the path.
    val registers: List<ULong>,
    // r0–r10 and pc before each instruction; empty if the trace was not recorded.
    val trace: List<List<ULong>>,
)

// For now a VM error is named after its Fault: mapping to the runner's InstructionError will come along
// with failing examples. A nonzero r0 is the program's error code; decoding it also comes later.
fun SbpfState.toPathResult(): PathResult {
    val registers = registers()
    val (status, error) = when (val result = requireNotNull(result) { "path is not finished" }) {
        is SbpfResult.Failed -> PathStatus.Failure to result.fault.name
        SbpfResult.Exited -> {
            val code = registers[0]
            if (code != 0uL) throw NotImplementedError("exit with r0 = $code is not supported yet")
            PathStatus.Success to null
        }
    }
    return PathResult(status, error, registers, traceValues())
}
