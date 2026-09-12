package com.tripletriad.server

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The second factor: RFC 6238 time-based one-time passwords, and RFC 4648 base32 to carry the key.
 *
 * ### Why this is written here rather than taken from a library
 *
 * `web-platform.md` said TOTP "costs one small vetted library and one column", and the library half
 * of that sentence is the part this file disagrees with — see the note it now carries.
 *
 * The algorithm is four operations: an HMAC over a counter, RFC 4226's dynamic truncation, a
 * modulus, and a base32 alphabet. Every one of them is in the JDK already except the alphabet, and
 * the specification ships **its own test vectors** — `TotpTest` pins all six of RFC 6238 Appendix
 * B's, which is a stronger statement about correctness than any dependency's presence is. A library
 * would be trusted; this is checked.
 *
 * Against that: a dependency here would sit on the classpath of the process that referees the
 * economy, pulled from a repository, updated by somebody else, for forty lines of arithmetic whose
 * answer is published. That trade only makes sense the other way round when the primitive is one
 * nobody should implement — which is exactly why the *password* hash is `at.favre.lib:bcrypt` and
 * not a hand-rolled Blowfish. The line between the two is whether a mistake is detectable, and
 * these test vectors are what makes it so here.
 *
 * ### What the second factor is worth, and what it is not
 *
 * It stops a stolen password. It does not stop a stolen *session* — that is what the two clocks on
 * `admin_sessions` are for — and it does not stop somebody who is phished into typing a code into
 * the wrong page. A hardware key would close the second of those and is the right answer the day
 * there are more than two administrators; it costs a WebAuthn ceremony, an attestation policy and a
 * recovery story, none of which exist yet.
 *
 * ### The secret is stored in the clear, and `V19__admin.sql` is where that is argued
 *
 * In one line: a code is *recomputed*, so there is no one-way form of the key that would still
 * work. The column comment says the rest.
 */
object Totp {

    /** Six, which is what every authenticator produces without being configured to. */
    const val DIGITS = 6

    /**
     * Thirty seconds a step — RFC 6238's own default, and the only value an authenticator app can
     * be relied on to use when it is handed a bare `otpauth://` URI.
     */
    const val STEP_SECONDS = 30L

    /**
     * A fresh key, base32, ready to be typed into an authenticator.
     *
     * **Secret.** It goes into `admins.totp_secret` and into the one response that shows it, and
     * nowhere else — not a log line, not an error message, not a metric label. The whole reason
     * enrolment happens in the console rather than at start-up is to keep this value out of
     * everything that writes to disk.
     */
    fun issueSecret(): String {
        val key = ByteArray(SECRET_BYTES)
        random.nextBytes(key)
        return Base32.encode(key)
    }

    /** Which RFC 6238 time step [millis] falls in. */
    fun stepAt(millis: Long): Long = millis / (STEP_SECONDS * MILLIS_PER_SECOND)

    /**
     * The code [secret] produces at [step].
     *
     * Public so the tests can drive it against Appendix B's vectors at a fixed counter. Nothing in
     * the server calls it directly: a route asks [matchingStep], which is the question a route
     * actually has.
     */
    fun code(secret: String, step: Long): String {
        val key = Base32.decode(secret) ?: return ""
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        val digest = mac.doFinal(ByteBuffer.allocate(COUNTER_BYTES).putLong(step).array())
        return truncate(digest).toString().padStart(DIGITS, '0')
    }

    /**
     * The step [code] was computed at, or null if it was not computed from [secret] at all.
     *
     * ### Why a window, and why such a narrow one
     *
     * A clock that is a few seconds out is the ordinary case, not the exceptional one: the phone's
     * clock, the server's clock and the moment the operator finishes typing are three different
     * instants. One step either side of [now] is RFC 6238 § 5.2's own recommendation — it accepts
     * a code up to a minute and a half old at the cost of tripling the space a guesser is shooting
     * at, from one in a million to three. Widening it further buys tolerance for a clock that is
     * actually wrong, which is a thing to fix rather than to accommodate.
     *
     * ### Why the answer is the step and not a boolean
     *
     * Because the caller has to *write it down*. A code stays valid for thirty seconds, which is
     * long enough for somebody reading over a shoulder or sitting on a proxy to use it a second
     * time — so `admins.totp_last_step` records the step that was used and the next sign-in must be
     * at a later one. That check belongs in the same UPDATE that stores it, not here: see
     * `AdminStore.recordStep`, which is what makes it atomic rather than merely sequential.
     *
     * The comparison is `MessageDigest.isEqual`, which does not return early. The leak it closes is
     * small — six digits, and an attacker would need to measure a difference across a network on a
     * request that has already spent a quarter of a second in bcrypt — and it costs one function
     * call to not have to reason about that.
     */
    fun matchingStep(secret: String, code: String, now: Long): Long? {
        val typed = code.filterNot { it.isWhitespace() }
        if (typed.length != DIGITS) return null
        val current = stepAt(now)
        return (-SKEW_STEPS..SKEW_STEPS)
            .map { current + it }
            .firstOrNull { step -> equal(code(secret, step), typed) }
    }

    /**
     * The `otpauth://` URI an authenticator can take instead of thirty-two typed characters.
     *
     * The label is `issuer:username` and the issuer is repeated as a parameter, which is what
     * Google Authenticator's Key URI format asks for and what every app that reads one expects.
     * `algorithm`, `digits` and `period` are written out even though all three are the defaults:
     * an app that ignores them loses nothing, and an app that reads them cannot get them wrong.
     *
     * **Secret**, as [issueSecret]'s output is — this string contains it.
     */
    fun uri(issuer: String, username: String, secret: String): String {
        val label = "${encode(issuer)}:${encode(username)}"
        return "otpauth://totp/$label?secret=$secret&issuer=${encode(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    /**
     * RFC 4226 § 5.3's dynamic truncation: the low nibble of the last byte picks where to read a
     * 31-bit integer from, and the modulus takes its last [DIGITS] digits.
     *
     * The offset is what stops the code being a fixed slice of the HMAC, which is the property the
     * whole construction rests on.
     */
    // The numbers here are the specification's, and naming them would be inventing vocabulary for
    // something a reader has to check against RFC 4226 anyway: `0x0F` is "the low nibble", `0x7F`
    // is "clear the sign bit", and the shifts are a four-byte big-endian read. A constant called
    // `LOW_NIBBLE_MASK` would say less than the line it replaced.
    @Suppress("MagicNumber")
    private fun truncate(digest: ByteArray): Int {
        val offset = digest[digest.size - 1].toInt() and 0x0F
        val binary = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        return binary % MODULUS
    }

    private fun equal(expected: String, typed: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        typed.toByteArray(Charsets.UTF_8),
    )

    /** Enough for a label, and deliberately not a general-purpose URL encoder. */
    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    /**
     * `HmacSHA1`, and it is not a lapse.
     *
     * SHA-1 is broken for collisions and HMAC-SHA1 is not affected by that: the attack is on
     * finding two messages with one digest, and this construction needs neither collision
     * resistance nor a message an attacker chooses. What it needs is a pseudo-random function under
     * a key nobody has, which HMAC-SHA1 remains. The practical argument is stronger still — an
     * authenticator app handed a URI with `algorithm=SHA256` may or may not honour it, and a second
     * factor that works on one operator's phone and not another's is worse than a cipher choice
     * somebody will raise an eyebrow at.
     */
    private const val ALGORITHM = "HmacSHA1"

    /**
     * 160 bits — one HMAC-SHA1 block, and 32 base32 characters with nothing left over.
     *
     * RFC 4226 § 4 requires at least 128 and recommends 160. It is also the length every published
     * test vector uses, which is not why it was chosen but is why nothing surprising happens.
     */
    private const val SECRET_BYTES = 20

    /** One step either side of the present. See [matchingStep]. */
    private const val SKEW_STEPS = 1

    /** The counter is eight bytes, big-endian, per RFC 4226 § 5.1. */
    private const val COUNTER_BYTES = 8

    private const val MODULUS = 1_000_000

    private const val MILLIS_PER_SECOND = 1_000L

    /** Shared: `SecureRandom` is thread-safe and expensive to construct. See [Tokens]. */
    private val random = SecureRandom()
}

/**
 * Base32 as RFC 4648 defines it, with no padding — because that is the shape an authenticator
 * expects a key to be typed in.
 *
 * ### Why not `java.util.Base64`, or any of the JDK
 *
 * Because the JDK has no base32. That is the whole of the reason, and it is worth writing down so
 * that nobody spends an afternoon looking for the class this file must have missed.
 *
 * ### Why base32 at all, when the token in `sessions` is base64
 *
 * A session token is copied by a program. This one is read off a screen and typed into a phone by a
 * person, and base32's alphabet is chosen for exactly that: upper case only, no padding characters
 * in the middle, and no `0`/`O` or `1`/`l` to confuse. It is also what every authenticator app's
 * manual-entry field accepts, which settles it regardless of taste.
 */
private object Base32 {

    /** RFC 4648 § 6, and the reason it excludes `0`, `1` and `8`. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private const val BITS_PER_CHARACTER = 5

    private const val BITS_PER_BYTE = 8

    private const val MASK = 0x1F

    private const val BYTE_MASK = 0xFF

    /**
     * [key] as base32. No padding: every key this server issues is 20 bytes, which is a whole
     * number of characters, and an authenticator's manual-entry field would rather not be given
     * `=` signs to type.
     */
    fun encode(key: ByteArray): String {
        val text = StringBuilder()
        var buffer = 0
        var bits = 0
        key.forEach { byte ->
            buffer = (buffer shl BITS_PER_BYTE) or (byte.toInt() and BYTE_MASK)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_CHARACTER) {
                bits -= BITS_PER_CHARACTER
                text.append(ALPHABET[(buffer shr bits) and MASK])
            }
        }
        // The tail: fewer than five bits left, padded with zeros on the right rather than dropped.
        // Unreachable for a 20-byte key and written anyway, because a decoder that silently loses
        // the last character of a key somebody typed is a bug nobody would find from the symptom.
        if (bits > 0) text.append(ALPHABET[(buffer shl (BITS_PER_CHARACTER - bits)) and MASK])
        return text.toString()
    }

    /**
     * [text] as bytes, or null when it is not base32 at all.
     *
     * Whitespace and `=` are **ignored**, and lower case is accepted. The console shows the key in
     * groups of four to be read accurately, and an operator who copies it back with the spaces in
     * should not be told their key is malformed — nor should one whose phone offered to lower-case
     * it. Anything else is null rather than an exception: this parses a value from a database
     * column and from nothing else, and a row that has been corrupted is a sign-in to refuse rather
     * than a 500 to serve.
     */
    fun decode(text: String): ByteArray? {
        val bytes = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0
        text.uppercase().forEach { character ->
            if (character.isWhitespace() || character == '=') return@forEach
            val value = ALPHABET.indexOf(character)
            if (value < 0) return null
            buffer = (buffer shl BITS_PER_CHARACTER) or value
            bits += BITS_PER_CHARACTER
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE
                bytes.add(((buffer shr bits) and BYTE_MASK).toByte())
            }
        }
        return if (bytes.isEmpty()) null else bytes.toByteArray()
    }
}
