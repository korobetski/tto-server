package com.tripletriad.server

import com.tripletriad.data.AuctionRules
import com.tripletriad.model.CardItem
import com.tripletriad.model.CardOrigin
import com.tripletriad.model.XpTable
import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.AuctionLotRequest
import com.tripletriad.protocol.AuctionOutcome
import com.tripletriad.protocol.AuctionPage
import com.tripletriad.protocol.AuctionRefusal
import com.tripletriad.protocol.AuctionStatus
import com.tripletriad.protocol.BidRequest
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.ListCardRequest
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.Unlocks
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The auction house **over HTTP**, which is a different subject from the one [AuctionFlowTest] has.
 *
 * ### Why both files exist
 *
 * That one drives [AuctionStore] directly and owns its clock, so it can move a lot past its own
 * deadline and assert who ended up with the card. Nothing here can do that — the store the module
 * builds reads `System.currentTimeMillis` — and nothing there touches a route. So the split is by
 * what each can prove: **money and cards there, gates and shapes here.** The four things a route
 * layer can get wrong are the four things below.
 *
 * ### What this file measures
 *
 * 1. The two gates, and which side of them each endpoint sits on — looking is free, trading is not.
 * 2. That the version check runs **before** the body is read, which is the convention every route
 *    in this server keeps and the one a refactor silently breaks.
 * 3. That a refusal arrives as a `200` carrying a reason, not as a status code.
 * 4. That the idempotency key survives the round trip, so a double-tapped button is one bid.
 *
 * **Not verified here:** anything that needs a clock. A lot ending, the seller's decision being
 * accepted, the sweeper — those are `AuctionFlowTest`'s, and the decision endpoint appears below
 * only in its refusing form.
 */
class AuctionRoutesTest {

    // ------------------------------------------------------------------ the gates

    /**
     * A player below the level may look at the house and may not trade in it.
     *
     * The asymmetry is the point rather than an oversight: browsing is what tells a player the
     * auction house is worth reaching, so gating it would hide the thing the level is an incentive
     * for. `AuctionRoutes` argues this at the two functions; this is where it is true or not.
     */
    @Test
    fun aPlayerBelowTheLevelCanLookButCannotList() = server {
        val name = Postgres.freshAccount("auction-window")
        val session = register(name)
        // Confirmed, so the only thing left to refuse on is the level — the same separation
        // `PvpUnlockTest` makes, and for the same reason: two doors, measured one at a time.
        unlockForPvp(name, level = 1)

        assertEquals(HttpStatusCode.OK, browse(session.token).status)

        val refused = post("/auctions", session.token, listing("never-sent"))
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        val failure = json.decodeFromString<AccountFailure>(refused.bodyAsText())
        assertEquals(AccountError.NOT_UNLOCKED, failure.error)
        assertTrue(
            Unlocks.DEFAULT_AUCTION.toString() in failure.detail,
            "the refusal did not say what level unlocks it: ${failure.detail}",
        )
    }

    /**
     * And an unconfirmed address is refused at the same door, whatever level it has reached.
     *
     * The auction house is where MGP moves between accounts with nothing checking what came back
     * the other way, which makes it the surface an account farm reaches for first. A confirmed
     * address is what that costs, per account — so it is checked here and not only in front of
     * refereed play.
     */
    @Test
    fun anUnconfirmedAccountCannotTradeEither() = server {
        val session = register(Postgres.freshAccount("auction-unconfirmed"))

        val refused = post("/auctions", session.token, listing("never-sent-either"))

        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertEquals(
            AccountError.EMAIL_UNVERIFIED,
            json.decodeFromString<AccountFailure>(refused.bodyAsText()).error,
        )
    }

    /**
     * A stale client is refused before its body is read.
     *
     * The body here is not a [BidRequest] at all, so a route that received first would answer
     * `400` — a client told its request was malformed when the actual answer is "update". That
     * ordering is a convention rather than something the type system holds, which is why it is
     * pinned with a body that cannot be parsed.
     */
    @Test
    fun theVersionGateRunsBeforeTheBodyIsRead() = server {
        val session = register(Postgres.freshAccount("auction-stale"))

        val refused = client.post("/auctions/bid") {
            contentType(ContentType.Application.Json)
            header(VERSION_HEADER, "1.0.0")
            bearer(session.token)
            setBody("""{"this":"is not a bid"}""")
        }

        assertEquals(HttpStatusCode.UpgradeRequired, refused.status, refused.bodyAsText())
    }

    // ------------------------------------------------------------------ the shapes

    /** A lot opened over HTTP is a lot everybody else can see. */
    @Test
    fun listingACardPutsItInTheBrowseList() = server {
        val seller = trader("auction-seller", holding = 1)

        val opened = outcome(post("/auctions", seller.token, listing("http-list")))
        val lot = requireNotNull(opened.lot) { "the listing was refused: ${opened.refusal}" }

        assertEquals(AuctionStatus.OPEN, lot.status)
        assertEquals(CARD, lot.cardId)
        // Somebody else's view of it, which is the half `POST` cannot vouch for.
        val onlooker = register(Postgres.freshAccount("auction-onlooker"))
        val page = json.decodeFromString<AuctionPage>(
            browse(onlooker.token, CARD).bodyAsText(),
        )
        assertTrue(page.lots.any { it.id == lot.id }, "the lot is not on the board")
    }

    /**
     * Losing a race is an answer, not an error.
     *
     * Somebody outbidding you while you were typing is the *normal* case near the end of a lot, so
     * the second bidder gets a `200` with [AuctionRefusal.BID_TOO_LOW] and their own profile in the
     * same body. A `409` would make the client's error path the busiest path it has.
     */
    @Test
    fun aBidThatLostTheRaceIsATwoHundredWithAReason() = server {
        val seller = trader("auction-raced", holding = 1)
        val first = trader("auction-quick", holding = 0)
        val second = trader("auction-slow", holding = 0)
        val lot = openLot(seller, "race-list")

        outcome(post("/auctions/bid", first.token, BidRequest(lot, FLOOR, "race-first")))
        val late = post("/auctions/bid", second.token, BidRequest(lot, FLOOR, "race-second"))

        assertEquals(HttpStatusCode.OK, late.status, late.bodyAsText())
        val answer = outcome(late)
        assertEquals(AuctionRefusal.BID_TOO_LOW, answer.refusal)
        assertEquals(PURSE, answer.player.save.mgp, "a refused bid still took the money")
    }

    /**
     * The same bid sent twice is one bid.
     *
     * This is the endpoint's whole reason for carrying an operation id: without it the second press
     * of a button whose first press has not come back yet is a *second* bid, outbidding the first
     * at the player's own expense with both holds taken. The replay is served out of a `jsonb`
     * column, so it is compared as a decoded document — a string comparison would be measuring
     * Postgres's key ordering.
     */
    @Test
    fun theSameBidSentTwiceIsOneBid() = server {
        val seller = trader("auction-double-seller", holding = 1)
        val buyer = trader("auction-double-buyer", holding = 0)
        val lot = openLot(seller, "double-list")
        val request = BidRequest(lot, FLOOR, "double-bid")

        val once = outcome(post("/auctions/bid", buyer.token, request))
        val twice = outcome(post("/auctions/bid", buyer.token, request))

        assertEquals(once, twice, "the second press was a second bid")
        assertEquals(1, requireNotNull(twice.lot).bidCount)
        assertEquals(
            PURSE - FLOOR - AuctionRules.buyerFee(FLOOR),
            twice.player.save.mgp,
            "the hold was taken twice",
        )
    }

    /** A lot nobody has bid on comes back, as an item to use like every other card. */
    @Test
    fun withdrawingALotNobodyBidOnGivesTheCardBack() = server {
        val seller = trader("auction-withdraw", holding = 1)
        val lot = openLot(seller, "withdraw-list")

        val back = outcome(
            post("/auctions/cancel", seller.token, AuctionLotRequest(lot, "withdraw-cancel")),
        )

        assertNull(back.refusal, "the withdrawal was refused")
        assertEquals(AuctionStatus.CANCELLED, requireNotNull(back.lot).status)
        assertEquals(
            listOf(CardItem(CARD, 1, CardOrigin.AUCTION_UNSOLD)),
            back.player.save.bag,
            "the card did not come back as an item",
        )
    }

    /**
     * And a decision on a lot that is not waiting for one is refused rather than applied.
     *
     * The endpoint a modified client would reach for: `accept` on a lot still running is a seller
     * ending their own auction early at whatever the current bid happens to be. The refusal is
     * [AuctionRefusal.NOT_YOUR_DECISION] — the same one another player's lot gets, because from
     * here they are the same mistake.
     */
    @Test
    fun acceptingALotThatIsStillRunningIsRefused() = server {
        val seller = trader("auction-early", holding = 1)
        val buyer = trader("auction-early-buyer", holding = 0)
        val lot = openLot(seller, "early-list")
        outcome(post("/auctions/bid", buyer.token, BidRequest(lot, FLOOR, "early-bid")))

        val refused = outcome(
            post("/auctions/accept", seller.token, AuctionLotRequest(lot, "early-accept")),
        )

        assertEquals(AuctionRefusal.NOT_YOUR_DECISION, refused.refusal)
        assertEquals(AuctionStatus.OPEN, statusOf(lot), "the lot was closed early")
    }

    /** What the seller has a stake in, which is where they find a lot waiting for an answer. */
    @Test
    fun mineShowsTheSellerTheirOwnLot() = server {
        val seller = trader("auction-mine", holding = 1)
        val lot = openLot(seller, "mine-list")

        val page = json.decodeFromString<AuctionPage>(
            client.get("/auctions/mine") {
                protocolHeaders()
                bearer(seller.token)
            }.bodyAsText(),
        )

        val theirs = requireNotNull(page.lots.firstOrNull { it.id == lot }) { "the lot is missing" }
        assertTrue(theirs.yours, "the seller's own lot did not come back marked as theirs")
    }

    // ------------------------------------------------------------------ the harness

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    /** A registered account, at the auction level, with [holding] copies of [CARD] and a purse. */
    private suspend fun ApplicationTestBuilder.trader(prefix: String, holding: Int): Session {
        val name = Postgres.freshAccount(prefix)
        val session = register(name)
        unlockForPvp(name, Unlocks.DEFAULT_AUCTION)

        val accounts = AccountStore(Postgres.dataSource)
        val accountId = requireNotNull(accounts.accountIdFor(name)) { "no account named $name" }
        val save = requireNotNull(accounts.saveFor(accountId)) { "$name has no character" }
        val stocked = (1..holding).fold(save) { profile, _ -> profile.withCard(CARD) }
        assertTrue(
            accounts.replaceSave(
                accountId,
                stocked.copy(
                    mgp = PURSE,
                    xp = XpTable.thresholdFor(Unlocks.DEFAULT_AUCTION),
                    level = Unlocks.DEFAULT_AUCTION,
                ),
            ),
        )
        return session
    }

    private suspend fun ApplicationTestBuilder.register(name: String): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(credentials(name)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private fun listing(operationId: String) =
        ListCardRequest(CARD, FLOOR, FLOOR, operationId = operationId)

    /** Opens a lot and returns its id, failing here rather than on the next line if refused. */
    private suspend fun ApplicationTestBuilder.openLot(
        seller: Session,
        operationId: String,
    ): String {
        val opened = outcome(post("/auctions", seller.token, listing(operationId)))
        val lot = requireNotNull(opened.lot) { "could not open a lot: ${opened.refusal}" }
        return lot.id
    }

    /**
     * The board, narrowed to one card when [cardId] is given.
     *
     * The narrowing is not decoration: the browse list is capped at a hundred lots ordered by
     * deadline, and `AuctionFlowTest` leaves open lots behind whose deadlines are years in the past
     * — they sort first. Asserting on an unfiltered board would be asserting about the suite.
     */
    private suspend fun ApplicationTestBuilder.browse(token: String, cardId: Int? = null) =
        client.get(if (cardId == null) "/auctions" else "/auctions?card=$cardId") {
            protocolHeaders()
            bearer(token)
        }

    private suspend inline fun <reified T> ApplicationTestBuilder.post(
        path: String,
        token: String,
        body: T,
    ) = client.post(path) {
        protocolHeaders()
        bearer(token)
        setBody(json.encodeToString(body))
    }

    private suspend fun outcome(response: HttpResponse): AuctionOutcome {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    /** Straight out of the table, because a lot's own answer is the thing under test. */
    private fun statusOf(lotId: String): AuctionStatus = Postgres.dataSource.connection.use { db ->
        db.prepareStatement("SELECT status FROM auction_lots WHERE id = ?").use { statement ->
            statement.setString(1, lotId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next(), "lot $lotId is not in the table")
                AuctionStatus.valueOf(rows.getString(1))
            }
        }
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
        /**
         * A rank-one card, and deliberately **not** the one `AuctionFlowTest` picks.
         *
         * The two files share a database. Trading a card nothing else in the suite trades is what
         * lets [browse] narrow to it and get back only this file's lots.
         */
        val CARD = Catalogs.cards.cards.last { it.rarity == 1 }.id

        /** Its shop price: the floor, and the only price any of these lots needs. */
        val FLOOR = AuctionRules.floorPriceOf(CARD, Catalogs.cards.byId)

        /** Enough that no assertion here is about running out. */
        const val PURSE = 100_000
    }
}
