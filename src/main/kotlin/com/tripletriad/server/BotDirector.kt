package com.tripletriad.server

import com.tripletriad.data.Campaign
import com.tripletriad.data.CampaignCatalog
import com.tripletriad.data.CampaignEntry
import com.tripletriad.data.CampaignRewards
import com.tripletriad.data.CardCatalog
import com.tripletriad.data.Format
import com.tripletriad.data.FormatCatalog
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.PveMatches
import com.tripletriad.data.StarterCatalog
import com.tripletriad.data.StarterPack
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchAiOptions
import com.tripletriad.model.questDayOf
import com.tripletriad.protocol.ANY_DECK
import com.tripletriad.protocol.AuctionDuration
import com.tripletriad.protocol.AuctionOutcome
import com.tripletriad.protocol.BidRequest
import com.tripletriad.protocol.ListCardRequest
import com.tripletriad.protocol.PveMatchRequest
import com.tripletriad.protocol.PveMove
import com.tripletriad.protocol.PvpClaim
import com.tripletriad.protocol.PvpMove
import com.tripletriad.protocol.PvpStakePolicy
import com.tripletriad.protocol.Unlocks
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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
 *   holds players back. The auction house's is [Unlocks.allowsAuction], checked here and again by
 *   [AuctionStore] itself.
 *
 * The address gate `PvpUnlock` also applies is deliberately not simulated. It exists to make a
 * *farm* of accounts cost an inbox each, and a bot enrolled by the server it runs on is not a
 * stranger creating accounts. Simulating it would mean writing a confirmation timestamp for an
 * address that does not exist, which is worse than not simulating it.
 *
 * ### Every bot is somebody
 *
 * A [BotPersonality] is drawn for each bot once and kept — `V20__bot_personality.sql` — and every
 * choice below that has more than one good answer goes through it: the set it collects and so the
 * format it plays, how much it keeps back, what it saves for, which opponent it sits down against,
 * which pack it buys, what it bids and asks, whether it goes in for today's tournament, and how
 * long it takes about all of it. The deployment's [BotPolicy] is the frame: a personality can make
 * a bot slower, more patient or more careful than the policy asks, never less.
 *
 * ### Nothing here is a deadline
 *
 * A pass that does no work costs two indexed queries. A bot that misses a pass is the most overdue
 * on the next one. The only clock a bot has to beat is a live PvP turn deadline, which is two and
 * a half minutes — see `PvpMatchRow.DEADLINE_MILLIS` — and the loop runs every couple of seconds.
 */
// Seventeen collaborators and they are one decision: everything an account that plays itself
// needs. Four catalogues and a starter table to deal from, stores and referees to act through, the
// deployment's two policies, and a clock and a generator so tests control both. Grouping any of
// them behind a holder would put an indirection between `Application.module` and the thing it
// configures, which is the argument `pveRoutes` and `pvpRoutes` make above their own suppressions.
@Suppress("LongParameterList", "TooManyFunctions")
class BotDirector(
    private val cards: CardCatalog,
    private val npcs: NpcCatalog,
    private val formats: FormatCatalog,
    private val starters: StarterCatalog,
    private val campaigns: CampaignCatalog,
    private val accounts: AccountStore,
    private val bots: BotStore,
    private val pve: PveStore,
    private val pvp: PvpStore,
    private val auctions: AuctionStore,
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
     * up twice in a row without the clock having moved. Both waits are stretched by the bot's own
     * `BotPersonality.pace`, which is never below one: some bots take their time, none is quicker
     * than the policy.
     *
     * A bot with no personality — enrolled before there were any — is drawn one first.
     */
    fun tick(): Int = bots.due(clock()).count { bot ->
        val personality = bot.personality ?: personalized(bot)
        // One bad row must not stop the pass: a bot whose profile will not parse, or whose match
        // cannot be replayed, would otherwise take every bot behind it down with it. The same
        // judgement `Application.sweepAbandonedMatches` makes about the sweep, one level in.

        @Suppress("TooGenericExceptionCaught")
        val acted = try {
            act(bot, personality)
        } catch (failure: Exception) {
            logger.warn("Bot {} could not act", bot.accountId, failure)
            false
        }
        val pause = if (acted) moveDelay() else policy.idleMillis
        bots.schedule(bot.accountId, clock() + personality.paced(pause))
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
     * 4. **A live PvE turn.** The bot's own match, which nothing else should interrupt.
     * 5. **The auction house**, ahead of the shop: a card it needs, on offer now, is a better use
     *    of the purse than a pack, and a lot does not wait.
     * 6. **Spending** — a card it has saved for, or a pack — and looking after the collection.
     * 7. **The next rung** of a tournament it is in, ahead of anything new: the fee is paid, and
     *    the run is what it paid for.
     * 8. **Entering a tournament**, when it has the achievement, the fee over its reserve, and the
     *    ambition today.
     * 9. **A new PvE match.** The grind, which is what turns a fresh account into one that has a
     *    collection and a level to wager with.
     */
    private fun act(bot: Bot, personality: BotPersonality): Boolean {
        val save = accounts.saveFor(bot.accountId) ?: return false
        return claimed(bot) ||
            playedPvp(bot) ||
            joined(bot, save, personality) ||
            playedPve(bot) ||
            auctioned(bot, save, personality) ||
            developed(bot, save, personality) ||
            climbed(bot, save) ||
            entered(bot, save, personality) ||
            opened(bot, save, personality)
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
    // ReturnCount: four ways to have nothing to place — no match, no side, an unreplayable board,
    // and a board that is not this bot's to move on — plus attending, which is an action of its
    // own. Each is a different fact and worth naming where it is decided.
    @Suppress("ReturnCount")
    private fun playedPvp(bot: Bot): Boolean {
        val row = pvp.liveMatchFor(bot.accountId) ?: return false
        val side = row.sideOf(bot.accountId) ?: return false

        // **Opening the board is an action, and a bot is its own board.**
        //
        // Since `V16__pvp_pairing.sql` the turn clock does not start until *both* sides have been
        // seen, and `PvpRoutes.attend` is what records a sighting — called by the board on open and
        // deliberately not by the poll. A bot reads its match straight out of the store, so nothing
        // would ever attend for it: the match would sit unattended until `sweepPairing` closed it
        // as `ABANDONED`, paying nobody, and the person across the table would have waited for a
        // match that never began.
        //
        // Attending counts as this pass's action so the first card comes a move-delay later, which
        // is also what a person does — open the board, then think.
        if (row.seenAt(side) == null) {
            return pvpReferee.attend(row.id, bot.accountId) != null
        }

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
     *
     * Only a table in a format one of its decks would be dealt in: a bot collecting one set has
     * nothing to bring to a table of the other, and sitting down with nothing to bring is taking a
     * person's match to lose it. And only once the table has stood for the bot's own wait —
     * `BotPersonality.waited`, never shorter than the policy's — so the roster does not pounce on
     * a table as one.
     */
    // ReturnCount: three refusals before the join, and they are three different reasons a bot is
    // not this table's business. Naming each where it is decided is the point of them.
    @Suppress("ReturnCount")
    private fun joined(bot: Bot, save: GameSave, personality: BotPersonality): Boolean {
        if (!unlocks.allowsMultiplayer(save)) return false
        if (pvp.liveMatchFor(bot.accountId) != null) return false

        val now = clock()
        val table = BotBrain.joinable(
            tables = pvp.openTables(now).filter { fieldable(save, it.formatId) != null },
            botId = bot.accountId,
            save = save,
            stakes = stakes,
            wagers = policy.wagers,
            trades = policy.trades,
            staleBefore = now - personality.waited(policy.tableWaitMillis),
        ) ?: return false

        // The terms are public and the deck is chosen from them, exactly as a person reading the
        // lobby would: the table states its rules, and `deckFor` draws among the bot's decks those
        // rules want. A roulette table draws further rules when the match opens, so what is read
        // here is the declared half — which is also all the joiner is shown.
        val deck = formats[table.formatId]
            ?.let { BotDecks.deckFor(save, it, cards, table.rules, random()) }
            ?: ANY_DECK

        val joined = pvpReferee.joinTable(table.id, bot.accountId, deck)
        if (joined is Joined.Playing) {
            // Immediately, rather than on the next pass: the host has been waiting since the table
            // went up, and the clock does not start until both sides are seen. See `playedPvp`,
            // which attends anything this missed — a restart between the join and here, say.
            pvpReferee.attend(joined.match.id, bot.accountId)
            logger.info("Bot {} joined and attended table {}", bot.accountId, table.id)
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
    // ReturnCount: an owed opening is an action of its own, like attending in `playedPvp`, and
    // the three ways to have nothing to place are three different facts.
    @Suppress("ReturnCount")
    private fun playedPve(bot: Bot): Boolean {
        val row = pve.activeFor(bot.accountId) ?: return false
        val at = row.position(cards) ?: return false

        // **Reading the board is what starts a match the opponent won the toss for.**
        //
        // `PveReferee.open` deals the board untouched, and the opponent's opening move is owed
        // until the match is read through `PveReferee.view` — which a client's board does once its
        // announcements are over. A bot reads its match straight out of the store, so nothing
        // would ever pay it: with no card of its own to place, the pass fell through to [opened],
        // and dealing a new match abandoned this one. That was every other solo match a bot sat
        // down to. Reading it counts as this pass's action, exactly as attending does in PvP.
        if (at.state.currentPlayer == CardColor.RED) {
            return pveReferee.view(row.id, bot.accountId) != null
        }

        // Blue is the player's colour in a refereed solo match — `PveReferee.opponentMove` is the
        // red half of this same call.
        val move = BotBrain.placement(at, CardColor.BLUE, optionsFor(bot), random()) ?: return false

        return pveReferee.play(
            row.id,
            bot.accountId,
            PveMove(move.handIndex, move.position),
        ) is Moved.Accepted
    }

    // ---- The auction house -------------------------------------------------

    /**
     * Opens a lot for a spare card, or bids on a card it needs — one of the two, once.
     *
     * Listing comes first because it is the one that frees something: a card nobody plays turned
     * into MGP a bid can then use. `BotAuctions` chooses both, and says why each stays small.
     *
     * ### Through the store, like a person's request, minus the route
     *
     * [AuctionStore.list] and [AuctionStore.bid] are what `POST /auctions` and `/auctions/bid`
     * call, and they re-check the level, the floor, the ceiling, the purse and the lot count
     * inside their own transaction. What the route adds and this does not is the version gate,
     * the rate limit and the confirmed address — see the class KDoc for why each is either
     * simulated here or deliberately not.
     *
     * Each call is given a fresh operation id: the idempotency the key buys is against a person
     * pressing twice, and a bot that wants to bid again on a later pass is placing a new bid.
     */
    private fun auctioned(bot: Bot, save: GameSave, personality: BotPersonality): Boolean {
        val format = formatOf(personality)?.takeIf { auctioning(save) } ?: return false
        val own = auctions.mine(bot.accountId).lots
        val listing = BotAuctions.listing(save, cards, own, personality)

        val response = if (listing != null) {
            val request = ListCardRequest(
                cardId = listing.cardId,
                startPrice = listing.price,
                reservePrice = listing.price,
                duration = AuctionDuration.MEDIUM,
                operationId = operationId(),
            )
            auctions.list(bot.accountId, request)
        } else {
            BotAuctions.bidding(
                save = save,
                format = format,
                cards = cards,
                lots = auctions.browse(bot.accountId).lots,
                own = own,
                personality = personality,
            )?.let { bid ->
                auctions.bid(bot.accountId, BidRequest(bid.lotId, bid.amount, operationId()))
            }
        }
        return accepted(response)
    }

    /** Whether this bot may use the auction house: the deployment's switch, then the level. */
    private fun auctioning(save: GameSave): Boolean = policy.auctions && unlocks.allowsAuction(save)

    /** Whether the auction house did what it was asked, rather than answering with a refusal. */
    private fun accepted(response: String?): Boolean =
        response?.let { ApiJson.decodeFromString<AuctionOutcome>(it).refusal == null } == true

    // ---- Developing -------------------------------------------------------

    /**
     * Spends what the match paid, takes what is in the bag, clears the surplus, and rebuilds.
     *
     * Spending is out of what lies above the bot's own reserve — `BotPersonality.reserveOver` the
     * policy's — and above what it is saving for: a card's price, and the fee of the tournament it
     * means to enter today ([fancied]). Without that last one, a bot would spend every match's pay
     * on packs a step before [entered] could see it, and never have the fee. See [BotShopping].
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
    private fun developed(bot: Bot, save: GameSave, personality: BotPersonality): Boolean {
        val format = formatOf(personality) ?: return false
        val reserve = personality.reserveOver(policy.reserve)
        val day = questDayOf(clock())
        fun plan(profile: GameSave) = BotBrain.developing(
            save = profile,
            format = format,
            cards = cards,
            personality = personality,
            reserve = reserve,
            random = random(),
            auctioning = auctioning(profile),
            earmarked = fancied(profile, personality, day)?.fee ?: 0,
        )
        if (plan(save) == null) return false

        val outcome = accounts.mutate(bot.accountId) { stored ->
            val changed = plan(stored)
            Outcome(
                changed?.copy(lastSave = clock(), saveNumber = stored.saveNumber + 1) ?: stored,
                changed != null,
            )
        }
        return outcome?.detail == true
    }

    // ---- Tournaments -------------------------------------------------------

    /**
     * Sits down against the next rung of the tournament this bot is in, if it is in one.
     *
     * Through [PveReferee.open] like any other match, naming the ladder — which is what makes the
     * referee check that the opponent asked for is the one on the run's current rung, and credit
     * the match to the run. The deck is chosen against the rung's declared rules, which a ladder
     * states on its entry screen.
     *
     * A run whose rung cannot be dealt — the ladder gone from the catalogue, or no deck of the
     * bot's admitted by its format any more — is left as it is, and the bot plays an ordinary match
     * instead. It is not abandoned, because a person cannot abandon one either: a run ends by
     * being lost or by being won.
     */
    private fun climbed(bot: Bot, save: GameSave): Boolean {
        val run = save.campaignRun ?: return false
        val ladder = campaigns.byKey(run.campaignKey) ?: return false
        val rung = ladder.stepAt(run.step) ?: return false
        val format = fieldable(save, ladder.format) ?: return false

        val dealt = pveReferee.open(
            bot.accountId,
            PveMatchRequest(
                opponentIconId = rung.npc.iconId,
                formatId = format.id,
                deck = BotDecks.deckFor(save, format, cards, rung.npc.gameRules(), random()),
                campaignKey = ladder.key,
            ),
        )
        return dealt is Dealt.Playing
    }

    /**
     * Pays to enter a tournament, when this bot fancies one today. See [BotBrain.tournament].
     *
     * The same arithmetic `POST /me/campaign/enter` runs — `CampaignRewards.enter`, on today's
     * UTC day — and the same shape [developed] has: decided once on the profile already read, and
     * again against the profile about to be written, so a pass with nothing to enter takes no lock.
     */
    private fun entered(bot: Bot, save: GameSave, personality: BotPersonality): Boolean {
        val now = clock()
        val day = questDayOf(now)
        val reserve = personality.reserveOver(policy.reserve)
        BotBrain.tournament(save, ladders(save), day, personality, reserve) ?: return false

        val outcome = accounts.mutate(bot.accountId) { stored ->
            val entry = BotBrain.tournament(stored, ladders(stored), day, personality, reserve)
                ?.let { CampaignRewards.enter(stored, it, day, now) }
            val entered = (entry as? CampaignEntry.Entered)?.save
            Outcome(
                entered?.copy(lastSave = now, saveNumber = stored.saveNumber + 1) ?: stored,
                entered?.campaignRun?.campaignKey,
            )
        }
        val key = outcome?.detail
        key?.let { logger.info("Bot {} entered tournament {}", bot.accountId, it) }
        return key != null
    }

    // ---- Free play --------------------------------------------------------

    /**
     * Sits down against an opponent, which is what a bot does when it has nothing else to do.
     *
     * In the format of the set it collects, against an opponent drawn by its own tastes — see
     * [BotBrain.opponent].
     */
    private fun opened(bot: Bot, save: GameSave, personality: BotPersonality): Boolean {
        val format = formatOf(personality) ?: return false
        val available = npcs.available(
            formatId = format.id,
            hour = hourOf(clock()),
            level = save.level,
            earned = save.achievements.keys,
        ).filter { it.isUnlockedFor(save) }

        val npc = BotBrain.opponent(available, save, format, personality, random()) ?: return false
        // An opponent's *declared* rules, which is what the selection screen shows a player before
        // they pick a deck. An opponent that declares the roulette has the rest of its rules drawn
        // when the match is dealt — `PveMatches.rulesFor` — and nobody, bot or person, can choose
        // a deck against those.
        val dealt = pveReferee.open(
            bot.accountId,
            PveMatchRequest(
                opponentIconId = npc.iconId,
                formatId = format.id,
                deck = BotDecks.deckFor(save, format, cards, npc.gameRules(), random()),
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
     * left as it is here; [BotDecks.decking] rebuilds the bot's decks on its first developing pass,
     * from whatever the collection holds by then.
     *
     * The box is one of its favourite set's — `StarterCatalog.forBlock`, for a block its format
     * admits, drawn when there is more than one — which is the choice a player makes on the starter
     * screen. A bot collecting FF8 that opened FF14's box would own nothing its format deals, and
     * would spend its first weeks unable to sit down anywhere. Null, when the format has no box of
     * its own, is `StarterPack`'s default: the first one.
     */
    // ReturnCount: a name that collided, a registration that collided, and an enrolment that lost
    // a race — each is a distinct way for a pass to create nothing, and each is worth its own line.
    @Suppress("ReturnCount")
    private fun enrol(): Long? {
        val generator = random()
        val name = nameFor(generator)
        if (accounts.usernameTaken(name)) return null

        val personality = BotPersonality.draw(generator)
        val starter = formatOf(personality)?.blocks
            ?.mapNotNull(starters::forBlock)
            ?.randomOrNull(generator)
        val save = StarterPack.grantedTo(
            GameSave.new(username = name, createdAt = clock()),
            starters,
            cards.byId,
            generator,
            starter,
        )
        val accountId = accounts.register(
            username = name,
            passwordHash = PasswordHasher.hash(Tokens.issue()),
            save = save,
            email = null,
        ) ?: return null

        if (!bots.enrol(accountId, policy.band, clock(), personality)) return null
        logger.info(
            "Enrolled bot account {} at band {}, a {} collecting {}",
            accountId,
            policy.band,
            personality.archetype,
            personality.favourite,
        )
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

    /** How hard this bot plays. The row's band, which `V17__bots.sql` says why it stores. */
    private fun optionsFor(bot: Bot): MatchAiOptions = MatchAiOptions.forLevel(bot.band)

    /**
     * The format this bot plays in: the one its favourite set is spelled as, or `TTO_BOTS_FORMAT`
     * when this deployment does not ship that one. Null when it has neither.
     *
     * The fallback rather than a bot that does nothing: a personality is stored, and a deployment
     * that drops a format should not strand every bot drawn to collect it.
     */
    private fun formatOf(personality: BotPersonality): Format? =
        formats[personality.favourite.formatId] ?: formats[policy.formatId]

    /**
     * [formatId]'s format, when this bot holds a deck that format would deal it — null otherwise.
     *
     * The same question `PveReferee.open` answers with a refusal, asked first so that a bot does
     * not sit down at a table or pay a tournament's fee it has nothing to bring to.
     */
    private fun fieldable(save: GameSave, formatId: String): Format? =
        formats[formatId]?.takeIf { PveMatches.playableDecks(save, cards, it).isNotEmpty() }

    /**
     * The tournaments this bot could play: those whose format one of its decks would field.
     *
     * Asked once per format rather than once per ladder — there are two formats and a couple of
     * dozen ladders, and this runs on every pass that reaches the shop.
     */
    private fun ladders(save: GameSave): List<Campaign> {
        val fielded = campaigns.all.map { it.format }.distinct()
            .filter { fieldable(save, it) != null }
            .toSet()
        return campaigns.all.filter { it.format in fielded }
    }

    /** The tournament this bot means to enter on [day], paid for or not. See [BotBrain.fancied]. */
    private fun fancied(save: GameSave, personality: BotPersonality, day: String): Campaign? =
        BotBrain.fancied(save, ladders(save), day, personality)

    /**
     * Draws a personality for a bot that has none, and keeps it.
     *
     * Only a bot enrolled before `V20__bot_personality.sql` gets here. It keeps the set it has been
     * collecting — `TTO_BOTS_FORMAT`'s, or FF14's when that is not one of the sets — so a
     * collection built over weeks is not stranded in a format the bot has stopped playing.
     * Everything else is drawn as for a new bot.
     */
    private fun personalized(bot: Bot): BotPersonality =
        BotPersonality.draw(random(), FavouriteSet.of(policy.formatId) ?: FavouriteSet.FF14).also {
            bots.personalize(bot.accountId, it)
            logger.info(
                "Bot {} is a {} collecting {}",
                bot.accountId,
                it.archetype,
                it.favourite,
            )
        }

    /**
     * The wall-clock hour `NpcCatalog.available` filters on, in UTC.
     *
     * UTC rather than the host's zone deliberately: an opponent that is only around in the evening
     * should be around at the same instant for every bot in the roster, and a server that is moved
     * between regions should not quietly change which opponents its bots meet.
     */
    private fun hourOf(at: Long): Int = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).hour

    /**
     * A key nobody else will have minted. A UUID rather than [random], because a test fixing the
     * generator must not make two bots' operations collide in `applied_operations`.
     */
    private fun operationId(): String = UUID.randomUUID().toString()

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
