package org.sbpf.symex.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable(with = WidthSerializer::class)
enum class Width(val bits: Int) {
    W8(8),
    W16(16),
    W32(32),
    W64(64);

    val mask: ULong get() = ULong.MAX_VALUE shr (64 - bits)

    companion object {
        fun ofBits(bits: Int): Width? = entries.find { it.bits == bits }
    }
}

// Имя операции в IR задаёт @SerialName; irName читает его же, чтобы не держать второй копии.

@Serializable
enum class BinOp {
    @SerialName("add") Add,
    @SerialName("sub") Sub,
    @SerialName("mul") Mul,
    @SerialName("and") And,
    @SerialName("or") Or,
    @SerialName("xor") Xor,
    @SerialName("udiv") Udiv,
    @SerialName("urem") Urem,
    @SerialName("shl") Shl,
    @SerialName("lshr") Lshr,
    @SerialName("ashr") Ashr;

    val irName: String get() = serializer().descriptor.getElementName(ordinal)
}

@Serializable
enum class UnOp {
    @SerialName("neg") Neg,
    @SerialName("bswap") Bswap;

    val irName: String get() = serializer().descriptor.getElementName(ordinal)
}

@Serializable
enum class ExtOp {
    @SerialName("zext") Zext,
    @SerialName("sext") Sext,
    @SerialName("trunc") Trunc;

    val irName: String get() = serializer().descriptor.getElementName(ordinal)
}

@Serializable
enum class CmpOp {
    @SerialName("eq") Eq,
    @SerialName("ne") Ne,
    @SerialName("ult") Ult,
    @SerialName("ule") Ule,
    @SerialName("ugt") Ugt,
    @SerialName("uge") Uge,
    @SerialName("slt") Slt,
    @SerialName("sle") Sle,
    @SerialName("sgt") Sgt,
    @SerialName("sge") Sge;

    val irName: String get() = serializer().descriptor.getElementName(ordinal)
}

// `kind` в JSON задаёт вид узла, `op` — операцию внутри вида.
// toString — текстовая запись IR, как у `sbpf-lift ir` (print.rs).
@Serializable
sealed interface Expr {
    @Serializable
    @SerialName("reg")
    class Reg(@SerialName("reg") val index: Int) : Expr {
        override fun toString() = "r$index"
    }

    @Serializable
    @SerialName("const")
    class Const(
        @Serializable(with = HexULongSerializer::class) val value: ULong,
        @SerialName("w") val width: Width,
    ) : Expr {
        override fun toString(): String {
            val text = if (value <= 0xffffu) value.toString() else "0x" + value.toString(16)
            return if (width == Width.W64) text else "$text:${width.bits}"
        }
    }

    @Serializable
    @SerialName("bin")
    class Bin(val op: BinOp, val a: Expr, val b: Expr) : Expr {
        override fun toString() = "${op.irName}($a, $b)"
    }

    @Serializable
    @SerialName("un")
    class Un(val op: UnOp, val a: Expr) : Expr {
        override fun toString() = "${op.irName}($a)"
    }

    @Serializable
    @SerialName("ext")
    class Ext(val op: ExtOp, @SerialName("w") val width: Width, val a: Expr) : Expr {
        override fun toString() = "${op.irName}${width.bits}($a)"
    }

    @Serializable
    @SerialName("cmp")
    class Cmp(val op: CmpOp, val a: Expr, val b: Expr) : Expr {
        override fun toString() = "${op.irName}($a, $b)"
    }
}
