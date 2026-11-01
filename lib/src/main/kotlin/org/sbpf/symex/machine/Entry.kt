package org.sbpf.symex.machine

// VM memory region addresses (solana-sbpf, ebpf.rs).
const val MM_STACK_START = 0x200000000uL
const val MM_INPUT_START = 0x400000000uL

// Stack frame for v0 and v3: they have no manual frame bump (manual_stack_frame_bump); only v1/v2 do.
const val STACK_FRAME_SIZE = 0x1000uL

// Registers r0–r10 at program entry, as set by agave 4.3: r10 from solana-sbpf vm.rs:381,
// r1 and r2 from solana-program-runtime vm.rs:312-313. The rest are 0.
// r2 is the instruction data address (SIMD-0321). Without accounts, the data is preceded only by the account count
// and the data length, 8 bytes each (serialization.rs:589-590). Accounts will come together with memory.
fun entryRegisters(): List<ULong> = List(REGISTER_COUNT) { index ->
    when (index) {
        1 -> MM_INPUT_START
        2 -> MM_INPUT_START + 16uL
        10 -> MM_STACK_START + STACK_FRAME_SIZE
        else -> 0uL
    }
}
