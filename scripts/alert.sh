#!/usr/bin/env sh
# Reports that a scheduled unit failed.
#
#   ./scripts/alert.sh tto-backup.service
#
# Started by systemd through `OnFailure=` on the units that matter, never called by hand except to
# test it. See docs/operations.md § Backups.
#
# ### Why this exists
#
# Everything else about the backups was finished before this was: they run on a schedule, they are
# pruned, a copy leaves the machine with OVH's image, and a drill restores one every Monday. All of
# that is worth nothing if it stops working and nobody is told — a backup silently broken for six
# weeks looks exactly like a backup that works, which is the failure shape the whole exercise was
# meant to remove. It had merely moved up one level.
#
# ### What it deliberately is not
#
# It is not a dead man's switch. A host that is off sends nothing, and its silence is
# indistinguishable from a quiet week. Detecting *that* needs something outside this machine which
# expects a ping and complains when none arrives; this script cannot be it, by construction.
set -eu

UNIT="${1:?usage: alert.sh <unit-name>}"

cd "$(dirname "$0")/.."

# One variable, in the same .env as everything else. Any service that accepts a plain POST works —
# ntfy.sh needs no account at all, a Discord or Slack webhook is a URL you already have. Unset means
# "not configured", and that is not an error: the failure is still in the journal, and refusing to
# run would turn a missing alert into a second failed unit.
URL=""
[ -f .env ] && URL="$(sed -n 's/^TTO_ALERT_URL=//p' .env | tail -n 1 || true)"

HOSTNAME="$(hostname)"
WHEN="$(date -u '+%Y-%m-%d %H:%M:%S UTC')"

# The last few lines of what actually happened. Without them the alert says something broke and
# sends you to read the journal anyway, which on a phone at the wrong hour is most of the friction.
#
# `--no-pager`, and a small number of lines: this is a notification, not a log shipper. Note that an
# error line here could quote a connection string — a database user and host, never a password,
# since neither pg_dump nor the drill ever prints one.
CONTEXT="$(journalctl -u "$UNIT" -n 12 --no-pager -o cat 2>/dev/null || echo '(journal unreadable)')"

BODY="$UNIT failed on $HOSTNAME at $WHEN

$CONTEXT"

# Always to the journal, whether or not a URL is configured. This is the copy that survives the
# notification service being down, which is the day you would most want both.
echo "ALERT: $UNIT failed on $HOSTNAME at $WHEN" >&2

if [ -z "$URL" ]; then
    echo "TTO_ALERT_URL is not set in /srv/tto/.env - no notification sent" >&2
    exit 0
fi

# `--max-time` so a hanging notification service cannot hold a systemd unit open, and `--silent`
# because curl's progress meter in a journal is noise. The exit status is deliberately swallowed:
# this script running as a consequence of a failure must not itself become a failed unit that
# triggers nothing and clutters `systemctl --failed`.
if curl --silent --show-error --max-time 20 \
        --header "Title: $UNIT failed on $HOSTNAME" \
        --header "Priority: high" \
        --header "Tags: warning" \
        --data-binary "$BODY" \
        "$URL" > /dev/null 2>&1; then
    echo "notification sent" >&2
else
    echo "FAILED to send the notification - the journal above is the only record" >&2
fi

exit 0
