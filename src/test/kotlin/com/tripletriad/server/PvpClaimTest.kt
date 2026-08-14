package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchResult
import com.tripletriad.model.TradeRule
import com.tripletriad.protocol.PvpClaim
import com.tripletriad.protocol.PvpJoinRequest
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpStake
import com.tripletriad.protocol.PvpTable
import com.tripletriad.protocol.PvpTableRequest
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settling a wager: what the winner takes, and what happens when they never come back for it.
 *
 * ### Why this drives [PvpReferee] and not the routes
 *
 * The claim deadline is the point of the file, and a deadline needs a clock the test controls.
 * `Application.module` constructs `pvpRoutes` with its defaults (`Application.kt:111`), so over
 * HTTP the clock is `System::currentTimeMillis` and the only way to reach the deadline is to wait
 * two real minutes. Driving the referee directly costs the routing layer — which [PvpFlowTest]
 * covers — and buys a clock, which is the trade worth making here.
 *
 * ### What is actually at stake
 *
 * Every test here is about a card leaving somebody's collection. That is the only irreversible
 * thing this game does to a player, so the assertions are on the **saves** rather than on a status
 * or a response code: a settlement that reports correctly and credits wrongly is the failure that
 * matters, and only reading the profile afterwards can catch it.
 */
class PvpClaimTest {

    private var now: Long = START

    private val accounts = AccountStore(Postgres.dataSource)
    private val pvp = PvpStore(Postgres.dataSource)
    private val referee = PvpReferee(
        cards = Catalogs.cards,
        formats = Catalogs.formats,
        accounts = accounts,
        pvp = pvp,
        clock = { now },
        // The shared generator, and both halves of that matter. Not a fresh `Random(SEED)` per
        // call — `newId` would then return the same 22 characters every time. And not a field on
        // this class either: JUnit builds a new instance per test method, so a per-instance
        // generator restarts at the seed for each one and every test collides with the first.
        random = { GENERATOR },
    )

    /** A match played for MGP alone moves it from the loser to the winner, and settles at once. */
    @Test
    fun theMgpWagerMovesFromLoserToWinner() {
        val (host, joiner) = twoPlayers("mgp")
        val before = purses(host, joiner)

        val match = playOut(host, joiner, PvpStake(mgp = WAGER))
        val settled = assertNotNull(pvp.matchById(match))

        assertEquals(PvpMatchStatus.FINISHED, settled.status, "an MGP match should need no claim")
        val after = purses(host, joiner)
        val winner = decided(settled)
        val loser = winner.opposite()

        // What each side was told the wager did, exactly.
        assertEquals(WAGER, assertNotNull(settled.outcomeFor(winner, Catalogs.cards)).stakeMgp)
        assertEquals(-WAGER, assertNotNull(settled.outcomeFor(loser, Catalogs.cards)).stakeMgp)

        // And that both purses moved. Deliberately **not** "the loser is poorer": a match credits
        // the daily quests it completed in the same call, and those can pay more than a wager of
        // this size takes — so the direction of a purse is not a fact about the wager. An
        // end-to-end run against a real server is where that came out; the numbers above are the
        // ones that actually pin the transfer.
        assertTrue(
            after.getValue(settled.accountOf(loser)) != before.getValue(settled.accountOf(loser)),
            "the loser was not credited at all",
        )
        assertTrue(
            after.getValue(settled.accountOf(winner)) >
                before.getValue(settled.accountOf(winner)) + WAGER,
            "the winner was not paid the wager on top of the payout",
        )
    }

    /**
     * Under One, the board ending is not the match ending: the winner is owed a choice.
     *
     * The assertion that matters is the second one — **nothing is credited yet**. Paying the money
     * now and the cards on the claim would be two writes to each profile for one match, and a
     * second chance for one of them to go missing.
     */
    @Test
    fun aTradeMatchWaitsForTheWinnersChoiceBeforeCreditingAnything() {
        val (host, joiner) = twoPlayers("trade")
        val before = purses(host, joiner)

        val match = playOut(host, joiner, PvpStake(mgp = WAGER, trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)

        assertEquals(PvpMatchStatus.AWAITING_CLAIM, settled.status)
        assertEquals(1, settled.picksOwedBy(winner, Catalogs.cards))
        assertEquals(0, settled.picksOwedBy(winner.opposite(), Catalogs.cards))
        assertEquals(before, purses(host, joiner), "a match awaiting a claim paid out early")
    }

    /** And the loser's five are on the wire, to the winner only, so there is something to pick. */
    @Test
    fun theLosersHandIsOfferedToTheWinnerAndNobodyElse() {
        val (host, joiner) = twoPlayers("offer")

        val match = playOut(host, joiner, PvpStake(trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)

        val toWinner = assertNotNull(settled.outcomeFor(winner, Catalogs.cards))
        val toLoser = assertNotNull(settled.outcomeFor(winner.opposite(), Catalogs.cards))

        assertContentEquals(settled.dealtHand(winner.opposite()), toWinner.pickFrom)
        assertTrue(toLoser.pickFrom.isEmpty(), "the loser was shown a hand to pick from")
        assertNotNull(toWinner.claimDeadline)
        assertNull(toLoser.claimDeadline)
    }

    /** Claiming credits both sides: the card arrives on one profile and leaves the other. */
    @Test
    fun claimingMovesTheCardAndPaysBothSides() {
        val (host, joiner) = twoPlayers("claim")

        val match = playOut(host, joiner, PvpStake(mgp = WAGER, trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)
        val loser = winner.opposite()
        val prize = settled.dealtHand(loser).first()
        val heldBefore = save(settled.accountOf(loser)).copiesOf(prize)
        val wonBefore = save(settled.accountOf(winner)).copiesOf(prize)

        val outcome = referee.claim(
            match,
            settled.accountOf(winner),
            PvpClaim(listOf(prize)),
        )

        assertTrue(outcome is Claimed.Settled, "the claim was refused: $outcome")
        assertEquals(PvpMatchStatus.FINISHED, assertNotNull(pvp.matchById(match)).status)
        assertEquals(wonBefore + 1, save(settled.accountOf(winner)).copiesOf(prize))
        assertEquals(heldBefore - 1, save(settled.accountOf(loser)).copiesOf(prize))
    }

    /** A second claim on the same match changes nothing, so a double tap cannot credit twice. */
    @Test
    fun aSecondClaimCreditsNothingFurther() {
        val (host, joiner) = twoPlayers("double")

        val match = playOut(host, joiner, PvpStake(trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)
        val prize = settled.dealtHand(winner.opposite()).first()

        referee.claim(match, settled.accountOf(winner), PvpClaim(listOf(prize)))
        val afterFirst = save(settled.accountOf(winner))
        referee.claim(match, settled.accountOf(winner), PvpClaim(listOf(prize)))

        assertEquals(afterFirst.cards, save(settled.accountOf(winner)).cards)
        assertEquals(afterFirst.mgp, save(settled.accountOf(winner)).mgp)
    }

    /** A card the loser never brought is refused: you may only take what was at stake. */
    @Test
    fun claimingACardTheLoserNeverHeldIsRefused() {
        val (host, joiner) = twoPlayers("theft")

        val match = playOut(host, joiner, PvpStake(trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)
        val notTheirs = Catalogs.cards.all
            .first { it.id !in settled.dealtHand(winner.opposite()) }
            .id

        val outcome = referee.claim(match, settled.accountOf(winner), PvpClaim(listOf(notTheirs)))

        assertEquals(Claimed.NotTheirs, outcome)
        assertEquals(PvpMatchStatus.AWAITING_CLAIM, assertNotNull(pvp.matchById(match)).status)
    }

    /** So is a claim of the wrong size — One takes one, not two. */
    @Test
    fun claimingMoreThanIsOwedIsRefused() {
        val (host, joiner) = twoPlayers("greedy")

        val match = playOut(host, joiner, PvpStake(trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)
        val two = settled.dealtHand(winner.opposite()).take(2)

        val outcome = referee.claim(match, settled.accountOf(winner), PvpClaim(two))

        assertEquals(Claimed.NotTheirs, outcome)
    }

    /** The loser has nothing to claim, and asking says so rather than taking anything. */
    @Test
    fun theLoserIsOwedNothing() {
        val (host, joiner) = twoPlayers("nowt")

        val match = playOut(host, joiner, PvpStake(trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)
        val theirs = settled.dealtHand(winner).first()

        val outcome = referee.claim(
            match,
            settled.accountOf(winner.opposite()),
            PvpClaim(listOf(theirs)),
        )

        assertEquals(Claimed.NothingOwed, outcome)
    }

    /**
     * The claims list is what you are **owed**, not what you are involved in.
     *
     * Both players are in a match whose status is `AWAITING_CLAIM`, and the store's query is
     * indexed on exactly that status — so the obvious implementation hands the loser their
     * opponent's prize to collect. The client draws a banner from this list, and the loser's would
     * lead to a screen with nothing on it.
     */
    @Test
    fun onlyTheWinnerIsListedAsOwedAPrize() {
        val (host, joiner) = twoPlayers("owed")

        val match = playOut(host, joiner, PvpStake(trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)

        assertEquals(1, referee.claims(settled.accountOf(winner)).size)
        assertTrue(
            referee.claims(settled.accountOf(winner.opposite())).isEmpty(),
            "the loser was offered a prize to collect",
        )
    }

    /**
     * A winner who never comes back still settles, and the loser is not left in limbo.
     *
     * The reason the sweep had to be wired at all. A match stuck in `AWAITING_CLAIM` is one neither
     * side is ever paid for — and the *loser* has no reason to keep polling a game they have lost,
     * so "the first person to look" can be nobody at all.
     */
    @Test
    fun anUnclaimedPrizeIsSettledOnceTheDeadlinePasses() {
        val (host, joiner) = twoPlayers("lapse")

        val match = playOut(host, joiner, PvpStake(trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)
        val expected = settled.autoClaim(winner, Catalogs.cards)

        now += PvpMatchRow.CLAIM_MILLIS + 1
        val swept = referee.sweepClaims()

        val after = assertNotNull(pvp.matchById(match))
        // At least one, not exactly one: this database is shared with the rest of the class, and
        // the clock this test moved is the same clock every unclaimed match in it is waiting on.
        // What is being asserted about *this* match is on the lines below.
        assertTrue(swept >= 1, "the sweep settled nothing")
        assertEquals(PvpMatchStatus.FINISHED, after.status)
        assertContentEquals(expected, after.claimed[winner], "the server picked something else")
        assertTrue(
            save(after.accountOf(winner)).ownsCard(expected.first()),
            "the winner was not credited the card the server picked for them",
        )
    }

    /** The automatic pick takes the strongest card, so inattention is not punished. */
    @Test
    fun theAutomaticPickTakesTheStrongestCard() {
        val (host, joiner) = twoPlayers("auto")

        val match = playOut(host, joiner, PvpStake(trade = TradeRule.ONE))
        val settled = assertNotNull(pvp.matchById(match))
        val winner = decided(settled)

        val picked = settled.autoClaim(winner, Catalogs.cards).single()
        val best = settled.dealtHand(winner.opposite())
            .mapNotNull { Catalogs.cards.byId[it] }
            .maxOf { it.total }

        assertEquals(best, assertNotNull(Catalogs.cards.byId[picked]).total)
    }

    /**
     * What the match paid is recorded, so an end-of-match screen has something to show.
     *
     * `PvpOutcome.mgp` and `.xp` shipped with PvP and were always zero: the payout is rolled inside
     * `MatchRewards.creditPvp` — with a random top-up, and spending whatever boons the profile was
     * holding — so it cannot be recomputed from the row afterwards. It has to be written down at
     * the one moment it exists.
     */
    @Test
    fun whatEachSideWasPaidIsRecorded() {
        val (host, joiner) = twoPlayers("paid")
        val before = purses(host, joiner)

        val match = playOut(host, joiner, PvpStake.None)
        val settled = assertNotNull(pvp.matchById(match))

        for (side in CardColor.entries) {
            val outcome = assertNotNull(settled.outcomeFor(side, Catalogs.cards))
            assertTrue(outcome.mgp > 0, "$side was told it earned nothing")
            assertTrue(outcome.xp > 0, "$side was told it gained no rank")

            // And it is what actually reached the purse: the reported figure is the credited one,
            // not an estimate of it. Quests are credited in the same call, so the purse can have
            // risen by more — never by less.
            val account = settled.accountOf(side)
            val gained = save(account).mgp - before.getValue(account)
            assertTrue(
                gained >= outcome.mgp,
                "$side was told ${outcome.mgp} MGP but gained $gained",
            )
        }
    }

    /** A match played for nothing needs no claim and is finished the moment the board is. */
    @Test
    fun anUnwageredMatchNeedsNoClaim() {
        val (host, joiner) = twoPlayers("free")

        val match = playOut(host, joiner, PvpStake.None)
        val settled = assertNotNull(pvp.matchById(match))

        assertEquals(PvpMatchStatus.FINISHED, settled.status)
        assertFalse(settled.awaitsClaim(Catalogs.cards))
    }

    /**
     * Both sides are dealt the deck they named, not the first one in the save.
     *
     * The two decks are the same five cards in a different order, which makes the assertion about
     * *which slot was read* rather than about which cards happen to be strong — and reordering is
     * enough, because `playerDeck` returns the slot's list as it stands.
     */
    @Test
    fun eachSideIsDealtTheDeckItNamed() {
        val host = twoDecked("host-deck", STRONGEST)
        val joiner = twoDecked("join-deck", WEAKEST)

        val opened = referee.openTable(host, PvpTableRequest(FORMAT, deck = 1))
        assertTrue(opened is Tabled.Opened, "the table was refused: $opened")
        val joined = referee.joinTable(opened.table.id, joiner, deck = 1)
        assertTrue(joined is Joined.Playing, "the join was refused: $joined")

        assertContentEquals(STRONGEST.reversed(), joined.match.blueHand)
        assertContentEquals(WEAKEST.reversed(), joined.match.redHand)
    }

    /**
     * Saying nothing is dealt the first complete deck — what every client did before it could ask.
     *
     * The compatibility claim [PvpJoinRequest] makes, asserted where it is actually decided: a
     * request that names no deck must reach the deal the server would have made on its own.
     */
    @Test
    fun namingNoDeckIsDealtTheFirstOne() {
        val host = twoDecked("host-any", STRONGEST)
        val joiner = twoDecked("join-any", WEAKEST)

        val opened = referee.openTable(host, PvpTableRequest(FORMAT))
        assertTrue(opened is Tabled.Opened, "the table was refused: $opened")
        val joined = referee.joinTable(opened.table.id, joiner)
        assertTrue(joined is Joined.Playing, "the join was refused: $joined")

        assertContentEquals(STRONGEST, joined.match.blueHand)
        assertContentEquals(WEAKEST, joined.match.redHand)
    }

    /** A table's public row says nothing about the deck waiting behind it. */
    @Test
    fun theDeckIsNotAdvertisedWithTheTable() {
        val host = twoDecked("host-quiet", STRONGEST)

        val opened = referee.openTable(host, PvpTableRequest(FORMAT, deck = 1))
        assertTrue(opened is Tabled.Opened, "the table was refused: $opened")

        val encoded = Json.encodeToString(PvpTable.serializer(), opened.table)
        assertFalse("deck" in encoded, "a table advertised its host's deck: $encoded")
    }

    // ---- Harness ----------------------------------------------------------

    /**
     * Two registered accounts, one of which is going to win.
     *
     * The decks are **deliberately unequal**, and that is the whole of the fixture. Two profiles
     * created with `GameSave.new` hold the same starter five, and the play loop below is the same
     * greedy rule for both sides — so the board comes out 5-5 essentially every time, and every
     * assertion about a *winner* would be skipped or vacuous. Handing one side the strongest cards
     * in the catalogue and the other the weakest makes the result a fact rather than a coin toss.
     */
    private fun twoPlayers(prefix: String): Pair<Long, Long> {
        val host = register("$prefix-h", WEAKEST)
        val joiner = register("$prefix-j", STRONGEST)
        return host to joiner
    }

    private fun register(prefix: String, deck: List<Int>): Long {
        val name = Postgres.freshAccount(prefix)
        // `GameSave.new` supplies the 100 MGP a wager is drawn from. The cards are added on top and
        // named as a complete deck, because that is what `PveMatches.playerDeck` reads first.
        val save = deck
            .fold(GameSave.new(name, createdAt = START)) { profile, id -> profile.withCard(id) }
            .let { profile ->
                profile.copy(decks = listOf(profile.decks.first().copy(cards = deck)))
            }
        return assertNotNull(accounts.register(name, "hash-$name", save))
    }

    /** [register], plus a second complete deck holding the same five in reverse. */
    private fun twoDecked(prefix: String, deck: List<Int>): Long {
        val accountId = register(prefix, deck)
        val profile = save(accountId)
        val second = profile.decks.first().copy(name = "Second", cards = deck.reversed())
        assertTrue(accounts.replaceSave(accountId, profile.copy(decks = profile.decks + second)))
        return accountId
    }

    private fun save(accountId: Long): GameSave = assertNotNull(accounts.saveFor(accountId))

    private fun purses(vararg ids: Long): Map<Long, Int> = ids.associateWith { save(it).mgp }

    /** Whoever won on the board, or null on a draw. */
    private fun winnerOf(row: PvpMatchRow): CardColor? = CardColor.entries.firstOrNull {
        row.outcomeFor(it, Catalogs.cards)?.result == MatchResult.WIN
    }

    /**
     * The winner, insisting there is one.
     *
     * A draw would make every assertion below vacuous, so it fails here rather than returning
     * early: a wager test that quietly does nothing when the board happens to tie is worse than no
     * test, because it still reports green. If [SEED] ever starts producing draws, change it — the
     * fixture is arbitrary and the claim being made is not about any particular board.
     */
    private fun decided(row: PvpMatchRow): CardColor =
        assertNotNull(winnerOf(row), "this fixture drew, so it proves nothing — change SEED")

    /**
     * Opens a table, joins it, and plays every card until the board is full.
     *
     * Always the first playable slot into the first empty square. What is under test is the
     * settlement, not the play — and a fixed strategy against a fixed seed makes the result the
     * same on every run, which is what lets a wager assertion be exact rather than approximate.
     */
    private fun playOut(host: Long, joiner: Long, stake: PvpStake): String {
        val opened = referee.openTable(host, PvpTableRequest(FORMAT, stake = stake))
        assertTrue(opened is Tabled.Opened, "the table was refused: $opened")

        val joined = referee.joinTable(opened.table.id, joiner)
        assertTrue(joined is Joined.Playing, "the join was refused: $joined")
        val matchId = joined.match.id

        repeat(PLACEMENTS) {
            val row = assertNotNull(pvp.matchById(matchId))
            if (row.status != PvpMatchStatus.PLAYING) return@repeat
            val side = CardColor.entries.first {
                row.viewFor(it, Catalogs.cards)?.isMyTurn == true
            }
            val view = assertNotNull(row.viewFor(side, Catalogs.cards))
            val move = PvpMove(
                handIndex = view.playableHandIndices.first(),
                position = view.playablePositions().first(),
            )
            val played = referee.play(matchId, row.accountOf(side), move)
            assertTrue(played is Played.Accepted, "move $it was refused: $played")
        }
        return matchId
    }

    private companion object {
        /** The widest authored format. `FormatCatalog.default`. */
        const val FORMAT = "free-play"

        /** A wager a starting purse of 100 MGP covers. */
        const val WAGER = 50

        const val PLACEMENTS = 9
        const val SEED = 20_260_813
        const val START = 1_767_268_800_000L

        /** Shared across the whole class — see the note at the construction site. */
        val GENERATOR = Random(SEED)

        /** The five strongest cards the format admits, and the five weakest. See [twoPlayers]. */
        val RANKED: List<Int> = Catalogs.cards.all
            .filter { Catalogs.formats[FORMAT]?.admitsCard(it.id) == true }
            .sortedBy { it.total }
            .map { it.id }
        val WEAKEST: List<Int> = RANKED.take(HAND)
        val STRONGEST: List<Int> = RANKED.takeLast(HAND)

        const val HAND = 5
    }
}
