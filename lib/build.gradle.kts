plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

repositories {
    mavenCentral()
    // usvm is not on Maven Central: JitPack builds it from a commit hash and serves only its group.
    exclusiveContent {
        forRepository { maven("https://jitpack.io") }
        filter { includeGroup("com.github.UnitTestBot.usvm") }
    }
}

dependencies {
    implementation(platform(libs.kotlin.bom))
    // No exclude for io.ksmt: KSMT versions match usvm's (libs.versions.toml).
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

// Tests compare the machine with the sbpf-ir lifter and runner by running sbpf-lift live. The sbpf-ir version is not
// pinned: the sibling directory (or -PsbpfIr=<path>) is used as is.
// A relative path in -PsbpfIr is resolved from the repository root, like the default path.
val sbpfIr: File = rootDir.resolve(providers.gradleProperty("sbpfIr").getOrElse("../sbpf-ir")).canonicalFile

// cargo skips the build itself if nothing changed.
val buildSbpfLift = tasks.register<Exec>("buildSbpfLift") {
    workingDir = sbpfIr
    commandLine("cargo", "build", "--release", "-p", "sbpf-lift-cli")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(buildSbpfLift)
    systemProperty("sbpf.ir", sbpfIr.path)
    // Without these inputs, tests would be considered UP-TO-DATE after a change to the lifter or runner.
    inputs.file(sbpfIr.resolve("target/release/sbpf-lift"))
        .withPropertyName("sbpfLift").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(sbpfIr.resolve("tests/examples"))
        .withPropertyName("sbpfIrExamples").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(sbpfIr.resolve("tests/programs/bin"))
        .withPropertyName("sbpfIrPrograms").withPathSensitivity(PathSensitivity.RELATIVE)
}
