package com.tripletriad.server

import com.tripletriad.data.StarterPack
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameRules
import com.tripletriad.model.GameSave
import com.tripletriad.model.NpcLevel
import com.tripletriad.model.TradeRule
import com.tripletriad.protocol.PvpStake
import com.tripletriad.protocol.PvpTableRequest
import com.tripletriad.protocol.Unlocks
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The accounts this server plays itself, against a real Postgres.
 *
 * ### The assertion this file exists for
 *
 * [aTableNobodyTookIsJoinedAndAFreshOneIsNot]. The rest is a bot getting on with things; that pair
 * is the feature — a lobby that answers when nobody else does, **without** taking the match a
 * person was two seconds from taking.
 *
 * ### Why the `bots` table is cleared between tests
 *
 * `Postgres` isolates tests by taking a fresh account name rather than truncating, and that is the
 * right default: a cleanup racing another test is worse than the state it removes. It does not
 * work here, because [BotDirector.tick] acts for *whichever* bots are due rather than for an
 * account the test names — so a bot left behind by the previous test would join the table this one
 * opened and the assertion would be about the wrong account.
 *
 * Clearing is safe in a way truncating `accounts` would not be: the table is touched by this file
 * alone, JUnit runs these methods one at a time, and each test enrols what it needs. The accounts
 * behind the deleted rows are left alone — they are ordinary accounts that nothing plays any more.
 */
class BotDirectorTest {

    /**
     * ### Why this clock starts at the real one instead of at a round number
     *
     * Every other fixture in this suite invents a `START` far from today and drives everything off
     * it, which works because the referee reads `clock()` for everything it decides. This one
     * cannot: a table's `openedAt` is read back from `pvp_tables.created_at`, and that column is
     * filled by the **database's** `now()` rather than by anything the test passes in — see
     * `PvpStore.openTable`, whose INSERT names every column except that one.
     *
     * So "has this table been standing for forty-five seconds" is the one question here that
     * compares the server's clock against Postgres's. In a deployment they are the same wall clock
     * and the comparison is sound; under an invented `START` eight years out, every table in the
     * lobby looks ancient and a bot joins it instantly. Starting from the real clock and advancing
     * from there is what makes the fixture ask the question a deployment asks.
     */
    private var now: Long = System.currentTimeMillis()

    private val accounts = AccountStore(Postgres.dataSource)
    private val bots = BotStore(Postgres.dataSource)
    private val pve = PveStore(Postgres.dataSource)
    private val pvp = PvpStore(Postgres.dataSource)

    private val pvpReferee = PvpReferee(
        cards = Catalogs.cards,
        formats = Catalogs.formats,
        accounts = accounts,
        pvp = pvp,
        clock = { now },
        // The shared generator, for the reason `PvpClaimTest` gives: a fresh `Random(SEED)` per
        // call would mint the same match id every time, and a field on this class would restart at
        // the seed for each test method.
        random = { GENERATOR },
    )

    private val pveReferee = PveReferee(
        cards = Catalogs.cards,
        npcs = Catalogs.npcs,
        formats = Catalogs.formats,
        accounts = accounts,
        pve = pve,
        clock = { now },
        random = { GENERATOR },
    )

    // ---- Enrolling --------------------------------------------------------

    /** A roster short of its count is filled, and the accounts it creates are playable. */
    @Test
    fun theRosterIsFilledToItsCount() {
        clearBots()
        val director = director(policy(count = PAIR))

        assertEquals(PAIR, director.ensureRoster())
        assertEquals(PAIR, bots.count())

        bots.due(now).forEach { bot ->
            val save = assertNotNull(accounts.saveFor(bot.accountId))
            assertEquals(NpcLevel.EXPERT, bot.band, "the roster is created at the policy's band")
            assertTrue(
                save.ownedCardIds().isNotEmpty(),
                "a bot with no cards cannot be dealt a hand",
            )
            assertTrue(
                save.decks.any { it.isComplete },
                "the starter's authored deck should have come with the box",
            )
        }
    }

    /** A roster already at its count is left alone, however often the loop comes round. */
    @Test
    fun aFullRosterIsLeftAlone() {
        clearBots()
        val director = director(policy(count = 1))

        assertEquals(1, director.ensureRoster())
        assertEquals(0, director.ensureRoster())
        assertEquals(1, bots.count())
    }

    /** Nobody can sign in as a bot: the password is a secret that was never kept. */
    @Test
    fun aBotHasNoUsablePassword() {
        clearBots()
        director(policy(count = 1)).ensureRoster()
        val bot = assertNotNull(bots.due(now).firstOrNull())

        val name = assertNotNull(accounts.usernameFor(bot.accountId))
        val stored = assertNotNull(accounts.credentialsFor(name))
        assertNull(accounts.identity(bot.accountId)?.email, "a bot has no inbox to confirm")
        assertTrue(
            listOf("", name, "password", "bot").none {
                PasswordHasher.verify(it, stored.passwordHash)
            },
            "a guessable password would be a collection with one credential over it",
        )
    }

    /**
     * The roster reads back with the numbers the metrics are built from.
     *
     * `BotStore.progress` is what every `tto_bots_*` gauge is derived from, and it is the one place
     * a bot's *profile* is read rather than its schedule. Asserted here because a gauge that reads
     * an empty list reports zero rather than failing — a chart that is quietly flat is worse than
     * one that is missing.
     */
    @Test
    fun theRosterReadsBackWithItsProgress() {
        clearBots()
        director(policy(count = PAIR)).ensureRoster()

        val progress = bots.progress()
        assertEquals(PAIR, progress.size)
        progress.forEach { row ->
            assertEquals(NpcLevel.EXPERT, row.band)
            assertEquals(
                row.save.cards.count { (_, copies) -> copies > 0 },
                row.collection,
                "the collection is the breadth of what is owned, not the depth",
            )
            assertTrue(row.collection > 0, "a starter box is cards, and cards are what is measured")
            assertTrue(row.save.level >= 1, "a profile that will not parse is dropped, not zeroed")
        }
    }

    // ---- Playing on its own -----------------------------------------------

    /**
     * A bot with nothing else to do sits down against an opponent and plays the match out.
     *
     * Asserted on the **profile** rather than on the match row: a bot that opened a board and
     * stalled on it would leave a row too, and what is being claimed is that a bot gets *paid* —
     * which is the whole of how it develops a collection.
     */
    @Test
    fun aBotPlaysASoloMatchThrough() {
        clearBots()
        val director = director(policy(count = 1))
        director.ensureRoster()
        val bot = assertNotNull(bots.due(now).firstOrNull())
        val before = assertNotNull(accounts.saveFor(bot.accountId))

        repeat(PASSES) {
            director.tick()
            now += PASS_MILLIS
        }

        val after = assertNotNull(accounts.saveFor(bot.accountId))
        assertTrue(after.pveMatches > before.pveMatches, "a bot should finish what it starts")
        assertTrue(after.xp > before.xp, "a settled match pays")
    }

    // ---- Sitting down with a person ---------------------------------------

    /**
     * **The pair this file exists for.**
     *
     * The same table, the same bot, and the only difference is how long it has been standing: a
     * table opened a moment ago is left for whoever is reading the lobby, and one nobody answered
     * is taken. Asserted in one test rather than two because the claim is the *difference* — two
     * tests could both pass with a bot that joins everything or nothing, depending on which
     * accident of the clock each of them chose.
     */
    @Test
    fun aTableNobodyTookIsJoinedAndAFreshOneIsNot() {
        clearBots()
        val director = director(policy(count = 1))
        director.ensureRoster()
        val bot = assertNotNull(bots.due(now).firstOrNull())
        levelUp(bot.accountId)

        val host = person("host")
        val table = assertNotNull(open(host, PvpStake.None), "the fixture needs a table")

        // A moment later: the host is still hoping for a person.
        now += BLINK
        director.tick()
        assertFalse(playing(bot.accountId, host), "a fresh table is not a bot's business")

        // Past the wait: nobody came.
        now += WAIT + GRACE
        director.tick()
        assertTrue(playing(bot.accountId, host), "a table nobody took should be answered")

        withdraw(table.table.id, host)
    }

    /**
     * **A bot opens the board it just sat down at.**
     *
     * Since `V16__pvp_pairing.sql` the turn clock does not start until both sides have been seen,
     * and a sighting is recorded by `PvpRoutes.attend` — which the *board* calls on open, not the
     * poll. A bot reads its match out of the store, so without this it would never attend: the
     * match would sit until `sweepPairing` closed it as `ABANDONED`, paying nobody, and the person
     * across the table would have waited for a match that never began.
     *
     * Asserted on the stored sighting rather than on the deadline, because the deadline only
     * appears once the *other* side has arrived too — and the host here is a fixture that never
     * opens anything.
     */
    @Test
    fun aBotAttendsTheMatchItJoins() {
        clearBots()
        val director = director(policy(count = 1))
        director.ensureRoster()
        val bot = assertNotNull(bots.due(now).firstOrNull())
        levelUp(bot.accountId)

        val host = person("attend")
        val table = assertNotNull(open(host, PvpStake.None), "the fixture needs a table")

        now += WAIT + GRACE
        director.tick()

        val match = assertNotNull(pvp.liveMatchFor(bot.accountId), "the bot should have joined")
        val side = assertNotNull(match.sideOf(bot.accountId))
        assertNotNull(match.seenAt(side), "a bot that never attends is a match nobody can win")
        assertNull(
            match.seenAt(side.opposite()),
            "and it must not attend for the person on the other side",
        )
        assertFalse(match.isAttended, "so the clock is still waiting on the host")

        withdraw(table.table.id, host)
    }

    /**
     * A bot does not sit down for a wager while the deployment has not said it may.
     *
     * The table stakes no MGP at all and is still refused, because a trade rule moves a **card** —
     * and a card taken from a bot is a card the world gained. Left standing rather than joined,
     * which is the honest outcome: the host is waiting for somebody who is playing for something.
     */
    @Test
    fun aBotWillNotSitDownForAWager() {
        clearBots()
        val director = director(policy(count = 1))
        director.ensureRoster()
        val bot = assertNotNull(bots.due(now).firstOrNull())
        levelUp(bot.accountId)

        val host = person("staked")
        assertNotNull(open(host, PvpStake(trade = TradeRule.ONE)), "the fixture needs a table")

        now += WAIT + BLINK
        director.tick()

        assertNull(pvp.liveMatchFor(bot.accountId), "a bot must not wager while wagers are off")
        assertTrue(
            pvp.openTables(now).any { it.hostAccount == host },
            "the table should still be there",
        )
    }

    /** A bot below the multiplayer level stays in the solo game, exactly as a player would. */
    @Test
    fun aBotBelowTheUnlockDoesNotEnterTheLobby() {
        clearBots()
        val director = director(policy(count = 1))
        director.ensureRoster()
        val bot = assertNotNull(bots.due(now).firstOrNull())

        val host = person("locked")
        val table = assertNotNull(open(host, PvpStake.None), "the fixture needs a table")

        now += WAIT + GRACE
        director.tick()

        assertFalse(playing(bot.accountId, host), "the level gate holds for a bot too")

        withdraw(table.table.id, host)
    }

    // ---- Fixtures ---------------------------------------------------------

    private fun policy(count: Int) = BotPolicy(
        enabled = true,
        count = count,
        band = NpcLevel.EXPERT,
        formatId = FORMAT,
        namePrefix = Postgres.freshAccount("bot"),
        wagers = false,
        tableWaitMillis = WAIT,
    )

    private fun director(policy: BotPolicy) = BotDirector(
        cards = Catalogs.cards,
        npcs = Catalogs.npcs,
        formats = Catalogs.formats,
        starters = Catalogs.starters,
        accounts = accounts,
        bots = bots,
        pve = pve,
        pvp = pvp,
        pveReferee = pveReferee,
        pvpReferee = pvpReferee,
        policy = policy,
        unlocks = Unlocks(),
        clock = { now },
        random = { GENERATOR },
    )

    /** A person with a starter box and a complete deck, so they can host a table. */
    private fun person(prefix: String): Long {
        val name = Postgres.freshAccount(prefix)
        val save = StarterPack.grantedTo(
            GameSave.new(name, createdAt = now),
            Catalogs.starters,
            Catalogs.cards.byId,
            GENERATOR,
            null,
        )
        return assertNotNull(accounts.register(name, "hash-$name", save, "$name@example.test"))
    }

    private fun open(host: Long, stake: PvpStake) = pvpReferee.openTable(
        host,
        PvpTableRequest(
            formatId = FORMAT,
            rules = GameRules(),
            roulette = false,
            stake = stake,
        ),
    ) as? Tabled.Opened

    /**
     * Whether [botId] is in a live match **against [host]**, rather than in one at all.
     *
     * Named rather than merely counted because this class shares a lobby with every other test in
     * the suite: a bot answering somebody else's leftover table would satisfy "has a match" and
     * prove nothing about the table this test opened. [withdraw] keeps that from happening in the
     * first place; this makes the assertion true regardless.
     */
    private fun playing(botId: Long, host: Long): Boolean =
        pvp.liveMatchFor(botId)?.let { host == it.accountOf(CardColor.BLUE) } == true

    /**
     * Takes a table back out of the lobby.
     *
     * A test that opens a table and leaves it standing hands the next one a free match nobody
     * asked for — which is how `aBotWillNotSitDownForAWager` first passed for the wrong reason,
     * its bot having joined the previous test's leftovers instead of refusing the wager.
     */
    private fun withdraw(tableId: String, host: Long) {
        pvp.dropTable(tableId, host)
    }

    /** Puts a bot past `Unlocks.multiplayer`, which is otherwise an evening of solo matches. */
    private fun levelUp(accountId: Long) {
        val save = assertNotNull(accounts.saveFor(accountId))
        assertTrue(accounts.replaceSave(accountId, save.copy(level = UNLOCKED)))
    }

    /** See the class KDoc: this is the one place in the suite that clears rather than isolates. */
    private fun clearBots() {
        Postgres.dataSource.connection.use { db ->
            db.prepareStatement("DELETE FROM bots").use { it.executeUpdate() }
            db.commit()
        }
    }

    private companion object {
        const val FORMAT = "ff14-standard"

        /** Two, which is `BotDirector.ENROL_PER_PASS` — the point being that one pass fills it. */
        const val PAIR = 2

        /** Comfortably past nine placements at one a pass, with rematches allowed for. */
        const val PASSES = 40
        const val PASS_MILLIS = 10_000L

        /** The policy's wait, and a moment that is nothing like it. */
        const val WAIT = 45_000L
        const val BLINK = 1_000L

        /**
         * Slack over the wait, to absorb the gap between the clock this test reads and the one
         * Postgres stamps `created_at` with. Milliseconds in practice; seconds is generous.
         */
        const val GRACE = 5_000L

        /** Past `Unlocks.DEFAULT_MULTIPLAYER`, without being near a stake ceiling worth having. */
        const val UNLOCKED = 6

        /**
         * **A seed no other test class in this suite uses.**
         *
         * Match and table ids are minted from this generator — `PvpReferee.newId` — and every test
         * class writes into the *same* database. Two classes seeded alike therefore mint the same
         * ids and the second one to run collides on `pvp_tables_pkey`, which surfaces as a failure
         * in whichever of the two happened to go second rather than in the one that is wrong.
         *
         * This file first used 20260907, the day it was written, and `PvpPairingTest` had picked
         * the same date for the same reason. Dates are the convention here; a date somebody else
         * has already taken is not one.
         */
        val GENERATOR = Random(20_260_922)
    }
}
