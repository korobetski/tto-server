package com.tripletriad.server

import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext

/**
 * Who is calling, established from the bearer token.
 *
 * ### Why this is a function and not Ktor's `Authentication` plugin
 *
 * The plugin is the right answer as soon as there are several schemes, several realms, or routes
 * that differ in whether they *may* be authenticated. There is one scheme and one realm, and what
 * the plugin would add here is a configuration block, a principal type and an indirection between
 * a route and the check it performs — for something that is nine lines and reads in one pass.
 *
 * The moment a second scheme appears (a service token, an OAuth exchange), this stops being true.
 */
suspend fun RoutingContext.authenticate(store: AccountStore): Long? {
    val token = call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // The token is fingerprinted before it is looked up and is never logged, never echoed, and
    // never put in an error message. It is exactly as good as the password for as long as it lives.
    val accountId = token?.let { store.accountForToken(Tokens.fingerprint(it)) }
    if (accountId == null) {
        call.respond(
            HttpStatusCode.Unauthorized,
            AccountFailure(
                AccountError.UNAUTHENTICATED,
                "sign in again: this session is unknown or has expired",
            ),
        )
    }
    return accountId
}

/**
 * The token as sent, without the scheme — for the routes that need to *revoke* rather than verify.
 *
 * Sign-out is the odd one out: it needs the token itself to fingerprint the row to delete, and it
 * must work even for a token the server no longer accepts. Reporting "your expired session could
 * not be ended" would be an error message about nothing.
 */
fun RoutingContext.bearerToken(): String? = call.request.headers[HttpHeaders.Authorization]
    ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
    ?.substring(BEARER_PREFIX.length)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private const val BEARER_PREFIX = "Bearer "
