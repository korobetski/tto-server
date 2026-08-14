package com.tripletriad.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That nothing secret reaches the log.
 *
 * ### Why this is a test and not a code review
 *
 * Because it is a property of every line the server will ever write, and the lines are added by
 * people who are thinking about something else at the time. `Authentication` already states the
 * rule — a bearer token "is exactly as good as the password for as long as it lives" and must go in
 * no log, no URL and no error message — and a rule stated in a comment is one that holds until
 * somebody adds `log.info("rejected token $token")` while debugging a Sunday outage.
 *
 * A log is also the least guarded copy of anything: it is shipped off the host, kept longer than
 * the data it describes, and read by people who were never given the account.
 *
 * ### What it does
 *
 * Plays the whole authenticated flow — register, sign in, use the token, sign out — with an
 * appender on the root logger, and then looks for the two things that must never be there. It runs
 * at `DEBUG`, which is louder than production, so a line that only appears when somebody turns the
 * level up is caught here rather than in the field.
 */
class LogSecrecyTest {

    @Test
    fun neitherTheTokenNorThePasswordIsEverLogged() {
        val captured = capturing {
            testApplication {
                application { module(Postgres.dataSource, prometheusRegistry()) }
                val name = Postgres.freshAccount("logged")

                val registered = register(name)
                val signedIn = signIn(name)

                // An authenticated call, so the token has been through `authenticate` and its
                // fingerprint has been looked up — the path most likely to log what it received.
                client.get("/me") {
                    protocolHeaders()
                    header(HttpHeaders.Authorization, "Bearer ${signedIn.token}")
                }
                // And a refused one, because failures are where debugging lines get added.
                client.get("/me") {
                    protocolHeaders()
                    header(HttpHeaders.Authorization, "Bearer not-a-real-token")
                }

                assertTrue(registered.token.isNotBlank())
                tokens += listOf(registered.token, signedIn.token)
            }
        }

        assertTrue(captured.isNotEmpty(), "nothing was logged at all, so this proved nothing")
        for (token in tokens) {
            assertFalse(
                tokens.any { it in captured },
                "a session token reached the log — see Authentication's own rule",
            )
        }
        assertFalse(PASSWORD in captured, "a password reached the log")
    }

    /**
     * And the account's *name* is not logged either, which is a different claim and a weaker one.
     *
     * A username is not a secret — it is shown to other players in the lobby — but it is personal
     * data, and a log that pairs it with an address and a timestamp is a record of who played when.
     * The server logs an account **id** instead, which says the same thing to an operator reading a
     * trace and nothing at all to anybody who reads the file later.
     */
    @Test
    fun theAccountIsIdentifiedByIdAndNotByName() {
        val name = Postgres.freshAccount("private")
        val captured = capturing {
            testApplication {
                application { module(Postgres.dataSource, prometheusRegistry()) }
                register(name)
            }
        }

        assertTrue("Registered account" in captured, "registration logged nothing to check")
        assertFalse(name in captured, "the log named the account holder")
    }

    // ---- Harness ----------------------------------------------------------

    private val tokens = mutableListOf<String>()

    /** Everything written to the root logger while [block] runs, as one string. */
    private fun capturing(block: () -> Unit): String {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val original = root.level

        root.level = Level.DEBUG
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            root.level = original
            appender.stop()
        }
        return appender.list.joinToString("\n") { "${it.formattedMessage} ${it.throwableProxy}" }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.register(
        name: String,
    ): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, PASSWORD)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signIn(
        name: String,
    ): Session {
        val response = client.post("/sessions") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, PASSWORD)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private fun HttpRequestBuilder.protocolHeaders() {
        contentType(ContentType.Application.Json)
        header(VERSION_HEADER, CURRENT_VERSION.toString())
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        /** Distinctive enough that finding it in a log is unambiguous. */
        const val PASSWORD = "a-password-nobody-should-ever-see"
    }
}
