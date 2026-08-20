package com.tripletriad.server

import com.tripletriad.model.OpenRule
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.PveMatchRequest
import com.tripletriad.protocol.PveMatchStatus
import com.tripletriad.protocol.PveMatchView
import com.tripletriad.protocol.PveMove
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
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
 * One account, one opponent, one refereed match, against a real Postgres.
 *
 * ### The assertion this file exists for
 *
 * [theOpponentsHandNeverReachesThePlayer]. Every other test here is about the match working; that
 * one is about the match being *worth playing*, and it is the whole reason the solo match stopped
 * being something the client ran.
 *
 * Under the transcript design the client held both hands by necessity — it had to run the same AI
 * from the same seed for the server to be able to replay it. A modified client therefore played in
 * perfect information and left nothing behind to show for it: the match really did happen exactly
 * as claimed. Refereed, the cards are simply not sent.
 *
 * It is asserted against the **raw response body** and against the hand the server actually dealt,
 * read back out of the store. "The field is null" and "the number is nowhere in what we sent" are
 * different claims, and only the second survives somebody reading the payload instead of the model.
 */
class PveFlowTest {

    /** Opening a match deals a hand, and the opponent moves first if the toss says so. */
    @Test
    fun openingAMatchDealsAPlayableBoard() = server {
        val session = register(Postgres.freshAccount("pve-open"))

        val view = openMatch(session.token)

        assertEquals(PveMatchStatus.PLAYING, view.status)
        assertEquals(OPPONENT, view.opponentIconId)
        assertEquals(HAND, view.hand.size)
        assertTrue(view.playable.isNotEmpty(), "the player should be on move after opening")

        // If the opponent won the toss it has already played, and the board says so before the
        // player has done anything. That is the round trip this design exists to avoid spending.
        val placed = view.cells.count { it != null }
        assertTrue(placed <= 1, "only the opponent's opening move may be on the board")
        assertEquals(placed, view.plays.size, "an opening move must be announced to be animated")
        assertEquals(
            HAND - placed,
            view.opponentHand.size,
            "the opponent's card count is public, and it has played $placed",
        )
    }

    /**
     * **The opponent's hidden cards are not on the wire.**
     *
     * Read against the hand the server dealt, which the store is asked for directly — a test that
     * only checked `opponentHand` for nulls would pass just as happily against a payload carrying
     * the cards somewhere else.
     */
    @Test
    fun theOpponentsHandNeverReachesThePlayer() = server {
        val name = Postgres.freshAccount("pve-hidden")
        val session = register(name)
        val view = openMatch(session.token)

        val accountId = assertNotNull(AccountStore(Postgres.dataSource).accountIdForUsername(name))
        val row = assertNotNull(PveStore(Postgres.dataSource).activeFor(accountId))
        val body = activeBody(session.token)

        // Whatever the roulette drew, only the cards the rules do **not** reveal are secret — and
        // one already on the board was played face up. What is left is what must not be there.
        val shown = view.opponentHand.filterNotNull() + view.cells.mapNotNull { it?.cardId }
        val secret = row.redHand.filterNot { it in shown || it in view.hand }

        assertTrue(secret.isNotEmpty(), "the fixture revealed the whole hand; nothing was tested")
        for (card in secret) {
            assertFalse("$card" in body, "the opponent's card $card reached the player: $body")
        }
        // Not vacuous: the player is certainly sent their own cards.
        assertTrue(view.hand.all { "$it" in body })
    }

    /**
     * A placement comes back with the opponent's reply already made.
     *
     * The two placements arrive together — that is the round trip this endpoint saves — and both
     * are announced, because a client told only about the reply would animate an answer to a move
     * the player never saw played.
     */
    @Test
    fun aPlacementIsAnsweredWithTheOpponentsReplyAlready() = server {
        val session = register(Postgres.freshAccount("pve-reply"))
        val opened = openMatch(session.token)
        val before = opened.placement

        val after = place(session.token, opened)

        assertEquals(before + 2, after.placement, "the opponent did not reply in the same answer")
        assertEquals(2, after.plays.size, "both placements have to be announced")
        assertEquals(after.plays.map { it.player }.distinct().size, 2, "one each")
        assertTrue(after.playable.isNotEmpty(), "it should be the player's turn again")
    }

    @Test
    fun anIllegalPlacementIsRefused() = server {
        val session = register(Postgres.freshAccount("pve-illegal"))
        val opened = openMatch(session.token)
        val taken = opened.cells.indexOfFirst { it != null }
        val move = if (taken >= 0) {
            // A cell the opponent has already used.
            PveMove(opened.playable.first(), taken)
        } else {
            // Nothing is on the board yet, so a slot outside the hand is the illegal thing.
            PveMove(HAND + 1, 0)
        }

        val response = client.post("/pve/matches/${opened.matchId}/moves") {
            protocolHeaders()
            bearer(session.token)
            setBody(json.encodeToString(move))
        }

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        val unchanged = assertNotNull(activeMatch(session.token))
        assertEquals(opened.placement, unchanged.placement, "a refused move still moved the board")
    }

    /**
     * A match survives the client disappearing — which is the whole of the offline question now.
     *
     * There is nothing to recover and nothing to reconcile: the match never left the server, so
     * coming back to it is an ordinary read. A killed application, a tunnel, a flat battery are all
     * the same event, and none of them is an abandon.
     */
    @Test
    fun aMatchSurvivesTheClientDisappearing() = server {
        val session = register(Postgres.freshAccount("pve-resume"))
        val opened = openMatch(session.token)
        val played = place(session.token, opened)

        // Nothing here stands in for "the app was killed" because nothing has to: the next request
        // is simply the next request, with no state carried between them but a token.
        val resumed = assertNotNull(activeMatch(session.token))

        assertEquals(played.matchId, resumed.matchId)
        assertEquals(played.placement, resumed.placement)
        assertEquals(played.hand, resumed.hand)
        assertEquals(played.cells, resumed.cells)
        assertTrue(resumed.plays.isEmpty(), "a resumed match has nothing to animate")
    }

    /**
     * A whole match is played out, credited, and credited **once**.
     *
     * The second half is what the two guards exist for — `PveStore.finish` gating on the status,
     * and the unique index behind `AccountStore.creditRefereedMatch`. A double tap on the ninth
     * card, or a retry after a lost response, must not pay twice.
     */
    @Test
    fun aWholeMatchIsPlayedOutAndCreditedExactlyOnce() = server {
        val session = register(Postgres.freshAccount("pve-credit"))
        val finished = playOut(session.token)

        assertEquals(PveMatchStatus.FINISHED, finished.status)
        val outcome = assertNotNull(finished.outcome, "a finished match owes a result")
        assertEquals(TOTAL_CARDS, outcome.blue + outcome.red, "every card counts for somebody")
        val reward = assertNotNull(outcome.reward, "a credited match owes a payout")
        val player = assertNotNull(outcome.player, "the credited profile is the answer")

        // Asking again pays nothing more. The profile the server holds is the same one it just
        // reported, which is exactly the claim a client relies on when it replaces what it holds.
        val again = assertNotNull(activeMatch(session.token))
        assertEquals(PveMatchStatus.FINISHED, again.status)
        assertEquals(player.save.mgp, assertNotNull(again.outcome?.player).save.mgp)
        assertEquals(reward.mgp, assertNotNull(again.outcome?.reward).mgp)

        // And a placement offered against a match that is over is refused rather than replayed.
        val late = client.post("/pve/matches/${finished.matchId}/moves") {
            protocolHeaders()
            bearer(session.token)
            setBody(json.encodeToString(PveMove(0, 0)))
        }
        assertEquals(HttpStatusCode.Conflict, late.status)
    }

    /**
     * Opening a second match abandons the first rather than refusing.
     *
     * The opposite of the player-versus-player rule, and deliberately: there is nobody on the other
     * side to be stranded. An abandoned match pays nothing, so walking away is never a way to avoid
     * a result that was going badly.
     */
    @Test
    fun openingASecondMatchAbandonsTheFirst() = server {
        val session = register(Postgres.freshAccount("pve-second"))
        val first = openMatch(session.token)
        val second = openMatch(session.token)

        assertNotEquals(first.matchId, second.matchId)
        assertEquals(second.matchId, assertNotNull(activeMatch(session.token)).matchId)

        val abandoned = assertNotNull(matchById(session.token, first.matchId))
        assertEquals(PveMatchStatus.ABANDONED, abandoned.status)
        assertNull(abandoned.outcome?.reward, "an abandoned match pays nothing")
    }

    @Test
    fun anotherPlayersMatchCannotBeRead() = server {
        val mine = register(Postgres.freshAccount("pve-mine"))
        val theirs = register(Postgres.freshAccount("pve-theirs"))
        val match = openMatch(mine.token)

        val response = client.get("/pve/matches/${match.matchId}") {
            protocolHeaders()
            bearer(theirs.token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun anUnknownOpponentIsRefused() = server {
        val session = register(Postgres.freshAccount("pve-nobody"))

        val response = client.post("/pve/matches") {
            protocolHeaders()
            bearer(session.token)
            setBody(json.encodeToString(PveMatchRequest("not-an-opponent", FORMAT)))
        }

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        assertNull(activeMatch(session.token))
    }

    // ---- Fixtures ---------------------------------------------------------

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
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.openMatch(token: String): PveMatchView {
        val response = client.post("/pve/matches") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(PveMatchRequest(OPPONENT, FORMAT)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    /** Plays the first playable card into the first empty cell. */
    private suspend fun ApplicationTestBuilder.place(
        token: String,
        from: PveMatchView,
    ): PveMatchView {
        val move = PveMove(
            handIndex = from.playable.first(),
            position = from.cells.indexOfFirst { it == null },
        )
        val response = client.post("/pve/matches/${from.matchId}/moves") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(move))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Plays a match to the end.
     *
     * Bounded rather than `while (true)`: under Sudden Death a drawn board starts another, and a
     * test that hung would be a worse way to learn that than one that fails.
     */
    private suspend fun ApplicationTestBuilder.playOut(token: String): PveMatchView {
        var view = openMatch(token)
        var placements = 0

        while (view.status == PveMatchStatus.PLAYING && placements < MAX_PLACEMENTS) {
            if (view.playable.isEmpty()) break
            view = place(token, view)
            placements++
        }
        assertEquals(PveMatchStatus.FINISHED, view.status, "the match never finished")
        return view
    }

    private suspend fun ApplicationTestBuilder.activeMatch(token: String): PveMatchView? {
        val response = client.get("/pve/matches/active") {
            protocolHeaders()
            bearer(token)
        }
        if (response.status == HttpStatusCode.NoContent) return null
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.matchById(
        token: String,
        id: String,
    ): PveMatchView? {
        val response = client.get("/pve/matches/$id") {
            protocolHeaders()
            bearer(token)
        }
        if (response.status != HttpStatusCode.OK) return null
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.activeBody(token: String): String =
        client.get("/pve/matches/active") {
            protocolHeaders()
            bearer(token)
        }.bodyAsText()

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
        const val HAND = 5
        const val TOTAL_CARDS = 10

        /** Enough for a full board and several Sudden Death rematches. */
        const val MAX_PLACEMENTS = 60

        /** The widest authored format — every block, every rule. `FormatCatalog.default`. */
        const val FORMAT = "free-play"

        /**
         * A shipped opponent that keeps its hand to itself.
         *
         * Chosen by its **rules** rather than named, so the fixture survives the roster being
         * re-authored, and chosen at all because [theOpponentsHandNeverReachesThePlayer] has
         * nothing to assert against an opponent playing All Open — the cards are on the wire on
         * purpose there. Excluding the roulette too, since an opponent that draws its rules could
         * draw one of those on any given deal and make the test intermittent.
         *
         * Forty-nine of the eighty-four qualify, so this is not a narrow pick.
         */
        val OPPONENT: String = Catalogs.npcs
            .playing(FORMAT)
            .first { it.gameRules().open == OpenRule.NONE && !it.gameRules().roulette }
            .iconId
    }
}
