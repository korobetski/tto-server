#!/bin/sh
#
# Creates the role the server actually connects as.
#
# ### Why the application does not use the superuser
#
# Until now it did: `compose.yaml` handed the server `POSTGRES_USER`, which is the role the image
# creates on first boot — a superuser that can create roles, drop the database, read every other
# database in the cluster and switch off row-level security. Nothing the server does needs any of
# that. It connects, runs its migrations, and reads and writes its own tables.
#
# The gap matters most in the case this file exists for: an SQL injection or a leaked
# `DATABASE_PASSWORD` costs the tables the application owns, rather than the cluster. That is a
# smaller blast radius for no operational cost, and it is the difference between a bad day and an
# unrecoverable one — progression being server-held, per the migration's decision 2.
#
# ### Why this runs here rather than being typed once
#
# `/docker-entrypoint-initdb.d` runs **only on an empty data directory**. That makes it a bootstrap,
# not a migration system: change this file and it will not re-run on a volume that already exists.
# It is here so that `docker compose down -v && docker compose up -d` produces a working database
# rather than one where the server cannot authenticate — a one-off `CREATE ROLE` typed into psql
# would work today and be lost the first time anybody recreated the volume.
#
# The values come from the environment, set by `compose.yaml` from `.env`. They are passed to psql
# as *variables* rather than interpolated into the SQL text, so psql does the quoting: `:"name"` as
# an identifier, `:'name'` as a literal. Building the statement with shell interpolation would make
# a password containing a quote either a syntax error or an injection.
set -eu

psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    -v app_user="$APP_DB_USER" \
    -v app_password="$APP_DB_PASSWORD" \
    -v db_name="$POSTGRES_DB" <<-'SQL'
	CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';

	GRANT CONNECT ON DATABASE :"db_name" TO :"app_user";

	-- USAGE to read the schema, CREATE because Flyway builds the schema as this role and owns
	-- what it creates. Postgres 15 stopped granting CREATE on `public` to PUBLIC by default, so
	-- this is required rather than belt-and-braces.
	GRANT USAGE, CREATE ON SCHEMA public TO :"app_user";
SQL
