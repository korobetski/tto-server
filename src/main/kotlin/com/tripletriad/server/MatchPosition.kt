package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.HandVisibility
import com.tripletriad.model.MatchPreparation
import com.tripletriad.model.MatchState
import com.tripletriad.model.MatchView
import com.tripletriad.model.PlayResult
import kotlin.random.Random

/**
 * Where a match stands: the board, what each side may see of the other, and how many boards in.
 *
 * One value rather than three returns, because they are derived together and cost the same replay.
 * A caller asking for the state and then for the visibility would walk the whole match twice and
 * could be handed two answers from different points in it.
 *
 * ### Shared by both kinds of refereed match
 *
 * It began as the environment match's own and is now the multiplayer one's too, which fixed two
 * things at a stroke. Sudden Death is the obvious one: `PvpMatchRow` had no notion of a second
 * board, so the rule was offerable on a table and settled a draw as a draw. The quieter one is
 * [advanced] — the visibility re-indexing below — which the multiplayer replay never did at all,
 * so under Three Open a card that had been face down all match turned face up the moment the hand
 * closed up over a played slot.
 *
 * @property rematch how many Sudden Death rematches have been played. 0 on the first board.
 */
data class MatchPosition(
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
     * This position after a placement, or null if it is not one the rules allow.
     *
     * Both halves are needed and they fail differently: a slot outside the hand is a corrupt row,
     * and a cell that is taken is what an off-by-one produces. Neither may reach `MatchState.play`,
     * which throws.
     *
     * Takes the two numbers rather than a move, because `PveMove` and `PvpMove` are the same two
     * numbers under different names and this is the one place both kinds of match meet.
     */
    fun advanced(handIndex: Int, position: Int): MatchPosition? {
        val mover = state.currentPlayer
        val card = state.currentHand
            .getOrNull(handIndex)
            ?.takeIf { mover != null && position in state.playablePositions() }
        return card?.let {
            copy(
                state = state.play(it, position),
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
                    blueSeesRed.afterPlaying(handIndex)
                } else {
                    blueSeesRed
                },
                redSeesBlue = if (mover == CardColor.BLUE) {
                    redSeesBlue.afterPlaying(handIndex)
                } else {
                    redSeesBlue
                },
            )
        }
    }
}

/**
 * Whether a finished board is the end of the match, or Sudden Death starts another.
 *
 * The distinction exists only because of that rule: nine placements normally settle a match, and
 * under Sudden Death a draw settles nothing. A caller reading `state.isFinished` as "over" would
 * credit a match that is about to be played again.
 */
internal val MatchState.settlesTheMatch: Boolean
    get() = !(rules.suddenDeath && score.winner() == null)

/**
 * The board the next placement lands on: this one, or the next after a Sudden Death draw.
 *
 * Null is the corrupt-row answer — placements left over on a board that was full *and* settled are
 * moves the match had no room for.
 *
 * [random] is walked, not re-seeded. A rematch draws its elements, its swap and its Three Open
 * slots from where the previous board left the generator, so a fresh `Random(seed)` per board would
 * replay the same ones every time.
 */
internal fun MatchPosition.boardFor(random: Random): MatchPosition? = when {
    !state.isFinished -> this
    state.settlesTheMatch -> null
    else -> MatchPreparation.prepareRematch(state, random).let { next ->
        MatchPosition(
            state = next.state,
            blueSeesRed = next.opponentVisibility,
            redSeesBlue = next.playerVisibility,
            rematch = rematch + 1,
        )
    }
}

/**
 * A replay: where the match ended up, and what each placement did on the way.
 *
 * Both come out of one walk because they cost the same walk. A caller asking for the position and
 * then for the placements would replay the match twice — and, worse, could be handed two answers
 * from different points in it if the row changed in between.
 *
 * @property plays one entry per stored placement, in order, each as the engine resolved it: who
 *   moved, which card, onto which cell, and every cell it flipped with the rule that did it.
 */
data class Replay(val end: MatchPosition, val plays: List<PlayResult>)

/**
 * Applies [moves] in order from this position, starting a new board wherever one ended.
 *
 * ### Why both kinds of match share this
 *
 * `PveMatchRow` and `PvpMatchRow` had a copy each, identical line for line, plus a third would have
 * been needed for the console's match inspector — which is the one caller that wants the
 * placements rather than the end. Three copies of the replay that decides who won a disputed match
 * is three chances for two of them to disagree, and the disagreement would surface as an
 * arbitration built on a board the referee never saw.
 *
 * ### The trailing [boardFor] is not redundant
 *
 * Without it a Sudden Death draw would leave [Replay.end] on the board that *drew* — full,
 * finished, with no current player — until the next placement happened to be applied. Every caller
 * asking whose turn it is would be told nobody's, and the opponent would never take its turn on the
 * new board because nothing would ever notice one had begun.
 *
 * So the end position always names the board the **next** placement will be played on. It costs
 * nothing in determinism: the rematch is prepared from the generator at exactly the point the next
 * replay will prepare it from, so both reads produce the same board.
 *
 * @param moves each placement as `handIndex to position`, which is what `PveMove` and `PvpMove`
 *   both are under different names.
 * @return null when a placement is not one the rules allow — a corrupt row rather than a playable
 *   match, and better surfaced as a refused request than as a board with a hole in it.
 */
internal fun MatchPosition.replaying(moves: List<Pair<Int, Int>>, random: Random): Replay? {
    var at = this
    val plays = ArrayList<PlayResult>(moves.size)
    for ((handIndex, position) in moves) {
        // Two failures, one refusal. A placement the rules refuse is a corrupt row; a placement
        // that lands without `lastPlay` behind it would be `MatchState.play` failing to record what
        // it had just done, which is a bug rather than a row — and neither leaves a walk that can
        // be continued, so telling them apart here would buy the caller nothing to do differently.
        val next = at.boardFor(random)?.advanced(handIndex, position)
        val play = next?.state?.lastPlay ?: return null
        at = next
        plays.add(play)
    }
    // Null means the match is genuinely over, and the finished board is the answer.
    return Replay(end = at.boardFor(random) ?: at, plays = plays)
}
