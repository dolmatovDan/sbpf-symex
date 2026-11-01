package org.sbpf.symex.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Format and semantics: sbpf-ir/docs/ir.md. Classes are plain, not data: identical statements
// in different places of the program are distinct steps and distinct graph keys.

@Serializable
class IrModule(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("sbpf_version") val sbpfVersion: Int,
    val entrypoint: Int,
    @SerialName("text_vaddr") @Serializable(with = HexULongSerializer::class) val textVaddr: ULong,
    @SerialName("ro_region") val roRegion: RoRegion,
    @SerialName("address_taken") val addressTaken: List<Int>,
    val functions: List<IrFunction>,
)

@Serializable
class RoRegion(
    @Serializable(with = HexULongSerializer::class) val vaddr: ULong,
    @Serializable(with = Base64Serializer::class) val data: ByteArray,
)

@Serializable
class IrFunction(
    val entry: Int,
    val name: String,
    val blocks: List<IrBlock>,
)

@Serializable
class IrBlock(
    val start: Int,
    val noreturn: Boolean,
    val statements: List<Stmt>,
    val exit: Exit,
)

@Serializable
enum class Fault {
    DivideByZero,
    UnsupportedInstruction,
    ExecutionOverrun,
}

// A machine step: a statement or a block exit. It is absent from JSON; it is the common node type of the program graph.
// Only a `next` exit without a `ja` instruction has no pc.
sealed interface Step {
    val pc: Int?
}

@Serializable
sealed interface Stmt : Step {
    override val pc: Int

    @Serializable
    @SerialName("assign")
    class Assign(override val pc: Int, val dst: Int, val value: Expr) : Stmt

    @Serializable
    @SerialName("load")
    class Load(override val pc: Int, val dst: Int, @SerialName("w") val width: Width, val addr: Expr) : Stmt

    @Serializable
    @SerialName("store")
    class Store(override val pc: Int, @SerialName("w") val width: Width, val addr: Expr, val value: Expr) : Stmt

    @Serializable
    @SerialName("check")
    class Check(override val pc: Int, val cond: Expr, val error: Fault) : Stmt
}

// `next` of a call is null if the call is the last one in the program; the field is still required.
@Serializable
sealed interface Exit : Step {
    // Without `pc`, falls through to the next block; with `pc`, it is a `ja` instruction.
    @Serializable
    @SerialName("next")
    class Next(val target: Int, override val pc: Int? = null) : Exit

    @Serializable
    @SerialName("branch")
    class Branch(
        override val pc: Int,
        val cond: Expr,
        @SerialName("then") val thenTarget: Int,
        @SerialName("else") val elseTarget: Int,
    ) : Exit

    @Serializable
    @SerialName("call")
    class Call(override val pc: Int, val target: Int, val next: Int?) : Exit

    @Serializable
    @SerialName("callx")
    class Callx(override val pc: Int, val reg: Int, val next: Int?) : Exit

    @Serializable
    @SerialName("syscall")
    class Syscall(override val pc: Int, val hash: UInt, val name: String?, val next: Int?) : Exit

    @Serializable
    @SerialName("return")
    class Return(override val pc: Int) : Exit

    @Serializable
    @SerialName("trap")
    class Trap(override val pc: Int, val error: Fault) : Exit
}
