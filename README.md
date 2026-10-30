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

- **Загрузка IR.** JSON из `sbpf-lift lift` превращается в `sealed`-иерархии Kotlin: statement'ы (дискриминатор `op`), выходы блока (`kind`), выражения (по набору ключей). Проверяется `format_version`, незнакомые необязательные поля игнорируются, как требует контракт.
- **Граф программы.** `ApplicationGraph` над функциями и блоками IR: шаг — это statement или выход блока, у обоих есть `pc`. Преемники берутся из выходов блоков (`next`, `branch`, `call`...).
- **Состояние.** Наследник `UState`. Регистры `r0`–`r10` лежат в кадрах `URegistersStack` (`memory.stack`), как описано в `ir.md`: регистр, прочитанный до записи, автоматически становится символом-входом. Остальное — условия пути, pc (`pathNode`), признак завершения, ошибка.
- **Интерпретатор.** `UInterpreter.step`: `when` по виду statement'а. Выражения IR один к одному переводятся в битвекторные термы KSMT через `UContext`. `check` — развилка: путь, где условие ложно, завершается ошибкой и тоже попадает в результаты.
- **Машина.** `UMachine.run` с селектором путей, наблюдателями (сбор завершённых путей, счётчик шагов) и стоп-стратегиями.
- **Результаты и сравнение.** Из модели завершённого пути берутся значения входов и итоговые регистры. Эталон — конкретный раннер `sbpf-lift run` (Mollusk, тот же код, что у валидатора agave 4.3), а не собственный интерпретатор: свой эталон повторял бы те же ошибки понимания семантики и не видел бы ошибок лифтера.

## Использование

Пока ничего не реализовано, разделы появятся по мере готовности.

```sh
./gradlew build
./gradlew test
./gradlew test -PlocalUsvm    # usvm из исходников ../usvm вместо JitPack
```

## Тестирование

Каждый тест сравнивает символьную машину с раннером на одном и том же контракте:

```
X.s / X.rs ──► X.so ──┬── sbpf-lift lift ──► IR JSON ──► sbpf-symex ──► регистры, статус ──┐
                      └── sbpf-lift run --registers --trace ──► Outcome JSON ──────────────┴─► сравнить
```

- Сначала сравнивается статус (`success`/`failure` и вид ошибки), регистры — только у успешных путей.
- Трасса раннера (регистры перед каждой инструкцией и `pc`) позволяет найти первую инструкцию, где началось расхождение.
- Если в программе нет ветвлений, путь должен быть ровно один.
- IR и ответы раннера лежат в `src/test/resources` как фикстуры и пересоздаются скриптом из `../sbpf-ir`. Поэтому для Kotlin-тестов не нужен Rust, а изменение лифтера видно в diff фикстур.
- Первые тесты — программы на ассемблере из [`sbpf-ir/tests/programs/asm/`](../sbpf-ir/tests/programs/asm) (`alu64`, `alu32`, `shift64`, `shift32`, `divmod`, `mov`, `endian`): один блок, только регистры и арифметика, каждый регистр читается после записи, итог в `r1`–`r9`. Ожидаемые значения уже проверены в `sbpf-ir` (тест `asm` в `crates/sbpf-run/tests/run.rs`).

## Структура

Сейчас в репозитории шаблон `gradle init`. Целевая структура первой версии:

```
sbpf-symex/
├── settings.gradle.kts         # подпроекты; includeBuild("../usvm") по флагу -PlocalUsvm
├── gradle/libs.versions.toml   # версии: usvm (хэш коммита), KSMT, kotlinx
└── lib/src/
    ├── main/kotlin/org/sbpf/symex/
    │   ├── ir/                 # модель IR и загрузка JSON
    │   ├── machine/            # SbpfContext, SbpfState, SbpfInterpreter, SbpfMachine,
    │   │                       # SbpfApplicationGraph, SbpfComponents, перевод выражений
    │   └── result/             # извлечение регистров, ошибки и входов из завершённых путей
    └── test/
        ├── kotlin/org/sbpf/symex/   # тесты: загрузка IR, сравнение с раннером
        └── resources/               # фикстуры: IR и ответы раннера
```

Образец устройства — `usvm-sample-language` в usvm (`SampleState`, `SampleInterpreter`, `SampleMachine`, `ResultModelConverter`) и анализатор TON [TSA](https://github.com/espritoxyz/tsa) (`TvmMachine`, `TvmStepScopeManager`, дифференциальные тесты в `tsa-test`).

## Окружение

| Инструмент | Версия | Установка |
|---|---|---|
| JDK | 21 | `brew install openjdk@21` |
| Gradle | wrapper (`./gradlew`) | — |
| Kotlin | 2.4.10 (плагин в `libs.versions.toml`) | — |
| usvm | `2c4f17c4c9fc0d70057e374ec462600a0d9c4bbf` | JitPack, `com.github.UnitTestBot.usvm:usvm-core` |
| KSMT | 0.5.26 — **та же, что в usvm** | Maven Central, `io.ksmt` |

- `./gradlew` требует установленную Java. `foojay-resolver` в `settings.gradle.kts` скачивает JDK только для компиляции.
- Все артефакты `io.ksmt:*` — ровно той версии, с которой собрана usvm (`usvm/buildSrc/src/main/kotlin/Dependencies.kt`). При конфликте Gradle молча поднимает версию до старшей, и usvm падает во время работы с `NoSuchMethodError`. Проверка: `./gradlew :lib:dependencyInsight --dependency ksmt-core`.
- Для фикстур нужно окружение `sbpf-ir` (Rust, Solana CLI), см. его README. Для запуска Kotlin-тестов оно не нужно.

## План

### Подготовка
- [ ] Установить JDK 21, проверить сборку шаблона
- [ ] Каталог версий: usvm по хэшу через JitPack, KSMT 0.5.26 + Yices, `kotlinx-serialization-json`, `kotlinx-collections-immutable`; убрать шаблонные `guava` и `commons-math3`
- [ ] `includeBuild("../usvm")` с `dependencySubstitution` по флагу `-PlocalUsvm`
- [ ] Скрипт пересоздания фикстур из `../sbpf-ir`: IR (`sbpf-lift lift`) и ответы раннера (`sbpf-lift run --registers --trace`) для `asm/*`
- [ ] CI: сборка и тесты

### IR в Kotlin
- [ ] Типы IR: `sealed` statement'ы, выходы блока, выражения; обычные `class`, не `data class` (разные места программы — разные ключи)
- [ ] Загрузка JSON: дискриминаторы `op` и `kind`, выражения по ключам, числа `"0x…"` как `ULong`
- [ ] Проверка `format_version`; в тестах — строгий режим (`ignoreUnknownKeys = false`), чтобы заметить новые поля лифтера
- [ ] Тест: все IR из фикстур загружаются

### Первая версия: регистры и арифметика
- [ ] `SbpfContext`: хелперы расширения и усечения (`zext`, `sext`, `trunc` с правильными аргументами KSMT), `bswap`
- [ ] Перевод выражений IR в KSMT: все операции из таблицы `ir.md`
- [ ] `SbpfApplicationGraph` для одного блока: statement'ы по порядку, выход `return`
- [ ] `SbpfState`: регистры в `memory.stack`, признак завершения, `clone` по образцу `SampleState`
- [ ] `SbpfInterpreter`: `assign`, `return`
- [ ] `SbpfMachine`: DFS, сбор завершённых путей, лимит шагов, `throwExceptionOnStepFailure = true`
- [ ] Тест: 7 asm-программ под v0 и v3, регистры `r1`–`r9` совпадают с раннером, путь ровно один

### Символьные входы
- [ ] Входные регистры — символы `URegisterReading`, итоговые регистры — формулы
- [ ] Извлечение результата из модели: входы `model.stack.readRegister`, выходы `model.eval`
- [ ] Тест: один символьный прогон против многих конкретных (программы с подставленной константой на входе; нужен генератор таких программ в `sbpf-ir`)

### Ветвления и ошибки
- [ ] Выходы `next`, `branch`, `trap`
- [ ] `check` — развилка с путём-ошибкой
- [ ] Ограничение циклов
- [ ] Тест: статус и ошибка совпадают с раннером на всех путях

### Память
- [ ] Модель регионов sBPF: input, стек, куча, `ro_region`; расположение в v0 и v3
- [ ] `load` / `store`, `AccessViolation`
- [ ] Начальное состояние точки входа: `r1` — указатель на сериализованные аккаунты и данные инструкции, `r10` — стек

### Вызовы и syscall'ы
- [ ] `call` / `return` по кадрам (`ir.md`, «`call` и `return`»), `CallDepthExceeded`
- [ ] `callx`: развилка по `address_taken`, `CallOutsideTextSegment`
- [ ] Модели syscall'ов, начиная с `sol_log_*` и `sol_mem*`
- [ ] Учёт CU

### Результаты
- [ ] Генерация входа раннера (`instruction_data`, аккаунты) из модели пути: каждый найденный путь проверяется конкретным запуском
- [ ] Прогон на контрактах из `sbpf-ir/tests/programs` и мейннета

## Ссылки

- [`sbpf-ir/docs/ir.md`](../sbpf-ir/docs/ir.md) — формат и семантика IR, контракт с лифтером
- [`sbpf-ir/docs/runner.md`](../sbpf-ir/docs/runner.md) — формат конкретного раннера
- [usvm](https://github.com/UnitTestBot/usvm) — ядро символьной машины; образец — модуль `usvm-sample-language`
- [KSMT](https://github.com/UnitTestBot/ksmt) — формулы и солверы
- [TSA](https://github.com/espritoxyz/tsa) — символьный анализатор TON на usvm
- [SSEREX: Practical Symbolic Execution of Solana Smart Contracts](https://arxiv.org/abs/2603.16349) — статья, на которую опирается проект
- [`../usvm-notes.md`](../usvm-notes.md) — конспект разбора usvm и TSA
