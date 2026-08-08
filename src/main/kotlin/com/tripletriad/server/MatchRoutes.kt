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
 * The exchange the whole design is built around: a client submits what it did, and the server
 * **replays it with the real engine** rather than believing it.
 *
 * ### Two endpoints, and the difference between them matters
 *
 * `/verify` answers a question — *is this a legal game?* — and forgets it. `/submit` answers a
 * claim — *this is my match, pay me for it* — and is therefore authenticated, checked against the
 * profile the **server** holds, and written down. The first is useful to a client that wants to
 * catch its own bugs before they look like cheating; the second is the one progression comes from.
 *
 * Notice how little either does: parse, call `:core`, respond. Every rule lives in the shared
 * module, so there is no server-side copy of them to drift.
 *
 * ### What is still missing
 *
 * - **Rate limiting.** Verification is cheap — nine placements — but it is unbounded work offered
 *   to unauthenticated callers, which is a thing to fix before this is reachable from anywhere.
 * - **Signatures.** A session proves *who* is submitting; nothing proves the transcript came from
 *   a genuine client rather than a script that computed a winning one. The replay makes that a
 *   fair fight — a forgery has to be a real, winnable match — but not an impossible one.
 */
fun Route.matchRoutes(cards: CardCatalog, npcs: NpcCatalog, store: AccountStore) {
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
            // Before `receive`, not after: see requireCompatibleClient for why a version mismatch
            // must be answered without reading a body this build may misread.
            if (!requireCompatibleClient()) return@post

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

        /**
         * Submits a match for credit — the endpoint that makes the server master of PvE.
         *
         * ### Why the answer is 200 even for a rejection
         *
         * Same reason as `/verify`, and it is worth being explicit because the stakes are higher
         * here: the server *did* process the request and reached a considered answer. Sending a
         * 403 for "your replay disagrees with mine" would put a rejection in the same bucket as a
         * missing token, and a client's error handling would have to take them apart again.
         *
         * A duplicate is likewise 200. An offline queue that drains twice after a dropped
         * acknowledgement has done nothing wrong, and telling it otherwise would make careful
         * behaviour look like an error.
         */
        post("/submit") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticate(store) ?: return@post

            val transcript = call.receive<MatchTranscript>()
            val receipt = MatchCrediting.credit(
                transcript = transcript,
                accountId = accountId,
                store = store,
                cards = cards,
                npcs = npcs,
                now = System.currentTimeMillis(),
            )

            when {
                receipt.verdict is MatchVerdict.Rejected -> {
                    // At warn, unlike /verify's info: this one came from an authenticated account,
                    // so it is either a real client disagreeing with the server about the rules —
                    // which is a bug worth waking up for — or somebody trying it on.
                    val rejection = receipt.verdict as MatchVerdict.Rejected
                    call.application.environment.log.warn(
                        "Account {} submitted a transcript against '{}' that was rejected: {} — {}",
                        accountId,
                        transcript.opponentIconId,
                        rejection.reason,
                        rejection.detail,
                    )
                }

                receipt.duplicate -> call.application.environment.log.info(
                    "Account {} resubmitted a match already credited",
                    accountId,
                )

                else -> call.application.environment.log.info(
                    "Credited account {} with a {} against '{}'",
                    accountId,
                    receipt.reward?.result,
                    transcript.opponentIconId,
                )
            }

            call.respond(HttpStatusCode.OK, receipt)
        }
    }
}
