package org.sbpf.symex.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.sbpf.symex.result.PathStatus

// Response of the sbpf-lift run runner (sbpf-ir/docs/runner.md, "Result"). Numbers are u64, hence ULong.
// The machine does not compute compute_units, return_data, accounts and logs yet, so they are not read.
@Serializable
class Outcome(
    val status: PathStatus,
    val error: String? = null,
    // r0–r10 and pc; null if the VM executed no instructions.
    val registers: List<ULong>? = null,
    // r0–r10 and pc before each instruction of the top-level program.
    val trace: List<List<ULong>>? = null,
)

val OutcomeJson = Json { ignoreUnknownKeys = true }
