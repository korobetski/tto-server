package com.tripletriad.server

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Getting into the administration console, and the several ways of not getting in.
 *
 * ### Why this is one file and not two
 *
 * Because the console's front door is one mechanism with three parts — a password, a one-time code
 * and a cookie — and every interesting failure is a failure of the *seam* between two of them: a
 * code accepted twice, a cookie that outlives its idle window, a password that verifies for an
 * administrator who has been retired. Testing the three parts separately would pass while the door
 * stood open.
 *
 * ### What is tested through HTTP and what is tested through the store
 *
 * Anything an operator's browser does goes through the routes, because the cookie's attributes and
 * the status codes *are* the contract the console was written against — `api.ts` reads the status
 * before the body, which is why a wrong password here must not be a 401.
 *
 * The two clocks are tested through [AdminStore] instead, and deliberately: idle and absolute
 * expiry are enforced by `now()` inside a single SQL statement, so there is no clock to fake from
 * outside. Passing a different idle window to the same query is the honest way to ask the question
 * the database will be asked in thirty minutes' time.
 */
class AdminConsoleAccessTest {

    /**
     * The first sign-in enrols; the second signs in. One flow, because they are one flow.
     *
     * The enrolment answer is a **200** and not an error, which is the decision worth pinning: a
     * first sign-in working as designed is not a failure, and the console renders the key it
     * returns as the next step of the same form. Nothing is signed in by it — no cookie comes back
     * — because a half-session between the password and the code would be a credential a stolen
     * password alone could obtain, which is the entire thing the second factor is bought to
     * prevent.
     */
    @Test
    fun theFirstSignInEnrolsAndTheSecondOpensASession() = console { name, password ->
        val enrolment = attempt(name, password)
        assertEquals(HttpStatusCode.OK, enrolment.status, enrolment.bodyAsText())
        assertNull(enrolment.cookieHeader(), "enrolment must not open a session")

        val secret = enrolment.field("secret")
        assertTrue(enrolment.field("uri").contains("secret=$secret"))

        val signedIn = confirm(name, password, codeFor(secret))
        assertEquals(HttpStatusCode.OK, signedIn.status, signedIn.bodyAsText())
        assertEquals("signedIn", signedIn.field("state"))

        val identity = client.get("/admin/me") { cookie(assertNotNull(signedIn.token())) }
        assertEquals(HttpStatusCode.OK, identity.status, identity.bodyAsText())
        assertEquals(name, identity.field("username"))
    }

    /**
     * A code that has been used cannot be used again, even inside its own thirty seconds.
     *
     * This is the property a TOTP implementation is usually missing. The code is valid for a whole
     * step, so somebody who reads it over a shoulder — or out of a phishing form a moment before
     * the real sign-in — has a window to use it in. `admins.totp_last_step` closes that window by
     * refusing any step already seen, and the refusal is in the `UPDATE`'s `WHERE` clause rather
     * than in Kotlin, because a read-compare-write is exactly the shape two simultaneous requests
     * slip through.
     *
     * The second answer is a plain `INVALID_CODE`: from the honest side, a step that has been spent
     * is a code that has gone stale on screen, and "that code is already used" would tell somebody
     * replaying it that they had the right one.
     */
    @Test
    fun aCodeCannotBeUsedTwice() = console { name, password ->
        val secret = attempt(name, password).field("secret")
        val code = codeFor(secret)

        assertEquals(HttpStatusCode.OK, confirm(name, password, code).status)

        val replayed = attempt(name, password, code)
        assertEquals(REFUSED, replayed.status, replayed.bodyAsText())
        assertEquals("INVALID_CODE", replayed.failure())
        assertNull(replayed.cookieHeader())
    }

    /**
     * The session cookie is `__Host-`, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, no
     * `Domain`.
     *
     * Every one of those is load-bearing and this is the only place they can be checked, because
     * they are attributes of a header rather than behaviour of a function:
     *
     * - `HttpOnly` is what makes an XSS on the console unable to *read* the credential.
     * - `Secure` and the absent `Domain` are what the `__Host-` prefix requires — and the prefix
     *   is enforcement rather than a promise: a browser refuses the cookie outright if either is
     *   wrong, so a misconfiguration is a console nobody can sign in to rather than one silently
     *   sharing its cookie with a sibling subdomain.
     * - `SameSite=Strict` is what stops another site's page from making an authenticated request to
     *   a console that can move balances.
     */
    @Test
    fun theCookieIsTheOneABrowserWillDefend() = console { name, password ->
        val secret = attempt(name, password).field("secret")

        val header = assertNotNull(confirm(name, password, codeFor(secret)).cookieHeader())

        assertTrue(header.startsWith("$ADMIN_COOKIE="), header)
        assertTrue(ADMIN_COOKIE.startsWith("__Host-"), ADMIN_COOKIE)
        assertTrue(header.contains("Path=/"), header)
        assertTrue(header.contains("Secure"), header)
        assertTrue(header.contains("HttpOnly"), header)
        assertTrue(header.contains("SameSite=Strict"), header)
        assertFalse(header.contains("Domain", ignoreCase = true), header)

        // The lifetime the console is told about is the absolute ceiling, in seconds.
        val lifetime = "Max-Age=(\\d+)".toRegex().find(header)?.groupValues?.get(1)?.toLong()
        assertEquals(ADMIN_SESSION_MILLIS / MILLIS_PER_SECOND, lifetime, header)
    }

    /**
     * A wrong password is **422**, not 401 — and the code says which thing was wrong to nobody.
     *
     * The status is the contract: the console's `failureFor()` reads it before the body and treats
     * 401 as "the session is gone", navigating to the sign-in page. Answering a mistyped password
     * with 401 would print "the session has expired" on the form somebody is signing in with.
     */
    @Test
    fun aWrongPasswordIsRefusedWithoutClaimingTheSessionExpired() = console { name, _ ->
        val refused = attempt(name, "$TEST_PASSWORD-wrong")

        assertEquals(REFUSED, refused.status, refused.bodyAsText())
        assertEquals("INVALID_CREDENTIALS", refused.failure())
        assertNull(refused.cookieHeader())
    }

    /**
     * An administrator who does not exist is refused in the same words as one whose password is
     * wrong.
     *
     * Otherwise the form is a way of asking which administrators exist — and the names of the two
     * or three people who can move balances are half of a credential. The timing is the other half
     * of this answer and it is `PasswordHasher.verifyOrDecoy`'s: an unknown name pays for a bcrypt
     * verification too, so the response time is not an oracle either.
     */
    @Test
    fun anUnknownAdministratorIsRefusedInTheSameWords() = console { _, password ->
        val refused = attempt("nobody-${Postgres.freshAccount("admin")}", password)

        assertEquals(REFUSED, refused.status, refused.bodyAsText())
        assertEquals("INVALID_CREDENTIALS", refused.failure())
    }

    /**
     * A disabled administrator is refused **after** their password verifies, and told so.
     *
     * Both halves are deliberate. The check is after verification because answering "this account
     * is disabled" to an unverified caller would say that the name is real and has been retired —
     * which is a map of the staff list. And it is a *distinct* code once verified, because at that
     * point the caller has proved they are the person whose access was withdrawn, and "wrong
     * password" to them is an afternoon of trying harder.
     *
     * An administrator is disabled and never deleted, because `admin_audit` names them: see
     * `V19__admin.sql` and `data-inventory.md`.
     */
    @Test
    fun aDisabledAdministratorIsToldRatherThanLeftGuessing() = console { name, password ->
        disable(name)

        val refused = attempt(name, password)

        assertEquals(REFUSED, refused.status, refused.bodyAsText())
        assertEquals("ACCOUNT_DISABLED", refused.failure())
    }

    /**
     * Disabling an administrator ends the session they already hold.
     *
     * A check at sign-in alone would mean withdrawing access takes effect at the next sign-in —
     * which is the twelve hours after the moment somebody decided it had to stop.
     * `AdminStore.session` joins `admins` and requires `disabled_at IS NULL` in the same statement
     * that validates the cookie, so the next request is the last one.
     */
    @Test
    fun disablingAnAdministratorEndsTheSessionTheyAreUsing() = console { name, password ->
        val secret = attempt(name, password).field("secret")
        val token = assertNotNull(confirm(name, password, codeFor(secret)).token())
        assertEquals(HttpStatusCode.OK, client.get("/admin/me") { cookie(token) }.status)

        disable(name)

        val after = client.get("/admin/me") { cookie(token) }
        assertEquals(HttpStatusCode.Unauthorized, after.status, after.bodyAsText())
    }

    /**
     * An enrolled administrator cannot be enrolled again, by anybody holding the password.
     *
     * This is the bootstrap window closing. Until the first code is entered, the password alone can
     * choose the second factor — inherent to a bootstrap that carries a password and no secret.
     * From then on `totp_enrolled_at IS NULL` in `beginEnrolment`'s `WHERE` clause means the secret
     * is not replaceable, so a stolen password cannot re-enrol an authenticator of its own. A lost
     * authenticator is answered by an operator with `psql` — `docs/operations.md` — and that is the
     * intended amount of friction.
     */
    @Test
    fun anEnrolledAdministratorCannotBeReEnrolled() = console { name, password ->
        val secret = attempt(name, password).field("secret")
        assertEquals(HttpStatusCode.OK, confirm(name, password, codeFor(secret)).status)

        // The sign-in route must not hand out a second key…
        val again = attempt(name, password)
        assertEquals(REFUSED, again.status, again.bodyAsText())
        assertEquals("INVALID_CODE", again.failure(), "a code is required now, not a new key")

        // …and the enrolment route must not accept a code against the old secret as a fresh
        // enrolment, which would move `totp_enrolled_at` and reopen the window.
        val replaced = confirm(name, password, codeFor(secret))
        assertEquals(REFUSED, replaced.status, replaced.bodyAsText())
        assertEquals("ENROLMENT_REQUIRED", replaced.failure())
    }

    /** Without a cookie, an authenticated route is a 401 and says nothing else. */
    @Test
    fun withoutACookieThereIsNothingToRead() = console { _, _ ->
        for (cookie in listOf(null, "", "not-a-token")) {
            val refused = client.get("/admin/me") { cookie?.let { cookie(it) } }
            assertEquals(HttpStatusCode.Unauthorized, refused.status, "cookie <$cookie>")
            assertEquals("UNAUTHENTICATED", refused.failure())
        }
    }

    /**
     * Signing out ends the session server-side and clears the cookie in front of the person.
     *
     * Both, because either alone is the failure somebody notices: a cleared cookie over a live row
     * leaves a credential anybody who copied it can still use, and a closed row behind a cookie
     * that survives leaves the console looking signed in until the next request.
     */
    @Test
    fun signingOutEndsTheSessionAndTakesTheCookieWithIt() = console { name, password ->
        val secret = attempt(name, password).field("secret")
        val token = assertNotNull(confirm(name, password, codeFor(secret)).token())

        val goodbye = client.delete("/admin/sessions") { cookie(token) }
        assertEquals(HttpStatusCode.NoContent, goodbye.status)
        assertTrue(goodbye.cookieHeader().orEmpty().contains("Max-Age=0"), "the cookie is cleared")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/me") { cookie(token) }.status)
    }

    /**
     * Signing out without a cookie is still a 204, and clears one anyway.
     *
     * A sign-out that answers an error is a person stuck on a screen they are trying to leave.
     * There is nothing to protect here either: the request names no session it has not already got.
     */
    @Test
    fun signingOutWithNothingToCloseIsStillASignOut() = console { _, _ ->
        val goodbye = client.delete("/admin/sessions")

        assertEquals(HttpStatusCode.NoContent, goodbye.status)
        assertTrue(goodbye.cookieHeader().orEmpty().contains("Max-Age=0"))
    }

    /**
     * An idle session is not a session — measured against the query that will decide it.
     *
     * The threat this answers is an unattended screen rather than a stolen cookie: a console left
     * open on a desk is the likeliest way an administrative session is used by somebody it does not
     * belong to. Thirty minutes is the window, and `seen_at` moves forward on every validated
     * request, so it measures *silence* rather than age.
     */
    @Test
    fun aSessionThatHasBeenSilentTooLongIsNotASession() {
        val admins = AdminStore(Postgres.dataSource)
        val (adminId, token) = enrolledAdmin(admins)
        val fingerprint = Tokens.fingerprint(token)

        assertNotNull(admins.session(fingerprint, ADMIN_IDLE_MILLIS), "just used")
        assertNull(admins.session(fingerprint, 0), "silent for longer than the window allows")

        // And the failed check did not touch `seen_at`, so the session is still good inside its own
        // window. A validation that refreshed the clock on a refusal would extend a dead session.
        assertEquals(adminId, admins.session(fingerprint, ADMIN_IDLE_MILLIS)?.id)
    }

    /**
     * A session past its absolute ceiling is refused however busy it has been.
     *
     * The idle window alone would let a session that is used every twenty minutes live for ever,
     * which is the same as no expiry for the one case that matters — a credential nobody has
     * noticed is in the wrong hands. Twelve hours is a working day, and it is not renewable.
     */
    @Test
    fun aSessionPastItsCeilingIsRefusedHoweverBusyItHasBeen() {
        val admins = AdminStore(Postgres.dataSource)
        val expired = Tokens.issue()
        val (adminId, _) = enrolledAdmin(admins, token = expired, expiresAt = -1)

        assertNull(admins.session(Tokens.fingerprint(expired), ADMIN_IDLE_MILLIS))
        // Still swept, because a row nobody can use is a row nobody should be keeping.
        assertTrue(admins.sweepSessions() >= 1)
        assertNull(admins.session(Tokens.fingerprint(expired), ADMIN_IDLE_MILLIS))
        assertEquals(adminId, admins.byUsername(adminName(adminId))?.id)
    }

    /**
     * The bootstrap creates the first administrator and is inert on every boot after that.
     *
     * Which is the property that makes leaving `TTO_ADMIN_*` set across a redeploy a mistake with
     * no consequence, rather than a password reset on every restart — and the reason a retired
     * administrator does not come back because somebody forgot to clear an environment file.
     */
    @Test
    fun theBootstrapCreatesOneAdministratorAndThenDoesNothing() {
        val admins = AdminStore(Postgres.dataSource)
        val name = Postgres.freshAccount("bootstrap")
        val bootstrap = FirstAdministrator(name, TEST_PASSWORD)
        val said = mutableListOf<String>()

        assertTrue(ensureFirstAdministrator(admins, bootstrap, said::add))
        val created = assertNotNull(admins.byUsername(name))

        assertFalse(ensureFirstAdministrator(admins, bootstrap, said::add), "the second boot")
        assertEquals(created.id, admins.byUsername(name)?.id, "the same row")
        assertEquals(created.passwordHash, admins.byUsername(name)?.passwordHash, "not rehashed")

        // Nothing it says may name the administrator or their password — `LogSecrecyTest` guards
        // the real logger, and this guards the strings before they reach it.
        assertTrue(said.isNotEmpty())
        for (line in said) {
            assertFalse(line.contains(name), line)
            assertFalse(line.contains(TEST_PASSWORD), line)
        }
    }

    /** With no administrator and no bootstrap, start-up says so rather than passing silently. */
    @Test
    fun anUnreachableConsoleIsSaidOutLoud() {
        val said = mutableListOf<String>()

        assertFalse(ensureFirstAdministrator(AdminStore(Postgres.dataSource), null, said::add))

        // The suite has administrators by now, so this is the steady state: nothing to report.
        assertTrue(said.isEmpty(), said.toString())
    }

    /**
     * The name is case-insensitive, and the row remembers the case it was created with.
     *
     * `admins.username_key` is `lower(username)` with a unique index on it, for the reason
     * `accounts` has the same pair: somebody typing `Ada` at three in the morning is the same
     * person as `ada`, and a console that refuses them is a console that is not there when it is
     * needed.
     */
    @Test
    fun theNameIsMatchedWithoutRegardForCase() {
        val admins = AdminStore(Postgres.dataSource)
        val name = Postgres.freshAccount("Mixed")
        assertNotNull(admins.create(name, PasswordHasher.hash(TEST_PASSWORD)))

        assertEquals(name, admins.byUsername(name.lowercase())?.username)
        assertEquals(name, admins.byUsername(name.uppercase())?.username)
        assertNull(admins.create(name.lowercase(), PasswordHasher.hash(TEST_PASSWORD)), "taken")
    }

    /**
     * An administrator with a bootstrap password, and a console to try it against.
     *
     * The administrator is created through [ensureFirstAdministrator] rather than by inserting a
     * row, so every test here starts from the state a real deployment's first boot leaves behind: a
     * password, and no second factor.
     */
    private fun console(block: suspend ApplicationTestBuilder.(String, String) -> Unit) =
        testApplication {
            application { module(Postgres.dataSource, prometheusRegistry()) }
            val name = Postgres.freshAccount("admin")
            ensureFirstAdministrator(
                AdminStore(Postgres.dataSource),
                FirstAdministrator(name, TEST_PASSWORD),
            ) { }
            block(name, TEST_PASSWORD)
        }

    /** `POST /admin/sessions` — the password, and the code when there is one to send. */
    private suspend fun ApplicationTestBuilder.attempt(
        name: String,
        password: String,
        code: String? = null,
    ): HttpResponse = client.post("/admin/sessions") {
        contentType(ContentType.Application.Json)
        setBody(body(name, password, code))
    }

    /** `POST /admin/sessions/totp` — the password again, and a code from the key just shown. */
    private suspend fun ApplicationTestBuilder.confirm(
        name: String,
        password: String,
        code: String,
    ): HttpResponse = client.post("/admin/sessions/totp") {
        contentType(ContentType.Application.Json)
        setBody(body(name, password, code))
    }

    /**
     * The body by hand rather than through a serialiser.
     *
     * `AdminCredentials` is private to `AdminAuthentication.kt` on purpose — nothing outside that
     * file has any business constructing one — and a test that made it internal to reach it would
     * be widening production visibility to save three lines here.
     */
    private fun body(name: String, password: String, code: String?): String = buildString {
        append("""{"username":"$name","password":"$password"""")
        code?.let { append(""","code":"$it"""") }
        append("}")
    }

    /** The code an authenticator would be showing right now for [secret]. */
    private fun codeFor(secret: String): String =
        Totp.code(secret, Totp.stepAt(System.currentTimeMillis()))

    private fun HttpRequestBuilder.cookie(token: String) =
        header(HttpHeaders.Cookie, "$ADMIN_COOKIE=$token")

    private suspend fun HttpResponse.field(name: String): String =
        assertNotNull(body()[name], "no `$name` in ${bodyAsText()}").jsonPrimitive.content

    private suspend fun HttpResponse.failure(): String =
        json.decodeFromString<ErrorResponse>(bodyAsText()).error

    private suspend fun HttpResponse.body(): JsonObject =
        json.decodeFromString<JsonObject>(bodyAsText())

    private fun HttpResponse.cookieHeader(): String? =
        headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("$ADMIN_COOKIE=") }

    /** The cookie's value, which is the session token itself — never stored, only fingerprinted. */
    private fun HttpResponse.token(): String? = cookieHeader()
        ?.removePrefix("$ADMIN_COOKIE=")
        ?.substringBefore(';')
        ?.takeIf { it.isNotEmpty() }

    /**
     * An enrolled administrator with a live session, built through the store.
     *
     * Reaching past the routes is the point: these are the tests about what the *database* decides,
     * and a sign-in over HTTP cannot be given a session that expired an hour ago.
     */
    private fun enrolledAdmin(
        admins: AdminStore,
        token: String = Tokens.issue(),
        expiresAt: Long = ADMIN_SESSION_MILLIS,
    ): Pair<Long, String> {
        val name = Postgres.freshAccount("session")
        val adminId = assertNotNull(admins.create(name, PasswordHasher.hash(TEST_PASSWORD)))
        val secret = Totp.issueSecret()
        assertTrue(admins.beginEnrolment(adminId, secret))
        assertTrue(
            admins.enrol(
                adminId = adminId,
                step = Totp.stepAt(System.currentTimeMillis()),
                tokenHash = Tokens.fingerprint(token),
                expiresAt = System.currentTimeMillis() + expiresAt,
            ),
        )
        return adminId to token
    }

    /** The name of an administrator by id, for a test that has only the id. */
    private fun adminName(adminId: Long): String = sql("SELECT username FROM admins WHERE id = ?") {
        it.setLong(1, adminId)
    }

    /**
     * Withdraws an administrator's access the way an operator would, with SQL.
     *
     * There is no route for it and there is not meant to be one yet: the console's own screens come
     * with `2.4`, and until then "disabled" is a column an operator sets. `docs/operations.md` is
     * where that is written down for somebody who has to do it at three in the morning.
     */
    private fun disable(name: String) {
        Postgres.dataSource.connection.use { db ->
            db.prepareStatement(
                "UPDATE admins SET disabled_at = now() WHERE username_key = lower(?)",
            ).use { statement ->
                statement.setString(1, name)
                assertEquals(1, statement.executeUpdate(), "no administrator named $name")
            }
            db.commit()
        }
    }

    /** One string out of one row. The pool is not auto-commit, so even a read closes its own. */
    private fun sql(query: String, bind: (java.sql.PreparedStatement) -> Unit): String =
        Postgres.dataSource.connection.use { db ->
            db.prepareStatement(query).use { statement ->
                bind(statement)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next(), query)
                    rows.getString(1)
                }
            }.also { db.commit() }
        }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        /** 422: the request was well-formed and the server would not act on what it asserted. */
        val REFUSED = HttpStatusCode.UnprocessableEntity

        const val MILLIS_PER_SECOND = 1_000L
    }
}
