plugins {
    alias(libs.plugins.org.jetbrains.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<JavaExec>("normalizeTranscript") {
    group = "application"
    description = "Paste a CARFU STT transcript; prints normalization fields. No microphone."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.stypox.dicio.aliasnormalizer.ProviderAliasHarnessKt")
    standardInput = System.`in`
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
