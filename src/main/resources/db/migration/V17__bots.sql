-- The accounts this server plays itself.
--
-- ### Why a table beside `accounts` and not a column on it
--
-- A bot **is** an ordinary account: `AccountStore.register` gives it the same `accounts` row and the
-- same `characters` document a person gets, and everything downstream — `saveFor`, `usernameFor`,
-- `MatchRewards.credit`, `PvpStakePolicy`, `PveMatches.playerDeck` — then works on it with no
-- special case anywhere. That property is the whole design, and a `is_bot` column on `accounts`
-- would put the exception in the one table every one of those paths reads.
--
-- It is also more than a flag. A bot carries state a person does not: which band it plays at, and
-- when the director may act for it next. Those belong with the fact that it is a bot, not spread
-- across the account it happens to own.
--
-- ### It is deliberately invisible to clients
--
-- Nothing in the protocol says "this is a bot" — no field on `PvpTable`, none on `PvpMatchView` —
-- so this table is the *only* place the distinction exists. That is a decision rather than an
-- oversight: adding the field costs a `:core` release and a client release, and the thing that
-- actually needs the distinction today is the statistics, which are read here. Revisiting it is a
-- protocol change, not a schema one.
CREATE TABLE bots (
    -- The account, and the primary key: one bot per account, and dropping the account drops the
    -- bot. `ON DELETE CASCADE` rather than `RESTRICT` because `AccountStore.deleteAccount` is the
    -- supported way to retire one, and a row here must not be what makes that fail.
    account_id     BIGINT      PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,

    -- How hard it plays: an `NpcLevel` name, fed to `MatchAiOptions.forLevel`.
    --
    -- Stored per bot rather than read from configuration because the point of having bots at all is
    -- to *measure*, and the first experiment anybody runs is a roster split across bands. A column
    -- makes that an UPDATE; a constant would make it a deploy.
    --
    -- Not a foreign key and not an enum type: the set of bands lives in `:core`, and a CHECK
    -- constraint here would be a second copy of it that a `:core` release could silently
    -- contradict. `BotStore` refuses a name this build does not know, and an unreadable one falls
    -- back rather than wedging the director — see `BotStore.toBot`.
    band           TEXT        NOT NULL,

    -- When the director may next act for this bot.
    --
    -- The cadence is the only thing standing between a bot and the machine speed the rate limits
    -- exist to deny a human: the director drives the referees in-process, so no `RateLimit` plugin
    -- is between it and the board. See `BotPolicy` for what the intervals are and why.
    next_action_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The director's only query is "which bots are due", ordered by how overdue they are, so the index
-- is on that column alone and the planner reads the head of it.
CREATE INDEX bots_due_idx ON bots (next_action_at);
