package org.sbpf.symex.runner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Ответ раннера sbpf-lift run (sbpf-ir/docs/runner.md, «Результат»). Числа — u64, поэтому ULong.
// compute_units, return_data, accounts и logs машина пока не считает, они не читаются.
@Serializable
class Outcome(
    val status: Status,
    val error: String? = null,
    // r0–r10 и pc; null, если VM не исполнила ни одной инструкции.
    val registers: List<ULong>? = null,
    // r0–r10 и pc перед каждой инструкцией программы верхнего уровня.
    val trace: List<List<ULong>>? = null,
)

@Serializable
enum class Status {
    @SerialName("success") Success,
    @SerialName("failure") Failure,
}

val OutcomeJson = Json { ignoreUnknownKeys = true }
