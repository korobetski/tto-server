package com.tripletriad.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * The connection pool and the schema, which are two problems and one lifecycle.
 *
 * ### Why migrations run at start-up, in the process
 *
 * The alternative — a separate migration step in the deploy pipeline — is what larger systems do,
 * and it is right as soon as there is more than one instance: two servers starting at once would
 * both try to migrate. Flyway takes a lock, so that is safe rather than corrupting, but it is
 * still a race worth not having.
 *
 * For a single instance, migrating in-process buys something more valuable: **the schema and the
 * code that expects it are deployed as one unit**. There is no window in which a new binary runs
 * against an old schema because someone forgot the manual step. When a second instance arrives,
 * this is the first thing that must change; the comment is here so that is a decision rather than
 * a surprise.
 */
object Database {

    private val logger = LoggerFactory.getLogger(Database::class.java)

    /**
     * Opens the pool — and, with it, the first connection.
     *
     * Hikari is *not* lazy by default: the constructor runs a fail-fast check and throws if the
     * database cannot be reached with these credentials. That is the behaviour we want, and it is
     * worth writing down because it moves the first plausible failure earlier than the name
     * "open a pool" suggests. Callers must guard this, not just [migrate].
     */
    fun pool(config: DatabaseConfig): HikariDataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            // The pool hands out connections that are in a transaction; committing is the caller's
            // decision. Auto-commit would silently make every statement its own transaction, which
            // is precisely wrong for a transcript that must be accepted or rejected as a whole.
            isAutoCommit = false
            // Fail a request that cannot get a connection rather than let it wait indefinitely: a
            // request queue that never times out turns a brief database hiccup into an outage that
            // outlives it.
            connectionTimeout = CONNECTION_TIMEOUT_MILLIS
            poolName = "tto-pool"
        }
        return HikariDataSource(hikari)
    }

    /**
     * Brings the schema up to date, and returns how many migrations were applied.
     *
     * This is also the server's first real contact with the database, so a wrong URL, a wrong
     * password or an unreachable host surfaces **here**, at start-up, rather than on the first
     * request from the first player.
     */
    fun migrate(dataSource: DataSource): Int {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            // Refuse to run against a database that already has objects but no Flyway history.
            // The permissive setting (`baselineOnMigrate`) is a convenience that, on the one day
            // it matters, adopts a schema nobody described and calls it version 1.
            .baselineOnMigrate(false)
            .load()

        val applied = flyway.migrate().migrationsExecuted
        logger.info(
            "Schema is at version {} ({} migration(s) applied)",
            currentVersion(flyway),
            applied,
        )
        return applied
    }

    private fun currentVersion(flyway: Flyway): String =
        flyway.info().current()?.version?.version ?: "empty"

    private const val MIGRATION_LOCATION = "classpath:db/migration"
    private const val CONNECTION_TIMEOUT_MILLIS = 5_000L
}
