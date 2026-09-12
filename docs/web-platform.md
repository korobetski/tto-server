# The web platform

The plan for `tto-web`: a public portal, the game in a browser, and an administration console —
the decisions that shape all three, and the order they get built in.

> **Status: none of this is built.** Every host, table, route and module named below is a proposal.
> This document exists so the choices are made once, in the open, rather than discovered one at a
> time by the first implementation — and so the alternatives that lost stay readable next to the
> ones that won. When a section is implemented, its future tense goes.

It lives in this repository rather than in `tto-web` because most of what is genuinely *decided*
here is server-side: the hosts Caddy will serve, the schema an administration console needs, and
the one constraint the whole design has to keep. `tto-web` will hold the pages, not the reasoning.

---

## Three products, not one

"A website for Triple Triad" is three things with three audiences, three weights and — the part
that matters — three different blast radii:

| | Audience | Authentication | Weight |
|---|---|---|---|
| **Portal** | anyone, including search engines | none, except during sign-up | static, indexable, small |
| **Game** | a player | player bearer token | Compose/wasm, megabytes |
| **Admin** | one or two people | admin credential + TOTP | static SPA, tiny |

They ship from one repository and they are **not one application**. Most of what follows is that
sentence, applied.

---

## The names, and the one that cannot move

`https://tto.moebiuscore.fr` is **compiled into builds that are already installed**:

```
tto-client/androidApp/src/main/res/values/server.xml   Moebius=https://tto.moebiuscore.fr
tto-client/desktopApp/…/Main.kt                        DEFAULT_SERVERS
```

and persisted per installation by `ServerDirectory`, which is a `DocumentStore` on the player's own
device. Renaming that host does not migrate anybody: it strands every install that has it, with no
route back, because the address a player would need to type is the thing that stopped answering.

**So the API keeps that name permanently, and everything new is placed beside it:**

| Host | Serves | Step |
|---|---|---|
| `tto.moebiuscore.fr` | the portal, and the public API underneath it | exists / 1 |
| `admintto.moebiuscore.fr` | the administration console and `/admin/*` | 2 |
| `playtto.moebiuscore.fr` | the browser game | 3 |

Siblings rather than sub-domains of `tto.` — `playtto.` and not `play.tto.` — deliberately. A
sub-domain can set a cookie scoped to its parent (`Domain=tto.moebiuscore.fr`) that every other
sub-domain then receives, so a compromised game host could fix a cookie the admin host would read.
Siblings cannot do that to each other.

That protection is **not** what the design relies on, because it does not cover the apex: anything
else ever hosted on `moebiuscore.fr` can still set a `Domain=moebiuscore.fr` cookie. What covers
both is the `__Host-` prefix on the admin session cookie, which browsers refuse to accept with a
`Domain` attribute at all. The prefix is the control; the sibling naming is the belt beside it.

---

## The shape of it

```
                         Caddy, on the VPS, terminating TLS
                                        │
   tto.moebiuscore.fr        admintto.moebiuscore.fr      playtto.moebiuscore.fr
   ──────────────────        ───────────────────────      ──────────────────────
   portal   (static)         console        (static)      wasm bundle   (static)
   + the public API          + /admin/* only              + the game's API
        │                            │                             │
        └────────────────────────────┴─────────────────────────────┘
                        one Ktor server, reached three ways
```

Each host serves its own static files **and reverse-proxies the API routes it needs**, so nothing
in the system is ever a cross-origin request. What each one refuses matters as much as what it
serves:

| Host | `file_server` | `reverse_proxy` | 404, explicitly |
|---|---|---|---|
| `tto.moebiuscore.fr` | the portal | everything else | `/metrics`, `/health/*`, `/admin/*` |
| `admintto.moebiuscore.fr` | the console | `/admin/*` only | everything else |
| `playtto.moebiuscore.fr` | the wasm bundle | the game's routes | `/metrics`, `/health/*`, `/admin/*` |

This is the pattern the `Caddyfile` already applies to `/metrics` and `/health/*` — served, but not
from outside — extended to say *which host* a route belongs to.

### What it buys

- **`ktor-server-cors` never enters this project.** Not for the portal, not for the console, not
  for the browser game. In particular there is never an `allowCredentials = true` to write, which
  is the CORS setting that is most often wrong.
- **`/admin/*` is not addressable from the game's origin.** A route that 404s on the public host
  cannot be probed there, whatever it is guarded by.
- **CSRF protection is nearly free.** With the console same-origin with its own API, a `__Host-`
  cookie and `SameSite=Strict`, there is no cross-site request left for an attacker to forge.

### The one thing to get the right way round

Caddy's fallback on the portal host must be the **API**, with the portal's paths enumerated:

```
route {
    handle @internal { respond 404 }
    handle @portal   { root * /srv/web/portal; file_server }
    handle           { reverse_proxy server:8080 }
}
```

The failure modes are not symmetric. A portal page missing from `@portal` 404s visibly and harms
nothing. An **API** route missing from it would be served as an absent static file instead — so
adding a route to this server, months from now, would silently break every installed client at the
next portal deployment. The fallback is what makes that impossible.

Wrapped in a `route` rather than left as three sibling `handle` blocks, because sibling handlers are
ordered by how specific Caddy judges their matchers to be and a named matcher's specificity is not
something the file can state or a reader can check. `route` runs its children in written order, so
the order above is the order that happens.

The API owns nine prefixes today — `/server`, `/accounts`, `/sessions`, `/me`, `/matches`, `/pvp`,
`/auctions`, `/health`, `/metrics` — and `/` is free. None of them collides with anything a static
site generator emits.

### Rejected: separate origins for everything, with CORS

The first draft of this document put the portal on its own host and reached the API cross-origin.
It is the tidier diagram and it costs a CORS configuration, a dependency, and a preflight on every
sign-up. Serving the portal from the API's own host deletes all of that and loses nothing, because
the portal is static: there is no server to move, only files.

### Rejected: the browser game at `tto.moebiuscore.fr/play`

Considered, because it needs no new host at all and the portal is already there. Rejected on the
timeline: today it looks harmless, and it bites at step 3.

The portal is the most exposed part of the system — editorial content, build-time dependencies,
whatever third-party script gets added one day. The browser game is the most valuable: it is the
thing holding players' session tokens. On one origin, a stored cross-site scripting hole in a news
post reaches the session of every signed-in web player. Separate hosts keep the cheap surface away
from the expensive one.

---

## The portal

Static pages on `tto.moebiuscore.fr`: what the game is, the news, download links for the real
clients, and sign-up. **Astro**, for Markdown content collections and a fully static output, and
because the admin console can later be built by the same toolchain.

### Bilingual from the first commit

French and English, at `/fr/*` and `/en/*`, with the root redirecting on `Accept-Language`. Chosen
now rather than later because retrofitting i18n means touching every page that exists by then, and
today that number is zero.

### News as files, not as a table

A `news` table with an editor in the console is the obvious design and it is not the first one to
build. Markdown in `tto-web`, rendered at build time, gives version history, review before
publication and no runtime at all; the cost is that publishing a post is a deployment. An RSS feed
comes free with the generator and is what aggregators read.

That trade flips the day somebody who is not a developer writes the news. Until then a CMS is a
schema, an editor, and a path that turns author input into HTML — a stored cross-site scripting
vector — in exchange for saving a `git push`.

### Downloads point at GitHub Releases

The client already knows that address as `RELEASES_PAGE` in `GithubReleases.kt`. The portal links
to it and does **not** call the GitHub API at build time: a build that depends on a rate-limited
third-party API is a build that fails at the wrong moment, which for a static site is the moment
somebody publishes a news post.

### Sign-up, exactly as the server already implements it

```
POST /accounts          X-TTO-Version: 6.1.0     {username, password, email}
    → 201 Created, and the body is a session      (the code is emailed by Brevo)
POST /me/email/verify   Authorization: Bearer …  {code}
    → verified → "download the client and sign in"
```

Three consequences, each of which is a thing to get right rather than a thing to notice later:

- **The portal holds a session token.** `POST /accounts` answers "201 with a session, not 201 with
  'now go and sign in'", and `/me/email/verify` is authenticated. So the token exists in the
  browser between the two calls. It goes **in memory only** — never `localStorage`, never a cookie
  — and is dropped once the address is verified. The player signs in properly in the real client.
- **An email address is mandatory.** The route refuses registration without one. There is no
  anonymous sign-up path to design.
- **The portal must send `X-TTO-Version`.** `requireCompatibleClient()` refuses an *absent* header
  with 426, deliberately, so a page that omits it cannot register anybody.

### Rejected: verification by clickable link

The ordinary web flow, and better UX. Rejected for v1 because the code flow already exists, is
tested, and needs no new route, no single-use token table and no landing page — and because a token
in a URL is a token in the browser history, the `Referer` and any log that records the path.

### The pages that collecting an email makes mandatory

A privacy policy — `data-inventory.md` is already its raw material, since it tracks personal data
per migration — legal notices, a contact address, and a mention of `DELETE /accounts/me`, which
exists.

---

## The administration console

Scope for v1: **consultation and statistics**, **economy**, **match disputes**. Explicitly *not*
sanctions — no ban, no suspension, no forced rename.

That combination says something worth writing down, because it should shape the interface: the need
is **player support**, not behaviour moderation. Repairing a bug, refunding, arbitrating a
disputed match. So the console opens on a player search, not on a queue of reports, and `accounts`
gains no state column in v1.

### It is not a flag on `accounts`

`accounts.is_admin BOOLEAN` is the smallest possible change and the wrong one.

A player's bearer token is minted by the game client and stored on phones and desktops; `sessions`
has no notion of privilege and never had to. Make administration a column on `accounts` and every
one of those tokens becomes a potential administrative credential — a stolen phone session, or a
cross-site scripting hole in the web game, escalating to full control. The privilege would ride on
a credential designed for a completely different threat model.

### The tables

- **`admins`** — its own password hash, its own TOTP secret, `disabled_at`. A link to `account_id`
  is optional and exists for attribution ("who did this, as a player"), never for authentication.
- **`admin_sessions`** — short expiry, delivered as a **`__Host-` prefixed** cookie: `HttpOnly`,
  `Secure`, `SameSite=Strict`, `Path=/`, and no `Domain` attribute, which the prefix enforces.
- **`admin_audit`** — append-only: who, when, what, on whom, before and after.

TOTP is not optional. It is the highest-value control available for an authenticated surface on the
public internet, and it costs about forty lines and one column.

**This paragraph said "one small vetted library" and the implementation went the other way.** `Totp.kt`
argues it out: the JDK already has HMAC-SHA1 and lacks only base32, RFC 6238 publishes test vectors —
so the arithmetic can be *checked* rather than trusted, which `TotpTest` does with all six of Appendix
B's — and a dependency on the classpath of the process refereeing the economy is a worse trade than
forty lines of published arithmetic. The contrast with bcrypt is the point: that is a primitive nobody
should implement, and it has no published vectors that would tell you if you had.

### The first administrator, without a secret in a log

An environment variable creates the account at start-up, idempotently, with a password only. The
TOTP secret is **enrolled on first sign-in**, in the console, and never passes through the process
that writes the logs. This repository's rule about secrets is explicit and a bootstrap that logged
an enrolment URI would break it on the very first boot.

### Every write goes through the code that already exists

`AdminStore` **reads**. It never writes on its own. Anything that grants or spends calls the
existing `AccountStore` methods and `applyOnce`, so a retried refund returns the first answer
instead of performing a second one — and the audit row is written **in the same transaction as the
effect**. An audit that can be missing while the effect happened is worth nothing.

`CLAUDE.md` requires `requireCompatibleClient()` on every new route. These are not game-client
routes and replay no transcript, so the gate does not apply — but that is an exception, and it is
expected to be **written down beside the code**, the way a detekt suppression carries the reason
the rule is wrong there.

### The match inspector shows a move list, not a board

The cheapest v1 by a wide margin, and it is not a compromise for long: once `:webApp` exists at
step 3, the console can reuse the game's own renderer. Building a second board renderer now means
throwing it away in six months.

### Rejected: administering through direct SQL

Considered, because `docker compose exec postgres psql` exists today and costs nothing. Rejected as
the destination: it bypasses `applyOnce`, leaves no audit trail, and puts a hand-typed `UPDATE`
against player balances one typo away from an irreversible mistake. It stays the right tool for
one-off investigation, which is a different job.

---

## Statistics

### The definitions belong in the schema

A Flyway migration creating a `stats` schema of read-only views, rather than queries living in
dashboard panels. Three ambiguities make it worth a migration, because every consumer would
otherwise resolve them differently and silently:

1. **"A match" is three different numbers.** `matches` is credited PvE history, `pve_matches` is
   server-refereed sessions, `pvp_matches` is player versus player with its own `FORFEITED` and
   `ABANDONED` states. All three are legitimate; a dashboard that does not say which one it shows
   is showing an accident.
2. **The bots inflate everything.** With `TTO_BOTS_ENABLED` set, the server plays itself. Any count
   of players or matches that does not exclude `bots.account_id` measures the lobby-filling
   machinery rather than the game.
3. **"Registered" is not "verified".** `accounts.email_verified_at` separates them, and
   `accounts.seen_at`, added in `V15__presence.sql`, is what makes daily and weekly actives
   answerable at all.

### A read-only role

`tto_stats`, with `USAGE` on the `stats` schema and `SELECT` on its views, and nothing on `public`.
Then a compromise of whatever reads those views cannot reach `password_hash`, the email addresses
`data-inventory.md` tracks, or `sessions.token_hash`.

This is the reasoning of `docker/postgres/init/10-app-role.sh` applied a second time — and it
carries that file's trap: `/docker-entrypoint-initdb.d` runs **only on an empty data directory**.
On the deployed host the role is created by a one-off `psql`; the init script exists so that
`docker compose down -v && docker compose up -d` still produces a working database.

### Grafana is a stopgap, on purpose

`operations.md` deliberately excludes Prometheus and Grafana — "two containers nobody looks at are
not observability". That premise changes the moment somebody is actually looking, and a Grafana on
the compose network with no published port, reached through an SSH tunnel, answers the statistics
question in an afternoon with no public surface at all.

It is written here as disposable: the console is where these numbers belong once it exists, and a
Grafana that outlives it is a second place to change a metric. What survives either way is the
`stats` schema, which both read.

---

## The browser game

### The browser can run the real engine

This was checked rather than assumed. `:core` is `commonMain` and nothing else — its own build file
states the constraint as "no Compose, no UI, no resource bundle, no platform I/O; `commonMain` here
imports `kotlin` and `kotlinx` and nothing else". Its four dependencies each publish a `wasm-js`
artifact at the exact versions pinned:

```
org.kotlincrypto.hash:sha2                       0.8.0     sha2-wasm-js                    ✓
org.kotlincrypto.random:crypto-rand              0.6.0     crypto-rand-wasm-js             ✓
org.jetbrains.kotlinx:kotlinx-serialization-json 1.11.0    …-serialization-json-wasm-js    ✓
org.jetbrains.kotlinx:kotlinx-coroutines-core    1.11.0    …-coroutines-core-wasm-js       ✓
```

### Why that decides the shape of `tto-web`

Because the alternative is a second implementation of the rules.

The server verifies a match by **replaying it with the same engine the client played it with**, so
that the two ends cannot disagree about who won. A browser client written in TypeScript would place
cards, resolve captures and produce a transcript — that engine again, in another language,
maintained by hand against `:core`.

The failure mode is worse than duplicated effort. An engine that diverges by one rule produces
transcripts that replay differently on the server, and this server's answer to a transcript that
does not replay is to reject it **as cheating**. The bug surfaces as honest players being told they
cheated, intermittently, in one client only.

So: **the browser runs `:core`, or there is no browser client.**

### The platform surface is five pieces, not four

`:shared/commonMain` holds four `expect` declarations — `OpenUrl`, `ReducedMotion`, `MatchNetwork`
and `ServerStatus` — which is why `androidMain`, `desktopMain` and `iosMain` each hold four files.

The fifth is **`DocumentStore`**, which is an interface implemented per application module rather
than an `expect`, and therefore easy to miss when counting. `ServerStores` wires four of them —
transcript queue, session, server directory, tickets. A browser implementation over `localStorage`
or IndexedDB is the piece that will **hold the session token**, so it deserves the most care of the
five.

Also to verify: the catalogs are read out of the Compose resource bundle by `CatalogLoaders.kt` in
`:shared`, and that path has to work under wasm.

### `:webApp` belongs in `tto-client`

It is the same application with another target, beside `androidApp` and `desktopApp`, and it links
`:shared` directly. Putting it in `tto-web` would mean publishing `:shared` as an artifact the way
`:core` already is — a second publication pipeline and a second version to keep in step, for a
module with exactly one consumer. What `tto-web` receives is the build output.

### What is unmeasured

Bundle size, cold-start time, text input on mobile browsers, canvas focus. These are the output of
the spike in step 3.1, not assumptions this document is entitled to make. A Compose/wasm bundle is
measured in megabytes because the renderer ships inside it — acceptable for a game somebody chose
to open, and the concrete reason the portal is not built this way.

---

## The plan

### Step 0 — Foundations

Dull, blocking, and smaller than it was once the portal shares the API's host: no new DNS record,
no new certificate, nothing Let's Encrypt can rate-limit.

| | |
|---|---|
| 0.1 | `Caddyfile`: the route above, with the API as the fallback and a strict CSP on the static branch |
| 0.2 | `compose.prod.yaml`: mount `./web` into Caddy read-only; a development Caddy behind `--profile web` |
| 0.3 | `scripts/deploy.sh`: reload Caddy, because a bind-mounted `Caddyfile` is delivered and never applied |
| 0.4 | A placeholder `index.html` under `/srv/tto/web/portal`, to prove the routing |
| 0.5 | `tto-web` repository, and CI: build → tar → ssh → unpack into a versioned directory → swap a symlink |

The symlink swap is not fastidiousness: without it, an interrupted upload serves a half-written
site.

**Done when** a blank page answers over HTTPS on `tto.moebiuscore.fr/`, deployed by `git push`,
and every existing API route still answers exactly as before.

### Step 1 — The portal

| | |
|---|---|
| 1.1 | Astro skeleton, `/fr` and `/en`, root redirect on `Accept-Language` |
| 1.2 | News: content collection, index, article page, RSS |
| 1.3 | Download page, linking GitHub Releases |
| 1.4 | Sign-up: the two calls above, token in memory, `X-TTO-Version` on both |
| 1.5 | Privacy policy, legal notices, contact |

**No server change at all in this step.** That is a property worth protecting: if something here
seems to need one, it is worth re-reading why before writing it.

**Done when** somebody reads a news post in French, creates an account, enters their code,
downloads the client and signs in with it.

### Step 2 — The administration console

| | |
|---|---|
| 2.1 | `V18__stats_views.sql` — the `stats` schema and the `tto_stats` role |
| 2.2 | `V19__admin.sql` — `admins`, `admin_sessions`, `admin_audit` |
| 2.3 | Admin authentication: password + TOTP, `__Host-` cookie, an `ADMIN_SIGN_IN` rate-limit bucket |
| 2.4 | `AdminRoutes.kt` and `AdminStore.kt` — reads direct, writes through `applyOnce`, audit in the same transaction |
| 2.5 | `admintto.moebiuscore.fr` in Caddy, and `/admin/*` → 404 on the other two hosts |
| 2.6 | The console: sign-in, dashboard, player search and detail, match inspector, auction house |

Step 2.1 is self-contained. If the numbers are wanted before the console exists, that migration
alone provides them, read by a Grafana over an SSH tunnel.

**Done when** signing in with password and TOTP shows the figures, a player can be found, five
hundred MGP can be credited to them, and the action is in `admin_audit` with its before and after.

### Step 3 — The browser game

| | |
|---|---|
| 3.1 | The spike: `wasmJs { browser() }` on `tto-core`, common tests green, published |
| 3.2 | `:shared` for wasm: the four actuals, plus a browser `DocumentStore`, plus catalog loading |
| 3.3 | `:webApp`, and its build output carried by `tto-web`'s CI |
| 3.4 | `playtto.moebiuscore.fr` in Caddy |

**Step 3.1 belongs in step 1's calendar, not step 3's.** It is half a day, it is the only item that
can invalidate the rest, and it produces the bundle-size numbers this document is missing. Doing it
early costs nothing; doing it late costs the plan.

**Done when** a complete PvE match is played in Chrome and in Firefox and the server accepts the
transcript.

---

## What breaks on the first day

Named rather than discovered:

- **A major protocol bump breaks web sign-up** until `tto-web` is redeployed, because the portal
  sends `X-TTO-Version` like any other client. Whether the server's release pipeline triggers that
  redeployment, or it is done by hand and watched, is **not yet decided**.
- **The registration rate limit becomes load-bearing.** `REGISTER` is keyed on the caller's
  address, which is a fact rather than a claim only because `trusted_proxies` is absent from the
  `Caddyfile` on purpose. Opening sign-up to the web raises that bucket's value; it does not change
  how it works. Do not add `trusted_proxies` without reading why it is missing.
- **Cache headers on the wasm bundle are part of shipping it.** A stale cached bundle is exactly
  the case `VersionGate.kt` exists for, and the browser is the one client that caches its own code.
- **Three hosts reach one server.** Rate limits are per address and unaffected, but every new route
  now has a question attached: which hosts should be able to see it.

---

## What is not decided yet

- Whether a server release redeploys the portal automatically.
- Where the browser game keeps its session token — `localStorage` is readable by any script that
  gets injected, an in-memory token dies on refresh. Decided when `:webApp` is written.
- The bundle size, and therefore whether the game is usable on a phone browser at all.
- Whether the news ever becomes a table.
- Whether the console and the portal share a build or only a repository.

---

## Related

- `deployment.md` — the VPS, Caddy, and how a tag becomes the running server
- `operations.md` — configuration, backups, and the observability this document extends
- `data-inventory.md` — the personal data the schema holds, which the statistics views must respect
- `../CLAUDE.md` — the route rules any new endpoint here has to follow
