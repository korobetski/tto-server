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
