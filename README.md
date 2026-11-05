# sbpf-symex

Символьная машина для смарт-контрактов Solana (sBPF) на базе [usvm](https://github.com/UnitTestBot/usvm).

Это вторая часть проекта. Первая — [`sbpf-ir`](../sbpf-ir): лифтер переводит скомпилированный контракт (`.so`) в IR, формат которого описан в [`sbpf-ir/docs/ir.md`](../sbpf-ir/docs/ir.md). Этот репозиторий читает IR и исполняет его символьно: значения регистров и памяти — формулы от неизвестных входов, на ветвлениях путь раздваивается, солвер подбирает входы для каждого пути. Байткод, ELF и CFG здесь не разбираются: всё это делает лифтер.

## Как это устроено

```
IR (JSON, sbpf-lift lift)
   │  загрузка                  — kotlinx.serialization
   ▼
модель IR: функции, блоки, statement'ы, выражения
   │  граф программы            — ApplicationGraph usvm
   ▼
символьная машина               — UMachine usvm
   │  интерпретатор: один statement или выход блока за шаг
   │  состояние: регистры, память, условия пути
   │  формулы и солвер          — KSMT (Yices)
   ▼
завершённые пути: регистры, ошибка, модель входов
   │
   ▼
сравнение с конкретным запуском (sbpf-lift run, Mollusk)
```

- **Загрузка IR.** JSON из `sbpf-lift lift` превращается в `sealed`-иерархии Kotlin: statement'ы, выходы блока и выражения; вид узла у всех — поле `kind`. Проверяются `format_version`, `sbpf_version` (0 или 3), номера регистров (чтение `r0`–`r10`, запись и `callx` — `r0`–`r9`) и то, что константы помещаются в свою ширину. Незнакомые необязательные поля игнорируются, как требует контракт.
- **Граф программы.** `ApplicationGraph` над функциями и блоками IR: шаг — это statement или выход блока; `pc` есть у всех, кроме `next` без `ja`. Преемники берутся из выходов блоков (`next`, `branch`, `call`...).
- **Состояние.** Наследник `UState`. Регистры `r0`–`r10` лежат в кадрах `URegistersStack` (`memory.stack`), как описано в `ir.md`. На входе они константы, как их выставляет VM agave 4.3 (`Entry.kt`): `r1` — начало входного региона, `r2` — адрес данных инструкции, `r10` — вершина стека, остальные 0. Остальное — условия пути, pc (`pathNode`), итог пути `result` (`Exited` — исполнен `exit`, `Failed` — ошибка VM) и трасса.
- **Интерпретатор.** `UInterpreter.step`: `when` по виду statement'а. Выражения IR один к одному переводятся в битвекторные термы KSMT через `UContext`. `check` — развилка: путь, где условие ложно, завершается ошибкой и тоже попадает в результаты.
- **Машина.** `UMachine.run` с селектором путей, наблюдателями (сбор завершённых путей, счётчик шагов, трасса) и стоп-стратегиями. `TraceObserver` по флагу пишет перед каждой инструкцией `r0`–`r10` и `pc`, как раннер: одна запись на инструкцию, у `check` — на нём самом.
- **Результаты и сравнение.** Из модели завершённого пути берутся итоговые регистры и трасса; значения входов — когда входы станут символьными. Эталон — конкретный раннер `sbpf-lift run` (Mollusk, тот же код, что у валидатора agave 4.3), а не собственный интерпретатор: свой эталон повторял бы те же ошибки понимания семантики и не видел бы ошибок лифтера.

## Использование

`SbpfMachine` исполняет программы из одного блока (`assign`, `check`, `return`): `SbpfMachine(module, recordTrace = true).use { it.results() }` даёт по пути `PathResult` — статус, ошибку, `r0`–`r10` в конце и трассу. Машина держит солвер Yices и контекст, поэтому результаты читаются до `close`. Остальные разделы появятся по мере готовности.

```sh
./gradlew build
./gradlew test
```

Тесты запускают лифтер и раннер `sbpf-lift` из соседней папки `../sbpf-ir` (другой путь — `-PsbpfIr=<путь>`, относительный — от корня репозитория). Задача `buildSbpfLift` перед тестами делает `cargo build --release` в `sbpf-ir`, поэтому нужен Rust. Версия `sbpf-ir` не закреплена: берётся то, что лежит в папке.

CI (`.github/workflows/ci.yml`) скачивает `sbpf-ir` (`main`) рядом, ставит Rust из его `rust-toolchain.toml` с кэшем `target/` и запускает `./gradlew build` на JDK 21.

## Тестирование

Каждый тест сравнивает символьную машину с раннером на одном и том же контракте:

```
X.s / X.rs ──► X.so ──┬── sbpf-lift lift ──► IR JSON ──► sbpf-symex ──► статус, регистры, трасса ──┐
                      └── sbpf-lift run --registers --trace ──► Outcome JSON ───────────────────────┴─► сравнить
```

Эталон — раннер, а не собственный интерпретатор IR. Первые тесты — программы на ассемблере из [`sbpf-ir/tests/programs/asm/`](../sbpf-ir/tests/programs/asm) (`alu64`, `alu32`, `shift64`, `shift32`, `divmod`, `mov`, `endian`): один блок, только регистры и арифметика (`divmod` — ещё `check` перед делением на регистр).

### Лифтер и раннер вживую

Фикстур нет: `SbpfRunner` (`lib/src/test/kotlin/org/sbpf/symex/runner/`) вызывает `sbpf-lift` прямо из тестов, как TSA вызывает fift.

- `lift(so)` разбирает IR строгим Json (`ignoreUnknownKeys = false`): так тесты замечают новые поля лифтера.
- `run(example, version)` исполняет пример раннера с `--registers --trace`. Все примеры указывают на `.v0.so`, поэтому для v3 пишется копия примера с путём `.v3.so`.
- У задачи `test` входами объявлены бинарь `sbpf-lift`, `tests/examples` и `tests/programs/bin`: после правки лифтера или примеров тесты перезапускаются, а не считаются UP-TO-DATE.

Входы известны (регистры как у VM, данных нет), поэтому у каждой программы ровно один путь.

### Граница между машиной и тестами

Тесты не знают устройства машины. Они видят только результат путей, `SbpfMachine.results(): List<PathResult>` (`lib/src/main/kotlin/org/sbpf/symex/result/`):

```kotlin
class PathResult(
    val status: PathStatus,          // success / failure, как status раннера
    val error: String?,              // вид ошибки
    val registers: List<ULong>,      // r0–r10 в конце пути
    val trace: List<List<ULong>>,    // r0–r10 и pc перед каждой инструкцией
)
```

Ошибка пока — имя `Fault` из IR (`DivideByZero`), а не `InstructionError` раннера; ненулевой `r0` на выходе — `NotImplementedError`. Перевод появится вместе с примерами, которые падают. Позже `PathResult` дополнится моделью входов.

### Сравнение

Одна общая функция `compareWithRunner` (`lib/src/test/kotlin/org/sbpf/symex/Compare.kt`) по образцу `compareSymbolicAndConcreteResults` из TSA (`tsa-test/src/main/kotlin/org/ton/test/utils/TestUtils.kt`):

1. ровно один путь;
2. статус и вид ошибки;
3. регистры `r0`–`r10` — только у успешного пути, у упавшего значения регистров бессмысленны;
4. трасса — всегда: промежуточное значение, перекрытое следующей записью в тот же регистр, видно только в ней. Сообщение называет первую расходящуюся запись (индекс, `pc`, регистры).

Модель ответа раннера (`Outcome`: `status`, `error`, `registers`, `trace`) читается по [`sbpf-ir/docs/runner.md`](../sbpf-ir/docs/runner.md). Числа там — `u64`, в Kotlin это `ULong`, не `Long`.

`DiffTest` параметризованный: один случай на каждую пару «пример из `sbpf-ir/tests/examples/asm/` × версия sBPF», без ручного списка. Новый asm-пример в `sbpf-ir` сам становится тестом.

## Структура

Структура первой версии:

```
sbpf-symex/
├── settings.gradle.kts         # подпроекты
├── gradle/libs.versions.toml   # версии: usvm (хэш коммита), KSMT, kotlinx
└── lib/src/
    ├── main/kotlin/org/sbpf/symex/
    │   ├── ir/                 # модель IR и загрузка JSON
    │   ├── machine/            # SbpfContext, SbpfState, SbpfInterpreter, SbpfMachine, TraceObserver,
    │   │                       # SbpfApplicationGraph, SbpfComponents, перевод выражений, Entry (регистры VM)
    │   └── result/             # PathResult: статус, ошибка, регистры и трасса завершённого пути
    └── test/kotlin/org/sbpf/symex/
        ├── runner/             # SbpfRunner (sbpf-lift lift/run вживую), модель Outcome
        ├── machine/, ir/       # тесты машины и модели IR
        └── …                   # compareWithRunner, DiffTest
```

Образец устройства — `usvm-sample-language` в usvm (`SampleState`, `SampleInterpreter`, `SampleMachine`, `ResultModelConverter`) и анализатор TON [TSA](https://github.com/espritoxyz/tsa) (`TvmMachine`, `TvmStepScopeManager`, дифференциальные тесты в `tsa-test`).

## Окружение

| Инструмент | Версия | Установка |
|---|---|---|
| JDK | 21 | `brew install openjdk@21` |
| Gradle | wrapper (`./gradlew`) | — |
| Kotlin | 2.4.10 (плагин в `libs.versions.toml`) | — |
| usvm | `6b2a0781a728d14ca9271b33830c8db734157e54` | JitPack, `com.github.UnitTestBot.usvm:usvm-core` |
| KSMT | 0.5.26 — **та же, что в usvm** | Maven Central, `io.ksmt` |

- `./gradlew` требует установленную Java. `foojay-resolver` в `settings.gradle.kts` скачивает JDK только для компиляции.
- usvm закреплена на `6b2a078` — `main` после [UnitTestBot/usvm#634](https://github.com/UnitTestBot/usvm/pull/634) и [#635](https://github.com/UnitTestBot/usvm/pull/635). До #634 JitPack не собирал ни один коммит usvm новее 20.09.2026: его команда по умолчанию запускала `assemble`, а TS-модули usvm при этом вызывают `npm`, которого в образе JitPack нет. #634 добавил `jitpack.yml`, который запускает только `publishToMavenLocal`. Перед переходом на новый коммит: проверить, что JitPack его собрал (`https://jitpack.io/com/github/UnitTestBot/usvm/<хэш>/build.log`), и что версии KSMT и collections-immutable в `usvm/buildSrc/src/main/kotlin/Dependencies.kt` не поменялись. [`../usvm-notes.md`](../usvm-notes.md) написан по более старому `2c4f17c4`; из заметных отличий в `usvm-core` — `StepLimitStopStrategy` останавливается на `totalSteps >= limit`, а не `>`.
- Локальной usvm (`includeBuild("../usvm")` по флагу) пока нет. Наш Gradle 9.8.2 usvm не конфигурирует: импорты сгенерированных аксессоров в `buildSrc` убраны в #634, но `usvm-jvm/build.gradle.kts` использует `resolvedConfiguration.files`, которого в Gradle 9 нет. Когда понадобится править usvm: собирать usvm её собственным Gradle, публиковать в `mavenLocal` и подставлять по флагу, так версия Gradle не важна.
- Нативная Yices в `ksmt-yices` 0.5.26 есть только для linux-x64, mac-arm и windows-x64. На Intel Mac и linux-arm64 тесты с солвером не запустятся.
- Все артефакты `io.ksmt:*` — ровно той версии, с которой собрана usvm (`usvm/buildSrc/src/main/kotlin/Dependencies.kt`). При конфликте Gradle молча поднимает версию до старшей, и usvm падает во время работы с `NoSuchMethodError`. Проверка: `./gradlew :lib:dependencyInsight --dependency ksmt-core`.
- Для Kotlin-тестов нужны соседняя папка `sbpf-ir` и Rust (toolchain из её `rust-toolchain.toml`): `sbpf-lift` собирается из исходников. Solana CLI не нужен — `.so` тестовых программ лежат в git `sbpf-ir`.

## План

### Подготовка
- [x] Установить JDK 21, проверить сборку шаблона
- [x] Каталог версий: usvm по хэшу через JitPack, KSMT 0.5.26 + Yices, `kotlinx-serialization-json`, `kotlinx-collections-immutable`; убрать шаблонные `guava` и `commons-math3`
- [ ] `includeBuild("../usvm")` с `dependencySubstitution` по флагу `-PlocalUsvm` — отложено, причина в «Окружении»
- [x] CI: сборка и тесты

### IR в Kotlin
- [x] Типы IR: `sealed` statement'ы, выходы блока, выражения; обычные `class`, не `data class` (разные места программы — разные ключи)
- [x] Загрузка JSON: дискриминатор `kind` у statement'ов, выходов и выражений, числа `"0x…"` как `ULong`
- [x] Проверка `format_version`; в тестах — строгий режим (`ignoreUnknownKeys = false`), чтобы заметить новые поля лифтера

### Первая версия: регистры и арифметика
- [x] `SbpfContext`: хелперы расширения и усечения (`zext`, `sext`, `trunc` с правильными аргументами KSMT), `bswap`
- [x] Перевод выражений IR в KSMT: все операции из таблицы `ir.md`
- [x] `SbpfApplicationGraph`: полный по выходам блоков, исполняются `assign`, `check` и `return`
- [x] `SbpfState`: регистры в `memory.stack`, итог пути `result`, `clone` по образцу `JcState.clone` (`SampleState` не обновляет жетон оригинала)
- [x] `SbpfInterpreter`: `assign`, `return`, `check`
- [x] `SbpfMachine`: DFS, сбор завершённых путей, лимит шагов, `throwExceptionOnStepFailure = true`

### Тестовая инфраструктура
- [x] Регистры на входе как у VM agave 4.3 (`r1`, `r2`, `r10`; остальные 0)
- [x] Трасса машины: `TraceObserver`, одна запись на инструкцию, формат раннера
- [x] `SbpfRunner`: `sbpf-lift lift` и `run` вживую, без фикстур; сборка `sbpf-lift` из Gradle, входы `sbpf-ir` у задачи `test`
- [x] Модель ответа раннера `Outcome` по `docs/runner.md` (`ULong`)
- [x] `PathResult` и `SbpfMachine.results()`
- [x] Общая функция сравнения: один путь → статус и ошибка → регистры → трасса с первой расходящейся записью
- [x] `DiffTest` по `sbpf-ir/tests/examples/asm/*.json`, без ручного списка
- [x] CI: `sbpf-ir` рядом, Rust, кэш `target/`
- [x] Все 7 asm-программ под v0 и v3 зелёные: статус, регистры `r0`–`r10` и трасса совпадают с раннером, путь ровно один

### Ветвления и ошибки
- [ ] Выходы `next`, `branch`, `trap`; у `trap ExecutionOverrun` за концом кода записи трассы нет (VM падает до записи)
- [x] `check` — развилка с путём-ошибкой
- [ ] Перевод `Fault` и ненулевого `r0` в ошибку раннера (`InstructionError`)
- [ ] Ограничение циклов
- [ ] Тест: статус и ошибка совпадают с раннером на всех путях

### Память
- [ ] Модель регионов sBPF: input, стек, куча, `ro_region`; расположение в v0 и v3
- [ ] `load` / `store`, `AccessViolation`
- [ ] Входной регион: сериализованные аккаунты и данные инструкции по адресу `r1`; `r2` с учётом аккаунтов (сейчас — вход без аккаунтов)
- [ ] Символьные `instruction_data` и аккаунты

### Вызовы и syscall'ы
- [ ] `call` / `return` по кадрам (`ir.md`, «`call` и `return`»), `CallDepthExceeded`
- [ ] `callx`: развилка по `address_taken`, `CallOutsideTextSegment`
- [ ] Модели syscall'ов, начиная с `sol_log_*` и `sol_mem*`
- [ ] Учёт CU

### Результаты
- [ ] Генерация входа раннера (`instruction_data`, аккаунты) из модели пути: каждый найденный путь проверяется конкретным запуском через `SbpfRunner`
- [ ] Прогон на контрактах из `sbpf-ir/tests/programs` и мейннета

## Ссылки

- [`sbpf-ir/docs/ir.md`](../sbpf-ir/docs/ir.md) — формат и семантика IR, контракт с лифтером
- [`sbpf-ir/docs/runner.md`](../sbpf-ir/docs/runner.md) — формат конкретного раннера
- [usvm](https://github.com/UnitTestBot/usvm) — ядро символьной машины; образец — модуль `usvm-sample-language`
- [KSMT](https://github.com/UnitTestBot/ksmt) — формулы и солверы
- [TSA](https://github.com/espritoxyz/tsa) — символьный анализатор TON на usvm
- [SSEREX: Practical Symbolic Execution of Solana Smart Contracts](https://arxiv.org/abs/2603.16349) — статья, на которую опирается проект
- [`../usvm-notes.md`](../usvm-notes.md) — конспект разбора usvm и TSA
