-- Seeds this server issued, so that a client cannot choose its own.
--
-- `MatchTranscript.seed` is the whole of a match's randomness: the opponent's hand, the roulette,
-- the coin flip and every one of the opponent's moves come out of it. A client that picks the seed
-- therefore picks the deal — play it, look at what the opponent was dealt, and if it is a bad hand
-- start again with another number. The transcript that finally arrives is a real match, honestly
-- played, and nothing in the replay can tell it apart from one that was not auditioned.
--
-- The comment on `MatchTranscript` used to call this "seed grinding" and judge the gain small. It
-- is not small. It is unbounded and it is free.
--
-- ### Why a stock of seeds rather than one per match
--
-- Because asking the server for a seed at match start would end offline play, and that is a
-- property this design has paid for elsewhere: a transcript is unforgeable as a *game*, which is
-- precisely what lets a match played on a plane be queued and credited later. A stock keeps that —
-- the client plays from seeds it already holds — while still leaving it unable to mint one.
--
-- ### `voided_at`, and why skipping ahead has to be allowed
--
-- A stock reintroduces a smaller search: fifty unspent seeds is fifty deals to choose between. So
-- crediting a match **voids every ticket issued to that account before the one it used**.
--
-- It has to be voiding rather than a strict in-order rule, because abandoning a match is ordinary —
-- the forfeit counter exists for it — and a client that had locally consumed ticket 1 and then
-- submitted ticket 2 would otherwise be stuck for ever behind a ticket it will never spend. So
-- skipping ahead works, and costs the seeds it skipped.
CREATE TABLE match_tickets (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    seed       INTEGER     NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Set when a match was credited against it. A ticket is good exactly once.
    spent_at   TIMESTAMPTZ,
    -- Set when a *later* ticket was spent first. Distinguished from `spent_at` so that "how did
    -- this account use its seeds" stays answerable — a run of voided tickets is what auditioning
    -- deals looks like from here.
    voided_at  TIMESTAMPTZ
);

-- The lookup every credit makes: is this seed one I issued to this account, and is it still good?
-- Unique on the pair, so the same seed cannot be issued twice to one account and be spendable
-- twice — the seeds are random `INTEGER`s and a collision is unlikely rather than impossible.
CREATE UNIQUE INDEX match_tickets_seed_idx ON match_tickets (account_id, seed);

-- For counting what an account still holds, which is what caps issuance.
CREATE INDEX match_tickets_unspent_idx ON match_tickets (account_id)
    WHERE spent_at IS NULL AND voided_at IS NULL;
