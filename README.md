# tto-server

The authority for [Triple Triad Online](../AS3-Triple-Triad) — the server that holds player
progression, referees matches and decides what a player earned.

> **Status: it owns accounts, the economy and both match paths.** A player registers, signs in,
> claims a starter, buys from the shop, opens their bag, plays the campaign, plays a refereed match
> against an opponent the *server* runs, and sits at a PvP table where the server holds both hands
> and the clients poll for their turn. Every cost, every payout and every pack comes from `:core`,
> so the server never has a second opinion about the rules.

---

## Why this is a separate repository

Because the client must not be able to become the server. The migration's decision 3 is that **no
server code ships inside the client APKs**, and a separate repository is the only version of that
which cannot be undone by an accidental import.

It also means this repository is free to be a plain JVM project — one target, one toolchain, no
Compose, no Android — while the client stays multiplatform.

## The one constraint that shaped every other choice

The server settles a match with **the real engine**, not with a second implementation of the rules.
That is what makes it impossible for the client and the server to disagree about who won — and it
is not only about the board: what a pack contains, what a card sells for and what a win pays are
all read from `:core` (`MatchRewards`, `PveMatches`, `Inventory`, `ShopCatalog`) rather than
recomputed here.

Which means the runtime must be a **JVM**, so it can consume the client's `:core` module directly.
That single requirement rules out most of the interesting hosting options — edge runtimes, most
serverless platforms, every game-backend product whose match handlers are written in its own
language — and it is the reason the stack below looks conventional.

`:core` is extracted, published from the `tto-core` repository to GitHub Packages, and consumed from
there — which is what makes this repository buildable on a machine that has never seen the client's
sources, and therefore what makes CI able to build an image and deploy it.

### Two ways a PvE match can be settled, and one of them is going away

`POST /matches/verify` and `/matches/submit` are the original bargain: the client plays a whole
match and submits a `MatchTranscript`, the server **replays** it and credits its own verdict. It is
still live, and it is the reason the design works at all — but it is being retired, because
replaying requires the client to run the same AI from the same seed, which means the client holds
the opponent's five cards and knows every move they will make from the first placement.

`/pve/matches/**` is the replacement, and it works exactly like PvP: the server opens the match,
plays the opponent, and answers each request with only what the asking player is entitled to see.
No route there returns a hidden card, and none accepts a board.

---

## Quick start

Reading `com.tripletriad:core` needs a GitHub token with `read:packages`, because GitHub Packages
answers an anonymous request with 401 even for a public package. It goes in your Gradle home file,
outside this repository:

```
# ~/.gradle/gradle.properties
gpr.user=your-github-username
gpr.key=ghp_...
```

Then — `GRADLE_PROPERTIES` in `.env` is the one value with no portable default, because the image
build mounts that file as a secret to resolve `:core`:

```
cp .env.sample .env
$EDITOR .env          # GRADLE_PROPERTIES, and a password for each of the two database roles
docker compose up -d --build
curl localhost:8080/health/ready
```

To try an *unreleased* engine change instead, publish `:core` locally from the client repository —
`settings.gradle.kts` prefers that copy over the published one, on purpose:

```
cd ../AS3-Triple-Triad && ./gradlew :core:publishToMavenLocal
```

The mirror image of that convenience is the trap: a stale local install keeps shadowing the
published artifact until `rm -rf ~/.m2/repository/com/tripletriad`.

`.env` has no working defaults on purpose: compose refuses to start rather than fall back to a
password that is published in a sample file. There are **two** roles to give a password to — the
superuser that owns the cluster, and the unprivileged `tto_app` the server actually connects as.
See `.env.sample`, which also carries the commands for the case where the volume already exists,
since the bootstrap only ever runs on an empty one.

Or without Docker, against a Postgres you already have — Gradle reads your home file directly, so
this needs no `GRADLE_PROPERTIES`, but it does need `TTO_ENV` and the two database variables, whose
code defaults no longer match any real database:

```
TTO_ENV=development DATABASE_USER=tto_app DATABASE_PASSWORD=... ./gradlew run
```

`TTO_ENV` is required and unset counts as production, which refuses every development default.

To see the point of the whole thing, submit a match. `Transcripts.honest(...)` builds a real
transcript by playing one with `:core`; the server replays it and answers with **its own** score,
and answers a tampered one with a reason:

```
{"type":"accepted","blue":3,"red":7,"winner":"RED"}
{"type":"rejected","reason":"TRUNCATED","detail":"the board still had 2 cells and the moves ran out after 3"}
```

`./gradlew build` runs ktlint, detekt, the tests and the coverage floor — the tests include ones
that migrate and query a real Postgres in a throwaway container, so **a Docker daemon is required
to build**.

---

## What is in here

| | |
|---|---|
| **Ktor 3** on Netty | HTTP only — PvP is refereed by polling, deliberately, see `PvpRoutes.kt` |
| **Postgres 17** | the same major version in `compose.yaml`, in the tests, and in CI |
| **Flyway** | schema migrations, run in-process at start-up |
| **HikariCP** | connection pool — note it connects eagerly, see `Database.kt` |
| **bcrypt** | password hashing, with the cost factor stored in the digest |
| **Micrometer + Prometheus** | `/metrics`, unauthenticated — it must not be publicly exposed |
| **ktlint + detekt** | `maxIssues: 0`, same policy as the client repository |
| **Testcontainers** | the database tests run against a real Postgres, never an in-memory stand-in |

```
src/main/kotlin/com/tripletriad/server/
  Application.kt       entry point — the start-up order is the point, as is the sweep it launches
  ServerConfig.kt      the environment, read once, refusing to guess outside development
  Database.kt          pool + migrations
  Catalogs.kt          cards, npcs, formats, campaigns, starters — the server's own copies
  Observability.kt     logging, correlation ids, metrics, error shape, rate-limit buckets
  Secrets.kt           password hashing and token fingerprints
  Authentication.kt    who is calling, established from the bearer token
  VersionGate.kt       refuses a client too old to be talked to, before its request is read
  BodyLimit.kt         how much body this server will read, and why there has to be a number
  Rejected.kt          the refusal shapes and the status codes they travel under
  HealthRoutes.kt      liveness and readiness, which are different questions
  ServerRoutes.kt      /server — the one permanently ungated route
  AccountRoutes.kt     registration, sign-in, profile, bag, shop, starter, campaign entry
  AccountStore.kt      who a player is and what they have
  MatchRoutes.kt       /matches — the transcript path: parse, replay with :core, answer
  MatchCrediting.kt    turning an accepted transcript into progression
  PveRoutes.kt         /pve/matches — the refereed path, where the server plays the opponent
  PveStore.kt          the live PvE matches
  PveMatchRow.kt       a stored PvE match, and the state derived from it
  PvpRoutes.kt         /pvp — tables, challenges, moves, claims
  PvpStore.kt          the open tables, the invitations and the live matches
  PvpMatchRow.kt       a stored PvP match, and the state derived from it
```

Every route above obeys the same four cross-cutting rules — gate the version *before* reading the
body, authenticate through `authenticate(store)`, put anything that spends or grants behind
`AccountStore.applyOnce`, and answer a rejected claim with a 200 and a reason rather than a 4xx.
`CLAUDE.md` states them; the files named there explain why.

## Operating it

[docs/operations.md](docs/operations.md) — configuration, exit codes, backups, and an explicit list
of what is not built yet.

The backups section is the one to read. Progression being server-held means losing this database
ends the game, and the scripts in `scripts/` are a starting point rather than a strategy.

[docs/deployment.md](docs/deployment.md) — provisioning the VPS, the GitHub secrets, and how a tag
becomes the running server. There is deliberately no `version` in `build.gradle.kts`: the release
version *is* the tag.

```
git tag -a v0.7.12 -m "What changed" && git push origin v0.7.12
```

CI verifies the tagged commit, pushes an image to `ghcr.io`, and the host pulls that digest and
restarts — rolling itself back if the new one never answers `/health/ready`.

[docs/data-inventory.md](docs/data-inventory.md) — what personal data the schema holds, derived per
migration. A migration that adds a column about a player updates it.

[docs/security-review.md](docs/security-review.md) — the review of the whole repository and what
each finding cost.

[docs/core-package.md](docs/core-package.md) — why `com.tripletriad:core` is a published package and
not a directory somebody has to have.
