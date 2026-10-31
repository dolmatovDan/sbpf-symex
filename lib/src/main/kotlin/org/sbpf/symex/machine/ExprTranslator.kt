package org.sbpf.symex.machine

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.CmpOp
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.ExtOp
import org.sbpf.symex.ir.UnOp
import org.usvm.UBoolExpr
import org.usvm.UBvSort
import org.usvm.UExpr

// Выражения IR один к одному переводятся в термы KSMT (sbpf-ir/docs/ir.md, «Выражения»).
// readReg отдаёт текущее значение регистра (bv64).
class ExprTranslator(private val ctx: SbpfContext, private val readReg: (Int) -> UExpr<UBvSort>) {
    fun bv(expr: Expr): UExpr<UBvSort> = with(ctx) {
        when (expr) {
            is Expr.Reg -> readReg(expr.index)
            is Expr.Const -> mkBv(expr.value.toLong(), expr.width.bits.toUInt())
            is Expr.Bin -> {
                val (a, b) = sameWidth(expr, expr.a, expr.b)
                when (expr.op) {
                    BinOp.Add -> mkBvAddExpr(a, b)
                    BinOp.Sub -> mkBvSubExpr(a, b)
                    BinOp.Mul -> mkBvMulExpr(a, b)
                    BinOp.And -> mkBvAndExpr(a, b)
                    BinOp.Or -> mkBvOrExpr(a, b)
                    BinOp.Xor -> mkBvXorExpr(a, b)
                    BinOp.Udiv -> mkBvUnsignedDivExpr(a, b)
                    BinOp.Urem -> mkBvUnsignedRemExpr(a, b)
                    BinOp.Shl -> mkBvShiftLeftExpr(a, b)
                    BinOp.Lshr -> mkBvLogicalShiftRightExpr(a, b)
                    BinOp.Ashr -> mkBvArithShiftRightExpr(a, b)
                }
            }
            is Expr.Un -> {
                val a = bv(expr.a)
                when (expr.op) {
                    UnOp.Neg -> mkBvNegationExpr(a)
                    UnOp.Bswap -> bswap(a)
                }
            }
            is Expr.Ext -> {
                val a = bv(expr.a)
                when (expr.op) {
                    ExtOp.Zext -> zextTo(expr.width, a)
                    ExtOp.Sext -> sextTo(expr.width, a)
                    ExtOp.Trunc -> truncTo(expr.width, a)
                }
            }
            is Expr.Cmp -> throw IllegalArgumentException("comparison $expr is bool, not a bitvector")
        }
    }

    fun bool(expr: Expr.Cmp): UBoolExpr = with(ctx) {
        val (a, b) = sameWidth(expr, expr.a, expr.b)
        when (expr.op) {
            CmpOp.Eq -> mkEq(a, b)
            CmpOp.Ne -> mkNot(mkEq(a, b))
            CmpOp.Ult -> mkBvUnsignedLessExpr(a, b)
            CmpOp.Ule -> mkBvUnsignedLessOrEqualExpr(a, b)
            CmpOp.Ugt -> mkBvUnsignedGreaterExpr(a, b)
            CmpOp.Uge -> mkBvUnsignedGreaterOrEqualExpr(a, b)
            CmpOp.Slt -> mkBvSignedLessExpr(a, b)
            CmpOp.Sle -> mkBvSignedLessOrEqualExpr(a, b)
            CmpOp.Sgt -> mkBvSignedGreaterExpr(a, b)
            CmpOp.Sge -> mkBvSignedGreaterOrEqualExpr(a, b)
        }
    }

    // KSMT ширины аргументов не сравнивает: без проверки несовпадение дошло бы до солвера.
    private fun sameWidth(expr: Expr, a: Expr, b: Expr): Pair<UExpr<UBvSort>, UExpr<UBvSort>> {
        val x = bv(a)
        val y = bv(b)
        require(x.bits == y.bits) { "$expr: arguments of ${x.bits} and ${y.bits} bits" }
        return x to y
    }
}
