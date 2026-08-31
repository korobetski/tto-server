package com.tripletriad.server

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
