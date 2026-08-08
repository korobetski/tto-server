package com.tripletriad.server

import com.tripletriad.protocol.AppVersion
import com.tripletriad.protocol.ClientPlatform
import com.tripletriad.protocol.ClientRelease
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
            )
        }

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
