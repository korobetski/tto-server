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
import io.ktor.util.AttributeKey
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
 * ### Why some buckets are keyed on the account and not the address
 *
 * An address is the only key available before a request is authenticated, and it is the wrong one
 * afterwards: a household or a campus shares one, and one player's grinding would refuse their
 * flatmate's match. Past sign-in there is a better key, and it is the **account**.
 *
 * ### It used to be the session, and that was a budget an attacker could multiply
 *
 * The key was the fingerprint of the bearer token, with the argument that rotating tokens to escape
 * a bucket means signing in repeatedly, which [SIGN_IN] limits. That answers rotating *now*. It
 * does not answer having rotated *already*: a session lasts thirty days, so ten sign-ins per five
 * minutes is not a ceiling on how many live tokens an account holds, it is a **fill rate** for
 * them. An hour of signing in banks a hundred and twenty tokens, each with its own
 * thirty-submissions-a-minute allowance, and an attacker then uses them together.
 *
 * The account is the thing an anti-farming limit is actually about, and it cannot be multiplied.
 * `AccountStore.openSession` caps how many sessions one account may hold as well — belt to this
 * brace, and the thing that bounds the table.
 *
 * ### What it costs, and why that is acceptable
 *
 * Resolving the account means the same indexed lookup `authenticate` is about to do. It is not done
 * twice: the answer is left on the call in [ResolvedAccount] and `authenticate` reads it there. A
 * token that resolves to nothing falls back to the address, which is right — an unauthenticated
 * caller has no account to be limited by.
 */
private fun Application.installRateLimits(accounts: AccountStore) {
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

        // Crediting a match, and opening a refereed one. The tightest per-account limit, because
        // these are the ones that pay.
        register(RateLimitName(SUBMIT)) {
            rateLimiter(limit = SUBMIT_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.callerKey(accounts) }
        }

        // Opening tables and sending invitations — cheap for the sender, visible to everyone else.
        register(RateLimitName(LOBBY)) {
            rateLimiter(limit = LOBBY_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.callerKey(accounts) }
        }

        // The intent endpoints. Loosest, because a player emptying a bag of thirty items is doing
        // something ordinary and must not be mistaken for a script.
        register(RateLimitName(INTENT)) {
            rateLimiter(limit = INTENT_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.callerKey(accounts) }
        }

        // Asking for a code, and typing one back. By address, because two of the four endpoints
        // have no session by definition — a player resetting a forgotten password cannot have one.
        register(RateLimitName(CODES)) {
            rateLimiter(limit = CODES_LIMIT, refillPeriod = CODES_WINDOW)
            requestKey { call -> call.callerAddress() }
        }

        // Placing a card, conceding, and collecting. See [PLAY_LIMIT] for the number.
        register(RateLimitName(PLAY)) {
            rateLimiter(limit = PLAY_LIMIT, refillPeriod = 1.minutes)
            requestKey { call -> call.callerKey(accounts) }
        }
    }
}

/**
 * Where the resolved account is left for `authenticate` to find.
 *
 * Set only by [callerKey], and only after a token has been looked up successfully — so its presence
 * means "this request carried a bearer token that named this account", which is exactly what
 * `authenticate` is about to establish for itself. Reading it there is not a shortcut past a check;
 * it is the same check, not run twice.
 */
internal val ResolvedAccount: AttributeKey<Long> = AttributeKey("tto.resolvedAccount")

/**
 * The account this request belongs to, or its address when it has none.
 *
 * The token is **fingerprinted** before it is used for anything, for the reason `Authentication`
 * gives: a token is as good as the password for as long as it lives, and a limiter's key map is
 * somewhere it would otherwise be sitting in memory under its own name. The fingerprint does not
 * become the key either — see the note on [installRateLimits] about banked tokens — it is only how
 * the account is found.
 */
private fun ApplicationCall.callerKey(accounts: AccountStore): String {
    val fingerprint = bearerFingerprint() ?: return callerAddress()
    val accountId = accounts.accountForToken(fingerprint) ?: return callerAddress()
    attributes.put(ResolvedAccount, accountId)
    return "account:$accountId"
}

private fun ApplicationCall.bearerFingerprint(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(Tokens::fingerprint)

private fun ApplicationCall.callerAddress(): String = "ip:" + request.origin.remoteHost

/**
 * Everything that makes the server observable and predictable, installed in one place.
 *
 * None of this is a feature. All of it is what makes the difference between "it is broken" and
 * "it is broken *here*" at the moment something goes wrong at three in the morning — which is the
 * only moment any of it is read.
 */
fun Application.installObservability(meters: PrometheusMeterRegistry, accounts: AccountStore) {
    // First, and before anything reads a byte. An unbounded body is the one failure here that
    // nothing downstream can recover from — see `BodyLimit`, which is also why this is a plugin of
    // ours rather than a setting: Ktor ships no limit at all.
    install(BodyLimit)

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
    // ### Why the *last* value and not the plugin's default
    //
    // The default is `useFirstProxy()`, which takes the entry furthest from this server — the one
    // a client writes if anything ever passes a client-supplied value through. That was safe only
    // by arrangement: Caddy declines to keep a prior `X-Forwarded-For` unless the peer is in
    // `trusted_proxies`, which is empty, so the header arrives holding exactly one entry and first
    // and last are the same value.
    //
    // An arrangement in another file is a poor place for the rate limiter's honesty to live. One
    // `trusted_proxies` line, or one more hop in front of Caddy, and the first entry becomes
    // whatever the caller typed — silently, with every per-address bucket keyed by the attacker.
    // The last entry is the one the nearest hop appended, which is the closest thing to a fact
    // this server can read. It is identical today and correct in the topologies that would have
    // broken the default.
    //
    // Still not a licence to expose this port directly: with no proxy at all there is no
    // `X-Forwarded-For`, the plugin leaves the socket address alone, and that is right — but a
    // deployment that puts this behind something which forwards blindly is back to trusting the
    // caller, whichever end of the list is read.
    install(XForwardedHeaders) { useLastProxy() }

    installRateLimits(accounts)

    // A correlation id per request, generated if the caller did not supply one. Without it, the
    // log lines of two concurrent matches interleave into something unreadable; with it, one grep
    // recovers a single request's whole story.
    install(CallId) {
        // `header` is both halves at once — read the caller's id from this header, and echo it
        // back on the response. Adding `replyToHeader` next to it sends the header twice.
        header(CALL_ID_HEADER)
        generate { UUID.randomUUID().toString() }

        // ### The caller's id is data, and this is where it stops being trusted
        //
        // `CallIdConfig` holds **one** verifier, not a list, so a `verify { }` here does not add a
        // rule — it replaces Ktor's. This used to say `it.isNotBlank()`, which threw away the
        // default dictionary check and accepted anything at any length that a caller cared to put
        // in `X-Request-Id`. The value is echoed on the response and pushed into the MDC, so
        // `logback.xml` then prints it on **every line** of that request: an id of four kilobytes
        // of punctuation is four kilobytes on every line, and it is the caller who chose it.
        //
        // So: the dictionary Ktor generates from, and a length that comfortably clears a UUID.
        // A failing verifier is not a rejection — the plugin skips that provider and falls through
        // to `generate` above — which is the right answer for a header nobody was asked to send.
        verify { id ->
            id.length in 1..MAX_CALL_ID_LENGTH && id.all { it in CALL_ID_ALPHABET }
        }
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
 * Long enough for a UUID and for any correlation id a proxy in front of this one would mint,
 * short enough that a log line stays a log line. Not a round number for its own sake: 36 is the
 * UUID this server generates, and this is comfortably past anything with a reason to be longer.
 */
private const val MAX_CALL_ID_LENGTH = 128

/**
 * What a call id may be made of.
 *
 * Ktor's own `CALL_ID_DEFAULT_DICTIONARY` is the same set without the capitals, and this is written
 * out rather than imported because that constant is not exported from the plugin's package — an
 * unresolved reference is a worse dependency than eight visible characters.
 *
 * The capitals are the one deliberate addition: uppercase hex and base64 are both ordinary shapes
 * for a correlation id minted upstream, and rejecting them would silently mint our own instead of
 * agreeing with whatever produced it. Everything outside this set — whitespace, control characters,
 * anything that would reformat a log line — is what the check is for.
 */
private const val CALL_ID_ALPHABET =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/=-"

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
const val PLAY = "play"
const val CODES = "codes"

/**
 * Ten code requests or code attempts per address per five minutes.
 *
 * ### Why this bucket exists on top of the per-code attempt ceiling
 *
 * Because the ceiling alone is not one. Five guesses per code is only a bound if the number of
 * codes is bounded too — otherwise the attack is *guess five, ask for a new one, repeat*, and a
 * six-digit space falls in an afternoon. `CodeStore.MAX_ATTEMPTS` bounds the guesses and
 * this bounds the resends, and neither is worth anything without the other.
 *
 * It also bounds the thing that costs money: every resend is a mail somebody pays for, and an
 * unthrottled resend button is an unthrottled bill.
 */
private const val CODES_LIMIT = 10
private val CODES_WINDOW = 5.minutes

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

/**
 * A hundred and twenty placements a minute — two a second, sustained.
 *
 * ### Why a bucket for this at all
 *
 * Because the refereed routes had none, and they pay. `/matches/submit` is throttled on the
 * argument that a transcript is unforgeable but not slow to produce, and that only the cadence
 * tells a bot from a player. Every word of that applies to `POST /pve/matches/{id}/moves`, which
 * plays a real match against a real opponent and settles it — and it was unthrottled, so the
 * defence [SUBMIT] provides was available to anyone willing to use the other endpoint.
 *
 * ### The number
 *
 * Two placements a second is about four times a player who is not thinking, and a match is nine
 * placements — so this bounds a bot to something like thirteen matches a minute where a person
 * plays one or two. That is deliberately *below* [SUBMIT_LIMIT]'s thirty: the two paths end in the
 * same payout, and the one the server referees should not be the loose one.
 *
 * It also covers conceding and collecting, which are the other two ways a match ends. They are
 * once-per-match actions and nowhere near this, which is the point — a limit a real player can
 * reach is a limit that was set wrong.
 */
private const val PLAY_LIMIT = 120

/** Sixty intents a minute — a player emptying a bag of thirty items in a hurry. */
private const val INTENT_LIMIT = 60
