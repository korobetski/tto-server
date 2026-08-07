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
DB_NAME="${POSTGRES_DB:-tripletriad}"
DB_USER="${POSTGRES_USER:-tripletriad}"

[ -f "$DUMP" ] || { echo "no such dump: $DUMP" >&2; exit 1; }

printf 'This will REPLACE the contents of "%s". Type the database name to confirm: ' "$DB_NAME"
read -r CONFIRM
[ "$CONFIRM" = "$DB_NAME" ] || { echo "aborted" >&2; exit 1; }

# The server holds connections open, and Postgres refuses to drop a database that has any. Stopping
# it first is quicker than terminating backends and less likely to leave a half-dropped schema.
docker compose stop server

# `--clean --if-exists` drops each object before recreating it, so a restore into a non-empty
# database is a replacement rather than a merge. Without `--if-exists` the drops fail noisily on a
# database that is already empty, which is the common case.
docker compose exec -T postgres \
    pg_restore --username="$DB_USER" --dbname="$DB_NAME" --clean --if-exists --no-owner \
    < "$DUMP"

docker compose start server

echo "Restored $DUMP. Verify with: curl -s localhost:8080/health/ready"
