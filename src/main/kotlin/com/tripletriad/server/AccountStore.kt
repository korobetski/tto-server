package com.tripletriad.server

import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchResult
import com.tripletriad.protocol.PlayerState
import com.tripletriad.protocol.PlayerStats
import com.tripletriad.protocol.SeedTickets
import com.tripletriad.protocol.VerifiedMatch
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * Everything that is stored about a player, over plain JDBC.
 *
 * ### Why JDBC and not Exposed, jOOQ or a mapper
 *
 * Because there are six queries. An ORM earns its place when the mapping is the work; here the
 * mapping is one JSONB column and eight scalars, and a query DSL would add a dependency, a
 * generated schema and a layer of indirection between this file and the SQL it runs — for a saving
 * measured in a few dozen lines. The moment there are joins worth naming, this reasoning expires.
 *
 * ### Transactions are explicit, because the pool made them so
 *
 * `Database.pool` sets `isAutoCommit = false` deliberately: crediting a match is an insert *and* an
 * update, and a crash between them must leave neither. Every method here therefore commits or rolls
 * back exactly once, and [transaction] is the only place that decides which.
 */
// TooManyFunctions counts queries here, which is what a data-access class is made of. The rule is
// aimed at a class doing too many *things*; this one does one thing — read and write what is stored
// about a player — and splitting it by table would put `openSession` and `accountForToken` behind a
// second object for no reason other than a count.
@Suppress("TooManyFunctions")
class AccountStore(
    private val dataSource: DataSource,
    private val json: Json = SaveJson,
) {

    // ---- Accounts ---------------------------------------------------------

    /**
     * Creates an account and the character it owns, in one transaction.
     *
     * The character is created **here**, not on first play, and that is what makes the account and
     * the profile the same thing: there is no window in which an account exists with nothing to
     * play, and no code path that has to invent a profile for an account that somehow lacks one.
     *
     * @return the new account's id, or null if the username is taken.
     */
    fun register(username: String, passwordHash: String, save: GameSave): Long? =
        transaction { db ->
            val accountId = try {
                db.prepareStatement(
                    "INSERT INTO accounts (username, password_hash) VALUES (?, ?) RETURNING id",
                ).use { statement ->
                    statement.setString(1, username)
                    statement.setString(2, passwordHash)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getLong(1) else null
                    }
                }
            } catch (failure: SQLException) {
                // 23505 is unique_violation, and it is the SQL **standard's** code rather than
                // Postgres's own — which is why this catches `SQLException` rather than
                // `PSQLException`. The driver is a runtime dependency here; compiling against its
                // exception type would put the whole of it on the compile classpath to read one
                // field JDBC already exposes. Narrowed to this one code deliberately: any other
                // constraint failing here is a bug that should surface as a 500, not be reported
                // to the player as "that name is taken".
                if (failure.sqlState == UNIQUE_VIOLATION) return@transaction null else throw failure
            } ?: return@transaction null

            db.prepareStatement(
                "INSERT INTO characters (account_id, save) VALUES (?, ?::jsonb)",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setString(2, json.encodeToString(save))
                statement.executeUpdate()
            }
            accountId
        }

    /** The stored digest and id for [username], or null if there is no such account. */
    fun credentialsFor(username: String): StoredCredentials? = transaction { db ->
        db.prepareStatement(
            "SELECT id, password_hash FROM accounts WHERE username_key = lower(?)",
        ).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    StoredCredentials(rows.getLong("id"), rows.getString("password_hash"))
                } else {
                    null
                }
            }
        }
    }

    /**
     * The account behind [username], matched case-insensitively as sign-in matches it.
     *
     * Added for the PvP challenge, which is the first thing in this server that looks a player up
     * by name for a reason other than authenticating them. Case-insensitive because that is what
     * `username_key` exists for, and because a player typing a friend's name from memory should not
     * have to remember its capitalisation.
     */
    fun accountIdForUsername(username: String): Long? = transaction { db ->
        db.prepareStatement("SELECT id FROM accounts WHERE username_key = lower(?)")
            .use { statement ->
                statement.setString(1, username)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
    }

    /** The digest to check a password against, by account rather than by name. */
    fun passwordHashFor(accountId: Long): String? = transaction { db ->
        db.prepareStatement("SELECT password_hash FROM accounts WHERE id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    /**
     * Deletes an account and everything that belongs to it.
     *
     * ### One statement, because the schema was built for this
     *
     * Every table that references an account does so `ON DELETE CASCADE` — the character, the
     * matches, the sessions, the applied operations, the seed tickets, the tables and invitations
     * and PvP rows. So this is one `DELETE` rather than a list of them, and a table added later
     * inherits the behaviour by declaring its foreign key properly instead of by being remembered
     * here. `docs/data-inventory.md` states the property; this is what relies on it.
     *
     * ### What it does not do
     *
     * It does not anonymise finished matches for the player's **opponents**. A PvP row names both
     * accounts, so deleting one cascades the shared row away and takes it out of the other player's
     * history too. That is a real consequence and the honest one to accept here: the alternative is
     * keeping a record of somebody who asked to be forgotten, in order to preserve somebody else's
     * statistics.
     *
     * @return false when there was no such account, which is not an error — a repeated request from
     *   a client that lost the first answer has still achieved what it asked for.
     */
    fun deleteAccount(accountId: Long): Boolean = transaction { db ->
        db.prepareStatement("DELETE FROM accounts WHERE id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate() > 0
        }
    }

    /** The name an account goes by, for showing a player who they are up against. */
    fun usernameFor(accountId: Long): String? = transaction { db ->
        db.prepareStatement("SELECT username FROM accounts WHERE id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    /** Replaces a password digest, when [PasswordHasher.needsRehash] says the cost has risen. */
    fun updatePasswordHash(accountId: Long, passwordHash: String) = transaction { db ->
        db.prepareStatement("UPDATE accounts SET password_hash = ? WHERE id = ?").use { statement ->
            statement.setString(1, passwordHash)
            statement.setLong(2, accountId)
            statement.executeUpdate()
        }
        Unit
    }

    // ---- Sessions ---------------------------------------------------------

    /**
     * Records a session for [tokenFingerprint], and clears out this account's expired ones.
     *
     * The cleanup rides along rather than running on a timer: sessions expire on a schedule nobody
     * watches, and a sweep attached to the one event that creates them keeps the table bounded
     * without a scheduler to operate. It is scoped to this account so a busy server does not turn
     * every sign-in into a full-table delete.
     */
    // The numbers are JDBC parameter positions, which is the one place a bare integer is not a
    // magic number: it is the ordinal of the `?` it fills, and naming it would only add a constant
    // whose value has to be read off the SQL anyway. Same below in creditMatch.
    @Suppress("MagicNumber")
    fun openSession(accountId: Long, tokenFingerprint: String, expiresAt: Long) =
        transaction { db ->
            db.prepareStatement("DELETE FROM sessions WHERE account_id = ? AND expires_at < now()")
                .use { statement ->
                    statement.setLong(1, accountId)
                    statement.executeUpdate()
                }
            db.prepareStatement(
                "INSERT INTO sessions (token_hash, account_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, tokenFingerprint)
                statement.setLong(2, accountId)
                statement.setTimestamp(3, Timestamp(expiresAt))
                statement.executeUpdate()
            }
            Unit
        }

    /**
     * Which account a token belongs to, or null if it is unknown **or expired**.
     *
     * The expiry is enforced in the `WHERE` clause rather than by comparing in Kotlin, so there is
     * no window in which a row is fetched, judged valid, and used a moment after it stopped being
     * so — and no chance of the server's clock and the database's disagreeing about it.
     */
    fun accountForToken(tokenFingerprint: String): Long? = transaction { db ->
        db.prepareStatement(
            "SELECT account_id FROM sessions WHERE token_hash = ? AND expires_at > now()",
        ).use { statement ->
            statement.setString(1, tokenFingerprint)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
    }

    /** Ends one session. Signing out must not end the player's other devices. */
    fun closeSession(tokenFingerprint: String) = transaction { db ->
        db.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { statement ->
            statement.setString(1, tokenFingerprint)
            statement.executeUpdate()
        }
        Unit
    }

    // ---- The player -------------------------------------------------------

    /** The profile and its match record, or null if the account has no character. */
    fun playerState(accountId: Long, recent: Int = RECENT_MATCHES): PlayerState? =
        transaction { db ->
            val save = readSave(db, accountId) ?: return@transaction null
            PlayerState(save = save, stats = readStats(db, accountId, recent))
        }

    /** The profile alone, without the match record — what crediting needs to read. */
    fun saveFor(accountId: Long): GameSave? = transaction { db -> readSave(db, accountId) }

    /**
     * Replaces the stored profile with one the client sent.
     *
     * ### This is the trusted-client hole that is left, and it is left knowingly
     *
     * Everything about a *match* is now the server's: it replays it, scores it, and decides what it
     * paid. Everything else a profile records — a card bought in the shop, a deck rearranged, a
     * potion drunk — still happens entirely on the client, because that is where those rules live
     * and there is no transcript of a shop visit to replay.
     *
     * So this endpoint exists and takes the client at its word, which means a determined player can
     * still give themselves MGP. That is not a regression: it is exactly where the game was before
     * accounts, minus the part that mattered most. Closing it means giving the shop and the deck
     * editor the same treatment matches got — an intent the server can check — and that is a piece
     * of work in its own right rather than something to do half of here.
     *
     * @return false if the account has no character, which is not reachable — see registration.
     */
    fun replaceSave(accountId: Long, save: GameSave): Boolean = transaction { db ->
        db.prepareStatement(
            "UPDATE characters SET save = ?::jsonb, updated_at = now() WHERE account_id = ?",
        ).use { statement ->
            statement.setString(1, json.encodeToString(save))
            statement.setLong(2, accountId)
            statement.executeUpdate() > 0
        }
    }

    /**
     * Records a verified match and the profile it produced, atomically.
     *
     * ### The two writes are one fact
     *
     * A match row without the credited profile would pay nothing; a credited profile without the
     * match row could be paid again. Either alone is a bug that only appears when the process dies
     * at the wrong instant, which is the sort of bug that is never reproduced and never quite
     * fixed. One transaction removes the question.
     *
     * ### Replays are answered, not refused
     *
     * The insert is `ON CONFLICT DO NOTHING` against `matches_transcript_idx`. A transcript already
     * credited therefore changes nothing and reports itself as a duplicate — which is ordinary
     * rather than suspicious: an offline queue whose acknowledgement was lost drains the
     * same transcript twice by design, and telling the player their real match was fake would be
     * the wrong answer to the client being careful.
     *
     * @return which of the three things happened. Two of them used to be `null` and must not be:
     *   a duplicate is a client being careful, and a bad ticket is a match that will not be paid.
     */
    @Suppress("LongParameterList", "MagicNumber")
    fun creditMatch(
        accountId: Long,
        transcriptHash: String,
        match: RecordedMatch,
        save: GameSave,
        recent: Int = RECENT_MATCHES,
    ): Credited = transaction { db ->
        // **The duplicate check comes first, before the ticket is even looked at.** Two failing
        // tests to get this order right, and the reason is the same both times: a resubmitted
        // transcript — an offline queue draining twice after a lost acknowledgement — is played on
        // a seed its own first submission already spent. Reaching for the ticket first finds it
        // spent and calls a careful client a forger, which is the one thing this endpoint has
        // always refused to do.
        if (alreadyCredited(db, accountId, transcriptHash)) return@transaction Credited.Duplicate

        // Locked rather than read, so two submissions of the same seed are ordered rather than both
        // finding it good. Released when this transaction ends, which is when the spend commits.
        val ticket = lockTicket(db, accountId, match.seed) ?: return@transaction Credited.NoTicket

        val inserted = db.prepareStatement(
            """
            INSERT INTO matches
                (account_id, opponent_icon_id, format, seed, blue, red, result, mgp, xp,
                 transcript_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, transcript_hash) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, match.opponentIconId)
            statement.setString(3, match.formatId)
            statement.setInt(4, match.seed)
            statement.setInt(5, match.blue)
            statement.setInt(6, match.red)
            statement.setString(7, match.result.name)
            statement.setInt(8, match.mgp)
            statement.setInt(9, match.xp)
            statement.setString(10, transcriptHash)
            statement.executeUpdate()
        }
        // The racing case the check above cannot catch: two identical submissions in flight at
        // once, both past it. The ticket is left **untouched** — the one that won spent it.
        if (inserted == 0) return@transaction Credited.Duplicate

        spend(db, accountId, ticket)

        db.prepareStatement(
            "UPDATE characters SET save = ?::jsonb, updated_at = now() WHERE account_id = ?",
        ).use { statement ->
            statement.setString(1, json.encodeToString(save))
            statement.setLong(2, accountId)
            statement.executeUpdate()
        }

        Credited.Paid(PlayerState(save = save, stats = readStats(db, accountId, recent)))
    }

    /**
     * Runs [perform] against the stored profile **exactly once per [operationId]**.
     *
     * The idempotency guarantee the intent endpoints are built on — see `Idempotent` in `:core` for
     * why they need one. A repeat of an id already applied does not run [perform] at all; it
     * returns the answer the first attempt was given, byte for byte.
     *
     * ### How the race is settled, and why the placeholder
     *
     * The answer cannot be written at the moment the key is claimed, because computing it is the
     * work being guarded. So the key is claimed first with an empty response, and filled in before
     * the transaction commits. Nothing ever observes the placeholder: it is written and overwritten
     * inside one transaction, and a concurrent request for the same id **blocks on the insert**
     * until that transaction settles — which is Postgres's behaviour for `ON CONFLICT DO NOTHING`
     * against an uncommitted row, and is exactly the serialisation this needs. It then reads zero
     * rows inserted, and the committed answer is there to return.
     *
     * The alternative — check, then insert — has a window between the two in which both requests
     * see nothing and both open a pack. That window is small, which is what makes it the kind of
     * bug that reaches production and is never reproduced.
     *
     * @param perform the change, as a pure function of the stored profile. Runs inside the
     *   transaction, so it must not do I/O of its own.
     * @param describe the response body, built from the state that was actually written. Separate
     *   from [perform] because it needs the [PlayerState] — the match record included — which does
     *   not exist until the profile has been stored.
     * @return the response body, whether freshly computed or replayed, or null when the account has
     *   no character.
     */
    // The indices are JDBC's parameter positions, as in `creditMatch` above and for the same
    // reason: they are the statement's own numbering, not a quantity anyone could name better.
    @Suppress("MagicNumber")
    fun <T> applyOnce(
        accountId: Long,
        operationId: String,
        perform: (GameSave) -> Outcome<T>,
        describe: (PlayerState, T) -> String,
    ): String? = transaction { db ->
        val claimed = db.prepareStatement(
            """
            INSERT INTO applied_operations (account_id, operation_id, response)
            VALUES (?, ?, '{}'::jsonb)
            ON CONFLICT (account_id, operation_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, operationId)
            statement.executeUpdate()
        }

        if (claimed == 0) return@transaction readAnswer(db, accountId, operationId)

        val stored = readSave(db, accountId) ?: return@transaction null
        val outcome = perform(stored)

        db.prepareStatement(
            "UPDATE characters SET save = ?::jsonb, updated_at = now() WHERE account_id = ?",
        ).use { statement ->
            statement.setString(1, json.encodeToString(outcome.save))
            statement.setLong(2, accountId)
            statement.executeUpdate()
        }

        val player = PlayerState(
            save = outcome.save,
            stats = readStats(db, accountId, RECENT_MATCHES),
        )
        val response = describe(player, outcome.detail)

        db.prepareStatement(
            """
            UPDATE applied_operations SET response = ?::jsonb
            WHERE account_id = ? AND operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, response)
            statement.setLong(2, accountId)
            statement.setString(3, operationId)
            statement.executeUpdate()
        }
        response
    }

    private fun readAnswer(db: Connection, accountId: Long, operationId: String): String? =
        db.prepareStatement(
            "SELECT response FROM applied_operations WHERE account_id = ? AND operation_id = ?",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, operationId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    /**
     * Tops [accountId] up to [SeedTickets.MAX_UNSPENT] unspent seeds and returns what it now holds.
     *
     * Idempotent by arithmetic rather than by an operation id: it issues the *difference*, so
     * calling it twice in a row issues nothing the second time. That is what makes it safe as a
     * `GET`, and it is why the client can simply ask whenever it notices it is low.
     *
     * @param seeds a generator for the new ones. The server's, obviously — a seed a caller could
     *   influence would defeat the point of issuing it here.
     */
    fun issueTickets(accountId: Long, seeds: () -> Int): List<Int> = transaction { db ->
        val held = unspentSeeds(db, accountId)
        val wanted = SeedTickets.MAX_UNSPENT - held.size
        if (wanted <= 0) return@transaction held

        db.prepareStatement(
            """
            INSERT INTO match_tickets (account_id, seed) VALUES (?, ?)
            ON CONFLICT (account_id, seed) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            repeat(wanted) {
                statement.setLong(1, accountId)
                statement.setInt(2, seeds())
                statement.addBatch()
            }
            statement.executeBatch()
        }
        unspentSeeds(db, accountId)
    }

    /** Whether this exact transcript has been credited to this account before. */
    private fun alreadyCredited(db: Connection, accountId: Long, transcriptHash: String): Boolean =
        db.prepareStatement(
            "SELECT 1 FROM matches WHERE account_id = ? AND transcript_hash = ?",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, transcriptHash)
            statement.executeQuery().use { rows -> rows.next() }
        }

    /**
     * Takes the row for [seed] and holds it, or null when it is not this account's to spend.
     *
     * `FOR UPDATE` rather than a plain read, so that two submissions of the same seed are ordered
     * rather than both finding it good. The lock is released when the caller's transaction ends,
     * which is also when the spend it guards is committed.
     */
    private fun lockTicket(db: Connection, accountId: Long, seed: Int): Long? = db.prepareStatement(
        """
            SELECT id FROM match_tickets
            WHERE account_id = ? AND seed = ? AND spent_at IS NULL AND voided_at IS NULL
            FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.setInt(2, seed)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
    }

    /**
     * Marks [ticket] spent and voids every ticket issued to [accountId] before it.
     *
     * The voiding is the anti-grinding rule — see `V9__match_tickets.sql` — and it is one `UPDATE`
     * away from being forgotten, so it lives here beside the spend rather than in the caller.
     */
    private fun spend(db: Connection, accountId: Long, ticket: Long) {
        db.prepareStatement("UPDATE match_tickets SET spent_at = now() WHERE id = ?").use {
            it.setLong(1, ticket)
            it.executeUpdate()
        }
        db.prepareStatement(
            """
            UPDATE match_tickets SET voided_at = now()
            WHERE account_id = ? AND id < ? AND spent_at IS NULL AND voided_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, ticket)
            statement.executeUpdate()
        }
    }

    private fun unspentSeeds(db: Connection, accountId: Long): List<Int> = db.prepareStatement(
        """
            SELECT seed FROM match_tickets
            WHERE account_id = ? AND spent_at IS NULL AND voided_at IS NULL
            ORDER BY id
        """.trimIndent(),
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(rows.getInt(1)) }
        }
    }

    // ---- Reads used by more than one of the above -------------------------

    private fun readSave(db: Connection, accountId: Long): GameSave? =
        db.prepareStatement("SELECT save FROM characters WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) json.decodeFromString<GameSave>(rows.getString(1)) else null
            }
        }

    /**
     * The counters and the recent list, both derived from `matches`.
     *
     * Aggregated on read rather than kept in a column, which is the decision worth defending: a
     * stored counter is a second source of truth that drifts the first time a write is interrupted
     * or a row is corrected, and no amount of care makes it self-checking. This cannot drift
     * because there is nothing for it to drift *from*. It costs an index scan on a table with one
     * row per match played by one player, which is a rounding error next to the request behind it.
     */
    private fun readStats(db: Connection, accountId: Long, recent: Int): PlayerStats {
        val counts = db.prepareStatement(
            "SELECT result, count(*) FROM matches WHERE account_id = ? GROUP BY result",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows -> rows.toCounts() }
        }

        return PlayerStats(
            wins = counts[MatchResult.WIN.name] ?: 0,
            losses = counts[MatchResult.LOSE.name] ?: 0,
            draws = counts[MatchResult.DRAW.name] ?: 0,
            recent = readRecent(db, accountId, recent),
        )
    }

    /**
     * A `GROUP BY` result as a map, with the absent groups simply absent.
     *
     * Postgres returns no row for a result the player has never had, which is why the caller reads
     * this with a `?: 0` rather than expecting three rows: a player who has only ever drawn has one
     * row, and treating that as an error would make a legitimate record look like a broken query.
     */
    private fun ResultSet.toCounts(): Map<String, Int> =
        buildMap { while (next()) put(getString(1), getInt(2)) }

    private fun readRecent(db: Connection, accountId: Long, limit: Int): List<VerifiedMatch> =
        db.prepareStatement(
            """
            SELECT id, played_at, opponent_icon_id, format, seed, blue, red, result, mgp, xp
            FROM matches WHERE account_id = ? ORDER BY played_at DESC, id DESC LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toVerifiedMatch()) }
            }
        }

    private fun ResultSet.toVerifiedMatch() = VerifiedMatch(
        id = getLong("id"),
        playedAt = getTimestamp("played_at").time,
        opponentIconId = getString("opponent_icon_id"),
        formatId = getString("format"),
        seed = getInt("seed"),
        blue = getInt("blue"),
        red = getInt("red"),
        result = MatchResult.valueOf(getString("result")),
        mgp = getInt("mgp"),
        xp = getInt("xp"),
    )

    /**
     * Runs [block] in one transaction, committing on success and rolling back on anything else.
     *
     * The rollback is not belt and braces: with `isAutoCommit = false` a connection returned to the
     * pool mid-transaction carries its open transaction to whoever gets it next. Hikari does roll
     * back on return, but relying on that would make correctness here a property of the pool's
     * configuration rather than of this file.
     */
    // The catch is deliberately as wide as it can be. Anything at all leaving `block` — an Error, a
    // cancellation — must not leave a transaction open, and a rollback followed by a rethrow
    // changes nothing about what the caller sees.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { db ->
        try {
            val result = block(db)
            db.commit()
            result
        } catch (failure: Throwable) {
            db.rollback()
            throw failure
        }
    }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        /** Enough for a recent-form list. The full history stays in the table. */
        const val RECENT_MATCHES = 20
    }
}

/** An account's id and password digest, as stored. Never leaves this package. */
data class StoredCredentials(val accountId: Long, val passwordHash: String)

/** The columns of one `matches` row that the caller decides. */
data class RecordedMatch(
    val opponentIconId: String,
    val formatId: String,
    val seed: Int,
    val blue: Int,
    val red: Int,
    val result: MatchResult,
    val mgp: Int,
    val xp: Int,
)

/**
 * What an intent did: the profile it produced, and whatever describes it to the caller.
 *
 * Two values rather than one because the response needs both and they come from different places —
 * the profile is written, and [detail] is what the core function reported doing. `Inventory.use`
 * returning `ItemUse` is the shape this generalises.
 */
data class Outcome<T>(val save: GameSave, val detail: T)

/**
 * What happened when a verified match was offered for payment.
 *
 * Three answers where there used to be a nullable [PlayerState], and the split is the point: two of
 * the three were the same `null` and mean opposite things. A duplicate is an offline queue draining
 * twice after a lost acknowledgement — careful behaviour, answered with the player's real state. A
 * missing ticket is a match played on a seed this server never issued.
 */
sealed interface Credited {
    /** Recorded and paid. */
    data class Paid(val player: PlayerState) : Credited

    /** Already credited. Nothing changed, and nothing is wrong. */
    data object Duplicate : Credited

    /** The seed was not this account's to use. See `RejectionReason.UNKNOWN_SEED`. */
    data object NoTicket : Credited
}

/**
 * How anything this server stores as JSONB is encoded — a [GameSave], a rule set, a move list.
 *
 * `encodeDefaults = true`, unlike the wire format: these documents are read back by a *future*
 * build of the server, and a field omitted because it happened to equal today's default would
 * silently take tomorrow's default instead. `ignoreUnknownKeys` for the mirror image — a document
 * written by a newer build must not make an older one unable to read a row at all.
 *
 * Internal rather than private because [PvpStore] stores its rows under exactly these rules and for
 * exactly these reasons. A second `Json` configured "the same way" would be two copies waiting to
 * drift the first time either is tuned.
 */
internal val SaveJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * How this server writes a **response body it encodes by hand**.
 *
 * Which is only the intent endpoints: everything else hands an object to content negotiation and
 * never sees the bytes. Those endpoints cannot, because the body they send is also the body they
 * *store* against the operation id — see `AccountStore.applyOnce` — so it has to exist as a string
 * before it is sent.
 *
 * It matches the negotiation plugin's settings deliberately, and [SaveJson] deliberately does not:
 * that one is for documents read back by a future build, where an omitted default is a field that
 * silently changes meaning. A response is read *now*, by a client that shares the schema, and
 * making `/me` and `/me/shop/buy` disagree about whether to spell out a default would put two
 * shapes of the same type on one API. Anyone reading the raw payload would find one endpoint
 * listing a starting purse and the other not.
 */
internal val ApiJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
