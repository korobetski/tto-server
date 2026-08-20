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
- **Metrics**: `/metrics` in Prometheus format, JVM and HTTP.

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
- `../../AS3-Triple-Triad/docs/migration/09-PHASE-5-NETWORK.md` — the design this serves
