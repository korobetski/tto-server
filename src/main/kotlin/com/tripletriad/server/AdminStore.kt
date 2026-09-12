package com.tripletriad.server

import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * The three tables `V19__admin.sql` added, over plain JDBC as everything else here is.
 *
 * ### Its own store, and the line it falls on
 *
 * `AccountStore` owns who a *player* is; this owns who an *administrator* is. They share no table
 * and, more to the point, no credential: the whole argument of `V19__admin.sql` is that a bearer
 * token minted by a game client must never be able to become an administrative one, and two stores
 * over two sets of tables is what that argument looks like in code.
 *
 * The one column that crosses is `admins.account_id`, and it is attribution rather than
 * authentication — nothing in this file reads it to decide anything.
 *
 * ### `AdminStore` reads; the writes that matter are somebody else's
 *
 * `web-platform.md` states it as a rule: anything that grants or spends goes through the
 * `AccountStore` methods and `applyOnce` that already exist, so a retried refund returns the first
 * answer instead of performing a second one. What this store writes is therefore only ever *its
 * own* rows — an administrator, a session, an audit line — and the audit line is written in the
 * same transaction as the effect it describes, which is why [append] takes a [Connection] as well
 * as offering to open one.
 *
 * ### Every write path here is guarded in SQL rather than in Kotlin
 *
 * [recordStep], [enrol] and [signIn] all carry their precondition in a `WHERE` clause and report
 * whether it held. That is not a style preference: the precondition in each case is about a
 * credential being used twice, and a read followed by a write is exactly the shape two simultaneous
 * requests slip through. See [recordStep], which is the one where it is load-bearing.
 */
// TooManyFunctions counts queries, which is what a data-access class is made of — the same
// judgement `AccountStore` and `AuctionStore` record above their own suppressions. Splitting this
// by table would put the administrator, their session and their audit row in three files that
// could only be read together.
@Suppress("TooManyFunctions")
class AdminStore(private val dataSource: DataSource) {

    /**
     * Creates an administrator with a password and no second factor.
     *
     * Called by [ensureFirstAdministrator] at start-up and by nothing else — there is no route that
     * creates an administrator, because a console that can mint its own operators is a console
     * where one compromised session is permanent. The second and third administrator are created
     * the same way the first one is, by an operator with the environment variable and a restart.
     *
     * @return the new id, or null when the name is taken. Which is the ordinary case on every boot
     *   after the first, and the reason this is idempotent rather than conditional on a read.
     */
    fun create(username: String, passwordHash: String): Long? = transaction { db ->
        try {
            db.prepareStatement(
                "INSERT INTO admins (username, password_hash) VALUES (?, ?) RETURNING id",
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, passwordHash)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
        } catch (failure: SQLException) {
            // 23505, the standard's unique_violation — the same narrow catch
            // `AccountStore.register` explains at length, for the same reason: any other constraint
            // failing here is a bug and should surface, not be reported as "that name is taken".
            if (failure.sqlState == UNIQUE_VIOLATION) null else throw failure
        }
    }

    /** Whether any administrator exists at all. Read once at start-up, for the log line. */
    fun count(): Int = transaction { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM admins").use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }
    }

    /**
     * Everything a sign-in needs about one administrator, by name, case-insensitively.
     *
     * Returns the row for a **disabled** administrator too. The route has to verify the password
     * before it says anything about the account's state — answering "this account is disabled" to
     * an unverified caller would turn the form into a way of asking which administrators exist, and
     * which of them have been retired.
     */
    fun byUsername(username: String): StoredAdmin? = transaction { db ->
        db.prepareStatement(
            """
            SELECT id, username, password_hash, totp_secret,
                   totp_enrolled_at IS NOT NULL AS enrolled,
                   disabled_at IS NOT NULL AS disabled
            FROM admins WHERE username_key = lower(?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    StoredAdmin(
                        id = rows.getLong("id"),
                        username = rows.getString("username"),
                        passwordHash = rows.getString("password_hash"),
                        totpSecret = rows.getString("totp_secret"),
                        enrolled = rows.getBoolean("enrolled"),
                        disabled = rows.getBoolean("disabled"),
                    )
                } else {
                    null
                }
            }
        }
    }

    /** Replaces a digest made at a weaker bcrypt cost. The player path does the same at sign-in. */
    fun updatePasswordHash(adminId: Long, passwordHash: String) = transaction { db ->
        db.prepareStatement("UPDATE admins SET password_hash = ? WHERE id = ?").use { statement ->
            statement.setString(1, passwordHash)
            statement.setLong(2, adminId)
            statement.executeUpdate()
        }
        Unit
    }

    /**
     * Stores a freshly generated secret for an administrator who has not enrolled yet.
     *
     * ### The window this opens, and why it is not closable here
     *
     * Between an administrator being created and their first successful enrolment, the password
     * alone is enough to enrol *an* authenticator — so whoever holds the password first chooses the
     * second factor. That is inherent to a bootstrap that carries a password and no secret, and the
     * alternative is worse: a secret generated at start-up would be printed, logged, or left in a
     * container's environment, which is the failure `web-platform.md` designed this flow to avoid.
     *
     * What closes the window is the guard in this statement. `totp_enrolled_at IS NULL` means an
     * enrolment that has completed can never be replaced, so the window shuts the moment the
     * legitimate administrator finishes enrolling and cannot be reopened by anything short of an
     * operator with `psql` — see `docs/operations.md`, which is where losing an authenticator is
     * answered.
     *
     * @return false when this administrator is already enrolled, which is a refusal and not a
     *   retry: the caller must not treat it as "try again".
     */
    fun beginEnrolment(adminId: Long, secret: String): Boolean = transaction { db ->
        db.prepareStatement(
            "UPDATE admins SET totp_secret = ? WHERE id = ? AND totp_enrolled_at IS NULL",
        ).use { statement ->
            statement.setString(1, secret)
            statement.setLong(2, adminId)
            statement.executeUpdate() == 1
        }
    }

    /**
     * Completes an enrolment and opens the session it earned, in one transaction.
     *
     * Both or neither, because the two halves are one act from the operator's side: they typed a
     * code out of an authenticator they had just configured, and an enrolment that stored the
     * secret but lost the session would send them back to a form that now demands a code — which
     * would work, but only after a screen that looked like a failure.
     *
     * @return false when the guard did not hold: no secret pending, or somebody enrolled already.
     */
    fun enrol(adminId: Long, step: Long, tokenHash: String, expiresAt: Long): Boolean =
        transaction { db ->
            val enrolled = db.prepareStatement(
                """
                UPDATE admins SET totp_enrolled_at = now(), totp_last_step = ?
                WHERE id = ? AND totp_enrolled_at IS NULL AND totp_secret IS NOT NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, step)
                statement.setLong(2, adminId)
                statement.executeUpdate() == 1
            }
            if (!enrolled) return@transaction false

            insertSession(db, adminId, tokenHash, expiresAt)
            append(db, AuditEntry(adminId = adminId, action = ENROLLED))
            true
        }

    /**
     * Records the time step a sign-in used and opens the session, in one transaction.
     *
     * ### Why the replay check is this statement's `WHERE` clause
     *
     * A code is valid for thirty seconds and for one step. Somebody who reads it over a shoulder,
     * or sits on a proxy, or finds it in a screenshot has the rest of that window to use it — and a
     * check written as *read `totp_last_step`, compare, write it back* is refused only if the two
     * requests are far enough apart, which an attacker replaying a code immediately is not.
     *
     * `totp_last_step < ?` inside the UPDATE makes the database the arbiter: two requests with the
     * same code both try to advance the column, exactly one row is updated, and the loser is told
     * so. That is the difference between a defence and the appearance of one.
     *
     * @return false when the step has already been used. The caller answers it as a wrong code,
     *   because from the honest operator's side that is what it is — the code on their screen has
     *   been consumed and the next one is thirty seconds away.
     */
    fun signIn(adminId: Long, step: Long, tokenHash: String, expiresAt: Long): Boolean =
        transaction { db ->
            if (!recordStep(db, adminId, step)) return@transaction false
            insertSession(db, adminId, tokenHash, expiresAt)
            append(db, AuditEntry(adminId = adminId, action = SIGNED_IN))
            true
        }

    /**
     * Who this cookie belongs to, or null — and the same statement moves the idle clock forward.
     *
     * ### One statement, and what each clause of it is for
     *
     * `expires_at > now()` is the ceiling: a session ends a fixed distance from sign-in whatever
     * happens. `seen_at > now() - interval` is the idle clock, which is the one that matters for an
     * administration console — the threat is an unattended screen, and only an idle timeout
     * addresses that. `a.disabled_at IS NULL` is the third: retiring an administrator has to end
     * their access *now*, not when their current session happens to lapse.
     *
     * All three are in the `WHERE` clause rather than compared in Kotlin, for the reason
     * `AccountStore.accountForToken` gives — there is no window in which a row is fetched, judged
     * valid, and used a moment after it stopped being so, and the server's clock and the database's
     * cannot disagree about it.
     *
     * ### It writes on every read, and that is affordable here
     *
     * An idle clock that is not moved forward is a fixed expiry with extra steps, so the write is
     * the feature. `AccountStore.touch` throttles its equivalent because it runs once per player
     * per second across the whole lobby; this runs a handful of times a minute for the one or two
     * people who have a console, and a throttle would only blur the number it exists to keep.
     */
    fun session(tokenHash: String, idleMillis: Long): SignedInAdmin? = transaction { db ->
        db.prepareStatement(
            """
            UPDATE admin_sessions s SET seen_at = now()
            FROM admins a
            WHERE s.token_hash = ? AND s.admin_id = a.id
              AND a.disabled_at IS NULL
              AND s.expires_at > now()
              AND s.seen_at > now() - make_interval(secs => ?)
            RETURNING s.admin_id, a.username, s.created_at, s.expires_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.setDouble(2, idleMillis / MILLIS_PER_SECOND)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    SignedInAdmin(
                        id = rows.getLong("admin_id"),
                        username = rows.getString("username"),
                        signedInAt = rows.getTimestamp("created_at").time,
                        expiresAt = rows.getTimestamp("expires_at").time,
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Ends one session, and says whether there was one to end.
     *
     * Signing out must work for a session the server would no longer accept — an expired cookie
     * still deserves to be cleared rather than answered with "your expired session could not be
     * ended", which is the argument `Authentication.bearerToken` makes for the player's side.
     */
    fun closeSession(tokenHash: String): Boolean = transaction { db ->
        db.prepareStatement("DELETE FROM admin_sessions WHERE token_hash = ?").use { statement ->
            statement.setString(1, tokenHash)
            statement.executeUpdate() == 1
        }
    }

    /**
     * Deletes every session past its ceiling, wherever it came from.
     *
     * Tidiness rather than correctness — [session] refuses an expired row on sight, so nothing
     * depends on this having run — which is why it rides the slow branch of the sweep in
     * `Application.kt` rather than getting a loop of its own.
     */
    fun sweepSessions(): Int = transaction { db ->
        db.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM admin_sessions WHERE expires_at < now()")
        }
    }

    /**
     * Appends one audit row on a connection somebody else is holding.
     *
     * **This is the form that matters.** `admin_audit` is only worth anything if a row cannot be
     * missing while the change it describes happened, and cannot exist while the change did not —
     * which means the insert has to be in the caller's transaction, not in one of its own. The
     * overload below opens a transaction for the case where the action *is* the whole effect, which
     * is signing in and nothing else so far.
     */
    // Six columns of one row, and every one of them is a JDBC parameter position — the one place a
    // bare integer is not a magic number, as `AccountStore` says at length.
    @Suppress("MagicNumber")
    fun append(db: Connection, entry: AuditEntry) {
        db.prepareStatement(
            """
            INSERT INTO admin_audit
                (admin_id, action, subject_account, operation_id, reason, before, after)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, entry.adminId)
            statement.setString(2, entry.action)
            entry.subjectAccount?.let { statement.setLong(3, it) } ?: statement.setNull(3, BIGINT)
            statement.setString(4, entry.operationId)
            statement.setString(5, entry.reason)
            statement.setString(6, entry.before)
            statement.setString(7, entry.after)
            statement.executeUpdate()
        }
    }

    /**
     * Appends one audit row in a transaction of its own, for an action with no other effect.
     */
    fun append(entry: AuditEntry) = transaction { db -> append(db, entry) }

    /**
     * Advances `totp_last_step`, or reports that this step is not in the future. See [signIn].
     */
    // The third parameter is `step` a second time — a JDBC position, not a number to name.
    @Suppress("MagicNumber")
    private fun recordStep(db: Connection, adminId: Long, step: Long): Boolean =
        db.prepareStatement(
            """
            UPDATE admins SET totp_last_step = ?
            WHERE id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, step)
            statement.setLong(2, adminId)
            statement.setLong(3, step)
            statement.executeUpdate() == 1
        }

    /**
     * Inserts the session row and keeps the number of them bounded.
     *
     * The cap is [MAX_ADMIN_SESSIONS] and it is lower than the player's ten for two reasons: an
     * administrator has a desk rather than a pocket, and a forgotten row here is worth far more to
     * whoever finds it. The `OFFSET` form, the tie-break on `token_hash` and the ordering are
     * `AccountStore.openSession`'s, which explains why they are that way round.
     */
    @Suppress("MagicNumber")
    private fun insertSession(db: Connection, adminId: Long, tokenHash: String, expiresAt: Long) {
        db.prepareStatement(
            "INSERT INTO admin_sessions (token_hash, admin_id, expires_at) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.setLong(2, adminId)
            statement.setTimestamp(3, Timestamp(expiresAt))
            statement.executeUpdate()
        }
        db.prepareStatement(
            """
            DELETE FROM admin_sessions WHERE token_hash IN (
                SELECT token_hash FROM admin_sessions
                WHERE admin_id = ?
                ORDER BY created_at DESC, token_hash DESC
                OFFSET ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, adminId)
            statement.setInt(2, MAX_ADMIN_SESSIONS)
            statement.executeUpdate()
        }
    }

    /** Commits on success, rolls back on anything at all. `AccountStore.transaction`'s twin. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { db ->
        try {
            val result = block(db)
            db.commit()
            result
        } catch (failure: Throwable) {
            db.rollback()
            throw failure
        }
    }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        /** `java.sql.Types.BIGINT`, named so the import is not one more line for one constant. */
        const val BIGINT = java.sql.Types.BIGINT

        const val MILLIS_PER_SECOND = 1_000.0

        /**
         * Five: a desktop, a laptop, and room for a browser profile that was reinstalled. Past
         * that, a row is a session nobody remembers opening.
         */
        const val MAX_ADMIN_SESSIONS = 5
    }
}

/**
 * One administrator, as a sign-in needs to see them.
 *
 * [totpSecret] is **secret** and is on this object because verifying a code needs the key itself —
 * see `V19__admin.sql` for why there is no one-way form of it. It goes to [Totp] and to the one
 * response that shows a freshly issued one, and nowhere else.
 */
data class StoredAdmin(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val totpSecret: String?,
    /** True once a code computed from [totpSecret] has actually been entered. */
    val enrolled: Boolean,
    val disabled: Boolean,
)

/** Who a console session belongs to, and the two instants `GET /admin/me` reports. */
data class SignedInAdmin(
    val id: Long,
    val username: String,
    val signedInAt: Long,
    val expiresAt: Long,
)

/**
 * One row of `admin_audit`, as the server writes it.
 *
 * A data class rather than eight parameters, because the interesting calls fill three of them and
 * the rest are absent — and because an audit row is a record, which is the thing a data class is.
 *
 * @property action the server's own name for what was done. Deliberately not an enum: the table has
 *   no CHECK listing the set for the reason its own comment gives, and the constants that make up
 *   the set live beside the routes that write them.
 * @property subjectAccount the player this was done to, or null for an action about nobody.
 * @property operationId the `applied_operations` key of the effect, so a retried write that
 *   returned the first answer can be told from a second write that really happened.
 * @property before the state either side of the change, as JSON. Both null for a read or a sign-in.
 */
data class AuditEntry(
    val adminId: Long,
    val action: String,
    val subjectAccount: Long? = null,
    val operationId: String? = null,
    val reason: String? = null,
    val before: String? = null,
    val after: String? = null,
)

/** Signing in. Recorded because "who has been in the console, and when" is a question about it. */
const val SIGNED_IN = "SIGN_IN"

/** The first sign-in, which created the second factor. Distinct because it happens once. */
const val ENROLLED = "TOTP_ENROLLED"

/**
 * Creates the administrator named by the environment, if there is one and the name is free.
 *
 * ### Why this is at start-up and not a route
 *
 * Because the first administrator has to exist before anybody can sign in, and a route that creates
 * one would either be unauthenticated — a console anybody on the internet can enrol into — or
 * authenticated, which is the chicken and the egg. An environment variable and a restart is a
 * credential only somebody with the host has, which is the right bar for the first one.
 *
 * ### What it deliberately does not do
 *
 * It **never touches an administrator that already exists**: not the password, not the second
 * factor, not `disabled_at`. So leaving the variables set across a redeploy is inert rather than a
 * password reset on every boot, and a retired administrator does not come back because somebody
 * forgot to clear an environment file. The right move after the first boot is to remove them, and
 * `.env.prod.sample` says so.
 *
 * ### What reaches the log
 *
 * That an administrator was created, or that the name was taken. Not the name, and emphatically not
 * the password: the name is half of a credential for the one surface that can move balances, and
 * `LogSecrecyTest` exists because a rule stated in a comment holds only until somebody is debugging
 * on a Sunday. The operator already knows what they typed.
 *
 * @return true when a row was created.
 */
fun ensureFirstAdministrator(
    store: AdminStore,
    bootstrap: FirstAdministrator?,
    log: (String) -> Unit,
): Boolean {
    if (bootstrap == null) {
        // Worth a line only when it leaves the console unreachable. A deployment past its first
        // boot has no variables set and an administrator in the table, which is the steady state.
        if (store.count() == 0) {
            log(
                "No administrator exists and TTO_ADMIN_USERNAME is unset: " +
                    "the administration console cannot be signed in to",
            )
        }
        return false
    }

    val created = store.create(bootstrap.username, PasswordHasher.hash(bootstrap.password)) != null
    log(
        if (created) {
            "Created the first administrator. Their second factor is enrolled at first sign-in"
        } else {
            "An administrator with that name already exists; TTO_ADMIN_* is ignored"
        },
    )
    return created
}
