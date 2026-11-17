package org.sbpf.symex.machine.memory

import org.sbpf.symex.ir.RoRegion
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryLayoutTest {
    private fun layout(version: Int = 3, rodata: Int = 16, inputSize: ULong = 64uL) = MemoryLayout(
        version,
        RoRegion(if (version == 0) MM_RODATA_V0_START else MM_RODATA_V3_START, ByteArray(rodata)),
        inputSize,
    )

    // rodata lives in slot 1 in v0 (together with the code) and in slot 0 in v3; the other regions do not move.
    @Test
    fun rodataSlotByVersion() {
        assertEquals(MM_RODATA_V0_START, layout(version = 0).rodata.start)
        assertEquals(MM_RODATA_V3_START, layout(version = 3).rodata.start)
        val v3 = layout(version = 3)
        assertEquals(MM_STACK_START, v3.stack.start)
        assertEquals(MM_HEAP_START, v3.heap.start)
        assertEquals(MM_INPUT_START, v3.input.start)
        assertEquals(DEFAULT_HEAP_SIZE, v3.heap.size)
        // The lifter's region address must match the version: a mismatch means the lifter changed.
        assertFailsWith<IllegalArgumentException> {
            MemoryLayout(0, RoRegion(MM_RODATA_V3_START, ByteArray(1)), 16uL)
        }
    }

    // The last n bytes of a region are accessible; one byte further is not, whatever the width.
    @Test
    fun boundsAtRegionEnd() {
        val l = layout()
        val end = l.stack.end
        for (n in listOf(1, 2, 4, 8)) {
            assertSame(l.stack, l.findRegion(end - n.toULong(), n, store = false), "last $n bytes")
            assertNull(l.findRegion(end - n.toULong() + 1uL, n, store = false), "$n bytes crossing the end")
        }
        assertSame(l.stack, l.findRegion(l.stack.start, 8, store = true))
        assertNull(l.findRegion(l.stack.start - 1uL, 8, store = false), "one byte below the stack")
        assertNull(l.findRegion(end, 1, store = false), "first byte past the stack")
    }

    // addr + n would wrap around to a small number and pass a naive check.
    @Test
    fun addressNearTopOfAddressSpace() {
        val l = layout()
        assertNull(l.findRegion(ULong.MAX_VALUE, 1, store = false))
        assertNull(l.findRegion(ULong.MAX_VALUE - 3uL, 8, store = false))
    }

    // rodata is readable but not writable; the input is both.
    @Test
    fun rodataIsReadOnly() {
        val l = layout(rodata = 16)
        assertSame(l.rodata, l.findRegion(l.rodata.start, 8, store = false))
        assertNull(l.findRegion(l.rodata.start, 8, store = true))
        assertSame(l.input, l.findRegion(l.input.start, 8, store = true))
    }

    // An empty rodata (as in hand-built IR) makes address 0 an AccessViolation in v3, where the slot is otherwise rodata.
    @Test
    fun emptyRodata() {
        val l = layout(rodata = 0)
        assertNull(l.findRegion(0uL, 1, store = false))
        assertEquals(0uL, l.rodata.size)
    }

    // Keys stay non-negative (the collection compares them as signed) and distinct across slots.
    @Test
    fun keys() {
        assertEquals(0, key(0uL))
        assertEquals((4 shl 28) or 0x10, key(MM_INPUT_START + 0x10uL))
        assertTrue(key(MM_INPUT_START + 0xFFFFFFFuL) > 0, "the last offset of slot 4 is positive")
        assertEquals(key(MM_STACK_START) + 0x40000, key(MM_STACK_START + 0x40000uL))
        assertFailsWith<IllegalArgumentException> { key(MM_INPUT_START + 0x10000000uL) }
        assertFailsWith<IllegalArgumentException> { key(0x500000000uL) }
    }

    // The region size is limited by the key: a region past 2^28 bytes could not be addressed.
    @Test
    fun regionMustFitTheSlot() {
        assertFailsWith<IllegalArgumentException> { MemoryRegion("big", MM_HEAP_START, 0x10000001uL, writable = true) }
        MemoryRegion("max", MM_HEAP_START, 0x10000000uL, writable = true)
    }
}

class InputSpecTest {
    private fun spec(ix: ByteArray) = InputSpec(ByteArray(32) { (it + 1).toByte() }, IxMode.Concrete(ix))

    // Header + ix + program id, padded to 8 bytes (serialization.rs:539-541): the padding is where the empty
    // account pointer array would start.
    @Test
    fun sizeIsPadded() {
        assertEquals(48, spec(ByteArray(0)).size)
        assertEquals(56, spec(ByteArray(1)).size)
        assertEquals(56, spec(ByteArray(8)).size)
        assertEquals(64, spec(ByteArray(9)).size)
    }

    // Account count 0, ix length, ix bytes skipped, program id after ix, zero padding.
    @Test
    fun layoutBytes() {
        val s = spec(ByteArray(3) { 0x55 })
        val bytes = s.layoutBytes()
        assertEquals(16, s.ixOffset)
        assertEquals(19, s.programIdOffset)
        assertContentEquals(ByteArray(8), bytes.copyOfRange(0, 8))
        assertContentEquals(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0), bytes.copyOfRange(8, 16))
        assertContentEquals(ByteArray(3), bytes.copyOfRange(16, 19), "ix bytes are not layout")
        assertContentEquals(s.programId, bytes.copyOfRange(19, 51))
        assertContentEquals(ByteArray(5), bytes.copyOfRange(51, 56))
        assertEquals(listOf(16, 17, 18), (0 until s.size).filter { s.isIx(it) })
    }

    @Test
    fun symbolicIxHasTheSameLayout() {
        val concrete = spec(ByteArray(5))
        val symbolic = InputSpec(concrete.programId, IxMode.Symbolic(5))
        assertEquals(concrete.size, symbolic.size)
        assertContentEquals(concrete.layoutBytes(), symbolic.layoutBytes())
    }

    @Test
    fun base58() {
        assertContentEquals(ByteArray(32), decodeBase58("11111111111111111111111111111111"))
        // Program id of the asm examples (tests/examples/asm/*.json), decoded independently.
        val expected = "02879ec7db2452f36ecb30409edc8f2bfc63cadbab47a073fbfe440000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, decodeBase58("Asm1111111111111111111111111111111111111111"))
        assertFailsWith<IllegalArgumentException> { decodeBase58("0") }
    }
}
