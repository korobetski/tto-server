-- Email on an account, and the short-lived codes that confirm it or reset a password.
--
-- Two things arrive together here because they are one mechanism used twice: a code is sent to an
-- address, and typing it back proves the person holds that address. Confirmation proves it at
-- registration; a password reset proves it again later, which is the only reason a forgotten
-- password can be recovered at all.
--
-- ### What this buys, stated honestly
--
-- Not protection from one person holding two accounts. An address is free and a second one is
-- free, and plus-addressing means one inbox answers for an unbounded number of them. What it buys
-- is a recovery path that does not involve a human reading a support mailbox, and a small cost per
-- account. The measure that actually catches collusion is after the fact and lives in the match
-- rows, not here.

ALTER TABLE accounts
    -- Nullable, and it stays nullable forever: see the grandfathering below. A registration is
    -- refused without one by the route, which is where the rule belongs — a NOT NULL here would
    -- also refuse every row that predates this file.
    ADD COLUMN email             TEXT,
    -- The same trick as `username_key`: matched case-insensitively, because nobody thinks of
    -- Kuplu@example.org and kuplu@example.org as two addresses, and letting them be two accounts
    -- would hand a farmer a free doubling.
    ADD COLUMN email_key         TEXT GENERATED ALWAYS AS (lower(email)) STORED,
    -- When it was confirmed, not whether. A timestamp answers "is it confirmed" as well as a
    -- boolean does and also answers "when", which is the question asked when something looks wrong.
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    CONSTRAINT accounts_email_length CHECK (email IS NULL OR char_length(email) BETWEEN 6 AND 254);

-- Partial, so the unbounded number of rows with no address do not collide with each other.
CREATE UNIQUE INDEX accounts_email_key_idx ON accounts (email_key) WHERE email_key IS NOT NULL;

-- Every account that already exists is treated as confirmed.
--
-- The alternative is to lock out everyone who registered before this deploy, which is punishing
-- players for the server having changed its mind. They have no address on file and therefore no way
-- to confirm one, so "unconfirmed" would be a permanent state with no exit — the sort of migration
-- that turns into a support queue.
--
-- The consequence is a state that reads oddly and is meant to: `email IS NULL` with
-- `email_verified_at` set means *this account predates the requirement*. If those accounts should
-- be made to supply an address after all, that is one UPDATE and a decision someone takes on
-- purpose, which is the right shape for it.
UPDATE accounts SET email_verified_at = created_at WHERE email IS NULL;

-- One live code per account per purpose.
--
-- The primary key is what enforces it, and that is the design rather than an incidental: asking for
-- a new code must *replace* the old one. Keeping several alive would multiply the guessing surface
-- by however many times somebody pressed the button, which is the opposite of what the button is
-- for.
CREATE TABLE account_codes (
    account_id BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- 'VERIFY_EMAIL' or 'RESET_PASSWORD'. Text rather than an enum type: a Postgres enum needs its
    -- own migration to gain a value, and this column will gain values.
    purpose    TEXT        NOT NULL,
    -- A digest, never the code — the same rule as `sessions.token_hash`, and worth stating what it
    -- does and does not buy. Six digits is a million possibilities, so a digest of one falls to a
    -- laptop in seconds; hashing does not make a stolen dump safe. What it does is keep the code out
    -- of the places a value leaks *without* the database being stolen: a backup, a log line, a
    -- support query pasted into a chat window. The defence that actually counts is [attempts] and
    -- [expires_at] below, which make the guess useless before it can be made.
    code_hash  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    -- Counted up, and the code dies at the ceiling. Without this a six-digit code is guessable in
    -- an afternoon by anyone willing to send a million requests; with it the attacker gets five and
    -- then has to make the server send a new one, which is separately rate-limited.
    attempts   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, purpose)
);

-- For the sweep that deletes expired codes. Not a foreign-key index: rows are found by account and
-- purpose everywhere except there.
CREATE INDEX account_codes_expiry_idx ON account_codes (expires_at);
