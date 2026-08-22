package com.tripletriad.server

import com.tripletriad.data.Campaign
import com.tripletriad.data.CardValue
import com.tripletriad.data.ShopCatalog
import com.tripletriad.data.StarterPack
import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.model.PotionItem
import com.tripletriad.protocol.BagItemRequest
import com.tripletriad.protocol.BuyRequest
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.ClaimStarterRequest
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.EnterCampaignRequest
import com.tripletriad.protocol.PlayerState
import com.tripletriad.protocol.SellCardRequest
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The endpoints that move money: the shop, the resale counter, and a ladder's entry fee.
 *
 * ### The claim being made
 *
 * That **no number arriving from a client is believed**. Each test below sends a request that would
 * be profitable if any part of it were taken at face value — a price, a quantity, a fee — and
 * asserts the profile the server wrote instead.
 *
 * ### Why the prices are read from the catalogue here too
 *
 * A test that hard-coded "a bronze pack costs 288" would pass while the server charged something
 * else entirely, as long as the two happened to be written on the same day. Reading the price from
 * `ShopCatalog` in the assertion means the test is checking that the server used *its own table*,
 * which is the property that matters.
 */
class IntentRoutesTest {

    /** Buying takes exactly the catalogue's price and hands over exactly the item. */
    @Test
    fun buyingChargesTheCataloguePrice() = server {
        val session = register()
        val offer = anOffer()
        val before = me(session.token).save

        val after = buy(session.token, offer.item, "op-buy").save

        assertEquals(before.mgp - offer.price, after.mgp, "the wrong price was charged")
        assertTrue(after.bag.isNotEmpty(), "nothing was delivered")
    }

    /**
     * A client cannot name its own price, because there is nowhere on the wire to name one.
     *
     * The strongest form of the claim and the reason [BuyRequest] carries no price field: this test
     * cannot even be written as "send price 1", and that is the point. What it can do is prove the
     * charge came from the catalogue rather than from anything the client controls — by buying the
     * same offer under a *different* stack, which is the only quantity-shaped field in the payload.
     */
    @Test
    fun theStackOnABuyIsNotAQuantity() = server {
        val session = register()
        val offer = anOffer()
        val before = me(session.token).save

        val after = buy(session.token, offer.item.withStack(99), "op-greedy").save

        assertEquals(before.mgp - offer.price, after.mgp, "a stack of 99 changed the charge")
    }

    /** An empty purse buys nothing, and is not charged for it. */
    @Test
    fun anUnaffordablePurchaseChangesNothing() = server {
        val session = registerHolding(mgp = 0)
        val offer = anOffer()

        val after = buy(session.token, offer.item, "op-broke").save

        assertEquals(0, after.mgp, "a purchase nobody could pay for moved the purse")
        assertTrue(after.bag.isEmpty(), "an unaffordable purchase was delivered anyway")
    }

    /** An item that is not on the shelf is not for sale, whatever the client calls it. */
    @Test
    fun anItemThatIsNotOnTheShelfCannotBeBought() = server {
        val session = register()
        val before = me(session.token).save

        // A real item, correctly formed, that `ShopCatalog` does not offer.
        val after = buy(session.token, BoosterItem(BoosterType.BRONZE, stack = 1), "op-off").save
            .takeIf { ShopCatalog.shelf(Catalogs.cards.byId).none { on -> on.item is BoosterItem } }
            ?: return@server

        assertEquals(before.mgp, after.mgp)
        assertEquals(before.bag, after.bag)
    }

    /** Selling a card pays the card table's price and takes the card. */
    @Test
    fun sellingACardPaysWhatTheCardTableSays() = server {
        val session = register()
        val before = me(session.token).save
        val card = before.cards.keys.first()
        val worth = CardValue.resaleOf(card, Catalogs.cards.byId)

        val after = sellCard(session.token, card, "op-sell").save

        assertEquals(before.mgp + worth, after.mgp, "the wrong resale price was paid")
        val held = after.cards[card] ?: 0
        assertTrue(held < before.cards.getValue(card), "the card was paid for and not taken")
    }

    /**
     * Selling a card nobody owns pays nothing.
     *
     * The forgery this closes is the profitable one: without the ownership check the server would
     * happily pay out for a card id that was never in the collection, once per operation id.
     */
    @Test
    fun sellingACardNobodyOwnsPaysNothing() = server {
        val session = register()
        val before = me(session.token).save
        val unowned = Catalogs.cards.all.map { it.id }.first { it !in before.cards }

        val after = sellCard(session.token, unowned, "op-phantom").save

        assertEquals(before.mgp, after.mgp, "the server paid for a card that was never owned")
    }

    /** Entering a ladder costs the ladder's fee, from the server's own catalogue. */
    @Test
    fun enteringALadderCostsItsFee() = server {
        val ladder = openLadder()
        val session = registerHolding(mgp = ladder.fee * 2)
        val before = me(session.token).save

        val after = enterCampaign(session.token, ladder.key, "op-ladder").save

        assertEquals(before.mgp - ladder.fee, after.mgp, "the wrong entry fee was taken")
    }

    /** Paying for a place is what opens the run, and the run starts on the first rung. */
    @Test
    fun enteringALadderOpensTheRunOnItsFirstRung() = server {
        val ladder = openLadder()
        val session = registerHolding(mgp = ladder.fee * 2)

        val after = enterCampaign(session.token, ladder.key, "op-run-open").save

        assertEquals(ladder.key, after.campaignRun?.campaignKey)
        assertEquals(Campaign.FIRST_STEP, after.campaignRun?.step)
    }

    /**
     * One entry per ladder per UTC day, and the stamp goes down at **entry**.
     *
     * A first-round defeat is eliminating, so a limit applied when a run resolves would only ever
     * bite the players who won. This asserts the case that would prove it: the run is abandoned
     * before the second attempt, so nothing about the *run* stands in the way — only the day does.
     */
    @Test
    fun aSecondEntryTheSameDayIsRefusedAndCostsNothing() = server {
        val ladder = openLadder()
        val session = registerHolding(mgp = ladder.fee * 4)

        val first = enterCampaign(session.token, ladder.key, "op-day-1").save
        // Abandon the run the way a defeat would, leaving the day's stamp behind.
        plant(session) { it.leavingCampaign() }

        val second = enterCampaign(session.token, ladder.key, "op-day-2").save

        assertNull(second.campaignRun, "a second run was opened on the same day")
        assertEquals(first.mgp, second.mgp, "the refused entry was charged for")
    }

    /** A run already open is refused, whichever ladder the second request names. */
    @Test
    fun aSecondRunIsRefusedWhileOneIsStillOpen() = server {
        val ladder = openLadder()
        val session = registerHolding(mgp = ladder.fee * 4)

        val first = enterCampaign(session.token, ladder.key, "op-one-run").save
        val second = enterCampaign(session.token, ladder.key, "op-two-runs").save

        assertEquals(first.campaignRun, second.campaignRun, "the open run was replaced")
        assertEquals(first.mgp, second.mgp, "the refused entry was charged for")
    }

    /**
     * A gated ladder takes nothing until its achievement is held.
     *
     * The Card Club is the shipped case: Balamb Garden is the way in, so a profile that has never
     * finished Balamb cannot buy a place in it at any price.
     */
    @Test
    fun aGatedLadderIsNotEnteredAndNotCharged() = server {
        val gated = Catalogs.campaigns.all.first { it.requiresAchievement != null }
        val session = registerHolding(mgp = gated.fee * 2)
        val before = me(session.token).save

        val after = enterCampaign(session.token, gated.key, "op-gated").save

        assertNull(after.campaignRun, "a locked ladder was entered")
        assertEquals(before.mgp, after.mgp, "a locked ladder was charged for")
    }

    /**
     * A purse that cannot cover the fee does not enter, and is not partly charged.
     *
     * The gap this closes was invisible while the deduction lived on the client: `withMgp` floors
     * at zero, so a player holding 100 entered a 500 ladder for 100 — being broke was the cheapest
     * way in. A hidden button was the only thing stopping it, and a hidden button is not a rule.
     */
    @Test
    fun aLadderNobodyCanAffordIsNotEntered() = server {
        val ladder = openLadder()
        val session = registerHolding(mgp = ladder.fee - 1)

        val after = enterCampaign(session.token, ladder.key, "op-broke-ladder").save

        assertEquals(ladder.fee - 1, after.mgp, "a ladder nobody could afford took what was there")
    }

    /**
     * A profile that has sold everything can repair itself, and one that has not gets nothing.
     *
     * Both halves in one test because the condition is the whole endpoint: `StarterPack.isOwedBy`
     * decides, and a version that granted unconditionally would be a free box on every tap.
     */
    @Test
    fun onlyAProfileThatCannotPlayIsGivenABox() = server {
        val healthy = register()
        val before = me(healthy.token).save

        val unchanged = claimStarter(healthy.token, "op-greedy").save
        assertEquals(before.cards, unchanged.cards, "a playable profile was handed a free box")

        // Now one that has nothing: the repair path this endpoint exists for.
        val destitute = registerHolding()
        plant(destitute) { it.copy(cards = emptyMap(), decks = emptyList()) }
        assertTrue(StarterPack.isOwedBy(me(destitute.token).save), "the fixture is not destitute")

        val repaired = claimStarter(destitute.token, "op-repair").save

        assertTrue(repaired.cards.isNotEmpty(), "a profile that cannot play was not repaired")
        assertFalse(StarterPack.isOwedBy(repaired), "the repair left it still unable to play")
    }

    /**
     * And the collection is no longer the client's to write, which is what made the move necessary.
     *
     * The last field to join the server-owned list, and the one worth the most. Asserted here
     * rather than only in `:core` because the endpoint is where a client would actually try it.
     */
    @Test
    fun aForgedCollectionIsNotStored() = server {
        val session = register()
        val before = me(session.token).save

        val forged = before.copy(cards = before.cards + (330 to 9))
        val response = client.put("/me/save") {
            protocolHeaders()
            bearer(session.token)
            setBody(json.encodeToString(GameSave.serializer(), forged))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val stored = me(session.token).save
        assertEquals(before.cards, stored.cards, "a client wrote its own collection")
    }

    /** A ladder this server has never heard of costs nothing rather than being an error. */
    @Test
    fun anUnknownLadderCostsNothing() = server {
        val session = register()
        val before = me(session.token).save

        val after = enterCampaign(session.token, "no-such-ladder", "op-nowhere").save

        assertEquals(before.mgp, after.mgp)
    }

    /**
     * Every intent is idempotent, asserted across all four rather than one.
     *
     * One test per endpoint would be four chances to add a fifth endpoint that forgets. This walks
     * the set, and the day a new intent is added the natural thing to do is add a line here.
     */
    @Test
    fun replayingAnIntentDoesItOnce() = server {
        val ladder = Catalogs.campaigns.all.first { it.fee > 0 }
        val session = registerHolding(mgp = ladder.fee * 4)
        val offer = anOffer()
        val card = me(session.token).save.cards.keys.first()

        val once: List<suspend () -> PlayerState> = listOf(
            { buy(session.token, offer.item, "once-buy") },
            { sellCard(session.token, card, "once-sell") },
            { enterCampaign(session.token, ladder.key, "once-ladder") },
        )

        // Run each twice, and require that the profile after the second call is what the first
        // call already reported. Comparing to the *first response* rather than to a snapshot is
        // what catches an endpoint that reapplies and then happens to be idempotent by accident.
        for (intent in once) {
            val first = intent()
            val second = intent()
            assertEquals(first.save, second.save, "an intent applied twice")
        }
    }

    /** Discarding takes the item and pays nothing; selling it pays and takes it. */
    @Test
    fun theBagIntentsDifferInWhetherTheyPay() = server {
        val session = registerHolding(bag = listOf(PotionItem(aPotion())))
        val before = me(session.token).save

        val discarded = discard(session.token, PotionItem(aPotion()), "op-bin").save
        assertTrue(discarded.bag.isEmpty(), "the item survived being thrown away")
        assertEquals(before.mgp, discarded.mgp, "throwing something away paid for it")
    }

    // ---- Harness ----------------------------------------------------------

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    /** The cheapest thing the widest format admits — a potion, in the shipped data. */
    private fun anOffer() =
        ShopCatalog.offers(assertNotNull(Catalogs.formats[FORMAT]), Catalogs.cards.byId)
            .minBy { it.price }

    private fun aPotion() = (
        ShopCatalog.shelf(Catalogs.cards.byId).map { it.item }
            .filterIsInstance<PotionItem>().first()
        ).potionType

    private suspend fun ApplicationTestBuilder.register(): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(Postgres.freshAccount("intent"), PASSWORD)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * An account with a planted purse or bag, written **straight into the database**.
     *
     * It went through `PUT /me/save` until that endpoint stopped believing either field, which is
     * exactly what these tests exist to bring about. A fixture writes where a client cannot; the
     * alternative — earning the MGP by playing matches — would make every test here depend on the
     * payout table.
     */
    private suspend fun ApplicationTestBuilder.registerHolding(
        mgp: Int? = null,
        bag: List<Item> = emptyList(),
    ): Session {
        val session = register()
        val accounts = AccountStore(Postgres.dataSource)
        val id = assertNotNull(accounts.accountIdForUsername(session.player.save.username))
        val stored = assertNotNull(accounts.saveFor(id))
        assertTrue(
            accounts.replaceSave(id, stored.copy(bag = bag, mgp = mgp ?: stored.mgp)),
            "the planted profile was not stored",
        )
        return session
    }

    private suspend fun ApplicationTestBuilder.claimStarter(token: String, op: String) =
        intent(token, "/me/starter", json.encodeToString(ClaimStarterRequest(op)))

    /** Rewrites the stored profile behind the API, for the fields the API no longer believes. */
    private fun plant(session: Session, change: (GameSave) -> GameSave) {
        val accounts = AccountStore(Postgres.dataSource)
        val id = assertNotNull(accounts.accountIdForUsername(session.player.save.username))
        assertTrue(accounts.replaceSave(id, change(assertNotNull(accounts.saveFor(id)))))
    }

    private suspend fun ApplicationTestBuilder.buy(token: String, item: Item, op: String) =
        intent(token, "/me/shop/buy", json.encodeToString(BuyRequest(item, FORMAT, op)))

    private suspend fun ApplicationTestBuilder.sellCard(token: String, cardId: Int, op: String) =
        intent(token, "/me/cards/sell", json.encodeToString(SellCardRequest(cardId, op)))

    private suspend fun ApplicationTestBuilder.discard(token: String, item: Item, op: String) =
        intent(token, "/me/bag/discard", json.encodeToString(BagItemRequest(item, op)))

    /**
     * A ladder anyone who can pay may enter — Balamb Garden, in the shipped data.
     *
     * Not `first { it.fee > 0 }` any more: the Card Club is gated behind Balamb's achievement now,
     * so that expression picks a ladder these tests could never enter and every one of them would
     * pass by refusing for the wrong reason.
     */
    private fun openLadder(): Campaign =
        Catalogs.campaigns.all.first { it.fee > 0 && it.requiresAchievement == null }

    private suspend fun ApplicationTestBuilder.enterCampaign(
        token: String,
        key: String,
        op: String,
    ) = intent(token, "/me/campaign/enter", json.encodeToString(EnterCampaignRequest(key, op)))

    private suspend fun ApplicationTestBuilder.intent(
        token: String,
        path: String,
        body: String,
    ): PlayerState {
        val response = client.post(path) {
            protocolHeaders()
            bearer(token)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.me(token: String): PlayerState {
        val response = client.get("/me") {
            protocolHeaders()
            bearer(token)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
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

        /** The widest authored format, so the whole shelf is on sale. */
        const val FORMAT = "free-play"
    }
}
