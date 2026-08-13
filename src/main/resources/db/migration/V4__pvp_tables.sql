-- Open tables, wagers, and the choice a winner is owed.
--
-- This migration supersedes the parts of `V2__pvp.sql` that describe a queue: `pvp_queue` and its
-- prose (V2 lines 9-25) are gone, and the paragraph there arguing that a queue beats a lobby no
-- longer holds. It was right while every match was the same match. It stopped being right the
-- moment a match could be played under chosen rules for a stake: two players who agree on nothing
-- have no business being paired, and a player told what they agreed to *after* agreeing has not
-- agreed to anything.
--
-- What replaces it is `pvp_tables` — one row per player waiting, carrying the terms they are
-- waiting on, readable by everybody.

-- ---------------------------------------------------------------------------
-- 1. The stake columns change shape.
-- ---------------------------------------------------------------------------
--
-- `PvpStake` was a sealed interface and is now a plain class, so the JSONB in these two columns is
-- in an encoding nothing can read any more: `{"type":"…PvpStake.None"}` against `{"mgp":0,
-- "trade":"NONE"}`.
--
-- Rewritten unconditionally rather than converted, and that is safe for a reason worth writing
-- down rather than trusting: **no client has ever set a non-`None` stake**. `PvpStake.Cards` existed
-- in the protocol and was reachable only through `POST /pvp/challenges`, which no screen ever
-- called with a wager. So every row in both tables holds the same value, and mapping it is a
-- one-line `UPDATE` rather than a JSONB rewrite that would have to guess at shapes.
UPDATE pvp_challenges SET stake = '{"mgp":0,"trade":"NONE"}'::jsonb;
UPDATE pvp_matches    SET stake = '{"mgp":0,"trade":"NONE"}'::jsonb;

-- ---------------------------------------------------------------------------
-- 2. The queue goes.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS pvp_queue_pairing_idx;
DROP TABLE IF EXISTS pvp_queue;

-- ---------------------------------------------------------------------------
-- 3. The claim a winner is owed.
-- ---------------------------------------------------------------------------
--
-- Under the One and Diff trade rules the winner **names** the cards they take, so a match whose
-- board is finished is not yet a match that can be paid. `AWAITING_CLAIM` is that gap, and it is a
-- status rather than a flag on `FINISHED` because the difference between them is whether both
-- profiles have been credited — and a status that sometimes means paid and sometimes does not is
-- the one thing standing between a settlement and crediting it twice.
--
-- `claimed` holds `{"BLUE":[261,262],"RED":[]}`. Storing the choice as **data** rather than
-- applying it and moving on is the same argument V2 makes for storing moves instead of a board
-- (V2 lines 54-70): a claim is an *input*, the settlement stays derivable from the row, and a
-- reader with the engine can check that what was credited is what the rules allow.
ALTER TABLE pvp_matches
    ADD COLUMN claim_deadline TIMESTAMPTZ,
    ADD COLUMN claimed        JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE pvp_matches DROP CONSTRAINT pvp_matches_status;
ALTER TABLE pvp_matches ADD CONSTRAINT pvp_matches_status
    CHECK (status IN ('PLAYING', 'AWAITING_CLAIM', 'FINISHED', 'FORFEITED', 'ABANDONED'));

-- What the claim sweep reads, and nothing else: partial, so it stays the size of the backlog rather
-- than the size of the table. The turn-deadline index next to it is built on the same principle.
CREATE INDEX pvp_matches_claim_idx ON pvp_matches (claim_deadline)
    WHERE status = 'AWAITING_CLAIM';

-- ---------------------------------------------------------------------------
-- 4. Open tables.
-- ---------------------------------------------------------------------------
--
-- `rules` is what the host **declared**, which is not necessarily what gets played: when `roulette`
-- is set the server draws one to three further rules as the match opens. The two are separate
-- columns because they are separate facts, and because `GameRules.roulette` is what the Wheel of
-- Fortune achievements count — a host writing it directly would credit a roulette win for a match
-- that never drew one. Only `Roulette.augment` may set that field, and it sets it on the match.
CREATE TABLE pvp_tables (
    id           TEXT        PRIMARY KEY,
    host_account BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    format       TEXT        NOT NULL,
    -- `GameRules`, as `pvp_matches.rules` is.
    rules        JSONB       NOT NULL,
    roulette     BOOLEAN     NOT NULL DEFAULT FALSE,
    -- `PvpStake`: an amount of MGP and a trade rule.
    stake        JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A table with no expiry is a lobby full of people who left hours ago, and joining one is a
    -- match against nobody. The host's client refreshes it while the screen is open.
    expires_at   TIMESTAMPTZ NOT NULL,
    -- Set when somebody joined. The row is kept rather than deleted, so the host's client can find
    -- out *which* match its table turned into — the same reason `pvp_challenges.match_id` is kept.
    match_id     TEXT
);

-- One open table per host, and this index is the rule rather than a check in application code.
-- Without it "cancel my table" has to say *which*, and two tables from one player are two matches
-- that player cannot both turn up to.
--
-- A partial unique index and not an `EXCLUDE` constraint: exclusion on `=` over a bigint needs
-- `btree_gist`, and this expresses the same thing with an extension nobody has to install.
CREATE UNIQUE INDEX pvp_tables_one_per_host ON pvp_tables (host_account)
    WHERE match_id IS NULL;

-- The lobby listing: open tables, soonest to lapse first.
CREATE INDEX pvp_tables_open_idx ON pvp_tables (expires_at) WHERE match_id IS NULL;
