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
     * finish.
     *
     * The price is `BotPersonality.askingPrice`: the bot's markup over worth, never under what the
     * counter would pay, which is also the auction house's own floor.
     *
     * @param own the bot's lots as `AuctionStore.mine` answers them, finished ones included.
     */
    fun listing(
        save: GameSave,
        cards: CardCatalog,
        own: List<AuctionLot>,
        personality: BotPersonality,
    ): BotListing? {
        val live = own.filter { it.yours && !it.status.isFinished }
        val listed = live.map { it.cardId }.toSet()
        // A lot for a card this catalogue no longer knows still takes a slot in a tier: counted as
        // rare, the tier a card nobody can price is least likely to belong to — and either way a
        // lot the bot cannot account for is not a reason to open another.
        val open = live.groupingBy { lot -> cards.byId[lot.cardId]?.let(::tier) ?: Tier.RARE }
            .eachCount()

        return save.cards.keys
            .mapNotNull { cards.byId[it] }
            .filter { card ->
                (open[tier(card)] ?: 0) < personality.lotsPerTier &&
                    card.id !in listed &&
                    save.spareCopiesOf(card.id) > BotBrain.KEPT_SPARES
            }
            .sortedWith(compareByDescending<Card> { CardValue.worthOf(it) }.thenBy { it.id })
            .firstOrNull()
            ?.let { card ->
                val price = personality.askingPrice(
                    worth = CardValue.worthOf(card),
                    resale = CardValue.resaleOf(card.id, cards.byId),
                )
                BotListing(card.id, price)
            }
            ?.takeIf { save.mgp >= AuctionRules.listingFee(it.price) }
    }

    /**
     * The lot this bot should bid on, and how much — or null when nothing on offer is worth it.
     *
     * ### What "needs" means
     *
     * A card the bot does not own, admitted by the format it plays — the set it collects. Beyond
     * that, it depends on the bot:
     *
     * - a **collector** or a **merchant** (`BotPersonality.collects`) bids on any such card. The
     *   collection is the goal every player shares, and a card in the binder is part of it. A
     *   card one of its decks would also field, [BotDecks.wouldField], is still bid on first.
     * - a **competitor** or a **duelist** bids only on what one of its decks would field: a card
     *   in the binder wins it nothing, and it would rather spend on a pack or a tournament.
     *
     * ### How much
     *
     * The least the lot will take, `AuctionLot.minimumBid`, and only while that is no more than the
     * bot's ceiling for the card, `BotPersonality.bidCeiling`. Outbid, the bot bids again on a
     * later pass, one increment at a time, until the price passes the ceiling — which is how a
     * person who knows what a card is worth to them bids.
     *
     * The purse, not the part above the bot's reserve: the reserve keeps a bot's money out of the
     * *shop* so there is something left for a better use, and a card it needs, on offer now, is
     * that use.
     *
     * @param lots what is on offer — `AuctionStore.browse`.
     * @param own the bot's own lots — `AuctionStore.mine` — which say what it is already winning.
     *   A bot leading on one lot for a card does not bid on a second one for the same card.
     */
    // LongParameterList: six, and each is a separate fact — the profile, the format, the
    // catalogue, the two lists the auction house answers with, and who is bidding.
    @Suppress("LongParameterList")
    fun bidding(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        lots: List<AuctionLot>,
        own: List<AuctionLot>,
        personality: BotPersonality,
    ): BotBid? {
        val winning = own.filter { it.youLead && !it.status.isFinished }.map { it.cardId }.toSet()

        val wanted = lots.asSequence()
            .filter { lot ->
                lot.status.isOpen &&
                    !lot.yours &&
                    !lot.youLead &&
                    lot.cardId !in winning &&
                    lot.cardId in cards.byId &&
                    format.admitsCard(lot.cardId) &&
                    save.copiesOf(lot.cardId) == 0 &&
                    lot.minimumBid <= personality.bidCeiling(worthOf(lot, cards)) &&
                    AuctionRules.totalDue(lot.minimumBid) <= save.mgp
            }
            .sortedWith(
                compareByDescending<AuctionLot> { worthOf(it, cards) }
                    .thenBy { it.minimumBid }
                    .thenBy { it.id },
            )
        // Last, and lazily: it builds every hand the bot has, once per candidate, so it is asked
        // only of lots that already passed everything cheap. A collector that finds no lot a deck
        // would field takes the first card its collection lacks instead.
        val chosen = wanted.firstOrNull { BotDecks.wouldField(save, format, cards, it.cardId) }
            ?: wanted.firstOrNull()?.takeIf { personality.collects }
        return chosen?.let { BotBid(it.id, it.minimumBid) }
    }

    private fun worthOf(lot: AuctionLot, cards: CardCatalog): Int =
        CardValue.worthOf(lot.cardId, cards.byId)

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
