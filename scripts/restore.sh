#!/usr/bin/env sh
# Restores a dump produced by backup.sh into the development database.
#
#   ./scripts/restore.sh backups/tripletriad-20260807T101500Z.dump
#
# ### Read this before running it anywhere but a laptop
#
# This DESTROYS the current contents of the target database. It is written for the development
# stack, where that is the point: the fastest way to find out whether a backup is real is to
# restore it and see whether the server still starts.
#
# That is also the reason this script exists at all. An untested backup is a belief, not a backup —
# and the failure mode is silent until the day it is the only copy left.
set -eu

DUMP="${1:?usage: restore.sh <dump-file>}"

# Resolved before the cd below, so a relative path means what the caller meant.
case "$DUMP" in
    /*) ;;
    *) DUMP="$PWD/$DUMP" ;;
esac

cd "$(dirname "$0")/.."

DB_NAME="${POSTGRES_DB:-tripletriad}"
DB_USER="${POSTGRES_USER:-tripletriad}"

# Same test as backup.sh, and the same polarity: a developer's checkout has both compose files, the
# deployed host only the production one — so the presence of the *development* file is what
# distinguishes them. It matters more here than there: this is the script somebody runs under
# pressure, and `no configuration file provided` is not a message anyone wants to debug that evening.
if [ -f compose.yaml ]; then
    COMPOSE="docker compose"
else
    COMPOSE="docker compose -f compose.prod.yaml"
fi

if [ -f .env ]; then
    DB_NAME="$(sed -n 's/^POSTGRES_DB=//p' .env | tail -n 1 || true)"
    DB_USER="$(sed -n 's/^POSTGRES_USER=//p' .env | tail -n 1 || true)"
    APP_USER="$(sed -n 's/^DATABASE_USER=//p' .env | tail -n 1 || true)"
    : "${DB_NAME:=tripletriad}"
    : "${DB_USER:=tripletriad}"
fi
: "${APP_USER:=tto_app}"

[ -f "$DUMP" ] || { echo "no such dump: $DUMP" >&2; exit 1; }

printf 'This will REPLACE the contents of "%s". Type the database name to confirm: ' "$DB_NAME"
read -r CONFIRM
[ "$CONFIRM" = "$DB_NAME" ] || { echo "aborted" >&2; exit 1; }

# The server holds connections open, and Postgres refuses to drop a database that has any. Stopping
# it first is quicker than terminating backends and less likely to leave a half-dropped schema.
$COMPOSE stop server

# `--clean --if-exists` drops each object before recreating it, so a restore into a non-empty
# database is a replacement rather than a merge. Without `--if-exists` the drops fail noisily on a
# database that is already empty, which is the common case.
$COMPOSE exec -T postgres \
    pg_restore --username="$DB_USER" --dbname="$DB_NAME" --clean --if-exists --no-owner \
    < "$DUMP"

# `--no-owner` above makes every restored object belong to whoever replayed the dump — the
# superuser — because that is the only role a dump is guaranteed to be replayable as. Left there,
# it is silently fatal: the server connects as the application role, which now owns nothing and
# holds no grant on anything, so start-up dies at `permission denied for table
# flyway_schema_history` and the container restart-loops on exit 70. From psql as the superuser the
# database looks perfect, which is what makes it an expensive evening.
#
# So the restore is only half the operation and this is the other half: the schema belongs to the
# application role, always, whoever replayed the bytes. Sequences attached to a column are skipped
# because Postgres refuses to own them apart from their table — the table's ALTER has already
# carried them across. It is idempotent, so it also repairs a database left mis-owned by a run of
# this script from before this block existed.
$COMPOSE exec -T postgres \
    psql -v ON_ERROR_STOP=1 --username="$DB_USER" --dbname="$DB_NAME" \
    -v app_user="$APP_USER" <<'SQL'
DO $$
DECLARE
    restored record;
BEGIN
    FOR restored IN
        SELECT c.relkind, n.nspname, c.relname
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind IN ('r', 'p', 'S', 'v', 'm')
           AND NOT EXISTS (
               SELECT 1
                 FROM pg_depend d
                WHERE d.classid = 'pg_class'::regclass
                  AND d.objid = c.oid
                  AND d.deptype IN ('a', 'i'))
    LOOP
        EXECUTE format('ALTER %s %I.%I OWNER TO %I',
            CASE restored.relkind
                WHEN 'S' THEN 'SEQUENCE'
                WHEN 'v' THEN 'VIEW'
                WHEN 'm' THEN 'MATERIALIZED VIEW'
                ELSE 'TABLE'
            END,
            restored.nspname, restored.relname, :'app_user');
    END LOOP;
END $$;
SQL

$COMPOSE start server

echo "Restored $DUMP."
echo "Verify: $COMPOSE exec -T server wget -q -O- http://127.0.0.1:8080/health/ready"
