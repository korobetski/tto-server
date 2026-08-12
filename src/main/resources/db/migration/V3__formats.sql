-- `collection` becomes `format`, in the three places it was stored.
--
-- Document 19's `MODE` is gone from `:core`: a profile is no longer confined to one of two card
-- tables, and what a match may be played with is a **format** — a named list of admitted blocks
-- and a rule pool — resolved from `formats.json`. The wire types moved with it (`formatId` in
-- `MatchTranscript`, `VerifiedMatch` and `PvpMatchView`), so these columns were the last place the
-- old idea survived.
--
-- A rename and not a new column: the two are the same fact, one row per match, and carrying both
-- would invite them to disagree. The values are backfilled by mapping each old collection onto the
-- format that admits exactly its block. That mapping used to be a helper in `FormatCatalog`,
-- deleted along with the `CardCollection` enum it took; this is the last place it is performed.

-- FF14 is block 1, FF8 is block 2. See `formats.json`; these two ids are authored there.
UPDATE matches SET collection = CASE collection WHEN 'FF8' THEN 'ff8' ELSE 'ff14' END;
UPDATE pvp_queue SET collection = CASE collection WHEN 'FF8' THEN 'ff8' ELSE 'ff14' END;
UPDATE pvp_matches SET collection = CASE collection WHEN 'FF8' THEN 'ff8' ELSE 'ff14' END;

ALTER TABLE matches RENAME COLUMN collection TO format;
ALTER TABLE pvp_queue RENAME COLUMN collection TO format;
ALTER TABLE pvp_matches RENAME COLUMN collection TO format;

-- The pairing index names the column, so it is rebuilt rather than renamed: an index over a
-- renamed column keeps working, but keeps the old name, and a `pvp_queue_pairing_idx` that reads
-- `(collection, since)` in `\d` would be the one place a reader is still told the old story.
DROP INDEX pvp_queue_pairing_idx;
CREATE INDEX pvp_queue_pairing_idx ON pvp_queue (format, since);
