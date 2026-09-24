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
 * So a bot takes part the way a careful player does and no further:
 *
 * - it **sells** what it has no use for — spare copies past the one it keeps — and asks what the
 *   card is worth, `CardValue.worthOf`, never more. A person gets a card at the price the game
 *   already puts on it, and the bot's purse gets more than the counter would have paid.
 * - it **buys** only what it needs — a card it does not own that one of its own decks would
 *   field, asked of `BotDecks.wouldField` — and never bids past that same worth. Never above it is
 *   what makes the bot useless as a way to pump MGP out of the roster: the most a person can get
 *   from a bot for a card is what the card is worth, and only when the bot actually wants it.
 *
 * At most one lot per [tier] open at once and one bid per pass, so a bot is a presence on the list
 * rather than the list.
 *
 * ### One lot for the rare cards, one for the common ones
 *
 * A single queue ordered by worth would never reach a one-star while a three-star was waiting,
 * and a bot that plays long enough always has a three-star waiting. So the tiers are the line
 * `BotBrain.SELLABLE_RARITY` already draws between what the counter clears and what it keeps, and
 * each has a lot of its own: the rare lot is where the purse is, the common lot is what a player
 * building a first collection can afford — and what `BotBrain.selling` holds one copy back for.
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
     * The most valuable spare whose [tier] has no lot open: it is the listing a person is
     * likeliest to want, and the rare tier is asked first for that reason alone. A card the bot
     * already has a lot open for waits for that lot to finish.
     *
     * @param own the bot's lots as `AuctionStore.mine` answers them, finished ones included.
     */
    fun listing(save: GameSave, cards: CardCatalog, own: List<AuctionLot>): BotListing? {
        val live = own.filter { it.yours && !it.status.isFinished }
        val listed = live.map { it.cardId }.toSet()
        // A lot for a card this catalogue no longer knows still takes its tier's slot: counted as
        // rare, the tier a card nobody can price is least likely to belong to — and either way a
        // lot the bot cannot account for is not a reason to open another.
        val busy = live.map { lot -> cards.byId[lot.cardId]?.let(::tier) ?: Tier.RARE }.toSet()

        return save.cards.keys
            .mapNotNull { cards.byId[it] }
            .filter { card ->
                tier(card) !in busy &&
                    card.id !in listed &&
                    save.spareCopiesOf(card.id) > BotBrain.KEPT_SPARES
            }
            .sortedWith(compareByDescending<Card> { CardValue.worthOf(it) }.thenBy { it.id })
            .firstOrNull()
            ?.let { card -> BotListing(card.id, CardValue.worthOf(card)) }
            ?.takeIf { save.mgp >= AuctionRules.listingFee(it.price) }
    }

    /**
     * The lot this bot should bid on, and how much — or null when nothing on offer is worth it.
     *
     * ### What "needs" means
     *
     * A card the bot does not own, admitted by the format it plays, that [BotDecks.wouldField]
     * would put in one of its hands. Not "a card missing from the collection": completing a set
     * is a person's reason to buy, and a bot that bought for it would be spending the roster's
     * earnings on cards it then never plays — which is the one thing its numbers are meant to be
     * about.
     *
     * ### How much
     *
     * The least the lot will take, `AuctionLot.minimumBid`, and only while that is no more than the
     * card's worth. Outbid, the bot bids again on a later pass, one increment at a time, until the
     * price passes the worth — which is how a person who knows what a card is worth bids, and is
     * what [BotAuctions]' KDoc means by never above it.
     *
     * The purse, not the part above `BotPolicy.reserve`: the reserve keeps a bot's money out of the
     * *shop* so there is something left for a better use, and a card one of its decks wants is that
     * use.
     *
     * @param lots what is on offer — `AuctionStore.browse`.
     * @param own the bot's own lots — `AuctionStore.mine` — which say what it is already winning.
     *   A bot leading on one lot for a card does not bid on a second one for the same card.
     */
    fun bidding(
        save: GameSave,
        format: Format,
        cards: CardCatalog,
        lots: List<AuctionLot>,
        own: List<AuctionLot>,
    ): BotBid? {
        val winning = own.filter { it.youLead && !it.status.isFinished }.map { it.cardId }.toSet()

        return lots.asSequence()
            .filter { lot ->
                lot.status.isOpen &&
                    !lot.yours &&
                    !lot.youLead &&
                    lot.cardId !in winning &&
                    lot.cardId in cards.byId &&
                    format.admitsCard(lot.cardId) &&
                    save.copiesOf(lot.cardId) == 0 &&
                    lot.minimumBid <= CardValue.worthOf(lot.cardId, cards.byId) &&
                    AuctionRules.totalDue(lot.minimumBid) <= save.mgp
            }
            .sortedWith(
                compareByDescending<AuctionLot> { CardValue.worthOf(it.cardId, cards.byId) }
                    .thenBy { it.minimumBid }
                    .thenBy { it.id },
            )
            // Last, and lazily: it builds every hand the bot has, once per candidate, so it is
            // asked only of lots that already passed everything cheap.
            .firstOrNull { BotDecks.wouldField(save, format, cards, it.cardId) }
            ?.let { BotBid(it.id, it.minimumBid) }
    }

    /**
     * Which of a bot's two lots [card] would take.
     *
     * One lot per tier, so two at most — well under `AuctionPolicy.maxOpenLots`: enough that a
     * bot's surplus reaches the list, few enough that ten bots are twenty lots and not the whole
     * house.
     */
    private fun tier(card: Card): Tier =
        if (card.rarity > BotBrain.SELLABLE_RARITY) Tier.RARE else Tier.COMMON

    private enum class Tier { RARE, COMMON }
}

/** A lot a bot means to open: the card, and the one price it asks — start and reserve alike. */
data class BotListing(val cardId: Int, val price: Int)

/** A bid a bot means to place. */
data class BotBid(val lotId: String, val amount: Int)
