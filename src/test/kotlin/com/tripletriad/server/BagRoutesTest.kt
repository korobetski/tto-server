package com.tripletriad.server

import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.CardItem
import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.protocol.BagItemRequest
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.ItemEffect
import com.tripletriad.protocol.ItemUsed
import com.tripletriad.protocol.PlayerState
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `POST /me/bag/use` — the first intent endpoint, and the first dice roll taken off the client.
 *
 * ### What is actually being asserted
 *
 * Not "the right cards came out" — that would be asserting the generator. The claims are that the
 * cards came from **that pack's pool**, that the pack was consumed exactly once, and that a client
 * cannot influence any of it. A test that pinned specific ids would fail the day the seed changed
 * and would prove nothing about who rolled.
 *
 * ### Why idempotence is tested here and not left to the store
 *
 * Because the endpoint is where it can be got wrong in a way that costs a player something. The
 * table and the `applyOnce` guard could both be perfect while the route computed the response
 * outside them; [theSameOperationOpensOnePackHoweverOftenItArrives] is what pins the whole path.
 */
class BagRoutesTest {

    /**
     * A pack yields cards from its own pool, and leaves the bag.
     *
     * The pool assertion is the one that matters: it is what would fail if the endpoint opened a
     * *different* pack than the one asked for, which is the mistake a bag index would have invited.
     */
    @Test
    fun openingAPackYieldsCardsFromItsOwnPool() = server {
        val session = registerHolding(BoosterItem(BoosterType.BRONZE))

        val used = use(session.token, BoosterItem(BoosterType.BRONZE), "op-1")

        val opened = assertIs<ItemEffect.PackOpened>(used.effect)
        assertEquals(BoosterType.BRONZE.size, opened.cardIds.size, "wrong number of cards")
        assertTrue(
            opened.cardIds.all { it in BoosterType.BRONZE.pool },
            "a card came from outside the pack's pool: ${opened.cardIds}",
        )
        assertTrue(
            used.player.save.bag.none { it is BoosterItem },
            "the pack was opened and stayed in the bag",
        )
    }

    /**
     * The same operation opens one pack, however many times it arrives — answer included.
     *
     * The second assertion is the sharp one. Returning *an* answer to a retry is not enough: it has
     * to be the **same** answer, or the reveal animation shows a different pack the second time and
     * the player watches cards they did not get.
     */
    @Test
    fun theSameOperationOpensOnePackHoweverOftenItArrives() = server {
        val session = registerHolding(BoosterItem(BoosterType.BRONZE, stack = 2))

        val first = use(session.token, BoosterItem(BoosterType.BRONZE), "op-retry")
        val second = use(session.token, BoosterItem(BoosterType.BRONZE), "op-retry")

        assertEquals(first.effect, second.effect, "a retry opened a different pack")
        assertEquals(
            1,
            me(session.token).save.bag.filterIsInstance<BoosterItem>().sumOf { it.stack },
            "a retry consumed a second pack",
        )
    }

    /**
     * A *different* operation opens a second pack.
     *
     * The other half of the claim above, and the one that stops the fix from being "one pack ever".
     * The guard is per intent, not per endpoint.
     */
    @Test
    fun aDifferentOperationOpensASecondPack() = server {
        val session = registerHolding(BoosterItem(BoosterType.BRONZE, stack = 2))

        use(session.token, BoosterItem(BoosterType.BRONZE), "op-a")
        use(session.token, BoosterItem(BoosterType.BRONZE), "op-b")

        assertTrue(
            me(session.token).save.bag.filterIsInstance<BoosterItem>().isEmpty(),
            "the second operation did not open the second pack",
        )
    }

    /**
     * An item the bag does not hold yields nothing, and the profile does not move.
     *
     * A `200` rather than a refusal, deliberately: the profile in the same response is the answer
     * to why. See the route's KDoc.
     */
    @Test
    fun anItemTheBagDoesNotHoldDoesNothing() = server {
        val session = registerHolding(BoosterItem(BoosterType.BRONZE))
        val before = me(session.token).save

        val used = use(session.token, BoosterItem(BoosterType.GOLD), "op-fake")

        assertIs<ItemEffect.NotUseable>(used.effect)
        assertEquals(before.bag, used.player.save.bag, "a pack nobody owned was opened anyway")
        assertEquals(before.cards, used.player.save.cards)
    }

    /**
     * `stack` on the arriving item says *which*, never how many.
     *
     * The check that does not exist in the route, asserted because its absence is a decision:
     * `Inventory.remove` takes its count separately and `stacksWith` normalises the stack away, so
     * there is nothing here for a validator to validate. If either of those ever changes, this is
     * the test that says so.
     */
    @Test
    fun theStackOnTheRequestCannotConsumeMoreThanOne() = server {
        val session = registerHolding(BoosterItem(BoosterType.BRONZE, stack = 3))

        use(session.token, BoosterItem(BoosterType.BRONZE, stack = 99), "op-greedy")

        assertEquals(
            2,
            me(session.token).save.bag.filterIsInstance<BoosterItem>().sumOf { it.stack },
            "the request's stack was taken as a quantity",
        )
    }

    /** A card item still enters the collection, which is the branch the reveal animation plays. */
    @Test
    fun usingACardItemPutsItInTheCollection() = server {
        val card = BoosterType.BRONZE.pool.first()
        val session = registerHolding(CardItem(card))

        val used = use(session.token, CardItem(card), "op-card")

        val drawn = assertIs<ItemEffect.CardDrawn>(used.effect)
        assertEquals(card, drawn.cardId)
        assertTrue(used.player.save.ownsCard(card), "the card was used and never arrived")
    }

    /**
     * Two packs opened separately do not come out identical.
     *
     * A weak assertion on purpose — it is a smoke test against the one implementation mistake that
     * every other test here would pass: a generator seeded per request, which would make every pack
     * in the game the same pack. It can in principle fail by chance; with six cards drawn from a
     * six-card pool that is rare enough to be worth the coverage.
     */
    @Test
    fun twoPacksAreNotTheSamePack() = server {
        val session = registerHolding(BoosterItem(BoosterType.GOLD, stack = 2))

        val first = use(session.token, BoosterItem(BoosterType.GOLD), "op-1")
        val second = use(session.token, BoosterItem(BoosterType.GOLD), "op-2")

        assertNotEquals(
            first.effect,
            second.effect,
            "every pack came out identical — seeded twice?",
        )
    }

    // ---- Harness ----------------------------------------------------------

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    /**
     * An account whose bag holds [items], planted **straight into the database**.
     *
     * It used to go through `PUT /me/save`, and that stopped working the day the bag joined the
     * server-owned list — which is the whole point of that list, and a good sign rather than a bad
     * one. A fixture is not a client, so it writes where a client cannot: the alternative is buying
     * each item through the shop, which would make every test here depend on the shop's prices.
     */
    private suspend fun ApplicationTestBuilder.registerHolding(vararg items: Item): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(Postgres.freshAccount("bag"), PASSWORD)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val session = json.decodeFromString<Session>(response.bodyAsText())

        plant(session) { it.copy(bag = items.toList()) }
        return session
    }

    /** Rewrites the stored profile behind the API, for the fields the API no longer believes. */
    private fun plant(session: Session, change: (GameSave) -> GameSave) {
        val accounts = AccountStore(Postgres.dataSource)
        val id = assertNotNull(
            accounts.accountIdForUsername(session.player.save.username),
            "the account just registered could not be found",
        )
        assertTrue(accounts.replaceSave(id, change(assertNotNull(accounts.saveFor(id)))))
    }

    private suspend fun ApplicationTestBuilder.use(
        token: String,
        item: Item,
        operationId: String,
    ): ItemUsed {
        val response = client.post("/me/bag/use") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(BagItemRequest(item, operationId)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.me(token: String): PlayerState {
        val response = client.get("/me") {
            protocolHeaders()
            bearer(token)
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
