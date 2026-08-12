package com.tripletriad.server

import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.PvpChallenge
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMatchView
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpQueueState
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

    /** Two players queue, and the second to arrive goes straight into a match with the first. */
    @Test
    fun theSecondPlayerToQueueIsPairedWithTheFirst() = server {
        val alice = register(Postgres.freshAccount("alice"))
        val bob = register(Postgres.freshAccount("bob"))

        val waiting = queue(alice.token)
        assertTrue(waiting.waiting, "the first player should be waiting")
        assertNull(waiting.matchId)

        val paired = queue(bob.token)
        assertFalse(paired.waiting, "the second player should have been paired")
        val matchId = assertNotNull(paired.matchId)

        // And both of them are now looking at the same match, from opposite ends.
        val fromAlice = assertNotNull(currentMatch(alice.token))
        val fromBob = assertNotNull(currentMatch(bob.token))
        assertEquals(matchId, fromAlice.matchId)
        assertEquals(matchId, fromBob.matchId)
        assertEquals(fromAlice.side.opposite(), fromBob.side)
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
        queue(alice.token)
        queue(bob.token)

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
        queue(alice.token)
        val matchId = assertNotNull(queue(bob.token).matchId)

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
        queue(alice.token)
        val matchId = assertNotNull(queue(bob.token).matchId)

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
        queue(alice.token)
        val matchId = assertNotNull(queue(bob.token).matchId)

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

        // The match is over for both, so neither is still in one.
        assertNull(currentMatch(alice.token))
        assertNull(currentMatch(bob.token))
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
            setBody(json.encodeToString(ChallengeRequest(bobName)))
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
            setBody(json.encodeToString(ChallengeRequest(bobName)))
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
            setBody(json.encodeToString(ChallengeRequest("nobody-at-all-$UNIQUE")))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    /** Leaving the queue means the next player to arrive waits rather than pairing. */
    @Test
    fun leavingTheQueueTakesYouOutOfIt() = server {
        val alice = register(Postgres.freshAccount("leave-a"))
        val bob = register(Postgres.freshAccount("leave-b"))

        queue(alice.token)
        client.delete("/pvp/queue") {
            protocolHeaders()
            bearer(alice.token)
        }
        val bobState = queue(bob.token)

        assertTrue(bobState.waiting, "Bob was paired with somebody who had left")
        assertNull(currentMatch(alice.token))
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
            client.post("/pvp/queue") { protocolHeaders() },
        )) {
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ---- Helpers ----------------------------------------------------------

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

    private suspend fun ApplicationTestBuilder.queue(token: String): PvpQueueState {
        val response = client.post("/pvp/queue") {
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
    }
}
