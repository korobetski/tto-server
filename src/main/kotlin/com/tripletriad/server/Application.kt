package com.tripletriad.server

import com.tripletriad.protocol.Unlocks
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
        module(dataSource, registry, config.identity, config.mail.mailer(), config.unlocks)
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
    // Both defaulted so the test seam stays a two-argument call. The defaults are the safe ones:
    // no mail leaves the process, and the thresholds are `:core`'s own.
    mailer: Mailer = Mailer.Disabled,
    unlocks: Unlocks = Unlocks(),
) {
    // One store for the whole application. It holds no state of its own — the pool does — so this
    // is about there being a single place the SQL lives, not about sharing anything.
    //
    // Constructed **before** the plugins, which it did not have to be until the rate limiter began
    // keying its buckets on the account rather than on the bearer token — see `installRateLimits`.
    val accounts = AccountStore(dataSource)

    installObservability(registry, accounts)

    // Separate from `accounts` on the line the two sides of this server fall on: that one owns who
    // a player is and what they have, this one owns what is happening right now. See [PvpStore].
    val pvp = PvpStore(dataSource)

    // The same line again, for the matches the server itself plays the opponent in. Its own store
    // rather than a wider `PvpStore`: the two tables share a shape and almost nothing else — no
    // lobby, no invitations, no wager, and no deadline, because a program is never waiting.
    val pve = PveStore(dataSource)

    // The codes mailed out for confirmation and password resets. Its own store for the same
    // reason the two above are: a different table, and one whose rows live for ten minutes
    // rather than for years.
    val codes = CodeStore(dataSource)

    sweepAbandonedMatches(
        PvpReferee(Catalogs.cards, Catalogs.formats, accounts, pvp),
        accounts,
        codes,
    )

    routing {
        healthRoutes(dataSource)
        serverRoutes(identity, dataSource, unlocks)
        accountRoutes(accounts, ShopTables.shipped(), CodeChannel(codes, mailer))
        matchRoutes(Catalogs.cards, Catalogs.npcs, Catalogs.formats, accounts)
        pvpRoutes(Catalogs.cards, Catalogs.formats, accounts, pvp, unlocks = unlocks)
        pveRoutes(Catalogs.cards, Catalogs.npcs, Catalogs.formats, accounts, pve)

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
private fun Application.sweepAbandonedMatches(
    referee: PvpReferee,
    accounts: AccountStore,
    codes: CodeStore,
) {
    launch {
        var sinceOperationPrune = 0L
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

                // Riding along on the loop that already exists rather than getting a scheduler of
                // its own, for the reason the loop itself gives — but on its own, much longer
                // interval: nothing here is urgent, and a `DELETE` over a table this size every
                // thirty seconds would be the most expensive thing this process does.
                sinceOperationPrune += SWEEP_INTERVAL_MILLIS
                if (sinceOperationPrune >= OPERATION_PRUNE_INTERVAL_MILLIS) {
                    sinceOperationPrune = 0
                    val forgotten = accounts.pruneOperations(
                        System.currentTimeMillis() - OPERATION_LIFETIME_MILLIS,
                    )
                    if (forgotten > 0) logger.info("Forgot {} applied operations", forgotten)

                    // On the same slow interval, and for the same reason. Expired codes are
                    // already refused on sight — see `CodeStore.consume`, which checks the
                    // expiry rather than trusting the row to be gone — so this is tidiness, not
                    // correctness, and tidiness does not need to run every thirty seconds.
                    val stale = codes.purgeExpired(System.currentTimeMillis())
                    if (stale > 0) logger.info("Purged {} expired codes", stale)
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

/**
 * How long an applied operation is remembered.
 *
 * **Thirty days, matching the session lifetime**, and the number is a floor rather than a target.
 * Forgetting an operation un-guards it: a client that never saw the answer and retries afterwards
 * has its intent applied a second time, which is the double purchase `AccountStore.applyOnce`
 * exists to prevent. So the question is not "how long is worth keeping" but "how long could a
 * client hold an unacknowledged operation", and a session is the longest a client can go without
 * signing in again.
 *
 * Raising it costs storage — one whole `PlayerState` per row. Lowering it costs correctness.
 */
private const val OPERATION_LIFETIME_DAYS = 30L
private const val OPERATION_LIFETIME_MILLIS = OPERATION_LIFETIME_DAYS * 24 * 60 * 60 * 1000

/** Once an hour. The rows being deleted are already a month old; nothing is waiting for them. */
private const val OPERATION_PRUNE_INTERVAL_MILLIS = 60 * 60 * 1000L

// sysexits.h conventions: 78 is a configuration error, 70 an internal failure, 69 a service the
// process depends on being unavailable. Distinct codes so a supervisor's log says which of the
// three happened without anyone reading the stack trace.
private const val EXIT_MISCONFIGURED = 78
private const val EXIT_MIGRATION_FAILED = 70
private const val EXIT_DATABASE_UNREACHABLE = 69
