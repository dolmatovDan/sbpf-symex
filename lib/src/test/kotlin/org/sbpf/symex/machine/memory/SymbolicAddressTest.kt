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
import org.usvm.solver.UUnsatResult
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

    private fun SbpfState.register(index: Int): UExpr<UBvSort> = memory.stack.readRegister(index, ctx.bv64Sort)

    private fun SbpfState.value(index: Int): ULong {
        val model = models.single()
        return (model.eval(register(index)) as KBitVec64Value).longValue.toULong()
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
                copy.pathConstraints += ctx.mkEq(copy.register(5), ctx.mkBv(address.toLong(), 64u))
                val result = solver.check(copy.pathConstraints)
                check(result is USatResult) { "a == 0x${address.toString(16)} is infeasible" }
                result.model
            }
            val hit = modelWith(x)
            assertEquals(hit.eval(state.register(3)), hit.eval(state.register(6)), "a == X: the load sees the store")
            val miss = modelWith(x + 1uL)
            assertEquals(ctx.mkBv(0L, 64u), miss.eval(state.register(6)), "a == X + 1: the byte at X is untouched")
        }
    }

    // A 64-bit symbolic value stored at a symbolic stack address a and loaded back from the same address: every
    // byte lands at a + k and comes back from there, so the loaded word equals the stored one on every model, and
    // when a == X the constant load of X + 3 sees byte 3 (a byte order broken the same way in store and load would
    // pass the round trip alone).
    @Test
    fun wideStoreAndLoadBySymbolicAddress() {
        val x = MM_STACK_START + 0x100uL
        val ir = program(
            load(0, 3, Width.W64, reg(2)),
            load(1, 4, Width.W8, bin(BinOp.Add, reg(2), imm(8uL))),
            assign(2, 4, bin(BinOp.And, reg(4), imm(7uL))),
            assign(3, 5, bin(BinOp.Add, reg(4), imm(x))),
            store(4, Width.W64, reg(5), reg(3)),
            load(5, 6, Width.W64, reg(5)),
            load(6, 7, Width.W8, imm(x + 3uL)),
        )
        SbpfMachine(ir, input = symbolicIx(9)).use { machine ->
            val state = machine.analyze().single()
            val ctx = state.ctx
            val solver = ctx.solver<SbpfType>()
            val differ = state.clone()
            differ.pathConstraints += ctx.mkNot(ctx.mkEq(differ.register(3), differ.register(6)))
            assertTrue(solver.check(differ.pathConstraints) is UUnsatResult, "r6 == r3 on every model")
            assertEquals(state.value(3), state.value(6))
            val byte3 = ctx.zextTo(Width.W64, ctx.mkBvExtractExpr(31, 24, state.register(3)))
            val hit = state.clone()
            hit.pathConstraints += ctx.mkEq(hit.register(5), ctx.mkBv(x.toLong(), 64u))
            assertTrue(solver.check(hit.pathConstraints) is USatResult, "a == X is feasible")
            hit.pathConstraints += ctx.mkNot(ctx.mkEq(hit.register(7), byte3))
            assertTrue(solver.check(hit.pathConstraints) is UUnsatResult, "a == X: [X + 3] is byte 3 on every model")
        }
    }

    // A 4-byte load at a symbolic offset into the input: the whole word must fit, so the valid offsets end at
    // size − 4, and size − 3 is an AccessViolation.
    @Test
    fun wideLoadForksAtTheRegionEnd() {
        val input = symbolicIx(1)
        val ir = program(
            load(0, 3, Width.W8, reg(2)),
            load(1, 4, Width.W32, bin(BinOp.Add, reg(1), reg(3))),
        )
        SbpfMachine(ir, input = input).use { machine ->
            val states = machine.analyze()
            val ok = states.single { it.result == SbpfResult.Exited }
            val violation = states.single { it.result == SbpfResult.AccessViolation }
            val ctx = ok.ctx
            val solver = ctx.solver<SbpfType>()
            fun feasible(state: SbpfState, offset: Int): Boolean = state.clone().let { copy ->
                copy.pathConstraints += ctx.mkEq(copy.register(3), ctx.mkBv(offset.toLong(), 64u))
                solver.check(copy.pathConstraints) is USatResult
            }
            assertTrue(feasible(ok, input.size - 4), "the last whole word is readable")
            assertTrue(!feasible(ok, input.size - 3), "a word crossing the end is not readable")
            assertTrue(feasible(violation, input.size - 3), "the crossing word is an AccessViolation")
            assertTrue(!feasible(violation, input.size - 4))
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
