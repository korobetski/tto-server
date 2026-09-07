package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.FormatCatalog
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.StarterCatalog
import com.tripletriad.data.StarterPack
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchAiOptions
import com.tripletriad.protocol.ANY_DECK
import com.tripletriad.protocol.PveMatchRequest
import com.tripletriad.protocol.PveMove
import com.tripletriad.protocol.PvpClaim
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpStakePolicy
import com.tripletriad.protocol.Unlocks
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

private val logger = LoggerFactory.getLogger("com.tripletriad.server.BotDirector")

/**
 * The accounts this server plays itself, and the loop that plays them.
 *
 * ### What a bot is, and what it is not
 *
 * It is an ordinary account — `accounts` row, `characters` document, purse, level, collection —
 * that nobody signs into. Nothing is special-cased for it anywhere else in this server: it opens
 * matches through [PveReferee], sits down at tables through [PvpReferee], is paid by
 * `MatchRewards.credit` and is bound by `PvpStakePolicy` exactly as a person is. The only thing
 * that knows it is a bot is `bots`, and the only thing that reads that is this class and the
 * metrics.
 *
 * It is **not** a second implementation of anything. Every decision it makes is `:core`'s —
 * `MatchSearch` chooses its cards, `ShopCatalog` prices its packs, `DeckLimits` bounds its deck —
 * for the reason the top of `CLAUDE.md` gives about the server itself. What is here is *when* to
 * act, never *what the rules are*.
 *
 * ### Why in-process and not a client
 *
 * A bot that spoke HTTP would exercise the real protocol, gates and throttles included, and would
 * be the better test of the server. It would also have to hold a session token, be given a
 * password that can sign in, and be scheduled somewhere else — and it would join a table seconds
 * later than this does, which is the one thing the lobby feature is about. This drives the
 * referees directly and accepts what that costs: the version gate, `authenticateUnlocked` and the
 * `RateLimit` plugin are all in the routes, so none of them is between a bot and the board.
 *
 * Both of those are re-imposed here rather than skipped:
 *
 * - the **cadence** is [BotPolicy]'s, and is what stops a bot playing at machine speed. It is not
 *   the rate limiter and does not pretend to be; it is the same intent applied where the limiter
 *   cannot reach.
 * - the **level gate** is checked before a bot sits down at a table — [Unlocks.allowsMultiplayer],
 *   the deployment's own number, so raising `TTO_UNLOCK_MULTIPLAYER` holds bots back exactly as it
 *   holds players back.
 *
 * The address gate `PvpUnlock` also applies is deliberately not simulated. It exists to make a
 * *farm* of accounts cost an inbox each, and a bot enrolled by the server it runs on is not a
 * stranger creating accounts. Simulating it would mean writing a confirmation timestamp for an
 * address that does not exist, which is worse than not simulating it.
 *
 * ### Nothing here is a deadline
 *
 * A pass that does no work costs two indexed queries. A bot that misses a pass is the most overdue
 * on the next one. The only clock a bot has to beat is a live PvP turn deadline, which is two and
 * a half minutes — see `PvpMatchRow.DEADLINE_MILLIS` — and the loop runs every couple of seconds.
 */
// Fourteen collaborators and they are one decision: everything an account that plays itself needs.
// Three catalogues and a starter table to deal from, stores and referees to act through, the
// deployment's two policies, and a clock and a generator so tests control both. Grouping any of
// them behind a holder would put an indirection between `Application.module` and the thing it
// configures, which is the argument `pveRoutes` and `pvpRoutes` make above their own suppressions.
@Suppress("LongParameterList", "TooManyFunctions")
class BotDirector(
    private val cards: CardCatalog,
    private val npcs: NpcCatalog,
    private val formats: FormatCatalog,
    private val starters: StarterCatalog,
    private val accounts: AccountStore,
    private val bots: BotStore,
    private val pve: PveStore,
    private val pvp: PvpStore,
    private val pveReferee: PveReferee,
    private val pvpReferee: PvpReferee,
    private val policy: BotPolicy,
    private val unlocks: Unlocks = Unlocks(),
    private val stakes: PvpStakePolicy = PvpStakePolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Random = { Random.Default },
) {

    /**
     * Brings the roster up to [BotPolicy.count], and answers how many it had to create.
     *
     * Called on every pass rather than once at start-up, because the roster can shrink under it: an
     * account deleted by an operator takes its `bots` row with it (`ON DELETE CASCADE`), and a
     * deployment that raised the count should not need a restart to get the extra bots.
     *
     * Bounded to [ENROL_PER_PASS] so raising the count from ten to a thousand does not turn one
     * pass into a thousand registrations and a thousand bcrypt hashes.
     */
    fun ensureRoster(): Int {
        val missing = (policy.count - bots.count()).coerceAtMost(ENROL_PER_PASS)
        if (missing <= 0) return 0
        return (1..missing).count { enrol() != null }
    }

    /**
     * Acts once for each bot that is due, and answers how many actually did something.
     *
     * Every bot is rescheduled whether or not it acted — a bot that found nothing to do waits
     * [BotPolicy.idleMillis], one that played waits a move's worth — so a bot can never be picked
     * up twice in a row without the clock having moved.
     */
    fun tick(): Int = bots.due(clock()).count { bot ->
        // One bad row must not stop the pass: a bot whose profile will not parse, or whose match
        // cannot be replayed, would otherwise take every bot behind it down with it. The same
        // judgement `Application.sweepAbandonedMatches` makes about the sweep, one level in.
        @Suppress("TooGenericExceptionCaught")
        val acted = try {
            act(bot)
        } catch (failure: Exception) {
            logger.warn("Bot {} could not act", bot.accountId, failure)
            false
        }
        bots.schedule(bot.accountId, clock() + if (acted) moveDelay() else policy.idleMillis)
        acted
    }

    /**
     * One bot, one action.
     *
     * ### The order is the priority, and it is not arbitrary
     *
     * 1. **A claim owed.** A won match sitting in `AWAITING_CLAIM` holds the loser's card in limbo
     *    and pays neither side until it is named. It is also the only thing here with somebody
     *    waiting on the other end.
     * 2. **A live PvP turn.** There is a person across the table and a deadline running.
     * 3. **A table nobody joined.** The feature this exists for. Ahead of the bot's own PvE match
     *    on purpose: a solo match has no deadline at all — "a program is never waiting", as
     *    `PveMatchStatus` puts it — so making a person wait for one would be choosing the only
     *    party that does not mind waiting.
     * 4. **A live PvE turn**, then **spending**, then **a new PvE match**. The grind, which is what
     *    turns a fresh account into one that has a collection and a level to wager with.
     */
    private fun act(bot: Bot): Boolean {
        val save = accounts.saveFor(bot.accountId) ?: return false
        return claimed(bot) ||
            playedPvp(bot) ||
            joined(bot, save) ||
            playedPve(bot) ||
            developed(bot, save) ||
            opened(bot, save)
    }

    // ---- Player versus player ---------------------------------------------

    /**
     * Names the cards on a match this bot won, if it owes a pick.
     *
     * `PvpMatchRow.autoClaim` is what the server names when nobody comes back, and it is what a bot
     * names too: the strongest cards in the loser's hand, deterministically. A cleverer choice —
     * the card the bot is missing from a set, say — is a real design decision and would want to be
     * the *server's* auto-claim as well, since an inattentive player deserves the same care.
     */
    private fun claimed(bot: Bot): Boolean {
        val row = pvp.claimsFor(bot.accountId).firstOrNull { candidate ->
            candidate.sideOf(bot.accountId)?.let { candidate.picksOwedBy(it, cards) > 0 } == true
        } ?: return false
        val side = row.sideOf(bot.accountId) ?: return false

        return pvpReferee.claim(
            row.id,
            bot.accountId,
            PvpClaim(row.autoClaim(side, cards)),
        ) is Claimed.Settled
    }

    /** Places one card in the live match, if there is one and it is this bot's turn. */
    private fun playedPvp(bot: Bot): Boolean {
        val row = pvp.liveMatchFor(bot.accountId) ?: return false
        val side = row.sideOf(bot.accountId) ?: return false
        val at = row.position(cards) ?: return false
        val move = BotBrain.placement(at, side, optionsFor(bot), random()) ?: return false

        return pvpReferee.play(
            row.id,
            bot.accountId,
            PvpMove(move.handIndex, move.position),
        ) is Played.Accepted
    }

    /**
     * Sits down at a table nobody has joined, if this bot is allowed to.
     *
     * The level gate is checked here rather than left to the referee because the referee does not
     * check it — `authenticateUnlocked` does, and that is in the routes. See the class KDoc.
     */
    // ReturnCount: three refusals before the join, and they are three different reasons a bot is
    // not this table's business. Naming each where it is decided is the point of them.
    @Suppress("ReturnCount")
    private fun joined(bot: Bot, save: GameSave): Boolean {
        if (!unlocks.allowsMultiplayer(save)) return false
        if (pvp.liveMatchFor(bot.accountId) != null) return false

        val now = clock()
        val table = BotBrain.joinable(
            tables = pvp.openTables(now),
            botId = bot.accountId,
            save = save,
            stakes = stakes,
            wagers = policy.wagers,
            staleBefore = now - policy.tableWaitMillis,
        ) ?: return false

        // The terms are public and the deck is chosen from them, exactly as a person reading the
        // lobby would: the table states its rules, and `deckFor` answers which of the bot's three
        // decks those rules want. A roulette table draws further rules when the match opens, so
        // what is read here is the declared half — which is also all the joiner is shown.
        val deck = formats[table.formatId]
            ?.let { BotDecks.deckFor(save, it, cards, table.rules) }
            ?: ANY_DECK

        val joined = pvpReferee.joinTable(table.id, bot.accountId, deck)
        if (joined is Joined.Playing) {
            logger.info("Bot {} joined table {}", bot.accountId, table.id)
            return true
        }
        return false
    }

    // ---- Player versus environment ----------------------------------------

    /**
     * Places one card in the solo match, if there is one.
     *
     * One placement per pass even though the referee answers with the opponent's reply already
     * made: the point of the cadence is that a bot takes a human amount of time over a board, and
     * playing a whole match inside one pass would undo it.
     */
    private fun playedPve(bot: Bot): Boolean {
        val row = pve.activeFor(bot.accountId) ?: return false
        val at = row.position(cards) ?: return false
        // Blue is the player's colour in a refereed solo match — `PveReferee.opponentMove` is the
        // red half of this same call.
        val move = BotBrain.placement(at, CardColor.BLUE, optionsFor(bot), random()) ?: return false

        return pveReferee.play(
            row.id,
            bot.accountId,
            PveMove(move.handIndex, move.position),
        ) is Moved.Accepted
    }

    /**
     * Spends what the match paid, takes what is in the bag, clears the surplus, and rebuilds.
     *
     * ### The decision is made twice, and that is not a duplicate
     *
     * Once on the profile [act] already read — which costs nothing and answers "is there anything
     * to do here" for the common case, where there is not — and again inside
     * [AccountStore.mutate], against the profile that is about to be written. The first is a read
     * anything could have invalidated; the second is the one that counts. Without the first, a bot
     * with nothing to develop would take a row lock and write an unchanged profile on every pass
     * it had nothing else to do.
     *
     * A bot only develops between matches. There is no rule against opening a pack mid-board, but
     * a profile written underneath a live match is the sort of thing that is fine until the day
     * the deal is re-read, and there is nothing to gain from it.
     */
    private fun developed(bot: Bot, save: GameSave): Boolean {
        val format = formatFor() ?: return false
        if (BotBrain.developing(save, format, cards, policy.reserve, random()) == null) return false

        val outcome = accounts.mutate(bot.accountId) { stored ->
            val changed = BotBrain.developing(stored, format, cards, policy.reserve, random())
            Outcome(
                changed?.copy(lastSave = clock(), saveNumber = stored.saveNumber + 1) ?: stored,
                changed != null,
            )
        }
        return outcome?.detail == true
    }

    /** Sits down against an opponent, which is what a bot does when it has nothing else to do. */
    private fun opened(bot: Bot, save: GameSave): Boolean {
        val format = formatFor() ?: return false
        val available = npcs.available(
            formatId = format.id,
            hour = hourOf(clock()),
            level = save.level,
            earned = save.achievements.keys,
        ).filter { it.isUnlockedFor(save) }

        val npc = BotBrain.opponent(available, random()) ?: return false
        // An opponent's *declared* rules, which is what the selection screen shows a player before
        // they pick a deck. An opponent that declares the roulette has the rest of its rules drawn
        // when the match is dealt — `PveMatches.rulesFor` — and nobody, bot or person, can choose
        // a deck against those.
        val dealt = pveReferee.open(
            bot.accountId,
            PveMatchRequest(
                opponentIconId = npc.iconId,
                formatId = format.id,
                deck = BotDecks.deckFor(save, format, cards, npc.gameRules()),
            ),
        )
        return dealt is Dealt.Playing
    }

    // ---- Enrolling --------------------------------------------------------

    /**
     * Creates one account, grants it a starter box, and enrols it. Null if the name collided.
     *
     * ### The password is a secret nobody keeps
     *
     * A bot is never signed into — the director drives the referees in-process and opens no
     * session — so its password exists only because the column is `NOT NULL`. It is a fresh
     * [Tokens.issue] hashed and dropped on the floor, which means *nobody* can sign in as a bot,
     * the operator included. A shared or derivable password would be a set of accounts with a
     * collection and a purse and one credential between them.
     *
     * ### No address, on purpose
     *
     * `email` is null. Registration through `POST /accounts` requires one, and a bot does not go
     * through it: there is no inbox to confirm and no confirmation to send. The consequence is that
     * a bot could not pass `PvpUnlock` if it ever went through the routes, which it does not — see
     * the class KDoc on why the level gate is simulated and this one is not.
     *
     * ### The starter comes with the account
     *
     * A character created by registering owns nothing at all, and a profile with no cards cannot
     * field a deck or be dealt a hand. `POST /me/starter` is where a player fixes that; a bot has
     * no client to call it, so the box is opened here — with this server's generator, from this
     * server's catalogue, exactly as that route does. The **authored** deck the box comes with is
     * kept rather than rebuilt: [BotBrain.rebuilt] ranks by rarity alone and would replace a hand
     * somebody designed with one nine cards can barely differ from. It takes over later, once the
     * collection is wide enough for the ranking to mean something.
     */
    // ReturnCount: a name that collided, a registration that collided, and an enrolment that lost
    // a race — each is a distinct way for a pass to create nothing, and each is worth its own line.
    @Suppress("ReturnCount")
    private fun enrol(): Long? {
        val generator = random()
        val name = nameFor(generator)
        if (accounts.usernameTaken(name)) return null

        val save = StarterPack.grantedTo(
            GameSave.new(username = name, createdAt = clock()),
            starters,
            cards.byId,
            generator,
            null,
        )
        val accountId = accounts.register(
            username = name,
            passwordHash = PasswordHasher.hash(Tokens.issue()),
            save = save,
            email = null,
        ) ?: return null

        if (!bots.enrol(accountId, policy.band, clock())) return null
        logger.info("Enrolled bot account {} at band {}", accountId, policy.band)
        return accountId
    }

    /**
     * A name for a new bot: the deployment's prefix and four random digits.
     *
     * Random rather than sequential because a sequence is a census — `Duelist-0007` says how many
     * there are and in what order they arrived, on a name every player can read in the lobby. A
     * collision is answered by giving up on this pass rather than retrying, since the next pass is
     * two seconds away and the space is ten thousand wide per prefix.
     */
    private fun nameFor(generator: Random): String =
        "${policy.namePrefix}${generator.nextInt(NAME_SPACE).toString().padStart(NAME_DIGITS, '0')}"

    // ---- Small answers ----------------------------------------------------

    /** How hard this bot plays. The row's band, which `V16__bots.sql` says why it stores. */
    private fun optionsFor(bot: Bot): MatchAiOptions = MatchAiOptions.forLevel(bot.band)

    /** The format bots play in, or null when this deployment does not have it. */
    private fun formatFor() = formats[policy.formatId]

    /**
     * The wall-clock hour `NpcCatalog.available` filters on, in UTC.
     *
     * UTC rather than the host's zone deliberately: an opponent that is only around in the evening
     * should be around at the same instant for every bot in the roster, and a server that is moved
     * between regions should not quietly change which opponents its bots meet.
     */
    private fun hourOf(at: Long): Int = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).hour

    /** A human-ish pause, drawn fresh so a roster does not move in lockstep. */
    private fun moveDelay(): Long =
        policy.moveMinMillis + random().nextLong(policy.moveSpreadMillis + 1)

    private companion object {
        /** How many bots one pass may create. See [ensureRoster]. */
        const val ENROL_PER_PASS = 2

        /** Four digits, so a prefix holds ten thousand names. See [nameFor]. */
        const val NAME_DIGITS = 4
        const val NAME_SPACE = 10_000
    }
}
