// TooManyFunctions counts the route groups this file is made of, which is close to counting a data
// class's properties. The rule is aimed at a file doing too many *things*; this one does one —
// everything about an account and the profile behind it — and every function in it is a `Route`
// extension that exists precisely so that no single one of them is too long to read. Splitting by
// count would put `sessionRoutes` behind a second file for no reason other than the count.
@file:Suppress("TooManyFunctions")

package com.tripletriad.server

import com.tripletriad.data.CampaignEntry
import com.tripletriad.data.CampaignRewards
import com.tripletriad.data.CardValue
import com.tripletriad.data.Inventory
import com.tripletriad.data.ShopCatalog
import com.tripletriad.data.StarterPack
import com.tripletriad.model.GameSave
import com.tripletriad.model.questDayOf
import com.tripletriad.protocol.AccountCode
import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.BagItemRequest
import com.tripletriad.protocol.BuyRequest
import com.tripletriad.protocol.ClaimStarterRequest
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.EnterCampaignRequest
import com.tripletriad.protocol.Idempotent
import com.tripletriad.protocol.ItemUsed
import com.tripletriad.protocol.PasswordReset
import com.tripletriad.protocol.PasswordResetRequest
import com.tripletriad.protocol.SellCardRequest
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.effect
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.random.Random

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
 * ### The intent endpoints
 *
 * `PUT /me/save` still takes the client at its word for most of the profile, and each endpoint
 * added below takes one more thing off that list. The rule they follow: the client states an
 * *intent*, the server runs the **same `:core` function the client used to run**, and answers with
 * the profile it wrote. Using `:core` rather than a server-side reimplementation is what stops the
 * prices, the drop table and the stacking rules from drifting between the two ends.
 *
 * Every one of them is idempotent by an id the client mints — see `Idempotent` in `:core` and
 * `AccountStore.applyOnce`. That is not optional decoration: a request whose answer is lost has an
 * unknown outcome, and a retry that opens a second booster is a worse bug than the cheat the
 * endpoint closes.
 *
 * ### What is deliberately not here
 *
 * - **Refresh tokens.** A session lasts [SESSION_DAYS] days and then the player signs in again.
 *   Rotation buys something real, and it buys it against an attacker who has already taken the
 *   token; there are cheaper things to fix first.
 */
// Six, and the sixth is the auction house. It is here rather than folded into `AccountStore`
// because the dependency only points one way — `AuctionStore` is built on `AccountStore` — and a
// registry that let it point back would make the order the two are constructed in load-bearing.
@Suppress("LongParameterList")
fun Route.accountRoutes(
    store: AccountStore,
    tables: ShopTables,
    codes: CodeChannel,
    clock: () -> Long = System::currentTimeMillis,
    random: () -> Random = { Random.Default },
    // Null in the tests that have no house to unwind, which is most of them. A deployment always
    // passes one: see `deleteAccount`, whose contract this completes.
    auctions: AuctionStore? = null,
) {
    registrationRoutes(store, codes, clock)
    sessionRoutes(store, clock)
    accountSelfRoutes(store, auctions)
    credentialRecoveryRoutes(store, codes, clock)

    profileRoutes(store, tables, random, clock)
}

/**
 * Confirming an address, and getting back in without a password.
 *
 * ### Why two of these four are unauthenticated
 *
 * Because a player who has forgotten their password cannot authenticate — that is the whole
 * premise. So `/accounts/password/forgot` and `/accounts/password/reset` are open, and what stands
 * in for a session is the code: it is sent to an address the account already holds, so answering it
 * proves the same thing a password would.
 *
 * ### Why the forgotten-password endpoint always answers the same way
 *
 * 202 whether or not the account exists, with no detail. The alternative turns the form into a way
 * of asking which usernames are registered — the leak `AccountError.INVALID_CREDENTIALS` closes on
 * the sign-in form, reopened on a form nobody was watching.
 */
private fun Route.credentialRecoveryRoutes(
    store: AccountStore,
    codes: CodeChannel,
    clock: () -> Long,
) {
    rateLimit(RateLimitName(CODES)) {
        emailConfirmationRoutes(store, codes, clock)
        passwordResetRoutes(store, codes, clock)
    }
}

/** Confirming the address on an account you are already signed in to. */
private fun Route.emailConfirmationRoutes(
    store: AccountStore,
    codes: CodeChannel,
    clock: () -> Long,
) {
    route("/me/email") {
        /** Types the code back in. 204: there is nothing to say that is not the state. */
        post("/verify") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticate(store) ?: return@post
            val submitted = call.receive<AccountCode>()
            if (!AccountCode.looksValid(submitted.code)) return@post call.respondBadCode()

            val outcome =
                codes.spend(accountId, CodePurpose.VERIFY_EMAIL, submitted.code, clock())
            if (outcome != CodeOutcome.ACCEPTED) return@post call.respondBadCode()

            store.markVerified(accountId, clock())
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * Sends another one. 202 even when there is nothing to send to, for the same reason the
         * forgotten-password endpoint does: an account with no address is one that predates the
         * requirement, and saying so serves nobody.
         */
        post("/resend") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticate(store) ?: return@post
            val identity = store.identity(accountId)

            if (identity?.email != null && !identity.verified) {
                codes.issue(
                    this,
                    accountId,
                    identity.email,
                    CodePurpose.VERIFY_EMAIL,
                    clock(),
                )
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }
}

/**
 * Getting back in without a password, which is the flow that has no session by definition.
 *
 * ### Why the forgotten-password endpoint always answers the same way
 *
 * 202 whether or not the account exists, with no detail. The alternative turns the form into a way
 * of asking which usernames are registered — the leak `AccountError.INVALID_CREDENTIALS` closes on
 * the sign-in form, reopened on a form nobody was watching.
 */
private fun Route.passwordResetRoutes(store: AccountStore, codes: CodeChannel, clock: () -> Long) {
    route("/accounts/password") {
        /** *I have forgotten it.* Always 202 — see this function's own KDoc. */
        post("/forgot") {
            if (!requireCompatibleClient()) return@post
            val request = call.receive<PasswordResetRequest>()
            val accountId = store.accountIdFor(request.username.trim())
            val email = accountId?.let { store.identity(it)?.email }

            if (accountId != null && email != null) {
                codes.issue(this, accountId, email, CodePurpose.RESET_PASSWORD, clock())
            }
            call.respond(HttpStatusCode.Accepted)
        }

        /** The code, and the new password. Every session on the account ends with it. */
        post("/reset") {
            if (!requireCompatibleClient()) return@post
            val request = call.receive<PasswordReset>()
            if (!request.looksValid() || !PasswordHasher.isUsable(request.password)) {
                return@post call.respondMalformed()
            }

            val accountId = store.accountIdFor(request.username.trim())
                ?: return@post call.respondBadCode()
            val outcome =
                codes.spend(accountId, CodePurpose.RESET_PASSWORD, request.code, clock())
            if (outcome != CodeOutcome.ACCEPTED) return@post call.respondBadCode()

            store.replacePassword(accountId, PasswordHasher.hash(request.password))
            // Confirmed by the same stroke. Answering a code sent to the address proves the player
            // holds it, which is exactly what confirmation asks — and an account that has just
            // proved it should not then be nagged to prove it again.
            store.markVerified(accountId, clock())
            call.application.environment.log.info("Password reset for account {}", accountId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * One answer for every way a code can fail — see [CodeOutcome] for why they are not distinguished.
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondBadCode() = respond(
    HttpStatusCode.BadRequest,
    AccountFailure(AccountError.INVALID_CODE, "that code is not valid"),
)

/**
 * Creating an account. One route, and it is the one that makes every other route possible.
 *
 * Split out of [accountRoutes] because that function had grown past what fits on a screen — the
 * password change and "sign out everywhere" both landed in it — and because these three functions
 * are three different subjects: becoming a player, holding a session, and looking after the
 * account behind them.
 */
private fun Route.registrationRoutes(store: AccountStore, codes: CodeChannel, clock: () -> Long) {
    route("/accounts") {
        /**
         * Creates an account, its character, and a session — one round trip, signed in.
         *
         * **201 with a session**, not 201 with "now go and sign in". The second is a round trip
         * that exists only to prove the client can do what it just did, and every client would
         * immediately make it.
         *
         * Throttled by address, in a bucket of its **own** rather than sign-in's — see [REGISTER]
         * for why sharing them turned an honest burst of installs into a sign-in lockout.
         */
        rateLimit(RateLimitName(REGISTER)) {
            post {
                if (!requireCompatibleClient()) return@post
                val credentials = call.receive<Credentials>()
                // Two checks and not one. `looksValid` counts characters, which is the rule the
                // form states; `isUsable` counts UTF-8 bytes, which is the rule bcrypt enforces by
                // throwing. A passphrase of emoji satisfies the first and fails the second, and
                // without this it reached `PasswordHasher.hash` and became a 500 — see
                // `PasswordHasher.MAX_PASSWORD_BYTES`.
                if (!credentials.looksValid() || !PasswordHasher.isUsable(credentials.password)) {
                    return@post call.respondMalformed()
                }
                // Separately from the two above, so the refusal names the field that is wrong. A
                // player told "malformed credentials" when their password was fine and their
                // address had a typo has been told nothing.
                val email = credentials.email?.trim()
                if (email == null || !Credentials.looksLikeEmail(email)) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        AccountFailure(
                            AccountError.MALFORMED_EMAIL,
                            "an email address is required to create an account",
                        ),
                    )
                }

                val username = credentials.username.trim()
                val accountId = store.register(
                    username = username,
                    passwordHash = PasswordHasher.hash(credentials.password),
                    // The character is created from the account's own name, so the profile a player
                    // signs into is already theirs rather than `Kuplu Kopo` waiting to be renamed.
                    save = GameSave.new(username = username, createdAt = clock()),
                    email = email,
                )

                if (accountId == null) return@post call.respondCollision(store, username, email)

                call.application.environment.log.info("Registered account {}", accountId)
                codes.issue(this, accountId, email, CodePurpose.VERIFY_EMAIL, clock())
                call.respond(HttpStatusCode.Created, store.newSession(accountId, clock()))
            }
        }
    }
}

/**
 * Which of the two unique columns collided, asked only after the insert has already failed.
 *
 * Two queries on a path nobody takes twice, rather than reading a constraint name out of the
 * driver's error message — which would work today and break on a Postgres that words it
 * differently. The username is checked first because it is the likelier of the two and because a
 * player who has taken both has to be told about one of them anyway.
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondCollision(
    store: AccountStore,
    username: String,
    email: String,
) {
    val failure = when {
        store.usernameTaken(username) ->
            AccountFailure(AccountError.USERNAME_TAKEN, "that name is already taken")

        store.emailTaken(email) ->
            AccountFailure(AccountError.EMAIL_TAKEN, "that address already has an account")

        // Neither, which means the row that collided was deleted between the insert and this
        // check. Vanishingly rare and not worth a retry loop; the honest answer is the generic one.
        else -> AccountFailure(AccountError.USERNAME_TAKEN, "that name is already taken")
    }
    respond(HttpStatusCode.Conflict, failure)
}

/** Holding a session: signing in, signing out, and signing out everywhere. */
private fun Route.sessionRoutes(store: AccountStore, clock: () -> Long) {
    route("/sessions") {
        // The one endpoint where guessing *is* the attack. bcrypt's cost makes each guess expensive
        // for the server as well as the attacker, so the limit is protecting this host as much as
        // the account — a hundred parallel guesses is a hundred cores of bcrypt.
        rateLimit(RateLimitName(SIGN_IN)) {
            post { signIn(store, clock) }
        }

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

        /**
         * Signs out **everywhere**, this device included.
         *
         * ### Why a player needs this and had no way to ask for it
         *
         * A token lasts [SESSION_DAYS] days and is stored on the device in the clear, on the
         * argument that what a stolen one can do is bounded. That argument holds, and it still left
         * the owner with nothing to do about it: signing out ended one session, there was no way to
         * change a password, and the only thing that ended a thief's access was deleting the
         * account. One irreversible action as the answer to a recoverable problem.
         *
         * Authenticated rather than password-checked, deliberately. This is the *safe* direction —
         * it takes access away and gives none — so it should be as easy to reach as possible for
         * somebody who is worried and not certain. Account deletion asks for the password because
         * it cannot be undone; this can, by signing in.
         */
        delete("/all") {
            if (!requireCompatibleClient()) return@delete
            val accountId = authenticate(store) ?: return@delete

            val ended = store.closeSessions(accountId)
            call.application.environment.log.info(
                "Ended all {} sessions for account {} at its own request",
                ended,
                accountId,
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Looking after the account itself: changing its password and destroying it.
 *
 * Both verify the password even though the caller already holds a token, and both are therefore in
 * the [SIGN_IN] bucket. Each says why in its own KDoc; what they have in common is that a token
 * proves a device, and neither of these is a thing a device should be able to do alone.
 *
 * The deletion has a function to itself, [deleteSelfRoute], because it grew a second subject — what
 * becomes of the auction lots the account is standing in the middle of — and the two read better
 * apart than they did with the password check and the unwinding in one block.
 */
private fun Route.accountSelfRoutes(store: AccountStore, auctions: AuctionStore?) {
    deleteSelfRoute(store, auctions)

    /**
     * Changes the password, and ends every **other** session.
     *
     * ### Why the revocation is part of it rather than a second call
     *
     * Because a password change is what somebody does when they think another person has their
     * credentials, and leaving that person's thirty-day token alive would answer the wrong half of
     * the problem. Every service worth copying does both, and doing them separately means the
     * player has to know to do the second.
     *
     * The caller's own session is spared. Signing somebody out of the device they are holding, as a
     * consequence of an action they just took on it, reads as a failure rather than as security.
     *
     * ### Why the old password, when the caller already holds a token
     *
     * The same reason account deletion asks: a token is not proof that the *player* asked. It is
     * the argument `DELETE /accounts/me` makes at length, and it applies more sharply here, since
     * changing a password from an unlocked phone would lock the owner out of their own account.
     *
     * Throttled by [SIGN_IN], because a request that verifies a password is a place to guess one.
     *
     * ### The request shape lives here and not in `:core`
     *
     * Nothing on the client calls this yet, so putting the type in the shared protocol would be
     * publishing a contract before either end has agreed it. `UpgradeRequired` in `VersionGate` is
     * the precedent. It belongs in `:core` the moment a client screen exists, and this comment is
     * the reminder to move it rather than to write a second one.
     */

    rateLimit(RateLimitName(SIGN_IN)) {
        post("/accounts/me/password") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticate(store) ?: return@post
            val change = call.receive<PasswordChange>()

            val digest = store.passwordHashFor(accountId)
            if (digest == null || !PasswordHasher.verify(change.password, digest)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    AccountFailure(
                        AccountError.INVALID_CREDENTIALS,
                        "that password does not match this account",
                    ),
                )
            }

            // The same two checks registration makes, and for the same reasons: a length the form
            // states, and a byte count bcrypt enforces by throwing. See `PasswordHasher.isUsable`.
            if (change.newPassword.length < Credentials.PASSWORD_LENGTH.first ||
                !PasswordHasher.isUsable(change.newPassword)
            ) {
                return@post call.respondMalformed()
            }

            store.updatePasswordHash(accountId, PasswordHasher.hash(change.newPassword))
            val ended = store.closeSessions(
                accountId,
                except = bearerToken()?.let(Tokens::fingerprint),
            )

            call.application.environment.log.info(
                "Account {} changed its password; {} other session(s) ended",
                accountId,
                ended,
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Deletes the account and everything belonging to it. **Irreversible.**
 *
 * ### Why it asks for the password when the caller already holds a token
 *
 * Because a token is not proof that the *player* asked. It is stored on the device in the clear
 * — `SessionStore` argues for that, and the argument holds only because what a stolen token can
 * do is bounded. Letting it destroy an account would put "somebody picked up an unlocked phone"
 * and "the owner asked to be forgotten" behind the same gesture, and only one of those is
 * recoverable. Re-typing the password is the smallest thing that distinguishes them, and it is
 * what every service worth copying asks for here.
 *
 * Throttled by [SIGN_IN] rather than the intent bucket, and for that endpoint's reason: a
 * request that verifies a password is a place to guess one.
 *
 * ### Why a wrong password is `401` and a missing account is `204`
 *
 * The first is a refusal to act. The second is the action having already happened — a client
 * that lost the answer and asked again has got what it wanted, and telling it "no such account"
 * would be reporting success as failure.
 *
 * ### Why [auctions] is nullable
 *
 * Most tests have no auction house to unwind and would otherwise have to build one to delete an
 * account. A deployment always passes it — `Application.module` does — and the cost of the null is
 * bounded: it means "there are no lots", which is true in exactly the configuration that passes it.
 */
private fun Route.deleteSelfRoute(store: AccountStore, auctions: AuctionStore?) {
    rateLimit(RateLimitName(SIGN_IN)) {
        delete("/accounts/me") {
            if (!requireCompatibleClient()) return@delete
            val accountId = authenticate(store) ?: return@delete
            val credentials = call.receive<Credentials>()

            val digest = store.passwordHashFor(accountId)
            if (digest == null || !PasswordHasher.verify(credentials.password, digest)) {
                return@delete call.respond(
                    HttpStatusCode.Unauthorized,
                    AccountFailure(
                        AccountError.INVALID_CREDENTIALS,
                        "that password does not match this account",
                    ),
                )
            }

            // The lots go first, on the delete's own connection. A player who leaves mid-auction
            // leaves other people's money and other people's cards behind them, and those have to
            // be settled rather than cascaded away — `AuctionStore.closeOutOn` says who gets what.
            var settled = 0
            store.deleteAccount(accountId) { db ->
                settled = auctions?.closeOutOn(db, accountId) ?: 0
            }
            // At warn: this is the one action here that cannot be undone, and an operator asked
            // "what happened to this account" should find the answer without turning on debug.
            // The lot count rides along because it is the part with somebody else in it.
            call.application.environment.log.warn(
                "Deleted account {} at its own request, settling {} auction lots",
                accountId,
                settled,
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * The body of a password change: the one in use, and the one to replace it with.
 *
 * Both named rather than reusing `Credentials`, which carries a username this request has no use
 * for — the account is the one the token names, and accepting a username here would invite the
 * question of what happens when it disagrees.
 */
@Serializable
private data class PasswordChange(val password: String, val newPassword: String)

/**
 * The profile itself: reading it, storing one the client changed, and the intent endpoints.
 *
 * Split out of [accountRoutes] because they are a different subject — that one is about *who*
 * you are, and these are about *what you have*. The split is the same one `PvpRoutes` makes
 * between the lobby and a live match, and it happened here for the same reason: the file grew a
 * bag endpoint and the function that held everything stopped fitting on a screen.
 */
private fun Route.profileRoutes(
    store: AccountStore,
    tables: ShopTables,
    random: () -> Random,
    clock: () -> Long,
) {
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
     *
     * ### The fields that are the server's, and are taken back
     *
     * "Nothing a match established" stopped being free the moment the profile started carrying
     * **daily quests**: a completed quest is paid on the strength of matches this server replayed,
     * so a client asserting one would be paying itself. [GameSave.withServerOwnedFrom] names that
     * set — once, in `:core`, where both ends can read it — and every such field is taken from the
     * stored document rather than from the request. Everything else is still believed.
     *
     * Silently, and not as a refusal: an honest client sends the whole profile back including the
     * quests it was last told about, so rejecting the request would break the ordinary case to
     * punish nobody.
     */
    // Under [INTENT] like the endpoints below it. This one takes a whole `GameSave` and writes it
    // to a JSONB column, so an unthrottled caller is an unthrottled writer of the largest body this
    // API accepts — and it was the only write on `/me` with no bucket at all.
    rateLimit(RateLimitName(INTENT)) {
        put("/me/save") {
            if (!requireCompatibleClient()) return@put
            val accountId = authenticate(store) ?: return@put

            val incoming = call.receive<GameSave>()
            // Read, merge and write in **one locked transaction** — see `AccountStore.mutate`. It
            // used to be three separate ones, so a match credited between the read and the write
            // was thrown away by this endpoint: the client's copy of the server-owned fields was
            // stale by exactly the change that had just landed.
            // `Outcome(…, Unit)` because `mutate` now answers with whatever the change reports as
            // well as the profile — PvP settlement needs the payout back out of the lock. This one
            // has nothing to report; the change is the answer.
            val written = store.mutate(accountId) { stored ->
                Outcome(incoming.withServerOwnedFrom(stored), Unit)
            }
            if (written == null) {
                call.application.environment.log.error("Account {} has no character", accountId)
                return@put call.respond(HttpStatusCode.InternalServerError, "no character")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    intentRoutes(store, tables, random, clock)
}

/**
 * The intent endpoints: the six things a player does that move something of value.
 *
 * Separate from [profileRoutes] because they are the opposite kind of thing. That one reads
 * the profile and stores one the client changed; these each run **one `:core` function** against
 * the stored profile and answer with what was written. Nothing here trusts an arriving number.
 *
 * All of them share one rate-limit bucket and one idempotency guard, which is why they are one
 * function: a seventh added outside this block would silently get neither.
 */
private fun Route.intentRoutes(
    store: AccountStore,
    tables: ShopTables,
    random: () -> Random,
    clock: () -> Long,
) {
    /**
     * Uses something from the bag — and, for a booster, **rolls it here**.
     *
     * ### The roll had to move, not be checked
     *
     * `Inventory.use` takes a generator, and until now it was the client's. A modified client could
     * open a pack, dislike it, and open it again from the same save until something rare fell out.
     * No server-side audit catches that: the pack was really owned, the cards really are in its
     * pool, and the arithmetic really adds up. The only fix is for the dice to belong to the party
     * the outcome is *not* worth anything to — the same argument the refereed match makes.
     *
     * So the generator is this server's, and everything else is unchanged: the very same
     * `Inventory.use` from `:core` decides what comes out, with the same pool and the same
     * guaranteed slot.
     *
     * ### What is checked, and what does not need to be
     *
     * Possession is checked by `Inventory.use` itself, which answers `NotUseable` for an item the
     * bag does not hold — so a fabricated item yields nothing and an unchanged profile. The
     * `stack` on the arriving item needs no check at all: `Inventory.remove` takes its count as a
     * separate argument and `stacksWith` compares with the stack normalised to one, so the field
     * says *which* item and can never say how many.
     *
     * A refused use is a `200` carrying `ItemEffect.NotUseable` rather than a `4xx`, because the
     * profile in the same response is the answer to why — the client's bag was stale, and here is
     * the real one.
     */
    rateLimit(RateLimitName(INTENT)) {
        post("/me/bag/use") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticate(store) ?: return@post
            val request = call.receive<BagItemRequest>()
            if (!acceptsOperationId(request)) return@post

            val response = store.applyOnce(
                accountId = accountId,
                operationId = request.operationId,
                perform = { save ->
                    val used = Inventory.use(save, request.item, random())
                    Outcome(used.save, used.effect())
                },
                describe = { player, effect -> ApiJson.encodeToString(ItemUsed(player, effect)) },
            )

            if (response == null) {
                call.application.environment.log.error("Account {} has no character", accountId)
                return@post call.respond(HttpStatusCode.InternalServerError, "no character")
            }
            call.respondText(response, ContentType.Application.Json, HttpStatusCode.OK)
        }

        /**
         * Sells a bag item at what the **card table** says it is worth.
         *
         * `Inventory.sell` refuses anything not sellable and anything the bag does not hold, in
         * both cases by returning the profile unchanged — so a fabricated item is worth nothing
         * rather than being an error. The price is never on the wire: `Inventory.priceOf` reads it
         * off the catalogue, which is what stopped an FFVIII common being worth more than an FFXIV
         * legend when the price was `cardId * 4`.
         */
        post("/me/bag/sell") {
            if (!requireCompatibleClient()) return@post
            val request = call.receive<BagItemRequest>()
            respondWithProfile(store, request) { save ->
                Inventory.sell(save, request.item, tables.cards.byId)
            }
        }

        /**
         * Sells **every one** of a bag item, at the same price each.
         *
         * ### Why this is its own route rather than a count on `/me/bag/sell`
         *
         * Because `stack` on this wire says *which*, never *how many* — `BagRoutesTest` pins that
         * as a decision rather than an accident, and `Inventory.remove` takes its count separately
         * for the same reason. A `count` field would be the first quantity a client got to name,
         * and the first thing to validate against the bag it claims to be emptying.
         *
         * Here the client says "all of these" and the **server** counts them. There is nothing to
         * validate because there is no number on the wire: `Inventory.count` reads the stored
         * profile, and a bag holding none of the item sells nothing for nothing.
         */
        post("/me/bag/sell-all") {
            if (!requireCompatibleClient()) return@post
            val request = call.receive<BagItemRequest>()
            respondWithProfile(store, request) { save ->
                Inventory.sell(
                    save,
                    request.item,
                    tables.cards.byId,
                    count = Inventory.count(save, request.item).coerceAtLeast(1),
                )
            }
        }

        /** Throws a bag item away. Nothing is paid, and an item nobody holds is already gone. */
        post("/me/bag/discard") {
            if (!requireCompatibleClient()) return@post
            val request = call.receive<BagItemRequest>()
            respondWithProfile(store, request) { save -> Inventory.remove(save, request.item) }
        }

        // Inside the block, deliberately: this is what makes the shop share the bucket and the
        // guard rather than quietly having neither.
        shopIntents(store, tables, random, clock)
    }
}

/**
 * Spending money: the shop, the resale counter, and a ladder's entry fee.
 *
 * Split from the bag intents next door only because the two together outgrew what one function
 * should hold. They belong to the same bucket and the same guard — [intentRoutes] is where both
 * are applied, and calling this from inside that block is what keeps that true.
 *
 * What they have in common is the thing worth stating: **no price arrives from the client.**
 * Each one names what it wants and the server looks up what that costs.
 */
private fun Route.shopIntents(
    store: AccountStore,
    tables: ShopTables,
    random: () -> Random,
    clock: () -> Long,
) {
    /**
     * Buys from the shop, at the server's price.
     *
     * The offer is looked up in this server's own `ShopCatalog` rather than taken from the
     * request — see [BuyRequest]. An item that is not on that format's shelf is not for sale,
     * and `ShopCatalog.buy` returns the profile unchanged when the purse cannot cover it, so
     * both refusals are the same quiet answer: here is your profile, nothing happened.
     */
    post("/me/shop/buy") {
        if (!requireCompatibleClient()) return@post
        val request = call.receive<BuyRequest>()
        val offer = tables.formats[request.formatId]?.let { format ->
            ShopCatalog.offers(format, tables.cards.byId)
                .firstOrNull { on -> on.item.withStack(1) == request.item.withStack(1) }
        }

        respondWithProfile(store, request) { save ->
            offer?.let { ShopCatalog.buy(save, it) } ?: save
        }
    }

    /**
     * Sells a card out of the collection.
     *
     * Both halves in one step, deliberately: the card leaves and the MGP arrives, or neither
     * happens. Selling a card the profile does not hold changes nothing — `withoutCard` on an
     * absent id is a no-op, and paying for it would be paying for nothing.
     */
    post("/me/cards/sell") {
        if (!requireCompatibleClient()) return@post
        val request = call.receive<SellCardRequest>()
        respondWithProfile(store, request) { save ->
            if (!save.ownsCard(request.cardId)) {
                save
            } else {
                save.withoutCard(request.cardId)
                    .withMgp(CardValue.resaleOf(request.cardId, tables.cards.byId))
            }
        }
    }

    /**
     * Grants the box a profile is owed, and nothing when it is owed nothing.
     *
     * `StarterPack.isOwedBy` is the whole condition and it is deliberately narrow: a profile that
     * can still field a deck gets its own profile back. So calling this repeatedly is harmless
     * quite apart from the operation id — the second call finds nothing owed.
     *
     * This is the endpoint that let `cards` join the server-owned list. See [ClaimStarterRequest].
     *
     * ### The choice arrives here, and it did not used to
     *
     * `starterId` names one of **this** server's starters. A character created by registering owns
     * nothing at all — `GameSave.new` deals no cards — so this is the call that makes it playable,
     * and the box it opens has to be the one the player picked. It was not: the id had nowhere to
     * travel, this route took `catalog.starters.first()`, and a player who chose FFVIII walked into
     * their first match holding FFXIV cards. An id this server does not know falls back to the
     * offer, which is also what the shop's repair sends — it names no box because it is not a
     * choice.
     *
     * The four unauthored cards are drawn **here**, from this server's generator, for the reason
     * `/me/bag/use` rolls a booster here: a draw worth anything to the client must not be the
     * client's.
     */
    post("/me/starter") {
        if (!requireCompatibleClient()) return@post
        val request = call.receive<ClaimStarterRequest>()
        val chosen = request.starterId?.let { tables.starters[it] }
        respondWithProfile(store, request) { save ->
            StarterPack.grantedTo(save, tables.starters, tables.cards.byId, random(), chosen)
        }
    }

    /**
     * Pays to enter a campaign ladder, and opens the run.
     *
     * The fee comes from this server's campaign catalogue, so a client cannot enter for less
     * than the ladder costs — nor for nothing, which is what happened when the deduction lived
     * on the client and the client could simply not apply it.
     *
     * ### Five ways in are refused, and all five answer the same way
     *
     * An unknown ladder, one still gated behind an achievement, a run already open, today's entry
     * already spent, and a purse that does not cover the fee. Each returns the profile **unchanged
     * and uncharged**, which is this file's convention for a refusal and is more use to a client
     * than a status code: it asked for something, and here is the state it asked against.
     *
     * `CampaignRewards.enter` decides all five, in `:core`, so the ladder's rules are the same
     * arithmetic the client shows the player and are not stated twice.
     *
     * ### The purse is checked there, and nothing else was going to
     *
     * `GameSave.withMgp` **floors at zero**. So a player holding 100 who entered a 500 ladder would
     * pay 100 and play, which makes being broke the cheapest way in. The client hid the button, and
     * a hidden button is not a rule.
     *
     * ### The day's entry is stamped here, at entry
     *
     * Not when the run resolves. A first-round defeat is eliminating, so a limit applied at
     * settlement would only ever bite the players who won — see `GameSave.enteringCampaign`.
     */
    post("/me/campaign/enter") {
        if (!requireCompatibleClient()) return@post
        val request = call.receive<EnterCampaignRequest>()
        val campaign = tables.campaigns.byKey(request.campaignKey)
        val now = clock()

        respondWithProfile(store, request) { save ->
            val entry = CampaignRewards.enter(save, campaign, questDayOf(now), now)
            (entry as? CampaignEntry.Entered)?.save ?: save
        }
    }
}

/**
 * Applies [perform] once and answers with the profile it wrote.
 *
 * The shape almost every intent has: a pure `GameSave -> GameSave` from `:core`, run against the
 * stored profile, answered with what was stored. `POST /me/bag/use` is the one that needs more,
 * because a pack has contents to report as well as a profile to return.
 *
 * **A refusal is not an error here.** Every core function below answers "no" by returning the
 * profile unchanged — an unaffordable purchase, an item the bag does not hold, a card that is not
 * owned. So the response is a `200` carrying the real profile, which tells the client what happened
 * more usefully than a status code would: it asked for something, and here is the state it asked
 * against.
 */
private suspend fun RoutingContext.respondWithProfile(
    store: AccountStore,
    request: Idempotent,
    perform: (GameSave) -> GameSave,
) {
    val accountId = authenticate(store) ?: return
    if (!acceptsOperationId(request)) return
    val response = store.applyOnce(
        accountId = accountId,
        operationId = request.operationId,
        perform = { save -> Outcome(perform(save), Unit) },
        describe = { player, _ -> ApiJson.encodeToString(player) },
    )

    if (response == null) {
        call.application.environment.log.error("Account {} has no character", accountId)
        return call.respond(HttpStatusCode.InternalServerError, "no character")
    }
    call.respondText(response, ContentType.Application.Json, HttpStatusCode.OK)
}

/**
 * Whether the client's operation id is one this server will store, answering 400 if not.
 *
 * ### Why a client-minted key needs a bound at all
 *
 * `applied_operations.operation_id` is unconstrained `TEXT` inside the table's primary key, and the
 * caller writes it. Two things follow. A btree index entry has a hard maximum of a couple of
 * kilobytes, so an id past it fails the insert — surfacing as a `500` from a request that is really
 * the client's mistake, which is precisely the confusion `StatusPages` sorts out for a body that
 * will not parse. And an unbounded key is unbounded storage, one row per call.
 *
 * [MAX_OPERATION_ID] is far past any id a client has a reason to mint — a UUID is 36 characters —
 * and far below the index limit, so the refusal only ever meets something that was never going to
 * work.
 *
 * Internal rather than private because the auction house applies the same bound for the same
 * reason: the rule is about the column the id is stored in, and both write to it.
 *
 * The shape is `ErrorResponse`, the one content negotiation already answers a malformed body with,
 * rather than an `AccountFailure`: this is not a statement about an account, and `AccountError` has
 * no member that would be true of it.
 */
internal suspend fun RoutingContext.acceptsOperationId(request: Idempotent): Boolean {
    if (request.operationId.length in 1..MAX_OPERATION_ID) return true

    call.application.environment.log.info(
        "Refused an operation id of {} characters",
        request.operationId.length,
    )
    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "malformed_request"))
    return false
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
            "characters and a password of at least ${Credentials.PASSWORD_LENGTH.first} " +
            // The byte limit is named as well as the character one because they are different
            // limits and a player can satisfy either while failing the other. Saying only
            // "at least 8" to somebody whose emoji passphrase was refused explains nothing.
            "characters, and at most ${PasswordHasher.MAX_PASSWORD_BYTES} bytes once encoded",
    ),
)

/**
 * The longest operation id this server will store. Generous: a UUID is 36 characters.
 *
 * Bounded rather than exact because the id is the client's to choose and its shape is not this
 * server's business — only that it fits in an index and in a table. See [acceptsOperationId].
 */
internal const val MAX_OPERATION_ID = 128

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
