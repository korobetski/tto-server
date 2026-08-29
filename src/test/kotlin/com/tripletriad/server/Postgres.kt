package com.tripletriad.server

import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.Unlocks
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

/**
 * A unique address for a test account, derived from its equally unique name.
 *
 * Registration requires one, and it has to be *unique* — `accounts_email_key_idx` is a unique
 * index, so a shared literal would make the second test in a run collide with the first and be
 * refused with `EMAIL_TAKEN`. `Postgres.freshAccount` already solves that problem for names, so
 * this borrows its answer instead of inventing a second one.
 *
 * `.test` is reserved by RFC 2606 precisely so that it can never resolve, which matters more than
 * it looks: if a test ever reached a real mailer with a real-looking domain, it would be sending
 * mail to a stranger.
 */
internal fun address(username: String): String = "$username@example.test"

/**
 * Registration credentials for [username], address included.
 *
 * The password is the one every test uses; a test that is *about* the password passes its own.
 */
internal fun credentials(username: String, password: String = TEST_PASSWORD) =
    Credentials(username, password, address(username))

/**
 * Confirms [username]'s address and levels the account enough to start refereed play.
 *
 * A fresh registration is level 1 with an unconfirmed address, and `authenticateUnlocked` refuses
 * on both counts — so without this every PvP test would be measuring the gate rather than the
 * lobby. Reaching past the API to do it is deliberate: the two honest routes are consuming a code
 * out of a mailer no test is running, and playing a career's worth of matches, and neither has
 * anything to do with what those tests are about. `PvpUnlockTest` is where the gate itself is
 * measured, and it does **not** call this.
 */
internal fun unlockForPvp(username: String, level: Int = Unlocks.DEFAULT_MULTIPLAYER) {
    val store = AccountStore(Postgres.dataSource)
    val accountId = requireNotNull(store.accountIdFor(username)) { "no account named $username" }
    store.markVerified(accountId, System.currentTimeMillis())
    store.mutate(accountId) { save -> Outcome(save.copy(level = level), Unit) }
}

/** Long enough for `Credentials.PASSWORD_LENGTH`, and not a real password anywhere. */
internal const val TEST_PASSWORD = "correct-horse-battery"
