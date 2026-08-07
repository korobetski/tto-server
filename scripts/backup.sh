#!/usr/bin/env sh
# Takes a compressed logical backup of the development database.
#
#   ./scripts/backup.sh [output-directory]
#
# ### Why a logical dump
#
# `pg_dump` produces a file that restores into a *different* Postgres version, on a different
# machine, with a different filesystem. Copying the data directory is faster and restores only into
# a byte-identical server — which is exactly the thing you will not have on the day you need it.
#
# ### What this script is not
#
# It is not a backup strategy. It runs when someone remembers to run it, keeps everything forever,
# and stores the result on the same disk as the database. All three are wrong for anything holding
# real progression. See docs/operations.md § Backups; the short version is that the only backup
# that counts is one whose RESTORE has been tested, on a schedule nobody has to remember.
set -eu

OUT_DIR="${1:-backups}"
DB_NAME="${POSTGRES_DB:-tripletriad}"
DB_USER="${POSTGRES_USER:-tripletriad}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="$OUT_DIR/${DB_NAME}-${STAMP}.dump"

mkdir -p "$OUT_DIR"

# `--format=custom` rather than plain SQL: it is compressed, and it lets pg_restore select
# individual tables and restore in parallel. A plain dump can only be replayed whole, in order.
#
# `-T` on exec: without it, Docker allocates a TTY and mangles the binary dump with CR translation.
docker compose exec -T postgres \
    pg_dump --username="$DB_USER" --dbname="$DB_NAME" --format=custom --no-owner \
    > "$TARGET"

# A dump that cannot be listed is a corrupt file that will be discovered during the restore, which
# is the worst possible moment. Verifying the table of contents costs a second and catches a
# truncated write.
if ! docker compose exec -T postgres pg_restore --list < "$TARGET" > /dev/null 2>&1; then
    echo "FAILED: $TARGET is not a readable dump; removing it" >&2
    rm -f "$TARGET"
    exit 1
fi

echo "$TARGET"
