package com.tripletriad.server

import com.tripletriad.data.Campaign
import com.tripletriad.data.CampaignCatalog
import com.tripletriad.data.CampaignPayout
import com.tripletriad.data.CampaignRewards
import com.tripletriad.data.CardCatalog
import com.tripletriad.data.Format
import com.tripletriad.data.FormatCatalog
import com.tripletriad.data.MatchReward
import com.tripletriad.data.MatchRewards
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.PveMatches
import com.tripletriad.data.RewardBoost
import com.tripletriad.model.Board
import com.tripletriad.model.CampaignRun
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
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
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
    campaigns: CampaignCatalog = Catalogs.campaigns,
    clock: () -> Long = System::currentTimeMillis,
    random: () -> Random = { Random.Default },
) {
    val referee = PveReferee(cards, npcs, formats, accounts, pve, campaigns, clock, random)

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
    /**
     * The server deals, draws the roulette and tosses.
     *
     * Throttled by [SUBMIT] — the bucket that guards `/matches/submit` — because this is the other
     * door into the same room. A refereed match ends in `MatchRewards.credit` against the stored
     * profile exactly as a submitted transcript does, so a cadence limit on one and not the other
     * is not a limit, it is a signpost to the unlimited endpoint. Every route under `/pve` was
     * unthrottled until this change.
     *
     * Dealing is also the expensive half: it reads the profile, draws the rules, builds two hands
     * and writes a row, and `abandonLive` leaves the previous attempt behind each time.
     */
    rateLimit(RateLimitName(SUBMIT)) {
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

    /**
     * Places a card, and answers with the opponent's reply already made.
     *
     * Throttled by [PLAY]. The ninth placement is the one that settles the match and pays, so this
     * is not merely a read endpoint that happens to write — it is the second half of the payout
     * path [SUBMIT] guards next door. The reads above are left alone: a client polls `/active` to
     * find out whether its match survived being killed, and refusing that is refusing to resume.
     */
    rateLimit(RateLimitName(PLAY)) {
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
}

/** The ladder a match belongs to and where the run stands on it. */
private data class Rung(val campaign: Campaign, val run: CampaignRun)

/**
 * A request's tournament claim, once it has been checked. See `PveReferee.claimed`.
 *
 * Three answers in two types, and the wrapper is what keeps them apart: **no `Claim` at all** is a
 * claim that was refused, a `Claim` with a null [run] is an ordinary free-play match, and one with
 * a run is a rung the player really is standing on. A bare `CampaignRun?` could not say the first
 * two differently, which is exactly the confusion that would let a refused claim be dealt.
 */
private data class Claim(val run: CampaignRun?)

/** Why a match did or did not open. */
sealed interface Dealt {
    data class Playing(val view: PveMatchView) : Dealt
    data object NoSuchOpponent : Dealt
    data object NoSuchFormat : Dealt
    data object Undealable : Dealt

    /** The request claimed a rung the profile's run is not standing on. See [PveRefusal]. */
    data object NotOnThatRung : Dealt
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
    Dealt.NotOnThatRung -> RejectedPve(
        HttpStatusCode.Conflict,
        PveRefusal.NOT_ON_THAT_RUNG,
        "you are not on that rung of that tournament",
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
    private val campaigns: CampaignCatalog = Catalogs.campaigns,
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
        // An opponent the profile has not earned is answered as though they were not there.
        // Deliberately the same refusal as an unknown icon: the roster is not a secret, but a
        // client learning the difference between "no such opponent" and "not yet" from a status
        // code learns nothing it cannot read off `npcs.json`, and one answer is one code path.
        val npc = npcs.byIcon(request.opponentIconId, format.id)
            ?.takeIf { it.isUnlockedFor(save) }
            ?: return Dealt.NoSuchOpponent
        val claim = claimed(save, request, npc) ?: return Dealt.NotOnThatRung
        val dealt = deal(accountId, save, npc, format, request.deck)
            ?.copy(campaignKey = claim.run?.campaignKey, campaignStep = claim.run?.step)
            ?: return Dealt.Undealable

        pve.abandonLive(accountId)
        // Losing the insert means a second tap got there first. Its match is the answer — which is
        // what the player wanted from both taps — so the deal just made is dropped on the floor.
        val opened = pve.open(dealt) ?: return reopened(accountId)

        // The board **as dealt**, announcing nothing — not even when the toss gave the opponent
        // the opening move, which is the case this used to answer with a card already on the
        // board. The client has announcements of its own to play first (the rules, the hand
        // turning over for Open, the coin flip that decides who moves first), and a card that
        // has landed before the flip that won it contradicts the flip. See [opening] for where
        // that placement is computed instead, and `PveMatchView.plays` for why here is the wrong
        // place to send it from.
        return answer(opened, emptyList())?.let(Dealt::Playing) ?: Dealt.Undealable
    }

    /**
     * The match this player is in or has just finished, as they may see it.
     *
     * **Leaves an owed opening owed**, where [view] pays it. Resuming is not an exception to the
     * deal, it *is* the deal: a client picking a match back up replays the rules captions and the
     * coin flip for it, and would have to take an opening applied here back off again — which is
     * the reconstruction this whole arrangement exists to delete. So a resumed match arrives in
     * the same shape a fresh one does, and begins the same way, through [view].
     */
    fun current(accountId: Long): PveMatchView? =
        pve.recentFor(accountId, clock())?.let { answer(it, emptyList()) }

    /**
     * One match by id, scoped to its owner by the store's own query — **and the read that starts
     * a match the opponent won the toss for.**
     *
     * A deal answers before the opponent has moved, so its opening is owed rather than made, and
     * this is where the debt is paid: [opening] computes and writes it, and [narrate] announces
     * exactly what was written, which is nothing at all on every other read. That is what makes
     * this one request the client's whole "the announcements are done, go ahead" — no endpoint of
     * its own, because a plain read of the match is honestly what it is.
     *
     * [current] deliberately does **not** do this, and the asymmetry is the point: see there.
     */
    fun view(matchId: String, accountId: Long): PveMatchView? {
        val row = pve.matchById(matchId, accountId) ?: return null
        val (started, plays) = opening(row)
        return answer(started, plays)
    }

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
        // rather than obeying them — see `MatchPosition.viewFor`, and `MatchSearch`, which
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
     * ### The payout is computed under the profile's row lock
     *
     * It used to be computed from an `accounts.saveFor(...)` taken in its own transaction and
     * written back in another, which is a read-modify-write with nothing between the halves: a
     * purchase committing in the gap was erased by the write, with a `200` for both requests.
     * `AccountStore.lockSave` exists to end that and this was one of the three paths still doing
     * it, so everything from the ladder lookup to the credited save now happens inside
     * `creditRefereedMatch`, against the profile that is about to be written.
     *
     * ### Crediting comes first and the gate second, which is the other way round from before
     *
     * It has to, now that the reward is computed inside the credit: [PveStore.finish] stores the
     * summary, and the summary does not exist until the closure has run. That is safe because the
     * credit is idempotent in its own right — its unique index refuses a second history row for the
     * same match, so of two callers racing to settle the same board exactly one is paid — and
     * [PveStore.finish] remains the gate on the *row*, only touching one that is still `PLAYING`.
     * The loser of the race is told what the winner wrote.
     */
    // Three guard clauses and the answer: an unreplayable board, an opponent whose icon has gone,
    // and an account with no character. Folding them into one nested expression to save a keyword
    // would bury which of the three happened, which is the only thing worth knowing here.
    @Suppress("ReturnCount")
    private fun settle(row: PveMatchRow): PveMatchRow {
        val state = row.replay(cards) ?: return row
        val npc = npcs.byIcon(row.opponentIconId, row.formatId) ?: return row

        val score = state.score
        val result = when {
            score.blue > score.red -> MatchResult.WIN
            score.blue < score.red -> MatchResult.LOSE
            else -> MatchResult.DRAW
        }
        val at = clock()
        val generator = random()
        // What the closure has to report back out: the row it writes carries the match, but the
        // summary belongs to `pve_matches` and is stored by the gate below.
        var summary: RewardSummary? = null

        val credited = accounts.creditRefereedMatch(row.accountId, matchKey = row.id) { save ->
            // Null unless this row was dealt as a rung **and** the profile still stands on that
            // run. See `V11__pve_campaign.sql`: a run that closed underneath a live match settles
            // it as the ordinary match it turned out to be, rather than crediting a tournament
            // nobody is in. Read from the locked profile, so "still stands" is true at the moment
            // the run is advanced rather than at the moment the request arrived.
            val ladder = runFor(row, save)

            val paid = MatchRewards.credit(
                save = save.startingMatch(againstNpc = true),
                npc = npc,
                result = result,
                rules = row.rules,
                at = at,
                random = generator,
                boost = ladder?.let { CampaignRewards.rungBoost(it.campaign, result) }
                    ?: RewardBoost.NONE,
            )
            // The run is advanced on the profile the match just wrote, so both land in one save.
            val climbed = ladder?.let { closing(paid.save, it, result, at, generator) }
            summary = summarise(paid.reward, climbed)

            Crediting(
                match = RecordedMatch(
                    opponentIconId = row.opponentIconId,
                    formatId = row.formatId,
                    seed = row.seed,
                    blue = score.blue,
                    red = score.red,
                    result = result,
                    mgp = paid.reward.mgp,
                    xp = paid.reward.xp,
                ),
                save = (climbed?.save ?: paid.save)
                    .copy(lastSave = at, saveNumber = save.saveNumber + 1),
            )
        }

        // No character, which registration makes unreachable. The match is left as it is rather
        // than marked finished: a board nobody can be paid for has not been settled.
        if (credited == Credited.NotCredited) return row

        if (!pve.finish(row.id, PveMatchStatus.FINISHED, summary)) {
            return pve.matchById(row.id, row.accountId) ?: row
        }
        return row.copy(status = PveMatchStatus.FINISHED, reward = summary)
    }

    /**
     * What the match paid, as one line, with anything the tournament added folded in.
     *
     * The two are added rather than reported apart because a player is owed one answer to "what did
     * that get me" — the panel shows a total. Which half came from the ladder is the run's own
     * business, and the summary screen already knows it was the last rung.
     */
    private fun summarise(reward: MatchReward, climbed: CampaignPayout?): RewardSummary =
        RewardSummary(
            result = reward.result,
            mgp = reward.mgp + (climbed?.mgp ?: 0),
            xp = reward.xp,
            items = reward.items + (climbed?.items ?: emptyList()),
            achievementIds = (reward.achievements + (climbed?.achievements ?: emptyList()))
                .map { it.id },
            questIds = reward.quests.map { it.id },
        )

    /**
     * The run this row belongs to, or null when settling it is nobody's tournament business.
     *
     * All four have to hold: the row claimed a ladder, the ladder is one this build knows, the
     * profile still holds a run in it, and that run is still standing where the row was dealt for.
     * The last is what makes an interrupted run safe to abandon — the moment a player forfeits and
     * enters again, an old live match settles as free play instead of advancing the new run.
     */
    private fun runFor(row: PveMatchRow, save: GameSave): Rung? {
        val key = row.campaignKey ?: return null
        val campaign = campaigns.byKey(key) ?: return null
        val run = save.runIn(key)?.takeIf { it.step == row.campaignStep } ?: return null

        return Rung(campaign, run)
    }

    /**
     * Moves the run on, and closes it when the rung was the last one or the player lost.
     *
     * The three endings, which are the whole of what a tournament is:
     *
     * - **a win that is not the top** advances, and pays only what the rung paid;
     * - **a win at the top** pays [CampaignRewards.finish] — the ladder's own multiple of its entry
     *   fee, plus one item drawn from its lot — and records the ladder as climbed;
     * - **anything else** holds or closes. A draw holds and pays nothing at all, so the rung may
     *   be replayed as often as the player likes; a defeat closes the run and refunds nothing.
     *
     * A first-round defeat is no different from any other, which is deliberate and is what the
     * entry fee prices.
     */
    private fun closing(
        save: GameSave,
        rung: Rung,
        result: MatchResult,
        at: Long,
        random: Random,
    ): CampaignPayout {
        val advanced = CampaignRewards.advance(rung.run, result)

        return when {
            result == MatchResult.LOSE ->
                CampaignPayout(CampaignRewards.forfeit(save), mgp = 0, items = emptyList())

            advanced.hasCompleted(rung.campaign.steps.size) ->
                CampaignRewards.finish(save, rung.campaign, at, random)

            else -> CampaignPayout(
                save.copy(campaignRun = advanced),
                mgp = 0,
                items = emptyList(),
            )
        }
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

    /**
     * Whether this request may be dealt as a tournament rung, and which one.
     *
     * Four things have to agree before a match is credited as part of a run, and every one of them
     * is checked here rather than at settlement — refusing to deal costs the player nothing, where
     * refusing to pay for a match they have already played costs them the match:
     *
     * - the request claims a ladder at all (otherwise it is free play, and [Standing.FreePlay]);
     * - the profile holds an open run, in **that** ladder;
     * - the ladder exists in this server's catalogue;
     * - the opponent asked for is the one standing on the run's current rung.
     *
     * The last is the one that is easy to leave out and the one that matters most. Without it a
     * client could open the first rung's opponent as many times as the ladder is long and finish
     * the tournament against the easiest of its opponents.
     */
    private fun claimed(save: GameSave, request: PveMatchRequest, npc: Npc): Claim? {
        val key = request.campaignKey ?: return Claim(run = null)
        val run = save.runIn(key) ?: return null
        val campaign = campaigns.byKey(key) ?: return null
        val rung = campaign.stepAt(run.step) ?: return null

        return if (rung.npc.iconId == npc.iconId) Claim(run) else null
    }

    /** The match that already existed, when opening lost the race to a second tap. */
    private fun reopened(accountId: Long): Dealt = pve.activeFor(accountId)
        ?.let { answer(it, emptyList()) }
        ?.let(Dealt::Playing)
        ?: Dealt.Undealable

    /**
     * The opponent's placement when it is on move and has not made one, or [row] untouched.
     *
     * ### Called from a read, which is the whole design
     *
     * A row is a move list, and an empty one under `first = RED` is not an incomplete match — it
     * is the complete, replayable fact *the opponent has not moved yet*. Nothing blocks on it and
     * no status expresses it, so it cannot be stuck: the placement is computed by whichever
     * request asks next, which is [view] in the ordinary case and a `NotYourTurn` refusal
     * re-reading the board in the case of a client that never asked.
     *
     * A mid-match row never reaches the appending branch, because [record] writes the player's
     * card and every reply the opponent owes in one statement — so the only board on which RED is
     * on move is one nobody has played on. The arithmetic does not rely on that being true, which
     * is why the expected count is [PveMatchRow.moves]'s size rather than the zero it was when
     * this was only ever called on a fresh deal.
     *
     * ### Losing the race costs an animation and nothing else
     *
     * Two reads arriving together both compute the opening and one loses the compare-and-set in
     * [PveStore.appendMoves]. Re-reading is the honest answer, exactly as it is in [record]: the
     * placement really was made, by the other request, and this one announces nothing, because
     * **this** request did not make it. Announcing it from both would step the same card onto the
     * board twice.
     *
     * @return the row as it now stands, and the placements **this call** wrote — which is what
     *   makes the sentence above enforceable rather than merely intended.
     */
    private fun opening(row: PveMatchRow): Pair<PveMatchRow, List<Placement>> {
        val owed = replies(row)
        if (owed.isEmpty()) return row to emptyList()

        return if (pve.appendMoves(row.id, row.moves.size, owed)) {
            val started = row.copy(moves = row.moves + owed)
            started to narrate(started, row.moves.size)
        } else {
            (pve.matchById(row.id, row.accountId) ?: row) to emptyList()
        }
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
        // `randomHand` draws under `DeckLimits`, so it answers short for a collection that cannot
        // field a legal five — a profile sold down to nothing but aces. The chosen deck is the
        // answer there: Random may take the choice away, it may not refuse to deal.
        val drawn = if (rules.random && collection.size >= HAND_SIZE) {
            MatchPreparation.randomHand(collection, generator).map { it.id }
        } else {
            emptyList()
        }
        val blue = drawn.takeIf { it.size == HAND_SIZE } ?: PveMatches.playerDeck(save, deck, legal)
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
