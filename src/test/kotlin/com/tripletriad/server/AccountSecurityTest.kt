package com.tripletriad.server

import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType
import com.tripletriad.protocol.BagItemRequest
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What an account can be made to do by somebody sending odd things at it, and what its owner can do
 * about a credential that has leaked.
 *
 * Everything here is a hole `docs/security-review.md` named. They are together in one file because
 * they are one subject — the account's own safety — rather than because they were fixed together.
 */
class AccountSecurityTest {

    /**
     * A password past bcrypt's limit is a **401**, not a 500.
     *
     * ### The defect
     *
     * `Secrets.kt` said input past 72 bytes was "ignored, silently". `at.favre.lib:bcrypt` throws
     * instead — its default long-password strategy is strict — so an over-long password reached
     * `BCrypt.verifyer()` and came back out as an `IllegalArgumentException`, which `StatusPages`
     * turned into `500 internal_error` and an error-level stack trace. On `POST /sessions`, which
     * anybody can reach without an account.
     *
     * 401 is not merely "not a 500": it is the correct answer. Every stored digest was made from a
     * password bcrypt accepted, so one it would refuse cannot be any account's.
     */
    @Test
    fun anOverlongPasswordIsRefusedRatherThanCrashing() = server {
        val name = Postgres.freshAccount("long")
        register(name, PASSWORD)

        val response = signIn(name, "x".repeat(OVER_THE_LIMIT))

        assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
    }

    /**
     * The limit is in **bytes**, so a short passphrase of emoji is refused too.
     *
     * `Credentials.PASSWORD_LENGTH` counts characters and bcrypt counts UTF-8 bytes. Sixty emoji
     * are sixty characters and a hundred and twenty bytes: inside any character range anybody would
     * write, and twice what bcrypt will take. A character check cannot stand in for this one.
     */
    @Test
    fun aPassphraseOfEmojiIsRefusedAtTheFormRatherThanAtBcrypt() = server {
        val emoji = Postgres.freshAccount("emoji")
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(
                json.encodeToString(
                    // The name is taken once and reused, so the address matches the account it
                    // names — and the refusal under test is about the password, not either.
                    emoji.let { Credentials(it, "🂡".repeat(EMOJI_COUNT), address(it)) },
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
    }

    /**
     * Changing the password ends every **other** session and keeps this one.
     *
     * Both halves matter and both were missing: there was no way to change a password at all, so
     * the only answer to a leaked one was deleting the account. Revoking as part of the change
     * rather than as a second call is the point — a player who changes their password because
     * somebody else has it has not fixed anything while that person's token is still good for
     * thirty days.
     */
    @Test
    fun changingThePasswordEndsTheOtherSessionsAndKeepsThisOne() = server {
        val name = Postgres.freshAccount("rotate")
        val first = register(name, PASSWORD).token
        val second = signIn(name, PASSWORD).let { json.decodeFromString<Session>(it.bodyAsText()) }

        val changed = client.post("/accounts/me/password") {
            protocolHeaders()
            bearer(second.token)
            setBody("""{"password":"$PASSWORD","newPassword":"$NEW_PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.NoContent, changed.status, changed.bodyAsText())

        assertEquals(
            HttpStatusCode.OK,
            me(second.token).status,
            "the session that asked for the change was signed out",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            me(first).status,
            "the other session survived a password change",
        )
        assertEquals(
            HttpStatusCode.OK,
            signIn(name, NEW_PASSWORD).status,
            "the new password does not work",
        )
    }

    /** A wrong current password changes nothing, whatever token the caller holds. */
    @Test
    fun changingThePasswordNeedsTheCurrentOne() = server {
        val name = Postgres.freshAccount("wrongpw")
        val session = register(name, PASSWORD)

        val response = client.post("/accounts/me/password") {
            protocolHeaders()
            bearer(session.token)
            setBody("""{"password":"not-the-password","newPassword":"$NEW_PASSWORD"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
        assertEquals(
            HttpStatusCode.OK,
            signIn(name, PASSWORD).status,
            "the password changed anyway",
        )
    }

    /** Signing out everywhere ends this device too — that is what "everywhere" means. */
    @Test
    fun signingOutEverywhereEndsThisSessionAsWell() = server {
        val name = Postgres.freshAccount("everywhere")
        val first = register(name, PASSWORD).token
        val second = json.decodeFromString<Session>(signIn(name, PASSWORD).bodyAsText()).token

        val response = client.delete("/sessions/all") {
            protocolHeaders()
            bearer(second)
        }
        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, me(first).status, "another device survived")
        assertEquals(HttpStatusCode.Unauthorized, me(second).status, "this device survived")
    }

    /**
     * An account holds at most [MAX_SESSIONS] sessions, oldest evicted.
     *
     * Sessions used to accumulate without limit for their full thirty days, which is what let an
     * attacker bank tokens: the sign-in limit bounds the *rate* of new ones and said nothing about
     * how many could be alive at once. The rate limiter keys on the account now, which closes the
     * budget-multiplying directly; this bounds the table and the blast radius.
     */
    @Test
    fun anAccountHoldsABoundedNumberOfSessions() = server {
        val name = Postgres.freshAccount("devices")
        val oldest = register(name, PASSWORD).token

        // One sign-in past the cap, counting the registration's own session as the first.
        repeat(MAX_SESSIONS) { attempt ->
            assertEquals(
                HttpStatusCode.OK,
                signIn(name, PASSWORD).status,
                "sign-in ${attempt + 1} was refused",
            )
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            me(oldest).status,
            "the oldest session survived past the cap",
        )
    }

    /**
     * An operation id longer than the server will store is a 400, not a 500.
     *
     * `applied_operations.operation_id` sits inside a btree primary key, which has a hard maximum
     * of a couple of kilobytes. Past it the insert fails, and a failed insert on a client-supplied
     * key surfaced as an internal error — reporting the caller's mistake as the server's.
     */
    @Test
    fun anUnreasonableOperationIdIsARefusalRatherThanAnInternalError() = server {
        val session = register(Postgres.freshAccount("opid"), PASSWORD)

        val response = client.post("/me/bag/discard") {
            protocolHeaders()
            bearer(session.token)
            setBody(
                json.encodeToString(
                    BagItemRequest(PotionItem(PotionType.MGP), "x".repeat(HUGE_OPERATION_ID)),
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue("malformed_request" in response.bodyAsText(), response.bodyAsText())
    }

    // ---- Harness -----------------------------------------------------------

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.register(name: String, password: String): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, password, address(name))))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val session = json.decodeFromString<Session>(response.bodyAsText())
        assertNotEquals("", session.token)
        return session
    }

    private suspend fun ApplicationTestBuilder.signIn(
        name: String,
        password: String,
    ): HttpResponse = client.post("/sessions") {
        protocolHeaders()
        setBody(json.encodeToString(Credentials(name, password)))
    }

    private suspend fun ApplicationTestBuilder.me(token: String): HttpResponse = client.get("/me") {
        protocolHeaders()
        bearer(token)
    }

    private fun HttpRequestBuilder.protocolHeaders() {
        contentType(ContentType.Application.Json)
        header(VERSION_HEADER, CURRENT_VERSION.toString())
    }

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val PASSWORD = "correct-horse-battery"
        const val NEW_PASSWORD = "a-different-horse-entirely"

        /** Comfortably past `PasswordHasher.MAX_PASSWORD_BYTES`, in plain ASCII. */
        const val OVER_THE_LIMIT = 100

        /** Sixty four-byte characters: 60 long, 240 bytes. */
        const val EMOJI_COUNT = 60

        /** Past `MAX_OPERATION_ID` without being so long the request is refused for its size. */
        const val HUGE_OPERATION_ID = 4_096
    }
}
