-- When each side first came to the board, and how long a paired match waits for them.
--
-- ### The bug this closes
--
-- A player may host a table and go play something else while they wait — the lobby is polled from
-- anywhere in the client, so hosting costs nothing and there is no reason not to. When an opponent
-- joins, the match was created with a 150-second turn clock already running against a randomly
-- chosen first mover. Half the time that was the host, who was on another board and never saw the
-- notification in time; they were forfeited at the maximum stake for a match they never looked at.
--
-- The clock now starts when **both** sides have opened the board (`PvpRoutes.attend`), so no turn
-- can run out against somebody who has not yet been shown it.
--
-- ### Why three nullable columns and not one "started" flag
--
-- The two sightings are what make the rule symmetric, and they are worth keeping after the fact:
-- "who was late" is the only evidence available when a player disputes an abandonment. The pairing
-- deadline is the bound on the wait — without it a match nobody attends is a match that never ends
-- and a stake nobody gets back. It is NULLed the moment the turn clock takes over, which is what
-- keeps a single wire `deadline` field unambiguous. See `PvpMatchRow.wireFor`.
--
-- Additive, and every column is nullable: matches already in flight read as unattended, and the
-- first poll from either side attends them. Their `turn_deadline` is already set, so they keep the
-- behaviour they were created under rather than being frozen by a rule they predate.
ALTER TABLE pvp_matches ADD COLUMN blue_seen_at TIMESTAMPTZ;
ALTER TABLE pvp_matches ADD COLUMN red_seen_at TIMESTAMPTZ;
ALTER TABLE pvp_matches ADD COLUMN pairing_deadline TIMESTAMPTZ;

-- The mirror of `pvp_matches_deadline_idx`: the sweep asks for live matches past their pairing
-- deadline, and only live matches can have one, so the partial index is the whole working set.
CREATE INDEX pvp_matches_pairing_idx ON pvp_matches (pairing_deadline) WHERE status = 'PLAYING';
