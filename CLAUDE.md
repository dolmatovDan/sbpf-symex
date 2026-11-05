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
  - `SbpfApplicationGraph`: a step is one statement or a block exit.
  - `SbpfState`: registers r0–r10 in `URegistersStack` frames (`memory.stack`), path `result` (`Exited`/`Failed`), trace. `clone` follows `JcState.clone`.
  - `Entry.kt`: initial registers as agave 4.3 VM sets them (r1 input region, r2 instruction data, r10 stack top, others 0).
  - `SbpfInterpreter`: `when` over statement kind; `check` forks a failing path. `ExprTranslator` maps IR ops 1:1 to KSMT bitvector terms via `SbpfContext` helpers (`zext`/`sext`/`trunc`/`bswap`).
  - `SbpfMachine`: DFS, step limit, `throwExceptionOnStepFailure = true`; `results()` must be read before `close()` (it owns the solver and context). `TraceObserver` records r0–r10 + pc before each instruction in the runner's format.
- `result/PathResult` — the only boundary tests see: status, error, final registers, trace.

### Testing model

The oracle is the real runner (`sbpf-lift run`, Mollusk = agave 4.3), never a hand-written IR interpreter. `test/.../runner/SbpfRunner` invokes `sbpf-lift lift` (parsed with strict Json, `ignoreUnknownKeys = false`, to notice new lifter fields) and `run --registers --trace` live — no fixtures. For v3 it writes a copy of the example pointing at `.v3.so`.

`DiffTest` generates one dynamic test per `sbpf-ir/tests/examples/asm/*.json` × {v0, v3}; adding an example in sbpf-ir adds a test. `compareWithRunner` (`Compare.kt`) checks: exactly one path → status/error → registers (success only) → trace, reporting the first diverging entry. `machine/IrBuilders.kt` builds IR by hand for unit tests.

## Environment pitfalls

- usvm comes from JitPack pinned to commit `6b2a078…`. Before bumping, check that JitPack built the commit and that KSMT/collections versions in usvm's `Dependencies.kt` are unchanged. Local `includeBuild("../usvm")` is deferred: usvm does not configure with Gradle 9 (`usvm-jvm/build.gradle.kts`).
- All `io.ksmt:*` must match usvm's version exactly (0.5.26, also `kotlinx-collections-immutable` 0.3.8); Gradle silently upgrades on conflict and usvm then fails at runtime with `NoSuchMethodError`.
- Native Yices exists only for linux-x64, mac-arm, windows-x64.

## Conventions

- Code comments in English; README, docs and commit messages in Russian.
