package com.tripletriad.server

import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the console reads and the one thing it writes.
 *
 * `AdminConsoleAccessTest` covers the door — the password, the code, the cookie. This is what is
 * behind it, and the two are separate files because they fail for entirely different reasons: that
 * one fails when a credential is accepted too easily, this one when an answer is shaped wrongly.
 *
 * ### Assertions are on the JSON, not on a deserialised object
 *
 * Deliberately, and it is the point of most of these tests. `tto-web/console/lib/api.ts` reads the
 * wire with `=== null` and `=== undefined`, so *present and null* and *absent* are two different
 * answers to it — and a test that decoded into an [AdminPlayerDetail] would map both onto the same
 * Kotlin null and pass while the console rendered a live match as finished. The body is parsed as a
 * [JsonObject] so that the distinction the console depends on is the distinction under test.
 *
 * ### One session, made through the store
 *
 * [signedIn] enrols an administrator and opens a session without going through `POST
 * /admin/sessions`, because the flow that does is tested at length next door and repeating it here
 * would make every test in this file depend on the second factor's clock.
 */
class AdminConsoleTest {

    /**
     * Every route answers 401 to a caller with no cookie, and the console reads that as "sign in".
     *
     * The one property worth testing across all seven at once: `failureFor` maps a 401 to
     * `unauthenticated` and every page treats it as a navigation to the sign-in screen, so a route
     * that answered 404 or 500 to an expired session would leave the operator on a broken page
     * rather than at a form. It is also the only thing standing between the internet and the credit
     * route, which is why the list is exhaustive rather than a sample.
     */
    @Test
    fun everyRouteRefusesACallerWithNoSession() = console {
        val routes = listOf(
            "/admin/stats/overview",
            "/admin/players?q=someone",
            "/admin/players/1",
            "/admin/matches/pve/whatever",
            "/admin/auctions",
            "/admin/audit",
        )
        for (path in routes) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.Unauthorized, response.status, path)
        }

        val credit = client.post("/admin/players/1/credit") {
            contentType(ContentType.Application.Json)
            setBody("""{"operationId":"op","amount":10,"reason":"why"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, credit.status, credit.bodyAsText())
    }

    /**
     * The dashboard arrives as three named groups and an instant.
     *
     * The grouping is the contract — `web-platform.md` names three ambiguities a flat list of
     * thirteen numbers hides — so the shape is asserted rather than the figures, which move every
     * time another test registers an account. `mgp` is the money **supply**: purses plus escrow,
     * which is why it is read from `mgp_total` and not from `mgp_in_purses`.
     */
    @Test
    fun theOverviewArrivesGroupedAndDated() = console {
        val session = signedIn()
        val body = client.get("/admin/stats/overview") { cookie(session) }.expectOk()

        val accounts = body["accounts"]!!.jsonObject
        assertTrue(accounts["registered"]!!.jsonPrimitive.int >= 0)
        assertNotNull(accounts["activeThisWeek"])
        assertNotNull(accounts["newToday"])

        val matches = body["matches"]!!.jsonObject
        for (key in listOf("credited", "pve", "pvp", "today")) assertNotNull(matches[key], key)

        val economy = body["economy"]!!.jsonObject
        for (key in listOf("mgp", "lotsLive", "mgpEscrowed")) assertNotNull(economy[key], key)

        // ISO 8601, so a figure on screen can be dated. A `Timestamp.toString` would be a local
        // time with a space in it, which is neither parseable by `Date` nor comparable to anything.
        assertContains(body["asOf"]!!.jsonPrimitive.content, "T")
    }

    /**
     * A search finds an account three ways, and puts the one that matched exactly first.
     *
     * The ordering is the part that earns a test. Without it the row somebody typed the name of
     * arrives wherever the planner put it, and a support operator reads a list to find the thing
     * they already had — so `ada` must come back ahead of `adalater` for the query `ada`,
     * and the case they typed must not matter.
     */
    @Test
    fun searchMatchesAnIdAnExactNameAndAPrefix() = console {
        val session = signedIn()
        val name = Postgres.freshAccount("searchable")
        val id = register(name)
        // A second account whose name *starts* with the first one's, so the exact match has
        // something to be ordered ahead of.
        register("${name}later")

        val byId = client.get("/admin/players?q=$id") { cookie(session) }.expectOkArray()
        assertEquals(1, byId.size)
        assertEquals(name, byId[0].jsonObject["username"]!!.jsonPrimitive.content)

        val byName = client.get("/admin/players?q=${name.uppercase()}") { cookie(session) }
            .expectOkArray()
        assertEquals(name, byName[0].jsonObject["username"]!!.jsonPrimitive.content)
        assertEquals(2, byName.size, "the prefix match should be there too, behind the exact one")

        val byEmail = client.get("/admin/players?q=${address(name)}") { cookie(session) }
            .expectOkArray()
        assertEquals(name, byEmail[0].jsonObject["username"]!!.jsonPrimitive.content)
    }

    /**
     * An empty `q` is an empty list, not the whole table.
     *
     * The console opens on this page before anything has been typed. A server that read a blank
     * query as "everything" would answer the first request of every session with every account it
     * has, which gets slower exactly as the game succeeds.
     */
    @Test
    fun anEmptySearchReturnsNothing() = console {
        val session = signedIn()
        register(Postgres.freshAccount("nobody"))
        assertEquals(0, client.get("/admin/players?q=") { cookie(session) }.expectOkArray().size)
    }

    /**
     * The player page is **flat**, carries the save's figures, and sends its nulls.
     *
     * Three claims in one request because they are three claims about one body. Flat, because the
     * console's type is `PlayerDetail extends PlayerSummary` and a `summary` key would leave every
     * field on it undefined. The figures out of the save document, because that is where a purse
     * and a collection live. And `seenAt` **present and null** for an account that has never been
     * seen — the console tests `=== null`, so an omitted key would read as "unknown" and render as
     * `undefined` in a column an operator uses to decide whether somebody is still playing.
     */
    @Test
    fun thePlayerPageIsFlatAndSendsItsNulls() = console {
        val session = signedIn()
        val name = Postgres.freshAccount("flat")
        val id = register(name)

        val body = client.get("/admin/players/$id") { cookie(session) }.expectOk()
        assertEquals(name, body["username"]!!.jsonPrimitive.content)
        assertNull(body["summary"], "the summary must be flattened into the body, not nested")
        assertEquals(GameSave.new(name, createdAt = 0L).mgp, body["mgp"]!!.jsonPrimitive.int)
        assertEquals(1, body["level"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, body["seenAt"], "a null must arrive as null and not as an absence")
        assertEquals(address(name), body["email"]!!.jsonPrimitive.content)
        assertFalse(body["bot"]!!.jsonPrimitive.content.toBoolean())

        for (key in listOf("record", "recentMatches", "lots", "audit")) {
            assertNotNull(body[key], key)
        }
    }

    /** An id that is not an account is a 404 with the code the console recognises. */
    @Test
    fun anUnknownPlayerIsNotFound() = console {
        val session = signedIn()
        val response = client.get("/admin/players/999999999") { cookie(session) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.failure())
    }

    /**
     * A credit moves the purse once, records it once, and says so — and a retry does neither again.
     *
     * This is the test the audit table exists for. The second request is byte-for-byte the first
     * one, which is what the console sends when an answer is lost: `applyOnce` must return the
     * **first** answer rather than moving the balance a second time, `applied` must come back
     * `false` so the operator is told this attempt did nothing, and `admin_audit` must still hold
     * exactly one row — because a second row for one effect is a record of something that did not
     * happen.
     */
    @Test
    fun aCreditIsAppliedOnceAndAuditedOnce() = console {
        val session = signedIn()
        val id = register(Postgres.freshAccount("credit"))
        val before = purse(session, id)
        val operation = "op-${Postgres.freshAccount("credit")}"

        val first = credit(session, id, operation, amount = 500, reason = "support case 17")
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val receipt = first.body()
        assertEquals(before, receipt["mgpBefore"]!!.jsonPrimitive.int)
        assertEquals(before + 500, receipt["mgpAfter"]!!.jsonPrimitive.int)
        assertTrue(receipt["applied"]!!.jsonPrimitive.content.toBoolean())

        val replay = credit(session, id, operation, amount = 500, reason = "support case 17")
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals(before + 500, replay.body()["mgpAfter"]!!.jsonPrimitive.int)
        assertFalse(
            replay.body()["applied"]!!.jsonPrimitive.content.toBoolean(),
            "a replayed answer must say it did not apply anything",
        )
        assertEquals(before + 500, purse(session, id), "the balance moved twice")

        val trail = client.get("/admin/audit?subject=$id") { cookie(session) }.expectOk()
        val entries = trail["entries"]!!.jsonArray
        assertEquals(1, entries.size, "one effect, one row: ${trail["entries"]}")
        val entry = entries[0].jsonObject
        assertEquals(CREDITED, entry["action"]!!.jsonPrimitive.content)
        assertEquals("support case 17", entry["reason"]!!.jsonPrimitive.content)
        assertEquals(id, entry["subject"]!!.jsonObject["accountId"]!!.jsonPrimitive.long)
        assertContains(entry["before"]!!.jsonPrimitive.content, """"mgp"""")
        assertContains(entry["after"]!!.jsonPrimitive.content, (before + 500).toString())

        // Absent, not null. `scripts/audit.ts` hides its "older" button on `=== undefined`, so a
        // `"nextCursor": null` would leave a button that fetches the same page for ever.
        assertFalse("nextCursor" in trail, "a short page must carry no cursor at all")
    }

    /**
     * Taking more than a player has floors the purse at zero and reports where it landed.
     *
     * Not a refusal, which is the decision worth pinning. The save floors MGP itself, so `after` is
     * something other than `before + amount` whenever a correction overshoots — and the console's
     * own type says the receipt is shown back to the operator rather than assumed for exactly this
     * reason. Refusing instead would leave an operator unable to zero an account they had just
     * mistakenly enriched.
     */
    @Test
    fun aSubtractionPastZeroIsFlooredRatherThanRefused() = console {
        val session = signedIn()
        val id = register(Postgres.freshAccount("floor"))
        val before = purse(session, id)

        val overshoot = -(before + 10_000)
        val response = credit(session, id, "op-floor-$id", amount = overshoot, reason = "x")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(before, response.body()["mgpBefore"]!!.jsonPrimitive.int)
        assertEquals(0, response.body()["mgpAfter"]!!.jsonPrimitive.int)
        assertEquals(0, purse(session, id))
    }

    /**
     * A credit with no reason, or of nothing, is refused before anything is written.
     *
     * The reason is what an audit row six months old is read for: without one, a correction cannot
     * be told from a mistake by the person who has to decide which it was. A zero amount is refused
     * for the mirror reason — it would write a row about nothing, which is noise in the one table
     * that has to stay worth reading.
     */
    @Test
    fun aCreditWithoutAReasonOrAnAmountIsRefused() = console {
        val session = signedIn()
        val id = register(Postgres.freshAccount("bad"))

        for (body in listOf(
            """{"operationId":"op-a","amount":10,"reason":"  "}""",
            """{"operationId":"op-b","amount":0,"reason":"a reason"}""",
            """{"operationId":"","amount":10,"reason":"a reason"}""",
        )) {
            val response = client.post("/admin/players/$id/credit") {
                cookie(session)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("malformed_request", response.failure(), body)
        }
        assertEquals(
            0,
            client.get("/admin/audit?subject=$id") { cookie(session) }
                .expectOk()["entries"]!!.jsonArray.size,
            "a refused credit must leave no trail",
        )
    }

    /**
     * The match inspector lists the placements the engine made, in order, with what they flipped.
     *
     * A move list rather than a board, because step 3 brings the game's own renderer and a second
     * one written now is one thrown away. What matters here is that the list comes from a
     * **replay** — `MatchPosition.replaying`, the one walk both match tables share — so the cells
     * and captures are the referee's own account and not a transcription of what the row happens
     * to store.
     *
     * The NPC side has no account, which the console renders as plain text rather than as a link to
     * a player page that does not exist.
     */
    @Test
    fun theMatchInspectorReplaysAPveSession() = console {
        val session = signedIn()
        val name = Postgres.freshAccount("replay")
        val accountId = register(name)
        val matchId = openPveMatch(accountId)

        val body = client.get("/admin/matches/pve/$matchId") { cookie(session) }.expectOk()
        assertEquals("PVE", body["kind"]!!.jsonPrimitive.content)
        assertEquals(PveMatchStatus.PLAYING.name, body["status"]!!.jsonPrimitive.content)
        assertEquals(SEED, body["seed"]!!.jsonPrimitive.int)
        assertEquals(name, body["blue"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(OPPONENT, body["red"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["red"]!!.jsonObject["accountId"], "an NPC has no account")
        assertEquals(JsonNull, body["finishedAt"], "a live match has not finished")
        assertEquals(JsonNull, body["transcriptHash"], "a refereed session has no transcript")

        val moves = body["moves"]!!.jsonArray
        assertEquals(2, moves.size)
        assertEquals(1, moves[0].jsonObject["index"]!!.jsonPrimitive.int)
        assertEquals(CardColor.BLUE.name, moves[0].jsonObject["side"]!!.jsonPrimitive.content)
        assertEquals(0, moves[0].jsonObject["cell"]!!.jsonPrimitive.int)
        assertEquals(CardColor.RED.name, moves[1].jsonObject["side"]!!.jsonPrimitive.content)
        assertEquals(1, moves[1].jsonObject["cell"]!!.jsonPrimitive.int)
        // Named, from the catalog the engine dealt from — the inspector is read by a person.
        assertTrue(moves[0].jsonObject["cardName"]!!.jsonPrimitive.content.isNotEmpty())
    }

    /**
     * A credited match shows its sides, score and digest and an empty move list, and is in the
     * player's history.
     *
     * The empty list is the claim under test: `matches` keeps a hash of the transcript and not the
     * transcript, so there is nothing to replay, and an inspector that invented placements would be
     * worse than one that shows none. The same row is then read back through the player page,
     * because the history's `UNION ALL` reads this table by column name too — `format`, which
     * `V3__formats.sql` renamed from `collection`, and which is exactly the kind of drift a query
     * that no test executes carries into production.
     */
    @Test
    fun aCreditedMatchShowsItsDigestAndNoMoves() = console {
        val session = signedIn()
        val name = Postgres.freshAccount("credited")
        val accountId = register(name)
        val matchId = creditMatch(accountId)

        val body = client.get("/admin/matches/credited/$matchId") { cookie(session) }.expectOk()
        assertEquals(KIND_CREDITED, body["kind"]!!.jsonPrimitive.content)
        assertEquals("WIN", body["status"]!!.jsonPrimitive.content)
        assertEquals(FORMAT, body["format"]!!.jsonPrimitive.content)
        assertEquals(name, body["blue"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(6, body["blue"]!!.jsonObject["score"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, body["red"]!!.jsonObject["accountId"])
        assertEquals(DIGEST, body["transcriptHash"]!!.jsonPrimitive.content)
        assertEquals(0, body["moves"]!!.jsonArray.size)
        assertEquals(0, body["rules"]!!.jsonArray.size)

        val history = client.get("/admin/players/$accountId") { cookie(session) }
            .expectOk()["recentMatches"]!!.jsonArray
        val row = assertNotNull(
            history.firstOrNull { it.jsonObject["id"]!!.jsonPrimitive.content == "$matchId" },
            "the credited match must be in the player's history",
        ).jsonObject
        assertEquals(KIND_CREDITED, row["kind"]!!.jsonPrimitive.content)
        assertEquals(FORMAT, row["format"]!!.jsonPrimitive.content)
    }

    /** A kind the server does not have, and an id it does not hold, are both plain 404s. */
    @Test
    fun anUnknownMatchIsNotFound() = console {
        val session = signedIn()
        for (path in listOf("/admin/matches/nonsense/1", "/admin/matches/pvp/no-such-match")) {
            val response = client.get(path) { cookie(session) }
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertEquals("NOT_FOUND", response.failure(), path)
        }
    }

    /**
     * The audit trail pages backwards by keyset, and the cursor is the id it ended on.
     *
     * Keyset rather than `OFFSET` because the table only grows at the head: a second page fetched
     * after a write would repeat a row page 1 already showed, and a trail that shows one action
     * twice is one nobody can testify from. The page size is forced down to one so that two rows
     * make two pages.
     */
    @Test
    fun theAuditTrailPagesByKeyset() = console {
        val session = signedIn()
        val id = register(Postgres.freshAccount("paged"))
        credit(session, id, "op-page-1-$id", amount = 10, reason = "first")
        credit(session, id, "op-page-2-$id", amount = 20, reason = "second")

        val admins = AdminStore(Postgres.dataSource)
        val first = admins.audit(subject = id, before = null, limit = 1)
        assertEquals(1, first.entries.size)
        assertEquals("second", first.entries[0].reason, "newest first")
        val cursor = assertNotNull(first.nextCursor, "a full page must offer a cursor")

        val second = admins.audit(subject = id, before = cursor.toLong(), limit = 1)
        assertEquals("first", second.entries[0].reason)
        assertTrue(
            second.entries[0].id < cursor.toLong(),
            "the second page must start strictly below the cursor",
        )
    }

    /**
     * A lot appears on the house page and on the pages of both accounts standing behind it.
     *
     * The seller *and* the bidder, which is wider than "their listings" and deliberate: somebody
     * writing in about the auction house does not distinguish between a card they are selling and
     * money they have committed, and a screen showing only one of the two would hide the half they
     * were asking about.
     */
    @Test
    fun aLotIsVisibleToTheHouseAndToBothPartiesToIt() = console {
        val session = signedIn()
        val seller = register(Postgres.freshAccount("seller"))
        val bidder = register(Postgres.freshAccount("bidder"))
        val lot = openLot(seller, bidder)

        val house = client.get("/admin/auctions?status=OPEN") { cookie(session) }.expectOkArray()
        val listed = assertNotNull(
            house.firstOrNull { it.jsonObject["id"]!!.jsonPrimitive.content == lot },
            "the open lot must be on the house page",
        ).jsonObject
        assertEquals(CARD_ID, listed["cardId"]!!.jsonPrimitive.int)
        assertTrue(listed["cardName"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(seller, listed["seller"]!!.jsonObject["accountId"]!!.jsonPrimitive.long)
        assertEquals(
            bidder,
            listed["topBidder"]!!.jsonObject["accountId"]!!.jsonPrimitive.long,
        )

        for (party in listOf(seller, bidder)) {
            val lots = client.get("/admin/players/$party") { cookie(session) }
                .expectOk()["lots"]!!.jsonArray
            assertTrue(
                lots.any { it.jsonObject["id"]!!.jsonPrimitive.content == lot },
                "account $party stands behind the lot and must see it",
            )
        }
    }

    /* -- the harness ------------------------------------------------------------------------- */

    private fun console(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
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
    private fun signedIn(): String {
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
    private fun register(name: String): Long {
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
    private fun openPveMatch(accountId: Long): String {
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
    private fun openLot(seller: Long, bidder: Long): String {
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
    private fun creditMatch(accountId: Long): Long = Postgres.dataSource.connection.use { db ->
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

    private suspend fun ApplicationTestBuilder.credit(
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
    private suspend fun ApplicationTestBuilder.purse(session: String, accountId: Long): Int =
        client.get("/admin/players/$accountId") { cookie(session) }
            .expectOk()["mgp"]!!.jsonPrimitive.int

    private fun HttpRequestBuilder.cookie(token: String) =
        header(HttpHeaders.Cookie, "$ADMIN_COOKIE=$token")

    private suspend fun HttpResponse.expectOk(): JsonObject {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return body()
    }

    private suspend fun HttpResponse.expectOkArray(): JsonArray {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return json.decodeFromString<JsonArray>(bodyAsText())
    }

    private suspend fun HttpResponse.body(): JsonObject =
        json.decodeFromString<JsonObject>(bodyAsText())

    private suspend fun HttpResponse.failure(): String =
        json.decodeFromString<ErrorResponse>(bodyAsText()).error

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val OPPONENT = "an-opponent"
        const val HAND = 5
        const val SEED = 4242
        const val CARD_ID = 1
        const val FORMAT = "ff14"
        const val DIGEST = "0123456789abcdef"
    }
}
