package com.tripletriad.server

import com.tripletriad.model.GameRules
import com.tripletriad.model.MatchResult
import com.tripletriad.model.TradeRule
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.PvpChallenge
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMatchView
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpQueueState
import com.tripletriad.protocol.PvpStake
import com.tripletriad.protocol.PvpTable
import com.tripletriad.protocol.PvpTableRequest
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two accounts, one match, against a real Postgres.
 *
 * ### The assertion this file exists for
 *
 * [neitherPlayerIsSentTheOthersHand]. Every other test here is about the match working; that one is
 * about the match being *fair*, and it is the only defect in this feature that a player could never
 * detect. A client that is sent its opponent's cards renders perfectly, plays perfectly, and cheats
 * silently — there is nothing on screen and nothing in any log to show for it.
 *
 * So it is asserted against the raw response body rather than the decoded object: "the field is
 * null" and "the number is nowhere in what we sent" are different claims, and only the second one
 * survives somebody reading the payload instead of the model.
 */
class PvpFlowTest {

    /**
     * A table is listed with the terms it was opened on, and joining it opens the match.
     *
     * The terms are the assertion, not the pairing. A lobby that listed tables without saying what
     * they were played for would be the queue again with more steps — the whole reason this
     * replaced a queue is that a player can read the wager before agreeing to it.
     */
    @Test
    fun aTableIsListedWithItsTermsAndJoiningItOpensTheMatch() = server {
        val aliceName = Postgres.freshAccount("alice")
        val alice = register(aliceName)
        val bob = register(Postgres.freshAccount("bob"))
        val stake = PvpStake(mgp = WAGER, trade = TradeRule.ONE)

        val table = openTable(alice.token, stake)

        val listed = assertNotNull(tables(bob.token).firstOrNull { it.id == table.id })
        assertEquals(stake, listed.stake, "the wager was not on the table Bob can see")
        assertEquals(aliceName, listed.hostName, "the lobby did not name the host")

        val matchId = assertNotNull(join(bob.token, table.id).matchId)

        // And both of them are now looking at the same match, from opposite ends.
        val fromAlice = assertNotNull(currentMatch(alice.token))
        val fromBob = assertNotNull(currentMatch(bob.token))
        assertEquals(matchId, fromAlice.matchId)
        assertEquals(matchId, fromBob.matchId)
        assertEquals(fromAlice.side.opposite(), fromBob.side)
        assertEquals(stake, fromAlice.stake, "the match was not opened on the table's terms")
    }

    /** A joined table stops being on offer, so nobody turns up to a match that already started. */
    @Test
    fun aJoinedTableLeavesTheLobby() = server {
        val alice = register(Postgres.freshAccount("taken-a"))
        val bob = register(Postgres.freshAccount("taken-b"))
        val carol = register(Postgres.freshAccount("taken-c"))

        val table = openTable(alice.token)
        join(bob.token, table.id)

        assertTrue(
            tables(carol.token).none { it.id == table.id },
            "a table somebody had already joined was still being offered",
        )
        val second = client.post("/pvp/tables/${table.id}/join") {
            protocolHeaders()
            bearer(carol.token)
        }
        assertEquals(HttpStatusCode.NotFound, second.status)
    }

    /** A host cannot join their own table, which would be a match against themselves. */
    @Test
    fun aHostCannotJoinTheirOwnTable() = server {
        val alice = register(Postgres.freshAccount("solo"))

        val table = openTable(alice.token)
        val response = client.post("/pvp/tables/${table.id}/join") {
            protocolHeaders()
            bearer(alice.token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertNull(currentMatch(alice.token))
    }

    /** One table per host: a second is refused rather than quietly replacing the first. */
    @Test
    fun aSecondTableIsRefused() = server {
        val alice = register(Postgres.freshAccount("twice"))
        openTable(alice.token)

        val response = client.post("/pvp/tables") {
            protocolHeaders()
            bearer(alice.token)
            setBody(json.encodeToString(PvpTableRequest(FORMAT)))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    /**
     * A table that has **lapsed** does not refuse a second one.
     *
     * The bug this pins: `pvp_tables_one_per_host` is partial on `match_id IS NULL` and says
     * nothing about `expires_at`, while the lobby listing says `expires_at > now`. A host whose
     * client stopped refreshing — they closed the screen — therefore held a row that no lobby would
     * show and no sweep would ever remove, and it refused them a table *forever*: "you already have
     * a table open" about a table the host could not see, cancel, or wait at.
     *
     * The lapsed row is written through [PvpStore] rather than by advancing a clock, because the
     * clock that matters here is the one inside the `INSERT` — the routes' `clock()` is not
     * reachable from a `testApplication` that wires the real module. What is asserted is the way
     * out: the next request succeeds, and the table it returns is the *new* one.
     */
    @Test
    fun aLapsedTableDoesNotRefuseTheNextOne() = server {
        val name = Postgres.freshAccount("lapsed")
        val alice = register(name)
        val accounts = AccountStore(Postgres.dataSource)
        val hostId = assertNotNull(accounts.accountIdForUsername(name))
        val stale = "stale-${alice.token.take(8)}"
        assertTrue(
            PvpStore(Postgres.dataSource).openTable(
                PvpTableRow(
                    id = stale,
                    hostAccount = hostId,
                    hostName = name,
                    formatId = FORMAT,
                    rules = GameRules(),
                    roulette = false,
                    stake = PvpStake.None,
                    openedAt = 0L,
                    // Lapsed before it was ever listed: the epoch is in the past by construction,
                    // so this needs no arithmetic against the wall clock to stay true.
                    expiresAt = 1L,
                ),
            ),
            "the stale table was not planted",
        )
        assertTrue(tables(alice.token).none { it.id == stale }, "a lapsed table was listed")

        val table = openTable(alice.token)

        assertNotEquals(stale, table.id)
        assertTrue(tables(alice.token).any { it.id == table.id }, "the new table was not listed")
    }

    /**
     * Rules the format does not allow are refused, and refused *before* anybody plays.
     *
     * Elemental is not in the FFXIV pool. A match opened under it would be one the format's players
     * never agreed to, and the only cost-free moment to say so is now.
     */
    @Test
    fun aTableNamingRulesOutsideTheFormatIsRefused() = server {
        val alice = register(Postgres.freshAccount("rules"))

        val response = client.post("/pvp/tables") {
            protocolHeaders()
            bearer(alice.token)
            setBody(
                json.encodeToString(
                    PvpTableRequest(
                        formatId = "ff14-standard",
                        rules = GameRules().withRuleKey("RULE_ELEMENTAL"),
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    /**
     * A wager bigger than the purse is refused at the table.
     *
     * There is no escrow — `MatchRewards.creditPvp` floors a purse at zero — so an unaffordable
     * stake would silently become a free one. Refusing here is what makes the wager mean anything.
     */
    @Test
    fun aTableYouCannotAffordIsRefused() = server {
        val alice = register(Postgres.freshAccount("broke"))

        val response = client.post("/pvp/tables") {
            protocolHeaders()
            bearer(alice.token)
            setBody(json.encodeToString(PvpTableRequest(FORMAT, stake = PvpStake(mgp = FORTUNE))))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    /**
     * Neither player's hand appears in what the other is sent.
     *
     * Checked on the encoded body. See the class KDoc.
     *
     * **Only the cards the reader does not also hold can be checked**, and that is not a weakening
     * of the test so much as a fact about fresh accounts: both start with the same starter pack, so
     * most ids appear legitimately in both payloads. Searching for those would fail on the reader's
     * own hand. `:core`'s `PvpMatchTest` makes the rigorous version of this claim with two disjoint
     * hands; what this one adds is that the **route** does not undo it.
     */
    @Test
    fun neitherPlayerIsSentTheOthersHand() = server {
        val alice = register(Postgres.freshAccount("hidden-a"))
        val bob = register(Postgres.freshAccount("hidden-b"))
        playing(alice.token, bob.token)

        val aliceBody = matchBody(alice.token)
        val bobBody = matchBody(bob.token)
        val aliceView = assertNotNull(currentMatch(alice.token))
        val bobView = assertNotNull(currentMatch(bob.token))

        // The structural claim holds whatever the two hands are — unless the roulette drew an Open
        // rule, in which case the cards are on the wire on purpose.
        if (bobView.opponentHand.all { it == null }) {
            for (card in aliceView.hand.filterNot { it in bobView.hand }) {
                assertFalse("$card" in bobBody, "Alice's card $card reached Bob: $bobBody")
            }
        }
        if (aliceView.opponentHand.all { it == null }) {
            for (card in bobView.hand.filterNot { it in aliceView.hand }) {
                assertFalse("$card" in aliceBody, "Bob's card $card reached Alice: $aliceBody")
            }
        }
        // Not vacuous: each player is certainly sent their own cards.
        assertTrue(aliceView.hand.all { "$it" in aliceBody })
    }

    /** Only the player to move is given anything to play, and the other is refused if they try. */
    @Test
    fun theWaitingPlayerCannotMove() = server {
        val alice = register(Postgres.freshAccount("turn-a"))
        val bob = register(Postgres.freshAccount("turn-b"))
        val matchId = playing(alice.token, bob.token)

        val aliceView = assertNotNull(currentMatch(alice.token))
        val bobView = assertNotNull(currentMatch(bob.token))
        val (mover, waiter) = if (aliceView.playable.isNotEmpty()) {
            alice to bob
        } else {
            bob to alice
        }
        val waiterView = if (mover == alice) bobView else aliceView

        assertTrue(waiterView.playable.isEmpty(), "the waiting side was offered cards to play")
        assertNull(waiterView.deadline, "the waiting side was given a clock")

        val refused = move(waiter.token, matchId, PvpMove(handIndex = 0, position = 0))
        assertEquals(HttpStatusCode.Conflict, refused, "a move out of turn was accepted")

        // And the one whose turn it is may play.
        val accepted = move(mover.token, matchId, firstLegalMove(mover.token))
        assertEquals(HttpStatusCode.OK, accepted)
    }

    /** A card placed by one player is on the other's board on their next look, with its owner. */
    @Test
    fun aPlacedCardAppearsOnBothBoards() = server {
        val alice = register(Postgres.freshAccount("board-a"))
        val bob = register(Postgres.freshAccount("board-b"))
        val matchId = playing(alice.token, bob.token)

        val mover = if (assertNotNull(
                currentMatch(alice.token),
            ).playable.isNotEmpty()
        ) {
            alice
        } else {
            bob
        }
        val other = if (mover == alice) bob else alice
        val before = assertNotNull(currentMatch(mover.token))
        val played = firstLegalMove(mover.token)

        move(mover.token, matchId, played)

        val theirs = assertNotNull(currentMatch(other.token))
        val cell = assertNotNull(theirs.cells[played.position], "the cell is still empty")
        assertEquals(before.hand[played.handIndex], cell.cardId)
        assertEquals(before.side, cell.owner)
        assertTrue(theirs.playable.isNotEmpty(), "the turn did not pass")
    }

    /**
     * Conceding ends the match, loses it, and pays both players.
     *
     * The MGP assertion is the one that matters: a forfeit is a settled match, not a match that
     * stopped. If it did not credit, a player could leave every losing game and pay nothing for it.
     */
    @Test
    fun concedingSettlesTheMatchAndPaysBoth() = server {
        val alice = register(Postgres.freshAccount("quit-a"))
        val bob = register(Postgres.freshAccount("quit-b"))
        val aliceBefore = me(alice.token)
        val bobBefore = me(bob.token)
        val matchId = playing(alice.token, bob.token)

        val response = client.post("/pvp/match/$matchId/forfeit") {
            protocolHeaders()
            bearer(alice.token)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val view = json.decodeFromString<PvpMatchView>(response.bodyAsText())

        assertEquals(PvpMatchStatus.FORFEITED, view.status)
        assertEquals(view.side, assertNotNull(view.outcome).forfeitedBy)
        assertTrue(me(alice.token) > aliceBefore, "the loser was not credited at all")
        assertTrue(me(bob.token) > bobBefore, "the winner was not paid")

        // Both sides can still read it, and both are told it was a forfeit. This used to assert
        // the opposite — that a settled match was gone from `GET /pvp/match` — and that is exactly
        // the bug: the player who did *not* concede finds out by polling, so a match that vanished
        // on settlement left them looking at an empty board with no result on it.
        assertEquals(
            PvpMatchStatus.FORFEITED,
            assertNotNull(currentMatch(alice.token), "the conceder lost the result").status,
        )
        val toBob = assertNotNull(currentMatch(bob.token), "the winner was never told")
        val outcome = assertNotNull(toBob.outcome)
        assertEquals(MatchResult.WIN, outcome.result)
        // `forfeitedBy` travels in the server's colours, so "was it me" is a comparison against
        // the side the server dealt — never against a client's own mirrored view of the board.
        assertNotEquals(toBob.side, outcome.forfeitedBy, "Bob was told he was the one who left")
    }

    /** An invitation is offered, accepted, and turns into a match both players are in. */
    @Test
    fun aChallengeByNameOpensAMatch() = server {
        val alice = register(Postgres.freshAccount("inv-a"))
        val bobName = Postgres.freshAccount("inv-b")
        val bob = register(bobName)

        val sent = client.post("/pvp/challenges") {
            protocolHeaders()
            bearer(alice.token)
            setBody(json.encodeToString(ChallengeRequest(bobName, PvpTableRequest(FORMAT))))
        }
        assertEquals(HttpStatusCode.Created, sent.status, sent.bodyAsText())
        val challenge = json.decodeFromString<PvpChallenge>(sent.bodyAsText())

        // Bob sees it standing.
        val pending = client.get("/pvp/challenges") {
            protocolHeaders()
            bearer(bob.token)
        }
        assertTrue(challenge.id in pending.bodyAsText(), pending.bodyAsText())

        val accepted = client.post("/pvp/challenges/${challenge.id}/accept") {
            protocolHeaders()
            bearer(bob.token)
        }
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        val matchId = assertNotNull(
            json.decodeFromString<PvpQueueState>(accepted.bodyAsText()).matchId,
        )
        assertEquals(matchId, assertNotNull(currentMatch(alice.token)).matchId)
        assertEquals(matchId, assertNotNull(currentMatch(bob.token)).matchId)
    }

    /** The same invitation cannot be accepted twice into two matches. */
    @Test
    fun anInvitationIsOnlyGoodOnce() = server {
        val alice = register(Postgres.freshAccount("once-a"))
        val bobName = Postgres.freshAccount("once-b")
        val bob = register(bobName)

        val sent = client.post("/pvp/challenges") {
            protocolHeaders()
            bearer(alice.token)
            setBody(json.encodeToString(ChallengeRequest(bobName, PvpTableRequest(FORMAT))))
        }
        val challenge = json.decodeFromString<PvpChallenge>(sent.bodyAsText())

        val first = client.post("/pvp/challenges/${challenge.id}/accept") {
            protocolHeaders()
            bearer(bob.token)
        }
        val second = client.post("/pvp/challenges/${challenge.id}/accept") {
            protocolHeaders()
            bearer(bob.token)
        }

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
    }

    /** Challenging a name nobody has is a 404, not an invitation into the void. */
    @Test
    fun challengingNobodyIsRefused() = server {
        val alice = register(Postgres.freshAccount("void"))

        val response = client.post("/pvp/challenges") {
            protocolHeaders()
            bearer(alice.token)
            setBody(
                json.encodeToString(
                    ChallengeRequest("nobody-at-all-$UNIQUE", PvpTableRequest(FORMAT)),
                ),
            )
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    /** Withdrawing a table takes it off the list, so nobody joins a match its host has left. */
    @Test
    fun withdrawingATableTakesItOffTheList() = server {
        val alice = register(Postgres.freshAccount("leave-a"))
        val bob = register(Postgres.freshAccount("leave-b"))

        val table = openTable(alice.token)
        client.delete("/pvp/tables/${table.id}") {
            protocolHeaders()
            bearer(alice.token)
        }

        assertTrue(tables(bob.token).none { it.id == table.id })
        val response = client.post("/pvp/tables/${table.id}/join") {
            protocolHeaders()
            bearer(bob.token)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertNull(currentMatch(alice.token))
    }

    /** And only its own host may withdraw it. */
    @Test
    fun somebodyElsesTableCannotBeWithdrawn() = server {
        val alice = register(Postgres.freshAccount("mine-a"))
        val bob = register(Postgres.freshAccount("mine-b"))

        val table = openTable(alice.token)
        client.delete("/pvp/tables/${table.id}") {
            protocolHeaders()
            bearer(bob.token)
        }

        assertTrue(
            tables(bob.token).any { it.id == table.id },
            "Bob withdrew a table that was not his",
        )
    }

    /**
     * **Both** players are told how the match ended, not only the one who finished it.
     *
     * The player who places the ninth card is handed the settled view as the response to their
     * move. Their opponent finds out by polling — and a match left the live query the instant it
     * settled, so by the time they polled there was nothing there. They were dropped onto an empty
     * board at the exact moment the game owed them a score.
     */
    @Test
    fun bothPlayersCanStillReadAFinishedMatch() = server {
        val alice = register(Postgres.freshAccount("end-a"))
        val bob = register(Postgres.freshAccount("end-b"))
        val matchId = playing(alice.token, bob.token)

        playOut(matchId, alice.token, bob.token)

        // Neither side has been forgotten, and each is told the result from their own end.
        val fromAlice = assertNotNull(currentMatch(alice.token), "Alice lost the finished match")
        val fromBob = assertNotNull(currentMatch(bob.token), "Bob lost the finished match")
        assertEquals(matchId, fromAlice.matchId)
        assertEquals(matchId, fromBob.matchId)
        assertNotNull(fromAlice.outcome, "Alice was shown a board with no outcome on it")
        assertNotNull(fromBob.outcome, "Bob was shown a board with no outcome on it")
        assertEquals(PLACEMENTS, fromBob.placement)
    }

    /**
     * And a finished match does not stop either of them starting another.
     *
     * The reason the readable window is a **separate** query: reusing the live one would answer
     * "you are already in a match" for two minutes after one ended, which would be a worse bug
     * than the blank screen it was fixing.
     */
    @Test
    fun aFinishedMatchDoesNotBlockTheNextOne() = server {
        val alice = register(Postgres.freshAccount("next-a"))
        val bob = register(Postgres.freshAccount("next-b"))
        val matchId = playing(alice.token, bob.token)

        playOut(matchId, alice.token, bob.token)

        // The match is still readable — and opening a table is still allowed.
        assertNotNull(currentMatch(alice.token))
        val response = client.post("/pvp/tables") {
            protocolHeaders()
            bearer(alice.token)
            setBody(json.encodeToString(PvpTableRequest(FORMAT)))
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    /** A player in no match gets 204, which is how a client knows there is nothing to resume. */
    @Test
    fun aPlayerWithNoMatchIsToldSo() = server {
        val alice = register(Postgres.freshAccount("idle"))

        val response = client.get("/pvp/match") {
            protocolHeaders()
            bearer(alice.token)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    /** Every route needs a token: none of this is readable by an unauthenticated caller. */
    @Test
    fun theRoutesRefuseAnUnauthenticatedCaller() = server {
        for (response in listOf(
            client.get("/pvp/match") { protocolHeaders() },
            client.get("/pvp/challenges") { protocolHeaders() },
            client.get("/pvp/tables") { protocolHeaders() },
        )) {
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    /**
     * An invitation carries its terms, and the match is opened on them.
     *
     * It could not before: `pvp_challenges` held a wager and nothing else, so accepting one always
     * played the default format under whatever the roulette drew. Naming your rules was something
     * you could do for strangers browsing the lobby and not for somebody you invited by name.
     */
    @Test
    fun anInvitationCarriesItsTerms() = server {
        val aliceName = Postgres.freshAccount("terms-a")
        val bobName = Postgres.freshAccount("terms-b")
        val alice = register(aliceName)
        val bob = register(bobName)
        val terms = PvpTableRequest(
            formatId = "ff14-standard",
            rules = GameRules().withRuleKey("RULE_SWAP"),
            stake = PvpStake(mgp = WAGER, trade = TradeRule.ONE),
        )

        val sent = client.post("/pvp/challenges") {
            protocolHeaders()
            bearer(alice.token)
            setBody(json.encodeToString(ChallengeRequest(bobName, terms)))
        }
        assertEquals(HttpStatusCode.Created, sent.status, sent.bodyAsText())
        val challenge = json.decodeFromString<PvpChallenge>(sent.bodyAsText())
        assertEquals(terms, challenge.terms, "the invitation dropped its terms")

        client.post("/pvp/challenges/${challenge.id}/accept") {
            protocolHeaders()
            bearer(bob.token)
        }

        val match = assertNotNull(currentMatch(bob.token))
        assertEquals("ff14-standard", match.formatId, "the match ignored the chosen format")
        assertTrue(match.rules.swap, "the match ignored the chosen rules")
        assertEquals(terms.stake, match.stake)
    }

    /** And terms nobody can play are refused, by the same check a table gets. */
    @Test
    fun anInvitationOnImpossibleTermsIsRefused() = server {
        val aliceName = Postgres.freshAccount("bad-a")
        val bobName = Postgres.freshAccount("bad-b")
        val alice = register(aliceName)
        register(bobName)

        val sent = client.post("/pvp/challenges") {
            protocolHeaders()
            bearer(alice.token)
            setBody(
                json.encodeToString(
                    // Elemental is not in the FFXIV pool — the same rule the lobby refuses.
                    ChallengeRequest(
                        bobName,
                        PvpTableRequest(
                            formatId = "ff14-standard",
                            rules = GameRules().withRuleKey("RULE_ELEMENTAL"),
                        ),
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, sent.status, sent.bodyAsText())
    }

    // ---- Helpers ----------------------------------------------------------

    /**
     * Plays a match to the last card, whichever side is to move.
     *
     * Always the first playable slot into the first empty square. What the tests using this care
     * about is the *settlement*, not the play — and the server decides who moves, so the caller
     * cannot know whose turn it is without asking.
     */
    private suspend fun ApplicationTestBuilder.playOut(matchId: String, vararg tokens: String) {
        repeat(PLACEMENTS) {
            val turn = tokens
                .mapNotNull { token -> currentMatch(token)?.let { token to it } }
                .firstOrNull { (_, view) -> view.playable.isNotEmpty() }
                ?: return
            val (token, view) = turn
            move(
                token,
                matchId,
                PvpMove(view.playable.first(), view.cells.indexOfFirst { it == null }),
            )
        }
    }

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.register(name: String): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, PASSWORD)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString<Session>(response.bodyAsText())
    }

    /** Opens a table on [stake], returning it. */
    private suspend fun ApplicationTestBuilder.openTable(
        token: String,
        stake: PvpStake = PvpStake.None,
        rules: GameRules = GameRules(),
        roulette: Boolean = false,
    ): PvpTable {
        val response = client.post("/pvp/tables") {
            protocolHeaders()
            bearer(token)
            setBody(
                json.encodeToString(
                    PvpTableRequest(FORMAT, rules = rules, roulette = roulette, stake = stake),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    /** Joins one, which opens the match. */
    private suspend fun ApplicationTestBuilder.join(
        token: String,
        tableId: String,
    ): PvpQueueState {
        val response = client.post("/pvp/tables/$tableId/join") {
            protocolHeaders()
            bearer(token)
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    /** The two of them: the shape almost every test below opens with. */
    private suspend fun ApplicationTestBuilder.playing(
        host: String,
        joiner: String,
        stake: PvpStake = PvpStake.None,
    ): String = join(joiner, openTable(host, stake).id).matchId.let(::assertNotNull)

    private suspend fun ApplicationTestBuilder.tables(token: String): List<PvpTable> {
        val response = client.get("/pvp/tables") {
            protocolHeaders()
            bearer(token)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.matchBody(token: String): String =
        client.get("/pvp/match") {
            protocolHeaders()
            bearer(token)
        }.bodyAsText()

    private suspend fun ApplicationTestBuilder.currentMatch(token: String): PvpMatchView? {
        val response = client.get("/pvp/match") {
            protocolHeaders()
            bearer(token)
        }
        if (response.status == HttpStatusCode.NoContent) return null
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.move(
        token: String,
        matchId: String,
        move: PvpMove,
    ): HttpStatusCode = client.post("/pvp/match/$matchId/move") {
        protocolHeaders()
        bearer(token)
        setBody(json.encodeToString(move))
    }.status

    /** The first slot and square the server says are allowed, read off the player's own view. */
    private suspend fun ApplicationTestBuilder.firstLegalMove(token: String): PvpMove {
        val view = assertNotNull(currentMatch(token))
        val free = view.cells.indexOfFirst { it == null }
        return PvpMove(handIndex = view.playable.first(), position = free)
    }

    /** The purse, which is the cheapest proof that a profile was credited. */
    private suspend fun ApplicationTestBuilder.me(token: String): Int {
        val response = client.get("/me") {
            protocolHeaders()
            bearer(token)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<com.tripletriad.protocol.PlayerState>(
            response.bodyAsText(),
        ).save.mgp
    }

    private fun HttpRequestBuilder.protocolHeaders() {
        contentType(ContentType.Application.Json)
        header(VERSION_HEADER, CURRENT_VERSION.toString())
    }

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val PASSWORD = "not-a-real-password"
        val UNIQUE: Long = System.nanoTime()

        /** The widest authored format — every block, every rule. `FormatCatalog.default`. */
        const val FORMAT = "free-play"

        /** A wager a starting purse of 100 MGP covers. */
        const val WAGER = 50

        /** More than any purse in these tests, so the refusal is what is being measured. */
        const val FORTUNE = 1_000_000

        /** A full board. */
        const val PLACEMENTS = 9
    }
}
