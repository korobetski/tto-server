package com.tripletriad.server

import com.tripletriad.data.CardValue
import com.tripletriad.data.Inventory
import com.tripletriad.data.ShopCatalog
import com.tripletriad.data.StarterPack
import com.tripletriad.model.GameSave
import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.BagItemRequest
import com.tripletriad.protocol.BuyRequest
import com.tripletriad.protocol.ClaimStarterRequest
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.EnterCampaignRequest
import com.tripletriad.protocol.Idempotent
import com.tripletriad.protocol.ItemUsed
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
 * - **Password reset and email.** Both need a channel to send to, and there is none. An account is
 *   currently a username, a password and a character; adding recovery is adding a second system.
 *   This is the gap that remains — rate limiting is no longer one, see `installRateLimits`.
 * - **Refresh tokens.** A session lasts [SESSION_DAYS] days and then the player signs in again.
 *   Rotation buys something real, and it buys it against an attacker who has already taken the
 *   token; there are cheaper things to fix first.
 */
fun Route.accountRoutes(
    store: AccountStore,
    tables: ShopTables,
    clock: () -> Long = System::currentTimeMillis,
    random: () -> Random = { Random.Default },
) {
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
    }

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
     */
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

            store.deleteAccount(accountId)
            // At warn: this is the one action here that cannot be undone, and an operator asked
            // "what happened to this account" should find the answer without turning on debug.
            call.application.environment.log.warn(
                "Deleted account {} at its own request",
                accountId,
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }

    profileRoutes(store, tables, random)
}

/**
 * The profile itself: reading it, storing one the client changed, and the intent endpoints.
 *
 * Split out of [accountRoutes] because they are a different subject — that one is about *who*
 * you are, and these are about *what you have*. The split is the same one `PvpRoutes` makes
 * between the lobby and a live match, and it happened here for the same reason: the file grew a
 * bag endpoint and the function that held everything stopped fitting on a screen.
 */
private fun Route.profileRoutes(store: AccountStore, tables: ShopTables, random: () -> Random) {
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
    put("/me/save") {
        if (!requireCompatibleClient()) return@put
        val accountId = authenticate(store) ?: return@put

        val incoming = call.receive<GameSave>()
        val stored = store.saveFor(accountId) ?: run {
            call.application.environment.log.error("Account {} has no character", accountId)
            return@put call.respond(HttpStatusCode.InternalServerError, "no character")
        }
        val save = incoming.withServerOwnedFrom(stored)
        if (!store.replaceSave(accountId, save)) {
            call.application.environment.log.error("Account {} has no character", accountId)
            return@put call.respond(HttpStatusCode.InternalServerError, "no character")
        }
        call.respond(HttpStatusCode.NoContent)
    }

    intentRoutes(store, tables, random)
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
private fun Route.intentRoutes(store: AccountStore, tables: ShopTables, random: () -> Random) {
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

        /** Throws a bag item away. Nothing is paid, and an item nobody holds is already gone. */
        post("/me/bag/discard") {
            if (!requireCompatibleClient()) return@post
            val request = call.receive<BagItemRequest>()
            respondWithProfile(store, request) { save -> Inventory.remove(save, request.item) }
        }

        // Inside the block, deliberately: this is what makes the shop share the bucket and the
        // guard rather than quietly having neither.
        shopIntents(store, tables)
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
private fun Route.shopIntents(store: AccountStore, tables: ShopTables) {
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
     */
    post("/me/starter") {
        if (!requireCompatibleClient()) return@post
        val request = call.receive<ClaimStarterRequest>()
        respondWithProfile(store, request) { save ->
            StarterPack.grantedTo(save, tables.starters)
        }
    }

    /**
     * Pays to enter a campaign ladder.
     *
     * The fee comes from this server's campaign catalogue, so a client cannot enter for less
     * than the ladder costs — nor for nothing, which is what happened when the deduction lived
     * on the client and the client could simply not apply it.
     *
     * A ladder that does not exist charges nothing rather than refusing: the profile comes back
     * unchanged and the client learns its catalogue disagrees with the server's, which is a
     * version problem and not a payment one.
     *
     * ### The purse is checked here, and nothing else was going to
     *
     * `GameSave.withMgp` **floors at zero**. So a player holding 100 who entered a 500 ladder would
     * pay 100 and play, which makes being broke the cheapest way in. The client hid the button, and
     * a hidden button is not a rule.
     *
     * `ShopCatalog.buy` makes this check itself and selling a card cannot overdraw, so this is the
     * one intent that needed it written out — exactly the sort of thing that stays invisible until
     * the arithmetic moves to the side that has to mean it.
     */
    post("/me/campaign/enter") {
        if (!requireCompatibleClient()) return@post
        val request = call.receive<EnterCampaignRequest>()
        val fee = tables.campaigns.byKey(request.campaignKey)?.fee ?: 0
        respondWithProfile(store, request) { save ->
            if (save.mgp < fee) save else save.withMgp(-fee)
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
