package com.tripletriad.server

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Getting into the administration console: a password, a code, and a cookie that no script can
 * read.
 *
 * ### Why this is not `authenticate(store)` with a different table
 *
 * `Authentication.kt` argues against Ktor's `Authentication` plugin on the grounds that there is
 * one scheme and one realm, and ends with the sentence this file is the arrival of: "the moment a
 * second scheme appears (a service token, an OAuth exchange), this stops being true." This is that
 * second scheme — a cookie rather than a bearer header, over its own table, with a second factor
 * the game's sign-in does not have. It still does not use the plugin, for the same reason as
 * before: what the plugin would add is a configuration block and a principal type between a route
 * and a check that reads in one pass. What has changed is that there are now two checks, and
 * keeping them in two files whose names say which is which is the part that matters.
 *
 * ### `requireCompatibleClient()` is deliberately absent, and this is the note about it
 *
 * `CLAUDE.md` requires the version gate on every new route, and `web-platform.md` names these
 * routes as the exception: they carry no transcript and no `GameSave`, so a protocol bump cannot
 * make this build misread their bodies — the failure mode the gate exists for. Applying it here
 * would have a real cost and no benefit: a server release nobody has rebuilt the console against
 * would lock the one person who could investigate it out of the console, which is precisely the
 * moment the console is needed. The console's own `api.ts` carries the other half of this note,
 * where somebody looking for the missing `X-TTO-Version` header will come.
 *
 * ### Why a wrong password is not a 401
 *
 * Because 401 means something else to the client: the console treats it as "the session is gone"
 * and navigates to the sign-in page, without reading the body — `api.ts` explains that a 401 from a
 * framework carries no code of its own, so the status has to be enough. Answering a mistyped
 * password with 401 would therefore show "the session has expired" on the form somebody is trying
 * to sign in *with*. So the credential refusals are [REFUSED], which is 422: the request was
 * well-formed and the server could not process what it asserted. 401 is reserved for exactly one
 * thing, which is a cookie that is missing, expired, idle or withdrawn.
 *
 * ### What a failed attempt leaves behind
 *
 * A log line and a spent token from the [ADMIN_SIGN_IN] bucket, and nothing in `admin_audit` —
 * `admin_id` there is `NOT NULL`, so a table only an authenticated request can write is a table an
 * attacker cannot fill. `V19__admin.sql` says the same from the schema's side.
 */
fun Route.adminAuthRoutes(
    admins: AdminStore,
    identity: ServerIdentity = ServerIdentity(name = "Triple Triad"),
    clock: () -> Long = System::currentTimeMillis,
) {
    route("/admin") {
        // Its own bucket, not [SIGN_IN]'s. The argument is `REGISTER`'s: two unauthenticated
        // surfaces attacked at different rates should not share a budget, because sharing means the
        // honest burst pays for the hostile one — and here the honest burst is the only person who
        // can investigate an incident, refused because the game's sign-in form was being hammered.
        rateLimit(RateLimitName(ADMIN_SIGN_IN)) {
            post("/sessions") { signInAdmin(admins, identity, clock) }
            post("/sessions/totp") { confirmEnrolment(admins, clock) }
        }

        // Outside the bucket on purpose. Signing out is not a guess at a credential, and a rate
        // limit on it would mean somebody who has just been told to sign out cannot.
        delete("/sessions") {
            val token = call.request.cookies[ADMIN_COOKIE]
            token?.let { admins.closeSession(Tokens.fingerprint(it)) }
            // Cleared whether or not a row went with it: the cookie is the thing in front of the
            // person clicking, and one that survives a sign-out is the failure they would notice.
            call.clearAdminCookie()
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val admin = authenticateAdmin(admins) ?: return@get
            call.respond(
                HttpStatusCode.OK,
                AdminIdentity(
                    username = admin.username,
                    signedInAt = Instant.ofEpochMilli(admin.signedInAt).toString(),
                    expiresAt = Instant.ofEpochMilli(admin.expiresAt).toString(),
                ),
            )
        }
    }
}

/**
 * Who is calling, from the session cookie — or a 401 that has already been sent.
 *
 * The shape `authenticate(store)` has, and for the same reason: a route wants one line that either
 * gives it an identity or has dealt with the caller. What comes back is the whole [SignedInAdmin]
 * rather than an id, because every admin route that will exist wants the username for the audit row
 * it is about to write, and looking it up again would be a second query for something just read.
 *
 * The cookie is **fingerprinted** before it is used for anything and is never logged, echoed, or
 * put in an error message. It is as good as the password and the second factor together for as long
 * as it lives, which is more than the player's bearer token is.
 */
suspend fun RoutingContext.authenticateAdmin(admins: AdminStore): SignedInAdmin? {
    val session = call.request.cookies[ADMIN_COOKIE]
        ?.takeIf { it.isNotBlank() }
        ?.let { admins.session(Tokens.fingerprint(it), ADMIN_IDLE_MILLIS) }

    if (session == null) {
        // No detail, and the console does not read one: "expired", "idle" and "never existed" are
        // the same answer to somebody who has to sign in again, and telling the difference to a
        // caller who is guessing would say whether a cookie they hold was ever real.
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "UNAUTHENTICATED"))
    }
    return session
}

/**
 * `POST /admin/sessions` — the password and, for everybody past their first visit, the code.
 *
 * Answers one of three things: a session, an enrolment for an administrator who has no second
 * factor yet, or a refusal. The enrolment is modelled as a success rather than an error because
 * that is what it is — a first sign-in working as designed — and the console renders it as the next
 * step of the same form.
 */
private suspend fun RoutingContext.signInAdmin(
    admins: AdminStore,
    identity: ServerIdentity,
    clock: () -> Long,
) {
    val admin = verifiedAdmin(admins, call.receive(), clock) ?: return

    // No second factor yet: issue one, store it against the row, and show it exactly once. Nothing
    // is signed in by this answer — the console sends the password again with a code from the key
    // it has just been given, which is what keeps "authenticated" meaning password *and* code with
    // no half-session in between for a stolen password to use.
    if (!admin.enrolled) return beginEnrolment(admins, admin, identity)

    // `enrolled` implies a secret — `admins_totp_enrolled` is a CHECK constraint — so this is a
    // corrupt row rather than a state, and a sign-in to refuse rather than a 500 to serve.
    val secret = admin.totpSecret ?: return call.refuseAdmin("ENROLMENT_REQUIRED")
    val step = admin.codeStep(secret, clock()) ?: return call.refuseAdmin("INVALID_CODE")

    if (!admins.signIn(admin.id, step, Tokens.fingerprint(admin.token), admin.expiresAt)) {
        // The step has already been used. From the honest side that is a code that has expired on
        // screen, so it is answered as a wrong one — see `AdminStore.signIn`.
        return call.refuseAdmin("INVALID_CODE")
    }
    call.openAdminSession(admin)
}

/**
 * `POST /admin/sessions/totp` — the password again, and a code from the key just shown.
 *
 * Its own route rather than a flag on the one above, because it is the only request in this API
 * that *stores* a credential. The password is sent a second time rather than carried in a
 * half-session: a temporary token between the two steps would be a credential that a stolen
 * password alone can obtain, which is the thing the second factor is bought to prevent.
 */
private suspend fun RoutingContext.confirmEnrolment(admins: AdminStore, clock: () -> Long) {
    val admin = verifiedAdmin(admins, call.receive(), clock) ?: return

    // Nothing pending. Either the enrolment was never begun, or — the case worth being precise
    // about — this administrator is already enrolled, and their secret must not be replaceable by
    // anybody holding the password. See `AdminStore.beginEnrolment` on the window and its closing.
    val secret = admin.totpSecret?.takeIf { !admin.enrolled }
        ?: return call.refuseAdmin("ENROLMENT_REQUIRED")

    val step = admin.codeStep(secret, clock()) ?: return call.refuseAdmin("INVALID_CODE")

    if (!admins.enrol(admin.id, step, Tokens.fingerprint(admin.token), admin.expiresAt)) {
        return call.refuseAdmin("CONFLICT")
    }
    call.openAdminSession(admin)
}

/**
 * Issues the secret, stores it, and shows it — once.
 *
 * The response is the only place this value is ever written outside the row it belongs to. It is
 * not logged, not in a metric, not in an error message, and the whole reason enrolment lives here
 * rather than in the bootstrap is that a start-up that printed it would have broken this
 * repository's rule about secrets on the very first boot.
 */
private suspend fun RoutingContext.beginEnrolment(
    admins: AdminStore,
    admin: Candidate,
    identity: ServerIdentity,
) {
    val secret = Totp.issueSecret()
    // False only if somebody enrolled between the read and this write, which means a second factor
    // now exists and this request must not show a key that would never work.
    if (!admins.beginEnrolment(admin.id, secret)) return call.refuseAdmin("CONFLICT")

    call.respond(
        HttpStatusCode.OK,
        AdminEnrolment(secret = secret, uri = Totp.uri(identity.name, admin.username, secret)),
    )
}

/**
 * The administrator whose password matches what was sent, or null with a refusal already answered.
 *
 * ### The two leaks this closes, both of which the player's sign-in closes the same way
 *
 * A wrong username and a wrong password give the **same** refusal, so the form is not a way of
 * asking which administrators exist. And an unknown username still pays for a bcrypt verification —
 * `PasswordHasher.verifyOrDecoy` — so the response time is not an oracle either, which would defeat
 * the identical message.
 *
 * `disabled` is reported only **after** the password verifies. Before that it would answer a
 * question the caller has not earned: that the name is real, and that it has been retired.
 */
// ReturnCount: two refusals and the administrator — and the two refusals are the two different
// things that can be wrong, which is exactly what the rule is asking to be made obvious.
@Suppress("ReturnCount")
private suspend fun RoutingContext.verifiedAdmin(
    admins: AdminStore,
    credentials: AdminCredentials,
    clock: () -> Long,
): Candidate? {
    val stored = admins.byUsername(credentials.username.trim())
    if (!PasswordHasher.verifyOrDecoy(credentials.password, stored?.passwordHash)) {
        call.refuseAdmin("INVALID_CREDENTIALS")
        return null
    }
    val admin = requireNotNull(stored)
    if (admin.disabled) {
        call.refuseAdmin("ACCOUNT_DISABLED")
        return null
    }

    // The cost factor may have been raised since this password was hashed, and a successful sign-in
    // is the only moment the plaintext exists to redo it with. The player's path does the same.
    if (PasswordHasher.needsRehash(admin.passwordHash)) {
        admins.updatePasswordHash(admin.id, PasswordHasher.hash(credentials.password))
    }
    return Candidate(admin, credentials.code, clock())
}

/**
 * One request's worth of a verified administrator: the row, the code they typed, and the token this
 * request would hand them.
 *
 * The token is minted **here**, before either write path, so that `AdminStore` only ever sees its
 * fingerprint — the property that makes a database dump useless for impersonation. `AccountRoutes`
 * keeps the same line for the player's side, and it is worth being able to see in one place.
 */
private class Candidate(stored: StoredAdmin, private val typedCode: String?, now: Long) {
    val id = stored.id
    val username = stored.username
    val totpSecret = stored.totpSecret
    val enrolled = stored.enrolled

    /** **Secret.** Goes into the cookie and nowhere else. */
    val token: String = Tokens.issue()

    val expiresAt: Long = now + ADMIN_SESSION_MILLIS

    /** The step the typed code was computed at, or null — absent counts as wrong, which it is. */
    fun codeStep(secret: String, now: Long): Long? =
        typedCode?.let { Totp.matchingStep(secret, it, now) }
}

/** Sets the cookie and says so. The console reads the state, not the status. */
private suspend fun ApplicationCall.openAdminSession(admin: Candidate) {
    setAdminCookie(admin.token, ADMIN_SESSION_MILLIS)
    respond(HttpStatusCode.OK, AdminSignedIn(state = "signedIn"))
}

/**
 * The session cookie, with every attribute the `__Host-` prefix makes the browser enforce.
 *
 * ### Why the prefix and not just the attributes
 *
 * `Secure`, `HttpOnly`, `SameSite=Strict` and `Path=/` are promises the server makes; `__Host-` is
 * the browser *refusing the cookie* if any of them is missing or a `Domain` is present. The
 * difference shows up on the day somebody adds a subdomain, or a proxy rewrites a header, or a
 * future version of this function drops an attribute — the failure becomes "no cookie at all",
 * which is immediately visible, instead of "a cookie that leaks to a sibling host", which is not.
 *
 * It also forbids a `Domain`, which is the attribute that would let this credential reach the
 * portal's origin or the browser game's. Neither should ever see it.
 *
 * ### `Max-Age`, and which clock actually decides
 *
 * The value here matches `admin_sessions.expires_at`, so a browser does not keep a credential past
 * the point the server would accept it. It is not the clock that matters: the server's **idle**
 * timeout is, and it is far shorter — see `AdminStore.session`, where all three conditions live.
 *
 * ### `RAW` encoding
 *
 * The token is URL-safe base64, so URI encoding would be a no-op — and being explicit means the
 * value the browser stores is byte for byte the value that was fingerprinted. A credential that
 * survives a round trip only because two encoders happen to agree is a credential waiting to break.
 */
private fun ApplicationCall.setAdminCookie(token: String, lifetimeMillis: Long) {
    response.cookies.append(
        Cookie(
            name = ADMIN_COOKIE,
            value = token,
            maxAge = (lifetimeMillis / MILLIS_PER_SECOND).toInt(),
            path = "/",
            secure = true,
            httpOnly = true,
            encoding = CookieEncoding.RAW,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

/**
 * Removes it, on the browser's side.
 *
 * Same name and same attributes with an empty value and `Max-Age=0`, because a cookie is replaced
 * only by one whose name, path and domain all match — a clearing cookie that forgets `Secure` or
 * `Path` leaves the original sitting there. `__Host-` would refuse this one outright without them.
 */
private fun ApplicationCall.clearAdminCookie() {
    response.cookies.append(
        Cookie(
            name = ADMIN_COOKIE,
            value = "",
            maxAge = 0,
            path = "/",
            secure = true,
            httpOnly = true,
            encoding = CookieEncoding.RAW,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

/**
 * A refusal the console can act on, under [REFUSED] rather than a 401 or a 403.
 *
 * The codes are upper case because that is the set `console/lib/api.ts` maps to behaviour —
 * `invalidCode` marks one field, `unauthenticated` navigates away, `conflict` means nothing changed
 * — and a code it does not recognise degrades to "unexpected", which is the honest answer for a
 * server and a console that disagree.
 *
 * There is no sentence in the body. The console holds the wording, in French, in one file, because
 * the reader is one person and the text changes for editorial reasons that have nothing to do with
 * this server. Compare `AccountFailure`, which carries an English sentence for a client that shows
 * it to thousands of players in two languages.
 */
private suspend fun ApplicationCall.refuseAdmin(code: String) =
    respond(REFUSED, ErrorResponse(error = code))

/** What `GET /admin/me` answers. ISO 8601 so the console can render either instant. */
@Serializable
private data class AdminIdentity(
    val username: String,
    val signedInAt: String,
    val expiresAt: String,
)

/**
 * `{username, password, code}`.
 *
 * The code is nullable because the first sign-in genuinely has none: an administrator with no
 * second factor cannot produce one, and a required field here would lock the first one out of the
 * console permanently.
 */
@Serializable
private data class AdminCredentials(
    val username: String,
    val password: String,
    val code: String? = null,
)

/**
 * The signed-in answer, as a state rather than as a bare 200 — see [AdminEnrolment].
 *
 * `state` has no default, and that is not an oversight: kotlinx.serialization omits a property that
 * still holds its default, so `AdminSignedIn()` would go out as `{}` and the field documented here
 * would exist only in this file. The console happens to survive that — it decides by looking for
 * `secret` and `uri` — but a shape the server describes and does not send is the kind of difference
 * that is discovered from the other side, months later.
 */
@Serializable
private data class AdminSignedIn(val state: String)

/**
 * The enrolment answer: **both fields are secret**, and this is the only response in the API that
 * carries one.
 *
 * It is a 200 rather than an error because enrolment is not a failure. The console discriminates on
 * the presence of these two fields, which is why the signed-in answer above carries a `state`
 * instead of being empty — two answers to one request should be told apart by what they contain.
 */
@Serializable
private data class AdminEnrolment(val secret: String, val uri: String)

/**
 * The cookie's name, with the prefix that makes its attributes the browser's business.
 *
 * Not `session`, and not anything the game uses: this credential and the player's must not be
 * confusable by anything reading a request, including a person reading a proxy log.
 */
internal const val ADMIN_COOKIE = "__Host-tto_admin"

/**
 * 422, for a request that was well formed and whose credentials do not let it proceed.
 *
 * The status matters less than what it is *not* — see the note at the top of this file on why a
 * mistyped password cannot be answered with 401.
 */
private val REFUSED = HttpStatusCode.UnprocessableEntity

/**
 * Twelve hours, the ceiling. A session that began this morning is over by tonight, whatever the
 * screen it is sitting on says.
 */
private const val ADMIN_SESSION_HOURS = 12L
internal const val ADMIN_SESSION_MILLIS = ADMIN_SESSION_HOURS * 60 * 60 * 1000

/**
 * Thirty minutes idle, and this is the clock that does the work.
 *
 * The threat an administration console actually faces is not a thirty-day token — it is a browser
 * left open on a desk, in an office, next to a coffee. The ceiling above bounds the worst case;
 * this bounds the ordinary one, and it is short enough that walking away from a console costs
 * somebody a password and a code when they come back. That is the intended amount of friction.
 */
private const val ADMIN_IDLE_MINUTES = 30L
internal const val ADMIN_IDLE_MILLIS = ADMIN_IDLE_MINUTES * 60 * 1000

private const val MILLIS_PER_SECOND = 1_000L
