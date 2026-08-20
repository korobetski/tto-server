package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.protocol.ANY_DECK
import com.tripletriad.protocol.PvpChallenge
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpStake
import com.tripletriad.protocol.PvpTable
import com.tripletriad.protocol.PvpTableRequest
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * The open tables, the invitations and the live matches, over plain JDBC.
 *
 * Separate from [AccountStore] rather than folded into it, and the line is not arbitrary: that one
 * owns *who a player is and what they have*, this one owns *what is happening right now*. The two
 * meet in exactly one place — [creditBoth] — and that meeting is a transaction, which is the whole
 * reason it is written here rather than left to a route to sequence.
 *
 * ### Joining is the interesting query
 *
 * Two players tapping Join on the same table within a few milliseconds must produce **one** match,
 * not two, and the loser of that race must be told so rather than dropped into a game their
 * opponent is not in. `FOR UPDATE OF` on the table row plus `WHERE match_id IS NULL` on the update
 * is what makes that safe: the second transaction blocks on the row, then finds it already claimed
 * and rolls back — taking the match row it had optimistically inserted with it.
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

    // ---- Open tables ------------------------------------------------------

    /**
     * Opens a table, or false if this host already has one **still standing**.
     *
     * The refusal comes from `pvp_tables_one_per_host`, a partial unique index, rather than from a
     * read followed by a write — which two taps' worth of latency apart would let the same host
     * open two. `ON CONFLICT` turns that one violation into an empty result, so the caller gets a
     * refusal instead of an exception.
     *
     * The conflict target is **named**, and that is not decoration. A bare `ON CONFLICT DO NOTHING`
     * swallows every constraint on the table, including a primary-key collision on `id` — which
     * would then be reported to the player as "you already have a table open", a sentence with no
     * relation to what happened. Naming the index means an id collision throws, which is right: it
     * is a bug in whatever generated the id, not something to explain to a player.
     *
     * `DO UPDATE` and not `DO NOTHING`, because the index does not mention `expires_at` and
     * [openTables] does. A host whose table lapsed while they were away — the client stops
     * refreshing the moment the screen closes — held a row that no lobby would show and no sweep
     * would ever remove, and it went on refusing them a table *permanently*: "you already have one"
     * about a table nobody, the host included, could see. The lapsed row is reclaimed here instead,
     * in the one statement that already holds the lock, so the host's next tap simply works.
     *
     * The `WHERE` on the update is what keeps the original rule intact: a table that has **not**
     * lapsed still refuses, and `executeUpdate` reports the zero rows as such. Reclaiming means
     * taking the new id too — the row is a new table on new terms, and leaving the old id would
     * hand the host's client a table it cannot match to the request it just made.
     */
    fun openTable(row: PvpTableRow): Boolean = transaction { db ->
        db.prepareStatement(
            """
            INSERT INTO pvp_tables
                (id, host_account, format, rules, roulette, stake, expires_at, host_deck)
            VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)
            ON CONFLICT (host_account) WHERE match_id IS NULL DO UPDATE
                SET id         = EXCLUDED.id,
                    format     = EXCLUDED.format,
                    rules      = EXCLUDED.rules,
                    roulette   = EXCLUDED.roulette,
                    stake      = EXCLUDED.stake,
                    created_at = now(),
                    expires_at = EXCLUDED.expires_at,
                    host_deck  = EXCLUDED.host_deck
                WHERE pvp_tables.expires_at <= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, row.id)
            statement.setLong(2, row.hostAccount)
            statement.setString(3, row.formatId)
            statement.setString(4, json.encodeToString(GameRules.serializer(), row.rules))
            statement.setBoolean(5, row.roulette)
            statement.setString(6, json.encodeToString(PvpStake.serializer(), row.stake))
            statement.setTimestamp(7, Timestamp(row.expiresAt))
            statement.setInt(8, row.hostDeck)
            // The host's own clock, not the database's: every other decision about whether a table
            // is still open is made against `clock()`, and mixing the two would make a table lapsed
            // for the lobby and standing for this statement.
            statement.setTimestamp(9, Timestamp(row.openedAt))
            statement.executeUpdate() > 0
        }
    }

    /** Every table still open, soonest to lapse first. Includes the caller's own. */
    fun openTables(now: Long, limit: Int = LOBBY_LIMIT): List<PvpTableRow> = transaction { db ->
        db.prepareStatement(
            """
            SELECT t.*, a.username
            FROM pvp_tables t
            JOIN accounts a ON a.id = t.host_account
            WHERE t.match_id IS NULL AND t.expires_at > ?
            ORDER BY t.expires_at
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp(now))
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toTable()) }
            }
        }
    }

    /** Withdraws a table. Only its own host may, which the `WHERE` is what enforces. */
    fun dropTable(tableId: String, accountId: Long): Boolean = transaction { db ->
        db.prepareStatement(
            "DELETE FROM pvp_tables WHERE id = ? AND host_account = ? AND match_id IS NULL",
        ).use { statement ->
            statement.setString(1, tableId)
            statement.setLong(2, accountId)
            statement.executeUpdate() > 0
        }
    }

    /**
     * Claims a table and opens the match it describes, atomically.
     *
     * Shaped exactly like [acceptChallenge], and for the same reason: the table is claimed with
     * `match_id IS NULL` in the `UPDATE`, so two people tapping Join within a few milliseconds of
     * each other settle to **one** match. The second update touches no rows, and the whole
     * transaction rolls back — including the match row the second caller had already inserted.
     *
     * @param open builds the match, or returns null to refuse it — an unaffordable stake, a profile
     *   with no legal deck. A null rolls everything back and leaves the table open.
     */
    fun claimTableAndOpen(
        tableId: String,
        accountId: Long,
        now: Long,
        open: (table: PvpTableRow, joiner: Long) -> PvpMatchRow?,
    ): PvpMatchRow? = transaction { db ->
        val table = db.prepareStatement(
            """
            SELECT t.*, a.username
            FROM pvp_tables t
            JOIN accounts a ON a.id = t.host_account
            WHERE t.id = ? AND t.match_id IS NULL AND t.expires_at > ? AND t.host_account <> ?
            FOR UPDATE OF t
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tableId)
            statement.setTimestamp(2, Timestamp(now))
            statement.setLong(3, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toTable() else null }
        } ?: return@transaction null

        val row = open(table, accountId) ?: return@transaction null
        insertMatch(db, row)

        db.prepareStatement(
            "UPDATE pvp_tables SET match_id = ? WHERE id = ? AND match_id IS NULL",
        ).use { statement ->
            statement.setString(1, row.id)
            statement.setString(2, tableId)
            if (statement.executeUpdate() == 0) return@transaction null
        }
        row
    }

    /** Withdraws every table this account has open, so a match cannot start behind their back. */
    private fun clearTables(db: Connection, accountId: Long) {
        db.prepareStatement(
            "DELETE FROM pvp_tables WHERE host_account = ? AND match_id IS NULL",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }
    }

    // ---- Invitations ------------------------------------------------------

    /** Records an invitation from one account to another. */
    fun challenge(id: String, from: Long, to: Long, terms: PvpTableRequest, expiresAt: Long) =
        transaction { db ->
            db.prepareStatement(
                """
            INSERT INTO pvp_challenges
                (id, from_account, to_account, format, rules, roulette, stake, expires_at,
                 from_deck)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setLong(2, from)
                statement.setLong(3, to)
                statement.setString(4, terms.formatId)
                statement.setString(5, json.encodeToString(GameRules.serializer(), terms.rules))
                statement.setBoolean(6, terms.roulette)
                statement.setString(7, json.encodeToString(PvpStake.serializer(), terms.stake))
                statement.setTimestamp(8, Timestamp(expiresAt))
                // Out of `terms` and into a column of its own, which is where it stays: the
                // recipient is sent the terms and a deck is not one of them.
                statement.setInt(9, terms.deck)
                statement.executeUpdate()
            }
            Unit
        }

    /** The invitations [accountId] has been sent that are still standing. */
    fun challengesFor(accountId: Long, now: Long): List<StoredChallenge> = transaction { db ->
        db.prepareStatement(
            """
            SELECT c.id, c.from_account, c.to_account, c.stake, c.expires_at, c.match_id,
                   f.username, t.username, c.format, c.rules, c.roulette, c.from_deck
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
        // The whole row rather than three of its fields: the challenger's deck joined them in V7
        // and a fourth positional argument is how a caller ends up passing them in the wrong order.
        open: (challenge: StoredChallenge, accepter: Long) -> PvpMatchRow?,
    ): PvpMatchRow? = transaction { db ->
        val challenge = db.prepareStatement(
            """
            SELECT c.id, c.from_account, c.to_account, c.stake, c.expires_at, c.match_id,
                   f.username, t.username, c.format, c.rules, c.roulette, c.from_deck
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

        val row = open(challenge, accountId) ?: return@transaction null
        insertMatch(db, row)

        db.prepareStatement(
            "UPDATE pvp_challenges SET match_id = ? WHERE id = ? AND match_id IS NULL",
        ).use { statement ->
            statement.setString(1, row.id)
            statement.setString(2, challengeId)
            if (statement.executeUpdate() == 0) return@transaction null
        }
        // Neither player is left advertising a table for a match they are now in.
        clearTables(db, challenge.fromAccount)
        clearTables(db, accountId)
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
     *
     * `AWAITING_CLAIM` counts as being in a match, because from the player's side it is: the board
     * is done but they are owed a card and have not taken it. Leaving it out would let a winner
     * start a second match and lose sight of the first — the row is `ORDER BY created_at DESC
     * LIMIT 1`, so a newer match would simply hide it. [claimsFor] is the belt to that brace.
     */
    fun liveMatchFor(accountId: Long): PvpMatchRow? = transaction { db ->
        db.prepareStatement(
            """
            SELECT * FROM pvp_matches
            WHERE (blue_account = ? OR red_account = ?)
              AND status IN ('PLAYING', 'AWAITING_CLAIM')
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMatch() else null }
        }
    }

    /**
     * The match [accountId] should be **looking at**, which is not the same as being in.
     *
     * A match stops being live the instant it is settled, and for [liveMatchFor] that is right —
     * it answers "may this player start something else", and the answer becomes yes immediately.
     *
     * It is wrong for the screen. The player who places the ninth card is handed the finished view
     * as the *response to their move*, so they see the result; their opponent finds out by polling,
     * and by the time they poll the match is settled and gone. They were dropped onto an empty
     * board with a "no match" note on it — a black screen at the exact moment the game owed them a
     * score. So a settled match stays visible for [RESULT_MILLIS] after it ends.
     *
     * The two queries are separate rather than one with a wider window, because a window here must
     * **not** stop the player opening a table: "you are already in a match" for two minutes after
     * one ended would be a worse bug than the one this fixes.
     */
    fun recentMatchFor(accountId: Long, now: Long): PvpMatchRow? = transaction { db ->
        db.prepareStatement(
            """
            SELECT * FROM pvp_matches
            WHERE (blue_account = ? OR red_account = ?)
              AND (
                status IN ('PLAYING', 'AWAITING_CLAIM')
                OR (finished_at IS NOT NULL AND finished_at > ?)
              )
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, accountId)
            statement.setTimestamp(3, Timestamp(now - RESULT_MILLIS))
            statement.executeQuery().use { rows -> if (rows.next()) rows.toMatch() else null }
        }
    }

    /**
     * Every match of [accountId]'s still waiting on a choice.
     *
     * Not the same question as [liveMatchFor], and the difference is the one that costs somebody a
     * card: that returns *the* match, newest first, so an unclaimed prize disappears behind the
     * next game. This returns all of them, so a client can say "you have something to collect".
     */
    fun claimsFor(accountId: Long): List<PvpMatchRow> = transaction { db ->
        db.prepareStatement(
            """
            SELECT * FROM pvp_matches
            WHERE (blue_account = ? OR red_account = ?) AND status = 'AWAITING_CLAIM'
            ORDER BY created_at
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, accountId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toMatch()) }
            }
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

    /**
     * Ends a match. Idempotent: a match already finished is not re-ended.
     *
     * `WHERE status = 'PLAYING'` is the whole of that guarantee, and it is what stops two callers
     * racing to settle — a poll and the sweep, say — from crediting the same match twice. Only one
     * of them updates a row; the other is told `false` and credits nothing.
     *
     * A match ending in [PvpMatchStatus.AWAITING_CLAIM] is **not** finished: `finished_at` stays
     * null, because it has not been paid. [recordClaim] is what closes it.
     */
    fun finish(
        id: String,
        status: PvpMatchStatus,
        forfeitedBy: CardColor? = null,
        claimDeadline: Long? = null,
    ): Boolean = transaction { db ->
        db.prepareStatement(
            """
            UPDATE pvp_matches
            SET status = ?, forfeited_by = ?, turn_deadline = NULL,
                claim_deadline = ?,
                finished_at = CASE WHEN ? THEN NULL ELSE now() END,
                updated_at = now()
            WHERE id = ? AND status = 'PLAYING'
            """.trimIndent(),
        ).use { statement ->
            val awaiting = status == PvpMatchStatus.AWAITING_CLAIM
            statement.setString(1, status.name)
            statement.setString(2, forfeitedBy?.name)
            statement.setTimestamp(3, claimDeadline?.let(::Timestamp))
            statement.setBoolean(4, awaiting)
            statement.setString(5, id)
            statement.executeUpdate() > 0
        }
    }

    /**
     * Records what a winner named and closes the match, or refuses.
     *
     * The same `WHERE status =` gate [finish] uses, for the same reason: a double tap on the last
     * card of a claim must credit once. `status = 'AWAITING_CLAIM'` is true for exactly one of the
     * two requests.
     *
     * @param status what the match becomes — `FINISHED`, or `FORFEITED` when that is how it ended.
     *   Carried through rather than assumed: "you won because they left" survives the claim.
     */
    fun recordClaim(
        id: String,
        claimed: Map<CardColor, List<Int>>,
        status: PvpMatchStatus,
    ): Boolean = transaction { db ->
        db.prepareStatement(
            """
            UPDATE pvp_matches
            SET claimed = ?::jsonb, status = ?, claim_deadline = NULL,
                finished_at = now(), updated_at = now()
            WHERE id = ? AND status = 'AWAITING_CLAIM'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, json.encodeToString(claimed.mapKeys { it.key.name }))
            statement.setString(2, status.name)
            statement.setString(3, id)
            statement.executeUpdate() > 0
        }
    }

    /**
     * Writes what each side was paid.
     *
     * Separate from [finish] because the number does not exist yet when a match ends: it is rolled
     * inside `MatchRewards.creditPvp`, one side at a time, along with whatever boons that profile
     * was holding. So the row is settled first, both profiles are credited, and what they were
     * credited is written back here.
     *
     * No status guard, unlike its neighbours. This cannot double-credit anything — it records a
     * number rather than moving one — and the callers that reach it have already been through a
     * guarded `finish` or `recordClaim`, so a second write would be writing the same values.
     */
    fun recordPayout(id: String, payout: Map<CardColor, Payout>) = transaction { db ->
        db.prepareStatement(
            "UPDATE pvp_matches SET payout = ?::jsonb, updated_at = now() WHERE id = ?",
        ).use { statement ->
            statement.setString(1, json.encodeToString(payout.mapKeys { it.key.name }))
            statement.setString(2, id)
            statement.executeUpdate()
        }
        Unit
    }

    /**
     * Every match whose claim deadline has passed.
     *
     * The safety net for a winner who never came back to take their prize. Without it the loser is
     * left holding a card that is neither theirs nor gone, on a match that is never paid.
     */
    fun claimOverdue(now: Long, limit: Int = SWEEP_LIMIT): List<PvpMatchRow> = transaction { db ->
        db.prepareStatement(
            """
            SELECT * FROM pvp_matches
            WHERE status = 'AWAITING_CLAIM' AND claim_deadline IS NOT NULL AND claim_deadline < ?
            ORDER BY claim_deadline
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
        claimed = json
            .decodeFromString<Map<String, List<Int>>>(getString("claimed"))
            .mapKeys { CardColor.valueOf(it.key) },
        claimDeadline = getTimestamp("claim_deadline")?.time,
        payout = json
            .decodeFromString<Map<String, Payout>>(getString("payout"))
            .mapKeys { CardColor.valueOf(it.key) },
    )

    private fun ResultSet.toTable() = PvpTableRow(
        id = getString("id"),
        hostAccount = getLong("host_account"),
        hostName = getString("username"),
        formatId = getString("format"),
        rules = json.decodeFromString(GameRules.serializer(), getString("rules")),
        roulette = getBoolean("roulette"),
        stake = json.decodeFromString(PvpStake.serializer(), getString("stake")),
        openedAt = getTimestamp("created_at").time,
        expiresAt = getTimestamp("expires_at").time,
        matchId = getString("match_id"),
        hostDeck = getInt("host_deck"),
    )

    private fun ResultSet.toChallenge() = StoredChallenge(
        id = getString(1),
        fromAccount = getLong(2),
        toAccount = getLong(3),
        expiresAt = getTimestamp(5).time,
        matchId = getString(6),
        fromName = getString(7),
        toName = getString(8),
        // `deck` is left at its default rather than filled from column 12. It is read back into
        // [StoredChallenge.fromDeck] instead, so that `terms` is only ever the public offer.
        terms = PvpTableRequest(
            formatId = getString(9),
            rules = json.decodeFromString(GameRules.serializer(), getString(10)),
            roulette = getBoolean(11),
            stake = json.decodeFromString(PvpStake.serializer(), getString(4)),
        ),
        fromDeck = getInt(12),
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

        /**
         * How many tables one lobby page shows.
         *
         * A cap and not a pager, deliberately: a lobby with more than this many open tables is a
         * problem this game does not have, and if it ever does, the answer is filtering by what a
         * player wants to play rather than a second page of strangers.
         */
        const val LOBBY_LIMIT = 100

        /**
         * How long a settled match stays readable, so both players can see how it ended.
         *
         * Long enough to survive the app being killed and reopened — a player who closes the game
         * the moment they lose should still be told they lost — and short enough that it is gone
         * before they would wonder why it is still there.
         */
        const val RESULT_MILLIS = 120_000L
    }
}

/**
 * One open table as stored, with the host's name resolved so a client needs no second lookup.
 *
 * The same shape and the same reasoning as [StoredChallenge]: the lobby lists people, and a list of
 * account ids would be a list the client cannot render.
 */
data class PvpTableRow(
    val id: String,
    val hostAccount: Long,
    val hostName: String,
    val formatId: String,
    val rules: GameRules,
    val roulette: Boolean,
    val stake: PvpStake,
    val openedAt: Long,
    val expiresAt: Long,
    val matchId: String? = null,
    /**
     * Which of the host's decks is waiting here, or [ANY_DECK].
     *
     * Absent from [toWire] on purpose. A table is a public offer and a deck is not part of one —
     * see [PvpTableRequest.deck]. A slot index would tell a joiner nothing they could act on, decks
     * being local to a save, but "it leaks nothing useful" is a weaker guarantee than "it is not
     * sent", and this costs nothing to keep as the stronger one.
     */
    val hostDeck: Int = ANY_DECK,
) {
    fun toWire(): PvpTable = PvpTable(
        id = id,
        hostName = hostName,
        formatId = formatId,
        rules = rules,
        roulette = roulette,
        stake = stake,
        openedAt = openedAt,
        expiresAt = expiresAt,
        matchId = matchId,
    )
}

/** One invitation as stored, with both names resolved so a client needs no second lookup. */
data class StoredChallenge(
    val id: String,
    val fromAccount: Long,
    val toAccount: Long,
    val expiresAt: Long,
    val matchId: String?,
    val fromName: String,
    val toName: String,
    /** Everything about the match being proposed. The same shape a table states. */
    val terms: PvpTableRequest,
    /** The challenger's deck, kept out of [terms] so [toWire] cannot carry it by accident. */
    val fromDeck: Int = ANY_DECK,
) {
    fun toWire(): PvpChallenge = PvpChallenge(
        id = id,
        fromName = fromName,
        toName = toName,
        expiresAt = expiresAt,
        terms = terms,
        matchId = matchId,
    )
}
