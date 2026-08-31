-- The auction house: lots players open, and every bid placed against them.
--
-- Two tables, and the split between them is the split between a fact and its cache. `auction_bids`
-- is the ledger — one row per bid, kept for ever, never updated except to record what happened to
-- the money it committed. `auction_lots` carries the same information summarised, because every
-- screen in the house reads "what does this stand at" and none of them wants to aggregate a
-- history to find out.
--
-- ### The money is held, not promised
--
-- A bid **debits** the bidder's purse the moment it is placed — the amount plus the buyer's fee —
-- and the debit is given back in full the moment somebody outbids it. `AuctionRules.validateBid`
-- argues why at length: a promise to pay is a promise an empty account can make, and an auction
-- where the winner can decline to pay is an auction a single malicious account can shut down for
-- everybody. Holding the money instead makes the whole class of "the winner cannot pay" unreachable
-- rather than handled, which is the difference between a rule and an arbitration procedure.
--
-- That decision is what the columns below are for. `auction_bids.fee` and `auction_bids.amount`
-- record what was actually taken, so a refund gives back what was taken and not what today's fee
-- rate would compute; `refunded_at` and `settled_at` record which of the two ends a held bid came
-- to. A bid row is in exactly one of three states — held, refunded, settled — and the partial
-- unique index at the bottom of this file makes "at most one held bid per lot" a property of the
-- database rather than a property of the code that happens to write it.
--
-- ### A lot outlives the accounts in it
--
-- Every other table in this schema references an account `ON DELETE CASCADE`, and
-- `AccountStore.deleteAccount` is one `DELETE` because of it. These two cannot follow that rule,
-- and the reason is that a lot is not one account's property: there is somebody else's money held
-- against it and somebody else's card inside it. Cascading a seller away would destroy the card a
-- buyer had already paid for, and cascading a bidder away would leave a lot recording a top bid
-- with no hold behind it — money invented, which is the one failure this schema exists to prevent.
--
-- So both references are `ON DELETE SET NULL`, and both columns are nullable. A null seller or a
-- null bidder means "that account is gone", and the deletion path settles the lot **before** the
-- delete lands so that the null is only ever seen on a lot that has already ended. See
-- `AuctionStore.closeOutOn` for who gets what: the surviving side is paid what they were owed, and
-- the departing side's half — the seller's proceeds, or the buyer's card — is destroyed. Destroyed
-- and not redistributed, deliberately: every other answer either invents MGP or hands somebody a
-- card nobody sold them.

-- ### What this does not attempt
--
-- Nothing here stops two accounts one person owns from trading a card between them at an agreed
-- price. Cards are real and MGP is real and no rule above is broken; what the house does is make
-- it *cost* — 5% of the reserve to open the lot, 3% of the price to win it — and cap the price at
-- a multiple of what the card is worth, so the transfer per laundered lot is bounded. See
-- `AuctionRules.ceilingPriceOf`. The measure that actually catches it is after the fact and reads
-- these two tables together, which is the other reason the bid history is kept whole.

-- ---------------------------------------------------------------------------
-- 1. Lots.
-- ---------------------------------------------------------------------------
--
-- The card is named by id and **is not in the seller's collection while the lot runs**: listing
-- takes the copy out of the profile, and the lot row is where it lives until it settles. That is
-- what makes selling one card to two people impossible without any locking beyond this row, and it
-- is why `status` distinguishes `UNSOLD` and `CANCELLED` from each other — both hand the card back,
-- but only one of them happened after somebody's money was involved.
CREATE TABLE auction_lots (
    id              TEXT        PRIMARY KEY,
    -- Nullable, and `SET NULL` rather than `CASCADE`. See "A lot outlives the accounts in it".
    seller_account  BIGINT      REFERENCES accounts (id) ON DELETE SET NULL,
    card_id         INTEGER     NOT NULL,
    start_price     INTEGER     NOT NULL,
    -- The seller's own number, and nobody else's business: publishing it would tell every bidder
    -- exactly what to bid and turn a reserve into a fixed price. `AuctionLot` on the wire carries
    -- `reserveMet` for everybody and `reservePrice` only for the seller.
    reserve_price   INTEGER     NOT NULL,
    -- What the seller was actually charged to open it, and not a rate to re-apply later. The fee is
    -- taken once, at listing, and never refunded — so the only question anybody asks of it
    -- afterwards is "how much was it", which a stored figure answers and a rate does not once the
    -- rate has been tuned.
    listing_fee     INTEGER     NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- **This moves.** A bid inside the closing window pushes it out — see
    -- `AuctionRules.extendedEnd`, which is the sniping answer — so it is the end as it currently
    -- stands and not the end the seller chose.
    ends_at         TIMESTAMPTZ NOT NULL,
    -- Set when the lot ends short of its reserve and the seller is asked whether to take the bid
    -- anyway. Bounded, and the bound is the point: the bidder's money stays held for exactly this
    -- long, so a window a seller could leave open indefinitely would be a way to freeze somebody
    -- else's purse. The sweep refuses the sale on their behalf when it lapses.
    decision_end    TIMESTAMPTZ,
    -- The standing bid, summarised from the ledger. Authoritative for reading; the held row in
    -- `auction_bids` is authoritative for money.
    top_bid         INTEGER,
    top_bidder      BIGINT      REFERENCES accounts (id) ON DELETE SET NULL,
    bid_count       INTEGER     NOT NULL DEFAULT 0,
    -- What it went for, once it is `SOLD`. Kept separately from `top_bid` because a lot that ended
    -- `UNSOLD` also has a top bid, and a column that sometimes means "sold for" and sometimes means
    -- "was offered" is the kind of column a payout query gets wrong once.
    sold_for        INTEGER,
    settled_at      TIMESTAMPTZ,
    CONSTRAINT auction_lots_status CHECK (
        status IN ('OPEN', 'AWAITING_SELLER', 'SOLD', 'UNSOLD', 'CANCELLED')
    ),
    -- The floor and the ceiling are `AuctionRules`' business, because they depend on the card table
    -- and this database has never seen a card. What is checkable here is the ordering the rules
    -- rest on, and it is checked here because it is the one a fee dodge would break: the listing
    -- fee is a fraction of the reserve, so a reserve under the start price is a seller paying 5 MGP
    -- to run a lot they intend to sell for fifty times that.
    CONSTRAINT auction_lots_prices CHECK (start_price > 0 AND reserve_price >= start_price),
    -- Shill bidding, refused by the schema. The route refuses it too, with a reason the seller can
    -- read; this is what makes it impossible rather than merely refused.
    CONSTRAINT auction_lots_no_self_bid CHECK (top_bidder IS NULL OR top_bidder <> seller_account),
    -- A bidder who deleted their account leaves `top_bidder` null on a lot that still records what
    -- they bid, which is the correct outcome for a finished lot and why this is not an equivalence.
    CONSTRAINT auction_lots_bidder CHECK (top_bid IS NOT NULL OR top_bidder IS NULL),
    -- A finished lot may have lost either party; a running one has both. This is what stops a
    -- `SET NULL` from ever being visible on an open lot — the deletion path has to settle first,
    -- and if it ever forgets to, the `DELETE` fails loudly instead of stranding a live lot with
    -- nobody behind it.
    CONSTRAINT auction_lots_live_seller CHECK (
        seller_account IS NOT NULL OR status IN ('SOLD', 'UNSOLD', 'CANCELLED')
    )
);

-- What the sweep reads, and what the browse list orders by — the same predicate and the same
-- column, so one partial index serves both and stays the size of the open house rather than the
-- size of its history. The V4 claim index is built on the same principle.
CREATE INDEX auction_lots_open_idx ON auction_lots (ends_at) WHERE status = 'OPEN';

-- The other half of the sweep: lots waiting on a seller who has stopped answering.
CREATE INDEX auction_lots_decision_idx ON auction_lots (decision_end)
    WHERE status = 'AWAITING_SELLER';

-- "My lots", and the count that `AuctionPolicy.maxOpenLots` is compared against.
CREATE INDEX auction_lots_seller_idx ON auction_lots (seller_account, created_at DESC);

-- Browsing for one card, which is what a player who wants a specific card does. Partial for the
-- same reason as the first: nobody browses a finished lot.
CREATE INDEX auction_lots_card_idx ON auction_lots (card_id, ends_at) WHERE status = 'OPEN';

-- ---------------------------------------------------------------------------
-- 2. Bids.
-- ---------------------------------------------------------------------------
--
-- Every bid ever placed, including the ones that were beaten minutes later. Keeping the losers is
-- the same one-way door V1 takes with `matches`: aggregates are recoverable from rows and rows are
-- not recoverable from aggregates, and collusion between two accounts is only visible across a
-- history — a pair who meet on lot after lot look exactly like two strangers on any single one.
--
-- Rows are inserted and then only ever stamped. Nothing rewrites an amount.
CREATE TABLE auction_bids (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lot_id          TEXT        NOT NULL REFERENCES auction_lots (id) ON DELETE CASCADE,
    -- Nullable for the reason `auction_lots.seller_account` is, and with one extra consequence:
    -- a *losing* bid whose bidder later deletes their account keeps its row, so the history two
    -- colluding accounts leave behind survives one of them leaving.
    bidder_account  BIGINT      REFERENCES accounts (id) ON DELETE SET NULL,
    amount          INTEGER     NOT NULL,
    -- The buyer's fee as it was computed at the time, held alongside the bid and taken with it. A
    -- refund gives back `amount + fee` from this row, so tuning the rate can never make a refund
    -- differ from what was actually debited.
    fee             INTEGER     NOT NULL,
    placed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Outbid, or the lot ended without selling: the whole hold went back.
    refunded_at     TIMESTAMPTZ,
    -- Won: the hold was spent, the card went to the bidder and the proceeds to the seller.
    settled_at      TIMESTAMPTZ,
    CONSTRAINT auction_bids_amount CHECK (amount > 0 AND fee > 0),
    CONSTRAINT auction_bids_one_ending CHECK (refunded_at IS NULL OR settled_at IS NULL)
);

-- **At most one live hold per lot**, enforced here rather than trusted.
--
-- This is the invariant the whole escrow rests on: two held bids on one lot would be two purses
-- debited for a card only one of them can win, and it is exactly what a bidder racing themselves
-- with two rapid taps would produce if the route's idempotency key ever failed to catch it. A
-- unique index cannot be raced. The insert of a new bid and the refund stamp on the old one are one
-- statement pair in one transaction, so the correct sequence never touches this and an incorrect
-- one cannot commit.
CREATE UNIQUE INDEX auction_bids_one_hold ON auction_bids (lot_id)
    WHERE refunded_at IS NULL AND settled_at IS NULL;

-- The bid history of one lot, newest first: what the lot pane shows and what an audit reads.
CREATE INDEX auction_bids_lot_idx ON auction_bids (lot_id, placed_at DESC);

-- "My bids" — every lot this account has money on, or had.
CREATE INDEX auction_bids_bidder_idx ON auction_bids (bidder_account, placed_at DESC);
