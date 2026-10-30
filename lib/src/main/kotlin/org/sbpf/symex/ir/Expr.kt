package org.sbpf.symex.ir

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

enum class BinOp(val irName: String) {
    Add("add"),
    Sub("sub"),
    Mul("mul"),
    And("and"),
    Or("or"),
    Xor("xor"),
    Udiv("udiv"),
    Urem("urem"),
    Shl("shl"),
    Lshr("lshr"),
    Ashr("ashr"),
}

enum class UnOp(val irName: String) {
    Neg("neg"),
    Bswap("bswap"),
}

enum class ExtOp(val irName: String) {
    Zext("zext"),
    Sext("sext"),
    Trunc("trunc"),
}

enum class CmpOp(val irName: String) {
    Eq("eq"),
    Ne("ne"),
    Ult("ult"),
    Ule("ule"),
    Ugt("ugt"),
    Uge("uge"),
    Slt("slt"),
    Sle("sle"),
    Sgt("sgt"),
    Sge("sge"),
}

// toString — текстовая запись IR, как у `sbpf-lift ir` (print.rs).
@Serializable(with = ExprSerializer::class)
sealed interface Expr {
    class Reg(val index: Int) : Expr {
        override fun toString() = "r$index"
    }

    class Const(val value: ULong, val width: Width) : Expr {
        override fun toString(): String {
            val text = if (value <= 0xffffu) value.toString() else "0x" + value.toString(16)
            return if (width == Width.W64) text else "$text:${width.bits}"
        }
    }

    class Bin(val op: BinOp, val a: Expr, val b: Expr) : Expr {
        override fun toString() = "${op.irName}($a, $b)"
    }

    class Un(val op: UnOp, val a: Expr) : Expr {
        override fun toString() = "${op.irName}($a)"
    }

    class Ext(val op: ExtOp, val width: Width, val a: Expr) : Expr {
        override fun toString() = "${op.irName}${width.bits}($a)"
    }

    class Cmp(val op: CmpOp, val a: Expr, val b: Expr) : Expr {
        override fun toString() = "${op.irName}($a, $b)"
    }
}
