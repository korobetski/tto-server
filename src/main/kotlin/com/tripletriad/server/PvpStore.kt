package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.protocol.PvpChallenge
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpStake
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * The queue, the invitations and the live matches, over plain JDBC.
 *
 * Separate from [AccountStore] rather than folded into it, and the line is not arbitrary: that one
 * owns *who a player is and what they have*, this one owns *what is happening right now*. The two
 * meet in exactly one place — [creditBoth] — and that meeting is a transaction, which is the whole
 * reason it is written here rather than left to a route to sequence.
 *
 * ### The pairing is the interesting query
 *
 * Two players tapping "play" at the same instant must not both be handed the other *and* left in
 * the queue, and must not pair with themselves. `FOR UPDATE SKIP LOCKED` is what makes that safe
 * without a lock table: the second transaction skips the row the first is holding and looks
 * further down the queue rather than blocking on it or, worse, reading it as available.
 */
// Two suppressions, both for the same reason a data-access class attracts them. `TooManyFunctions`
// counts queries, which is what this class is made of; the rule is aimed at a class doing too many
// *things*, and this one does one. `MagicNumber` counts JDBC's positional parameter indices — the
// `4` in `setString(4, …)` is a position in the statement three lines above, and naming it would
// invent a concept SQL does not have.
@Suppress("TooManyFunctions", "MagicNumber")
class PvpStore(
    private val dataSource: DataSource,
    private val json: Json = SaveJson,
) {

    // ---- The quick queue --------------------------------------------------

    /** Puts [accountId] in the queue, or refreshes their place if they are already in it. */
    fun enqueue(accountId: Long, formatId: String) = transaction { db ->
        db.prepareStatement(
            """
            INSERT INTO pvp_queue (account_id, format) VALUES (?, ?)
            ON CONFLICT (account_id) DO UPDATE SET format = EXCLUDED.format
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, formatId)
            statement.executeUpdate()
        }
        Unit
    }

    fun dequeue(accountId: Long) = transaction { db -> removeFromQueue(db, accountId) }

    fun isQueued(accountId: Long): Boolean = transaction { db ->
        db.prepareStatement("SELECT 1 FROM pvp_queue WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { it.next() }
        }
    }

    /**
     * Takes the longest-waiting opponent for [accountId] out of the queue, or null if there is one.
     *
     * Removes both rows in the same transaction as the read, so the pair it returns cannot be
     * handed to anybody else. A caller that then fails to create a match has taken two players out
     * of the queue for nothing — which is why [pairAndOpen] does both in one call.
     */
    private fun takeOpponent(db: Connection, accountId: Long, formatId: String): Long? {
        val opponent = db.prepareStatement(
            """
            SELECT account_id FROM pvp_queue
            WHERE format = ? AND account_id <> ?
            ORDER BY since
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, formatId)
            statement.setLong(2, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        } ?: return null

        removeFromQueue(db, opponent)
        removeFromQueue(db, accountId)
        return opponent
    }

    /**
     * Pairs [accountId] with a waiting opponent and opens the match, or queues them.
     *
     * One call, one transaction, because the two halves cannot be separated safely: an opponent
     * taken out of the queue by a caller that then failed would be a player removed from the queue
     * with no match to show for it, and no way to notice.
     *
     * @param open builds the match from the two account ids. Returns null when it cannot — a
     *   profile with no legal deck, say — and the whole transaction then rolls back, leaving both
     *   players in the queue exactly as they were.
     */
    fun pairAndOpen(
        accountId: Long,
        formatId: String,
        open: (blue: Long, red: Long) -> PvpMatchRow?,
    ): PvpMatchRow? = transaction { db ->
        val opponent = takeOpponent(db, accountId, formatId) ?: run {
            db.prepareStatement(
                """
                INSERT INTO pvp_queue (account_id, format) VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET format = EXCLUDED.format
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setString(2, formatId)
                statement.executeUpdate()
            }
            return@transaction null
        }

        // The one who was waiting plays blue. Arbitrary, and worth fixing rather than leaving to
        // whichever account id sorted lower: who starts is decided by `first`, not by colour, so
        // colour only decides which end of the board a player looks at.
        val row = open(opponent, accountId) ?: return@transaction null
        insertMatch(db, row)
        row
    }

    private fun removeFromQueue(db: Connection, accountId: Long) {
        db.prepareStatement("DELETE FROM pvp_queue WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }
    }

    // ---- Invitations ------------------------------------------------------

    /** Records an invitation from one account to another. */
    fun challenge(id: String, from: Long, to: Long, stake: PvpStake, expiresAt: Long) =
        transaction { db ->
            db.prepareStatement(
                """
            INSERT INTO pvp_challenges (id, from_account, to_account, stake, expires_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setLong(2, from)
                statement.setLong(3, to)
                statement.setString(4, json.encodeToString(PvpStake.serializer(), stake))
                statement.setTimestamp(5, Timestamp(expiresAt))
                statement.executeUpdate()
            }
            Unit
        }

    /** The invitations [accountId] has been sent that are still standing. */
    fun challengesFor(accountId: Long, now: Long): List<StoredChallenge> = transaction { db ->
        db.prepareStatement(
            """
            SELECT c.id, c.from_account, c.to_account, c.stake, c.expires_at, c.match_id,
                   f.username, t.username
            FROM pvp_challenges c
            JOIN accounts f ON f.id = c.from_account
            JOIN accounts t ON t.id = c.to_account
            WHERE (c.to_account = ? OR c.from_account = ?)
              AND c.expires_at > ?
              AND c.match_id IS NULL
            ORDER BY c.created_at
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, accountId)
            statement.setTimestamp(3, Timestamp(now))
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toChallenge()) }
            }
        }
    }

    /**
     * Accepts an invitation and opens the match it names, atomically.
     *
     * The invitation is claimed with `match_id IS NULL` in the `UPDATE`, so two taps on Accept —
     * or a tap racing a retry — settle to one match rather than two. The second update touches no
     * rows and the whole thing rolls back.
     */
    fun acceptChallenge(
        challengeId: String,
        accountId: Long,
        now: Long,
        open: (challenger: Long, accepter: Long, stake: PvpStake) -> PvpMatchRow?,
    ): PvpMatchRow? = transaction { db ->
        val challenge = db.prepareStatement(
            """
            SELECT c.id, c.from_account, c.to_account, c.stake, c.expires_at, c.match_id,
                   f.username, t.username
            FROM pvp_challenges c
            JOIN accounts f ON f.id = c.from_account
            JOIN accounts t ON t.id = c.to_account
            WHERE c.id = ? AND c.to_account = ? AND c.expires_at > ? AND c.match_id IS NULL
            FOR UPDATE OF c
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, challengeId)
            statement.setLong(2, accountId)
            statement.setTimestamp(3, Timestamp(now))
            statement.executeQuery().use { rows -> if (rows.next()) rows.toChallenge() else null }
        } ?: return@transaction null

        val row = open(challenge.fromAccount, accountId, challenge.stake)
            ?: return@transaction null
        insertMatch(db, row)

        db.prepareStatement(
            "UPDATE pvp_challenges SET match_id = ? WHERE id = ? AND match_id IS NULL",
        ).use { statement ->
            statement.setString(1, row.id)
            statement.setString(2, challengeId)
            if (statement.executeUpdate() == 0) return@transaction null
        }
        // Neither player is left waiting in the quick queue for a match they are now in.
        removeFromQueue(db, challenge.fromAccount)
        removeFromQueue(db, accountId)
        row
    }

    /** Refuses an invitation, or withdraws one. Deleting is the whole of it. */
    fun dropChallenge(challengeId: String, accountId: Long): Boolean = transaction { db ->
        db.prepareStatement(
            "DELETE FROM pvp_challenges WHERE id = ? AND (to_account = ? OR from_account = ?)",
        ).use { statement ->
            statement.setString(1, challengeId)
            statement.setLong(2, accountId)
            statement.setLong(3, accountId)
            statement.executeUpdate() > 0
        }
    }

    // ---- Matches ----------------------------------------------------------

    /**
     * The match [accountId] is in, if any.
     *
     * The query the client asks at every launch. It is what makes a match survive the application
     * being killed — which mobile does without asking, and which the player did not choose.
     */
    fun liveMatchFor(accountId: Long): PvpMatchRow? = transaction { db ->
        db.prepareStatement(
            """
            SELECT * FROM pvp_matches
            WHERE (blue_account = ? OR red_account = ?) AND status = 'PLAYING'
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMatch() else null }
        }
    }

    fun matchById(id: String): PvpMatchRow? = transaction { db -> readMatch(db, id) }

    /**
     * Appends [move] and sets the next deadline, refusing if the match moved on underneath.
     *
     * The refusal is what makes a double tap harmless: the expected move count is checked in the
     * `WHERE`, so a second identical request finds the count already advanced and changes nothing.
     * Without it, two requests a few milliseconds apart would place two cards from one tap.
     */
    fun appendMove(id: String, expectedMoves: Int, move: PvpMove, deadline: Long?): Boolean =
        transaction { db ->
            db.prepareStatement(
                """
            UPDATE pvp_matches
            SET moves = moves || ?::jsonb, turn_deadline = ?, updated_at = now()
            WHERE id = ? AND status = 'PLAYING' AND jsonb_array_length(moves) = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, json.encodeToString(listOf(move)))
                statement.setTimestamp(2, deadline?.let(::Timestamp))
                statement.setString(3, id)
                statement.setInt(4, expectedMoves)
                statement.executeUpdate() > 0
            }
        }

    /** Ends a match. Idempotent: a match already finished is not re-ended. */
    fun finish(id: String, status: PvpMatchStatus, forfeitedBy: CardColor? = null): Boolean =
        transaction { db ->
            db.prepareStatement(
                """
                UPDATE pvp_matches
                SET status = ?, forfeited_by = ?, turn_deadline = NULL,
                    finished_at = now(), updated_at = now()
                WHERE id = ? AND status = 'PLAYING'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, forfeitedBy?.name)
                statement.setString(3, id)
                statement.executeUpdate() > 0
            }
        }

    /**
     * Every live match whose deadline has passed.
     *
     * Read by the sweep. Returning rows rather than forfeiting them in SQL is deliberate: deciding
     * *who* forfeited and crediting both profiles is engine work, and the one credit path is
     * `MatchRewards`, which does not live in the database.
     */
    fun overdue(now: Long, limit: Int = SWEEP_LIMIT): List<PvpMatchRow> = transaction { db ->
        db.prepareStatement(
            """
            SELECT * FROM pvp_matches
            WHERE status = 'PLAYING' AND turn_deadline IS NOT NULL AND turn_deadline < ?
            ORDER BY turn_deadline
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp(now))
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toMatch()) }
            }
        }
    }

    private fun readMatch(db: Connection, id: String): PvpMatchRow? =
        db.prepareStatement("SELECT * FROM pvp_matches WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMatch() else null }
        }

    @Suppress("MagicNumber")
    private fun insertMatch(db: Connection, row: PvpMatchRow) {
        db.prepareStatement(
            """
            INSERT INTO pvp_matches
                (id, blue_account, red_account, format, rules, seed, blue_hand, red_hand,
                 first_player, moves, stake, status, turn_deadline)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, row.id)
            statement.setLong(2, row.blueAccount)
            statement.setLong(3, row.redAccount)
            statement.setString(4, row.formatId)
            statement.setString(5, json.encodeToString(GameRules.serializer(), row.rules))
            statement.setInt(6, row.seed)
            statement.setString(7, json.encodeToString(row.blueHand))
            statement.setString(8, json.encodeToString(row.redHand))
            statement.setString(9, row.first.name)
            statement.setString(10, json.encodeToString(row.moves))
            statement.setString(11, json.encodeToString(PvpStake.serializer(), row.stake))
            statement.setString(12, row.status.name)
            statement.setTimestamp(13, row.turnDeadline?.let(::Timestamp))
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toMatch() = PvpMatchRow(
        id = getString("id"),
        blueAccount = getLong("blue_account"),
        redAccount = getLong("red_account"),
        formatId = getString("format"),
        rules = json.decodeFromString(GameRules.serializer(), getString("rules")),
        seed = getInt("seed"),
        blueHand = json.decodeFromString(getString("blue_hand")),
        redHand = json.decodeFromString(getString("red_hand")),
        first = CardColor.valueOf(getString("first_player")),
        moves = json.decodeFromString(getString("moves")),
        stake = json.decodeFromString(PvpStake.serializer(), getString("stake")),
        status = PvpMatchStatus.valueOf(getString("status")),
        turnDeadline = getTimestamp("turn_deadline")?.time,
        forfeitedBy = getString("forfeited_by")?.let(CardColor::valueOf),
    )

    private fun ResultSet.toChallenge() = StoredChallenge(
        id = getString(1),
        fromAccount = getLong(2),
        toAccount = getLong(3),
        stake = json.decodeFromString(PvpStake.serializer(), getString(4)),
        expiresAt = getTimestamp(5).time,
        matchId = getString(6),
        fromName = getString(7),
        toName = getString(8),
    )

    // As wide as it can be, and for the reason `AccountStore.transaction` gives: anything at all
    // leaving `block` — an Error, a cancellation — must not leave a transaction open, and a
    // rollback followed by a rethrow changes nothing about what the caller sees.
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
        /** Enough that a sweep clears a backlog, few enough that one pass is bounded. */
        const val SWEEP_LIMIT = 50
    }
}

/** One invitation as stored, with both names resolved so a client needs no second lookup. */
data class StoredChallenge(
    val id: String,
    val fromAccount: Long,
    val toAccount: Long,
    val stake: PvpStake,
    val expiresAt: Long,
    val matchId: String?,
    val fromName: String,
    val toName: String,
) {
    fun toWire(): PvpChallenge = PvpChallenge(
        id = id,
        fromName = fromName,
        toName = toName,
        stake = stake,
        expiresAt = expiresAt,
        matchId = matchId,
    )
}
