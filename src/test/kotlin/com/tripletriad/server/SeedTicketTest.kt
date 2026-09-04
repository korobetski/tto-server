package com.tripletriad.server

import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.MatchReceipt
import com.tripletriad.protocol.MatchVerdict
import com.tripletriad.protocol.RejectionReason
import com.tripletriad.protocol.SeedTickets
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Seeds are the server's to issue, and a client cannot mint one.
 *
 * ### What was wrong
 *
 * `MatchTranscript.seed` is the whole of a match's randomness — the opponent's hand, the roulette,
 * the coin flip, every one of the opponent's moves. A client that chose it chose the deal: play the
 * match, look at what the opponent was dealt, and if it is bad start again on another number. The
 * transcript that eventually arrived was a real match, honestly played, indistinguishable from one
 * nobody auditioned. The old comment called it "seed grinding" and judged the gain small; it was
 * unbounded and free.
 *
 * ### What these tests hold to
 *
 * That an unissued seed is refused, that a seed is good exactly once, that **spending one voids the
 * ones before it** — the rule that stops a stock of fifty from being a stock of fifty deals to
 * choose between — and that none of it broke the two things that were already true: an offline
 * queue may drain twice, and a match played with no network still counts.
 */
class SeedTicketTest {

    /** A seed the server never issued is refused, with a reason that names itself. */
    @Test
    fun aSeedThatWasNeverIssuedIsRefused() = server {
        val session = register("minted")

        val receipt = submit(session.token, Transcripts.honest(session.player.save, 12_345))

        val rejected = assertIs<MatchVerdict.Rejected>(receipt.verdict)
        assertEquals(RejectionReason.UNKNOWN_SEED, rejected.reason, rejected.detail)
    }

    /** An issued one is accepted, which is what stops the rule above from refusing everybody. */
    @Test
    fun anIssuedSeedIsAccepted() = server {
        val session = register("honest")

        val receipt = submit(
            session.token,
            Transcripts.honest(session.player.save, tickets(session.token).first()),
        )

        assertIs<MatchVerdict.Accepted>(receipt.verdict)
    }

    /**
     * A seed is good once. A **second, different** match on it is refused.
     *
     * Distinct from a resubmission of the same transcript, which is a duplicate and is fine — see
     * [aQueueMayStillDrainTwice]. The two look alike from a distance and mean opposite things.
     */
    @Test
    fun aSeedCannotBePlayedTwice() = server {
        val session = register("twice")
        val seed = tickets(session.token).first()

        assertIs<MatchVerdict.Accepted>(
            submit(session.token, Transcripts.honest(session.player.save, seed)).verdict,
        )

        // The same seed, a different opponent: a different match, on a spent ticket.
        val other = Transcripts.honest(session.player.save, seed, opponent = 1)
        val again = submit(session.token, other)
        val rejected = assertIs<MatchVerdict.Rejected>(again.verdict)
        assertEquals(RejectionReason.UNKNOWN_SEED, rejected.reason, rejected.detail)
    }

    /**
     * Resubmitting the **same** transcript is still a duplicate, not a forgery.
     *
     * The regression this pins cost two failing tests to find. A resubmission is played on a seed
     * its own first submission spent, so an implementation that reaches for the ticket before
     * checking the transcript hash reports a careful offline client as a cheat.
     */
    @Test
    fun aQueueMayStillDrainTwice() = server {
        val session = register("draining")
        val transcript = Transcripts.honest(session.player.save, tickets(session.token).first())

        val first = submit(session.token, transcript)
        val second = submit(session.token, transcript)

        assertIs<MatchVerdict.Accepted>(second.verdict, "a resubmission was called a forgery")
        assertTrue(second.duplicate, "a resubmission was credited twice")
        assertEquals(first.player?.save?.mgp, second.player?.save?.mgp)
    }

    /**
     * Spending a ticket voids the ones issued before it.
     *
     * The anti-grinding rule, and the reason a stock is safe to hand out. Skipping ahead is allowed
     * — abandoning a match and starting another has to keep working — but it costs the seeds it
     * skipped, so searching the stock shrinks it instead of being free.
     */
    @Test
    fun spendingASeedVoidsTheOnesBeforeIt() = server {
        val session = register("skipper")
        val held = tickets(session.token)
        assertTrue(held.size > 2, "the fixture needs a stock to skip through")

        // Play the third one, skipping two.
        val skipped = held[0]
        val played = held[2]
        assertIs<MatchVerdict.Accepted>(
            submit(session.token, Transcripts.honest(session.player.save, played)).verdict,
        )

        // The two behind it are gone, so the skip cost what it skipped.
        val rejected = submit(session.token, Transcripts.honest(session.player.save, skipped))
        assertEquals(
            RejectionReason.UNKNOWN_SEED,
            assertIs<MatchVerdict.Rejected>(rejected.verdict).reason,
            "a skipped seed was still good, so searching the stock is free",
        )
    }

    /**
     * Asking twice issues nothing the second time, which is what makes a `GET` honest here.
     *
     * The endpoint tops up to a ceiling rather than handing out a batch, so a client that retries
     * after a response it never saw does not end up with a hundred seeds.
     */
    @Test
    fun toppingUpTwiceIssuesNothingTheSecondTime() = server {
        val session = register("topped")

        val first = tickets(session.token)
        val second = tickets(session.token)

        assertEquals(SeedTickets.MAX_UNSPENT, first.size, "a fresh account was not filled up")
        assertEquals(first, second, "asking twice issued a second batch")
    }

    /** And a spent one is replaced on the next top-up, so the stock does not dwindle. */
    @Test
    fun aSpentSeedIsReplacedOnTheNextTopUp() = server {
        val session = register("refilled")
        val held = tickets(session.token)
        submit(session.token, Transcripts.honest(session.player.save, held.first()))

        val refilled = tickets(session.token)

        assertEquals(SeedTickets.MAX_UNSPENT, refilled.size, "the stock was not topped back up")
        assertTrue(held.first() !in refilled, "a spent seed came back")
    }

    // ---- Harness ----------------------------------------------------------

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.register(prefix: String): Session {
        val who = Postgres.freshAccount(prefix)
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(credentials(who)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        // Registration deals no cards; the box does. See [openStarterBox].
        return openStarterBox(json.decodeFromString(response.bodyAsText()))
    }

    private suspend fun ApplicationTestBuilder.tickets(token: String): List<Int> {
        val response = client.get("/matches/tickets") {
            protocolHeaders()
            bearer(token)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<SeedTickets>(response.bodyAsText()).seeds
    }

    private suspend fun ApplicationTestBuilder.submit(
        token: String,
        transcript: com.tripletriad.protocol.MatchTranscript,
    ): MatchReceipt {
        val response = client.post("/matches/submit") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(transcript))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
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
        const val PASSWORD = "correct-horse-battery"
    }
}
