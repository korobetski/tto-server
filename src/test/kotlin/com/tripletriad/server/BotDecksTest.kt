package com.tripletriad.server

import com.tripletriad.data.CardValue
import com.tripletriad.model.Card
import com.tripletriad.model.DeckLimits
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.TypeRule
import com.tripletriad.protocol.ANY_DECK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which five cards a bot brings, against the shipped catalogue and no database.
 *
 * ### The assertion this file exists for
 *
 * [theTypedDeckIsBroughtToAscensionAndTheMixedOneToDescension]. The rest is deck building working;
 * that one is the reason there is more than one deck at all.
 *
 * `ASCENSION` and `DESCENSION` are one tally with opposite signs — each card of a type placed moves
 * every card of that type by one — so the hand that compounds under the first is the hand that
 * collapses under the second. A bot bringing its strongest five to both would be giving back the
 * whole of the rule in one of them, and nothing else in the suite would notice: the matches would
 * still deal, still settle and still pay.
 */
class BotDecksTest {

    private val cards = Catalogs.cards
    private val format = assertNotNull(Catalogs.formats[FORMAT])

    // ---- Building the three ------------------------------------------------

    /** Three slots, each a legal five, each drawn from what the profile actually owns. */
    @Test
    fun threeDecksAreBuiltAndAllOfThemAreLegal() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))

        assertTrue(save.decks.size >= SLOTS, "a bot keeps one deck per shape it might be asked for")
        save.decks.take(SLOTS).forEach { deck ->
            assertEquals(
                HAND_SIZE,
                deck.cards.size,
                "a short deck is one the referee will not deal",
            )
            assertTrue(DeckLimits.isLegal(deck.cards, cards.byId), "the caps are not optional")
            assertTrue(deck.isAffordable(save.cards), "a bot may only field what it owns")
        }
    }

    /**
     * The three are not the same five, and each is the best at its own job.
     *
     * Asserted as an ordering rather than on exact ids: the typed deck must be **more**
     * concentrated than the strongest one and the mixed deck must be **more** varied, which is the
     * claim. Pinning the ids would pin the catalogue instead.
     */
    @Test
    fun eachDeckIsTheBestAtItsOwnJob() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))
        val power = hand(save, POWER_SLOT)
        val typed = hand(save, TYPED_SLOT)
        val mixed = hand(save, MIXED_SLOT)

        assertTrue(
            concentration(typed) >= concentration(power),
            "the ascension deck must be at least as concentrated as the strongest one",
        )
        assertTrue(
            variety(mixed) >= variety(power),
            "the descension deck must be at least as varied as the strongest one",
        )
        assertTrue(
            concentration(typed) > 1 || variety(mixed) > 1,
            "this fixture has no types in it, so it proves nothing — widen the collection",
        )
        assertTrue(
            strength(power) >= strength(typed),
            "the strongest deck is the strongest one, or it is misnamed",
        )
    }

    /**
     * Rebuilding an unchanged profile changes nothing, and says so.
     *
     * The director schedules on the answer — a bot that "did something" waits a move rather than
     * an idle spell — so a rebuild that reported success every pass would keep a bot busy doing
     * nothing forever. It is also why the orderings break their ties on the card id.
     */
    @Test
    fun decksAlreadyBuiltAreLeftAlone() {
        val once = assertNotNull(BotDecks.decking(collector(), format, cards))
        assertNull(BotDecks.decking(once, format, cards))
    }

    /**
     * **A collection of nothing but aces cannot be built into a hand, and says so.**
     *
     * `DeckLimits` admits one five-star and two four-stars, so a profile holding twenty of the
     * most valuable cards in the format can legally field three of them. Null is the only honest
     * answer — there is no fourth card to reach for — and the caller keeps whatever decks it had.
     *
     * Worth pinning because it is the collection a bot that only ever bought the most expensive
     * pack would drift towards, and a silent short deck would be much worse than a refusal.
     */
    @Test
    fun aCollectionOfNothingButAcesCannotBeBuiltInto() {
        val aces = worthOrdered().take(COLLECTION)
            .fold(GameSave.new("aces", createdAt = NOW)) { save, id -> save.withCard(id) }
        assertNull(BotDecks.decking(aces, format, cards))
    }

    /** Fewer than five playable cards is not a deck, and is not pretended to be one. */
    @Test
    fun aBareCollectionCannotBeBuiltInto() {
        val bare = spread().take(HAND_SIZE - 1)
            .fold(GameSave.new("bare", createdAt = NOW)) { save, id -> save.withCard(id) }
        assertNull(BotDecks.decking(bare, format, cards))
    }

    // ---- Choosing between them ---------------------------------------------

    /**
     * **The pair this file exists for.**
     *
     * The same profile and the same three decks; only the rule differs, and the deck that comes
     * out has to differ with it. Asserted as one test rather than two because the claim is the
     * *difference* — two tests could both pass against a bot that always brought slot 1.
     */
    @Test
    fun theTypedDeckIsBroughtToAscensionAndTheMixedOneToDescension() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))

        val ascending = BotDecks.deckFor(save, format, cards, rules(TypeRule.ASCENSION))
        val descending = BotDecks.deckFor(save, format, cards, rules(TypeRule.DESCENSION))

        assertTrue(
            concentration(hand(save, ascending)) >= concentration(hand(save, descending)),
            "ascension compounds a type, so it wants the concentrated hand",
        )
        assertTrue(
            variety(hand(save, descending)) >= variety(hand(save, ascending)),
            "descension punishes a type, so it wants the varied hand",
        )
        assertNotEquals(
            ascending,
            descending,
            "one deck for both rules is the rule not being played",
        )
    }

    /** A match that says nothing about types gets the strongest hand. */
    @Test
    fun aPlainMatchGetsTheStrongestDeck() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))
        val chosen = BotDecks.deckFor(save, format, cards, GameRules())

        assertEquals(strength(hand(save, POWER_SLOT)), strength(hand(save, chosen)))
    }

    /**
     * Elemental is answered with the strongest hand, and that is a decision rather than a gap.
     *
     * Its modifier belongs to the **cell** and the elements are drawn when the match is dealt, so
     * there is no hand to prepare: a deck chosen for it would be a guess at a board nobody has
     * seen. Pinned so that a future change to it is deliberate.
     */
    @Test
    fun elementalGetsTheStrongestDeckToo() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))

        assertEquals(
            BotDecks.deckFor(save, format, cards, GameRules()),
            BotDecks.deckFor(save, format, cards, rules(TypeRule.ELEMENTAL)),
        )
    }

    /**
     * **Under Random there is no choice to make, and none is made.**
     *
     * The hand is spliced from the collection and the chosen slot is ignored entirely — see
     * `PvpReferee.handFor`. Naming one would be theatre; `BotBrain.selling` is what a bot actually
     * does about this rule.
     */
    @Test
    fun randomIsAnsweredWithNoDeckAtAll() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))
        assertEquals(ANY_DECK, BotDecks.deckFor(save, format, cards, GameRules(random = true)))
    }

    /** A profile with nothing fieldable names no slot rather than naming a bad one. */
    @Test
    fun aProfileWithNoPlayableDeckNamesNoSlot() {
        val bare = GameSave.new("bare", createdAt = NOW)
        assertEquals(ANY_DECK, BotDecks.deckFor(bare, format, cards, GameRules()))
    }

    // ---- Fixtures ----------------------------------------------------------

    private fun admitted(): List<Card> = cards.admittedBy(format)

    /** The format ordered by resale worth, whose head is all five-stars. */
    private fun worthOrdered(): List<Int> = admitted()
        .sortedWith(compareByDescending<Card> { CardValue.worthOf(it) }.thenBy { it.id })
        .map { it.id }

    /** The format sampled across that ordering, so the sample spans the ranks and the types. */
    private fun spread(): List<Int> = worthOrdered().filterIndexed { index, _ -> index % STEP == 0 }

    /** A profile wide enough that all three shapes are buildable, and holding no deck. */
    private fun collector(): GameSave = spread().take(COLLECTION)
        .fold(GameSave.new("collector", createdAt = NOW)) { save, id -> save.withCard(id) }

    private fun hand(save: GameSave, slot: Int): List<Card> =
        assertNotNull(save.decks.getOrNull(slot)).cards.mapNotNull { cards.byId[it] }

    private fun rules(type: TypeRule) = GameRules(typeRule = type)

    private fun strength(hand: List<Card>): Int = hand.sumOf { it.total }

    /** The largest group sharing one type — untyped cards compound nothing, so they are ignored. */
    private fun concentration(hand: List<Card>): Int =
        hand.mapNotNull { it.type }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0

    private fun variety(hand: List<Card>): Int = hand.map { it.type }.distinct().size

    private companion object {
        const val FORMAT = "ff14-standard"
        const val NOW = 1_800_000_000_000L

        /** The slots `BotDecks` keeps, which are private there and are what this file is about. */
        const val POWER_SLOT = 0
        const val TYPED_SLOT = 1
        const val MIXED_SLOT = 2
        const val SLOTS = 3

        /** Wide enough for three distinct shapes, sampled every [STEP] of the worth ordering. */
        const val COLLECTION = 40
        const val STEP = 7
    }
}
