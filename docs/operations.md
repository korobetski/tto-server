# Operations

What this server needs to be run, watched and repaired. Written while it does almost nothing, on
the theory that the time to decide how a thing is operated is before it holds anything you cannot
lose.

---

## Running it

```
cp .env.sample .env
docker compose up -d --build
docker compose logs -f server
```

| Endpoint | Answers |
|---|---|
| `GET /health/live` | is the process working — **never touches the database** |
| `GET /health/ready` | can it serve a request now — checks the database |
| `GET /metrics` | Prometheus exposition format |

The split between the two health endpoints is the one piece of this that is easy to get wrong and
expensive to fix later. An orchestrator uses liveness to decide whether to **kill** the process and
readiness to decide whether to **route traffic** to it. If liveness reported the database, then a
database that blinks makes every instance look dead, they are all restarted, and the restart does
not bring the database back — it just loses whatever was in flight. So liveness answers for the
process alone.

### Running the server outside the container

```
TTO_ENV=development ./gradlew run
```

`TTO_ENV` is required. Anything other than `development`/`dev`/`local` — including **unset** — is
treated as production, and production refuses to fall back to the development database defaults.
That is deliberate: the alternative is a host where somebody forgot the variable running happily
against a `localhost` that is not there.

---

## Configuration

Every setting is an environment variable, read once at start-up in `ServerConfig`. A missing value
outside development stops the process with exit code 78 rather than starting a half-configured
server.

| Variable | Default (development only) | Notes |
|---|---|---|
| `TTO_ENV` | — | `development` enables defaults. Anything else, including unset, does not |
| `TTO_HOST` | `0.0.0.0` | inside a container this must stay `0.0.0.0` |
| `TTO_PORT` | `8080` | |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tripletriad` | |
| `DATABASE_USER` | `tripletriad` | compose supplies `tto_app`, an unprivileged role — see below |
| `DATABASE_PASSWORD` | `tripletriad` | the code default no longer matches any real database |
| `DATABASE_POOL_SIZE` | `10` | raise in response to a measurement, not a worry |
| `BREVO_API_KEY` | — | **secret.** Required outside development; see below |
| `MAIL_FROM` | `no-reply@localhost` | the envelope sender, and a sender Brevo has verified |
| `MAIL_SENDER_NAME` | `Triple Triad` | what the recipient sees in the From line |
| `TTO_ADMIN_USERNAME` | — | the first administrator's name. Unset on every boot after the first — see below |
| `TTO_ADMIN_PASSWORD` | — | **secret.** Their password, at least 12 characters. Remove both after the first sign-in |
| `TTO_UNLOCK_MULTIPLAYER` | `5` | the level refereed play opens at |
| `TTO_UNLOCK_AUCTION` | `5` | the level the auction house opens at |
| `TTO_BOTS_ENABLED` | `false` | **off everywhere until set.** Whether this server plays accounts of its own — see below |
| `TTO_BOTS_COUNT` | `10` | how many |
| `TTO_BOTS_BAND` | `EXPERT` | how hard they play. An `NpcLevel` name; an unknown one falls back to `EXPERT` |
| `TTO_BOTS_FORMAT` | `ff14-standard` | which format they grind and shop in |
| `TTO_BOTS_NAME_PREFIX` | `Duelist` | what they are called, before four random digits |
| `TTO_BOTS_WAGER` | `false` | whether they may sit down at a table that stakes MGP or cards |
| `TTO_BOTS_RESERVE` | `5000` | MGP a bot keeps out of the shop. Idle while wagering is off |
| `TTO_BOTS_TABLE_WAIT_SECONDS` | `45` | how long a table stands unanswered before a bot takes it |
| `TTO_BOTS_MOVE_MIN_SECONDS` | `3` | the fastest a bot places a card |
| `TTO_BOTS_MOVE_SPREAD_SECONDS` | `6` | added to the above, drawn per move |
| `TTO_BOTS_IDLE_SECONDS` | `20` | how long a bot with nothing to do waits |
| `TTO_BOTS_TICK_SECONDS` | `2` | how often the director looks at all |

### Mail, and why the server refuses to start without a provider

Confirmation codes and password resets go out through Brevo's transactional API. Without
`BREVO_API_KEY` the server falls back to a mailer that **writes each code to the log** — which is
how a developer finishes a registration on a laptop with no inbox, and which would be a standing
way into every account on a real host. So `MailConfig.from` refuses to boot outside development
when the key is absent, the same way the database defaults are refused, and for the same reason:
the failure it prevents is silent in both directions. A player who never gets a mail waits; an
operator whose log holds codes does not find out.

Sending through a provider rather than from this host is not a convenience. A VPS sits in a
hosting provider's address space, outbound port 25 is restricted at OVH, and mail that does leave
such a range lands in spam often enough that a password-reset mail in a spam folder means a
locked-out player. Port 443 is never the port anybody blocks.

`MAIL_FROM` wants to be a subdomain carrying no other traffic — `mail.example.com` — so this
mail's sending reputation stands on its own, and it has to be a sender Brevo has verified or
every send is refused. SPF, DKIM and DMARC records for that subdomain are a DNS task on the
operator's side; Brevo's console states the exact records.

### The unlock thresholds are configuration, not a release

`TTO_UNLOCK_MULTIPLAYER` and `TTO_UNLOCK_AUCTION` are levels, and the server enforces them on
every endpoint that starts refereed play. The **rule** is in `:core` so the client and the server
cannot disagree about what a threshold means; the **numbers** are these, and they are sent to
clients on `GET /server`. Raising one is this variable and a restart — a client that has not been
updated asks the same question and gets the new answer.

An unparseable value falls back to `:core`'s default rather than stopping the boot, which is the
opposite judgement from `DATABASE_URL` and deliberately so: a wrong database is a server that
cannot work, a wrong threshold costs a door being open too early.

### The bots, and the four things to decide before turning them on

`TTO_BOTS_ENABLED` makes this server play accounts of its own: ordinary `accounts` rows with
ordinary profiles, driven in-process by `BotDirector`, which opens matches through the same
referees a client reaches over HTTP. See `BotDirector` for the design and `V16__bots.sql` for the
one table it adds.

What a bot actually does, between matches and in them:

- **Plays both modes.** Solo matches against the roster, and a lobby table nobody else took. Every
  placement is `MatchSearch` at the band its row names, given only the visibility the rules grant.
- **Takes what it wins.** An opened pack and an opponent's drops arrive as *bag items*, not as
  cards; `BotBrain.emptying` uses them, which is also how the XP and MGP potions get spent — a boon
  is a count of boosted matches, so using one is what makes the next win pay more.
- **Buys packs** with what it earns, keeping `TTO_BOTS_RESERVE` back.
- **Sells its surplus commons**, which is about the Random rule as much as the money: the collection
  is drawn from *one entry per copy*, so a fourth copy of a one-star is a fourth ticket in a draw
  the bot does not want to win. It never sells a copy a deck is built on, and never above two stars.
- **Keeps three decks and chooses between them** (`BotDecks`): the strongest legal five, the five
  most concentrated in one card type, and the five spread over the most types. A table states its
  rules and an opponent declares theirs, so the choice is made from public terms — the concentrated
  hand under **Ascension**, where every card of a type on the board raises every card of that type;
  the spread one under **Descension**, which is the same tally punishing what Ascension rewards.
  Elemental gets the strongest hand: its modifier belongs to the cell, drawn when the match is
  dealt, so there is nothing to prepare against.

It does **not** read the opponent's cards to counter-pick, though `npcs.json` would let it. That is
a different game from the one a person is playing, and these accounts exist to measure the one that
is played.

**It is off by default and stays off on upgrade.** A server that populates its own lobby is a
different product from one that does not, and nobody should get it by deploying a new tag.

Four things are worth deciding deliberately rather than inheriting:

1. **Nothing marks a bot on the wire.** No field on `PvpTable`, none on `PvpMatchView`; the only
   place the distinction exists is the `bots` table and the metrics. So `TTO_BOTS_NAME_PREFIX` is
   the whole of what a player can read. The default is neutral. Set it to something obvious if this
   deployment would rather be plain about it.
2. **A fresh roster cannot fill the lobby yet.** A bot starts at level 1 and `TTO_UNLOCK_MULTIPLAYER`
   is 5, and the gate is checked for a bot exactly as it is for a person. So the first hours after
   enabling this are bots playing solo matches; the lobby fills once they have climbed. That is the
   feature working, not a fault — but it means turning this on the evening you need a busy lobby
   does not produce one.
3. **`TTO_BOTS_WAGER` moves real value.** With it off a bot only sits down at a table that risks
   nothing at all — no MGP and no trade rule. With it on, a card won from a bot is a card the world
   gained and one lost to a bot is a card that left it. The economy is the reason to hold this until
   the metrics below say what it would cost.
4. **`TTO_BOTS_COUNT` is CPU.** Every placement at `EXPERT` is a depth-five alpha-beta search with a
   node budget of 400 000, on the process that serves requests. Ten bots at one placement every
   three to nine seconds is small; a hundred has not been measured.

### What the bots are for, and how to read them

Three uses, in the order they pay off:

- **A lobby that answers.** `TTO_BOTS_TABLE_WAIT_SECONDS` is the whole of the policy: below it a
  table belongs to whoever is reading the lobby, above it to a bot.
- **A progression curve nobody had to play.** The gauges below are sampled by Prometheus, so *rate*
  over them is the answer to "how fast does an account actually climb" — the question the reward
  tables were tuned against a guess for.
- **A balance experiment.** The band is a column on `bots`, not a constant: `UPDATE bots SET band =
  'ADVANCED' WHERE ...` splits the roster, and the same gauges then read per band.

| Gauge | Tag | What it says |
|---|---|---|
| `tto_bots_count` | — | how many accounts this server plays |
| `tto_bots_level` | `band` | mean level |
| `tto_bots_mgp` | `band` | mean purse |
| `tto_bots_collection` | `band` | mean distinct cards owned |
| `tto_bots_matches` | `band` | mean matches played |
| `tto_bots_wins` | `band` | mean matches won |

They are read from the bots' own profiles — one query per scrape over `bots` joined to
`characters`, bounded by the roster rather than by how long the deployment has been running. The
`matches` table remains the better source for anything asked once: what a particular opponent pays,
how a band's win rate moved across a release.

**Bot matches are in `matches` alongside everybody else's.** Any query about players has to exclude
them — `WHERE NOT EXISTS (SELECT 1 FROM bots b WHERE b.account_id = matches.account_id)` — and any
that does not is measuring the server playing itself.

### Changing a value on the deployed host

Every setting above lives in one file on the VPS, `/srv/tto/.env`, and it is read **once at
start-up** — so an edit does nothing until the container is recreated. Edit it in place, on the
host, over SSH:

```bash
ssh deploy@tto.example.com
cd /srv/tto
cp .env .env.bak          # restoring is `mv .env.bak .env`, which matters at 2am
nano .env
docker compose -f compose.prod.yaml up -d server
```

Four things about that sequence are not incidental:

* **`up -d server`, not `restart`.** `docker compose restart` restarts the process with the
  environment the container was *created* with — the edit is read, the file is right, and nothing
  changes. `up -d` recreates the container, which is the only thing that re-reads `.env`. This is
  the single commonest way an environment change appears not to work.
* **Only `server`.** Naming it leaves Postgres and Caddy untouched, so a mail key change costs a
  second of API downtime rather than a database restart and a certificate reload.
* **Never `TTO_IMAGE` by hand.** `scripts/deploy.sh` owns that line; editing it is how the file
  and the running container stop agreeing about what this host runs.
* **The file's mode is part of it.** `chmod 600`, owned by `deploy`. If you ever recreate it from
  the sample, set that again — `scp` does not preserve it.

Two variables ignore all of this, and it is worth knowing which before an evening is spent on
them: `POSTGRES_PASSWORD` and `DATABASE_PASSWORD` are read by the Postgres image **only when the
data directory is empty**. On a host that has ever started, changing them in `.env` changes
nothing at all, and `.env.sample` carries the `ALTER ROLE` that actually does it.

Check the change took, rather than assuming:

```bash
docker compose -f compose.prod.yaml logs --tail 50 server
curl -s https://tto.example.com/server
```

`GET /server` is the honest confirmation for the two unlock thresholds, because it answers with
what the process actually parsed. There is no such route for `BREVO_API_KEY` on purpose — the way
to confirm a mail key is to ask for a code and watch for the mail, not to have the server read a
credential back to you.

### The proxy is reloaded by the deployment, not by `up -d`

`/srv/tto/Caddyfile` arrives with every release — the tarball in `.github/workflows/release.yml`
carries it — and it is a **bind mount**. Its contents are not part of the `caddy` service's
specification, so `docker compose up -d` finds nothing to change and leaves the container running
the configuration it was started with. That was invisible for as long as the file never changed;
from the moment it does, the file on the host and the routing actually in force stop agreeing, and
nothing anywhere says so.

`scripts/deploy.sh` closes that with a reload after the readiness gate. The one case it does not
cover is an edit made on the host, which needs the same command by hand:

```bash
cd /srv/tto
docker compose -f compose.prod.yaml exec caddy caddy reload --config /etc/caddy/Caddyfile
```

`reload` and not `restart`, and the difference is the whole point. Caddy parses and validates the
new configuration first and swaps it in only if it is good, so a typo leaves the previous one
serving and costs nobody a connection; a restart drops every connection first and discovers the typo
afterwards. It is also why `deploy.sh` exits **3** instead of rolling the release back when the
reload fails: a configuration Caddy refused is one it never loaded, so the site is stale rather than
down, and rolling a healthy image back would be the more disruptive of the two.

To read what it is running rather than what the file claims:

```bash
docker compose -f compose.prod.yaml exec caddy wget -qO- localhost:2019/config/
```

None of this applies to the portal's **content**. `/srv/tto/web` is read from disk on every request,
so a portal deployment lands the moment its symlink moves and Caddy is never told anything.

### The server does not connect as the superuser

Two roles, and the distinction is deliberate. `POSTGRES_USER` (`tripletriad`) owns the cluster and is
the account for psql, `scripts/backup.sh` and `scripts/restore.sh`. `DATABASE_USER` (`tto_app`) is
what the **server** authenticates as: it may connect, and create and use objects in `public`, and
nothing else — it cannot create roles, read `pg_shadow`, or drop the database. A leaked
`DATABASE_PASSWORD` therefore costs the application's own tables rather than the cluster, which
given that progression is server-held is the difference between a bad day and an unrecoverable one.

`tto_app` is created on first boot by `docker/postgres/init/10-app-role.sh`, from the values in
`.env`. That directory runs **only on an empty data directory**, so an existing volume needs the
role created by hand — `.env.sample` carries the exact commands, including the one that is easy to
miss: handing every object in `public` over to the new owner, without which the server refuses to
start with `permission denied for table flyway_schema_history` and exits 70.

The reflex on seeing that message is to cede the one table it names. Don't: a volume that ran with
the server as superuser has `accounts`, `pvp_tables` and the rest owned the same way, so ceding
`flyway_schema_history` alone moves the error to the next table and reads like a second, unrelated
problem. `.env.sample`'s block walks `pg_tables` and `pg_sequences` for that reason.

The two development defaults in the table above are now a `./gradlew run` fallback and nothing
else. They name a role whose password is machine-specific, so that path needs `DATABASE_USER` and
`DATABASE_PASSWORD` set explicitly from `.env`.

### The figures live in a schema of their own

`V18__stats_views.sql` puts the numbers the console's dashboard shows — and the ones a Grafana
would show — into a `stats` schema: `stats.accounts`, `stats.match_events`, `stats.matches`,
`stats.economy`, and `stats.overview`, which is all of them as one row with the instant it was read
at. Reading them is one query:

```bash
docker compose -f compose.prod.yaml exec postgres \
  psql -U tripletriad -d tripletriad -x -c "SELECT * FROM stats.overview"
```

The definitions are in the migration, at length, because three of them have more than one
defensible answer: which of three tables "a match" means, whether the accounts the server plays
itself are players, and whether MGP sitting in an auction escrow still exists. That is why the
predicate two sections above is not repeated by hand anywhere — every view applies it.

**⚠️ The schema is a bootstrap step, not a migration step.** `tto_app` can create objects inside
`public` and cannot create a schema, which needs `CREATE` on the database — and widening that would
undo the separation the previous section is about. On a fresh volume
`docker/postgres/init/20-stats-role.sh` creates `stats` and hands it to `tto_app`; on a volume that
already exists **nothing does**, and the first deployment carrying `V18` fails its migration and
exits 70. One command, before that deployment:

```bash
docker compose -f compose.prod.yaml exec postgres \
  psql -U tripletriad -d tripletriad -v ON_ERROR_STOP=1 \
  -c "CREATE SCHEMA IF NOT EXISTS stats AUTHORIZATION tto_app"
```

**`tto_stats` is optional and exists for readers that are not the server.** It may `SELECT` those
views and reach nothing else — not `accounts`, not `sessions`, not an address — so a Grafana
reached over an SSH tunnel, or a notebook, can be given a connection that cannot leak anything a
privacy notice has to mention. `STATS_DB_PASSWORD` in `.env` creates it on a fresh volume;
`.env.prod.sample` carries the four statements that create it on a volume that already exists. A
deployment nobody graphs does not need it: the server reads the views as itself.

### Getting into the administration console the first time

The console has no "sign up", by design: a route that creates an administrator is either
unauthenticated — a console anybody on the internet can enrol into — or authenticated, which is the
chicken and the egg. So the first administrator is created from the environment, once:

```bash
# In .env.prod, for one deployment only
TTO_ADMIN_USERNAME=ada
TTO_ADMIN_PASSWORD=<from a password manager, 12 characters or more>
```

Then `docker compose -f compose.prod.yaml up -d`, sign in at the console's own hostname —
`{$TTO_ADMIN_DOMAIN}` in the `Caddyfile`, which is the only host from which `/admin/*` is reachable
at all — and **remove both lines again**. The server logs that an administrator was created and does not log which — the
name is half of a credential for the one surface that can move balances.

Leaving them set is inert rather than dangerous: `ensureFirstAdministrator` never touches an
administrator who already exists — not the password, not the second factor, not `disabled_at` — so a
redeploy does not reset a password and a retired administrator does not come back. It is still worth
removing them, because a password in an environment file is a password in a backup of that file.

**The first sign-in enrols the second factor.** Password alone the first time; the server answers with
a TOTP secret and an `otpauth://` URI, the browser shows it once as a QR code, and the same form is
submitted again with the first six digits from the authenticator. Nothing is signed in until that
second submission. From then on the secret cannot be replaced by anybody holding the password —
`totp_enrolled_at IS NULL` in the `UPDATE`'s `WHERE` clause is what closes that window, permanently.

It follows that the window between creating an administrator and their first sign-in is one where the
password alone chooses the authenticator. Sign in immediately after the boot that created the
account. The alternative — a secret generated at start-up — would have to be printed, logged or left
in a container's environment, which is the failure this flow exists to avoid.

### A lost authenticator, and a second administrator

Neither has a screen, and both are one command. **Create a second administrator** the same way the
first one was: set the two variables, restart, sign in, remove them. Two people who can reach the
console is not redundancy for its own sake — an administrator locked out by a lost phone is otherwise
a database query.

**Clear a lost second factor** so the next sign-in enrols a new one:

```bash
docker compose -f compose.prod.yaml exec postgres   psql -U tripletriad -d tripletriad -v ON_ERROR_STOP=1   -c "UPDATE admins SET totp_secret = NULL, totp_enrolled_at = NULL, totp_last_step = NULL
      WHERE username_key = lower('ada')"
```

That reopens the bootstrap window for that administrator, so it is the same rule as above: they sign
in straight away. Anybody who can run it already holds the database, which is why it is deliberately
this and not a route.

**Withdraw access** by disabling rather than deleting — `admin_audit` names the administrator who did
each thing, and rows about them outlive their access on purpose:

```bash
docker compose -f compose.prod.yaml exec postgres   psql -U tripletriad -d tripletriad -v ON_ERROR_STOP=1   -c "UPDATE admins SET disabled_at = now() WHERE username_key = lower('ada')"
```

It takes effect on the **next request**, not at the next sign-in: the query that validates a console
cookie joins `admins` and requires `disabled_at IS NULL`, so a session already open stops working.

**What an administrator's session is worth:** twelve hours absolute, thirty minutes idle, in a
`__Host-` cookie no script can read. Two clocks because the threat is an unattended screen rather
than a stolen cookie, and neither is renewable — a console left open on a desk signs itself out.

### Exit codes

| Code | Meaning |
|---|---|
| 78 | misconfigured — a required variable is absent |
| 70 | the schema could not be brought up to date |
| 69 | the database could not be reached |

Distinct on purpose: a supervisor's log should say which of the three happened without anyone
opening a stack trace.

---

## The schema

Flyway runs at start-up, in-process, from `src/main/resources/db/migration`. See that directory's
README for the rules, and `Database.kt` for why in-process — and for what must change the day
there is a second instance.

`db/migration` is empty today. Flyway still earns its place: it creates its history table, and in
doing so proves at start-up that the database is reachable, the credentials work and the role can
write.

---

## Backups

**This is the part that matters.**

Decision 2 of the Phase 5 design makes player progression server-held. That means losing this
database is not an incident, it is the end of the game — the same way the original Triple Triad
Online ended.

### Two mechanisms, doing different jobs

**OVH's automated VPS backup** images the whole machine daily at **12:41 UTC**. It is taken hot, so
for Postgres it is a power cut — which Postgres survives by design, that being what the WAL is for.
It is a genuine safety net and, crucially, it is *off this machine*, which is the hard part.

What it cannot do is restore a *database*. It restores a VPS, to one moment, whole. No extracting a
single table, no standing a copy beside production to check it, no going back two days if the
retention holds one image.

**The logical dumps** are the other shape. `pg_dump` custom-format, restorable into a different
Postgres version on a different machine, small enough to keep many of, and readable without touching
anything.

The two are wired together on purpose: the dump runs at **12:10 UTC**, half an hour ahead of OVH's
window, into `/srv/tto/backups` — a directory the VPS image includes. **OVH provides the transport
off the machine; the dumps make what OVH carries restorable in a useful way.** If OVH's window
moves, `deploy/systemd/tto-backup.timer` moves with it; that coupling is written in the unit rather
than left as a coincidence for somebody to rediscover.

### The commands

```
./scripts/backup.sh              # writes backups/<db>-<utc-timestamp>.dump, verified, then prunes
./scripts/restore-drill.sh       # restores the newest dump into a throwaway container and checks it
./scripts/restore.sh <dump>      # DESTROYS the target and restores. The real one.
```

All three work in both places: a developer's checkout drives `compose.yaml`, the deployed host —
which has no `compose.yaml` — drives `compose.prod.yaml`. That is tested by the presence of the
*development* file, since the checkout has both.

`backup.sh` writes under a `.partial` name and moves it into place only after `pg_restore --list`
has read it back, so a failed run leaves nothing rather than a zero-byte file that would count
against retention and be picked up as "the newest dump". Retention keeps the newest `BACKUP_KEEP`
(14) by **count and not by age** — a rule expressed in days deletes the last surviving copy on a
host whose backups have been failing for a fortnight, which is the one moment it must not.

### The schedule

Two systemd timers, in `deploy/systemd/`. Delivered by every release so the host has the current
copy; installed once by hand, because a release that could rewrite what runs as root on a schedule
is a release that could be made to.

```
sudo install -m 644 /srv/tto/deploy/systemd/*.service /srv/tto/deploy/systemd/*.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now tto-backup.timer tto-restore-drill.timer
systemctl list-timers 'tto-*'
```

| Unit | When | What |
|---|---|---|
| `tto-backup.timer` | daily 12:10 UTC | one dump, verified, then prune |
| `tto-restore-drill.timer` | Monday 06:00 UTC | restore the newest dump into a throwaway Postgres |

Both are `Persistent=true`, so a run missed while the host was down fires at boot rather than
silently not happening.

### The drill is the one that decides whether any of this is real

A schedule, a copy off the machine and a retention policy can all be satisfied while every dump held
is unreadable. Nothing detects that except restoring one, and the failure is silent by construction:
a backup is only ever consulted on the day it is the last copy.

`restore-drill.sh` restores into a container that exists for a minute — its own tmpfs, no network to
production, destroyed on exit however the script ends. It creates the roles the dump names rather
than skipping the grants with `--no-privileges`, so a dump whose privileges are broken still fails
here instead of during the restore it was kept for. It asserts the schema is not empty and prints a
row count per table; it deliberately does not assert a row *threshold*, which would fail on the day
the server is legitimately new.

What it cannot prove is that the contents are correct — a dump of an already-corrupted database
restores perfectly. Nothing automatic closes that gap. What closes it is noticing early, which is
the argument for holding fourteen dumps rather than one.

### Reading the history

```
journalctl -u tto-backup --since '7 days ago'
journalctl -u tto-restore-drill --since '30 days ago'
systemctl list-timers 'tto-*'
```

### When one of them fails

Both units carry `OnFailure=tto-alert@%n.service`, which runs `scripts/alert.sh` with the failed
unit's name. It POSTs the last twelve journal lines to the Discord webhook in `TTO_ALERT_URL`, read
from `/srv/tto/.env`. That URL is itself the credential — anyone holding it can post to the channel
— so it belongs in that file and nowhere else.

Three properties are deliberate. The alert writes to the journal *before* attempting to send, so
the record survives the notification service being down — the day you would most want both. The
**HTTP status** is checked rather than curl's exit code, because curl exits 0 on a 4xx: an early
version reported "notification sent" over a refusal, which is this mechanism's own failure mode
appearing at its last link. And `alert.sh` always exits 0: a notifier that fails is a notifier that
shows up in `systemctl --failed` having told nobody, and a notifier with its own `OnFailure` is a
loop.

Test it without breaking anything:

```
sudo systemctl start tto-alert@tto-backup.service
```

**What this is not is a dead man's switch.** A host that is off sends nothing, and its silence is
indistinguishable from a quiet week. This tells you a job failed; it cannot tell you the machine
died, and nothing running on the machine can. Closing that needs something outside it that expects
a ping and complains when none arrives — which is the same missing piece as `/metrics` being served
and unscraped.

---

## Observability

- **Logs** go to stdout only. In a container the platform already collects, timestamps, rotates and
  ships that stream; a file appender inside the container writes into a layer that dies with it.
- **Correlation ids**: every request carries `X-Request-Id`, generated if the caller did not supply
  one, and printed on every log line via the MDC. One grep recovers a request's whole story.
- **Metrics**: `/metrics` in Prometheus format, JVM and HTTP — plus the `tto_bots_*` gauges when
  `TTO_BOTS_ENABLED` is set, which are the progression measurement described under Configuration.

Deliberately absent: a Prometheus and a Grafana in `compose.yaml`. Two containers nobody looks at
are not observability, and the endpoint is there for the day something scrapes it.

`/metrics` is **not authenticated**. It is bound to loopback in development and must not be exposed
publicly on a host — it leaks route names, latencies and traffic volume.

---

## What is not here yet

Named rather than implied, so none of it is discovered at the wrong moment:

- **Secrets management.** `.env` is still a file with passwords in it — on the VPS it is mode 600 and
  owned by the deploy user, which is adequate for one host and not a secrets store. Nothing rotates
  them, and nothing would notice if a copy leaked.
- **More than one instance.** Several decisions here are single-instance decisions and say so.
- **Anything watching the deployed host.** `/metrics` is served and nothing scrapes it; see
  `deployment.md` for the rest of what a real host is still missing.

Resolved since this list was written, and kept here so the change is visible rather than silently
edited away: TLS is Caddy's, in `compose.prod.yaml`; the registry is `ghcr.io`, and CI pushes to it
on a tag; `:core` is published from the `tto-core` repository and consumed as an artifact.

---

## Related

- `deployment.md` — provisioning the VPS, and how a tag becomes the running server
- `security-review.md` — a read of the whole repository for the ways something could be taken or
  taken down, with what is already right recorded next to what is not
- `web-platform.md` — why Caddy serves static files at all, and what `/srv/tto/web` is for
- `../../AS3-Triple-Triad/docs/migration/09-PHASE-5-NETWORK.md` — the design this serves
