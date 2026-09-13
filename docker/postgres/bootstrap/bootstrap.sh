#!/bin/sh
#
# What has to exist before the server migrates, and cannot be made by the role that migrates.
#
# ### The failure this file exists because of
#
# `V18__stats_views.sql` fills a `stats` schema, and Flyway runs it as `tto_app` — a role that may
# create objects inside `public` and nothing else. Creating a schema needs `CREATE` on the
# **database**, which that role deliberately does not have. So the schema is made by the superuser,
# and the only question is *when*.
#
# The first answer was `/docker-entrypoint-initdb.d`, which runs on an empty data directory and
# never again. It is correct on a fresh volume and silently wrong on every host that already has
# one — and that is not a hypothetical: the deployment carrying `V18` to a live host failed exactly
# there, with
#
#     SQL State  : 42501
#     Message    : ERROR: permission denied for database tripletriad
#       Where: SQL statement "CREATE SCHEMA stats"
#
# and the documented remedy was a `psql` command typed by hand before the release could go out. A
# deployment that needs a human to run one statement first is a deployment that will be attempted
# without it, at the worst possible moment, by whoever is on call rather than by whoever read the
# note.
#
# ### So this runs on every boot instead
#
# It is a one-shot container in both compose files — `postgres-bootstrap` — that connects as the
# superuser, does the two privileged things, and exits. `server` declares
# `condition: service_completed_successfully` on it, so the ordering is compose's rather than a
# comment's, and a failure here stops the deployment before the migration has a chance to fail
# more obscurely.
#
# Everything below is idempotent by construction, because "every boot" includes the ten thousandth
# one. Nothing here is a migration: it must stay the sort of thing that is *true* rather than the
# sort of thing that *happens*, since it will run again against a database where it already holds.
#
# ### What it deliberately does not do
#
# It does not create `tto_app` — see `init/10-app-role.sh`. That role must exist before anything
# connects as it, including this container's sibling, and a password that reaches a new role on
# every boot is a password that can be changed by editing `.env` and restarting. Which is either a
# feature or a way to lock the server out of its own database depending on who edited the file, and
# a bootstrap is the wrong place to make that call.
#
# It grants nothing on `public`. The whole value of `tto_stats` is that a compromise of whatever
# reads the figures — a Grafana behind an SSH tunnel — learns how many players there are and not
# who they are. `V18` grants it `USAGE` on the schema and `SELECT` on the views, which is all it
# has.
#
# Values reach psql as *variables* rather than interpolated into SQL text, so psql does the
# quoting: `:"name"` as an identifier, `:'name'` as a literal. See `init/10-app-role.sh` for why
# that is not a style preference — a password containing a quote is otherwise a syntax error at
# best and an injection at worst.
set -eu

# Connection details come from the environment (`PGHOST`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`),
# which is what keeps the password off this container's command line and out of `docker inspect`'s
# `Cmd`. `ON_ERROR_STOP` on every call: a bootstrap that reports success after a failed statement
# is worse than no bootstrap, because the deployment then fails somewhere else.
psql -v ON_ERROR_STOP=1 -v app_user="$APP_DB_USER" <<-'SQL'
	CREATE SCHEMA IF NOT EXISTS stats AUTHORIZATION :"app_user";
SQL
echo "postgres-bootstrap: schema stats exists and belongs to the application role."

if [ -z "${STATS_DB_PASSWORD:-}" ]; then
	# Unset is the ordinary state, not an oversight: a deployment nobody graphs does not need a
	# second reader, and one carrying a password it never uses is worse than one without it.
	echo "postgres-bootstrap: STATS_DB_PASSWORD unset, no read-only role. The views exist anyway."
	exit 0
fi

# `CREATE ROLE` is not idempotent and has no `IF NOT EXISTS`, so the existence check is explicit: a
# `WHERE NOT EXISTS` that produces the statement, and `\gexec` that runs whatever rows came back —
# none on every boot after the first. The `ALTER ROLE` after it is unconditional, which is the point
# rather than belt-and-braces: it makes editing `STATS_DB_PASSWORD` and restarting how the role's
# password is rotated, because that is the only mechanism an operator would think to reach for.
# Setting a password a role already has is a no-op, so nothing churns.
#
# ### Why `\gexec` and not the `DO $$ ... $$` block this used to be
#
# Because psql does **not** substitute its variables inside a dollar-quoted string, so
# `EXECUTE format('... %L', :'stats_password')` inside a `DO` block reaches the server with the
# colon still in it and fails with `syntax error at or near ":"`. Found by running it, which is the
# only way this sort of thing is found. `\gexec` keeps the variable in ordinary SQL text where psql
# can see it, and `format('%L')` still does the quoting — so a password containing a quote is a
# password and not an injection.
#
# The grants are outside the branch because a grant is a statement about a state, and repeating one
# costs nothing. `V18` makes the same four, and that is not duplication to be tidied away — it is
# what closes the gap between them. The migration's grants run once, when the migration runs, and
# only if the role happened to exist at that moment; a `STATS_DB_PASSWORD` set for the first time on
# a database where `V18` has already been applied creates a role the migration will never grant
# anything to again. Repeating them here on every boot means the answer to "I set the password and
# the role cannot see anything" is a restart rather than four statements out of a sample file.
#
# `ALL TABLES` grants nothing on the very first boot, when the views do not exist yet — `V18` covers
# that case moments later. `ALTER DEFAULT PRIVILEGES` carries `FOR ROLE`, unlike the migration's
# bare form: default privileges attach to whoever creates the object, and the objects are created
# by the application role running the migration, not by the superuser typing this.
psql -v ON_ERROR_STOP=1 -v stats_password="$STATS_DB_PASSWORD" -v db_name="$PGDATABASE" \
	-v app_user="$APP_DB_USER" <<-'SQL'
	SELECT format('CREATE ROLE tto_stats LOGIN PASSWORD %L', :'stats_password')
	WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tto_stats')
	\gexec

	SELECT format('ALTER ROLE tto_stats LOGIN PASSWORD %L', :'stats_password')
	\gexec

	GRANT CONNECT ON DATABASE :"db_name" TO tto_stats;
	GRANT USAGE ON SCHEMA stats TO tto_stats;
	GRANT SELECT ON ALL TABLES IN SCHEMA stats TO tto_stats;
	ALTER DEFAULT PRIVILEGES FOR ROLE :"app_user" IN SCHEMA stats
	    GRANT SELECT ON TABLES TO tto_stats;
SQL
echo "postgres-bootstrap: read-only role tto_stats exists and may read the stats views."
