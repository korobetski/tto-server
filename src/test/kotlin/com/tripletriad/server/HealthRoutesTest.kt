package com.tripletriad.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Liveness and readiness answer different questions — asserted, because collapsing them is the
 * default mistake and its cost only shows up during an incident.
 *
 * These run against Ktor's test host, with no socket and no database. The point is the *contract*
 * an orchestrator relies on; whether a real Postgres answers is [MigrationTest]'s business.
 */
class HealthRoutesTest {

    @Test
    fun livenessIgnoresTheDatabaseEntirely() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.get("/health/live")

        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "a database outage must not make the process look dead — it would be restarted",
        )
        assertTrue(response.bodyAsText().contains("alive"))
    }

    @Test
    fun readinessFailsWhenTheDatabaseIsUnreachable() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(
            response.bodyAsText().contains("database"),
            "the body must name the failing dependency, got: ${response.bodyAsText()}",
        )
    }

    @Test
    fun everyResponseCarriesACorrelationIdSoLogsCanBeJoined() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.get("/health/live")

        val ids = response.headers.getAll(CALL_ID_HEADER).orEmpty()

        assertTrue(
            ids.singleOrNull()?.isNotBlank() == true,
            "expected exactly one non-blank $CALL_ID_HEADER, got $ids",
        )
    }

    @Test
    fun metricsAreScrapeable() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.bodyAsText().contains("jvm_"),
            "the registry exposed no JVM metrics, so it was never bound",
        )
    }
}

/**
 * A [DataSource] that always fails to connect.
 *
 * Hand-written rather than mocked: the readiness probe's whole job is to behave well when the
 * database is gone, and a stub that throws on `getConnection` reproduces that in three lines
 * without a mocking framework in the dependency set.
 */
private object UnreachableDataSource : DataSource {
    override fun getConnection(): Connection = throw SQLException("connection refused")

    override fun getConnection(username: String?, password: String?): Connection = connection

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getGlobal()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
