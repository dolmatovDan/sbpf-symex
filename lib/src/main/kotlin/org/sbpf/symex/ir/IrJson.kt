package org.sbpf.symex.ir

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.io.encoding.Base64

const val IR_FORMAT_VERSION = 1

// Незнакомые поля игнорируются: так требует контракт docs/ir.md. Тесты разбирают строже.
val IrJson = Json { ignoreUnknownKeys = true }

// format_version проверяется до разбора: новый формат даёт понятную ошибку, а не ошибку в его полях.
// Любая ошибка загрузки — SerializationException.
fun parseIr(text: String, json: Json = IrJson): IrModule {
    val root = json.parseToJsonElement(text) as? JsonObject ?: fail("IR is not a JSON object")
    val version = root["format_version"]?.int("format_version")
    if (version != IR_FORMAT_VERSION) {
        fail("format_version ${version ?: "missing"}, supported $IR_FORMAT_VERSION")
    }
    return json.decodeFromJsonElement(IrModule.serializer(), root).also(::checkModule)
}

// Часть Module::validate из лифтера: то, что иначе всплыло бы только в машине.
private fun checkModule(module: IrModule) {
    if (module.sbpfVersion != 0 && module.sbpfVersion != 3) {
        fail("sbpf_version ${module.sbpfVersion}, supported 0 and 3")
    }
    for (block in module.functions.flatMap { it.blocks }) {
        for (stmt in block.statements) {
            val dst = when (stmt) {
                is Stmt.Assign -> stmt.dst
                is Stmt.Load -> stmt.dst
                is Stmt.Store, is Stmt.Check -> null
            }
            // В r10 писать нельзя.
            if (dst != null && dst !in 0..9) fail("block ${block.start}, pc ${stmt.pc}: write to r$dst")
        }
        val exit = block.exit
        if (exit is Exit.Callx && exit.reg !in 0..9) {
            fail("block ${block.start}, pc ${exit.pc}: callx via r${exit.reg}")
        }
    }
}

// IR только читается: писать его — дело лифтера.
private fun readOnly(): Nothing = throw UnsupportedOperationException("IR is read-only in Kotlin")

private fun fail(message: String): Nothing = throw SerializationException(message)

private val hexFormat = HexFormat { number.prefix = "0x" }

// 64-битные числа в IR — строки "0x…". hexToULong, в отличие от toULong(16), не принимает "+5" и не-ASCII цифры.
private fun parseHex(text: String): ULong =
    try {
        text.hexToULong(hexFormat)
    } catch (e: NumberFormatException) {
        fail("expected 0x…, got \"$text\": ${e.message}")
    }

internal object HexULongSerializer : KSerializer<ULong> {
    override val descriptor = PrimitiveSerialDescriptor("org.sbpf.symex.ir.HexULong", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder) = parseHex(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: ULong) = readOnly()
}

internal object Base64Serializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("org.sbpf.symex.ir.Base64", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ByteArray {
        val text = decoder.decodeString()
        return try {
            Base64.decode(text)
        } catch (e: IllegalArgumentException) {
            fail("expected base64: ${e.message}")
        }
    }

    override fun serialize(encoder: Encoder, value: ByteArray) = readOnly()
}

private fun width(bits: Int) = Width.ofBits(bits) ?: fail("invalid width $bits")

internal object WidthSerializer : KSerializer<Width> {
    override val descriptor = PrimitiveSerialDescriptor("org.sbpf.symex.ir.Width", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder) = width(decoder.decodeInt())

    override fun serialize(encoder: Encoder, value: Width) = readOnly()
}

// Поля как у RawExpr в ir/json.rs: null равнозначен отсутствию поля.
@Serializable
private class RawExpr(
    val reg: Int? = null,
    @SerialName("const") @Serializable(with = HexULongSerializer::class) val value: ULong? = null,
    val w: Width? = null,
    val op: String? = null,
    val args: List<Expr>? = null,
)

// У листьев и операций нет общего дискриминатора, поэтому узел выбирается по набору полей.
internal object ExprSerializer : KSerializer<Expr> {
    override val descriptor = SerialDescriptor("org.sbpf.symex.ir.Expr", RawExpr.serializer().descriptor)

    override fun deserialize(decoder: Decoder): Expr {
        val raw = decoder.decodeSerializableValue(RawExpr.serializer())
        val reg = raw.reg
        val value = raw.value
        val w = raw.w
        val op = raw.op
        val args = raw.args
        return when {
            reg != null && value == null && w == null && op == null && args == null -> register(reg)
            reg == null && value != null && w != null && op == null && args == null -> constant(value, w)
            reg == null && value == null && op != null && args != null -> operation(op, w, args)
            else -> fail("expected exactly one of: reg, const+w, op+args")
        }
    }

    override fun serialize(encoder: Encoder, value: Expr) = readOnly()
}

private fun register(index: Int): Expr {
    if (index !in 0..10) fail("no register r$index")
    return Expr.Reg(index)
}

private fun constant(value: ULong, width: Width): Expr {
    if (value and width.mask.inv() != 0uL) fail("constant 0x${value.toString(16)} does not fit in ${width.bits} bits")
    return Expr.Const(value, width)
}

private val extOps = ExtOp.entries.associateBy { it.irName }
private val binOps = BinOp.entries.associateBy { it.irName }
private val cmpOps = CmpOp.entries.associateBy { it.irName }
private val unOps = UnOp.entries.associateBy { it.irName }

// `w` есть только у приведений ширины.
private fun operation(name: String, w: Width?, args: List<Expr>): Expr {
    fun arity(n: Int) {
        if (args.size != n) fail("$name: wrong number of arguments ${args.size}")
    }
    extOps[name]?.let { op ->
        if (w == null) fail("$name: missing field w")
        arity(1)
        return Expr.Ext(op, w, args[0])
    }
    if (w != null) fail("$name: unexpected field w")
    binOps[name]?.let { op ->
        arity(2)
        return Expr.Bin(op, args[0], args[1])
    }
    cmpOps[name]?.let { op ->
        arity(2)
        return Expr.Cmp(op, args[0], args[1])
    }
    unOps[name]?.let { op ->
        arity(1)
        return Expr.Un(op, args[0])
    }
    fail("unknown operation \"$name\"")
}

private fun JsonElement.int(key: String): Int =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: fail("$key: expected an integer, got $this")
