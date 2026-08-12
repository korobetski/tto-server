package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.model.Card
import com.tripletriad.model.CardCollection
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.MatchPreparation
import com.tripletriad.model.MatchResult
import com.tripletriad.model.MatchState
import com.tripletriad.model.MatchView
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
data class PvpMatchRow(
    val id: String,
    val blueAccount: Long,
    val redAccount: Long,
    val collection: CardCollection,
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
) {
    /** Which side [accountId] is playing, or null if they are not in this match. */
    fun sideOf(accountId: Long): CardColor? = when (accountId) {
        blueAccount -> CardColor.BLUE
        redAccount -> CardColor.RED
        else -> null
    }

    fun accountOf(side: CardColor): Long = if (side == CardColor.BLUE) blueAccount else redAccount

    /** The card this side put up, or null when the match is played for MGP only. */
    fun stakeOf(side: CardColor): Int? = when (val wager = stake) {
        PvpStake.None -> null
        is PvpStake.Cards ->
            if (side == CardColor.BLUE) wager.challengerCard else wager.opponentCard
    }

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
            collection = collection,
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

        val result = when {
            forfeitedBy == side -> MatchResult.LOSE
            forfeitedBy != null -> MatchResult.WIN
            else -> resultFor(side, score.blue, score.red)
        }
        return PvpOutcome(
            result = result,
            blue = score.blue,
            red = score.red,
            forfeitedBy = forfeitedBy,
        )
    }

    /**
     * The generator for this turn's Chaos roll.
     *
     * Mixed with the placement count rather than added to it, so that two matches whose seeds
     * differ by one do not walk each other's sequence one turn apart.
     */
    private fun turnRandom(): Random = Random(seed * TURN_MIX + moves.size)

    private fun resultFor(side: CardColor, blue: Int, red: Int): MatchResult {
        val mine = if (side == CardColor.BLUE) blue else red
        val theirs = if (side == CardColor.BLUE) red else blue
        return when {
            mine > theirs -> MatchResult.WIN
            mine < theirs -> MatchResult.LOSE
            else -> MatchResult.DRAW
        }
    }

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
