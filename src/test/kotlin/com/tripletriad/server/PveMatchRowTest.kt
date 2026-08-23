package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardSet
import com.tripletriad.data.Format
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.HandVisibility
import com.tripletriad.model.MatchAi
import com.tripletriad.model.OpenRule
import com.tripletriad.protocol.PveMatchStatus
import com.tripletriad.protocol.PveMove
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PveMatchRow] against fixtures: no database, no HTTP, no shipped catalogue.
 *
 * ### The assertion this file exists for
 *
 * [aStoredOpponentMoveIsReplayedRatherThanRecomputed]. Everything else here is about a row
 * replaying correctly; that one is about *what a row is for*, and it is the one property whose loss
 * would not show up as a failure anywhere else.
 *
 * A transcript recorded only the player's placements and let the server derive the opponent's by
 * re-running `MatchAi` from the seed. Re-introducing that here — "the moves are half redundant, the
 * seed already determines them" — is a natural-looking optimisation that would silently rewrite the
 * opponent's moves in every stored match the next time the AI changed, including matches a player
 * was in the middle of. The test below fails if anybody tries it.
 */
class PveMatchRowTest {

    // ---- Fixtures ---------------------------------------------------------

    private val set =
        CardSet(blocks = listOf(BLOCK), slug = "test", nameKey = "APP_TEST", sortOrder = 1)

    private fun card(number: Int, power: Int) = Card(
        id = Card.idFor(block = BLOCK, number = number),
        nameKey = "STR_TEST_$number",
        name = "Test $number",
        top = power,
        right = power,
        bottom = power,
        left = power,
        rarity = 1,
    )

    /**
     * Ten cards that cannot capture each other, so any nine placements end 5-5.
     *
     * Both comparisons in `RulesEngine.basicRule` are strict, so equal powers never flip anything —
     * which is what makes a **drawn** match constructible by hand rather than hunted for across
     * seeds. Sudden Death is the only rule that cares about a draw, and it is the reason this
     * fixture exists.
     */
    private val flat = CardCatalog(sets = listOf(set), cards = (1..CARDS).map { card(it, MID) })

    /** The same ten cards with powers that differ, so the AI has a preference to have. */
    private val varied =
        CardCatalog(sets = listOf(set), cards = (1..CARDS).map { card(it, it % ACE + 1) })

    private val format =
        Format(id = "test", nameKey = "APP_TEST", blocks = listOf(BLOCK), rules = emptyList())

    private fun row(
        catalog: CardCatalog,
        rules: GameRules = GameRules(),
        moves: List<PveMove> = emptyList(),
        first: CardColor = CardColor.BLUE,
    ) = PveMatchRow(
        id = "a-match",
        accountId = 1L,
        formatId = format.id,
        opponentIconId = "an-opponent",
        rules = rules,
        seed = SEED,
        blueHand = catalog.admittedBy(format).take(HAND).map { it.id },
        redHand = catalog.admittedBy(format).drop(HAND).take(HAND).map { it.id },
        first = first,
        moves = moves,
        status = PveMatchStatus.PLAYING,
    )

    /** Nine placements: always the first card in hand, into cells 0 through 8. */
    private fun wholeBoard() = List(PLACEMENTS) { PveMove(handIndex = 0, position = it) }

    // ---- Replaying --------------------------------------------------------

    /**
     * **Three Open shows three cards for the whole match, and it was showing more as it went on.**
     *
     * `HandVisibility` names *positions*, and `MatchState.play` closes the hand over the slot that
     * was played rather than leaving a hole — so a set of three positions starts naming different
     * cards the moment one is played. [PveMatchPosition.advanced] was not calling
     * [HandVisibility.afterPlaying], and the result was visible from the sofa: the opponent played,
     * their hand shifted down, and a card that had been face down all match turned face up.
     *
     * Asserted in both directions on purpose. That nothing new is revealed is the bug; that
     * everything already revealed *stays* revealed is the over-correction that shifting the wrong
     * way would produce, and it would look almost right.
     */
    @Test
    fun aHiddenCardDoesNotTurnFaceUpAsTheOpponentsHandShrinks() {
        val rules = GameRules(open = OpenRule.THREE_OPEN)
        val moves = wholeBoard()
        val start = assertNotNull(row(flat, rules).position(flat))
        val opening = start.blueSeesRed
            .visible(start.state.hands[CardColor.RED].orEmpty())
            .map { it.id }
            .toSet()
        assertEquals(HandVisibility.THREE_OPEN_COUNT, opening.size, "the deal should show three")

        for (played in 1..moves.size) {
            val at = assertNotNull(row(flat, rules, moves.take(played)).position(flat))
            val red = at.state.hands[CardColor.RED].orEmpty()
            val seen = at.blueSeesRed.visible(red).map { it.id }.toSet()

            assertEquals(
                red.map { it.id }.filter { it in opening }.toSet(),
                seen,
                "after $played placements the face-up cards are not the ones dealt face up",
            )
        }
    }

    @Test
    fun anUnplayedRowReplaysToAnEmptyBoardWithTheDealtHands() {
        val at = assertNotNull(row(varied).position(varied))

        assertEquals(0, at.state.placement)
        assertEquals(CardColor.BLUE, at.state.currentPlayer)
        assertEquals(HAND, at.state.hands[CardColor.BLUE]?.size)
        assertEquals(HAND, at.state.hands[CardColor.RED]?.size)
    }

    /** Reading a row twice is reading the same match — the property every other read rests on. */
    @Test
    fun theSameRowAlwaysReplaysToTheSameBoard() {
        val subject = row(varied, moves = wholeBoard().take(4))

        assertEquals(
            assertNotNull(subject.replay(varied)).board,
            assertNotNull(subject.replay(varied)).board,
        )
    }

    /**
     * **A stored opponent placement is honoured, whatever the AI would have chosen.**
     *
     * The fixture deliberately stores a move the AI *rejects*: the opponent is put on the opening
     * move, the AI is asked what it would play, and a different legal cell is written down instead.
     * A row that re-derived its opponent's moves would replay the AI's choice and this would fail.
     *
     * See this class's note for why that would be worse than it sounds.
     */
    @Test
    fun aStoredOpponentMoveIsReplayedRatherThanRecomputed() {
        val opening = row(varied, first = CardColor.RED)
        val at = assertNotNull(opening.position(varied))
        val wouldPlay = assertNotNull(MatchAi().choose(at.state, Random(SEED)))
        val insteadPlay = at.state.playablePositions().first { it != wouldPlay.position }

        val stored = row(
            varied,
            first = CardColor.RED,
            moves = listOf(PveMove(handIndex = 0, position = insteadPlay)),
        )
        val after = assertNotNull(stored.position(varied))

        assertNotEquals(wouldPlay.position, insteadPlay, "the fixture must offer a second cell")
        assertEquals(1, after.state.placement)
        assertEquals(
            CardColor.RED,
            after.state.board[insteadPlay]?.owner,
            "the opponent's recorded cell is empty: the row re-derived its move",
        )
        assertNull(
            after.state.board[wouldPlay.position],
            "the AI's own choice was played instead of the one written down",
        )
    }

    /**
     * A match interrupted mid-way comes back where it was.
     *
     * This is what "a dropped connection is not an abandon" amounts to on the storage side. Nothing
     * about it is clever — that is the point, and it is why the row holds inputs rather than a
     * board.
     */
    @Test
    fun anInterruptedRowResumesAtTheRightPlacement() {
        val interrupted = row(varied, moves = wholeBoard().take(5))
        val at = assertNotNull(interrupted.position(varied))

        assertEquals(5, at.state.placement)
        assertFalse(at.state.isFinished)
        assertEquals(PLACEMENTS - 5, at.state.playablePositions().size)
    }

    // ---- Refusals ---------------------------------------------------------

    @Test
    fun aRowNamingAnUnknownCardIsRefusedRatherThanReplayedWithAHoleInIt() {
        // A well-formed id — `Card.idFor` refuses a number outside 1..255 — that names no card in
        // this catalogue. The refusal being tested is the lookup's, not the id's.
        val corrupt = row(varied).copy(blueHand = List(HAND) { Card.idFor(BLOCK, ABSENT) })

        assertNull(corrupt.position(varied))
        assertFalse(corrupt.isOver(varied))
    }

    @Test
    fun aRowNamingAnOccupiedCellIsRefused() {
        val corrupt = row(varied, moves = listOf(PveMove(0, 4), PveMove(0, 4)))

        assertNull(corrupt.position(varied), "the same cell was played twice and nothing objected")
    }

    // ---- Sudden Death -----------------------------------------------------

    /**
     * Nine placements settle a match — unless nobody won and Sudden Death is on.
     *
     * The pair below is the whole of that distinction, and it matters because a caller treating
     * `state.isFinished` as the end would credit a drawn match that is about to be played again.
     */
    @Test
    fun aFullBoardEndsTheMatchWhenSuddenDeathIsNotInForce() {
        val finished = row(flat, moves = wholeBoard())
        val at = assertNotNull(finished.position(flat))

        assertTrue(at.state.isFinished)
        assertEquals(0, at.rematch)
        assertTrue(finished.isOver(flat))
        assertEquals(at.state.score.blue, at.state.score.red, "the fixture must draw")
    }

    @Test
    fun aSuddenDeathDrawContinuesOntoAFreshBoard() {
        val drawn = row(flat, rules = GameRules(suddenDeath = true), moves = wholeBoard())
        val at = assertNotNull(drawn.position(flat))

        assertFalse(drawn.isOver(flat), "a drawn Sudden Death match is not over")
        assertEquals(1, at.rematch)
        assertEquals(0, at.state.placement, "the rematch should be an empty board")
        assertNotNull(at.state.currentPlayer, "somebody has to be on move for the rematch")
    }

    /**
     * The rematch is derived from the seed, and derived the same way every time.
     *
     * It is not stored — the regrouped hands, the new elements and the fresh Open draws all come
     * out of the generator the replay is already walking. So the only thing keeping two reads of
     * one row agreeing is that they walk it identically, which is what this asserts.
     */
    @Test
    fun aRematchIsDerivedIdenticallyOnEveryRead() {
        val drawn = row(flat, rules = GameRules(suddenDeath = true), moves = wholeBoard())

        val first = assertNotNull(drawn.position(flat))
        val second = assertNotNull(drawn.position(flat))

        assertEquals(first.state.hands, second.state.hands)
        assertEquals(first.state.board.elements, second.state.board.elements)
        assertEquals(first.blueSeesRed, second.blueSeesRed)
        assertEquals(first.redSeesBlue, second.redSeesBlue)
    }

    /** A placement on the rematch board lands there, not on the board that drew. */
    @Test
    fun theRematchIsPlayedOnRatherThanTheBoardThatDrew() {
        val moves = wholeBoard() + PveMove(handIndex = 0, position = 0)
        val playing = row(flat, rules = GameRules(suddenDeath = true), moves = moves)
        val at = assertNotNull(playing.position(flat))

        assertEquals(1, at.rematch)
        assertEquals(1, at.state.placement)
        assertNotNull(at.state.board[0])
    }

    private companion object {
        const val BLOCK = 1
        const val CARDS = 10
        const val HAND = 5

        /** A card number this fixture's catalogue does not go up to. */
        const val ABSENT = 200
        const val PLACEMENTS = 9
        const val MID = 5
        const val ACE = 10
        const val SEED = 20260817
    }
}
