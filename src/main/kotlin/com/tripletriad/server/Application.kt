package com.tripletriad.server

import com.tripletriad.model.NpcLevel
import com.tripletriad.protocol.AuctionPolicy
import com.tripletriad.protocol.PvpStakePolicy
import com.tripletriad.protocol.Unlocks
import io.ktor.server.application.Application
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Gauge
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

    // After the migration, because `admins` has to exist, and before the port opens, because a
    // console nobody can sign in to is a misconfiguration rather than a runtime surprise. It
    // creates nothing when the environment names nobody, which is every boot after the first.
    try {
        ensureFirstAdministrator(AdminStore(dataSource), config.admin, logger::info)
    } catch (failure: Exception) {
        logger.error("Refusing to start: the first administrator could not be created", failure)
        dataSource.close()
        exitProcess(EXIT_MISCONFIGURED)
    }

    val registry = prometheusRegistry()
    val server = embeddedServer(Netty, port = config.port, host = config.host) {
        module(
            dataSource,
            registry,
            config.identity,
            config.mail.mailer(),
            config.unlocks,
            config.auction,
            config.stakes,
            config.bots,
        )
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
// Six capabilities and one composition root. Grouping two of them behind a holder to satisfy
// the counter would put an indirection between `main` and the thing it configures, and the
// tests that call this with a bare DataSource would gain a wrapper to construct — the same
// argument `PveRoutes` and `PvpRoutes` make above their own suppressions.
@Suppress("LongParameterList")
fun Application.module(
    dataSource: DataSource,
    registry: PrometheusMeterRegistry,
    identity: ServerIdentity = ServerIdentity(name = "Triple Triad"),
    // Both defaulted so the test seam stays a two-argument call. The defaults are the safe ones:
    // no mail leaves the process, and the thresholds are `:core`'s own.
    mailer: Mailer = Mailer.Disabled,
    unlocks: Unlocks = Unlocks(),
    auction: AuctionPolicy = AuctionPolicy(),
    stakes: PvpStakePolicy = PvpStakePolicy(),
    // Defaulted **off**, so the test seam and every existing caller get a server that plays
    // nobody. See `BotPolicy`, which says why that is the only safe default for this one.
    bots: BotPolicy = BotPolicy(),
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

    // The administration console's own store. Separate from `accounts` on the line `V19__admin.sql`
    // draws: that one owns who a player is, this one owns who an *administrator* is, and the two
    // share no credential at all.
    val admins = AdminStore(dataSource)

    // The auction house. Its own store for the reason the three above have theirs, and one
    // more: it is the only thing here that writes *two* profiles in one transaction, which is why
    // it needs `AccountStore` rather than the pool alone.
    val auctions = AuctionStore(dataSource, accounts, Catalogs.cards.byId, unlocks, auction)

    // The referee the sweep uses, and the one the director plays through. One instance rather than
    // two: it holds no state of its own, and a second would be a second copy of the deployment's
    // stake policy to keep in step.
    val pvpReferee = PvpReferee(Catalogs.cards, Catalogs.formats, accounts, pvp, stakes)

    sweepAbandonedMatches(pvpReferee, accounts, codes, auctions, admins)

    // The accounts this server plays itself. Inert unless the deployment asked for them.
    playBots(BotStore(dataSource), accounts, pve, pvp, pvpReferee, registry, bots, unlocks, stakes)

    routing {
        healthRoutes(dataSource)
        serverRoutes(identity, dataSource, unlocks, auction, stakes)
        accountRoutes(
            accounts,
            ShopTables.shipped(),
            CodeChannel(codes, mailer),
            auctions = auctions,
        )
        matchRoutes(Catalogs.cards, Catalogs.npcs, Catalogs.formats, accounts)
        pvpRoutes(
            Catalogs.cards,
            Catalogs.formats,
            accounts,
            pvp,
            unlocks = unlocks,
            stakes = stakes,
        )
        pveRoutes(Catalogs.cards, Catalogs.npcs, Catalogs.formats, accounts, pve)
        auctionRoutes(auctions, accounts, unlocks)

        // The administration console. Reachable only through `admintto.moebiuscore.fr` in the
        // deployment — the other two hosts answer 404 for the prefix — and mounted unconditionally
        // here, because "is there an administrator" is a question about the table rather than about
        // this process's configuration.
        adminAuthRoutes(admins, identity)

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
// Five collaborators, each owning one table the pass touches. Grouping them behind a holder to
// satisfy the counter would put an indirection between this function and the list of things it
// sweeps, which is the whole of what it is — the argument `module` makes above its own suppression.
@Suppress("LongParameterList")
private fun Application.sweepAbandonedMatches(
    referee: PvpReferee,
    accounts: AccountStore,
    codes: CodeStore,
    auctions: AuctionStore,
    admins: AdminStore,
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
                // The third deadline: a match paired between two players who never opened it.
                // Unlike the other two this one really does need the loop — a match nobody came
                // to is a match nobody is polling, so "the first person to look" is nobody.
                val unattended = referee.sweepPairing()
                if (forfeited + claimed + unattended > 0) {
                    logger.info(
                        "Swept {} abandoned, {} unclaimed and {} unattended",
                        forfeited,
                        claimed,
                        unattended,
                    )
                }

                // On the same loop, and it is the loop's most load-bearing passenger. A PvP
                // forfeit is settled by whoever polls next and somebody usually does; nobody polls
                // a lot. An auction that ended and was never swept holds the buyer's money, the
                // seller's card and both players' attention indefinitely — so here "the first
                // person to look" is genuinely nobody, and this is the only thing that closes it.
                val settled = auctions.sweep()
                if (settled > 0) logger.info("Settled {} auction lots", settled)

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

                    // And on the same interval, for the same reason again: `AdminStore.session`
                    // refuses an expired row in its `WHERE` clause, so nothing depends on this
                    // having run. What it buys is that a table of credentials does not accumulate
                    // rows nobody will ever accept — the least interesting kind of tidiness, and
                    // the one it is least excusable to skip in a table like this.
                    val ended = admins.sweepSessions()
                    if (ended > 0) logger.info("Ended {} expired administrator sessions", ended)
                }
            } catch (failure: Exception) {
                logger.error("The sweep failed; retrying at the next interval", failure)
            }
        }
    }
}

/**
 * The loop that plays this server's own accounts.
 *
 * ### Its own loop, and not a passenger on the sweep
 *
 * `sweepAbandonedMatches` runs every thirty seconds because nothing it does is urgent — a forfeit
 * settled thirty seconds late is a forfeit. This is the one background thing here that *is* timed
 * against a person: "nobody joined the table for forty-five seconds" has to mean forty-five and
 * not up to seventy-five, and a bot's turn in a live match is answered while the other player is
 * still looking at the board. So it runs on [BotPolicy.tickMillis], which is two seconds.
 *
 * Two coroutines rather than one for the same reason the sweep is a coroutine rather than a cron
 * entry: it is a `launch` in the application's own scope, it stops when the application stops, and
 * there is nothing to deploy, monitor or schedule.
 *
 * ### It is safe to run twice, and that is not an accident
 *
 * A second instance would take the same rows: `BotStore.due` is not a lock, so two directors could
 * both act for one bot. Every action underneath is already guarded against exactly that — a second
 * placement is refused by `PvpStore.appendMove`'s expected-move-count, a second join by
 * `claimTableAndOpen`, a second deal by `pve_matches_live_idx`, a second settlement by `finish`.
 * The bot would move sooner than its cadence intended, which is the whole of the harm.
 */
// Nine, and every one of them is something the director cannot look up for itself. Extracted from
// `module` rather than inlined there because that function is a list of what this application is,
// and this is one line of it.
@Suppress("LongParameterList")
private fun Application.playBots(
    bots: BotStore,
    accounts: AccountStore,
    pve: PveStore,
    pvp: PvpStore,
    pvpReferee: PvpReferee,
    registry: PrometheusMeterRegistry,
    policy: BotPolicy,
    unlocks: Unlocks,
    stakes: PvpStakePolicy,
) {
    if (!policy.enabled) return

    val director = BotDirector(
        cards = Catalogs.cards,
        npcs = Catalogs.npcs,
        formats = Catalogs.formats,
        starters = Catalogs.starters,
        accounts = accounts,
        bots = bots,
        pve = pve,
        pvp = pvp,
        // Its own referee rather than a shared one: `pveRoutes` builds one for the routes and this
        // is the same class with the same collaborators, holding no state either way.
        pveReferee = PveReferee(Catalogs.cards, Catalogs.npcs, Catalogs.formats, accounts, pve),
        pvpReferee = pvpReferee,
        policy = policy,
        unlocks = unlocks,
        stakes = stakes,
    )
    registerBotMetrics(registry, bots)

    launch {
        logger.info(
            "Playing {} bots at band {} in {}",
            policy.count,
            policy.band,
            policy.formatId,
        )
        while (isActive) {
            delay(policy.tickMillis)
            // The same net the sweep casts, for the same reason: one unplayable board must not end
            // the loop and strand every bot behind it.
            @Suppress("TooGenericExceptionCaught")
            try {
                val enrolled = director.ensureRoster()
                if (enrolled > 0) logger.info("Enrolled {} bots", enrolled)
                director.tick()
            } catch (failure: Exception) {
                logger.error("The bot pass failed; retrying at the next tick", failure)
            }
        }
    }
}

/**
 * The gauges that answer "are the bots getting anywhere".
 *
 * ### Gauges over a live read, rather than counters incremented as things happen
 *
 * A bot's progress is *state*, not a stream of events: its level, its purse and how wide its
 * collection is are all in its profile, maintained by `MatchRewards.credit` on every settlement.
 * A counter here would be a second copy of numbers `:core` already keeps, and the first thing to
 * disagree with them after a restart.
 *
 * What the time dimension costs, therefore, is nothing: Prometheus samples these and *is* the
 * history. "How fast does a bot at this band accumulate cards" is a rate over a series that exists
 * the moment this is scraped, and no table here has to store it.
 *
 * ### What it costs to scrape
 *
 * One query per scrape, over `bots` joined to `characters` — a row per bot, indexed by primary
 * key, with no aggregate over `matches` anywhere. It is bounded by [BotPolicy.count] rather than
 * by how long the deployment has been running, which is the property that makes it safe to sample
 * every fifteen seconds forever.
 *
 * `Micrometer` calls the supplier once per gauge per scrape, so the snapshot is taken once and the
 * gauges read from it — otherwise five gauges per band would be five queries.
 */
private fun registerBotMetrics(registry: PrometheusMeterRegistry, bots: BotStore) {
    val snapshot = BotSnapshot(bots)

    Gauge.builder("tto.bots.count", snapshot) { it.read().size.toDouble() }
        .description("How many accounts this server plays itself")
        // **Micrometer holds a gauge's subject weakly by default.** Nothing else references this
        // snapshot, so without the strong reference it is collectable the moment registration
        // returns and every gauge below reports NaN — silently, and only in a long-running process
        // where a collection has actually happened. This is the one line standing between a
        // working chart and one that goes blank overnight.
        .strongReference(true)
        .register(registry)

    NpcLevel.entries.forEach { band ->
        listOf(
            Metric("tto.bots.level", "Mean level of the bots at this band") { it.save.level },
            Metric("tto.bots.mgp", "Mean purse of the bots at this band") { it.save.mgp },
            Metric("tto.bots.collection", "Mean distinct cards owned") { it.collection },
            Metric("tto.bots.matches", "Mean matches played") { it.save.stats.played },
            Metric("tto.bots.wins", "Mean matches won") { it.save.stats.wins },
        ).forEach { metric ->
            Gauge.builder(metric.name, snapshot) { it.mean(band, metric.of) }
                .tag("band", band.name)
                .description(metric.description)
                .strongReference(true)
                .register(registry)
        }
    }
}

/** One gauge's name, help text and the field it reads. See [registerBotMetrics]. */
private data class Metric(
    val name: String,
    val description: String,
    val of: (BotProgress) -> Int,
)

/**
 * One read of the roster, reused across the gauges of a single scrape.
 *
 * The window is deliberately shorter than any sane scrape interval and longer than the burst of
 * calls one scrape makes: it exists to collapse *those*, not to cache. A stale reading here would
 * be a chart that lags, which is the one thing a progression chart must not do.
 */
private class BotSnapshot(private val bots: BotStore) {
    private var taken = 0L
    private var progress: List<BotProgress> = emptyList()

    @Synchronized
    fun read(): List<BotProgress> {
        val now = System.currentTimeMillis()
        if (now - taken > WINDOW_MILLIS) {
            progress = bots.progress()
            taken = now
        }
        return progress
    }

    /** The mean of [of] over one band, or zero when the band has no bots in it. */
    fun mean(band: NpcLevel, of: (BotProgress) -> Int): Double {
        val values = read().filter { it.band == band }
        return if (values.isEmpty()) 0.0 else values.sumOf { of(it) }.toDouble() / values.size
    }

    private companion object {
        const val WINDOW_MILLIS = 1_000L
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
