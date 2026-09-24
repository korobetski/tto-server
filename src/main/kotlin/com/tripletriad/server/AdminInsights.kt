package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.NpcCatalog
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchResult
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/**
 * The console's aggregate reads: the roster of every account, and the statistics about NPCs and
 * bots that no single player page can show.
 *
 * ### A store of its own, beside [AdminStore] rather than inside it
 *
 * [AdminStore] answers *one* thing at a time — an administrator, a session, a player, a match — and
 * is already the largest file in the server. What is here answers questions about *all* of them at
 * once, over tables the game writes and this never does, and grouping queries by which screen asks
 * them is the split `web-platform.md` chose for the console in the first place. It shares the one
 * column list both need, [AdminStore.PLAYER_COLUMNS], rather than a copy of it.
 *
 * ### It writes nothing
 *
 * Not even its own rows: every statement below is a `SELECT`. The two console writes are in
 * `AdminRoutes.kt` and `AdminInventory.kt` and go through [AccountStore].
 *
 * ### Bots are a filter, and the default is the caller's
 *
 * `stats` excludes bots from every count, and a search never hides them. These queries sit between
 * the two: a balance figure that counts bots measures the bots, and a roster that hides them hides
 * accounts that exist. So every query takes a [BotFilter] and the console decides — it defaults to
 * humans, and says so beside the figures.
 */
class AdminInsightStore(private val dataSource: DataSource) {

    /**
     * `GET /admin/players/list` — every account, a page at a time.
     *
     * Offset paging, unlike the audit trail's keyset: this is a table somebody browses and sorts by
     * columns that change under them — a purse, a last-seen instant — so there is no stable key to
     * page on, and a row appearing twice across two pages is a harmless artefact of browsing
     * rather than a false record. The count is its own statement in the same transaction so that an
     * offset past the end still reports how many there are.
     *
     * ### The search, as a filter on the roster
     *
     * [query] narrows the same list rather than answering a different one, so a search keeps the
     * roster's sort, its bot filter and its pages. It matches the account id exactly and the
     * username or the e-mail address *anywhere* in them — broader than `AdminStore.searchPlayers`,
     * which answers "who is this" with a handful of prefix matches, because a filter over a paged
     * list can afford to be generous: a second page costs a click, and a missing row costs a
     * support request.
     */
    fun players(sort: PlayerSort, bots: BotFilter, offset: Int, query: String?): AdminPlayerPage =
        transaction { db ->
            val search = query?.trim()?.takeIf { it.isNotEmpty() }
            val where = if (search == null) bots.sql else "${bots.sql} AND $SEARCH_CLAUSE"
            val total = db.prepareStatement(
                """
                SELECT count(*)
                FROM accounts a
                LEFT JOIN bots b ON b.account_id = a.id
                WHERE $where
                """.trimIndent(),
            ).use { statement ->
                search?.let { statement.bindSearch(it, first = 1) }
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            }
            val players = db.prepareStatement(
                """
                SELECT ${AdminStore.PLAYER_COLUMNS}
                FROM accounts a
                LEFT JOIN characters c ON c.account_id = a.id
                LEFT JOIN bots b ON b.account_id = a.id
                WHERE $where
                ORDER BY ${sort.sql}
                LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { statement ->
                val next = search?.let { statement.bindSearch(it, first = 1) } ?: 1
                statement.setInt(next, PLAYER_PAGE)
                statement.setInt(next + 1, offset)
                statement.executeQuery().use { rows -> rows.collect { it.toPlayerSummary() } }
            }
            AdminPlayerPage(players = players, total = total, offset = offset, limit = PLAYER_PAGE)
        }

    /**
     * Every NPC that has been played in the window, as counts per icon.
     *
     * ### Out of `matches`, which is where every settled PvE match lands
     *
     * A match a client submitted and a match this server refereed both end as a `matches` row —
     * `creditRefereedMatch` writes one too — so this table is the one place every finished game
     * against an NPC is counted exactly once. `pve_matches` holds the refereed ones a second time
     * and adds what `matches` cannot: the sessions nobody finished, which are [NpcTally.abandoned]
     * and come from a second statement.
     *
     * [NpcTally.margin] is the player's score minus the NPC's, averaged. The win rate says who
     * wins; the margin says by how much, and an NPC every player beats 9–1 and one they beat 6–4
     * are the same win rate and very different opponents.
     */
    @Suppress("MagicNumber")
    fun npcTallies(days: Int?, bots: BotFilter): Map<String, NpcTally> = transaction { db ->
        val tallies = db.prepareStatement(
            """
            SELECT m.opponent_icon_id AS icon,
                   count(*) AS played,
                   count(*) FILTER (WHERE m.result = 'WIN') AS wins,
                   count(*) FILTER (WHERE m.result = 'LOSE') AS losses,
                   count(*) FILTER (WHERE m.result = 'DRAW') AS draws,
                   avg(m.blue - m.red) AS margin,
                   coalesce(sum(m.mgp), 0) AS mgp,
                   coalesce(sum(m.xp), 0) AS xp,
                   count(DISTINCT m.account_id) AS players,
                   max(m.played_at) AS last_played
            FROM matches m
            LEFT JOIN bots b ON b.account_id = m.account_id
            WHERE ${bots.sql}
              AND (?::int IS NULL OR m.played_at > now() - make_interval(days => ?::int))
            GROUP BY m.opponent_icon_id
            """.trimIndent(),
        ).use { statement ->
            statement.bindDays(days, 1)
            statement.executeQuery().use { rows ->
                rows.collect { it.getString("icon") to it.toNpcTally() }.toMap()
            }
        }
        val abandoned = db.prepareStatement(
            """
            SELECT p.opponent_icon AS icon, count(*) AS abandoned
            FROM pve_matches p
            LEFT JOIN bots b ON b.account_id = p.account_id
            WHERE p.status = 'ABANDONED'
              AND ${bots.sql}
              AND (?::int IS NULL
                   OR coalesce(p.finished_at, p.created_at) > now() - make_interval(days => ?::int))
            GROUP BY p.opponent_icon
            """.trimIndent(),
        ).use { statement ->
            statement.bindDays(days, 1)
            statement.executeQuery().use { rows ->
                rows.collect { it.getString("icon") to it.getInt("abandoned") }.toMap()
            }
        }
        (tallies.keys + abandoned.keys).associateWith { icon ->
            (tallies[icon] ?: NpcTally.NONE).copy(abandoned = abandoned[icon] ?: 0)
        }
    }

    /**
     * Every bot, its profile decoded, and what it has played in the window against NPCs.
     *
     * The save is decoded here rather than read by column because a bot's record, level and
     * collection all live in it, and `BotStore.progress` already does the same for the director.
     * A roster is a few dozen rows, so decoding each is cheap.
     */
    @Suppress("MagicNumber")
    fun botRoster(days: Int?): List<BotRosterEntry> = transaction { db ->
        val pve = db.prepareStatement(
            """
            SELECT m.account_id,
                   count(*) FILTER (WHERE m.result = 'WIN') AS wins,
                   count(*) FILTER (WHERE m.result = 'LOSE') AS losses,
                   count(*) FILTER (WHERE m.result = 'DRAW') AS draws
            FROM matches m
            JOIN bots b ON b.account_id = m.account_id
            WHERE ?::int IS NULL OR m.played_at > now() - make_interval(days => ?::int)
            GROUP BY m.account_id
            """.trimIndent(),
        ).use { statement ->
            statement.bindDays(days, 1)
            statement.executeQuery().use { rows ->
                rows.collect { it.getLong("account_id") to it.toRecord() }.toMap()
            }
        }
        db.prepareStatement(
            """
            SELECT b.account_id, a.username, b.band, b.next_action_at, b.created_at, a.seen_at,
                   c.save::text AS save
            FROM bots b
            JOIN accounts a ON a.id = b.account_id
            LEFT JOIN characters c ON c.account_id = b.account_id
            ORDER BY b.band, a.username_key
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                rows.collect {
                    val accountId = it.getLong("account_id")
                    BotRosterEntry(
                        accountId = accountId,
                        username = it.getString("username"),
                        band = it.getString("band"),
                        nextActionAt = it.getTimestamp("next_action_at").iso(),
                        createdAt = it.getTimestamp("created_at").iso(),
                        seenAt = it.getTimestamp("seen_at")?.iso(),
                        save = it.getString("save")?.let { save ->
                            SaveJson.decodeFromString(GameSave.serializer(), save)
                        },
                        pve = pve[accountId] ?: AdminRecord(0, 0, 0),
                    )
                }
            }
        }
    }

    /**
     * The MGP every purse holds, bots included — the denominator for "how much of it is theirs".
     *
     * Purses rather than `stats.overview`'s money supply: a bot's share is compared against what it
     * holds, and a bot's MGP sitting in escrow is not in its purse either.
     */
    fun mgpInPurses(): Long = transaction { db ->
        db.prepareStatement(
            "SELECT coalesce(sum((save ->> 'MGP')::bigint), 0) FROM characters",
        ).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    private fun ResultSet.toNpcTally() = NpcTally(
        played = getInt("played"),
        wins = getInt("wins"),
        losses = getInt("losses"),
        draws = getInt("draws"),
        margin = getDouble("margin").takeUnless { wasNull() },
        mgp = getLong("mgp"),
        xp = getLong("xp"),
        players = getInt("players"),
        lastPlayed = getTimestamp("last_played")?.iso(),
        abandoned = 0,
    )

    private fun ResultSet.toRecord() =
        AdminRecord(wins = getInt("wins"), losses = getInt("losses"), draws = getInt("draws"))

    private fun ResultSet.toPlayerSummary() = AdminPlayerSummary(
        id = getLong("id"),
        username = getString("username"),
        email = getString("email"),
        emailVerified = getTimestamp("email_verified_at") != null,
        createdAt = getTimestamp("created_at").iso(),
        seenAt = getTimestamp("seen_at")?.iso(),
        mgp = getInt("mgp"),
        level = getInt("level"),
        bot = getBoolean("bot"),
    )

    /** Binds the window twice from [first], or two NULLs for all time. */
    private fun java.sql.PreparedStatement.bindDays(days: Int?, first: Int) {
        for (position in first..first + 1) {
            days?.let { setInt(position, it) } ?: setNull(position, java.sql.Types.INTEGER)
        }
    }

    private fun <T> ResultSet.collect(read: (ResultSet) -> T): List<T> =
        buildList { while (next()) add(read(this@collect)) }

    /** ISO 8601 in UTC — `AdminStore`'s rule for every timestamp on the console's wire. */

    /** Commits on success, rolls back on anything at all. `AccountStore.transaction`'s twin. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { db ->
        try {
            val result = block(db)
            db.commit()
            result
        } catch (failure: Throwable) {
            db.rollback()
            throw failure
        }
    }

    internal companion object {
        /** A screenful of accounts. The search keeps its own fifty. */
        const val PLAYER_PAGE = 50
    }
}

/**
 * How the roster is ordered. The SQL is a constant per entry, so what reaches the statement is
 * always one of these five strings and never anything the caller typed.
 */
enum class PlayerSort(val sql: String) {
    SEEN("a.seen_at DESC NULLS LAST, a.id DESC"),
    CREATED("a.created_at DESC, a.id DESC"),
    MGP("mgp DESC, a.id"),
    LEVEL("level DESC, a.id"),
    NAME("a.username_key, a.id"),
}

/**
 * Which accounts a figure counts. Every query using it joins `bots b` as a left join, which is what
 * [sql] tests. A constant per entry, for the reason [PlayerSort] gives.
 */
enum class BotFilter(val sql: String) {
    EXCLUDE("b.account_id IS NULL"),
    INCLUDE("TRUE"),
    ONLY("b.account_id IS NOT NULL"),
}

/** One NPC's counts. [margin] is null when nothing was played, rather than a misleading zero. */
data class NpcTally(
    val played: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val margin: Double?,
    val mgp: Long,
    val xp: Long,
    val players: Int,
    val lastPlayed: String?,
    val abandoned: Int,
) {
    companion object {
        val NONE = NpcTally(0, 0, 0, 0, null, 0L, 0L, 0, null, 0)
    }
}

/** A bot as the roster reads it, before the PvP results are folded in. */
data class BotRosterEntry(
    val accountId: Long,
    val username: String,
    val band: String,
    val nextActionAt: String,
    val createdAt: String,
    val seenAt: String?,
    /**
     * Null for a bot whose starter was never claimed, which `BotDirector` would be mid-way through.
     */
    val save: GameSave?,
    val pve: AdminRecord,
)

/* -- the routes ------------------------------------------------------------------------------- */

/**
 * `GET /admin/players/list?q=&sort=&bots=&offset=` — the roster, optionally searched.
 *
 * Unknown values fall back to the defaults rather than refusing: every one of them comes from a
 * select box or an address bar, and the first page sorted by last visit is a better answer to a
 * mistyped parameter than an error page. The same reasoning as the audit trail's cursor.
 */
internal suspend fun RoutingContext.listPlayers(admins: AdminStore, insights: AdminInsightStore) {
    authenticateAdmin(admins) ?: return
    val parameters = call.request.queryParameters
    call.respondConsole(
        insights.players(
            sort = enumParameter(parameters["sort"]) ?: PlayerSort.SEEN,
            bots = enumParameter(parameters["bots"]) ?: BotFilter.INCLUDE,
            offset = parameters["offset"]?.toIntOrNull()?.coerceIn(0, MAX_OFFSET) ?: 0,
            query = parameters["q"]?.take(MAX_QUERY),
        ),
    )
}

/**
 * `GET /admin/stats/npcs?days=&bots=` — every NPC in the catalog, with how players fare against it.
 *
 * ### Every NPC, played or not
 *
 * The catalog is walked, not the tallies: an NPC nobody plays is itself a finding about balance —
 * too expensive, too hard, unreachable — and a table that listed only what was played would hide
 * exactly those. An icon the tallies know and the catalog does not (an NPC since removed) is
 * appended with its catalog fields null, so its history is not silently dropped either.
 *
 * ### The judgement is the console's
 *
 * This answers counts. Whether 80 % is too easy depends on the band, on how many matches back it,
 * and on what the operator is looking for; the console compares each NPC to the others of its band
 * and says why it flagged one. A threshold baked in here would be a policy nobody could see.
 */
internal suspend fun RoutingContext.npcStats(
    admins: AdminStore,
    insights: AdminInsightStore,
    npcs: NpcCatalog,
) {
    authenticateAdmin(admins) ?: return
    val parameters = call.request.queryParameters
    val days = windowOf(parameters["days"])
    val bots = enumParameter(parameters["bots"]) ?: BotFilter.EXCLUDE
    val tallies = insights.npcTallies(days, bots)
    val known = npcs.all.map { npc ->
        (tallies[npc.iconId] ?: NpcTally.NONE).toRow(
            iconId = npc.iconId,
            nameKey = npc.nameKey,
            difficulty = npc.difficulty,
            band = npc.level.name,
            formats = npc.formats,
            matchFee = npc.matchFee,
        )
    }
    val catalogued = npcs.all.map { it.iconId }.toSet()
    val gone = tallies.filterKeys { it !in catalogued }.toSortedMap().map { (icon, tally) ->
        tally.toRow(icon, null, null, null, emptyList(), null)
    }
    call.respondConsole(
        AdminNpcStats(
            asOf = Instant.now().toString(),
            days = days,
            bots = bots.name,
            npcs = known + gone,
        ),
    )
}

/**
 * `GET /admin/stats/bots?days=` — the roster of bots, what they hold, and how they fare.
 *
 * ### PvP results come from replays, and are sampled
 *
 * `pvp_matches` stores a payout per colour and no result, because the result is a function of the
 * moves — see `PvpMatchRow.outcomeFor`. So each match a bot sat at is replayed here, the newest
 * [BOT_PVP_SAMPLE] of them in the window. A replay is a few hundred microseconds; the cap is what
 * keeps "all time" from becoming a request that grows with the server's age, and the response says
 * how many were read and whether the cap was reached so that nobody mistakes a sample for a census.
 */
internal suspend fun RoutingContext.botStats(
    admins: AdminStore,
    insights: AdminInsightStore,
    pvp: PvpStore,
    cards: CardCatalog,
) {
    authenticateAdmin(admins) ?: return
    val days = windowOf(call.request.queryParameters["days"])
    val roster = insights.botRoster(days)
    val botIds = roster.map { it.accountId }.toSet()
    val since = days?.let { Instant.now().minus(it.toLong(), ChronoUnit.DAYS).toEpochMilli() }
    val matches = pvp.settledWithBots(since, BOT_PVP_SAMPLE)

    val tallies = PvpTallies(matches, botIds, cards)

    call.respondConsole(
        AdminBotStats(
            asOf = Instant.now().toString(),
            days = days,
            bots = roster.map { bot -> bot.toRow(tallies.of(bot.accountId)) },
            pvpVersusHumans = tallies.versusHumans.record(),
            pvpBetweenBots = tallies.betweenBots,
            pvpSampled = matches.size,
            pvpSampleLimit = BOT_PVP_SAMPLE,
            mgpHeld = roster.sumOf { (it.save?.mgp ?: 0).toLong() },
            mgpInPurses = insights.mgpInPurses(),
        ),
    )
}

/** Every sampled match replayed once, and credited to whichever of its seats a bot held. */
private class PvpTallies(matches: List<PvpMatchRow>, botIds: Set<Long>, cards: CardCatalog) {
    private val perBot = HashMap<Long, Tally>()
    val versusHumans = Tally()
    var betweenBots = 0
        private set

    init {
        for (match in matches) {
            val seated = listOf(
                CardColor.BLUE to match.blueAccount,
                CardColor.RED to match.redAccount,
            ).filter { (_, account) -> account in botIds }
            // Both seats a bot: counted in each bot's record, and kept out of the versus-humans
            // figure, where it would add one win and one loss and say nothing about either side.
            if (seated.size == 2) betweenBots++
            for ((side, account) in seated) {
                val result = match.resultFor(side, cards) ?: continue
                perBot.getOrPut(account) { Tally() }.add(result)
                if (seated.size == 1) versusHumans.add(result)
            }
        }
    }

    fun of(accountId: Long): AdminRecord = (perBot[accountId] ?: Tally()).record()
}

private fun BotRosterEntry.toRow(pvp: AdminRecord) = AdminBotRow(
    accountId = accountId,
    username = username,
    band = band,
    level = save?.level ?: 0,
    mgp = save?.mgp ?: 0,
    cards = save?.cards?.values?.sum() ?: 0,
    distinctCards = save?.cards?.size ?: 0,
    createdAt = createdAt,
    seenAt = seenAt,
    nextActionAt = nextActionAt,
    record = AdminRecord(
        wins = save?.stats?.wins ?: 0,
        losses = save?.stats?.defeats ?: 0,
        draws = save?.stats?.draws ?: 0,
    ),
    pve = pve,
    pvp = pvp,
)

/** Wins, losses and draws being counted, from one side's point of view. */
private class Tally {
    private var wins = 0
    private var losses = 0
    private var draws = 0

    fun add(result: MatchResult) {
        when (result) {
            MatchResult.WIN -> wins++
            MatchResult.LOSE -> losses++
            MatchResult.DRAW -> draws++
        }
    }

    fun record() = AdminRecord(wins = wins, losses = losses, draws = draws)
}

@Suppress("LongParameterList")
private fun NpcTally.toRow(
    iconId: String,
    nameKey: String?,
    difficulty: Int?,
    band: String?,
    formats: List<String>,
    matchFee: Int?,
) = AdminNpcRow(
    iconId = iconId,
    nameKey = nameKey,
    difficulty = difficulty,
    band = band,
    formats = formats,
    matchFee = matchFee,
    played = played,
    wins = wins,
    losses = losses,
    draws = draws,
    abandoned = abandoned,
    averageMargin = margin,
    mgp = mgp,
    xp = xp,
    players = players,
    lastPlayed = lastPlayed,
)

/** The window in days, or null for all time — which is also what anything unreadable means. */
private fun Timestamp.iso(): String = toInstant().toString()

private fun windowOf(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it in 1..MAX_DAYS }

private inline fun <reified E : Enum<E>> enumParameter(raw: String?): E? =
    enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }

/**
 * Binds [SEARCH_CLAUSE]'s three parameters from [first] on, and answers the next free position.
 *
 * A query that is not a number matches no id, and `?::bigint` with a NULL is how that is said —
 * the same device as `AdminStore.searchPlayers`, for the same reason: binding the text and
 * letting Postgres cast it would refuse every search by name with a `22P02`.
 */
private fun PreparedStatement.bindSearch(search: String, first: Int): Int {
    search.toLongOrNull()
        ?.let { setLong(first, it) }
        ?: setNull(first, AdminStore.BIGINT)
    val pattern = with(AdminStore) { search.escapedForLike() }
    setString(first + 1, pattern)
    setString(first + 2, pattern)
    return first + SEARCH_PARAMETERS
}

/**
 * Matches an id exactly, or a username or e-mail address containing the text. The keys are the
 * lower-cased columns the unique indexes are on, so the comparison ignores case as the sign-in
 * does.
 */
private const val SEARCH_CLAUSE =
    "(a.id = ?::bigint OR a.username_key LIKE '%' || lower(?) || '%' ESCAPE '\\' " +
        "OR a.email_key LIKE '%' || lower(?) || '%' ESCAPE '\\')"

private const val SEARCH_PARAMETERS = 3

/** A search longer than any username or address is a paste gone wrong; the rest is dropped. */
private const val MAX_QUERY = 200

/** Ten years. A window wider than the server has existed is "all time", said more slowly. */
private const val MAX_DAYS = 3650

/** A hundred thousand accounts in. An offset past it is a typed URL, not a page anybody reached. */
private const val MAX_OFFSET = 100_000

/**
 * See [botStats]. Five hundred replays is well under a second, and months of bot play at the start.
 */
private const val BOT_PVP_SAMPLE = 500

/* -- the wire ---------------------------------------------------------------------------------- */

/** One page of the roster. [total] counts every account the filter admits, not just this page. */
@Serializable
data class AdminPlayerPage(
    val players: List<AdminPlayerSummary>,
    val total: Long,
    val offset: Int,
    val limit: Int,
)

@Serializable
data class AdminNpcStats(
    val asOf: String,
    /** The window, or null for all time. */
    val days: Int?,
    /** Which accounts were counted: `EXCLUDE`, `INCLUDE` or `ONLY` bots. */
    val bots: String,
    val npcs: List<AdminNpcRow>,
)

/**
 * One NPC. The catalog fields are null for an icon the catalog no longer has; the counts are from
 * the player's side, so [wins] is how often the *player* won.
 *
 * [nameKey] is the i18n key and not a name: the server ships no locale, and the console holds the
 * French names it shows.
 */
@Suppress("LongParameterList")
@Serializable
data class AdminNpcRow(
    val iconId: String,
    val nameKey: String?,
    val difficulty: Int?,
    /** The `NpcLevel` band by name. */
    val band: String?,
    val formats: List<String>,
    val matchFee: Int?,
    val played: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    /** Refereed sessions left unfinished, which `matches` never sees. */
    val abandoned: Int,
    /** Player's score minus the NPC's, averaged. Null when nothing was played. */
    val averageMargin: Double?,
    /** Paid out across those matches, as `matches` recorded it. */
    val mgp: Long,
    val xp: Long,
    val players: Int,
    val lastPlayed: String?,
)

@Suppress("LongParameterList")
@Serializable
data class AdminBotStats(
    val asOf: String,
    val days: Int?,
    val bots: List<AdminBotRow>,
    /** Every sampled match with exactly one bot in it, from the bot's side. */
    val pvpVersusHumans: AdminRecord,
    /** Sampled matches with a bot on both sides, counted in each bot's own record. */
    val pvpBetweenBots: Int,
    val pvpSampled: Int,
    val pvpSampleLimit: Int,
    val mgpHeld: Long,
    val mgpInPurses: Long,
)

@Suppress("LongParameterList")
@Serializable
data class AdminBotRow(
    val accountId: Long,
    val username: String,
    val band: String,
    val level: Int,
    val mgp: Int,
    val cards: Int,
    val distinctCards: Int,
    val createdAt: String,
    val seenAt: String?,
    val nextActionAt: String,
    /** Lifetime, out of the save. */
    val record: AdminRecord,
    /** In the window, against NPCs. */
    val pve: AdminRecord,
    /** In the window, against anybody, from the sampled replays. */
    val pvp: AdminRecord,
)
