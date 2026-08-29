# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The authoritative server for Triple Triad Online. One Gradle module, one package
(`com.tripletriad.server`), Ktor 3 on Netty, Postgres 17, plain JDBC. The client lives in
`../AS3-Triple-Triad`; the shared rules engine is `com.tripletriad:core`, published from the
`tto-core` repository.

## Prerequisites the build will not work without

1. **A GitHub token with `read:packages`**, in `~/.gradle/gradle.properties` (outside this repo):
   `gpr.user=<username>` / `gpr.key=<token>`. GitHub Packages answers an anonymous request with 401
   even for a public package, so no credentials means the build fails resolving `com.tripletriad:core`.
2. **A running Docker daemon.** `./gradlew build` runs Testcontainers tests against a real Postgres.

## Commands

```bash
./gradlew build                 # ktlint + detekt + tests + coverageVerify + installDist
./gradlew test                  # tests only
./gradlew test --tests 'com.tripletriad.server.PvpFlowTest'
./gradlew test --tests '*PvpFlowTest.aHostCannotJoinTheirOwnTable'    # single test method
./gradlew ktlintFormat          # fix formatting
./gradlew coverageReport        # build/reports/jacoco/
```

Running it:

```bash
docker compose up -d --build    # needs .env — cp .env.sample .env first
curl localhost:8080/health/ready

# without Docker, against a Postgres you already have
TTO_ENV=development DATABASE_USER=tto_app DATABASE_PASSWORD=... ./gradlew run
```

`TTO_ENV` is required. Anything other than `development`/`dev`/`local` — **including unset** — is
production, and production refuses every development default.

### Testing an unreleased engine change

`settings.gradle.kts` puts `mavenLocal()` **first** for the `com.tripletriad` group, on purpose:

```bash
cd ../AS3-Triple-Triad && ./gradlew :core:publishToMavenLocal
```

The mirror image is the trap — a stale local install keeps shadowing the published artifact until
`rm -rf ~/.m2/repository/com/tripletriad`.

### Releasing

There is deliberately **no `version` in build.gradle.kts**; the release version is the git tag.
`git push origin v0.7.2` runs verify → publish to `ghcr.io` → deploy by digest, with rollback if the
new image never answers `/health/ready`. Bumping `:core` means editing `core` in
`gradle/libs.versions.toml`; that number and the client's must move together.

## Architecture

### The one constraint everything else follows from

The server verifies a match by **replaying it with `:core`**, the same engine the client played it
with. There is no server-side copy of the rules, and adding one would make the two ends able to
disagree about who won. When a route needs to know what something costs, what a pack contains or
what a win pays, it calls into `:core` (`TranscriptVerifier`, `MatchRewards.credit`, `PveMatches`,
`Inventory`, `ShopCatalog`) rather than computing it.

### Start-up order (`Application.kt`)

Config → catalogs → pool → migrate → *then* open the port. Each stage exits with a distinct code
(78 misconfigured, 70 migration failed, 69 database unreachable) rather than starting a server that
would 500 on the first request. `Application.module(dataSource, registry, identity)` is the test
seam — tests wire it without a socket or a shutdown hook. It also launches a background coroutine
sweeping abandoned matches and unclaimed cards every 30s.

### The two stores

| | Owns | File |
|---|---|---|
| `AccountStore` | who a player is and what they have | accounts, sessions, characters, matches, tickets, applied_operations |
| `PvpStore` | what is happening right now | pvp tables, challenges, live matches |

They meet in exactly one place, `PvpStore.creditBoth`, and that meeting is a transaction. Plain JDBC
throughout: the pool sets `isAutoCommit = false`, and each store's private `transaction {}` helper is
the only thing that commits or rolls back.

### Route surface

| Prefix | File | Notes |
|---|---|---|
| `/server` | `ServerRoutes.kt` | the **only** ungated route — a refused client learns here that it must update |
| `/health/live`, `/health/ready` | `HealthRoutes.kt` | liveness never touches the database; readiness does |
| `/accounts`, `/sessions`, `/me/**` | `AccountRoutes.kt` | registration, sign-in, profile, bag, shop, starter, campaign entry |
| `/matches/verify`, `/submit`, `/tickets` | `MatchRoutes.kt` + `MatchCrediting.kt` | PvE: client plays, server replays and credits |
| `/pvp/**` | `PvpRoutes.kt`, `PvpMatchRow.kt` | PvP: server referees, clients poll. No websockets |
| `/metrics` | `Application.kt` | Prometheus, unauthenticated — must not be publicly exposed |

Cross-cutting rules every new route must follow:

- **Call `requireCompatibleClient()` before `call.receive()`.** A major-version mismatch is exactly
  the case where this build may misread the body. `VersionGate.kt` explains why 426 and why first.
- **Authenticate with `authenticate(store)`** (`Authentication.kt`), which returns the account id or
  has already responded 401. Bearer tokens are stored only as fingerprints and never logged.
- **Anything that spends or grants goes through `AccountStore.applyOnce`** with a client-minted
  operation id. A retried purchase must return the *first* answer, not perform a second one.
- **A rejected claim is a 200 with a reason**, not a 4xx: it is an answer, not a malformed request.
  Refusal shapes and their status codes live in `Rejected.kt`.
- Rate-limit buckets are declared once in `Observability.kt` (`SIGN_IN`, `REGISTER`, `SUBMIT`,
  `INTENT`, `LOBBY`) and applied with `rateLimit(RateLimitName(...))`.
- `PUT /me/save` takes the client at its word for the parts `GameSave.withServerOwnedFrom` does not
  reclaim. Each new intent endpoint takes one more thing off that list.

### Catalogs

`src/main/resources/catalog/*.json` are the server's **own copies** of client resources (cards, npcs,
formats, campaigns, starters), parsed with `:core`'s parsers. They must stay in step with the
client's copies — drift makes every honest transcript replay to a different board and be rejected as
if it were cheating. `VersionGate` is the mitigation, not a fix. `Catalogs.preload()` forces them at
start-up so a bad file is a failed boot rather than a failed request.

### Schema

Flyway runs in-process at start-up from `src/main/resources/db/migration` (V1–V9 today). Rules:
`V<n>__<description>.sql`; **an applied migration is immutable** — write a new one; prefer additive
changes, since a deploy is not atomic with its migration. `baselineOnMigrate` is off deliberately.
In-process migration is a single-instance decision; `Database.kt` says what changes when there is a
second one.

## Tests

- `Postgres.dataSource` — one Testcontainers Postgres for the whole run, migrated once. Tests isolate
  by taking `Postgres.freshAccount("prefix")` rather than truncating tables.
- `MigrationTest` starts its own container, because it is *about* a database coming up from nothing.
- `Transcripts.honest(...)` plays a real match with `:core` to build a transcript. Its move choice
  must not draw from the shared `Random`, or the replay desynchronises.
- Routes take `clock: () -> Long` and `random: () -> Random` parameters so tests can control both.
- `UnreachableDataSource` covers the "database is gone" arms.

## Quality gates

`maxIssues: 0` for detekt, and `check` also runs `coverageVerify` (0.87 line / 0.62 branch, pinned
just under what the suite reaches so it catches a regression). A detekt suppression is expected to
carry a comment saying why the rule is wrong *here* — see `AccountStore`, `PvpStore`, `PvpMatchRow`.

## House style

The code is written to explain **why**, at length, in KDoc and in comments — including in SQL, YAML
and Dockerfiles. Decisions that were considered and rejected are recorded next to the decision that
won. Match that density when editing; a change that removes the reasoning is a regression. Formatting:
`intellij_idea` ktlint style, 100 columns, no wildcard imports (`.editorconfig` is the source of truth
and detekt's `MaxLineLength` is kept in step with it).

## Secrets never reach a transcript

No command output may carry a password or a token: `POSTGRES_PASSWORD`, `DATABASE_PASSWORD`,
`gpr.key`, a bearer token. A conversation is durable and shareable, so a secret that appears in one
costs a rotation — of the cluster superuser, in the case that matters most here.

The trap is the incidental line, not the deliberate `cat .env`. `docker compose config` prints the
whole resolved environment, and a grep for `POSTGRES` matches `POSTGRES_PASSWORD` alongside the two
values you wanted. So **name the keys**: `grep -E '^(POSTGRES_USER|POSTGRES_DB|DATABASE_USER)='`.
Same for `printenv`, `docker inspect` and `git config --list`.

The code already holds this line — `Secrets.kt` and `LogSecrecyTest` assert that bearer tokens are
stored and logged as fingerprints and never in the clear. This is that rule, applied to the shell.

## Docs

`docs/operations.md` (configuration, exit codes, backups, alerting) and `docs/deployment.md` are
current and worth reading before touching compose files, `scripts/` or `deploy/`.
`docs/data-inventory.md` tracks what personal data the schema holds and is derived per migration —
update it when a migration adds a column about a player. `README.md` was rewritten against the
current surface and is trustworthy again; one document is still **stale** and should not be trusted:
the "Why this directory is empty" section of `src/main/resources/db/migration/README.md` predates
accounts, the economy and PvP.
