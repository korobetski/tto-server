package com.tripletriad.server

import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `stats.overview` counts, and what it refuses to count.
 *
 * ### Why the definitions are worth a test at all
 *
 * `V18__stats_views.sql` exists because three questions in this schema have more than one
 * defensible answer — which of three tables "a match" means, whether the accounts the server
 * plays itself are players, and whether MGP held in an escrow still exists. The views are where
 * those are decided, so a view that quietly starts answering differently is not a broken query: it
 * is a number on the console's dashboard that means something else than it did last month, with
 * nothing on screen to say so.
 *
 * Each assertion below is therefore one of those decisions, written twice — once as SQL in the
 * migration, once here as a fixture and an expected delta.
 *
 * ### Deltas, not totals
 *
 * [Postgres] is one database for the whole suite, by the argument in its own KDoc, so every other
 * test's accounts and matches are in these figures too. Reading the view before and after this
 * test's fixtures measures exactly what this test inserted and nothing else — where an absolute
 * count would be a number that changes whenever a test is added elsewhere.
 */
class StatsViewsTest {

    @Test
    fun theViewsCountPeopleAndNotTheServerPlayingItself() {
        val before = overview()

        Postgres.dataSource.connection.use { db ->
            val seller = person(db, verified = true, seen = true, mgp = SELLER_MGP)
            val bidder = person(db, verified = false, seen = false, mgp = BIDDER_MGP)
            val bot = bot(db, mgp = BOT_MGP)
            val other = bot(db, mgp = BOT_MGP)

            // One credited transcript each. The bot's is the whole point: it is a real row in
            // `matches`, and it is not somebody playing the game.
            credited(db, seller)
            credited(db, bot)

            // A person against a bot is a match a person played; two bots against each other is
            // the lobby-filling machinery talking to itself.
            pvp(db, "stats-mixed-$seller", seller, bot)
            pvp(db, "stats-bots-$bot", bot, other)

            lot(db, "stats-lot-$seller", seller)
            lot(db, "stats-lot-$bot", bot)
            hold(db, "stats-lot-$seller", bidder)
            db.commit()
        }

        val after = overview()

        assertEquals(2L, after.registered - before.registered, "bots were counted as registered")
        assertEquals(1L, after.verified - before.verified, "an unverified address counted as one")
        assertEquals(
            1L,
            after.activeToday - before.activeToday,
            "an account that has never been seen was counted as active",
        )
        assertEquals(1L, after.credited - before.credited, "the bot's credited match was counted")
        assertEquals(
            1L,
            after.pvp - before.pvp,
            "bot against bot was counted, or bot against person was not",
        )
        assertEquals(
            2L,
            after.matchesToday - before.matchesToday,
            "today is not the three kinds added up",
        )
        assertEquals(
            SELLER_MGP + BIDDER_MGP,
            after.purses - before.purses,
            "a bot's purse was counted as money somebody holds",
        )
        assertEquals(
            HOLD,
            after.escrowed - before.escrowed,
            "the live hold is not what the escrowed figure reports",
        )
        assertEquals(
            SELLER_MGP + BIDDER_MGP + HOLD,
            after.total - before.total,
            "the money supply is not purses plus escrow",
        )
        assertEquals(1L, after.lotsLive - before.lotsLive, "a bot's lot counted as a live lot")
    }

    /**
     * The figures, as one row.
     *
     * `now()` is transaction start, so every column here describes the same instant — which is
     * the property the view is built around, and the reason the console asks for one row rather
     * than three.
     */
    private fun overview(): Figures = Postgres.dataSource.connection.use { db ->
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT * FROM stats.overview").use { rows ->
                rows.next()
                Figures(
                    registered = rows.getLong("accounts_registered"),
                    verified = rows.getLong("accounts_verified"),
                    activeToday = rows.getLong("accounts_active_today"),
                    credited = rows.getLong("matches_credited"),
                    pvp = rows.getLong("matches_pvp"),
                    matchesToday = rows.getLong("matches_today"),
                    total = rows.getLong("mgp_total"),
                    purses = rows.getLong("mgp_in_purses"),
                    escrowed = rows.getLong("mgp_escrowed"),
                    lotsLive = rows.getLong("lots_live"),
                )
            }
        }
    }

    private data class Figures(
        val registered: Long,
        val verified: Long,
        val activeToday: Long,
        val credited: Long,
        val pvp: Long,
        val matchesToday: Long,
        val total: Long,
        val purses: Long,
        val escrowed: Long,
        val lotsLive: Long,
    )

    /**
     * An account with a purse, written as the schema holds one: `characters.save` is a document,
     * and the purse is a field in it rather than a column beside it.
     */
    private fun person(db: Connection, verified: Boolean, seen: Boolean, mgp: Long): Long {
        val name = Postgres.freshAccount("stats")
        val id = db.prepareStatement(
            """
            INSERT INTO accounts (username, password_hash, email, email_verified_at, seen_at)
            VALUES (?, 'x', ?, ${if (verified) "now()" else "NULL"},
                    ${if (seen) "now()" else "NULL"})
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, address(name))
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
        execute(db, "INSERT INTO characters (account_id, save) VALUES ($id, '{\"MGP\": $mgp}')")
        return id
    }

    /** An account the server plays itself — an ordinary account, plus the row that says so. */
    private fun bot(db: Connection, mgp: Long): Long {
        val id = person(db, verified = false, seen = true, mgp = mgp)
        execute(db, "INSERT INTO bots (account_id, band) VALUES ($id, 'NOVICE')")
        return id
    }

    private fun credited(db: Connection, account: Long) = execute(
        db,
        "INSERT INTO matches (account_id, opponent_icon_id, format, seed, blue, red, result, " +
            "transcript_hash) VALUES ($account, 'npc', 'standard', 1, 6, 4, 'WIN', 'h$account')",
    )

    private fun pvp(db: Connection, id: String, blue: Long, red: Long) = execute(
        db,
        "INSERT INTO pvp_matches (id, blue_account, red_account, format, rules, seed, blue_hand, " +
            "red_hand, first_player, stake, status) VALUES ('$id', $blue, $red, 'standard', " +
            "'{}', 1, '[]', '[]', 'BLUE', '{}', 'FINISHED')",
    )

    private fun lot(db: Connection, id: String, seller: Long) = execute(
        db,
        "INSERT INTO auction_lots (id, seller_account, card_id, start_price, reserve_price, " +
            "listing_fee, ends_at) VALUES ('$id', $seller, 1001, 100, 400, 20, " +
            "now() + interval '1 hour')",
    )

    /** A bid nobody has refunded or settled: the money is out of a purse and not yet spent. */
    private fun hold(db: Connection, lot: String, bidder: Long) = execute(
        db,
        "INSERT INTO auction_bids (lot_id, bidder_account, amount, fee) " +
            "VALUES ('$lot', $bidder, $BID, $FEE)",
    )

    private fun execute(db: Connection, statement: String) =
        db.createStatement().use { it.execute(statement) }

    private companion object {
        const val SELLER_MGP = 500L
        const val BIDDER_MGP = 250L

        /** Deliberately large: a purse that would be obvious in the totals if it were counted. */
        const val BOT_MGP = 9_999L

        const val BID = 100L
        const val FEE = 5L

        /** What leaves a purse when a bid is placed — the bid and the buyer's fee together. */
        const val HOLD = BID + FEE
    }
}
