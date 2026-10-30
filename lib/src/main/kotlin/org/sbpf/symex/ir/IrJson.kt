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
    val root = json.parseToJsonElement(text) as? JsonObject ?: fail("IR — не JSON-объект")
    val version = root["format_version"]?.int("format_version")
    if (version != IR_FORMAT_VERSION) {
        fail("format_version ${version ?: "отсутствует"}, поддерживается $IR_FORMAT_VERSION")
    }
    return json.decodeFromJsonElement(IrModule.serializer(), root)
}

// IR только читается: писать его — дело лифтера.
private fun readOnly(): Nothing = throw UnsupportedOperationException("IR в Kotlin только читается")

private fun fail(message: String): Nothing = throw SerializationException(message)

// 64-битные числа в IR — строки "0x…". toULong(16) сам принял бы "+5", поэтому цифры проверяются явно.
private fun parseHex(text: String): ULong {
    val digits = text.removePrefix("0x")
    val isHex = digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    if (digits.length == text.length || digits.isEmpty() || !isHex) {
        fail("ожидалось 0x…, получено \"$text\"")
    }
    return digits.toULongOrNull(16) ?: fail("$text не помещается в 64 бита")
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
            fail("ожидался base64: ${e.message}")
        }
    }

    override fun serialize(encoder: Encoder, value: ByteArray) = readOnly()
}

private fun width(bits: Int) = Width.ofBits(bits) ?: fail("ширина $bits")

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
            reg != null && value == null && w == null && op == null && args == null -> Expr.Reg(reg)
            reg == null && value != null && w != null && op == null && args == null -> constant(value, w)
            reg == null && value == null && op != null && args != null -> operation(op, w, args)
            else -> fail("ожидалось ровно одно из: reg, const+w, op+args")
        }
    }

    override fun serialize(encoder: Encoder, value: Expr) = readOnly()
}

private fun constant(value: ULong, width: Width): Expr {
    if (value and width.mask.inv() != 0uL) fail("константа 0x${value.toString(16)} не помещается в ${width.bits} бит")
    return Expr.Const(value, width)
}

private val extOps = ExtOp.entries.associateBy { it.irName }
private val binOps = BinOp.entries.associateBy { it.irName }
private val cmpOps = CmpOp.entries.associateBy { it.irName }
private val unOps = UnOp.entries.associateBy { it.irName }

// `w` есть только у приведений ширины.
private fun operation(name: String, w: Width?, args: List<Expr>): Expr {
    fun arity(n: Int) {
        if (args.size != n) fail("$name: неверное число аргументов ${args.size}")
    }
    extOps[name]?.let { op ->
        if (w == null) fail("$name: нет поля w")
        arity(1)
        return Expr.Ext(op, w, args[0])
    }
    if (w != null) fail("$name: лишнее поле w")
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
    fail("неизвестная операция \"$name\"")
}

private fun JsonElement.int(key: String): Int =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: fail("$key: ожидалось целое, получено $this")
