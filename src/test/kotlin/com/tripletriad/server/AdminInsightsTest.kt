package com.tripletriad.server

import com.tripletriad.model.NpcLevel
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.assertTrue

/**
 * The console's second generation of routes: the roster, the balance tables, and the inventory
 * write.
 *
 * Its own file because `AdminConsoleTest` had grown past what one class should hold, and these are
 * the routes that arrived together — the roster and the two statistics pages read, the inventory
 * edit writes. The rule `AdminConsoleTest` states holds here unchanged: assertions are on the JSON
 * the console reads, never on a decoded object, so *present and null* stays distinguishable from
 * *absent*. The harness is `AdminConsoleHarness.kt`.
 */
class AdminInsightsTest {

    /**
     * The roster pages through every account, and the filter decides whether bots are among them.
     *
     * Sorted by creation, newest first, so the account this test just registered is on the first
     * page whatever else the shared database holds. `total` counts the filter's accounts and not
     * the page — the console's pager is built from it.
     */
    @Test
    fun theRosterListsAccountsAndFiltersBots() = console {
        val session = signedIn()
        val name = Postgres.freshAccount("roster")
        val id = register(name)
        val bot = register(Postgres.freshAccount("rosterbot"))
        withBot(bot) {
            val page = client
                .get("/admin/players/list?sort=created&bots=exclude") { cookie(session) }
                .expectOk()
            val players = page["players"]!!.jsonArray.map { it.jsonObject }
            val row = assertNotNull(players.firstOrNull { it["id"]!!.jsonPrimitive.long == id })
            assertEquals(name, row["username"]!!.jsonPrimitive.content)
            assertEquals(1, row["level"]!!.jsonPrimitive.int)
            assertTrue(players.none { it["id"]!!.jsonPrimitive.long == bot }, "bots were excluded")
            assertTrue(page["total"]!!.jsonPrimitive.long >= players.size)
            assertEquals(0, page["offset"]!!.jsonPrimitive.int)

            val only = client.get("/admin/players/list?bots=only") { cookie(session) }.expectOk()
            val bots = only["players"]!!.jsonArray.map { it.jsonObject }
            assertTrue(bots.all { it["bot"]!!.jsonPrimitive.content.toBoolean() })
            assertTrue(bots.any { it["id"]!!.jsonPrimitive.long == bot })

            // Past the end is an empty page that still knows how many there are.
            val beyond = client.get("/admin/players/list?offset=100000") { cookie(session) }
                .expectOk()
            assertEquals(0, beyond["players"]!!.jsonArray.size)
            assertTrue(beyond["total"]!!.jsonPrimitive.long > 0)

            // A search narrows the roster: anywhere in the name and ignoring case, or the id.
            val middle = name.drop(2).uppercase()
            for (query in listOf(middle, id.toString())) {
                val searched = client
                    .get("/admin/players/list?q=${query.encodeURLParameter()}&bots=exclude") {
                        cookie(session)
                    }.expectOk()
                val found = searched["players"]!!.jsonArray.map { it.jsonObject }
                assertTrue(found.any { it["id"]!!.jsonPrimitive.long == id }, query)
                assertEquals(found.size.toLong(), searched["total"]!!.jsonPrimitive.long, query)
            }
            // `_` is a character here, not LIKE's wildcard.
            val literal = client.get("/admin/players/list?q=${"_".repeat(40)}") { cookie(session) }
                .expectOk()
            assertEquals(0, literal["total"]!!.jsonPrimitive.long)
        }
    }

    /**
     * Every catalog NPC is listed, played or not, and an icon the catalog does not know is kept.
     *
     * The unplayed NPC is the finding a balance table exists to surface — too hard, too expensive,
     * unreachable — so it must be a row of zeros, not a missing row. The fixture's opponent is not
     * a catalog icon, which is the second case: history against an NPC since removed is appended
     * with its catalog fields null rather than dropped.
     */
    @Test
    fun npcStatisticsListTheCatalogAndKeepUnknownIcons() = console {
        val session = signedIn()
        creditMatch(register(Postgres.freshAccount("npcstats")))

        val body = client.get("/admin/stats/npcs?days=30&bots=include") { cookie(session) }
            .expectOk()
        assertEquals(30, body["days"]!!.jsonPrimitive.int)
        assertEquals("INCLUDE", body["bots"]!!.jsonPrimitive.content)
        val npcs = body["npcs"]!!.jsonArray.map { it.jsonObject }
        assertTrue(npcs.size >= Catalogs.npcs.all.size, "every catalog NPC must be listed")

        val first = Catalogs.npcs.all[0]
        val known = npcs.first { it["iconId"]!!.jsonPrimitive.content == first.iconId }
        assertEquals(first.nameKey, known["nameKey"]!!.jsonPrimitive.content)
        assertNotNull(known["band"]!!.jsonPrimitive.content)

        val gone = npcs.first { it["iconId"]!!.jsonPrimitive.content == OPPONENT }
        assertEquals(JsonNull, gone["nameKey"], "an icon the catalog lost has no catalog fields")
        assertTrue(gone["played"]!!.jsonPrimitive.int >= 1)
        assertTrue(gone["wins"]!!.jsonPrimitive.int >= 1)
        assertEquals(2.0, gone["averageMargin"]!!.jsonPrimitive.content.toDouble(), 4.0)

        // All time is null, and a figure nobody played has no margin rather than a zero one.
        val all = client.get("/admin/stats/npcs") { cookie(session) }.expectOk()
        assertEquals(JsonNull, all["days"])
        assertEquals("EXCLUDE", all["bots"]!!.jsonPrimitive.content)
    }

    /** The bot page lists each bot with its band and figures, and says how much PvP it sampled. */
    @Test
    fun botStatisticsListTheRoster() = console {
        val session = signedIn()
        val bot = register(Postgres.freshAccount("statbot"))
        withBot(bot) {
            val body = client.get("/admin/stats/bots?days=7") { cookie(session) }.expectOk()
            assertEquals(7, body["days"]!!.jsonPrimitive.int)
            val row = body["bots"]!!.jsonArray.map { it.jsonObject }
                .first { it["accountId"]!!.jsonPrimitive.long == bot }
            assertEquals(NpcLevel.AVERAGE.name, row["band"]!!.jsonPrimitive.content)
            assertEquals(1, row["level"]!!.jsonPrimitive.int)
            for (key in listOf("record", "pve", "pvp")) {
                assertNotNull(row[key]!!.jsonObject["wins"], key)
            }
            val inPurses = body["mgpInPurses"]!!.jsonPrimitive.long
            assertTrue(inPurses >= body["mgpHeld"]!!.jsonPrimitive.long)
            val totals = listOf("pvpVersusHumans", "pvpBetweenBots", "pvpSampled", "pvpSampleLimit")
            for (key in totals) {
                assertNotNull(body[key], key)
            }
        }
    }

    /**
     * An inventory edit applies once, audits once, floors removals, and a retry changes nothing.
     *
     * The credit's test, for the second write — the two share every guarantee, and a guarantee
     * that is not tested on each path that claims it holds on one of them.
     */
    @Test
    fun anInventoryEditIsAppliedOnceAndAuditedOnce() = console {
        val session = signedIn()
        val id = register(Postgres.freshAccount("inventory"))
        val operation = "op-${Postgres.freshAccount("inventory")}"
        val changes = """[{"kind":"CARD","cardId":$CATALOGUED_CARD,"delta":2},""" +
            """{"kind":"BOOSTER","key":"GOLD","delta":3}]"""

        val first = inventory(session, id, operation, changes).expectOk()
        assertTrue(first["applied"]!!.jsonPrimitive.content.toBoolean())
        val outcomes = first["changes"]!!.jsonArray.map { it.jsonObject }
        val card = outcomes[0]
        assertEquals(card["before"]!!.jsonPrimitive.int + 2, card["after"]!!.jsonPrimitive.int)
        assertEquals(0, outcomes[1]["before"]!!.jsonPrimitive.int)
        assertEquals(3, outcomes[1]["after"]!!.jsonPrimitive.int)
        assertNotNull(first["decksShort"]!!.jsonArray)

        val replay = inventory(session, id, operation, changes).expectOk()
        assertFalse(replay["applied"]!!.jsonPrimitive.content.toBoolean())

        val detail = client.get("/admin/players/$id") { cookie(session) }.expectOk()
        val owned = detail["collection"]!!.jsonArray.map { it.jsonObject }
            .first { it["cardId"]!!.jsonPrimitive.int == CATALOGUED_CARD }
        assertEquals(card["after"]!!.jsonPrimitive.int, owned["copies"]!!.jsonPrimitive.int)
        val booster = detail["bag"]!!.jsonArray.map { it.jsonObject }
            .first { it["kind"]!!.jsonPrimitive.content == "BOOSTER" }
        assertEquals("GOLD", booster["key"]!!.jsonPrimitive.content)
        assertEquals(
            3,
            booster["stack"]!!.jsonPrimitive.int,
            "the retry must not add a second time",
        )
        assertNotNull(detail["decks"]!!.jsonArray)

        // Taking more than is held takes what is held.
        val floored = inventory(
            session,
            id,
            "op-floor-inventory-$id",
            """[{"kind":"BOOSTER","key":"GOLD","delta":-50}]""",
        ).expectOk()
        assertEquals(0, floored["changes"]!!.jsonArray[0].jsonObject["after"]!!.jsonPrimitive.int)

        val entries = client.get("/admin/audit?subject=$id") { cookie(session) }
            .expectOk()["entries"]!!.jsonArray
        assertEquals(2, entries.size, "two effects, two rows")
        val entry = entries[1].jsonObject
        assertEquals(INVENTORY_EDITED, entry["action"]!!.jsonPrimitive.content)
        assertContains(entry["after"]!!.jsonPrimitive.content, "BOOSTER:GOLD")
    }

    /**
     * An edit naming nothing, nothing to move, a pouch, or one entry twice is refused unwritten.
     */
    @Test
    fun aMalformedInventoryEditIsRefused() = console {
        val session = signedIn()
        val id = register(Postgres.freshAccount("badinventory"))
        for (changes in listOf(
            "[]",
            """[{"kind":"CARD","cardId":$CATALOGUED_CARD,"delta":0}]""",
            """[{"kind":"CARD","cardId":-4,"delta":1}]""",
            """[{"kind":"BOOSTER","key":"NOT_A_BOOSTER","delta":1}]""",
            """[{"kind":"POUCH","key":"lot","delta":1}]""",
            """[{"kind":"CARD","cardId":$CATALOGUED_CARD,"delta":1000}]""",
            """[{"kind":"CARD","cardId":$CATALOGUED_CARD,"delta":1},""" +
                """{"kind":"CARD","cardId":$CATALOGUED_CARD,"delta":1}]""",
        )) {
            val response = inventory(session, id, "op-bad-${changes.hashCode()}-$id", changes)
            assertEquals(HttpStatusCode.BadRequest, response.status, changes)
            assertEquals("malformed_request", response.failure(), changes)
        }
        assertEquals(
            0,
            client.get("/admin/audit?subject=$id") { cookie(session) }
                .expectOk()["entries"]!!.jsonArray.size,
            "a refused edit must leave no trail",
        )
    }

    /** The pickers' catalog: every card the server knows, and the enum names edits are read as. */
    @Test
    fun theCatalogOffersWhatAnEditAccepts() = console {
        val session = signedIn()
        val body = client.get("/admin/catalog") { cookie(session) }.expectOk()
        assertEquals(Catalogs.cards.all.size, body["cards"]!!.jsonArray.size)
        assertTrue(body["boosters"]!!.jsonArray.any { it.jsonPrimitive.content == "GOLD" })
        assertTrue(body["potions"]!!.jsonArray.any { it.jsonPrimitive.content == "LUCK" })
        assertTrue(body["origins"]!!.jsonArray.any { it.jsonPrimitive.content == "PLAIN" })
    }
}
