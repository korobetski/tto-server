package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.Inventory
import com.tripletriad.model.BoosterItem
import com.tripletriad.model.BoosterType
import com.tripletriad.model.CardItem
import com.tripletriad.model.CardOrigin
import com.tripletriad.model.GameSave
import com.tripletriad.model.Item
import com.tripletriad.model.MiscItem
import com.tripletriad.model.PotionItem
import com.tripletriad.model.PotionType
import com.tripletriad.model.PouchItem
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import kotlin.math.absoluteValue

/**
 * `POST /admin/players/{id}/inventory` — the console's second write: cards and bag items, in or
 * out.
 *
 * ### It is the credit, with a list where the amount was
 *
 * Everything [creditPlayer] argues holds here unchanged, and the code is laid out the same way so
 * that the two can be read against each other: [AccountStore.applyOnceAcross] keyed on an operation
 * id the console mints once per intent, the profile locked and written on the writer's connection,
 * the audit row appended on that same connection, and a replayed answer re-emitted with `applied`
 * cleared. A support operator restoring a card a bug ate is doing exactly what a credit does to a
 * purse, and a second mechanism for it would be a second set of guarantees to get right.
 *
 * ### Several changes, one operation
 *
 * A request carries a list, applied in order, in one transaction. Restoring what a failed booster
 * opening should have produced is five cards and one booster, and making that six operations would
 * make it six audit rows and six chances for the operator's connection to drop halfway through a
 * repair. The list is bounded ([MAX_CHANGES]) and may not name the same entry twice — two changes
 * to one entry are one change the operator has not added up yet, and an audit row showing both
 * would record an intention nobody had.
 *
 * ### Removals floor at zero, and the receipt says where each entry landed
 *
 * The purse's rule, for the same reason: refusing a removal of three copies from somebody holding
 * two would send the operator back to reload the page and do the arithmetic, when "take them all"
 * is what they meant. [Inventory.remove] refuses rather than floors — it is a no-op when the bag
 * holds fewer — so the count is clamped here before it is called, and [AdminInventoryOutcome]
 * reports the before and after of every entry the request touched.
 *
 * ### Decks are left alone
 *
 * Taking away a card a deck fields leaves that deck unaffordable, and this does **not** repair it.
 * `GameSave.withoutCard` argues why at length: a deck is something the player built, every lookup
 * the game makes already refuses a deck it cannot afford, and the deck repairs itself the moment
 * the card comes back. So the receipt names the decks this change left short
 * ([AdminInventoryReceipt.decksShort]) and the console shows them — which is information, not a
 * refusal.
 *
 * ### What cannot be edited
 *
 * A pouch is a sale's proceeds waiting to be collected, tied to a lot by id. Creating one here
 * would be MGP out of nowhere with a lot id that settles nothing, and destroying one would be
 * taking money the auction house owes. The bag lists them; the credit route is the tool for money.
 */
internal suspend fun RoutingContext.editInventory(
    admins: AdminStore,
    accounts: AccountStore,
    cards: CardCatalog,
) {
    val admin = authenticateAdmin(admins) ?: return
    val accountId = call.parameters["id"]?.toLongOrNull() ?: return call.notFound()
    val request = call.receive<AdminInventoryRequest>()
    val resolved = request.resolve(cards)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed_request"))

    var fresh = false
    val body = accounts.applyOnceAcross(accountId, request.operationId) { writer ->
        // As in the credit: no character row is a 404, and abandoning rolls the claimed operation
        // id back with it, so the same request is free to be sent again once that is fixed.
        val before = writer.lock(accountId) ?: return@applyOnceAcross null
        val after = resolved.fold(before) { save, (target, delta) -> target.apply(save, delta) }
        writer.write(accountId, after)

        val outcomes = resolved.map { (target, _) ->
            AdminInventoryOutcome(
                kind = target.kind,
                cardId = target.cardId,
                key = target.key,
                before = target.count(before),
                after = target.count(after),
            )
        }
        admins.append(
            writer.db,
            AuditEntry(
                adminId = admin.id,
                action = INVENTORY_EDITED,
                subjectAccount = accountId,
                operationId = request.operationId,
                reason = request.reason,
                before = resolved.countsIn(before),
                after = resolved.countsIn(after),
            ),
        )
        fresh = true
        ConsoleJson.encodeToString(
            AdminInventoryReceipt(
                changes = outcomes,
                decksShort = after.decks
                    .filter { !it.isAffordable(after.cards) && it.isAffordable(before.cards) }
                    .map { it.name },
                applied = true,
            ),
        )
    } ?: return call.notFound()

    val receipt = ConsoleJson.decodeFromString<AdminInventoryReceipt>(body)
    call.respondConsole(if (fresh) receipt else receipt.copy(applied = false))
}

/**
 * `GET /admin/catalog` — what the inventory editor's pickers offer.
 *
 * Served rather than bundled into the console, because the catalog is the *server's*: a card the
 * client ships and this server does not know is a card [editInventory] refuses, and a picker built
 * from another copy would offer it.
 */
internal suspend fun RoutingContext.catalog(admins: AdminStore, cards: CardCatalog) {
    authenticateAdmin(admins) ?: return
    call.respondConsole(
        AdminCatalog(
            cards = cards.all.map { card ->
                AdminCatalogCard(id = card.id, name = card.name, rarity = card.rarity)
            },
            boosters = BoosterType.entries.map { it.name },
            potions = PotionType.entries.map { it.name },
            origins = CardOrigin.entries.map { it.name },
        ),
    )
}

/**
 * One bag entry as the console lists it.
 *
 * `key` names the variant within a kind — the booster, the potion, the card item's origin, the
 * pouch's lot — by the Kotlin enum name rather than the save's serial name, because it is also what
 * [AdminInventoryChange.key] is read back as.
 */
internal fun Item.toBagItem(cards: CardCatalog): AdminBagItem = when (this) {
    is CardItem -> AdminBagItem(
        kind = KIND_CARD_ITEM,
        key = origin.name,
        cardId = cardId,
        name = cards.byId[cardId]?.name ?: UNKNOWN,
        stack = stack,
        mgp = null,
    )
    is BoosterItem -> AdminBagItem(KIND_BOOSTER, boosterType.name, null, null, stack, null)
    is PotionItem -> AdminBagItem(KIND_POTION, potionType.name, null, null, stack, null)
    is MiscItem -> AdminBagItem(KIND_MISC, null, null, null, stack, null)
    is PouchItem -> AdminBagItem(
        kind = KIND_POUCH,
        key = lotId,
        cardId = cardId,
        name = cards.byId[cardId]?.name ?: UNKNOWN,
        stack = stack,
        mgp = mgp,
    )
}

/**
 * Each change paired with what it is aimed at, or null when the request is not one to apply: no
 * operation id or reason, no changes or too many, a delta of zero or out of bounds, a target that
 * names nothing, or two changes to one entry.
 */
private fun AdminInventoryRequest.resolve(cards: CardCatalog): List<Pair<Target, Int>>? {
    val targets = changes.map { it.target(cards) }
    val malformed = operationId.isBlank() ||
        reason.isBlank() ||
        changes.isEmpty() ||
        changes.size > MAX_CHANGES ||
        changes.any { it.delta == 0 || it.delta.absoluteValue > MAX_DELTA } ||
        targets.any { it == null } ||
        targets.distinctBy { it?.label }.size != targets.size
    return if (malformed) null else targets.filterNotNull().zip(changes.map { it.delta })
}

/**
 * The audit row's `before` or `after`: keyed by the entry's label — `CARD:12`, `BOOSTER:GOLD` — so
 * the console's side-by-side rendering lines the two up by key without knowing about inventories.
 */
private fun List<Pair<Target, Int>>.countsIn(save: GameSave): String =
    ConsoleJson.encodeToString(associate { (target, _) -> target.label to target.count(save) })

/**
 * What one change is aimed at, once its kind, card and key have been checked.
 *
 * Two shapes, because a collection entry and a bag entry are counted and moved by different
 * functions: [GameSave.copiesOf] and [GameSave.withCard] for the first, [Inventory] for the second.
 */
private sealed interface Target {
    val kind: String
    val cardId: Int?
    val key: String?

    /** Unique per entry: what the duplicate check compares and what the audit row is keyed by. */
    val label: String get() = listOfNotNull(kind, cardId, key).joinToString(":")

    fun count(save: GameSave): Int

    fun apply(save: GameSave, delta: Int): GameSave
}

private data class CollectionTarget(override val cardId: Int) : Target {
    override val kind: String get() = KIND_CARD
    override val key: String? get() = null

    override fun count(save: GameSave) = save.copiesOf(cardId)

    override fun apply(save: GameSave, delta: Int): GameSave {
        if (delta > 0) return save.withCard(cardId, delta)
        val taken = minOf(-delta, save.copiesOf(cardId))
        return if (taken == 0) save else save.withoutCard(cardId, taken)
    }
}

private data class BagTarget(
    override val kind: String,
    override val cardId: Int?,
    override val key: String?,
    /** A stack of one, which is what [Inventory] compares entries by. */
    val item: Item,
) : Target {
    override fun count(save: GameSave) = Inventory.count(save, item)

    override fun apply(save: GameSave, delta: Int): GameSave {
        if (delta > 0) return Inventory.add(save, item.withStack(delta))
        // Clamped, because `Inventory.remove` refuses a removal larger than the stack outright —
        // and "take five" from somebody holding three means "take all three" here.
        val taken = minOf(-delta, Inventory.count(save, item))
        return if (taken == 0) save else Inventory.remove(save, item, taken)
    }
}

/**
 * The change, checked against the catalog and the enums, or null when any part of it names nothing.
 *
 * Every refusal is the same 400 on purpose. The console builds these from pickers fed by
 * [catalog], so a bad one is a console bug or a hand-written request, and neither is helped by
 * being told which field it got wrong in more detail than the body it sent.
 */
private fun AdminInventoryChange.target(cards: CardCatalog): Target? {
    val card = cardId?.takeIf { it in cards.byId }
    return when (kind) {
        KIND_CARD -> card?.takeIf { key == null }?.let(::CollectionTarget)
        KIND_CARD_ITEM -> {
            val origin = if (key == null) CardOrigin.PLAIN else enumOrNull<CardOrigin>(key)
            if (card == null || origin == null) {
                null
            } else {
                BagTarget(kind, card, origin.name, CardItem(card, origin = origin))
            }
        }
        KIND_BOOSTER -> enumOrNull<BoosterType>(key)?.takeIf { cardId == null }
            ?.let { BagTarget(kind, null, it.name, BoosterItem(it)) }
        KIND_POTION -> enumOrNull<PotionType>(key)?.takeIf { cardId == null }
            ?.let { BagTarget(kind, null, it.name, PotionItem(it)) }
        KIND_MISC -> MiscItem().takeIf { key == null && cardId == null }
            ?.let { BagTarget(kind, null, null, it) }
        else -> null
    }
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    enumValues<E>().firstOrNull { it.name == name }

/** A collection entry: the only kind that is not in the bag. */
internal const val KIND_CARD = "CARD"
internal const val KIND_CARD_ITEM = "CARD_ITEM"
internal const val KIND_BOOSTER = "BOOSTER"
internal const val KIND_POTION = "POTION"
internal const val KIND_MISC = "MISC"

/** Listed, never editable — see [editInventory]. */
internal const val KIND_POUCH = "POUCH"

/**
 * Fifty changes in one request. A repair is a handful; fifty is a whole booster series restored at
 * once, and past that the request is more likely a script gone wrong than a person.
 */
private const val MAX_CHANGES = 50

/**
 * 999 of anything per change. The same argument as [MAX_CHANGES], per entry: nobody restores a
 * thousand copies of a card by hand, and an extra zero typed into the field should be refused
 * rather than audited.
 */
private const val MAX_DELTA = 999

/** A card id the catalog no longer knows. The console shows the id beside it. */
private const val UNKNOWN = "?"

/* -- the wire ---------------------------------------------------------------------------------- */

/** One collection entry. [rarity] is 0 for a card the catalog no longer knows. */
@Serializable
data class AdminOwnedCard(val cardId: Int, val name: String, val rarity: Int, val copies: Int)

/**
 * One bag entry. [cardId] and [name] are set for a card item and a pouch, [mgp] for a pouch
 * alone, and [key] for everything but a miscellaneous item — see [toBagItem].
 */
@Serializable
data class AdminBagItem(
    val kind: String,
    val key: String?,
    val cardId: Int?,
    val name: String?,
    val stack: Int,
    val mgp: Int?,
)

@Serializable
data class AdminDeck(val name: String, val cards: List<Int>)

/**
 * `{operationId, reason, changes}` — the body of the inventory write.
 *
 * Read with the server's own `Json`, not [ConsoleJson], which is why the nullable fields may carry
 * defaults here: the no-defaults rule is about what the console *reads*, and this is what it sends.
 */
@Serializable
data class AdminInventoryRequest(
    val operationId: String,
    val reason: String,
    val changes: List<AdminInventoryChange>,
)

/**
 * One entry to move. [kind] is one of the `KIND_*` constants above but [KIND_POUCH]; [cardId] is
 * set for [KIND_CARD] and [KIND_CARD_ITEM]; [key] names the booster, the potion or the card item's
 * origin (absent means plain).
 */
@Serializable
data class AdminInventoryChange(
    val kind: String,
    val cardId: Int? = null,
    val key: String? = null,
    /** Signed. Never zero, and never more than [MAX_DELTA] either way. */
    val delta: Int,
)

/**
 * Where each touched entry started and landed, the decks left short, and whether this caused it.
 */
@Serializable
data class AdminInventoryReceipt(
    val changes: List<AdminInventoryOutcome>,
    val decksShort: List<String>,
    val applied: Boolean,
)

@Serializable
data class AdminInventoryOutcome(
    val kind: String,
    val cardId: Int?,
    val key: String?,
    val before: Int,
    val after: Int,
)

/** What the pickers offer. The enum names are the ones [AdminInventoryChange.key] is read as. */
@Serializable
data class AdminCatalog(
    val cards: List<AdminCatalogCard>,
    val boosters: List<String>,
    val potions: List<String>,
    val origins: List<String>,
)

@Serializable
data class AdminCatalogCard(val id: Int, val name: String, val rarity: Int)
