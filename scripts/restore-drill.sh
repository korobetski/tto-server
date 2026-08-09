#!/usr/bin/env sh
# Restores the newest dump into a throwaway Postgres and checks that it is a database.
#
#   ./scripts/restore-drill.sh [dump-file]
#
# ### Why this is the only one of the four that matters
#
# A schedule, a copy off the machine and a retention policy are all things you can have while every
# dump you hold is unreadable. Nothing detects that except restoring one. The failure is silent by
# construction: a backup is only ever consulted on the day it is the last copy, and that is a very
# poor moment to learn it was never a backup.
#
# So this restores for real — into a container that exists for a minute and is destroyed, on its own
# volume, with no network to production. It touches nothing the server uses. That is the whole
# design: a drill nobody is afraid to run is a drill that gets run.
#
# ### What it proves, and what it does not
#
# It proves the dump parses, that pg_restore replays it without error, and that the schema and rows
# arrive. It does not prove the *contents* are correct — a dump of a database that was already
# corrupted restores perfectly. Nothing automatic can close that gap; what closes it is noticing
# early, which is the argument for keeping fourteen of these rather than one.
set -eu

cd "$(dirname "$0")/.."

BACKUP_DIR="${BACKUP_DIR:-backups}"
# Pinned to the same major as compose.prod.yaml. A dump restores into a *newer* Postgres, so a drill
# on an older one would fail for a reason that says nothing about the dump.
IMAGE="${DRILL_IMAGE:-postgres:17-alpine}"
CONTAINER="tto-restore-drill-$$"
DRILL_PASSWORD="drill-$(date +%s)"

DUMP="${1:-}"
if [ -z "$DUMP" ]; then
    DUMP="$(ls -1t "$BACKUP_DIR"/*.dump 2>/dev/null | head -n 1 || true)"
fi
[ -n "$DUMP" ] || { echo "no dump found in $BACKUP_DIR" >&2; exit 1; }
[ -f "$DUMP" ] || { echo "no such dump: $DUMP" >&2; exit 1; }

echo "==> drilling $DUMP"
echo "    size: $(wc -c < "$DUMP") bytes"

# Removed however this script exits, including on an interrupt. A drill that leaves a container and
# a volume behind is one that stops being run after the third time.
cleanup() {
    docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# No published port and no compose network: nothing outside this script can reach it, and it cannot
# reach production. `--tmpfs` for the data directory, so the whole thing lives in RAM and leaves no
# volume to forget about.
docker run -d --name "$CONTAINER" \
    -e POSTGRES_PASSWORD="$DRILL_PASSWORD" \
    -e POSTGRES_DB=drill \
    --tmpfs /var/lib/postgresql/data:rw \
    "$IMAGE" > /dev/null

echo "==> waiting for the drill instance"
i=0
until docker exec "$CONTAINER" pg_isready -U postgres -d drill > /dev/null 2>&1; do
    i=$((i + 1))
    [ "$i" -lt 30 ] || { echo "FAILED: the drill instance never became ready" >&2; exit 1; }
    sleep 1
done

# The dump carries `GRANT ... TO tto_app`, and a role that does not exist makes pg_restore fail —
# which is what happened the first time this drill ran. There were two ways out and only one of
# them is worth having: `--no-privileges` would have skipped the grants and made the drill green by
# checking less. Creating the roles instead keeps the grants in the exercise, so a dump whose
# privileges are broken still fails here rather than during the restore it was kept for.
#
# `NOLOGIN`: these are placeholders for a name, not accounts. Nothing connects to this container.
APP_ROLE="tto_app"
OWNER_ROLE="tripletriad"
if [ -f .env ]; then
    APP_ROLE="$(sed -n 's/^DATABASE_USER=//p' .env | tail -n 1 || true)"
    OWNER_ROLE="$(sed -n 's/^POSTGRES_USER=//p' .env | tail -n 1 || true)"
    : "${APP_ROLE:=tto_app}"
    : "${OWNER_ROLE:=tripletriad}"
fi

echo "==> creating the roles the dump names: $OWNER_ROLE, $APP_ROLE"
for role in "$OWNER_ROLE" "$APP_ROLE"; do
    docker exec "$CONTAINER" psql -U postgres -d drill -qc \
        "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$role') THEN CREATE ROLE \"$role\" NOLOGIN; END IF; END \$\$" \
        > /dev/null
done

echo "==> restoring"
# `--no-owner` so objects land owned by the drill superuser rather than by a role this instance only
# has as a placeholder. Errors are NOT ignored: `--exit-on-error` is what turns "restored with 400
# warnings" into a failure, which is the only reading of it worth anything.
if ! docker exec -i "$CONTAINER" \
        pg_restore --username=postgres --dbname=drill --no-owner --exit-on-error < "$DUMP"; then
    echo "FAILED: $DUMP did not restore cleanly" >&2
    exit 1
fi

echo "==> checking what arrived"
# Two questions, in order of what they would catch. First: are there tables at all — a dump taken
# against the wrong database, or before the migrations ran, restores successfully and empties.
TABLES="$(docker exec "$CONTAINER" psql -U postgres -d drill -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")"
echo "    tables in public: $TABLES"
[ "$TABLES" -gt 0 ] || { echo "FAILED: the restored database has no tables" >&2; exit 1; }

# Second: does Flyway's own history say the schema is the one this server expects? It is the one
# table whose absence means the dump predates the schema entirely.
if docker exec "$CONTAINER" psql -U postgres -d drill -tAc \
        "SELECT to_regclass('public.flyway_schema_history')" | grep -q flyway_schema_history; then
    VERSION="$(docker exec "$CONTAINER" psql -U postgres -d drill -tAc \
        "SELECT coalesce(max(version), '(none)') FROM public.flyway_schema_history WHERE success")"
    echo "    schema version: $VERSION"
else
    echo "    schema version: no flyway_schema_history - the dump predates the schema" >&2
fi

# A row count per table, so that a dump which restores an empty schema is visibly different from one
# that restores a game. Printed rather than asserted: what "enough rows" means is a judgement, and a
# threshold invented here would fail on the day the server is new and legitimately almost empty.
docker exec "$CONTAINER" psql -U postgres -d drill -c \
    "SELECT relname AS table, n_live_tup AS rows FROM pg_stat_user_tables ORDER BY n_live_tup DESC, relname"

echo "==> drill passed: $DUMP restores into a working database"
