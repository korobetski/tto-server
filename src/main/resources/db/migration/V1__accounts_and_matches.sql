-- The first schema: an account, the character it owns, and every match the server has replayed.
--
-- Written now, and not before, because the Phase 5 design finally settles what these are: the
-- account *is* the character (decision 2 in full), and a match row is a fact the server computed
-- rather than one a client reported. `db/migration/README.md` explains why nothing was guessed
-- earlier — a migration becomes immutable the moment it runs anywhere.

-- Case-insensitive account names without a case-insensitive column type. `citext` would be
-- tidier, but it is an extension, and requiring `CREATE EXTENSION` puts a superuser step between
-- a fresh database and a working one — which is exactly what the unprivileged `tto_app` role
-- exists to avoid. A generated lower-case column with the unique index on it does the same job in
-- SQL every Postgres has.
CREATE TABLE accounts (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username        TEXT        NOT NULL,
    username_key    TEXT        GENERATED ALWAYS AS (lower(username)) STORED,
    -- A bcrypt digest, salt included. Never a password: nothing in this database, in any column,
    -- can be turned back into what the player typed.
    password_hash   TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_username_length CHECK (char_length(username) BETWEEN 3 AND 24)
);

CREATE UNIQUE INDEX accounts_username_key_idx ON accounts (username_key);

-- Sessions hold a **hash** of the bearer token, never the token.
--
-- The distinction is the whole point: a leaked dump of this table cannot be replayed against the
-- server, because what the client sends is the pre-image. It costs one SHA-256 per authenticated
-- request, which is nothing next to the bcrypt verification it replaces on every call after the
-- first.
CREATE TABLE sessions (
    token_hash   TEXT        PRIMARY KEY,
    account_id   BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX sessions_account_idx ON sessions (account_id);
CREATE INDEX sessions_expiry_idx ON sessions (expires_at);

-- The character, as one JSONB document.
--
-- Not 23 columns. `DocumentStore`'s own KDoc on the client makes the argument and it holds here
-- too: nothing reads a profile by column, because the game reads a whole profile or none of it.
-- What a relational schema would buy — partial reads, indexes per field — no caller wants, and it
-- would cost a migration every time the save gains a field. JSONB keeps the document queryable if
-- that ever changes, which a `TEXT` column would not.
--
-- What makes this trustworthy is not the shape but the *writer*: only the server writes here, and
-- only in the same transaction as the match row that justifies the change.
CREATE TABLE characters (
    account_id BIGINT      PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    save       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every match the server replayed and accepted.
--
-- The history is kept rather than only the counters, and that is a deliberate one-way door taken in
-- the open direction: aggregates can always be recomputed from rows, and rows can never be
-- recovered from aggregates. It is also what makes anomaly detection possible later — the design
-- names collusion and seed grinding as problems no signature scheme solves, and both are only
-- visible across a player's history.
CREATE TABLE matches (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id        BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- When the server accepted it, not when the client says it was played: a transcript queued
    -- offline for three days arrives today, and today is the only one of the two the server can
    -- vouch for.
    played_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    opponent_icon_id  TEXT        NOT NULL,
    collection        TEXT        NOT NULL,
    seed              INTEGER     NOT NULL,
    blue              SMALLINT    NOT NULL,
    red               SMALLINT    NOT NULL,
    result            TEXT        NOT NULL,
    mgp               INTEGER     NOT NULL DEFAULT 0,
    xp                INTEGER     NOT NULL DEFAULT 0,
    -- A digest of the transcript, and the reason a match cannot be credited twice.
    --
    -- Without it, replaying one accepted submission would pay out again on every send, and an
    -- offline queue that drains after a lost acknowledgement would do it *by accident* rather than
    -- maliciously. The unique index turns both into a lookup that returns the original verdict.
    transcript_hash   TEXT        NOT NULL,
    CONSTRAINT matches_result CHECK (result IN ('WIN', 'LOSE', 'DRAW')),
    CONSTRAINT matches_score CHECK (blue >= 0 AND red >= 0 AND blue + red = 10)
);

-- Per account, not global: two players may legitimately play the same seed against the same
-- opponent, and a global constraint would refuse the second one's honest match.
CREATE UNIQUE INDEX matches_transcript_idx ON matches (account_id, transcript_hash);

-- The aggregate query is `... WHERE account_id = ? GROUP BY result`, and the recent list is the
-- same predicate ordered by time. One index serves both.
CREATE INDEX matches_account_played_idx ON matches (account_id, played_at DESC);
