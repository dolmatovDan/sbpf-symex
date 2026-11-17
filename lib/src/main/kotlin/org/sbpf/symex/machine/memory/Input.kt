package org.sbpf.symex.machine.memory

import java.math.BigInteger

// Instruction data: either the bytes of a concrete run (the runner's example) or an unknown byte string of a known
// length. The length is always known: it shapes the input layout, and a symbolic length would make the addresses of
// everything after it symbolic too.
sealed interface IxMode {
    val length: Int

    class Concrete(val bytes: ByteArray) : IxMode {
        override val length: Int get() = bytes.size
    }

    class Symbolic(override val length: Int) : IxMode
}

// Input region of a program run without accounts (ABI v1, solana-program-runtime serialization.rs:490-611):
//
//   0    u64  number of accounts = 0
//   8    u64  length of instruction data
//   16        instruction data                 <- r2
//   16+n      program id (32 bytes)
//   48+n      zero padding to 8 bytes, then the (empty) account pointer array (SIMD-0449)
//
// The layout bytes (everything but the instruction data) are known before the run and are written as constants.
class InputSpec(val programId: ByteArray, val ix: IxMode) {
    init {
        require(programId.size == PROGRAM_ID_SIZE) { "program id of ${programId.size} bytes" }
    }

    val ixOffset: Int get() = HEADER_SIZE
    val programIdOffset: Int get() = HEADER_SIZE + ix.length

    // Whole region, padded to 8 bytes as the serializer does.
    val size: Int get() = padTo8(HEADER_SIZE + ix.length + PROGRAM_ID_SIZE)

    // Bytes with known values: the whole region except the instruction data; its bytes are left as zeros here and
    // must be skipped by the caller (see isIx).
    fun layoutBytes(): ByteArray = ByteArray(size).also { bytes ->
        putU64(bytes, 0, 0uL)
        putU64(bytes, 8, ix.length.toULong())
        programId.copyInto(bytes, programIdOffset)
    }

    // Whether the byte at offset belongs to the instruction data.
    fun isIx(offset: Int): Boolean = offset >= ixOffset && offset < ixOffset + ix.length

    companion object {
        const val HEADER_SIZE = 16
        const val PROGRAM_ID_SIZE = 32

        // No instruction data, zero program id: enough for programs that read nothing from the input.
        val EMPTY = InputSpec(ByteArray(PROGRAM_ID_SIZE), IxMode.Concrete(ByteArray(0)))

        private fun padTo8(size: Int) = (size + 7) and 7.inv()

        private fun putU64(bytes: ByteArray, offset: Int, value: ULong) {
            for (i in 0 until 8) bytes[offset + i] = (value shr (8 * i)).toByte()
        }
    }
}

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// Base58 as Solana uses it for public keys: big-endian base-58 number, each leading '1' is a leading zero byte.
fun decodeBase58(text: String): ByteArray {
    var number = BigInteger.ZERO
    for (char in text) {
        val digit = BASE58_ALPHABET.indexOf(char)
        require(digit >= 0) { "not a base58 string: $text" }
        number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
    }
    val leadingZeros = text.takeWhile { it == '1' }.length
    // toByteArray is two's complement: a leading sign byte appears when the high bit is set.
    val digits = number.toByteArray().dropWhile { it == 0.toByte() }
    return ByteArray(leadingZeros) + digits.toByteArray()
}
