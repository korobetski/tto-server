package com.tripletriad.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.respond

/**
 * How much body this server will read, and why there has to be a number at all.
 *
 * ### The hole this closes
 *
 * `POST /matches/verify` takes an unauthenticated transcript and is deliberately not throttled,
 * on the argument that verification is cheap — nine placements. Nine placements *are* cheap. The
 * parsing that has to happen before the tenth move can be rejected is not, and nothing bounded it:
 * Ktor installs no body limit of its own, `call.receive<MatchTranscript>()` reads the whole body
 * into memory before `TranscriptVerifier` sees a single move, and a `moves` array of a few million
 * entries is a few million allocations offered by anybody who can reach the port.
 *
 * The Dockerfile then sets `-XX:+ExitOnOutOfMemoryError`, which is the right flag and turns this
 * into a **process exit** rather than a limping JVM — and `restart: unless-stopped` turns that into
 * a crash loop. So the cost of not having this number was an unauthenticated caller being able to
 * hold the server down for as long as they kept sending.
 *
 * ### Two answers, because there are two ways to arrive
 *
 * - A declared length over [DEFAULT_MAX_BODY_BYTES] is **413**. The caller said how much they were
 *   about to send and it is more than this server reads.
 * - A body with **no** declared length — `Transfer-Encoding: chunked` — is **411**. Not 413: the
 *   server has not decided the body is too large, it has declined to find out by reading it. That
 *   is exactly what `411 Length Required` means, and it is a status any HTTP client already
 *   understands, where a bespoke refusal would not be.
 *
 *   Refusing chunked outright is a real restriction and it is taken deliberately. Every body this
 *   API accepts is a few hundred bytes of JSON with a length its sender knows before it starts
 *   writing, and no client has a reason to stream one. The alternative — reading a chunked body to
 *   find out how big it is — is the attack.
 *
 * ### Why this exists as well as `request_body max_size` in the `Caddyfile`
 *
 * The proxy is the layer that should carry this in production and it now does. It is not the only
 * way in: the server answers on the compose network, `scripts/deploy.sh` reaches it directly, and
 * `docs/operations.md` documents running it with no proxy at all. A limit that lives only at the
 * edge is a limit that is absent in every topology except the one it was written for.
 */
public val BodyLimit: ApplicationPlugin<BodyLimitConfig> =
    createApplicationPlugin("BodyLimit", ::BodyLimitConfig) {
        val maximum = pluginConfig.maxBodyBytes

        on(PluginsPhase) { call ->
            // Another plugin — the rate limiter, most likely — may already have answered. Ktor
            // routes past a handled call, and responding twice is an exception rather than a
            // second response.
            if (call.isHandled) return@on

            val declared = call.request.contentLength()
            when {
                declared != null && declared > maximum -> call.refuse(
                    HttpStatusCode.PayloadTooLarge,
                    "body_too_large",
                    "declared $declared bytes",
                    maximum,
                )

                declared == null && call.isChunked() -> call.refuse(
                    HttpStatusCode.LengthRequired,
                    "length_required",
                    "chunked",
                    maximum,
                )
            }
        }
    }

/** The one knob. A parameter rather than a constant so a test can set it to something small. */
public class BodyLimitConfig {
    public var maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES
}

/**
 * 256 KiB.
 *
 * Sized against the largest honest body rather than against a round number. The biggest thing a
 * client sends is a whole `GameSave` on `PUT /me/save`: a collection held as card ids, a bag, four
 * decks and the quest and achievement state. For scale, `catalog/cards.json` — every card in the
 * game with its name, set and four ranks — is 137 KB, and a save names those cards by integer id.
 * A player who owned every card several times over would not reach a quarter of this.
 *
 * Which is the property that matters: a number this far above the honest case is one that never
 * refuses a real request, and it is still four orders of magnitude below what it takes to trouble
 * a JVM sized by `MaxRAMPercentage`.
 */
public const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024

/**
 * Runs the check in the phase Ktor's own rate limiter uses, and for the same reason.
 *
 * `ApplicationCallPipeline.Plugins` is before routing, so the refusal happens without a route
 * having been chosen — which is the point, since the cheapest place to stop reading a body is
 * before anything has decided who would have read it.
 */
private object PluginsPhase : Hook<suspend (ApplicationCall) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) { handler(call) }
    }
}

/**
 * Whether the caller is streaming the body rather than declaring its length.
 *
 * A `Transfer-Encoding` may name several codings in order — `gzip, chunked` — with chunked last,
 * so this is a substring test rather than an equality one.
 */
private fun ApplicationCall.isChunked(): Boolean =
    request.header(HttpHeaders.TransferEncoding)?.contains("chunked", ignoreCase = true) == true

/**
 * Answers, and says the limit.
 *
 * The number is on the wire deliberately. A client refused without one has no way to tell a body it
 * should split from a body it should never have built, and the limit is not a secret — it is a
 * property of the API, like the version this server speaks.
 */
private suspend fun ApplicationCall.refuse(
    status: HttpStatusCode,
    error: String,
    detail: String,
    maximum: Long,
) {
    application.environment.log.info(
        "Refused a body on {} ({}); this server reads at most {} bytes",
        request.uri,
        detail,
        maximum,
    )
    respond(status, ErrorResponse(error = error))
}
