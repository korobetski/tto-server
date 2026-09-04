package com.tripletriad.server

import com.tripletriad.model.CardColor
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    /**
     * **The deal puts no card on the board, and the toss is not left to chance to prove it.**
     *
     * It used to put one there: an opponent that won the opening played it into the deal's own
     * answer, so the client received a decision it had not yet announced and had to take the card
     * back off before it could announce it. The board is dealt here and begun in
     * [theFirstReadPlaysTheOpeningTheTossGaveTheOpponent].
     *
     * Driven through [PveReferee] rather than the route, because that is where the seam for the
     * toss is — see [refereeSeeded]. Asserting this over HTTP would mean dealing until the coin
     * came up the right way, which is a slower way of testing a weaker claim.
     */
    @Test
    fun theDealAnnouncesNothingAndLeavesTheBoardEmpty() = server {
        val session = register(Postgres.freshAccount("pve-open"))

        val view = dealSeeded(session, OPPONENT_WINS_TOSS)

        assertEquals(CardColor.RED, view.first, "the seed is chosen to give the opponent the toss")
        assertEquals(PveMatchStatus.PLAYING, view.status)
        assertEquals(OPPONENT, view.opponentIconId)
        assertEquals(HAND, view.hand.size)
        assertTrue(view.cells.all { it == null }, "nobody has played on a board just dealt")
        assertTrue(view.plays.isEmpty(), "and so there is nothing to announce")
        assertEquals(HAND, view.opponentHand.size, "the opponent still holds all five")
        assertTrue(view.playable.isEmpty(), "and it is not the player's turn to move")
    }

    /** The other half of the toss needs no read to be playable, and owes no announcement. */
    @Test
    fun aDealThePlayerWonTheTossForIsPlayableStraightAway() = server {
        val session = register(Postgres.freshAccount("pve-blue"))
        val referee = refereeSeeded(PLAYER_WINS_TOSS)
        val accountId = accountIdOf(session)

        val view = dealt(referee, accountId)

        assertEquals(CardColor.BLUE, view.first, "the seed is chosen to give the player the toss")
        assertTrue(view.playable.isNotEmpty(), "so the player is on move already")
        assertTrue(view.cells.all { it == null }, "and nobody has played yet")
        assertTrue(view.plays.isEmpty())

        // The read that begins a match owes nothing here, and must invent nothing: the opponent is
        // not on move, so there is no placement for it to compute.
        val read = assertNotNull(referee.view(view.matchId, accountId))

        assertTrue(read.cells.all { it == null }, "a read is not a placement")
        assertTrue(read.plays.isEmpty())
        assertTrue(read.playable.isNotEmpty(), "and it is still the player's move")
    }

    /**
     * **The first read is what starts the match**, and it starts it exactly once.
     *
     * The opening owed by a toss the opponent won is computed here rather than at the deal, so it
     * arrives after the client's announcements rather than under them. Reading again must not play
     * again — the row's move count is what the append is gated on, and this is the assertion that
     * says so from the outside.
     */
    @Test
    fun theFirstReadPlaysTheOpeningTheTossGaveTheOpponent() = server {
        val session = register(Postgres.freshAccount("pve-begin"))
        val referee = refereeSeeded(OPPONENT_WINS_TOSS)
        val accountId = accountIdOf(session)
        val dealt = dealt(referee, accountId)
        assertEquals(CardColor.RED, dealt.first, "the seed is chosen to give the opponent the toss")

        val begun = assertNotNull(referee.view(dealt.matchId, accountId))

        assertEquals(1, begun.cells.count { it != null }, "the opening the toss owed has landed")
        assertEquals(1, begun.plays.size, "and it has to be announced to be animated")
        assertEquals(
            HAND - 1,
            begun.opponentHand.size,
            "the opponent's card count is public, and it has played one",
        )
        assertTrue(begun.playable.isNotEmpty(), "the player is on move once the opening is in")

        // Reading is not playing. The second read finds the opening already written, appends
        // nothing and announces nothing — otherwise a client that refreshed twice would watch the
        // same card land twice, and one that refreshed nine times would fill the board.
        val again = assertNotNull(referee.view(dealt.matchId, accountId))

        assertEquals(begun.cells, again.cells, "a read must not move a card")
        assertTrue(again.plays.isEmpty(), "and must not announce one it did not make")
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
            setBody(json.encodeToString(Credentials(name, PASSWORD, address(name))))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        // Registration deals no cards; the box does. See [openStarterBox].
        return openStarterBox(json.decodeFromString(response.bodyAsText()))
    }

    /** The deal alone: a board nobody has played on, whichever way the toss went. */
    private suspend fun ApplicationTestBuilder.dealMatch(token: String): PveMatchView {
        val response = client.post("/pve/matches") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(PveMatchRequest(OPPONENT, FORMAT)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * A referee whose **deal** is reproducible, [seed] deciding the toss.
     *
     * `PveReferee` takes its generator as a parameter for exactly this, and the route wires the
     * real one — so this reaches the seam without opening a way for a *client* to choose a toss,
     * which is the thing that must never exist.
     *
     * ### Only the first draw is seeded, and that is not a detail
     *
     * `PveReferee.deal` opens by taking one generator and decides the rules, both hands and the
     * toss from it; the match id comes from a **second** call, and the opponent's own choices from
     * later ones. Seeding every call would make the id a function of the seed alone — the same
     * board dealt twice in a run collides on the primary key, which is a test failing on a
     * fixture's arithmetic rather than on the thing it is about. Seeding the first call gives the
     * reproducible deal and leaves the id alone.
     *
     * Every test using this asserts the toss it expected. A seed is an opaque number whose meaning
     * comes from the order the deal happens to draw in, and that assertion is what turns a
     * reordered deal from a silently weaker test into a failing one.
     */
    private fun refereeSeeded(seed: Int): PveReferee {
        var dealt = false
        return PveReferee(
            Catalogs.cards,
            Catalogs.npcs,
            Catalogs.formats,
            AccountStore(Postgres.dataSource),
            PveStore(Postgres.dataSource),
            Catalogs.campaigns,
            System::currentTimeMillis,
        ) {
            if (dealt) Random.Default else Random(seed).also { dealt = true }
        }
    }

    /** The account behind a session, which a referee is addressed by and a route is not. */
    private fun accountIdOf(session: Session): Long = assertNotNull(
        AccountStore(Postgres.dataSource).accountIdForUsername(session.player.save.username),
    )

    /** A board dealt by [referee], refusing to guess at anything else it might have answered. */
    private fun dealt(referee: PveReferee, accountId: Long): PveMatchView = assertIs<Dealt.Playing>(
        referee.open(accountId, PveMatchRequest(OPPONENT, FORMAT)),
        "the fixture account has to be able to field a deck",
    ).view

    /** [dealt] for a test that has no other use for the referee it dealt with. */
    private fun ApplicationTestBuilder.dealSeeded(session: Session, seed: Int): PveMatchView =
        dealt(refereeSeeded(seed), accountIdOf(session))

    /**
     * A board the player can play on: dealt, then **begun**.
     *
     * Two requests, because that is what a client makes — the read is where an opening owed to the
     * toss is played, and without it half the matches a test opens are waiting on the opponent. A
     * test about anything other than the deal wants this one.
     */
    private suspend fun ApplicationTestBuilder.openMatch(token: String): PveMatchView =
        assertNotNull(matchById(token, dealMatch(token).matchId))

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

        /**
         * Seeds naming a deal whose toss went each way, found by trying the small integers and
         * reading the toss back. Shareable between tests — see `refereeSeeded`, which keeps the
         * match id out of what a seed decides.
         */
        const val OPPONENT_WINS_TOSS = 2
        const val PLAYER_WINS_TOSS = 1

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
