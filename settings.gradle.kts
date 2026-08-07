rootProject.name = "tto-server"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions the JDK named by `jvmToolchain` below, so a contributor whose only JDK is 17 can
    // still build against the 21 the container runs. Without it the build fails with an unhelpful
    // "no matching toolchain" instead of downloading one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // The client's `:core` will land here first while it is still unpublished. Kept last so a
        // stale local install can never shadow a real Maven Central artifact.
        mavenLocal()
    }
}
