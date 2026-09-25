package com.tripletriad.server

import com.tripletriad.data.Campaign
import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardValue
import com.tripletriad.data.Format
import com.tripletriad.data.Inventory
import com.tripletriad.data.ItemUse
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchAiOptions
import com.tripletriad.model.MatchSearch
import com.tripletriad.model.Npc
import com.tripletriad.model.TradeRule
import com.tripletriad.protocol.PvpStakePolicy
import kotlin.math.pow
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
 * defers to `MatchSearch`, [selling] to `CardValue`, [tournament] to `Campaign`. [BotDecks] and
 * [BotShopping] are the same object split along the two seams wide enough to be worth a file:
 * which five cards a bot brings, and what it buys.
 *
 * ### Who is deciding
 *
 * Every choice that has more than one good answer — which opponent, which pack, which ladder — is
 * weighed by the bot's [BotPersonality], so a roster is a set of players rather than one player
 * sampled ten times. The weighing is the bot's; the rules it is weighed inside are nobody's to
 * bend: a daring bot still only meets the opponents its profile has unlocked.
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
     * ### Drawn, and drawn differently by every bot
     *
     * The hardest available opponent pays the most, so a bot that always took it would be the
     * right *earner* and the wrong *instrument*: the roster's difficulty curve is the thing being
     * measured, and a measurement that only ever samples its last rung says nothing about the
     * rest. It was a uniform draw over the hardest four, and that made every bot of a level the
     * same bot, queueing against the same four characters. So every opponent the profile can face
     * is in the draw, weighed by what *this* bot wants out of a match — see [appeal] — and the
     * roster spreads over the ladder because its members want different things.
     *
     * @param format the format the match will be played in, which is what decides whether a card
     *   an opponent drops is one this bot could ever play.
     */
    fun opponent(
        available: List<Npc>,
        save: GameSave,
        format: Format,
        personality: BotPersonality,
        random: Random,
    ): Npc? = pickWeighted(available.map { it to appeal(it, save, format, personality) }, random)

    /**
     * How much [npc] draws this bot, as a weight. Four factors, multiplied:
     *
     * - **the challenge**, `(difficulty + 1)` raised to [DARING_POWER] times its daring. A timid
     *   bot barely tells a novice from a master; a daring one is drawn a thousand times harder to
     *   the top of the ladder than to its foot — and is paid for it, since the purse grows with
     *   the difficulty.
     * - **the loot**: one plus its greed times [LOOT_WEIGHT] times the drop rates of the cards on
     *   the opponent's table that it is missing and could play. Farming a drop is how a collection
     *   is finished, and a bot that has the card stops being drawn to whoever drops it.
     * - **the unbeaten**, [UNBEATEN_WEIGHT] times while the profile has never beaten it. A place's
     *   achievement asks for every opponent in it to be beaten once, and that achievement is what
     *   opens its tournament — so an unbeaten opponent is the way forward, for a bot as for a
     *   person.
     * - **the whim**: between a half and one and a half, the bot's own and the same every time.
     *   Two bots with the same numbers still prefer different people.
     */
    private fun appeal(
        npc: Npc,
        save: GameSave,
        format: Format,
        personality: BotPersonality,
    ): Double {
        val challenge = (npc.difficulty + 1.0).pow(DARING_POWER * personality.daring)
        val missing = npc.itemRewards
            .filter { reward ->
                val cardId = reward.cardId
                reward.type == CARD_REWARD &&
                    cardId != null &&
                    format.admitsCard(cardId) &&
                    save.copiesOf(cardId) == 0
            }
            .sumOf { it.rate }
        val loot = 1 + personality.greed * LOOT_WEIGHT * missing
        val unbeaten = if ((save.npcWins[npc.iconId] ?: 0) == 0) UNBEATEN_WEIGHT else 1.0
        return challenge * loot * unbeaten * (WHIM_FLOOR + personality.whim(npc.id))
    }

    /**
     * The tournament to enter now, or null when this bot will not enter one.
     *
     * The one it [fancies][fancied] today, once its fee leaves [reserve] untouched: a tournament
     * is a wager against the ladder, and a bot that wagered its reserve would be left with nothing
     * to bid or bet with the day it lost. Until then the bot saves for the fee — see
     * `BotDirector.developed`.
     */
    fun tournament(
        save: GameSave,
        ladders: List<Campaign>,
        day: String,
        personality: BotPersonality,
        reserve: Int,
    ): Campaign? = fancied(save, ladders, day, personality)?.takeIf { save.mgp - it.fee >= reserve }

    /**
     * The tournament this bot means to enter today, whether or not it can pay for it yet — or
     * null when it means to enter none.
     *
     * One it may enter — its place's achievement earned, today's entry not spent, no run open.
     *
     * ### Ambition decides once a day, not every pass
     *
     * Whether a bot fancies a ladder today is its whim for that ladder *and that day*, held
     * against its ambition. A roll on every pass would make ambition meaningless — a bot asked
     * every few seconds says yes before long whatever the odds — where this makes it the share of
     * days on which the bot goes in. Among the ladders it fancies today, the one it fancies most.
     *
     * ### Separate from [tournament] because the purse is not part of wanting
     *
     * A bot that only fancied the ladders it could already afford would never afford one: the shop
     * comes first in a pass, and it spends everything above the reserve on packs long before the
     * fee has built up. Asked without the purse, this is what the shop keeps the fee back for —
     * the way it keeps back the price of a single the bot is saving for.
     *
     * @param ladders the tournaments it could play: the director passes only those whose format
     *   one of its decks would field, which is also why a bot of both sets rarely enters one.
     * @param day the UTC day, `questDayOf` — the same key the entry is stamped with.
     */
    fun fancied(
        save: GameSave,
        ladders: List<Campaign>,
        day: String,
        personality: BotPersonality,
    ): Campaign? {
        if (save.campaignRun != null) return null
        return ladders
            .filter { ladder ->
                ladder.isUnlockedFor(save) &&
                    !save.hasEnteredToday(ladder.key, day) &&
                    personality.whim("$day/${ladder.key}".hashCode()) < personality.ambition
            }
            .maxByOrNull { personality.whim(it.key.hashCode()) }
    }

    /**
     * The profile after one round of looking after itself, or null when nothing changed.
     *
     * Four steps, and the order is the one a player takes:
     *
     * 1. **buy something** with what the matches paid — a card it is saving for, or a pack
     *    ([BotShopping.shopping]);
     * 2. **use what is in the bag** — the pack's cards, the opponent's drops, and the XP and MGP
     *    potions, which is the whole of "use your boons" ([emptying]);
     * 3. **sell the surplus commons** ([selling]) — money, and a better hand under Random;
     * 4. **rebuild the decks** around what is left ([BotDecks.decking]).
     *
     * Each is separately null-able and the answer is null only when all four did nothing, because
     * the director schedules on it — a bot that reported having done something every pass would
     * never stand still.
     *
     * Selling comes **after** the bag is emptied and **before** the decks are rebuilt, and both
     * orderings are load-bearing: a pack's commons are in the bag until step 2, and a deck built
     * around a card sold in step 3 would be unaffordable the moment it was written.
     *
     * @param reserve what this bot keeps out of the shop — `BotPersonality.reserveOver` the
     *   deployment's own.
     * @param auctioning whether this bot may use the auction house now — see [selling], which
     *   holds a low card back for it when it may.
     * @param earmarked what it keeps out of the shop on top of [reserve] for something the shop
     *   does not sell — today's tournament fee, see [fancied]. See `BotShopping.shopping`.
     * @return the changed profile, or null when this bot had nothing to do.
     */
    // LongParameterList: eight, and each is a separate fact the director owns — the profile, the
    // format, the catalogue, who the bot is, its reserve, the generator, the auction gate, and what
    // it is saving for today. Bundling them would be a type that exists only to be unpacked on the
    // next line.
    @Suppress("LongParameterList")
    fun developing(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        personality: BotPersonality,
        reserve: Int,
        random: Random,
        auctioning: Boolean = false,
        earmarked: Int = 0,
    ): GameSave? {
        val shopped =
            BotShopping.shopping(save, format, cards, personality, reserve, random, earmarked)
        val emptied = emptying(shopped ?: save, random)
        val sold = selling(emptied ?: shopped ?: save, cards, auctioning)
        val built = BotDecks.decking(sold ?: emptied ?: shopped ?: save, format, cards)
        return built ?: sold ?: emptied ?: shopped
    }

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
     * **Anything above [SELLABLE_RARITY].** A spare three-star or better is worth more to a person
     * than to the counter, so it is offered to them — `BotAuctions.listing` — rather than melted
     * here. And one spare of everything is kept ([KEPT_SPARES]), because the
     * copy that lets a deck exist at all is the one after the copy a deck already names.
     *
     * **One more low copy, when [auctioning].** A low card goes to the auction house too, on a
     * lot of its own, and it only can if the counter leaves one behind: this runs in the same
     * step that takes the card out of the bag, so a counter that melted every spare would melt it
     * before `BotAuctions.listing` ever saw it. So a bot that may use the auction house keeps
     * [AUCTIONED_SPARES] more low copy than one that may not, and sells the rest. That is one
     * more ticket per card in the Random draw, which is the price of the counter's forty percent
     * becoming whatever a person will pay; a bot below the auction level keeps nothing extra,
     * since it would be holding a copy for a house it cannot enter.
     *
     * The price is `CardValue.resaleOf`, the same number `POST /me/cards/sell` pays a person.
     */
    fun selling(save: GameSave, cards: CardCatalog, auctioning: Boolean = false): GameSave? {
        val kept = KEPT_SPARES + if (auctioning) AUCTIONED_SPARES else 0
        val surplus = save.cards.keys.mapNotNull { id ->
            val card = cards.byId[id] ?: return@mapNotNull null
            val spare = save.spareCopiesOf(id) - kept
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
     * The **stake** refusals are not duplicates of anything. The two halves of a stake are two
     * decisions, and each has its own switch:
     *
     * - **MGP** only with [wagers]. A bot that bets its purse moves money into and out of the
     *   players' economy by the thousand, and that is a decision to be taken with the numbers in
     *   front of you rather than inherited from a lobby listing.
     * - **A trade rule** only with [trades]. What changes hands is the five cards each side
     *   brings, which a bot is as able to lose as to win: it plays the trade the way a person
     *   does, and `BotDirector.claimed` names its picks when it wins. Bounded per match by the
     *   hand, which is what made it the one to open first.
     *
     * A table that states both needs both. The ceiling and the purse are checked whenever MGP is
     * on the table, exactly as the referee will.
     */
    // Seven parameters, and each is a fact this cannot look up: the lobby, who is asking, what they
    // are holding, the deployment's ceiling, its two stake switches, and the clock. A holder would
    // name the same seven one indirection away.
    @Suppress("LongParameterList")
    fun joinable(
        tables: List<PvpTableRow>,
        botId: Long,
        save: GameSave,
        stakes: PvpStakePolicy,
        wagers: Boolean,
        trades: Boolean,
        staleBefore: Long,
    ): PvpTableRow? = tables.firstOrNull { table ->
        table.hostAccount != botId &&
            table.matchId == null &&
            table.openedAt <= staleBefore &&
            (table.stake.mgp == 0 || wagers) &&
            (table.stake.trade == TradeRule.NONE || trades) &&
            affordable(table, save, stakes)
    }

    private fun affordable(table: PvpTableRow, save: GameSave, stakes: PvpStakePolicy): Boolean =
        stakes.allows(save, table.stake) && save.mgp >= table.stake.mgp

    /**
     * What a bot's daring is raised to: at the top of the range, the hardest opponent weighs
     * `11³` — about thirteen hundred — times the easiest. See [appeal].
     */
    private const val DARING_POWER = 3.0

    /**
     * What a missing card's drop rate is worth to a greedy bot, in units of "one more opponent".
     * Ten: a card that drops one match in ten doubles the appeal of whoever drops it.
     */
    private const val LOOT_WEIGHT = 10.0

    /** How much more an opponent the profile has never beaten draws it. See [appeal]. */
    private const val UNBEATEN_WEIGHT = 3.0

    /** The least a whim multiplies an opponent's appeal by, for the reason `BotShopping` gives. */
    private const val WHIM_FLOOR = 0.5

    /** The `ItemReward.type` of a card drop — `npcs.json`'s own spelling. */
    private const val CARD_REWARD = "card"

    /**
     * The rank at and below which a spare copy is stock to clear rather than a card to keep.
     *
     * Two stars. A fourth one-star is four tickets in the Random draw — see [selling] — and the
     * counter pays what an auction's floor would anyway. A spare three-star and up is worth more to
     * a person than to the shop, so it goes to the auction house instead: see
     * `BotAuctions.listing`, which is the other side of this line.
     */
    internal const val SELLABLE_RARITY = 2

    /**
     * One spare of everything survives, because the copy after a deck's copy is the useful one.
     * Shared with `BotAuctions.listing`, which keeps the same spare for the same reason.
     */
    internal const val KEPT_SPARES = 1

    /**
     * The low copies [selling] leaves for `BotAuctions.listing`, over [KEPT_SPARES]. One: the
     * bot keeps one low lot open at a time, so a second held copy would only wait for the first.
     */
    private const val AUCTIONED_SPARES = 1

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

/**
 * One of [candidates], drawn with a chance proportional to its weight — or null when none of them
 * weighs anything.
 *
 * The one way a bot chooses among good answers, shared by [BotBrain] and [BotShopping] so a weight
 * means the same thing in both. A weight of zero is never drawn, which is how a candidate is ruled
 * out without being filtered out by a second rule beside the one that weighed it.
 */
internal fun <T> pickWeighted(candidates: List<Pair<T, Double>>, random: Random): T? {
    val weighed = candidates.filter { it.second > 0.0 }
    if (weighed.isEmpty()) return null

    var roll = random.nextDouble() * weighed.sumOf { it.second }
    // The last candidate answers a roll that rounding carried past the end of the sum.
    return weighed.firstOrNull { (_, weight) ->
        roll -= weight
        roll < 0.0
    }?.first ?: weighed.last().first
}
