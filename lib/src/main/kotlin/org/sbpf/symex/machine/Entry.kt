package org.sbpf.symex.machine

// Адреса регионов памяти VM (solana-sbpf, ebpf.rs).
const val MM_STACK_START = 0x200000000uL
const val MM_INPUT_START = 0x400000000uL

// Кадр стека v0 и v3: ручного сдвига кадра (manual_stack_frame_bump) в них нет, он только у v1/v2.
const val STACK_FRAME_SIZE = 0x1000uL

// Регистры r0–r10 на входе в программу, как их выставляет agave 4.3: r10 — solana-sbpf vm.rs:381,
// r1 и r2 — solana-program-runtime vm.rs:312-313. Остальные — 0.
// r2 — адрес данных инструкции (SIMD-0321). Без аккаунтов перед данными лежат только число аккаунтов
// и длина данных, по 8 байт (serialization.rs:589-590). Аккаунты появятся вместе с памятью.
fun entryRegisters(): List<ULong> = List(REGISTER_COUNT) { index ->
    when (index) {
        1 -> MM_INPUT_START
        2 -> MM_INPUT_START + 16uL
        10 -> MM_STACK_START + STACK_FRAME_SIZE
        else -> 0uL
    }
}
