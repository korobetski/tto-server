-- Which tournament run a match belongs to, if any.
--
-- ### Why the row has to carry it, when the profile already holds the run
--
-- Settlement reads the account's `CAMPAIGN_RUN` to know what to advance. It cannot use that alone
-- to decide whether *this* match counted: a run is opened and closed while matches are live, so a
-- match played in free play and settled a moment after a run opened would be credited as a rung,
-- and the last rung of a run would settle against a run that had already been closed by something
-- else. Both are the same bug — asking the profile a question only the match can answer.
--
-- So the claim is written down when the match is dealt, having been checked against the run at that
-- moment, and settlement compares the two. A row whose `campaign_key` disagrees with the run the
-- profile now holds is settled as an ordinary match, which is what it turned out to be.
--
-- Null for every match outside a run, which is almost all of them, and for every row that existed
-- before this migration.
ALTER TABLE pve_matches ADD COLUMN campaign_key TEXT;

-- The rung this match was dealt for, as `CampaignRun.step`.
--
-- Also a claim checked at deal time, and it is the half that stops a run being climbed out of
-- order: without it a client could open the first rung's opponent four times and finish the ladder
-- against the easiest of its four opponents.
ALTER TABLE pve_matches ADD COLUMN campaign_step INTEGER;

-- Either both or neither. A key with no rung cannot be settled and a rung with no key names
-- nothing, so neither half is allowed to exist on its own.
ALTER TABLE pve_matches
    ADD CONSTRAINT pve_matches_campaign
    CHECK ((campaign_key IS NULL) = (campaign_step IS NULL));
