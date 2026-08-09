#!/usr/bin/env sh
# Puts a released image live on the host it runs on.
#
#   ./scripts/deploy.sh ghcr.io/korobetski/tto-server:v0.2.0
#   ./scripts/deploy.sh ghcr.io/korobetski/tto-server:v0.1.9   # ...and that is the rollback
#
# This runs **on the VPS**, in /srv/tto, called over SSH by .github/workflows/release.yml. It is a
# script in the repository rather than a heredoc in the workflow for two reasons: a rollback has to
# be runnable by a human on a bad evening without opening GitHub, and a deployment procedure that
# exists only inside a YAML file is a procedure nobody has read.
#
# ### What it guarantees
#
# The tag it is given either ends up serving traffic, or the previous one still is. There is no
# third outcome where the stack is half-updated and nobody noticed — the readiness gate below is
# what makes the difference, and the rollback is what makes it true rather than merely intended.
#
# ### What it does not
#
# It does not back the database up first, and it cannot: the migration has already run by the time
# the server reports ready, and Flyway does not roll back. A release whose migration is destructive
# needs a dump taken *before* it, by hand — see docs/operations.md § Backups.
set -eu

IMAGE="${1:?usage: deploy.sh <image-reference>}"
COMPOSE="docker compose -f compose.prod.yaml"

# How long to wait for the new container to answer /health/ready. Generous: it covers JVM start-up,
# the connection pool, and Flyway running every migration on a database that may have been idle.
READY_TIMEOUT="${READY_TIMEOUT:-120}"

cd "$(dirname "$0")/.."

# Waits for the server to answer /health/ready, or gives up. Used twice — once for the release and
# once for the rollback — because a rollback that is not checked is a guess, and the run that most
# needs the truth is the one where something has already gone wrong.
#
# /health/ready and not /health/live: live answers for the process alone and would be satisfied by a
# server that cannot reach the database. Reached from inside the container, because the host cannot
# reach it — nothing publishes that port, on purpose.
await_ready() {
    elapsed=0
    while [ "$elapsed" -lt "$READY_TIMEOUT" ]; do
        if $COMPOSE exec -T server wget --quiet --spider http://127.0.0.1:8080/health/ready 2>/dev/null; then
            return 0
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done
    return 1
}

# Rewrites the one line that says what this host runs. Atomic: a half-written .env is a host that
# cannot start at all, which is worse than either version of it.
pin_image() {
    {
        grep -v '^TTO_IMAGE=' .env || true
        echo "TTO_IMAGE=$1"
    } > .env.next
    mv .env.next .env
}

if [ ! -f .env ]; then
    echo "FAILED: no .env in $(pwd). See docs/deployment.md - the host is not provisioned." >&2
    exit 1
fi

# What is running now, so there is something to go back to. Read from .env rather than from the
# running container, because .env is what a reboot would bring back up.
PREVIOUS="$(sed -n 's/^TTO_IMAGE=//p' .env | tail -n 1 || true)"

# Pull first, and separately. A pull that fails — a bad tag, an expired registry login — must not
# be discovered halfway through recreating the stack, with the old container already gone.
echo "==> pulling $IMAGE"
docker pull "$IMAGE"

# `.env` is the single source of truth for what this host runs, so that `docker compose up -d`
# after a reboot brings back the deployed version rather than whatever `latest` has become.
echo "==> pinning TTO_IMAGE"
pin_image "$IMAGE"

echo "==> starting"
$COMPOSE up -d --remove-orphans

# ---- the gate ----------------------------------------------------------------------------------
#
# `up -d` returns as soon as the container is *started*, which says nothing about whether the
# process inside it survived its own configuration. A server that exits 78 on a missing variable,
# or 70 on a migration it cannot apply, restarts forever behind a deployment that reported success.
echo "==> waiting for readiness (up to ${READY_TIMEOUT}s)"
if await_ready; then
    echo "==> ready: $IMAGE"
    # Images accumulate at ~200 MB each and the smallest OVH VPS has a small disk. Dangling only:
    # every tag this host has deployed stays pullable locally, which is what makes a rollback
    # instant instead of a download.
    docker image prune --force > /dev/null 2>&1 || true
    exit 0
fi

# ---- the rollback ------------------------------------------------------------------------------
echo "FAILED: $IMAGE did not become ready in ${READY_TIMEOUT}s" >&2
$COMPOSE logs --tail 100 server >&2

if [ -z "$PREVIOUS" ] || [ "$PREVIOUS" = "$IMAGE" ]; then
    # First deployment, or a redeploy of the same tag. There is nothing to go back to, and leaving
    # the broken stack up is right: it is the state that has to be debugged.
    echo "no previous image recorded - leaving the stack as it is" >&2
    exit 1
fi

echo "==> rolling back to $PREVIOUS" >&2
pin_image "$PREVIOUS"
$COMPOSE up -d

# The rollback is waited on too, and this is not symmetry for its own sake. `up -d` returning says
# the previous container started, not that it recovered — and the one case where that distinction
# decides what somebody does next is exactly this one. Reporting "rolled back" over a stack that is
# also down sends whoever reads it looking in the wrong place.
if await_ready; then
    echo "==> rolled back: $PREVIOUS is serving" >&2
    exit 1
fi

echo "FAILED: the rollback to $PREVIOUS did not become ready either - THIS HOST IS DOWN" >&2
$COMPOSE logs --tail 100 server >&2
exit 2
