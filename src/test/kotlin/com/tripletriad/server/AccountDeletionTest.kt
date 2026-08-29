package com.tripletriad.server

import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting an account, and the two things that makes it different from every other request here.
 *
 * ### It is the only irreversible one
 *
 * Everything else this API does can be undone by doing something else: a sold card can be bought
 * back, a withdrawn table re-opened, a session signed into again. This cannot. That is the whole
 * reason it asks for the password even though the caller already holds a token — see the route.
 *
 * ### It is the one that has to actually remove things
 *
 * A deletion that leaves the character behind is worse than no deletion at all, because it is a
 * promise that reads as kept. [everythingBelongingToTheAccountGoesWithIt] is the assertion that
 * matters, and it checks the tables rather than the endpoint's answer.
 */
class AccountDeletionTest {

    /** The password is what authorises it, not the token the caller is already holding. */
    @Test
    fun aStolenTokenAloneCannotDeleteTheAccount() = server {
        val name = Postgres.freshAccount("victim")
        val session = register(name)

        val refused = deleteAccount(session.token, "not-the-password")

        assertEquals(HttpStatusCode.Unauthorized, refused.status, refused.bodyAsText())
        assertEquals(
            AccountError.INVALID_CREDENTIALS,
            json.decodeFromString<AccountFailure>(refused.bodyAsText()).error,
        )
        // And the account is still there, which is the half a status code cannot tell you.
        assertEquals(HttpStatusCode.OK, me(session.token).status, "the account was deleted anyway")
    }

    /** With the password, it goes. */
    @Test
    fun thePasswordDeletesTheAccount() = server {
        val name = Postgres.freshAccount("leaving")
        val session = register(name)

        val response = deleteAccount(session.token, PASSWORD)

        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
        assertEquals(
            HttpStatusCode.Unauthorized,
            me(session.token).status,
            "the token still worked after the account was deleted",
        )
    }

    /**
     * Everything belonging to the account goes with it — checked in the database, not the response.
     *
     * `docs/data-inventory.md` claims this, on the strength of every table referencing `accounts`
     * with `ON DELETE CASCADE`. A claim in a document about privacy is exactly the sort that has to
     * be tested rather than believed, and the failure it guards against is silent: a table added
     * later without the cascade leaves rows behind and nothing says so.
     */
    @Test
    fun everythingBelongingToTheAccountGoesWithIt() = server {
        val name = Postgres.freshAccount("thorough")
        val session = register(name)
        val accounts = AccountStore(Postgres.dataSource)
        val id = assertNotNullId(accounts.accountIdForUsername(name))

        // Give the account something in as many tables as a test can reach: a character exists
        // already, a match needs a ticket, and a ticket needs issuing.
        val seed = tickets(session.token)
        submit(session.token, Transcripts.honest(session.player.save, seed))
        assertTrue(rowsFor(id) > 0, "the fixture left nothing to delete")

        deleteAccount(session.token, PASSWORD)

        assertEquals(0, rowsFor(id), "rows survived the account they belonged to")
        assertNull(accounts.saveFor(id), "the character survived")
    }

    /**
     * Asking twice is not an error.
     *
     * A client that lost the first answer and asked again has got what it wanted. Reporting "no
     * such account" would be reporting success as a failure — and the second request cannot even
     * authenticate, since deleting the account took its sessions with it.
     */
    @Test
    fun askingTwiceIsNotAnError() = server {
        val name = Postgres.freshAccount("twice")
        val session = register(name)

        assertEquals(HttpStatusCode.NoContent, deleteAccount(session.token, PASSWORD).status)

        // The token is gone with the sessions, so the second attempt is refused for *that* reason
        // rather than for the account being missing — which is the correct answer to a request
        // nobody can prove they are entitled to make.
        assertEquals(HttpStatusCode.Unauthorized, deleteAccount(session.token, PASSWORD).status)
    }

    /** And the name is free again, which is what "deleted" has to mean to be worth anything. */
    @Test
    fun theNameCanBeUsedAgainAfterwards() = server {
        val name = Postgres.freshAccount("recycled")
        val session = register(name)
        deleteAccount(session.token, PASSWORD)

        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, PASSWORD, address(name))))
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    // ---- Harness ----------------------------------------------------------

    /** Every row this account owns, across the tables that reference it. */
    private fun rowsFor(accountId: Long): Int = Postgres.dataSource.connection.use { db ->
        val tables = listOf(
            "characters" to "account_id",
            "matches" to "account_id",
            "sessions" to "account_id",
            "match_tickets" to "account_id",
            "applied_operations" to "account_id",
        )
        tables.sumOf { (table, column) ->
            db.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }
    }

    private fun assertNotNullId(id: Long?): Long = requireNotNull(
        id,
    ) { "the account was not found" }

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
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.deleteAccount(token: String, password: String) =
        client.delete("/accounts/me") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(Credentials("ignored", password)))
        }

    private suspend fun ApplicationTestBuilder.me(token: String) = client.get("/me") {
        protocolHeaders()
        bearer(token)
    }

    private suspend fun ApplicationTestBuilder.tickets(token: String): Int {
        val response = client.get("/matches/tickets") {
            protocolHeaders()
            bearer(token)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<com.tripletriad.protocol.SeedTickets>(
            response.bodyAsText(),
        ).seeds.first()
    }

    private suspend fun ApplicationTestBuilder.submit(
        token: String,
        transcript: com.tripletriad.protocol.MatchTranscript,
    ) {
        val response = client.post("/matches/submit") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(transcript))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
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
