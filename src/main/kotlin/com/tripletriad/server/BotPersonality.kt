package com.tripletriad.server

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Who a bot is: the set it collects, what it is after, and the temper it goes after it with.
 *
 * ### Why bots have one at all
 *
 * Every bot wants what every player wants — the collection — and there is more than one way to
 * get it: grind the opponents who drop what is missing, buy packs, buy singles from the shop, bid
 * at the auction house, win it at a table, earn the achievements that open the tournaments. A
 * roster that all took the same path would be ten copies of one player: the same pack bought, the
 * same five opponents queued against, the same card bid on. So each bot is drawn an [archetype]
 * and a [favourite] set, and then a value for every trait **inside the archetype's range** — two
 * collectors are both collectors, and still not the same collector.
 *
 * ### What each trait is, and what reads it
 *
 * Every trait is a number some decision elsewhere reads; none of them is flavour. They are
 * multipliers over the deployment's own numbers wherever there is one, so an operator who turns a
 * dial in `BotPolicy` turns it for every personality at once.
 *
 * - [thrift] — how much more than `BotPolicy.reserve` this bot keeps back. At least the policy's
 *   own reserve: a personality can make a bot more careful than the deployment asked, never less.
 * - [horizon] — how far above its reserve it will save for one card from the shop, as a multiple
 *   of the reserve. `BotShopping.goal` reads it: the longer the horizon, the dearer the singles a
 *   bot is prepared to go without packs for.
 * - [appetite] — the most it bids at the auction house, as a fraction of what the card is worth.
 *   A collector pays [COLLECTOR_PREMIUM] on top of it for the set it collects.
 * - [markup] — what it asks for a lot, as a multiple of worth. See [askingPrice].
 * - [daring] — its taste for hard opponents, 0 to 1. `BotBrain.opponent` reads it.
 * - [greed] — how much a missing card on an opponent's drop table draws it to that opponent.
 * - [ambition] — the share of days on which it enters a ladder it could enter. See
 *   `BotBrain.tournament`.
 * - [pace] — how much slower than the policy's cadence it plays. At least 1, for the reason
 *   [thrift] is: the cadence is what stands where the rate limits cannot (`BotDirector`), and a
 *   personality may slow a bot down but must not speed one up.
 * - [patience] — how much longer than `BotPolicy.tableWaitMillis` it leaves a table standing
 *   before it sits down. At least 1, so a person always has the whole of the policy's wait.
 * - [seed] — the bot's own whims: what makes one collector save for a different card from the next.
 *   See [whim].
 *
 * ### The ranges are a sketch, and meant to be tuned
 *
 * They are what `TEMPERAMENTS` says, drawn uniformly, and they are the first numbers anybody
 * watching the roster will want to move. A personality is stored as it was drawn —
 * `V20__bot_personality.sql` — so moving a range changes the bots enrolled after it, and an UPDATE
 * is how an existing bot is changed.
 */
@Serializable
data class BotPersonality(
    val archetype: BotArchetype,
    val favourite: FavouriteSet,
    val thrift: Double,
    val horizon: Double,
    val appetite: Double,
    val markup: Double,
    val daring: Double,
    val greed: Double,
    val ambition: Double,
    val pace: Double,
    val patience: Double,
    val seed: Int,
) {

    /** What this bot keeps out of the shop: [base], the deployment's reserve, times [thrift]. */
    fun reserveOver(base: Int): Int = (base * thrift.coerceAtLeast(1.0)).roundToInt()

    /** The dearest single this bot will save for, over a [reserve] of its own. */
    fun horizonOver(reserve: Int): Int = (reserve * horizon.coerceAtLeast(0.0)).roundToInt()

    /**
     * The most this bot will bid for a card [worth] that much.
     *
     * A collector goes to [COLLECTOR_PREMIUM] of its [appetite] — at most a quarter over worth —
     * because completing the set is what it is for. `BotAuctions.bidding` only ever asks this of a
     * card of the bot's own format, which is the set it collects: the premium is for the set, and
     * the format is how the set is spelled.
     */
    fun bidCeiling(worth: Int): Int {
        val premium = if (archetype == BotArchetype.COLLECTOR) COLLECTOR_PREMIUM else 1.0
        return (worth * appetite * premium).toInt()
    }

    /**
     * What this bot asks for a card [worth] that much, rounded to a price a person would write.
     *
     * Never below the counter's price, [resale]: a lot that asked less than the shop pays would be
     * a bot paying a person to take a card, and `AuctionRules.floorPriceOf` would refuse it anyway.
     * Far under the auction house's ceiling, twenty times worth, whatever the markup is drawn at.
     */
    fun askingPrice(worth: Int, resale: Int): Int =
        ((worth * markup / PRICE_STEP).roundToInt() * PRICE_STEP).coerceAtLeast(resale)

    /** [millis] of the policy's cadence at this bot's [pace]. Never faster than the policy. */
    fun paced(millis: Long): Long = (millis * pace.coerceAtLeast(1.0)).toLong()

    /** How long a table stands before this bot takes it: [millis], the policy's wait, drawn out. */
    fun waited(millis: Long): Long = (millis * patience.coerceAtLeast(1.0)).toLong()

    /**
     * A number in `0 until 1` that is this bot's own and always the same for [key].
     *
     * How a bot prefers without a table of preferences: a card, a pack or an opponent is weighed by
     * the same rules for every bot, and then nudged by a whim that belongs to this bot alone.
     * Stable across passes because a preference that changed every two seconds would not be one —
     * a bot saving for a card must still want it on the next pass.
     */
    fun whim(key: Int): Double = Random(seed * WHIM_MIX + key).nextDouble()

    /** Whether this bot bids on any card it is missing, not only on what a deck would use. */
    val collects: Boolean get() = archetype.collects

    /** How many lots per tier this bot keeps open. See `BotAuctions.listing`. */
    val lotsPerTier: Int get() = archetype.lotsPerTier

    companion object {

        /**
         * A fresh personality: an archetype, a [favourite] set (drawn when not given), and every
         * trait drawn inside that archetype's range.
         *
         * @param favourite the set to collect, when the caller already knows it — an existing bot
         *   keeps the one its collection was built in. See `BotDirector.personalized`.
         */
        fun draw(random: Random, favourite: FavouriteSet? = null): BotPersonality {
            val archetype = BotArchetype.entries[random.nextInt(BotArchetype.entries.size)]
            val temper = TEMPERAMENTS.getValue(archetype)
            return BotPersonality(
                archetype = archetype,
                favourite = favourite
                    ?: FavouriteSet.entries[random.nextInt(FavouriteSet.entries.size)],
                thrift = random.within(temper.thrift),
                horizon = random.within(temper.horizon),
                appetite = random.within(temper.appetite),
                markup = random.within(temper.markup),
                daring = random.within(temper.daring),
                greed = random.within(temper.greed),
                ambition = random.within(temper.ambition),
                pace = random.within(temper.pace),
                patience = random.within(temper.patience),
                seed = random.nextInt(),
            )
        }

        private fun Random.within(range: ClosedFloatingPointRange<Double>): Double =
            range.start + nextDouble() * (range.endInclusive - range.start)

        /**
         * What a collector pays over its appetite for the set it collects: a quarter, so never more
         * than 125 % of worth. The line the auction house's economy can stand — a bot is the one
         * buyer that never runs out of patience, and a roster of them paying double would set the
         * price every person has to meet.
         */
        const val COLLECTOR_PREMIUM = 1.25

        /** Asking prices are multiples of ten, which is how a person prices a lot. */
        private const val PRICE_STEP = 10

        /** Spreads a bot's [seed] over the keys it is asked about: two whims are not neighbours. */
        private const val WHIM_MIX = 31

        /**
         * Each archetype's ranges.
         *
         * Read the columns, not the rows: what makes a merchant a merchant is that its [thrift] and
         * [markup] start where everybody else's end and its [appetite] ends where theirs start.
         *
         * - A **collector** farms drops ([greed]) and bids readily, a premium on top. Saves long
         *   ([horizon]) for the singles it is missing, rarely bothers with tournaments.
         * - A **competitor** likes hard opponents ([daring]) and tournaments ([ambition]), and
         *   spends rather than saves.
         * - A **duelist** takes a table sooner than anybody ([patience] at the policy's own wait)
         *   and plays faster ([pace] near 1). Saves little.
         * - A **merchant** keeps the biggest reserve, asks over worth, bids under it, and is in no
         *   hurry to sit down at anything.
         *
         * `ambition` reaches 0 for a merchant on purpose: some of them never enter a tournament at
         * all, which is a personality too.
         */
        private val TEMPERAMENTS = mapOf(
            BotArchetype.COLLECTOR to Temperament(
                thrift = 1.2..2.0,
                horizon = 2.0..5.0,
                appetite = 0.85..1.0,
                markup = 0.95..1.1,
                daring = 0.1..0.5,
                greed = 0.7..1.0,
                ambition = 0.05..0.25,
                pace = 1.0..1.4,
                patience = 1.5..3.0,
            ),
            BotArchetype.COMPETITOR to Temperament(
                thrift = 1.0..1.6,
                horizon = 0.5..2.0,
                appetite = 0.8..1.0,
                markup = 0.9..1.05,
                daring = 0.6..1.0,
                greed = 0.1..0.4,
                ambition = 0.5..0.9,
                pace = 1.0..1.3,
                patience = 1.2..2.0,
            ),
            BotArchetype.DUELIST to Temperament(
                thrift = 1.0..1.4,
                horizon = 0.3..1.2,
                appetite = 0.75..0.95,
                markup = 0.9..1.0,
                daring = 0.3..0.7,
                greed = 0.1..0.4,
                ambition = 0.1..0.3,
                pace = 1.0..1.1,
                patience = 1.0..1.2,
            ),
            BotArchetype.MERCHANT to Temperament(
                thrift = 2.0..3.5,
                horizon = 1.5..4.0,
                appetite = 0.7..0.85,
                markup = 1.05..1.2,
                daring = 0.1..0.4,
                greed = 0.3..0.6,
                ambition = 0.0..0.1,
                pace = 1.1..1.5,
                patience = 2.0..3.0,
            ),
        )
    }

    /** One archetype's ranges, one per trait. Only ever read by [draw]. */
    private data class Temperament(
        val thrift: ClosedFloatingPointRange<Double>,
        val horizon: ClosedFloatingPointRange<Double>,
        val appetite: ClosedFloatingPointRange<Double>,
        val markup: ClosedFloatingPointRange<Double>,
        val daring: ClosedFloatingPointRange<Double>,
        val greed: ClosedFloatingPointRange<Double>,
        val ambition: ClosedFloatingPointRange<Double>,
        val pace: ClosedFloatingPointRange<Double>,
        val patience: ClosedFloatingPointRange<Double>,
    )
}

/**
 * The four kinds of player a bot can be. See [BotPersonality] for what each does with its traits.
 *
 * @property collects whether it bids on any card of its set it is missing. A collector and a
 *   merchant do — the first to complete the set, the second because a card bought under worth is a
 *   card bought well. A competitor and a duelist bid only on what one of their decks would field:
 *   a card in the binder wins them nothing.
 * @property lotsPerTier how many lots of each tier it keeps open at the auction house. A merchant
 *   keeps two, so it sells at the house what another bot would sell at the counter; everybody else
 *   keeps one. See `BotAuctions.listing`.
 */
@Serializable
enum class BotArchetype(val collects: Boolean, val lotsPerTier: Int) {
    COLLECTOR(collects = true, lotsPerTier = 1),
    COMPETITOR(collects = false, lotsPerTier = 1),
    DUELIST(collects = false, lotsPerTier = 1),
    MERCHANT(collects = true, lotsPerTier = 2),
}

/**
 * The set a bot collects, and so the format it plays: a set is spelled, in this server, as the
 * format that admits it.
 *
 * `BOTH` is `free-play`, which admits every block and deals against every opponent. The price of
 * that breadth is the tournaments: every ladder belongs to one of the two standard formats, and a
 * deck built from both sets rarely fits either — see `BotBrain.tournament`.
 *
 * @property formatId the format a bot of this set plays. `BotDirector.formatOf` falls back to
 *   `TTO_BOTS_FORMAT` when a deployment does not ship it.
 */
@Serializable
enum class FavouriteSet(val formatId: String) {
    FF14("ff14-standard"),
    FF8("ff8-standard"),
    BOTH("free-play"),
    ;

    companion object {
        /** The set whose format is [formatId], or null when no set plays it. */
        fun of(formatId: String): FavouriteSet? = entries.firstOrNull { it.formatId == formatId }
    }
}
