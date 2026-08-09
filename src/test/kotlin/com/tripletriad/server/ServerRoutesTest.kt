package com.tripletriad.server

import com.tripletriad.protocol.AppVersion
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.ClientPlatform
import com.tripletriad.protocol.ClientRelease
import com.tripletriad.protocol.ServerInfo
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The endpoint a client asks before it is allowed to ask anything else.
 *
 * These run against Ktor's test host with an unreachable database on purpose: `/server` has to work
 * when the rest of the server does not, because "reachable but not usable" is one of the states it
 * exists to report.
 */
class ServerRoutesTest {

    /**
     * The test this whole route exists for.
     *
     * Every other endpoint refuses a client the version gate rejects, before reading its body. If
     * this one did the same, a stale build could learn *that* it was refused and never *why* — and
     * "update" is the one remedy a 426 cannot express. A gate added here would pass every other
     * test in the suite and silently remove the only way a refused client can explain itself.
     *
     * The refused client here is one whose header this server cannot read, rather than one on an
     * older major: both projects are on major 0, so there is no version below this one to send —
     * and an unreadable header is refused by exactly the same branch.
     */
    @Test
    fun aClientTheGateWouldRefuseCanStillReadIt() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.get("/server") {
            header(VERSION_HEADER, "from before there were versions")
        }

        assertEquals(HttpStatusCode.OK, response.status, "the version gate reached /server")
        assertEquals(CURRENT_VERSION, info(response).version)
    }

    /** And so can one that sends no version header at all, which every other route refuses. */
    @Test
    fun aClientWithNoVersionHeaderCanReadItToo() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        assertEquals(HttpStatusCode.OK, client.get("/server").status)
    }

    /** The contrast that makes the point: the same request to any other route is a 426. */
    @Test
    fun everyOtherRouteStillRefusesThatSameClient() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        // `/me` and not a POST: the gate runs before authentication, so this is a 426 rather than
        // a 401, and there is no body for content negotiation to reject first.
        val response = client.get("/me")

        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
    }

    /** The number is in the header as well, for a client that cannot decode the body. */
    @Test
    fun theVersionTravelsInTheHeaderAsWell() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.get("/server")

        assertEquals(CURRENT_VERSION.toString(), response.headers[VERSION_HEADER])
    }

    /**
     * Reachable and unusable is its own state.
     *
     * "Come back in a minute" and "you cannot reach this server" call for different things from a
     * player, so collapsing them into one failure would be throwing away the distinction the round
     * trip just established.
     */
    @Test
    fun aServerWhoseDatabaseIsDownSaysSoRatherThanFailing() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.get("/server")

        assertEquals(HttpStatusCode.OK, response.status, "it answered, so it is reachable")
        assertFalse(info(response).ready, "but it cannot serve anything")
    }

    @Test
    fun aDeploymentAnnouncesItsNameAndItsPublishedBuild() = testApplication {
        application {
            module(
                UnreachableDataSource,
                prometheusRegistry(),
                ServerIdentity(
                    name = "eu-1",
                    release = ClientRelease(
                        version = AppVersion(1, 2, 3),
                        downloads = mapOf(
                            ClientPlatform.DESKTOP to "https://example.invalid/t.msi",
                        ),
                        notes = "the card tables changed",
                    ),
                ),
            )
        }

        val info = info(client.get("/server"))

        assertEquals("eu-1", info.name)
        assertEquals(AppVersion(1, 2, 3), info.release?.version)
        assertEquals(
            "https://example.invalid/t.msi",
            info.release?.downloads?.get(ClientPlatform.DESKTOP),
        )
    }

    @Test
    fun aDeploymentThatPublishesNothingSaysNothing() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        assertNull(info(client.get("/server")).release)
    }

    // ---- The identity, read from the environment --------------------------

    @Test
    fun aBlankNameFallsBackRatherThanShippingAnEmptyLabel() {
        assertTrue(ServerIdentity.from { "" }.name.isNotBlank())
    }

    @Test
    fun theDownloadsAreReadPerPlatform() {
        val identity = ServerIdentity.from(
            mapOf(
                "TTO_CLIENT_VERSION" to "2.0.0",
                "TTO_CLIENT_DOWNLOAD_ANDROID" to "https://example.invalid/store",
                "TTO_CLIENT_DOWNLOAD_DESKTOP" to "https://example.invalid/tto.msi",
            )::get,
        )

        val release = assertNotNull(identity.release)
        assertEquals(AppVersion(2, 0, 0), release.version)
        assertEquals("https://example.invalid/store", release.downloads[ClientPlatform.ANDROID])
        assertNull(release.downloads[ClientPlatform.IOS], "an unset platform is not offered a link")
    }

    /**
     * A typo in a version string must not take the server down.
     *
     * The judgement goes the other way from the database's, deliberately: a wrong database is a
     * server that cannot work, a wrong version string costs an update banner. Refusing to boot over
     * the second would take a working deployment offline to protect a label.
     */
    @Test
    fun aMalformedPublishedVersionCostsTheBannerAndNotTheBoot() {
        val identity = ServerIdentity.from(mapOf("TTO_CLIENT_VERSION" to "two point oh")::get)

        assertNull(identity.release)
    }

    // ---- Fixtures ---------------------------------------------------------

    /**
     * Decoded by hand, as the rest of this suite does: the test client has no content negotiation
     * installed, and installing one would be testing Ktor's plumbing rather than this route.
     */
    private suspend fun info(response: HttpResponse): ServerInfo =
        json.decodeFromString(response.bodyAsText())

    private companion object {
        /** One format for the suite: building a `Json` per call is slow, and 1.11 now says so. */
        val json = Json { ignoreUnknownKeys = true }
    }
}
