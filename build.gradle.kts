plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    application
}

group = "com.tripletriad"
version = "0.1.0-SNAPSHOT"

kotlin {
    // 21, not the client's 17. This is a long-lived service rather than a library: virtual threads
    // and the newer collectors are worth having, and the container below runs a 21 JRE. Matching
    // the local build to the runtime is what keeps "works on my machine" from meaning anything.
    // A `:core` compiled for 17 remains consumable from here; the reverse would not be true.
    jvmToolchain(21)
}

application {
    mainClass.set("com.tripletriad.server.ApplicationKt")
}

dependencies {
    implementation(libs.tripletriad.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)

    implementation(libs.logback.classic)
    implementation(libs.micrometer.prometheus)
    implementation(libs.bcrypt)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

// Formatting rules live in .editorconfig, which the IDE reads too, so the plugin needs no
// configuration beyond skipping generated sources.
ktlint {
    filter {
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("detekt/detekt.yml"))
    source.setFrom(files("src"))
    parallel = true
}

tasks.test {
    useJUnitPlatform()
    // Testcontainers writes its progress to stderr and swallows assertion context otherwise.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The distribution the container runs. `installDist` lays out `bin/` + `lib/` without building a
// tarball only to unpack it again inside the image — a fat jar would be simpler to copy but would
// re-pack every dependency on every source change, defeating Docker's layer cache.
tasks.named("build") {
    dependsOn("installDist")
}
