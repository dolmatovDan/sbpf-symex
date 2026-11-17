package org.sbpf.symex.machine.memory

import io.ksmt.expr.KBitVec32Value
import io.ksmt.expr.KBitVec64Value
import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.Expr
import org.sbpf.symex.ir.ExtOp
import org.sbpf.symex.ir.RoRegion
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.Width
import org.sbpf.symex.machine.SbpfComponents
import org.sbpf.symex.machine.SbpfContext
import org.sbpf.symex.machine.SbpfMachine
import org.sbpf.symex.machine.SbpfResult
import org.sbpf.symex.machine.SbpfState
import org.sbpf.symex.machine.SbpfType
import org.sbpf.symex.machine.assign
import org.sbpf.symex.machine.bin
import org.sbpf.symex.machine.block
import org.sbpf.symex.machine.function
import org.sbpf.symex.machine.imm
import org.sbpf.symex.machine.load
import org.sbpf.symex.machine.module
import org.sbpf.symex.machine.reg
import org.sbpf.symex.machine.store
import org.usvm.UBvSort
import org.usvm.UExpr
import org.usvm.UMachineOptions
import org.usvm.solver.USatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Loads and stores whose address depends on the (symbolic) instruction data: one fork on the bounds condition,
// the access itself goes through the byte array with a symbolic key.
class SymbolicAddressTest {
    private val programId = ByteArray(32) { (it + 1).toByte() }

    private fun symbolicIx(length: Int) = InputSpec(programId, IxMode.Symbolic(length))

    private fun program(vararg statements: Stmt, rodata: ByteArray = ByteArray(0)) = module(
        function("entrypoint", block(0, *statements, exit = Exit.Return(statements.size))),
        rodata = rodata,
    )

    private fun SbpfState.reg(index: Int): UExpr<UBvSort> = memory.stack.readRegister(index, ctx.bv64Sort)

    private fun SbpfState.value(index: Int): ULong {
        val model = models.single()
        return (model.eval(reg(index)) as KBitVec64Value).longValue.toULong()
    }

    // The two implementations of the key agree on every region boundary.
    @Test
    fun symbolicKeyMatchesKey() {
        val ctx = SbpfContext(SbpfComponents(UMachineOptions()))
        val layout = MemoryLayout(0, RoRegion(MM_RODATA_V0_START + 0x120uL, ByteArray(216)), 64uL)
        ctx.use {
            for (region in layout.regions) {
                for (addr in listOf(region.start, region.end - 1uL)) {
                    val symbolic = ctx.symbolicKey(ctx.mkBv(addr.toLong(), 64u)) as KBitVec32Value
                    assertEquals(key(addr), symbolic.intValue, "key of 0x${addr.toString(16)}")
                }
            }
        }
    }

    // ldxb r3, [r2]; ldxb r4, [r1 + r3]: the byte of the input selects an offset, and only offsets inside the
    // input are valid. Two paths: the load and the AccessViolation, each with a model that shows why.
    @Test
    fun loadForksOnBounds() {
        val input = symbolicIx(1)
        val ir = program(
            load(0, 3, Width.W8, reg(2)),
            load(1, 4, Width.W8, bin(BinOp.Add, reg(1), reg(3))),
        )
        SbpfMachine(ir, input = input).use { machine ->
            val states = machine.analyze()
            assertEquals(2, states.size)
            val ok = states.single { it.result == SbpfResult.Exited }
            val violation = states.single { it.result == SbpfResult.AccessViolation }
            assertTrue(ok.value(3) < input.size.toULong(), "offset ${ok.value(3)} is inside the input")
            assertTrue(violation.value(3) >= input.size.toULong(), "offset ${violation.value(3)} is past the input")
        }
    }

    // A symbolic offset into rodata: the byte read in the model is the byte of rodata at the model's offset, so
    // rodata is really copied into the byte array.
    @Test
    fun loadFromRodataBySymbolicOffset() {
        val rodata = byteArrayOf(10, 20, 30, 40)
        val ir = program(
            load(0, 3, Width.W8, reg(2)),
            assign(1, 4, bin(BinOp.And, reg(3), imm(3uL))),
            load(2, 5, Width.W8, bin(BinOp.Add, imm(MM_RODATA_V3_START), reg(4))),
            rodata = rodata,
        )
        SbpfMachine(ir, input = symbolicIx(1)).use { machine ->
            val state = machine.analyze().single()
            assertEquals(SbpfResult.Exited, state.result)
            val offset = state.value(4).toInt()
            assertEquals(rodata[offset].toULong(), state.value(5), "byte at offset $offset")
        }
    }

    // A symbolic byte stored at a symbolic stack address a, then a constant load of X: equal to the stored byte
    // when a == X, and still zero when a is elsewhere.
    @Test
    fun storeBySymbolicAddressThenConstantLoad() {
        val x = MM_STACK_START + 0x100uL
        val ir = program(
            load(0, 3, Width.W8, reg(2)),
            load(1, 4, Width.W8, bin(BinOp.Add, reg(2), imm(1uL))),
            assign(2, 4, bin(BinOp.And, reg(4), imm(7uL))),
            assign(3, 5, bin(BinOp.Add, reg(4), imm(x))),
            store(4, Width.W8, reg(5), Expr.Ext(ExtOp.Trunc, Width.W8, reg(3))),
            load(5, 6, Width.W8, imm(x)),
        )
        SbpfMachine(ir, input = symbolicIx(2)).use { machine ->
            val state = machine.analyze().single()
            val ctx = state.ctx
            val solver = ctx.solver<SbpfType>()
            fun modelWith(address: ULong) = state.clone().let { copy ->
                copy.pathConstraints += ctx.mkEq(copy.reg(5), ctx.mkBv(address.toLong(), 64u))
                val result = solver.check(copy.pathConstraints)
                check(result is USatResult) { "a == 0x${address.toString(16)} is infeasible" }
                result.model
            }
            val hit = modelWith(x)
            assertEquals(hit.eval(state.reg(3)), hit.eval(state.reg(6)), "a == X: the load sees the store")
            val miss = modelWith(x + 1uL)
            assertEquals(ctx.mkBv(0L, 64u), miss.eval(state.reg(6)), "a == X + 1: the byte at X is untouched")
        }
    }

    // The address is either rodata (bit 0 clear) or the stack: the store forks, the rodata side is an
    // AccessViolation and its model has the bit clear.
    @Test
    fun storeMayHitRodata() {
        val ir = program(
            load(0, 3, Width.W8, reg(2)),
            assign(1, 4, bin(BinOp.And, reg(3), imm(1uL))),
            assign(2, 5, bin(BinOp.Mul, reg(4), imm(MM_STACK_START))),
            store(3, Width.W8, reg(5), imm(1uL, Width.W8)),
            rodata = byteArrayOf(1, 2, 3, 4),
        )
        SbpfMachine(ir, input = symbolicIx(1)).use { machine ->
            val states = machine.analyze()
            assertEquals(2, states.size)
            val ok = states.single { it.result == SbpfResult.Exited }
            val violation = states.single { it.result == SbpfResult.AccessViolation }
            assertEquals(1uL, ok.value(4))
            assertEquals(0uL, violation.value(4))
        }
    }
}
