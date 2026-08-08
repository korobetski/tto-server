package com.tripletriad.server

import com.tripletriad.model.Deck
import com.tripletriad.model.MatchResult
import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.MatchReceipt
import com.tripletriad.protocol.MatchTranscript
import com.tripletriad.protocol.MatchVerdict
import com.tripletriad.protocol.PlayerState
import com.tripletriad.protocol.RejectionReason
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole feature, against a real Postgres: **sign up, play, come back, find it all there.**
 *
 * ### Why this is one test class and not four
 *
 * Because the property being proven spans all of it. "Register" is not interesting on its own;
 * neither is "submit a match". What matters is that a match played on one device is credited by
 * the server, written to the database, and still there for a client that has never seen it — and
 * the only way to demonstrate that is to do the whole thing and then throw the client away.
 *
 * Each test signs a fresh account up, so nothing here depends on what another test left behind.
 */
class AccountFlowTest {

    /**
     * The headline: a returning client with nothing but a token gets its profile and its stats.
     *
     * The second `testApplication` block is the point. It is a different server instance with a
     * different in-memory everything; all that survives is the row in Postgres and the token the
     * player is holding. That is what "reconnecting restores the account" has to mean.
     */
    @Test
    fun aMatchPlayedOnOneClientIsStillThereForTheNext() {
        val name = Postgres.freshAccount("returning")
        var token = ""
        var afterMatch: PlayerState? = null

        server {
            val session = register(name)
            token = session.token

            val receipt = submit(token, Transcripts.honest(session.player.save, SEED))
            assertIs<MatchVerdict.Accepted>(receipt.verdict)
            assertFalse(receipt.duplicate, "a first submission was treated as a repeat")
            afterMatch = assertNotNull(receipt.player, "an accepted match credited nothing")
        }

        // A different server, a different client, the same token: what a player relaunching the app
        // on another device has.
        server {
            val restored = me(token)
            val credited = assertNotNull(afterMatch)

            assertEquals(1, restored.stats.played, "the match did not survive the round trip")
            assertEquals(credited.stats.wins, restored.stats.wins)
            assertEquals(credited.save.mgp, restored.save.mgp, "the profile's MGP was not stored")
            assertEquals(name, restored.save.username)
            assertEquals(1, restored.stats.recent.size)
            assertEquals(SEED, restored.stats.recent.first().seed)
        }
    }

    /** Signing in returns the same character, not a fresh one — the account *is* the profile. */
    @Test
    fun signingInReturnsTheStoredProfile() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        val name = Postgres.freshAccount("signin")

        val registered = register(name)
        submit(registered.token, Transcripts.honest(registered.player.save, SEED))

        val signedIn = signIn(name)
        assertEquals(1, signedIn.player.stats.played)
        assertTrue(
            signedIn.player.save.mgp > registered.player.save.mgp,
            "signing in returned a profile that had not been paid for the match",
        )
        assertTrue(signedIn.token != registered.token, "signing in reused the old token")
    }

    /**
     * A queue draining twice is paid once.
     *
     * The second answer is not a rejection — the match was real — but it must not credit again, or
     * an offline client that loses one acknowledgement mints MGP by retrying.
     */
    @Test
    fun thesameMatchSubmittedTwiceIsCreditedOnce() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }

        val session = register(Postgres.freshAccount("duplicate"))
        val transcript = Transcripts.honest(session.player.save, SEED)

        val first = submit(session.token, transcript)
        val second = submit(session.token, transcript)

        assertFalse(first.duplicate)
        assertTrue(second.duplicate, "the same transcript was accepted for credit twice")
        assertEquals(
            assertNotNull(first.player).save.mgp,
            assertNotNull(second.player).save.mgp,
            "the repeat submission moved the balance",
        )
        assertEquals(1, second.player!!.stats.played, "the repeat was recorded as a second match")
    }

    /**
     * The check that could not be made without accounts: a deck of cards the player does not own.
     *
     * The transcript is internally consistent and would pass `/verify`, because there it declares
     * its own `ownedCards` and nothing contradicts it. Against the stored profile it is a claim
     * about somebody else's collection, and the server says so.
     */
    @Test
    fun aDeckTheStoredProfileDoesNotOwnIsRejected() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }

        val session = register(Postgres.freshAccount("borrowed"))
        val rich = session.player.save.let { save ->
            val extra = Catalogs.cards.collection(save.mode.prefix)
                .map { it.id }
                .filterNot { it in save.cards }
                .take(DECK_SIZE)
            save.copy(cards = save.cards + extra, decks = listOf(Deck("borrowed", extra)))
        }

        val borrowed = Transcripts.honest(rich, SEED)

        // It is a legal game — the unauthenticated endpoint accepts it.
        assertIs<MatchVerdict.Accepted>(verify(borrowed))

        // It is not this player's game.
        val rejected = assertIs<MatchVerdict.Rejected>(submit(session.token, borrowed).verdict)
        assertEquals(RejectionReason.DECK_NOT_OWNED, rejected.reason, rejected.detail)
    }

    /** Nothing is credited without a session, and the refusal names why. */
    @Test
    fun submittingWithoutASessionIsRefused() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }

        val session = register(Postgres.freshAccount("anonymous"))
        val transcript = Transcripts.honest(session.player.save, SEED)

        val response = client.post("/matches/submit") {
            protocolHeaders()
            setBody(json.encodeToString(transcript))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(AccountError.UNAUTHENTICATED, response.failure().error)
    }

    /** A signed-out token stops working immediately — that is the whole job of sign-out. */
    @Test
    fun signingOutRevokesTheToken() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }

        val session = register(Postgres.freshAccount("signout"))
        val signOut = client.delete("/sessions") { bearer(session.token) }
        assertEquals(HttpStatusCode.NoContent, signOut.status)

        val response = client.get("/me") {
            protocolHeaders()
            bearer(session.token)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun aTakenNameIsRefusedWithoutSayingAnythingElse() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        val name = Postgres.freshAccount("taken")
        register(name)

        // Different case, because the account name is compared case-insensitively: `username_key`
        // is what stops `Kuplu` and `kuplu` from being two players with the same name on a
        // scoreboard.
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name.uppercase(), PASSWORD)))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(AccountError.USERNAME_TAKEN, response.failure().error)
    }

    /**
     * A wrong password and an unknown account give the **same** answer.
     *
     * Asserted rather than left to the implementation, because the two branches are far apart in
     * `AccountRoutes` and the natural edit — "tell them the name doesn't exist, it's friendlier" —
     * turns the sign-in form into a way of enumerating accounts.
     */
    @Test
    fun aWrongPasswordAndAnUnknownAccountAreIndistinguishable() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        val name = Postgres.freshAccount("oracle")
        register(name)

        val wrongPassword = client.post("/sessions") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, "$PASSWORD-wrong")))
        }
        val noSuchAccount = client.post("/sessions") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials("$name-nobody", PASSWORD)))
        }

        assertEquals(wrongPassword.status, noSuchAccount.status)
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        assertEquals(wrongPassword.bodyAsText(), noSuchAccount.bodyAsText())
    }

    @Test
    fun aPasswordTooShortIsRefusedBeforeAnAccountExists() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        val name = Postgres.freshAccount("short")

        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, "short")))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(AccountError.MALFORMED_CREDENTIALS, response.failure().error)

        // And the name is still free, which is what "refused before an account exists" means.
        val retry = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, PASSWORD)))
        }
        assertEquals(HttpStatusCode.Created, retry.status)
    }

    /** The payout is the server's, and the profile it hands back is the one it wrote. */
    @Test
    fun theServerCreditsTheRewardItself() = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }

        val session = register(Postgres.freshAccount("reward"))
        val before = session.player.save

        val receipt = submit(session.token, Transcripts.honest(before, SEED))
        val reward = assertNotNull(receipt.reward, "an accepted match reported no reward")
        val after = assertNotNull(receipt.player).save

        assertEquals(before.mgp + reward.mgp, after.mgp)
        assertEquals(before.pveMatches + 1, after.pveMatches, "the match was not counted")
        assertTrue(
            reward.items.isEmpty() || reward.result == MatchResult.WIN,
            "the drop table was rolled for a result that does not roll it",
        )
    }

    // ---- The client half --------------------------------------------------

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

    private suspend fun ApplicationTestBuilder.signIn(name: String): Session {
        val response = client.post("/sessions") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, PASSWORD)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<Session>(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.me(token: String): PlayerState {
        val response = client.get("/me") {
            protocolHeaders()
            bearer(token)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<PlayerState>(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.submit(
        token: String,
        transcript: MatchTranscript,
    ): MatchReceipt {
        val response = client.post("/matches/submit") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(transcript))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<MatchReceipt>(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.verify(transcript: MatchTranscript): MatchVerdict {
        val response = client.post("/matches/verify") {
            protocolHeaders()
            setBody(json.encodeToString(transcript))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<MatchVerdict>(response.bodyAsText())
    }

    private suspend fun HttpResponse.failure(): AccountFailure =
        json.decodeFromString<AccountFailure>(bodyAsText())

    private fun HttpRequestBuilder.protocolHeaders() {
        contentType(ContentType.Application.Json)
        header(VERSION_HEADER, CURRENT_VERSION.toString())
    }

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val SEED = 20260807
        const val DECK_SIZE = 5

        /**
         * Long enough for [Credentials.PASSWORD_LENGTH] and not a real one anywhere.
         *
         * A fixture, not a secret: it exists only inside a container that is destroyed with the
         * test run, and it is here in the source precisely so nobody wonders whether it is reused.
         */
        const val PASSWORD = "not-a-real-password"
    }
}
