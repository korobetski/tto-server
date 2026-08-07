# tto-server

The authority for [Triple Triad Online](../AS3-Triple-Triad) — the server that holds player
progression and decides whether a match really happened.

> **Status: first bricks.** It starts, migrates an empty schema, reports its health and exposes
> metrics. It does not yet know what an account is, what a match is, or how to verify one. What is
> here is the part that has to be right before any of that is written.

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

`:core` is not extracted yet. Until it is, this server can hold data but cannot judge anything.

---

## Quick start

```
cp .env.sample .env
docker compose up -d --build
curl localhost:8080/health/ready
```

Or without Docker, against a Postgres you already have:

```
TTO_ENV=development ./gradlew run
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
```

## Operating it

[docs/operations.md](docs/operations.md) — configuration, exit codes, backups, and an explicit list
of what is not built yet.

The backups section is the one to read. Progression being server-held means losing this database
ends the game, and the scripts in `scripts/` are a starting point rather than a strategy.
