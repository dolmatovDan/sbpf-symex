package org.sbpf.symex.machine

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.Fault
import org.sbpf.symex.ir.IrBlock
import org.sbpf.symex.ir.IrFunction
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.RoRegion
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.Width
import org.sbpf.symex.machine.memory.MM_RODATA_V0_START
import org.sbpf.symex.machine.memory.MM_RODATA_V3_START

// IR for machine tests is built with the model's constructors, without JSON.

fun reg(index: Int) = Expr.Reg(index)

fun imm(value: ULong, width: Width = Width.W64) = Expr.Const(value, width)

fun bin(op: BinOp, a: Expr, b: Expr) = Expr.Bin(op, a, b)

fun assign(pc: Int, dst: Int, value: Expr) = Stmt.Assign(pc, dst, value)

fun check(pc: Int, cond: Expr, error: Fault = Fault.DivideByZero) = Stmt.Check(pc, cond, error)

fun load(pc: Int, dst: Int, width: Width, addr: Expr) = Stmt.Load(pc, dst, width, addr)

fun store(pc: Int, width: Width, addr: Expr, value: Expr) = Stmt.Store(pc, width, addr, value)

fun block(start: Int, vararg statements: Stmt, exit: Exit) =
    IrBlock(start, noreturn = false, statements = statements.toList(), exit = exit)

fun function(name: String, vararg blocks: IrBlock) = IrFunction(blocks.first().start, name, blocks.toList())

// rodata starts at its slot for the version (memory-notes.md, 1.4): slot 1 in v0, slot 0 in v3, as the loader maps it.
fun module(
    vararg functions: IrFunction,
    entrypoint: Int = functions.first().entry,
    sbpfVersion: Int = 3,
    rodata: ByteArray = ByteArray(0),
) = IrModule(
    formatVersion = 1,
    sbpfVersion = sbpfVersion,
    entrypoint = entrypoint,
    textVaddr = 0x100000000uL,
    roRegion = RoRegion(if (sbpfVersion == 0) MM_RODATA_V0_START else MM_RODATA_V3_START, rodata),
    addressTaken = emptyList(),
    functions = functions.toList(),
)
