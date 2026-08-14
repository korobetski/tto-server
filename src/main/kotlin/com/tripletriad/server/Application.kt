package com.tripletriad.server

import io.ktor.server.application.Application
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("com.tripletriad.server.Application")

/**
 * The entry point.
 *
 * ### The order here is the whole point
 *
 * Configuration, then the pool, then the migration — and only then does the port open. A server
 * that starts listening before it knows its schema is sound will accept a request it cannot serve
 * and answer it with a 500, which looks like an application bug rather than a failed deployment.
 * Failing here instead means a bad deploy is loud, immediate, and does not take traffic.
 */
fun main() {
    val config = try {
        ServerConfig.from()
    } catch (failure: IllegalStateException) {
        // Not rethrown: a stack trace for a missing environment variable buries the one line that
        // says which one. Exiting non-zero is what a supervisor reads anyway.
        logger.error("Refusing to start: {}", failure.message)
        exitProcess(EXIT_MISCONFIGURED)
    }

    logger.info("Starting in {} mode on {}:{}", config.environment, config.host, config.port)

    // Before the database, because it needs nothing and costs milliseconds: a catalog that will
    // not parse should not wait behind a connection attempt to be discovered.
    try {
        Catalogs.preload()
    } catch (failure: Exception) {
        logger.error("Refusing to start: the card or opponent catalog could not be read", failure)
        exitProcess(EXIT_MISCONFIGURED)
    }

    // Two blocks rather than one: opening the pool already connects (see Database.pool), so a
    // wrong host or password fails here, before there is anything to close. Only the second block
    // owns a resource.
    val dataSource = try {
        Database.pool(config.database)
    } catch (failure: Exception) {
        logger.error("Refusing to start: the database could not be reached", failure)
        exitProcess(EXIT_DATABASE_UNREACHABLE)
    }

    try {
        Database.migrate(dataSource)
    } catch (failure: Exception) {
        logger.error("Refusing to start: the schema could not be brought up to date", failure)
        dataSource.close()
        exitProcess(EXIT_MIGRATION_FAILED)
    }

    val registry = prometheusRegistry()
    val server = embeddedServer(Netty, port = config.port, host = config.host) {
        module(dataSource, registry, config.identity)
    }

    // Closes the pool on SIGTERM, which is what `docker stop` and every orchestrator send first.
    // Without it the process is killed with connections still checked out, and Postgres spends its
    // own timeout discovering they are gone.
    server.addShutdownHook {
        logger.info("Shutting down")
        dataSource.close()
    }

    server.start(wait = true)
}

/**
 * Wires the application. Kept separate from [main] so tests can start it without a socket, a
 * shutdown hook or a real Postgres.
 */
fun Application.module(
    dataSource: DataSource,
    registry: PrometheusMeterRegistry,
    identity: ServerIdentity = ServerIdentity(name = "Triple Triad"),
) {
    installObservability(registry)

    // One store for the whole application. It holds no state of its own — the pool does — so this
    // is about there being a single place the SQL lives, not about sharing anything.
    val accounts = AccountStore(dataSource)

    // Separate from `accounts` on the line the two sides of this server fall on: that one owns who
    // a player is and what they have, this one owns what is happening right now. See [PvpStore].
    val pvp = PvpStore(dataSource)

    sweepAbandonedMatches(PvpReferee(Catalogs.cards, Catalogs.formats, accounts, pvp))

    routing {
        healthRoutes(dataSource)
        serverRoutes(identity, dataSource)
        accountRoutes(accounts, ShopTables.shipped())
        matchRoutes(Catalogs.cards, Catalogs.npcs, Catalogs.formats, accounts)
        pvpRoutes(Catalogs.cards, Catalogs.formats, accounts, pvp)

        // Plain text, because that is the format Prometheus scrapes. Not behind authentication
        // yet, and not exposed publicly either — see docs/operations.md.
        get("/metrics") {
            call.respondText(registry.scrape())
        }
    }
}

/**
 * The background pass over matches nobody is looking at.
 *
 * ### It did not exist, and the comment saying it did was wrong
 *
 * `PvpReferee.sweep` has always been written, and until now was called from nowhere at all —
 * `PvpRoutes` claimed "a background sweep still exists for the case where **nobody** looks", and
 * that was simply false. It mattered less than it sounds: a forfeit is settled by whoever polls
 * next, and in a two-player match somebody almost always polls.
 *
 * It matters now. `AWAITING_CLAIM` can be reached by a winner who then closes the app, and the
 * *loser* has no reason to keep polling a match they have lost — so "the first person to look"
 * can be nobody, indefinitely, with a card in limbo and neither side paid.
 *
 * ### Why a coroutine and not a scheduler
 *
 * One server, one process, and a pass that is two indexed queries against a partial index. A cron
 * entry or a job table would be infrastructure to run, monitor and deploy for something that is
 * six lines here. If this ever runs on more than one instance the sweeps will overlap — and they
 * are safe to, because `finish` and `recordClaim` both gate on the status they are changing, so a
 * second sweeper settles nothing twice.
 */
private fun Application.sweepAbandonedMatches(referee: PvpReferee) {
    launch {
        while (isActive) {
            delay(SWEEP_INTERVAL_MILLIS)
            // Never let one bad row stop the loop: a match that cannot be replayed would otherwise
            // take the sweep down with it and strand every match behind it.
            @Suppress("TooGenericExceptionCaught")
            try {
                val forfeited = referee.sweep()
                val claimed = referee.sweepClaims()
                if (forfeited + claimed > 0) {
                    logger.info("Swept {} abandoned and {} unclaimed", forfeited, claimed)
                }
            } catch (failure: Exception) {
                logger.error("The sweep failed; retrying at the next interval", failure)
            }
        }
    }
}

/**
 * How often the sweep runs.
 *
 * Comfortably under `CLAIM_MILLIS` and `DEADLINE_MILLIS` — both two minutes and up — so a deadline
 * is never more than this long overdue, and far enough apart that the queries are noise.
 */
private const val SWEEP_INTERVAL_MILLIS = 30_000L

// sysexits.h conventions: 78 is a configuration error, 70 an internal failure, 69 a service the
// process depends on being unavailable. Distinct codes so a supervisor's log says which of the
// three happened without anyone reading the stack trace.
private const val EXIT_MISCONFIGURED = 78
private const val EXIT_MIGRATION_FAILED = 70
private const val EXIT_DATABASE_UNREACHABLE = 69
