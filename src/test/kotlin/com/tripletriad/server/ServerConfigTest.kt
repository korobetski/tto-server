package com.tripletriad.server

import com.tripletriad.protocol.Unlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        val environment = production(
            "TTO_PORT" to "9090",
            // Not what this test is about, and required since confirmation mail arrived — see
            // `productionWithoutAMailProviderRefusesToBoot`, which is what it *is* about.
            "BREVO_API_KEY" to NOT_A_REAL_KEY,
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

    // ---- Mail, and the refusal to ship without it -------------------------

    /**
     * Production without a mail provider does not boot.
     *
     * ### Why this is a refusal and not a warning
     *
     * Because the fallback is [Mailer.Disabled], which **writes the code to the log**. A production
     * deployment that resolved to it would be printing a credential into a log file, and would keep
     * doing so for as long as nobody noticed — while every player who forgot a password waited for
     * a mail that was never going to arrive. The two failures are silent in opposite directions,
     * which is exactly the pair a boot check exists for.
     */
    @Test
    fun productionWithoutAMailProviderRefusesToBoot() {
        val failure = assertFailsWith<IllegalStateException> {
            ServerConfig.from(production()::get)
        }

        assertTrue(
            failure.message.orEmpty().contains("BREVO_API_KEY"),
            "the message must name the missing variable, got: ${failure.message}",
        )
    }

    /** Development does not: there is no inbox on a laptop, and the log is how a code is read. */
    @Test
    fun developmentFallsBackToTheMailerThatSendsNothing() {
        val config = ServerConfig.from(mapOf("TTO_ENV" to "development")::get)

        assertNull(config.mail.apiKey)
        assertSame(Mailer.Disabled, config.mail.mailer())
    }

    /** A key set to nothing is a variable somebody meant to set. It must not pass for one. */
    @Test
    fun aBlankMailKeyIsNotAKey() {
        assertFailsWith<IllegalStateException> {
            ServerConfig.from(production("BREVO_API_KEY" to "   ")::get)
        }
    }

    @Test
    fun aConfiguredKeyProducesTheProviderAndNotTheFallback() {
        val config = ServerConfig.from(
            production(
                "BREVO_API_KEY" to NOT_A_REAL_KEY,
                "MAIL_FROM" to "no-reply@triad.example",
                "MAIL_SENDER_NAME" to "Triple Triad EU",
            )::get,
        )

        assertEquals("no-reply@triad.example", config.mail.from)
        assertEquals("Triple Triad EU", config.mail.senderName)
        assertTrue(config.mail.mailer() is Mailer.Brevo)
    }

    // ---- The unlock thresholds --------------------------------------------

    /** Unset means `:core`'s numbers, which is what a client that hears nothing also assumes. */
    @Test
    fun unstatedThresholdsAreTheOnesCoreDefines() {
        val config = ServerConfig.from(mapOf("TTO_ENV" to "development")::get)

        assertEquals(Unlocks(), config.unlocks)
    }

    /** And a deployment that wants its own says so without anybody rebuilding a client. */
    @Test
    fun statedThresholdsAreRead() {
        val config = ServerConfig.from(
            mapOf(
                "TTO_ENV" to "development",
                "TTO_UNLOCK_MULTIPLAYER" to "12",
                "TTO_UNLOCK_AUCTION" to "20",
            )::get,
        )

        assertEquals(Unlocks(multiplayer = 12, auction = 20), config.unlocks)
    }

    /**
     * A typo costs the default rather than the server.
     *
     * The same judgement `TTO_CLIENT_VERSION` makes. Refusing to boot over a threshold would take a
     * working deployment offline to protect a number that has a perfectly good default.
     */
    @Test
    fun anUnparseableThresholdFallsBackRatherThanFailing() {
        val config = ServerConfig.from(
            mapOf("TTO_ENV" to "dev", "TTO_UNLOCK_MULTIPLAYER" to "five")::get,
        )

        assertEquals(Unlocks.DEFAULT_MULTIPLAYER, config.unlocks.multiplayer)
    }

    /** Everything a production boot needs, plus whatever the test is about. */
    private fun production(vararg extra: Pair<String, String>) = mapOf(
        "TTO_ENV" to "production",
        "DATABASE_URL" to "jdbc:postgresql://db:5432/tto",
        "DATABASE_USER" to "tto",
        "DATABASE_PASSWORD" to "not-a-real-password",
    ) + extra

    private companion object {
        const val DEFAULT_PORT = 8080
        const val OVERRIDDEN_PORT = 9090

        /** Shaped like a Brevo key and belonging to nobody. Never sent anywhere: see [Mailer]. */
        const val NOT_A_REAL_KEY = "xkeysib-0000000000000000-not-a-real-key"
    }
}
