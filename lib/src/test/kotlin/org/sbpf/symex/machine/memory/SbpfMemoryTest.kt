package org.sbpf.symex.machine.memory

import org.sbpf.symex.ir.BinOp
import org.sbpf.symex.ir.Exit
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Stmt
import org.sbpf.symex.ir.Width
import org.sbpf.symex.machine.SbpfMachine
import org.sbpf.symex.machine.assign
import org.sbpf.symex.machine.bin
import org.sbpf.symex.machine.block
import org.sbpf.symex.machine.function
import org.sbpf.symex.machine.imm
import org.sbpf.symex.machine.load
import org.sbpf.symex.machine.module
import org.sbpf.symex.machine.reg
import org.sbpf.symex.machine.store
import org.sbpf.symex.result.PathResult
import org.sbpf.symex.result.PathStatus
import org.usvm.UBvSort
import org.usvm.UExpr
import kotlin.test.Test
import kotlin.test.assertEquals

// Loads and stores by constant addresses on hand-built IR; against the runner they are checked by DiffTest (memory.s).
class SbpfMemoryTest {
    private val programId = ByteArray(32) { (it + 1).toByte() }

    private fun input(vararg ix: Int) = InputSpec(programId, IxMode.Concrete(ix.map { it.toByte() }.toByteArray()))

    private fun program(vararg statements: Stmt, sbpfVersion: Int = 3, rodata: ByteArray = ByteArray(0)) = module(
        function("entrypoint", block(0, *statements, exit = Exit.Return(statements.size))),
        sbpfVersion = sbpfVersion,
        rodata = rodata,
    )

    private fun pathOf(module: IrModule, input: InputSpec = InputSpec.EMPTY): PathResult =
        SbpfMachine(module, recordTrace = true, input = input).use { it.results() }.single()

    private fun at(base: Int, offset: Long) = bin(BinOp.Add, reg(base), imm(offset.toULong()))

    private fun PathResult.pcs() = trace.map { it.last() }

    private fun assertViolation(path: PathResult, pc: ULong) {
        assertEquals(PathStatus.Failure, path.status)
        assertEquals("AccessViolation", path.error)
        assertEquals(pc, path.pcs().last(), "the failing instruction is the last in the trace")
    }

    // A 64-bit store, read back with every width: little-endian, zero-extended.
    @Test
    fun stackAllWidths() {
        val path = pathOf(
            program(
                assign(0, 1, imm(0x1122334455667788uL)),
                store(1, Width.W64, at(10, -8), reg(1)),
                load(2, 2, Width.W8, at(10, -8)),
                load(3, 3, Width.W16, at(10, -8)),
                load(4, 4, Width.W32, at(10, -8)),
                load(5, 5, Width.W64, at(10, -8)),
                load(6, 6, Width.W8, at(10, -1)),
            ),
        )
        assertEquals(PathStatus.Success, path.status)
        assertEquals(0x88uL, path.registers[2])
        assertEquals(0x7788uL, path.registers[3])
        assertEquals(0x55667788uL, path.registers[4])
        assertEquals(0x1122334455667788uL, path.registers[5])
        assertEquals(0x11uL, path.registers[6])
    }

    // Stores of different widths overlap byte by byte; a read in the middle of a word sees its bytes.
    @Test
    fun overlappingWidths() {
        val path = pathOf(
            program(
                assign(0, 1, imm(0x1122334455667788uL)),
                store(1, Width.W64, at(10, -8), reg(1)),
                load(2, 2, Width.W32, at(10, -6)),
                store(3, Width.W16, at(10, -4), imm(0xABCDuL, Width.W16)),
                load(4, 3, Width.W64, at(10, -8)),
            ),
        )
        assertEquals(0x33445566uL, path.registers[2])
        assertEquals(0x1122ABCD55667788uL, path.registers[3])
    }

    // Symbolic instruction data stored to the stack and loaded back is the same term, not a concatenation of its
    // bytes; a narrower load in the middle is a single extract.
    @Test
    fun loadAfterStoreFoldsToTheValue() {
        val ir = program(
            load(0, 1, Width.W64, reg(2)),
            store(1, Width.W64, at(10, -8), reg(1)),
            load(2, 3, Width.W64, at(10, -8)),
            load(3, 4, Width.W32, at(10, -6)),
        )
        SbpfMachine(ir, input = InputSpec(programId, IxMode.Symbolic(8))).use { machine ->
            val state = machine.analyze().single()
            val ctx = state.ctx
            val r1: UExpr<UBvSort> = state.memory.stack.readRegister(1, ctx.bv64Sort)
            val r3: UExpr<UBvSort> = state.memory.stack.readRegister(3, ctx.bv64Sort)
            val r4: UExpr<UBvSort> = state.memory.stack.readRegister(4, ctx.bv64Sort)
            assertEquals(r1, r3)
            assertEquals(ctx.zextTo(Width.W64, ctx.mkBvExtractExpr(47, 16, r1)), r4)
        }
    }

    // Concrete instruction data: the bytes of the example are read through the input array, in the model.
    @Test
    fun instructionData() {
        val path = pathOf(
            program(
                load(0, 1, Width.W64, reg(2)),
                load(1, 3, Width.W8, at(2, 2)),
                load(2, 4, Width.W16, at(2, 6)),
            ),
            input(1, 2, 3, 4, 5, 6, 7, 8),
        )
        assertEquals(PathStatus.Success, path.status)
        assertEquals(0x0807060504030201uL, path.registers[1])
        assertEquals(3uL, path.registers[3])
        assertEquals(0x0807uL, path.registers[4])
    }

    // After the instruction data comes the program id, as a constant.
    @Test
    fun programIdAfterInstructionData() {
        val path = pathOf(program(load(0, 1, Width.W64, at(2, 3))), input(9, 9, 9))
        assertEquals(0x0807060504030201uL, path.registers[1])
    }

    // The header: account count, then the instruction data length; r2 points right after them.
    @Test
    fun header() {
        val path = pathOf(
            program(
                load(0, 3, Width.W64, reg(1)),
                load(1, 4, Width.W64, at(1, 8)),
            ),
            input(1, 2, 3),
        )
        assertEquals(0uL, path.registers[3])
        assertEquals(3uL, path.registers[4])
        assertEquals(path.registers[1] + 16uL, path.registers[2])
    }

    // The heap size is a parameter of the machine: the last byte of a 64 KiB heap is readable, past it is not.
    @Test
    fun heapSize() {
        val end = MM_HEAP_START + 0x10000uL
        val ir = program(assign(0, 1, imm(end - 1uL)), load(1, 2, Width.W8, reg(1)), load(2, 3, Width.W8, at(1, 1)))
        val path = SbpfMachine(ir, recordTrace = true, heapSize = 0x10000uL).use { it.results() }.single()
        assertViolation(path, 2uL)
        assertViolation(pathOf(ir), 1uL)
    }

    // Untouched heap reads as zeros; a store is read back.
    @Test
    fun heap() {
        val path = pathOf(
            program(
                assign(0, 1, imm(MM_HEAP_START + 0x100uL)),
                load(1, 2, Width.W64, reg(1)),
                store(2, Width.W32, reg(1), imm(0xCAFEuL, Width.W32)),
                load(3, 3, Width.W64, reg(1)),
            ),
        )
        assertEquals(0uL, path.registers[2])
        assertEquals(0xCAFEuL, path.registers[3])
    }

    // rodata comes from the IR, at slot 0 in v3 and slot 1 in v0.
    @Test
    fun rodata() {
        val rodata = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val v3 = pathOf(
            program(
                assign(0, 1, imm(MM_RODATA_V3_START)),
                load(1, 2, Width.W8, reg(1)),
                load(2, 3, Width.W32, reg(1)),
                rodata = rodata,
            ),
        )
        assertEquals(0xAAuL, v3.registers[2])
        assertEquals(0xDDCCBBAAuL, v3.registers[3])
        val v0 = pathOf(
            program(
                assign(0, 1, imm(MM_RODATA_V0_START + 2uL)),
                load(1, 2, Width.W16, reg(1)),
                sbpfVersion = 0,
                rodata = rodata,
            ),
        )
        assertEquals(0xDDCCuL, v0.registers[2])
    }

    @Test
    fun loadPastInput() {
        val input = input(1, 2, 3)
        val path = pathOf(
            program(
                load(0, 3, Width.W8, at(1, input.size.toLong() - 1)),
                load(1, 4, Width.W8, at(1, input.size.toLong())),
                assign(2, 5, imm(1uL)),
            ),
            input,
        )
        assertViolation(path, 1uL)
        assertEquals(listOf(0uL, 1uL), path.pcs(), "the instruction after the failing one is not executed")
    }

    @Test
    fun storeToRodata() {
        val path = pathOf(
            program(
                assign(0, 1, imm(MM_RODATA_V3_START)),
                store(1, Width.W8, reg(1), imm(1uL, Width.W8)),
                rodata = byteArrayOf(1, 2, 3, 4),
            ),
        )
        assertViolation(path, 1uL)
    }

    // With an empty rodata (hand-built IR) address 0 is in no region.
    @Test
    fun loadFromEmptyRodata() {
        val path = pathOf(program(assign(0, 1, imm(0uL)), load(1, 2, Width.W8, reg(1))))
        assertViolation(path, 1uL)
    }

    // The whole access must fit: a word ending one byte past the stack fails, nothing is written.
    @Test
    fun storeCrossingStackEnd() {
        val end = MM_STACK_START + STACK_SIZE
        val ok = pathOf(
            program(
                assign(0, 1, imm(end - 8uL)),
                store(1, Width.W64, reg(1), imm(7uL)),
                load(2, 2, Width.W64, reg(1)),
            ),
        )
        assertEquals(7uL, ok.registers[2])
        val crossing = pathOf(
            program(
                assign(0, 1, imm(end - 4uL)),
                store(1, Width.W64, reg(1), imm(7uL)),
            ),
        )
        assertViolation(crossing, 1uL)
    }
}
