<div align="center">

# tto-server

**The authority behind [Triple Triad Online](https://tto.moebiuscore.fr) — it holds every player's
progression, and decides whether a match really happened.**

[🌐 Website](https://tto.moebiuscore.fr) ·
[🎮 Play in the browser](https://playtto.moebiuscore.fr) ·
[⬇️ Download the game](https://github.com/korobetski/tto-client/releases/latest) ·
[💬 Discord](https://discord.gg/cPZW74AUTj)

[![CI](https://github.com/korobetski/tto-server/actions/workflows/ci.yml/badge.svg)](https://github.com/korobetski/tto-server/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/tag/korobetski/tto-server?sort=semver&label=release)](https://github.com/korobetski/tto-server/tags)
![Ktor 3](https://img.shields.io/badge/Ktor-3-087CFA?logo=ktor&logoColor=white)
![Postgres 17](https://img.shields.io/badge/Postgres-17-4169E1?logo=postgresql&logoColor=white)
![JVM 21](https://img.shields.io/badge/JVM-21-E76F00?logo=openjdk&logoColor=white)

</div>

---

> **Live at `https://tto.moebiuscore.fr`**, the one address every installed client is built to
> call. Accounts, progression, the shop, solo campaigns, refereed matches between players, the
> auction house and the administration API all run here — and every match that pays anything is
> **replayed with the client's own engine** before it is credited.

## Part of Triple Triad Online

| | Repository | Role |
|---|---|---|
| 🃏 | [tto-client](https://github.com/korobetski/tto-client) | the game — Android, Windows, macOS, Linux and the browser |
| ⚙️ | [tto-core](https://github.com/korobetski/tto-core) | the rules engine both ends link |
| 🛡️ | **tto-server** — *you are here* | the authority: accounts, progression, refereeing |
| 🌐 | [tto.moebiuscore.fr](https://tto.moebiuscore.fr) | the public site, served from this server's own hostname — see [web-platform.md](docs/web-platform.md) |

## What it does

| | |
|---|---|
| 🔑 **Accounts** | registration with e-mail verification, sign-in, password reset, sessions as bearer tokens stored only as fingerprints |
| 🗡️ **Solo play** | the client plays, the server replays the transcript with `:core` and credits what it actually earned |
| ⚔️ **Player vs player** | the server referees every move — tables, challenges, claims. Clients poll; there are no WebSockets |
| 🛒 **Economy** | the shop, the bag, starter decks, campaign entries — every spend or grant idempotent on a client-minted operation id |
| 🏛️ **Auction house** | lots, bids and sales between players |
| 🤖 **Bots** | optional accounts the server plays itself — solo, at lobby tables and at auction — through the same referees a client reaches |
| 🧭 **Administration** | the API behind the operator console, signed in with a second factor (TOTP), with every write audited |

## Why this is a separate repository

Because the client must not be able to become the server. **No server code ships inside the client
builds**, and a separate repository is the only version of that rule which cannot be undone by an
accidental import.

It also means this repository is free to be a plain JVM project — one target, one toolchain, no
Compose, no Android — while the client stays multiplatform.

## The one constraint that shaped every other choice

The server verifies matches by **replaying them with the real engine**, not with a second
implementation of the rules. That is what makes it impossible for the client and the server to
disagree about who won. When a route needs to know what something costs, what a pack contains or
what a win pays, it calls into `:core` rather than computing it.

Which means the runtime must be a **JVM**, so it can link `com.tripletriad:core` directly. That
single requirement rules out most of the interesting hosting options — edge runtimes, most
serverless platforms, every game-backend product whose match handlers are written in its own
language — and it is the reason the stack below looks conventional.

`:core` is published from [tto-core](https://github.com/korobetski/tto-core) to GitHub Packages and
consumed from there — which is what makes this repository buildable on a machine that has never seen
the client's sources, and therefore what makes CI able to build an image and deploy it.

---

## Quick start

**1. A GitHub token.** Reading `com.tripletriad:core` needs a token with `read:packages`, because
GitHub Packages answers an anonymous request with 401 even for a public package. It goes in your
Gradle home file, outside this repository:

```properties
# ~/.gradle/gradle.properties
gpr.user=your-github-username
gpr.key=<token with read:packages>
```

**2. An `.env`.** `GRADLE_PROPERTIES` is the one value with no portable default, because the image
build mounts that file as a secret to resolve `:core`:

```bash
cp .env.sample .env
$EDITOR .env          # GRADLE_PROPERTIES, and a password for each of the two database roles
docker compose up -d --build
curl localhost:8080/health/ready
```

`.env` has no working defaults on purpose: compose refuses to start rather than fall back to a
password that is published in a sample file. There are **two** roles to give a password to — the
superuser that owns the cluster, and the unprivileged `tto_app` the server actually connects as.
See `.env.sample`, which also carries the commands for the case where the volume already exists,
since the bootstrap only ever runs on an empty one.

**Or without Docker**, against a Postgres you already have — Gradle reads your home file directly, so
this needs no `GRADLE_PROPERTIES`, but it does need the two database variables, whose code defaults
no longer match any real database:

```bash
TTO_ENV=development DATABASE_USER=tto_app DATABASE_PASSWORD=... ./gradlew run
```

> [!IMPORTANT]
> `TTO_ENV` is required. Anything other than `development`/`dev`/`local` — **including unset** — is
> production, and production refuses every development default.

### Building and testing

```bash
./gradlew build       # ktlint + detekt + tests + coverage gate
```

The tests migrate and query a real Postgres in a throwaway container, so **a Docker daemon is
required to build**.

### Trying an unreleased engine change

Publish `core` locally from its **own** repository — `settings.gradle.kts` prefers that copy over the
published one, on purpose:

```bash
cd ../tto-core && ./gradlew publishToMavenLocal -PcoreVersion=<the version pinned here>
```

Without `-PcoreVersion` it publishes `build.gradle.kts`'s own fallback, which is usually a different
number from the one `gradle/libs.versions.toml` pins here — and the local copy then shadows nothing.

### Seeing the point of the whole thing

Submit a match. `MatchRoutesTest` builds a real transcript by playing one with `:core`; the server
replays it and answers with **its own** score, and answers a tampered one with a reason:

```json
{"type":"accepted","blue":3,"red":7,"winner":"RED"}
{"type":"rejected","reason":"TRUNCATED","detail":"the board still had 2 cells and the moves ran out after 3"}
```

---

## What is in here

| | |
|---|---|
| **Ktor 3** on Netty | HTTP, with rate limits on sign-in, registration and submissions |
| **Postgres 17** | the same major version in `compose.yaml`, in the tests, and in CI |
| **Plain JDBC** | no ORM; each store's own `transaction {}` is the only thing that commits |
| **Flyway** | schema migrations, run in-process at start-up |
| **HikariCP** | connection pool — note it connects eagerly, see `Database.kt` |
| **Micrometer + Prometheus** | `/metrics` — unauthenticated, and never to be exposed publicly |
| **ktlint + detekt** | `maxIssues: 0`, same policy as the client and the engine |
| **Testcontainers** | the database tests run against a real Postgres, never an in-memory stand-in |

### Route surface

| Prefix | Where | |
|---|---|---|
| `/server` | `ServerRoutes.kt` | the **only** ungated route — a refused client learns here that it must update |
| `/health/live`, `/health/ready` | `HealthRoutes.kt` | liveness never touches the database; readiness does |
| `/accounts`, `/sessions`, `/me/**` | `AccountRoutes.kt` | registration, sign-in, profile, bag, shop, starter, campaign entry |
| `/matches/**`, `/pve/matches` | `MatchRoutes.kt`, `PveRoutes.kt` | solo play: the client plays, the server replays and credits |
| `/pvp/**` | `PvpRoutes.kt` | player vs player: the server referees, the clients poll |
| `/auctions/**` | `AuctionRoutes.kt` | the auction house |
| `/admin/**` | `AdminRoutes.kt` | the operator console's API — only reachable on the console's own hostname |

### Catalogs

`src/main/resources/catalog/*.json` are the server's **own copies** of the client's cards, NPCs,
formats, campaigns and starters. They must stay in step with the client's — drift makes every honest
transcript replay to a different board and be rejected as if it were cheating. A player who cannot
sit down against a new opponent is the usual symptom; re-syncing is a copy from `tto-client`.

---

## Operating it

| Document | Read it for |
|---|---|
| 📘 [docs/operations.md](docs/operations.md) | configuration, mail, bots, exit codes, backups, and an explicit list of what is not built yet |
| 🚀 [docs/deployment.md](docs/deployment.md) | provisioning the VPS, the GitHub secrets, and how a tag becomes the running server |
| 🌐 [docs/web-platform.md](docs/web-platform.md) | the portal, the administration console and the browser game: three sites, three hostnames, and why the API's own hostname is the one that can never move |
| 📦 [docs/core-package.md](docs/core-package.md) | why `com.tripletriad:core` is a published package and not a directory somebody has to have |
| 🔒 [docs/security-review.md](docs/security-review.md) | the ways something could be taken or taken down, with what is already right next to what is not |
| 🗂️ [docs/data-inventory.md](docs/data-inventory.md) | what is stored about a player, and where |

The backups section of `operations.md` is the one to read first. Progression being server-held means
losing this database ends the game.

### Releasing

```bash
git tag -a v0.13.0 -m "What changed" && git push origin v0.13.0
```

CI verifies the tagged commit, pushes an image to `ghcr.io`, and the host pulls that digest and
restarts — rolling itself back if the new one never answers `/health/ready`.
