package org.sbpf.symex.ir

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
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
// Вид узла — поле `kind` у statement'ов, выходов блока и выражений.
val IrJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
}

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
            val at = "block ${block.start}, pc ${stmt.pc}"
            when (stmt) {
                is Stmt.Assign -> {
                    checkDst(stmt.dst, at)
                    checkExpr(stmt.value, at)
                }
                is Stmt.Load -> {
                    checkDst(stmt.dst, at)
                    checkExpr(stmt.addr, at)
                }
                is Stmt.Store -> {
                    checkExpr(stmt.addr, at)
                    checkExpr(stmt.value, at)
                }
                is Stmt.Check -> checkExpr(stmt.cond, at)
            }
        }
        when (val exit = block.exit) {
            is Exit.Branch -> checkExpr(exit.cond, "block ${block.start}, pc ${exit.pc}")
            is Exit.Callx -> if (exit.reg !in 0..9) fail("block ${block.start}, pc ${exit.pc}: callx via r${exit.reg}")
            is Exit.Next, is Exit.Call, is Exit.Syscall, is Exit.Return, is Exit.Trap -> {}
        }
    }
}

// В r10 писать нельзя.
private fun checkDst(dst: Int, at: String) {
    if (dst !in 0..9) fail("$at: write to r$dst")
}

private fun checkExpr(expr: Expr, at: String) {
    when (expr) {
        is Expr.Reg -> if (expr.index !in 0..10) fail("$at: no register r${expr.index}")
        is Expr.Const -> if (expr.value and expr.width.mask.inv() != 0uL) {
            fail("$at: constant ${expr.value} does not fit in ${expr.width.bits} bits")
        }
        is Expr.Bin -> {
            checkExpr(expr.a, at)
            checkExpr(expr.b, at)
        }
        is Expr.Cmp -> {
            checkExpr(expr.a, at)
            checkExpr(expr.b, at)
        }
        is Expr.Un -> checkExpr(expr.a, at)
        is Expr.Ext -> checkExpr(expr.a, at)
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

internal object WidthSerializer : KSerializer<Width> {
    override val descriptor = PrimitiveSerialDescriptor("org.sbpf.symex.ir.Width", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Width {
        val bits = decoder.decodeInt()
        return Width.ofBits(bits) ?: fail("invalid width $bits")
    }

    override fun serialize(encoder: Encoder, value: Width) = readOnly()
}

private fun JsonElement.int(key: String): Int =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: fail("$key: expected an integer, got $this")
