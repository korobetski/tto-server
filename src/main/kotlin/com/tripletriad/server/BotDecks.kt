package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardValue
import com.tripletriad.data.Format
import com.tripletriad.data.PveMatches
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

/**
 * Which five cards a bot brings, and which of its decks it brings them in.
 *
 * ### Why this is not in [BotBrain]
 *
 * Because it is the one part of a bot's judgement with a shape of its own. Everything next door is
 * a decision taken once against a profile — buy this, use that, sit down there. This is a small
 * optimisation problem asked once per shape over the same collection, and answered by choosing an
 * **ordering** and letting `DeckLimits.firstLegalHand` cut it: the caps are greedy-exact, so there
 * is no search here and there does not need to be one.
 *
 * ### The rule it is really about
 *
 * `ASCENSION` and `DESCENSION` are the same tally with opposite signs — each card of a type placed
 * moves every card of that type by one — and they want opposite hands. A bot that brought its
 * strongest five to both would be handing back the whole of the rule in one of the two. See
 * [decking] for the shapes and [deckFor] for how one is chosen.
 *
 * Nothing here decides what a capture does or what a deck may hold: `DeckLimits` owns the caps,
 * `PveMatches.playableDecks` owns what counts as fieldable, and `Card.total` is the printed
 * strength. This chooses among what they allow.
 */
// TooManyFunctions counts the three questions this object answers — which decks, which one now,
// would a card be fielded — plus the one-line scores each of them ranks hands by. Folding a score
// into its caller would hide the one thing a reader tuning the bots wants to find by name.
@Suppress("TooManyFunctions")
object BotDecks {

    /**
     * The profile with its decks rebuilt from what it owns, or null when none of them moved.
     *
     * ### One deck per shape a match might ask for, and several of the typed one
     *
     * A bot keeps a deck per shape it might be asked for, and [deckFor] chooses between them when a
     * match is proposed:
     *
     * - **[POWER_SLOT]** — the strongest legal five. The yardstick every other deck is measured
     *   against in a match that says nothing about types.
     * - **[MIXED_SLOT]** — the five spread over as many types as possible. `DESCENSION` punishes a
     *   type for every card of it on the board, so the hand that spreads is the one it cannot
     *   drag down.
     * - **[TYPED_SLOTS]** — one deck per card type the collection can concentrate, up to
     *   [TYPED_DECKS] of them, the most concentrated first. Under `ASCENSION` every card of a type
     *   on the board raises every card of that type by one, so a mono-type hand compounds: the
     *   third Primal played attacks at +2.
     *
     * The typed decks are several rather than one because one was the whole of the roster's
     * repertoire: every bot answered `ASCENSION` with the same type and every plain match with the
     * same five, which is a lobby of mirror matches and a statistic about one hand per bot. Each
     * type a bot *can* concentrate is a deck it can field, and [deckFor] draws among the ones that
     * are good enough rather than always naming the single best.
     *
     * A typed deck needs [MIN_CONCENTRATION] cards of its type — a majority of the hand — or it is
     * not built: two of a type compounds once, which is a strongest-five deck with a worse name.
     * A typed slot with nothing left to hold is **cleared** rather than left standing, because a
     * deck is what `GameSave.spareCopiesOf` reserves: a stale one would keep cards out of the
     * shop and the auction house for a shape the bot no longer plays.
     *
     * `ELEMENTAL` gets no deck of its own on purpose. Its modifier is a property of the **cell**,
     * drawn when the match is dealt, so there is nothing to prepare against — a hand chosen for it
     * would be a guess at a board nobody has seen.
     *
     * ### Every hand goes through `DeckLimits`
     *
     * The caps admit two cards of four stars or more, one five-star at most, and each card once,
     * so "the best five" is not the five best.
     * `DeckLimits.firstLegalHand` is greedy over the order it is given and exact — a capped rank is
     * only ever refused, never required — which is what makes each of these a matter of choosing
     * an *ordering* rather than writing a search.
     *
     * Slots are written with `GameSave.withDeck`, which pads the list rather than appending, so a
     * profile that arrives with one deck ends with its decks at fixed indices.
     */
    fun decking(save: GameSave, format: Format, cards: CardCatalog): GameSave? {
        val wanted = hands(save, format, cards)

        return (listOf(POWER_SLOT, MIXED_SLOT) + TYPED_SLOTS).fold(save) { profile, slot ->
            val ids = wanted[slot]?.map { it.id }
            val standing = profile.decks.getOrNull(slot)
            when {
                // A strongest or a mixed hand that cannot be built leaves the old deck alone:
                // `deckFor` scores what is playable, so a deck that fell apart is skipped anyway.
                ids == null && slot !in TYPED_SLOTS -> profile
                ids == null && (standing == null || standing.cards.isEmpty()) -> profile
                ids == null -> profile.withDeck(slot, Deck(name = "", cards = emptyList()))
                standing?.cards == ids -> profile
                else -> profile.withDeck(slot, Deck(name = nameOf(slot, wanted), cards = ids))
            }
        }.takeIf { it.decks != save.decks }
    }

    /**
     * Whether owning [cardId] would put it in one of the hands [decking] builds.
     *
     * The auction house's question — "do I need this card" — answered with the same builder that
     * would field it, so a bot never bids on a card its own decks would leave in the binder. Asked
     * of a profile that does not own the card yet; a second copy never enters a hand, since the
     * caps take each card once.
     */
    fun wouldField(save: GameSave, format: Format, cards: CardCatalog, cardId: Int): Boolean =
        hands(save.withCard(cardId), format, cards).values.any { hand ->
            hand?.any { it.id == cardId } == true
        }

    /**
     * Which deck to bring against [rules], as a slot — or [ANY_DECK] when the choice is not one.
     *
     * ### It scores the decks that exist rather than trusting the slots
     *
     * [decking] writes each shape to a fixed slot, and a slot can go stale under it: a card sold,
     * or lost to a trade, leaves a deck `PveMatches.playableDecks` will not offer. So this reads
     * that list — complete, owned, admitted by the format and inside the caps — and scores whatever
     * is in it. A bot whose typed deck has fallen apart brings its best remaining hand rather than
     * naming a slot the referee would silently ignore.
     *
     * ### Good enough is drawn from, the best is not always named
     *
     * The deck is drawn at random among those that tie the best on what the rule rewards and come
     * within [NEAR_BEST_PERCENT] of the strongest of them in printed power. Always naming the one
     * best deck made every match a bot played the same match; this keeps the choice honest — a
     * deck that gives the rule away is never in the draw — while letting a bot's typed decks see a
     * plain board too, which is also what makes the roster's numbers say something about more than
     * one hand.
     *
     * ### Random is answered with no choice at all
     *
     * Under `RULE_RANDOM` the hand is spliced from the **collection** and the chosen deck is
     * ignored entirely — `PvpReferee.handFor` and `PveRoutes.deal` both. Naming a slot would be
     * theatre. `BotBrain.selling` is what a bot actually does about Random.
     *
     * ### What it does not look at
     *
     * The opponent's cards. `npcs.json` names an opponent's fetish cards and this server holds it,
     * so a bot *could* counter-pick a hand against the five it is about to face. That is a
     * different game from the one a person is playing, and the point of these accounts is to
     * measure the game as played. The rules are public — a table states them and an opponent
     * declares them — and choosing a deck from public terms is what a person does.
     */
    fun deckFor(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        rules: GameRules,
        random: Random,
    ): Int {
        if (rules.random) return ANY_DECK

        // A plain board rewards no shape, so every deck ties on it and power alone decides —
        // scoring it by power here would leave only the strongest in the draw below.
        val score: (List<Card>) -> Int = when (rules.typeRule) {
            TypeRule.ASCENSION -> ::concentration
            TypeRule.DESCENSION -> ::variety
            TypeRule.NONE, TypeRule.ELEMENTAL -> { _ -> 0 }
        }

        val playable = PveMatches.playableDecks(save, cards, format).mapNotNull { (slot, deck) ->
            deck.cards.mapNotNull { cards.byId[it] }
                .takeIf { it.size == HAND_SIZE }
                ?.let { hand -> slot to hand }
        }
        val best = playable.maxOfOrNull { score(it.second) } ?: return ANY_DECK

        // Only the decks that give nothing away on the rule, then only the ones close enough in
        // raw power to the strongest of *those* — the tie-break the single-best choice used.
        val rewarded = playable.filter { score(it.second) == best }
        val strongest = rewarded.maxOf { power(it.second) }
        val candidates = rewarded
            .filter { power(it.second) * PERCENT >= strongest * NEAR_BEST_PERCENT }
            .map { it.first }
            .sorted()
        return candidates[random.nextInt(candidates.size)]
    }

    // ---- Building the hands -----------------------------------------------

    /**
     * Every hand [decking] would write, by slot — null where that shape cannot be built.
     *
     * One function for both [decking] and [wouldField], so "would this card be fielded" is asked
     * of exactly the builder that fields it.
     */
    private fun hands(save: GameSave, format: Format, cards: CardCatalog): Map<Int, List<Card>?> {
        val owned = save.ownedCardIds().mapNotNull { cards.byId[it] }
            .filter { format.admitsCard(it.id) }
        val typed = typedHands(owned)

        return buildMap {
            put(POWER_SLOT, strongest(owned))
            put(MIXED_SLOT, spread(owned))
            TYPED_SLOTS.forEachIndexed { rank, slot -> put(slot, typed.getOrNull(rank)) }
        }
    }

    /** The strongest legal five, which is what a match with nothing to say about types wants. */
    private fun strongest(owned: List<Card>): List<Card>? =
        DeckLimits.firstLegalHand(owned.sortedWith(BY_STRENGTH)).takeIf { it.size == HAND_SIZE }

    /**
     * The concentrated hands this collection can field, most concentrated first, at most
     * [TYPED_DECKS] of them.
     *
     * One ordering per type — that type first, everything else by strength behind it — and the
     * greedy legal cut over each. A hand is kept only when its type actually leads it by
     * [MIN_CONCENTRATION]: the cut can fill a hand mostly from the tail when a type is thin, and
     * that hand belongs to some other type's deck or to none. Two types that cut to the same five
     * are one deck.
     *
     * Ordered by concentration, then power, then the type's own order, so the slots are stable
     * for as long as the collection is — a rebuild that reshuffled them would be a write per pass.
     */
    private fun typedHands(owned: List<Card>): List<List<Card>> = CardType.entries
        .mapNotNull { type ->
            val ordered = owned.sortedWith(
                compareByDescending<Card> { it.type == type }.then(BY_STRENGTH),
            )
            DeckLimits.firstLegalHand(ordered)
                .takeIf { hand ->
                    hand.size == HAND_SIZE && hand.count { it.type == type } >= MIN_CONCENTRATION
                }
        }
        .distinctBy { hand -> hand.map { it.id }.sorted() }
        .sortedWith(
            compareByDescending<List<Card>> { concentration(it) }.thenByDescending { power(it) },
        )
        .take(TYPED_DECKS)

    /**
     * The legal five spread over as many types as possible.
     *
     * Built by dealing round-robin from the type groups — the best of each type, then the second
     * best of each, and so on — so the greedy cut takes one of everything before it takes two of
     * anything. Untyped cards form a group of their own, which is right under `DESCENSION`: a card
     * with no type is never in the tally at all, so it is the one card that cannot be dragged down.
     */
    private fun spread(owned: List<Card>): List<Card>? {
        val groups = owned.sortedWith(BY_STRENGTH).groupBy { it.type }.values.toList()
        if (groups.isEmpty()) return null

        val depth = groups.maxOf { it.size }
        val ordered = (0 until depth).flatMap { rank -> groups.mapNotNull { it.getOrNull(rank) } }
        return DeckLimits.firstLegalHand(ordered).takeIf { it.size == HAND_SIZE }
    }

    // ---- Scoring a hand ---------------------------------------------------

    /** The printed strength of a hand — four sides a card, and the plain answer to "how good". */
    private fun power(hand: List<Card>): Int = hand.sumOf { it.total }

    /**
     * The largest number of cards sharing one type, which is what `ASCENSION` compounds.
     *
     * Untyped cards are not counted: `AscensionTally.record` ignores a null type, so five untyped
     * cards compound nothing at all and must not score as a perfect concentration.
     */
    private fun concentration(hand: List<Card>): Int =
        hand.mapNotNull { it.type }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0

    /**
     * How many distinct types a hand spreads over, which is what `DESCENSION` rewards.
     *
     * Untyped cards count as one group here rather than as none, and the asymmetry with
     * [concentration] is deliberate: an untyped card cannot be lowered, so it is genuinely part of
     * the spread even though it is not part of any tally.
     */
    private fun variety(hand: List<Card>): Int = hand.map { it.type }.distinct().size

    /**
     * What a deck is called: its shape, and for a typed one the type it is built on.
     *
     * Never shown to anybody. It is there for whoever reads a bot's profile out of the database and
     * wants to know why it holds the decks it does — and which types it has been playing.
     */
    private fun nameOf(slot: Int, wanted: Map<Int, List<Card>?>): String = when (slot) {
        POWER_SLOT -> "Bot"
        MIXED_SLOT -> "Bot mixed"
        else -> wanted[slot]
            ?.mapNotNull { it.type }
            ?.groupingBy { it }
            ?.eachCount()
            ?.maxByOrNull { it.value }
            ?.let { "Bot ${it.key.name.lowercase()}" }
            ?: "Bot typed"
    }

    /**
     * The slots [decking] keeps.
     *
     * Fixed indices rather than appended decks, so a rebuild replaces a shape instead of growing
     * the list. The typed decks take every slot `GameSave.MAX_DECKS` leaves after the two fixed
     * shapes — six of them against twelve card types, which is as many as a collection is ever
     * likely to concentrate to a majority at once.
     */
    private const val POWER_SLOT = 0
    private const val MIXED_SLOT = 1
    private const val FIRST_TYPED_SLOT = 2
    private val TYPED_SLOTS = (FIRST_TYPED_SLOT until GameSave.MAX_DECKS).toList()
    private val TYPED_DECKS = TYPED_SLOTS.size

    /** Three of a type, a majority of the hand: what makes a typed deck more than a label. */
    private const val MIN_CONCENTRATION = 3

    /**
     * How close in printed power a deck must come to the best one to be drawn from.
     *
     * Ninety percent of the summed sides: close enough that the bot is not giving the match away,
     * wide enough that a typed deck a few points short of the strongest five still gets played —
     * a bot that only ever played its strongest five was a bot that only ever played one hand.
     * The first number to revisit if the roster's win rate against NPCs falls when this ships —
     * that is what the per-band win gauge is for.
     */
    private const val NEAR_BEST_PERCENT = 90
    private const val PERCENT = 100

    /**
     * How a hand is ordered when nothing else decides it.
     *
     * Printed strength first — `Card.total`, the sum of the four sides — because that is what
     * decides a capture. Worth second, so two cards that fight alike are separated by the rank the
     * shop and the auction house price them at, and the id last so the answer is stable: a deck
     * that reshuffles itself every pass is a write per pass forever.
     */
    private val BY_STRENGTH = compareByDescending<Card> { it.total }
        .thenByDescending { CardValue.worthOf(it) }
        .thenBy { it.id }
}
