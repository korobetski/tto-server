package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.NpcLevel
import com.tripletriad.protocol.PveMatchStatus
import com.tripletriad.protocol.PveMove
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * The harness the two console test files share: an application on the test database, an
 * administrator's session built through the store, the accounts and rows the routes read, and the
 * JSON the assertions are written against.
 *
 * Top-level rather than a base class, because what is shared is a set of tools and not a kind of
 * test — and `AdminConsoleTest` explains why the assertions are on raw JSON, which is the reason
 * `expectOk` answers a [JsonObject] rather than a decoded wire type.
 */

internal fun console(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application { module(Postgres.dataSource, prometheusRegistry()) }
    block()
}

/**
 * An enrolled administrator with a live session, built through the store.
 *
 * Past the routes on purpose: `AdminConsoleAccessTest` owns the flow that opens a session over
 * HTTP, and every test here would otherwise depend on a TOTP code being generated inside the
 * same thirty-second step it is verified in.
 */
internal fun signedIn(): String {
    val admins = AdminStore(Postgres.dataSource)
    val name = Postgres.freshAccount("console")
    val adminId = assertNotNull(admins.create(name, PasswordHasher.hash(TEST_PASSWORD)))
    val secret = Totp.issueSecret()
    assertTrue(admins.beginEnrolment(adminId, secret))
    val token = Tokens.issue()
    assertTrue(
        admins.enrol(
            adminId = adminId,
            step = Totp.stepAt(System.currentTimeMillis()),
            tokenHash = Tokens.fingerprint(token),
            expiresAt = System.currentTimeMillis() + ADMIN_SESSION_MILLIS,
        ),
    )
    return token
}

/** An account with a starter profile, which is what every player page is read against. */
internal fun register(name: String): Long {
    val accounts = AccountStore(Postgres.dataSource)
    return assertNotNull(
        accounts.register(
            name,
            "hash-$name",
            GameSave.new(name, createdAt = 0L),
            address(name),
        ),
    )
}

/** Two placements on a live board, which is the smallest thing the inspector can replay. */
internal fun openPveMatch(accountId: Long): String {
    val hand = Catalogs.cards.all.take(HAND * 2)
    val id = "inspect-${Postgres.freshAccount("match")}"
    val row = PveMatchRow(
        id = id,
        accountId = accountId,
        formatId = "any",
        opponentIconId = OPPONENT,
        // Plain rules: the replay under test is the walk, not the rules engine, and Sudden
        // Death or an elemental board would make the two placements below depend on the seed.
        rules = GameRules(),
        seed = SEED,
        blueHand = hand.take(HAND).map { it.id },
        redHand = hand.drop(HAND).map { it.id },
        first = CardColor.BLUE,
        moves = listOf(
            PveMove(handIndex = 0, position = 0),
            PveMove(handIndex = 0, position = 1),
        ),
        status = PveMatchStatus.PLAYING,
    )
    assertNotNull(PveStore(Postgres.dataSource).open(row), "the fixture row has to be storable")
    return id
}

/**
 * An open lot with a standing bid, written directly.
 *
 * Through SQL rather than through `AuctionStore`, because what is under test is a *read* and
 * the shortest path to the row it reads is the row. Going through the house would mean giving
 * the seller the card, the bidder the money and both of them the unlock level, none of which
 * this screen is about.
 */
internal fun openLot(seller: Long, bidder: Long): String {
    val id = "lot-${Postgres.freshAccount("auction")}"
    Postgres.dataSource.connection.use { db ->
        db.prepareStatement(
            """
            INSERT INTO auction_lots
                (id, seller_account, card_id, start_price, reserve_price, listing_fee,
                 status, ends_at, top_bid, top_bidder, bid_count)
            VALUES (?, ?, ?, 10, 20, 1, 'OPEN', now() + interval '1 hour', 15, ?, 1)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setLong(2, seller)
            statement.setInt(3, CARD_ID)
            statement.setLong(4, bidder)
            assertEquals(1, statement.executeUpdate())
        }
        db.commit()
    }
    return id
}

/** A submitted match, as `matches` records one: a score and a digest, and no transcript. */
internal fun creditMatch(accountId: Long): Long = Postgres.dataSource.connection.use { db ->
    val id = db.prepareStatement(
        """
        INSERT INTO matches
            (account_id, opponent_icon_id, format, seed, blue, red, result, mgp,
             transcript_hash)
        VALUES (?, ?, ?, ?, 6, 4, 'WIN', 25, ?)
        RETURNING id
        """.trimIndent(),
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.setString(2, OPPONENT)
        statement.setString(3, FORMAT)
        statement.setInt(4, SEED)
        statement.setString(5, DIGEST)
        statement.executeQuery().use { rows ->
            assertTrue(rows.next())
            rows.getLong(1)
        }
    }
    db.commit()
    id
}

/**
 * [accountId] enrolled as a bot for the length of [block], and retired after it.
 *
 * Retired because `BotDirectorTest` counts the table, and a bot left behind by this file would
 * fail it for a reason that has nothing to do with the director. Due far in the future, so no
 * director that happens to be running ever acts for it.
 */
internal suspend fun withBot(accountId: Long, block: suspend () -> Unit) {
    val bots = BotStore(Postgres.dataSource)
    assertTrue(bots.enrol(accountId, NpcLevel.AVERAGE, System.currentTimeMillis() + DAY_MILLIS))
    try {
        block()
    } finally {
        Postgres.dataSource.connection.use { db ->
            db.prepareStatement("DELETE FROM bots WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
            db.commit()
        }
    }
}

internal suspend fun ApplicationTestBuilder.inventory(
    session: String,
    accountId: Long,
    operationId: String,
    changes: String,
): HttpResponse = client.post("/admin/players/$accountId/inventory") {
    cookie(session)
    contentType(ContentType.Application.Json)
    setBody("""{"operationId":"$operationId","reason":"restore","changes":$changes}""")
}

internal suspend fun ApplicationTestBuilder.credit(
    session: String,
    accountId: Long,
    operationId: String,
    amount: Int,
    reason: String,
): HttpResponse = client.post("/admin/players/$accountId/credit") {
    cookie(session)
    contentType(ContentType.Application.Json)
    setBody("""{"operationId":"$operationId","amount":$amount,"reason":"$reason"}""")
}

/** The purse as the console would read it, which is the only figure a credit has to move. */
internal suspend fun ApplicationTestBuilder.purse(session: String, accountId: Long): Int =
    client.get("/admin/players/$accountId") { cookie(session) }
        .expectOk()["mgp"]!!.jsonPrimitive.int

internal fun HttpRequestBuilder.cookie(token: String) =
    header(HttpHeaders.Cookie, "$ADMIN_COOKIE=$token")

internal suspend fun HttpResponse.expectOk(): JsonObject {
    assertEquals(HttpStatusCode.OK, status, bodyAsText())
    return body()
}

internal suspend fun HttpResponse.expectOkArray(): JsonArray {
    assertEquals(HttpStatusCode.OK, status, bodyAsText())
    return json.decodeFromString<JsonArray>(bodyAsText())
}

internal suspend fun HttpResponse.body(): JsonObject =
    json.decodeFromString<JsonObject>(bodyAsText())

internal suspend fun HttpResponse.failure(): String =
    json.decodeFromString<ErrorResponse>(bodyAsText()).error

private val json = Json { ignoreUnknownKeys = true }

internal const val OPPONENT = "an-opponent"
internal const val HAND = 5
internal const val SEED = 4242
internal const val CARD_ID = 1
internal const val FORMAT = "ff14"
internal const val DIGEST = "0123456789abcdef"
internal const val DAY_MILLIS = 86_400_000L

/** The inventory edit refuses a card the catalog does not know, as [CARD_ID] is. */
internal val CATALOGUED_CARD = Catalogs.cards.all.first().id
