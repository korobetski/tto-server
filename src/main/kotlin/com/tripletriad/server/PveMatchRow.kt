package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.HandVisibility
import com.tripletriad.model.MatchPreparation
import com.tripletriad.model.MatchResult
import com.tripletriad.model.MatchState
import com.tripletriad.model.MatchView
import com.tripletriad.protocol.Placement
import com.tripletriad.protocol.PveMatchStatus
import com.tripletriad.protocol.PveMatchView
import com.tripletriad.protocol.PveMove
import com.tripletriad.protocol.PveOutcome
import com.tripletriad.protocol.RewardSummary
import kotlin.random.Random

/**
 * A live match against an opponent, as it is stored and as it is played.
 *
 * ### The row is the inputs, and the state is derived
 *
 * Nothing here holds a board. A match is its two hands, its seed, who started, the rules, and the
 * placements so far — and [position] turns those into a `MatchState` with the same engine that
 * decided them. `V3__pve.sql` argues the storage side of this; the reason it is *also* the right
 * shape in memory is that it makes the state impossible to get wrong by accident. There is no
 * cached board to fall out of step with the move list, because there is no cached board.
 *
 * That shape is also the whole of "a dropped connection must not be an abandon". The match lives
 * here, so coming back to it is an ordinary read and there is nothing on the client to lose.
 *
 * ### [moves] holds **both** sides, and the rest depends on that
 *
 * A transcript recorded only the player's placements and let the server *derive* the opponent's by
 * re-running `MatchAi` from the seed. That made the AI part of the replay: change how it plays and
 * every stored match replays differently.
 *
 * Here the opponent's placements are written down beside the player's, in one list, in the order
 * they happened. The row is therefore self-contained — it replays to what it replayed to yesterday
 * whatever the AI does next. Two things follow, and they are why the AI work this unblocks costs
 * no protocol version at all:
 *
 * - the opponent can be made cleverer, or retuned, without invalidating anything;
 * - a match interrupted by a deployment resumes instead of contradicting itself.
 *
 * `PveMatchRowTest.recordedOpponentMovesSurviveAChangeOfAi` is what holds this.
 *
 * ### The hands are stored, not re-dealt
 *
 * `PveMatches.assemble` deals from the player's *profile*, and under `RULE_RANDOM` it draws the
 * hand from the whole collection rather than from the deck. A collection changes as a player wins
 * cards, so re-dealing a week-old row against the live profile would deal a different match. The
 * deal happens once, when the match is opened, and the five cards each side actually got are what
 * is stored — as [PvpMatchRow] stores them, for a reason it never had to state because a person's
 * hand was never drawn from anything else.
 *
 * Stored **before the swap**: `RULE_SWAP` is derived from the seed, so keeping the post-swap hands
 * as well would be keeping one fact twice and inviting the two copies to disagree.
 *
 * ### Chaos, and why the seed is not enough on its own
 *
 * Under `OrderRule.CHAOS` the playable card is a roll, and the server takes it. But a view can be
 * built on any read, and a fresh roll each time would offer the player a different card every time
 * they looked. So the roll is seeded from the match *and* the placement number: any number of reads
 * at the same point give the same answer, and the next placement gives a new one. See [turnRandom].
 *
 * @property blueHand the five cards the player brought, by id. Blue is always the player — see
 *   [PveMatchView], which has no `side` field for the same reason.
 * @property redHand the five the opponent brought, from its fetish cards topped up out of its pool.
 * @property reward what the match paid, written at settlement. **Not derivable**, which is why it
 *   is stored rather than recomputed on each read: the payout rolls a random top-up, rolls the drop
 *   table and spends whatever boons the profile happened to be holding. Null until the match ends.
 */
data class PveMatchRow(
    val id: String,
    val accountId: Long,
    val formatId: String,
    val opponentIconId: String,
    val rules: GameRules,
    val seed: Int,
    val blueHand: List<Int>,
    val redHand: List<Int>,
    val first: CardColor,
    val moves: List<PveMove>,
    val status: PveMatchStatus,
    val reward: RewardSummary? = null,
) {
    /**
     * The match as it stands, replayed from the inputs.
     *
     * Returns null when a card id names nothing in [cards], or when a stored move is not one the
     * rules allow. Both are a corrupt row rather than a playable match, and better surfaced as a
     * refused request than as a board with a hole in it.
     *
     * ### Sudden Death is replayed, not stored
     *
     * A drawn match under `RULE_SUDDEN_DEATH` is not over: each side takes the cards it ended up
     * owning and a new board begins (`MatchState.suddenDeathRematch`). The rematch is derived here
     * rather than recorded, because everything it needs is already in the row — the regrouping is a
     * function of the finished board, and the new elements, swap and Open draws come out of the
     * same generator this replay is already walking. So [moves] runs on past the ninth placement,
     * and how many boards have been played is something [PveMatchPosition.rematch] counts rather
     * than a column to be kept in step.
     */
    fun position(cards: CardCatalog): PveMatchPosition? {
        val blue = blueHand.map { cards.byId[it] ?: return null }
        val red = redHand.map { cards.byId[it] ?: return null }

        // One generator for the whole row, walked in the order the deal walked it. A rematch draws
        // from where the previous board left it, so a fresh `Random(seed)` per board would replay
        // the same elements and the same Three Open slots every time.
        val random = Random(seed)
        val opening = MatchPreparation.prepareVersus(blue, red, first, rules, random)

        return walk(
            PveMatchPosition(opening.state, opening.blueSeesRed, opening.redSeesBlue, 0),
            random,
        )
    }

    /**
     * Applies every stored placement in order, starting a new board wherever one ended.
     *
     * The trailing [boardFor] is not redundant. Without it a Sudden Death draw would leave the
     * position on the board that *drew* — full, finished, with no current player — until the next
     * move happened to be applied. Every caller asking whose turn it is would be told nobody's, and
     * the opponent would never take its turn on the new board because nothing would ever notice one
     * had begun.
     *
     * So a position always names the board the **next** placement will be played on. It costs
     * nothing in determinism: the rematch is prepared from the generator at exactly the point the
     * next `walk` will prepare it from, so both reads produce the same board.
     */
    private fun walk(from: PveMatchPosition, random: Random): PveMatchPosition? {
        var at = from
        for (move in moves) {
            at = boardFor(at, random)?.advanced(move) ?: return null
        }
        // Null here means the match is genuinely over, and the finished board is the answer.
        return boardFor(at, random) ?: at
    }

    /**
     * The board [move] will be played on: this one, or the next after a Sudden Death draw.
     *
     * Null is the corrupt-row answer — moves left over on a board that was full *and settled* are
     * placements the match had no room for.
     */
    private fun boardFor(at: PveMatchPosition, random: Random): PveMatchPosition? = when {
        !at.state.isFinished -> at
        !continuesAfter(at.state) -> null
        else -> MatchPreparation.prepareRematch(at.state, random).let { next ->
            PveMatchPosition(
                state = next.state,
                blueSeesRed = next.opponentVisibility,
                redSeesBlue = next.playerVisibility,
                rematch = at.rematch + 1,
            )
        }
    }

    /** The board as it stands, or null on a row that cannot be replayed. */
    fun replay(cards: CardCatalog): MatchState? = position(cards)?.state

    /**
     * What the player may see, or null if the row cannot be replayed.
     *
     * The visibility comes back out of the replay, which draws the two sides separately — so this
     * is not the opponent's view mirrored. See [MatchPreparation.prepareVersus]. For the opponent's
     * own view, which is all the AI is entitled to, ask [position] and then
     * [PveMatchPosition.viewFor].
     */
    fun viewFor(cards: CardCatalog): MatchView? =
        position(cards)?.viewFor(CardColor.BLUE, turnRandom())

    /**
     * [viewFor] on the wire, with everything the screen needs.
     *
     * @param plays the placements this answer is announcing — see [PveMatchView.plays]. Empty on a
     *   plain read, which is deliberate: resuming a match is not a thing to animate.
     */
    fun wireFor(cards: CardCatalog, plays: List<Placement> = emptyList()): PveMatchView? {
        val at = position(cards) ?: return null

        return PveMatchView.of(
            view = at.viewFor(CardColor.BLUE, turnRandom()),
            matchId = id,
            opponentIconId = opponentIconId,
            formatId = formatId,
            status = status,
            rematch = at.rematch,
            outcome = outcomeFrom(at),
            plays = plays,
        )
    }

    /**
     * Whether the board is full **and** that is the end of it.
     *
     * The distinction exists only because of Sudden Death: nine placements normally settle a match,
     * and under that rule a draw settles nothing. A caller treating `state.isFinished` as the end
     * would credit a drawn match that is about to be played again.
     */
    fun isOver(cards: CardCatalog): Boolean {
        val state = replay(cards) ?: return false
        return state.isFinished && !continuesAfter(state)
    }

    /** How this ended from the player's side, or null while it is still being played. */
    private fun outcomeFrom(at: PveMatchPosition): PveOutcome? {
        if (status == PveMatchStatus.PLAYING) return null
        val score = at.state.score
        val result = when {
            score.blue > score.red -> MatchResult.WIN
            score.blue < score.red -> MatchResult.LOSE
            else -> MatchResult.DRAW
        }
        // `player` is filled in by whoever credited the match — the row holds no profile, and
        // reading one here would make the answer depend on when it was asked.
        return PveOutcome(result = result, blue = score.blue, red = score.red, reward = reward)
    }

    /**
     * The generator for this turn's Chaos roll.
     *
     * Mixed with the placement count rather than added to it, so that two matches whose seeds
     * differ by one do not walk each other's sequence one turn apart.
     */
    private fun turnRandom(): Random = Random(seed * TURN_MIX + moves.size)

    private fun continuesAfter(state: MatchState): Boolean =
        state.rules.suddenDeath && state.score.winner() == null

    companion object {
        /** An odd multiplier, so the seed and the turn number do not cancel in the low bits. */
        private const val TURN_MIX = 31

        /**
         * Whether [move] is one the side to move may actually make.
         *
         * Checked against the **server's** view rather than against the raw board, so that the
         * Order and Chaos restrictions apply: a client offering a card those rules do not allow
         * this turn is refused here, which is the only place the refusal can be trusted.
         */
        fun isLegal(view: MatchView, move: PveMove): Boolean =
            move.handIndex in view.playableHandIndices &&
                move.position in view.playablePositions()
    }
}

/**
 * Where a row stands: the board, what each side may see of the other, and how many boards in.
 *
 * One value rather than three returns, because they are derived together and cost the same replay.
 * A caller asking for the state and then for the visibility would walk the whole match twice and
 * could be handed two answers from different points in it.
 *
 * @property rematch how many Sudden Death rematches have been played. 0 on the first board.
 */
data class PveMatchPosition(
    val state: MatchState,
    val blueSeesRed: HandVisibility,
    val redSeesBlue: HandVisibility,
    val rematch: Int,
) {
    /** What [side] may see of the other hand — the argument [MatchView.of] wants. */
    fun visibilityFor(side: CardColor): HandVisibility =
        if (side == CardColor.BLUE) blueSeesRed else redSeesBlue

    /**
     * This position as [side] sees it.
     *
     * The **only** way the opponent's turn should reach the AI. Handing it the `MatchState` would
     * hand it both hands, which is how a program ends up ignoring All Open and Three Open rather
     * than obeying them.
     */
    fun viewFor(side: CardColor, random: Random): MatchView =
        MatchView.of(state, side, visibilityFor(side), random)

    /**
     * This position after [move], or null if the move is not one the rules allow.
     *
     * Both halves are needed and they fail differently: a slot outside the hand is a corrupt row,
     * and a cell that is taken is what an off-by-one produces. Neither may reach `MatchState.play`,
     * which throws.
     */
    fun advanced(move: PveMove): PveMatchPosition? {
        val mover = state.currentPlayer
        val card = state.currentHand
            .getOrNull(move.handIndex)
            ?.takeIf { mover != null && move.position in state.playablePositions() }
        return card?.let {
            copy(
                state = state.play(it, move.position),
                // **The visibility follows the hand, and forgetting that is a real bug this had.**
                // `HandVisibility` names *positions*, and `MatchState.play` closes the gap rather
                // than leaving a hole — so a set of three positions keeps pointing at whatever now
                // sits at them. Under Three Open the effect was visible from the sofa: the
                // opponent played, the hand shifted down, and a card that had been face down all
                // match turned face up. See `HandVisibility.afterPlaying`, which exists for exactly
                // this and which this function was not calling.
                //
                // Only the mover's side re-indexes. `blueSeesRed` is indexed into *red's* hand, so
                // it moves when red plays and not when blue does.
                blueSeesRed = if (mover == CardColor.RED) {
                    blueSeesRed.afterPlaying(move.handIndex)
                } else {
                    blueSeesRed
                },
                redSeesBlue = if (mover == CardColor.BLUE) {
                    redSeesBlue.afterPlaying(move.handIndex)
                } else {
                    redSeesBlue
                },
            )
        }
    }
}
