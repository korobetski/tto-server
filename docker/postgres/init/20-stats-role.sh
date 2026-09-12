#!/bin/sh
#
# The schema the statistics live in, and the role allowed to read them.
#
# ### Why the schema is made here and not by the migration that fills it
#
# `V18__stats_views.sql` creates the views, and it runs as `tto_app` — a role that may create
# objects *inside* `public` and nothing else. Creating a schema needs `CREATE` on the **database**,
# which that role deliberately does not have and should not be given: the point of `10-app-role.sh`
# is a blast radius the size of the application's own tables.
#
# So the schema is made once, by the superuser, and handed to `tto_app` — which then owns the views
# it creates in it, with no new privilege anywhere. The migration looks the schema up and creates it
# only when it is missing, which is the case the test suite comes through: Testcontainers runs no
# init script and migrates as a superuser. It cannot simply say `CREATE SCHEMA IF NOT EXISTS` —
# Postgres checks the privilege before the existence, so that form fails for `tto_app` even on a
# database where this script has already run.
#
# ### Why the reader role is optional
#
# `tto_stats` exists so that whatever reads the statistics — a Grafana over an SSH tunnel, today —
# cannot reach `password_hash`, an email address or a session token even if it is compromised. It
# is worth having the moment somebody is actually looking, and worth nothing before that: a
# deployment with no second reader would be carrying a password it never uses. So the role is
# created only when `STATS_DB_PASSWORD` names one, and `V18`'s grants are written to skip an
# absent role rather than fail on it.
#
# The name is a literal in both files. It cannot be a variable in the migration — a migration is a
# file with a checksum, and Flyway would have no environment to read anyway — so the two agree by
# spelling it out, and renaming the role means a new migration.
#
# ### The trap this file inherits
#
# `/docker-entrypoint-initdb.d` runs **only on an empty data directory**. Everything here is a
# bootstrap and not a migration: on a volume that already exists it does not re-run, and the two
# statements have to be typed once by hand. `.env.sample` carries them, including the one that
# cannot be skipped — without the schema, `V18` fails and the server exits 70.
#
# Values reach psql as *variables* rather than interpolated into the SQL, so psql does the quoting:
# `:"name"` as an identifier, `:'name'` as a literal. See `10-app-role.sh` for why that matters
# more than it looks.
set -eu

psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    -v app_user="$APP_DB_USER" <<-'SQL'
	CREATE SCHEMA IF NOT EXISTS stats AUTHORIZATION :"app_user";
SQL

if [ -n "${STATS_DB_PASSWORD:-}" ]; then
	psql -v ON_ERROR_STOP=1 \
	    --username "$POSTGRES_USER" \
	    --dbname "$POSTGRES_DB" \
	    -v stats_password="$STATS_DB_PASSWORD" \
	    -v db_name="$POSTGRES_DB" <<-'SQL'
		CREATE ROLE tto_stats LOGIN PASSWORD :'stats_password';

		-- Connect, and nothing else. `USAGE` on the schema and `SELECT` on the views are
		-- granted by `V18__stats_views.sql`, because the views do not exist yet — the server
		-- has not started. Nothing here grants anything on `public`, which is the whole point
		-- of the role.
		GRANT CONNECT ON DATABASE :"db_name" TO tto_stats;
	SQL
	echo "20-stats-role: read-only role tto_stats created."
else
	echo "20-stats-role: STATS_DB_PASSWORD unset, no read-only role created. The stats schema exists."
fi
