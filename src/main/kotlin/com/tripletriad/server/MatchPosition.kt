package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.HandVisibility
import com.tripletriad.model.MatchState
import com.tripletriad.model.MatchView
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
