package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardSet
import com.tripletriad.data.Format
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.protocol.PveMatchStatus
import com.tripletriad.protocol.PveMatchView
import com.tripletriad.protocol.PveMove
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The deferred opening, met with Sudden Death.
 *
 * ### Why this file exists
 *
 * `PveReferee.view` now computes and writes a placement — the opening a toss the opponent won
 * leaves owed — where before it only read. That is safe exactly as long as "the opponent is on
 * move and has not moved" means *one* thing, and Sudden Death is the rule that could make it mean
 * two: a drawn board is not the end of the match, `MatchState.suddenDeathRematch` keeps the turn
 * order, and the rematch board starts empty at placement zero with the same side on move as the
 * deal had. Every ingredient of a fresh deal, in the middle of a match.
 *
 * So the question this answers is whether a *read* can put a card on a rematch board. It must not:
 * `PveReferee.record` writes the player's placement and every reply the opponent owes in one
 * statement, across the board boundary, so the row is never left with red to move. If that ever
 * stopped being true, a client refreshing mid-match would deal itself an opponent's turn.
 *
 * ### The fixture draws on purpose
 *
 * Ten cards with equal powers on all four sides. Both comparisons in the capture rule are strict,
 * so nothing ever flips, every side keeps what it played, and nine placements end 5-5 — the same
 * trick `PveMatchRowTest` uses, and the only way to reach Sudden Death without hunting for a seed
 * that happens to draw. Forty seeds of ordinary play were tried first and produced none.
 */
class PveSuddenDeathTest {

    private val accounts = AccountStore(Postgres.dataSource)
    private val pve = PveStore(Postgres.dataSource)

    /**
     * **A read never plays, and a drawn board is the case that could have made it.**
     *
     * One match, driven placement by placement, with a read between every one of them. The deal is
     * left owing its opening — `first = RED` — so the first read is the one that starts the match,
     * and after that no read may ever announce anything again, including the read that lands on
     * the rematch board.
     */
    @Test
    fun aReadStartsTheMatchAndThenNeverPlaysAgain() {
        val accountId = account("sd-read")
        val referee = referee()
        val row = row(accountId, first = CardColor.RED)
        assertNotNull(pve.open(row), "the fixture row has to be storable")

        // The opening is owed, exactly as it is on a real deal, and the read is what pays it.
        val begun = assertNotNull(referee.view(row.id, accountId))

        assertEquals(1, begun.plays.size, "the first read plays the opening the toss owed")
        assertEquals(1, begun.placement)

        var view = begun
        var reads = 0
        while (view.status == PveMatchStatus.PLAYING && view.rematch == 0) {
            val move = PveMove(
                handIndex = view.playable.firstOrNull() ?: break,
                position = view.cells.indexOfFirst { it == null },
            )
            view = accepted(referee, row.id, accountId, move)

            // The claim, asserted after every single placement rather than once at the end: a read
            // is a read. It may not append, and it may not announce something it did not append.
            val read = assertNotNull(referee.view(row.id, accountId))
            reads++
            assertTrue(read.plays.isEmpty(), "read $reads announced a placement it did not make")
            assertEquals(view.cells, read.cells, "read $reads moved a card")
            assertEquals(view.placement, read.placement, "read $reads advanced the match")
            view = read
        }

        assertEquals(1, view.rematch, "the fixture has to reach Sudden Death to be worth anything")
        assertTrue(reads >= 4, "every placement of the first board should have been read across")
    }

    /**
     * **The rematch's own opening travels with the placement that caused it, not with a read.**
     *
     * `PveReferee.replies` loops across the board boundary on purpose, so the answer that fills the
     * ninth cell also carries the opponent's first card on the *new* board. That is what leaves the
     * row with blue to move and nothing owed, which is the whole reason a read is still safe.
     *
     * Left deliberately as the description of what happens rather than what should: the rematch's
     * opening still lands on the client under its own announcements, which is the same complaint
     * the deal's opening used to draw. Fixing that is a separate change; this pins the behaviour so
     * it cannot move by accident in the meantime.
     */
    @Test
    fun theRematchOpeningArrivesWithTheAnswerThatStartedTheRematch() {
        val accountId = account("sd-rematch")
        val referee = referee()
        val row = row(accountId, first = CardColor.RED)
        assertNotNull(pve.open(row))

        var view = assertNotNull(referee.view(row.id, accountId))
        var last = view
        while (view.status == PveMatchStatus.PLAYING && view.rematch == 0) {
            val move = PveMove(
                handIndex = view.playable.firstOrNull() ?: break,
                position = view.cells.indexOfFirst { it == null },
            )
            last = accepted(referee, row.id, accountId, move)
            view = last
        }

        assertEquals(1, last.rematch, "the drawn board continues rather than settling")
        assertEquals(
            1,
            last.placement,
            "the opponent's card is already on the rematch board, in the same answer",
        )
        assertTrue(
            last.plays.any { it.player == CardColor.RED },
            "and that placement is announced, so a client has something to animate",
        )
        assertTrue(last.playable.isNotEmpty(), "which leaves the player on move, owing nothing")
    }

    // ---- Harness ----------------------------------------------------------

    private fun accepted(
        referee: PveReferee,
        matchId: String,
        accountId: Long,
        move: PveMove,
    ): PveMatchView {
        val played = referee.play(matchId, accountId, move)
        assertTrue(played is Moved.Accepted, "the move was refused: $played")
        return played.view
    }

    /**
     * A referee on the flat catalogue.
     *
     * The shipped rosters are passed for the two lookups that survive into a match — an opponent
     * whose icon this fixture's row does not name simply resolves to null, which `opponentMove`
     * already treats as "play the default game" rather than as a reason not to move.
     */
    private fun referee(): PveReferee = PveReferee(
        flat,
        Catalogs.npcs,
        Catalogs.formats,
        accounts,
        pve,
        Catalogs.campaigns,
        System::currentTimeMillis,
    ) { Random(SEED) }

    private fun account(prefix: String): Long {
        val name = Postgres.freshAccount(prefix)
        val save = GameSave.new(name, createdAt = CREATED)
        return assertNotNull(accounts.register(name, "hash-$name", save))
    }

    private fun row(accountId: Long, first: CardColor) = PveMatchRow(
        id = "sudden-${Postgres.freshAccount("id")}",
        accountId = accountId,
        formatId = format.id,
        opponentIconId = "an-opponent",
        rules = GameRules(suddenDeath = true),
        seed = SEED,
        blueHand = flat.admittedBy(format).take(HAND).map { it.id },
        redHand = flat.admittedBy(format).drop(HAND).take(HAND).map { it.id },
        first = first,
        moves = emptyList(),
        status = PveMatchStatus.PLAYING,
    )

    private val set =
        CardSet(blocks = listOf(BLOCK), slug = "test", nameKey = "APP_TEST", sortOrder = 1)

    private val format =
        Format(id = "test", nameKey = "APP_TEST", blocks = listOf(BLOCK), rules = emptyList())

    /** Ten cards that cannot capture each other, so any nine placements end 5-5. */
    private val flat = CardCatalog(
        sets = listOf(set),
        cards = (1..CARDS).map {
            Card(
                id = Card.idFor(block = BLOCK, number = it),
                nameKey = "STR_TEST_$it",
                name = "Test $it",
                top = MID,
                right = MID,
                bottom = MID,
                left = MID,
                rarity = 1,
            )
        },
    )

    private companion object {
        const val BLOCK = 1
        const val CARDS = 10
        const val HAND = 5
        const val MID = 5
        const val SEED = 20260901

        /** Any fixed instant: nothing here reads a clock for anything but a row's timestamps. */
        const val CREATED = 1_756_000_000_000L
    }
}
