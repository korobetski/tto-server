plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    application
    jacoco
}

group = "com.tripletriad"

// **The version of this service is its git tag.** There is deliberately no `version` here.
//
// There was one, and it said `0.1.1` while `v0.1.2`, `v0.1.3`, `v0.1.4` and `v0.2.0` had all shipped
// — four releases of drift that nothing caught, because nothing read it. The image is tagged from
// `github.ref_name` (`.github/workflows/release.yml`), the deployment pulls by digest, and
// `GET /server` reports `CURRENT_VERSION`, which is the *protocol* version and lives in `:core`. So
// the constant reached no artifact, no image and no response: it was a fourth number to keep in step
// that only ever went out of step.
//
// Two of the four numbers in `tto-core/docs/RELEASING.md` § 1 are genuinely consumed — `coreVersion`
// names a published artifact, `clientVersion` becomes the APK's `versionName` and the app's own
// `CLIENT_VERSION` — and this one was not. Deleting it is the honest fix; bumping it would have
// restored a habit whose only purpose was to feed a value nobody reads.
//
// What this costs: `installDist` names the jar `tto-server.jar` instead of `tto-server-0.1.1.jar`.
// Nothing depends on that name — the start script it generates and the `COPY` in the Dockerfile both
// address the distribution directory, which is named after the project.

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
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.forwarded.header)
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

/**
 * Coverage, and a floor under it.
 *
 * ### Why this arrives late and matters more here than in the client
 *
 * The client has had a gate at 0.90/0.75 since it had screens; this module had none at all — which
 * was defensible while it was a thin layer over `:core`, and stopped being so the moment the
 * economy moved here. Every rule about what a thing costs, who may spend it and whether a seed was
 * issued now lives in this module, and an untested branch in any of them is a way to get something
 * for nothing.
 *
 * ### Set from what the suite already achieves, not from an aspiration
 *
 * A gate above the current figure fails on day one and gets deleted; a gate well below it permits a
 * long slide. Measured at **89.7% line / 64.5% branch** (2026-08-14) and pinned just under, so it
 * catches a *regression* rather than demanding an improvement — the same reasoning the client's own
 * gate is written with.
 *
 * The branch figure is much the lower of the two and that is expected here rather than alarming: a
 * route is mostly guard clauses, and the arms that answer "no such account", "no character" and
 * "not reachable" are the ones a test has to construct deliberately. The number to watch is whether
 * it falls, not whether it looks like the client's.
 *
 * The tests behind it run against a real Postgres in a container, so this measures the paths a
 * deployment actually takes rather than the ones a mock would allow.
 */
tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "HTML + XML coverage, from the JUnit suite."
    dependsOn(tasks.test)
    // The `.exec` the test task writes, named through the extension rather than guessed at — the
    // obvious `executionData(tasks.test)` resolves to the JUnit results directory instead.
    executionData(
        tasks.test.map { test ->
            test.extensions.getByType<JacocoTaskExtension>().destinationFile!!
        },
    )
    sourceSets(sourceSets.main.get())

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
}

tasks.register<JacocoCoverageVerification>("coverageVerify") {
    group = "verification"
    description = "Fails if coverage drops below what the suite already reaches."
    val report = tasks.named<JacocoReport>("coverageReport")
    dependsOn(report)
    executionData(report.map { it.executionData })
    classDirectories.setFrom(report.map { it.classDirectories })
    sourceDirectories.setFrom(report.map { it.sourceDirectories })

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.87".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.62".toBigDecimal()
            }
        }
    }
}

tasks.named("check") { dependsOn("coverageVerify") }

// The distribution the container runs. `installDist` lays out `bin/` + `lib/` without building a
// tarball only to unpack it again inside the image — a fat jar would be simpler to copy but would
// re-pack every dependency on every source change, defeating Docker's layer cache.
tasks.named("build") {
    dependsOn("installDist")
}
