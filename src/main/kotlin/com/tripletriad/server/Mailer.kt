package com.tripletriad.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sending one short message to one address, and nothing else.
 *
 * ### Why this is an interface with two implementations rather than a function
 *
 * Because the flows that need it must be buildable and testable without a mail account. Every test
 * in this repository runs against a real Postgres and no network, and a registration that could not
 * complete without reaching a third party would be a registration nobody could test. [Disabled] is
 * what the suite and a developer's laptop use; [Brevo] is what production uses.
 *
 * ### Why nothing here throws
 *
 * A mail that does not go out must never fail the request that triggered it. An account whose
 * confirmation mail was lost is an account that asks for another one and carries on; an account
 * whose *creation* failed because a third party was down is a player who cannot play. So [send]
 * reports and returns, and every caller is written to treat sending as best-effort.
 *
 * ### Why the JDK's client and not Ktor's
 *
 * One POST to one URL, once in a while. `java.net.http.HttpClient` has shipped in the JDK since 11
 * and does it; adding a Ktor client to this module would be a dependency, a serialization
 * configuration and an engine choice, for the sake of code that would look the same.
 */
interface Mailer {

    /**
     * @return whether the provider accepted it — which is not whether it was delivered. Nothing
     *   here can know that, and a caller that treated `true` as delivery would be wrong in the one
     *   case that matters, an address that does not exist.
     */
    suspend fun send(to: String, message: MailMessage): Boolean

    /**
     * The one used when no provider is configured: it writes the message to the log and stops.
     *
     * **Development only, and enforced as such** — see `ServerConfig`, which refuses to boot a
     * production deployment that resolves to this. That refusal is the whole reason it is safe:
     * the log line contains the code, which is a credential, and a production log holding one
     * would be a way in that never touched an inbox.
     *
     * A developer needs the code to finish the flow on a laptop, and there is no inbox there. This
     * is how they get it, and the boot check is why it cannot follow them to a real deployment.
     *
     * ### Why the recipient is not in the line
     *
     * It was, and `LogSecrecyTest` caught it. The code is a credential that expires in ten minutes
     * and says nothing on its own; the address is personal data that does not expire, and pairing
     * the two in a file is the record `docs/data-inventory.md` says the logs do not keep — every
     * other line in this server names an account **id** for that reason. Dropping the recipient
     * costs a developer nothing: on a laptop, the code they are waiting for is the last one
     * printed.
     */
    object Disabled : Mailer {
        private val log = LoggerFactory.getLogger("com.tripletriad.server.Mailer")

        override suspend fun send(to: String, message: MailMessage): Boolean {
            log.warn(
                "No mail provider configured; a message was not sent. Subject: {}\n{}",
                message.subject,
                message.body,
            )
            return false
        }
    }

    /**
     * Brevo's transactional endpoint.
     *
     * Chosen over sending from this host directly, which is the tempting option and the wrong one:
     * the VPS this runs on sits in a hosting provider's address space, outbound port 25 is
     * restricted there, and the reputation of the range means even a correctly configured sender
     * lands in spam. A password-reset mail in a spam folder is a locked-out player.
     *
     * The HTTP API rather than their SMTP relay, for the same reason turned around: port 443 is
     * never the port anybody blocks.
     *
     * @param apiKey **secret**. Never logged, never echoed, and read from configuration only.
     * @param from the envelope sender. Wants to be on a subdomain that sends nothing else, so that
     *   this traffic's reputation is its own.
     */
    class Brevo(
        private val apiKey: String,
        private val from: String,
        private val senderName: String,
        private val client: HttpClient = defaultClient(),
    ) : Mailer {

        override suspend fun send(to: String, message: MailMessage): Boolean =
            withContext(Dispatchers.IO) {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(REQUEST_TIMEOUT)
                    .header("api-key", apiKey)
                    .header("content-type", "application/json")
                    .header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(to, message)))
                    .build()

                try {
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    val accepted = response.statusCode() in ACCEPTED
                    if (!accepted) {
                        // The status and nothing else. A provider's error body can quote the
                        // request back, and the request contains an address — see
                        // `docs/data-inventory.md` on what may reach a log.
                        log.error("Brevo refused a message with status {}", response.statusCode())
                    }
                    accepted
                } catch (failure: java.io.IOException) {
                    log.error("Could not reach Brevo: {}", failure.message)
                    false
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.error("Interrupted while sending mail: {}", failure.message)
                    false
                }
            }

        /**
         * Hand-built JSON, which is worth a sentence.
         *
         * The payload is four strings and one of them is attacker-supplied — [to] is whatever the
         * player typed. So it is *encoded*, by `kotlinx.serialization`'s own string encoder rather
         * than by concatenation, because a hand-quoted address containing a quote character is how
         * this becomes a way of writing arbitrary fields into somebody else's API request.
         */
        private fun body(to: String, message: MailMessage): String = buildString {
            append("""{"sender":{"email":""").append(quote(from))
            append(""","name":""").append(quote(senderName))
            append("""},"to":[{"email":""").append(quote(to))
            append("""}],"subject":""").append(quote(message.subject))
            append(""","textContent":""").append(quote(message.body))
            append("}")
        }

        private fun quote(value: String): String = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive(value),
        )

        private companion object {
            const val ENDPOINT = "https://api.brevo.com/v3/smtp/email"

            val ACCEPTED = 200..299

            val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

            val log = LoggerFactory.getLogger(Brevo::class.java)

            val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

            fun defaultClient(): HttpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build()
        }
    }
}

/** Subject and body, plain text. No HTML: these are four lines and a number. */
data class MailMessage(val subject: String, val body: String)
