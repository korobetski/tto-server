package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.NpcCatalog
import com.tripletriad.protocol.MatchTranscript
import com.tripletriad.protocol.MatchVerdict
import com.tripletriad.protocol.TranscriptVerifier
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * The first exchange between a client and this server, and the one the design is built around.
 *
 * A client submits what it did; the server **replays it with the real engine** and answers with the
 * score it computed itself. Notice how little the endpoint does: parse, call `:core`, respond. That
 * is the whole point — every rule lives in the shared module, so there is no server-side copy of
 * them to drift.
 *
 * ### What this endpoint is not, yet
 *
 * - **Authenticated.** Anyone can post anything in anyone's name. The transcript is unforgeable as
 *   a *game* and worthless as a *claim* until it is signed.
 * - **Persistent.** An accepted verdict is returned and forgotten. Nothing is credited to a
 *   profile, because there are no profiles.
 * - **Rate-limited.** Verification is cheap — nine placements — but it is unbounded work offered to
 *   unauthenticated callers, which is a thing to fix before this is reachable from anywhere.
 *
 * All three are deliberate: they need accounts, and accounts are the next brick rather than this
 * one.
 */
fun Route.matchRoutes(cards: CardCatalog, npcs: NpcCatalog) {
    route("/matches") {
        /**
         * Verifies a transcript.
         *
         * ### Why a rejected transcript is 200 and not 400
         *
         * Because it is an *answer*, not a malformed request. "This match did not happen" is the
         * service working exactly as intended, and it carries a reason the client is expected to
         * read. A 4xx would say the client sent something the server could not process, which is
         * the different situation of a body that will not parse — and that one is a 400, raised by
         * content negotiation before this handler runs.
         */
        post("/verify") {
            val transcript = call.receive<MatchTranscript>()
            val verdict = TranscriptVerifier.verify(transcript, cards, npcs)

            if (verdict is MatchVerdict.Rejected) {
                // Logged, because a rise in rejections is either an attack or — far more likely —
                // a catalog that has drifted out of step with the clients. Both need to be visible.
                call.application.environment.log.info(
                    "Rejected a transcript against '{}': {} — {}",
                    transcript.opponentIconId,
                    verdict.reason,
                    verdict.detail,
                )
            }

            call.respond(HttpStatusCode.OK, verdict)
        }
    }
}
