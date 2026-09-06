-- When each account was last heard from, so the lobby can say whether anybody else is awake.
--
-- ### Why a column on `accounts` and not a `presence` table
--
-- There is exactly one fact per account and it is overwritten, never appended: a row per sighting
-- would be a log nothing reads, swept by a job nothing else needs. The column is nullable because
-- every account that existed before this migration has never been *seen* under this definition,
-- and pretending they were seen at migration time would show a server full of people the moment
-- it deployed.
--
-- ### What it costs to keep current
--
-- One `UPDATE` per account per half-minute at most: the write is guarded by its own `WHERE` on
-- `seen_at`, so the lobby's once-a-second poll does not become a once-a-second write. See
-- `AccountStore.touch`.
ALTER TABLE accounts ADD COLUMN seen_at TIMESTAMPTZ;

-- The only question asked of it is "how many rows are newer than a cutoff", and the cutoff is
-- always recent — so the index is on the column alone and the planner reads the tail of it.
CREATE INDEX accounts_seen_at_idx ON accounts (seen_at);
