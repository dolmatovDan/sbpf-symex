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
- **Граф программы.** `ApplicationGraph` над функциями и блоками IR: шаг — это statement или выход блока; `pc` есть у всех, кроме `next` без `ja`. Преемники берутся из выходов блоков (`next`, `branch`, `call`...); `blockEntry(start)` — первый шаг блока, куда ведёт выход.
- **Состояние.** Наследник `UState`. Регистры `r0`–`r10` лежат в кадрах `URegistersStack` (`memory.stack`), как описано в `ir.md`. На входе они константы, как их выставляет VM agave 4.3 (`Entry.kt`): `r1` — начало входного региона, `r2` — адрес данных инструкции, `r10` — вершина стека, остальные 0. Остальное — память (ниже), условия пути, pc (`pathNode`), итог пути `result` (`Exited` — исполнен `exit`, `Failed` — ошибка VM из IR, `AccessViolation` — ошибка доступа к памяти) и трасса.
- **Интерпретатор.** `UInterpreter.step`: `when` по виду шага. Выражения IR один к одному переводятся в битвекторные термы KSMT через `UContext`. `branch` — развилка на `then` и `else` (при `then == else` развилки нет), `next` — переход в начало блока. `check` — развилка: путь, где условие ложно, завершается ошибкой и тоже попадает в результаты. `trap` завершает путь своей ошибкой. `load`/`store` с константным адресом проверяются в Kotlin без солвера и либо исполняются, либо завершают путь `AccessViolation`; с символьным — одна развилка по условию «доступ целиком внутри какого-то региона (для `store` — записываемого)», доступ исполняется на допустимой стороне. Вызовы и syscall'ы пока не исполняются.
- **Память** (`machine/memory/`, разбор VM — в [`../memory-notes.md`](../memory-notes.md)). Все регионы VM — rodata (`ro_region` IR: слот 1 в v0 вместе с кодом, слот 0 в v3), стек (`0x2_…`, 64 кадра по 4 КиБ = `0x40000`, без дыр между кадрами, как у раннера), куча (`0x3_…`, 32 КиБ по умолчанию, размер — параметр `heapSize` машины) и input (`0x4_…`) — лежат в одном байтовом массиве `mem`, allocated-массиве usvm: нетронутый байт равен 0, что и нужно стеку и куче. Ключ байта — 32-битный `(слот << 28) | смещение` (функция `key` в `MemoryLayout.kt`; ключи usvm — bv32 со знаковым сравнением, поэтому 28 бит, а не 29); он однозначен только для адресов, прошедших проверку границ, и проверка всегда идёт до обращения. Граница считается как `addr ≤ end − n`, чтобы `addr + n` не переполнялось; `store` пишет байты только после успешной проверки всего доступа, как VM. Input на старте копируется одним ranged-узлом из input-массива `input0`, чьи нетронутые байты — свежие символы; затем байты раскладки (число аккаунтов, длина `instruction_data`, `program_id`, выравнивание SIMD-0449) перезаписываются константами, так что символьными остаются только байты `instruction_data` (`InputSpec`: режим `Concrete` добавляет к условиям пути равенства `input0[k] == байт`, `Symbolic(len)` оставляет их свободными). Чтение rodata по константному адресу берёт байт прямо из `ByteArray`; по символьному — rodata копируется в `mem` одним ranged-узлом из массива `ro`, лениво и один раз на путь (в v0 это весь образ с кодом, и после копии каждое символьное чтение `mem` смотрит сквозь него); сам `ro` заполняется по узлу на байт один раз при старте и общий для всех путей. Слово собирается из байтов little-endian; соседние `extract` одного значения сворачиваются обратно в значение своей свёрткой: KSMT делает это только в полном переписывателе, не при построении терма, иначе `stxdw`/`ldxdw` превращали бы `r1` в `concat(extract(63, 56, r1), …)`.
- **Циклы** (`Loops.kt`, `SbpfLoopTracker`). Естественные циклы функции ищутся по графу блоков (доминаторы, обратные рёбра); точки развилки цикла — `branch` в теле, исход которых решает конец итерации (в графе одной итерации у них нет пост-доминатора, как у `JcLoopTracker` в usvm): выход из тела, переход на заголовок (`continue`), выход через блок с безусловным `ja`; развилка, обе стороны которой сходятся внутри итерации, — нет. `LoopLimiterPs` usvm считает итерации, на которых путь развилился в такой точке, и молча отбрасывает состояние, когда их больше `loopIterationLimit` (по умолчанию 10): остаются пути с 0…L символьными итерациями, циклы с конкретным условием не ограничены. Нередуцируемые циклы (вход в середину) не находятся — их держит только лимит шагов.
- **Машина.** `UMachine.run` с селектором путей (DFS в обёртке `LoopLimiterPs`), наблюдателями (сбор завершённых путей, счётчик шагов, трасса) и стоп-стратегиями. `TraceObserver` по флагу пишет перед каждой инструкцией `r0`–`r10` и `pc`, как раннер: одна запись на инструкцию, у `check` — на нём самом. У `next` без `pc` (провал в следующий блок) записи нет: это не инструкция. У `trap ExecutionOverrun` тоже нет: VM бросает эту ошибку до записи трассы.
- **Результаты и сравнение.** Из модели завершённого пути берутся итоговые регистры и трасса; значения входов (байты `instruction_data` в режиме `Symbolic`) пока не извлекаются — появятся с генерацией входа раннера из модели. Эталон — конкретный раннер `sbpf-lift run` (Mollusk, тот же код, что у валидатора agave 4.3), а не собственный интерпретатор: свой эталон повторял бы те же ошибки понимания семантики и не видел бы ошибок лифтера.

## Использование

`SbpfMachine` исполняет программы без вызовов (`assign`, `check`, `load`, `store`, переходы, `return`, `trap`): `SbpfMachine(module, recordTrace = true, input = InputSpec(programId, IxMode.Concrete(bytes))).use { it.results() }` даёт по пути `PathResult` — статус, ошибку, `r0`–`r10` в конце и трассу. `input` описывает входной регион без аккаунтов: `program_id` и `instruction_data` — конкретные байты (`IxMode.Concrete`) или неизвестная строка известной длины (`IxMode.Symbolic(len)`); по умолчанию `InputSpec.EMPTY` — пустые данные и нулевой `program_id`. Машина держит солвер Yices и контекст, поэтому результаты читаются до `close`. Остальные разделы появятся по мере готовности.

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

Эталон — раннер, а не собственный интерпретатор IR. Первые тесты — программы на ассемблере из [`sbpf-ir/tests/programs/asm/`](../sbpf-ir/tests/programs/asm) без вызовов. `alu64`, `alu32`, `shift64`, `shift32`, `divmod`, `mov`, `endian` — один блок, регистры и арифметика (`divmod` — ещё `check` перед делением на регистр). `jump` — все 64-битные условные переходы, `loop` — циклы, `jmp32` — 32-битные переходы (только v3). `memory` — стек всех ширин с перекрытием, куча, заголовок входа через `r1`, `instruction_data` через `r2` и `program_id` за ними; у примера непустой `instruction_data`. Раннер не отдаёт память (agave возвращает стек и кучу в пул сразу после исполнения, до хука Mollusk), поэтому каждая запись в `memory.s` читается обратно в регистр до `exit`, а трасса сравнивается на каждой инструкции: расхождение `store`/`load` видно в первой записи после чтения.

### Лифтер и раннер вживую

Фикстур нет: `SbpfRunner` (`lib/src/test/kotlin/org/sbpf/symex/runner/`) вызывает `sbpf-lift` прямо из тестов, как TSA вызывает fift.

- `lift(so)` разбирает IR строгим Json (`ignoreUnknownKeys = false`): так тесты замечают новые поля лифтера.
- `run(example, version)` исполняет пример раннера с `--registers --trace`. Пример с `.v0.so` собран под обе версии, для v3 пишется копия примера с путём `.v3.so`. Пример с `.v3.so` — программа только под v3 (например, jmp32, которого нет в v0); версии примера даёт `versionsOf`.
- У задачи `test` входами объявлены бинарь `sbpf-lift`, `tests/examples` и `tests/programs/bin`: после правки лифтера или примеров тесты перезапускаются, а не считаются UP-TO-DATE.

- `inputOf(example)` отдаёт машине `program_id` и `instruction_data` примера (`InputSpec`, режим `Concrete`); примеры с непустым `accounts` пока отклоняются (пустой список — та раскладка, которую моделируем).

Входы известны (регистры как у VM, `instruction_data` из примера ограничивает символьные байты input), поэтому у каждой программы ровно один путь.

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

Ошибка пока — имя `Fault` из IR (`DivideByZero`) или `AccessViolation`, а не `InstructionError` раннера; ненулевой `r0` на выходе — `NotImplementedError`. Перевод появится вместе с примерами, которые падают. На нарушение доступа раннер отвечает `status: failure`, `error: ProgramFailedToComplete`, а причина только в логах (`Program … failed: Access violation reading 8 bytes at address 0x400001000 (in unallocated region)`); упавшая инструкция попадает в трассу. Поэтому пути с `AccessViolation` проверяются юнит-тестами (`SbpfMemoryTest`, `SymbolicAddressTest`), а в `DiffTest` их пока нет. Позже `PathResult` дополнится моделью входов.

### Сравнение

Одна общая функция `compareWithRunner` (`lib/src/test/kotlin/org/sbpf/symex/Compare.kt`) по образцу `compareSymbolicAndConcreteResults` из TSA (`tsa-test/src/main/kotlin/org/ton/test/utils/TestUtils.kt`):

1. ровно один путь;
2. статус и вид ошибки;
3. регистры `r0`–`r10` — только у успешного пути, у упавшего значения регистров бессмысленны;
4. трасса — всегда: промежуточное значение, перекрытое следующей записью в тот же регистр, видно только в ней. Сообщение называет первую расходящуюся запись (индекс, `pc`, регистры).

Модель ответа раннера (`Outcome`: `status`, `error`, `registers`, `trace`) читается по [`sbpf-ir/docs/runner.md`](../sbpf-ir/docs/runner.md). Числа там — `u64`, в Kotlin это `ULong`, не `Long`.

`DiffTest` параметризованный: один случай на каждую пару «пример из `sbpf-ir/tests/examples/asm/` × версия sBPF из `versionsOf`», без ручного списка. Новый asm-пример в `sbpf-ir` сам становится тестом.

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
    │   │   │                   # SbpfApplicationGraph, SbpfComponents, перевод выражений, Entry (регистры VM),
    │   │   │                   # Loops и SbpfLoopTracker (циклы для лимитера)
    │   │   └── memory/         # MemoryLayout (регионы, ключи, условие доступа), Input (раскладка input,
    │   │                       # base58), SbpfMemory (массивы usvm, load/store, копия rodata)
    │   └── result/             # PathResult: статус, ошибка, регистры и трасса завершённого пути
    └── test/kotlin/org/sbpf/symex/
        ├── runner/             # SbpfRunner (sbpf-lift lift/run вживую), модель Outcome
        ├── machine/, ir/       # тесты машины (в т. ч. memory/, циклы и лимитер) и модели IR
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
- [x] `SbpfApplicationGraph`: полный по выходам блоков
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
- [x] Все asm-программы зелёные (10 под v0 и v3, `jmp32` только под v3): статус, регистры `r0`–`r10` и трасса совпадают с раннером, путь ровно один

### Ветвления и ошибки
- [x] Выходы `next`, `branch`, `trap`; у `trap ExecutionOverrun` за концом кода записи трассы нет (VM падает до записи). Переходы и циклы проверены против раннера (`jump`, `loop`, `jmp32`), `trap` — юнит-тестами: пути-ошибки против раннера — в пункте про `InstructionError`
- [x] `check` — развилка с путём-ошибкой
- [ ] Перевод `Fault` и ненулевого `r0` в ошибку раннера (`InstructionError`)
- [x] Ограничение циклов: `LoopLimiterPs` usvm и свой поиск циклов (`Loops.kt`), считаются только итерации с развилкой в точке выхода, отрезанные пути выбрасываются молча; нередуцируемые циклы держит лимит шагов
- [ ] Тест: статус и ошибка совпадают с раннером на всех путях

### Память
- [x] Модель регионов sBPF: один байтовый массив на rodata (`ro_region`, слот 0 в v3 / 1 в v0), стек, кучу и input; ключ `(слот << 28) | смещение`; проверка границ до обращения. 64-битный ключ потребует поддержки `Long`-интервалов в usvm — отдельная задача
- [x] `load` / `store` по константным и символьным адресам, `AccessViolation` как результат пути; свёртка байтов обратно в значение
- [x] Входной регион без аккаунтов: заголовок, `instruction_data` (символьные байты input-массива, в тестах ограничены байтами примера), `program_id`, выравнивание SIMD-0449; `DiffTest` берёт вход из примера (`memory` под v0 и v3)
- [ ] Аккаунты в input: сериализация по адресу `r1`, права по интервалам (`data` — по `can_data_be_changed`), `r2` с учётом аккаунтов, проверки после `exit` (`memory-notes.md`, 4)
- [ ] Полностью символьные `instruction_data` и аккаунты: вход раннера из модели пути, сравнение каждого пути со своим запуском; перевод `AccessViolation` в `ProgramFailedToComplete` — в пункте про `InstructionError`

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
