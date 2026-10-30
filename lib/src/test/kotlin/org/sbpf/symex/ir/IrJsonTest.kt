package org.sbpf.symex.ir

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class IrJsonTest {
    private val strict = Json(IrJson) { ignoreUnknownKeys = false }

    private inline fun <reified T> decode(text: String) = strict.decodeFromString<T>(text)

    private fun module(block: String = """{"start": 0, "noreturn": false, "statements": [], "exit": {"kind": "return", "pc": 0}}""", extra: String = "") = """{
        "format_version": 1,
        "sbpf_version": 3,
        "entrypoint": 0,
        "text_vaddr": "0x100000000",
        "ro_region": {"vaddr": "0x0", "data": "c2JwZg=="},
        "address_taken": [76, 1891],
        "functions": [{"entry": 0, "name": "entrypoint", "blocks": [$block]}]$extra
    }"""

    // Блок bb_85 из примера в docs/ir.md.
    @Test
    fun docExampleBlock() {
        val block = decode<IrBlock>(
            """{
              "start": 85,
              "noreturn": false,
              "statements": [
                { "pc": 85, "op": "check", "error": "DivideByZero",
                  "cond": { "op": "ne", "args": [{ "reg": 3 }, { "const": "0x0", "w": 64 }] } },
                { "pc": 85, "op": "assign", "dst": 2,
                  "value": { "op": "udiv", "args": [{ "reg": 2 }, { "reg": 3 }] } },
                { "pc": 86, "op": "store", "w": 64,
                  "addr": { "op": "add", "args": [{ "reg": 1 }, { "const": "0x8", "w": 64 }] },
                  "value": { "reg": 2 } },
                { "pc": 87, "op": "assign", "dst": 2, "value": { "const": "0x0", "w": 64 } }
              ],
              "exit": { "kind": "next", "target": 88 }
            }""",
        )
        assertEquals(85, block.start)
        assertEquals(false, block.noreturn)
        assertEquals(4, block.statements.size)

        val check = assertIs<Stmt.Check>(block.statements[0])
        assertEquals(85, check.pc)
        assertEquals("ne(r3, 0)", check.cond.toString())
        assertSame(Fault.DivideByZero, check.error)

        val div = assertIs<Stmt.Assign>(block.statements[1])
        assertEquals(85, div.pc)
        assertEquals(2, div.dst)
        assertEquals("udiv(r2, r3)", div.value.toString())

        val store = assertIs<Stmt.Store>(block.statements[2])
        assertEquals(86, store.pc)
        assertSame(Width.W64, store.width)
        assertEquals("add(r1, 8)", store.addr.toString())
        assertEquals("r2", store.value.toString())

        val zero = assertIs<Stmt.Assign>(block.statements[3])
        assertEquals(87, zero.pc)
        assertEquals("0", zero.value.toString())

        val exit = assertIs<Exit.Next>(block.exit)
        assertEquals(88, exit.target)
        assertNull(exit.pc)
    }

    @Test
    fun load() {
        val load = assertIs<Stmt.Load>(decode<Stmt>("""{"pc": 10, "op": "load", "dst": 1, "w": 8, "addr": {"reg": 2}}"""))
        assertEquals(10, load.pc)
        assertEquals(1, load.dst)
        assertSame(Width.W8, load.width)
        assertEquals("r2", load.addr.toString())
    }

    @Test
    fun exits() {
        val ja = assertIs<Exit.Next>(decode<Exit>("""{"kind": "next", "target": 88, "pc": 92}"""))
        assertEquals(88, ja.target)
        assertEquals(92, ja.pc)

        val branch = assertIs<Exit.Branch>(
            decode<Exit>(
                """{"kind": "branch", "pc": 84, "then": 90, "else": 85,
                    "cond": {"op": "eq", "args": [{"reg": 3}, {"const": "0x0", "w": 64}]}}""",
            ),
        )
        assertEquals(84, branch.pc)
        assertEquals("eq(r3, 0)", branch.cond.toString())
        assertEquals(90, branch.then)
        assertEquals(85, branch.elseTarget)

        val call = assertIs<Exit.Call>(decode<Exit>("""{"kind": "call", "pc": 7, "target": 84, "next": null}"""))
        assertEquals(7, call.pc)
        assertEquals(84, call.target)
        assertNull(call.next)

        val callx = assertIs<Exit.Callx>(decode<Exit>("""{"kind": "callx", "pc": 7, "reg": 5, "next": 8}"""))
        assertEquals(5, callx.reg)
        assertEquals(8, callx.next)

        // Хеш syscall'а — u32: значения от 2³¹ не помещаются в Int.
        val syscall = assertIs<Exit.Syscall>(
            decode<Exit>("""{"kind": "syscall", "pc": 7, "hash": 4000000000, "name": null, "next": 8}"""),
        )
        assertEquals(4_000_000_000u, syscall.hash)
        assertNull(syscall.name)
        assertEquals(8, syscall.next)

        val named = assertIs<Exit.Syscall>(
            decode<Exit>("""{"kind": "syscall", "pc": 7, "hash": 544561597, "name": "sol_log_", "next": 8}"""),
        )
        assertEquals("sol_log_", named.name)

        assertEquals(89, assertIs<Exit.Return>(decode<Exit>("""{"kind": "return", "pc": 89}""")).pc)

        val trap = assertIs<Exit.Trap>(decode<Exit>("""{"kind": "trap", "pc": 9, "error": "ExecutionOverrun"}"""))
        assertEquals(9, trap.pc)
        assertSame(Fault.ExecutionOverrun, trap.error)
    }

    @Test
    fun nullableFieldsAreRequired() {
        assertFailsWith<SerializationException> { decode<Exit>("""{"kind": "call", "pc": 7, "target": 84}""") }
        assertFailsWith<SerializationException> { decode<Exit>("""{"kind": "syscall", "pc": 7, "hash": 1, "next": 8}""") }
    }

    @Test
    fun moduleHeader() {
        val module = parseIr(module(), strict)
        assertEquals(1, module.formatVersion)
        assertEquals(3, module.sbpfVersion)
        assertEquals(0, module.entrypoint)
        assertEquals(0x1_0000_0000uL, module.textVaddr)
        assertEquals(0uL, module.roRegion.vaddr)
        assertContentEquals("sbpf".encodeToByteArray(), module.roRegion.data)
        assertEquals(listOf(76, 1891), module.addressTaken)
        assertEquals("entrypoint", module.functions.single().name)
    }

    @Test
    fun formatVersion() {
        // Версия проверяется до разбора: остальные поля нового формата могут быть любыми.
        val newer = assertFailsWith<SerializationException> { parseIr("""{"format_version": 2, "functions": "?"}""") }
        assertContains(newer.message!!, "format_version 2")

        val missing = assertFailsWith<SerializationException> {
            parseIr(module().replace("\"format_version\": 1,", ""))
        }
        assertContains(missing.message!!, "format_version")

        assertFailsWith<SerializationException> { parseIr(module().replace("\"format_version\": 1", "\"format_version\": \"1\"")) }
    }

    @Test
    fun badBase64() {
        assertFailsWith<SerializationException> { parseIr(module().replace("c2JwZg==", "c2JwZg=")) }
    }

    @Test
    fun unknownFields() {
        val cases = listOf(
            module(extra = """, "future": 1"""),
            module(
                block = """{"start": 0, "noreturn": false, "exit": {"kind": "return", "pc": 1},
                    "statements": [{"pc": 0, "op": "assign", "dst": 0, "value": {"reg": 1}, "future": 1}]}""",
            ),
            module(block = """{"start": 0, "noreturn": false, "statements": [], "exit": {"kind": "return", "pc": 0, "future": 1}}"""),
        )
        for (text in cases) {
            assertFailsWith<SerializationException>(text) { parseIr(text, strict) }
            parseIr(text)
        }
    }

    @Test
    fun equalStatementsAreDistinct() {
        val text = """{"pc": 0, "op": "assign", "dst": 2, "value": {"const": "0x0", "w": 64}}"""
        assertNotEquals(decode<Stmt>(text), decode<Stmt>(text))
    }
}
