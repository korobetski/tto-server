# tto-server

The authority for [Triple Triad Online](../AS3-Triple-Triad) — the server that holds player
progression and decides whether a match really happened.

> **Status: it can judge a match, and nothing else.** It starts, migrates an empty schema, reports
> its health, exposes metrics — and **replays a submitted solo match with the client's own engine**
> to decide what really happened (`POST /matches/verify`). It still does not know what an account
> is, so a verdict is returned to whoever asked and then forgotten: the transcript is unforgeable as
> a *game* and worthless as a *claim* until it is signed and credited to a profile.

---

## Why this is a separate repository

Because the client must not be able to become the server. The migration's decision 3 is that **no
server code ships inside the client APKs**, and a separate repository is the only version of that
which cannot be undone by an accidental import.

It also means this repository is free to be a plain JVM project — one target, one toolchain, no
Compose, no Android — while the client stays multiplatform.

## The one constraint that shaped every other choice

The server verifies matches by **replaying them with the real engine**, not with a second
implementation of the rules. That is what makes it impossible for the client and the server to
disagree about who won.

Which means the runtime must be a **JVM**, so it can consume the client's `:core` module directly.
That single requirement rules out most of the interesting hosting options — edge runtimes, most
serverless platforms, every game-backend product whose match handlers are written in its own
language — and it is the reason the stack below looks conventional.

`:core` is extracted and consumed. It is not *published* anywhere, though — it is resolved from the
developer's local Maven repository, which is the one thing below that needs a step before the
obvious one.

---

## Quick start

`:core` has to exist locally first, from the client repository:

```
cd ../AS3-Triple-Triad && ./gradlew :core:publishToMavenLocal
```

Then, back here — `MAVEN_LOCAL_REPO` in `.env` is the one value with no portable default, because
the image build mounts that directory to resolve `:core`:

```
cp .env.sample .env
$EDITOR .env          # MAVEN_LOCAL_REPO, and a password for each of the two database roles
docker compose up -d --build
curl localhost:8080/health/ready
```

`.env` has no working defaults on purpose: compose refuses to start rather than fall back to a
password that is published in a sample file. There are **two** roles to give a password to — the
superuser that owns the cluster, and the unprivileged `tto_app` the server actually connects as.
See `.env.sample`, which also carries the commands for the case where the volume already exists,
since the bootstrap only ever runs on an empty one.

Or without Docker, against a Postgres you already have — `mavenLocal()` is in
`settings.gradle.kts`, so this needs no `MAVEN_LOCAL_REPO`, but it does need the two database
variables, whose code defaults no longer match any real database:

```
TTO_ENV=development DATABASE_USER=tto_app DATABASE_PASSWORD=... ./gradlew run
```

To see the point of the whole thing, submit a match. `MatchRoutesTest` builds a real transcript by
playing one with `:core`; the server replays it and answers with **its own** score, and answers a
tampered one with a reason:

```
{"type":"accepted","blue":3,"red":7,"winner":"RED"}
{"type":"rejected","reason":"TRUNCATED","detail":"the board still had 2 cells and the moves ran out after 3"}
```

`./gradlew build` runs ktlint, detekt, and the tests — including one that migrates a real Postgres
in a throwaway container, so **a Docker daemon is required to build**.

---

## What is in here

| | |
|---|---|
| **Ktor 3** on Netty | HTTP today, WebSockets when there are matches to referee |
| **Postgres 17** | the same major version in `compose.yaml`, in the tests, and in CI |
| **Flyway** | schema migrations, run in-process at start-up |
| **HikariCP** | connection pool — note it connects eagerly, see `Database.kt` |
| **Micrometer + Prometheus** | `/metrics` |
| **ktlint + detekt** | `maxIssues: 0`, same policy as the client repository |
| **Testcontainers** | the database tests run against a real Postgres, never an in-memory stand-in |

```
src/main/kotlin/com/tripletriad/server/
  Application.kt      entry point — the start-up order is the point
  ServerConfig.kt     the environment, read once, refusing to guess outside development
  Database.kt         pool + migrations
  HealthRoutes.kt     liveness and readiness, which are different questions
  Observability.kt    logging, correlation ids, metrics, error shape
  Catalogs.kt         the card and opponent tables — the server's own copy, deliberately
  MatchRoutes.kt      POST /matches/verify — parse, replay with :core, answer
```

## Operating it

[docs/operations.md](docs/operations.md) — configuration, exit codes, backups, and an explicit list
of what is not built yet.

The backups section is the one to read. Progression being server-held means losing this database
ends the game, and the scripts in `scripts/` are a starting point rather than a strategy.
