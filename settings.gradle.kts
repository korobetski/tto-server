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

        // **First** among the two that can serve `com.tripletriad:core`, and deliberately so. A
        // developer only has a `:core` here because they ran `./gradlew :core:publishToMavenLocal`
        // in the client repository, which is an explicit act with exactly one purpose: trying an
        // engine change against this server before it is released. Ordering the published copy
        // first would silently defeat that — Gradle takes the first repository that answers, and
        // for a SNAPSHOT the published one always does.
        //
        // The cost is the mirror image: a local install that is no longer wanted keeps shadowing
        // the real artifact until it is removed. That failure is at least visible from here —
        // `rm -rf ~/.m2/repository/com/tripletriad` — whereas the other one looks like the engine
        // change simply having no effect. CI has no local repository at all, so neither applies
        // there.
        mavenLocal {
            content { includeGroup("com.tripletriad") }
        }

        // `com.tripletriad:core` — the client's rules engine, published from the `tto-core`
        // repository to GitHub Packages. This is what makes the server buildable on a machine that
        // has never seen the client's sources, which is the precondition for CI building an image
        // and for that image being deployed.
        //
        // GitHub Packages requires authentication **even for public packages** — an anonymous GET
        // returns 401, not 200. So a build with no credentials does not fall back to a public read;
        // it fails here and continues to `mavenLocal()` below. The credentials are a GitHub username
        // and a token carrying `read:packages`, and they come from, in order:
        //
        //   ~/.gradle/gradle.properties   gpr.user / gpr.key        (developers)
        //   the environment               GITHUB_ACTOR / GITHUB_TOKEN (CI, and the image build)
        //
        // Never from this file, and never from a file inside the repository.
        maven {
            name = "tto-core"
            url = uri("https://maven.pkg.github.com/korobetski/tto-core")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
            content {
                // Scoped to the one group it serves. Without this, every dependency that missed
                // Maven Central would query GitHub Packages too — a slower build, and a 401 in the
                // log for artifacts that were never going to be there.
                includeGroup("com.tripletriad")
            }
        }
    }
}
