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
| `DATABASE_USER` | `tripletriad` | |
| `DATABASE_PASSWORD` | `tripletriad` | |
| `DATABASE_POOL_SIZE` | `10` | raise in response to a measurement, not a worry |

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

**This is the part that matters, and it is the least finished.**

Decision 2 of the Phase 5 design makes player progression server-held. That means losing this
database is not an incident, it is the end of the game — the same way the original Triple Triad
Online ended.

What exists:

```
./scripts/backup.sh              # writes backups/<db>-<utc-timestamp>.dump
./scripts/restore.sh <dump>      # DESTROYS the target and restores
```

`backup.sh` takes a `pg_dump` custom-format dump and verifies it can be listed before declaring
success, so a truncated write is caught now instead of during a restore.

What does **not** exist, and must before anything real is stored:

- **A schedule.** A backup that runs when somebody remembers is not a backup.
- **Somewhere else.** The dumps land next to the database, on the same disk, which protects against
  exactly none of the failures that destroy a disk.
- **Retention.** Nothing is ever deleted, so the useful history is eventually buried and the disk
  eventually fills.
- **A tested restore, on a schedule.** This is the only one that actually decides whether the
  backups are real. `restore.sh` exists so that the test is a command and not a project.

Managed backups from a database provider replace the first three. They do not replace the fourth.

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

- **TLS.** Nothing terminates HTTPS. On a host that is a reverse proxy's job, not this process's.
- **Secrets.** `.env` is a file with a password in it. Adequate for a laptop, not for a host.
- **Authentication.** No accounts, no keys, no sessions — the design exists, the code does not.
- **A registry.** CI builds an image and throws it away; there is nowhere to push it.
- **`:core`.** The whole point of a JVM server is that it replays matches with the *real* engine.
  Until the client's `:core` is extracted and consumable, this server cannot verify anything.
- **More than one instance.** Several decisions here are single-instance decisions and say so.

---

## Related

- `../../AS3-Triple-Triad/docs/migration/09-PHASE-5-NETWORK.md` — the design this serves
