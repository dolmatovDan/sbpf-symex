package org.sbpf.symex.machine

import org.sbpf.symex.ir.Width
import org.usvm.UBvSort
import org.usvm.UContext
import org.usvm.UExpr

// The width of an IR expression is known only at runtime, so bitvectors here are UExpr<UBvSort>
// without the width in the type; widths are checked with require.
class SbpfContext(components: SbpfComponents) : UContext<SbpfSizeSort>(components) {
    // KSMT takes "how many bits to add", not the resulting width.
    fun zextTo(width: Width, x: UExpr<UBvSort>): UExpr<UBvSort> =
        mkBvZeroExtensionExpr(extension(width, x), x)

    fun sextTo(width: Width, x: UExpr<UBvSort>): UExpr<UBvSort> =
        mkBvSignExtensionExpr(extension(width, x), x)

    // KSMT takes the index of the high bit, not the width.
    fun truncTo(width: Width, x: UExpr<UBvSort>): UExpr<UBvSort> {
        require(width.bits <= x.bits) { "trunc to ${width.bits} bits from ${x.bits}" }
        return mkBvExtractExpr(width.bits - 1, 0, x)
    }

    // KSMT has no ready-made bswap: the low byte becomes the high one via extract + concat.
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
