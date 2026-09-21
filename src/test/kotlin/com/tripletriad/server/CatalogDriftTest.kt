package com.tripletriad.server

import com.tripletriad.model.AchievementCatalog
import com.tripletriad.model.Requirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The server's catalog copies against the one thing in this process that knows the client's map.
 *
 * ### The gap this closes, and the one it does not
 *
 * [Catalogs]' own KDoc names the failure and leaves it open: `npcs.json` and `campaigns.json` under
 * `resources/catalog/` are copies of the client's files, nothing checked that they stayed in step,
 * and on 2026-09-21 they had not — the client had shipped six new places, thirty-one opponents and
 * two achievement gates, and the server was still dealing from the roster before them. Every one of
 * those opponents answered `PveRefusal.UNKNOWN_OPPONENT`, which reads to a player as "this
 * opponent does not exist" rather than as "your server is stale".
 *
 * The check is possible because the map is not only in the client: `:core`'s `PlaceAchievements`
 * carries its own copy of who holds each place shut and what its tournament costs, and that copy
 * ships **inside the artifact this server links**. So the server can ask a question it can answer
 * alone — *does the roster I hold contain everybody the engine I link expects to find in it?* — and
 * a client-side data change that has not reached here fails the build here.
 *
 * It is not a full equality check, and cannot be: `:core` knows the places, not the card pools. A
 * pool edited on one side only still passes this and still makes an honest transcript replay to a
 * different board. The fix for that is the one [Catalogs] already names — publishing the catalogs
 * inside `:core` — and this test is the guard until then, not a substitute for it.
 */
class CatalogDriftTest {

    /**
     * Every opponent `:core` expects a place to be held shut by is in the server's own roster.
     *
     * This is the one that fails on the drift described above: the thirty-one FFVIII opponents are
     * named by `PlaceAchievements` and were absent from `resources/catalog/npcs.json`.
     */
    @Test
    fun everyOpponentTheMapNamesIsInTheServersRoster() {
        val roster = Catalogs.npcs.all.map { it.iconId }.toSet()
        val named = AchievementCatalog.all
            .mapNotNull { it.requirement as? Requirement.NpcsBeaten }
            .flatMap { it.iconIds }
            .distinct()

        assertEquals(emptyList(), named.filterNot { it in roster }, "named by :core, absent here")
    }

    /** And every tournament it expects to be opened by clearing a place. */
    @Test
    fun everyTournamentTheMapNamesIsInTheServersLadders() {
        val ladders = Catalogs.campaigns.all.map { it.key }.toSet()
        val named = AchievementCatalog.all
            .mapNotNull { it.requirement as? Requirement.CampaignWins }
            .map { it.campaignKey }
            .distinct()

        assertEquals(emptyList(), named.filterNot { it in ladders }, "named by :core, absent here")
    }

    /**
     * A place pays exactly what its tournament charges — the first entry is on the house, and a
     * server that credits one number while the client charges another makes the house take a cut.
     *
     * Reached through the achievement rather than through `PlaceAchievements`, which is `internal`
     * to `:core`: the reward on `ac-zone-<id>` *is* that copy of the fee.
     */
    @Test
    fun clearingAPlacePaysItsOwnTournamentsFee() {
        val clearings = AchievementCatalog.all.filter { it.id.startsWith(PLACE_PREFIX) }
        assertEquals(Catalogs.campaigns.all.size, clearings.size, "one clearing per tournament")

        for (campaign in Catalogs.campaigns.all) {
            val gate = assertNotNull(
                campaign.requiresAchievement,
                "${campaign.key} opens on nothing",
            )
            assertEquals(
                campaign.fee,
                AchievementCatalog[gate]?.mgpReward,
                "${campaign.key}: fee here against what $gate pays in :core",
            )
        }
    }

    /** An opponent behind a door that does not exist could never be sat down against. */
    @Test
    fun everyDoorAnOpponentWaitsBehindIsAnAchievementThatExists() {
        val missing = Catalogs.npcs.all
            .mapNotNull { npc -> npc.requiresAchievement?.let { npc.iconId to it } }
            .filter { (_, gate) -> AchievementCatalog[gate] == null }

        assertEquals(emptyList(), missing, "opponents behind an unknown achievement")
    }

    private companion object {
        /** `AchievementCatalog.placeCleared` builds ids with this. */
        const val PLACE_PREFIX = "ac-zone-"
    }
}
