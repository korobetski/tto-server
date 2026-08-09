#!/usr/bin/env sh
# Takes a compressed logical backup of the database this host runs.
#
#   ./scripts/backup.sh [output-directory]
#
# ### Why a logical dump, when OVH already images the whole VPS
#
# The two answer different questions and neither replaces the other.
#
# OVH's automated backup is a snapshot of the machine, taken hot. For Postgres that is a power cut,
# which it survives by design — the WAL exists for exactly this. So it is a real safety net, and it
# is off the machine, which is the hard part. What it cannot do is restore *a database*: it restores
# a VPS, to one moment, whole. There is no extracting one table from it, no standing it up beside
# production to check it, and no going back two days if the retention holds one image.
#
# A dump is the opposite shape. It restores into a different Postgres version, on a different
# machine, and it can be read, listed and diffed without touching anything. It is also small enough
# to keep many of.
#
# So this runs *before* OVH's window and writes into a directory the VPS image includes. OVH carries
# the dumps off the machine; the dumps make what OVH carries restorable in a useful way.
#
# ### What this script is still not
#
# It is not a tested restore. That is `restore-drill.sh`, and it is the only one of the four
# properties that actually decides whether any of this works — see docs/operations.md § Backups.
set -eu

# Run from the deployment root whatever the caller's working directory is: a systemd timer has none
# worth speaking of, and the compose file is resolved relative to it.
cd "$(dirname "$0")/.."

OUT_DIR="${1:-backups}"
DB_NAME="${POSTGRES_DB:-tripletriad}"
DB_USER="${POSTGRES_USER:-tripletriad}"
# How many dumps to keep here. The VPS image carries this directory, so this number is also how far
# back a single OVH restore can reach — which is the reason it is not 2.
KEEP="${BACKUP_KEEP:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="$OUT_DIR/${DB_NAME}-${STAMP}.dump"

# Which stack to talk to. The deployed host receives `compose.prod.yaml` and nothing else; a
# developer's checkout has both files, so the test has to be for the *development* one — asking
# "is compose.prod.yaml here" is true in both places and picks the wrong stack on a laptop, where
# it fails on a `TTO_DOMAIN` that has no business being set.
#
# Without any of this the script failed on the VPS with `no configuration file provided`, which is
# how it spent its first weeks: present, committed, and unusable exactly where it mattered.
if [ -f compose.yaml ]; then
    COMPOSE="docker compose"
else
    COMPOSE="docker compose -f compose.prod.yaml"
fi

# `.env` holds POSTGRES_USER and POSTGRES_DB on a deployed host, and a timer inherits no shell that
# would have read it. Sourced only for the values above; compose reads the file itself for the rest.
if [ -f .env ]; then
    DB_NAME="$(sed -n 's/^POSTGRES_DB=//p' .env | tail -n 1 || true)"
    DB_USER="$(sed -n 's/^POSTGRES_USER=//p' .env | tail -n 1 || true)"
    : "${DB_NAME:=tripletriad}"
    : "${DB_USER:=tripletriad}"
    TARGET="$OUT_DIR/${DB_NAME}-${STAMP}.dump"
fi

mkdir -p "$OUT_DIR"

# Written under a name no other script looks for, and moved into place only once it has been
# verified. The shell creates the redirection target *before* running the command, so a dump that
# fails — an unreachable container, a wrong password — otherwise leaves a zero-byte `.dump` behind:
# a file that counts against retention, and that `restore-drill.sh` would pick up as the newest one.
# A backup directory whose newest entry is empty is worse than one with a gap, because it looks fine.
PARTIAL="$TARGET.partial"
trap 'rm -f "$PARTIAL"' EXIT INT TERM

# `--format=custom` rather than plain SQL: it is compressed, and it lets pg_restore select
# individual tables and restore in parallel. A plain dump can only be replayed whole, in order.
#
# `-T` on exec: without it, Docker allocates a TTY and mangles the binary dump with CR translation.
$COMPOSE exec -T postgres \
    pg_dump --username="$DB_USER" --dbname="$DB_NAME" --format=custom --no-owner \
    > "$PARTIAL"

# A dump that cannot be listed is a corrupt file that will be discovered during the restore, which
# is the worst possible moment. Verifying the table of contents costs a second and catches a
# truncated write.
if ! $COMPOSE exec -T postgres pg_restore --list < "$PARTIAL" > /dev/null 2>&1; then
    echo "FAILED: the dump is not readable; keeping nothing" >&2
    exit 1
fi

mv "$PARTIAL" "$TARGET"

# ---- retention ---------------------------------------------------------------------------------
#
# Keep the newest KEEP dumps and delete the rest. By count and not by age, deliberately: a rule
# expressed in days deletes the last surviving copy on a host whose backups have been failing for a
# fortnight, which is the one moment it must not. This one can only ever delete a file that has
# KEEP newer siblings.
#
# Pruning happens *after* the new dump is verified, so a failed run never costs an old one.
ls -1t "$OUT_DIR"/"${DB_NAME}"-*.dump 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
    echo "pruning $old"
    rm -f "$old"
done

echo "$TARGET"
