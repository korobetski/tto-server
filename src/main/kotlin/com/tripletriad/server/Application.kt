package com.tripletriad.server

import io.ktor.server.application.Application
import io.ktor.server.engine.addShutdownHook
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("com.tripletriad.server.Application")

/**
 * The entry point.
 *
 * ### The order here is the whole point
 *
 * Configuration, then the pool, then the migration — and only then does the port open. A server
 * that starts listening before it knows its schema is sound will accept a request it cannot serve
 * and answer it with a 500, which looks like an application bug rather than a failed deployment.
 * Failing here instead means a bad deploy is loud, immediate, and does not take traffic.
 */
fun main() {
    val config = try {
        ServerConfig.from()
    } catch (failure: IllegalStateException) {
        // Not rethrown: a stack trace for a missing environment variable buries the one line that
        // says which one. Exiting non-zero is what a supervisor reads anyway.
        logger.error("Refusing to start: {}", failure.message)
        exitProcess(EXIT_MISCONFIGURED)
    }

    logger.info("Starting in {} mode on {}:{}", config.environment, config.host, config.port)

    // Two blocks rather than one: opening the pool already connects (see Database.pool), so a
    // wrong host or password fails here, before there is anything to close. Only the second block
    // owns a resource.
    val dataSource = try {
        Database.pool(config.database)
    } catch (failure: Exception) {
        logger.error("Refusing to start: the database could not be reached", failure)
        exitProcess(EXIT_DATABASE_UNREACHABLE)
    }

    try {
        Database.migrate(dataSource)
    } catch (failure: Exception) {
        logger.error("Refusing to start: the schema could not be brought up to date", failure)
        dataSource.close()
        exitProcess(EXIT_MIGRATION_FAILED)
    }

    val registry = prometheusRegistry()
    val server = embeddedServer(Netty, port = config.port, host = config.host) {
        module(dataSource, registry)
    }

    // Closes the pool on SIGTERM, which is what `docker stop` and every orchestrator send first.
    // Without it the process is killed with connections still checked out, and Postgres spends its
    // own timeout discovering they are gone.
    server.addShutdownHook {
        logger.info("Shutting down")
        dataSource.close()
    }

    server.start(wait = true)
}

/**
 * Wires the application. Kept separate from [main] so tests can start it without a socket, a
 * shutdown hook or a real Postgres.
 */
fun Application.module(dataSource: DataSource, registry: PrometheusMeterRegistry) {
    installObservability(registry)

    routing {
        healthRoutes(dataSource)

        // Plain text, because that is the format Prometheus scrapes. Not behind authentication
        // yet, and not exposed publicly either — see docs/operations.md.
        get("/metrics") {
            call.respondText(registry.scrape())
        }
    }
}

// sysexits.h conventions: 78 is a configuration error, 70 an internal failure, 69 a service the
// process depends on being unavailable. Distinct codes so a supervisor's log says which of the
// three happened without anyone reading the stack trace.
private const val EXIT_MISCONFIGURED = 78
private const val EXIT_MIGRATION_FAILED = 70
private const val EXIT_DATABASE_UNREACHABLE = 69
