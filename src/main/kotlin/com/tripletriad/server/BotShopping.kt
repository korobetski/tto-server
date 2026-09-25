package com.tripletriad.server

import com.tripletriad.data.BoosterPricing
import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardValue
import com.tripletriad.data.Format
import com.tripletriad.data.Inventory
import com.tripletriad.data.ShopCatalog
import com.tripletriad.data.ShopOffer
import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameSave
import kotlin.random.Random

/**
 * What a bot buys at the shop, and what it goes without so it can buy it.
 *
 * ### Why this is not in [BotBrain]
 *
 * For the reason [BotDecks] is not: it grew a shape of its own. A shopping trip used to be one
 * line — the dearest pack the purse could reach — and it is now three questions asked in order:
 * is there a card worth saving for, can it be had today, and if not, which pack is worth what is
 * left over. Each is a function a reader tuning the bots will want to find by name.
 *
 * ### Saving is a card, not a number
 *
 * A player does not save MGP, they save *for* something. So a bot's thrift is two things:
 *
 * - a **reserve** it never spends at the shop — `BotPolicy.reserve` times its own
 *   `BotPersonality.thrift` — which is what keeps a wager or a bid possible;
 * - a **goal**: the single from the shop's shelf it is missing and means to buy, up to
 *   `BotPersonality.horizon` times its reserve. While it has one, packs are paid for out of what
 *   is left *after* the goal, so the goal is reached rather than eaten by packs one at a time.
 * - an **earmark**: the fee of the tournament it means to enter today, `BotBrain.fancied`. Kept
 *   back from the goal and the packs alike until the bot has paid it, for the same reason.
 *
 * The bid at the auction house is the one purchase that may reach into the reserve — see
 * `BotAuctions.bidding` — because a card wanted and on offer now is the use the money was kept for.
 *
 * ### Varied, but never careless
 *
 * Every bot weighs a pack by the same measure — how much of what it holds is new to this
 * collection — and then by a whim of its own, so two collectors facing the same shelf do not buy
 * the same pack. A pack with nothing new in it is never bought: that is a rake of a fifth paid to
 * turn MGP into a card the counter buys back at two-fifths of its worth.
 *
 * Nothing here prices anything: `ShopCatalog` owns the shelf and the prices, `BoosterPricing` the
 * odds, `CardValue` the worth, `Inventory` the opening.
 */
object BotShopping {

    /**
     * The profile after one shopping trip, or null when it did not make one.
     *
     * The goal first, when the purse can stand it with [reserve] still left over; otherwise a pack
     * from what remains once the reserve **and** the goal are set aside.
     *
     * [earmarked] is kept back on top of the reserve, from the goal and the packs alike: it is
     * money the bot has already promised to something the shop does not sell — a tournament's
     * fee, today — and a single can wait for tomorrow where today's entry cannot. It is not part
     * of the reserve because the reserve is also what the [goal]'s horizon is measured over, and a
     * bot saving for a fee has no more reason than yesterday to covet a dearer card.
     *
     * The purchase is opened in the same step. Leaving a pack in the bag would be the more
     * player-like behaviour and a worse instrument: an unopened pack is MGP that has left the
     * economy without having become cards yet. Opening a pack puts `CardItem`s in the bag rather
     * than cards in the collection, and so does buying a single — `BotBrain.emptying` is the half
     * that takes them.
     *
     * Both halves are `:core`'s: `ShopCatalog.buy` prices it and refuses an unaffordable one by
     * returning the profile unchanged, and `Inventory.use` rolls a pack on **this** server's
     * generator, exactly as `POST /me/bag/use` does.
     */
    // LongParameterList: seven separate facts the director owns — the profile, the format, the
    // catalogue, who the bot is, its reserve, the generator, and what it has promised elsewhere.
    // See `BotBrain.developing`.
    // ReturnCount: nothing worth buying, a purchase that did not take, and the one that did.
    @Suppress("LongParameterList", "ReturnCount")
    fun shopping(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        personality: BotPersonality,
        reserve: Int,
        random: Random,
        earmarked: Int = 0,
    ): GameSave? {
        val offers = ShopCatalog.offers(format, cards.byId)
        val kept = reserve + earmarked
        val goal = goal(save, offers, personality, reserve)
        val offer = goal?.takeIf { save.mgp - it.price >= kept }
            ?: pack(save, offers, cards, personality, kept + (goal?.price ?: 0), random)
            ?: return null

        val bought = ShopCatalog.buy(save, offer)
        // Unchanged means the purse could not cover it after all — the choice above and this check
        // read the same number, so it cannot happen today, and null is the honest answer if a
        // future price ever moves between them.
        if (bought.mgp == save.mgp) return null

        return if (offer.item is BoosterItem) {
            Inventory.use(
                bought,
                offer.item,
                random,
            ).save
        } else {
            bought
        }
    }

    /**
     * The single this bot is saving for, or null when nothing on the shelf is worth it.
     *
     * A card it does not own, priced within its horizon over [reserve]. Among those, the
     * cheapest — the card it can have soonest — nudged by the bot's whim for each card, so a
     * roster does not all save for the same one and a bot does not change its mind between
     * passes: the whim is the bot's own and the same every time it is asked.
     *
     * A card already in the bag counts as owned. It is bought and not yet taken only between one
     * step of `BotBrain.developing` and the next, and buying it twice in that window would be the
     * one purchase a bot could make without wanting it.
     */
    fun goal(
        save: GameSave,
        offers: List<ShopOffer>,
        personality: BotPersonality,
        reserve: Int,
    ): ShopOffer? {
        val horizon = personality.horizonOver(reserve)
        return offers
            .filter { offer ->
                val item = offer.item
                item is CardItem &&
                    offer.price <= horizon &&
                    save.copiesOf(item.cardId) == 0 &&
                    save.bag.none { it is CardItem && it.cardId == item.cardId }
            }
            .minWithOrNull(
                compareBy<ShopOffer> { offer ->
                    offer.price * (1 + personality.whim((offer.item as CardItem).cardId))
                }.thenBy { (it.item as CardItem).cardId },
            )
    }

    /**
     * A pack this purse can reach without spending [kept], or null when none is worth buying.
     *
     * Drawn rather than chosen: each pack weighs its [novelty] times the bot's whim for that pack,
     * so a collection that two packs would both feed is fed from both, and two bots with the same
     * collection do not buy the same one. A pack with no novelty weighs nothing and is never drawn.
     */
    // LongParameterList: [shopping]'s own facts, less the format the offers were priced for, plus
    // what is kept back — which is the reserve, the earmark and the goal together, not one of them.
    @Suppress("LongParameterList")
    private fun pack(
        save: GameSave,
        offers: List<ShopOffer>,
        cards: CardCatalog,
        personality: BotPersonality,
        kept: Int,
        random: Random,
    ): ShopOffer? = pickWeighted(
        offers.mapNotNull { offer ->
            val type = (offer.item as? BoosterItem)?.boosterType
                ?.takeIf { offer.price <= save.mgp - kept }
                ?: return@mapNotNull null
            val whim = WHIM_FLOOR + personality.whim(type.name.hashCode())
            offer to novelty(save, type, cards) * whim
        },
        random,
    )

    /**
     * How much of what a pack of [type] holds is new to [save], from 0 (nothing) to 1 (all of it).
     *
     * The expected worth of the cards it would add, over the expected worth of a draw — both
     * weighted by `BoosterPricing.oddsOf`, the same odds the shop prices it by. A ratio rather than
     * an amount on purpose: the amount is what the price already says, and a measure that grew
     * with the price would be the old "the dearest pack it can reach" under another name. This is
     * how much of the price buys something new, which is the same question for a Bronze pack and
     * for a Mithril one.
     */
    internal fun novelty(save: GameSave, type: BoosterType, cards: CardCatalog): Double {
        val draws = type.pool.zip(BoosterPricing.oddsOf(type)) { id, odds ->
            Triple(id, odds, CardValue.worthOf(id, cards.byId))
        }
        val whole = draws.sumOf { (_, odds, worth) -> odds * worth }
        if (whole <= 0.0) return 0.0
        return draws.filter { (id, _, _) -> save.copiesOf(id) == 0 }
            .sumOf { (_, odds, worth) -> odds * worth } / whole
    }

    /**
     * The least a whim multiplies a pack's weight by. A half: a whim may halve a pack's appeal or
     * make it half as appealing again, never erase it — a pack full of new cards stays in the draw
     * for every bot, whatever it happens to fancy.
     */
    private const val WHIM_FLOOR = 0.5
}
