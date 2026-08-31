package com.tripletriad.server

import com.tripletriad.protocol.PvpRefusal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * A refusal, and the status it travels under.
 *
 * Pulled out of the route bodies rather than written inline at each `when`. Four exhaustive `when`s
 * over four sealed hierarchies is most of what a route function *is*, and inlining them pushed both
 * `tableRoutes` and `liveMatchRoutes` past the complexity gate — for a reason the gate is right
 * about, since the interesting part of a route was buried in a list of status codes. What is left
 * above reads as "the happy path, or a refusal"; what is here is the table of refusals.
 */
data class Rejected(val status: HttpStatusCode, val code: PvpRefusal, val reason: String)

internal suspend fun ApplicationCall.refuse(rejected: Rejected) =
    respond(rejected.status, Refusal(rejected.code, rejected.reason))

internal fun Tabled.refusal(): Rejected = when (this) {
    is Tabled.Opened -> error("an opened table is not a refusal")
    Tabled.NoSuchFormat ->
        Rejected(HttpStatusCode.BadRequest, PvpRefusal.NO_SUCH_FORMAT, "no such format")
    Tabled.RulesNotAllowed -> Rejected(
        HttpStatusCode.BadRequest,
        PvpRefusal.RULES_NOT_ALLOWED,
        "that format does not allow those rules",
    )
    Tabled.CannotAfford ->
        Rejected(HttpStatusCode.Conflict, PvpRefusal.CANNOT_AFFORD, "you cannot cover that stake")
    Tabled.StakeTooHigh -> Rejected(
        HttpStatusCode.Conflict,
        PvpRefusal.STAKE_TOO_HIGH,
        "that stake is above the limit for your level",
    )
    Tabled.AlreadyWaiting -> Rejected(
        HttpStatusCode.Conflict,
        PvpRefusal.ALREADY_WAITING,
        "you already have a table open",
    )
    Tabled.AlreadyPlaying -> Rejected(
        HttpStatusCode.Conflict,
        PvpRefusal.ALREADY_PLAYING,
        "you are already in a match",
    )
}

internal fun Joined.refusal(): Rejected = when (this) {
    is Joined.Playing -> error("an opened match is not a refusal")
    Joined.NoSuchTable -> Rejected(
        HttpStatusCode.NotFound,
        PvpRefusal.TABLE_GONE,
        "that table is no longer open",
    )
    Joined.CannotAfford ->
        Rejected(HttpStatusCode.Conflict, PvpRefusal.CANNOT_AFFORD, "you cannot cover that stake")
    Joined.StakeTooHigh -> Rejected(
        HttpStatusCode.Conflict,
        PvpRefusal.STAKE_TOO_HIGH,
        "that stake is above the limit for your level",
    )
    Joined.AlreadyPlaying -> Rejected(
        HttpStatusCode.Conflict,
        PvpRefusal.ALREADY_PLAYING,
        "you are already in a match",
    )
}

internal fun Played.refusal(): Rejected = when (this) {
    is Played.Accepted -> error("an accepted move is not a refusal")
    Played.NoSuchMatch ->
        Rejected(HttpStatusCode.NotFound, PvpRefusal.NO_SUCH_MATCH, "no such match")
    Played.NotYourTurn ->
        Rejected(HttpStatusCode.Conflict, PvpRefusal.NOT_YOUR_TURN, "it is not your turn")
    Played.IllegalMove ->
        Rejected(HttpStatusCode.Conflict, PvpRefusal.ILLEGAL_MOVE, "that move is not allowed")
}

internal fun Claimed.refusal(): Rejected = when (this) {
    is Claimed.Settled -> error("a settled claim is not a refusal")
    Claimed.NoSuchMatch ->
        Rejected(HttpStatusCode.NotFound, PvpRefusal.NO_SUCH_MATCH, "no such match")
    Claimed.NothingOwed ->
        Rejected(HttpStatusCode.Conflict, PvpRefusal.NOTHING_OWED, "there is nothing to claim")
    Claimed.NotTheirs -> Rejected(
        HttpStatusCode.Conflict,
        PvpRefusal.NOT_THEIRS,
        "that is not one of their cards",
    )
}
