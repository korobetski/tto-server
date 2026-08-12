package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardCatalogParser
import com.tripletriad.data.FormatCatalog
import com.tripletriad.data.FormatCatalogParser
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.NpcCatalogParser

/**
 * The card and opponent tables, read once from the classpath.
 *
 * ### Why the server needs its own copy
 *
 * Because it must not take the client's word for what a card is worth. Replaying a match means
 * dealing the same hands and resolving the same captures, and both come out of these two files —
 * so if the claimant supplied them, the claimant would be choosing the rules.
 *
 * ### The duplication, which is a real problem and is not solved here
 *
 * `cards.json` and `npcs.json` are **copies** of the two files under the client's
 * `shared/src/commonMain/composeResources/files/`. Nothing checks that they stay identical, and
 * they will not: the day one of them is regenerated and the other is not, every transcript from the
 * updated client is rejected by a server dealing from the old table, and the rejection will look
 * like cheating rather than like a stale file.
 *
 * The parsers are already shared — [CardCatalogParser] comes from `:core`, so the two sides cannot
 * disagree about how to *read* the data. Making them share the bytes as well means publishing the
 * catalogs inside the `:core` artifact, which is the right fix and is not the first brick.
 */
object Catalogs {

    val cards: CardCatalog by lazy { CardCatalogParser.parse(read(CARDS_RESOURCE)) }

    val npcs: NpcCatalog by lazy { NpcCatalogParser.parse(read(NPCS_RESOURCE)) }

    /**
     * The match formats — which cards a match admits and which rules it may draw.
     *
     * The third copy of a client resource, and it earns its place the same way the other two do:
     * the server replays transcripts with the real engine, and since `Roulette.pools` moved out of
     * `:core` the roulette draws from *this*. A server without it could not deal a match to verify.
     */
    val formats: FormatCatalog by lazy { FormatCatalogParser.parse(read(FORMATS_RESOURCE)) }

    /**
     * Forces both catalogs, so that a missing or malformed one fails now.
     *
     * `by lazy` alone would defer the failure to the first request that happened to need it — a
     * server that starts, reports itself healthy, and then rejects every transcript. A missing
     * catalog is a broken build, not a runtime condition, so it belongs on the way up next to the
     * failed migration. That is why [Application.main] calls this.
     */
    fun preload() {
        check(cards.all.isNotEmpty()) { "$CARDS_RESOURCE parsed to an empty catalog" }
        check(npcs.all.isNotEmpty()) { "$NPCS_RESOURCE parsed to an empty catalog" }
        check(formats.formats.isNotEmpty()) { "$FORMATS_RESOURCE parsed to an empty catalog" }
    }

    private fun read(resource: String): String =
        checkNotNull(Catalogs::class.java.getResourceAsStream(resource)) {
            "$resource is not on the classpath"
        }.use { it.readBytes().decodeToString() }

    private const val CARDS_RESOURCE = "/catalog/cards.json"
    private const val NPCS_RESOURCE = "/catalog/npcs.json"
    private const val FORMATS_RESOURCE = "/catalog/formats.json"
}
