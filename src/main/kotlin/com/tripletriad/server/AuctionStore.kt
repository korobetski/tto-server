package com.tripletriad.server

import com.tripletriad.data.AuctionRules
import com.tripletriad.data.Inventory
import com.tripletriad.model.Card
import com.tripletriad.model.CardItem
import com.tripletriad.model.CardOrigin
import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.model.PouchItem
import com.tripletriad.protocol.AuctionLot
import com.tripletriad.protocol.AuctionLotRequest
import com.tripletriad.protocol.AuctionOutcome
import com.tripletriad.protocol.AuctionPage
import com.tripletriad.protocol.AuctionPolicy
import com.tripletriad.protocol.AuctionRefusal
import com.tripletriad.protocol.AuctionStatus
import com.tripletriad.protocol.BidRequest
import com.tripletriad.protocol.ListCardRequest
import com.tripletriad.protocol.Unlocks
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource
import kotlin.random.Random

/**
 * The auction house: what a lot costs to open, what a bid takes, and who ends up with what.
 *
 * ### The money is held, and that is the whole design
 *
 * A bid **debits** the bidder's purse when it is placed and is refunded in full the moment somebody
 * outbids it. `AuctionRules.validateBid` argues why, and `V14__auction_house.sql` says what the
 * schema does about it; the consequence for this file is that there is no "collect from the winner"
 * step anywhere in it. A settlement moves a card and a pouch, never money — the money moved when
 * the bid was placed.
 *
 * ### Every write goes through `AccountStore.applyOnceAcross`
 *
 * Not [AccountStore.applyOnce], because almost nothing here touches one account: a bid refunds the
 * player it outbid, a settlement pays the seller and hands the buyer a card. The transaction has to
 * cover both profiles *and* the auction rows, and it has to be idempotent, because a player tapping
 * "bid" twice in the last ten seconds of a lot is not a hypothetical.
 *
 * ### Refusals are answers, not errors
 *
 * Every entry point here returns a `200` carrying an [AuctionRefusal] when it will not do what was
 * asked, with the profile in the same body — the shape `ItemEffect.NotUseable` already has. The
 * client's view of a lot is always slightly stale by definition, so "somebody outbid you while you
 * were typing" is an ordinary outcome and not a client error.
 *
 * ### What is verified, and what is not
 *
 * `AuctionFlowTest` drives every path in this file against a real Postgres. **Not verified:** the
 * sweep has never run against a production-sized table, and the ordering guarantees below rest on
 * row locks rather than on serialisable isolation — which is a deliberate choice, but one no test
 * here contradicts.
 */
// TooManyFunctions counts queries, which is what a data-access class is made of — the same
// argument `AccountStore` makes above its own suppression. MagicNumber counts JDBC parameter
// positions, which are the statement's own numbering and not a quantity anyone could name better;
// every figure this file decides is decided in `AuctionRules`, where it is a named constant.
@Suppress("TooManyFunctions", "MagicNumber")
class AuctionStore(
    private val dataSource: DataSource,
    private val accounts: AccountStore,
    private val cards: Map<Int, Card>,
    private val unlocks: Unlocks = Unlocks(),
    private val policy: AuctionPolicy = AuctionPolicy(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Lots on offer, soonest to close first.
     *
     * @param cardId narrows to one card, which is what a player hunting a specific card does.
     */
    fun browse(accountId: Long, cardId: Int? = null, limit: Int = BROWSE_LIMIT): AuctionPage =
        read { db ->
            val filter = if (cardId == null) "" else "AND l.card_id = $cardId"
            val rows = query(
                db,
                """
                $LOT_SELECT
                WHERE l.status = 'OPEN' $filter
                ORDER BY l.ends_at ASC
                LIMIT $limit
                """.trimIndent(),
            )
            page(db, rows, accountId)
        }

    /**
     * Everything this account has a stake in: lots it opened, and lots it has ever bid on.
     *
     * Finished lots are included and that is the point — a seller comes back to find out what
     * happened, and a lot that vanished the moment it closed would answer nobody.
     */
    fun mine(accountId: Long, limit: Int = BROWSE_LIMIT): AuctionPage = read { db ->
        val rows = query(
            db,
            """
            $LOT_SELECT
            WHERE l.seller_account = $accountId
               OR EXISTS (
                   SELECT 1 FROM auction_bids b
                   WHERE b.lot_id = l.id AND b.bidder_account = $accountId
               )
            ORDER BY l.status = 'OPEN' DESC, l.ends_at DESC
            LIMIT $limit
            """.trimIndent(),
        )
        page(db, rows, accountId)
    }

    /**
     * Opens a lot: the card leaves the collection and the listing fee leaves the purse.
     *
     * The card is held by the lot row rather than by a flag on the profile, which is what makes
     * selling one copy to two people impossible without any locking beyond the profile's own.
     */
    fun list(accountId: Long, request: ListCardRequest): String? =
        accounts.applyOnceAcross(accountId, request.operationId) { writer ->
            val save = writer.lock(accountId) ?: return@applyOnceAcross null
            val refusal = listingRefusal(writer.db, accountId, save, request)
            if (refusal != null) return@applyOnceAcross refused(writer, accountId, save, refusal)

            val fee = AuctionRules.listingFee(request.reservePrice)
            val updated = save.withoutCard(request.cardId).withMgp(-fee)
            writer.write(accountId, updated)

            val id = newId()
            insertLot(writer.db, id, accountId, request, fee)
            settled(writer, accountId, updated, lotById(writer.db, id))
        }

    /**
     * Places a bid: this purse is debited, the outbid one is made whole, the end may move.
     *
     * ### The order the locks are taken in
     *
     * The lot row first, then the profiles in ascending account id — which [AccountWriter] enforces
     * rather than trusts. Locking the lot first is what serialises two bids arriving together: the
     * second one waits, and then reads a standing bid that already includes the first, so it is
     * refused as too low rather than accepted as a second hold.
     */
    fun bid(accountId: Long, request: BidRequest): String? =
        accounts.applyOnceAcross(accountId, request.operationId) { writer ->
            val lot = lockLot(writer.db, request.lotId)
            val held = lot?.let { heldBid(writer.db, it.id) }

            // Ascending, and both before anything is written. The bidder's own profile may be
            // either of the two, so neither can simply be locked first by name.
            listOfNotNull(accountId, held?.bidder).distinct().sorted()
                .forEach { writer.lock(it) }
            val save = writer.lock(accountId) ?: return@applyOnceAcross null

            val refusal = biddingRefusal(accountId, save, lot, request.amount)
            if (refusal != null) return@applyOnceAcross refused(writer, accountId, save, refusal)
            checkNotNull(lot)

            refund(writer, held)
            val due = AuctionRules.totalDue(request.amount)
            val updated = save.withMgp(-due)
            writer.write(accountId, updated)

            insertBid(writer.db, lot.id, accountId, request.amount)
            raiseLot(writer.db, lot, accountId, request.amount)
            settled(writer, accountId, updated, lotById(writer.db, lot.id), request.amount)
        }

    /**
     * Withdraws a lot nobody has bid on. The card comes back; the listing fee does not.
     *
     * The fee stays spent because it bought what it was charged for — a lot that ran. Refunding it
     * would make "open a lot, watch, withdraw" free, which is a way to keep a card visible on the
     * list for ever at no cost.
     */
    fun withdraw(accountId: Long, request: AuctionLotRequest): String? =
        accounts.applyOnceAcross(accountId, request.operationId) { writer ->
            val lot = lockLot(writer.db, request.lotId)
            val save = writer.lock(accountId) ?: return@applyOnceAcross null

            val refusal = withdrawalRefusal(accountId, lot)
            if (refusal != null) return@applyOnceAcross refused(writer, accountId, save, refusal)
            checkNotNull(lot)

            val updated = Inventory.add(save, unsoldCard(lot.cardId))
            writer.write(accountId, updated)
            closeLot(writer.db, lot.id, AuctionStatus.CANCELLED)
            settled(writer, accountId, updated, lotById(writer.db, lot.id))
        }

    /**
     * The seller's answer to a bid that fell short of their reserve.
     *
     * @param accept whether to take it anyway. The reserve is a floor the seller *may* waive — a
     *   bid at 90% of it is still money for a card they were willing to part with — so this is a
     *   real decision and not a formality. Declining hands the card back exactly as an unsold lot
     *   does, because that is what it is.
     */
    fun decide(accountId: Long, request: AuctionLotRequest, accept: Boolean): String? =
        accounts.applyOnceAcross(accountId, request.operationId) { writer ->
            val lot = lockLot(writer.db, request.lotId)
            val held = lot?.let { heldBid(writer.db, it.id) }
            listOfNotNull(accountId, held?.bidder).distinct().sorted()
                .forEach { writer.lock(it) }
            val save = writer.lock(accountId) ?: return@applyOnceAcross null

            val refusal = decisionRefusal(accountId, lot)
            if (refusal != null) return@applyOnceAcross refused(writer, accountId, save, refusal)
            checkNotNull(lot)

            if (accept && held != null) sell(writer, lot, held) else unsell(writer, lot, held)
            val after = writer.lock(accountId) ?: save
            settled(writer, accountId, after, lotById(writer.db, lot.id))
        }

    /**
     * Closes everything whose time is up: lots that ended, and sellers who stopped answering.
     *
     * Runs from the same background loop as the PvP sweep. Each lot settles in its own
     * transaction, so one lot whose profiles cannot be locked delays that lot and nothing else.
     *
     * @return how many lots were moved out of a waiting state.
     */
    fun sweep(): Int {
        val now = clock()
        val ended = due(
            "SELECT id FROM auction_lots WHERE status = 'OPEN' AND ends_at <= ?",
            now,
        )
        val lapsed = due(
            "SELECT id FROM auction_lots WHERE status = 'AWAITING_SELLER' AND decision_end <= ?",
            now,
        )
        return ended.count { settleEnded(it) } + lapsed.count { settleLapsed(it) }
    }

    /**
     * Settles every live lot [leaving] is party to, so that deleting the account cannot strand one.
     *
     * ### Why this runs inside the delete's own transaction
     *
     * Because the alternative has a window in it. Settle first and delete second and the account
     * can place a bid in between, leaving exactly the row this function exists to prevent; delete
     * first and settle second and there is nothing left to settle from. So `AccountStore` hands
     * this its connection and the two commit together — either the lots are settled and the
     * account is gone, or neither happened.
     *
     * ### What each side gets
     *
     * The rule is the user's and it is symmetric: **the surviving party is paid what they were
     * owed, and the departing party's half is destroyed.**
     *
     * - The *seller* leaves, and somebody has bid: the bidder wins the card they already paid for.
     *   The proceeds are destroyed — there is nobody to pay them to.
     * - The *seller* leaves with nobody having bid: nothing was sold and nobody is owed anything.
     *   The card goes with them.
     * - The *top bidder* leaves: the sale concludes now. The seller is paid out of the hold that
     *   was already taken — so no MGP is invented — and the card is destroyed.
     *
     * None of those is written out below, which is the point of the design. Nulling the departing
     * side and calling [sell] or [unsell] produces all three, because [pay] already means "or
     * nobody". The rule lives in one line and the settlement lives where settlement lives.
     *
     * A lot the account merely *bid* on and lost is untouched: that money came back when they were
     * outbid, and the bid row stays in the ledger with a null bidder for the reason
     * `V14__auction_house.sql` gives.
     *
     * @return how many lots were settled, for the log line the deletion route writes.
     */
    fun closeOutOn(db: Connection, leaving: Long): Int {
        val lots = query(
            db,
            """
            $LOT_SELECT
            WHERE (l.seller_account = $leaving OR l.top_bidder = $leaving)
              AND l.status IN ('OPEN', 'AWAITING_SELLER')
            ORDER BY l.id
            FOR UPDATE OF l
            """.trimIndent(),
        )
        if (lots.isEmpty()) return 0

        val writer = AccountWriter(db, accounts)
        val holds = lots.associate { it.id to heldBid(db, it.id) }

        // Every counterparty, ascending, before anything is written — `AccountWriter` refuses them
        // in any other order and it is right to: this is the one call in the house that can touch
        // an unbounded number of profiles at once, so it is the one where two of them running
        // together would actually find a deadlock.
        lots.asSequence()
            .flatMap { sequenceOf(it.seller, holds[it.id]?.bidder) }
            .filterNotNull()
            .filter { it != leaving }
            .distinct()
            .sorted()
            .forEach { writer.lock(it) }

        lots.forEach { lot ->
            val orphaned = lot.copy(seller = lot.seller.takeIf { it != leaving })
            when (val held = holds[lot.id]) {
                null -> unsell(writer, orphaned, null)
                else -> sell(
                    writer,
                    orphaned,
                    held.copy(bidder = held.bidder.takeIf { it != leaving }),
                )
            }
        }
        return lots.size
    }

    // ------------------------------------------------------------------ refusals

    private fun listingRefusal(
        db: Connection,
        accountId: Long,
        save: GameSave,
        request: ListCardRequest,
    ): AuctionRefusal? {
        if (!unlocks.allowsAuction(save)) return AuctionRefusal.LOCKED
        if (!cards.containsKey(request.cardId)) return AuctionRefusal.NOT_YOURS
        return AuctionRules.validateListing(
            cardId = request.cardId,
            startPrice = request.startPrice,
            reservePrice = request.reservePrice,
            cards = cards,
            policy = policy,
            spareCopies = save.spareCopiesOf(request.cardId),
            purse = save.mgp,
            openLots = openLots(db, accountId),
        )
    }

    private fun biddingRefusal(
        accountId: Long,
        save: GameSave,
        lot: LotRow?,
        amount: Int,
    ): AuctionRefusal? {
        if (!unlocks.allowsAuction(save)) return AuctionRefusal.LOCKED
        if (lot == null || !lot.status.isOpen || lot.endsAt <= clock()) {
            return AuctionRefusal.LOT_GONE
        }
        return AuctionRules.validateBid(
            amount = amount,
            startPrice = lot.startPrice,
            topBid = lot.topBid,
            cardId = lot.cardId,
            cards = cards,
            policy = policy,
            purse = save.mgp,
            isSeller = lot.seller == accountId,
            isTopBidder = lot.topBidder == accountId,
        )
    }

    /** A lot is only the seller's to answer for, and only while it is actually asking. */
    private fun decisionRefusal(accountId: Long, lot: LotRow?): AuctionRefusal? = when {
        lot == null -> AuctionRefusal.LOT_GONE
        lot.status != AuctionStatus.AWAITING_SELLER -> AuctionRefusal.NOT_YOUR_DECISION
        lot.seller != accountId -> AuctionRefusal.NOT_YOUR_DECISION
        else -> null
    }

    private fun withdrawalRefusal(accountId: Long, lot: LotRow?): AuctionRefusal? = when {
        lot == null || !lot.status.isOpen -> AuctionRefusal.LOT_GONE
        lot.seller != accountId -> AuctionRefusal.NOT_YOURS
        lot.bidCount > 0 -> AuctionRefusal.ALREADY_BID
        else -> null
    }

    // ------------------------------------------------------------------ settlement

    /**
     * Pays the seller, hands the buyer their card, and spends the hold that was already taken.
     *
     * Either side may be absent, and then their half is simply destroyed. That is not an error
     * path bolted on: it is how account deletion is expressed — see [closeOutOn], which settles a
     * lot with the departing party already nulled out and lets this function do the rest.
     */
    private fun sell(writer: AccountWriter, lot: LotRow, held: HeldBid) {
        pay(writer, lot.seller, PouchItem(held.amount, lot.cardId, lot.id))
        pay(writer, held.bidder, CardItem(lot.cardId))
        stampBid(writer.db, held.id, "settled_at")
        closeLot(writer.db, lot.id, AuctionStatus.SOLD, soldFor = held.amount)
    }

    /** Refunds whatever is held and gives the card back. The two halves of an unsold lot. */
    private fun unsell(writer: AccountWriter, lot: LotRow, held: HeldBid?) {
        refund(writer, held)
        pay(writer, lot.seller, unsoldCard(lot.cardId))
        closeLot(writer.db, lot.id, AuctionStatus.UNSOLD)
    }

    /** Gives a hold back whole — what was taken, not what today's fee rate would compute. */
    private fun refund(writer: AccountWriter, held: HeldBid?) {
        if (held == null) return
        held.bidder?.let { bidder ->
            writer.lock(bidder)?.let { writer.write(bidder, it.withMgp(held.amount + held.fee)) }
        }
        stampBid(writer.db, held.id, "refunded_at")
    }

    /**
     * Hands [item] to [accountId], or destroys it when there is no such account any more.
     *
     * Destroyed rather than redistributed, and that is the decision the whole deletion rule rests
     * on. The alternatives are worse in the same way: paying the proceeds to somebody else invents
     * a windfall, and returning the card to the house means a card nobody sold is in circulation.
     * Both quietly move value; destroying is the only answer that cannot be farmed.
     *
     * The lot row keeps the record either way — `sold_for` still says what it went for — so what
     * is lost is the payout, not the history.
     */
    private fun pay(writer: AccountWriter, accountId: Long?, item: Item) {
        if (accountId == null) return
        val save = writer.lock(accountId) ?: return
        writer.write(accountId, Inventory.add(save, item))
    }

    /** A lot whose clock ran out: sold, put to the seller, or unsold. */
    private fun settleEnded(lotId: String): Boolean = write { db ->
        val writer = AccountWriter(db, accounts)
        val lot = lockLot(db, lotId)?.takeIf { it.status.isOpen } ?: return@write false
        val held = heldBid(db, lotId)

        listOfNotNull(lot.seller, held?.bidder).distinct().sorted().forEach { writer.lock(it) }

        when {
            held == null -> unsell(writer, lot, null)
            held.amount >= lot.reservePrice -> sell(writer, lot, held)
            else -> awaitSeller(db, lotId)
        }
        true
    }

    /** A seller who was asked and did not answer. Refusing on their behalf is the safe default:
     * it hands the card back and gives the bidder their money, where accepting would be selling
     * something on terms its owner never agreed to. */
    private fun settleLapsed(lotId: String): Boolean = write { db ->
        val writer = AccountWriter(db, accounts)
        val lot = lockLot(db, lotId)?.takeIf { it.status == AuctionStatus.AWAITING_SELLER }
            ?: return@write false
        val held = heldBid(db, lotId)
        listOfNotNull(lot.seller, held?.bidder).distinct().sorted().forEach { writer.lock(it) }
        unsell(writer, lot, held)
        true
    }

    // ------------------------------------------------------------------ SQL

    private fun openLots(db: Connection, accountId: Long): Int = db.prepareStatement(
        "SELECT count(*) FROM auction_lots WHERE seller_account = ? AND status IN " +
            "('OPEN', 'AWAITING_SELLER')",
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun insertLot(
        db: Connection,
        id: String,
        seller: Long,
        request: ListCardRequest,
        fee: Int,
    ) {
        db.prepareStatement(
            """
            INSERT INTO auction_lots
                (id, seller_account, card_id, start_price, reserve_price, listing_fee, ends_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setLong(2, seller)
            statement.setInt(3, request.cardId)
            statement.setInt(4, request.startPrice)
            statement.setInt(5, request.reservePrice)
            statement.setInt(6, fee)
            statement.setTimestamp(7, Timestamp(clock() + request.duration.millis))
            statement.executeUpdate()
        }
    }

    private fun insertBid(db: Connection, lotId: String, bidder: Long, amount: Int) {
        db.prepareStatement(
            "INSERT INTO auction_bids (lot_id, bidder_account, amount, fee) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, lotId)
            statement.setLong(2, bidder)
            statement.setInt(3, amount)
            statement.setInt(4, AuctionRules.buyerFee(amount))
            statement.executeUpdate()
        }
    }

    private fun raiseLot(db: Connection, lot: LotRow, bidder: Long, amount: Int) {
        db.prepareStatement(
            """
            UPDATE auction_lots
            SET top_bid = ?, top_bidder = ?, bid_count = bid_count + 1, ends_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, amount)
            statement.setLong(2, bidder)
            statement.setTimestamp(
                3,
                Timestamp(AuctionRules.extendedEnd(lot.endsAt, clock(), policy)),
            )
            statement.setString(4, lot.id)
            statement.executeUpdate()
        }
    }

    private fun awaitSeller(db: Connection, lotId: String) {
        db.prepareStatement(
            "UPDATE auction_lots SET status = 'AWAITING_SELLER', decision_end = ? WHERE id = ?",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp(clock() + policy.sellerDecisionMillis))
            statement.setString(2, lotId)
            statement.executeUpdate()
        }
    }

    private fun closeLot(
        db: Connection,
        lotId: String,
        status: AuctionStatus,
        soldFor: Int? = null,
    ) {
        db.prepareStatement(
            "UPDATE auction_lots SET status = ?, sold_for = ?, settled_at = now() WHERE id = ?",
        ).use { statement ->
            statement.setString(1, status.name)
            soldFor?.let { statement.setInt(2, it) } ?: statement.setNull(2, Types.INTEGER)
            statement.setString(3, lotId)
            statement.executeUpdate()
        }
    }

    /** Stamps a held bid as refunded or settled. [column] is never client input. */
    private fun stampBid(db: Connection, bidId: Long, column: String) {
        db.prepareStatement("UPDATE auction_bids SET $column = now() WHERE id = ?")
            .use { statement ->
                statement.setLong(1, bidId)
                statement.executeUpdate()
            }
    }

    /** The one live hold on a lot, if there is one. The schema guarantees there is at most one. */
    private fun heldBid(db: Connection, lotId: String): HeldBid? = db.prepareStatement(
        "SELECT id, bidder_account, amount, fee FROM auction_bids " +
            "WHERE lot_id = ? AND refunded_at IS NULL AND settled_at IS NULL",
    ).use { statement ->
        statement.setString(1, lotId)
        statement.executeQuery().use { rows ->
            if (rows.next()) {
                HeldBid(
                    id = rows.getLong(1),
                    bidder = rows.getLong(2).takeUnless { rows.wasNull() },
                    amount = rows.getInt(3),
                    fee = rows.getInt(4),
                )
            } else {
                null
            }
        }
    }

    private fun lockLot(db: Connection, lotId: String): LotRow? = db.prepareStatement(
        "$LOT_SELECT WHERE l.id = ? FOR UPDATE OF l",
    ).use { statement ->
        statement.setString(1, lotId)
        statement.executeQuery().use { rows -> if (rows.next()) row(rows) else null }
    }

    private fun lotById(db: Connection, lotId: String): LotRow = db.prepareStatement(
        "$LOT_SELECT WHERE l.id = ?",
    ).use { statement ->
        statement.setString(1, lotId)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "lot $lotId disappeared inside its own transaction" }
            row(rows)
        }
    }

    private fun query(db: Connection, sql: String): List<LotRow> =
        db.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(row(rows)) }
            }
        }

    private fun due(sql: String, now: Long): List<String> = read { db ->
        db.prepareStatement(sql).use { statement ->
            statement.setTimestamp(1, Timestamp(now))
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun row(rows: ResultSet) = LotRow(
        id = rows.getString("id"),
        seller = rows.getLong("seller_account").takeUnless { rows.wasNull() },
        cardId = rows.getInt("card_id"),
        startPrice = rows.getInt("start_price"),
        reservePrice = rows.getInt("reserve_price"),
        status = AuctionStatus.valueOf(rows.getString("status")),
        endsAt = rows.getTimestamp("ends_at").time,
        topBid = rows.getInt("top_bid").takeUnless { rows.wasNull() },
        topBidder = rows.getLong("top_bidder").takeUnless { rows.wasNull() },
        bidCount = rows.getInt("bid_count"),
        soldFor = rows.getInt("sold_for").takeUnless { rows.wasNull() },
        sellerName = rows.getString("seller_name"),
        topBidderName = rows.getString("bidder_name"),
    )

    // ------------------------------------------------------------------ views

    /** Fills in the per-viewer answers a client cannot work out from names. See [AuctionLot]. */
    private fun page(db: Connection, rows: List<LotRow>, viewer: Long): AuctionPage {
        val bids = yourBids(db, rows.map { it.id }, viewer)
        return AuctionPage(lots = rows.map { view(it, viewer, bids[it.id]) }, now = clock())
    }

    private fun view(lot: LotRow, viewer: Long, yourBid: Int?): AuctionLot {
        val yours = lot.seller == viewer
        return AuctionLot(
            id = lot.id,
            cardId = lot.cardId,
            sellerName = lot.sellerName,
            startPrice = lot.startPrice,
            endsAt = lot.endsAt,
            status = lot.status,
            topBid = lot.topBid,
            topBidderName = lot.topBidderName,
            bidCount = lot.bidCount,
            reserveMet = lot.topBid != null && lot.topBid >= lot.reservePrice,
            // The seller's own number and nobody else's: publishing it would turn a reserve into
            // a fixed price. The *fact* above is everyone's; the figure is not.
            reservePrice = lot.reservePrice.takeIf { yours },
            yours = yours,
            youLead = lot.topBidder == viewer,
            yourBid = yourBid,
            soldFor = lot.soldFor,
        )
    }

    /** The highest each lot has ever seen from this viewer — including bids since beaten. */
    private fun yourBids(db: Connection, lotIds: List<String>, viewer: Long): Map<String, Int> {
        if (lotIds.isEmpty()) return emptyMap()
        val ids = lotIds.joinToString(",") { "'${it.filter(Char::isLetterOrDigit)}'" }
        return db.prepareStatement(
            "SELECT lot_id, max(amount) FROM auction_bids " +
                "WHERE bidder_account = ? AND lot_id IN ($ids) GROUP BY lot_id",
        ).use { statement ->
            statement.setLong(1, viewer)
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) }
            }
        }
    }

    private fun refused(
        writer: AccountWriter,
        accountId: Long,
        save: GameSave,
        reason: AuctionRefusal,
    ): String = ApiJson.encodeToString(
        AuctionOutcome(player = writer.state(accountId, save), refusal = reason),
    )

    private fun settled(
        writer: AccountWriter,
        accountId: Long,
        save: GameSave,
        lot: LotRow,
        yourBid: Int? = null,
    ): String = ApiJson.encodeToString(
        AuctionOutcome(
            player = writer.state(accountId, save),
            lot = view(lot, accountId, yourBid),
        ),
    )

    /** The card as it comes back: an item to use, saying why it is in the bag. */
    private fun unsoldCard(cardId: Int) = CardItem(cardId, 1, CardOrigin.AUCTION_UNSOLD)

    // ------------------------------------------------------------------ plumbing

    private fun <T> read(block: (Connection) -> T): T = dataSource.connection.use { db ->
        val result = block(db)
        db.commit()
        result
    }

    // As wide as `AccountStore.transaction`'s, and for the same reason: nothing leaving [block]
    // may leave an open transaction on a connection going back to the pool.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> write(block: (Connection) -> T): T = dataSource.connection.use { db ->
        try {
            val result = block(db)
            db.commit()
            result
        } catch (failure: Throwable) {
            db.rollback()
            throw failure
        }
    }

    /**
     * An opaque lot id.
     *
     * `Random.Default` and not an injected generator, unlike the one `PvpRoutes` takes: that one
     * exists because a match *seed* is a fact a test has to be able to fix, and a lot id is not —
     * nothing reads one except by having been handed it. It needs to be unguessable enough that an
     * id is not a way to find somebody's lot, which twenty-two characters of it is.
     */
    private fun newId(): String {
        val generator = Random.Default
        return buildString {
            repeat(ID_LENGTH) {
                append(ID_ALPHABET[generator.nextInt(ID_ALPHABET.length)])
            }
        }
    }

    /** A lot as the database holds it — every field, viewer-independent. */
    private data class LotRow(
        val id: String,
        // Null once that account is deleted — see `closeOutOn`. Every use of these two below
        // is written to mean "nobody" rather than to assume somebody, which is what makes the
        // deletion rule fall out of the null instead of needing a path of its own.
        val seller: Long?,
        val sellerName: String?,
        val cardId: Int,
        val startPrice: Int,
        val reservePrice: Int,
        val status: AuctionStatus,
        val endsAt: Long,
        val topBid: Int?,
        val topBidder: Long?,
        val topBidderName: String?,
        val bidCount: Int,
        val soldFor: Int?,
    )

    /** The one bid on a lot whose money is currently held. */
    private data class HeldBid(val id: Long, val bidder: Long?, val amount: Int, val fee: Int)

    private companion object {
        const val ID_LENGTH = 22
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Enough to fill the list twice over. The house is browsed, not paged through. */
        const val BROWSE_LIMIT = 100

        const val LOT_COLUMNS = "l.id, l.seller_account, l.card_id, l.start_price, " +
            "l.reserve_price, l.status, l.ends_at, l.top_bid, l.top_bidder, l.bid_count, " +
            "l.sold_for"

        /** The read shape, with the two names a lot is displayed with. */
        val LOT_SELECT = """
            SELECT $LOT_COLUMNS, s.username AS seller_name, b.username AS bidder_name
            FROM auction_lots l
            LEFT JOIN accounts s ON s.id = l.seller_account
            LEFT JOIN accounts b ON b.id = l.top_bidder
        """.trimIndent()
    }
}
