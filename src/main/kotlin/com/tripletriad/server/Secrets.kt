package com.tripletriad.server

import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Password hashing, and the two rules that make it worth having.
 *
 * ### Slow on purpose
 *
 * A password hash is the one place in a system where speed is a defect. SHA-256 over a password
 * would be a correct digest and a useless defence: a modern GPU tries billions a second, and the
 * whole table falls in an afternoon. bcrypt's cost factor makes each attempt cost roughly
 * [COST]-doublings of work, which is imperceptible once per sign-in and ruinous a billion times.
 *
 * ### The cost travels inside the digest
 *
 * `$2a$12$…` names its own cost, so raising [COST] later does not need a migration or a second
 * column: existing digests keep verifying at the cost they were made with, and each is silently
 * replaced at the owner's next sign-in — which is what [needsRehash] is for.
 */
object PasswordHasher {

    /**
     * 12, which is roughly a quarter-second on the hardware this runs on.
     *
     * Chosen to be *felt* by an attacker and not by a player. It is the one number here worth
     * revisiting on faster hardware: the target is a fixed wall-clock cost, so as machines get
     * faster this must go up to stay in the same place.
     */
    private const val COST = 12

    /**
     * bcrypt's own limit — and it is enforced by **refusal**, not by truncation.
     *
     * This paragraph used to say that input past 72 bytes is "ignored, silently", and it was
     * wrong about the library underneath. `at.favre.lib:bcrypt` defaults to its strict
     * long-password strategy and **throws** `IllegalArgumentException`:
     *
     *     password must not be longer than 72 bytes plus null terminator encoded in utf-8, was 100
     *
     * Which mattered, because the guard the old paragraph argued for was never written on the
     * strength of it: an over-long password reached bcrypt on every path that hashes, and the
     * throw surfaced as a `500` from `StatusPages` — on `POST /sessions`, which is
     * unauthenticated. [isUsable] is that guard, and it is applied here rather than left to each
     * caller to remember.
     *
     * ### Bytes, not characters
     *
     * `Credentials.PASSWORD_LENGTH` counts characters and bcrypt counts UTF-8 bytes, so no
     * character range can stand in for this: sixty emoji are sixty characters and a hundred and
     * twenty bytes. The two limits are different limits and both have to be checked.
     */
    const val MAX_PASSWORD_BYTES = 72

    /**
     * Whether [password] is short enough for bcrypt to accept.
     *
     * Public because registration has to answer a too-long password at the form — see
     * `AccountRoutes.respondMalformed` — where [verify]'s quiet `false` would be the wrong shape.
     */
    fun isUsable(password: String): Boolean =
        password.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES

    /**
     * Hashes [password]. The result contains its own salt and cost; store it as-is.
     *
     * @throws IllegalArgumentException if [password] is longer than [MAX_PASSWORD_BYTES]. A caller
     *   that has not checked [isUsable] first is storing a password nobody will ever be able to
     *   sign in with, so this one is a programming error rather than something to report to a
     *   player — the report belongs at the form, before the account exists.
     */
    fun hash(password: String): String {
        require(isUsable(password)) { "password exceeds $MAX_PASSWORD_BYTES bytes" }
        return BCrypt.withDefaults().hashToString(COST, password.toCharArray())
    }

    /**
     * Whether [password] matches [digest].
     *
     * A malformed or truncated digest verifies as **false** rather than throwing. A corrupt row is
     * a reason to refuse a sign-in, not to return a 500 that tells the caller their account exists
     * and is broken.
     *
     * A password past [MAX_PASSWORD_BYTES] is the same judgement, and it is more than defensive
     * tidiness: it is the *correct* answer. Every stored digest was made from a password bcrypt
     * accepted, so one it would refuse cannot be the password for any account — and answering
     * `false` lets `signIn` and account deletion give their ordinary 401 rather than a 500. The
     * answer does not depend on whether the account exists, so it tells a caller nothing the
     * decoy in `AccountRoutes` is hiding.
     */
    fun verify(password: String, digest: String): Boolean =
        isUsable(password) && BCrypt.verifyer().verify(password.toCharArray(), digest).verified

    /** True when [digest] was made at a weaker cost than the current one and should be replaced. */
    fun needsRehash(digest: String): Boolean = runCatching {
        BCrypt.verifyer()
        // `$2a$10$…` — the cost is the second field, and reading it is cheaper than a full parse.
        digest.split('$').getOrNull(2)?.toIntOrNull()?.let { it < COST } ?: true
    }.getOrDefault(true)
}

/**
 * Bearer tokens: how one is made, and why the database never sees it.
 *
 * ### The token is not stored
 *
 * `sessions.token_hash` holds a SHA-256 of the token, not the token. That single decision is what
 * makes a leaked database dump useless for impersonation — what the client sends is the pre-image,
 * and the dump has only the image.
 *
 * ### Why SHA-256 here and bcrypt for passwords
 *
 * They defend against different things, and using bcrypt here would be a mistake rather than extra
 * care. A password is short, human-chosen and guessable, so the defence has to be *slowness*. A
 * token is [TOKEN_BYTES] bytes from a CSPRNG — there is nothing to guess, and no dictionary to run
 * — so the only requirement is that the digest is one-way. Making it slow would tax every
 * authenticated request for a property it does not need.
 */
object Tokens {

    /**
     * 32 bytes — 256 bits of entropy.
     *
     * Not a length picked for looks: it is the point past which guessing is not a strategy anyone
     * can pursue, and going further buys nothing measurable.
     */
    private const val TOKEN_BYTES = 32

    /** Seeded by the OS, and shared: `SecureRandom` is thread-safe and expensive to construct. */
    private val random = SecureRandom()

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * A fresh token. **Secret**: the only place it may go is the response body that returns it and
     * the client's own storage. Not a log, not a URL, not an error message.
     */
    fun issue(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    /** What goes in `sessions.token_hash`. */
    fun fingerprint(token: String): String = encoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
    )
}

/**
 * The short numeric codes mailed to an address, and the digest stored in their place.
 *
 * ### Why a code and not a link, and what that costs
 *
 * A link would carry 256 bits and be unguessable; six digits carry about twenty. That is a real
 * loss and it is paid for elsewhere — five attempts per code, ten minutes of life, and a rate limit
 * on asking for more. What it buys is a flow that works identically on desktop, Android and iOS
 * with no page to serve, no deep link to register and no browser handoff. See
 * `protocol/Accounts.kt`'s `AccountCode` for the same argument from the client's side.
 *
 * ### Why `SecureRandom` for six digits
 *
 * Because the alternative is `Random`, whose sequence is predictable from a couple of outputs — and
 * a code generator whose next value can be computed from the last one is not a code generator. The
 * cost of getting this right is the word `Secure`.
 */
object Codes {

    private val random = SecureRandom()

    /** A fresh code, zero-padded so that every one is exactly [AccountCode.LENGTH] digits. */
    fun issue(): String = random.nextInt(CODE_SPACE).toString().padStart(DIGITS, '0')

    /**
     * What goes in `account_codes.code_hash`.
     *
     * SHA-256, like a session token, and the migration's own comment is honest about what that
     * does not buy: a million-entry rainbow table for six digits is trivial, so this protects
     * against a code leaking *without* the database leaking — a backup, a log, a support query —
     * and not against a stolen dump. The attempt ceiling is the defence that counts.
     */
    fun fingerprint(code: String): String = Tokens.fingerprint(code.filterNot { it.isWhitespace() })

    private const val DIGITS = 6

    private const val CODE_SPACE = 1_000_000
}
