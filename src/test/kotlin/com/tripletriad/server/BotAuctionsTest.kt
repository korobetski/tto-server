package com.tripletriad.server

import com.tripletriad.data.AuctionRules
import com.tripletriad.data.CardValue
import com.tripletriad.model.Card
import com.tripletriad.model.GameSave
import com.tripletriad.protocol.AuctionLot
import com.tripletriad.protocol.AuctionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a bot puts up at the auction house and what it bids on, with no database under it.
 *
 * ### The assertion this file exists for
 *
 * [aLotAboveTheCardsWorthIsLeftAlone]. The rest is a bot taking part sensibly; that one is what
 * keeps the roster from being a way to pump MGP out of the accounts the server plays itself — the
 * most a person can get from a bot for a card is what the card is worth.
 *
 * `AuctionStore` re-checks every term inside its own transaction and `AuctionFlowTest` holds it to
 * that. What is asserted here is the choosing, which is this server's and nobody else's.
 */
class BotAuctionsTest {

    private val cards = Catalogs.cards
    private val format = assertNotNull(Catalogs.formats[FORMAT])

    private val rare = admitted().first { it.rarity > BotBrain.SELLABLE_RARITY }
    private val common = admitted().first { it.rarity <= BotBrain.SELLABLE_RARITY }

    // ---- Listing ----------------------------------------------------------

    /** A spare rare card goes up, and at the one price the game already puts on it. */
    @Test
    fun aSpareRareCardIsListedAtItsWorth() {
        val save = seller().withCard(rare.id, HOARD)

        assertEquals(
            BotListing(rare.id, CardValue.worthOf(rare)),
            BotAuctions.listing(save, cards, emptyList()),
        )
    }

    /** The one spare a bot keeps is kept here too: it is the copy that lets a second deck exist. */
    @Test
    fun theKeptSpareIsNotListed() {
        val save = seller().withCard(rare.id, BotBrain.KEPT_SPARES)

        assertNull(BotAuctions.listing(save, cards, emptyList()))
    }

    /** With both tiers free, the rare card is the listing a person is likeliest to want. */
    @Test
    fun theRareTierIsAskedFirst() {
        val save = seller().withCard(rare.id, HOARD).withCard(common.id, HOARD)

        assertEquals(rare.id, BotAuctions.listing(save, cards, emptyList())?.cardId)
    }

    /**
     * **A common gets a lot of its own**, rather than waiting behind every rare card.
     *
     * The request this answers: a bot selling "low-level" cards as well. A single queue ordered by
     * worth would never have reached one while a rare lot was open.
     */
    @Test
    fun aCommonGetsALotOfItsOwn() {
        val save = seller().withCard(rare.id, HOARD).withCard(common.id, HOARD)
        val rareLot = lot(rare.id, yours = true)

        assertEquals(
            BotListing(common.id, CardValue.worthOf(common)),
            BotAuctions.listing(save, cards, listOf(rareLot)),
        )
    }

    /** One lot per tier: with both open, nothing more goes up however much is spare. */
    @Test
    fun bothTiersBusyListNothing() {
        val otherRare = admitted().last { it.rarity > BotBrain.SELLABLE_RARITY }
        val otherCommon = admitted().last { it.rarity <= BotBrain.SELLABLE_RARITY }
        val save = seller().withCard(rare.id, HOARD).withCard(common.id, HOARD)
        val own = listOf(lot(otherRare.id, yours = true), lot(otherCommon.id, yours = true))

        assertNull(BotAuctions.listing(save, cards, own))
    }

    /** A finished lot frees its tier: a bot's history does not count against it. */
    @Test
    fun aFinishedLotFreesItsTier() {
        val save = seller().withCard(rare.id, HOARD)
        val sold = lot(rare.id, yours = true).copy(status = AuctionStatus.SOLD)

        assertEquals(rare.id, BotAuctions.listing(save, cards, listOf(sold))?.cardId)
    }

    /** A purse that cannot cover the listing fee lists nothing, rather than being refused. */
    @Test
    fun aPurseShortOfTheFeeListsNothing() {
        val save = seller().withCard(rare.id, HOARD).copy(mgp = 0)

        assertNull(BotAuctions.listing(save, cards, emptyList()))
    }

    // ---- Bidding ----------------------------------------------------------

    /** A card the bot's strongest hand would take is bid on, at the least the lot will take. */
    @Test
    fun aNeededCardIsBidOnAtTheMinimum() {
        val wanted = lot(needed().id)

        assertEquals(
            BotBid(wanted.id, wanted.minimumBid),
            BotAuctions.bidding(buyer(), format, cards, listOf(wanted), emptyList()),
        )
    }

    /**
     * **The pin this file exists for.** A lot already past the card's worth is left to people.
     *
     * Outbid, a bot follows one increment at a time — and stops here. A bot that paid more than a
     * card is worth would be a tap anybody could open by listing a card it needs at a price of
     * their choosing.
     */
    @Test
    fun aLotAboveTheCardsWorthIsLeftAlone() {
        val card = needed()
        val dear = lot(card.id, startPrice = CardValue.worthOf(card) + 1)

        assertNull(BotAuctions.bidding(buyer(), format, cards, listOf(dear), emptyList()))
    }

    /** A card the bot already owns is not bought twice: a second copy never enters a hand. */
    @Test
    fun aCardAlreadyOwnedIsNotBidOn() {
        val card = needed()
        val save = buyer().withCard(card.id)

        assertNull(BotAuctions.bidding(save, format, cards, listOf(lot(card.id)), emptyList()))
    }

    /** A bot's own lot, and one it already leads, are not bid on. */
    @Test
    fun aBotDoesNotBidAgainstItself() {
        val card = needed()
        val own = lot(card.id, yours = true)
        val leading = lot(card.id, id = "leading").leading()

        assertNull(BotAuctions.bidding(buyer(), format, cards, listOf(own, leading), emptyList()))
    }

    /** Leading on one lot for a card, a bot does not open a second front for the same card. */
    @Test
    fun oneLeadPerCard() {
        val card = needed()
        val winning = lot(card.id, id = "winning").leading()
        val another = lot(card.id, id = "another")

        assertNull(
            BotAuctions.bidding(buyer(), format, cards, listOf(another), listOf(winning)),
        )
    }

    /** A bid and its fee must fit the purse — the store would refuse it, so it is not asked. */
    @Test
    fun aBidThePurseCannotCoverIsNotMade() {
        val wanted = lot(needed().id)
        val poor = buyer().copy(mgp = AuctionRules.totalDue(wanted.minimumBid) - 1)

        assertNull(BotAuctions.bidding(poor, format, cards, listOf(wanted), emptyList()))
    }

    // ---- Fixtures ---------------------------------------------------------

    private fun admitted(): List<Card> = cards.admittedBy(format)

    /** A purse that covers any listing fee, and no cards. */
    private fun seller(): GameSave = GameSave.new("seller", createdAt = NOW).copy(mgp = PURSE)

    /** The weakest five of the format, so nearly anything stronger is a card its decks want. */
    private fun buyer(): GameSave = admitted()
        .sortedWith(compareBy<Card> { it.total }.thenBy { it.id })
        .take(HAND_SIZE_OWNED)
        .fold(GameSave.new("buyer", createdAt = NOW)) { save, card -> save.withCard(card.id) }
        .copy(mgp = PURSE)

    /** Stronger than everything [buyer] owns, so its strongest hand starts from it. */
    private fun needed(): Card = admitted()
        .filter { buyer().copiesOf(it.id) == 0 }
        .maxWith(compareBy<Card> { it.total }.thenByDescending { it.id })

    private fun lot(
        cardId: Int,
        id: String = "lot-$cardId",
        startPrice: Int = AuctionRules.floorPriceOf(cardId, cards.byId),
        yours: Boolean = false,
    ) = AuctionLot(
        id = id,
        cardId = cardId,
        startPrice = startPrice,
        endsAt = NOW + HOUR,
        status = AuctionStatus.OPEN,
        yours = yours,
    )

    /** The same lot with the bot's bid on top of it. */
    private fun AuctionLot.leading() = copy(youLead = true, topBid = CHEAP)

    private companion object {
        const val FORMAT = "ff14-standard"
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L

        /** Two past the kept spare. */
        const val HOARD = 3
        const val PURSE = 100_000
        const val CHEAP = 1

        /** A hand's worth, the least a collection needs for any deck to be built at all. */
        const val HAND_SIZE_OWNED = 5
    }
}
