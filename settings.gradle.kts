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
        // ---- `com.tripletriad`, and nowhere else -------------------------------------------
        //
        // The group is declared **exclusive** to the two repositories below: they serve it, and no
        // other repository is asked for it.
        //
        // ### Why exclusivity rather than a filter on each
        //
        // A `content { includeGroup(...) }` says what a repository *may* serve. It says nothing
        // about a repository carrying no filter at all — and `mavenCentral()` was one, listed
        // first, so Gradle asked Central for `com.tripletriad:core` before either repository that
        // owns it. Nothing went wrong, because nobody has registered the namespace: Central
        // answers 404 and Gradle moves on. That is somebody else's decision standing in for a
        // rule of ours.
        //
        // It is worth more here than it would be for an ordinary dependency. `:core` is the engine
        // a match is *replayed* with, so whatever serves it decides what a legal move is, what a
        // pack contains and what a win pays. An artifact resolved from an unintended source would
        // not fail loudly — it would referee.
        //
        // `exclusiveContent` states the rule from the other side, so a repository added later
        // inherits it instead of quietly getting in front of these two.
        exclusiveContent {
            forRepositories(
                // **First** among the two, and deliberately so. A developer only has a `:core`
                // here because they ran `:core:publishToMavenLocal` from the `tto-core`
                // repository, which is an explicit act with exactly one purpose: trying an engine
                // change against this server before it is released. Ordering the published copy
                // first would silently defeat that — Gradle takes the first repository that
                // answers.
                //
                // The cost is the mirror image: a local install that is no longer wanted keeps
                // shadowing the real artifact until it is removed. That failure is at least
                // visible from here — `rm -rf ~/.m2/repository/com/tripletriad` — whereas the
                // other one looks like the engine change simply having no effect. CI has no local
                // repository at all, so neither applies there.
                mavenLocal(),
                // `com.tripletriad:core` — the client's rules engine, published from the
                // `tto-core` repository to GitHub Packages. This is what makes the server
                // buildable on a machine that has never seen the client's sources, which is the
                // precondition for CI building an image and for that image being deployed.
                //
                // GitHub Packages requires authentication **even for public packages** — an
                // anonymous GET returns 401, not 200. So a build with no credentials does not fall
                // back to a public read; it fails. The credentials are a GitHub username and a
                // token carrying `read:packages`, and they come from, in order:
                //
                //   ~/.gradle/gradle.properties   gpr.user / gpr.key          (developers)
                //   the environment               GITHUB_ACTOR / GITHUB_TOKEN (CI, image build)
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
                },
            )
            filter { includeGroup("com.tripletriad") }
        }

        // Everything else. It is listed after the exclusive block for readability only — the block
        // above binds the group wherever this line sits, which is the property that was missing.
        mavenCentral()
    }
}
