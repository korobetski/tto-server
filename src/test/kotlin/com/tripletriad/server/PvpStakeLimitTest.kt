package com.tripletriad.server

import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.PvpRefusal
import com.tripletriad.protocol.PvpStake
import com.tripletriad.protocol.PvpStakePolicy
import com.tripletriad.protocol.PvpTable
import com.tripletriad.protocol.PvpTableRequest
import com.tripletriad.protocol.Session
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

/**
 * The ceiling on a wager, enforced where it counts.
 *
 * ### Why this is a server test and not a client one
 *
 * The client bounds the field, and a bounded field is not a rule — [PvpUnlockTest] makes the same
 * argument about the level gate, and it applies harder here. The thing a ceiling exists to stop is
 * a farmed account handing its balance to a main one in a single match, and whoever is doing that
 * is by definition prepared to post the request by hand.
 *
 * ### Why every way into a match is measured separately
 *
 * There are three — open a table, join one, send an invitation — and they are three different
 * functions in `PvpReferee`. A check on the first alone would leave a player able to *join* a wager
 * they could not have proposed, which is precisely the direction the abuse runs in: the account
 * with the balance opens, and the account without it sits down.
 *
 * All the accounts here are levelled to `Unlocks.DEFAULT_MULTIPLAYER` by `register`, so the ceiling
 * under test is [CEILING] unless a test says otherwise.
 */
class PvpStakeLimitTest {

    // ---- Opening a table ---------------------------------------------------

    @Test
    fun aWagerAtTheCeilingOpens() = server {
        val name = Postgres.freshAccount("at-ceiling")
        val alice = register(name)
        purse(name, CEILING)

        assertEquals(HttpStatusCode.Created, openTable(alice.token, CEILING).status)
    }

    @Test
    fun aWagerOverTheCeilingIsRefusedAndSaysWhy() = server {
        val name = Postgres.freshAccount("over-ceiling")
        val alice = register(name)
        // Enough money for it, so the purse cannot be what refuses this.
        purse(name, CEILING * 10)

        val response = openTable(alice.token, CEILING + 1)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PvpRefusal.STAKE_TOO_HIGH, refusal(response).code)
    }

    /**
     * **A table you win by losing.**
     *
     * `PvpMatchRow.spoils` pays the winner `stake.mgp`, so a negative wager settles backwards. The
     * affordability check cannot catch it — it asks whether the purse is *at least* the stake, and
     * every negative number passes that. Before this, `POST /pvp/tables` accepted one.
     */
    @Test
    fun aNegativeWagerIsRefused() = server {
        val alice = register(Postgres.freshAccount("negative"))

        val response = openTable(alice.token, -CEILING)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PvpRefusal.STAKE_TOO_HIGH, refusal(response).code)
    }

    /**
     * A ceiling and an empty purse are different refusals, and are told apart.
     *
     * One is fixed by playing to the next level and the other by earning; a player told the wrong
     * one waits for the wrong thing. Both are 409s, which is why the code is what is asserted.
     */
    @Test
    fun aWagerUnderTheCeilingAndOverThePurseIsStillTheOtherRefusal() = server {
        val alice = register(Postgres.freshAccount("broke-not-capped"))

        val response = openTable(alice.token, CEILING - 1)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PvpRefusal.CANNOT_AFFORD, refusal(response).code)
    }

    /** The ceiling climbs, so the same wager one level up is an ordinary table. */
    @Test
    fun aLevelHigherIsAHigherCeiling() = server {
        val name = Postgres.freshAccount("levelled")
        val alice = register(name)
        purse(name, CEILING * 10)
        assertEquals(HttpStatusCode.Conflict, openTable(alice.token, CEILING + 1).status)

        unlockForPvp(name, level = LEVEL + 1)

        assertEquals(HttpStatusCode.Created, openTable(alice.token, CEILING + 1).status)
    }

    // ---- Joining one -------------------------------------------------------

    /**
     * The ceiling that applies to a seat is the **joiner's**, not the table's.
     *
     * This is the whole point of a limit that climbs with the level. The host here is levelled far
     * enough to propose the wager legitimately; the joiner is not, and is holding more than enough
     * money to cover it. Without this check that is exactly the shape of the transfer the ceiling
     * exists to prevent — and the shape of a new player losing an evening's play in one match.
     */
    @Test
    fun aTableAboveYourOwnCeilingCannotBeJoined() = server {
        val hostName = Postgres.freshAccount("high-host")
        val host = register(hostName)
        unlockForPvp(hostName, level = HIGH_LEVEL)
        purse(hostName, RICH)

        val joinerName = Postgres.freshAccount("low-joiner")
        val joiner = register(joinerName)
        purse(joinerName, RICH)

        val table = table(host.token, HIGH_WAGER)
        val response = join(joiner.token, table.id)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PvpRefusal.STAKE_TOO_HIGH, refusal(response).code)
    }

    @Test
    fun aTableWithinBothCeilingsIsJoinedNormally() = server {
        val hostName = Postgres.freshAccount("fair-host")
        val host = register(hostName)
        purse(hostName, RICH)
        val joinerName = Postgres.freshAccount("fair-joiner")
        val joiner = register(joinerName)
        purse(joinerName, RICH)

        val response = join(joiner.token, table(host.token, CEILING).id)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    // ---- Inviting somebody -------------------------------------------------

    /**
     * An invitation is checked at both ends, the way its affordability already was.
     *
     * The directed path is the one that matters most here: a table is advertised to everybody and
     * an invitation is aimed at one person, so if either way in were to skip the check it would be
     * this one that an arranged transfer used.
     */
    @Test
    fun anInvitationAboveTheInviteesCeilingIsRefused() = server {
        val hostName = Postgres.freshAccount("high-inviter")
        val host = register(hostName)
        unlockForPvp(hostName, level = HIGH_LEVEL)
        purse(hostName, RICH)

        val friendName = Postgres.freshAccount("low-friend")
        register(friendName)
        purse(friendName, RICH)

        val response = challenge(host.token, friendName, HIGH_WAGER)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PvpRefusal.STAKE_TOO_HIGH, refusal(response).code)
    }

    // ---- Whose numbers ------------------------------------------------------

    /**
     * The ceiling enforced is this deployment's, not `:core`'s default.
     *
     * Otherwise `ServerInfo.stakes` would be a label on a rule the server does not follow, and a
     * client would draw a field bounded at one number against a server refusing at another.
     */
    @Test
    fun theDeploymentsOwnCeilingIsTheOneEnforced() = testApplication {
        application {
            module(
                Postgres.dataSource,
                prometheusRegistry(),
                stakes = PvpStakePolicy(perLevel = TIGHT_PER_LEVEL),
            )
        }
        val name = Postgres.freshAccount("tight")
        val alice = register(name)
        purse(name, RICH)

        // Legal under the default ceiling of 500, and above this deployment's of 50.
        val response = openTable(alice.token, TIGHT_PER_LEVEL * LEVEL + 1)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PvpRefusal.STAKE_TOO_HIGH, refusal(response).code)
    }

    // ---- Harness -----------------------------------------------------------

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    /** Registered, confirmed and levelled to the multiplayer threshold, like `PvpFlowTest`. */
    private suspend fun ApplicationTestBuilder.register(name: String): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, TEST_PASSWORD, address(name))))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        unlockForPvp(name)
        return json.decodeFromString<Session>(response.bodyAsText())
    }

    /**
     * Sets the purse directly, so a test can separate "cannot afford" from "not allowed".
     *
     * Through the store rather than by playing matches for it: the amount is a precondition here,
     * and earning it would make every test in this file a test of `MatchRewards` as well.
     */
    private fun purse(name: String, mgp: Int) {
        val store = AccountStore(Postgres.dataSource)
        val accountId = requireNotNull(store.accountIdFor(name))
        store.mutate(accountId) { save -> Outcome(save.copy(mgp = mgp), Unit) }
    }

    private suspend fun ApplicationTestBuilder.openTable(token: String, mgp: Int): HttpResponse =
        client.post("/pvp/tables") {
            protocolHeaders()
            bearer(token)
            setBody(
                json.encodeToString(
                    PvpTableRequest(FORMAT, stake = PvpStake(mgp = mgp)),
                ),
            )
        }

    private suspend fun ApplicationTestBuilder.table(token: String, mgp: Int): PvpTable {
        val response = openTable(token, mgp)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.join(token: String, tableId: String): HttpResponse =
        client.post("/pvp/tables/$tableId/join") {
            protocolHeaders()
            bearer(token)
        }

    private suspend fun ApplicationTestBuilder.challenge(
        token: String,
        username: String,
        mgp: Int,
    ): HttpResponse = client.post("/pvp/challenges") {
        protocolHeaders()
        bearer(token)
        setBody(
            json.encodeToString(
                ChallengeRequest(
                    username = username,
                    terms = PvpTableRequest(FORMAT, stake = PvpStake(mgp = mgp)),
                ),
            ),
        )
    }

    private suspend fun refusal(response: HttpResponse): Refusal =
        json.decodeFromString(response.bodyAsText())

    private fun HttpRequestBuilder.protocolHeaders() {
        contentType(ContentType.Application.Json)
        header(VERSION_HEADER, CURRENT_VERSION.toString())
    }

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        /** The format that ships, as everywhere else in these tests. */
        const val FORMAT = "free-play"

        /** What `register` leaves an account at. */
        const val LEVEL = 5

        /** The ceiling that follows from it under the shipped policy: 500 MGP. */
        const val CEILING = PvpStakePolicy.DEFAULT_PER_LEVEL * LEVEL

        /** Well above the multiplayer threshold, so its ceiling is well above [CEILING]. */
        const val HIGH_LEVEL = 20

        /** Legal at [HIGH_LEVEL] and far over [CEILING]. */
        const val HIGH_WAGER = PvpStakePolicy.DEFAULT_PER_LEVEL * 15

        /** More than any wager here, so the purse is never what refuses. */
        const val RICH = 1_000_000

        /** A tenth of the shipped number, so a pass proves the configured one was consulted. */
        const val TIGHT_PER_LEVEL = 10
    }
}
