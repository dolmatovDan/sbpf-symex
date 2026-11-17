package org.sbpf.symex.machine.memory

import org.sbpf.symex.ir.RoRegion

// VM address space (solana-sbpf, ebpf.rs): the high 32 bits of an address select a slot, the low 32 bits are the
// offset inside it. The runner is agave 4.3 with all features enabled (sbpf-ir/docs/runner.md): no gaps between stack
// frames, so the stack is one contiguous region in v0 and v3 alike.
const val MM_RODATA_V3_START = 0x000000000uL
const val MM_RODATA_V0_START = 0x100000000uL
const val MM_STACK_START = 0x200000000uL
const val MM_HEAP_START = 0x300000000uL
const val MM_INPUT_START = 0x400000000uL

const val STACK_SIZE = 0x40000uL
const val DEFAULT_HEAP_SIZE = 0x8000uL

// Offsets inside a slot are limited to 28 bits so that the key fits into a non-negative Int (see key).
private const val OFFSET_BITS = 28
private const val MAX_SLOT = 4uL
private const val MAX_OFFSET = 0xFFFFFFFuL

// One VM memory region: [start, start + size). Immutable regions reject stores.
class MemoryRegion(val name: String, val start: ULong, val size: ULong, val writable: Boolean) {
    init {
        require(start shr 32 <= MAX_SLOT) { "$name: start 0x${start.toString(16)} is outside the VM slots" }
        // A region never crosses a slot: start and the last byte share the slot.
        require(size <= MAX_OFFSET + 1uL - (start and 0xFFFFFFFFuL)) {
            "$name: size 0x${size.toString(16)} does not fit into the slot at 0x${start.toString(16)}"
        }
    }

    // Exclusive end; never overflows thanks to the slot check above.
    val end: ULong get() = start + size

    // Whether the access [addr, addr + n) lies inside the region. Written as addr <= end - n rather than addr + n <= end:
    // an addr near 2^64 would wrap around and pass.
    fun contains(addr: ULong, n: Int): Boolean =
        n.toULong() <= size && addr >= start && addr <= end - n.toULong()
}

// Regions of one program run: rodata as the loader maps it (ro_region of the IR), the stack, the heap and the input.
// The heap size and the sBPF version are parameters: the VM takes them from the transaction and the ELF.
class MemoryLayout(
    sbpfVersion: Int,
    roRegion: RoRegion,
    inputSize: ULong,
    heapSize: ULong = DEFAULT_HEAP_SIZE,
) {
    val rodata: MemoryRegion
    val stack = MemoryRegion("stack", MM_STACK_START, STACK_SIZE, writable = true)
    val heap = MemoryRegion("heap", MM_HEAP_START, heapSize, writable = true)
    val input = MemoryRegion("input", MM_INPUT_START, inputSize, writable = true)

    init {
        // v0: code and rodata in slot 1; v3: only rodata, in slot 0 (memory-notes.md, 1.4).
        val expectedStart = if (sbpfVersion == 0) MM_RODATA_V0_START else MM_RODATA_V3_START
        require(roRegion.vaddr == expectedStart) {
            "ro_region at 0x${roRegion.vaddr.toString(16)} in sBPF v$sbpfVersion, expected 0x${expectedStart.toString(16)}"
        }
        rodata = MemoryRegion("rodata", roRegion.vaddr, roRegion.data.size.toULong(), writable = false)
    }

    val regions: List<MemoryRegion> = listOf(rodata, stack, heap, input)

    // The region holding the whole access [addr, addr + n), or null: an AccessViolation. A store needs a writable region.
    // Regions do not overlap, so the first match is the only one.
    fun findRegion(addr: ULong, n: Int, store: Boolean): MemoryRegion? =
        regions.firstOrNull { it.contains(addr, n) && (it.writable || !store) }
}

// Key of a byte in the memory collection: the slot in bits 28..31 and the offset in bits 0..27. Collection keys are
// 32-bit (usvm's size sort) and compared as signed, so the key must stay non-negative: 28 offset bits leave room for
// slots 0..4 below 2^31. The mapping is injective only on addresses inside the slots' first 2^28 bytes, which is
// why every access is bounds-checked before it reaches the collection.
fun key(addr: ULong): Int {
    val slot = addr shr 32
    val offset = addr and 0xFFFFFFFFuL
    require(slot <= MAX_SLOT && offset <= MAX_OFFSET) { "address 0x${addr.toString(16)} has no key" }
    return (slot.toInt() shl OFFSET_BITS) or offset.toInt()
}
