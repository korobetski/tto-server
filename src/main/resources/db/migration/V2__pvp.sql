-- Player versus player: the queue, the invitations, and the matches themselves.
--
-- Written when PvP became server-mediated. The decision that shapes every table here is that the
-- **server is the referee, not a relay**: it holds the one match state, deals both hands and tells
-- each client only what that client may see. A relay design would have needed none of this — two
-- clients would have agreed between themselves — and would have had nothing protecting a player's
-- hand but the other client's good manners.

-- Who is waiting for a quick match.
--
-- One row per account, not a row per request: a player is either in the queue or not, and a table
-- that could hold two entries for one account would need a rule about which of them wins. The
-- primary key is that rule.
--
-- `collection` is here because a deck cannot mix sets, so two players of different collections have
-- no legal match to play. It goes when `MODE` does — see document 19 — and becomes the format.
CREATE TABLE pvp_queue (
    account_id BIGINT      PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    collection TEXT        NOT NULL,
    since      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Oldest first, within a collection: the pairing query is `... WHERE collection = ? AND account_id
-- <> ? ORDER BY since LIMIT 1`, and waiting longest should mean being served first.
CREATE INDEX pvp_queue_pairing_idx ON pvp_queue (collection, since);

-- An invitation to a named player.
--
-- Separate from the queue rather than a flavour of it. The two answer different questions — "pair
-- me with anybody" and "ask *this* person" — and folding them together would mean a queue row that
-- sometimes names a recipient and sometimes does not.
CREATE TABLE pvp_challenges (
    id           TEXT        PRIMARY KEY,
    from_account BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    to_account   BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- The wager, as a `PvpStake`. Null is not used: `PvpStake.None` is a value, and a nullable
    -- column would be a second way to say the same thing.
    stake        JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- An invitation with no expiry is a notification that never goes away. Both sides need to know
    -- when the offer stops standing rather than finding out on a refusal.
    expires_at   TIMESTAMPTZ NOT NULL,
    -- Set when accepted. The row is kept rather than deleted so the challenger's client can find
    -- out *which* match its invitation turned into, without a second lookup keyed on nothing.
    match_id     TEXT,
    CONSTRAINT pvp_challenges_not_self CHECK (from_account <> to_account)
);

CREATE INDEX pvp_challenges_to_idx ON pvp_challenges (to_account, expires_at);
CREATE INDEX pvp_challenges_from_idx ON pvp_challenges (from_account, expires_at);

-- A live match.
--
-- ### Why this stores the *inputs* and the moves, and not the state
--
-- The obvious column would be the `MatchState` as JSONB. It is rejected for two reasons.
--
-- The first is coupling: `MatchState` is a model type with no `@Serializable` on it, deliberately,
-- and annotating it would make the engine's internal shape a storage format that cannot change
-- without a migration.
--
-- The second is that this shape is **auditable**. A row holding two hands, a seed and a list of
-- moves can be replayed by anyone with the engine, and the replay either produces the recorded
-- outcome or it does not. A row holding a serialised board can only be believed. That is the same
-- argument `matches.transcript_hash` already makes, and it means the final transcript comes out of
-- this table for free rather than being assembled separately.
--
-- The cost is that every read replays up to nine placements. That is roughly forty microseconds of
-- pure arithmetic — `EnginePerformanceTest` measures 4.8 µs per placement — against a network round
-- trip, so it is not a cost worth optimising away.
CREATE TABLE pvp_matches (
    id            TEXT        PRIMARY KEY,
    blue_account  BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    red_account   BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    collection    TEXT        NOT NULL,
    -- `GameRules`, which is `@Serializable` precisely so a match can be recorded with the rules it
    -- was played under.
    rules         JSONB       NOT NULL,
    -- Everything random about this match: the elements, the swap, the per-side Three Open draw.
    -- One integer, and the match is reproducible from it.
    seed          INTEGER     NOT NULL,
    -- The five cards each side brought, **before** the swap. The swap is derived from the seed, so
    -- storing the post-swap hands as well would be storing the same fact twice and inviting the two
    -- copies to disagree.
    blue_hand     JSONB       NOT NULL,
    red_hand      JSONB       NOT NULL,
    first_player  TEXT        NOT NULL,
    -- The placements so far, in order, as `[{"handIndex":n,"position":n}, …]`.
    moves         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    stake         JSONB       NOT NULL,
    status        TEXT        NOT NULL,
    -- When the side to move loses the match by not moving.
    --
    -- **One deadline, two numbers on screen.** The turn timer the game has always had is 30
    -- seconds, and a player who misses it has not necessarily left — a tunnel, a killed app, a
    -- phone that rebooted. So the server enforces 30 seconds *plus* a two-minute grace, and the
    -- client shows the 30 as pressure and the rest as "waiting for them to come back". Enforcing
    -- both server-side would be two states to reason about for one fact: has this player gone.
    turn_deadline TIMESTAMPTZ,
    -- Set when the match ends, whatever ended it. Null while it is live.
    finished_at   TIMESTAMPTZ,
    -- Who walked away, when that is what happened. Null on a match that was played out.
    forfeited_by  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pvp_matches_sides CHECK (blue_account <> red_account),
    CONSTRAINT pvp_matches_status CHECK (status IN ('PLAYING', 'FINISHED', 'FORFEITED', 'ABANDONED')),
    CONSTRAINT pvp_matches_first CHECK (first_player IN ('BLUE', 'RED')),
    CONSTRAINT pvp_matches_forfeited_by CHECK (forfeited_by IS NULL OR forfeited_by IN ('BLUE', 'RED'))
);

-- "Do I have a match in progress?" is the question the client asks at every launch — that is what
-- makes a match survive the app being killed, which mobile does without asking. Both sides are
-- indexed because either of them may be the one asking.
CREATE INDEX pvp_matches_blue_idx ON pvp_matches (blue_account, status);
CREATE INDEX pvp_matches_red_idx ON pvp_matches (red_account, status);

-- The sweep that forfeits abandoned matches reads exactly this.
CREATE INDEX pvp_matches_deadline_idx ON pvp_matches (turn_deadline) WHERE status = 'PLAYING';
