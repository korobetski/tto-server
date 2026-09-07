package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpStake
import com.tripletriad.protocol.PvpTableRequest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wait between "an opponent joined" and "the match is being played".
 *
 * ### The defect this file is about
 *
 * A table is polled from anywhere in the client, so hosting one and playing something else while it
 * fills is the obvious thing to do. Until this, joining started a 150-second turn clock against a
 * randomly chosen first mover — so half the time the host was forfeited **at the maximum stake**
 * for a match they were never shown, by their own client's next poll. There was no test of it
 * because there was no code that knew the situation existed.
 *
 * ### Why the referee and not the routes
 *
 * The same trade [PvpClaimTest] makes, for the same reason: every assertion here is about a
 * deadline, and over HTTP the clock is the wall clock and reaching a deadline means waiting five
 * real minutes. `PvpFlowTest` covers the route.
 *
 * ### Why no test here counts what the sweep swept
 *
 * `sweepPairing` closes every overdue row in the one Postgres the whole suite shares, and the
 * methods below leave matches behind that are overdue by then. The count it returns therefore
 * depends on execution order — it was asserted as 1 at first and CI read 5. What each test owns is
 * its own match, so each reads that row back instead.
 */
class PvpPairingTest {

    private var now: Long = START

    private val accounts = AccountStore(Postgres.dataSource)
    private val pvp = PvpStore(Postgres.dataSource)
    private val referee = PvpReferee(
        cards = Catalogs.cards,
        formats = Catalogs.formats,
        accounts = accounts,
        pvp = pvp,
        clock = { now },
        random = { GENERATOR },
    )

    /** The whole point: a fresh pairing puts nobody on a turn clock. */
    @Test
    fun aPairedMatchStartsNobodysTurnClock() {
        val row = assertNotNull(pvp.matchById(pair("cold")))

        assertNull(row.turnDeadline, "the turn clock started before either side saw the board")
        assertEquals(START + PvpMatchRow.PAIRING_MILLIS, row.pairingDeadline)
    }

    /** And both are told how long they have: neither of them is waiting on the other. */
    @Test
    fun bothSidesAreGivenTheSameCountdownUntilOneOfThemArrives() {
        val row = assertNotNull(pvp.matchById(pair("both")))

        for (side in CardColor.entries) {
            val view = assertNotNull(row.wireFor(side, NOBODY, Catalogs.cards))
            assertEquals(
                row.pairingDeadline,
                view.deadline,
                "$side was not told what it is waiting on",
            )
        }
    }

    /** One side at the board is not a match being played, and the clock stays stopped. */
    @Test
    fun oneSideArrivingIsNotEnoughToStartTheClock() {
        val matchId = pair("half")
        val row = assertNotNull(pvp.matchById(matchId))

        referee.attend(matchId, row.blueAccount)

        val after = assertNotNull(pvp.matchById(matchId))
        assertEquals(now, after.blueSeenAt)
        assertNull(after.redSeenAt)
        assertNull(after.turnDeadline, "one arrival started the turn clock")
        assertEquals(row.pairingDeadline, after.pairingDeadline, "the wait was cut short")
    }

    /** The second arrival is what starts it, and the pairing wait is over. */
    @Test
    fun theSecondArrivalStartsTheTurnClock() {
        val matchId = pair("full")
        val row = assertNotNull(pvp.matchById(matchId))

        referee.attend(matchId, row.blueAccount)
        now += LATE
        referee.attend(matchId, row.redAccount)

        val after = assertNotNull(pvp.matchById(matchId))
        assertEquals(now + PvpMatchRow.DEADLINE_MILLIS, after.turnDeadline)
        assertNull(after.pairingDeadline, "the pairing wait outlived the pairing")
        assertTrue(after.isAttended)
    }

    /**
     * The clock is dated from the **late** arrival, not the early one.
     *
     * The failure this rules out is the quiet one: a deadline written when the first side arrived
     * would give the second a turn shorter than a turn by however long they took to get there, and
     * nothing on either screen would say so.
     */
    @Test
    fun theClockIsDatedFromWhenTheSecondSideArrived() {
        val matchId = pair("late")
        val row = assertNotNull(pvp.matchById(matchId))
        val early = now

        referee.attend(matchId, row.blueAccount)
        now += LATE
        referee.attend(matchId, row.redAccount)

        val after = assertNotNull(pvp.matchById(matchId))
        assertNotEquals(early + PvpMatchRow.DEADLINE_MILLIS, after.turnDeadline)
        assertEquals(early + LATE + PvpMatchRow.DEADLINE_MILLIS, after.turnDeadline)
    }

    /** A board announces arrival on every open, so doing so must not move the clock. */
    @Test
    fun arrivingTwiceDoesNotPushTheClockForward() {
        val matchId = pair("twice")
        val row = assertNotNull(pvp.matchById(matchId))

        referee.attend(matchId, row.blueAccount)
        referee.attend(matchId, row.redAccount)
        val started = assertNotNull(pvp.matchById(matchId)).turnDeadline

        now += LATE
        referee.attend(matchId, row.blueAccount)
        referee.attend(matchId, row.redAccount)

        val after = assertNotNull(pvp.matchById(matchId))
        assertEquals(started, after.turnDeadline, "re-opening the board bought another turn")
        assertEquals(START, after.blueSeenAt, "the first sighting was overwritten")
    }

    /** Once the clock is running, the waiting side is told nothing — as it always was. */
    @Test
    fun onlyTheSideToMoveIsGivenAClockOnceBothHaveArrived() {
        val matchId = pair("turn")
        val row = assertNotNull(pvp.matchById(matchId))
        referee.attend(matchId, row.blueAccount)
        referee.attend(matchId, row.redAccount)

        val after = assertNotNull(pvp.matchById(matchId))
        val waiting = after.first.opposite()
        val view = assertNotNull(after.wireFor(waiting, NOBODY, Catalogs.cards))

        assertNull(view.deadline, "the waiting side was given a countdown against the wrong turn")
    }

    /**
     * A match neither side opened is closed, and **nobody is paid**.
     *
     * Deliberately not a forfeit: there is no winner in a match that was never looked at, and
     * routing this through `settle` would credit both profiles for a game that never happened —
     * which would make hosting a table and walking away a way to earn.
     */
    @Test
    fun aMatchNeitherSideOpensIsAbandonedAndPaysNobody() {
        val (host, joiner) = twoPlayers("gone")
        val before = purses(host, joiner)
        val matchId = pair(host, joiner, PvpStake(mgp = WAGER))

        now += PvpMatchRow.PAIRING_MILLIS + 1
        referee.sweepPairing()

        val after = assertNotNull(pvp.matchById(matchId))
        assertEquals(PvpMatchStatus.ABANDONED, after.status)
        assertNull(after.forfeitedBy, "somebody was blamed for a match nobody played")
        assertEquals(before, purses(host, joiner), "an unplayed match moved money")
    }

    /** A match one side did come to is abandoned too. The other never arrived, so nobody lost. */
    @Test
    fun aMatchOnlyOneSideOpensIsAbandonedRatherThanForfeited() {
        val matchId = pair("lonely")
        val row = assertNotNull(pvp.matchById(matchId))
        referee.attend(matchId, row.blueAccount)

        now += PvpMatchRow.PAIRING_MILLIS + 1
        referee.sweepPairing()

        val after = assertNotNull(pvp.matchById(matchId))
        assertEquals(PvpMatchStatus.ABANDONED, after.status)
        assertNull(after.forfeitedBy, "the side that showed up was blamed")
    }

    /** Before the wait is up there is nothing to sweep. */
    @Test
    fun aMatchStillInsideItsWaitIsLeftAlone() {
        val matchId = pair("early")

        now += PvpMatchRow.PAIRING_MILLIS - 1
        referee.sweepPairing()
        assertEquals(PvpMatchStatus.PLAYING, assertNotNull(pvp.matchById(matchId)).status)
    }

    /**
     * A match with a card in it is never swept as unattended, however it got there.
     *
     * The case is a client too old to announce its arrival: it plays without ever attending, so
     * the pairing wait would still be sitting on the row and would abandon a game in progress.
     * Placing a card is arriving, whatever the client says.
     */
    @Test
    fun amatchThatHasBeenPlayedInIsNotSweptAsUnattended() {
        val matchId = pair("old")
        val row = assertNotNull(pvp.matchById(matchId))
        val side = row.first
        val view = assertNotNull(row.viewFor(side, Catalogs.cards))
        val played = referee.play(
            matchId,
            row.accountOf(side),
            PvpMove(view.playableHandIndices.first(), view.playablePositions().first()),
        )
        assertTrue(played is Played.Accepted, "the move was refused: $played")

        now += PvpMatchRow.PAIRING_MILLIS + 1
        referee.sweepPairing()
        assertEquals(PvpMatchStatus.PLAYING, assertNotNull(pvp.matchById(matchId)).status)
    }

    /** The lapse is noticed by whoever polls, the way a forfeit is, and not only by the sweep. */
    @Test
    fun theSideThatDidComeIsToldOnTheirNextPoll() {
        val matchId = pair("poll")
        val row = assertNotNull(pvp.matchById(matchId))
        referee.attend(matchId, row.blueAccount)

        now += PvpMatchRow.PAIRING_MILLIS + 1
        val view = assertNotNull(referee.currentView(row.blueAccount))

        assertEquals(matchId, view.matchId)
        assertEquals(PvpMatchStatus.ABANDONED, view.status)
    }

    /** Arriving at a match that has already been closed changes nothing. */
    @Test
    fun arrivingAfterTheWaitLapsedDoesNotRestartIt() {
        val matchId = pair("stale")
        val row = assertNotNull(pvp.matchById(matchId))

        now += PvpMatchRow.PAIRING_MILLIS + 1
        referee.sweepPairing()
        referee.attend(matchId, row.blueAccount)

        val after = assertNotNull(pvp.matchById(matchId))
        assertEquals(PvpMatchStatus.ABANDONED, after.status)
        assertNull(after.turnDeadline, "a closed match was put back on the clock")
    }

    // ---- fixtures ---------------------------------------------------------

    private fun pair(prefix: String): String = twoPlayers(prefix).let { (h, j) -> pair(h, j) }

    private fun pair(host: Long, joiner: Long, stake: PvpStake = PvpStake.None): String {
        val opened = referee.openTable(host, PvpTableRequest(FORMAT, stake = stake))
        assertTrue(opened is Tabled.Opened, "the table was refused: $opened")
        val joined = referee.joinTable(opened.table.id, joiner)
        assertTrue(joined is Joined.Playing, "the join was refused: $joined")
        return joined.match.id
    }

    private fun twoPlayers(prefix: String): Pair<Long, Long> =
        register("$prefix-h") to register("$prefix-j")

    private fun register(prefix: String): Long {
        val name = Postgres.freshAccount(prefix)
        val save = HAND_CARDS
            .fold(GameSave.new(name, createdAt = START)) { profile, id -> profile.withCard(id) }
            .copy(decks = listOf(Deck(GameSave.DEFAULT_DECK_NAME, HAND_CARDS)))
        return assertNotNull(accounts.register(name, "hash-$name", save))
    }

    private fun purses(vararg ids: Long): Map<Long, Int> = ids.associateWith {
        assertNotNull(accounts.saveFor(it)).mgp
    }

    private companion object {
        const val FORMAT = "free-play"
        const val WAGER = 50
        const val SEED = 20_260_907
        const val START = 1_767_268_800_000L

        /** Long enough to tell a deadline dated from the first arrival from one dated later. */
        const val LATE = 45_000L

        val NOBODY = Opponent(name = "", avatarId = null)
        val GENERATOR = Random(SEED)

        /** Any five the format admits; nothing here turns on which. */
        val HAND_CARDS: List<Int> = Catalogs.cards.all
            .filter { Catalogs.formats[FORMAT]?.admitsCard(it.id) == true }
            .sortedBy { it.total }
            .take(5)
            .map { it.id }
    }
}
