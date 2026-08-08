package com.tripletriad.server

import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One Postgres for the whole test run, migrated once.
 *
 * ### Why shared, when [MigrationTest] starts its own
 *
 * Because the two want different things. That test is *about* a container coming up from nothing,
 * so it must own one. Everything else merely needs a database to be there, and starting a fresh
 * Postgres per class costs several seconds each — which is the difference between a suite people
 * run and one they skip.
 *
 * Shared state across tests is a real hazard and it is bounded here by [freshAccount]: tests take a
 * username nobody else uses rather than truncating tables between runs. Cleaning up would be the
 * other answer and a worse one — it turns every test into a test that can be broken by another
 * test's cleanup running late.
 *
 * Nothing closes the container. Testcontainers' own reaper does it when the JVM exits, and an
 * `@AfterAll` racing a lazily-started singleton is how these fixtures usually break.
 */
object Postgres {

    private val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>(DockerImageName.parse(IMAGE)).apply { start() }
    }

    /** The pool every test shares, with the schema already applied. */
    val dataSource: HikariDataSource by lazy {
        Database.pool(
            DatabaseConfig(
                url = container.jdbcUrl,
                user = container.username,
                password = container.password,
                maxPoolSize = POOL_SIZE,
            ),
        ).also { Database.migrate(it) }
    }

    /** A username no other test will have taken. */
    fun freshAccount(prefix: String): String = "$prefix${counter++}"

    private var counter = 1

    /** Kept identical to the image in `compose.yaml`, for the reason [MigrationTest] gives. */
    private const val IMAGE = "postgres:17-alpine"
    private const val POOL_SIZE = 4
}
