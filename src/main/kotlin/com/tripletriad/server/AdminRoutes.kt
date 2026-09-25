// TooManyFunctions counts the seven handlers this file is made of, plus the helpers that keep each
// of them short enough to read. The rule is aimed at a file doing too many *things*; this one does
// one — everything the administration console asks for — and splitting it by count would separate
// a route from the shape it answers with, which is the pairing the whole file is organised around.
// `AccountRoutes.kt` records the same judgement above its own.
@file:Suppress("TooManyFunctions")

package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.NpcCatalog
import com.tripletriad.model.CaptureKind
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.MatchState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the administration console reads and the one thing it writes.
 *
 * ### The contract was written down before this file existed
 *
 * `tto-web/console/lib/api.ts` is the specification: every interface in it is a claim about what
 * this server answers, argued next to the reasoning that produced it, and written first on purpose
 * so that "consultation, economy, disputes" became a list of requests that could be disagreed with
 * before a migration made any of it immutable. The shapes below are that file, in Kotlin. Where the
 * two ever disagree the console is the one to change second, because it is the one with a reader.
 *
 * ### `requireCompatibleClient()` is deliberately absent, and this is where that is written down
 *
 * `CLAUDE.md` requires the version gate on every new route, and every route in `PveRoutes`,
 * `PvpRoutes` and `AccountRoutes` carries it. These do not, and the exception is recorded here
 * rather than in a commit message because here is where somebody looking for the missing call will
 * come.
 *
 * The gate exists because a protocol bump changes how a `GameSave` or a transcript is *read*, and
 * an old client submitting one would have it misread rather than refused. Nothing below replays a
 * transcript or accepts a save: the single write takes three fields and moves an integer. So the
 * gate would buy nothing — and it would cost the thing that matters most, because a server release
 * nobody has redeployed the console against would lock the one person who could investigate it out
 * of the console, at exactly the moment they needed it. The console's own client states the
 * corresponding half of the exception, and neither end sends the header.
 *
 * ### Every route authenticates first, and there is no second role
 *
 * [authenticateAdmin] either answers with an identity or has already sent the 401 — so the first
 * line of every handler is the same, and a handler that forgot it would not compile into anything
 * that works, because it has no `admin` to attribute an audit row to. `web-platform.md` plans a
 * read-only role; v1 has one, and `Failure.forbidden` in the console is reserved for the day there
 * are two.
 *
 * ### Reads go straight to [AdminStore]; the writes go through [AccountStore]
 *
 * That is `web-platform.md`'s rule and it is the load-bearing one: **`AdminStore` never writes a
 * player's profile.** The credit below calls [AccountStore.applyOnceAcross], which is the same
 * idempotency machinery every purchase in the game already goes through, and the `admin_audit` row
 * is appended on that transaction's own connection — so the record and the effect commit together
 * or not at all. See [creditPlayer], which is where both halves of that are visible at once, and
 * `AdminInventory.kt`, which is the second write and follows it line for line.
 *
 * The aggregate reads — the roster, the NPC and bot statistics — are [AdminInsightStore]'s, in
 * `AdminInsights.kt`, beside the shapes they answer with.
 */
@Suppress("LongParameterList")
fun Route.adminRoutes(
    admins: AdminStore,
    insights: AdminInsightStore,
    accounts: AccountStore,
    pve: PveStore,
    pvp: PvpStore,
    cards: CardCatalog = Catalogs.cards,
    npcs: NpcCatalog = Catalogs.npcs,
) {
    route("/admin") {
        // Not under a rate limit. The two unauthenticated routes in `AdminAuthentication` are, for
        // the reason stated there; these are behind a session cookie that cost a password and a
        // second factor, and a budget on them would only ever be spent by the operator who is
        // clicking through a support case.
        get("/stats/overview") { overview(admins) }
        get("/stats/npcs") { npcStats(admins, insights, npcs) }
        get("/stats/bots") { botStats(admins, insights, pvp, cards) }
        get("/players") { searchPlayers(admins) }
        // A constant segment outranks `{id}` in Ktor's routing, so "list" is never read as an id —
        // and it could not be one anyway, since an id that is not a number is already a 404.
        get("/players/list") { listPlayers(admins, insights) }
        get("/players/{id}") { playerDetail(admins) }
        post("/players/{id}/credit") { creditPlayer(admins, accounts) }
        post("/players/{id}/inventory") { editInventory(admins, accounts, cards) }
        get("/catalog") { catalog(admins, cards) }
        get("/matches/{kind}/{id}") { matchDetail(admins, pve, pvp, cards) }
        get("/auctions") { auctions(admins) }
        get("/auctions/{lotId}/bids") { auctionBids(admins) }
        get("/audit") { auditTrail(admins) }
    }
}

/**
 * `GET /admin/stats/overview` — the dashboard, in one row from the `stats` schema of step 2.1.
 *
 * A missing schema is a 404 rather than the 500 a missing relation would otherwise produce. It
 * means `postgres-bootstrap` did not run, which is a deployment fault with a name — see
 * `docker/postgres/bootstrap/bootstrap.sh` — and one the console can put on screen instead of
 * showing a blank dashboard over a stack trace in a log nobody is reading yet.
 */
private suspend fun RoutingContext.overview(admins: AdminStore) {
    authenticateAdmin(admins) ?: return
    val overview = admins.overview() ?: return call.notFound()
    call.respondConsole(overview)
}

/**
 * `GET /admin/players?q=…` — the front door.
 *
 * An empty `q` answers with an empty list rather than with every account. The console opens on this
 * page before anything has been typed, and a server that read that as "all of them" would answer
 * the first request of every session with the whole table.
 */
private suspend fun RoutingContext.searchPlayers(admins: AdminStore) {
    authenticateAdmin(admins) ?: return
    val query = call.request.queryParameters["q"].orEmpty().trim()
    if (query.isEmpty()) return call.respondConsole(emptyList<AdminPlayerSummary>())
    call.respondConsole(admins.searchPlayers(query))
}

/** `GET /admin/players/{id}` — one screen's worth about one player, in one transaction. */
private suspend fun RoutingContext.playerDetail(admins: AdminStore) {
    authenticateAdmin(admins) ?: return
    val accountId = call.parameters["id"]?.toLongOrNull() ?: return call.notFound()
    val detail = admins.player(accountId) ?: return call.notFound()
    call.respondConsole(detail)
}

/**
 * `POST /admin/players/{id}/credit` — the only write in v1, and the reason `admin_audit` exists.
 *
 * ### Why the id is the client's and not this server's
 *
 * [AccountStore.applyOnceAcross] keys on an operation id minted by the caller, so a retry of a
 * request whose answer was lost returns the **first** answer rather than moving the balance a
 * second time. The console mints it once per *intent* and reuses it across attempts, which is what
 * makes "did my 500 MGP go through?" answerable by sending the same request again.
 *
 * [AdminCreditReceipt.applied] is how the two cases are told apart. `perform` sets [fresh] when it
 * actually runs; a replayed answer is decoded and re-emitted with the flag cleared, so the operator
 * sees the balance that resulted *and* is told this attempt did not cause it. Storing `false` in
 * the first place would be worse — the stored body is the first answer, and the first answer was
 * true.
 *
 * ### The audit row is written by `perform`, on `perform`'s connection
 *
 * Not after it, and not in a transaction of its own. An audit row that can exist without the change
 * it describes is a record of something that may not have happened, and a change that can commit
 * without its row is a balance nobody can account for. Both are avoided by the same line:
 * [AccountWriter.db] is the transaction, and [AdminStore.append] takes it.
 *
 * ### What is refused, and what is merely floored
 *
 * A blank reason and a zero amount are refused: the reason is what an audit row six months old is
 * read for, and a zero credit is an audit row about nothing. A subtraction that would take the
 * purse below zero is **not** refused — it is floored, and the receipt reports where the balance
 * actually landed. That is the one arithmetic the console's own type warns about.
 */
private suspend fun RoutingContext.creditPlayer(admins: AdminStore, accounts: AccountStore) {
    val admin = authenticateAdmin(admins) ?: return
    val accountId = call.parameters["id"]?.toLongOrNull() ?: return call.notFound()
    val request = call.receive<AdminCreditRequest>()
    if (request.operationId.isBlank() || request.reason.isBlank() || request.amount == 0) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "malformed_request"))
    }

    var fresh = false
    val body = accounts.applyOnceAcross(accountId, request.operationId) { writer ->
        // Null means the account has no character row — a registration that never claimed a
        // starter, or an id that is not an account at all. Abandoning rolls the claimed operation
        // id back with it, so the same request is free to be sent again once that is fixed.
        val before = writer.lock(accountId) ?: return@applyOnceAcross null

        // In `Long` and then coerced, because `mgp` is an `Int` and the sum of two of them is not:
        // a grant near `Int.MAX_VALUE` would otherwise wrap to a negative purse, which is the one
        // failure mode where the audit row would faithfully record the wrong thing.
        val purse = (before.mgp.toLong() + request.amount).coerceIn(0L, Int.MAX_VALUE.toLong())
        val after = before.copy(mgp = purse.toInt())
        writer.write(accountId, after)

        admins.append(
            writer.db,
            AuditEntry(
                adminId = admin.id,
                action = CREDITED,
                subjectAccount = accountId,
                operationId = request.operationId,
                reason = request.reason,
                before = ConsoleJson.encodeToString(AdminBalance(before.mgp)),
                after = ConsoleJson.encodeToString(AdminBalance(after.mgp)),
            ),
        )
        fresh = true
        ConsoleJson.encodeToString(
            AdminCreditReceipt(mgpBefore = before.mgp, mgpAfter = after.mgp, applied = true),
        )
    } ?: return call.notFound()

    val receipt = ConsoleJson.decodeFromString<AdminCreditReceipt>(body)
    call.respondConsole(if (fresh) receipt else receipt.copy(applied = false))
}

/**
 * `GET /admin/matches/{kind}/{id}` — the inspector, for whichever of the three tables holds it.
 *
 * The kind is in the path rather than guessed from the id because `matches.id` is a bigint and the
 * other two are text, so "1234" is ambiguous — and a wrong guess would show the wrong match to
 * somebody arbitrating a dispute, which is the one situation this screen exists for.
 */
private suspend fun RoutingContext.matchDetail(
    admins: AdminStore,
    pve: PveStore,
    pvp: PvpStore,
    cards: CardCatalog,
) {
    authenticateAdmin(admins) ?: return
    val id = call.parameters["id"].orEmpty()
    val detail = when (call.parameters["kind"]) {
        "credited" -> id.toLongOrNull()?.let { admins.creditedMatch(it) }
        "pve" -> pveDetail(admins, pve, cards, id)
        "pvp" -> pvpDetail(admins, pvp, cards, id)
        else -> null
    } ?: return call.notFound()
    call.respondConsole(detail)
}

/** `GET /admin/auctions?status=…` — the house, newest first. */
private suspend fun RoutingContext.auctions(admins: AdminStore) {
    authenticateAdmin(admins) ?: return
    call.respondConsole(admins.auctions(call.request.queryParameters["status"]))
}

/** `GET /admin/auctions/{lotId}/bids` — who bid what on one lot. See [AdminStore.bids]. */
private suspend fun RoutingContext.auctionBids(admins: AdminStore) {
    authenticateAdmin(admins) ?: return
    val lotId = call.parameters["lotId"].orEmpty()
    val bids = admins.bids(lotId) ?: return call.notFound()
    call.respondConsole(bids)
}

/**
 * `GET /admin/audit?subject=&cursor=` — the whole trail, or one account's.
 *
 * A `cursor` that is not a number is treated as no cursor rather than as a bad request. It is
 * opaque to the console by contract, so the only way to hold a malformed one is to have typed it
 * into the address bar — and answering the first page is a better response to that than a 400 the
 * console has no page for.
 */
private suspend fun RoutingContext.auditTrail(admins: AdminStore) {
    authenticateAdmin(admins) ?: return
    val parameters = call.request.queryParameters
    call.respondConsole(
        admins.audit(
            subject = parameters["subject"]?.toLongOrNull(),
            before = parameters["cursor"]?.toLongOrNull(),
        ),
    )
}

/**
 * A refereed PvE session, assembled from the row and the two facts the row does not carry.
 *
 * [PveMatchRow] holds the hands, the moves, the rules and the seed, and deliberately not the
 * timestamps or the player's name — nothing in the game needs either, so neither is in the type.
 * [AdminStore.matchContext] fetches exactly those, which is one extra query and no second copy of
 * the JSONB decoding `PveStore` already does.
 *
 * The opponent is an NPC, so it has no account: `accountId` is null and the name is the icon id the
 * row records. The console renders a null `accountId` as plain text rather than as a link, which is
 * the same treatment it gives a deleted account — and both are correct for the same reason, that
 * there is no player page to go to.
 */
private fun pveDetail(
    admins: AdminStore,
    pve: PveStore,
    cards: CardCatalog,
    id: String,
): AdminMatchDetail? {
    val row = pve.matchForInspection(id) ?: return null
    val context = admins.matchContext(KIND_PVE, id) ?: return null
    val replay = row.replayed(cards)
    val position = replay?.end
    return AdminMatchDetail(
        id = row.id,
        kind = KIND_PVE,
        status = row.status.name,
        format = row.formatId,
        rules = row.rules.activeRuleKeys(),
        seed = row.seed,
        blue = context.blue.scored(position?.state?.score?.blue ?: 0),
        red = AdminParty(accountId = null, name = row.opponentIconId)
            .scored(position?.state?.score?.red ?: 0),
        startedAt = context.startedAt,
        finishedAt = context.finishedAt,
        payout = AdminPayout(blue = row.reward?.mgp ?: 0, red = 0),
        hands = replay?.start?.state?.toHands(),
        moves = replay?.toMoves().orEmpty(),
        // PvE sessions are refereed here, move by move, so there is nothing to digest: the server
        // saw every placement as it happened. The hash belongs to `matches`, which is the table for
        // transcripts a client submitted after the fact.
        transcriptHash = null,
    )
}

/** The same, for a match between two players — where both sides have an account and a name. */
private fun pvpDetail(
    admins: AdminStore,
    pvp: PvpStore,
    cards: CardCatalog,
    id: String,
): AdminMatchDetail? {
    val row = pvp.matchById(id) ?: return null
    val context = admins.matchContext(KIND_PVP, id) ?: return null
    // Both columns are `NOT NULL`, so a PvP match without a red side is not a match that lost a
    // player — it is this file and `matchContext` disagreeing about which query ran. Answering 404
    // rather than inventing an empty name keeps that a missing match instead of a nameless one.
    val red = context.red ?: return null
    val replay = row.replayed(cards)
    val position = replay?.end
    return AdminMatchDetail(
        id = row.id,
        kind = KIND_PVP,
        status = row.status.name,
        format = row.formatId,
        rules = row.rules.activeRuleKeys(),
        seed = row.seed,
        blue = context.blue.scored(position?.state?.score?.blue ?: 0),
        red = red.scored(position?.state?.score?.red ?: 0),
        startedAt = context.startedAt,
        finishedAt = context.finishedAt,
        payout = AdminPayout(
            blue = row.payout[CardColor.BLUE]?.mgp ?: 0,
            red = row.payout[CardColor.RED]?.mgp ?: 0,
        ),
        hands = replay?.start?.state?.toHands(),
        moves = replay?.toMoves().orEmpty(),
        transcriptHash = null,
    )
}

/**
 * The engine's own account of each placement, and the board it left behind.
 *
 * ### A list *and* a board
 *
 * This used to be a list alone, on the argument that a second board renderer written before the
 * web client existed would be thrown away. The web client exists now, and what the inspector was
 * missing turned out not to be a renderer but the *positions*: which card sat where after move
 * six, and what the hands still held. So each move carries the board as it stood once the move
 * resolved — nine cells, the elements, the score, what each hand still holds — and the console
 * draws it from these facts alone. A board built from the replay rather than re-derived in the
 * browser is the only one that can be trusted to show what the referee saw.
 *
 * Nine cells and ten cards per move is a few kilobytes for a whole match, which is a price worth
 * paying for a screen opened a handful of times a week.
 *
 * ### Which rule explains the move
 *
 * [AdminMove.rule] carries the **first non-basic** kind among the captures, because that is the
 * one that explains the move: a `SAME` that cascades produces `SAME` and then a wave of `COMBO`s,
 * and naming the cascade rather than its cause would put `COMBO` beside a move nobody disputes. A
 * plain comparison is null rather than `BASIC` — the console shows the column only when a rule did
 * something, and "BASIC" in every other row is noise in the one place noise is expensive.
 *
 * ### The board is the one *before* any rematch
 *
 * [Replay.positions] keeps each position as the placement left it, so the ninth move of a drawn
 * Sudden Death board shows that board full, not the regrouped one that follows. [AdminMove.round]
 * is how the console tells the boards apart.
 */
private fun Replay.toMoves(): List<AdminMove> = plays.mapIndexed { at, play ->
    val after = positions[at].state
    AdminMove(
        index = at + 1,
        side = play.player.name,
        cardId = play.card.id,
        // The card as the engine held it, not a second lookup: a hand can hold a card the catalog
        // has since renamed, and the name in a dispute should be the one that was played.
        cardName = play.card.name,
        cell = play.position,
        captured = play.captures.map { it.position },
        rule = play.captures.firstOrNull { it.kind != CaptureKind.BASIC }?.kind?.name,
        round = positions[at].rematch,
        board = after.board.cells.map { placed ->
            placed?.let { AdminCell(face = it.card.face(), owner = it.owner.name) }
        },
        elements = after.board.elements.map { it?.name },
        score = AdminScore(blue = after.score.blue, red = after.score.red),
        hands = after.toHands(),
    )
}

/** What each side holds in hand, in hand order, face up — the console is nobody's opponent. */
private fun MatchState.toHands() = AdminHands(
    blue = hands[CardColor.BLUE].orEmpty().map { it.face() },
    red = hands[CardColor.RED].orEmpty().map { it.face() },
)

/**
 * A card as the board draws it: the four printed values, and nothing the rules did to them.
 *
 * Ascension, Descension and the elements move a card's *effective* values during a match, and the
 * engine applies them at comparison time rather than by rewriting the card. The console shows the
 * printed values and the cell's element beside them, which is what a player looking at the same
 * board saw — the modifiers were a badge on the card, not a different number.
 */
internal fun Card.face() = AdminCardFace(
    cardId = id,
    name = name,
    top = top,
    right = right,
    bottom = bottom,
    left = left,
    rarity = rarity,
    type = type?.name,
)

/**
 * [AdminParty] with the score filled in, which is the only part that comes from the board.
 *
 * The board is the one the match is **currently on**, which under Sudden Death is not the first:
 * a rematch replaces the board rather than accumulating onto it, so 5–4 followed by a rematch reads
 * as the rematch's score and not as a running tally. There is no running tally to report — see
 * `MatchPosition.replaying`, where the walk that produces this is written once for both tables —
 * and the move list beside it is the full account across every board.
 */
private fun AdminParty.scored(score: Int) = AdminMatchSide(accountId, name, score)

/**
 * `{"error":"NOT_FOUND"}` with a 404, which is what the console reads as `notFound`.
 *
 * Its own helper because six handlers end this way and the code has to be exactly this string —
 * `failureFor` reads the status for a 404 and never opens the body, but the two agree here so that
 * a route answering 404 from somewhere else still says the same thing.
 */
internal suspend fun ApplicationCall.notFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse(error = "NOT_FOUND"))

/**
 * Every console response, encoded by hand rather than handed to content negotiation.
 *
 * Because the server's global `Json` has `explicitNulls = false`, while this console reads `null`
 * as a value: `email === null` means the account has none, `finishedAt === null` means the match is
 * live, `subject.accountId === null` means the row is about nobody. Omitting those would turn every
 * one of them into `undefined`, which the console's strict `=== null` tests answer `false` to — so
 * a live match would render as finished and an account with no address would render as one whose
 * address simply failed to arrive.
 *
 * [ConsoleJson] therefore writes nulls, and keeps `encodeDefaults = false` for the one place the
 * console needs the *opposite*: `nextCursor` must be **absent** when there is nothing older, since
 * `scripts/audit.ts` hides its button on `cursor === undefined`. That pairing is why no other
 * property in this file may carry a default — a default value would silently vanish from the wire.
 */
internal val ConsoleJson = Json {
    explicitNulls = true
    encodeDefaults = false
}

internal suspend inline fun <reified T> ApplicationCall.respondConsole(body: T) =
    respondText(ConsoleJson.encodeToString(body), ContentType.Application.Json)

/** `admin_audit.action` for a balance correction. The set lives beside the routes that write it. */
const val CREDITED = "CREDIT"

/** `admin_audit.action` for a change to a collection or a bag. See `AdminInventory.kt`. */
const val INVENTORY_EDITED = "INVENTORY"

/**
 * The three kinds, as the console reads them back in a body.
 *
 * Upper case here and lower case in the path — `/admin/matches/pve/{id}` — because that is what the
 * console sends: its type is a union of upper-case literals and it lower-cases for the URL. Two
 * spellings of one thing is a small cost, and the alternative is a path segment whose case matters
 * to a reader who is typing it into an address bar.
 */
internal const val KIND_CREDITED = "CREDITED"
internal const val KIND_PVE = "PVE"
internal const val KIND_PVP = "PVP"

/* -- the wire ---------------------------------------------------------------------------------- */

/**
 * The dashboard. Three named groups rather than a flat list of thirteen numbers, because
 * `web-platform.md` names three ambiguities a flat list hides — "a match" is three different
 * counts, the bots inflate everything, and registered is not verified — and every consumer would
 * otherwise resolve them differently.
 */
@Serializable
data class AdminOverview(
    val accounts: AdminOverviewAccounts,
    val matches: AdminOverviewMatches,
    val economy: AdminOverviewEconomy,
    /** When the views were read, so a figure on screen can be dated. `now()`, transaction start. */
    val asOf: String,
)

@Serializable
data class AdminOverviewAccounts(
    val registered: Long,
    val verified: Long,
    val activeToday: Long,
    val activeThisWeek: Long,
    val newToday: Long,
)

@Serializable
data class AdminOverviewMatches(
    val credited: Long,
    val pve: Long,
    val pvp: Long,
    val today: Long,
)

@Serializable
data class AdminOverviewEconomy(
    /** The money supply: purses **plus** escrow. See the mapper for why not purses alone. */
    val mgp: Long,
    val lotsLive: Long,
    val mgpEscrowed: Long,
)

/** One row of a search result, and the head of [AdminPlayerDetail]. */
@Serializable
data class AdminPlayerSummary(
    val id: Long,
    val username: String,
    val email: String?,
    val emailVerified: Boolean,
    val createdAt: String,
    val seenAt: String?,
    val mgp: Int,
    /** Out of the save document, as the purse is. Zero for an account with no profile yet. */
    val level: Int,
    /** True when the account is one of the lobby-filling bots. Shown, never hidden. */
    val bot: Boolean,
)

/**
 * Everything one screen needs about one player.
 *
 * ### Flat, and the nine repeated properties are the price of that
 *
 * The console's type is `PlayerDetail extends PlayerSummary`, so on the wire the summary's fields
 * sit beside the detail's rather than under a `summary` key. `@Serializable` has no way to inline a
 * nested object into its parent, so the choice is between repeating nine properties here and
 * asking the console to read a shape it does not describe.
 *
 * Repeating them is the lesser cost, and it is bounded: [AdminStore.player] builds this from the
 * same `ResultSet` mapper the search results come from, so the two can disagree about the *names*
 * of these fields only by failing to compile — never about their values.
 */
@Suppress("LongParameterList")
@Serializable
data class AdminPlayerDetail(
    val id: Long,
    val username: String,
    val email: String?,
    val emailVerified: Boolean,
    val createdAt: String,
    val seenAt: String?,
    val mgp: Int,
    /** Level and experience, out of the save document rather than out of a column. */
    val level: Int,
    val bot: Boolean,
    /**
     * A bot's personality, as `/admin/stats/bots` sends it — see [AdminBotRow.personality]. Null
     * for every person, and for a bot the director has not drawn one for yet: [bot] is what tells
     * the two apart.
     */
    val personality: BotPersonality?,
    val xp: Long,
    /** How many cards the collection holds, and how many distinct ones. */
    val cards: Int,
    val distinctCards: Int,
    val record: AdminRecord,
    /** Newest first, across all three match tables, capped at a screenful. */
    val recentMatches: List<AdminMatchRow>,
    /**
     * Lots this player is selling **and** lots they have bid on — leading, outbid and refunded, or
     * won. [AdminStore.readLots] says why outbid is included.
     */
    val lots: List<AdminAuctionLot>,
    /** Administrative actions already taken on this account, newest first. */
    val audit: List<AdminAuditEntry>,
    /** Every card held, one entry per id with its count, in id order. */
    val collection: List<AdminOwnedCard>,
    /** The bag, in the order the game's inventory screen sorts it. */
    val bag: List<AdminBagItem>,
    /**
     * The saved decks, so the console can warn before taking away a card one of them fields.
     * Not editable here: a deck is something the player built, and `GameSave.withoutCard` argues
     * why even the game itself never rewrites one.
     */
    val decks: List<AdminDeck>,
)

@Serializable
data class AdminRecord(val wins: Int, val losses: Int, val draws: Int)

/**
 * One line of a player's history, from whichever of the three tables it came from.
 *
 * [result] is a real outcome for `CREDITED` — `WIN`, `LOSE`, `DRAW` — and a *status* for the other
 * two: `FINISHED`, `ABANDONED`, `FORFEITED`. Both are sent as they are stored rather than
 * reconciled into one vocabulary, because reconciling them would mean deciding that an abandoned
 * match was a loss, and that is a judgement the schema deliberately does not make and an operator
 * arbitrating a dispute must not have made for them.
 *
 * A `PVE` session that was paid is the exception: it carries the outcome its settlement recorded,
 * because that settlement is no longer a line of its own — see [AdminStore.readMatches].
 */
@Serializable
data class AdminMatchRow(
    /** `matches.id` is a bigint and the other two are text, so: a string, always. */
    val id: String,
    val kind: String,
    val at: String,
    val result: String,
    val format: String,
    val mgp: Int,
    /** An opponent icon id for PvE, a username for PvP, null for a side that is gone. */
    val opponent: String?,
)

/** `{operationId, amount, reason}` — the body of the one write. */
@Serializable
data class AdminCreditRequest(val operationId: String, val amount: Int, val reason: String)

/** The balance either side of the change, and whether this attempt is what caused it. */
@Serializable
data class AdminCreditReceipt(val mgpBefore: Int, val mgpAfter: Int, val applied: Boolean)

/**
 * What an audit row's `before` and `after` hold for a [CREDITED] action.
 *
 * One field, and a named type for it rather than a raw string, because the column is read by the
 * console as an opaque pair and by a person as evidence — and evidence that was assembled by string
 * concatenation is evidence with a typo in it eventually.
 */
@Serializable
data class AdminBalance(val mgp: Int)

@Suppress("LongParameterList")
@Serializable
data class AdminMove(
    /** 1-based, in the order the engine applied them. */
    val index: Int,
    val side: String,
    val cardId: Int,
    val cardName: String,
    /** Board position, 0..8, reading rows left to right. */
    val cell: Int,
    val captured: List<Int>,
    val rule: String?,
    /** Which board this was played on: 0 for the first, one more per Sudden Death rematch. */
    val round: Int,
    /** The nine cells once this move resolved, null where nothing has been placed. */
    val board: List<AdminCell?>,
    /** The Elemental rule's element per cell, by `CardType` name, null for none. */
    val elements: List<String?>,
    val score: AdminScore,
    /** What each side still held once this move resolved. */
    val hands: AdminHands,
)

/** A card's printed face. See [face] for why the rules' modifiers are not folded in. */
@Suppress("LongParameterList")
@Serializable
data class AdminCardFace(
    val cardId: Int,
    val name: String,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val left: Int,
    val rarity: Int,
    /** `CardType` name, or null for a card with no type. */
    val type: String?,
)

/** A placed card and the colour it belongs to *now* — after whatever flipped it. */
@Serializable
data class AdminCell(val face: AdminCardFace, val owner: String)

@Serializable
data class AdminScore(val blue: Int, val red: Int)

@Serializable
data class AdminHands(val blue: List<AdminCardFace>, val red: List<AdminCardFace>)

/** One side of a match: an account and a name, or a name alone when there is no account. */
@Serializable
data class AdminMatchSide(val accountId: Long?, val name: String, val score: Int)

@Serializable
data class AdminPayout(val blue: Int, val red: Int)

/**
 * The match inspector's payload.
 *
 * [seed] is nullable because only a reproducible match has one worth showing, and [transcriptHash]
 * because only a *submitted* one does. A credited match carries the hash and no move list; a
 * refereed one carries the moves and no hash. Neither absence is an error, and modelling them as
 * nullable fields on one type rather than as three types is what lets the console render all three
 * with one template.
 */
@Suppress("LongParameterList")
@Serializable
data class AdminMatchDetail(
    val id: String,
    val kind: String,
    val status: String,
    val format: String,
    /** The rules in force, by key: a dispute is usually about a rule the player did not expect. */
    val rules: List<String>,
    val seed: Int?,
    val blue: AdminMatchSide,
    val red: AdminMatchSide,
    val startedAt: String,
    val finishedAt: String?,
    val payout: AdminPayout,
    /** The hands as dealt, after any swap. Null for a credited match, whose deal is not kept. */
    val hands: AdminHands?,
    val moves: List<AdminMove>,
    val transcriptHash: String?,
)

@Suppress("LongParameterList")
@Serializable
data class AdminAuctionLot(
    val id: String,
    val status: String,
    val cardId: Int,
    val cardName: String,
    val seller: AdminParty,
    val startPrice: Int,
    /**
     * Shown here and to nobody else. `AuctionLot` on the player's wire carries `reserveMet` for
     * everybody and the figure itself only for the seller, because publishing it would tell every
     * bidder exactly what to bid. An operator investigating a lot is the one reader who needs it.
     */
    val reservePrice: Int,
    val topBid: Int?,
    val topBidder: AdminParty?,
    val bidCount: Int,
    val createdAt: String,
    val endsAt: String,
    val soldFor: Int?,
)

/**
 * One offer on one lot, as `auction_bids` holds it.
 *
 * [amount] and [fee] separately, because a hold is their sum and a refund returns both: an
 * operator reconciling a purse needs the two figures that left it, not a total they must split.
 * [refundedAt] and [settledAt] are both null for the one live hold on an open lot, and at most
 * one of them is ever set — `auction_bids_one_ending` makes that a constraint rather than a hope.
 * The console derives "leading / outbid / won" from the pair rather than being sent a label,
 * so the wire carries the facts and the wording stays the console's.
 */
@Suppress("LongParameterList")
@Serializable
data class AdminAuctionBid(
    val id: Long,
    val bidder: AdminParty,
    /** True when the bidder is one of the lobby-filling bots. False, too, for a bidder gone. */
    val bot: Boolean,
    val amount: Int,
    val fee: Int,
    val placedAt: String,
    val refundedAt: String?,
    val settledAt: String?,
)

/**
 * An account as it appears *inside* something else — a lot, a match, an audit row.
 *
 * [accountId] is null when the account is gone, and [name] is then whatever was recorded at the
 * time. The console renders the pair rather than the id: a link when there is a page to go to,
 * plain text when there is not, and it supplies its own wording for the difference.
 */
@Serializable
data class AdminParty(val accountId: Long?, val name: String)

/** The same, for an audit subject — where the name is null too, for an action about nobody. */
@Serializable
data class AdminAuditSubject(val accountId: Long?, val name: String?)

@Serializable
data class AdminAuditEntry(
    val id: Long,
    val at: String,
    /** The administrator's username, not their id: the row is read by a person. */
    val admin: String,
    val action: String,
    val subject: AdminAuditSubject,
    val reason: String?,
    /** As stored, and unparsed — see the mapper for why the console is not told their shape. */
    val before: String?,
    val after: String?,
)

/**
 * One page of the trail.
 *
 * [nextCursor] is the **only** defaulted property in this file, and the default is what makes it
 * absent rather than null on the last page — see [ConsoleJson], which explains why that distinction
 * is load-bearing and why nothing else here may carry a default.
 */
@Serializable
data class AdminAuditPage(
    val entries: List<AdminAuditEntry>,
    val nextCursor: String? = null,
)
