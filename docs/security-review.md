# Security review

A read of the whole repository — server, schema, compose files, scripts and workflows — looking for
the ways a player, a passer-by or a bad dependency could take something that is not theirs, or take
the server down. Dated 2026-08-24, against `e8988d3`.

It is a point-in-time document and it will go stale, which is why every finding names the file and
the line rather than describing the code: a reader six months from now can check for themselves
whether the thing is still there. When one is fixed, strike it here rather than deleting it. A list
that only ever gets shorter cannot tell anyone which entries were fixed and which were quietly
dropped, and the second kind is the one worth finding.

## Status

Every finding below has been acted on, and each one now ends with a **Fixed** paragraph naming what
changed. Two of them deserve the qualification up front, because "fixed" is doing different work in
each:

- **Finding 6 is half fixed.** The economy no longer mints MGP and the directed path re-checks what
  the lobby path always did. The wager itself is still not *enforceable*, because `PUT /me/save`
  takes the client's word for a purse — closing that means MGP joining the server-owned set in
  `:core`, which is another repository. The half that is done is the half that was a leak; the half
  that remains is the one that was already a known and accepted hole before PvP existed.
- **Finding 10 is a recorded decision rather than a code change.** Usernames on this server are
  public by construction — the lobby lists them — and the property `signIn` protects is the pairing
  of a name with a password, which nothing here touches. The asymmetry was worth resolving; it is
  resolved by writing down which of the two behaviours is deliberate.

The line numbers in the findings are the ones from **before** the fixes, since that is what they
describe. Follow the names, not the numbers.

### What this review has not looked at

**Email confirmation and password reset, added 2026-08-29, are not covered by anything below.**
They arrived after the read and they are the largest new attack surface since it: two endpoints
that take no session by design (`POST /accounts/password/forgot` and `/reset`), a six-digit
secret, a table of code digests, and an outbound call to a third party. The reasoning behind each
bound is written where it is implemented — `CodeStore`, `CodeChannel`, the `CODES` bucket in
`Observability.kt` — and `CredentialRecoveryTest` pins the behaviour. None of that is a review.
The questions a next pass should ask: whether the rate limit and the attempt ceiling are still
inseparable after any later change to either; whether the forgotten-password endpoint is still
indistinguishable between a known and an unknown username in *timing* as well as in its answer;
and whether anything has started logging a code.

## How this was done, and what it could not check

Everything below comes from reading the source. The findings were **found** by reading; they were
not all *confirmed* that way, and the difference is worth stating per finding rather than in one
sweeping sentence.

`./gradlew build` now runs here — ktlint, detekt, the suite against a real Postgres, and the
coverage gate — so every fix in this document was made against a green gate and left one. Where a
fix could be expressed as a test it has one, and where the test could be made to fail without the
fix, it was: finding 1's regression test was run three times against the code with the lock removed
and failed all three, with the exact symptom the finding describes.

Two claims turn on how a third-party library behaves rather than on how this code reads, and
guessing at either would have put a wrong severity on a real finding. Both were checked against the
real artefacts:

- **bcrypt past 72 bytes.** `at.favre.lib:bcrypt:0.10.2` was downloaded and driven directly. It
  **throws** `IllegalArgumentException` — it does not truncate and does not ignore. See finding 5,
  which exists because of that result.
- **What Ktor and Caddy do with `X-Forwarded-For`.** The 3.5.2 sources of
  `ktor-server-forwarded-header` and Caddy 2.10's `reverseproxy.go` were both read. The answer
  turned a suspected rate-limit bypass into finding 9, which is a much smaller thing. It is written
  up as the smaller thing.

One more was checked by running Gradle: which repository serves `com.tripletriad:core`, and in what
order. See finding 8.

## Findings

Ordered by what they cost if exploited, not by how easy they are to fix.

### 1 · Match settlement writes a profile without the lock every other writer takes — HIGH

`AccountStore.lockSave` exists, and its KDoc is the best description of this bug in the repository:
every write to a profile is a read-modify-write, two of them at once both read the same starting
document, and **the one that commits first is erased** — silently, with a `200` for both. `mutate`
and `applyOnce` were given `SELECT … FOR UPDATE` to end that.

The three paths that *pay* were not.

| Path | Reads | Writes | Same transaction? |
|---|---|---|---|
| PvP settlement | `PvpRoutes.kt:853` `saveFor` | `PvpRoutes.kt:867` `replaceSave` | no, and no lock |
| PvE transcript submit | `MatchCrediting.kt:61` `saveFor` | `AccountStore.creditMatch` | no, and no lock |
| PvE refereed settle | `PveRoutes.kt:396` `saveFor` | `PveRoutes.kt:429` `creditRefereedMatch` | no, and no lock |

Each reads the profile in one transaction, computes the credited version in Kotlin, and writes it
back in another. `lockSave`'s lock does not help: it only excludes writers that *take* it, and a
plain `UPDATE characters SET save = …` takes a row lock at the moment it writes, which is far too
late — by then the document it is about to write was derived from a read that is stale.

The `lockSave` KDoc names the exact scenario as the reason the lock exists: "a player who opens the
app has the offline queue draining (which credits matches and writes the profile) at the same moment
they can tap Buy, and both paths land here." The Buy side was fixed. The offline queue — the credit
— is one of the three rows above, so the scenario the comment describes is still open.

**Why it is worse than a lost purchase.** A lost update is symmetric, and a player can choose which
side of it to be on. A PvP loser knows exactly when settlement happens (they made the last move, or
they pressed forfeit), and `PUT /me/save` is covered by no throttle at all — see finding 3 — so
they can spray it across the window. Win the race and the settlement's write is overwritten by the
client's own document: the card they staked never leaves. The winner is credited from their own
row, in a different transaction, and is paid regardless. That is card duplication, driven from an
ordinary client, needing nothing but timing.

**What would fix it.** Route all three through `AccountStore.mutate`, so the read that produces the
credited save is the locked one. The awkward part is that `creditMatch` and `creditRefereedMatch`
also insert a `matches` row and spend a ticket in the same transaction, so the change is to move the
*computation* inside the store rather than to wrap the existing calls — `mutate` takes a
`(GameSave) -> GameSave`, and what these need is a `(GameSave) -> GameSave` that also gets to say
"and this is the match row". Worth doing as one change across the three, because a fix applied to
one of them leaves the other two looking fixed.

**Fixed.** All three paths now compute the credited profile inside the transaction that writes it,
against the row `AccountStore.lockSave` has locked. `creditMatch` and `creditRefereedMatch` take a
`(GameSave) -> Crediting?` instead of a finished `GameSave`, so the replay and the reward see the
profile as it is at the moment it is written; `mutate` grew a return value so a caller can get its
own arithmetic back out of the lock. PvP settlement went further and became
`AccountStore.transfer`, which locks **both** profiles and writes both or neither — it was four
transactions, so a process dying between the loser's write and the winner's paid one side only.
Lock order is by ascending account id, so two matches settling between the same pair cannot
deadlock.

`ConcurrentWriteTest.aCreditedMatchAndAPurchaseDoNotEraseEachOther` pins it, and it is a real
regression test rather than a hopeful one: with the lock swapped back for an unlocked read it fails
three runs out of three, reporting "crediting the match erased the purchase: []".

### 2 · No limit on a request body, on an endpoint that is unauthenticated and unthrottled — HIGH

`POST /matches/verify` (`MatchRoutes.kt:64`) requires only a well-formed version header. It is
deliberately not authenticated — its KDoc explains why, and the reasoning is sound — and it is
deliberately not rate-limited, because "verification is cheap — nine placements".

Nine placements are cheap. Parsing is not, and parsing happens first.
`call.receive<MatchTranscript>()` reads the entire body into memory and deserialises `moves` into a
list before `TranscriptVerifier` ever sees it and rejects the tenth move. Nothing bounds that: Ktor
installs no body-size limit, the `Caddyfile` sets no `request_body max_size`, and the JSON is
aggregated before it is judged.

The Dockerfile then sets `-XX:+ExitOnOutOfMemoryError` (`Dockerfile`, runtime stage). That flag is
correct — a JVM limping on after an OOM is worse than one that dies — but it converts a memory
exhaustion into a **process exit**, and `restart: unless-stopped` converts that into a crash loop.
So an unauthenticated caller who can reach the domain can hold the server down for as long as they
keep sending, and nothing in the logs will say more than "killed".

Reproducing it needs no cleverness: `POST /matches/verify` with a `moves` array of a few million
entries, repeated.

**What would fix it.** A body cap at the proxy is the cheap half — `request_body { max_size 64KB }`
in the `Caddyfile` — and it is the half that also protects every other route. The other half belongs
in the application, because the proxy is not the only way in on the compose network: a
`RequestValidation`-style guard, or simply reading `Content-Length` and refusing early. A transcript
is nine moves and a deck; anything past a few kilobytes is not one.

**Fixed**, at both layers. `BodyLimit.kt` is a small application plugin that refuses a declared
length over 256 KiB with **413** and a body with no declared length — chunked — with **411 Length
Required**, both in `ApplicationCallPipeline.Plugins`, before routing has chosen a handler. The
`Caddyfile` gained `request_body { max_size 256KB }` for the same number at the edge, where a body
can be dropped on the first hop that sees it.

`BodyLimitTest` asserts both refusals over a **real socket** against a real Netty, because Ktor's
test host does not frame requests and its client will not set `Transfer-Encoding`. Those two tests
send the request head and then nothing at all: a server that answers has refused without reading,
which is the property under test. A read timeout means a server that decided to wait fails the test
rather than hanging it.

### 3 · The whole `/pve/**` surface has no rate limit — MEDIUM

`Observability.kt` names five buckets and explains what each defends. The second is farming: "a
transcript is unforgeable but it is not slow to produce: a bot playing real matches at machine speed
earns real rewards … Only the cadence distinguishes them, so only a cadence limit addresses it."
`SUBMIT` is that limit, and it is applied at `MatchRoutes.kt:110`.

The refereed PvE path pays the same rewards and passes through none of it. `rateLimit(` appears
seven times in the source and not once in `PveRoutes.kt`: `POST /pve/matches`,
`GET /pve/matches/active`, `GET /pve/matches/{id}` and `POST /pve/matches/{id}/moves` are all
unthrottled. A bot opens a match,
plays nine moves, is credited by `PveReferee.settle`, and repeats — at whatever rate the network
allows. The cadence defence is not weakened here; it is absent.

Two smaller things ride along. `POST /pve/matches` calls `pve.abandonLive` and then inserts, so each
attempt leaves a dead row behind — unbounded growth in `pve_matches` with nothing pruning it. And
`PUT /me/save`, `GET /me`, `GET /matches/tickets` and the PvP move, forfeit and claim routes are
unthrottled too; `GET /matches/tickets` is a `GET` that writes, which is defensible on its own terms
(it is idempotent by arithmetic) and less so with no ceiling at all.

**What would fix it.** `SUBMIT` on `POST /pve/matches` and on `POST /pve/matches/{id}/moves`. The
numbers already chosen fit: thirty a minute is twenty times a person's ceiling either way.

**Fixed.** `POST /pve/matches` is now in the `SUBMIT` bucket — the one guarding `/matches/submit`,
because they are two doors into the same payout — and `POST /pve/matches/{id}/moves` is in a new
`PLAY` bucket at 120 a minute, which also covers the PvP move, forfeit and claim routes that had
none. `PUT /me/save` joined `INTENT`, and so did `GET /matches/tickets`, which is a `GET` that
writes. The polling reads are deliberately left alone: throttling `/pve/matches/active` or
`GET /pvp/match` would throttle resuming and watching an opponent think.

The row churn in `pve_matches` is bounded by the same limit rather than by a sweep of its own — one
dead row per attempt, at a rate now capped at thirty a minute per account.

### 4 · The per-session rate-limit key can be multiplied by banking tokens — MEDIUM

`callerKey()` (`Observability.kt:104`) keys `SUBMIT`, `LOBBY` and `INTENT` on the fingerprint of the
bearer token. The KDoc anticipates the obvious attack and answers it: "Rotating tokens to escape a
session bucket means signing in repeatedly, which is what `SIGN_IN` limits."

That answer holds for rotating *now* and not for having rotated *already*. Nothing caps how many
live sessions an account may hold — `openSession` (`AccountStore.kt:187`) deletes only this
account's **expired** rows and then inserts, without a count — and a session lasts thirty days. So
ten sign-ins per five minutes is not a ceiling on concurrent budgets, it is a *fill rate* for them:
an hour of signing in banks a hundred and twenty tokens, all valid for a month, each with its own
thirty-submissions-a-minute allowance. The attacker then uses them together.

The key is wrong rather than the number. An anti-farming limit is about what an **account** earns,
and the account id is known at the moment the bucket is consulted — it is exactly what
`authenticate` returns.

**What would fix it.** Key the authenticated buckets on the account rather than the token. That
needs the account id available to `requestKey`, which today runs before the handler does its own
`authenticate`; the smaller version, worth doing regardless, is to cap live sessions per account in
`openSession` — evict the oldest past some number — which bounds the multiplier whatever else
changes.

**Fixed**, both halves. `callerKey` resolves the bearer token to an account and keys `SUBMIT`,
`LOBBY`, `INTENT` and `PLAY` on `account:<id>`, which cannot be multiplied by banking tokens. It
costs no extra query: the resolved id is left on the call in `ResolvedAccount` and `authenticate`
reads it there rather than looking the same token up again a moment later.

`AccountStore.openSession` also caps an account at `MAX_SESSIONS` — ten devices — evicting the
oldest, which bounds the table and the blast radius of a stolen token whatever else changes.
`AccountSecurityTest.anAccountHoldsABoundedNumberOfSessions` pins it.

### 5 · A long password is a 500, not a refusal — MEDIUM

`Secrets.kt:38` says of bcrypt's 72-byte limit: "Input past 72 bytes is **ignored**, silently."

It is not. Driving `at.favre.lib:bcrypt:0.10.2` directly:

```
hash(100 bytes)   THREW: java.lang.IllegalArgumentException: password must not be longer than
                         72 bytes plus null terminator encoded in utf-8, was 100
verify(100 bytes) THREW: java.lang.IllegalArgumentException: … was 100
```

The library's default is to refuse rather than to truncate. So the paragraph describes a behaviour
this dependency does not have, and the guard the paragraph argues for — tell the player at the form
— was never built: `MAX_PASSWORD_BYTES` is declared at `Secrets.kt:42` and referenced nowhere in the
server.

Registration at least calls `credentials.looksValid()` (`AccountRoutes.kt:92`). The other two paths
that hash do not:

- `signIn` (`AccountRoutes.kt:547`) receives `Credentials` and goes straight to `verifyOrDecoy`.
- `DELETE /accounts/me` (`AccountRoutes.kt:159`) receives `Credentials` and goes straight to
  `PasswordHasher.verify`.

Both throw. `StatusPages` catches it as an unhandled failure, answers `500 internal_error`, and logs
`Unhandled failure` at **error** with a full stack trace. Signing in is unauthenticated, so anyone
can produce that.

And on the one path that does validate, the check cannot express the limit it is standing in for:
`Credentials.PASSWORD_LENGTH` counts characters and bcrypt counts bytes.

```
emoji: 60 chars, 120 bytes → verify THREW: … was 120
```

Sixty characters is inside any plausible character-count range and is twice the byte limit, so a
passphrase of emoji reaches bcrypt however tight the range is set.

**What would fix it.** Check `password.toByteArray(UTF_8).size <= MAX_PASSWORD_BYTES` in
`PasswordHasher`, at the top of both `hash` and `verify`, and return `false` from `verify` rather
than throwing — the same judgement `verify` already makes for a malformed digest, which was checked
and does behave as its KDoc claims. Then have the three routes report it as the malformed-credentials
`400` they already have a shape for. And correct the paragraph at `Secrets.kt:38`, because it is the
reason nobody wrote the guard.

**Fixed.** `PasswordHasher.isUsable` measures the password in UTF-8 **bytes**; `verify` returns
`false` past the limit rather than throwing, which is both safe and correct — no stored digest can
match a password bcrypt would refuse — so `signIn` and account deletion give their ordinary 401.
`hash` `require`s it, and registration checks it alongside `looksValid` so an over-long password is
refused at the form with the 400 it always should have had, naming the byte limit.

The wrong paragraph in `Secrets.kt` is rewritten to say what the library actually does and why the
guard now exists. `AccountSecurityTest` covers both arms, including the emoji case: sixty
characters, two hundred and forty bytes, refused at registration.

### 6 · The PvP wager has no escrow, and is re-checked in one of the two ways in — MEDIUM

`PvpReferee.openTable` states the position plainly: "there is **no escrow** — `MatchRewards.creditPvp`
floors a purse at zero, so an unaffordable wager would quietly become a free one, and the only
honest moment to refuse is before anybody plays." The purse is therefore checked when a table opens
(`PvpRoutes.kt:494`), when one is joined (`PvpRoutes.kt:529–537`, both sides, inside the claiming
transaction — carefully done), and when a challenge is sent (`PvpRoutes.kt:604–605`, both sides).

Two gaps follow from "before anybody plays" being the only moment.

**Nothing re-checks at settlement.** A match runs for minutes; `PUT /me/save` and `POST /me/shop/buy`
are available throughout. A player who wagers 5000 and spends it mid-match loses whatever they have
left, floored at zero, while the winner is credited the full 5000 from `spoilsFor`. The difference
is MGP that did not exist before the match.

**`accept` does not check at all.** `challenge` verifies both purses when the invitation is *sent*
(`PvpRoutes.kt:601`), and `accept` (`PvpRoutes.kt:610`) opens the match on the stored terms without
looking again — where the table path deliberately re-checks inside `claimTableAndOpen`. The KDoc on
`checkTerms` warns about exactly this asymmetry for the *rules* — "which, given one of them would be
the *directed* path, is how you end up able to invite a friend to a match the lobby would have
refused to advertise" — and the purse check is the case it did not get applied to.

There is a third edge on the same argument, and it is the one that makes the others matter:
`PUT /me/save` takes the client's word for MGP by design, which `AccountStore.replaceSave` records
as a known and accepted hole ("a determined player can still give themselves MGP"). That acceptance
predates PvP, and PvP changed what it costs: an accepted "a player can edit their own purse" becomes
"a player can refuse to pay a wager while their opponent is paid for winning it". Cards are not
exposed the same way — the `/me/starter` KDoc says `cards` joined the server-owned set that
`GameSave.withServerOwnedFrom` reclaims — but MGP explicitly did not.

**What would fix it.** Escrow at match open: deduct both stakes into the match row, and settle from
what was taken rather than from what the purses hold at the end. That also makes finding 1's race
worth less, because the money has already left. Short of escrow, re-check in `accept` as `joinTable`
does, and treat a purse that no longer covers the stake at settlement as a forfeit rather than a
discount.

**Half fixed, and the half that remains is named.**

Two things changed. `accept` now re-checks both purses inside the claiming transaction and refuses a
player already in a match, which is what `joinTable` always did — it answers with `Joined` now, the
same type joining answers with, so the two doors agree. And settlement **clamps the transfer** to
what the loser can actually pay, read under the same lock that writes it: the winner is credited
what the loser is debited, so the difference the floor used to create is no longer minted.

What is not fixed is that the wager is not *enforceable*. A player can still arrive at settlement
unable to cover what they agreed, and pay less than they staked. Closing that needs either escrow —
deducting both stakes at match open, which is a schema change — or, better, MGP joining the
server-owned set in `GameSave.withServerOwnedFrom`, which is a `:core` change and would also close
the older hole this one grew out of. `PvpReferee.creditBoth` carries that note next to the clamp.

### 7 · `/pvp/**` never calls `requireCompatibleClient()` — MEDIUM

`CLAUDE.md` states one cross-cutting rule ahead of the others: "Call `requireCompatibleClient()`
before `call.receive()`. A major-version mismatch is exactly the case where this build may misread
the body." `AccountRoutes`, `MatchRoutes` and `PveRoutes` all follow it. `PvpRoutes.kt` contains no
call to it at all — not on `POST /pvp/tables` (`:131`), `POST /pvp/tables/{id}/join` (`:158`),
`POST /pvp/challenges` (`:194`), `POST /pvp/challenges/{id}/accept` (`:228`),
`POST /pvp/match/{id}/move` (`:281`), `POST /pvp/match/{id}/forfeit` (`:293`), or
`POST /pvp/match/{id}/claim` (`:337`).

So the entire surface on which wagers are agreed and cards change hands is the one surface with no
version gate. A client on an incompatible major reaches `call.receive<PvpClaim>()` and
`call.receive<PvpTableRequest>()` with a body this build has no reason to believe it can read — and
those two bodies name, respectively, which cards are taken and what is being staked.

`seatedDeck()` (`PvpRoutes.kt:364`) shows the reasoning that got here: it tolerates a missing or
malformed body precisely because "the version gate will not stop it, because adding an optional
field is a minor and a minor is not a refusal". That is right about minors and says nothing about
majors, which is what the gate is for.

**What would fix it.** `if (!requireCompatibleClient()) return@post` at the top of the seven
handlers, before their `receive`. It costs one line each and is the rule the rest of the server
already keeps.

**Fixed.** All thirteen handlers under `/pvp` call `requireCompatibleClient()` as their first
statement, before `authenticate` and before any `receive`. The `GET`s too, for the reason
`AccountRoutes` gates `GET /me`: a client too old to be talked to should learn that from the first
thing it asks. `seatedDeck`'s KDoc — which reasoned about minors while the gate it named was not
being called at all — now says which of the two it was talking about.

The PvP tests already sent the version header, so this needed no test changes; the file's KDoc
carries the reasoning.

### 8 · Maven Central is asked for `com.tripletriad:core` before the repository that owns it — LOW

`settings.gradle.kts` filters `mavenLocal()` and the `tto-core` GitHub Packages repository to the
`com.tripletriad` group, and lists `mavenCentral()` **first with no filter at all**. Gradle
therefore asks Central for this artefact before it asks either of the two repositories that are
supposed to serve it. Confirmed by running the resolution:

```
https://repo.maven.apache.org/maven2            ← asked first
https://maven.pkg.github.com/korobetski/tto-core
```

`:core` is not an ordinary dependency. It is the rules engine the server replays matches with, which
means whatever serves it decides what a legal move is, what a pack contains and what a win pays.
An artefact resolved from somewhere unintended would not fail loudly — it would referee.

What stands between that and a supply-chain compromise today is that nobody has registered the
namespace: `https://repo1.maven.org/maven2/com/tripletriad/` answers 404. That is the whole
protection, and it is somebody else's decision.

Worth noting alongside: `CLAUDE.md` says "`settings.gradle.kts` puts `mavenLocal()` **first** for the
`com.tripletriad` group, on purpose". That is true of the two repositories it is comparing —
`mavenLocal()` is listed before the GitHub one, so a local install does shadow the published
artefact, and the documented way to test an unreleased engine change works. What the sentence does
not say, because it is not what it was written about, is that an unfiltered `mavenCentral()` sits in
front of both.

**What would fix it.** `exclusiveContent` on the two `com.tripletriad` repositories, which tells
Gradle that this group is served *only* from there and stops Central being consulted for it at all.
Adding Gradle dependency verification (`gradle/verification-metadata.xml`) is the larger version of
the same argument and would cover every other dependency too; there is no lockfile or checksum
verification anywhere today.

**Fixed.** `settings.gradle.kts` now declares `com.tripletriad` `exclusiveContent`, bound to
`mavenLocal()` and the `tto-core` GitHub Packages repository in that order, so no other repository
is asked for the group — including a `mavenCentral()` added later, which is the property a
`content` filter could not give.

Verified rather than assumed: with the local artifact moved aside, resolution now names only
`maven.pkg.github.com`, where before the change it reached `repo.maven.apache.org` first.

Gradle dependency verification is **not** added. It is the larger version of the same argument and
would cover every other dependency too, but a `verification-metadata.xml` is a generated file with
its own maintenance story and does not belong in a change about this.

### 9 · The rate limiter's honesty rests on an arrangement nothing enforces — LOW

`Observability.kt:143` installs `XForwardedHeaders` with the comment that trusting a client-supplied
header "is normally a spoofing vector, and here it is not one: Caddy … overwrites the header on
every proxied request".

Both halves were checked, because if the comment were wrong the per-IP `SIGN_IN` and `REGISTER`
buckets would be trivially defeated and finding 5's unauthenticated 500 would be unbounded.

- Ktor's `XForwardedHeadersConfig` has `init { useFirstProxy() }` — the default takes the **first**
  value of `X-Forwarded-For`, i.e. the one furthest from the server, i.e. the one a client can set.
  The plugin trusts any `X-Forwarded-For` from anywhere.
- Caddy's `addForwardedHeaders` retains a prior value only `if trusted && ok && prior != ""`, where
  `trusted` comes from `trusted_proxies` — and the field's own documentation says "By default, no
  proxies are trusted, so existing values will be ignored when setting these headers."

So the comment is **correct as deployed**, and there is no bypass to report. What there is instead
is a safety property that lives entirely outside the code that depends on it:

- **One line in the `Caddyfile` reverses it.** `trusted_proxies` exists to be set, and a deployment
  that sets it — or that puts anything in front of Caddy — starts appending the client's value in
  front of the real one. Ktor then reads the client's, because it reads the first.
- **The dependency is invisible from either end.** `Observability.kt` explains the arrangement and
  the `Caddyfile` does not mention it, so the file that would break it is the one file that does not
  say it is load-bearing. Nothing tests it either: no test asserts that a request arriving with a
  forged `X-Forwarded-For` lands in the bucket for its real address.
- `compose.prod.yaml` pins `caddy:2-alpine`, a floating major. The default-deny behaviour was
  checked back to 2.5.2 and is stable, so this is not the hazard it first looks like — but the
  property being relied on is still one a tag does not name.

**What would fix it.** Say it in the `Caddyfile`, next to `reverse_proxy`, where somebody about to
add `trusted_proxies` will read it: the application trusts the first `X-Forwarded-For` value, so
this proxy must remain the one that sets it. Configuring Ktor's plugin with `useLastProxy()` or
`skipKnownProxies` would make the server robust on its own rather than by arrangement, and is the
better answer the moment the topology grows a second hop.

**Fixed**, by making the server robust on its own rather than by arrangement.
`install(XForwardedHeaders) { useLastProxy() }` reads the entry the nearest hop appended instead of
the one furthest away, so a topology that ever forwards a client-supplied value degrades to "the
last proxy's word" rather than to "the caller's word". It is identical today, when the header holds
exactly one entry, and correct in the cases that would have broken the default. The `Caddyfile` says
next to `reverse_proxy` that `trusted_proxies` must stay empty and why, which is the file somebody
about to break this will be editing.

`caddy:2-alpine` is left floating. The default-deny behaviour was checked back to 2.5.2 and is not
a recent addition, so pinning a minor would buy little and cost security updates.

### 10 · An authenticated username oracle, next to a sign-in that carefully has none — LOW

`signIn` goes to real trouble to avoid confirming that an account exists: the same message for both
failures, and `verifyOrDecoy` burning a genuine bcrypt on a decoy digest so the timing matches too.
That care is undone one route over. `PvpReferee.challenge` (`PvpRoutes.kt:564`) answers
`Challenged.NoSuchPlayer` for a name nobody holds and something else for a name somebody does, which
is a clean existence check for any name the caller cares to try — bounded only by `LOBBY`, at twenty
a minute. `GET /pvp/tables` (`PvpRoutes.kt:118`) hands over host usernames directly.

This is not the same severity as leaking it unauthenticated, and a name in a card game is closer to
public than to secret — the lobby exists to show them. It is listed because the asymmetry is
probably not deliberate: someone spent real effort on the sign-in path and would want to know that
the property it buys is available elsewhere for the cost of registering an account.

**Resolved as a decision, not a change.** `PvpReferee.challenge` now carries the reasoning: a
username on this server is public by construction — `GET /pvp/tables` lists hosts by name, a match
view names your opponent, and typing a friend's name is the whole point of a directed challenge —
and what `signIn` protects is not the existence of a name but the pairing of a name with a password,
which nothing here touches. Inventing a vague refusal would cost a player the only useful answer to
a typo while protecting something the lobby publishes anyway. It stays bounded by `LOBBY`, from an
account the caller had to register.

### 11 · Smaller things

- **`alert.sh` prints the webhook URL on failure.** `scripts/alert.sh:110` — `echo "FAILED to reach
  $URL …"` on a transport error, into the journal. A Discord webhook URL *is* the credential: anyone
  holding it can post to that channel. Print the fact, not the URL.
- **`backups/` is created with the default umask.** `scripts/backup.sh` runs `mkdir -p "$OUT_DIR"`
  and writes dumps with no `chmod`. Those dumps hold every bcrypt digest and every session
  fingerprint in the database, unencrypted, and `compose.prod.yaml` bind-mounts the directory so
  they outlive the volume. `docs/operations.md` already says `.env` is mode 600; the dumps deserve
  the same sentence and a `chmod 700` on the directory.
- **`X-Request-Id` is accepted on the caller's word.** `Observability.kt:155` verifies only
  `isNotBlank()`. The value is echoed on the response and written into the MDC, so it is printed on
  every log line of that request — at whatever length and with whatever printable content the caller
  chose. Constrain it: a length cap and a character class.
- **`applied_operations` grows without bound.** `operation_id` is unconstrained `TEXT`
  (`V8__applied_operations.sql`) inside the primary key, so an id past a few kilobytes fails the
  btree index and surfaces as a 500 rather than a refusal, and nothing prunes the table — the
  `applied_operations_age_idx` on `applied_at` suggests a sweep that was never written. `V12` clears
  it wholesale, which is a migration, not a policy. A length check on receipt and a periodic delete
  of rows older than the idempotency window would close both.
- **No password change, and no way to end other sessions.** There is no endpoint to change a
  password (deliberately, per the "what is deliberately not here" note, which reasons about *reset*)
  and no "sign out everywhere". A player whose password leaks can only delete the account, and a
  stolen token is good for thirty days regardless.
- **`/health/ready` returns the driver's own message.** `HealthRoutes.kt:68` reports
  `failure.message` verbatim, which can carry a JDBC URL and a hostname. The `Caddyfile` refuses
  `/health/*` from outside, so this is defence in depth only — but the catch-all in
  `Observability.kt` deliberately says nothing about a cause for exactly this reason, and the two
  should agree.
- **Actions are pinned by tag.** `actions/checkout@v4`, `docker/build-push-action@v6` and the rest.
  `release.yml` pushes to `ghcr.io` and holds `VPS_SSH_KEY`, so a moved tag runs with the deployment
  key in reach. Pin to commit SHAs.

**All fixed.** In the order above:

- `scripts/alert.sh` prints `TTO_ALERT_URL` by name instead of by value.
- `scripts/backup.sh` sets `umask 077` before it creates anything and `chmod 700`s the directory —
  umask rather than a later `chmod`, because the shell creates the redirection target before
  `pg_dump` writes a byte and a file made world-readable for one second was still world-readable.
- The `X-Request-Id` verifier checks a character set and a 128-character cap. Worth noting what was
  actually there: `CallIdConfig` holds **one** verifier rather than a list, so the old
  `verify { it.isNotBlank() }` did not add a rule — it replaced Ktor's own dictionary check. A
  failing verifier is not a rejection; the plugin falls through to `generate`, which is the right
  answer for a header nobody was asked to send.
- `operationId` is capped at 128 characters and answered with a 400 rather than reaching a btree
  index that would refuse it as a 500, and `AccountStore.pruneOperations` forgets rows older than
  thirty days, called hourly from the sweep that already exists. Thirty days is a **floor**, not a
  target: forgetting a row un-guards the operation it recorded, so the window has to exceed the
  longest a client could hold an unacknowledged one, and a session is that long.
- `POST /accounts/me/password` changes a password and ends every **other** session in the same
  action — separately would mean the player had to know to do the second — and
  `DELETE /sessions/all` ends them all including the caller's. The password change asks for the
  current password, as account deletion does and for the same reason; signing out everywhere does
  not, because it is the safe direction and should be easy to reach when somebody is worried and
  not certain.
- `/health/ready` answers `"unavailable"` and leaves the driver's message in the warn line, which is
  what `Observability.kt`'s catch-all already did.
- Every `uses:` in both workflows names a commit, with the tag it was resolved from in a trailing
  comment so an update stays reviewable.


## What is already right

Recorded so it is not re-litigated, and because several of these are the reason findings above are
as small as they are.

- **No SQL injection anywhere.** All 61 statements are `prepareStatement` with bound parameters;
  there is no `createStatement`, and no interpolation into SQL text — checked across the whole
  source. `docker/postgres/init/10-app-role.sh` passes values to `psql` as variables rather than
  interpolating them, for the same reason and with the reasoning written down.
- **Tokens are stored and logged as fingerprints.** SHA-256 in `sessions.token_hash`, never the
  token; `LogSecrecyTest` pins it; the rate limiter keys on the fingerprint rather than putting the
  token in a map. A database dump is not an impersonation kit.
- **bcrypt at cost 12, with rehash-on-sign-in.** The cost travels inside the digest, so raising it
  needs no migration. `verify` returns `false` for a malformed digest rather than throwing — checked
  against the real library, and its KDoc is accurate on that point.
- **Sign-in leaks neither existence nor timing**, per finding 10's first paragraph.
- **Every by-id lookup is scoped to its owner in the query**, not checked afterwards.
  `PveStore.matchById` takes the account id as part of the `WHERE`; `PvpMatchRow.sideOf` gates every
  PvP action. Match ids are generated from a non-cryptographic `Random`, which would matter a great
  deal if they were capabilities — they are not, because of this.
- **Claims are counted with multiplicity.** `PvpMatchRow.isClaimable` (`PvpMatchRow.kt:284`) checks
  the count against what is owed and removes each id from a copy of the loser's dealt hand, so
  naming a card twice out of a hand holding one fails.
- **Concurrency is handled where it was thought about.** Optimistic move counts in
  `PveStore.appendMoves`, status-gated `finish` and `recordClaim`, `FOR UPDATE` on the seed ticket,
  the `ON CONFLICT` claim-then-fill in `applyOnce`, and the partial unique indexes on tables and live
  matches. Finding 1 is the exception, and it stands out against this.
- **The database role is not the superuser**, the reasoning is in the init script, and every table
  cascades from `accounts` so account deletion is one statement.
- **The deployed topology publishes nothing but Caddy**, deploys by digest with a readiness gate and
  an automatic rollback, and refuses development defaults outside development — `TTO_ENV` unset means
  production, which is the right way round.
- **No secret has ever been committed.** Checked across every commit for token-shaped strings and for
  additions of `.env`, key and credential files: `gradle.properties` has only Gradle settings, and
  `.env` is ignored.

## Related

- `operations.md` § What is not here yet — secrets management, and what a real host still lacks
- `deployment.md` § What is still missing on this host
- `data-inventory.md` — what the schema holds about a player, which is what finding 11's backup
  note is about
