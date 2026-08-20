package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.protocol.PveMatchStatus
import com.tripletriad.protocol.PveMove
import com.tripletriad.protocol.RewardSummary
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * Matches against an opponent, over plain JDBC.
 *
 * Much shorter than [PvpStore], and every difference is the same difference: **nobody is waiting.**
 * There is no lobby to advertise in, no invitation to accept, no prize to name, and no deadline to
 * miss. What is left is opening a match, reading it, appending to it and ending it.
 *
 * ### The one race worth guarding
 *
 * A player tapping an opponent twice, or retrying after a lost response, must end up in **one**
 * match rather than two. `pve_matches_live_idx` — unique on `account_id` where the status is
 * `PLAYING` — is what settles it, and [open] turns the violation into an empty result so the caller
 * can hand back the match that already exists instead of an error nobody can act on.
 *
 * That the guard lives in the index rather than in a read-then-write is the same decision
 * `pvp_tables_one_per_host` makes, for the same reason: two taps a millisecond apart both pass a
 * check.
 */
// `MagicNumber` counts JDBC's positional parameter indices — the `4` in `setString(4, …)` is a
// position in the statement three lines above, and naming it would invent a concept SQL does not
// have. The same suppression, for the same reason, as `PvpStore`.
@Suppress("MagicNumber")
class PveStore(
    private val dataSource: DataSource,
    private val json: Json = SaveJson,
) {

    /**
     * Opens a match, or null if this account already has one live.
     *
     * Null is not a failure to report to a player. It means a match already exists, and the
     * caller's job is to answer with *that* — which is what a double tap wanted anyway, and what a
     * retry after a dropped response needs in order to be harmless.
     *
     * Any earlier match is abandoned first. A player is allowed to walk away from a program and
     * start something else — there is nobody on the other side to be stranded, and an abandoned
     * match pays nothing, so leaving one is never a way to avoid a result that was going badly.
     */
    fun open(row: PveMatchRow): PveMatchRow? = transaction { db ->
        db.prepareStatement(
            """
            INSERT INTO pve_matches
                (id, account_id, format_id, opponent_icon, rules, seed, blue_hand, red_hand,
                 first_player, moves, status)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?)
            ON CONFLICT (account_id) WHERE status = 'PLAYING' DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, row.id)
            statement.setLong(2, row.accountId)
            statement.setString(3, row.formatId)
            statement.setString(4, row.opponentIconId)
            statement.setString(5, json.encodeToString(GameRules.serializer(), row.rules))
            statement.setInt(6, row.seed)
            statement.setString(7, json.encodeToString(row.blueHand))
            statement.setString(8, json.encodeToString(row.redHand))
            statement.setString(9, row.first.name)
            statement.setString(10, json.encodeToString(row.moves))
            statement.setString(11, row.status.name)
            if (statement.executeUpdate() > 0) row else null
        }
    }

    /**
     * Abandons whatever this account has live, so a new match can be opened.
     *
     * Its own call rather than a step inside [open], because the two are different decisions and
     * the caller makes them in that order deliberately: nothing is abandoned until the new match's
     * deal has been worked out and found legal. Abandoning first and then failing to deal would
     * take a player's match away and give them nothing.
     */
    fun abandonLive(accountId: Long): Boolean = transaction { db ->
        db.prepareStatement(
            """
            UPDATE pve_matches
            SET status = 'ABANDONED', finished_at = now(), updated_at = now()
            WHERE account_id = ? AND status = 'PLAYING'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate() > 0
        }
    }

    /**
     * The match this account is in, if any. **This is the whole of "resume".**
     *
     * A lookup on the partial unique index, not `ORDER BY created_at DESC LIMIT 1` — so it cannot
     * quietly return the wrong one of two rows that should never both have existed.
     */
    fun activeFor(accountId: Long): PveMatchRow? = transaction { db ->
        db.prepareStatement(
            "SELECT * FROM pve_matches WHERE account_id = ? AND status = 'PLAYING'",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMatch() else null }
        }
    }

    /**
     * The match this account should be **looking at**, which is not the same as being in.
     *
     * A match stops being live the instant it is settled, and for [activeFor] that is right — it
     * answers "may this player start something else", and the answer becomes yes immediately.
     *
     * It is wrong for the screen. The player is normally handed the finished view as the response
     * to their own last move, so they see the result; a player whose app was killed between
     * placing the ninth card and reading the answer was not. They would reopen the game to no match
     * at all, having just been credited for one. So a settled match stays findable for
     * [RESULT_MILLIS].
     *
     * Two queries rather than one with a wider window, because a window here must **not** stop the
     * player starting something else: "you are already in a match" for two minutes after one ended
     * would be a worse bug than the one this fixes.
     */
    fun recentFor(accountId: Long, now: Long): PveMatchRow? = transaction { db ->
        db.prepareStatement(
            """
            SELECT * FROM pve_matches
            WHERE account_id = ?
              AND (status = 'PLAYING' OR (finished_at IS NOT NULL AND finished_at > ?))
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setTimestamp(2, Timestamp(now - RESULT_MILLIS))
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMatch() else null }
        }
    }

    /**
     * One match by id, **scoped to its owner**.
     *
     * The account is part of the lookup rather than checked afterwards. An id is opaque and hard to
     * guess, but "hard to guess" is not an authorisation rule, and a query that can only return
     * this player's match cannot be followed by a forgotten check.
     */
    fun matchById(id: String, accountId: Long): PveMatchRow? = transaction { db ->
        db.prepareStatement(
            "SELECT * FROM pve_matches WHERE id = ? AND account_id = ?",
        ).use { statement ->
            statement.setString(1, id)
            statement.setLong(2, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMatch() else null }
        }
    }

    /**
     * Appends [moves] and refuses if the match moved on underneath.
     *
     * **Plural, and that is the point.** One request produces the player's placement and the
     * opponent's reply, and they must land together or not at all: a crash between two writes would
     * leave a board where it is nobody's turn in a way the row cannot express.
     *
     * The refusal is what makes a double tap harmless. The expected move count is checked in the
     * `WHERE`, so a second identical request finds the count already advanced and changes nothing —
     * without it, two requests a few milliseconds apart would place two cards from one tap.
     */
    fun appendMoves(id: String, expectedMoves: Int, moves: List<PveMove>): Boolean =
        transaction { db ->
            db.prepareStatement(
                """
                UPDATE pve_matches
                SET moves = moves || ?::jsonb, updated_at = now()
                WHERE id = ? AND status = 'PLAYING' AND jsonb_array_length(moves) = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, json.encodeToString(moves))
                statement.setString(2, id)
                statement.setInt(3, expectedMoves)
                statement.executeUpdate() > 0
            }
        }

    /**
     * Ends a match and records what it paid. Idempotent: one already ended is not re-ended.
     *
     * `WHERE status = 'PLAYING'` is the whole of that guarantee, and it is what stops a match being
     * credited twice — a double tap on the ninth card, or a retry after a lost response, finds the
     * row already settled and is told `false`. The caller credits nothing and answers with the row.
     *
     * The reward is written **in the same statement** that ends the match, unlike [PvpStore], where
     * settling and paying are two writes because two profiles are credited one at a time. Here
     * there is one profile, so there is no reason to leave a window in which a match is over and
     * what it paid is not yet recorded.
     */
    fun finish(id: String, status: PveMatchStatus, reward: RewardSummary?): Boolean =
        transaction { db ->
            db.prepareStatement(
                """
                UPDATE pve_matches
                SET status = ?, reward = ?::jsonb, finished_at = now(), updated_at = now()
                WHERE id = ? AND status = 'PLAYING'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(
                    2,
                    reward?.let { json.encodeToString(RewardSummary.serializer(), it) },
                )
                statement.setString(3, id)
                statement.executeUpdate() > 0
            }
        }

    private fun ResultSet.toMatch() = PveMatchRow(
        id = getString("id"),
        accountId = getLong("account_id"),
        formatId = getString("format_id"),
        opponentIconId = getString("opponent_icon"),
        rules = json.decodeFromString(GameRules.serializer(), getString("rules")),
        seed = getInt("seed"),
        blueHand = json.decodeFromString(getString("blue_hand")),
        redHand = json.decodeFromString(getString("red_hand")),
        first = CardColor.valueOf(getString("first_player")),
        moves = json.decodeFromString(getString("moves")),
        status = PveMatchStatus.valueOf(getString("status")),
        reward = getString("reward")
            ?.let { json.decodeFromString(RewardSummary.serializer(), it) },
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
        /**
         * How long a settled match stays findable, so the result survives a killed application.
         *
         * The same number and the same argument as `PvpStore.RESULT_MILLIS`: long enough that a
         * player who closes the game the moment they lose is still told they lost, short enough
         * that it is gone before they would wonder why it is still there.
         */
        const val RESULT_MILLIS = 120_000L
    }
}
