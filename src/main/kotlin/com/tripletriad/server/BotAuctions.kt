package com.tripletriad.server

import com.tripletriad.data.AuctionRules
import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardValue
import com.tripletriad.data.Format
import com.tripletriad.model.Card
import com.tripletriad.model.GameSave
import com.tripletriad.protocol.AuctionLot

/**
 * What a bot puts up at the auction house, and what it bids on.
 *
 * ### A little, on purpose
 *
 * The auction house is the one place in this game where MGP moves between accounts with nothing
 * checking what came back the other way — `AuctionRules.ceilingPriceOf` says so at length — and a
 * roster of accounts the server plays itself is exactly the kind of participant that could tilt it.
 * So a bot takes part the way a player with a price in mind does, and no further:
 *
 * - it **sells** what it has no use for — spare copies past the one it keeps — at its own price
 *   around what the card is worth, `CardValue.worthOf` times its `BotPersonality.markup`: a
 *   merchant asks a little over, a duelist a little under, nobody far from it. A person gets a
 *   card at about the price the game already puts on it, and the bot's purse gets more than the
 *   counter would have paid.
 * - it **buys** what it needs — a card of its set it does not own — and stops bidding at its own
 *   ceiling, `BotPersonality.bidCeiling`: at most worth, and at most a quarter over it for a
 *   collector completing its set. A bounded ceiling is what makes the bot useless as a way to pump
 *   MGP out of the roster: the most a person can get from a bot for a card is a little over what
 *   the card is worth, and only when the bot actually wants it.
 * - a **merchant** also buys to sell again — any card, from any set — and pays under worth for
 *   it, so the resale is its margin: see [bidding].
 *
 * What "needs" means depends on who is asking — see [bidding] — and that is most of what makes
 * one bot's day at the house different from another's.
 *
 * A few lots per [tier] open at once and one bid per pass, so a bot is a presence on the list
 * rather than the list.
 *
 * ### Lots for the rare cards, lots for the common ones
 *
 * A single queue ordered by worth would never reach a one-star while a three-star was waiting,
 * and a bot that plays long enough always has a three-star waiting. So the tiers are the line
 * `BotBrain.SELLABLE_RARITY` already draws between what the counter clears and what it keeps, and
 * each has lots of its own — one, or two for a merchant: the rare lots are where the purse is, the
 * common ones are what a player building a first collection can afford, and what
 * `BotBrain.selling` holds a copy back for.
 *
 * ### The reserve is the start price
 *
 * A lot that ends above its start and below its reserve waits for the seller to decide, and a bot
 * would have to be taught to answer. With the two equal, any bid is a sale: the decision a bot
 * would face never arises, and a lot a bot opened never holds a person's money for the twelve
 * hours `AuctionPolicy.sellerDecisionHours` allows.
 *
 * ### Pure, like [BotBrain]
 *
 * These functions choose; `BotDirector` acts through `AuctionStore`, which re-checks everything
 * inside its own transaction — the level gate, the floor, the ceiling, the purse, the lot count —
 * and answers with a refusal if any of it has moved. Nothing here is the auction's rules.
 */
object BotAuctions {

    /**
     * The card this bot should put up, and its price — or null when it has nothing to sell.
     *
     * The most valuable spare whose [tier] still has room for a lot — `BotPersonality.lotsPerTier`
     * of them: it is the listing a person is likeliest to want, and the rare tier is asked first
     * for that reason alone. A card the bot already has a lot open for waits for that lot to
     * finish. A spare is a copy past the ones [kept] holds back.
     *
     * The price is `BotPersonality.askingPrice`: the bot's markup over worth, never under what the
     * counter would pay, which is also the auction house's own floor.
     *
     * @param format the format the bot plays, which says which of its cards are of its set.
     * @param own the bot's lots as `AuctionStore.mine` answers them, finished ones included.
     */
    fun listing(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        own: List<AuctionLot>,
        personality: BotPersonality,
    ): BotListing? {
        val live = own.filter { it.yours && !it.status.isFinished }
        val listed = live.map { it.cardId }.toSet()
        val open = perTier(live, cards)

        return save.cards.keys
            .mapNotNull { cards.byId[it] }
            .filter { card ->
                (open[tier(card)] ?: 0) < personality.lotsPerTier &&
                    card.id !in listed &&
                    save.spareCopiesOf(card.id) > kept(card.id, format, personality)
            }
            .sortedWith(compareByDescending<Card> { CardValue.worthOf(it) }.thenBy { it.id })
            .firstOrNull()
            ?.let { card -> BotListing(card.id, askingPrice(card.id, cards, personality)) }
            ?.takeIf { save.mgp >= AuctionRules.listingFee(it.price) }
    }

    /**
     * The lot this bot should bid on, and how much — or null when nothing on offer is worth it.
     *
     * ### What it bids on, in order
     *
     * 1. **A card of its set it does not own that one of its decks would field**,
     *    [BotDecks.wouldField]. Every bot, and from the whole purse: the reserve keeps a bot's
     *    money out of the *shop* so there is something left for a better use, and a card it
     *    needs, on offer now, is that use.
     * 2. **Any other card of its set it does not own.** The collection is the goal every player
     *    shares, and a card in the binder is part of it. A collector or a merchant
     *    (`BotPersonality.collects`) bids from the whole purse, as for a deck card. A competitor
     *    or a duelist bids only with what the bid leaves over its [reserve]: a card in the binder
     *    wins it nothing, and the reserve is the wager and the tournament fee it would rather keep
     *    — but money over it is money it has no plan for, and a card nobody else bid on is a
     *    better use of it than none.
     * 3. **A card to sell again**, for a merchant (`BotPersonality.trades`) alone — see [resale].
     *
     * Within each, the most valuable card first — the one worth the pass — then the cheapest lot
     * for it.
     *
     * **Rejected: competitors and duelists bidding on the binder from the whole purse.** Their
     * reserve is what keeps them sitting down at tables and entering ladders, which is what they
     * are for; a binder card is the thing they would buy last, so it gets the money they would
     * spend last.
     *
     * ### How much
     *
     * The least the lot will take, `AuctionLot.minimumBid`, and only while that is no more than the
     * bot's ceiling for the card, `BotPersonality.bidCeiling`. Outbid, the bot bids again on a
     * later pass, one increment at a time, until the price passes the ceiling — which is how a
     * person who knows what a card is worth to them bids.
     *
     * @param lots what is on offer — `AuctionStore.browse`.
     * @param own the bot's own lots — `AuctionStore.mine` — which say what it is already winning.
     *   A bot leading on one lot for a card does not bid on a second one for the same card.
     * @param reserve the bot's own reserve, `BotPersonality.reserveOver` the policy's.
     */
    // LongParameterList: seven, and each is a separate fact — the profile, the format, the
    // catalogue, the two lists the auction house answers with, who is bidding, and what it keeps.
    @Suppress("LongParameterList")
    fun bidding(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        lots: List<AuctionLot>,
        own: List<AuctionLot>,
        personality: BotPersonality,
        reserve: Int,
    ): BotBid? {
        val live = own.filter { !it.status.isFinished }
        val winning = live.filter { it.youLead }.map { it.cardId }.toSet()

        val within = lots
            .filter { lot ->
                lot.status.isOpen &&
                    !lot.yours &&
                    !lot.youLead &&
                    lot.cardId !in winning &&
                    lot.cardId in cards.byId &&
                    lot.minimumBid <= personality.bidCeiling(worthOf(lot, cards)) &&
                    AuctionRules.totalDue(lot.minimumBid) <= save.mgp
            }
            .sortedWith(
                compareByDescending<AuctionLot> { worthOf(it, cards) }
                    .thenBy { it.minimumBid }
                    .thenBy { it.id },
            )
        val missing = within.filter { lot ->
            format.admitsCard(lot.cardId) && save.copiesOf(lot.cardId) == 0
        }

        // Deck cards first, and lazily: [BotDecks.wouldField] builds every hand the bot has, once
        // per candidate, so it is asked only of lots that already passed everything cheap.
        val chosen = missing.firstOrNull { BotDecks.wouldField(save, format, cards, it.cardId) }
            ?: missing.firstOrNull {
                personality.collects || save.mgp - AuctionRules.totalDue(it.minimumBid) >= reserve
            }
            ?: within.takeIf { personality.trades }
                ?.firstOrNull(resale(save, format, cards, live, personality))
        return chosen?.let { BotBid(it.id, it.minimumBid) }
    }

    /**
     * What a lot must be for a merchant to buy it to sell again.
     *
     * A merchant is after MGP, not a set: any card of any set, owned or not, that it can have at
     * its ceiling — under worth, `BotPersonality.bidCeiling` — and put up again at its asking
     * price, over worth. Each condition below is one way it would otherwise end up holding a card
     * it bought to sell:
     *
     * - **it would list the copy it wins** — a copy past what [kept] holds back. So a card of its
     *   own set that it is missing is not bought here: that first copy is the collection's, and
     *   [bidding] asked for it already. A card of another set, it keeps none of.
     * - **a lot is free for it** in the card's [tier]. The lots it is leading on count as lots
     *   already taken, whether or not it will list what they win: a card won with nowhere to put
     *   it up is MGP asleep in the binder, and counting every lead is the side that never
     *   happens on.
     * - **it has no lot open for that card** — [listing] would not open a second one.
     * - **the resale pays**: the asking price, less the listing fee, over what the win costs with
     *   the buyer's fee. Under today's ranges a ceiling under worth and an ask at or over it always
     *   clear this; it is checked all the same, because the ranges are the numbers meant to be
     *   tuned, and a tuning that crossed them must not turn a merchant into a bot that buys dear to
     *   sell cheap.
     *
     * One bid a pass, like any bot, so a merchant's stock grows no faster than its slots free.
     *
     * @param live the bot's own lots that have not finished, sold and bid on alike.
     */
    // LongParameterList: [bidding]'s own facts less the lots on offer — this is what is asked of
    // each of them — and with the bot's lots already narrowed to the live ones.
    @Suppress("LongParameterList")
    private fun resale(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        live: List<AuctionLot>,
        personality: BotPersonality,
    ): (AuctionLot) -> Boolean {
        val listed = live.filter { it.yours }.map { it.cardId }.toSet()
        val taken = perTier(live.filter { it.yours || it.youLead }, cards)
        return { lot ->
            val card = cards.byId.getValue(lot.cardId)
            val ask = askingPrice(card.id, cards, personality)
            (taken[tier(card)] ?: 0) < personality.lotsPerTier &&
                card.id !in listed &&
                save.spareCopiesOf(card.id) + 1 > kept(card.id, format, personality) &&
                AuctionRules.totalDue(lot.minimumBid) + AuctionRules.listingFee(ask) < ask
        }
    }

    /**
     * How many spare copies of [cardId] a bot keeps back from the auction house.
     *
     * [BotBrain.KEPT_SPARES], the copy after a deck's copy — except a merchant's card of a set it
     * does not play: no deck of its will ever name one, and it came to the merchant to be sold.
     */
    private fun kept(cardId: Int, format: Format, personality: BotPersonality): Int =
        if (personality.trades && !format.admitsCard(cardId)) 0 else BotBrain.KEPT_SPARES

    private fun askingPrice(cardId: Int, cards: CardCatalog, personality: BotPersonality): Int =
        personality.askingPrice(
            worth = CardValue.worthOf(cardId, cards.byId),
            resale = CardValue.resaleOf(cardId, cards.byId),
        )

    private fun worthOf(lot: AuctionLot, cards: CardCatalog): Int =
        CardValue.worthOf(lot.cardId, cards.byId)

    /**
     * How many of [lots] each [tier] holds.
     *
     * A lot for a card this catalogue no longer knows still takes a slot: counted as rare, the tier
     * a card nobody can price is least likely to belong to — and either way a lot the bot cannot
     * account for is not a reason to open another.
     */
    private fun perTier(lots: List<AuctionLot>, cards: CardCatalog): Map<Tier, Int> =
        lots.groupingBy { lot -> cards.byId[lot.cardId]?.let(::tier) ?: Tier.RARE }.eachCount()

    /**
     * Which of a bot's tiers [card] would take a lot in.
     *
     * Two tiers, and one or two lots in each, so four at most — well under
     * `AuctionPolicy.maxOpenLots`: enough that a bot's surplus reaches the list, few enough that
     * ten bots are a few dozen lots and not the whole house.
     */
    private fun tier(card: Card): Tier =
        if (card.rarity > BotBrain.SELLABLE_RARITY) Tier.RARE else Tier.COMMON

    private enum class Tier { RARE, COMMON }
}

/** A lot a bot means to open: the card, and the one price it asks — start and reserve alike. */
data class BotListing(val cardId: Int, val price: Int)

/** A bid a bot means to place. */
data class BotBid(val lotId: String, val amount: Int)
