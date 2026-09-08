package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardValue
import com.tripletriad.data.Format
import com.tripletriad.data.Inventory
import com.tripletriad.data.ItemUse
import com.tripletriad.data.ShopCatalog
import com.tripletriad.model.BoosterItem
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchAiOptions
import com.tripletriad.model.MatchSearch
import com.tripletriad.model.Npc
import com.tripletriad.protocol.PvpStakePolicy
import kotlin.random.Random

/**
 * What a bot decides, with nothing to decide it against.
 *
 * ### Why every function here is pure
 *
 * The director owns the stores, the referees and the clock; this owns the judgement. Splitting them
 * on that line is what makes a bot's behaviour testable at all — a deck rebuild or an opponent
 * choice is a function of a profile and a catalogue, and asserting on one should not need a
 * Postgres, a match or a referee.
 *
 * It is the same split `PvpReferee` makes against its routes, one layer further in.
 *
 * ### None of this is the rules
 *
 * Nothing here computes what a capture does, what a pack contains or what a win pays: [placement]
 * defers to `MatchSearch`, [shopping] to `ShopCatalog` and `Inventory`, and [selling] to
 * `CardValue`. [BotDecks] is the same object split along the one seam wide enough to be worth a
 * file: everything about which five cards a bot brings.
 * A bot is a *player*, and a player does not get its own copy of the engine — see the top of
 * `CLAUDE.md`, which is about the server and is no less true of the accounts it plays itself.
 */
object BotBrain {

    /**
     * The card and the square, or null when it is not this side's move.
     *
     * ### It is handed a position, not a state
     *
     * [MatchPosition] carries the visibility alongside the board, and the visibility is the whole
     * of what keeps this honest: `MatchSearch` replaces every opponent card the rules do not
     * reveal before it expands a node, so a bot under Three Open searches the two cards it can see
     * and a substitute for the rest. Handing it `MatchPosition.state` and `HandVisibility.HIDDEN`
     * would be a different — and worse — bot; handing it the state alone and letting it read both
     * hands is the one thing `MatchSearch` is written not to allow.
     *
     * This is the same call `PveReferee.opponentMove` makes for the NPC, with the side flipped.
     * That it *is* the same call is the point: a bot plays with the opponent engine, at whichever
     * band its row names.
     */
    fun placement(
        at: MatchPosition,
        side: CardColor,
        options: MatchAiOptions,
        random: Random,
    ): BotPlacement? {
        val onMove = at.state.takeIf { it.currentPlayer == side } ?: return null
        val chosen = MatchSearch(options).choose(
            state = onMove,
            visible = at.visibilityFor(side),
            random = random,
        ) ?: return null

        // The search answers with a card; the wire wants the slot it sits in. A card the hand does
        // not hold cannot happen — the search draws its candidates from this very hand — so the
        // guard is against a future where it can, not against today.
        val slot = onMove.currentHand.indexOfFirst { it.id == chosen.card.id }
        return slot.takeIf { it >= 0 }?.let { BotPlacement(it, chosen.position) }
    }

    /**
     * Who to play next, from the opponents the profile has actually unlocked.
     *
     * ### Near the top of what is open, not at the top of it
     *
     * The hardest available opponent pays the most, so a bot that always took it would be the
     * right *earner* and the wrong *instrument*: the roster's difficulty curve is the thing being
     * measured, and a measurement that only ever samples its last rung says nothing about the
     * rest. So the choice is uniform over the hardest [SAMPLED_OPPONENTS] the profile can face,
     * which keeps the income near the ceiling while spreading the sample across a band of the
     * ladder.
     *
     * [available] arrives sorted by difficulty ascending — `NpcCatalog.available` — so the tail is
     * the hard end.
     */
    fun opponent(available: List<Npc>, random: Random): Npc? {
        if (available.isEmpty()) return null
        val band = available.takeLast(SAMPLED_OPPONENTS)
        return band[random.nextInt(band.size)]
    }

    /**
     * The profile after one round of looking after itself, or null when nothing changed.
     *
     * Four steps, and the order is the one a player takes:
     *
     * 1. **buy a pack** with what the matches paid ([shopping]);
     * 2. **use what is in the bag** — the pack's cards, the opponent's drops, and the XP and MGP
     *    potions, which is the whole of "use your boons" ([emptying]);
     * 3. **sell the surplus commons** ([selling]) — money, and a better hand under Random;
     * 4. **rebuild the decks** around what is left ([decking]).
     *
     * Each is separately null-able and the answer is null only when all four did nothing, because
     * the director schedules on it — a bot that reported having done something every pass would
     * never stand still.
     *
     * Selling comes **after** the bag is emptied and **before** the decks are rebuilt, and both
     * orderings are load-bearing: a pack's commons are in the bag until step 2, and a deck built
     * around a card sold in step 3 would be unaffordable the moment it was written.
     *
     * @return the changed profile, or null when this bot had nothing to do.
     */
    fun developing(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        reserve: Int,
        random: Random,
    ): GameSave? {
        val shopped = shopping(save, format, cards, reserve, random)
        val emptied = emptying(shopped ?: save, random)
        val sold = selling(emptied ?: shopped ?: save, cards)
        val built = BotDecks.decking(sold ?: emptied ?: shopped ?: save, format, cards)
        return built ?: sold ?: emptied ?: shopped
    }

    /**
     * The profile after one shopping trip, or null when it did not make one.
     *
     * ### A pack, and only when the purse can stand it
     *
     * A bot buys the most expensive booster it can afford **while keeping [reserve] back**, which
     * is what stops the collection being funded out of the money a wager would need.
     *
     * The pack is opened in the same step. Leaving it in the bag would be the more player-like
     * behaviour and a worse instrument: an unopened pack is MGP that has left the economy without
     * having become cards yet, and the collection curve is the thing being measured.
     *
     * Note what opening it does **not** do. `Inventory.use` on a booster puts `CardItem`s in the
     * bag rather than cards in the collection — the pack is opened, the cards are not yet taken —
     * so [emptying] is the half that finishes the job. Getting that wrong is invisible in the purse
     * and obvious in the collection, which is exactly the sort of thing a bot would do for months.
     *
     * Both halves are `:core`'s: `ShopCatalog.buy` prices it and refuses an unaffordable one by
     * returning the profile unchanged, and `Inventory.use` rolls the contents on **this** server's
     * generator, exactly as `POST /me/bag/use` does.
     */
    // ReturnCount: nothing affordable, a purchase that did not take, and the profile that did.
    @Suppress("ReturnCount")
    fun shopping(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        reserve: Int,
        random: Random,
    ): GameSave? {
        val offer = packsFor(save, format, cards, reserve).maxByOrNull { it.price } ?: return null

        val bought = ShopCatalog.buy(save, offer)
        // Unchanged means the purse could not cover it after all — the filter above and this check
        // read the same number, so it cannot happen today, and returning null is the honest answer
        // if a future price ever moves between them.
        if (bought.mgp == save.mgp) return null

        return Inventory.use(bought, offer.item, random).save
    }

    /** The packs this purse can reach without spending its [reserve]. */
    private fun packsFor(save: GameSave, format: Format, cards: CardCatalog, reserve: Int) =
        ShopCatalog.offers(format, cards.byId)
            .filter { it.item is BoosterItem && it.price <= save.mgp - reserve }

    /**
     * The profile with its bag used up, or null when there was nothing in it to use.
     *
     * ### This is not tidiness, it is where a collection comes from
     *
     * A bot's bag fills from two directions and neither of them is the collection: an opened pack
     * leaves `CardItem`s in it, and `Npc.rollRewards` drops cards and potions into it after every
     * match. Both are things a player taps to *take*, and a bot that never tapped would grind for
     * weeks with a growing bag and the same nine cards it started with.
     *
     * The loop re-reads the bag each time round rather than folding over a snapshot, because using
     * one item can add another: a booster resolves into card items, and those are what the next
     * pass takes. [MAX_USES] bounds it — a bag cannot legitimately hold more than that, and a
     * bounded loop is what stops a bag that somehow refills from wedging the director.
     *
     * `MiscItem` is the one thing that is never `useable`, so it is skipped by the filter rather
     * than by a special case, and a bag holding only those correctly reports nothing to do.
     */
    fun emptying(save: GameSave, random: Random): GameSave? {
        var profile = save
        var used = 0
        while (used < MAX_USES) {
            // One at a time: `Inventory.remove` takes its count separately and `stacksWith`
            // normalises the stack to one, so `withStack(1)` is how you say "this kind of thing".
            // A `NotUseable` answer ends the pass rather than skipping the item, because the only
            // way to reach it here is a bag `Inventory` and this file disagree about — and looping
            // past that would be looping forever.
            val outcome = profile.bag.firstOrNull { it.useable }
                ?.let { Inventory.use(profile, it.withStack(1), random) }
                ?.takeUnless { it is ItemUse.NotUseable }
                ?: break
            profile = outcome.save
            used++
        }
        return profile.takeIf { used > 0 }
    }

    /**
     * The profile with its surplus low cards sold, or null when it had none to sell.
     *
     * ### This is not tidying up, it is the Random rule
     *
     * `GameSave.ownedCardIds` returns the collection **one entry per copy**, and
     * `MatchPreparation.randomHand` shuffles exactly that list. So a fourth copy of a one-star is
     * four tickets in a lottery the bot does not want to win: under `RULE_RANDOM` — which a table
     * may declare and a roulette may draw — the hand comes from the collection rather than from a
     * deck, and a collection padded with commons deals a hand of commons.
     *
     * A player learns this and thins their collection. A bot that hoarded everything would get
     * quietly worse at every Random match it played, in a way that would look like the AI being
     * bad rather than the collection being bad.
     *
     * ### What it will not sell
     *
     * **Anything a deck is built on.** `GameSave.spareCopiesOf` is the whole guard and it is
     * `:core`'s: it counts the copies no saved deck has promised, the maximum over decks rather
     * than the sum. Selling below that leaves a deck `Deck.isAffordable` refuses, which the player
     * — here, the bot — would meet as a match that will not deal.
     *
     * **Anything above [SELLABLE_RARITY].** A spare four-star is a card to build a second deck
     * around, not stock to clear. And one spare of everything is kept ([KEPT_SPARES]), because the
     * copy that lets a deck exist at all is the one after the copy a deck already names.
     *
     * The price is `CardValue.resaleOf`, the same number `POST /me/cards/sell` pays a person.
     */
    fun selling(save: GameSave, cards: CardCatalog): GameSave? {
        val surplus = save.cards.keys.mapNotNull { id ->
            val card = cards.byId[id] ?: return@mapNotNull null
            val spare = save.spareCopiesOf(id) - KEPT_SPARES
            if (card.rarity > SELLABLE_RARITY || spare <= 0) null else id to spare
        }
        if (surplus.isEmpty()) return null

        return surplus.fold(save) { profile, (id, copies) ->
            profile.withoutCard(id, copies)
                .withMgp(CardValue.resaleOf(id, cards.byId) * copies)
        }
    }

    /**
     * The table this bot should sit down at, or null when none of them is its business.
     *
     * ### The wait is the whole feature
     *
     * A bot joining the moment a table opens takes the match a person was about to take. So a table
     * is only a bot's business once it has been standing since [staleBefore] with nobody in it —
     * which is the deployment's `TTO_BOTS_TABLE_WAIT_SECONDS`, and is what "if nobody joins
     * quickly" means in code.
     *
     * ### What it refuses, and why each refusal is here rather than in the referee
     *
     * `PvpReferee.joinTable` re-checks the purse and the ceiling inside its own transaction and
     * would refuse an ineligible join anyway. Checking here as well is not belt and braces: a
     * refused join is a wasted pass and a table left standing, where declining to try leaves the
     * table for somebody who can take it.
     *
     * The **wager** refusal is not a duplicate of anything. With [wagers] false a bot only sits
     * down at a table that risks nothing at all — `PvpStake.isFree`, so neither MGP nor a trade
     * rule — because a bot that stakes moves real value into and out of the players' economy, and
     * that is a decision to be taken deliberately rather than inherited from a lobby listing.
     */
    // Six parameters, and each is a fact this cannot look up: the lobby, who is asking, what they
    // are holding, the deployment's ceiling, its wager policy, and the clock. A holder would name
    // the same six one indirection away.
    @Suppress("LongParameterList")
    fun joinable(
        tables: List<PvpTableRow>,
        botId: Long,
        save: GameSave,
        stakes: PvpStakePolicy,
        wagers: Boolean,
        staleBefore: Long,
    ): PvpTableRow? = tables.firstOrNull { table ->
        table.hostAccount != botId &&
            table.matchId == null &&
            table.openedAt <= staleBefore &&
            (if (wagers) affordable(table, save, stakes) else table.stake.isFree)
    }

    private fun affordable(table: PvpTableRow, save: GameSave, stakes: PvpStakePolicy): Boolean =
        stakes.allows(save, table.stake) && save.mgp >= table.stake.mgp

    /**
     * How wide a slice of the ladder [opponent] samples from.
     *
     * Four is enough that a roster of bots does not all queue against one character, and narrow
     * enough that the sample stays near the difficulty the band is meant to be measured at.
     */
    private const val SAMPLED_OPPONENTS = 4

    /**
     * The rank at and below which a spare copy is stock to clear rather than a card to keep.
     *
     * Two stars. A spare three-star is the beginning of a second deck; a fourth one-star is
     * four tickets in the Random draw — see [selling].
     */
    private const val SELLABLE_RARITY = 2

    /** One spare of everything survives, because the copy after a deck's copy is the useful one. */
    private const val KEPT_SPARES = 1

    /**
     * A ceiling on one bag-emptying pass.
     *
     * Generous — a bot's bag holds a match's drops and a pack's eleven cards, not hundreds — and
     * present because the loop's termination depends on `Inventory.use` shrinking the bag, which
     * is a property of `:core` rather than of this file.
     */
    private const val MAX_USES = 200
}

/**
 * A card in a hand and a square on the board — the two numbers `PveMove` and `PvpMove` both are.
 *
 * Its own type rather than either of theirs, because [BotBrain] does not know which kind of match
 * it is playing and should not have to: the director names the wire type. The same reasoning
 * `MatchPosition.advanced` gives for taking two integers instead of a move.
 */
data class BotPlacement(val handIndex: Int, val position: Int)
