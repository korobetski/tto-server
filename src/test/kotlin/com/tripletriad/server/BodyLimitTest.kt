package com.tripletriad.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The body a caller may send, and the two ways of refusing one.
 *
 * `POST /matches/verify` is the endpoint these run against on purpose: it is unauthenticated and
 * deliberately unthrottled, so it is the one place where the only thing between a stranger and this
 * process's heap is the number in [DEFAULT_MAX_BODY_BYTES]. If the refusal ever stops happening
 * before the body is parsed, this is where it shows.
 *
 * No database — the refusal happens in the pipeline, before a route is chosen, which is itself part
 * of what is being asserted.
 */
class BodyLimitTest {

    @Test
    fun aDeclaredLengthOverTheLimitIsRefusedWithoutBeingRead() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            // Deliberately *valid-looking* JSON, and deliberately larger than the limit. A body
            // that would parse proves the refusal is about the size rather than about the content:
            // this must not reach the parser at all.
            setBody("""{"moves":[${"0,".repeat(200_000)}0]}""")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status, response.bodyAsText())
        assertTrue(
            "body_too_large" in response.bodyAsText(),
            "the refusal must name itself, got: ${response.bodyAsText()}",
        )
    }

    @Test
    fun aBodyThatDoesNotSayHowLongItIsRefusedRatherThanRead() = withRealServer { port ->
        // Ktor's test host does not frame requests, and its client refuses to set
        // `Transfer-Encoding` — it is "controlled by the engine". So this one talks to a real
        // Netty on a real socket and writes the request by hand, which is the only way to ask the
        // question the attack asks.
        //
        // Note what is *not* sent: any body at all. The headers announce a chunked body and then
        // the connection goes quiet. A server that answers this has refused without reading; a
        // server that waits is the one an attacker holds open while streaming.
        val reply = request(
            port,
            "POST /matches/verify HTTP/1.1",
            "Host: localhost",
            "Content-Type: application/json",
            "Transfer-Encoding: chunked",
        )

        assertTrue(
            reply.startsWith("HTTP/1.1 411"),
            "expected 411 Length Required without the body being read, got: $reply",
        )
    }

    @Test
    fun aDeclaredLengthOverTheLimitIsRefusedOverRealHttpToo() = withRealServer { port ->
        // The same refusal as the first test, over a socket rather than the test host — because
        // the first test proves the plugin runs and this one proves it runs *before* Netty has
        // read the ${DEFAULT_MAX_BODY_BYTES + 1} bytes the caller promised. None are sent.
        val reply = request(
            port,
            "POST /matches/verify HTTP/1.1",
            "Host: localhost",
            "Content-Type: application/json",
            "Content-Length: ${DEFAULT_MAX_BODY_BYTES + 1}",
        )

        assertTrue(
            reply.startsWith("HTTP/1.1 413"),
            "expected 413 without the promised body arriving, got: $reply",
        )
    }

    @Test
    fun anOrdinaryBodyIsUntouched() = testApplication {
        application { module(UnreachableDataSource, prometheusRegistry()) }

        val response = client.post("/matches/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"not":"a transcript"}""")
        }

        // 426 or 400 — whichever the version gate and the parser make of it. What matters is that
        // it is neither of this plugin's refusals: an ordinary body reaches the handlers.
        assertTrue(
            response.status != HttpStatusCode.PayloadTooLarge &&
                response.status != HttpStatusCode.LengthRequired,
            "an ordinary body must not be refused for its size, got ${response.status}",
        )
    }

    /**
     * Runs [block] against a real Netty on an ephemeral port.
     *
     * Heavier than `testApplication` and used only where the question is about HTTP framing, which
     * the test host does not model. Port 0 rather than a fixed one so two of these can run at once
     * and neither picks a port something else is on.
     */
    private fun withRealServer(block: (Int) -> Unit) {
        val server = embeddedServer(Netty, port = 0) {
            module(UnreachableDataSource, prometheusRegistry())
        }
        server.start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            // No grace period: nothing here holds a connection worth draining, and a test that
            // waits for one is a test that hangs when the thing it is testing is broken.
            server.stop(0, 0)
        }
    }

    /**
     * Writes [lines] as a request head, sends nothing else, and returns the first line of the
     * answer.
     *
     * The read timeout is the assertion that matters as much as the status: without it, a server
     * that decided to wait for the body it was promised would hang this test rather than fail it.
     */
    private fun request(port: Int, vararg lines: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            // ISO-8859-1 is what an HTTP head is defined in, and everything written here is ASCII.
            out.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.flush()
            socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
        }

    private companion object {
        /** Long enough for a JVM under load, short enough that a hang is a failed test. */
        const val REPLY_TIMEOUT_MILLIS = 10_000
    }
}
