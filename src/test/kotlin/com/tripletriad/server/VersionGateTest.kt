package com.tripletriad.server

import com.tripletriad.protocol.AppVersion
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A client too old to be trusted is turned away, and told so in a way it can act on.
 *
 * ### What this is really protecting
 *
 * Not the wire format. The server deals hands from its own card and opponent tables, so a client
 * whose tables have moved on replays to a different board and has **every** transcript rejected —
 * a failure that looks exactly like cheating and is not. These tests are the difference between
 * that and one clear "update".
 *
 * The asymmetry test is the one to keep: refusing a *newer* client would break every player who
 * updated before the server was deployed.
 */
class VersionGateTest {

    @Test
    fun aClientOnTheServersVersionIsLetThrough() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = verify(CURRENT_VERSION.toString())

        assertNotEquals(HttpStatusCode.UpgradeRequired, response.status)
    }

    /**
     * A client a major behind is refused.
     *
     * Tested against a **stated** server version rather than [CURRENT_VERSION], because today that
     * is `0.1.0` and no major is lower than zero — a test written against the live constant would
     * assert nothing now and would start asserting something different the day it is bumped. The
     * route below is the same gate the real one calls, with the version supplied instead of
     * defaulted.
     */
    @Test
    fun aClientOnAnOlderMajorIsRefusedWith426() = testApplication {
        application { gatedAt(AppVersion(2, 0, 0)) }

        val response = client.post("/gated") {
            header(VERSION_HEADER, "1.9.9")
        }

        assertEquals(HttpStatusCode.UpgradeRequired, response.status, response.bodyAsText())
        assertEquals("2.0.0", response.headers[VERSION_HEADER])
    }

    /**
     * A client a major *ahead* is let through — the asymmetry, which is the decision most at risk
     * of being simplified away into `major == major` during a tidy-up.
     */
    @Test
    fun aClientOnANewerMajorIsLetThrough() = testApplication {
        application { gatedAt(AppVersion(1, 0, 0)) }

        val response = client.post("/gated") {
            header(VERSION_HEADER, "2.0.0")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("through", response.bodyAsText())
    }

    /**
     * No header at all is refused rather than assumed current.
     *
     * The opposite choice would make the gate useless exactly when it matters: a client old enough
     * to predate the header is precisely the one whose replay cannot be trusted to agree.
     */
    @Test
    fun aRequestWithNoVersionHeaderIsRefused() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.UpgradeRequired, response.status, response.bodyAsText())
    }

    @Test
    fun anUnparseableVersionHeaderIsRefusedRatherThanCrashing() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        listOf("", "latest", "1", "1.2", "x.y.z", "-1.0.0").forEach { raw ->
            assertEquals(
                HttpStatusCode.UpgradeRequired,
                verify(raw).status,
                "'$raw' should have been refused",
            )
        }
    }

    /** The refusal names the server's own version, so a client can say what to update to. */
    @Test
    fun theRefusalCarriesTheServersVersionInHeaderAndBody() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = verify("nonsense")

        assertEquals(CURRENT_VERSION.toString(), response.headers[VERSION_HEADER])
        val body = response.bodyAsText()
        assertTrue(body.contains("upgrade_required"), body)
        assertTrue(body.contains(CURRENT_VERSION.toString()), body)
    }

    /**
     * The gate runs **before** the body is parsed.
     *
     * A major mismatch is precisely the case where this build may misread the body, so a refused
     * client must be refused whatever it sent — including something unparseable, which would
     * otherwise be a 400 and would send it looking for a bug in its own serialisation.
     */
    @Test
    fun aRefusedClientIsRefusedBeforeItsBodyIsRead() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            header(VERSION_HEADER, "nonsense")
            setBody("this is not json at all")
        }

        assertEquals(HttpStatusCode.UpgradeRequired, response.status, response.bodyAsText())
    }

    /**
     * A minimal application whose one route is the gate, at a version the test chooses.
     *
     * Deliberately not the real `module`: this isolates the rule from the catalogs, the metrics and
     * the data source, so a failure here means the gate is wrong rather than that something else
     * broke on the way to it.
     */
    private fun Application.gatedAt(serverVersion: AppVersion) {
        install(ContentNegotiation) { json() }
        routing {
            post("/gated") {
                if (!requireCompatibleClient(serverVersion)) return@post
                call.respondText("through")
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.verify(version: String) =
        client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            header(VERSION_HEADER, version)
            setBody("{}")
        }
}
