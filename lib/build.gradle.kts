plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

repositories {
    mavenCentral()
    // usvm нет в Maven Central: JitPack собирает её по хэшу коммита и отвечает только за её группу.
    exclusiveContent {
        forRepository { maven("https://jitpack.io") }
        filter { includeGroup("com.github.UnitTestBot.usvm") }
    }
}

dependencies {
    implementation(platform(libs.kotlin.bom))
    // Без exclude для io.ksmt: версии KSMT совпадают с usvm (libs.versions.toml).
    implementation(libs.usvm.core)
    implementation(libs.ksmt.core)
    implementation(libs.ksmt.yices)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Тесты сравнивают машину с лифтером и раннером sbpf-ir, запуская sbpf-lift вживую. Версия sbpf-ir не
// закреплена: берётся соседняя папка (или -PsbpfIr=<путь>) как есть.
val sbpfIr: File = providers.gradleProperty("sbpfIr").map { file(it) }
    .getOrElse(rootDir.resolve("../sbpf-ir")).canonicalFile

// cargo сам пропускает сборку, если ничего не менялось.
val buildSbpfLift by tasks.registering(Exec::class) {
    workingDir = sbpfIr
    commandLine("cargo", "build", "--release", "-p", "sbpf-lift-cli")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(buildSbpfLift)
    systemProperty("sbpf.ir", sbpfIr.path)
    // Без этих входов после правки лифтера или раннера тесты считались бы UP-TO-DATE.
    inputs.file(sbpfIr.resolve("target/release/sbpf-lift"))
        .withPropertyName("sbpfLift").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(sbpfIr.resolve("tests/examples"))
        .withPropertyName("sbpfIrExamples").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(sbpfIr.resolve("tests/programs/bin"))
        .withPropertyName("sbpfIrPrograms").withPathSensitivity(PathSensitivity.RELATIVE)
}
