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
    : "${DB_NAME:=tripletriad}"
    : "${DB_USER:=tripletriad}"
fi

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

$COMPOSE start server

echo "Restored $DUMP."
echo "Verify: $COMPOSE exec -T server wget -q -O- http://127.0.0.1:8080/health/ready"
