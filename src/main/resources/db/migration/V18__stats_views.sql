-- The figures, as definitions rather than as queries typed into a dashboard.
--
-- `web-platform.md` § Statistics is the argument for this file existing: three ambiguities in the
-- schema that every consumer would otherwise resolve on its own, silently and differently.
--
--   1. **"A match" is three different numbers.** `matches` is credited PvE history — transcripts
--      the server replayed and paid. `pve_matches` is the refereed sessions it held itself.
--      `pvp_matches` is player against player, forfeits and abandons included. All three are
--      legitimate and none of them is "the number of matches".
--   2. **The bots inflate everything.** With `TTO_BOTS_ENABLED` set, the server plays itself. A
--      population count that does not exclude `bots.account_id` measures the lobby-filling
--      machinery — `operations.md` gives the predicate every query here uses.
--   3. **"Registered" is not "verified".** `accounts.email_verified_at` separates them, and
--      `accounts.seen_at` — added in `V15__presence.sql` — is what makes "active" answerable at
--      all.
--
-- Putting the answers in views rather than in panel definitions means a consumer that disagrees
-- with one has to change *this file*, in a migration, with the reason written down — instead of
-- disagreeing quietly in a Grafana panel nobody diffs. The console reads the same views, so its
-- dashboard and the operator's graph cannot drift apart.
--
-- ### These views hold no personal data
--
-- Nothing below selects a username, an address, a token or a save document: every column is a
-- count or a sum over a population. That is what makes the read-only role at the end of this file
-- worth having — a compromise of whatever reads these views learns how many players there are and
-- not who they are. `docs/data-inventory.md` gains no row from this migration for the same reason,
-- and says so.

-- ---------------------------------------------------------------------------
-- 0. The schema.
-- ---------------------------------------------------------------------------
--
-- Guarded by a lookup rather than written `CREATE SCHEMA IF NOT EXISTS`, and the difference is a
-- privilege one. Creating a schema requires `CREATE` **on the database**, which `tto_app`
-- deliberately does not have — see `10-app-role.sh`, where the whole point of the role is that it
-- owns its own objects and nothing else. On a deployed host the schema is therefore made once, by
-- the superuser, with `AUTHORIZATION tto_app`: `20-stats-role.sh` does it on a fresh volume, and
-- `.env.sample` carries the one-off `psql` for a volume that already exists.
--
-- `CREATE SCHEMA IF NOT EXISTS stats` would then look like a no-op, and is not one: Postgres 17
-- checks the privilege **before** the existence, so that statement fails with `permission denied
-- for database` on a database where the schema is already sitting there. Measured, not assumed.
-- The lookup below is therefore the mechanism and not a precaution.
--
-- The test suite comes through the other branch: Testcontainers runs no init scripts and migrates
-- as the superuser of a fresh database, so the schema is absent and creating it is allowed.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'stats') THEN
        EXECUTE 'CREATE SCHEMA stats';
    END IF;
END
$$;

-- Every view below qualifies its tables `public.*`, which is not the house style anywhere else in
-- this directory. A view body is resolved once, at creation, against the `search_path` in force
-- then — so an unqualified `matches` inside `stats.matches` would be correct today and would mean
-- something else the day somebody creates these views with `stats` in their path. Qualifying costs
-- seven characters and removes the question.

-- ---------------------------------------------------------------------------
-- 1. The population.
-- ---------------------------------------------------------------------------
--
-- Rolling windows, not calendar days. A calendar day needs a timezone, and the database has no
-- business choosing one on behalf of an operator in Paris and a dashboard in UTC: "the last 24
-- hours" is the same number whoever reads it and whenever they read it. The cost is that these
-- figures cannot be lined up with a billing period, which nothing here wants to do.
--
-- `seen_at` is written by the lobby rather than by signing in — so "active" here means a client
-- that reached the server, including one that launched and played nothing. That is the honest
-- reading of the column, and the console repeats it under the tiles.
CREATE OR REPLACE VIEW stats.accounts AS
SELECT
    count(*)                                                           AS registered,
    count(*) FILTER (WHERE a.email_verified_at IS NOT NULL)            AS verified,
    count(*) FILTER (WHERE a.seen_at > now() - INTERVAL '24 hours')    AS active_today,
    count(*) FILTER (WHERE a.seen_at > now() - INTERVAL '7 days')      AS active_this_week,
    count(*) FILTER (WHERE a.created_at > now() - INTERVAL '24 hours') AS new_today
FROM public.accounts a
WHERE NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = a.id);

-- ---------------------------------------------------------------------------
-- 2. Matches, all three kinds, as one stream.
-- ---------------------------------------------------------------------------
--
-- Ambiguity 1 made into a column. A consumer that wants "matches" has to name which kind, because
-- there is no row here that does not carry a `kind` — and one that genuinely wants all of them
-- gets a defensible total instead of an accidental one.
--
-- It is also the shape a time series wants: `kind, at` is what a rate-per-hour panel groups by,
-- and deriving the counts from it (section 3) means the graph and the tile cannot disagree about
-- what they are counting.
--
-- **Which timestamp.** `matches.played_at` is when the server accepted the transcript, not when
-- the client says it was played — an offline queue that drains after three days lands today, and
-- today is the only one of the two the server can vouch for. The other two use `created_at`,
-- which is when the session was opened; a match still `PLAYING` is counted, because "started" and
-- "finished" are different questions and this stream answers the first.
--
-- **The cost.** Three sequential scans. That is nothing on a database this size and it stops
-- being nothing somewhere north of a million rows; the answer then is a materialised view
-- refreshed on a schedule, which is a change to this file's successor and to no consumer.
CREATE OR REPLACE VIEW stats.match_events AS
SELECT 'CREDITED'::text AS kind, m.id::text AS id, m.played_at AS at
FROM public.matches m
WHERE NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = m.account_id)
UNION ALL
SELECT 'PVE'::text, p.id, p.created_at
FROM public.pve_matches p
WHERE NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = p.account_id)
UNION ALL
-- A bot on **one** side is not the machinery playing itself: a person played that match, and
-- dropping it would understate what the lobby did for them. A bot on both sides is, and this is
-- the only place in the file where the predicate is not simply "the account is not a bot".
SELECT 'PVP'::text, v.id, v.created_at
FROM public.pvp_matches v
WHERE NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = v.blue_account)
   OR NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = v.red_account);

-- ---------------------------------------------------------------------------
-- 3. The three counts, and the one that adds them up.
-- ---------------------------------------------------------------------------
--
-- `today` is deliberately the sum across all three kinds, and it is the only figure here that
-- mixes them. It answers "did anybody play today", which is a question about the service rather
-- than about a table — and a dashboard that wants the breakdown has `stats.match_events`.
CREATE OR REPLACE VIEW stats.matches AS
SELECT
    count(*) FILTER (WHERE e.kind = 'CREDITED')                AS credited,
    count(*) FILTER (WHERE e.kind = 'PVE')                     AS pve,
    count(*) FILTER (WHERE e.kind = 'PVP')                     AS pvp,
    count(*) FILTER (WHERE e.at > now() - INTERVAL '24 hours') AS today
FROM stats.match_events e;

-- ---------------------------------------------------------------------------
-- 4. The money.
-- ---------------------------------------------------------------------------
--
-- **Three columns and not one, because a purse is not the whole supply.** Listing a card and
-- bidding on one both *remove* MGP from a save document: the bid and its fee sit in
-- `auction_bids` until the lot settles or the hold is refunded. Counting purses alone would make
-- the money supply dip whenever the auction house got busy and recover when it went quiet, which
-- is a fact about where the coins are sitting rather than about how many exist. So `mgp` is what
-- exists, `mgp_in_purses` is what is spendable right now, and `mgp_escrowed` is the difference —
-- counted once inside the first and once on its own.
--
-- **`(save ->> 'MGP')::bigint`, not a column.** The purse lives in the profile document, because
-- `characters.save` is one document by V1's decision and nothing reads a profile by column. A
-- save written before the field existed yields NULL and `sum` skips it; a save holding something
-- that is not a number raises, which is the right outcome for a document `GameSave` cannot have
-- produced. `bigint`, because a hundred thousand players with a six-figure purse overflows `int`
-- and a sum is the one place that is reachable.
--
-- **Escrow counts holds, not bids.** `refunded_at IS NULL AND settled_at IS NULL` is the
-- predicate of `auction_bids_one_hold`, the partial unique index the whole escrow rests on — so
-- this figure is exactly the money that index protects, and a bid beaten an hour ago contributes
-- nothing. A hold whose bidder has since deleted their account has `bidder_account` NULL and
-- `NOT EXISTS` admits it, which is right: the coins are still held.
--
-- **`lots_live` includes `AWAITING_SELLER`.** A lot waiting on its seller's decision is still
-- holding a bidder's money, and an operator asking how many lots are live is asking how much of
-- the house is unsettled.
CREATE OR REPLACE VIEW stats.economy AS
SELECT
    purses.mgp + escrow.mgp AS mgp,
    purses.mgp              AS mgp_in_purses,
    escrow.mgp              AS mgp_escrowed,
    lots.live               AS lots_live
FROM
    (
        SELECT coalesce(sum((c.save ->> 'MGP')::bigint), 0) AS mgp
        FROM public.characters c
        WHERE NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = c.account_id)
    ) purses,
    (
        SELECT coalesce(sum(ab.amount::bigint + ab.fee), 0) AS mgp
        FROM public.auction_bids ab
        WHERE ab.refunded_at IS NULL
          AND ab.settled_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = ab.bidder_account)
    ) escrow,
    (
        SELECT count(*) AS live
        FROM public.auction_lots l
        WHERE l.status IN ('OPEN', 'AWAITING_SELLER')
          AND NOT EXISTS (SELECT 1 FROM public.bots b WHERE b.account_id = l.seller_account)
    ) lots;

-- ---------------------------------------------------------------------------
-- 5. One row, for the one screen that shows all of it.
-- ---------------------------------------------------------------------------
--
-- The console's dashboard is a single request and this is the query behind it: `SELECT * FROM
-- stats.overview`, one row, thirteen figures and the instant they were read at. Reading the three
-- views separately would be three round trips and — worse — three instants, so a dashboard could
-- show a match credited at a moment its payout is not yet in the purse total.
--
-- Columns are prefixed by their group rather than inherited, because two of them would otherwise
-- collide in meaning if not in name: `accounts_new_today` and `matches_today` are both "today"
-- and count entirely different things.
--
-- `now()` is transaction start, so it is the instant the snapshot the rest of the row was read in
-- began — the only timestamp that describes all of them at once. A figure with no timestamp
-- cannot be compared with anything, including itself ten minutes ago.
CREATE OR REPLACE VIEW stats.overview AS
SELECT
    now()              AS as_of,
    a.registered       AS accounts_registered,
    a.verified         AS accounts_verified,
    a.active_today     AS accounts_active_today,
    a.active_this_week AS accounts_active_this_week,
    a.new_today        AS accounts_new_today,
    m.credited         AS matches_credited,
    m.pve              AS matches_pve,
    m.pvp              AS matches_pvp,
    m.today            AS matches_today,
    e.mgp              AS mgp_total,
    e.mgp_in_purses    AS mgp_in_purses,
    e.mgp_escrowed     AS mgp_escrowed,
    e.lots_live        AS lots_live
FROM stats.accounts a, stats.matches m, stats.economy e;

-- ---------------------------------------------------------------------------
-- 6. The read-only role.
-- ---------------------------------------------------------------------------
--
-- `tto_stats` may use this schema and select from it, and has nothing on `public`. That is the
-- reasoning of `10-app-role.sh` applied a second time and one step further down: a compromise of
-- whatever reads these views — a Grafana, a notebook, a cron — cannot reach `password_hash`, the
-- addresses `data-inventory.md` tracks, or `sessions.token_hash`. It cannot reach them *through*
-- the views either, because no view selects one.
--
-- **The views are the mechanism and not just the interface.** A view executes with its owner's
-- rights unless it is declared `security_invoker`, and these deliberately are not: `tto_stats` has
-- no SELECT on `accounts`, and the view reading it on their behalf is exactly the boundary being
-- bought. `WITH (security_invoker = true)` reads like the safer option — and is, for a view that
-- is meant to respect row-level security — but here it would make every view fail for the only
-- role that is supposed to read them.
--
-- **Conditional, because the role is optional.** It is created by the superuser
-- (`20-stats-role.sh` on a fresh volume, a one-off `psql` otherwise) and only when somebody has
-- set a password for it; a deployment that never wanted a second reader has no such role, and a
-- migration that failed on its absence would turn an unused feature into a refusal to start. The
-- name is a literal here — it cannot come from the environment, because a migration is a file
-- with a checksum — so the init script and this file agree by both spelling it out, and renaming
-- it is a new migration.
--
-- Two grants and not one: `ALL TABLES` covers what exists this instant, and `ALTER DEFAULT
-- PRIVILEGES` covers what a later migration adds, so the next view in this schema does not
-- quietly become invisible to the only role that reads it. Default privileges attach to the
-- *creating* role, which is the same role that runs every migration, so the pair holds for the
-- same reason the schema's ownership does.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tto_stats') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA stats TO tto_stats';
        EXECUTE 'GRANT SELECT ON ALL TABLES IN SCHEMA stats TO tto_stats';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA stats GRANT SELECT ON TABLES TO tto_stats';
    END IF;
END
$$;
