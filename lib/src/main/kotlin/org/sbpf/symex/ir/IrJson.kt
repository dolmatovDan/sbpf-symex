package org.sbpf.symex.ir

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// Незнакомые поля игнорируются: так требует контракт docs/ir.md. Тесты разбирают строже.
val IrJson = Json { ignoreUnknownKeys = true }

// IR только читается: писать его — дело лифтера.
private fun readOnly(): Nothing = throw UnsupportedOperationException("IR в Kotlin только читается")

private fun fail(message: String): Nothing = throw SerializationException(message)

// 64-битные числа в IR — строки "0x…". toULong(16) сам принял бы "+5", поэтому цифры проверяются явно.
private fun parseHex(text: String): ULong {
    val digits = text.removePrefix("0x")
    if (digits.length == text.length || digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        fail("ожидалось 0x…, получено \"$text\"")
    }
    return digits.toULongOrNull(16) ?: fail("$text не помещается в 64 бита")
}

internal object HexULongSerializer : KSerializer<ULong> {
    override val descriptor = PrimitiveSerialDescriptor("org.sbpf.symex.ir.HexULong", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder) = parseHex(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: ULong) = readOnly()
}

private fun width(bits: Int) = Width.ofBits(bits) ?: fail("ширина $bits")

internal object WidthSerializer : KSerializer<Width> {
    override val descriptor = PrimitiveSerialDescriptor("org.sbpf.symex.ir.Width", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder) = width(decoder.decodeInt())

    override fun serialize(encoder: Encoder, value: Width) = readOnly()
}

// У листьев и операций нет общего дискриминатора, поэтому разбор по набору ключей, как RawExpr в ir/json.rs.
internal object ExprSerializer : KSerializer<Expr> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Expr {
        val input = decoder as? JsonDecoder ?: fail("IR читается только из JSON")
        return parseExpr(input.decodeJsonElement(), input.json)
    }

    override fun serialize(encoder: Encoder, value: Expr) = readOnly()
}

private val exprKeys = setOf("reg", "const", "w", "op", "args")

private fun parseExpr(element: JsonElement, json: Json): Expr {
    val obj = element as? JsonObject ?: fail("выражение — не объект: $element")
    if (!json.configuration.ignoreUnknownKeys) {
        val unknown = obj.keys - exprKeys
        if (unknown.isNotEmpty()) fail("незнакомые поля $unknown в выражении $obj")
    }
    val reg = obj["reg"]
    val const = obj["const"]
    val w = obj["w"]
    val op = obj["op"]
    val args = obj["args"]
    return when {
        reg != null && const == null && w == null && op == null && args == null ->
            Expr.Reg(reg.int("reg"))
        reg == null && const != null && w != null && op == null && args == null ->
            constant(parseHex(const.string("const")), width(w.int("w")))
        reg == null && const == null && op != null && args != null ->
            operation(op.string("op"), w?.let { width(it.int("w")) }, args.exprs(json))
        else -> fail("ожидалось ровно одно из: reg, const+w, op+args, получено $obj")
    }
}

private fun constant(value: ULong, width: Width): Expr {
    if (value and width.mask.inv() != 0uL) fail("константа 0x${value.toString(16)} не помещается в ${width.bits} бит")
    return Expr.Const(value, width)
}

// `w` есть только у приведений ширины.
private fun operation(name: String, w: Width?, args: List<Expr>): Expr {
    fun arity(n: Int) {
        if (args.size != n) fail("$name: неверное число аргументов ${args.size}")
    }
    ExtOp.entries.find { it.irName == name }?.let { op ->
        if (w == null) fail("$name: нет поля w")
        arity(1)
        return Expr.Ext(op, w, args[0])
    }
    if (w != null) fail("$name: лишнее поле w")
    BinOp.entries.find { it.irName == name }?.let { op ->
        arity(2)
        return Expr.Bin(op, args[0], args[1])
    }
    CmpOp.entries.find { it.irName == name }?.let { op ->
        arity(2)
        return Expr.Cmp(op, args[0], args[1])
    }
    UnOp.entries.find { it.irName == name }?.let { op ->
        arity(1)
        return Expr.Un(op, args[0])
    }
    fail("неизвестная операция \"$name\"")
}

private fun JsonElement.int(key: String): Int =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: fail("$key: ожидалось целое, получено $this")

private fun JsonElement.string(key: String): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$key: ожидалась строка, получено $this")

private fun JsonElement.exprs(json: Json): List<Expr> =
    (this as? JsonArray ?: fail("args: ожидался массив, получено $this")).map { parseExpr(it, json) }
