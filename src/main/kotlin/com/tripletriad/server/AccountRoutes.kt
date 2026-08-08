package com.tripletriad.server

import com.tripletriad.model.GameSave
import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.Session
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Accounts and sessions — the brick `MatchRoutes` said had to come before anything could be
 * credited to a profile.
 *
 * ### The account is the character
 *
 * Registering creates both, in one transaction, and there is no endpoint that creates a character
 * separately. That is decision 2 taken to its conclusion: the profile lives here, the client holds
 * a copy for as long as it is useful, and only a match this server replayed can change it.
 *
 * ### What is deliberately not here
 *
 * - **Password reset and email.** Both need a channel to send to, and there is none. An account is
 *   currently a username, a password and a character; adding recovery is adding a second system.
 * - **Rate limiting.** Sign-in is the one endpoint where guessing is the attack, and bcrypt's cost
 *   is a throttle rather than a limit. Named here so it is a known gap rather than an oversight.
 * - **Refresh tokens.** A session lasts [SESSION_DAYS] days and then the player signs in again.
 *   Rotation buys something real, and it buys it against an attacker who has already taken the
 *   token; there are cheaper things to fix first.
 */
fun Route.accountRoutes(store: AccountStore, clock: () -> Long = System::currentTimeMillis) {
    route("/accounts") {
        /**
         * Creates an account, its character, and a session — one round trip, signed in.
         *
         * **201 with a session**, not 201 with "now go and sign in". The second is a round trip
         * that exists only to prove the client can do what it just did, and every client would
         * immediately make it.
         */
        post {
            if (!requireCompatibleClient()) return@post
            val credentials = call.receive<Credentials>()
            if (!credentials.looksValid()) return@post call.respondMalformed()

            val username = credentials.username.trim()
            val accountId = store.register(
                username = username,
                passwordHash = PasswordHasher.hash(credentials.password),
                // The character is created from the account's own name, so the profile a player
                // signs into is already theirs rather than `Kuplu Kopo` waiting to be renamed.
                save = GameSave.new(username = username, createdAt = clock()),
            )

            if (accountId == null) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    AccountFailure(AccountError.USERNAME_TAKEN, "that name is already taken"),
                )
            }

            call.application.environment.log.info("Registered account {}", accountId)
            call.respond(HttpStatusCode.Created, store.newSession(accountId, clock()))
        }
    }

    route("/sessions") {
        post { signIn(store, clock) }

        /**
         * Signs out — this session only.
         *
         * Always 204, even for a token the server does not recognise. "Your session could not be
         * ended because it had already ended" is an error message about nothing, and a client
         * clearing its own storage should not have to interpret one.
         */
        delete {
            bearerToken()?.let { store.closeSession(Tokens.fingerprint(it)) }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    /**
     * The profile and the match record — what a returning player is shown.
     *
     * This is the endpoint that makes "sign in again and find your account" true: everything the
     * game renders comes from here, so a fresh install with a valid token is indistinguishable from
     * the device the matches were played on.
     */
    get("/me") {
        if (!requireCompatibleClient()) return@get
        val accountId = authenticate(store) ?: return@get

        val player = store.playerState(accountId)
        if (player == null) {
            // An account with no character. Not reachable — registration creates both in one
            // transaction — so this is a 500 rather than a 404: it means the invariant broke, and
            // reporting it as "not found" would hide that behind an ordinary-looking answer.
            call.application.environment.log.error("Account {} has no character", accountId)
            return@get call.respond(HttpStatusCode.InternalServerError, "no character")
        }
        call.respond(HttpStatusCode.OK, player)
    }

    /**
     * Stores a profile the client changed on its own — a card bought, a deck rearranged.
     *
     * ### Why this exists, and why it is not a hole in what matches proved
     *
     * Moving the profile to the server made the server the only writer of a [GameSave]. That is
     * what makes a match's payout trustworthy. But a profile also records things no match produces
     * and no transcript can describe: the shop, the deck editor, the bag. Those rules live on the
     * client and always have, and without somewhere to put their results, moving the profile
     * server-side would mean a card bought before a relaunch was a card lost.
     *
     * So this takes the client at its word. What that costs is stated plainly in
     * [AccountStore.replaceSave]: a determined player can still edit their own MGP. What it does
     * **not** cost is anything a match established — the score, the reward and the match record
     * are computed here from a replay and are not reachable from this endpoint at all.
     */
    put("/me/save") {
        if (!requireCompatibleClient()) return@put
        val accountId = authenticate(store) ?: return@put

        val save = call.receive<GameSave>()
        if (!store.replaceSave(accountId, save)) {
            call.application.environment.log.error("Account {} has no character", accountId)
            return@put call.respond(HttpStatusCode.InternalServerError, "no character")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Signs in.
 *
 * A wrong username and a wrong password give the **same** answer, which is what stops this endpoint
 * from being a way to find out which accounts exist. The password is still verified against a real
 * digest when the account is unknown — see [verifyOrDecoy] — so the two paths take the same time as
 * well as saying the same thing.
 */
private suspend fun RoutingContext.signIn(store: AccountStore, clock: () -> Long) {
    if (!requireCompatibleClient()) return
    val credentials = call.receive<Credentials>()

    val stored = store.credentialsFor(credentials.username.trim())
    if (!verifyOrDecoy(credentials.password, stored?.passwordHash)) {
        return call.respond(
            HttpStatusCode.Unauthorized,
            AccountFailure(
                AccountError.INVALID_CREDENTIALS,
                "that username and password do not match an account",
            ),
        )
    }
    val account = requireNotNull(stored)

    // The cost factor may have been raised since this password was last hashed. Re-hashing on a
    // successful sign-in is the only moment the plaintext is available to do it with, and it costs
    // the player nothing they did not already pay.
    if (PasswordHasher.needsRehash(account.passwordHash)) {
        store.updatePasswordHash(account.accountId, PasswordHasher.hash(credentials.password))
    }

    call.respond(HttpStatusCode.OK, store.newSession(account.accountId, clock()))
}

/**
 * Issues a token and records the session.
 *
 * A method on the store's behalf rather than in it, because the token is generated here and the
 * store only ever sees its fingerprint — which is the property that makes a database dump useless
 * for impersonation, and it is worth being able to see that in one place.
 */
private fun AccountStore.newSession(accountId: Long, now: Long): Session {
    val token = Tokens.issue()
    val expiresAt = now + SESSION_LIFETIME_MILLIS
    openSession(accountId, Tokens.fingerprint(token), expiresAt)
    return Session(
        token = token,
        expiresAt = expiresAt,
        player = requireNotNull(playerState(accountId)) {
            "account $accountId has no character"
        },
    )
}

/**
 * Verifies [password] against [digest], or burns the same time when there is no account.
 *
 * Without the decoy, a sign-in for an unknown username returns in microseconds and one for a known
 * username takes bcrypt's quarter-second — which turns the response time into a perfectly good
 * account-existence oracle, defeating the identical error message above. The decoy digest is a real
 * bcrypt hash of a value nobody knows, so the work is genuinely the same.
 */
private fun verifyOrDecoy(password: String, digest: String?): Boolean {
    if (digest == null) {
        PasswordHasher.verify(password, DECOY_DIGEST)
        return false
    }
    return PasswordHasher.verify(password, digest)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondMalformed() = respond(
    HttpStatusCode.BadRequest,
    AccountFailure(
        AccountError.MALFORMED_CREDENTIALS,
        "a name of ${Credentials.USERNAME_LENGTH.first}-${Credentials.USERNAME_LENGTH.last} " +
            "characters and a password of at least ${Credentials.PASSWORD_LENGTH.first}",
    ),
)

/** Thirty days: long enough that a player is not asked again on a device they use weekly. */
private const val SESSION_DAYS = 30L
private const val SESSION_LIFETIME_MILLIS = SESSION_DAYS * 24 * 60 * 60 * 1000

/**
 * A bcrypt digest of a random value, computed once at start-up.
 *
 * Not a constant in the source: a checked-in digest is a checked-in hash of *something*, and the
 * one thing worse than a decoy is a decoy somebody eventually tries to recover. This one exists
 * only in memory and nobody — including this process — knows its pre-image after the line runs.
 */
private val DECOY_DIGEST: String = PasswordHasher.hash(Tokens.issue())
