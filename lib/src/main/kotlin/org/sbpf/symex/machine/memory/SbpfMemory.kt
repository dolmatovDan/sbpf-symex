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

// Memory of one path (memory-notes.md, 7). All writable VM regions live in one byte array `mem`, an allocated usvm
// array: an untouched byte reads as 0, which is what the stack and the heap need. The instruction data is copied
// into it from `input0`, an input array whose untouched bytes are fresh symbols; the rest of the input region (the
// layout) is written as constants. rodata is never written, so it stays in its own array `ro` and a load takes it
// from there. The array index is the 32-bit key of MemoryLayout, so a constant address resolves through the update
// log without the solver.
class SbpfMemoryModel(
    val layout: MemoryLayout,
    // rodata is immutable: a load from a constant address takes the byte from here, not from an array.
    val rodata: ByteArray,
    val mem: UConcreteHeapRef,
    // The initial bytes of the input region, indexed by the offset inside the region.
    val input0: UConcreteHeapRef,
    // rodata as an array, indexed by the offset inside the region: for loads by a symbolic address.
    val ro: UConcreteHeapRef,
)

typealias ByteExpr = UExpr<KBv8Sort>

private typealias ByteRegion = UArrayRegion<SbpfType, KBv8Sort, SbpfSizeSort>

val SbpfState.memoryModel: SbpfMemoryModel
    get() = requireNotNull(memoryModelOrNull) { "memory is not initialized" }

// Sets up the memory of the initial state: allocates the arrays, copies the instruction data from input0 into the
// input region as one ranged update, fills ro with rodata, writes the non-zero layout bytes (mem is zero already)
// and, for concrete instruction data, constrains its bytes to the given ones.
// The static reference of input0 is what makes usvm read it as an input array (mapWithStaticAsSymbolic).
fun SbpfState.initializeMemory(module: IrModule, input: InputSpec, heapSize: ULong) = with(ctx) {
    val layout = MemoryLayout(module.sbpfVersion, module.roRegion, input.size.toULong(), heapSize)
    val mem = memory.allocConcrete(SbpfType)
    val input0 = memory.allocStatic(SbpfType)
    val ro = memory.allocConcrete(SbpfType)
    memoryModelOrNull = SbpfMemoryModel(layout, module.roRegion.data, mem, input0, ro)

    val inputKey = key(layout.input.start)
    if (input.ix.length > 0) {
        updateByteRegion { region ->
            copyInto(region, input0, input.ixOffset, inputKey + input.ixOffset, input.ix.length)
        }
    }
    // One update node per byte (usvm's initializedArray), built once and shared by every path.
    if (module.roRegion.data.isNotEmpty()) {
        val content = module.roRegion.data.map { mkBv(it) as ByteExpr }
        updateByteRegion { region ->
            region.initializeAllocatedArray(
                address = ro.address,
                arrayType = SbpfType,
                sort = bv8Sort,
                content = content,
                operationGuard = trueExpr,
                ownership = memory.ownership,
            )
        }
    }
    val layoutBytes = input.layoutBytes()
    for (offset in layoutBytes.indices) {
        if (input.isIx(offset) || layoutBytes[offset] == 0.toByte()) continue
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
// region is unknown, so a byte is ro[offset] when the address falls into rodata and mem[key] otherwise.
fun SbpfState.loadSymbolic(addr: UExpr<UBvSort>, width: Width): UExpr<UBvSort> {
    val rodata = memoryModel.layout.rodata
    val bytes = List(width.bits / 8) { k ->
        val a = byteAddr(addr, k)
        val fromMem = memory.read(memLValue(ctx.symbolicKey(a)))
        if (rodata.size == 0uL) {
            fromMem
        } else {
            val offset = ctx.mkBvSubExpr(a, ctx.mkBv(rodata.start.toLong(), 64u))
            val fromRo = memory.read(roLValue(ctx.mkBvExtractExpr(31, 0, offset).asExpr(ctx.bv32Sort)))
            ctx.mkIte(rodata.contains(ctx, a, 1), fromRo, fromMem)
        }
    }
    return ctx.zextTo(Width.W64, concatBytes(bytes))
}

fun SbpfState.storeSymbolic(addr: UExpr<UBvSort>, value: UExpr<UBvSort>) {
    for (k in 0 until value.bits / 8) {
        memory.write(memLValue(ctx.symbolicKey(byteAddr(addr, k))), byteOf(value, k), ctx.trueExpr)
    }
}

// mem[dstKey, dstKey + size) := source[srcIdx, srcIdx + size), one ranged update. The last destination index is
// inclusive (USymbolicArrayCopyAdapter.includesSymbolically).
private fun SbpfState.copyInto(
    region: ByteRegion,
    source: UConcreteHeapRef,
    srcIdx: Int,
    dstKey: Int,
    size: Int,
): ByteRegion =
    region.memcpy(
        srcRef = source,
        dstRef = memoryModel.mem,
        type = SbpfType,
        elementSort = ctx.bv8Sort,
        fromSrcIdx = ctx.mkBv(srcIdx),
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

private fun SbpfState.roLValue(offset: UExpr<SbpfSizeSort>) =
    UArrayIndexLValue(ctx.bv8Sort, memoryModel.ro, offset, SbpfType)
