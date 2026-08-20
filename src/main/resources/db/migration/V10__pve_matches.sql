-- Player versus environment, refereed.
--
-- Written when the solo match stopped being something the client played and reported. It used to
-- submit a `MatchTranscript` and the server replayed it — a design that really did make an offline
-- match checkable, and that is being retired for a reason worth recording here because this table
-- is what replaces it.
--
-- To replay a transcript the server had to *derive* every one of the opponent's moves. That means
-- the client had to be running the same AI from the same seed, which means it also held the
-- opponent's five cards and knew every move they were going to make from the first placement. A
-- modified client played in perfect information and left no trace, because the match really did
-- happen exactly as claimed. Holding the match here is what closes that, and it closes something
-- the transcript never addressed at all: what the *opponent* may see. Under All Open and Three Open
-- a program reading its opponent's hand off a state it happens to hold is not obeying the rule.

-- A live or finished match against an opponent.
--
-- ### Why this stores the inputs and the moves, and not the state
--
-- The same argument `pvp_matches` makes, and it has not weakened. The obvious column would be the
-- `MatchState` as JSONB; it is rejected because `MatchState` is a model type with no
-- `@Serializable` on it, deliberately, and annotating it would make the engine's internal shape a
-- storage format that cannot change without a migration. And because a row holding two hands, a
-- seed and a list of moves can be *replayed* by anyone with the engine, where a row holding a
-- serialised board can only be believed.
--
-- ### `moves` holds both sides, which `pvp_matches` did not have to decide
--
-- In a match between two people every placement is somebody's request, so recording them all was
-- never a choice. Here the opponent is a program the server itself runs, and there is a real
-- temptation to store only the player's moves and re-derive the rest from `seed`.
--
-- That is exactly what the transcript did, and it is what made `MatchAi` part of the replay: a
-- cleverer opponent would silently rewrite the moves of every match already in this table,
-- including the ones a player is in the middle of. So the opponent's placements are written down
-- next to the player's, in one ordered list, and a row means the same thing tomorrow as today.
--
-- The cost is bytes. The benefit is that the AI can be retuned, or replaced, with no migration and
-- no protocol version — see `PveMatchRow`.
CREATE TABLE pve_matches (
    id            TEXT        PRIMARY KEY,
    account_id    BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    format_id     TEXT        NOT NULL,
    -- The opponent, by `Npc.iconId` and not by `Npc.id` — the latter is not unique. `npcs.json`
    -- holds the name, the portrait and the payout, and both ends have it.
    opponent_icon TEXT        NOT NULL,
    -- `GameRules`, which is `@Serializable` precisely so a match can be recorded with the rules it
    -- was played under. The roulette has already been drawn by the time a row exists.
    rules         JSONB       NOT NULL,
    -- Everything random about this match: the elements, the swap, the per-side Three Open draw,
    -- and every Sudden Death rematch after the first board. One integer.
    seed          INTEGER     NOT NULL,
    -- The five cards each side brought, **before** the swap, as `[id, …]`.
    --
    -- Stored rather than re-dealt, which is the one place this table differs from `pvp_matches` in
    -- substance. `PveMatches.assemble` deals the player's hand from their *profile*, and under
    -- `RULE_RANDOM` from the whole collection rather than from the deck — and a collection changes
    -- as cards are won. Re-dealing a week-old row against the live profile would deal a different
    -- match. The swap is still derived from `seed`, so the post-swap hands are not stored: that
    -- would be one fact twice, with two copies free to disagree.
    blue_hand     JSONB       NOT NULL,
    red_hand      JSONB       NOT NULL,
    first_player  TEXT        NOT NULL,
    -- Every placement, both sides, in order, as `[{"handIndex":n,"position":n}, …]`.
    moves         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status        TEXT        NOT NULL,
    -- What the match paid, as a `RewardSummary`, written at settlement. Null while it is live.
    --
    -- Not derivable, which is why it is a column: the payout rolls a random top-up, rolls the drop
    -- table and spends whatever boons the profile happened to be holding, so replaying the match
    -- cannot reproduce it. `pvp_matches` learned this the hard way — see `V6__match_payout.sql`,
    -- which added the same thing after the fact.
    reward        JSONB,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pve_matches_status CHECK (status IN ('PLAYING', 'FINISHED', 'ABANDONED')),
    CONSTRAINT pve_matches_first CHECK (first_player IN ('BLUE', 'RED'))
);

-- There is no `turn_deadline`, and the absence is the feature.
--
-- `pvp_matches` has one because a person is waiting: a player who walks away has to lose eventually
-- or the other one is stuck forever. Nothing is waiting here. A program does not mind an hour, so
-- there is no clock, no forfeit, and no state a dropped connection can push a match into.
--
-- That is the whole of "a lost connection must not be an abandon". It is not a feature that had to
-- be built; it is a column that had to not exist.

-- At most one live match per account.
--
-- A partial unique index rather than a check in the referee, for the reason `pvp_queue`'s primary
-- key is a primary key: two taps a millisecond apart both pass a check-then-insert, and the player
-- ends up with two live matches and no way to say which one "resume" means. The database is where
-- that question gets one answer.
--
-- It is also what makes resuming cheap. `GET /pve/matches/active` is a lookup on this index, not a
-- scan with `ORDER BY created_at DESC LIMIT 1` — so it cannot quietly return the wrong one of two
-- rows that should never have both existed.
CREATE UNIQUE INDEX pve_matches_live_idx ON pve_matches (account_id) WHERE status = 'PLAYING';

-- The match history a profile screen reads, newest first.
CREATE INDEX pve_matches_account_idx ON pve_matches (account_id, created_at DESC);
