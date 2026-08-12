package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.MatchRewards
import com.tripletriad.data.PveMatches
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.Roulette
import com.tripletriad.protocol.PvpChallenge
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpMatchView
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpQueueState
import com.tripletriad.protocol.PvpStake
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
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
 */
fun Route.pvpRoutes(
    cards: CardCatalog,
    accounts: AccountStore,
    pvp: PvpStore,
    clock: () -> Long = System::currentTimeMillis,
    random: () -> Random = { Random.Default },
) {
    val referee = PvpReferee(cards, accounts, pvp, clock, random)

    route("/pvp") {
        lobbyRoutes(referee, accounts, pvp, clock)
        liveMatchRoutes(referee, accounts)
    }
}

/**
 * Finding an opponent: the quick queue and the invitations.
 *
 * Split from [liveMatchRoutes] along the line the player experiences — before a match and during
 * one — rather than to satisfy a complexity counter, though it does that too. Nothing here reads a
 * board and nothing there reads the queue.
 */
private fun Route.lobbyRoutes(
    referee: PvpReferee,
    accounts: AccountStore,
    pvp: PvpStore,
    clock: () -> Long,
) {
    challengeRoutes(referee, accounts, pvp, clock)

    /**
     * Joins the quick queue, or takes the opponent already waiting in it.
     *
     * One endpoint for both because they are one action from the player's side — "find me a
     * match" — and because splitting them would make the client decide whether to queue or to
     * pair, which is exactly the decision it cannot make without seeing the queue.
     */
    post("/queue") {
        val accountId = authenticate(accounts) ?: return@post
        val save = accounts.saveFor(accountId) ?: return@post noCharacter(accountId)

        val match = referee.quickMatch(accountId, save)
        call.respond(
            HttpStatusCode.OK,
            PvpQueueState(
                waiting = match == null,
                since = clock().takeIf { match == null },
                matchId = match?.id,
            ),
        )
    }

    /** Leaves the queue. Harmless if not in it — the player wanted to not be waiting. */
    delete("/queue") {
        val accountId = authenticate(accounts) ?: return@delete
        pvp.dequeue(accountId)
        call.respond(HttpStatusCode.OK, PvpQueueState(waiting = false))
    }
}

/** Inviting a named player, and answering an invitation. */
private fun Route.challengeRoutes(
    referee: PvpReferee,
    accounts: AccountStore,
    pvp: PvpStore,
    clock: () -> Long,
) {
    /** The invitations standing either way, so one screen can show both. */
    get("/challenges") {
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
    post("/challenges") {
        val accountId = authenticate(accounts) ?: return@post
        val request = call.receive<ChallengeRequest>()

        when (val outcome = referee.challenge(accountId, request)) {
            is Challenged.Sent -> call.respond(HttpStatusCode.Created, outcome.challenge)
            Challenged.NoSuchPlayer ->
                call.respond(HttpStatusCode.NotFound, Refusal("no such player"))
            Challenged.Yourself ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    Refusal("you cannot challenge yourself"),
                )
            Challenged.StakeNotOwned ->
                call.respond(HttpStatusCode.Conflict, Refusal("you do not own that card"))
        }
    }

    /** Accepts an invitation, which opens the match. */
    post("/challenges/{id}/accept") {
        val accountId = authenticate(accounts) ?: return@post
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        val match = referee.accept(id, accountId)
        if (match == null) {
            call.respond(HttpStatusCode.Conflict, Refusal("that invitation is no longer open"))
        } else {
            call.respond(HttpStatusCode.Created, PvpQueueState(false, matchId = match.id))
        }
    }

    /** Declines an invitation, or withdraws one. */
    delete("/challenges/{id}") {
        val accountId = authenticate(accounts) ?: return@delete
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        pvp.dropChallenge(id, accountId)
        call.respond(HttpStatusCode.OK)
    }
}

/** Playing: the match in progress, a placement, and conceding. */
private fun Route.liveMatchRoutes(referee: PvpReferee, accounts: AccountStore) {
    /**
     * The match in progress, as this player may see it.
     *
     * Also the answer to "did my match survive the app being killed?", which is why it takes no
     * id: the client asks who it is and the server says what it is doing. On a phone the system
     * kills applications without asking and the player did not choose to leave.
     *
     * The overdue check runs here rather than on a timer, so a forfeit is settled by the first
     * person who looks. A background sweep still exists for the case where **nobody** looks —
     * see [PvpStore.overdue] — but the common path needs no scheduler.
     */
    get("/match") {
        val accountId = authenticate(accounts) ?: return@get
        val view = referee.currentView(accountId)

        if (view == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.OK, view)
        }
    }

    /** Places a card. */
    post("/match/{id}/move") {
        val accountId = authenticate(accounts) ?: return@post
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val move = call.receive<PvpMove>()

        when (val played = referee.play(id, accountId, move)) {
            is Played.Accepted -> call.respond(HttpStatusCode.OK, played.view)
            Played.NoSuchMatch -> call.respond(
                HttpStatusCode.NotFound,
                Refusal("no such match"),
            )
            Played.NotYourTurn ->
                call.respond(HttpStatusCode.Conflict, Refusal("it is not your turn"))
            Played.IllegalMove ->
                call.respond(HttpStatusCode.Conflict, Refusal("that move is not allowed"))
        }
    }

    /** Concedes. The same settlement a timeout produces, chosen rather than suffered. */
    post("/match/{id}/forfeit") {
        val accountId = authenticate(accounts) ?: return@post
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        val view = referee.forfeit(id, accountId)
        if (view == null) {
            call.respond(HttpStatusCode.NotFound, Refusal("no such match"))
        } else {
            call.respond(HttpStatusCode.OK, view)
        }
    }
}

/** What a client asks for when it wants to challenge somebody. */
@Serializable
data class ChallengeRequest(val username: String, val stake: PvpStake = PvpStake.None)

/** A refusal a player can read. The shape every 4xx here answers with. */
@Serializable
data class Refusal(val reason: String)

/** Why a challenge did or did not go out. */
sealed interface Challenged {
    data class Sent(val challenge: PvpChallenge) : Challenged
    data object NoSuchPlayer : Challenged
    data object Yourself : Challenged
    data object StakeNotOwned : Challenged
}

/** What happened to a move. */
sealed interface Played {
    data class Accepted(val view: PvpMatchView) : Played
    data object NoSuchMatch : Played
    data object NotYourTurn : Played
    data object IllegalMove : Played
}

private suspend fun RoutingContext.noCharacter(accountId: Long) {
    call.application.environment.log.error("Account {} has no character", accountId)
    call.respond(HttpStatusCode.InternalServerError)
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
    private val accounts: AccountStore,
    private val pvp: PvpStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Random = { Random.Default },
) {
    /** Pairs with whoever is waiting, or joins the queue. Null means "queued, nothing yet". */
    fun quickMatch(accountId: Long, save: GameSave): PvpMatchRow? =
        pvp.pairAndOpen(accountId, save.mode) { blue, red -> open(blue, red, PvpStake.None) }

    /** Sends an invitation, or says why it is not one. */
    fun challenge(accountId: Long, request: ChallengeRequest): Challenged {
        val target = accounts.accountIdForUsername(request.username)
            ?: return Challenged.NoSuchPlayer
        refuse(accountId, target, request.stake)?.let { return it }

        val stake = request.stake
        val id = newId()
        val expiresAt = clock() + PvpMatchRow.CHALLENGE_MILLIS
        pvp.challenge(id, accountId, target, stake, expiresAt)

        return Challenged.Sent(
            PvpChallenge(
                id = id,
                fromName = accounts.usernameFor(accountId).orEmpty(),
                toName = request.username,
                stake = stake,
                expiresAt = expiresAt,
            ),
        )
    }

    /**
     * Why this invitation cannot go out, or null when it can.
     *
     * The wagers are checked against what each player actually holds, and checked **now** rather
     * than at settlement: refusing before a match is played costs nobody a game, and refusing after
     * one costs the winner their prize.
     */
    private fun refuse(accountId: Long, target: Long, stake: PvpStake): Challenged? = when {
        target == accountId -> Challenged.Yourself
        stake !is PvpStake.Cards -> null
        accounts.saveFor(accountId)?.ownsCard(stake.challengerCard) != true ->
            Challenged.StakeNotOwned
        accounts.saveFor(target)?.ownsCard(stake.opponentCard) != true ->
            Challenged.StakeNotOwned
        else -> null
    }

    /** Accepts an invitation and opens the match. The challenger plays blue. */
    fun accept(challengeId: String, accountId: Long): PvpMatchRow? =
        pvp.acceptChallenge(challengeId, accountId, clock()) { challenger, accepter, stake ->
            open(challenger, accepter, stake)
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
        val row = pvp.liveMatchFor(accountId) ?: return null
        val settled = settleIfOverdue(row) ?: row
        val side = settled.sideOf(accountId) ?: return null

        return settled.wireFor(side, opponentName(settled, side), cards)
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
        val view = row.wireFor(side, opponentName(row, side), cards) ?: return Played.NoSuchMatch
        return Played.Accepted(view)
    }

    /** Concedes: the same settlement a timeout produces. */
    fun forfeit(matchId: String, accountId: Long): PvpMatchView? {
        val row = pvp.matchById(matchId) ?: return null
        val side = row.sideOf(accountId) ?: return null
        if (row.status != PvpMatchStatus.PLAYING) {
            return row.wireFor(side, opponentName(row, side), cards)
        }

        val settled = settle(row, PvpMatchStatus.FORFEITED, side)
        return settled.wireFor(side, opponentName(settled, side), cards)
    }

    /** Forfeits every match whose deadline has passed. The safety net for when nobody looks. */
    fun sweep(): Int = pvp.overdue(clock()).count { settleIfOverdue(it) != null }

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
     * Ends [row] and pays both players.
     *
     * `finish` is the gate: it only touches a row that is still `PLAYING`, so two callers racing to
     * settle the same match — the sweep and a poll, say — result in one settlement and one credit.
     * That is what stops a forfeit paying twice.
     */
    private fun settle(
        row: PvpMatchRow,
        status: PvpMatchStatus,
        forfeitedBy: CardColor?,
    ): PvpMatchRow {
        val ended = row.copy(status = status, forfeitedBy = forfeitedBy, turnDeadline = null)
        if (!pvp.finish(row.id, status, forfeitedBy)) return pvp.matchById(row.id) ?: ended

        for (side in CardColor.entries) {
            creditSide(ended, side)
        }
        return ended
    }

    private fun creditSide(row: PvpMatchRow, side: CardColor) {
        val accountId = row.accountOf(side)
        val save = accounts.saveFor(accountId) ?: return
        val outcome = row.outcomeFor(side, cards) ?: return

        val credited = MatchRewards.creditPvp(
            save = save,
            result = outcome.result,
            rules = row.rules,
            at = clock(),
            stakeLost = row.stakeOf(side),
            stakeWon = row.stakeOf(side.opposite()),
            random = random(),
        )
        accounts.replaceSave(accountId, credited.save)
    }

    /**
     * Opens a match between two accounts.
     *
     * Both hands come from the **server's** copy of each profile — `PveMatches.playerDeck` takes
     * the first complete deck, or five owned cards when none is built — so neither client chooses
     * what it is dealt, and neither can name a card it does not own.
     *
     * The rules are drawn by the roulette, as a PvE match's are. Who starts is the server's toss:
     * `CoinFlip` still exists for the screens to animate, and is handed a winner rather than
     * tossing its own, so neither client can roll until it likes the answer.
     */
    private fun open(blue: Long, red: Long, stake: PvpStake): PvpMatchRow? {
        val blueSave = accounts.saveFor(blue) ?: return null
        val redSave = accounts.saveFor(red) ?: return null
        if (blueSave.mode != redSave.mode) return null

        val generator = random()
        val seed = generator.nextInt()
        val settled = Random(seed)

        return PvpMatchRow(
            id = newId(),
            blueAccount = blue,
            redAccount = red,
            collection = blueSave.mode,
            rules = Roulette.augment(GameRules(), blueSave.mode, settled),
            seed = seed,
            blueHand = PveMatches.playerDeck(blueSave),
            redHand = PveMatches.playerDeck(redSave),
            first = if (settled.nextBoolean()) CardColor.BLUE else CardColor.RED,
            moves = emptyList(),
            stake = stake,
            status = PvpMatchStatus.PLAYING,
            turnDeadline = clock() + PvpMatchRow.DEADLINE_MILLIS,
        )
    }

    private fun opponentName(row: PvpMatchRow, side: CardColor): String =
        accounts.usernameFor(row.accountOf(side.opposite())).orEmpty()

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
