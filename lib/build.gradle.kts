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

tasks.named<Test>("test") {
    useJUnitPlatform()
}
