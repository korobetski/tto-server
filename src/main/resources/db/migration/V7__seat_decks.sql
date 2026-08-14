-- Which deck the waiting player is bringing.
--
-- Until now the referee dealt both hands with `PveMatches.playerDeck`, which takes the first
-- complete deck in the save and has no way of being told otherwise. A player with an aces deck and
-- a themed one could choose between them against an NPC and not against a person, which is exactly
-- backwards: the deck matters more when the opponent chose theirs too.
--
-- Only the *waiting* side needs storing. A joiner and an accepter name their deck in the request
-- that opens the match, and it goes straight into the deal — there is no moment between the two at
-- which it would have to be remembered. A host and a challenger, by contrast, state a deck now and
-- play it whenever somebody turns up.
--
-- -1 is "not stated", and is the default for the same reason `ANY_DECK` is: slot 0 and no choice at
-- all are different requests, and every row that predates this column made no choice. They keep the
-- behaviour they have always had, because that is what the referee still does with -1.
ALTER TABLE pvp_tables     ADD COLUMN host_deck INT NOT NULL DEFAULT -1;
ALTER TABLE pvp_challenges ADD COLUMN from_deck INT NOT NULL DEFAULT -1;
