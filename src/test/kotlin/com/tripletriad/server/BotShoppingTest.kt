package com.tripletriad.server

import com.tripletriad.data.ShopCatalog
import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameSave
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a bot buys at the shop, and what it goes without to buy it — with no database under it.
 *
 * ### The assertion this file exists for
 *
 * [aBotSavesForTheSingleItIsMissing]. Before it, a bot spent every coin above its reserve on the
 * dearest pack it could reach, pass after pass, and a single from the shelf was a purchase it could
 * never make: the money never stayed long enough to add up. Saving is a card it goes without packs
 * for, and this is the pin that says so.
 */
class BotShoppingTest {

    private val cards = Catalogs.cards
    private val format = assertNotNull(Catalogs.formats[FORMAT])
    private val offers = ShopCatalog.offers(format, cards.byId)

    // ---- The reserve ------------------------------------------------------

    /** A purse at the reserve buys nothing: that money is not the shop's. */
    @Test
    fun aBotKeepsItsReserveOutOfTheShop() {
        val save = bare().copy(mgp = RESERVE)
        assertNull(BotShopping.shopping(save, format, cards, PLAIN, RESERVE, Random(SEED)))
    }

    /**
     * What is earmarked — today's tournament fee — is kept back like the reserve: a purse that
     * buys a pack over the reserve alone buys nothing once the fee is set aside from it, and a
     * single it has saved for waits for the fee too.
     */
    @Test
    fun anEarmarkIsKeptOutOfTheShopToo() {
        val save = bare().copy(mgp = RICH)
        val promised = RICH - RESERVE
        assertNotNull(BotShopping.shopping(save, format, cards, PLAIN, RESERVE, Random(SEED)))
        assertNull(
            BotShopping.shopping(save, format, cards, PLAIN, RESERVE, Random(SEED), promised),
            "the fee is not the shop's either",
        )

        val goal = assertNotNull(BotShopping.goal(bare(), offers, SAVER, RESERVE))
        val enough = bare().copy(mgp = RESERVE + goal.price)
        assertNull(
            BotShopping.shopping(
                enough,
                format,
                cards,
                SAVER,
                RESERVE,
                Random(SEED),
                earmarked = 1,
            ),
            "today's entry comes before a single that can wait for tomorrow",
        )
    }

    // ---- Packs ------------------------------------------------------------

    /**
     * A purse that can stand it buys a pack and opens it — into the **bag**.
     *
     * Pinned as its own step because the boundary is easy to get wrong in the invisible direction:
     * `Inventory.use` on a booster yields card *items*, and a bot that stopped here would spend
     * its money forever and never own anything. `BotBrainTest.theBagIsWhereTheCollectionComesFrom`
     * is the other half.
     */
    @Test
    fun aPackBoughtIsAPackOpened() {
        val save = bare().copy(mgp = RICH)
        val shopped = assertNotNull(
            BotShopping.shopping(save, format, cards, PLAIN, RESERVE, Random(SEED)),
        )

        assertTrue(shopped.mgp < save.mgp, "a purchase costs something")
        assertTrue(shopped.mgp >= RESERVE, "the reserve must survive the trip")
        assertTrue(shopped.bag.any { it is CardItem }, "an opened pack yields card items")
        assertTrue(shopped.bag.none { it is BoosterItem }, "the pack itself should be gone")
    }

    /**
     * **Bots buy different packs.** The request this answers: not the dearest pack every time.
     *
     * Twenty bots differing only in their whims, facing the same shelf with the same purse, should
     * not all pay the same price — which is what buying the same pack would look like.
     */
    @Test
    fun botsBuyDifferentPacks() {
        val save = bare().copy(mgp = RICH)
        val spent = (1..BOTS).map { seed ->
            val whims = PLAIN.copy(seed = seed)
            val shopped = assertNotNull(
                BotShopping.shopping(save, format, cards, whims, RESERVE, Random(seed)),
            )
            save.mgp - shopped.mgp
        }

        assertTrue(spent.distinct().size > 1, "every bot bought the same pack: $spent")
    }

    /**
     * A pack holding nothing new is never bought: it would be a fifth of its price paid to turn
     * MGP into a card the counter buys back for two-fifths of its worth.
     */
    @Test
    fun aPackWithNothingNewIsNeverBought() {
        val complete = cards.all.fold(bare()) { save, card -> save.withCard(card.id) }
            .copy(mgp = RICH)

        assertNull(BotShopping.shopping(complete, format, cards, PLAIN, RESERVE, Random(SEED)))
    }

    /** Novelty runs from all of it, for a bare collection, to none of it, for a complete one. */
    @Test
    fun noveltyIsTheShareOfAPackThatIsNew() {
        val complete = cards.all.fold(bare()) { save, card -> save.withCard(card.id) }

        packs().forEach { type ->
            assertEquals(1.0, BotShopping.novelty(bare(), type, cards), NOVELTY_TOLERANCE)
            assertEquals(0.0, BotShopping.novelty(complete, type, cards), NOVELTY_TOLERANCE)
        }
        val type = packs().first { it.pool.size > 1 }
        val half = bare().withCard(type.pool.first())
        assertTrue(BotShopping.novelty(half, type, cards) in 0.0..1.0)
        assertTrue(BotShopping.novelty(half, type, cards) < 1.0, "an owned card is not new")
    }

    // ---- Saving for a single ----------------------------------------------

    /**
     * **A bot saves for a single it is missing**, rather than spending the money on packs.
     *
     * One coin short of its goal over the reserve, a saver buys nothing at all — not a pack, which
     * is the whole point: the pack would be paid for out of the goal. With the coin, it buys the
     * single, and the card is in its bag for `BotBrain.emptying` to take.
     */
    @Test
    fun aBotSavesForTheSingleItIsMissing() {
        val goal = assertNotNull(BotShopping.goal(bare(), offers, SAVER, RESERVE))
        val cardId = (goal.item as CardItem).cardId
        val short = bare().copy(mgp = RESERVE + goal.price - 1)
        val enough = bare().copy(mgp = RESERVE + goal.price)

        assertNull(
            BotShopping.shopping(short, format, cards, SAVER, RESERVE, Random(SEED)),
            "a coin short, it keeps saving",
        )
        val bought = assertNotNull(
            BotShopping.shopping(enough, format, cards, SAVER, RESERVE, Random(SEED)),
        )
        assertEquals(RESERVE, bought.mgp, "the single costs its price, and the reserve is left")
        assertTrue(bought.bag.any { it is CardItem && it.cardId == cardId })
    }

    /** A card it owns, or already has in its bag, is not the one it saves for. */
    @Test
    fun aBotDoesNotSaveForACardItHas() {
        val goal = assertNotNull(BotShopping.goal(bare(), offers, SAVER, RESERVE))
        val cardId = (goal.item as CardItem).cardId

        assertNotEquals(goal, BotShopping.goal(bare().withCard(cardId), offers, SAVER, RESERVE))
        assertNotEquals(
            goal,
            BotShopping.goal(bare().copy(bag = listOf(CardItem(cardId))), offers, SAVER, RESERVE),
        )
    }

    /** Nothing dearer than its horizon over the reserve; with no horizon, nothing at all. */
    @Test
    fun aGoalIsWithinTheHorizon() {
        val goal = assertNotNull(BotShopping.goal(bare(), offers, SAVER, RESERVE))

        assertTrue(goal.price <= SAVER.horizonOver(RESERVE))
        assertNull(BotShopping.goal(bare(), offers, PLAIN, RESERVE), "no horizon reaches nothing")
    }

    /** Two savers with different whims do not all save for the same card. */
    @Test
    fun saversWantDifferentCards() {
        val goals = (1..BOTS).mapNotNull { seed ->
            BotShopping.goal(bare(), offers, SAVER.copy(seed = seed), RESERVE)
        }.map { (it.item as CardItem).cardId }

        assertTrue(goals.distinct().size > 1, "every saver wanted the same card: $goals")
    }

    // ---- Fixtures ---------------------------------------------------------

    private fun bare(): GameSave = GameSave.new("shopper", createdAt = NOW)

    private fun packs(): List<BoosterType> =
        offers.mapNotNull { (it.item as? BoosterItem)?.boosterType }

    private companion object {
        const val FORMAT = "ff14-standard"

        /** Distinct from every other class's, per the note in `BotDirectorTest`. */
        const val SEED = 20_260_925
        const val NOW = 1_800_000_000_000L

        const val RESERVE = 5_000
        const val RICH = 200_000
        const val BOTS = 20
        const val NOVELTY_TOLERANCE = 1e-9

        val PLAIN = plainPersonality()

        /** A horizon wide enough that every single on the shelf is within it. */
        val SAVER = PLAIN.copy(horizon = 100.0)
    }
}
