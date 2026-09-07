package com.tripletriad.server

import com.tripletriad.data.CardValue
import com.tripletriad.model.BoosterItem
import com.tripletriad.model.Card
import com.tripletriad.model.CardColor
import com.tripletriad.model.CardItem
import com.tripletriad.model.Deck
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.HAND_SIZE
import com.tripletriad.model.MatchAiOptions
import com.tripletriad.model.Npc
import com.tripletriad.model.NpcLevel
import com.tripletriad.model.TradeRule
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
        assertNull(BotBrain.opponent(emptyList(), Random(SEED)))
    }

    /**
     * The choice comes from the hard end of what is open, and is not always the same one.
     *
     * Both halves matter: sampling only the last entry would make the whole roster below it
     * unmeasured, and sampling the whole list would put the income nowhere near the ceiling.
     */
    @Test
    fun theHardestOpponentsAreTheOnesSampled() {
        val available = Catalogs.npcs.available(formatId = FORMAT, hour = 12, level = 99)
        assertTrue(available.size > SAMPLE, "this fixture needs a roster to choose from")

        val hardest = available.takeLast(SAMPLE).map(Npc::iconId).toSet()
        val chosen = (1..DRAWS)
            .mapNotNull { BotBrain.opponent(available, Random(it))?.iconId }
            .toSet()

        assertTrue(chosen.isNotEmpty(), "something should have been chosen")
        assertTrue(hardest.containsAll(chosen), "a bot should not drop down the ladder: $chosen")
        assertTrue(chosen.size > 1, "one opponent for every draw is not a sample")
    }

    // ---- Spending ---------------------------------------------------------

    /** A purse at the reserve buys nothing: that money is not the shop's. */
    @Test
    fun aBotKeepsItsReserveOutOfTheShop() {
        val save = GameSave.new("reserved", createdAt = NOW).copy(mgp = RESERVE)
        assertNull(BotBrain.shopping(save, format, cards, RESERVE, Random(SEED)))
    }

    /**
     * A purse that can stand it buys a pack and opens it — into the **bag**.
     *
     * Pinned as its own step because the boundary is easy to get wrong in the invisible direction:
     * `Inventory.use` on a booster yields card *items*, and a bot that stopped here would spend
     * its money forever and never own anything. [theBagIsWhereTheCollectionComesFrom] is the
     * other half.
     */
    @Test
    fun aPackBoughtIsAPackOpened() {
        val save = GameSave.new("rich", createdAt = NOW).copy(mgp = RICH)
        val shopped = assertNotNull(BotBrain.shopping(save, format, cards, RESERVE, Random(SEED)))

        assertTrue(shopped.mgp < save.mgp, "a purchase costs something")
        assertTrue(shopped.mgp >= RESERVE, "the reserve must survive the trip")
        assertTrue(shopped.bag.any { it is CardItem }, "an opened pack yields card items")
        assertTrue(shopped.bag.none { it is BoosterItem }, "the pack itself should be gone")
    }

    /**
     * **What is in the bag is not owned until it is taken, and a bot takes it.**
     *
     * The assertion that catches the failure a bot would hide for months: spending every match's
     * pay on packs, opening every one of them, and fielding the same nine cards it started with
     * because nothing ever moved the drops out of the bag. `Npc.rollRewards` fills it the same way
     * after every single match.
     */
    @Test
    fun theBagIsWhereTheCollectionComesFrom() {
        val save = GameSave.new("collector", createdAt = NOW).copy(mgp = RICH)
        val developed = assertNotNull(
            BotBrain.developing(save, format, cards, RESERVE, Random(SEED)),
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
        assertNull(BotBrain.developing(bare, format, cards, RESERVE, Random(SEED)))
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
     * **A bot risks nothing while the deployment has not said it may.**
     *
     * Both halves of a wager are refused, and the second is the one worth writing down: a table
     * staking no MGP at all still moves a *card* under a trade rule, and a bot that read only the
     * purse would be quietly feeding cards into — or out of — the players' economy.
     */
    @Test
    fun aBotWillNotSitDownForAWager() {
        val money = table(openedAt = NOW - WAIT, stake = PvpStake(mgp = 100))
        val cardsAtStake = table(openedAt = NOW - WAIT, stake = PvpStake(trade = TradeRule.ONE))

        assertNull(joinable(listOf(money), staleBefore = NOW - WAIT))
        assertNull(joinable(listOf(cardsAtStake), staleBefore = NOW - WAIT))
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
    ) = BotBrain.joinable(
        tables = tables,
        botId = BOT,
        save = save,
        stakes = PvpStakePolicy(),
        wagers = wagers,
        staleBefore = staleBefore,
    )

    private companion object {
        const val FORMAT = "ff14-standard"
        const val SEED = 20260907
        const val NOW = 1_800_000_000_000L
        const val WAIT = 45_000L

        val EXPERT: MatchAiOptions = MatchAiOptions.forLevel(NpcLevel.EXPERT)

        /** Matches `BotBrain.SAMPLED_OPPONENTS`, which is private and is the thing being pinned. */
        const val SAMPLE = 4
        const val DRAWS = 40

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
