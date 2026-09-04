package com.tripletriad.server

import com.tripletriad.data.Campaign
import com.tripletriad.model.CampaignRun
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchResult
import com.tripletriad.model.Npc
import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.Credentials
import com.tripletriad.protocol.PveFailure
import com.tripletriad.protocol.PveMatchRequest
import com.tripletriad.protocol.PveMatchStatus
import com.tripletriad.protocol.PveMatchView
import com.tripletriad.protocol.PveMove
import com.tripletriad.protocol.PveRefusal
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A tournament rung, refereed — what the server does that a ladder cannot do for itself.
 *
 * ### What is asserted here, and what is asserted in `:core`
 *
 * The *arithmetic* of a run — what a boosted drop is worth, what a drawn rung pays, what finishing
 * hands over — belongs to `CampaignRewardsTest`, where it can be stated exactly against a fixture.
 * What only this file can state is that the arithmetic is reached at all, and reached only by a
 * player actually standing on the rung they claim.
 *
 * ### Why nothing here plays a ladder to the top
 *
 * The server plays the opponent, so a test cannot decide to win. What it can do is play a rung out
 * and assert the run moved **consistently with whatever happened** — advanced on a win, closed on a
 * defeat, held on a draw. That is the invariant the wiring can break; the specific payouts are not
 * reachable from here at all.
 */
class PveCampaignTest {

    /**
     * A claim to be on a rung is checked against the profile, not believed.
     *
     * The refusal that matters most: without it `campaignKey` is a client-supplied flag that
     * doubles every drop in the game.
     */
    @Test
    fun aRungClaimedWithNoOpenRunIsRefused() = server {
        val session = register("run-none")

        val response = open(session.token, LADDER.opponents.first().iconId, LADDER.key)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PveRefusal.NOT_ON_THAT_RUNG, failure(response).code)
    }

    /**
     * The opponent has to be the one standing on the run's current rung.
     *
     * The check that is easy to leave out. With only "is there a run in this ladder", a client
     * could open the first rung's opponent as many times as the ladder is long and finish the
     * tournament against the easiest of its four.
     */
    @Test
    fun anotherRungsOpponentIsRefusedWhileStandingOnThisOne() = server {
        val session = register("run-skip")
        plant(session) { it.copy(campaignRun = CampaignRun(LADDER.key)) }

        val response = open(session.token, LADDER.opponents.last().iconId, LADDER.key)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PveRefusal.NOT_ON_THAT_RUNG, failure(response).code)
    }

    /** A run in a different ladder is not a run in this one. */
    @Test
    fun aRunInAnotherLadderDoesNotOpenThisOnesRungs() = server {
        val session = register("run-elsewhere")
        plant(session) { it.copy(campaignRun = CampaignRun(OTHER_LADDER)) }

        val response = open(session.token, LADDER.opponents.first().iconId, LADDER.key)

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(PveRefusal.NOT_ON_THAT_RUNG, failure(response).code)
    }

    /** Standing on the rung, against the right opponent, deals the match. */
    @Test
    fun theRungTheRunStandsOnOpens() = server {
        val session = register("run-ok")
        plant(session) { it.copy(campaignRun = CampaignRun(LADDER.key)) }

        val response = open(session.token, LADDER.opponents.first().iconId, LADDER.key)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    /** An ordinary free-play match still opens, and claims nothing. */
    @Test
    fun aMatchClaimingNoLadderIsUnaffected() = server {
        val session = register("run-free")

        val response = open(session.token, LADDER.opponents.first().iconId, campaignKey = null)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    /**
     * Playing the rung out moves the run in the way its own result says it should.
     *
     * Three outcomes, one assertion each, because the server plays the opponent and the test cannot
     * choose which it gets. Every branch of `PveReferee.closing` is reachable from here, and
     * exactly one of them runs.
     */
    @Test
    fun finishingARungMovesTheRunAccordingToItsResult() = server {
        val session = register("run-play")
        plant(session) { it.copy(campaignRun = CampaignRun(LADDER.key)) }

        val view = playOut(session.token, LADDER.opponents.first().iconId, LADDER.key)
        val result = assertNotNull(view.outcome?.result, "a finished match has a result")
        val after = assertNotNull(saveOf(session))

        when (result) {
            MatchResult.WIN ->
                assertEquals(1, after.campaignRun?.step, "a won rung did not advance the run")
            MatchResult.LOSE ->
                assertNull(after.campaignRun, "a lost rung left the run open")
            MatchResult.DRAW ->
                assertEquals(0, after.campaignRun?.step, "a drawn rung moved the run")
        }
    }

    /**
     * A match that outlives its run settles as an ordinary one.
     *
     * The case `V11__pve_campaign.sql` exists for. The row's claim is compared against the run the
     * profile holds **at settlement**, so a match left over from a run that is gone cannot advance
     * whatever run happens to be open by then.
     */
    @Test
    fun aMatchOutlivingItsRunIsSettledAsAnOrdinaryOne() = server {
        val session = register("run-orphan")
        plant(session) { it.copy(campaignRun = CampaignRun(LADDER.key)) }
        val response = open(session.token, LADDER.opponents.first().iconId, LADDER.key)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val dealt: PveMatchView = json.decodeFromString(response.bodyAsText())

        // The run goes away underneath the live match, exactly as a forfeit would leave it.
        plant(session) { it.leavingCampaign() }
        finish(session.token, begin(session.token, dealt.matchId))

        assertNull(saveOf(session)?.campaignRun, "a settled match reopened a closed run")
    }

    /**
     * An opponent behind an achievement is refused until it is held.
     *
     * The **server** refusing is the point. The roster the client draws already leaves her off, and
     * that is a list rather than a rule: a request naming her arrives all the same, and this is
     * what answers it.
     *
     * Answered as `NO_SUCH_OPPONENT`, the same code an unknown icon gets — see `PveReferee.open`
     * for why the two are deliberately not told apart.
     */
    @Test
    fun anOpponentBehindAnAchievementIsRefusedUntilItIsHeld() = server {
        val session = register("gate-shut")

        val response = open(session.token, GATED.iconId, campaignKey = null, format = GATED_FORMAT)

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        assertEquals(PveRefusal.NO_SUCH_OPPONENT, failure(response).code)
    }

    /** And is dealt the moment it is. */
    @Test
    fun earningTheAchievementOpensHer() = server {
        val session = register("gate-open")
        plant(session) { it.withAchievement(requireNotNull(GATED.requiresAchievement), 0L) }

        val response = open(session.token, GATED.iconId, campaignKey = null, format = GATED_FORMAT)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    // --- harness ----------------------------------------------------------------------------

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Postgres.dataSource, prometheusRegistry()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.register(name: String): Session {
        val response = client.post("/accounts") {
            protocolHeaders()
            setBody(json.encodeToString(Credentials(name, PASSWORD, address(name))))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        // Registration deals no cards; the box does. See [openStarterBox].
        return openStarterBox(json.decodeFromString(response.bodyAsText()))
    }

    private suspend fun ApplicationTestBuilder.open(
        token: String,
        opponent: String,
        campaignKey: String?,
        format: String = LADDER.format,
    ): HttpResponse = client.post("/pve/matches") {
        protocolHeaders()
        bearer(token)
        setBody(
            json.encodeToString(PveMatchRequest(opponent, format, campaignKey = campaignKey)),
        )
    }

    /**
     * Opens a rung and plays it to the end, whichever way it goes.
     *
     * The deal is followed by a read, because the deal alone leaves a board waiting: an opening the
     * toss gave the opponent is played by the first read of the match, not by the request that
     * dealt it. Without it every rung whose toss went the opponent's way would find nothing
     * playable and stop on its first turn.
     */
    private suspend fun ApplicationTestBuilder.playOut(
        token: String,
        opponent: String,
        campaignKey: String?,
    ): PveMatchView {
        val response = open(token, opponent, campaignKey)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val dealt: PveMatchView = json.decodeFromString(response.bodyAsText())
        return finish(token, begin(token, dealt.matchId))
    }

    /** The read that starts a match — see [playOut]. */
    private suspend fun ApplicationTestBuilder.begin(
        token: String,
        matchId: String,
    ): PveMatchView {
        val response = client.get("/pve/matches/$matchId") {
            protocolHeaders()
            bearer(token)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Places cards until the match ends.
     *
     * Bounded rather than `while (true)`: Sudden Death starts another board on a drawn one, and a
     * hung test is a worse way to learn that than a failing one. The bound `PveFlowTest` uses.
     */
    private suspend fun ApplicationTestBuilder.finish(
        token: String,
        from: PveMatchView,
    ): PveMatchView {
        var view = from
        var placements = 0
        while (view.status == PveMatchStatus.PLAYING && placements < MAX_PLACEMENTS) {
            if (view.playable.isEmpty()) break
            val move = PveMove(
                handIndex = view.playable.first(),
                position = view.cells.indexOfFirst { it == null },
            )
            val response = client.post("/pve/matches/${view.matchId}/moves") {
                protocolHeaders()
                bearer(token)
                setBody(json.encodeToString(move))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            view = json.decodeFromString(response.bodyAsText())
            placements++
        }
        assertEquals(PveMatchStatus.FINISHED, view.status, "the match never finished")
        return view
    }

    /** Rewrites the stored profile behind the API — a run is server-owned and has no endpoint. */
    private fun plant(session: Session, change: (GameSave) -> GameSave) {
        val accounts = AccountStore(Postgres.dataSource)
        val id = assertNotNull(accounts.accountIdForUsername(session.player.save.username))
        assertTrue(accounts.replaceSave(id, change(assertNotNull(accounts.saveFor(id)))))
    }

    private fun saveOf(session: Session): GameSave? {
        val accounts = AccountStore(Postgres.dataSource)
        return accounts.accountIdForUsername(session.player.save.username)
            ?.let(accounts::saveFor)
    }

    private suspend fun failure(response: HttpResponse): PveFailure =
        json.decodeFromString(response.bodyAsText())

    private fun HttpRequestBuilder.protocolHeaders() {
        header(VERSION_HEADER, CURRENT_VERSION.toString())
        contentType(ContentType.Application.Json)
    }

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val PASSWORD = "not-a-real-password"
        const val MAX_PLACEMENTS = 60

        /**
         * The one ladder a fresh profile can reach: open to all, and it charges to enter.
         *
         * Named by its properties rather than by `"balamb"` so the fixture survives the roster
         * being re-authored — the same reason `PveFlowTest` picks its opponent by rules.
         */
        val LADDER: Campaign =
            Catalogs.campaigns.all.first { it.requiresAchievement == null && it.fee > 0 }

        /** Any other ladder, for the run-in-the-wrong-tournament case. */
        val OTHER_LADDER: String = Catalogs.campaigns.all.first { it.key != LADDER.key }.key

        /**
         * The one opponent in the roster behind an achievement — Ishtar, in the shipped data.
         *
         * Found by the property rather than named, so this keeps testing the *rule* if a second
         * gated opponent is ever authored and the first one is renamed.
         */
        val GATED: Npc =
            Catalogs.npcs.all.first { it.requiresAchievement != null }

        /**
         * Free play, which is where a fresh account can actually meet her.
         *
         * Her own set's format would refuse the match for an unrelated reason — a new profile is
         * dealt an FFXIV starter and cannot field five FFVIII cards — and a test that passed by
         * being UNDEALABLE would say nothing about the gate. Free play admits both blocks, and it
         * is where the two Queens of Cards are meant to be reachable at all.
         */
        const val GATED_FORMAT: String = "free-play"
    }
}
