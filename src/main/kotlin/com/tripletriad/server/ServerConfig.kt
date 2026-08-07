package com.tripletriad.server

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
            )
        }

        private const val DEV_DATABASE_URL = "jdbc:postgresql://localhost:5432/tripletriad"

        /** The username and the password happen to coincide in `compose.yaml`. */
        private const val DEV_DATABASE_CREDENTIAL = "tripletriad"
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
