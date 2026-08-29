package com.tripletriad.server

import com.tripletriad.protocol.AccountError
import com.tripletriad.protocol.AccountFailure
import com.tripletriad.protocol.Unlocks
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext

/**
 * Authenticates, and then answers whether this account may **start** refereed play.
 *
 * ### Why the check is here at all, when the client already greys the door
 *
 * Because a client is not a check. The lobby draws a padlock and the level beside it as a courtesy,
 * so a player is told rather than refused; this is what actually holds, and it is the only one of
 * the two an account farmer has to get past.
 *
 * ### Two conditions and one of them is the address
 *
 * The level makes a second account cost an evening. The confirmed address makes it cost an inbox.
 * Neither is much on its own and neither is claimed to be — see [Unlocks] on what a threshold can
 * honestly buy — but they compose: a farmer needs both the time and the addresses, per account.
 *
 * Grandfathered accounts pass the second on a null address, deliberately. See `V13`: an account
 * created before this existed has no address and no way to supply one retroactively, and locking
 * those players out to close a gap they did not open would be punishing them for a schema change.
 *
 * ### Why only the four endpoints that *start* a match
 *
 * A match already under way has to be finishable. If a threshold is raised while two people are
 * playing, the answer is that they finish; gating `/pvp/match` as well would strand a live board
 * and hand one side a win nobody played for.
 */
suspend fun RoutingContext.authenticateUnlocked(accounts: AccountStore, unlocks: Unlocks): Long? {
    val accountId = authenticate(accounts) ?: return null

    val refusal = refuseUnlock(accounts, accountId, unlocks) ?: return accountId
    call.respond(HttpStatusCode.Forbidden, refusal)
    return null
}

/** Why this account may not start refereed play, or null when it may. */
private fun refuseUnlock(
    accounts: AccountStore,
    accountId: Long,
    unlocks: Unlocks,
): AccountFailure? {
    val identity = accounts.identity(accountId)
    val save = accounts.saveFor(accountId)

    // Read as a table rather than as a run of early returns, because that is what it is: two
    // independent conditions, and the order between them is a presentation choice. Confirming an
    // address is the one a player can act on immediately, so it is named first.
    return when {
        identity != null && !identity.verified -> AccountFailure(
            AccountError.EMAIL_UNVERIFIED,
            "confirm your email address before playing against other people",
        )

        save != null && !unlocks.allowsMultiplayer(save) -> AccountFailure(
            AccountError.NOT_UNLOCKED,
            "multiplayer unlocks at level ${unlocks.multiplayer}",
        )

        else -> null
    }
}
