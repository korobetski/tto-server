package com.tripletriad.server

import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.ServerInfo
import com.tripletriad.protocol.Unlocks
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import javax.sql.DataSource

/**
 * The one endpoint that answers everybody.
 *
 * ### Why it is not behind the version gate
 *
 * This is the point of the whole file, so it is stated first. Every other route calls
 * [requireCompatibleClient] and refuses a client on an incompatible major with a 426 — correctly,
 * because a body it might misread is exactly what a major mismatch means. But that leaves a stale
 * client holding a status code and nothing to tell the player: "the server refused you" is
 * indistinguishable from "the server is down" to somebody looking at a spinner, and the actual
 * remedy — update — is the one thing the refusal cannot say.
 *
 * So this route is deliberately, permanently ungated. **An endpoint that refuses incompatible
 * clients cannot be the endpoint that tells them they are incompatible.** Anyone adding a gate here
 * would be removing the only way a refused build can explain itself.
 *
 * That is safe because it reads nothing from the request. There is no body to misparse, no
 * authentication to get wrong and no state to change; the version header is still *sent*, so a
 * client that only reads headers learns the same thing.
 *
 * ### Why readiness is in here as well as in `/health/ready`
 *
 * They answer different questions for different readers. The health routes are an orchestrator's:
 * kill this process, route traffic here. This is a player's: can I sign in now, and if not, is it
 * worth trying again in a minute. Answering both in one round trip is the point — the alternative
 * is two requests on a phone's connection to draw one status dot — and [ServerInfo.ready] says only
 * "usable", which is all a client can act on anyway. The check names stay in `/health/ready`, where
 * whoever is on call reads them.
 */
fun Route.serverRoutes(
    identity: ServerIdentity,
    dataSource: DataSource,
    // Defaulted, so a test that only cares about readiness need not name them. A deployment always
    // passes its own — see `ServerConfig.unlocksFrom`.
    unlocks: Unlocks = Unlocks(),
) {
    get("/server") {
        // Sent even though nothing here is refused, so a client that reads only headers — or that
        // fails to decode a body from a future version of this server — still learns the number
        // that decides whether it may talk to us.
        call.response.header(VERSION_HEADER, CURRENT_VERSION.toString())

        call.respond(
            ServerInfo(
                name = identity.name,
                version = CURRENT_VERSION,
                // The same number today: `AppVersion.acceptsPeer` compares majors, so this server
                // accepts exactly the clients its own version does. Sent as its own field so a
                // deployment can one day widen that without every client inferring the policy.
                minimumClient = CURRENT_VERSION,
                ready = isDatabaseReachable(dataSource),
                release = identity.release,
                // What this deployment gates player-to-player play and trade at. Sent so a client
                // can say "unlocks at level 8" when that is what this server means, rather than
                // drawing a number compiled into it months ago. The server refuses on its own copy
                // regardless — see [Unlocks], and `PvpRoutes` for where it does.
                unlocks = unlocks,
            ),
        )
    }
}
