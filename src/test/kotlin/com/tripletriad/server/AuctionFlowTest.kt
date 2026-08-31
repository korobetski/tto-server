package com.tripletriad.server

import com.tripletriad.data.AuctionRules
import com.tripletriad.model.CardItem
import com.tripletriad.model.CardOrigin
import com.tripletriad.model.GameSave
import com.tripletriad.model.PouchItem
import com.tripletriad.model.XpTable
import com.tripletriad.protocol.AuctionDuration
import com.tripletriad.protocol.AuctionLotRequest
import com.tripletriad.protocol.AuctionOutcome
import com.tripletriad.protocol.AuctionPolicy
import com.tripletriad.protocol.AuctionRefusal
import com.tripletriad.protocol.AuctionStatus
import com.tripletriad.protocol.BidRequest
import com.tripletriad.protocol.ListCardRequest
import com.tripletriad.protocol.Unlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The auction house end to end, against a real Postgres and a clock the test moves.
 *
 * ### Why the store and not the routes
 *
 * Because everything that can go wrong here goes wrong in a transaction, and the interesting half
 * of it happens with nobody connected: a lot ends on a background loop, hours after both players
 * closed the app. `AuctionRoutes` is four lines of plumbing over these calls and is measured where
 * plumbing should be — [VersionGateTest] for the gate, [PvpUnlockTest] for the unlock, and
 * [RateLimitTest] for the bucket. What is measured here is the money.
 *
 * ### The clock is a field, and that is the whole design of this file
 *
 * `AuctionStore` takes its clock as a parameter for exactly one reason: a lot that ends in six
 * hours is untestable otherwise, and a sweep that never runs in a test is a sweep that ships
 * broken. [now] moves; every store in this file reads it.
 *
 * ### What is verified, and what is not
 *
 * Verified: every path money takes — the listing fee, the hold, the refund on being outbid, both
 * settlements, and both ways a lot can end without selling. Also the two refusals a schema
 * constraint stands behind, from the application side; [MigrationTest] holds the database's own.
 *
 * **Not verified here:** concurrency. Two bids arriving in the same millisecond are serialised by
 * `SELECT ... FOR UPDATE` on the lot row and by `auction_bids_one_hold`, and neither is exercised
 * by this file — [ConcurrentWriteTest] is the pattern that would, and no such test exists for the
 * auction house yet.
 */
class AuctionFlowTest {

    // ---- Money -------------------------------------------------------------

    @Test
    fun listingTakesTheCardOutOfTheCollectionAndTheFeeOutOfThePurse() {
        val seller = player("sell", holding = 1)
        val before = profile(seller)

        val lot = assertNotNull(list(seller).lot, "the listing was refused")

        val after = profile(seller)
        assertEquals(
            before.copiesOf(CARD) - 1,
            after.copiesOf(CARD),
            "the card did not leave the collection",
        )
        assertEquals(
            before.mgp - AuctionRules.listingFee(RESERVE),
            after.mgp,
            "the listing fee was not 5% of the reserve",
        )
        assertEquals(AuctionStatus.OPEN, lot.status)
        assertTrue(
            store().browse(seller).lots.any { it.id == lot.id },
            "the lot is not on the board",
        )
    }

    /**
     * The floor, from the application side.
     *
     * A listing under what the counter already pays is one nobody would ever take — but it is also
     * the shape of a laundering lot, priced to look like noise. `AuctionRules` decides it; this
     * proves the store asks.
     */
    @Test
    fun aListingUnderTheShopPriceIsRefused() {
        val seller = player("floor", holding = 1)
        val refused = outcome(
            store().list(
                seller,
                ListCardRequest(CARD, FLOOR - 1, RESERVE, DURATION, operation("under")),
            ),
        )
        assertEquals(AuctionRefusal.BELOW_FLOOR, refused.refusal)
        assertEquals(PURSE, profile(seller).mgp, "a refused listing still charged the fee")
    }

    @Test
    fun aBidHoldsTheBidAndItsFeeAndOutbiddingHandsThemBackWhole() {
        val seller = player("sell", holding = 1)
        val first = player("bid-a")
        val second = player("bid-b")
        val lot = assertNotNull(list(seller).lot)

        assertNull(bid(first, lot.id, OPENING).refusal, "the first bid was refused")
        assertEquals(
            PURSE - AuctionRules.totalDue(OPENING),
            profile(first).mgp,
            "the hold was not the bid plus the buyer's fee",
        )

        assertNull(bid(second, lot.id, RAISE).refusal, "the raise was refused")
        assertEquals(PURSE, profile(first).mgp, "the outbid player was not made whole")
        assertEquals(PURSE - AuctionRules.totalDue(RAISE), profile(second).mgp)
    }

    /**
     * The double tap, which is the reason every request here carries an operation id.
     *
     * Without it the second press is a *second bid* — outbidding the first, at the same player's
     * expense, with both holds taken and only one of them ever refunded. This is the test that
     * fails if `applyOnceAcross` ever stops claiming the key.
     */
    @Test
    fun theSameBidSentTwiceIsOneBid() {
        val seller = player("sell", holding = 1)
        val bidder = player("tap")
        val lot = assertNotNull(list(seller).lot)

        val request = BidRequest(lot.id, OPENING, operation("double-tap"))
        val once = outcome(store().bid(bidder, request))
        val twice = outcome(store().bid(bidder, request))

        // Decoded rather than compared as text: the stored answer comes back out of a `jsonb`
        // column, and jsonb is a parsed document — it keeps no key order and no whitespace. A
        // string comparison here would fail on a replay that is byte-for-byte correct.
        assertEquals(once, twice, "the replay was not the stored answer")
        assertEquals(
            PURSE - AuctionRules.totalDue(OPENING),
            profile(bidder).mgp,
            "the purse was debited twice",
        )
        assertEquals(1, mine(bidder).lots.single().bidCount, "two bids were recorded")
    }

    @Test
    fun youCannotBidOnYourOwnLotNorAgainstYourself() {
        val seller = player("sell", holding = 1)
        val bidder = player("lead")
        val lot = assertNotNull(list(seller).lot)

        assertEquals(
            AuctionRefusal.YOUR_OWN_LOT,
            bid(seller, lot.id, OPENING).refusal,
            "the seller bid on their own lot",
        )
        assertNull(bid(bidder, lot.id, OPENING).refusal)
        assertEquals(
            AuctionRefusal.ALREADY_LEADING,
            bid(bidder, lot.id, RAISE).refusal,
            "the leader raised their own bid",
        )
        assertEquals(
            PURSE - AuctionRules.totalDue(OPENING),
            profile(bidder).mgp,
            "the refused raise still moved money",
        )
    }

    // ---- Withdrawal --------------------------------------------------------

    @Test
    fun aQuietLotComesBackAsACardToUseAndAContestedOneCannotBeWithdrawn() {
        val seller = player("sell", holding = 2)
        val quiet = assertNotNull(list(seller).lot)

        val back = outcome(
            store().withdraw(seller, AuctionLotRequest(quiet.id, operation("pull"))),
        )
        assertEquals(AuctionStatus.CANCELLED, assertNotNull(back.lot).status)
        assertEquals(
            listOf(CardItem(CARD, 1, CardOrigin.AUCTION_UNSOLD)),
            profile(seller).bag,
            "the card did not come back as an item to use",
        )

        val contested = assertNotNull(list(seller, operation("second")).lot)
        assertNull(bid(player("bid"), contested.id, OPENING).refusal)
        assertEquals(
            AuctionRefusal.ALREADY_BID,
            outcome(
                store().withdraw(seller, AuctionLotRequest(contested.id, operation("pull-2"))),
            ).refusal,
            "a lot with money on it was withdrawn",
        )
    }

    // ---- The clock ---------------------------------------------------------

    /**
     * Sniping, answered.
     *
     * A bid with seconds left pushes the end out, so the last bid is the highest anybody was
     * willing to go rather than the fastest connection in the closing second.
     */
    @Test
    fun aBidInTheClosingWindowPushesTheEndOut() {
        val seller = player("sell", holding = 1)
        val sniper = player("snipe")
        val lot = assertNotNull(list(seller).lot)

        now = lot.endsAt - 1_000L
        val pushed = assertNotNull(bid(sniper, lot.id, OPENING).lot)

        assertTrue(
            pushed.endsAt > lot.endsAt,
            "the end did not move: ${lot.endsAt} -> ${pushed.endsAt}",
        )
        assertEquals(
            now + POLICY.antiSnipeSeconds * MILLIS_PER_SECOND,
            pushed.endsAt,
            "the extension was not the anti-snipe window",
        )
    }

    // ---- Settlement --------------------------------------------------------

    @Test
    fun theSweepSellsALotThatMetItsReserveAndPaysBothSides() {
        val seller = player("sell", holding = 1)
        val buyer = player("buy")
        val lot = assertNotNull(list(seller).lot)
        assertNull(bid(buyer, lot.id, OVER_RESERVE).refusal)

        val purseAfterBidding = profile(buyer).mgp
        expire()
        store().sweep()

        assertEquals(
            listOf(PouchItem(OVER_RESERVE, CARD, lot.id)),
            profile(seller).bag,
            "the seller was not handed a pouch of the proceeds",
        )
        assertEquals(
            listOf(CardItem(CARD)),
            profile(buyer).bag,
            "the buyer was not handed the card",
        )
        assertEquals(
            purseAfterBidding,
            profile(buyer).mgp,
            "the hold was charged a second time at settlement",
        )
        assertEquals(AuctionStatus.SOLD, mine(seller).lots.single().status)

        store().sweep()
        assertEquals(
            listOf(PouchItem(OVER_RESERVE, CARD, lot.id)),
            profile(seller).bag,
            "a second sweep paid the seller again",
        )
    }

    @Test
    fun aLotNobodyBidOnComesBackToItsSeller() {
        val seller = player("sell", holding = 1)
        list(seller)

        expire()
        store().sweep()

        assertEquals(
            listOf(CardItem(CARD, 1, CardOrigin.AUCTION_UNSOLD)),
            profile(seller).bag,
            "the unsold card did not come back as an item to use",
        )
        assertEquals(AuctionStatus.UNSOLD, mine(seller).lots.single().status)
    }

    /**
     * A bid short of the reserve is a question, not an answer.
     *
     * Both halves of the wait are here — the hold that must not be released while the question
     * stands, and the refund that must be whole once it is answered.
     */
    @Test
    fun aShortBidIsPutToTheSellerAndDecliningReturnsEverything() {
        val seller = player("sell", holding = 1)
        val bidder = player("short")
        val lot = assertNotNull(list(seller).lot)
        assertNull(bid(bidder, lot.id, UNDER_RESERVE).refusal)

        expire()
        store().sweep()
        assertEquals(
            AuctionStatus.AWAITING_SELLER,
            mine(seller).lots.single().status,
            "the short lot was not put to its seller",
        )
        assertEquals(
            PURSE - AuctionRules.totalDue(UNDER_RESERVE),
            profile(bidder).mgp,
            "the hold was released before the seller answered",
        )

        val declined = outcome(
            store().decide(seller, AuctionLotRequest(lot.id, operation("no")), accept = false),
        )
        assertEquals(AuctionStatus.UNSOLD, assertNotNull(declined.lot).status)
        assertEquals(PURSE, profile(bidder).mgp, "the bidder was not made whole")
        assertEquals(
            listOf(CardItem(CARD, 1, CardOrigin.AUCTION_UNSOLD)),
            profile(seller).bag,
        )
    }

    @Test
    fun acceptingAShortBidSellsItAnyway() {
        val seller = player("sell", holding = 1)
        val bidder = player("short")
        val lot = assertNotNull(list(seller).lot)
        assertNull(bid(bidder, lot.id, UNDER_RESERVE).refusal)
        expire()
        store().sweep()

        val sold = outcome(
            store().decide(seller, AuctionLotRequest(lot.id, operation("yes")), accept = true),
        )

        assertEquals(AuctionStatus.SOLD, assertNotNull(sold.lot).status)
        assertEquals(listOf(PouchItem(UNDER_RESERVE, CARD, lot.id)), profile(seller).bag)
        assertEquals(listOf(CardItem(CARD)), profile(bidder).bag)
    }

    /**
     * The seller who never comes back.
     *
     * Silence has to mean *refuse*: the bidder's money is held for as long as the question stands,
     * so a window a seller could leave open indefinitely would be a way to freeze somebody else's
     * purse. Accepting on their behalf would sell a card on terms its owner never agreed to.
     */
    @Test
    fun aSellerWhoNeverAnswersHasTheSaleRefusedForThem() {
        val seller = player("sell", holding = 1)
        val bidder = player("short")
        val lot = assertNotNull(list(seller).lot)
        assertNull(bid(bidder, lot.id, UNDER_RESERVE).refusal)

        expire()
        store().sweep()
        now += POLICY.sellerDecisionMillis + MILLIS_PER_SECOND
        store().sweep()

        assertEquals(
            AuctionStatus.UNSOLD,
            mine(seller).lots.single().status,
            "the lapsed decision was not settled",
        )
        assertEquals(PURSE, profile(bidder).mgp, "the frozen purse was never released")
        assertEquals(
            listOf(CardItem(CARD, 1, CardOrigin.AUCTION_UNSOLD)),
            profile(seller).bag,
        )
        assertEquals(
            AuctionRefusal.NOT_YOUR_DECISION,
            outcome(
                store().decide(seller, AuctionLotRequest(lot.id, operation("late")), accept = true),
            ).refusal,
            "the seller answered a question that had already been answered for them",
        )
    }

    // ---- Departures --------------------------------------------------------

    /**
     * The seller deletes their account, and the lot is settled rather than erased.
     *
     * The bidder has already paid; a cascade would take the card they paid for out of the database
     * with the seller. So the sale concludes: the bidder wins, and the proceeds — which are owed to
     * an account that no longer exists — are destroyed rather than handed to anybody.
     *
     * The last assertion is the one that would catch a `CASCADE` creeping back into `V14`: the lot
     * has to still be *there*, readable by the buyer, saying what they won.
     */
    @Test
    fun deletingASellerLetsTheLastBidderWinAndDestroysTheProceeds() {
        val seller = player("gone-sell", holding = 1)
        val buyer = player("winner")
        val lot = assertNotNull(list(seller).lot)
        assertNull(bid(buyer, lot.id, OVER_RESERVE).refusal)
        val spent = profile(buyer).mgp

        assertTrue(delete(seller), "the account was not deleted")

        assertEquals(
            listOf(CardItem(CARD)),
            profile(buyer).bag,
            "the bidder did not win the card they had already paid for",
        )
        assertEquals(spent, profile(buyer).mgp, "the buyer was charged again, or refunded")
        val settled = mine(buyer).lots.single()
        assertEquals(AuctionStatus.SOLD, settled.status, "the lot went with its seller")
        assertEquals(OVER_RESERVE, settled.soldFor)
        assertNull(settled.sellerName, "a deleted seller still has a name on the lot")
    }

    /**
     * The same rule from the other side: the top bidder leaves, so the sale ends now.
     *
     * The seller is paid out of the hold that was already taken — no MGP is invented — and the
     * card is destroyed, because handing it back to the seller would mean a player could undo any
     * auction they were losing by deleting the account that was winning it.
     */
    @Test
    fun deletingTheTopBidderPaysTheSellerAndDestroysTheCard() {
        val seller = player("paid", holding = 1)
        val bidder = player("gone-bid")
        val lot = assertNotNull(list(seller).lot)
        assertNull(bid(bidder, lot.id, OVER_RESERVE).refusal)

        assertTrue(delete(bidder), "the account was not deleted")

        assertEquals(
            listOf(PouchItem(OVER_RESERVE, CARD, lot.id)),
            profile(seller).bag,
            "the seller was not paid out of the hold",
        )
        assertEquals(AuctionStatus.SOLD, mine(seller).lots.single().status)
        assertEquals(
            0,
            store().browse(seller).lots.count { it.id == lot.id },
            "a settled lot is still on the board",
        )
    }

    /**
     * A seller who leaves with nobody having bid takes the card with them.
     *
     * Nothing was sold and nobody is owed anything, so there is no third party to protect — and a
     * card returned to a bag nobody owns would be a card in circulation that nobody sold.
     */
    @Test
    fun deletingASellerNobodyBidOnClosesTheLotUnsold() {
        val seller = player("quiet-gone", holding = 1)
        val watcher = player("watch")
        val lot = assertNotNull(list(seller).lot)

        assertTrue(delete(seller))

        assertEquals(
            0,
            store().browse(watcher).lots.count { it.id == lot.id },
            "a lot with no seller is still taking bids",
        )
        assertNull(
            bid(watcher, lot.id, OPENING).lot,
            "a lot whose seller is gone accepted a bid",
        )
        // Read out of the table, because there is no viewer left who could ask for it: `mine` needs
        // a seller or a bidder and this lot has neither. `UNSOLD` and not `CANCELLED` — the lot ran
        // and ended, which is not the same event as a seller withdrawing it, and `V14` keeps the
        // two apart precisely so a payout query cannot confuse them.
        assertEquals(AuctionStatus.UNSOLD.name, statusOf(lot.id))
    }

    /**
     * Leaving after being outbid costs nothing and settles nothing.
     *
     * The money came back when they were outbid, so there is no hold to unwind — and the lot has
     * to keep running, because the player who *is* leading has money on it.
     */
    @Test
    fun deletingAnOutbidPlayerLeavesTheLotRunning() {
        val seller = player("sell", holding = 1)
        val first = player("outbid")
        val second = player("leads")
        val lot = assertNotNull(list(seller).lot)
        assertNull(bid(first, lot.id, OPENING).refusal)
        assertNull(bid(second, lot.id, OVER_RESERVE).refusal)

        assertTrue(delete(first))

        val running = store().browse(second).lots.single { it.id == lot.id }
        assertEquals(AuctionStatus.OPEN, running.status, "the lot ended when a loser left")
        assertEquals(OVER_RESERVE, running.topBid, "the standing bid moved")

        expire()
        store().sweep()
        assertEquals(listOf(CardItem(CARD)), profile(second).bag, "the leader did not win")
    }

    // ---- Harness -----------------------------------------------------------

    /**
     * The clock every store in this test reads, moved by the tests that care.
     *
     * Starts far from zero so an `endsAt` is a plausible instant rather than one a few hours after
     * the epoch, which is the shape a `Timestamp` round trip is least likely to be honest about.
     */
    private var now = START

    private val accounts = AccountStore(Postgres.dataSource)

    /** A store reading the current [now]. Built per call, so a moved clock is picked up. */
    private fun store() = AuctionStore(
        Postgres.dataSource,
        accounts,
        Catalogs.cards.byId,
        Unlocks(),
        POLICY,
    ) { now }

    /**
     * An account at the auction unlock level, with [PURSE] and [holding] copies of [CARD].
     *
     * The XP is set alongside the level for the reason `unlockForPvp` gives at length: `level` is
     * derived from `xp`, so a level without the XP behind it falls back the first time anything
     * pays the account.
     */
    private fun player(prefix: String, holding: Int = 0): Long {
        val name = Postgres.freshAccount("auc-$prefix")
        val save = (1..holding)
            .fold(GameSave.new(name, createdAt = START)) { profile, _ -> profile.withCard(CARD) }
            .copy(mgp = PURSE, xp = XpTable.thresholdFor(LEVEL), level = LEVEL)
        return assertNotNull(accounts.register(name, "hash-$name", save))
    }

    private fun profile(accountId: Long): GameSave =
        assertNotNull(accounts.saveFor(accountId), "account $accountId has no profile")

    private fun list(accountId: Long, id: String = operation("list")): AuctionOutcome = outcome(
        store().list(accountId, ListCardRequest(CARD, START_PRICE, RESERVE, DURATION, id)),
    )

    private fun bid(accountId: Long, lotId: String, amount: Int): AuctionOutcome =
        outcome(store().bid(accountId, BidRequest(lotId, amount, operation("bid-$amount"))))

    private fun mine(accountId: Long) = store().mine(accountId)

    /** The stored status of a lot, for the one case no viewer can still read. */
    private fun statusOf(lotId: String): String = Postgres.dataSource.connection.use { db ->
        db.prepareStatement("SELECT status FROM auction_lots WHERE id = ?").use { statement ->
            statement.setString(1, lotId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next(), "lot $lotId was deleted with its seller")
                rows.getString(1)
            }
        }
    }

    /**
     * Deletes an account the way the route does — the auction unwind on the delete's own
     * transaction.
     *
     * Reaching past `DELETE /accounts/me` deliberately: that route is about a password, and this
     * file is about what happens to other people's money when somebody leaves. That the route still
     * performs this exact pairing is measured once, next door, by
     * `AccountDeletionTest.leavingMidAuctionSettlesTheLotThroughTheRoute` — which exists because
     * nothing in this file would notice if the route stopped calling `closeOutOn` tomorrow.
     */
    private fun delete(accountId: Long): Boolean =
        accounts.deleteAccount(accountId) { db -> store().closeOutOn(db, accountId) }

    /**
     * Moves [now] past the end of every lot a test could have opened.
     *
     * Which is also why no test here asserts what [AuctionStore.sweep] *returned*. The database is
     * shared across the whole run ([Postgres] says why), the clock is not, and a test that moves
     * its own clock forward makes every other test's lot due at the same moment — so the count is
     * a number about the suite rather than about the lot. What each test asserts instead is the
     * state of its own lot and the balance of its own two accounts, which no other test can move.
     */
    private fun expire() {
        now += DURATION.millis + MILLIS_PER_SECOND
    }

    /**
     * A key unique within this instance, which is what makes [Postgres]' shared database safe here.
     *
     * `applied_operations` is keyed on the account too, so two *tests* could reuse a literal
     * without colliding — but the tests that hand one account several requests could not, and a
     * fixture that is only sometimes safe is a fixture that breaks later.
     */
    private fun operation(what: String): String = "$what-${operations++}"

    private var operations = 1

    /**
     * The store's own JSON, decoded with the store's own configuration.
     *
     * [ApiJson] rather than a lenient instance of this file's own, unlike the route tests: those
     * decode what a *client* would see and should fail the way a client fails. This decodes what
     * the store just encoded, and a disagreement between the two would be a bug worth reporting
     * rather than absorbing.
     */
    private fun outcome(response: String?): AuctionOutcome =
        ApiJson.decodeFromString(assertNotNull(response, "the store answered null"))

    private companion object {
        /** 2024-01-01. Any fixed instant would do — see [now]. */
        const val START = 1_704_067_200_000L

        const val MILLIS_PER_SECOND = 1_000L

        val POLICY = AuctionPolicy()

        const val LEVEL = Unlocks.DEFAULT_AUCTION

        /** Far more than any figure below, so no test is ever measuring an empty purse. */
        const val PURSE = 100_000

        /**
         * A common from the shipped catalogue, chosen at runtime rather than written down.
         *
         * A hard-coded id would tie every figure in this file to one card's rarity surviving the
         * next import of `cards.json`, which is generated and does change.
         */
        val CARD = Catalogs.cards.cards.first { it.rarity == 1 }.id

        val FLOOR = AuctionRules.floorPriceOf(CARD, Catalogs.cards.byId)

        val START_PRICE = FLOOR
        val RESERVE = FLOOR * 4
        val DURATION = AuctionDuration.SHORT

        /** The opening bid, and a raise clear of `minimumBid`'s 5% increment. */
        val OPENING = START_PRICE
        val RAISE = START_PRICE * 2

        /** One either side of the reserve, so both branches of the sweep are reachable. */
        val UNDER_RESERVE = RESERVE - 1
        val OVER_RESERVE = RESERVE + 1
    }
}
