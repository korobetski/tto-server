package com.tripletriad.server

import com.tripletriad.data.CardValue
import com.tripletriad.model.Card
import com.tripletriad.model.CardType
import com.tripletriad.model.Deck
import com.tripletriad.model.DeckLimits
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.TypeRule
import com.tripletriad.protocol.ANY_DECK
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * ### Draws are asserted over many seeds, never on one
 *
 * `BotDecks.deckFor` draws among the decks good enough for the rule, so one seed proves only that
 * one draw was good. Each choosing test asks [DRAWS] generators and asserts on every answer — a
 * claim about the whole draw rather than about whichever deck a seed happened to land on.
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

    // ---- Building them -----------------------------------------------------

    /** Every deck built is a legal five, drawn from what the profile actually owns. */
    @Test
    fun everyDeckBuiltIsLegal() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))
        val built = save.decks.filter { it.cards.isNotEmpty() }

        assertTrue(
            built.size >= FIRST_TYPED_SLOT + 1,
            "the strongest, the mixed and at least one typed deck — widen the collection if not",
        )
        built.forEach { deck ->
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
     * The fixed shapes are each the best at their own job.
     *
     * Asserted as an ordering rather than on exact ids: the first typed deck must be **more**
     * concentrated than the strongest one and the mixed deck must be **more** varied, which is the
     * claim. Pinning the ids would pin the catalogue instead.
     */
    @Test
    fun eachDeckIsTheBestAtItsOwnJob() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))
        val power = hand(save, POWER_SLOT)
        val typed = hand(save, FIRST_TYPED_SLOT)
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
            strength(power) >= strength(typed) && strength(power) >= strength(mixed),
            "the strongest deck is the strongest one, or it is misnamed",
        )
    }

    /**
     * **The typed decks are several, each a majority of one type, and no two alike.**
     *
     * The variety this exists for: one typed deck made every `ASCENSION` match a bot played the
     * same match. Ordered most concentrated first, so the first typed slot is the one a single
     * typed deck used to be.
     */
    @Test
    fun theTypedDecksAreSeveralAndDistinct() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))
        val typed = (FIRST_TYPED_SLOT until GameSave.MAX_DECKS)
            .mapNotNull { save.decks.getOrNull(it) }
            .filter { it.cards.isNotEmpty() }
            .map { deck -> deck.cards.mapNotNull { cards.byId[it] } }

        assertTrue(typed.size > 1, "one typed deck is the repertoire this change widened")
        typed.forEach { hand ->
            assertTrue(
                concentration(hand) >= MIN_CONCENTRATION,
                "a typed deck is a majority of its type, or it is the strongest five misnamed",
            )
        }
        assertEquals(
            typed.size,
            typed.map { hand -> hand.map { it.id }.sorted() }.distinct().size,
            "two slots holding the same five is one deck written twice",
        )
        assertEquals(
            typed.map { concentration(it) }.sortedDescending(),
            typed.map { concentration(it) },
            "the most concentrated first",
        )
    }

    /**
     * A typed slot the collection can no longer fill is cleared, not left standing.
     *
     * A deck is what `GameSave.spareCopiesOf` reserves, so a stale one would keep its cards out of
     * the counter and the auction house for a shape the bot no longer builds.
     */
    @Test
    fun aTypedSlotWithNothingToHoldIsCleared() {
        val built = assertNotNull(BotDecks.decking(collector(), format, cards))
        val last = GameSave.MAX_DECKS - 1
        assertTrue(
            built.decks.getOrNull(last)?.cards.isNullOrEmpty(),
            "the fixture fills every typed slot, so there is no stale one to clear",
        )
        val stale = built.withDeck(
            last,
            Deck(
                name = "stale",
                cards = hand(built, POWER_SLOT).map {
                    it.id
                },
            ),
        )

        val rebuilt = assertNotNull(BotDecks.decking(stale, format, cards))

        assertTrue(
            rebuilt.decks[last].cards.isEmpty(),
            "a slot with no shape behind it holds nothing",
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
     * `DeckLimits` admits two cards of four stars or more, one five-star at most, so a profile
     * holding twenty of the most valuable cards in the format can legally field two of them. Null
     * is the only honest answer — there is no third card to reach for — and the caller keeps
     * whatever decks it had.
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
     * The same profile and the same decks; only the rule differs, and every deck drawn has to be
     * the best at what that rule rewards. Asserted as one test rather than two because the claim
     * is the *difference* — two tests could both pass against a bot that always brought one slot.
     */
    @Test
    fun theTypedDeckIsBroughtToAscensionAndTheMixedOneToDescension() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))
        val decks = playable(save)

        val ascending = draws(save, rules(TypeRule.ASCENSION))
        val descending = draws(save, rules(TypeRule.DESCENSION))

        ascending.forEach { slot ->
            assertEquals(
                decks.maxOf { concentration(it) },
                concentration(hand(save, slot)),
                "ascension compounds a type, so it wants the most concentrated hand",
            )
        }
        descending.forEach { slot ->
            assertEquals(
                decks.maxOf { variety(it) },
                variety(hand(save, slot)),
                "descension punishes a type, so it wants the most varied hand",
            )
        }
        assertTrue(
            ascending.intersect(descending).isEmpty(),
            "one deck for both rules is the rule not being played",
        )
    }

    /**
     * A match that says nothing about types gets a hand close to the strongest — never far below.
     *
     * Ninety percent is `BotDecks.NEAR_BEST_PERCENT`, private there and pinned here.
     */
    @Test
    fun aPlainMatchGetsAHandNearTheStrongest() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))
        val strongest = strength(hand(save, POWER_SLOT))

        draws(save, GameRules()).forEach { slot ->
            assertTrue(
                strength(hand(save, slot)) * PERCENT >= strongest * NEAR_BEST_PERCENT,
                "slot $slot is too far below the strongest to be brought to a plain match",
            )
        }
    }

    /**
     * The draw is a draw: a plain match is not always met with the same deck.
     *
     * The reason `deckFor` takes a generator at all. Asserted on a collection built so that a
     * typed deck comes within reach of the strongest, which is when it should be.
     */
    @Test
    fun aPlainMatchIsNotAlwaysTheSameDeck() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))

        assertTrue(
            draws(save, GameRules()).distinct().size > 1,
            "every plain match against the same deck is the repertoire this change widened",
        )
    }

    /**
     * Elemental is answered like a plain match, and that is a decision rather than a gap.
     *
     * Its modifier belongs to the **cell** and the elements are drawn when the match is dealt, so
     * there is no hand to prepare: a deck chosen for it would be a guess at a board nobody has
     * seen. Pinned so that a future change to it is deliberate.
     */
    @Test
    fun elementalIsAnsweredLikeAPlainMatch() {
        val save = assertNotNull(BotDecks.decking(collector(), format, cards))

        assertEquals(draws(save, GameRules()), draws(save, rules(TypeRule.ELEMENTAL)))
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
        assertEquals(
            setOf(ANY_DECK),
            draws(save, GameRules(random = true)).toSet(),
        )
    }

    /** A profile with nothing fieldable names no slot rather than naming a bad one. */
    @Test
    fun aProfileWithNoPlayableDeckNamesNoSlot() {
        val bare = GameSave.new("bare", createdAt = NOW)
        assertEquals(ANY_DECK, BotDecks.deckFor(bare, format, cards, GameRules(), Random(SEED)))
    }

    // ---- What it would field -----------------------------------------------

    /**
     * A card stronger than anything the profile owns is one it would field: the strongest hand's
     * ordering starts from it, and the legal cut never refuses the first card it is given.
     *
     * This is the auction house's question — `BotAuctions.bidding` bids on nothing else.
     */
    @Test
    fun aCardStrongerThanTheCollectionWouldBeFielded() {
        val save = collector()
        val best = save.ownedCardIds().mapNotNull { cards.byId[it] }.maxOf { it.total }
        val stronger = assertNotNull(
            admitted().firstOrNull { save.copiesOf(it.id) == 0 && it.total > best },
            "the fixture already owns the strongest card in the format",
        )

        assertTrue(BotDecks.wouldField(save, format, cards, stronger.id))
    }

    /**
     * A weak card of a type the profile already holds plenty of would not be fielded.
     *
     * Five stronger cards of its type stand ahead of it in the typed hand, the mixed hand takes
     * that type's best, and the strongest hand has no room for it: a bot would buy it to leave it
     * in the binder.
     *
     * The five are three stars or fewer so the caps field all of them. Owning five of a type is not
     * enough on its own: with one of them capped out, the weak card is the fifth that makes the
     * hand mono-type — and then it *would* be fielded, rightly, under `ASCENSION`.
     */
    @Test
    fun aWeakCardOfACrowdedTypeWouldNotBeFielded() {
        val crowded = admitted().mapNotNull { it.type }.groupingBy { it }.eachCount()
            .maxWith(compareBy<Map.Entry<CardType, Int>> { it.value }.thenBy { it.key })
            .key
        val uncapped = admitted()
            .filter { it.type == crowded && it.rarity <= UNCAPPED_RARITY }
            .sortedWith(BY_STRENGTH)
        val save = uncapped.take(HAND_SIZE).fold(collector()) { profile, card ->
            profile.withCard(card.id)
        }
        val weakest = uncapped.last { save.copiesOf(it.id) == 0 }

        assertFalse(BotDecks.wouldField(save, format, cards, weakest.id))
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

    /** What [DRAWS] generators choose against [rules] — see the class KDoc. */
    private fun draws(save: GameSave, rules: GameRules): List<Int> = (0 until DRAWS).map { draw ->
        BotDecks.deckFor(save, format, cards, rules, Random(SEED + draw))
    }

    /** The hands `PveMatches.playableDecks` would offer, which is what `deckFor` scores. */
    private fun playable(save: GameSave): List<List<Card>> = save.decks
        .filter { it.cards.size == HAND_SIZE }
        .map { deck -> deck.cards.mapNotNull { cards.byId[it] } }

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
        const val MIXED_SLOT = 1
        const val FIRST_TYPED_SLOT = 2

        /** `BotDecks.MIN_CONCENTRATION` and `BotDecks.NEAR_BEST_PERCENT`, pinned. */
        const val MIN_CONCENTRATION = 3

        /** The highest rarity `DeckLimits` does not cap: five of them are always a legal hand. */
        const val UNCAPPED_RARITY = 3

        /** Strongest first, then by id so the order is total. */
        val BY_STRENGTH = compareByDescending<Card> { it.total }.thenBy { it.id }
        const val NEAR_BEST_PERCENT = 90
        const val PERCENT = 100

        /** Distinct from every other class's, per the note in `BotDirectorTest`. */
        const val SEED = 20_260_924
        const val DRAWS = 40

        /** Wide enough for three distinct shapes, sampled every [STEP] of the worth ordering. */
        const val COLLECTION = 40
        const val STEP = 7
    }
}
