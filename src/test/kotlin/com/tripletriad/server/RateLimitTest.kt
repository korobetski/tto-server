package com.tripletriad.server

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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That the limits actually fire.
 *
 * ### Why this file has to exist
 *
 * Because a rate limiter is configuration, and configuration that is never exercised is
 * configuration that is wrong. The whole suite passed the day the plugin was installed — every
 * other test stays comfortably under every threshold — so without this one, "the server is
 * throttled" would be a claim resting on a block of code nobody had ever run.
 *
 * ### And why it asserts the *boundary*
 *
 * A test that sent a thousand requests and found a 429 somewhere would pass against a limiter set
 * to one, which would lock out every real player on their second tap. The assertions below pin both
 * sides: the last allowed request succeeds, and only the next one is refused.
 */
class RateLimitTest {

    /**
     * Guessing a password stops being possible after ten tries from one address.
     *
     * Ten is chosen to be well past a person who has forgotten which password they used and
     * nowhere near enough to be a strategy. The refusal is what stops bcrypt's cost from being a
     * denial of service on this host as much as a defence of the account.
     */
    @Test
    fun signingInRepeatedlyIsRefusedAfterTheTenthTry() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        val name = Postgres.freshAccount("guessed")
        register(name)

        // All ten are available: registering has a bucket of its own, which it did not always —
        // see `REGISTER`. That split is exactly what [registeringDoesNotSpendTheSignInBudget] pins.
        repeat(SIGN_IN_LIMIT) { attempt ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                signIn(name, "$PASSWORD-wrong").status,
                "attempt ${attempt + 1} was refused before the limit",
            )
        }

        val refused = signIn(name, "$PASSWORD-wrong")
        assertEquals(HttpStatusCode.TooManyRequests, refused.status, refused.bodyAsText())
    }

    /**
     * And the refusal says when to come back.
     *
     * Without `Retry-After` a client's only options are to give up or to poll, and polling a
     * limiter is how a throttle becomes the load it was meant to shed.
     */
    @Test
    fun aThrottledRequestSaysWhenToTryAgain() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        val name = Postgres.freshAccount("patient")
        register(name)
        repeat(SIGN_IN_LIMIT) { signIn(name, "$PASSWORD-wrong") }

        val refused = signIn(name, "$PASSWORD-wrong")

        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        val retryAfter = assertNotNull(
            refused.headers[HttpHeaders.RetryAfter],
            "a throttled client was not told when to come back",
        )
        assertTrue(
            retryAfter.toLongOrNull()?.let { it > 0 } == true,
            "Retry-After was not a positive number of seconds: $retryAfter",
        )
    }

    /**
     * Registering does not spend the sign-in budget, which it used to.
     *
     * The regression this pins was found by an end-to-end run and not by this file: a script that
     * created nine accounts and then signed in was refused on its **first** attempt, because the
     * two shared a bucket. Behind one address that is a household or a classroom, and the symptom
     * is that nobody can sign in and nothing explains why.
     *
     * Written as "register several, then sign in successfully" rather than by counting buckets,
     * because what matters is the player's experience of it.
     */
    @Test
    fun registeringDoesNotSpendTheSignInBudget() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }

        val names = List(A_HOUSEHOLD) { Postgres.freshAccount("housemate") }
        names.forEach { register(it) }

        // Everyone can still sign in, which is the whole claim.
        names.forEach { name ->
            assertEquals(
                HttpStatusCode.OK,
                signIn(name, PASSWORD).status,
                "$name could not sign in after the others registered",
            )
        }
    }

    /**
     * Reading the profile is not throttled, which is the other half of getting this right.
     *
     * A limiter that catches the polling the game does constantly — the lobby, the match, the
     * dashboard — would break the game in the name of protecting it. Only the endpoints that pay
     * out, sign in, or publish something to other players are limited; this asserts one that is
     * not, at a volume far past any of the thresholds.
     */
    @Test
    fun readingTheProfileIsNotThrottled() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        val session = register(Postgres.freshAccount("poller"))

        repeat(WELL_PAST_EVERY_LIMIT) { attempt ->
            val response = client.get("/me") {
                protocolHeaders()
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
            }
            assertEquals(HttpStatusCode.OK, response.status, "poll ${attempt + 1} was throttled")
        }
    }

    // ---- Harness ----------------------------------------------------------

    private suspend fun ApplicationTestBuilder.register(name: String): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, PASSWORD, address(name))))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.signIn(name: String, password: String) =
        client.post("/sessions") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, password)))
        }

    private fun HttpRequestBuilder.protocolHeaders() {
        contentType(ContentType.Application.Json)
        header(VERSION_HEADER, CURRENT_VERSION.toString())
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val PASSWORD = "correct-horse-battery"

        /** Mirrors `SIGN_IN_LIMIT`, which is private to the server module. */
        const val SIGN_IN_LIMIT = 10

        /** More people than share an address by accident, and fewer than a bucket holds. */
        const val A_HOUSEHOLD = 5

        /** Past every bucket in the file, so "not throttled" means it by any of them. */
        const val WELL_PAST_EVERY_LIMIT = 80
    }
}
