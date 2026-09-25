package com.tripletriad.server

import com.tripletriad.data.Campaign
import com.tripletriad.data.CardValue
import com.tripletriad.model.CampaignRun
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.Deck
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.MatchAiOptions
import com.tripletriad.model.Npc
import com.tripletriad.model.NpcLevel
import com.tripletriad.model.TradeRule
import com.tripletriad.model.questDayOf
import com.tripletriad.protocol.ANY_DECK
import com.tripletriad.protocol.PvpMatchStatus
import com.tripletriad.protocol.PvpStake
import com.tripletriad.protocol.PvpStakePolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a bot decides, with no database and no referee under it.
 *
 * ### The assertion this file exists for
 *
 * [aBotSeesOnlyWhatTheRulesReveal]. Everything else here is a bot behaving sensibly; that one is
 * about a bot being an *opponent worth having* rather than one that knows your hand.
 *
 * `MatchSearch` is written to substitute every opponent card the rules do not reveal, and
 * `MatchSearchTest` in `:core` holds it to that. What is asserted here is the half that lives on
 * this side of the boundary: that [BotBrain.placement] hands it the **visibility** rather than
 * defaulting it, which is the one mistake at this call site that would silently produce a cheating
 * bot with `:core` entirely innocent.
 */
class BotBrainTest {

    private val cards = Catalogs.cards
    private val format = assertNotNull(Catalogs.formats[FORMAT])

    // ---- Placing a card ---------------------------------------------------

    /** A bot asked for a move it is not on for answers nothing rather than moving anyway. */
    @Test
    fun aBotPlaysOnlyOnItsOwnTurn() {
        val at = assertNotNull(board().position(cards))
        val onMove = assertNotNull(at.state.currentPlayer)

        assertNotNull(BotBrain.placement(at, onMove, EXPERT, Random(SEED)))
        assertNull(BotBrain.placement(at, onMove.opposite(), EXPERT, Random(SEED)))
    }

    /** What it answers is a card it holds and a square that is free. */
    @Test
    fun aBotPlaysALegalCard() {
        val at = assertNotNull(board().position(cards))
        val side = assertNotNull(at.state.currentPlayer)

        val move = assertNotNull(BotBrain.placement(at, side, EXPERT, Random(SEED)))
        assertTrue(move.handIndex in at.state.currentHand.indices, "the slot must be in the hand")
        assertTrue(move.position in at.state.playablePositions(), "the square must be free")
        assertNotNull(at.advanced(move.handIndex, move.position), "the move must be playable")
    }

    /**
     * **The bot is handed the visibility, not the board.**
     *
     * Under a closed hand every unrevealed opponent card is replaced before the search begins, so
     * *changing* those cards cannot change the move. Swap red's hand for five aces and the chosen
     * placement is identical; if this file ever passed `HandVisibility.HIDDEN` by hand, or reached
     * for `MatchPosition.state` and searched it directly, the two would diverge.
     *
     * It is the same property `MatchSearchTest` asserts inside `:core`, asserted again at the one
     * call site that could give it away.
     */
    @Test
    fun aBotSeesOnlyWhatTheRulesReveal() {
        val honest = assertNotNull(board().position(cards))
        val stacked = assertNotNull(board(red = aces()).position(cards))
        val side = assertNotNull(honest.state.currentPlayer)

        assertEquals(
            BotBrain.placement(honest, side, EXPERT, Random(SEED)),
            BotBrain.placement(stacked, side, EXPERT, Random(SEED)),
            "what the other hand holds must not reach a search that cannot see it",
        )
    }

    // ---- Choosing an opponent ---------------------------------------------

    /** With nobody available, a bot picks nobody rather than the first thing it finds. */
    @Test
    fun anEmptyRosterYieldsNoOpponent() {
        assertNull(BotBrain.opponent(emptyList(), bare(), format, PLAIN, Random(SEED)))
    }

    /**
     * **A roster spreads over the whole ladder**, rather than queueing against its top four.
     *
     * The complaint this answers: every bot of a level met the same handful of characters. Forty
     * bots, each drawn as `BotDirector` draws one, choosing once each: they should meet more than
     * the four the old sample allowed, and not only at the top.
     */
    @Test
    fun aRosterSpreadsOverTheWholeLadder() {
        val available = ladder()
        assertTrue(available.size > OLD_SAMPLE, "this fixture needs a roster to choose from")
        val top = available.takeLast(OLD_SAMPLE).map(Npc::iconId).toSet()

        val chosen = (1..DRAWS).mapNotNull { draw ->
            val personality = BotPersonality.draw(Random(draw))
            BotBrain.opponent(available, bare(), format, personality, Random(draw))?.iconId
        }.toSet()

        assertTrue(chosen.size > OLD_SAMPLE, "forty bots should not meet four people: $chosen")
        assertTrue(chosen.any { it !in top }, "somebody should have met the foot of the ladder")
    }

    /** A daring bot climbs: on the same draws, it meets harder opponents than a timid one. */
    @Test
    fun aDaringBotSeeksHarderOpponents() {
        val available = ladder()
        fun meanDifficulty(daring: Double) = (1..MANY_DRAWS).mapNotNull { draw ->
            BotBrain.opponent(available, bare(), format, PLAIN.copy(daring = daring), Random(draw))
        }.map { it.difficulty }.average()

        assertTrue(meanDifficulty(daring = 1.0) > meanDifficulty(daring = 0.0))
    }

    /**
     * A greedy bot is drawn to whoever drops a card it is missing — and stops being drawn once it
     * has the card.
     */
    @Test
    fun aMissingDropDrawsAGreedyBot() {
        val available = ladder()
        val (dropper, cardId) = available.flatMap { npc ->
            npc.itemRewards.mapNotNull { reward ->
                reward.cardId?.takeIf { reward.type == "card" && format.admitsCard(it) }
                    ?.let { Triple(npc, it, reward.rate) }
            }
        }.maxWith(compareBy<Triple<Npc, Int, Double>> { it.third }.thenBy { it.second })
            .let { (npc, id, _) -> npc to id }
        val greedy = PLAIN.copy(greed = 1.0)

        fun meetings(save: GameSave) = (1..MANY_DRAWS).count { draw ->
            BotBrain.opponent(available, save, format, greedy, Random(draw)) == dropper
        }

        assertTrue(meetings(bare()) > meetings(bare().withCard(cardId)))
    }

    /** An opponent never beaten pulls harder than one already beaten: it is the way forward. */
    @Test
    fun anUnbeatenOpponentPullsHarder() {
        val available = ladder()
        val first = available.first()

        fun meetings(save: GameSave) = (1..MANY_DRAWS).count { draw ->
            BotBrain.opponent(available, save, format, PLAIN, Random(draw)) == first
        }
        val beaten = bare().copy(npcWins = mapOf(first.iconId to 1))

        assertTrue(meetings(bare()) > meetings(beaten))
    }

    // ---- Entering a tournament --------------------------------------------

    /** A bot with the ambition, the achievement and the fee over its reserve goes in. */
    @Test
    fun anAmbitiousBotEntersALadderItHasEarned() {
        val ladder = tournament()

        assertEquals(
            ladder,
            BotBrain.tournament(earned(ladder, RICH), listOf(ladder), today(), AMBITIOUS, RESERVE),
        )
    }

    /** The place's achievement is what opens its ladder, for a bot as for a person. */
    @Test
    fun aLadderNotYetEarnedIsNotEntered() {
        val ladder = tournament()
        val unearned = bare().copy(mgp = RICH)

        assertNull(BotBrain.tournament(unearned, listOf(ladder), today(), AMBITIOUS, RESERVE))
    }

    /** The fee comes out of what lies above the reserve, never out of the reserve itself. */
    @Test
    fun theFeeMustLeaveTheReserveWhole() {
        val ladder = tournament()
        val exact = earned(ladder, RESERVE + ladder.fee)
        val short = earned(ladder, RESERVE + ladder.fee - 1)

        assertEquals(
            ladder,
            BotBrain.tournament(exact, listOf(ladder), today(), AMBITIOUS, RESERVE),
        )
        assertNull(BotBrain.tournament(short, listOf(ladder), today(), AMBITIOUS, RESERVE))
    }

    /**
     * Wanting a ladder is not affording it: a bot short of the fee still fancies it — which is
     * what the shop keeps the fee back for, see `BotShopping.shopping` — and enters only once the
     * fee leaves its reserve whole.
     */
    @Test
    fun aBotFanciesALadderBeforeItCanPayForIt() {
        val ladder = tournament()
        val short = earned(ladder, RESERVE)

        assertEquals(ladder, BotBrain.fancied(short, listOf(ladder), today(), AMBITIOUS))
        assertNull(BotBrain.tournament(short, listOf(ladder), today(), AMBITIOUS, RESERVE))
        assertNull(BotBrain.fancied(short, listOf(ladder), today(), PLAIN), "no ambition, no wish")
    }

    /** One entry a day, one run at a time — the rules `CampaignRewards.enter` would refuse on. */
    @Test
    fun oneEntryADayAndOneRunAtATime() {
        val ladder = tournament()
        val day = today()
        val entered = earned(ladder, RICH).copy(campaignEntries = mapOf(ladder.key to day))
        val running = earned(ladder, RICH).copy(campaignRun = CampaignRun(ladder.key))

        assertNull(BotBrain.tournament(entered, listOf(ladder), day, AMBITIOUS, RESERVE))
        assertNull(BotBrain.tournament(running, listOf(ladder), day, AMBITIOUS, RESERVE))
    }

    /**
     * **Ambition is the share of days a bot goes in**, decided once a day.
     *
     * No ambition never enters; half an ambition enters on some days and not others; and asked
     * twice on one day, a bot gives the same answer — a roll on every pass would say yes before
     * long whatever the odds.
     */
    @Test
    fun ambitionIsTheShareOfDaysABotGoesIn() {
        val ladder = tournament()
        val save = earned(ladder, RICH)
        val days = (0 until DAYS).map { questDayOf(NOW + it * DAY_MILLIS) }
        fun entries(personality: BotPersonality) = days.map { day ->
            BotBrain.tournament(save, listOf(ladder), day, personality, RESERVE) != null
        }

        val halfHearted = PLAIN.copy(ambition = HALF)
        assertTrue(entries(PLAIN).none { it }, "a bot with no ambition never enters")
        assertTrue(entries(halfHearted).any { it }, "half an ambition enters on some days")
        assertTrue(entries(halfHearted).any { !it }, "and stays out on others")
        assertEquals(entries(halfHearted), entries(halfHearted), "the same day, the same answer")
    }

    // ---- Developing -------------------------------------------------------

    /**
     * **What is in the bag is not owned until it is taken, and a bot takes it.**
     *
     * The assertion that catches the failure a bot would hide for months: spending every match's
     * pay on packs, opening every one of them, and fielding the same nine cards it started with
     * because nothing ever moved the drops out of the bag. `Npc.rollRewards` fills it the same way
     * after every single match. `BotShoppingTest.aPackBoughtIsAPackOpened` is the other half.
     */
    @Test
    fun theBagIsWhereTheCollectionComesFrom() {
        val save = GameSave.new("collector", createdAt = NOW).copy(mgp = RICH)
        val developed = assertNotNull(
            BotBrain.developing(save, format, cards, PLAIN, RESERVE, Random(SEED)),
        )

        assertTrue(developed.mgp < save.mgp, "the money should have gone somewhere")
        assertTrue(
            developed.ownedCardIds().size > save.ownedCardIds().size,
            "the cards should have reached the collection, not stopped in the bag",
        )
        assertTrue(developed.bag.none { it.useable }, "a bot leaves nothing unused behind it")
    }

    /** An empty bag is nothing to do, and says so rather than reporting a change. */
    @Test
    fun anEmptyBagIsLeftAlone() {
        assertNull(BotBrain.emptying(GameSave.new("idle", createdAt = NOW), Random(SEED)))
    }

    /** A bot with nothing to spend and nothing to take develops nothing. */
    @Test
    fun aBotWithNothingDevelopsNothing() {
        val bare = GameSave.new("bare", createdAt = NOW).copy(mgp = 0)
        assertNull(BotBrain.developing(bare, format, cards, PLAIN, RESERVE, Random(SEED)))
    }

    // ---- Clearing the surplus ---------------------------------------------

    /**
     * **Spare commons are sold, and that is about the Random rule as much as the money.**
     *
     * `GameSave.ownedCardIds` is one entry per copy and `MatchPreparation.randomHand` shuffles
     * exactly that list, so every unsold duplicate common is another ticket in a draw the bot does
     * not want to win. The assertion is therefore on the **draw pool** and not only on the purse.
     */
    @Test
    fun spareCommonsAreSoldOutOfTheDrawPool() {
        val common = assertNotNull(cards.all.firstOrNull { it.rarity == 1 })
        val save = GameSave.new("hoarder", createdAt = NOW).withCard(common.id, HOARD)

        val sold = assertNotNull(BotBrain.selling(save, cards))

        assertEquals(1, sold.copiesOf(common.id), "one spare survives, the rest are stock")
        assertTrue(sold.mgp > save.mgp, "selling pays")
        assertTrue(
            sold.ownedCardIds().size < save.ownedCardIds().size,
            "the Random draw pool is what actually shrank",
        )
    }

    /**
     * **A card a deck is built on is never sold.**
     *
     * `GameSave.spareCopiesOf` is the guard, and losing it would leave a deck
     * `Deck.isAffordable` refuses — which a bot would meet as a match that will not deal, several
     * passes later and nowhere near the cause.
     */
    @Test
    fun aCardADeckNeedsIsNeverSold() {
        val common = assertNotNull(cards.all.firstOrNull { it.rarity == 1 })
        val save = GameSave.new("decked", createdAt = NOW)
            .withCard(common.id, PAIR_OF_COPIES)
            .copy(decks = listOf(Deck(name = "kept", cards = List(HAND_SIZE) { common.id })))

        assertNull(
            BotBrain.selling(save, cards),
            "five copies are promised to a deck and two are held, so nothing is spare",
        )
    }

    /**
     * A bot that may use the auction house keeps one more common for it, and sells the rest.
     *
     * The counter runs in the same step that takes cards out of the bag, so without this the
     * common would be melted before `BotAuctions.listing` could ever put it up.
     */
    @Test
    fun anAuctioningBotHoldsOneMoreCommonBack() {
        val common = assertNotNull(cards.all.firstOrNull { it.rarity == 1 })
        val save = GameSave.new("lister", createdAt = NOW).withCard(common.id, HOARD)

        val sold = assertNotNull(BotBrain.selling(save, cards, auctioning = true))

        assertEquals(PAIR_OF_COPIES, sold.copiesOf(common.id), "the kept spare and the one to list")
        assertNull(
            BotBrain.selling(save.withoutCard(common.id, HOARD - PAIR_OF_COPIES), cards, true),
            "two spares is exactly what an auctioning bot keeps",
        )
    }

    /** A spare four-star is the start of a second deck, not stock to clear. */
    @Test
    fun aSpareHighCardIsKept() {
        val ace = assertNotNull(cards.all.firstOrNull { it.rarity >= 4 })
        val save = GameSave.new("rich", createdAt = NOW).withCard(ace.id, HOARD)

        assertNull(BotBrain.selling(save, cards))
    }

    // ---- Sitting down at a table ------------------------------------------

    /** A table that has just opened belongs to whoever is reading the lobby, not to a bot. */
    @Test
    fun aFreshTableIsLeftForAPerson() {
        val fresh = table(openedAt = NOW)
        assertNull(joinable(listOf(fresh), staleBefore = NOW - WAIT))
    }

    /** One nobody took is exactly what a bot is for. */
    @Test
    fun aTableNobodyTookIsJoined() {
        val stale = table(openedAt = NOW - WAIT)
        assertEquals(stale.id, joinable(listOf(stale), staleBefore = NOW - WAIT)?.id)
    }

    /** A bot never answers its own advertisement. */
    @Test
    fun aBotDoesNotJoinItself() {
        val own = table(openedAt = NOW - WAIT, host = BOT)
        assertNull(joinable(listOf(own), staleBefore = NOW - WAIT))
    }

    /**
     * **A bot risks no MGP while the deployment has not said it may.**
     *
     * Refused with the trade switch on as well: the two switches are separate on purpose, and a
     * table's purse is the one that moves money into and out of the players' economy.
     */
    @Test
    fun aBotWillNotSitDownForAnMgpWager() {
        val money = table(openedAt = NOW - WAIT, stake = PvpStake(mgp = SMALL_STAKE))
        val rich = GameSave.new("bot", createdAt = NOW).copy(mgp = RICH, level = 2)

        assertNull(joinable(listOf(money), staleBefore = NOW - WAIT, save = rich, trades = true))
    }

    /**
     * A card trade is joined with the trade switch on and refused with it off.
     *
     * One test rather than two, because the claim is that the switch is what decides: the table,
     * the bot and the clock are the same on both lines.
     */
    @Test
    fun aCardTradeIsJoinedOnlyWhileTradesAreOn() {
        val traded = table(openedAt = NOW - WAIT, stake = PvpStake(trade = TradeRule.ONE))

        assertEquals(
            traded.id,
            joinable(listOf(traded), staleBefore = NOW - WAIT, trades = true)?.id,
        )
        assertNull(joinable(listOf(traded), staleBefore = NOW - WAIT, trades = false))
    }

    /** A table staking MGP and cards needs both switches, not just the one for cards. */
    @Test
    fun aTradeWithMoneyOnItNeedsWagersToo() {
        val both = table(
            openedAt = NOW - WAIT,
            stake = PvpStake(mgp = SMALL_STAKE, trade = TradeRule.ONE),
        )
        val rich = GameSave.new("bot", createdAt = NOW).copy(mgp = RICH, level = 2)

        assertNull(joinable(listOf(both), staleBefore = NOW - WAIT, save = rich, trades = true))
        assertEquals(
            both.id,
            joinable(
                listOf(both),
                staleBefore = NOW - WAIT,
                save = rich,
                wagers = true,
                trades = true,
            )?.id,
        )
    }

    /** With wagering on, the ceiling and the purse decide — and they are the referee's numbers. */
    @Test
    fun aWageringBotIsStillBoundByTheCeiling() {
        val affordable = table(openedAt = NOW - WAIT, stake = PvpStake(mgp = SMALL_STAKE))
        val beyond = table(openedAt = NOW - WAIT, stake = PvpStake(mgp = HUGE_STAKE))
        val save = GameSave.new("gambler", createdAt = NOW).copy(mgp = RICH, level = 2)

        assertEquals(
            affordable.id,
            joinable(listOf(affordable), staleBefore = NOW - WAIT, save = save, wagers = true)?.id,
        )
        assertNull(joinable(listOf(beyond), staleBefore = NOW - WAIT, save = save, wagers = true))
    }

    // ---- Fixtures ---------------------------------------------------------

    private fun bare(): GameSave = GameSave.new("bot", createdAt = NOW)

    /** Everybody a level-99 profile may face at noon in [FORMAT], easiest first. */
    private fun ladder(): List<Npc> = Catalogs.npcs.available(
        formatId = FORMAT,
        hour = 12,
        level = 99,
    )

    /** A ladder of [FORMAT] that asks for an achievement, so earning it is what opens it. */
    private fun tournament(): Campaign = Catalogs.campaigns.all.first {
        it.format == FORMAT && it.requiresAchievement != null
    }

    /** A purse of [mgp] holding [ladder]'s achievement. */
    private fun earned(ladder: Campaign, mgp: Int): GameSave = bare().copy(
        mgp = mgp,
        achievements = mapOf(assertNotNull(ladder.requiresAchievement) to NOW),
    )

    private fun today(): String = questDayOf(NOW)

    /** The ids this format admits, strongest first — what a bot would want to own. */
    private fun playable(): List<Int> = cards.admittedBy(format)
        .sortedWith(compareByDescending<Card> { CardValue.worthOf(it) }.thenBy { it.id })
        .map { it.id }

    private fun aces(): List<Int> = playable().take(HAND_SIZE)

    /** Five cards from the tail of the format, so the two hands are not the same five. */
    private fun modest(): List<Int> = playable().takeLast(HAND_SIZE)

    private fun board(red: List<Int> = modest()) = PvpMatchRow(
        id = "board",
        blueAccount = BOT,
        redAccount = HUMAN,
        formatId = FORMAT,
        rules = GameRules(),
        seed = SEED,
        blueHand = aces(),
        redHand = red,
        first = CardColor.BLUE,
        moves = emptyList(),
        stake = PvpStake.None,
        status = PvpMatchStatus.PLAYING,
        turnDeadline = null,
    )

    private fun table(openedAt: Long, host: Long = HUMAN, stake: PvpStake = PvpStake.None) =
        PvpTableRow(
            id = "table-$openedAt-$host-${stake.mgp}-${stake.trade}",
            hostAccount = host,
            hostName = "host",
            formatId = FORMAT,
            rules = GameRules(),
            roulette = false,
            stake = stake,
            openedAt = openedAt,
            expiresAt = openedAt + PvpMatchRow.TABLE_MILLIS,
            hostDeck = ANY_DECK,
        )

    private fun joinable(
        tables: List<PvpTableRow>,
        staleBefore: Long,
        save: GameSave = GameSave.new("bot", createdAt = NOW),
        wagers: Boolean = false,
        trades: Boolean = false,
    ) = BotBrain.joinable(
        tables = tables,
        botId = BOT,
        save = save,
        stakes = PvpStakePolicy(),
        wagers = wagers,
        trades = trades,
        staleBefore = staleBefore,
    )

    private companion object {
        const val FORMAT = "ff14-standard"

        /** Distinct from every other class's, per the note in `BotDirectorTest`. */
        const val SEED = 20_260_921
        const val NOW = 1_800_000_000_000L
        const val WAIT = 45_000L

        val EXPERT: MatchAiOptions = MatchAiOptions.forLevel(NpcLevel.EXPERT)

        /** How many opponents the roster was once sampled from, which it must now exceed. */
        const val OLD_SAMPLE = 4
        const val DRAWS = 40

        /** Enough draws that a weight's pull shows through a bot's whims. */
        const val MANY_DRAWS = 400

        val PLAIN = plainPersonality()
        val AMBITIOUS = PLAIN.copy(ambition = 1.0)
        const val HALF = 0.5
        const val DAYS = 30
        const val DAY_MILLIS = 86_400_000L

        const val RESERVE = 5_000
        const val RICH = 200_000

        /** More copies than any deck could promise, and more than one spare. */
        const val HOARD = 6
        const val PAIR_OF_COPIES = 2

        /** Inside a level-2 ceiling under the default policy, and far outside it. */
        const val SMALL_STAKE = 100
        const val HUGE_STAKE = 100_000

        const val BOT = 1L
        const val HUMAN = 2L
    }
}
