package com.tripletriad.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The configuration rules, which are the ones that decide whether a bad deploy is loud or silent.
 *
 * Worth testing rather than trusting: every assertion below describes a mistake somebody will
 * actually make — forgetting `TTO_ENV` on a host, misspelling it, leaving `DATABASE_PASSWORD`
 * unset — and the point of the class under test is that none of those may end in a running server
 * pointed at the wrong database.
 */
class ServerConfigTest {

    @Test
    fun developmentSuppliesDefaultsSoNothingIsNeededToRunLocally() {
        val environment = mapOf("TTO_ENV" to "development")

        val config = ServerConfig.from(environment::get)

        assertEquals(DeploymentEnvironment.DEVELOPMENT, config.environment)
        assertEquals(DEFAULT_PORT, config.port)
        assertTrue(config.database.url.startsWith("jdbc:postgresql://"))
    }

    /** The failure this whole design exists to prevent. */
    @Test
    fun anUnsetEnvironmentIsProductionAndRefusesToGuess() {
        val failure = assertFailsWith<IllegalStateException> {
            ServerConfig.from(emptyMap<String, String>()::get)
        }

        assertTrue(
            failure.message.orEmpty().contains("DATABASE_URL"),
            "the message must name the missing variable, got: ${failure.message}",
        )
    }

    /** A typo must not re-enable the development fallbacks. */
    @Test
    fun aMisspeltEnvironmentIsTreatedAsProduction() {
        assertEquals(DeploymentEnvironment.PRODUCTION, DeploymentEnvironment.of("prd"))
        assertEquals(DeploymentEnvironment.PRODUCTION, DeploymentEnvironment.of("Developement"))
        assertEquals(DeploymentEnvironment.DEVELOPMENT, DeploymentEnvironment.of("  Local "))
    }

    @Test
    fun productionStartsOnceEveryValueIsStated() {
        val environment = mapOf(
            "TTO_ENV" to "production",
            "DATABASE_URL" to "jdbc:postgresql://db:5432/tto",
            "DATABASE_USER" to "tto",
            "DATABASE_PASSWORD" to "secret",
            "TTO_PORT" to "9090",
        )

        val config = ServerConfig.from(environment::get)

        assertEquals(DeploymentEnvironment.PRODUCTION, config.environment)
        assertEquals(OVERRIDDEN_PORT, config.port)
        assertEquals("jdbc:postgresql://db:5432/tto", config.database.url)
    }

    /** A blank value is a variable somebody set to nothing, which is not a value. */
    @Test
    fun aBlankValueCountsAsAbsent() {
        val environment = mapOf(
            "TTO_ENV" to "production",
            "DATABASE_URL" to "   ",
        )

        assertFailsWith<IllegalStateException> { ServerConfig.from(environment::get) }
    }

    /** An unparseable port falls back rather than crashing — but never silently changes hosts. */
    @Test
    fun anUnparseablePortFallsBackToTheDefault() {
        val environment = mapOf("TTO_ENV" to "dev", "TTO_PORT" to "not-a-number")

        val config = ServerConfig.from(environment::get)

        assertEquals(DEFAULT_PORT, config.port)
    }

    private companion object {
        const val DEFAULT_PORT = 8080
        const val OVERRIDDEN_PORT = 9090
    }
}
