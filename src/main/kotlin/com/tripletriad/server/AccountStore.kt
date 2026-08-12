package com.tripletriad.server

import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchResult
import com.tripletriad.protocol.PlayerState
import com.tripletriad.protocol.PlayerStats
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
     * @return null when this transcript had already been credited.
     */
    @Suppress("LongParameterList", "MagicNumber")
    fun creditMatch(
        accountId: Long,
        transcriptHash: String,
        match: RecordedMatch,
        save: GameSave,
        recent: Int = RECENT_MATCHES,
    ): PlayerState? = transaction { db ->
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
        if (inserted == 0) return@transaction null

        db.prepareStatement(
            "UPDATE characters SET save = ?::jsonb, updated_at = now() WHERE account_id = ?",
        ).use { statement ->
            statement.setString(1, json.encodeToString(save))
            statement.setLong(2, accountId)
            statement.executeUpdate()
        }

        PlayerState(save = save, stats = readStats(db, accountId, recent))
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
