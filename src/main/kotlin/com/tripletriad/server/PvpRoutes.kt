package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.Format
import com.tripletriad.data.FormatCatalog
import com.tripletriad.data.MatchRewards
import com.tripletriad.data.PveMatches
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.MatchPreparation
import com.tripletriad.model.Roulette
import com.tripletriad.protocol.ANY_DECK
import com.tripletriad.protocol.PvpChallenge
import com.tripletriad.protocol.PvpClaim
import com.tripletriad.protocol.PvpJoinRequest
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMatchView
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpOutcome
import com.tripletriad.protocol.PvpQueueState
import com.tripletriad.protocol.PvpRefusal
import com.tripletriad.protocol.PvpStake
import com.tripletriad.protocol.PvpTable
import com.tripletriad.protocol.PvpTableRequest
import com.tripletriad.protocol.Unlocks
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Player versus player: finding an opponent, taking a turn, and being paid for it.
 *
 * ### The server is the referee
 *
 * Every route here reads from a state the **server** holds and answers with a
 * [com.tripletriad.model.MatchView] — what the asking player is entitled to see. No endpoint ever
 * returns the opponent's hand, and none accepts a board: a client offers a slot and a square, and
 * everything else is computed here. That is the whole difference from the PvE path, where the
 * client plays the match and submits a transcript afterwards.
 *
 * It is not fastidiousness. If each client held both hands, "do not look" would be the only thing
 * protecting a player's cards, and a modified client would see everything with nothing in any
 * transcript to show for it.
 *
 * ### Polling, not sockets
 *
 * Nothing in this server uses a websocket, and a turn-based card game does not need one. Two
 * players alternating placements with a second or two of latency are playing Triple Triad, not a
 * shooter. `GET /pvp/match` is the whole channel.
 *
 * ### Every handler here calls [requireCompatibleClient] first
 *
 * It is the rule the rest of the server keeps and this file did not keep at all — thirteen
 * handlers, no gate on any of them, on the one surface where a wager is agreed and cards change
 * hands. `POST /pvp/match/{id}/claim` reads a body that names **which of the loser's cards are
 * taken**, and `POST /pvp/tables` reads one that names what is staked; a major-version mismatch is
 * exactly the case where this build may misread those, and reading them anyway was doing the one
 * thing the version number had just said not to do.
 *
 * On the `GET`s as well as the bodies, for the same reason `AccountRoutes` gates `GET /me`: a
 * client too old to be talked to should learn that from the first thing it asks, not from whichever
 * request happens to carry a body. `/server` stays ungated and remains the place a refused client
 * finds out why — see `ServerRoutes`.
 */
// Six collaborators and a clock, and they are one decision: everything a refereed match needs.
// Bundling them behind a type would put a wrapper between `Application.module` and the routes for
// the sake of a counter.
@Suppress("LongParameterList")
fun Route.pvpRoutes(
    cards: CardCatalog,
    formats: FormatCatalog,
    accounts: AccountStore,
    pvp: PvpStore,
    clock: () -> Long = System::currentTimeMillis,
    random: () -> Random = { Random.Default },
    // Defaulted so every existing test keeps its call shape. A deployment passes its own — see
    // `ServerConfig.unlocksFrom`, and `Unlocks` for why the number travels rather than being
    // compiled in.
    unlocks: Unlocks = Unlocks(),
) {
    val referee = PvpReferee(cards, formats, accounts, pvp, clock, random)

    route("/pvp") {
        lobbyRoutes(referee, accounts, pvp, clock, unlocks)
        liveMatchRoutes(referee, accounts)
    }
}

private fun Route.lobbyRoutes(
    referee: PvpReferee,
    accounts: AccountStore,
    pvp: PvpStore,
    clock: () -> Long,
    unlocks: Unlocks,
) {
    challengeRoutes(referee, accounts, pvp, clock, unlocks)
    tableRoutes(referee, accounts, pvp, clock, unlocks)
}

/**
 * The lobby: opening a table, listing them, and joining one.
 *
 * ### What replaced the quick queue, and why
 *
 * There used to be one endpoint here — `POST /pvp/queue` — that both joined a queue and took
 * whoever was in it, on the argument that from the player's side those are one action and that a
 * client cannot choose between them without seeing a queue it has no business seeing.
 *
 * That argument held while every match was the same match. It fails the moment a match has *terms*:
 * a player paired into a wager they never saw has not agreed to it, and no amount of one-endpoint
 * convenience makes that acceptable. So the queue nobody could see became a list everybody can, the
 * client picks a table it has read, and the decision it could not make is now the only one it
 * makes.
 */
private fun Route.tableRoutes(
    referee: PvpReferee,
    accounts: AccountStore,
    pvp: PvpStore,
    clock: () -> Long,
    unlocks: Unlocks,
) {
    /** Every table still open, the caller's own included — they need to see it to withdraw it. */
    get("/tables") {
        if (!requireCompatibleClient()) return@get
        authenticate(accounts) ?: return@get
        call.respond(HttpStatusCode.OK, pvp.openTables(clock()).map { it.toWire() })
    }

    /**
     * Opens one. The terms are the host's and are public from this moment.
     *
     * Throttled because it is cheap for the host and visible to everybody: a loop here fills the
     * lobby faster than anyone can read it, and no other player has a way to opt out of seeing it.
     * Withdrawing is not throttled — a player must always be able to stop advertising.
     */
    rateLimit(RateLimitName(LOBBY)) {
        post("/tables") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticateUnlocked(accounts, unlocks) ?: return@post
            val request = call.receive<PvpTableRequest>()

            when (val outcome = referee.openTable(accountId, request)) {
                is Tabled.Opened -> call.respond(HttpStatusCode.Created, outcome.table)
                else -> call.refuse(outcome.refusal())
            }
        }
    }

    tableAnswerRoutes(referee, accounts, pvp, unlocks)
}

/**
 * Answering a table: withdrawing your own, or sitting down at somebody else's.
 *
 * The same split [challengeAnswerRoutes] makes, along the same line and for the same reason —
 * advertising and answering are two players' halves of one screen, and each function is short
 * enough to read whole.
 */
private fun Route.tableAnswerRoutes(
    referee: PvpReferee,
    accounts: AccountStore,
    pvp: PvpStore,
    unlocks: Unlocks,
) {
    /** Withdraws it. Harmless if it is already gone — the host wanted to not be waiting. */
    delete("/tables/{id}") {
        if (!requireCompatibleClient()) return@delete
        val accountId = authenticate(accounts) ?: return@delete
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        pvp.dropTable(id, accountId)
        call.respond(HttpStatusCode.OK)
    }

    /**
     * Joins one, which opens the match.
     *
     * Answers with the shape `challenges/{id}/accept` answers with, so a client has one code path
     * for "a match now exists and here is its id" rather than two that differ for no reason.
     *
     * The body says which deck the joiner brings, and is optional — see [seatedDeck].
     */
    post("/tables/{id}/join") {
        if (!requireCompatibleClient()) return@post
        val accountId = authenticateUnlocked(accounts, unlocks) ?: return@post
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        when (val outcome = referee.joinTable(id, accountId, call.seatedDeck())) {
            is Joined.Playing ->
                call.respond(
                    HttpStatusCode.Created,
                    PvpQueueState(waiting = false, matchId = outcome.match.id),
                )
            else -> call.refuse(outcome.refusal())
        }
    }
}

/** Inviting a named player, and answering an invitation. */
private fun Route.challengeRoutes(
    referee: PvpReferee,
    accounts: AccountStore,
    pvp: PvpStore,
    clock: () -> Long,
    unlocks: Unlocks,
) {
    /** The invitations standing either way, so one screen can show both. */
    get("/challenges") {
        if (!requireCompatibleClient()) return@get
        val accountId = authenticate(accounts) ?: return@get
        call.respond(pvp.challengesFor(accountId, clock()).map { it.toWire() })
    }

    /**
     * Invites a named player.
     *
     * The wager is named in full here — both cards — so the other side can see what they are
     * risking *and* what they stand to win before agreeing. An offer naming only the
     * challenger's card is one the recipient cannot evaluate.
     */
    rateLimit(RateLimitName(LOBBY)) {
        post("/challenges") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticateUnlocked(accounts, unlocks) ?: return@post
            val request = call.receive<ChallengeRequest>()

            when (val outcome = referee.challenge(accountId, request)) {
                is Challenged.Sent -> call.respond(HttpStatusCode.Created, outcome.challenge)
                Challenged.NoSuchPlayer ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        Refusal(PvpRefusal.NO_SUCH_PLAYER, "no such player"),
                    )
                Challenged.Yourself ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        Refusal(PvpRefusal.YOURSELF, "you cannot challenge yourself"),
                    )
                Challenged.CannotAfford ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        Refusal(PvpRefusal.CANNOT_AFFORD, "you cannot cover that stake"),
                    )
                Challenged.BadTerms ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        Refusal(
                            PvpRefusal.RULES_NOT_ALLOWED,
                            "those are not terms anybody can play",
                        ),
                    )
            }
        }
    }

    /** Accepts an invitation, which opens the match. The body names a deck, as joining does. */

    challengeAnswerRoutes(referee, accounts, pvp, unlocks)
}

/**
 * Answering an invitation: taking it, or refusing it.
 *
 * Split from the half that *sends* one because they belong to different players. Everything above
 * is the challenger's side of the screen and everything here is the recipient's, and the split
 * keeps each function short enough to read in one pass — which is the same line `tableRoutes` and
 * [liveMatchRoutes] are drawn along.
 */
private fun Route.challengeAnswerRoutes(
    referee: PvpReferee,
    accounts: AccountStore,
    pvp: PvpStore,
    unlocks: Unlocks,
) {
    post("/challenges/{id}/accept") {
        if (!requireCompatibleClient()) return@post
        val accountId = authenticateUnlocked(accounts, unlocks) ?: return@post
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        when (val outcome = referee.accept(id, accountId, call.seatedDeck())) {
            is Joined.Playing ->
                call.respond(
                    HttpStatusCode.Created,
                    PvpQueueState(false, matchId = outcome.match.id),
                )

            // Worded for an invitation rather than for a table, which is the one place these two
            // doors say different things. `Joined.refusal()` would call it a table.
            Joined.NoSuchTable -> call.respond(
                HttpStatusCode.Conflict,
                Refusal(PvpRefusal.TABLE_GONE, "that invitation is no longer open"),
            )

            else -> call.refuse(outcome.refusal())
        }
    }

    /** Declines an invitation, or withdraws one. */
    delete("/challenges/{id}") {
        if (!requireCompatibleClient()) return@delete
        val accountId = authenticate(accounts) ?: return@delete
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        pvp.dropChallenge(id, accountId)
        call.respond(HttpStatusCode.OK)
    }
}

/** Playing: the match in progress, a placement, and conceding. */
private fun Route.liveMatchRoutes(referee: PvpReferee, accounts: AccountStore) {
    claimRoutes(referee, accounts)

    /**
     * The match in progress, as this player may see it.
     *
     * Also the answer to "did my match survive the app being killed?", which is why it takes no
     * id: the client asks who it is and the server says what it is doing. On a phone the system
     * kills applications without asking and the player did not choose to leave.
     *
     * The overdue check runs here rather than on a timer, so a forfeit is settled by the first
     * person who looks — which in a two-player match is almost always somebody, quickly. The
     * background sweep in `Application.sweepAbandonedMatches` covers the case where nobody does.
     * It is a net rather than the mechanism, and until this release it did not exist at all,
     * despite this comment having always claimed it did.
     */
    get("/match") {
        if (!requireCompatibleClient()) return@get
        val accountId = authenticate(accounts) ?: return@get
        val view = referee.currentView(accountId)

        if (view == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.OK, view)
        }
    }

    /**
     * Places a card, concedes, or collects.
     *
     * All three under [PLAY], which is one bucket rather than three because they are one thing: the
     * ways a player acts on a match that is already open. `GET /pvp/match` is outside it on purpose
     * — it is the poll this design uses instead of a websocket, and throttling it would throttle
     * watching your opponent think.
     */
    rateLimit(RateLimitName(PLAY)) {
        /** Places a card. */
        post("/match/{id}/move") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticate(accounts) ?: return@post
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val move = call.receive<PvpMove>()

            when (val played = referee.play(id, accountId, move)) {
                is Played.Accepted -> call.respond(HttpStatusCode.OK, played.view)
                else -> call.refuse(played.refusal())
            }
        }

        /** Concedes. The same settlement a timeout produces, chosen rather than suffered. */
        post("/match/{id}/forfeit") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticate(accounts) ?: return@post
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)

            val view = referee.forfeit(id, accountId)
            if (view == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    Refusal(PvpRefusal.NO_SUCH_MATCH, "no such match"),
                )
            } else {
                call.respond(HttpStatusCode.OK, view)
            }
        }
    }
}

/**
 * Collecting a prize: what is owed, and naming it.
 *
 * Its own function rather than two more routes in [liveMatchRoutes], and the line is the same one
 * that separates the lobby from the board: those routes are a match being *played*, these are one
 * being *paid*. A match reaches these having already finished.
 */
private fun Route.claimRoutes(referee: PvpReferee, accounts: AccountStore) {
    /**
     * Everything this player has won and not yet collected.
     *
     * Not decoration on top of `GET /pvp/match`. That one is `ORDER BY created_at DESC LIMIT 1`, so
     * a winner who starts another game before claiming would have the unclaimed match hidden behind
     * the new one — and would lose the prize when the deadline passed. This is the list that cannot
     * hide anything.
     */
    get("/claims") {
        if (!requireCompatibleClient()) return@get
        val accountId = authenticate(accounts) ?: return@get
        call.respond(HttpStatusCode.OK, referee.claims(accountId))
    }

    /**
     * Names the cards taken, under One or Diff.
     *
     * The one place a client tells the server which cards change hands, so it is also the one place
     * a client could try to take a card that was never at stake. Every id is checked against the
     * loser's dealt hand, counted with multiplicity.
     */
    rateLimit(RateLimitName(PLAY)) {
        post("/match/{id}/claim") {
            if (!requireCompatibleClient()) return@post
            val accountId = authenticate(accounts) ?: return@post
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val claim = call.receive<PvpClaim>()

            when (val outcome = referee.claim(id, accountId, claim)) {
                is Claimed.Settled -> call.respond(HttpStatusCode.OK, outcome.view)
                else -> call.refuse(outcome.refusal())
            }
        }
    }
}

/**
 * Which deck the caller is sitting down with, and [ANY_DECK] when they did not say.
 *
 * Tolerant of a missing or unreadable body on purpose, and it is the only place in these routes
 * that is. Joining a table and accepting an invitation both took no body at all until decks could
 * be chosen, so a client built against the older protocol posts nothing here — and the version gate
 * will not stop it, because adding an optional field is a minor and a minor is not a refusal. The
 * honest reading of "said nothing" is that they made no choice, which lands on the deal they would
 * have got anyway.
 *
 * That reasoning is about **minors**, and it was quietly doing duty for majors too: the gate it
 * says will not stop an old client was not being called here at all. It is now, at the top of both
 * handlers. The tolerance below is unchanged and still correct — a minor still is not a refusal —
 * but it is now tolerance of a client this build has agreed it can read, rather than of any client
 * at all.
 *
 * A malformed body is read the same way rather than as a 400. There is nothing a caller could put
 * here that changes whether the match may open, so refusing one would fail a request that is
 * otherwise entirely valid over a detail the server is happy to default.
 */
@Suppress("SwallowedException")
private suspend fun ApplicationCall.seatedDeck(): Int = try {
    receiveNullable<PvpJoinRequest>()?.deck ?: ANY_DECK
} catch (_: BadRequestException) {
    // Which is what an **empty** body raises, not only a malformed one: content negotiation
    // has nothing to hand the deserializer and says so in the same breath it uses for garbage.
    // Since the empty body is the compatible case this whole function exists for, the two
    // cannot be told apart here — and both mean the same thing anyway.
    ANY_DECK
}

/** What a client asks for when it wants to challenge somebody. */
@Serializable
data class ChallengeRequest(
    val username: String,
    /** What is being proposed. The same shape a table states — see [PvpChallenge.terms]. */
    val terms: PvpTableRequest,
)

/**
 * A refusal, as the code a client acts on and the sentence a human reads.
 *
 * [reason] was the whole of this and could not be shown to anybody: it is English, and the game
 * ships in four languages. See [PvpRefusal] — the code is what a client switches on, and the
 * sentence stays for logs and for whoever is reading the payload by hand.
 */
@Serializable
data class Refusal(val code: PvpRefusal, val reason: String)

/** Why a challenge did or did not go out. */
sealed interface Challenged {
    data class Sent(val challenge: PvpChallenge) : Challenged
    data object NoSuchPlayer : Challenged
    data object Yourself : Challenged
    data object CannotAfford : Challenged
    data object BadTerms : Challenged
}

/** Why a table did or did not open. */
sealed interface Tabled {
    data class Opened(val table: PvpTable) : Tabled
    data object NoSuchFormat : Tabled
    data object RulesNotAllowed : Tabled
    data object CannotAfford : Tabled
    data object AlreadyWaiting : Tabled
    data object AlreadyPlaying : Tabled
}

/** Why a join did or did not turn into a match. */
sealed interface Joined {
    data class Playing(val match: PvpMatchRow) : Joined
    data object NoSuchTable : Joined
    data object CannotAfford : Joined
    data object AlreadyPlaying : Joined
}

/** What happened to a move. */
sealed interface Played {
    data class Accepted(val view: PvpMatchView) : Played
    data object NoSuchMatch : Played
    data object NotYourTurn : Played
    data object IllegalMove : Played
}

/** What happened to a claim. */
sealed interface Claimed {
    data class Settled(val view: PvpMatchView) : Claimed
    data object NoSuchMatch : Claimed
    data object NothingOwed : Claimed
    data object NotTheirs : Claimed
}

/**
 * Everything a PvP match does between two players, with no HTTP in it.
 *
 * Split from the routes for the reason the rest of this server splits things: the rules of the game
 * should be testable without a request. What is left in the routes above is parsing, authenticating
 * and choosing a status code.
 */
// Sixteen members, and the rule is aimed at a class doing too many *things*. This one does one:
// it is the rules of a player-versus-player match with no HTTP in it. Ten of the sixteen are
// two-line private helpers — resolving a name, building an id — that exist so the five public
// entry points read as prose. Splitting them across two classes would mean passing the same four
// collaborators to both.
@Suppress("TooManyFunctions")
class PvpReferee(
    private val cards: CardCatalog,
    private val formats: FormatCatalog,
    private val accounts: AccountStore,
    private val pvp: PvpStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Random = { Random.Default },
) {
    /**
     * Opens a table on the terms [request] names, or says why it cannot.
     *
     * Every refusal here is one it would be worse to discover later. The rules are checked against
     * the format's pool because a match played under rules the format does not allow is not a match
     * that format's players agreed to; the stake is checked against the purse because there is **no
     * escrow** — `MatchRewards.creditPvp` floors a purse at zero, so an unaffordable wager would
     * quietly become a free one, and the only honest moment to refuse is before anybody plays.
     */
    fun openTable(accountId: Long, request: PvpTableRequest): Tabled {
        refuseTable(accountId, request)?.let { return it }

        val row = PvpTableRow(
            id = newId(),
            hostAccount = accountId,
            hostName = accounts.usernameFor(accountId).orEmpty(),
            formatId = request.formatId,
            rules = request.rules,
            roulette = request.roulette,
            stake = request.stake,
            openedAt = clock(),
            expiresAt = clock() + PvpMatchRow.TABLE_MILLIS,
            hostDeck = request.deck,
        )
        // The last refusal comes from the unique index, not from a read: two taps a millisecond
        // apart would both pass a check-then-insert and leave this host advertising two matches.
        return if (pvp.openTable(row)) Tabled.Opened(row.toWire()) else Tabled.AlreadyWaiting
    }

    /**
     * Why this host cannot open this table, or null when they can.
     *
     * The terms first, then the two things about the *player*: a purse that covers the wager, and
     * not already being in a match. Split out so [openTable] reads as "refuse, or build one".
     */
    private fun refuseTable(accountId: Long, request: PvpTableRequest): Tabled? = when {
        checkTerms(request) != null -> checkTerms(request)
        accounts.saveFor(accountId) == null -> Tabled.NoSuchFormat
        (accounts.saveFor(accountId)?.mgp ?: 0) < request.stake.mgp -> Tabled.CannotAfford
        pvp.liveMatchFor(accountId) != null -> Tabled.AlreadyPlaying
        else -> null
    }

    /**
     * Whether these terms describe a match anybody can play, or why not.
     *
     * One function for both ways in. A table and an invitation propose the same four things, and
     * two copies of "which rules is this format allowed" is two places for the answer to differ —
     * which, given one of them would be the *directed* path, is how you end up able to invite a
     * friend to a match the lobby would have refused to advertise.
     */
    private fun checkTerms(request: PvpTableRequest): Tabled? {
        val format = formats[request.formatId] ?: return Tabled.NoSuchFormat
        // A pool with nothing in it cannot be drawn from — `Roulette.augment` requires it — so the
        // refusal happens here rather than as a 500 when somebody joins.
        if (request.roulette && format.rules.isEmpty()) return Tabled.RulesNotAllowed
        if (!format.admitsRules(request.rules)) return Tabled.RulesNotAllowed
        return null
    }

    /**
     * Joins a table and opens the match. The host plays blue.
     *
     * The host's purse is re-checked **inside** the claiming transaction, not only the joiner's.
     * Between opening a table and somebody joining it a host can spend the wager in the shop, and a
     * match opened against a stake one side cannot cover is one the winner is quietly short-changed
     * on. When that has happened the table is withdrawn rather than left to fail again.
     */
    fun joinTable(tableId: String, accountId: Long, deck: Int = ANY_DECK): Joined {
        if (pvp.liveMatchFor(accountId) != null) return Joined.AlreadyPlaying
        var refusal: Joined? = null

        val match = pvp.claimTableAndOpen(tableId, accountId, clock()) { table, joiner ->
            val joinerSave = accounts.saveFor(joiner)
            val hostSave = accounts.saveFor(table.hostAccount)
            if (joinerSave == null || joinerSave.mgp < table.stake.mgp) {
                refusal = Joined.CannotAfford
                return@claimTableAndOpen null
            }
            if (hostSave == null || hostSave.mgp < table.stake.mgp) {
                refusal = Joined.NoSuchTable
                return@claimTableAndOpen null
            }
            open(
                blue = table.hostAccount,
                red = joiner,
                formatId = table.formatId,
                declared = table.rules,
                roulette = table.roulette,
                stake = table.stake,
                blueDeck = table.hostDeck,
                redDeck = deck,
            )
        }

        if (match == null) {
            // The host being short is a table that should stop being offered. Outside the
            // transaction that rolled back, so the deletion survives it.
            if (refusal == Joined.NoSuchTable) pvp.dropTable(tableId, hostOf(tableId))
            return refusal ?: Joined.NoSuchTable
        }
        return Joined.Playing(match)
    }

    private fun hostOf(tableId: String): Long =
        pvp.openTables(clock()).firstOrNull { it.id == tableId }?.hostAccount ?: 0L

    /**
     * Sends an invitation, or says why it is not one.
     *
     * ### `NoSuchPlayer` tells the caller a username exists, and that is a decision
     *
     * `signIn` goes to real trouble not to: one message for both failures, and a decoy bcrypt so
     * the timing matches. This answers the question directly, which looks like the same property
     * being given away one route over — so it is worth writing down that the two are protecting
     * different things and that this one is not an oversight.
     *
     * A username on this server is **public**. `GET /pvp/tables` lists the host of every open table
     * by name, a match view names your opponent, and the whole point of a directed challenge is to
     * type a name you were told. There is no version of this feature in which names are secret.
     *
     * What `signIn` protects is not the existence of a name but the **pairing** of a name with a
     * password: without the decoy, response time would say which half of a guess was right and turn
     * one endpoint into two. That property is unaffected by anything here, and inventing a
     * deliberately vague refusal for a typo'd friend's name would cost a player the only useful
     * answer while protecting something the lobby publishes anyway.
     *
     * It is bounded rather than hidden: this route sits in the [LOBBY] bucket, so a caller may ask
     * about twenty names a minute, from an account they had to register.
     */
    fun challenge(accountId: Long, request: ChallengeRequest): Challenged {
        val target = accounts.accountIdForUsername(request.username)
            ?: return Challenged.NoSuchPlayer
        // The same terms check a table gets, because an invitation proposes the same things.
        val refusal = checkTerms(request.terms)?.let { Challenged.BadTerms }
            ?: refuse(accountId, target, request.terms.stake)
        refusal?.let { return it }

        val id = newId()
        val expiresAt = clock() + PvpMatchRow.CHALLENGE_MILLIS
        pvp.challenge(id, accountId, target, request.terms, expiresAt)

        return Challenged.Sent(
            PvpChallenge(
                id = id,
                fromName = accounts.usernameFor(accountId).orEmpty(),
                toName = request.username,
                expiresAt = expiresAt,
                terms = request.terms,
            ),
        )
    }

    /**
     * Why this invitation cannot go out, or null when it can.
     *
     * ### There is no longer a card to check
     *
     * A wager used to name two cards, and both were checked against what each player held. A trade
     * rule names none: you stake the five you bring, and `PveMatches.playerDeck` already drew those
     * from what the profile owns. So the ownership check is gone because there is nothing left for
     * it to check, not because it stopped mattering.
     *
     * The MGP check remains, on **both** sides, and for the reason [openTable] gives: there is no
     * escrow, so an unaffordable wager becomes a free one, and refusing before a match is played
     * costs nobody a game where refusing after one costs the winner their prize.
     */
    private fun refuse(accountId: Long, target: Long, stake: PvpStake): Challenged? = when {
        target == accountId -> Challenged.Yourself
        stake.mgp == 0 -> null
        (accounts.saveFor(accountId)?.mgp ?: 0) < stake.mgp -> Challenged.CannotAfford
        (accounts.saveFor(target)?.mgp ?: 0) < stake.mgp -> Challenged.CannotAfford
        else -> null
    }

    /**
     * Accepts an invitation and opens the match. The challenger plays blue.
     *
     * ### The two checks this did not make
     *
     * [challenge] verifies both purses when the invitation is **sent**, and this used to verify
     * nothing at all when it was answered — where [joinTable] deliberately re-checks inside the
     * claiming transaction, because a host can spend the wager while the table stands and a match
     * opened against a stake one side cannot cover short-changes the winner. An invitation stands
     * for `CHALLENGE_MILLIS` and offers exactly the same window.
     *
     * The `checkTerms` KDoc names this shape of bug precisely — "given one of them would be the
     * *directed* path, is how you end up able to invite a friend to a match the lobby would have
     * refused to advertise" — about the rules. It was true of the purse and of already being in a
     * match, which is the other thing joining refuses and accepting did not.
     *
     * So it answers with [Joined] now, the same type joining answers with, and the refusals mean
     * the same things. The one exception the route keeps for itself is `NoSuchTable`, which for an
     * invitation is worded as an invitation.
     */
    fun accept(challengeId: String, accountId: Long, deck: Int = ANY_DECK): Joined {
        if (pvp.liveMatchFor(accountId) != null) return Joined.AlreadyPlaying
        var refusal: Joined? = null

        val match = pvp.acceptChallenge(challengeId, accountId, clock()) { challenge, accepter ->
            // Both sides, as `refuse` checks both when the invitation goes out and as `joinTable`
            // checks both when a table is claimed. Checking only the accepter would leave the
            // challenger free to spend the wager between sending and being answered.
            val stake = challenge.terms.stake.mgp
            val short = listOf(challenge.fromAccount, accepter)
                .any { (accounts.saveFor(it)?.mgp ?: 0) < stake }

            if (stake > 0 && short) {
                refusal = Joined.CannotAfford
                return@acceptChallenge null
            }

            // On the terms the invitation named. It used to be `formats.default` and a roulette
            // draw regardless of what either player wanted, because an invitation could not say
            // anything else — see `V5__challenge_terms.sql`.
            open(
                blue = challenge.fromAccount,
                red = accepter,
                formatId = challenge.terms.formatId,
                declared = challenge.terms.rules,
                roulette = challenge.terms.roulette,
                stake = challenge.terms.stake,
                blueDeck = challenge.fromDeck,
                redDeck = deck,
            )
        }

        return match?.let(Joined::Playing) ?: refusal ?: Joined.NoSuchTable
    }

    /**
     * The current match as [accountId] sees it, settling an overdue turn on the way.
     *
     * Reading is where a forfeit is noticed, because reading is what both players do constantly and
     * a scheduler is one more thing to run. The player who *missed* the deadline sees the settled
     * result on their next poll too, which is the right way round: they find out they lost by
     * looking, not by being told nothing.
     */
    fun currentView(accountId: Long): PvpMatchView? {
        // `recentMatchFor`, not `liveMatchFor`: a settled match has to stay readable long enough
        // for the side that did not place the last card to be told how it ended.
        val row = pvp.recentMatchFor(accountId, clock()) ?: return null
        val settled = settleIfOverdue(row) ?: row
        val side = settled.sideOf(accountId) ?: return null

        return settled.wireFor(side, opponent(settled, side), cards)
    }

    /** Places a card, if it is this player's turn and the move is one the rules allow. */
    fun play(matchId: String, accountId: Long, move: PvpMove): Played {
        val row = pvp.matchById(matchId) ?: return Played.NoSuchMatch
        val side = row.sideOf(accountId) ?: return Played.NoSuchMatch

        // Overdue is settled before the move is looked at, not after: a player coming back three
        // minutes late has already lost, and accepting the card first would credit a match twice.
        val settled = settleIfOverdue(row)
        val view = (settled ?: row).viewFor(side, cards) ?: return Played.NoSuchMatch

        // A match that has already ended answers "not your turn", which is true: a finished
        // `MatchView` has no current player, so one branch covers both.
        return when {
            settled != null -> report(settled, side)
            row.status != PvpMatchStatus.PLAYING || !view.isMyTurn -> Played.NotYourTurn
            !PvpMatchRow.isLegal(view, move) -> Played.IllegalMove
            else -> record(row, side, move)
        }
    }

    /**
     * Writes the move down and settles the match if it was the ninth.
     *
     * Split from [play] because the two halves fail differently: everything above is *refusing* a
     * move, and everything here has already accepted one and is dealing with the world having
     * moved on underneath.
     */
    private fun record(row: PvpMatchRow, side: CardColor, move: PvpMove): Played {
        val after = row.copy(moves = row.moves + move)
        val finished = after.replay(cards)?.isFinished == true
        val deadline = if (finished) null else clock() + PvpMatchRow.DEADLINE_MILLIS

        // A refused append means somebody advanced the match between the read and the write — a
        // double tap, or a retry after a lost response. The move is already in, so reporting the
        // state is the honest answer, and it is what the client would have got anyway.
        val settled = when {
            !pvp.appendMove(row.id, row.moves.size, move, deadline) ->
                pvp.matchById(row.id) ?: return Played.NoSuchMatch
            finished -> settle(after, PvpMatchStatus.FINISHED, null)
            else -> after
        }
        return report(settled, side)
    }

    /** A row as an answer, or `NoSuchMatch` when it cannot be rendered at all. */
    private fun report(row: PvpMatchRow, side: CardColor): Played {
        val view = row.wireFor(side, opponent(row, side), cards) ?: return Played.NoSuchMatch
        return Played.Accepted(view)
    }

    /** Concedes: the same settlement a timeout produces. */
    fun forfeit(matchId: String, accountId: Long): PvpMatchView? {
        val row = pvp.matchById(matchId) ?: return null
        val side = row.sideOf(accountId) ?: return null
        if (row.status != PvpMatchStatus.PLAYING) {
            return row.wireFor(side, opponent(row, side), cards)
        }

        val settled = settle(row, PvpMatchStatus.FORFEITED, side)
        return settled.wireFor(side, opponent(settled, side), cards)
    }

    /**
     * The matches this player has won and not yet collected.
     *
     * Read straight from the store rather than through [currentView], because that one answers with
     * *the* match and there can be several of these — see [PvpStore.claimsFor].
     */
    fun claims(accountId: Long): List<PvpMatchView> = pvp.claimsFor(accountId).mapNotNull { row ->
        val settled = claimIfOverdue(row) ?: row
        val side = settled.sideOf(accountId) ?: return@mapNotNull null
        // Owing a pick, not merely *being in* a match that owes one. The store's query cannot tell
        // the two apart — it is indexed on the status, and both players share it — so without this
        // the loser is offered their opponent's prize and sent to a screen with nothing on it.
        if (settled.picksOwedBy(side, cards) == 0) return@mapNotNull null
        settled.wireFor(side, opponent(settled, side), cards)
    }

    /** Names the cards taken, or says why they cannot be. */
    fun claim(matchId: String, accountId: Long, claim: PvpClaim): Claimed {
        val row = pvp.matchById(matchId) ?: return Claimed.NoSuchMatch
        val side = row.sideOf(accountId) ?: return Claimed.NoSuchMatch

        // Overdue first, exactly as `play` settles an overdue turn before looking at a move: a
        // winner arriving after the server already picked for them must be told what happened, not
        // allowed to pick a second time on top of it.
        val settled = claimIfOverdue(row)

        return when {
            settled != null -> reportClaim(settled, side)
            row.picksOwedBy(side, cards) == 0 -> Claimed.NothingOwed
            !row.isClaimable(side, claim.cardIds, cards) -> Claimed.NotTheirs
            else -> settleClaim(row, side, claim.cardIds)
                ?.let { reportClaim(it, side) }
                ?: Claimed.NoSuchMatch
        }
    }

    private fun reportClaim(row: PvpMatchRow, side: CardColor): Claimed {
        val view = row.wireFor(side, opponent(row, side), cards) ?: return Claimed.NoSuchMatch
        return Claimed.Settled(view)
    }

    /**
     * Settles a claim nobody made, once its deadline has passed.
     *
     * On the **read** path, exactly as [settleIfOverdue] is for turns: a match is closed by the
     * first person who looks at it, and the loser looking is enough. Without it a winner who walked
     * away leaves the loser's card in a state that is neither theirs nor gone, on a match neither
     * side is ever paid for.
     */
    private fun claimIfOverdue(row: PvpMatchRow): PvpMatchRow? {
        val deadline = row.claimDeadline ?: return null
        if (row.status != PvpMatchStatus.AWAITING_CLAIM || clock() < deadline) return null

        val owing = CardColor.entries.firstOrNull { row.picksOwedBy(it, cards) > 0 } ?: return null
        return settleClaim(row, owing, row.autoClaim(owing, cards))
    }

    /** Forfeits every match whose deadline has passed. The safety net for when nobody looks. */
    fun sweep(): Int = pvp.overdue(clock()).count { settleIfOverdue(it) != null }

    /** Settles every claim nobody came back for. The same net, for the other deadline. */
    fun sweepClaims(): Int = pvp.claimOverdue(clock()).count { claimIfOverdue(it) != null }

    // ---- The two things that end a match ----------------------------------

    private fun settleIfOverdue(row: PvpMatchRow): PvpMatchRow? {
        val deadline = row.turnDeadline ?: return null
        if (row.status != PvpMatchStatus.PLAYING || clock() < deadline) return null

        // Whoever was to move is the one who left. The board is not consulted: a match abandoned
        // at 3-1 is a loss for whoever walked away, and reading the score would make leaving at
        // the right moment a strategy.
        val state = row.replay(cards) ?: return null
        val absent = state.currentPlayer ?: return null
        return settle(row, PvpMatchStatus.FORFEITED, absent)
    }

    /**
     * Ends [row], and pays both players unless somebody still owes a choice.
     *
     * `finish` is the gate: it only touches a row that is still `PLAYING`, so two callers racing to
     * settle the same match — the sweep and a poll, say — result in one settlement and one credit.
     * That is what stops a forfeit paying twice.
     *
     * Under One and Diff the match stops at `AWAITING_CLAIM` and **nothing is credited yet**, not
     * even the MGP. Paying the money now and the cards later would mean two writes to each profile
     * for one match, and a second chance for one of them to be lost.
     */
    private fun settle(
        row: PvpMatchRow,
        status: PvpMatchStatus,
        forfeitedBy: CardColor?,
    ): PvpMatchRow {
        val decided = row.copy(status = status, forfeitedBy = forfeitedBy, turnDeadline = null)
        val owed = decided.awaitsClaim(cards)
        val ending = if (owed) PvpMatchStatus.AWAITING_CLAIM else status
        val deadline = (clock() + PvpMatchRow.CLAIM_MILLIS).takeIf { owed }

        val ended = decided.copy(status = ending, claimDeadline = deadline)

        // Losing the race means somebody else settled this match; whatever they wrote is the truth.
        if (!pvp.finish(row.id, ending, forfeitedBy, deadline)) {
            return pvp.matchById(row.id) ?: ended
        }
        return ended.also { if (!owed) creditBoth(it) }
    }

    /**
     * Settles a claim: writes what was named, then pays.
     *
     * The status it restores is the one the match actually ended in — a forfeit that went through a
     * claim is still a forfeit, because "you won because they left" survives collecting the prize.
     */
    private fun settleClaim(row: PvpMatchRow, side: CardColor, ids: List<Int>): PvpMatchRow? {
        val claimed = row.claimed + (side to ids)
        val ending = if (row.forfeitedBy != null) {
            PvpMatchStatus.FORFEITED
        } else {
            PvpMatchStatus.FINISHED
        }

        val ended = row.copy(claimed = claimed, status = ending, claimDeadline = null)
        if (!pvp.recordClaim(row.id, claimed, ending)) return pvp.matchById(row.id)

        creditBoth(ended)
        return ended
    }

    /**
     * Pays both sides, and writes down what they were paid.
     *
     * The write is the reason this is not just a loop. `PvpOutcome` has always carried `mgp` and
     * `xp` and the server has always sent zero for both, so an end-of-match screen could report
     * the score and the wager but not the one number a player looks for. It cannot be derived
     * afterwards either — the payout rolls a random top-up and spends whatever boons the profile
     * was holding — so it is recorded at the only moment it exists.
     */
    private fun creditBoth(row: PvpMatchRow) {
        // Both sides' spoils come from the **row** alone — the replay, the score and the wager — so
        // they are settled before a lock is taken, and a row that will not replay credits nobody
        // without costing a transaction to find out.
        val outcomes = CardColor.entries.associateWith { row.outcomeFor(it, cards) ?: return }
        val spoils = CardColor.entries.associateWith { row.spoilsFor(it, cards) ?: return }

        val blue = row.accountOf(CardColor.BLUE)
        val red = row.accountOf(CardColor.RED)

        // ### One transaction, two locks, and why it had to become that
        //
        // This was four transactions and no locks: read a profile, credit it, write it, twice. Both
        // halves of that were wrong. A process dying between the two writes paid one player and not
        // the other — and much easier to reach, a losing player could race `PUT /me/save` against
        // their own settlement and keep the card they had just staked, because the settlement was
        // computed from a read anything could invalidate. `AccountStore.transfer` holds both rows
        // for the duration, so the profiles that are read are the profiles that are written.
        val paid = accounts.transfer(blue, red) { blueSave, redSave ->
            val purses = mapOf(CardColor.BLUE to blueSave, CardColor.RED to redSave)

            // ### What the wager actually moves
            //
            // Not `stake.mgp`, which is what was *agreed*. `MatchRewards.creditPvp` floors a purse
            // at zero, so a loser who spent the wager between opening the match and losing it paid
            // whatever was left while the winner was credited the whole stake — and the difference
            // was MGP that had not existed before the match. There is no escrow to prevent that
            // (see `openTable`, which is honest about it), so the transfer is clamped instead: the
            // winner is paid what the loser can actually pay, read under the same lock that is
            // about to take it.
            //
            // It does not make the wager enforceable — a player can still arrive at settlement
            // unable to cover it — but the economy no longer mints the difference. Enforcing it
            // needs MGP to become server-owned in `GameSave.withServerOwnedFrom`, which is a
            // `:core` change, named in `docs/security-review.md` as the half that remains.
            val moved = CardColor.entries
                .firstOrNull { spoils.getValue(it).mgp < 0 }
                ?.let { minOf(row.stake.mgp, purses.getValue(it).mgp) }
                ?: 0

            val settlement = CardColor.entries.associateWith { side ->
                creditSide(
                    row = row,
                    save = purses.getValue(side),
                    outcome = outcomes.getValue(side),
                    spoils = spoils.getValue(side),
                    moved = moved,
                )
            }
            settlement.getValue(CardColor.BLUE) to settlement.getValue(CardColor.RED)
        } ?: return

        pvp.recordPayout(
            row.id,
            mapOf(CardColor.BLUE to paid.first, CardColor.RED to paid.second),
        )
    }

    /**
     * Credits one side against the profile that is already locked, and answers with what it paid.
     *
     * Takes the profile rather than fetching one: it runs inside `AccountStore.transfer`, where
     * reaching for a second connection would be both a stale read and a way to exhaust the pool.
     *
     * [moved] is the clamped stake from [creditBoth] and replaces `spoils.mgp` as the amount —
     * `spoils` still decides the *direction*, which is a property of who won rather than of what
     * either purse can bear.
     */
    // Six parameters, all of them values this cannot look up for itself while holding a lock.
    @Suppress("LongParameterList")
    private fun creditSide(
        row: PvpMatchRow,
        save: GameSave,
        outcome: PvpOutcome,
        spoils: Spoils,
        moved: Int,
    ): Outcome<Payout> {
        val stakeMgp = when {
            spoils.mgp > 0 -> moved
            spoils.mgp < 0 -> -moved
            else -> 0
        }

        val credited = MatchRewards.creditPvp(
            save = save,
            result = outcome.result,
            rules = row.rules,
            at = clock(),
            stakeMgp = stakeMgp,
            cardsLost = spoils.lost,
            cardsWon = spoils.won,
            random = random(),
        )
        return Outcome(
            credited.save,
            Payout(
                mgp = credited.reward.mgp,
                xp = credited.reward.xp,
                // Recorded here because here is where they happen. `creditPvp` has unlocked
                // achievements and finished daily quests since PvP was refereed, and both were
                // dropped on the floor: the payout row had nowhere to put them and `PvpOutcome`
                // had no field, so a player earned them and was never told.
                achievementIds = credited.reward.achievements.map { it.id },
                questIds = credited.reward.quests.map { it.id },
            ),
        )
    }

    /**
     * Opens a match between two accounts, on the terms the table named.
     *
     * Both hands come from the **server's** copy of each profile. A client names a *deck slot* and
     * nothing more — `PveMatches.playerDeck` resolves it against the save the server holds, and
     * falls back to the first complete deck when the slot names nothing playable. So a player
     * chooses which of their decks to bring and still cannot name a card they do not own, which is
     * what makes a trade rule safe to settle without an ownership check: you can only ever wager
     * what you have.
     *
     * The format is the one the table named, not [FormatCatalog.default]. It used to be the
     * default and could not be anything else, because nobody chose; a host now does.
     *
     * Who starts is the server's toss: `CoinFlip` still exists for the screens to animate, and is
     * handed a winner rather than tossing its own, so neither client can roll until it likes the
     * answer.
     *
     * @param roulette whether to draw one to three further rules on top of [declared]. The only
     *   thing entitled to set `GameRules.roulette`, which is what the Wheel of Fortune achievements
     *   count — see `Format.choosableRuleKeys`.
     */
    @Suppress("LongParameterList")
    private fun open(
        blue: Long,
        red: Long,
        formatId: String,
        declared: GameRules,
        roulette: Boolean,
        stake: PvpStake,
        blueDeck: Int,
        redDeck: Int,
    ): PvpMatchRow? {
        val blueSave = accounts.saveFor(blue) ?: return null
        val redSave = accounts.saveFor(red) ?: return null
        val format = formats[formatId] ?: return null

        val generator = random()
        val seed = generator.nextInt()
        val settled = Random(seed)

        // Drawn before the hands, because the draw can *add* Random — the rule that decides where
        // a hand comes from. Reading `declared` for that would let a roulette that lands on Random
        // deal from the deck anyway, which is the rule not happening.
        // `Roulette.pools` is gone — a rule pool is a property of the format now, and the engine is
        // handed one rather than looking one up.
        val rules = if (roulette && format.rules.isNotEmpty()) {
            Roulette.augment(declared, format.rules, settled)
        } else {
            declared
        }

        return PvpMatchRow(
            id = newId(),
            blueAccount = blue,
            redAccount = red,
            formatId = format.id,
            rules = rules,
            seed = seed,
            blueHand = handFor(blueSave, blueDeck, rules, format, settled),
            redHand = handFor(redSave, redDeck, rules, format, settled),
            first = if (settled.nextBoolean()) CardColor.BLUE else CardColor.RED,
            moves = emptyList(),
            stake = stake,
            status = PvpMatchStatus.PLAYING,
            turnDeadline = clock() + PvpMatchRow.DEADLINE_MILLIS,
        )
    }

    /**
     * Where one player's five cards come from, which is the Random rule's whole job.
     *
     * `RULE_RANDOM` does not draw from a deck: it splices from the player's **collection**, and the
     * deck they chose is ignored entirely. PvE has done this since it was refereed — see
     * `PveRoutes.deal` — and multiplayer did not, so a table could be opened under Random, the
     * caption could announce it, and both sides would be dealt the decks they picked. The rule was
     * offered, named on screen, and did nothing.
     *
     * Confined to what the **format** admits before drawing, for the reason the deck path is: a
     * collection spans every block a player owns, and a hand dealt outside the table's pool is a
     * hand the engine refuses.
     *
     * Falls back to the chosen deck when the admitted collection cannot fill a hand.
     * `MatchPreparation.randomHand` refuses fewer than five rather than dealing a duplicate, and a
     * player who owns four cards in this format should get a match rather than a refusal.
     */
    private fun handFor(
        save: GameSave,
        deck: Int,
        rules: GameRules,
        format: Format,
        random: Random,
    ): List<Int> {
        if (!rules.random) return PveMatches.playerDeck(save, deck)

        val legal = cards.admittedBy(format).associateBy { it.id }
        val collection = save.ownedCardIds().mapNotNull { legal[it] }
        return if (collection.size >= HAND_SIZE) {
            MatchPreparation.randomHand(collection, random).map { it.id }
        } else {
            PveMatches.playerDeck(save, deck)
        }
    }

    /**
     * Who [side] is up against — the name and the face, which a board needs together.
     *
     * An account that has vanished between being paired and being read answers an empty name and
     * no face rather than stopping the board: the match is still a row, still replayable and still
     * settleable, and "who was that" is the one question it can afford not to answer.
     */
    private fun opponent(row: PvpMatchRow, side: CardColor): Opponent =
        accounts.opponentFor(row.accountOf(side.opposite()))
            ?: Opponent(name = "", avatarId = null)

    // `generator` is pulled out rather than called inside `buildString`, and not for tidiness:
    // the builder's receiver is a `CharSequence`, so `random()` in there resolves to
    // `CharSequence.random()` — a character — instead of this class's generator. It compiles right
    // up until the member access, which is a confusing place to learn it.
    private fun newId(): String {
        val generator = random()
        return buildString {
            repeat(ID_LENGTH) { append(ID_ALPHABET[generator.nextInt(ID_ALPHABET.length)]) }
        }
    }

    private companion object {
        /** Opaque and unguessable enough that a match id is not a way to find somebody's game. */
        const val ID_LENGTH = 22
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
