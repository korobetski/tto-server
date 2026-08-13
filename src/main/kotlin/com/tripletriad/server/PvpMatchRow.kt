package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.MatchPreparation
import com.tripletriad.model.MatchResult
import com.tripletriad.model.MatchScore
import com.tripletriad.model.MatchState
import com.tripletriad.model.MatchView
import com.tripletriad.model.TOTAL_CARDS
import com.tripletriad.model.TradeRule
import com.tripletriad.model.TradeRules
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMatchView
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpOutcome
import com.tripletriad.protocol.PvpStake
import kotlin.random.Random

/**
 * A live player-versus-player match, as it is stored and as it is played.
 *
 * ### The row is the inputs, and the state is derived
 *
 * Nothing here holds a board. A match is its two hands, its seed, who started, the rules, and the
 * placements so far — and [replay] turns those into a `MatchState` with the same engine the client
 * runs. `V2__pvp.sql` argues the storage side of this; the reason it is *also* the right shape in
 * memory is that it makes the state impossible to get wrong by accident. There is no cached board
 * to fall out of step with the move list, because there is no cached board.
 *
 * ### Chaos, and why the seed is not enough on its own
 *
 * Under `OrderRule.CHAOS` the playable card is a roll, and the server takes it — see
 * [com.tripletriad.model.MatchView]. But a view is built on **every poll**, and a fresh roll each
 * time would offer the player a different card every second until they managed to tap one. So the
 * roll is seeded from the match *and the placement number*: any number of polls at the same point
 * in the match give the same answer, and the next placement gives a new one. See [turnRandom].
 */
// `TooManyFunctions` counts members, and this class has grown past the threshold by acquiring the
// *settlement* — what a wager moves, who owes a choice, what the server picks when nobody does.
// The rule is aimed at a class doing too many things; this one still does one, which is being a
// match. Splitting the settlement out would mean a second type holding a reference to this one and
// reaching through it for the hands, the stake, the board and the forfeit — every field it reads.
@Suppress("TooManyFunctions")
data class PvpMatchRow(
    val id: String,
    val blueAccount: Long,
    val redAccount: Long,
    /** The format both sides are playing — an id into `formats.json`. */
    val formatId: String,
    val rules: GameRules,
    val seed: Int,
    /** The five cards blue brought, **before** the swap. Ids; the catalogue resolves them. */
    val blueHand: List<Int>,
    val redHand: List<Int>,
    val first: CardColor,
    val moves: List<PvpMove>,
    val stake: PvpStake,
    val status: PvpMatchStatus,
    val turnDeadline: Long?,
    val forfeitedBy: CardColor? = null,
    /** What each side has named under One or Diff. Absent until they name it. */
    val claimed: Map<CardColor, List<Int>> = emptyMap(),
    val claimDeadline: Long? = null,
) {
    /** Which side [accountId] is playing, or null if they are not in this match. */
    fun sideOf(accountId: Long): CardColor? = when (accountId) {
        blueAccount -> CardColor.BLUE
        redAccount -> CardColor.RED
        else -> null
    }

    fun accountOf(side: CardColor): Long = if (side == CardColor.BLUE) blueAccount else redAccount

    /**
     * The five cards [side] brought, as ids, **before** the swap.
     *
     * The wager is over these and not over what the side is holding when the dust settles. Under
     * Swap the two differ, and settling on the second would let a player win back a card they had
     * given away — or lose one they had only borrowed. See `TradeRules.directTransfers`.
     */
    fun dealtHand(side: CardColor): List<Int> = if (side == CardColor.BLUE) blueHand else redHand

    /**
     * The match as it stands, replayed from the inputs.
     *
     * Returns null when a card id names nothing in [cards], which is a corrupt row rather than a
     * playable match — better surfaced as a refused request than as a board with a hole in it.
     */
    fun replay(cards: CardCatalog): MatchState? {
        val blue = blueHand.map { cards.byId[it] ?: return null }
        val red = redHand.map { cards.byId[it] ?: return null }
        var state = MatchPreparation
            .prepareVersus(blue, red, first, rules, Random(seed))
            .state

        for (move in moves) {
            val hand = state.currentHand
            val card = hand.getOrNull(move.handIndex) ?: return null
            state = state.play(card, move.position)
        }
        return state
    }

    /**
     * What [side] may see, or null if the row cannot be replayed.
     *
     * The visibility comes back out of [MatchPreparation.prepareVersus], which draws the two sides
     * separately — so this is not blue's view mirrored.
     */
    fun viewFor(side: CardColor, cards: CardCatalog): MatchView? {
        val blue = blueHand.map { cards.byId[it] ?: return null }
        val red = redHand.map { cards.byId[it] ?: return null }
        val prepared = MatchPreparation.prepareVersus(blue, red, first, rules, Random(seed))
        val state = replay(cards) ?: return null

        return MatchView.of(
            state = state,
            side = side,
            opponentVisibility = prepared.visibilityFor(side),
            random = turnRandom(),
        )
    }

    /** [viewFor] on the wire, with the opponent's name and everything the screen needs. */
    fun wireFor(side: CardColor, opponentName: String, cards: CardCatalog): PvpMatchView? {
        val view = viewFor(side, cards) ?: return null

        return PvpMatchView.of(
            view = view,
            matchId = id,
            opponentName = opponentName,
            formatId = formatId,
            status = status,
            stake = stake,
            // Only the side that is on the clock is given one. A player who is waiting has no
            // deadline to render, and sending one would invite a countdown against the wrong turn.
            deadline = turnDeadline.takeIf { view.isMyTurn },
            outcome = outcomeFor(side, cards),
        )
    }

    /**
     * How this ended, from [side]'s point of view, or null while it is still being played.
     *
     * The forfeit case does not consult the board at all: a match abandoned at 3-1 is a loss for
     * whoever left, not a win for whoever was ahead. Reading the score would make walking away at
     * the right moment a strategy.
     */
    fun outcomeFor(side: CardColor, cards: CardCatalog): PvpOutcome? {
        if (status == PvpMatchStatus.PLAYING) return null
        val state = replay(cards) ?: return null
        val score = state.score
        val result = resultFor(side, score)
        val spoils = spoilsFor(side, cards) ?: return null
        val owed = picksOwedBy(side, cards)

        return PvpOutcome(
            result = result,
            blue = score.blue,
            red = score.red,
            forfeitedBy = forfeitedBy,
            stakeMgp = spoils.mgp,
            cardsWon = spoils.won,
            cardsLost = spoils.lost,
            picksOwed = owed,
            // The loser's hand, and **only** to the side that still owes a choice. It is the one
            // place this server puts a hand it has spent the whole match hiding on the wire, so the
            // gate is two conditions rather than one: you must be owed picks, and being owed picks
            // means you won.
            pickFrom = if (owed > 0) dealtHand(side.opposite()) else emptyList(),
            claimDeadline = claimDeadline.takeIf { owed > 0 },
        )
    }

    /**
     * How many of the opponent's cards [side] still has to name, or zero.
     *
     * Zero once they have named them — `claimed` holding an entry is what closes it — so this is
     * safe to ask on a settled match and answers the same thing on every poll.
     *
     * Gated on the match being **over**, not on its being `AWAITING_CLAIM`. That distinction is
     * load-bearing: `PvpReferee.settle` calls this to decide whether the status *should* be
     * `AWAITING_CLAIM`, so requiring it here would make the answer always zero and no match would
     * ever wait for a claim at all.
     */
    fun picksOwedBy(side: CardColor, cards: CardCatalog): Int {
        if (status == PvpMatchStatus.PLAYING) return 0
        if (side in claimed) return 0
        val state = replay(cards) ?: return 0
        if (resultFor(side, state.score) != MatchResult.WIN) return 0

        return TradeRules.picks(stake.trade, MatchResult.WIN, winnerScore(side, state.score))
    }

    /**
     * What the wager moves for [side]: MGP signed, and the cards each way.
     *
     * ### A forfeit settles at the maximum
     *
     * Whoever walked away loses as heavily as the rule allows: Direct is settled as though it were
     * All, and Diff at the full margin. The alternative is worse than it looks — a Direct match
     * conceded at 1-0 down would cost the forfeiter one card instead of five, which makes leaving
     * at the right moment the cheapest way to lose. That is precisely what `settleIfOverdue` and
     * [outcomeFor] refuse to allow elsewhere, and this is the same refusal applied to the wager.
     */
    fun spoilsFor(side: CardColor, cards: CardCatalog): Spoils? {
        val state = replay(cards) ?: return null
        val result = resultFor(side, state.score)
        val mgp = when (result) {
            MatchResult.WIN -> stake.mgp
            MatchResult.LOSE -> -stake.mgp
            MatchResult.DRAW -> 0
        }
        // A draw never moves a card, under any rule: any other reading makes a 5-5 a loss for
        // somebody, and Direct is no exception — it is settled by *result* here, not by capture.
        if (result == MatchResult.DRAW) return Spoils(mgp = mgp)

        val trade = if (forfeitedBy != null && stake.trade == TradeRule.DIRECT) {
            TradeRule.ALL
        } else {
            stake.trade
        }
        val moved = cardsUnder(trade, side, result == MatchResult.WIN, state)
        return moved.copy(mgp = mgp)
    }

    /** Which cards move under [trade], for [side]. The MGP is [spoilsFor]'s business. */
    private fun cardsUnder(
        trade: TradeRule,
        side: CardColor,
        won: Boolean,
        state: MatchState,
    ): Spoils = when (trade) {
        TradeRule.NONE -> Spoils()

        TradeRule.ALL -> Spoils(
            won = if (won) dealtHand(side.opposite()) else emptyList(),
            lost = if (won) emptyList() else dealtHand(side),
        )

        TradeRule.DIRECT -> {
            val transfers = TradeRules.directTransfers(
                state,
                CardColor.entries.associateWith(::dealtHand),
            )
            Spoils(won = transfers[side].orEmpty(), lost = transfers[side.opposite()].orEmpty())
        }

        // The winner's own list is what they named; the loser's is the same list, read from the
        // other side. Empty until the claim is made, which is what `AWAITING_CLAIM` is for.
        TradeRule.ONE, TradeRule.DIFF -> {
            val named = claimed[if (won) side else side.opposite()].orEmpty()
            Spoils(
                won = if (won) named else emptyList(),
                lost = if (won) emptyList() else named,
            )
        }
    }

    /** Whether this match still owes somebody a choice before it can be paid. */
    fun awaitsClaim(cards: CardCatalog): Boolean =
        CardColor.entries.any { picksOwedBy(it, cards) > 0 }

    /**
     * Whether [ids] is a claim [side] may actually make.
     *
     * Counted with multiplicity, not merely contained: a loser who brought two copies of a card may
     * lose both, and a winner naming the same card twice out of a hand holding one may not.
     */
    fun isClaimable(side: CardColor, ids: List<Int>, cards: CardCatalog): Boolean {
        if (ids.size != picksOwedBy(side, cards)) return false
        val available = dealtHand(side.opposite()).toMutableList()
        return ids.all { available.remove(it) }
    }

    /**
     * What the server names when nobody did, deterministically.
     *
     * The strongest cards in the loser's hand, so an inattentive winner is not punished for it, and
     * seeded from nothing at all — [Card.total] and a tie-break on the id is a total order, so the
     * pick is reproducible from the row like everything else on it.
     */
    fun autoClaim(side: CardColor, cards: CardCatalog): List<Int> = dealtHand(side.opposite())
        .mapNotNull { cards.byId[it] }
        .sortedWith(compareByDescending<Card> { it.total }.thenBy { it.id })
        .take(picksOwedBy(side, cards))
        .map { it.id }

    /**
     * The generator for this turn's Chaos roll.
     *
     * Mixed with the placement count rather than added to it, so that two matches whose seeds
     * differ by one do not walk each other's sequence one turn apart.
     */
    private fun turnRandom(): Random = Random(seed * TURN_MIX + moves.size)

    /** [side]'s result: a forfeit decides it outright, otherwise the board does. */
    private fun resultFor(side: CardColor, score: MatchScore): MatchResult = when {
        forfeitedBy == side -> MatchResult.LOSE
        forfeitedBy != null -> MatchResult.WIN
        else -> {
            val mine = scoreOf(side, score)
            val theirs = scoreOf(side.opposite(), score)
            when {
                mine > theirs -> MatchResult.WIN
                mine < theirs -> MatchResult.LOSE
                else -> MatchResult.DRAW
            }
        }
    }

    /**
     * The score Diff counts, which on a forfeit is the whole board.
     *
     * See [spoilsFor]: a forfeit settles at the maximum, so Diff takes five rather than however
     * many the abandoned board happened to show. Reading the real score here would mean a player
     * who left while ahead cost their opponent the prize.
     */
    private fun winnerScore(side: CardColor, score: MatchScore): Int =
        if (forfeitedBy != null) TOTAL_CARDS else scoreOf(side, score)

    private fun scoreOf(side: CardColor, score: MatchScore): Int =
        if (side == CardColor.BLUE) score.blue else score.red

    companion object {
        /** An odd multiplier, so the seed and the turn number do not cancel in the low bits. */
        private const val TURN_MIX = 31

        /**
         * How long the side to move has, in milliseconds: the game's own thirty-second turn, plus
         * two minutes to come back from a tunnel or a killed app.
         *
         * One number, and the client shows it as two — see `V2__pvp.sql`. The grace is what makes
         * "resume" mean anything on a phone, where the system kills applications without asking and
         * the player did not choose to leave.
         */
        const val TURN_MILLIS: Long = 30_000L
        const val GRACE_MILLIS: Long = 120_000L
        const val DEADLINE_MILLIS: Long = TURN_MILLIS + GRACE_MILLIS

        /** How long an invitation stands before it lapses. */
        const val CHALLENGE_MILLIS: Long = 60_000L

        /** How long a table stays in the lobby before it is treated as somebody who left. */
        const val TABLE_MILLIS: Long = 300_000L

        /**
         * How long a winner has to name their prize before the server names it for them.
         *
         * Generous, because unlike a turn there is nobody waiting on it — and bounded, because a
         * match that is never claimed is a match that is never paid, on **both** sides. The loser
         * has already lost and should not be left holding a card in limbo.
         */
        const val CLAIM_MILLIS: Long = 120_000L

        /**
         * Whether [move] is one the side to move may actually make.
         *
         * Checked against the **server's** view rather than against the raw board, so that the
         * Order and Chaos restrictions apply: a client that offers a card those rules do not allow
         * this turn is refused here, which is the only place the refusal can be trusted.
         */
        fun isLegal(view: MatchView, move: PvpMove): Boolean =
            move.handIndex in view.playableHandIndices && move.position in view.playablePositions()

        /** The hand a profile brings, resolved from its chosen deck. */
        fun handOf(deck: List<Int>, cards: CardCatalog): List<Card>? =
            deck.map { cards.byId[it] ?: return null }
    }
}

/**
 * What one side takes away from a settled match, beyond the flat payout.
 *
 * [won] and [lost] can both be non-empty at once — that is Direct, where each side keeps whatever
 * it captured — which is the reason this is a pair of lists rather than a single signed thing.
 */
data class Spoils(
    val mgp: Int = 0,
    val won: List<Int> = emptyList(),
    val lost: List<Int> = emptyList(),
)
