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

/**
 * Which five cards a bot brings, and which of its decks it brings them in.
 *
 * ### Why this is not in [BotBrain]
 *
 * Because it is the one part of a bot's judgement with a shape of its own. Everything next door is
 * a decision taken once against a profile — buy this, use that, sit down there. This is a small
 * optimisation problem asked three times over the same collection, and answered by choosing an
 * **ordering** and letting `DeckLimits.firstLegalHand` cut it: the caps are greedy-exact, so there
 * is no search here and there does not need to be one.
 *
 * ### The rule it is really about
 *
 * `ASCENSION` and `DESCENSION` are the same tally with opposite signs — each card of a type placed
 * moves every card of that type by one — and they want opposite hands. A bot that brought its
 * strongest five to both would be handing back the whole of the rule in one of the two. See
 * [decking] for the three shapes and [deckFor] for how one is chosen.
 *
 * Nothing here decides what a capture does or what a deck may hold: `DeckLimits` owns the caps,
 * `PveMatches.playableDecks` owns what counts as fieldable, and `Card.total` is the printed
 * strength. This chooses among what they allow.
 */
object BotDecks {

    /**
     * The profile with its three decks rebuilt from what it owns, or null when none of them moved.
     *
     * ### Three decks, because three rules want different hands
     *
     * A bot keeps one deck per shape it might be asked for, and [deckFor] chooses between them
     * when a match is proposed:
     *
     * - **[POWER_SLOT]** — the strongest legal five. What every match that says nothing about
     *   types is played with.
     * - **[TYPED_SLOT]** — the five most concentrated in one card type. Under `ASCENSION` every
     *   card of a type on the board raises every card of that type by one, so a mono-type hand
     *   compounds: the third Primal played attacks at +2.
     * - **[MIXED_SLOT]** — the five spread over as many types as possible. `DESCENSION` is the
     *   same tally running the other way, and it punishes exactly the hand `ASCENSION` rewards.
     *
     * `ELEMENTAL` gets no deck of its own on purpose. Its modifier is a property of the **cell**,
     * drawn when the match is dealt, so there is nothing to prepare against — a hand chosen for it
     * would be a guess at a board nobody has seen.
     *
     * ### Every hand goes through `DeckLimits`
     *
     * The caps admit one five-star and two four-stars, so "the best five" is not the five best.
     * `DeckLimits.firstLegalHand` is greedy over the order it is given and exact — a capped rank is
     * only ever refused, never required — which is what makes each of these a matter of choosing
     * an *ordering* rather than writing a search.
     *
     * Slots are written with `GameSave.withDeck`, which pads the list rather than appending, so a
     * profile that arrives with one deck ends with three at fixed indices.
     */
    fun decking(save: GameSave, format: Format, cards: CardCatalog): GameSave? {
        val owned = save.ownedCardIds().mapNotNull { cards.byId[it] }
            .filter { format.admitsCard(it.id) }

        val wanted = mapOf(
            POWER_SLOT to strongest(owned),
            TYPED_SLOT to concentrated(owned),
            MIXED_SLOT to spread(owned),
        )

        return wanted.entries.fold(save) { profile, (slot, hand) ->
            val ids = hand?.map { it.id } ?: return@fold profile
            if (profile.decks.getOrNull(slot)?.cards == ids) {
                profile
            } else {
                profile.withDeck(slot, Deck(name = DECK_NAMES.getValue(slot), cards = ids))
            }
        }.takeIf { it.decks != save.decks }
    }

    /**
     * Which deck to bring against [rules], as a slot — or [ANY_DECK] when the choice is not one.
     *
     * ### It scores the decks that exist rather than trusting the slots
     *
     * [decking] writes each shape to a fixed slot, and a slot can go stale under it: a card sold,
     * or lost to a wager, leaves a deck `PveMatches.playableDecks` will not offer. So this reads
     * that list — complete, owned, admitted by the format and inside the caps — and scores whatever
     * is in it. A bot whose typed deck has fallen apart brings its best remaining hand rather than
     * naming a slot the referee would silently ignore.
     *
     * ### Random is answered with no choice at all
     *
     * Under `RULE_RANDOM` the hand is spliced from the **collection** and the chosen deck is
     * ignored entirely — `PvpReferee.handFor` and `PveRoutes.deal` both. Naming a slot would be
     * theatre. [selling] is what a bot actually does about Random.
     *
     * ### What it does not look at
     *
     * The opponent's cards. `npcs.json` names an opponent's fetish cards and this server holds it,
     * so a bot *could* counter-pick a hand against the five it is about to face. That is a
     * different game from the one a person is playing, and the point of these accounts is to
     * measure the game as played. The rules are public — a table states them and an opponent
     * declares them — and choosing a deck from public terms is what a person does.
     */
    fun deckFor(save: GameSave, format: Format, cards: CardCatalog, rules: GameRules): Int {
        if (rules.random) return ANY_DECK

        val playable = PveMatches.playableDecks(save, cards, format)
        if (playable.isEmpty()) return ANY_DECK

        val score: (List<Card>) -> Int = when (rules.typeRule) {
            TypeRule.ASCENSION -> ::concentration
            TypeRule.DESCENSION -> ::variety
            TypeRule.NONE, TypeRule.ELEMENTAL -> ::power
        }

        return playable
            .mapNotNull { (slot, deck) ->
                deck.cards.mapNotNull { cards.byId[it] }
                    .takeIf { it.size == HAND_SIZE }
                    ?.let { hand -> slot to hand }
            }
            // The tie-break is raw power in every case, including the two where it is not the
            // primary score: two equally typed hands should still be the stronger of the two.
            .maxWithOrNull(compareBy({ score(it.second) }, { power(it.second) }))
            ?.first
            ?: ANY_DECK
    }

    // ---- Building the three hands -----------------------------------------

    /** The strongest legal five, which is what a match with nothing to say about types wants. */
    private fun strongest(owned: List<Card>): List<Card>? =
        DeckLimits.firstLegalHand(owned.sortedWith(BY_STRENGTH)).takeIf { it.size == HAND_SIZE }

    /**
     * The legal five most concentrated in one type, or null when no five are legal at all.
     *
     * One ordering per type — that type first, everything else by strength behind it — and the
     * greedy legal cut over each. Trying every type rather than the commonest one the profile owns
     * is what makes the answer *the best* concentration rather than a plausible one: the commonest
     * type may be all commons the caps admit five of, and a smaller group of stronger cards is the
     * better hand at the same concentration.
     */
    private fun concentrated(owned: List<Card>): List<Card>? = CardType.entries
        .mapNotNull { type ->
            val ordered = owned.sortedWith(
                compareByDescending<Card> { it.type == type }.then(BY_STRENGTH),
            )
            DeckLimits.firstLegalHand(ordered).takeIf { it.size == HAND_SIZE }
        }
        .maxWithOrNull(compareBy({ concentration(it) }, { power(it) }))

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
     * The three slots [decking] keeps, and what they are called.
     *
     * Fixed indices rather than appended decks, so a rebuild replaces a shape instead of growing
     * the list — `GameSave.MAX_DECKS` is eight, and a bot that appended would fill it in an hour.
     * The names are never shown to anybody; they are there for whoever reads a bot's profile out
     * of the database and wants to know why it holds three decks.
     */
    private const val POWER_SLOT = 0
    private const val TYPED_SLOT = 1
    private const val MIXED_SLOT = 2

    private val DECK_NAMES = mapOf(
        POWER_SLOT to "Bot",
        TYPED_SLOT to "Bot ascension",
        MIXED_SLOT to "Bot descension",
    )

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
