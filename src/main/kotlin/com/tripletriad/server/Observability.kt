package com.tripletriad.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * How often one caller may ask for the expensive things.
 *
 * ### What this is defending, and what it is not
 *
 * Three attacks, none of which any amount of correctness elsewhere prevents:
 *
 * - **Guessing a password.** bcrypt at cost 12 makes each attempt cost a quarter-second of *server*
 *   time, which is a throttle on the attacker and a denial of service on everybody else — a
 *   hundred parallel guesses is a hundred cores of bcrypt. The limit turns that into a refusal.
 * - **Farming matches.** A transcript is unforgeable but it is not slow to produce: a bot playing
 *   real matches at machine speed earns real rewards. Nothing in the replay can tell that apart,
 *   and nothing should — the matches *are* real. Only the cadence distinguishes them, so only a
 *   cadence limit addresses it.
 * - **Flooding the lobby**, where a table costs nothing to open and everybody else sees it.
 *
 * It is deliberately not a defence against a distributed attacker: the buckets live in this
 * process's memory and are keyed per address or per session, so a thousand addresses get a thousand
 * buckets. That is the honest limit of anything this side of a proxy, and naming it here is better
 * than implying otherwise.
 *
 * ### Why some buckets are keyed on the session and not the address
 *
 * An address is the only key available before a request is authenticated, and it is the wrong one
 * afterwards: a household or a campus shares one, and one player's grinding would refuse their
 * flatmate's match. Past sign-in there is a better key — the session — and the fingerprint of the
 * bearer token stands for it without putting the token itself in a map.
 *
 * Rotating tokens to escape a session bucket means signing in repeatedly, which is what
 * [SIGN_IN] limits. The two are meant to be read together.
 */
private fun Application.installRateLimits() {
    install(RateLimit) {
        // Guessing a password. By address, because there is no session yet by definition.
        register(RateLimitName(SIGN_IN)) {
            rateLimiter(limit = SIGN_IN_LIMIT, refillPeriod = SIGN_IN_WINDOW)
            requestKey { call -> call.callerAddress() }
        }

        // Creating accounts. A **separate** bucket from signing in — see [REGISTER].
        register(RateLimitName(REGISTER)) {
            rateLimiter(limit = REGISTER_LIMIT, refillPeriod = REGISTER_WINDOW)
            requestKey { call -> call.callerAddress() }
        }

        // Crediting a match. The tightest per-session limit, because it is the one that pays.
        register(RateLimitName(SUBMIT)) {
            rateLimiter(limit = SUBMIT_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.callerKey() }
        }

        // Opening tables and sending invitations — cheap for the sender, visible to everyone else.
        register(RateLimitName(LOBBY)) {
            rateLimiter(limit = LOBBY_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.callerKey() }
        }

        // The intent endpoints. Loosest, because a player emptying a bag of thirty items is doing
        // something ordinary and must not be mistaken for a script.
        register(RateLimitName(INTENT)) {
            rateLimiter(limit = INTENT_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.callerKey() }
        }
    }
}

/**
 * The session this request belongs to, or its address when it has none.
 *
 * A **fingerprint** of the bearer token rather than the token, for the reason `Authentication`
 * gives: a token is as good as the password for as long as it lives, and a limiter's key map is
 * still somewhere it would be sitting in memory under its own name.
 */
private fun ApplicationCall.callerKey(): String = request.headers[HttpHeaders.Authorization]
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.removePrefix("Bearer ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { "session:" + Tokens.fingerprint(it) }
    ?: callerAddress()

private fun ApplicationCall.callerAddress(): String = "ip:" + request.origin.remoteHost

/**
 * Everything that makes the server observable and predictable, installed in one place.
 *
 * None of this is a feature. All of it is what makes the difference between "it is broken" and
 * "it is broken *here*" at the moment something goes wrong at three in the morning — which is the
 * only moment any of it is read.
 */
fun Application.installObservability(meters: PrometheusMeterRegistry) {
    install(ContentNegotiation) {
        json(
            Json {
                // Off deliberately: an unknown field arriving from a client is either a version
                // mismatch or a probe, and both are worth failing on rather than ignoring.
                ignoreUnknownKeys = false
                explicitNulls = false
            },
        )
    }

    install(DefaultHeaders)

    // Reads `X-Forwarded-For`, and **the rate limiter is useless without it**: behind the reverse
    // proxy every request arrives from the same container address, so a per-IP bucket would hold
    // the entire internet and the first ten sign-ins anywhere would lock out the eleventh.
    //
    // Trusting a client-supplied header is normally a spoofing vector, and here it is not one:
    // Caddy is the only thing on this host the internet can reach (see `Caddyfile`), it overwrites
    // the header on every proxied request, and nothing else can present one. A deployment that ever
    // exposes this port directly must remove this line in the same change.
    install(XForwardedHeaders)

    installRateLimits()

    // A correlation id per request, generated if the caller did not supply one. Without it, the
    // log lines of two concurrent matches interleave into something unreadable; with it, one grep
    // recovers a single request's whole story.
    install(CallId) {
        // `header` is both halves at once — read the caller's id from this header, and echo it
        // back on the response. Adding `replyToHeader` next to it sends the header twice.
        header(CALL_ID_HEADER)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }

    install(CallLogging) {
        level = Level.INFO
        // Publishes the id into the logging context so `logback.xml` can print it on every line,
        // including lines written deep inside a handler that knows nothing about HTTP.
        callIdMdc(MDC_CALL_ID)
        // The health probes run every few seconds forever. Logging them buries everything else.
        filter { call -> !call.request.local.uri.startsWith("/health") }
    }

    install(MicrometerMetrics) {
        registry = meters
    }

    install(StatusPages) {
        // Before the catch-all, and it has to be: a body that will not parse is the *client's*
        // mistake, and the catch-all would report it as a 500 — telling the caller to retry an
        // identical request that cannot ever succeed, and putting a server error in the metrics
        // for every malformed probe.
        exception<BadRequestException> { call, cause ->
            call.application.environment.log.debug("Rejected a malformed request", cause)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "malformed_request"))
        }

        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled failure", cause)
            // Deliberately says nothing about the cause. An exception message can carry a SQL
            // fragment, a file path or a value from another player's row; the correlation id is
            // what connects this response to the log line that has the detail.
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error = "internal_error"),
            )
        }
    }
}

/** The one shape every failure takes on the wire. */
@Serializable
data class ErrorResponse(val error: String)

/** Creates the registry. Separate from installation so tests can scrape it without a server. */
fun prometheusRegistry(): PrometheusMeterRegistry =
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

const val CALL_ID_HEADER = "X-Request-Id"
private const val MDC_CALL_ID = "callId"

/**
 * The names the routes throttle themselves by, and the numbers behind them.
 *
 * Public because a route has to name one to be limited, and a typo in a name is a route that is
 * silently *not* limited — a constant makes that a compile error instead.
 *
 * The numbers are deliberately generous. Every one of them is far above what a person does and far
 * below what a script does, and the failure mode of guessing too low is a real player being refused
 * something they are entitled to. If any of these ever needs raising for an honest client, the
 * limit was wrong, not the client.
 */
const val SIGN_IN = "sign-in"
const val REGISTER = "register"
const val SUBMIT = "submit"
const val LOBBY = "lobby"
const val INTENT = "intent"

/** Ten tries per address per five minutes. A person who has forgotten their password uses three. */
private const val SIGN_IN_LIMIT = 10
private val SIGN_IN_WINDOW = 5.minutes

/**
 * Ten new accounts per address per hour, in a bucket of their own.
 *
 * ### Why this is not the sign-in bucket, having been at first
 *
 * The original reasoning was that registering in a loop and guessing in a loop abuse the same
 * unauthenticated surface, so an attacker should not get both budgets. It is a tidy argument and it
 * is wrong, because the two are not attacked by the same person at the same rate, and sharing means
 * the *honest* burst pays for the hostile one.
 *
 * The end-to-end run is what settled it: a script that created nine accounts and then tried to sign
 * in was refused on its first attempt, having spent the whole budget registering. Behind one
 * address that is not an exotic case — a household, a classroom, a LAN party, anywhere several
 * people install a card game together — and the symptom is that nobody can sign in and nothing says
 * why.
 *
 * Split, each attack is bounded by the number that fits it. An hour is the right window here
 * because registration is a thing a person does **once**: ten per hour is generous past any real
 * group and still caps a farm at a rate that makes it not worth the addresses.
 */
private const val REGISTER_LIMIT = 10
private val REGISTER_WINDOW = 1.hours

/**
 * Thirty credited matches a minute per session.
 *
 * A match takes a minute or two to play, so this is twenty times a person's ceiling — and it still
 * bounds a bot to something a human could theoretically have done, which is the most a cadence
 * limit can honestly claim. It also has to clear the **offline queue**, which drains several
 * matches at once when a player comes back online, and refusing that would punish the careful case.
 */
private const val SUBMIT_LIMIT = 30

/** Twenty tables or invitations a minute. Opening one and cancelling it is two. */
private const val LOBBY_LIMIT = 20

/** Sixty intents a minute — a player emptying a bag of thirty items in a hurry. */
private const val INTENT_LIMIT = 60
