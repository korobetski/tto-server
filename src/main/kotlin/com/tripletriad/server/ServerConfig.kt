package com.tripletriad.server

import com.tripletriad.model.NpcLevel
import com.tripletriad.protocol.AppVersion
import com.tripletriad.protocol.AuctionPolicy
import com.tripletriad.protocol.ClientPlatform
import com.tripletriad.protocol.ClientRelease
import com.tripletriad.protocol.PvpStakePolicy
import com.tripletriad.protocol.Unlocks
import org.slf4j.LoggerFactory

/**
 * Everything the process needs to start, read once from the environment.
 *
 * ### Why the environment and not a file
 *
 * The same artifact has to run on a laptop, in CI and on a host, and the only thing that differs
 * between them is configuration. Environment variables are what a container orchestrator, a
 * systemd unit and `docker compose` all already speak, so there is no format to invent and no
 * secret to check in.
 *
 * ### Why it is read once, into a value
 *
 * Reading `System.getenv` at the point of use scatters the contract across the codebase and makes
 * a missing variable a runtime failure at some unpredictable later moment. Gathering it here means
 * the process either starts correctly configured or **does not start at all**, which is the only
 * behaviour that is honest about a misconfiguration.
 */
data class ServerConfig(
    val environment: DeploymentEnvironment,
    val host: String,
    val port: Int,
    val database: DatabaseConfig,
    val identity: ServerIdentity,
    val mail: MailConfig,
    val unlocks: Unlocks,
    val auction: AuctionPolicy,
    val stakes: PvpStakePolicy,
    val bots: BotPolicy,
    /** The administrator to create at start-up, or null — which is the steady state. */
    val admin: FirstAdministrator? = null,
) {
    companion object {
        /**
         * Builds the configuration from [lookup], defaulting to the real environment.
         *
         * [lookup] is a parameter rather than a direct call to `System.getenv` so the rules below
         * — in particular the refusal to use development defaults outside development — can be
         * tested without mutating the JVM's own environment, which Java offers no supported way
         * to do.
         *
         * @throws IllegalStateException if a value required outside development is absent.
         */
        fun from(lookup: (String) -> String? = System::getenv): ServerConfig {
            val environment = DeploymentEnvironment.of(lookup("TTO_ENV"))

            return ServerConfig(
                environment = environment,
                host = lookup("TTO_HOST") ?: "0.0.0.0",
                port = lookup("TTO_PORT")?.toIntOrNull() ?: 8080,
                database = DatabaseConfig(
                    url = environment.require(lookup, "DATABASE_URL", DEV_DATABASE_URL),
                    user = environment.require(lookup, "DATABASE_USER", DEV_DATABASE_CREDENTIAL),
                    password = environment.require(
                        lookup,
                        "DATABASE_PASSWORD",
                        DEV_DATABASE_CREDENTIAL,
                    ),
                    maxPoolSize = lookup("DATABASE_POOL_SIZE")?.toIntOrNull() ?: 10,
                ),
                identity = ServerIdentity.from(lookup),
                mail = MailConfig.from(environment, lookup),
                unlocks = unlocksFrom(lookup),
                auction = auctionFrom(lookup),
                stakes = stakesFrom(lookup),
                bots = BotPolicy.from(lookup),
                admin = FirstAdministrator.from(lookup),
            )
        }

        /**
         * The two thresholds, read from the environment rather than compiled in.
         *
         * `:core` holds the rule and the defaults; this holds the numbers *this deployment* uses,
         * and sends them to clients in `ServerInfo` so a change here does not need a client
         * release. A value that is not a number is ignored in favour of the default and not a
         * failure to boot — the same judgement `TTO_CLIENT_VERSION` makes, and for the same reason:
         * a typo here should cost the default, not the server.
         */
        private fun unlocksFrom(lookup: (String) -> String?) = Unlocks(
            multiplayer = lookup("TTO_UNLOCK_MULTIPLAYER")?.toIntOrNull()
                ?: Unlocks.DEFAULT_MULTIPLAYER,
            auction = lookup("TTO_UNLOCK_AUCTION")?.toIntOrNull() ?: Unlocks.DEFAULT_AUCTION,
        )

        /**
         * How this deployment runs its auction house.
         *
         * Every one of these is a number that will be tuned in response to what players actually
         * do — a lot cap that turns out to throttle honest sellers, a ceiling that turns out to
         * block a legitimately scarce card. Reading them here rather than compiling them in is
         * what keeps a tuning a restart instead of a coordinated release of three artifacts; they
         * travel to clients in `ServerInfo.auction`, and the server refuses on its own copy.
         *
         * A value that is not a number falls back to the default rather than stopping the boot,
         * which is the judgement `unlocksFrom` makes and for the same reason.
         */
        private fun auctionFrom(lookup: (String) -> String?) = AuctionPolicy(
            maxOpenLots = lookup("TTO_AUCTION_MAX_LOTS")?.toIntOrNull()
                ?: AuctionPolicy.DEFAULT_MAX_OPEN_LOTS,
            maxPriceMultiple = lookup("TTO_AUCTION_MAX_MULTIPLE")?.toIntOrNull()
                ?: AuctionPolicy.DEFAULT_MAX_PRICE_MULTIPLE,
            sellerDecisionHours = lookup("TTO_AUCTION_DECISION_HOURS")?.toIntOrNull()
                ?: AuctionPolicy.DEFAULT_SELLER_DECISION_HOURS,
            antiSnipeSeconds = lookup("TTO_AUCTION_ANTI_SNIPE_SECONDS")?.toIntOrNull()
                ?: AuctionPolicy.DEFAULT_ANTI_SNIPE_SECONDS,
        )

        /**
         * How large a wager this deployment lets a player propose.
         *
         * Read here for the reason [auctionFrom]'s numbers are: the ceiling is a balance dial, not
         * a protocol constant, and the first thing anybody will want to change about it is the
         * number. It travels to clients in `ServerInfo.stakes` so they can draw the limit rather
         * than discover it, and `PvpReferee` refuses on this copy, which is the one that counts.
         */
        private fun stakesFrom(lookup: (String) -> String?) = PvpStakePolicy(
            perLevel = lookup("TTO_PVP_STAKE_PER_LEVEL")?.toIntOrNull()
                ?: PvpStakePolicy.DEFAULT_PER_LEVEL,
            heavyPercent = lookup("TTO_PVP_STAKE_HEAVY_PERCENT")?.toIntOrNull()
                ?: PvpStakePolicy.DEFAULT_HEAVY_PERCENT,
        )

        private const val DEV_DATABASE_URL = "jdbc:postgresql://localhost:5432/tripletriad"

        /** The username and the password happen to coincide in `compose.yaml`. */
        private const val DEV_DATABASE_CREDENTIAL = "tripletriad"
    }
}

/**
 * The administrator the process creates at start-up, when the environment names one.
 *
 * ### Why the first administrator is an environment variable at all
 *
 * Because there is no other credential to use. A route that creates one would be unauthenticated —
 * a console anybody on the internet can enrol into — or authenticated, which is the chicken and the
 * egg. An environment variable and a restart is something only somebody with the host can do, which
 * is the right bar for the first one. `ensureFirstAdministrator` is where it is applied, and it
 * never touches a row that already exists.
 *
 * ### Why there is no TOTP secret here
 *
 * A password only, deliberately. The second factor is generated at the first sign-in and shown
 * once, in the console — because a secret placed in an environment variable is a secret in a `.env`
 * file, in `docker inspect`, in the output of `docker compose config`, and in whatever shell
 * history put it there. `web-platform.md` § "The first administrator, without a secret in a log" is
 * the long form, and this repository's rule about secrets is the reason.
 *
 * A password in the environment is the same category of exposure, and the difference is that it can
 * be *changed by using it*: it is a bootstrap credential whose whole life is one sign-in, after
 * which the variables come out of the environment. A shared TOTP secret cannot be rotated by being
 * used.
 *
 * ### Why a half-configured pair refuses to boot
 *
 * One variable without the other is somebody halfway through setting this up, and the two failure
 * modes of guessing are both bad: inventing a password would create an account nobody can sign in
 * to, and ignoring the pair would leave a deployment that looks configured and has no console. So
 * it is `check`ed, in the same breath as a missing database password and for the same reason.
 *
 * @property password **secret**. It reaches `PasswordHasher.hash` and nothing else. No message in
 *   this file names it, including the failure messages, which name the *variable*.
 */
data class FirstAdministrator(val username: String, val password: String) {
    companion object {
        fun from(lookup: (String) -> String?): FirstAdministrator? {
            val username = lookup("TTO_ADMIN_USERNAME")?.trim()?.takeIf { it.isNotBlank() }
            val password = lookup("TTO_ADMIN_PASSWORD")?.takeIf { it.isNotBlank() }
            if (username == null && password == null) return null

            check(username != null && password != null) {
                "TTO_ADMIN_USERNAME and TTO_ADMIN_PASSWORD must be set together: " +
                    "one without the other creates an administrator nobody can sign in as"
            }
            check(username.length in USERNAME_LENGTH) {
                "TTO_ADMIN_USERNAME must be ${USERNAME_LENGTH.first}-${USERNAME_LENGTH.last} " +
                    "characters, which is what the admins_username_length constraint allows"
            }
            check(password.length >= MIN_PASSWORD_LENGTH) {
                "TTO_ADMIN_PASSWORD must be at least $MIN_PASSWORD_LENGTH characters"
            }
            check(PasswordHasher.isUsable(password)) {
                "TTO_ADMIN_PASSWORD is longer than " +
                    "${PasswordHasher.MAX_PASSWORD_BYTES} bytes once encoded, which bcrypt refuses"
            }
            return FirstAdministrator(username, password)
        }

        /**
         * The same range as `admins_username_length` in `V19__admin.sql`.
         *
         * Checked here as well as there because a `CHECK` violation at start-up surfaces as a
         * `SQLException` from an INSERT, and "refusing to start: TTO_ADMIN_USERNAME must be 3-24
         * characters" is the line an operator can act on.
         */
        private val USERNAME_LENGTH = 3..24

        /**
         * Twelve, against the eight `Credentials.PASSWORD_LENGTH` asks a player for.
         *
         * A higher bar for a password that guards the economy rather than one profile, and an
         * affordable one: this is typed by one of two people, once, from a password manager, and
         * never on a phone keyboard between matches.
         */
        private const val MIN_PASSWORD_LENGTH = 12
    }
}

/**
 * What this deployment calls itself, and which client build it points people at.
 *
 * ### Why it is configuration and not a constant
 *
 * Because none of it is a property of the *code*. The name distinguishes two deployments of the
 * same artifact in a client's server list, and the release is a fact about a store listing or a
 * file on a web host, which changes without this program being rebuilt. Baking either in would
 * mean a redeploy to correct a URL.
 *
 * ### Why a missing release is not an error
 *
 * A development container publishes nothing, and demanding a download URL from it would make the
 * common case the one that fails to start. Absent means "this deployment makes no claim about
 * client builds", which is honest and is what a client renders as nothing at all.
 */
data class ServerIdentity(
    val name: String,
    val release: ClientRelease? = null,
) {
    companion object {
        /**
         * Reads the identity from the environment.
         *
         * A malformed `TTO_CLIENT_VERSION` yields **no release** rather than a failure to start.
         * The judgement is deliberate and goes the other way from [DatabaseConfig]'s: a wrong
         * database is a server that cannot work, whereas a wrong version string costs an update
         * banner. Refusing to boot over the second would take a working server down to protect a
         * label — so it is logged loudly instead, where the deploy that typed it can see it.
         */
        fun from(lookup: (String) -> String?): ServerIdentity = ServerIdentity(
            name = lookup("TTO_SERVER_NAME")?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME,
            release = releaseFrom(lookup),
        )

        private fun releaseFrom(lookup: (String) -> String?): ClientRelease? {
            val raw = lookup("TTO_CLIENT_VERSION")?.takeIf { it.isNotBlank() } ?: return null
            val version = AppVersion.parse(raw)

            if (version == null) {
                logger.warn(
                    "TTO_CLIENT_VERSION is '{}', which is not a version; publishing no release",
                    raw,
                )
            }

            return version?.let {
                ClientRelease(
                    version = it,
                    downloads = buildMap {
                        DOWNLOAD_VARIABLES.forEach { (platform, variable) ->
                            lookup(variable)?.takeIf { url -> url.isNotBlank() }
                                ?.let { url -> put(platform, url) }
                        }
                    },
                    notes = lookup("TTO_CLIENT_NOTES")?.takeIf { notes -> notes.isNotBlank() },
                )
            }
        }

        private const val DEFAULT_NAME = "Triple Triad"

        /** One variable per platform, because the answer is a different artifact for each. */
        private val DOWNLOAD_VARIABLES = mapOf(
            ClientPlatform.ANDROID to "TTO_CLIENT_DOWNLOAD_ANDROID",
            ClientPlatform.DESKTOP to "TTO_CLIENT_DOWNLOAD_DESKTOP",
            ClientPlatform.IOS to "TTO_CLIENT_DOWNLOAD_IOS",
        )

        private val logger = LoggerFactory.getLogger(ServerIdentity::class.java)
    }
}

/**
 * Connection settings for the single Postgres the server owns.
 *
 * `maxPoolSize` deserves a word: the instinct is to raise it under load, and it is usually wrong.
 * A pool larger than the database can serve concurrently converts a queue that is visible and
 * bounded into one that is neither. Ten is a starting point for a workload that is turn-based and
 * tiny; it should be changed in response to a measurement, not to a worry.
 */
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
)

/**
 * Which of the two worlds this process is in — and the reason the distinction is in the code.
 *
 * The development defaults above are a real hazard: a server that silently falls back to
 * `localhost` with a published password is a server that will one day start in production, connect
 * to nothing, and report itself healthy. [DEVELOPMENT] is therefore the *only* value that permits
 * a default, and every other environment must state its configuration in full.
 */
enum class DeploymentEnvironment {
    DEVELOPMENT,
    PRODUCTION,
    ;

    /** Returns the value of [name], falling back to [developmentDefault] only in development. */
    fun require(lookup: (String) -> String?, name: String, developmentDefault: String): String {
        val value = lookup(name)
        if (!value.isNullOrBlank()) return value

        check(this == DEVELOPMENT) {
            "$name must be set explicitly unless TTO_ENV=development; " +
                "development defaults are never used outside development"
        }
        return developmentDefault
    }

    companion object {
        /**
         * Maps `TTO_ENV` onto an entry. **Anything but an explicit development value is
         * [PRODUCTION]**, including absent and misspelt.
         *
         * The unset case is the one that matters. Defaulting it to development would mean a host
         * where somebody forgot the variable runs with the published password against a
         * `localhost` that is not there — and reports itself healthy until the first write. Making
         * development opt-in costs one line in `compose.yaml` and removes that failure entirely.
         */
        fun of(raw: String?): DeploymentEnvironment = when (raw?.trim()?.lowercase()) {
            "dev", "development", "local" -> DEVELOPMENT
            else -> PRODUCTION
        }
    }
}

/**
 * Where confirmation and password-reset mail goes out through.
 *
 * ### Why an absent provider is fatal in production and fine in development
 *
 * Because of what each costs. On a laptop there is no inbox and no API key, and demanding one would
 * mean nobody could run the server without signing up to a third party — so [Mailer.Disabled] logs
 * the code and the flow completes. In production that same fallback would write a live credential
 * into a log file *and* silently stop every password reset from arriving, which is the failure
 * nobody notices until a player is locked out. So it is refused at boot, in the same breath as a
 * missing database password, and for the same reason: the process either starts correctly
 * configured or does not start.
 *
 * @property apiKey **secret**. It reaches [Mailer.Brevo] and nothing else; it is never logged and
 *   never sent to a client.
 * @property from the envelope sender. Wants to be a subdomain that carries no other traffic, so
 *   this mail's reputation stands on its own.
 */
data class MailConfig(
    val apiKey: String?,
    val from: String,
    val senderName: String,
) {
    /** The [Mailer] this configuration describes. */
    fun mailer(): Mailer =
        apiKey?.let { Mailer.Brevo(apiKey = it, from = from, senderName = senderName) }
            ?: Mailer.Disabled

    companion object {
        fun from(environment: DeploymentEnvironment, lookup: (String) -> String?): MailConfig {
            val apiKey = lookup("BREVO_API_KEY")?.takeIf { it.isNotBlank() }

            check(apiKey != null || environment == DeploymentEnvironment.DEVELOPMENT) {
                "BREVO_API_KEY is required outside development: without it no confirmation or " +
                    "password-reset mail is sent, and the fallback writes codes to the log"
            }

            return MailConfig(
                apiKey = apiKey,
                from = lookup("MAIL_FROM")?.takeIf { it.isNotBlank() } ?: DEV_FROM,
                senderName = lookup("MAIL_SENDER_NAME")?.takeIf { it.isNotBlank() } ?: DEV_NAME,
            )
        }

        private const val DEV_FROM = "no-reply@localhost"

        private const val DEV_NAME = "Triple Triad"
    }
}

/**
 * Whether this deployment plays accounts of its own, and how.
 *
 * ### Off by default, and that is the important line
 *
 * A server that quietly populates its own lobby is a different product from one that does not, and
 * nobody should get one by upgrading. [enabled] is false unless `TTO_BOTS_ENABLED` says otherwise,
 * and every other number here is inert until it is true.
 *
 * ### Why the numbers are environment variables rather than constants
 *
 * The same argument the auction house's and the stake ceiling's make: every one of these is a dial
 * that will be turned in response to what actually happens — a wait that turns out to be so short
 * that bots take matches from people, a cadence that turns out to read as a machine, a roster that
 * turns out to be too small to ever be there when somebody opens a table. Compiling them in would
 * make each of those a release.
 *
 * They do **not** travel to clients in `ServerInfo`. Nothing in the protocol says a bot exists —
 * see `V16__bots.sql` on why that is a decision rather than an omission — so there is nothing for a
 * client to render and nothing it could act on.
 *
 * @property band how hard every bot plays. `EXPERT` is the top of `MatchAiOptions.forLevel`: depth
 *   five, a solved endgame from six free cells, and no blunder at all. It is the band the roster is
 *   created at; an operator splitting the roster across bands does it with an UPDATE, which is what
 *   the column in `bots` is for.
 * @property wagers whether a bot may sit down at a table that risks something — MGP or a trade
 *   rule. **False**, and the default is the whole of the current position on it: a bot that stakes
 *   moves real value into and out of the players' economy, and a card won from one is a card the
 *   world gained. Turning it on is a decision to be taken with the numbers in front of you, which
 *   is what the metrics are for.
 * @property reserve MGP a bot keeps back from the shop. Idle while [wagers] is false, and the
 *   thing that stops a bot arriving at a table it cannot cover once it is not.
 */
data class BotPolicy(
    val enabled: Boolean = false,
    val count: Int = DEFAULT_COUNT,
    val band: NpcLevel = NpcLevel.EXPERT,
    val formatId: String = DEFAULT_FORMAT,
    val namePrefix: String = DEFAULT_NAME_PREFIX,
    val wagers: Boolean = false,
    val reserve: Int = DEFAULT_RESERVE,
    val tableWaitMillis: Long = DEFAULT_TABLE_WAIT_SECONDS * MILLIS,
    val moveMinMillis: Long = DEFAULT_MOVE_MIN_SECONDS * MILLIS,
    val moveSpreadMillis: Long = DEFAULT_MOVE_SPREAD_SECONDS * MILLIS,
    val idleMillis: Long = DEFAULT_IDLE_SECONDS * MILLIS,
    val tickMillis: Long = DEFAULT_TICK_SECONDS * MILLIS,
) {
    companion object {
        /**
         * Reads the policy from the environment.
         *
         * A value that is not a number falls back to the default rather than stopping the boot,
         * which is the judgement `ServerConfig.unlocksFrom` makes and for the same reason: a typo
         * in a dial should cost the dial, not the server. A band this build does not know falls
         * back to `EXPERT`, which is `BotStore`'s reading of the same question.
         */
        fun from(lookup: (String) -> String?): BotPolicy {
            val defaults = BotPolicy()
            return BotPolicy(
                enabled = lookup("TTO_BOTS_ENABLED").toBoolean(),
                count = lookup("TTO_BOTS_COUNT")?.toIntOrNull() ?: defaults.count,
                band = NpcLevel.entries.firstOrNull { it.name == lookup("TTO_BOTS_BAND") }
                    ?: defaults.band,
                formatId = lookup("TTO_BOTS_FORMAT")?.takeIf { it.isNotBlank() }
                    ?: defaults.formatId,
                namePrefix = lookup("TTO_BOTS_NAME_PREFIX")?.takeIf { it.isNotBlank() }
                    ?: defaults.namePrefix,
                wagers = lookup("TTO_BOTS_WAGER").toBoolean(),
                reserve = lookup("TTO_BOTS_RESERVE")?.toIntOrNull() ?: defaults.reserve,
                tableWaitMillis = seconds(lookup("TTO_BOTS_TABLE_WAIT_SECONDS"))
                    ?: defaults.tableWaitMillis,
                moveMinMillis = seconds(lookup("TTO_BOTS_MOVE_MIN_SECONDS"))
                    ?: defaults.moveMinMillis,
                moveSpreadMillis = seconds(lookup("TTO_BOTS_MOVE_SPREAD_SECONDS"))
                    ?: defaults.moveSpreadMillis,
                idleMillis = seconds(lookup("TTO_BOTS_IDLE_SECONDS")) ?: defaults.idleMillis,
                tickMillis = seconds(lookup("TTO_BOTS_TICK_SECONDS")) ?: defaults.tickMillis,
            )
        }

        /** A count of seconds as milliseconds, or null when it is not a positive number. */
        private fun seconds(value: String?): Long? =
            value?.toLongOrNull()?.takeIf { it > 0 }?.times(MILLIS)

        private const val MILLIS = 1_000L

        /**
         * Ten, which is enough that somebody is usually free when a table goes unanswered and few
         * enough that a depth-five search per placement stays noise on the process that serves
         * requests. It is the first number to measure, not the last.
         */
        private const val DEFAULT_COUNT = 10

        /** The widest shipped format, which is where the roster and the lobby both are. */
        private const val DEFAULT_FORMAT = "ff14-standard"

        /**
         * What a bot is called, before four random digits.
         *
         * Neutral rather than either honest or disguised, because the deployment decides which of
         * those it wants: nothing in the protocol marks a bot, so this prefix is the only thing a
         * player can read. An operator who wants them obvious sets it to something obvious.
         */
        private const val DEFAULT_NAME_PREFIX = "Duelist"

        /**
         * Forty-five seconds before a table is a bot's business.
         *
         * Long enough that a person browsing the lobby has read it and had time to tap it, short
         * enough that the host has not given up — `PvpMatchRow.TABLE_MILLIS` gives them five
         * minutes, so this spends the first sixth of it waiting for a human.
         */
        private const val DEFAULT_TABLE_WAIT_SECONDS = 45L

        /**
         * Three to nine seconds a placement.
         *
         * A pace a person could keep, and the only thing standing where the `PLAY` rate limit
         * cannot: 120 placements a minute is what that bucket allows a client, and a bot driving
         * the referee in-process is bounded by nothing but this. Nine seconds is also comfortably
         * inside the thirty-second turn timer, so a bot never forfeits by thinking.
         */
        private const val DEFAULT_MOVE_MIN_SECONDS = 3L
        private const val DEFAULT_MOVE_SPREAD_SECONDS = 6L

        /** How long a bot with nothing to do waits before looking again. */
        private const val DEFAULT_IDLE_SECONDS = 20L

        /**
         * How often the director looks at all.
         *
         * Two seconds, which is what makes "forty-five seconds and nobody joined" mean forty-five
         * rather than up to seventy-five. It is its own loop rather than a passenger on
         * `sweepAbandonedMatches` for exactly that reason: the sweep runs every thirty seconds
         * because nothing it does is urgent, and this is the one thing here that is.
         */
        private const val DEFAULT_TICK_SECONDS = 2L

        /**
         * Five thousand MGP kept out of the shop.
         *
         * Idle while [wagers] is false. It is roughly the ceiling a level-25 account may wager
         * under the default `PvpStakePolicy`, so a bot that has climbed that far can cover a table
         * at its own limit without having to stop buying packs first.
         */
        private const val DEFAULT_RESERVE = 5_000
    }
}
