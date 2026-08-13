-- An invitation states its terms, as a table does.
--
-- `pvp_challenges` has carried a `stake` since V2 and nothing else about the match, so accepting one
-- always opened the default format under whatever the roulette drew — see the `accept` branch of
-- `PvpReferee`. That made the two ways into a match unequal for no reason a player could see: you
-- could name your rules to a stranger browsing the lobby and not to somebody you invited by name.
--
-- The columns mirror `pvp_tables` exactly, and deliberately: both hold a `PvpTableRequest`, and the
-- server validates them through the same function. A second shape here would be a second set of
-- rules about which rules are legal.
ALTER TABLE pvp_challenges
    ADD COLUMN format   TEXT,
    ADD COLUMN rules    JSONB,
    ADD COLUMN roulette BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfilled to what an existing invitation *meant*: the widest format, rules drawn on open. That
-- is precisely what `accept` did with them, so no standing invitation changes behaviour by being
-- migrated — it simply now says out loud what it was always going to do.
UPDATE pvp_challenges SET format = 'free-play', rules = '{}'::jsonb, roulette = TRUE
WHERE format IS NULL;

ALTER TABLE pvp_challenges
    ALTER COLUMN format SET NOT NULL,
    ALTER COLUMN rules  SET NOT NULL;
