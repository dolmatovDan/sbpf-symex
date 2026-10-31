package org.sbpf.symex.machine

import org.sbpf.symex.ir.Width
import org.usvm.UBvSort
import org.usvm.UContext
import org.usvm.UExpr

// Ширина выражения IR известна только во время работы, поэтому битвекторы здесь — UExpr<UBvSort>
// без ширины в типе; ширину проверяют require.
class SbpfContext(components: SbpfComponents) : UContext<SbpfSizeSort>(components) {
    fun bvSort(width: Width): UBvSort = mkBvSort(width.bits.toUInt())

    // KSMT принимает «сколько бит добавить», а не итоговую ширину.
    fun zextTo(width: Width, x: UExpr<UBvSort>): UExpr<UBvSort> =
        mkBvZeroExtensionExpr(extension(width, x), x)

    fun sextTo(width: Width, x: UExpr<UBvSort>): UExpr<UBvSort> =
        mkBvSignExtensionExpr(extension(width, x), x)

    // KSMT принимает номер старшего бита, а не ширину.
    fun truncTo(width: Width, x: UExpr<UBvSort>): UExpr<UBvSort> {
        require(width.bits <= x.bits) { "trunc to ${width.bits} bits from ${x.bits}" }
        return mkBvExtractExpr(width.bits - 1, 0, x)
    }

    // Готового bswap в KSMT нет: младший байт становится старшим через extract + concat.
    fun bswap(x: UExpr<UBvSort>): UExpr<UBvSort> {
        require(x.bits in setOf(16, 32, 64)) { "bswap of ${x.bits} bits" }
        return (0 until x.bits / 8)
            .map { i -> mkBvExtractExpr(8 * i + 7, 8 * i, x) }
            .reduce { high, low -> mkBvConcatExpr(high, low) }
    }

    private fun extension(width: Width, x: UExpr<UBvSort>): Int {
        require(width.bits >= x.bits) { "extend to ${width.bits} bits from ${x.bits}" }
        return width.bits - x.bits
    }
}

val UExpr<UBvSort>.bits: Int get() = sort.sizeBits.toInt()
