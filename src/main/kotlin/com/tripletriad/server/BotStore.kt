package com.tripletriad.server

import com.tripletriad.model.GameSave
import com.tripletriad.model.NpcLevel
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * The roster of accounts this server plays itself.
 *
 * ### Its own store, on the line the other three are drawn along
 *
 * `AccountStore` owns who a player is and what they have, `PvpStore` and `PveStore` own what is
 * happening right now — and this owns *which accounts are not people*. It is a different question
 * from all three, asked by one caller, and answered by one small table. See `V16__bots.sql`.
 *
 * It deliberately holds no profile of its own: a bot's cards, purse and level live in `characters`
 * exactly as a player's do, and [progress] reads them from there rather than keeping a second copy
 * that could disagree.
 */
// `MagicNumber` counts JDBC's positional parameter indices, for the reason `PveStore` gives.
@Suppress("MagicNumber")
class BotStore(
    private val dataSource: DataSource,
    private val json: Json = SaveJson,
) {

    /**
     * Enrols an existing account as a bot, or answers false if it already is one.
     *
     * Idempotent by the primary key rather than by a read: two directors starting at once — a
     * deployment rolling over, say — must not turn one account into two rows or fail the boot of
     * the second. `ON CONFLICT DO NOTHING` makes the second call a no-op that says so.
     */
    fun enrol(accountId: Long, band: NpcLevel, dueAt: Long): Boolean = transaction { db ->
        db.prepareStatement(
            """
            INSERT INTO bots (account_id, band, next_action_at)
            VALUES (?, ?, ?)
            ON CONFLICT (account_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, band.name)
            statement.setTimestamp(3, Timestamp(dueAt))
            statement.executeUpdate() == 1
        }
    }

    /**
     * The bots the director may act for, most overdue first.
     *
     * Bounded by [limit] rather than returning the roster: one pass of the loop should do a bounded
     * amount of work whatever the roster size, and a bot that misses this pass is picked up by the
     * next one a couple of seconds later. Nothing here is a deadline — the deadlines that do exist
     * belong to live PvP matches, and those are two minutes and a half wide.
     */
    fun due(now: Long, limit: Int = DUE_LIMIT): List<Bot> = transaction { db ->
        db.prepareStatement(
            """
            SELECT account_id, band, next_action_at
            FROM bots
            WHERE next_action_at <= ?
            ORDER BY next_action_at
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp(now))
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toBot()) }
            }
        }
    }

    /** Puts a bot to sleep until [dueAt]. The only thing that paces the director. */
    fun schedule(accountId: Long, dueAt: Long) = transaction { db ->
        db.prepareStatement("UPDATE bots SET next_action_at = ? WHERE account_id = ?").use {
            it.setTimestamp(1, Timestamp(dueAt))
            it.setLong(2, accountId)
            it.executeUpdate()
        }
    }

    /** How many bots are enrolled, which is what decides whether the roster needs filling. */
    fun count(): Int = transaction { db ->
        db.prepareStatement("SELECT count(*) FROM bots").use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    }

    /**
     * Every bot with the profile it has built, for the statistics.
     *
     * ### Why the save and not the `matches` table
     *
     * Because the numbers worth watching are already in it. `MatchRewards.credit` maintains
     * `stats`, `level`, `mgp` and `cards` on every settlement, so a join to `characters` answers
     * "how far has this bot got" in one row per bot — where the same question asked of `matches`
     * is an aggregate over a table that grows without bound and is scanned on every scrape.
     *
     * The history in `matches` is still the better source for anything asked *once* — what a
     * particular opponent pays, how a band's win rate moved across a release. This is the source
     * for what is sampled *continuously*.
     *
     * A row whose profile will not parse is dropped rather than failing the read: one corrupt
     * document must not take the whole metric down.
     */
    fun progress(): List<BotProgress> = transaction { db ->
        db.prepareStatement(
            """
            SELECT b.account_id, b.band, c.save
            FROM bots b
            JOIN characters c ON c.account_id = b.account_id
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) rows.toProgress()?.let(::add) }
            }
        }
    }

    private fun ResultSet.toBot() = Bot(
        accountId = getLong("account_id"),
        band = bandOf(getString("band")),
        nextActionAt = getTimestamp("next_action_at").time,
    )

    // A profile that will not parse is one bot missing from a chart, which is the right cost. The
    // alternative — letting it throw — takes every other bot's numbers with it.
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun ResultSet.toProgress(): BotProgress? {
        val save = try {
            json.decodeFromString<GameSave>(getString("save"))
        } catch (failure: Exception) {
            return null
        }
        return BotProgress(
            accountId = getLong("account_id"),
            band = bandOf(getString("band")),
            save = save,
        )
    }

    /**
     * The stored band, or [NpcLevel.EXPERT] when this build does not know the name.
     *
     * The column is free text on purpose — `V16__bots.sql` says why a CHECK constraint would be a
     * second copy of an enum that lives in `:core`. The cost of that is exactly this function, and
     * the fallback is the strong band rather than the weak one: a bot whose band was written by a
     * newer build should play *well* while the mismatch is noticed, not become a free win.
     */
    private fun bandOf(stored: String?): NpcLevel =
        NpcLevel.entries.firstOrNull { it.name == stored } ?: NpcLevel.EXPERT

    // As wide as it can be, for the reason `AccountStore.transaction` gives.
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
         * How many bots one pass of the director may act for.
         *
         * Small, because each action can cost a depth-five search and the loop runs on the same
         * process that serves requests. A roster larger than this is not starved — the bots that
         * miss a pass are the most overdue on the next one, which is the order [due] returns.
         */
        const val DUE_LIMIT = 8
    }
}

/** One enrolled bot: the account it plays as, how hard it plays, and when it may act. */
data class Bot(val accountId: Long, val band: NpcLevel, val nextActionAt: Long)

/** One bot's profile as it stands, which is what the progression metrics are read from. */
data class BotProgress(val accountId: Long, val band: NpcLevel, val save: GameSave) {
    /** Distinct cards owned. The collection's *breadth*, which is what a bot is building. */
    val collection: Int get() = save.cards.count { (_, copies) -> copies > 0 }
}
