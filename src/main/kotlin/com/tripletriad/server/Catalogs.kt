package com.tripletriad.server

import com.tripletriad.data.CampaignCatalog
import com.tripletriad.data.CampaignCatalogParser
import com.tripletriad.data.CardCatalog
import com.tripletriad.data.CardCatalogParser
import com.tripletriad.data.FormatCatalog
import com.tripletriad.data.FormatCatalogParser
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.NpcCatalogParser
import com.tripletriad.data.StarterCatalog
import com.tripletriad.data.StarterCatalogParser

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
     * The campaign ladders, for one field: [com.tripletriad.data.Campaign.fee].
     *
     * The fourth copy, and the one that carries the least. A ladder is played entirely on the
     * client — its rungs are ordinary PvE matches, each submitted and replayed on its own — so the
     * server has no use for the opponents or the steps. What it needs is the **entry fee**, because
     * a fee is a spend and a spend the client applied to itself is a fee it can decline to pay.
     *
     * Reading the whole file to use one integer per ladder is the right trade anyway: the
     * alternative is a second, smaller table of fees that has to be kept in step with this one, and
     * a fee that disagrees between the two ends is worse than a file that is larger than it needs
     * to be.
     */
    val campaigns: CampaignCatalog by lazy { CampaignCatalogParser.parse(read(CAMPAIGNS_RESOURCE)) }

    /**
     * The boxes a character can be repaired with — `StarterPack.grantedTo`.
     *
     * The fifth copy, and the one that only matters when something has gone wrong: a profile that
     * has sold every card it owns cannot field a deck, and this is what puts five back. It is a
     * *grant of cards*, so it had to come here the moment `GameSave.cards` stopped being the
     * client's to write — a repair the server cannot perform is a repair that does not happen.
     */
    val starters: StarterCatalog by lazy { StarterCatalogParser.parse(read(STARTERS_RESOURCE)) }

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
        check(campaigns.all.isNotEmpty()) { "$CAMPAIGNS_RESOURCE parsed to an empty catalog" }
        check(starters.starters.isNotEmpty()) { "$STARTERS_RESOURCE parsed to an empty catalog" }
    }

    private fun read(resource: String): String =
        checkNotNull(Catalogs::class.java.getResourceAsStream(resource)) {
            "$resource is not on the classpath"
        }.use { it.readBytes().decodeToString() }

    private const val CARDS_RESOURCE = "/catalog/cards.json"
    private const val NPCS_RESOURCE = "/catalog/npcs.json"
    private const val FORMATS_RESOURCE = "/catalog/formats.json"
    private const val CAMPAIGNS_RESOURCE = "/catalog/campaigns.json"
    private const val STARTERS_RESOURCE = "/catalog/starters.json"
}

/**
 * The tables the profile endpoints price things from.
 *
 * A bundle rather than three parameters, because they travel together and always will: every one of
 * them answers "what is this worth", and a route needing two of the three would still have to be
 * given the third the day a price moved. `matchRoutes` takes its three separately for the opposite
 * reason — they answer different questions, and one of them is the opponent table.
 */
data class ShopTables(
    val cards: CardCatalog,
    val formats: FormatCatalog,
    val campaigns: CampaignCatalog,
    val starters: StarterCatalog,
) {
    companion object {
        /** The shipped tables, which is what the application runs on. */
        fun shipped(): ShopTables = ShopTables(
            cards = Catalogs.cards,
            formats = Catalogs.formats,
            campaigns = Catalogs.campaigns,
            starters = Catalogs.starters,
        )
    }
}
