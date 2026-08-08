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
     * bcrypt's own limit, and the reason `Credentials.PASSWORD_LENGTH` names it too.
     *
     * Input past 72 bytes is **ignored**, silently. A limit the algorithm imposes should be one the
     * player is told about at the form, rather than one that quietly makes a 90-character
     * passphrase equivalent to its first 72.
     */
    const val MAX_PASSWORD_BYTES = 72

    /** Hashes [password]. The result contains its own salt and cost; store it as-is. */
    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(COST, password.toCharArray())

    /**
     * Whether [password] matches [digest].
     *
     * A malformed or truncated digest verifies as **false** rather than throwing. A corrupt row is
     * a reason to refuse a sign-in, not to return a 500 that tells the caller their account exists
     * and is broken.
     */
    fun verify(password: String, digest: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), digest).verified

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
