package com.tripletriad.server

import com.tripletriad.protocol.AuctionLotRequest
import com.tripletriad.protocol.BidRequest
import com.tripletriad.protocol.ListCardRequest
import com.tripletriad.protocol.Unlocks
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * The auction house over HTTP.
 *
 * ### The lot id is in the body and never in the path
 *
 * `POST /auctions/bid` and not `POST /auctions/{id}/bid`, because [BidRequest] and
 * [AuctionLotRequest] already carry the lot. A path segment as well would be a second place for it
 * to be, a disagreement to resolve, and a validation to write — for nothing, since the request is
 * refused unless the *store* recognises the id anyway. `AuctionLotRequest`'s own KDoc makes the
 * other half of the argument: one request type for withdraw, accept and decline, with the verb in
 * the URL.
 *
 * ### Refusals come back as 200
 *
 * Every write here answers with an `AuctionOutcome`, and a refusal is a field in it rather than a
 * status code. The client's view of a lot is stale by construction — somebody outbid you while you
 * were typing is the *normal* case near the end of a lot — so this is an answer to a reasonable
 * question, and the profile in the same body is the evidence for it. The same shape
 * `ItemEffect.NotUseable` already has.
 *
 * A `500` here means something else: [AuctionStore] answered null, which happens only when the
 * account has no character. That is not reachable through registration and is logged rather than
 * explained.
 */
fun Route.auctionRoutes(
    auctions: AuctionStore,
    accounts: AccountStore,
    // Defaulted so an existing test keeps its call shape; a deployment passes its own. See
    // `Unlocks` for why the threshold travels rather than being compiled into both ends.
    unlocks: Unlocks = Unlocks(),
) {
    route("/auctions") {
        browsingRoutes(auctions, accounts)
        tradingRoutes(auctions, accounts, unlocks)
    }
}

/**
 * Reading the house. Not throttled, and not gated on the unlock level.
 *
 * Looking is what tells a player the auction house exists and what it is worth reaching — a level
 * gate on the *list* would hide the thing the level is supposed to be an incentive for. Writing is
 * gated; reading is a shop window.
 */
private fun Route.browsingRoutes(auctions: AuctionStore, accounts: AccountStore) {
    /** Every open lot, soonest to close first. `?card=` narrows to one card. */
    get("") {
        if (!requireCompatibleClient()) return@get
        val accountId = authenticate(accounts) ?: return@get
        val cardId = call.request.queryParameters["card"]?.toIntOrNull()
        call.respond(HttpStatusCode.OK, auctions.browse(accountId, cardId))
    }

    /**
     * Everything the caller has a stake in, finished lots included.
     *
     * Separate from the browse list rather than a flag on it, because it answers a different
     * question — what happened to my things — and because a lot that vanished the moment it closed
     * would answer nobody. It is where a seller finds a decision waiting for them.
     */
    get("/mine") {
        if (!requireCompatibleClient()) return@get
        val accountId = authenticate(accounts) ?: return@get
        call.respond(HttpStatusCode.OK, auctions.mine(accountId))
    }
}

/**
 * Everything that moves a card or a coin.
 *
 * All five are throttled together and all five go through [authenticateUnlocked]: the auction house
 * is the one place in this game where MGP moves between accounts with nothing checking what came
 * back the other way, which makes it the surface an account farm would reach for first. The level
 * and the confirmed address are what that costs, per account.
 */
private fun Route.tradingRoutes(auctions: AuctionStore, accounts: AccountStore, unlocks: Unlocks) {
    rateLimit(RateLimitName(AUCTION)) {
        /** Opens a lot. The card leaves the collection and the listing fee leaves the purse. */
        post("") {
            val accountId = beginTrade(accounts, unlocks) ?: return@post
            val request = call.receive<ListCardRequest>()
            if (!acceptsOperationId(request)) return@post
            answer(accountId) { auctions.list(accountId, request) }
        }

        /**
         * Bids.
         *
         * The idempotency key is not decoration here and is the reason this endpoint can be
         * tapped twice safely: a player pressing the button again because the first press has not
         * come back yet is placing *one* bid, and without the key it would be two — the second one
         * outbidding the first, at the player's own expense, with both holds taken.
         */
        post("/bid") {
            val accountId = beginTrade(accounts, unlocks) ?: return@post
            val request = call.receive<BidRequest>()
            if (!acceptsOperationId(request)) return@post
            answer(accountId) { auctions.bid(accountId, request) }
        }

        /** Withdraws a lot nobody has bid on. Once money is committed, a lot runs to its end. */
        post("/cancel") {
            val accountId = beginTrade(accounts, unlocks) ?: return@post
            val request = call.receive<AuctionLotRequest>()
            if (!acceptsOperationId(request)) return@post
            answer(accountId) { auctions.withdraw(accountId, request) }
        }

        /** Takes a bid that fell short of the reserve. */
        post("/accept") {
            decide(auctions, accounts, unlocks, accept = true)
        }

        /** Refuses one. The card comes back and the bidder is made whole. */
        post("/decline") {
            decide(auctions, accounts, unlocks, accept = false)
        }
    }
}

/** The two halves of the seller's answer differ by one boolean, so they are written once. */
private suspend fun RoutingContext.decide(
    auctions: AuctionStore,
    accounts: AccountStore,
    unlocks: Unlocks,
    accept: Boolean,
) {
    val accountId = beginTrade(accounts, unlocks) ?: return
    val request = call.receive<AuctionLotRequest>()
    if (!acceptsOperationId(request)) return
    answer(accountId) { auctions.decide(accountId, request, accept) }
}

/**
 * The version gate and the unlock gate, in the order they have to run in.
 *
 * `requireCompatibleClient` **before** `call.receive`, which is the convention every route in this
 * server keeps: a body from a client we are about to refuse is a body we should not be decoding,
 * and a decode failure would answer with the wrong reason.
 */
private suspend fun RoutingContext.beginTrade(accounts: AccountStore, unlocks: Unlocks): Long? {
    if (!requireCompatibleClient()) return null
    return authenticateUnlocked(accounts, unlocks, Feature.AUCTION)
}

/** Writes the store's own JSON through, or reports the one condition that is not an answer. */
private suspend fun RoutingContext.answer(accountId: Long, act: () -> String?) {
    val response = act()
    if (response == null) {
        call.application.environment.log.error("Account {} has no character", accountId)
        return call.respond(HttpStatusCode.InternalServerError, "no character")
    }
    call.respondText(response, ContentType.Application.Json, HttpStatusCode.OK)
}
