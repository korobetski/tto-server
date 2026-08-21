package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.Format
import com.tripletriad.data.FormatCatalog
import com.tripletriad.data.MatchRewards
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.PveMatches
import com.tripletriad.model.Board
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.MatchAiOptions
import com.tripletriad.model.MatchPreparation
import com.tripletriad.model.MatchResult
import com.tripletriad.model.MatchSearch
import com.tripletriad.model.Npc
import com.tripletriad.model.PlayResult
import com.tripletriad.protocol.Placement
import com.tripletriad.protocol.PveFailure
import com.tripletriad.protocol.PveMatchRequest
import com.tripletriad.protocol.PveMatchStatus
import com.tripletriad.protocol.PveMatchView
import com.tripletriad.protocol.PveMove
import com.tripletriad.protocol.PveRefusal
import com.tripletriad.protocol.RewardSummary
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.random.Random

/**
 * Player versus environment, refereed.
 *
 * ### The server plays the opponent now
 *
 * It used to check one afterwards. A client played the whole match and submitted a
 * `MatchTranscript`, which the server replayed — a design that made an offline match genuinely
 * checkable and is being retired anyway, because replaying required the *client* to be running the
 * same AI from the same seed. That meant it held the opponent's five cards and knew every move they
 * would make from the first placement, and a modified client could act on it without leaving a
 * trace: the match really did happen exactly as claimed.
 *
 * Every route here answers with a [PveMatchView] — what the asking player is entitled to see. None
 * of them ever returns a hidden opponent card, and none accepts a board.
 *
 * ### One round trip per placement
 *
 * `POST /pve/matches/{id}/moves` applies the player's card **and the opponent's reply**, and
 * answers with both. Against a person a client has to poll, because it cannot know when the other
 * side will move; against a program the answer exists the moment the question is asked. Making the
 * client come back for it would put a round trip in front of every turn to no purpose.
 */
// Six collaborators and a clock, and they are one decision: everything a refereed match needs. The
// same shape, and the same suppression, as `pvpRoutes`.
@Suppress("LongParameterList")
fun Route.pveRoutes(
    cards: CardCatalog,
    npcs: NpcCatalog,
    formats: FormatCatalog,
    accounts: AccountStore,
    pve: PveStore,
    clock: () -> Long = System::currentTimeMillis,
    random: () -> Random = { Random.Default },
) {
    val referee = PveReferee(cards, npcs, formats, accounts, pve, clock, random)

    route("/pve/matches") {
        openRoute(referee, accounts)
        liveRoutes(referee, accounts)
    }
}

/**
 * Sitting down against an opponent.
 *
 * Its own function, split from [liveRoutes] along the line the player experiences — before a match
 * and during one. It does the same work for the complexity gate that `pvpRoutes` splits for, and
 * the gate is right about it: four handlers in one body buries the interesting part of each.
 */
private fun Route.openRoute(referee: PveReferee, accounts: AccountStore) {
    /** The server deals, draws the roulette and tosses. */
    post {
        if (!requireCompatibleClient()) return@post
        val accountId = authenticate(accounts) ?: return@post
        val request = call.receive<PveMatchRequest>()

        when (val dealt = referee.open(accountId, request)) {
            is Dealt.Playing -> call.respond(HttpStatusCode.Created, dealt.view)
            else -> call.refusePve(dealt.refusal())
        }
    }
}

/** Reading a match in progress, and placing a card in one. */
private fun Route.liveRoutes(referee: PveReferee, accounts: AccountStore) {
    /**
     * The match in progress, if there is one. **This is resuming.**
     *
     * It takes no id: the client asks who it is and the server says what it is doing. On a phone
     * the system kills applications without asking, and a tunnel ends a connection without the
     * player choosing to leave. Neither is an abandon, and neither needs recovering from — the
     * match never left this server.
     */
    get("/active") {
        if (!requireCompatibleClient()) return@get
        val accountId = authenticate(accounts) ?: return@get
        val view = referee.current(accountId)

        if (view == null) call.respond(HttpStatusCode.NoContent) else call.respond(view)
    }

    /** One match by id, for a client that already knows which one it is holding. */
    get("/{id}") {
        if (!requireCompatibleClient()) return@get
        val accountId = authenticate(accounts) ?: return@get
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val view = referee.view(id, accountId)

        if (view == null) call.refusePve(Moved.NoSuchMatch.refusal()) else call.respond(view)
    }

    /** Places a card, and answers with the opponent's reply already made. */
    post("/{id}/moves") {
        if (!requireCompatibleClient()) return@post
        val accountId = authenticate(accounts) ?: return@post
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val move = call.receive<PveMove>()

        when (val played = referee.play(id, accountId, move)) {
            is Moved.Accepted -> call.respond(HttpStatusCode.OK, played.view)
            else -> call.refusePve(played.refusal())
        }
    }
}

/** Why a match did or did not open. */
sealed interface Dealt {
    data class Playing(val view: PveMatchView) : Dealt
    data object NoSuchOpponent : Dealt
    data object NoSuchFormat : Dealt
    data object Undealable : Dealt
}

/** What happened to a placement. */
sealed interface Moved {
    data class Accepted(val view: PveMatchView) : Moved
    data object NoSuchMatch : Moved
    data object NotYourTurn : Moved
    data object IllegalMove : Moved
}

/** A refusal and the status it travels under — the counterpart of [Rejected] for these routes. */
data class RejectedPve(val status: HttpStatusCode, val code: PveRefusal, val detail: String)

internal suspend fun ApplicationCall.refusePve(rejected: RejectedPve) =
    respond(rejected.status, PveFailure(rejected.code, rejected.detail))

internal fun Dealt.refusal(): RejectedPve = when (this) {
    is Dealt.Playing -> error("an opened match is not a refusal")
    Dealt.NoSuchOpponent -> RejectedPve(
        HttpStatusCode.NotFound,
        PveRefusal.NO_SUCH_OPPONENT,
        "no such opponent in that format",
    )
    Dealt.NoSuchFormat ->
        RejectedPve(HttpStatusCode.BadRequest, PveRefusal.NO_SUCH_FORMAT, "no such format")
    Dealt.Undealable -> RejectedPve(
        HttpStatusCode.Conflict,
        PveRefusal.UNDEALABLE,
        "you cannot field five cards in that format",
    )
}

internal fun Moved.refusal(): RejectedPve = when (this) {
    is Moved.Accepted -> error("an accepted move is not a refusal")
    Moved.NoSuchMatch ->
        RejectedPve(HttpStatusCode.NotFound, PveRefusal.NO_SUCH_MATCH, "no such match")
    Moved.NotYourTurn ->
        RejectedPve(HttpStatusCode.Conflict, PveRefusal.NOT_YOUR_TURN, "it is not your turn")
    Moved.IllegalMove ->
        RejectedPve(HttpStatusCode.Conflict, PveRefusal.ILLEGAL_MOVE, "that move is not allowed")
}

/**
 * Everything a refereed match against an opponent does, with no HTTP in it.
 *
 * Split from the routes for the reason the rest of this server splits things: the rules of the game
 * should be testable without a request. What is left above is parsing, authenticating and choosing
 * a status code.
 */
// The rule is aimed at a class doing too many things; this one does one, which is being the referee
// of a solo match. Most of the members are two-line private helpers that exist so the four public
// entry points read as prose.
@Suppress("TooManyFunctions", "LongParameterList")
class PveReferee(
    private val cards: CardCatalog,
    private val npcs: NpcCatalog,
    private val formats: FormatCatalog,
    private val accounts: AccountStore,
    private val pve: PveStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Random = { Random.Default },
) {
    /**
     * Deals a match against [request]'s opponent, or says why it cannot.
     *
     * ### The order of the two writes matters
     *
     * The deal is worked out **first**, and only a deal that came out legal abandons whatever the
     * player had going. The other order — abandon, then try to deal — takes a match away and then
     * discovers it has nothing to replace it with, which is the one outcome a player would rightly
     * call a bug.
     */
    fun open(accountId: Long, request: PveMatchRequest): Dealt {
        val save = accounts.saveFor(accountId) ?: return Dealt.Undealable
        val format = formats[request.formatId] ?: return Dealt.NoSuchFormat
        val npc = npcs.byIcon(request.opponentIconId, format.id) ?: return Dealt.NoSuchOpponent
        val dealt = deal(accountId, save, npc, format, request.deck) ?: return Dealt.Undealable

        pve.abandonLive(accountId)
        // Losing the insert means a second tap got there first. Its match is the answer — which is
        // what the player wanted from both taps — so the deal just made is dropped on the floor.
        val opened = pve.open(dealt) ?: return reopened(accountId)
        val started = opening(opened)

        // Narrated from the very beginning, which is nothing at all unless the toss gave the
        // opponent the opening move. Answering with an empty list there would put a card on the
        // board that the client has no placement to animate — it would simply appear.
        return answer(started, narrate(started, 0))?.let(Dealt::Playing) ?: Dealt.Undealable
    }

    /** The match this player is in or has just finished, as they may see it. */
    fun current(accountId: Long): PveMatchView? =
        pve.recentFor(accountId, clock())?.let { answer(it, emptyList()) }

    /** One match by id, scoped to its owner by the store's own query. */
    fun view(matchId: String, accountId: Long): PveMatchView? =
        pve.matchById(matchId, accountId)?.let { answer(it, emptyList()) }

    /** Places a card, if it is this player's turn and the move is one the rules allow. */
    fun play(matchId: String, accountId: Long, move: PveMove): Moved {
        val row = pve.matchById(matchId, accountId) ?: return Moved.NoSuchMatch
        val view = row.viewFor(cards) ?: return Moved.NoSuchMatch

        return when {
            row.status != PveMatchStatus.PLAYING || !view.isMyTurn -> Moved.NotYourTurn
            !PveMatchRow.isLegal(view, move) -> Moved.IllegalMove
            else -> record(row, move)
        }
    }

    /**
     * Writes the placement down, with the opponent's reply, and settles the match if that was it.
     *
     * The two moves are appended in **one** statement. A crash between two writes would leave a
     * board where it is nobody's turn, which is a state the row has no way to express and no way to
     * recover from.
     */
    private fun record(row: PveMatchRow, move: PveMove): Moved {
        val added = listOf(move) + replies(row.copy(moves = row.moves + move))

        // A refused append means the match advanced between the read and the write — a double tap,
        // or a retry after a lost response. The move is already in, so reporting the state as it
        // now stands is the honest answer, and it is what the client would have got anyway. It
        // announces no placements: whatever they were, this request did not make them.
        val (settled, plays) = if (pve.appendMoves(row.id, row.moves.size, added)) {
            val advanced = row.copy(moves = row.moves + added)
            val done = if (advanced.isOver(cards)) settle(advanced) else advanced
            done to narrate(done, row.moves.size)
        } else {
            pve.matchById(row.id, row.accountId) to emptyList()
        }

        return settled
            ?.let { answer(it, plays) }
            ?.let(Moved::Accepted)
            ?: Moved.NoSuchMatch
    }

    /**
     * The opponent's placements from here, if it is on move.
     *
     * A loop rather than a single move, because a Sudden Death rematch keeps the turn order: the
     * board can fill, a new one begin, and the opponent be on move again immediately. Bounded by
     * the size of a board so that a bug cannot spin — the loop is self-terminating otherwise, since
     * turns alternate and each pass appends a placement.
     */
    private fun replies(from: PveMatchRow): List<PveMove> {
        val moves = mutableListOf<PveMove>()
        var at = from
        var reply = opponentMove(at)

        while (reply != null && moves.size < Board.SIZE) {
            moves += reply
            at = at.copy(moves = at.moves + reply)
            reply = opponentMove(at)
        }
        return moves
    }

    /**
     * What the opponent plays from here, or null if it is not on move.
     *
     * "Not on move" is folded in rather than checked by the caller, so that [replies] has one
     * condition to loop on instead of three ways out of a loop body.
     *
     * The generator is **not** the row's. Nothing about this choice has to be reproducible: it is
     * written into the row the moment it is made, and the row is what every later read replays.
     * That is the whole of why the AI can change without a protocol version — see [PveMatchRow].
     */
    private fun opponentMove(row: PveMatchRow): PveMove? {
        val at = row.position(cards) ?: return null
        val onMove = at.state.takeIf { it.currentPlayer == CardColor.RED } ?: return null
        val npc = npcs.byIcon(row.opponentIconId, row.formatId)

        // **How hard it plays comes from the opponent's authored band**, not from how hard it was
        // measured to be — `NpcRating` deliberately stopped writing `level` so that this read
        // cannot close the loop. An opponent whose icon no longer resolves plays the old one-move
        // game rather than not moving: a missing row in `npcs.json` must not wedge a live match.
        val options = npc?.let { MatchAiOptions.forLevel(it.level) } ?: MatchAiOptions()

        // The opponent's **own view**, which is all it is entitled to. Handing it `onMove` would
        // hand it both hands, which is how a program ends up ignoring All Open and Three Open
        // rather than obeying them — see `PveMatchPosition.viewFor`, and `MatchSearch`, which
        // substitutes for everything the visibility does not name.
        val chosen = MatchSearch(options).choose(
            state = onMove,
            visible = at.visibilityFor(CardColor.RED),
            random = random(),
        ) ?: return null

        val slot = onMove.currentHand.indexOfFirst { it.id == chosen.card.id }
        return slot.takeIf { it >= 0 }?.let { PveMove(it, chosen.position) }
    }

    /**
     * Ends the match and pays for it, exactly once.
     *
     * [PveStore.finish] is the gate: it only touches a row that is still `PLAYING`, so two callers
     * racing to settle the same match — a double tap on the ninth card, say — result in one
     * settlement and one credit. `AccountStore.creditRefereedMatch` is the belt to that brace, its
     * unique index refusing a second history row for the same match.
     *
     * The payout is computed **before** the gate because it has to be stored by it, and computing
     * it writes nothing: `MatchRewards.credit` is a pure function that hands back a profile rather
     * than saving one. Losing the race therefore costs an arithmetic that is thrown away.
     */
    private fun settle(row: PveMatchRow): PveMatchRow {
        val state = row.replay(cards) ?: return row
        val npc = npcs.byIcon(row.opponentIconId, row.formatId) ?: return row
        val save = accounts.saveFor(row.accountId) ?: return row

        val score = state.score
        val result = when {
            score.blue > score.red -> MatchResult.WIN
            score.blue < score.red -> MatchResult.LOSE
            else -> MatchResult.DRAW
        }
        val credited = MatchRewards.credit(
            save = save.startingMatch(againstNpc = true),
            npc = npc,
            result = result,
            rules = row.rules,
            at = clock(),
            random = random(),
        )
        val summary = RewardSummary(
            result = credited.reward.result,
            mgp = credited.reward.mgp,
            xp = credited.reward.xp,
            items = credited.reward.items,
            achievementIds = credited.reward.achievements.map { it.id },
            questIds = credited.reward.quests.map { it.id },
        )

        if (!pve.finish(row.id, PveMatchStatus.FINISHED, summary)) {
            return pve.matchById(row.id, row.accountId) ?: row
        }
        accounts.creditRefereedMatch(
            accountId = row.accountId,
            matchKey = row.id,
            match = RecordedMatch(
                opponentIconId = row.opponentIconId,
                formatId = row.formatId,
                seed = row.seed,
                blue = score.blue,
                red = score.red,
                result = result,
                mgp = credited.reward.mgp,
                xp = credited.reward.xp,
            ),
            save = credited.save.copy(lastSave = clock(), saveNumber = save.saveNumber + 1),
        )
        return row.copy(status = PveMatchStatus.FINISHED, reward = summary)
    }

    /**
     * A row as an answer, with the credited profile attached when the match is over.
     *
     * The profile is read here rather than carried out of [settle] so that a *poll* of a finished
     * match answers with it too. It is the one authority on what the player now owns — a client
     * replaces what it holds rather than adding anything up, which is what removes the window two
     * copies of a profile used to disagree in.
     */
    private fun answer(row: PveMatchRow, plays: List<Placement>): PveMatchView? {
        val view = row.wireFor(cards, plays) ?: return null
        val outcome = view.outcome ?: return view
        return view.copy(outcome = outcome.copy(player = accounts.playerState(row.accountId)))
    }

    /**
     * The placements added since [from], as the wire announces them.
     *
     * Replays a prefix per placement, which is nine replays of nine placements at the very worst —
     * microseconds of pure arithmetic against a network round trip. The alternative is threading
     * intermediate states out of the row's own walk, which would make the row's shape answer a
     * presentation question.
     */
    private fun narrate(row: PveMatchRow, from: Int): List<Placement> =
        (from until row.moves.size).mapNotNull { count ->
            row.copy(moves = row.moves.take(count + 1))
                .replay(cards)
                ?.lastPlay
                ?.let(::toPlacement)
        }

    private fun toPlacement(play: PlayResult): Placement = Placement(
        player = play.player,
        cardId = play.card.id,
        position = play.position,
        captures = play.captures,
        handIndex = play.handIndex,
    )

    /** The match that already existed, when opening lost the race to a second tap. */
    private fun reopened(accountId: Long): Dealt = pve.activeFor(accountId)
        ?.let { answer(it, emptyList()) }
        ?.let(Dealt::Playing)
        ?: Dealt.Undealable

    /** The opponent's first placement, when the toss gave it the opening move. */
    private fun opening(row: PveMatchRow): PveMatchRow {
        val first = replies(row)
        if (first.isEmpty() || !pve.appendMoves(row.id, 0, first)) return row
        return row.copy(moves = first)
    }

    /**
     * The row a new match starts from, or null if this profile cannot field five cards.
     *
     * ### The hands are drawn here and stored, not re-derived
     *
     * `PveMatches.assemble` would do all of this, and is not used, for one reason: it hands back
     * the hands **after** the swap, and the row stores them before it. Everything else here is that
     * function's own logic, taken apart — the roulette, `RULE_RANDOM` drawing from the collection
     * rather than the deck, the opponent's fetish cards topped up out of its pool, and the toss.
     *
     * The generator is not the one [PveMatchRow] replays with. Rules, hands and who starts are
     * *stored*, so they need no seed; the seed drives only what the replay re-derives — the swap,
     * the elements and the per-side Open draws. Keeping the two apart means the deal cannot
     * correlate with the board it produced.
     */
    private fun deal(
        accountId: Long,
        save: GameSave,
        npc: Npc,
        format: Format,
        deck: Int,
    ): PveMatchRow? {
        val generator = random()
        val rules = PveMatches.rulesFor(npc, format, generator)
        val legal = cards.admittedBy(format).associateBy { it.id }

        val collection = save.ownedCardIds().mapNotNull { legal[it] }
        val blue = if (rules.random && collection.size >= HAND_SIZE) {
            MatchPreparation.randomHand(collection, generator).map { it.id }
        } else {
            PveMatches.playerDeck(save, deck)
        }
        val red = npc.randomHand(generator)

        val fieldable = blue.size == HAND_SIZE && red.size == HAND_SIZE &&
            (blue + red).all { it in legal }

        return if (!fieldable) {
            null
        } else {
            PveMatchRow(
                id = newId(),
                accountId = accountId,
                formatId = format.id,
                opponentIconId = npc.iconId,
                rules = rules,
                seed = generator.nextInt(),
                blueHand = blue,
                redHand = red,
                first = if (generator.nextBoolean()) CardColor.BLUE else CardColor.RED,
                moves = emptyList(),
                status = PveMatchStatus.PLAYING,
            )
        }
    }

    // `generator` is pulled out rather than called inside `buildString`, and not for tidiness: the
    // builder's receiver is a `CharSequence`, so `random()` in there resolves to
    // `CharSequence.random()` — a character — instead of this class's generator.
    private fun newId(): String {
        val generator = random()
        return buildString {
            repeat(ID_LENGTH) { append(ID_ALPHABET[generator.nextInt(ID_ALPHABET.length)]) }
        }
    }

    private companion object {
        /** Opaque enough that a match id is not a way to find somebody's game. */
        const val ID_LENGTH = 22
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
