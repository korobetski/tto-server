package com.tripletriad.server

import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.PvpTableRequest
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.Unlocks
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
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
import kotlin.test.assertTrue

/**
 * The two doors in front of refereed play, enforced here rather than hidden on a client.
 *
 * ### Why the server has to be the one that says no
 *
 * The client hides the buttons, and a hidden button is not a rule — `AccountRoutes` makes the same
 * point about the campaign entry fee, which a modified client simply did not pay. The whole reason
 * for the gate is a player running two accounts and feeding one match to the other, and that player
 * is by definition the one prepared to send the request anyway.
 *
 * ### Why the rule is in `:core` and the numbers are not
 *
 * `Unlocks.allowsMultiplayer` is one function, in the repository both ends link, so there is no
 * second implementation to drift. The *thresholds* travel in `ServerInfo`, so raising one is an
 * environment variable and a restart rather than a coordinated release — see `ServerRoutesTest`,
 * which pins that they reach the client at all.
 *
 * ### What this file measures, and what `PvpFlowTest` does instead
 *
 * This one registers accounts and leaves them as they arrive. Every test there calls
 * `unlockForPvp`, because those tests are about what two players can do to each other once both
 * are allowed in — and a gate they had to satisfy on the way to every assertion would be measured
 * fifty times and stated nowhere.
 */
class PvpUnlockTest {

    /**
     * An unconfirmed address cannot open a table, whatever level it has reached.
     *
     * Named first in the refusal because it is the one a player can act on immediately: the code
     * is already in their inbox.
     */
    @Test
    fun anUnconfirmedAccountCannotOpenATable() = server {
        val name = Postgres.freshAccount("unconfirmed")
        val session = register(name)
        // Levelled but not confirmed, so the only thing left to refuse on is the address.
        levelUp(name, Unlocks.DEFAULT_MULTIPLAYER)

        val response = openTable(session.token)

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        assertEquals(AccountError.EMAIL_UNVERIFIED, failure(response).error)
    }

    /** And a confirmed one that has not reached the level cannot either. */
    @Test
    fun aConfirmedAccountBelowTheLevelIsStillRefused() = server {
        val name = Postgres.freshAccount("too-low")
        val session = register(name)
        confirm(name)

        val response = openTable(session.token)

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        assertEquals(AccountError.NOT_UNLOCKED, failure(response).error)
    }

    /** The refusal names the level, because "not unlocked" on its own is not an instruction. */
    @Test
    fun theRefusalSaysWhatLevelWouldBeEnough() = server {
        val name = Postgres.freshAccount("how-far")
        val session = register(name)
        confirm(name)

        val response = openTable(session.token)

        val detail = failure(response).detail
        assertTrue(
            Unlocks.DEFAULT_MULTIPLAYER.toString() in detail,
            "the refusal did not say what level unlocks it: $detail",
        )
    }

    /** Both satisfied, and the door opens. */
    @Test
    fun aConfirmedAccountAtTheLevelIsLetIn() = server {
        val name = Postgres.freshAccount("allowed")
        val session = register(name)
        unlockForPvp(name)

        val response = openTable(session.token)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    /**
     * The threshold that applies is this deployment's, not `:core`'s default.
     *
     * Otherwise the numbers in `ServerInfo` would be a label on a rule the server does not follow —
     * a client that showed "unlocks at level 2" while the server refused everyone below five.
     */
    @Test
    fun theDeploymentsOwnThresholdIsTheOneEnforced() = testApplication {
        application {
            module(
                Postgres.dataSource,
                prometheusRegistry(),
                unlocks = Unlocks(multiplayer = LOWERED),
            )
        }
        val name = Postgres.freshAccount("lowered")
        val session = register(name)
        confirm(name)
        levelUp(name, LOWERED)

        val response = openTable(session.token)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    /**
     * The gate is behind authentication, not in front of it.
     *
     * A caller with no token is a 401 — there is no account to have a level or an address, and
     * answering `NOT_UNLOCKED` would be a statement about an account nobody named.
     */
    @Test
    fun anUnauthenticatedCallerIsStillUnauthenticated() = server {
        val response = client.post("/pvp/tables") {
            protocolHeaders()
            setBody(json.encodeToString(PvpTableRequest(FORMAT)))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
    }

    // ---- Harness -----------------------------------------------------------

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.register(name: String): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(credentials(name)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString<Session>(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.openTable(token: String): HttpResponse =
        client.post("/pvp/tables") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(PvpTableRequest(FORMAT)))
        }

    /**
     * Confirms the address without going through the mail.
     *
     * The flow itself is `CredentialRecoveryTest`'s subject. Here it is a precondition, and one
     * that has to be settable *independently* of the level — the point of half these tests is that
     * the two doors are separate.
     */
    private fun confirm(name: String) {
        val store = AccountStore(Postgres.dataSource)
        val accountId = requireNotNull(store.accountIdFor(name))
        store.markVerified(accountId, System.currentTimeMillis())
    }

    private fun levelUp(name: String, level: Int) {
        val store = AccountStore(Postgres.dataSource)
        val accountId = requireNotNull(store.accountIdFor(name))
        store.mutate(accountId) { save -> Outcome(save.copy(level = level), Unit) }
    }

    private suspend fun failure(response: HttpResponse): AccountFailure =
        json.decodeFromString<AccountFailure>(response.bodyAsText())

    private fun HttpRequestBuilder.protocolHeaders() {
        contentType(ContentType.Application.Json)
        header(VERSION_HEADER, CURRENT_VERSION.toString())
    }

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        /** The format `PvpFlowTest` uses, for the same reason: it is the one that ships. */
        const val FORMAT = "free-play"

        /** Below the default, so a pass proves the configured number is what was consulted. */
        const val LOWERED = 2
    }
}
