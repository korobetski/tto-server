package com.tripletriad.server

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The schema comes up against a real Postgres.
 *
 * ### Why a container and not an in-memory database
 *
 * H2 in "Postgres compatibility mode" is the tempting shortcut and it defeats the purpose: it
 * accepts a dialect Postgres would reject and rejects one Postgres accepts, so a migration can
 * pass here and fail on deployment — which is the single failure this test exists to catch.
 *
 * ### The image is pinned to the one `compose.yaml` runs
 *
 * Testing against a different major version than production would test the wrong thing. When the
 * container's image changes, this constant changes with it.
 */
class MigrationTest {

    @Test
    fun theSchemaMigratesFromEmptyAndIsIdempotent() {
        withPostgres { config ->
            Database.pool(config).use { pool ->
                Database.migrate(pool)

                // Running twice is what every restart and every redeploy does. A migration set
                // that is not idempotent turns an ordinary restart into an outage.
                val secondPass = Database.migrate(pool)
                assertEquals(0, secondPass, "a second migration pass applied something")

                assertTrue(
                    tableExists(pool, "flyway_schema_history"),
                    "Flyway left no history table, so nothing actually ran",
                )

                // V4 claims to have replaced the quick queue with open tables. Asserted rather
                // than trusted, because a migration that half-ran leaves a server talking to a
                // schema it thinks it has: `pvp_queue` still present would mean the drop was
                // skipped, and `pvp_tables` absent would mean every lobby request is a 500.
                assertFalse(
                    tableExists(pool, "pvp_queue"),
                    "the quick queue survived the migration that removes it",
                )
                assertTrue(tableExists(pool, "pvp_tables"), "V4 did not create pvp_tables")

                // V14. The two tables the auction house lives in; the invariants they carry are
                // asserted by the test below, which is where a schema that came up wrong shows.
                assertTrue(tableExists(pool, "auction_lots"), "V14 did not create auction_lots")
                assertTrue(tableExists(pool, "auction_bids"), "V14 did not create auction_bids")
            }
        }
    }

    /**
     * The start-up contract: an unreachable database stops the process, it does not degrade it.
     *
     * This is what the database wiring buys while `db/migration` is still empty — the server finds
     * out at start-up that it cannot reach or write to its database, rather than on a player's
     * first request.
     *
     * Note *where* the failure comes from. Hikari's constructor performs a fail-fast connection
     * check, so a dead host throws at [Database.pool] and never reaches [Database.migrate] — which
     * is why `main` guards the two separately. This test found that; it is asserted here so a
     * future change to `connectionTimeout` or to Hikari's defaults cannot quietly move the failure
     * to somewhere nothing is watching.
     */
    @Test
    fun anUnreachableDatabaseFailsWhenThePoolIsOpened() {
        val unreachable = DatabaseConfig(
            url = "jdbc:postgresql://127.0.0.1:1/nowhere",
            user = "nobody",
            password = "nobody",
            maxPoolSize = 1,
        )

        val failure = runCatching {
            Database.pool(unreachable).use { Database.migrate(it) }
        }.exceptionOrNull()

        assertTrue(failure != null, "opening a pool against a dead host reported success")
    }

    /**
     * The two invariants V14 asks the database to hold, held against a real database.
     *
     * Both are things the routes also check, and that is the point of checking them again here:
     * application code is what a bug gets past, and these two are the ones where getting past it
     * costs somebody money. A second live hold on a lot is two purses debited for one card — what
     * a bidder tapping twice would produce if the idempotency key ever missed. A bid from the
     * seller is shill bidding, whatever the intent behind it.
     *
     * Written as raw SQL rather than through the store deliberately: a store that refuses to issue
     * the statement proves nothing about the schema, and the schema is what has to hold the day a
     * future store forgets.
     */
    @Test
    fun theAuctionLedgerRefusesASecondHoldAndASellersOwnBid() {
        withPostgres { config ->
            Database.pool(config).use { pool ->
                Database.migrate(pool)

                val (seller, bidder) = pool.connection.use { db ->
                    val sellerId = insertAccount(db, "seller")
                    val bidderId = insertAccount(db, "bidder")
                    db.createStatement().use { sql ->
                        sql.execute(
                            "INSERT INTO auction_lots (id, seller_account, card_id, " +
                                "start_price, reserve_price, listing_fee, ends_at) VALUES " +
                                "('lot-1', $sellerId, 1001, 100, 400, 20, " +
                                "now() + interval '1 hour')",
                        )
                        sql.execute(
                            "INSERT INTO auction_bids (lot_id, bidder_account, amount, fee) " +
                                "VALUES ('lot-1', $bidderId, 100, 3)",
                        )
                    }
                    db.commit()
                    sellerId to bidderId
                }

                assertTrue(
                    refused(
                        pool,
                        "INSERT INTO auction_bids (lot_id, bidder_account, amount, fee) " +
                            "VALUES ('lot-1', $bidder, 200, 6)",
                    ),
                    "a second unrefunded, unsettled bid was accepted on one lot",
                )

                assertTrue(
                    refused(
                        pool,
                        "UPDATE auction_lots SET top_bid = 100, top_bidder = $seller " +
                            "WHERE id = 'lot-1'",
                    ),
                    "the seller was recorded as their own top bidder",
                )
            }
        }
    }

    /**
     * Whether Postgres refuses [statement] — on a connection of its own, which is the whole point.
     *
     * The pool hands out connections with `autoCommit = false`, so a statement that fails aborts
     * its transaction and *every* statement after it on that connection fails too. A second probe
     * sharing the first one's connection therefore passes whether or not the constraint it is
     * asking about exists. This was found by mutation: with the shill-bid check deleted from V14,
     * the shared-connection version of the test above still went green.
     */
    private fun refused(pool: javax.sql.DataSource, statement: String): Boolean = runCatching {
        pool.connection.use { db ->
            db.createStatement().use { it.execute(statement) }
            db.commit()
        }
    }.isFailure

    /** @return the generated id, which the auction rows have to reference. */
    private fun insertAccount(db: java.sql.Connection, name: String): Long = db.prepareStatement(
        "INSERT INTO accounts (username, password_hash) VALUES (?, 'x') RETURNING id",
    ).use { statement ->
        statement.setString(1, name)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }

    private fun tableExists(pool: javax.sql.DataSource, name: String): Boolean =
        pool.connection.use { connection ->
            connection.metaData.getTables(null, null, name, null).use { it.next() }
        }

    private fun withPostgres(block: (DatabaseConfig) -> Unit) {
        PostgreSQLContainer<Nothing>(DockerImageName.parse(POSTGRES_IMAGE)).use { container ->
            container.start()
            block(
                DatabaseConfig(
                    url = container.jdbcUrl,
                    user = container.username,
                    password = container.password,
                    maxPoolSize = 2,
                ),
            )
        }
    }

    private companion object {
        /** Kept identical to the image in `compose.yaml`. */
        const val POSTGRES_IMAGE = "postgres:17-alpine"
    }
}
