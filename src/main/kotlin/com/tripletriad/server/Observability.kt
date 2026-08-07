package com.tripletriad.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

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
