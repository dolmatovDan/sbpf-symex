package org.sbpf.symex.machine

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.IrBlock
import org.sbpf.symex.ir.IrFunction
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.RoRegion
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.Width

// IR для тестов машины собирается конструкторами модели, без JSON.

fun reg(index: Int) = Expr.Reg(index)

fun imm(value: ULong, width: Width = Width.W64) = Expr.Const(value, width)

fun bin(op: BinOp, a: Expr, b: Expr) = Expr.Bin(op, a, b)

fun assign(pc: Int, dst: Int, value: Expr) = Stmt.Assign(pc, dst, value)

fun block(start: Int, vararg statements: Stmt, exit: Exit) =
    IrBlock(start, noreturn = false, statements = statements.toList(), exit = exit)

fun function(name: String, vararg blocks: IrBlock) = IrFunction(blocks.first().start, name, blocks.toList())

fun module(vararg functions: IrFunction, entrypoint: Int = functions.first().entry) = IrModule(
    formatVersion = 1,
    sbpfVersion = 3,
    entrypoint = entrypoint,
    textVaddr = 0x100000000uL,
    roRegion = RoRegion(0uL, ByteArray(0)),
    addressTaken = emptyList(),
    functions = functions.toList(),
)
