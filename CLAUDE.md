# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Symbolic execution engine for Solana sBPF contracts, built on [usvm](https://github.com/UnitTestBot/usvm) (Kotlin, JDK 21, single Gradle subproject `lib`). It does **not** parse bytecode/ELF/CFG: it consumes the JSON IR produced by the sibling repo `../sbpf-ir` (`sbpf-lift lift`). The IR contract is `../sbpf-ir/docs/ir.md`; the concrete runner format is `../sbpf-ir/docs/runner.md`. Both repos are one project — the IR format may be changed in `sbpf-ir` when it simplifies code here (no `format_version` bump needed).

README.md (in Russian) is the main design doc and roadmap ("План" checklist). Keep it current in the same task as the code change.

## Commands

```sh
./gradlew build                                   # compile + all tests
./gradlew :lib:test                               # tests only
./gradlew :lib:test --tests 'org.sbpf.symex.machine.SbpfMachineTest'   # one class
./gradlew :lib:test --tests 'org.sbpf.symex.DiffTest'                  # differential tests vs. runner
./gradlew :lib:dependencyInsight --dependency ksmt-core                # check KSMT version wasn't bumped
```

- `test` depends on `buildSbpfLift`, which runs `cargo build --release -p sbpf-lift-cli` in `../sbpf-ir` (override path with `-PsbpfIr=<path>`, relative to repo root). So tests need Rust and the sibling `sbpf-ir` checkout; its version is not pinned.
- The sbpf-lift binary, `sbpf-ir/tests/examples` and `sbpf-ir/tests/programs/bin` are declared test inputs, so changing the lifter re-runs tests.
- CI (`.github/workflows/ci.yml`) checks out `sbpf-ir` main next to this repo and runs `./gradlew build`.

## Architecture

Pipeline: IR JSON → Kotlin model (`ir/`) → usvm `ApplicationGraph` → `UMachine` with KSMT/Yices → `PathResult` per finished path → compared against the concrete runner.

- `ir/` — `sealed` hierarchies for statements, block exits and expressions, discriminated by the `kind` field; hex numbers `"0x…"` as `ULong`. Validates `format_version`, `sbpf_version` (0 or 3), register ranges, constant widths. Plain `class`, not `data class`: distinct program points must be distinct keys.
- `machine/` — usvm plug-in following `usvm-sample-language` and TSA:
  - `SbpfApplicationGraph`: a step is one statement or a block exit; `blockEntry(start)` is the first step of a block, where exits lead.
  - `SbpfState`: registers r0–r10 in `URegistersStack` frames (`memory.stack`), path `result` (`Exited`/`Failed`/`AccessViolation`), trace, `memoryModelOrNull` (memory model, see below). `clone` follows `JcState.clone` and must copy every field.
  - `Entry.kt`: initial registers as agave 4.3 VM sets them (r1 input region, r2 instruction data, r10 stack top, others 0).
  - `SbpfInterpreter`: `when` over step kind; `branch` forks into then/else (no fork when then == else), `check` forks a failing path, `trap` ends the path with its error, `load`/`store` go through `memory/` (constant address: checked in Kotlin; symbolic: one fork on the bounds condition, `AccessViolation` on the other side). Calls and syscalls are not executed yet. `ExprTranslator` maps IR ops 1:1 to KSMT bitvector terms via `SbpfContext` helpers (`zext`/`sext`/`trunc`/`bswap`).
  - `memory/`: `MemoryLayout` (regions for the sBPF version: rodata from `ro_region`, stack, heap, input; `findRegion`, 32-bit `key = (slot << 28) | offset` and its symbolic twin, `accessCondition`), `Input.kt` (`InputSpec`: input region without accounts, `IxMode.Concrete`/`Symbolic`, base58), `SbpfMemory.kt` (one usvm byte array `mem` for all writable regions, instruction data copied from the input array `input0` by `memcpy`, non-zero layout bytes as constants, concrete ix as path constraints; rodata lives in its own array `ro`, a symbolic load reads `ite(in rodata, ro[offset], mem[key])`; byte folding `mergedExtract` because KSMT folds `concat(extract…)` only in its full rewriter). Details and the VM analysis: `../memory-notes.md`.
  - `Loops.kt` + `SbpfLoopTracker`: natural loops on the module-wide block graph (dominators from a virtual root over all function entries; a jump into another function is an ordinary edge), fork points via post-dominators of the iteration graph (as `JcLoopTracker`); feeds usvm `LoopLimiterPs`. `loopIterationLimit = L` keeps paths with 0..L symbolic iterations (passed as is, TSA passes L - 1); cut paths are dropped silently; irreducible loops are not found.
  - `SbpfMachine(module, options, recordTrace, input: InputSpec, heapSize)`: `input` is the program id + instruction data (`IxMode.Concrete`/`Symbolic`), `heapSize` defaults to `DEFAULT_HEAP_SIZE` (32 KiB). DFS wrapped in `LoopLimiterPs`, step limit, `throwExceptionOnStepFailure = true`; `results()` must be read before `close()` (it owns the solver and context). `TraceObserver` records r0–r10 + pc before each instruction in the runner's format (none for `next` without pc and for `trap ExecutionOverrun`, which the VM raises before tracing).
  - `SbpfTypeSystem.topTypeStream` is a single-type stream (as TSA): with an empty one the type solver rejects every state once a static ref (`input0`) exists.
- `result/PathResult` — the only boundary tests see: status, error, final registers, trace.

### Testing model

The oracle is the real runner (`sbpf-lift run`, Mollusk = agave 4.3), never a hand-written IR interpreter. `test/.../runner/SbpfRunner` invokes `sbpf-lift lift` (parsed with strict Json, `ignoreUnknownKeys = false`, to notice new lifter fields) and `run --registers --trace` live — no fixtures. An example on `.v0.so` runs on v0 and v3 (for v3 a copy of the example pointing at `.v3.so` is written); one on `.v3.so` runs only on v3 (`versionsOf`).

`DiffTest` generates one dynamic test per `sbpf-ir/tests/examples/asm/*.json` × its versions ({v0, v3}, or {v3} for `.v3.so`); adding an example in sbpf-ir adds a test. The machine gets the example's `program_id` and `instruction_data` (`SbpfRunner.inputOf`, concrete mode), so there is still exactly one path. `compareWithRunner` (`Compare.kt`) checks: exactly one path → status/error → registers (success only) → trace, reporting the first diverging entry. The runner does not return memory (agave frees stack and heap before Mollusk's hook), so asm programs read every store back into a register before `exit`. `AccessViolation` paths are unit-tested only: the runner reports them as `ProgramFailedToComplete` with the reason in the logs, and the error mapping is not done yet. `machine/IrBuilders.kt` builds IR by hand for unit tests (`module(sbpfVersion, rodata)` places rodata at the start of the version's slot, as the loader does).

## Environment pitfalls

- usvm comes from JitPack pinned to commit `6b2a078…`. Before bumping, check that JitPack built the commit and that KSMT/collections versions in usvm's `Dependencies.kt` are unchanged. Local `includeBuild("../usvm")` is deferred: usvm does not configure with Gradle 9 (`usvm-jvm/build.gradle.kts`).
- All `io.ksmt:*` must match usvm's version exactly (0.5.26, also `kotlinx-collections-immutable` 0.3.8); Gradle silently upgrades on conflict and usvm then fails at runtime with `NoSuchMethodError`.
- Native Yices exists only for linux-x64, mac-arm, windows-x64.

## Conventions

- Code comments in English; README, docs and commit messages in Russian.
