# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------------------------------
#
# The build runs in a container too, so the image does not depend on what happens to be installed
# on the machine that built it. That is the difference between an image anyone can reproduce and
# one that only the author's laptop can make.
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# The wrapper and the dependency declarations are copied first, alone. Docker caches each layer by
# the files it consumed, so this layer — the slow one, which downloads Gradle and every dependency
# — is reused on every build where only the sources changed. Copying everything at once would
# re-download the world on each edit.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
# The same credentials mount as the build step below, for the same reason — without it this warm-up
# cannot resolve `com.tripletriad:core` and quietly caches an incomplete set, leaving the real
# work for the uncached layer it exists to protect.
RUN --mount=type=secret,id=gradle_properties,target=/root/.gradle/gradle.properties \
    chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY detekt ./detekt
COPY src ./src

# `installDist` rather than a fat jar: it produces `bin/` + `lib/` with one file per dependency, so
# the runtime layer below changes only in the jars that actually changed. A fat jar is one large
# file that is rewritten in full on every source edit.
#
# Tests are NOT run here. They need a Docker daemon (Testcontainers), which is not available inside
# a build container; CI runs them, and CI is where a failing test must stop a release.
#
# ### The one credential this build needs
#
# `com.tripletriad:core` is the client's rules engine, published from the `tto-core` repository to
# GitHub Packages — which requires authentication even to read a public package. So the build needs
# a GitHub username and a `read:packages` token, and a token must never become a layer: an `ARG` is
# readable in `docker history`, and an `ENV` is readable in the image itself.
#
# A BuildKit secret is neither. It is a tmpfs that exists for the duration of this one instruction
# and is not recorded in the layer, so the artifact that lands in the image is the jar Gradle
# resolved here and nothing else. Mounted where Gradle already looks — `~/.gradle/gradle.properties`
# — so `settings.gradle.kts` needs no special case for the container.
#
# `docker build --secret id=gradle_properties,src=$HOME/.gradle/gradle.properties .`
#
# The file needs the two properties `settings.gradle.kts` reads:
#
#   gpr.user=your-github-username
#   gpr.key=ghp_...
#
# The mount is optional as far as BuildKit is concerned — a build without it does not fail here, it
# fails a few seconds later on an unresolved `com.tripletriad:core`, which is the message worth
# reading anyway.
RUN --mount=type=secret,id=gradle_properties,target=/root/.gradle/gradle.properties \
    ./gradlew --no-daemon installDist -x test

# ---- runtime ----------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# A JRE, not a JDK: no compiler, no debugging tools, a smaller image and a smaller attack surface.

# Runs as a non-root user. A container process is root on the host's kernel namespace by default,
# and nothing here needs to write outside its own working directory.
RUN addgroup -S tto && adduser -S -G tto tto

WORKDIR /app
COPY --from=build --chown=tto:tto /build/build/install/tto-server ./

USER tto

# Documentation only — publishing the port is compose's decision, not the image's.
EXPOSE 8080

# Container memory is not the JVM's heap. Without this the JVM sizes its heap from the *host's*
# memory and is killed by the cgroup limit long before it thinks it is under pressure.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Liveness, not readiness: this decides whether the container is broken, and the database being
# briefly away must not be grounds for restarting the process. See HealthRoutes.kt.
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD wget --quiet --spider http://127.0.0.1:8080/health/live || exit 1

# The exec form, so the JVM is PID 1 and receives SIGTERM directly. Wrapped in a shell it would be
# the shell that receives it, and the JVM would be killed outright — losing the graceful shutdown
# in Application.kt.
ENTRYPOINT ["./bin/tto-server"]
