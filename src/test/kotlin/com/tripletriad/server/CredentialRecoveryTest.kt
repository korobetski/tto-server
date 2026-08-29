package com.tripletriad.server

import com.tripletriad.protocol.AccountCode
import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.PasswordReset
import com.tripletriad.protocol.PasswordResetRequest
import com.tripletriad.protocol.PlayerState
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The address on an account: giving one, confirming it, and using it to get back in.
 *
 * ### What this does and does not establish
 *
 * It establishes that the flows work end to end against a real Postgres, that a code is bound to
 * one account and one purpose, and that every refusal says the same thing. It establishes nothing
 * at all about mail actually arriving — [RecordingMailer] stands in for a provider, deliberately:
 * a suite that could not run without reaching Brevo is a suite nobody runs, and the code is read
 * out of the message here exactly the way a player reads it out of an inbox.
 *
 * ### Why confirmation is not an anti-multi-account measure, and is worth having anyway
 *
 * Plus-addressing and disposable inboxes mean a determined player registers as many accounts as
 * they like. What an address buys is a **recovery path** that did not exist — see
 * `AccountRoutes`, whose KDoc used to name password reset as the gap that remained — and a small
 * per-account cost. The measure that actually raises the price of a rigged PvP match is the level
 * gate, and that one is `PvpUnlockTest`'s.
 */
class CredentialRecoveryTest {

    // ---- Registration ------------------------------------------------------

    /** The address is required now, and its absence is a refusal rather than a null column. */
    @Test
    fun registeringWithoutAnAddressIsRefused() = server {
        val name = Postgres.freshAccount("no-mail")

        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, TEST_PASSWORD)))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertEquals(AccountError.MALFORMED_EMAIL, failure(response).error)
    }

    /**
     * And a malformed one names the field that is wrong.
     *
     * `MALFORMED_CREDENTIALS` would be the easy answer and a useless one: a player whose password
     * was fine and whose address had a typo would be told to look at the password.
     */
    @Test
    fun aMalformedAddressIsRefusedAsAnAddressAndNotAsCredentials() = server {
        val name = Postgres.freshAccount("typo")

        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, TEST_PASSWORD, "kuplu.example")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertEquals(AccountError.MALFORMED_EMAIL, failure(response).error)
    }

    /**
     * Two collisions, two different answers.
     *
     * The distinction is the whole reason `respondCollision` runs two queries after a failed
     * insert: "that name is taken" told to somebody whose *address* is already registered sends
     * them off to invent a new username, which will fail in exactly the same way.
     */
    @Test
    fun aTakenAddressIsRefusedAsAnAddressAndATakenNameAsAName() = server {
        val first = Postgres.freshAccount("dup-a")
        register(first)

        val sameAddress = client.post("/accounts") {
            protocolHeaders()
            setBody(
                json.encodeToString(
                    Credentials(Postgres.freshAccount("dup-b"), TEST_PASSWORD, address(first)),
                ),
            )
        }
        val sameName = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(credentials(first).copy(email = "elsewhere@example.test")))
        }

        assertEquals(HttpStatusCode.Conflict, sameAddress.status, sameAddress.bodyAsText())
        assertEquals(AccountError.EMAIL_TAKEN, failure(sameAddress).error)
        assertEquals(HttpStatusCode.Conflict, sameName.status, sameName.bodyAsText())
        assertEquals(AccountError.USERNAME_TAKEN, failure(sameName).error)
    }

    // ---- Confirming an address ---------------------------------------------

    /** A new account is signed in, unconfirmed, and has already been sent the code. */
    @Test
    fun registeringSendsACodeAndLeavesTheAccountUnconfirmed() = server { mailer ->
        val name = Postgres.freshAccount("fresh")
        val session = register(name)

        assertEquals(1, mailer.sent.size, "registration sent ${mailer.sent.size} mails")
        assertEquals(address(name), mailer.sent.single().first)
        assertFalse(me(session.token).verified, "a brand new account was already confirmed")
        assertEquals(address(name), me(session.token).email)
    }

    /** And answering it confirms the account. */
    @Test
    fun theCodeFromTheMailConfirmsTheAddress() = server { mailer ->
        val name = Postgres.freshAccount("confirm")
        val session = register(name)

        val response = verify(session.token, mailer.codeFor(address(name)))

        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
        assertTrue(me(session.token).verified, "the account is still unconfirmed")
    }

    /**
     * Whitespace does not stop it.
     *
     * A code copied out of a mail arrives with spaces in it often enough to matter, `looksValid`
     * accepts one, and the fingerprint has to be taken after the strip or the *right* code is
     * answered "invalid". See `CodeChannel.spend`.
     */
    @Test
    fun aCodePastedWithSpacesStillWorks() = server { mailer ->
        val name = Postgres.freshAccount("spaced")
        val session = register(name)
        val code = mailer.codeFor(address(name))

        val response = verify(session.token, "${code.take(3)} ${code.drop(3)}")

        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
    }

    @Test
    fun aWrongCodeIsRefusedAndConfirmsNothing() = server {
        val session = register(Postgres.freshAccount("wrong"))

        val response = verify(session.token, "000000")

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertEquals(AccountError.INVALID_CODE, failure(response).error)
        assertFalse(me(session.token).verified)
    }

    /**
     * Five guesses, and then the code is dead — including for the person who then gets it right.
     *
     * This is the bound that makes six digits safe at all, and it is only a bound alongside the
     * `CODES` rate limit: without one the attack is guess five, resend, repeat. Both halves are
     * stated where they are implemented; this measures the half that can be measured cheaply.
     */
    @Test
    fun theCorrectCodeIsRefusedAfterTooManyWrongGuesses() = server { mailer ->
        val name = Postgres.freshAccount("burn")
        val session = register(name)
        val code = mailer.codeFor(address(name))

        repeat(CodeStore.MAX_ATTEMPTS) { attempt ->
            val response = verify(session.token, wrongCodeOtherThan(code, attempt))
            assertEquals(HttpStatusCode.BadRequest, response.status, "guess $attempt was accepted")
        }

        val response = verify(session.token, code)
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertFalse(me(session.token).verified)
    }

    /**
     * Asking for another code kills the first.
     *
     * The primary key on `account_codes` is what enforces it, and it has to: pressing the button
     * five times must not leave five live codes and five times the surface to guess at.
     */
    @Test
    fun resendingReplacesTheCodeRatherThanAddingOne() = server { mailer ->
        val name = Postgres.freshAccount("resend")
        val session = register(name)
        val first = mailer.codeFor(address(name))

        val resent = client.post("/me/email/resend") {
            protocolHeaders()
            bearer(session.token)
        }
        assertEquals(HttpStatusCode.Accepted, resent.status, resent.bodyAsText())
        // Index 1, not 0: registration already mailed one code, so the resent one is the *second*
        // message to this address. Reading index 0 here compares the first code with itself and
        // passes for a server that never resent anything.
        val second = mailer.codeFor(address(name), index = 1)
        assertNotEquals(first, second, "the resend sent the same code again")

        assertEquals(HttpStatusCode.BadRequest, verify(session.token, first).status)
        assertEquals(HttpStatusCode.NoContent, verify(session.token, second).status)
    }

    /**
     * A code for one purpose does not serve the other.
     *
     * `account_codes` is keyed on the purpose as well as the account, so a reset code offered to
     * the confirmation endpoint finds nothing. Worth pinning: the two flows are near-identical and
     * the column that keeps them apart is easy to drop by accident.
     */
    @Test
    fun aResetCodeCannotConfirmAnAddress() = server { mailer ->
        val name = Postgres.freshAccount("purpose")
        val session = register(name)
        forgot(name)
        val resetCode = mailer.codeFor(address(name), index = 1)

        val response = verify(session.token, resetCode)

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertFalse(me(session.token).verified)
    }

    // ---- Forgotten passwords -----------------------------------------------

    /**
     * The endpoint answers the same way for an account that does not exist.
     *
     * Otherwise the form is a way of asking which usernames are registered — the leak that
     * `INVALID_CREDENTIALS` closes on the sign-in form, reopened on a form nobody was watching.
     * The response is checked *and* the mailer is: an identical status with a mail going out only
     * in one case is the same oracle with a longer stopwatch.
     */
    @Test
    fun forgettingThePasswordOfAnAccountThatDoesNotExistLooksIdentical() = server { mailer ->
        val known = Postgres.freshAccount("known")
        register(known)
        mailer.sent.clear()

        val forStranger = forgot("nobody-by-that-name")
        val forKnown = forgot(known)

        assertEquals(HttpStatusCode.Accepted, forStranger.status, forStranger.bodyAsText())
        assertEquals(HttpStatusCode.Accepted, forKnown.status, forKnown.bodyAsText())
        assertEquals(
            listOf(address(known)),
            mailer.sent.map { it.first },
            "a mail went to somebody it should not have, or none went to somebody it should",
        )
    }

    /**
     * The reset works, and takes every session with it.
     *
     * The revocation is the half that is easy to leave out and is the reason somebody resets a
     * password in the first place: if the thief's thirty-day token survives, the reset has
     * answered the wrong half of the problem.
     */
    @Test
    fun resettingChangesThePasswordAndEndsEverySession() = server { mailer ->
        val name = Postgres.freshAccount("reset")
        val session = register(name)
        forgot(name)

        val response = reset(name, mailer.codeFor(address(name), index = 1), NEW_PASSWORD)

        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
        assertEquals(
            HttpStatusCode.Unauthorized,
            meResponse(session.token).status,
            "the session that existed before the reset still works",
        )
        assertEquals(HttpStatusCode.Unauthorized, signIn(name, TEST_PASSWORD).status)
        assertEquals(HttpStatusCode.OK, signIn(name, NEW_PASSWORD).status)
    }

    /**
     * And confirms the address by the same stroke.
     *
     * Answering a code sent to the address proves the player holds it, which is exactly what
     * confirmation asks. Nagging them to prove it again immediately afterwards would be asking a
     * question they have just answered.
     */
    @Test
    fun resettingConfirmsTheAddressItWasSentTo() = server { mailer ->
        val name = Postgres.freshAccount("reset-confirm")
        register(name)
        forgot(name)

        reset(name, mailer.codeFor(address(name), index = 1), NEW_PASSWORD)

        val session = json.decodeFromString<Session>(signIn(name, NEW_PASSWORD).bodyAsText())
        assertTrue(me(session.token).verified, "a reset left the address unconfirmed")
    }

    /** A wrong reset code changes nothing, and says no more than any other bad code. */
    @Test
    fun aWrongResetCodeLeavesThePasswordAlone() = server {
        val name = Postgres.freshAccount("reset-wrong")
        register(name)
        forgot(name)

        val response = reset(name, "000000", NEW_PASSWORD)

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertEquals(AccountError.INVALID_CODE, failure(response).error)
        assertEquals(HttpStatusCode.OK, signIn(name, TEST_PASSWORD).status)
    }

    /**
     * A code is bound to the account it was sent to.
     *
     * The username on the request body names the account; the code is looked up under *that*
     * account. So Bob's code cannot reset Alice's password, whatever Bob writes in the field.
     */
    @Test
    fun aCodeIssuedForOneAccountCannotResetAnother() = server { mailer ->
        val alice = Postgres.freshAccount("bound-a")
        val bob = Postgres.freshAccount("bound-b")
        register(alice)
        register(bob)
        forgot(bob)

        val response = reset(alice, mailer.codeFor(address(bob), index = 1), NEW_PASSWORD)

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertEquals(HttpStatusCode.OK, signIn(alice, TEST_PASSWORD).status)
    }

    // ---- Harness -----------------------------------------------------------

    /**
     * A mailer that keeps what it was given instead of sending it.
     *
     * The one thing every test here needs: the code, which only exists inside a message. Reading it
     * back out with a regular expression rather than from a field is deliberate — it is the same
     * thing a player does, so a template that stopped carrying the code would fail these tests
     * rather than quietly send a mail with nothing usable in it.
     */
    private class RecordingMailer : Mailer {
        val sent = mutableListOf<Pair<String, MailMessage>>()

        override suspend fun send(to: String, message: MailMessage): Boolean {
            sent += to to message
            return true
        }

        /** The code in the [index]th mail sent to [address], counting from zero. */
        fun codeFor(address: String, index: Int = 0): String {
            val body = sent.filter { it.first == address }[index].second.body
            return assertNotNull(
                SIX_DIGITS.find(body)?.value,
                "no code in the mail sent to $address:\n$body",
            )
        }

        private companion object {
            val SIX_DIGITS = Regex("\\b\\d{${AccountCode.LENGTH}}\\b")
        }
    }

    private fun server(block: suspend ApplicationTestBuilder.(RecordingMailer) -> Unit) =
        testApplication {
            val mailer = RecordingMailer()
            application { module(Postgres.dataSource, prometheusRegistry(), mailer = mailer) }
            block(mailer)
        }

    private suspend fun ApplicationTestBuilder.register(name: String): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(credentials(name)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString<Session>(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.verify(token: String, code: String): HttpResponse =
        client.post("/me/email/verify") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(AccountCode(code)))
        }

    private suspend fun ApplicationTestBuilder.forgot(name: String): HttpResponse =
        client.post("/accounts/password/forgot") {
            protocolHeaders()
            setBody(json.encodeToString(PasswordResetRequest(name)))
        }

    private suspend fun ApplicationTestBuilder.reset(
        name: String,
        code: String,
        password: String,
    ): HttpResponse = client.post("/accounts/password/reset") {
        protocolHeaders()
        setBody(json.encodeToString(PasswordReset(name, code, password)))
    }

    private suspend fun ApplicationTestBuilder.signIn(
        name: String,
        password: String,
    ): HttpResponse = client.post("/sessions") {
        protocolHeaders()
        setBody(json.encodeToString(Credentials(name, password)))
    }

    private suspend fun ApplicationTestBuilder.meResponse(token: String) = client.get("/me") {
        protocolHeaders()
        bearer(token)
    }

    private suspend fun ApplicationTestBuilder.me(token: String): PlayerState {
        val response = meResponse(token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<PlayerState>(response.bodyAsText())
    }

    private suspend fun failure(response: HttpResponse): AccountFailure =
        json.decodeFromString<AccountFailure>(response.bodyAsText())

    /** A six-digit code that is definitely not [code], and differs from the last wrong guess. */
    private fun wrongCodeOtherThan(code: String, attempt: Int): String {
        val guess = attempt.toString().padStart(AccountCode.LENGTH, '1')
        return if (guess == code) guess.replaceRange(0, 1, "2") else guess
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
        const val NEW_PASSWORD = "a-different-horse-entirely"
    }
}
