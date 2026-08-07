package com.tripletriad.server

import com.tripletriad.data.PveMatches
import com.tripletriad.model.CardCollection
import com.tripletriad.model.CardColor
import com.tripletriad.model.Deck
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchAi
import com.tripletriad.protocol.MatchTranscript
import com.tripletriad.protocol.MatchVerdict
import com.tripletriad.protocol.RejectionReason
import com.tripletriad.protocol.TranscriptMove
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The first real exchange between a client and this server, end to end.
 *
 * ### What is actually being proven here
 *
 * That the server reaches its own verdict about a match it never watched, using the **shipped**
 * card and opponent tables and the **client's own engine**. The transcripts below are built by
 * playing a match with `:core` — the same code the client runs — and the server accepts them
 * without being told the score.
 *
 * That is why the fixture plays a real match rather than hand-writing nine moves: a hand-written
 * transcript would only prove that the endpoint parses JSON.
 */
class MatchRoutesTest {

    @Test
    fun anHonestTranscriptIsAcceptedAndScoredByTheServer() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(honestTranscript(SEED)))
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val verdict = json.decodeFromString<MatchVerdict>(response.bodyAsText())
        val accepted = assertIs<MatchVerdict.Accepted>(verdict, response.bodyAsText())
        assertEquals(TOTAL_CARDS, accepted.blue + accepted.red)
    }

    /**
     * A tampered transcript is refused — and refused with **200**, not 4xx.
     *
     * "This match did not happen" is the service working, and the reason is part of the answer. A
     * 4xx would confuse a rejected claim with a request the server could not process.
     */
    @Test
    fun aForgedTranscriptIsRejectedWithAReasonAndStillTwoHundred() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val honest = honestTranscript(SEED)
        val forged = honest.copy(moves = honest.moves.dropLast(1))

        val response = client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(forged))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rejected = assertIs<MatchVerdict.Rejected>(
            json.decodeFromString<MatchVerdict>(response.bodyAsText()),
        )
        assertEquals(RejectionReason.TRUNCATED, rejected.reason, rejected.detail)
    }

    /** A body that will not parse *is* a client error, unlike a claim that does not hold up. */
    @Test
    fun aMalformedBodyIsAClientError() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            setBody("{\"not\":\"a transcript\"}")
        }

        assertTrue(
            response.status.value in CLIENT_ERROR_RANGE,
            "expected a 4xx for an unparseable body, got ${response.status}",
        )
    }

    /**
     * Verification does not touch the database.
     *
     * Worth asserting rather than assuming: every test here runs with an unreachable data source,
     * so if replaying ever started needing a connection, these would fail rather than the property
     * quietly changing. It is also what makes the endpoint usable before accounts exist.
     */
    @Test
    fun verificationNeedsNoDatabase() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(honestTranscript(SEED + 1)))
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertIs<MatchVerdict.Accepted>(json.decodeFromString<MatchVerdict>(response.bodyAsText()))
    }

    /**
     * Plays a real match against a shipped opponent and writes down what the player did.
     *
     * This is the client's half of the protocol. Note the move choice: first card in hand, first
     * empty cell. It must not consult [Random], because the match generator is shared with the
     * opponent's moves and drawing from it on the player's turn would desynchronise the replay —
     * the invariant documented on `TranscriptVerifier`.
     */
    private fun honestTranscript(seed: Int): MatchTranscript {
        val opponent = Catalogs.npcs.collection(COLLECTION).first()
        val deck = Catalogs.cards.collection(COLLECTION.prefix).take(DECK_SIZE).map { it.id }
        val profile = GameSave(
            mode = COLLECTION,
            cards = deck,
            decks = listOf(Deck("test", deck)),
        )

        val random = Random(seed)
        val match = PveMatches.assemble(profile, opponent, Catalogs.cards, random)
        val ai = MatchAi()
        var state = match.setup.state
        val moves = mutableListOf<TranscriptMove>()

        while (!state.isFinished) {
            state = if (state.currentPlayer == CardColor.BLUE) {
                val card = state.currentHand.first()
                val position = state.playablePositions().first()
                moves += TranscriptMove(card.id, position)
                state.play(card, position)
            } else {
                ai.play(state, random)
            }
        }

        return MatchTranscript(
            seed = seed,
            collection = COLLECTION,
            opponentIconId = opponent.iconId,
            deck = deck,
            ownedCards = deck,
            moves = moves,
        )
    }

    private val json = Json

    private companion object {
        const val SEED = 20260807
        const val DECK_SIZE = 5

        /** Nine cells, plus the card left in the winner's hand. */
        const val TOTAL_CARDS = 10

        val COLLECTION = CardCollection.FF14
        val CLIENT_ERROR_RANGE = 400..499
    }
}
