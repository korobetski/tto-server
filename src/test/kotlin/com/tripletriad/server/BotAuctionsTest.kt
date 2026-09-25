package com.tripletriad.server

import com.tripletriad.data.AuctionRules
import com.tripletriad.data.CardValue
import com.tripletriad.model.Card
import com.tripletriad.model.GameSave
import com.tripletriad.protocol.AuctionLot
import com.tripletriad.protocol.AuctionStatus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a bot puts up at the auction house and what it bids on, with no database under it.
 *
 * ### The assertion this file exists for
 *
 * [aLotAboveTheCardsWorthIsLeftAlone], and its collector's twin
 * [aCollectorGoesAQuarterOverWorthAndNoFurther]. The rest is a bot taking part sensibly; those two
 * are what keep the roster from being a way to pump MGP out of the accounts the server plays
 * itself — the most a person can get from a bot for a card is what the card is worth, and a
 * quarter more from a collector completing its set.
 *
 * Every other test runs under [plainPersonality], so what it asserts is the choosing and not a
 * drawn temper: a test about one archetype says which.
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

    /**
     * A spare rare card goes up at the bot's own price — and a plain bot's is the one the game
     * already puts on the card, to the nearest ten.
     */
    @Test
    fun aSpareRareCardIsListedAtItsOwnPrice() {
        val save = seller().withCard(rare.id, HOARD)

        val listing = assertNotNull(BotAuctions.listing(save, cards, emptyList(), PLAIN))

        assertEquals(BotListing(rare.id, priceOf(rare, PLAIN)), listing)
        assertTrue(listing.price % PRICE_STEP == 0, "a person prices in tens: ${listing.price}")
        assertTrue(
            abs(listing.price - CardValue.worthOf(rare)) <= PRICE_STEP / 2,
            "a plain bot asks what the card is worth",
        )
    }

    /** A merchant asks over worth: that is what makes it one. */
    @Test
    fun aMerchantAsksMoreThanTheCardIsWorth() {
        val merchant = plainPersonality(BotArchetype.MERCHANT).copy(markup = MERCHANT_MARKUP)
        val save = seller().withCard(rare.id, HOARD)

        val listing = assertNotNull(BotAuctions.listing(save, cards, emptyList(), merchant))

        assertTrue(listing.price > CardValue.worthOf(rare), "asked ${listing.price}")
    }

    /** The one spare a bot keeps is kept here too: it is the copy that lets a second deck exist. */
    @Test
    fun theKeptSpareIsNotListed() {
        val save = seller().withCard(rare.id, BotBrain.KEPT_SPARES)

        assertNull(BotAuctions.listing(save, cards, emptyList(), PLAIN))
    }

    /** With both tiers free, the rare card is the listing a person is likeliest to want. */
    @Test
    fun theRareTierIsAskedFirst() {
        val save = seller().withCard(rare.id, HOARD).withCard(common.id, HOARD)

        assertEquals(rare.id, BotAuctions.listing(save, cards, emptyList(), PLAIN)?.cardId)
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
            BotListing(common.id, priceOf(common, PLAIN)),
            BotAuctions.listing(save, cards, listOf(rareLot), PLAIN),
        )
    }

    /** One lot per tier: with both open, nothing more goes up however much is spare. */
    @Test
    fun bothTiersBusyListNothing() {
        val save = seller().withCard(rare.id, HOARD).withCard(common.id, HOARD)

        assertNull(BotAuctions.listing(save, cards, busyTiers(), PLAIN))
    }

    /**
     * A merchant keeps a second lot in each tier, so the same two busy tiers still take one more.
     *
     * Which is how a merchant sells at the house what another bot sells at the counter.
     */
    @Test
    fun aMerchantKeepsASecondLotPerTier() {
        val save = seller().withCard(rare.id, HOARD).withCard(common.id, HOARD)
        val merchant = plainPersonality(BotArchetype.MERCHANT)

        assertEquals(rare.id, BotAuctions.listing(save, cards, busyTiers(), merchant)?.cardId)
    }

    /** A finished lot frees its tier: a bot's history does not count against it. */
    @Test
    fun aFinishedLotFreesItsTier() {
        val save = seller().withCard(rare.id, HOARD)
        val sold = lot(rare.id, yours = true).copy(status = AuctionStatus.SOLD)

        assertEquals(rare.id, BotAuctions.listing(save, cards, listOf(sold), PLAIN)?.cardId)
    }

    /** A purse that cannot cover the listing fee lists nothing, rather than being refused. */
    @Test
    fun aPurseShortOfTheFeeListsNothing() {
        val save = seller().withCard(rare.id, HOARD).copy(mgp = 0)

        assertNull(BotAuctions.listing(save, cards, emptyList(), PLAIN))
    }

    // ---- Bidding ----------------------------------------------------------

    /** A card the bot's strongest hand would take is bid on, at the least the lot will take. */
    @Test
    fun aNeededCardIsBidOnAtTheMinimum() {
        val wanted = lot(needed().id)

        assertEquals(
            BotBid(wanted.id, wanted.minimumBid),
            BotAuctions.bidding(buyer(), format, cards, listOf(wanted), emptyList(), PLAIN),
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

        assertNull(BotAuctions.bidding(buyer(), format, cards, listOf(dear), emptyList(), PLAIN))
    }

    /**
     * **A collector pays up to a quarter over worth for its own set — and not a coin more.**
     *
     * The premium is the one place a bot pays over worth, and it is bounded for the reason the
     * pin above is: `BotPersonality.COLLECTOR_PREMIUM`. A duelist facing the same lot leaves it.
     */
    @Test
    fun aCollectorGoesAQuarterOverWorthAndNoFurther() {
        val card = needed()
        val collector = plainPersonality(BotArchetype.COLLECTOR)
        val ceiling = CardValue.worthOf(card) * PREMIUM_NUMERATOR / PREMIUM_DENOMINATOR
        val atCeiling = lot(card.id, startPrice = ceiling)
        val beyond = lot(card.id, startPrice = ceiling + 1)

        assertEquals(
            BotBid(atCeiling.id, ceiling),
            BotAuctions.bidding(buyer(), format, cards, listOf(atCeiling), emptyList(), collector),
        )
        assertNull(
            BotAuctions.bidding(buyer(), format, cards, listOf(beyond), emptyList(), collector),
        )
        assertNull(
            BotAuctions.bidding(buyer(), format, cards, listOf(atCeiling), emptyList(), PLAIN),
            "the premium is a collector's, not everybody's",
        )
    }

    /**
     * **A collector bids for the binder, a duelist only for its decks.**
     *
     * A purse with no cards builds no hand at all, so no lot is a card any deck of its would field:
     * the duelist has nothing to bid on, and the collector bids on the card it is missing that is
     * worth the most — which is every card, since it owns none.
     */
    @Test
    fun aCollectorBidsOnACardNoDeckWouldField() {
        val bare = GameSave.new("binder", createdAt = NOW).copy(mgp = PURSE)
        val lots = listOf(lot(rare.id), lot(common.id))
        val collector = plainPersonality(BotArchetype.COLLECTOR)

        assertNull(BotAuctions.bidding(bare, format, cards, lots, emptyList(), PLAIN))
        assertEquals(
            "lot-${rare.id}",
            BotAuctions.bidding(bare, format, cards, lots, emptyList(), collector)?.lotId,
            "the dearer of two missing cards is the one a collector goes for first",
        )
    }

    /** A card outside the set it collects is not the collector's business, however cheap. */
    @Test
    fun aCollectorBidsOnlyOnItsOwnSet() {
        val foreign = assertNotNull(
            cards.all.firstOrNull { !format.admitsCard(it.id) },
            "the catalogue holds a card this format does not admit",
        )
        val bare = GameSave.new("binder", createdAt = NOW).copy(mgp = PURSE)
        val collector = plainPersonality(BotArchetype.COLLECTOR)

        assertNull(
            BotAuctions.bidding(
                bare,
                format,
                cards,
                listOf(lot(foreign.id)),
                emptyList(),
                collector,
            ),
        )
    }

    /** A card the bot already owns is not bought twice: a second copy never enters a hand. */
    @Test
    fun aCardAlreadyOwnedIsNotBidOn() {
        val card = needed()
        val save = buyer().withCard(card.id)

        assertNull(
            BotAuctions.bidding(save, format, cards, listOf(lot(card.id)), emptyList(), PLAIN),
        )
    }

    /** A bot's own lot, and one it already leads, are not bid on. */
    @Test
    fun aBotDoesNotBidAgainstItself() {
        val card = needed()
        val own = lot(card.id, yours = true)
        val leading = lot(card.id, id = "leading").leading()

        assertNull(
            BotAuctions.bidding(buyer(), format, cards, listOf(own, leading), emptyList(), PLAIN),
        )
    }

    /** Leading on one lot for a card, a bot does not open a second front for the same card. */
    @Test
    fun oneLeadPerCard() {
        val card = needed()
        val winning = lot(card.id, id = "winning").leading()
        val another = lot(card.id, id = "another")

        assertNull(
            BotAuctions.bidding(buyer(), format, cards, listOf(another), listOf(winning), PLAIN),
        )
    }

    /** A bid and its fee must fit the purse — the store would refuse it, so it is not asked. */
    @Test
    fun aBidThePurseCannotCoverIsNotMade() {
        val wanted = lot(needed().id)
        val poor = buyer().copy(mgp = AuctionRules.totalDue(wanted.minimumBid) - 1)

        assertNull(BotAuctions.bidding(poor, format, cards, listOf(wanted), emptyList(), PLAIN))
    }

    // ---- Fixtures ---------------------------------------------------------

    private fun admitted(): List<Card> = cards.admittedBy(format)

    /** What [personality] asks for [card]. The price rule is `BotPersonality`'s, not ours. */
    private fun priceOf(card: Card, personality: BotPersonality): Int = personality.askingPrice(
        worth = CardValue.worthOf(card),
        resale = CardValue.resaleOf(card.id, cards.byId),
    )

    /** One lot of the bot's own open in each tier, for cards other than [rare] and [common]. */
    private fun busyTiers(): List<AuctionLot> = listOf(
        lot(admitted().last { it.rarity > BotBrain.SELLABLE_RARITY }.id, yours = true),
        lot(admitted().last { it.rarity <= BotBrain.SELLABLE_RARITY }.id, yours = true),
    )

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

        val PLAIN = plainPersonality()

        /** `BotPersonality.PRICE_STEP`, which is private and is what a listing is rounded to. */
        const val PRICE_STEP = 10
        const val MERCHANT_MARKUP = 1.2

        /** `BotPersonality.COLLECTOR_PREMIUM`, as the fraction it is, so the ceiling is exact. */
        const val PREMIUM_NUMERATOR = 5
        const val PREMIUM_DENOMINATOR = 4
    }
}
