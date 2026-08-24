package com.tripletriad.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * The two health endpoints, which answer two different questions.
 *
 * Collapsing them into one `/health` is the usual mistake and it has a specific cost. An
 * orchestrator uses **liveness** to decide whether to kill the process and **readiness** to decide
 * whether to send it traffic. If a single endpoint reports the database, then a database that is
 * briefly unreachable makes every server look dead, and they are all restarted — which does not
 * bring the database back and does lose whatever was in flight.
 *
 * So: [live] answers "is this process still working" and touches nothing. [ready] answers "can it
 * serve a request right now" and checks the dependency it cannot work without.
 */
fun Route.healthRoutes(dataSource: DataSource) {
    route("/health") {
        get("/live") {
            call.respond(HealthResponse(status = "alive"))
        }

        get("/ready") {
            val database = probeDatabase(dataSource)
            val healthy = database == OK

            call.respond(
                status = if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                message = HealthResponse(
                    status = if (healthy) "ready" else "unready",
                    checks = mapOf("database" to database),
                ),
            )
        }
    }
}

/**
 * The same probe, reduced to the yes-or-no a client can act on.
 *
 * `/server` needs the fact and not the diagnosis: a player can be told "this server is reachable
 * but cannot serve you yet", and nothing they could do with the driver's error message would help.
 * The message stays in [ready], which is read by whoever is on call.
 */
internal fun isDatabaseReachable(dataSource: DataSource): Boolean = probeDatabase(dataSource) == OK

/**
 * Asks the database whether it is there, and returns [OK] or the reason it is not.
 *
 * `isValid` rather than `SELECT 1`: the driver implements it as a protocol-level check with a
 * timeout the JDBC contract actually specifies, so a database that has accepted the socket but
 * stopped answering is reported as unhealthy instead of hanging this endpoint — which would make
 * the readiness probe itself the thing that times out.
 */
private fun probeDatabase(dataSource: DataSource): String = runCatching {
    dataSource.connection.use { connection ->
        if (connection.isValid(PROBE_TIMEOUT_SECONDS)) OK else "not responding"
    }
}.getOrElse { failure ->
    // The driver's own message goes to the log and **not** onto the wire. It can carry the JDBC
    // URL, the container's hostname and the role the server connects as, and this endpoint is one
    // Caddy refuses from outside rather than one that is authenticated — so the only thing keeping
    // that off the internet is a `respond @internal 404` in another file. `Observability.kt`'s
    // catch-all already declined to describe a cause for this exact reason; the two now agree.
    //
    // Whoever is on call reads the warn line, which has the exception and the correlation id.
    logger.warn("Readiness probe failed", failure)
    "unavailable"
}

/**
 * The body of both endpoints.
 *
 * `checks` is empty for liveness and populated for readiness, which keeps one shape on the wire
 * for anything consuming it.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val checks: Map<String, String> = emptyMap(),
)

private val logger = LoggerFactory.getLogger("com.tripletriad.server.Health")
private const val OK = "ok"
private const val PROBE_TIMEOUT_SECONDS = 2
