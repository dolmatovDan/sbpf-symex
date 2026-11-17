package org.sbpf.symex.machine.memory

import io.ksmt.expr.KBvExtractExpr
import io.ksmt.sort.KBv8Sort
import io.ksmt.utils.asExpr
import org.sbpf.symex.ir.IrModule
import org.sbpf.symex.ir.Width
import org.sbpf.symex.machine.SbpfSizeSort
import org.sbpf.symex.machine.SbpfState
import org.sbpf.symex.machine.SbpfType
import org.sbpf.symex.machine.bits
import org.usvm.UBvSort
import org.usvm.UConcreteHeapRef
import org.usvm.UExpr
import org.usvm.collection.array.UArrayIndexLValue
import org.usvm.collection.array.UArrayRegion
import org.usvm.collection.array.UArrayRegionId

// Memory of one path (memory-notes.md, 7). All VM regions live in one byte array `mem`, an allocated usvm array:
// an untouched byte reads as 0, which is what the stack and the heap need. The input region starts as a copy of
// `input0`, an input array whose untouched bytes are fresh symbols; the layout bytes (everything but the instruction
// data) are then overwritten with constants, so only the instruction data stays symbolic. The array index is the
// 32-bit key of MemoryLayout, so a constant address resolves through the update log without the solver.
class SbpfMemoryModel(
    val layout: MemoryLayout,
    val input: InputSpec,
    // rodata is immutable: a load from a constant address takes the byte from here, not from mem.
    val rodata: ByteArray,
    val mem: UConcreteHeapRef,
    // The initial bytes of the input region, indexed by the offset inside the region.
    val input0: UConcreteHeapRef,
    // rodata as an array, for loads by a symbolic address; filled on demand (ensureRodataInMemory).
    val ro: UConcreteHeapRef,
)

typealias ByteExpr = UExpr<KBv8Sort>

private typealias ByteRegion = UArrayRegion<SbpfType, KBv8Sort, SbpfSizeSort>

val SbpfState.memoryModel: SbpfMemoryModel
    get() = requireNotNull(memoryModelOrNull) { "memory is not initialized" }

// Sets up the memory of the initial state: allocates the arrays, copies input0 into the input region as one ranged
// update, writes the layout bytes and, for concrete instruction data, constrains its bytes to the given ones.
// The static reference of input0 is what makes usvm read it as an input array (mapWithStaticAsSymbolic).
fun SbpfState.initializeMemory(module: IrModule, input: InputSpec, heapSize: ULong) = with(ctx) {
    val layout = MemoryLayout(module.sbpfVersion, module.roRegion, input.size.toULong(), heapSize)
    val mem = memory.allocConcrete(SbpfType)
    val input0 = memory.allocStatic(SbpfType)
    val ro = memory.allocConcrete(SbpfType)
    memoryModelOrNull = SbpfMemoryModel(layout, input, module.roRegion.data, mem, input0, ro)

    val inputKey = key(layout.input.start)
    updateByteRegion { region -> copyInto(region, input0, inputKey, input.size) }
    val layoutBytes = input.layoutBytes()
    for (offset in layoutBytes.indices) {
        if (input.isIx(offset)) continue
        memory.write(memLValue(mkBv(inputKey + offset)), mkBv(layoutBytes[offset]), trueExpr)
    }
    val ix = input.ix
    if (ix is IxMode.Concrete) {
        ix.bytes.forEachIndexed { i, byte ->
            pathConstraints += mkEq(memory.read(inputLValue(input.ixOffset + i)), mkBv(byte))
        }
    }
}

// The value of [addr, addr + w/8) as a 64-bit term (zero-extended, little-endian), or null: the access is an
// AccessViolation. The address is a constant, so the region and the keys are computed without the solver.
fun SbpfState.loadConst(addr: ULong, width: Width): UExpr<UBvSort>? {
    val model = memoryModel
    val n = width.bits / 8
    val region = model.layout.findRegion(addr, n, store = false) ?: return null
    val bytes = List(n) { k ->
        val byteAddr = addr + k.toULong()
        if (region === model.layout.rodata) {
            ctx.mkBv(model.rodata[(byteAddr - region.start).toInt()])
        } else {
            memory.read(memLValue(ctx.mkBv(key(byteAddr))))
        }
    }
    return ctx.zextTo(Width.W64, concatBytes(bytes))
}

// Writes value (its width is the store width) to [addr, addr + w/8); false means an AccessViolation, and then
// nothing is written: the VM checks the whole access before touching memory.
fun SbpfState.storeConst(addr: ULong, value: UExpr<UBvSort>): Boolean {
    val n = value.bits / 8
    memoryModel.layout.findRegion(addr, n, store = true) ?: return false
    for (k in 0 until n) {
        memory.write(memLValue(ctx.mkBv(key(addr + k.toULong()))), byteOf(value, k), ctx.trueExpr)
    }
    return true
}

// The same for a symbolic address, on a path where accessCondition already holds: the keys are valid, and the
// region is unknown, so the bytes come from mem, where rodata must be present too.
fun SbpfState.loadSymbolic(addr: UExpr<UBvSort>, width: Width): UExpr<UBvSort> {
    ensureRodataInMemory()
    val bytes = List(width.bits / 8) { k -> memory.read(memLValue(ctx.symbolicKey(byteAddr(addr, k)))) }
    return ctx.zextTo(Width.W64, concatBytes(bytes))
}

fun SbpfState.storeSymbolic(addr: UExpr<UBvSort>, value: UExpr<UBvSort>) {
    for (k in 0 until value.bits / 8) {
        memory.write(memLValue(ctx.symbolicKey(byteAddr(addr, k))), byteOf(value, k), ctx.trueExpr)
    }
}

// A symbolic load may hit rodata, so rodata has to be in mem. The copy costs one update node per byte (usvm's
// initializedArray) and in v0 the region is the whole image including the code, hence it is made once per path and
// only when a symbolic load happens. Stores never need it: rodata is read-only, accessCondition excludes it.
private fun SbpfState.ensureRodataInMemory() {
    if (rodataInMemory) return
    rodataInMemory = true
    val model = memoryModel
    if (model.rodata.isEmpty()) return
    val content = model.rodata.map { ctx.mkBv(it) as ByteExpr }
    updateByteRegion { region ->
        val withRo = region.initializeAllocatedArray(
            address = model.ro.address,
            arrayType = SbpfType,
            sort = ctx.bv8Sort,
            content = content,
            operationGuard = ctx.trueExpr,
            ownership = memory.ownership,
        )
        copyInto(withRo, model.ro, key(model.layout.rodata.start), model.rodata.size)
    }
}

// mem[dstKey, dstKey + size) := source[0, size), one ranged update. The last destination index is inclusive
// (USymbolicArrayCopyAdapter.includesSymbolically).
private fun SbpfState.copyInto(region: ByteRegion, source: UConcreteHeapRef, dstKey: Int, size: Int): ByteRegion =
    region.memcpy(
        srcRef = source,
        dstRef = memoryModel.mem,
        type = SbpfType,
        elementSort = ctx.bv8Sort,
        fromSrcIdx = ctx.mkBv(0),
        fromDstIdx = ctx.mkBv(dstKey),
        toDstIdx = ctx.mkBv(dstKey + size - 1),
        operationGuard = ctx.trueExpr,
        ownership = memory.ownership,
    )

// Replaces the byte array region (all arrays of SbpfType × bv8 live in it) with update applied.
private fun SbpfState.updateByteRegion(update: SbpfState.(ByteRegion) -> ByteRegion) {
    val regionId = UArrayRegionId<SbpfType, KBv8Sort, SbpfSizeSort>(SbpfType, ctx.bv8Sort)
    val region = memory.getRegion(regionId)
    check(region is UArrayRegion<SbpfType, KBv8Sort, SbpfSizeSort>) { "byte memory is not an array region: $region" }
    memory.setRegion(regionId, update(region))
}

private fun SbpfState.byteAddr(addr: UExpr<UBvSort>, k: Int): UExpr<UBvSort> =
    if (k == 0) addr else ctx.mkBvAddExpr(addr, ctx.mkBv(k.toLong(), 64u))

// Byte k of a value: bits 8k+7..8k.
private fun SbpfState.byteOf(value: UExpr<UBvSort>, k: Int): ByteExpr =
    ctx.mkBvExtractExpr(8 * k + 7, 8 * k, value).asExpr(ctx.bv8Sort)

// Little-endian: the byte at the lowest address is the least significant.
private fun SbpfState.concatBytes(bytes: List<ByteExpr>): UExpr<UBvSort> =
    mergedExtract(bytes)
        ?: bytes.drop(1).fold(bytes.first() as UExpr<UBvSort>) { acc, byte -> ctx.mkBvConcatExpr(byte, acc) }

// Bytes stored by storeConst are extract(8k+7, 8k, v) terms; read back, adjacent ones fold into one extract of v
// (the whole v when it is read entirely). KSMT does this only in its full rewriter, not when a term is built, so
// without this a 64-bit store and load would turn r1 into concat(extract(63, 56, r1), …).
private fun SbpfState.mergedExtract(bytes: List<ByteExpr>): UExpr<UBvSort>? {
    val first = bytes.first() as? KBvExtractExpr ?: return null
    val source = first.value
    val low = first.low
    bytes.forEachIndexed { k, byte ->
        if (byte !is KBvExtractExpr || byte.value != source || byte.low != low + 8 * k || byte.high != byte.low + 7) {
            return null
        }
    }
    val high = low + 8 * bytes.size - 1
    return if (low == 0 && high == source.bits - 1) source else ctx.mkBvExtractExpr(high, low, source)
}

private fun SbpfState.memLValue(key: UExpr<SbpfSizeSort>) =
    UArrayIndexLValue(ctx.bv8Sort, memoryModel.mem, key, SbpfType)

private fun SbpfState.inputLValue(offset: Int) =
    UArrayIndexLValue(ctx.bv8Sort, memoryModel.input0, ctx.mkBv(offset), SbpfType)
