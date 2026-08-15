package com.tripletriad.server

import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType
import com.tripletriad.protocol.BuyRequest
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.PlayerState
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two things happening to one account at the same time.
 *
 * ### The defect this was written for
 *
 * Every write here is a read-modify-write: read the whole profile, apply something, write the whole
 * profile back. Until this test existed, the read took **no row lock**, so two requests arriving
 * together both read the same starting profile and both wrote their own result — and whichever
 * committed first was erased. Both callers got a `200`. Nothing logged. The player saw an item they
 * had just bought simply not be there.
 *
 * It reached a real player as three different-looking bugs, which is why it took so long to find:
 *
 * - an item used, and a *different* item gone with it;
 * - a pack bought, and the bag empty afterwards;
 * - a daily quest completable twice, because a profile read before the completion was written back
 *   over it.
 *
 * One cause. `AccountStore.lockSave` is the fix, and this is the test that fails without it.
 *
 * ### Why it is written as a burst rather than as two requests
 *
 * Two requests can be made to overlap only by guessing at timings, and a test that passes because
 * the guess was wrong is worse than none. A burst of [BURST] concurrent writes needs no timing at
 * all: without a lock, *some* of them collide, and the arithmetic at the end is short by however
 * many did. The assertion is exact — every purchase is in the bag — so one lost write fails it.
 * Measured against the unlocked code, two of eight arrived.
 */
class ConcurrentWriteTest {

    /**
     * [BURST] purchases at once, and the bag holds all of them.
     *
     * Each buy is its own `operationId`, so idempotency is not what is being tested — these are
     * genuinely distinct purchases that must all land.
     */
    @Test
    fun everySimultaneousPurchaseSurvives() = server {
        val session = rich()
        val potion = onSaleAsPotion()

        coroutineScope {
            (1..BURST)
                .map { attempt -> async { buy(session.token, potion, "op-burst-$attempt") } }
                .awaitAll()
        }

        val held = me(session.token).save.bag
            .filterIsInstance<PotionItem>()
            .filter { it.potionType == potion.potionType }
            .sumOf { it.stack }
        assertEquals(BURST, held, "concurrent purchases were lost: $held of $BURST arrived")
    }

    /**
     * A purchase and a profile write at once leave **both** their marks.
     *
     * The pairing that actually bit a player: the offline queue drains and the deck editor saves on
     * the same launch a purchase is made. `PUT /me/save` may not touch the bag — it is server-owned
     * — but until it read and wrote under one lock it could still *carry away* a bag it had read
     * before the purchase landed.
     */
    @Test
    fun aPurchaseAndAProfileWriteDoNotEraseEachOther() = server {
        val session = rich()
        val potion = onSaleAsPotion()
        val before = me(session.token).save

        coroutineScope {
            val bought = async { buy(session.token, potion, "op-with-save") }
            val saved = async { putSave(session.token, before.copy(avatarId = MOVED_AVATAR)) }
            bought.await()
            saved.await()
        }

        val after = me(session.token).save
        assertTrue(
            after.bag.filterIsInstance<PotionItem>().any { it.potionType == potion.potionType },
            "the profile write erased the purchase: ${after.bag}",
        )
        assertEquals(MOVED_AVATAR, after.avatarId, "the purchase erased the profile write")
    }

    // ---- Harness -----------------------------------------------------------

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    /** An account that can afford the whole burst. */
    private suspend fun ApplicationTestBuilder.rich(): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(Postgres.freshAccount("race"), PASSWORD)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val session = json.decodeFromString<Session>(response.bodyAsText())

        val accounts = AccountStore(Postgres.dataSource)
        val id = assertNotNull(accounts.accountIdForUsername(session.player.save.username))
        assertTrue(accounts.replaceSave(id, assertNotNull(accounts.saveFor(id)).copy(mgp = PURSE)))
        return session
    }

    /**
     * A potion the shop sells, read off the catalogue rather than named here.
     *
     * A potion because it **stacks**: the assertion can then be a single number, and a stack of
     * eight is a much sharper claim than eight separate rows would be.
     */
    private fun onSaleAsPotion(): PotionItem = PotionItem(PotionType.MGP)

    private suspend fun ApplicationTestBuilder.buy(token: String, item: Item, op: String) {
        val response = client.post("/me/shop/buy") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(BuyRequest(item, FORMAT, op)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.putSave(token: String, save: GameSave) {
        val response = client.put("/me/save") {
            protocolHeaders()
            bearer(token)
            setBody(json.encodeToString(save))
        }
        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
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
        const val FORMAT = "free-play"
        const val BURST = 8
        const val PURSE = 1_000_000
        const val MOVED_AVATAR = "ffxiv_twi03006"
    }
}
