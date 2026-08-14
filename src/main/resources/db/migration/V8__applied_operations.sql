-- What a client has already asked for, so asking twice does it once.
--
-- The economy is moving server-side: opening a booster, buying an offer and selling a card stop
-- being arithmetic the client performs and become requests it makes. That closes the forgery — the
-- client no longer rolls its own pack — and opens a failure the old arrangement never had.
--
-- A client that computed the result knew what happened. A client that *asks* does not, if the
-- answer is lost on the way back. Its only sensible move is to ask again, and without this table
-- asking again opens a second pack, paid for with a second booster the player only owned one of.
-- Replacing a cheat with a way to lose a purchase in a tunnel is not a trade worth making.
--
-- So a request carries the client's own id for the *intent* — not for the attempt — and this row is
-- what makes a repeat return the first answer rather than perform a second one. Card-for-card
-- identical, which also means the reveal animation shows the same pack twice instead of two
-- different ones.
--
-- ### Why the whole response and not a marker
--
-- A marker would let the server say "already done" and nothing else, and a client whose answer was
-- lost would be told its pack was opened without ever learning what was in it. The answer is the
-- part that was lost, so the answer is what is kept.
--
-- ### Why keyed on the account too
--
-- The id is minted by a client and is opaque here; nothing stops two clients choosing the same one.
-- Scoping it to the account means the collision that would matter — one player's operation
-- answering another player's request — cannot be constructed, however the ids are generated.
CREATE TABLE applied_operations (
    account_id   BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    operation_id TEXT        NOT NULL,
    -- The response body as it was sent, replayed verbatim on a repeat.
    response     JSONB       NOT NULL,
    applied_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, operation_id)
);

-- For the pruning job this table will eventually need. An operation is only interesting for as long
-- as a client might still retry it — minutes — but nothing deletes rows yet, and an index added
-- later on a large table is a lock nobody wants at that point.
CREATE INDEX applied_operations_age_idx ON applied_operations (applied_at);
