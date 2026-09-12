-- Administration: its own credentials, its own sessions, and a record of everything it did.
--
-- ### Why none of this is a column on `accounts`
--
-- `accounts.is_admin BOOLEAN` is the smallest possible change and the wrong one, and
-- `web-platform.md` § "It is not a flag on `accounts`" is the long form of why. A player's bearer
-- token is minted by the game client and lives on phones and desktops; `sessions` has never had a
-- notion of privilege and was never designed to hold one. Put administration on `accounts` and
-- every one of those tokens becomes a potential administrative credential — a stolen phone, or a
-- cross-site scripting hole in a future browser client, escalating to full control of the economy.
-- The privilege would be riding on a credential built for an entirely different threat model.
--
-- So: a separate identity, a separate password, a second factor that is not optional, a separate
-- session table, and a cookie that is not the game's bearer token.
--
-- ### What v1 can do, and what it deliberately cannot
--
-- Consultation, statistics, economy, match disputes. **No sanctions** — no ban, no suspension, no
-- forced rename — because the need behind the console is player support rather than behaviour
-- moderation. That is why `accounts` gains no state column here either: there is no state to hold.
--
-- ### This migration adds a place personal data is written
--
-- `docs/data-inventory.md` gains rows for all three tables. An administrator is a person: a name,
-- a password digest, a shared secret, and a trail of what they did. That last one is the point of
-- the table and also its cost, and the deletion rules below are where that is settled.

-- ---------------------------------------------------------------------------
-- 1. The administrators.
-- ---------------------------------------------------------------------------
--
-- One row per person who may sign in to the console. There is no "role" column and no permission
-- set: with v1's scope, an administrator can do all of it or is not one. Splitting the privilege
-- before there is a second kind of administrator would be inventing a distinction to maintain.
CREATE TABLE admins (
    id               BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Case-insensitive, by the generated-column trick `accounts` uses — same reasoning, and the
    -- same refusal to require a `citext` extension a superuser would have to install.
    username         TEXT        NOT NULL,
    username_key     TEXT        GENERATED ALWAYS AS (lower(username)) STORED,

    -- bcrypt, exactly as `accounts.password_hash`: the same verifier, the same cost, and nothing
    -- here that can be turned back into what was typed.
    password_hash    TEXT        NOT NULL,

    -- The TOTP shared secret, base32, **in the clear** — and that is a decision rather than an
    -- oversight.
    --
    -- A password is only ever *verified*, so a digest is enough. A one-time code has to be
    -- *recomputed*, which needs the secret itself; there is no one-way form that would still
    -- work. Encrypting the column would mean a key held by the same process that reads it, in the
    -- same container, restorable from the same backup — that moves the secret rather than
    -- protecting it, and it would read like a guarantee nobody could rely on.
    --
    -- What the second factor is actually worth is therefore: a stolen *password* is not enough,
    -- and a stolen database is not enough either — it also takes the password, which is not in
    -- there in any usable form. That is the property TOTP is bought for.
    --
    -- NULL until enrolment. The first administrator is created from an environment variable with a
    -- password only, and the secret is generated at the first sign-in and shown once, in the
    -- console. Nothing that writes the log ever holds it: a bootstrap that printed an enrolment
    -- URI would break this repository's rule about secrets on the very first boot.
    totp_secret      TEXT,

    -- Set when a code computed from `totp_secret` has actually been entered. Secret present and
    -- this NULL is enrolment in progress — the state between "here is your QR code" and "I can
    -- read it", in which no sign-in may complete.
    totp_enrolled_at TIMESTAMPTZ,

    -- The last time step this administrator successfully used, as the RFC 6238 counter.
    --
    -- Replay prevention, and it belongs in the database rather than in memory: a code is valid for
    -- thirty seconds, which is long enough for somebody watching a shoulder or a proxy to reuse
    -- it, and an in-process guard would forget every restart and would not be shared if this ever
    -- runs as two instances. Refusing a step less than or equal to this one costs a column.
    totp_last_step   BIGINT,

    -- Attribution, never authentication.
    --
    -- It answers "who did this, as a player" — the administrator's own game account, when they
    -- have one. Nothing authenticates through it: this column being set gives that account no
    -- privilege at all, and this column being NULL takes none away. `SET NULL` because an
    -- administrator who deletes their *player* account is still an administrator.
    account_id       BIGINT      REFERENCES accounts (id) ON DELETE SET NULL,

    -- How an administrator is retired. There is no `DELETE FROM admins` anywhere, and the audit
    -- table's foreign key below is what makes that true rather than merely intended.
    disabled_at      TIMESTAMPTZ,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT admins_username_length CHECK (char_length(username) BETWEEN 3 AND 24),
    -- Enrolled without a secret is a row that cannot be verified against anything, and it is
    -- exactly what a half-finished enrolment would leave behind if the two writes ever came apart.
    CONSTRAINT admins_totp_enrolled CHECK (totp_enrolled_at IS NULL OR totp_secret IS NOT NULL)
);

CREATE UNIQUE INDEX admins_username_key_idx ON admins (username_key);

-- One administrator per player account. Attribution that points two ways is not attribution, and
-- partial because the common case is an administrator with no player account at all — several of
-- those are not a collision.
CREATE UNIQUE INDEX admins_account_idx ON admins (account_id) WHERE account_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. Sessions, which are not the game's sessions.
-- ---------------------------------------------------------------------------
--
-- A **hash** of the cookie value, never the value — the argument `sessions` makes in V1, and it
-- is worth more here: this is the credential that can move somebody's balance, so a leaked dump
-- must not be replayable against the console.
--
-- Two clocks, and both are real. `expires_at` is the ceiling: a session ends at a fixed distance
-- from sign-in whatever happens, so an administrator who signs in on a Monday is not still signed
-- in on Friday. `seen_at` is moved forward by each request, and the policy in the server expires a
-- session that has been idle for far less than the ceiling. The unattended screen is the threat an
-- administration console actually faces, and only the idle clock addresses it.
--
-- **No address column.** `data-inventory.md` says this service stores no IP addresses in any
-- table, and an admin session is not the place to make that sentence false. The reverse proxy's
-- log has the address if an incident ever needs one.
CREATE TABLE admin_sessions (
    token_hash TEXT        PRIMARY KEY,
    admin_id   BIGINT      NOT NULL REFERENCES admins (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- "Sign me out of everywhere", and the sweep that removes what has expired.
CREATE INDEX admin_sessions_admin_idx ON admin_sessions (admin_id);
CREATE INDEX admin_sessions_expiry_idx ON admin_sessions (expires_at);

-- ---------------------------------------------------------------------------
-- 3. The audit.
-- ---------------------------------------------------------------------------
--
-- Who, when, what, on whom, before and after. Written **in the same transaction as the effect** —
-- that is a property of `AdminStore` rather than of this table, and it is the only way the two can
-- be trusted to agree: an audit row that can be missing while the change happened is worth
-- nothing, and one that can exist without the change is worse than nothing.
--
-- ### Append-only, and exactly how far that goes
--
-- Rows are never rewritten, and a trigger below refuses an `UPDATE` rather than leaving that to
-- everyone who ever writes this table. `DELETE` is deliberately *not* refused, for one reason: a
-- player deleting their account cascades through `subject_account`, and the schema cannot tell
-- that cascade apart from a hand-typed `DELETE`. So "append-only" here means **never rewritten**,
-- and the only rows that ever disappear are rows about an account that no longer exists.
--
-- ### Why the subject cascades instead of leaving a tombstone
--
-- The alternative is `ON DELETE SET NULL` plus the username copied into the row, so the trail
-- survives the player. Rejected: `data-inventory.md` promises that deleting an account takes
-- everything belonging to it, and a tombstone naming somebody who asked to be forgotten is that
-- promise quietly broken in the one table nobody would think to look in. Nothing is lost that
-- still means anything — the audit explains changes to a player's data, and both the player and
-- the data are gone.
--
-- It costs nothing in accountability, because in v1 an administrator cannot delete a player
-- account: deletion needs the player's own password.
--
-- ### Failed sign-ins are not in here
--
-- `admin_id` is `NOT NULL`, so this table can only be written by a request that has already
-- authenticated. A failed sign-in names no administrator — often it names no existing username at
-- all — and a table an unauthenticated caller can insert into is a table they can fill. Those
-- attempts belong to the log and to the rate limiter, which is where they are.
CREATE TABLE admin_audit (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- `RESTRICT`, and this is what makes "an administrator is disabled, never deleted" a rule the
    -- database keeps rather than a habit the code has. Dropping the row would take the trail with
    -- it, which is the one thing this table exists not to allow.
    admin_id        BIGINT      NOT NULL REFERENCES admins (id) ON DELETE RESTRICT,

    -- What was done, as a name the server chooses — `SIGN_IN`, `CREDIT_MGP`, and whatever comes
    -- after. No CHECK constraint listing them: the set lives in the server, and a copy of it here
    -- would be a second list that a release can silently contradict, exactly as `bots.band`
    -- explains for its own set. The length limit is the part worth enforcing.
    action          TEXT        NOT NULL,

    -- On whom. NULL for an action about nobody in particular — signing in, for one.
    subject_account BIGINT      REFERENCES accounts (id) ON DELETE CASCADE,

    -- The `applied_operations` key of the effect, when the action had one. It is what ties this
    -- row to the idempotency record of the thing it describes, so a retried credit that returned
    -- the first answer can be told apart from a second credit that really happened.
    operation_id    TEXT,

    -- Why, in the administrator's own words. The console requires one for anything that moves a
    -- balance and prefills it from the match inspector, so a dispute credit arrives already naming
    -- the match it repairs. Nullable because a sign-in has no motive to give.
    reason          TEXT,

    -- What changed, as the server saw it either side of the change — `{"mgp": 500}` before and
    -- `{"mgp": 1000}` after. JSONB rather than two numeric columns because the next action to be
    -- audited will not be about MGP, and a table that grows a column per audited field is one that
    -- needs a migration every time somebody is given a new button.
    --
    -- Both NULL for an action that changed nothing, which is how a read or a sign-in is recorded.
    before          JSONB,
    after           JSONB,

    CONSTRAINT admin_audit_action_length CHECK (char_length(action) BETWEEN 1 AND 64),
    CONSTRAINT admin_audit_reason_length CHECK (reason IS NULL OR char_length(reason) <= 500)
);

-- The two questions asked of this table: "what has this administrator done" and "what has been
-- done to this player". The second is the one a support request starts from, and it is partial
-- because most rows have no subject.
CREATE INDEX admin_audit_admin_idx ON admin_audit (admin_id, at DESC);
CREATE INDEX admin_audit_subject_idx ON admin_audit (subject_account, at DESC)
    WHERE subject_account IS NOT NULL;

-- A rewritten audit row is not an audit row. This refuses the statement rather than silently
-- discarding it — a `DO INSTEAD NOTHING` rule would make an `UPDATE` *look* like it worked, which
-- is the failure mode least likely to be noticed and most likely to matter.
CREATE FUNCTION admin_audit_is_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit is append-only; % refused', TG_OP;
END
$$;

CREATE TRIGGER admin_audit_no_rewrite
    BEFORE UPDATE ON admin_audit
    FOR EACH ROW EXECUTE FUNCTION admin_audit_is_append_only();
