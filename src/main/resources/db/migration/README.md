# Schema migrations

Flyway runs everything here, in version order, at start-up. See `Database.migrate` for why it runs
in-process and what has to change when there is more than one instance.

## Rules

- **Name files `V<n>__<description>.sql`** — two underscores, ascending `n`.
- **A migration that has been applied anywhere is immutable.** Flyway records a checksum; editing
  an applied file makes every deployment that already ran it refuse to start. Write a new
  migration instead.
- **Prefer additive changes.** A deploy is not atomic with its migration: for a moment the old
  code runs against the new schema. Adding a nullable column is safe; dropping one is a two-step
  change spread over two releases.
- **No `baselineOnMigrate`.** It is off in `Database.kt` on purpose — it would adopt an
  undescribed schema as version 1 on the one day that matters.

## Why this directory is empty

Deliberately. The Phase 5 design settles that profiles are server-held, but not yet what a profile,
an account or a transcript looks like. A schema written before those are defined would be a guess,
and the rule above says guesses become immutable the moment they run anywhere.

Flyway with no migrations is still doing something useful today: it creates `flyway_schema_history`
and, in doing so, proves at start-up that the database is reachable, the credentials work and the
role can write. `MigrationTest` covers exactly that.
