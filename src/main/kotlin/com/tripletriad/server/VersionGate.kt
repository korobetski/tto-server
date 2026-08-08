package com.tripletriad.server

import com.tripletriad.protocol.AppVersion
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable

/**
 * Refuses a client too old to be talked to, before its request is read.
 *
 * ### The failure this prevents
 *
 * The server deals hands from **its own** copy of the card and opponent tables — it has to, since
 * asking the claimant for the rules would be letting the claimant choose them. But two copies
 * drift. The day one side's tables are regenerated and the other's are not, every transcript from
 * the updated client replays to a different board on the server and is rejected — and the rejection
 * is **indistinguishable from cheating**. A player who did nothing wrong is told their match did
 * not happen, and the logs agree.
 *
 * A version check turns that into one loud, correct message: update. See
 * `docs/migration/09-PHASE-5-NETWORK.md` § One version, shared, and [AppVersion] for why a major
 * bump is the right trigger.
 *
 * ### Why 426 and why it is checked first
 *
 * `426 Upgrade Required` says precisely this and nothing else — not 400, which would blame the
 * request's syntax, and not 200 with a rejection, which is the answer to a *claim* and would send
 * a stale client away believing it had cheated.
 *
 * It runs before the body is parsed on purpose. A major mismatch is the case where this build may
 * not read the body correctly, so parsing it first would be doing the one thing the version number
 * just said not to do.
 *
 * ### What is deliberately allowed
 *
 * A client with a **newer** major passes. During any rollout the client ships before or after the
 * server, and refusing the people who updated fastest would be the wrong way round: the newer side
 * is the one equipped to be careful. [AppVersion.acceptsPeer] holds that asymmetry.
 */
suspend fun RoutingContext.requireCompatibleClient(
    serverVersion: AppVersion = CURRENT_VERSION,
): Boolean {
    val raw = call.request.headers[VERSION_HEADER]

    // An absent header is refused rather than assumed compatible. The alternative — treating it as
    // "probably current" — makes the gate useless exactly when it matters, since a client old
    // enough to predate the header is precisely the one that cannot be trusted to replay the same.
    val client = raw?.let(AppVersion::parse)

    if (client != null && serverVersion.acceptsPeer(client)) return true

    call.response.header(VERSION_HEADER, serverVersion.toString())
    call.respondUpgradeRequired(raw, serverVersion)
    return false
}

private suspend fun ApplicationCall.respondUpgradeRequired(raw: String?, server: AppVersion) {
    application.environment.log.info(
        "Refused a client on version '{}'; this server is {}",
        raw ?: "<absent>",
        server,
    )
    respond(
        HttpStatusCode.UpgradeRequired,
        UpgradeRequired(
            error = "upgrade_required",
            server = server.toString(),
            client = raw,
        ),
    )
}

/**
 * The body of a 426.
 *
 * The version also travels in the header, where a client can read it without parsing anything. This
 * is for the human reading a failed request in a console, and it echoes what the client *claimed*
 * so the two numbers appear side by side.
 */
@Serializable
private data class UpgradeRequired(
    /**
     * Carries no default on purpose. The server encodes with `encodeDefaults = false`, so a
     * property equal to its default is **omitted from the wire** — which silently dropped the one
     * field that tells a reader what this body is. A discriminator that can vanish is not one.
     */
    val error: String,
    val server: String,
    val client: String?,
)
