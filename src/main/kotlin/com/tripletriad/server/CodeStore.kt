package com.tripletriad.server

import io.ktor.http.HttpHeaders
import io.ktor.server.routing.RoutingContext
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * The short-lived codes mailed to an address: one table, four operations.
 *
 * ### Why not on `AccountStore`
 *
 * It was there first, and it made that class too big — which is a fair complaint about it and also
 * the wrong reason on its own. The right one is that this is a different subject with a different
 * lifetime: an account row lives for years and a code lives for ten minutes, and nothing here reads
 * or writes a profile. It is the same line `PvpStore` is drawn along, one table over.
 *
 * ### The two bounds, and why neither works alone
 *
 * Six digits is twenty bits, which is nothing. What makes a code safe is [MAX_ATTEMPTS] and its
 * expiry: five guesses inside ten minutes is one chance in two hundred thousand. That is only a
 * bound if the *number of codes* is bounded too — otherwise the attack is guess five, ask for
 * another, repeat — which is why the `CODES` rate limit in `Observability` and this class have to
 * be read together. Remove either and the other is decorative.
 */
class CodeStore(private val dataSource: DataSource) {

    /**
     * Stores a code, replacing whatever was out for the same purpose.
     *
     * The replacement is the point — see the primary key on `account_codes`. Asking for a new code
     * has to invalidate the old one, or pressing the button five times leaves five live codes and
     * five times the surface to guess at.
     */
    // JDBC parameter positions, which is the one place a bare integer is not a magic number — see
    // `AccountStore.openSession`, which says it at length.
    @Suppress("MagicNumber")
    fun put(accountId: Long, purpose: CodePurpose, codeHash: String, expiresAt: Long) {
        transaction { db ->
            db.prepareStatement(
                """
                INSERT INTO account_codes (account_id, purpose, code_hash, expires_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id, purpose) DO UPDATE
                    SET code_hash = EXCLUDED.code_hash,
                        expires_at = EXCLUDED.expires_at,
                        created_at = now(),
                        attempts = 0
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, accountId)
                st.setString(2, purpose.name)
                st.setString(3, codeHash)
                st.setTimestamp(4, Timestamp(expiresAt))
                st.executeUpdate()
            }
        }
    }

    /**
     * Spends a code: one attempt, and a correct one is gone.
     *
     * ### Why the attempt is counted inside the transaction that checks it
     *
     * Because otherwise it is not counted at all under load. Read-then-write with the increment in
     * a second statement lets a hundred parallel guesses each read `attempts = 0`, and a
     * five-attempt ceiling becomes a five-hundred-attempt one. `FOR UPDATE` on the row is what
     * makes the ceiling mean what it says.
     *
     * A correct code is **deleted** rather than marked: a code that has been used is not a code,
     * and leaving the row would leave something for a second request to race against.
     */
    fun consume(accountId: Long, purpose: CodePurpose, codeHash: String, now: Long): CodeOutcome =
        transaction { db ->
            val row = read(db, accountId, purpose) ?: return@transaction CodeOutcome.NO_CODE

            when {
                row.expiresAt <= now -> {
                    delete(db, accountId, purpose)
                    CodeOutcome.EXPIRED
                }

                // Left in place rather than deleted, so every further guess keeps
                // answering "exhausted" instead of "no code" — the second would read, to
                // somebody guessing, as a hint that they had run out on the *right*
                // account.
                row.attempts >= MAX_ATTEMPTS -> CodeOutcome.EXHAUSTED

                // Constant-time, because the comparison is against a secret. Two digests
                // differ in the first byte often enough that early exit leaks measurable
                // timing, and not leaking it costs one function call.
                !MessageDigest.isEqual(row.hash.toByteArray(), codeHash.toByteArray()) -> {
                    countAttempt(db, accountId, purpose)
                    CodeOutcome.WRONG
                }

                else -> {
                    delete(db, accountId, purpose)
                    CodeOutcome.ACCEPTED
                }
            }
        }

    /** Drops codes nobody can use any more. Ridden along on the sweep that clears stale matches. */
    fun purgeExpired(now: Long): Int = transaction { db ->
        db.prepareStatement("DELETE FROM account_codes WHERE expires_at <= ?").use { st ->
            st.setTimestamp(1, Timestamp(now))
            st.executeUpdate()
        }
    }

    // JDBC parameter positions again.
    @Suppress("MagicNumber")
    private fun read(db: Connection, accountId: Long, purpose: CodePurpose): StoredCode? =
        db.prepareStatement(
            "SELECT code_hash, expires_at, attempts FROM account_codes " +
                "WHERE account_id = ? AND purpose = ? FOR UPDATE",
        ).use { st ->
            st.setLong(1, accountId)
            st.setString(2, purpose.name)
            st.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    StoredCode(rows.getString(1), rows.getTimestamp(2).time, rows.getInt(3))
                }
            }
        }

    private fun countAttempt(db: Connection, accountId: Long, purpose: CodePurpose) {
        db.prepareStatement(
            "UPDATE account_codes SET attempts = attempts + 1 WHERE account_id = ? AND purpose = ?",
        ).use { st ->
            st.setLong(1, accountId)
            st.setString(2, purpose.name)
            st.executeUpdate()
        }
    }

    private fun delete(db: Connection, accountId: Long, purpose: CodePurpose) {
        db.prepareStatement(
            "DELETE FROM account_codes WHERE account_id = ? AND purpose = ?",
        ).use { st ->
            st.setLong(1, accountId)
            st.setString(2, purpose.name)
            st.executeUpdate()
        }
    }

    /** The same explicit-transaction rule `AccountStore` follows, and for the same reason. */
    // And the same deliberately wide catch, for the reason `AccountStore.transaction` gives at
    // length: anything at all leaving `block` must not leave a transaction open.
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

    private class StoredCode(val hash: String, val expiresAt: Long, val attempts: Int)

    companion object {
        /**
         * Five guesses at a six-digit code, and then it is dead.
         *
         * A million possibilities against five tries is one in two hundred thousand, which is the
         * number that makes a short code safe at all — but only alongside the request limit. See
         * this class's own KDoc.
         */
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Issuing a code and spending one, over the two things that takes: a table and a mailer.
 *
 * ### Why the routes hold this rather than the store and the mailer separately
 *
 * Because neither half is useful alone. A code stored and not sent is a code nobody can answer; a
 * mail with nothing recorded behind it proves nothing when the player types it back. Every caller
 * wanted both, and passing both meant `accountRoutes` carried two parameters to say one thing.
 *
 * ### Why it is handed the time rather than holding a clock
 *
 * `accountRoutes` already takes one, and tests set it. A second clock in here would be a second
 * *answer* to what time it is, and the interesting tests — a code that has just expired — are
 * exactly the ones the two would disagree in.
 */
class CodeChannel(
    private val store: CodeStore,
    // Defaulted to the mailer that sends nothing, so a test that does not care about mail
    // constructs this with one argument. A deployment cannot reach the default — see
    // `MailConfig.from`, which refuses to boot production without a provider.
    private val mailer: Mailer = Mailer.Disabled,
) {

    /**
     * Sends a fresh code for [purpose] and stores its fingerprint, replacing any code already out.
     *
     * Best-effort by construction: [Mailer.send] never throws, and its answer is deliberately
     * ignored. A provider that is down must not fail the registration that triggered this — the
     * account exists, and the player asks for another code when the first does not arrive.
     */
    suspend fun issue(
        context: RoutingContext,
        accountId: Long,
        email: String,
        purpose: CodePurpose,
        now: Long,
    ) {
        val code = Codes.issue()
        store.put(
            accountId = accountId,
            purpose = purpose,
            codeHash = Codes.fingerprint(code),
            expiresAt = now + EXPIRY_MINUTES * MILLIS_PER_MINUTE,
        )

        // From the header rather than from a field on the body: HTTP already carries this, and
        // adding it to `Credentials` would have been a protocol change to say something twice.
        val language = context.call.request.headers[HttpHeaders.AcceptLanguage]
        val message = when (purpose) {
            CodePurpose.VERIFY_EMAIL -> MailTemplates.verification(language, code)
            CodePurpose.RESET_PASSWORD -> MailTemplates.passwordReset(language, code)
        }
        mailer.send(email, message)
    }

    /**
     * Spends what the player typed. The plaintext is fingerprinted here and nowhere else, which is
     * what keeps [CodeStore] a store of digests with no opinion about what they are digests of.
     *
     * Whitespace is stripped first, and that is not cosmetic: `AccountCode.looksValid` accepts
     * `123 456` — a code copied out of a mail picks up spaces, and refusing it would be refusing a
     * player who typed the right thing — so a fingerprint taken before the strip would disagree
     * with the one that was stored, and the correct code would be answered "invalid".
     */
    fun spend(accountId: Long, purpose: CodePurpose, code: String, now: Long): CodeOutcome =
        store.consume(
            accountId,
            purpose,
            Codes.fingerprint(code.filterNot(Char::isWhitespace)),
            now,
        )
}

private const val MILLIS_PER_MINUTE = 60_000L

/** What a code is for. Stored as its name in `account_codes.purpose`. */
enum class CodePurpose {
    VERIFY_EMAIL,
    RESET_PASSWORD,
}

/**
 * What happened when a code was spent.
 *
 * Five outcomes here and **one answer on the wire**: every failure below is reported to a client as
 * `AccountError.INVALID_CODE`. Distinguishing them there would tell somebody guessing whether the
 * account exists, whether a code is outstanding, and how close to the ceiling they are — three
 * facts that are only useful to them. They are separate here because the server's own log and its
 * tests need to tell them apart.
 */
enum class CodeOutcome {
    ACCEPTED,

    /** Nothing outstanding for this account and purpose. */
    NO_CODE,

    WRONG,

    EXPIRED,

    /** Too many wrong guesses. A new code has to be asked for. */
    EXHAUSTED,
}
