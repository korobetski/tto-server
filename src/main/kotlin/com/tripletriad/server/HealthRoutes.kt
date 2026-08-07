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
    logger.warn("Readiness probe failed", failure)
    failure.message ?: failure::class.simpleName ?: "unavailable"
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
