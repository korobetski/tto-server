package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.model.GameSave
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * The three tables `V19__admin.sql` added, over plain JDBC as everything else here is.
 *
 * ### Its own store, and the line it falls on
 *
 * `AccountStore` owns who a *player* is; this owns who an *administrator* is. They share no table
 * and, more to the point, no credential: the whole argument of `V19__admin.sql` is that a bearer
 * token minted by a game client must never be able to become an administrative one, and two stores
 * over two sets of tables is what that argument looks like in code.
 *
 * The one column that crosses is `admins.account_id`, and it is attribution rather than
 * authentication — nothing in this file reads it to decide anything.
 *
 * ### `AdminStore` reads; the writes that matter are somebody else's
 *
 * `web-platform.md` states it as a rule: anything that grants or spends goes through the
 * `AccountStore` methods and `applyOnce` that already exist, so a retried refund returns the first
 * answer instead of performing a second one. What this store writes is therefore only ever *its
 * own* rows — an administrator, a session, an audit line — and the audit line is written in the
 * same transaction as the effect it describes, which is why [append] takes a [Connection] as well
 * as offering to open one.
 *
 * ### Every write path here is guarded in SQL rather than in Kotlin
 *
 * [recordStep], [enrol] and [signIn] all carry their precondition in a `WHERE` clause and report
 * whether it held. That is not a style preference: the precondition in each case is about a
 * credential being used twice, and a read followed by a write is exactly the shape two simultaneous
 * requests slip through. See [recordStep], which is the one where it is load-bearing.
 *
 * ### The console's reads live here too, and they reach past `admins`
 *
 * Everything below [append] answers a screen rather than a credential: accounts, matches, lots, the
 * trail. None of it belongs to this store's three tables, and putting it in `AccountStore`,
 * `PveStore`, `PvpStore` and `AuctionStore` instead would scatter one screen's query across four
 * files — each of which would gain a method with no caller in the game, shaped by a console it
 * knows nothing about. `web-platform.md` chose the other split: one store for the console, reading
 * widely and writing nothing outside its own tables.
 *
 * @param cards the catalog, for the card names the auction house stores only by id. Defaulted
 *   rather than injected at every call site because there is exactly one and it is `by lazy` — a
 *   test that never asks a lot for a card name never loads it.
 */
// TooManyFunctions counts queries, which is what a data-access class is made of — the same
// judgement `AccountStore` and `AuctionStore` record above their own suppressions. Splitting this
// by table would put the administrator, their session and their audit row in three files that
// could only be read together. LargeClass counts the same thing twice over, since the size here is
// queries and the KDoc explaining what each one is for.
@Suppress("TooManyFunctions", "LargeClass")
class AdminStore(
    private val dataSource: DataSource,
    private val cards: CardCatalog = Catalogs.cards,
) {

    /**
     * Creates an administrator with a password and no second factor.
     *
     * Called by [ensureFirstAdministrator] at start-up and by nothing else — there is no route that
     * creates an administrator, because a console that can mint its own operators is a console
     * where one compromised session is permanent. The second and third administrator are created
     * the same way the first one is, by an operator with the environment variable and a restart.
     *
     * @return the new id, or null when the name is taken. Which is the ordinary case on every boot
     *   after the first, and the reason this is idempotent rather than conditional on a read.
     */
    fun create(username: String, passwordHash: String): Long? = transaction { db ->
        try {
            db.prepareStatement(
                "INSERT INTO admins (username, password_hash) VALUES (?, ?) RETURNING id",
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, passwordHash)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
        } catch (failure: SQLException) {
            // 23505, the standard's unique_violation — the same narrow catch
            // `AccountStore.register` explains at length, for the same reason: any other constraint
            // failing here is a bug and should surface, not be reported as "that name is taken".
            if (failure.sqlState == UNIQUE_VIOLATION) null else throw failure
        }
    }

    /** Whether any administrator exists at all. Read once at start-up, for the log line. */
    fun count(): Int = transaction { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM admins").use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }
    }

    /**
     * Everything a sign-in needs about one administrator, by name, case-insensitively.
     *
     * Returns the row for a **disabled** administrator too. The route has to verify the password
     * before it says anything about the account's state — answering "this account is disabled" to
     * an unverified caller would turn the form into a way of asking which administrators exist, and
     * which of them have been retired.
     */
    fun byUsername(username: String): StoredAdmin? = transaction { db ->
        db.prepareStatement(
            """
            SELECT id, username, password_hash, totp_secret,
                   totp_enrolled_at IS NOT NULL AS enrolled,
                   disabled_at IS NOT NULL AS disabled
            FROM admins WHERE username_key = lower(?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    StoredAdmin(
                        id = rows.getLong("id"),
                        username = rows.getString("username"),
                        passwordHash = rows.getString("password_hash"),
                        totpSecret = rows.getString("totp_secret"),
                        enrolled = rows.getBoolean("enrolled"),
                        disabled = rows.getBoolean("disabled"),
                    )
                } else {
                    null
                }
            }
        }
    }

    /** Replaces a digest made at a weaker bcrypt cost. The player path does the same at sign-in. */
    fun updatePasswordHash(adminId: Long, passwordHash: String) = transaction { db ->
        db.prepareStatement("UPDATE admins SET password_hash = ? WHERE id = ?").use { statement ->
            statement.setString(1, passwordHash)
            statement.setLong(2, adminId)
            statement.executeUpdate()
        }
        Unit
    }

    /**
     * Stores a freshly generated secret for an administrator who has not enrolled yet.
     *
     * ### The window this opens, and why it is not closable here
     *
     * Between an administrator being created and their first successful enrolment, the password
     * alone is enough to enrol *an* authenticator — so whoever holds the password first chooses the
     * second factor. That is inherent to a bootstrap that carries a password and no secret, and the
     * alternative is worse: a secret generated at start-up would be printed, logged, or left in a
     * container's environment, which is the failure `web-platform.md` designed this flow to avoid.
     *
     * What closes the window is the guard in this statement. `totp_enrolled_at IS NULL` means an
     * enrolment that has completed can never be replaced, so the window shuts the moment the
     * legitimate administrator finishes enrolling and cannot be reopened by anything short of an
     * operator with `psql` — see `docs/operations.md`, which is where losing an authenticator is
     * answered.
     *
     * @return false when this administrator is already enrolled, which is a refusal and not a
     *   retry: the caller must not treat it as "try again".
     */
    fun beginEnrolment(adminId: Long, secret: String): Boolean = transaction { db ->
        db.prepareStatement(
            "UPDATE admins SET totp_secret = ? WHERE id = ? AND totp_enrolled_at IS NULL",
        ).use { statement ->
            statement.setString(1, secret)
            statement.setLong(2, adminId)
            statement.executeUpdate() == 1
        }
    }

    /**
     * Completes an enrolment and opens the session it earned, in one transaction.
     *
     * Both or neither, because the two halves are one act from the operator's side: they typed a
     * code out of an authenticator they had just configured, and an enrolment that stored the
     * secret but lost the session would send them back to a form that now demands a code — which
     * would work, but only after a screen that looked like a failure.
     *
     * @return false when the guard did not hold: no secret pending, or somebody enrolled already.
     */
    fun enrol(adminId: Long, step: Long, tokenHash: String, expiresAt: Long): Boolean =
        transaction { db ->
            val enrolled = db.prepareStatement(
                """
                UPDATE admins SET totp_enrolled_at = now(), totp_last_step = ?
                WHERE id = ? AND totp_enrolled_at IS NULL AND totp_secret IS NOT NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, step)
                statement.setLong(2, adminId)
                statement.executeUpdate() == 1
            }
            if (!enrolled) return@transaction false

            insertSession(db, adminId, tokenHash, expiresAt)
            append(db, AuditEntry(adminId = adminId, action = ENROLLED))
            true
        }

    /**
     * Records the time step a sign-in used and opens the session, in one transaction.
     *
     * ### Why the replay check is this statement's `WHERE` clause
     *
     * A code is valid for thirty seconds and for one step. Somebody who reads it over a shoulder,
     * or sits on a proxy, or finds it in a screenshot has the rest of that window to use it — and a
     * check written as *read `totp_last_step`, compare, write it back* is refused only if the two
     * requests are far enough apart, which an attacker replaying a code immediately is not.
     *
     * `totp_last_step < ?` inside the UPDATE makes the database the arbiter: two requests with the
     * same code both try to advance the column, exactly one row is updated, and the loser is told
     * so. That is the difference between a defence and the appearance of one.
     *
     * @return false when the step has already been used. The caller answers it as a wrong code,
     *   because from the honest operator's side that is what it is — the code on their screen has
     *   been consumed and the next one is thirty seconds away.
     */
    fun signIn(adminId: Long, step: Long, tokenHash: String, expiresAt: Long): Boolean =
        transaction { db ->
            if (!recordStep(db, adminId, step)) return@transaction false
            insertSession(db, adminId, tokenHash, expiresAt)
            append(db, AuditEntry(adminId = adminId, action = SIGNED_IN))
            true
        }

    /**
     * Who this cookie belongs to, or null — and the same statement moves the idle clock forward.
     *
     * ### One statement, and what each clause of it is for
     *
     * `expires_at > now()` is the ceiling: a session ends a fixed distance from sign-in whatever
     * happens. `seen_at > now() - interval` is the idle clock, which is the one that matters for an
     * administration console — the threat is an unattended screen, and only an idle timeout
     * addresses that. `a.disabled_at IS NULL` is the third: retiring an administrator has to end
     * their access *now*, not when their current session happens to lapse.
     *
     * All three are in the `WHERE` clause rather than compared in Kotlin, for the reason
     * `AccountStore.accountForToken` gives — there is no window in which a row is fetched, judged
     * valid, and used a moment after it stopped being so, and the server's clock and the database's
     * cannot disagree about it.
     *
     * ### It writes on every read, and that is affordable here
     *
     * An idle clock that is not moved forward is a fixed expiry with extra steps, so the write is
     * the feature. `AccountStore.touch` throttles its equivalent because it runs once per player
     * per second across the whole lobby; this runs a handful of times a minute for the one or two
     * people who have a console, and a throttle would only blur the number it exists to keep.
     */
    fun session(tokenHash: String, idleMillis: Long): SignedInAdmin? = transaction { db ->
        db.prepareStatement(
            """
            UPDATE admin_sessions s SET seen_at = now()
            FROM admins a
            WHERE s.token_hash = ? AND s.admin_id = a.id
              AND a.disabled_at IS NULL
              AND s.expires_at > now()
              AND s.seen_at > now() - make_interval(secs => ?)
            RETURNING s.admin_id, a.username, s.created_at, s.expires_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.setDouble(2, idleMillis / MILLIS_PER_SECOND)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    SignedInAdmin(
                        id = rows.getLong("admin_id"),
                        username = rows.getString("username"),
                        signedInAt = rows.getTimestamp("created_at").time,
                        expiresAt = rows.getTimestamp("expires_at").time,
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Ends one session, and says whether there was one to end.
     *
     * Signing out must work for a session the server would no longer accept — an expired cookie
     * still deserves to be cleared rather than answered with "your expired session could not be
     * ended", which is the argument `Authentication.bearerToken` makes for the player's side.
     */
    fun closeSession(tokenHash: String): Boolean = transaction { db ->
        db.prepareStatement("DELETE FROM admin_sessions WHERE token_hash = ?").use { statement ->
            statement.setString(1, tokenHash)
            statement.executeUpdate() == 1
        }
    }

    /**
     * Deletes every session past its ceiling, wherever it came from.
     *
     * Tidiness rather than correctness — [session] refuses an expired row on sight, so nothing
     * depends on this having run — which is why it rides the slow branch of the sweep in
     * `Application.kt` rather than getting a loop of its own.
     */
    fun sweepSessions(): Int = transaction { db ->
        db.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM admin_sessions WHERE expires_at < now()")
        }
    }

    /**
     * Appends one audit row on a connection somebody else is holding.
     *
     * **This is the form that matters.** `admin_audit` is only worth anything if a row cannot be
     * missing while the change it describes happened, and cannot exist while the change did not —
     * which means the insert has to be in the caller's transaction, not in one of its own. The
     * overload below opens a transaction for the case where the action *is* the whole effect, which
     * is signing in and nothing else so far.
     */
    // Six columns of one row, and every one of them is a JDBC parameter position — the one place a
    // bare integer is not a magic number, as `AccountStore` says at length.
    @Suppress("MagicNumber")
    fun append(db: Connection, entry: AuditEntry) {
        db.prepareStatement(
            """
            INSERT INTO admin_audit
                (admin_id, action, subject_account, operation_id, reason, before, after)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, entry.adminId)
            statement.setString(2, entry.action)
            entry.subjectAccount?.let { statement.setLong(3, it) } ?: statement.setNull(3, BIGINT)
            statement.setString(4, entry.operationId)
            statement.setString(5, entry.reason)
            statement.setString(6, entry.before)
            statement.setString(7, entry.after)
            statement.executeUpdate()
        }
    }

    /**
     * Appends one audit row in a transaction of its own, for an action with no other effect.
     */
    fun append(entry: AuditEntry) = transaction { db -> append(db, entry) }

    /* -- what the console reads ---------------------------------------------------------------- */

    /**
     * `GET /admin/stats/overview` — one row, because it is one screen.
     *
     * Every figure comes out of `stats.overview`, which is one `SELECT` over three views joined in
     * the migration rather than three queries here. `V18__stats_views.sql` argues that at length
     * and the short version is that three queries are three *instants*: a dashboard could show a
     * match credited at a moment its payout was not yet in the purse total, and an operator
     * comparing two numbers that disagree has no way to know they were read a second apart.
     *
     * Null when the schema is not there, which means `postgres-bootstrap` did not run. The route
     * answers that as a 404 the console can explain rather than as a 500 from a missing relation.
     */
    fun overview(): AdminOverview? = transaction { db ->
        db.prepareStatement("SELECT * FROM stats.overview").use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.toOverview() else null }
        }
    }

    /**
     * `GET /admin/players?q=…` — the console's front door.
     *
     * ### Why one statement decides what the string was
     *
     * The console sends what the operator pasted and does not classify it, because classifying it
     * would mean asking them to know whether they were holding an id, a name or an address. So all
     * three are tried at once: the numeric form against `id`, the whole string against
     * `username_key` and `email_key`, and a prefix against `username_key`.
     *
     * The two `_key` columns are the generated lower-case ones, so "Ada" and "ada" are one query
     * rather than two — and the prefix match uses the same column, which is what makes it
     * case-insensitive without a function call that would defeat the index behind it.
     *
     * ### The ordering is part of the answer
     *
     * An exact name first, then an exact address, then whatever the prefix caught, alphabetically.
     * Without it the row somebody is looking for arrives wherever the planner felt like putting it,
     * which for a support search means reading a list to find the thing you already typed.
     *
     * ### Bots are returned, never hidden
     *
     * A lobby-filling bot is an account and the operator will meet one. `stats` excludes them from
     * every count for the reason its own migration gives; a *search* that excluded them would
     * answer "no such player" about a row that exists, which is the worst of the three possible
     * answers. [AdminPlayerSummary.bot] is how the console labels it instead.
     */
    // The positions are JDBC's, as everywhere else in this file: the same value is bound six times
    // because the statement asks six questions about it.
    @Suppress("MagicNumber")
    fun searchPlayers(query: String, limit: Int = SEARCH_LIMIT): List<AdminPlayerSummary> =
        transaction { db ->
            db.prepareStatement(
                """
                SELECT $PLAYER_COLUMNS
                FROM accounts a
                LEFT JOIN characters c ON c.account_id = a.id
                LEFT JOIN bots b ON b.account_id = a.id
                WHERE a.id = ?::bigint
                   OR a.username_key = lower(?)
                   OR a.email_key = lower(?)
                   OR a.username_key LIKE lower(?) || '%' ESCAPE '\'
                ORDER BY (a.username_key = lower(?)) DESC,
                         (a.email_key = lower(?)) DESC,
                         a.username_key
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                val trimmed = query.trim()
                // A query that is not a number matches no id, and `?::bigint` with a NULL is how
                // that is said without a second statement. Binding the text and letting Postgres
                // cast would refuse the whole query with a `22P02` for every search by name.
                trimmed.toLongOrNull()
                    ?.let { statement.setLong(1, it) }
                    ?: statement.setNull(1, BIGINT)
                statement.setString(2, trimmed)
                statement.setString(3, trimmed)
                statement.setString(4, trimmed.escapedForLike())
                statement.setString(5, trimmed)
                statement.setString(6, trimmed)
                statement.setInt(7, limit)
                statement.executeQuery().use { rows -> rows.collect { it.toPlayerSummary() } }
            }
        }

    /**
     * `GET /admin/players/{id}` — one screen's worth about one player, in four queries.
     *
     * Four rather than one, because the four are a row and three lists with no join that returns
     * all of them without multiplying rows together. They are in **one transaction**, which is the
     * part that matters: a balance read before a credit and a history read after it would show an
     * operator a purse that does not match the match that filled it.
     *
     * Null when the account does not exist. An account with no `characters` row is **not** null: it
     * is a registration that never claimed a starter, and answering 404 for it would tell the
     * operator the account is gone when it is sitting there. Everything the save document would
     * have supplied reads as zero, which is what it holds.
     */
    fun player(accountId: Long): AdminPlayerDetail? = transaction { db ->
        val summary = db.prepareStatement(
            """
            SELECT $PLAYER_COLUMNS
            FROM accounts a
            LEFT JOIN characters c ON c.account_id = a.id
            LEFT JOIN bots b ON b.account_id = a.id
            WHERE a.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toPlayerSummary() else null
            }
        } ?: return@transaction null

        val save = readSave(db, accountId)
        AdminPlayerDetail(
            id = summary.id,
            username = summary.username,
            email = summary.email,
            emailVerified = summary.emailVerified,
            createdAt = summary.createdAt,
            seenAt = summary.seenAt,
            mgp = summary.mgp,
            level = summary.level,
            bot = summary.bot,
            xp = save?.xp ?: 0L,
            cards = save?.cards?.values?.sum() ?: 0,
            distinctCards = save?.cards?.size ?: 0,
            record = AdminRecord(
                wins = save?.stats?.wins ?: 0,
                losses = save?.stats?.defeats ?: 0,
                draws = save?.stats?.draws ?: 0,
            ),
            recentMatches = readMatches(db, accountId),
            lots = readLots(db, party = accountId),
            audit = readAudit(db, subject = accountId, before = null, limit = RECENT_LIMIT).entries,
            collection = save?.cards.orEmpty().entries.sortedBy { it.key }.map { (id, copies) ->
                val card = cards.byId[id]
                AdminOwnedCard(
                    cardId = id,
                    name = card?.name ?: UNKNOWN_CARD,
                    rarity = card?.rarity ?: 0,
                    copies = copies,
                )
            },
            bag = save?.bag.orEmpty().map { it.toBagItem(cards) },
            decks = save?.decks.orEmpty().map { AdminDeck(name = it.name, cards = it.cards) },
        )
    }

    /**
     * The two facts a match row does not carry: when it happened, and who the sides are called.
     *
     * [PveMatchRow] and [PvpMatchRow] hold the hands, the moves, the rules and the seed, and
     * deliberately not the timestamps or a username — nothing in the game needs either, so neither
     * is in the type. Rather than widen two types the game uses on every turn for the benefit of
     * one administrative screen, this fetches exactly the difference.
     *
     * @param kind [KIND_PVE] or [KIND_PVP]. The table is not a parameter a statement can take, so
     *   the two statements are written out and chosen between here; a third kind is a third branch
     *   and not a string somebody could pass in.
     * @return null when there is no such row, and for [KIND_PVE] an [AdminMatchContext.red] of null
     *   — the opponent is an NPC, and the route fills its name from the row's icon id.
     */
    fun matchContext(kind: String, id: String): AdminMatchContext? = transaction { db ->
        val sql = when (kind) {
            KIND_PVE ->
                """
                SELECT m.created_at, m.finished_at, m.account_id AS blue_id,
                       blue.username AS blue_name, NULL::bigint AS red_id, NULL::text AS red_name
                FROM pve_matches m
                JOIN accounts blue ON blue.id = m.account_id
                WHERE m.id = ?
                """.trimIndent()

            else ->
                """
                SELECT m.created_at, m.finished_at, m.blue_account AS blue_id,
                       blue.username AS blue_name, m.red_account AS red_id,
                       red.username AS red_name
                FROM pvp_matches m
                JOIN accounts blue ON blue.id = m.blue_account
                JOIN accounts red ON red.id = m.red_account
                WHERE m.id = ?
                """.trimIndent()
        }
        db.prepareStatement(sql).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toMatchContext() else null
            }
        }
    }

    /**
     * `GET /admin/matches/credited/{id}` — a match a client submitted and this server credited.
     *
     * ### It has no move list, and that is what the digest is for
     *
     * `matches` stores the seed, the score, the payout and a hash of the transcript — not the
     * transcript. `V1__accounts_and_matches.sql` keeps the history rather than only the counters
     * and stops there on purpose: what the server needs afterwards is to refuse the *same*
     * submission twice, which a digest answers, and a replay of a client-side match is not evidence
     * the way a refereed session's move list is. So the inspector shows the sides, the score and
     * the hash, and an empty list of moves. A dispute about one of these is a dispute about a
     * transcript the operator can ask the player to resubmit.
     *
     * The whole answer is assembled here rather than from a row type, because there is no
     * `MatchRow` for this table: nothing in the game reads `matches` back.
     */
    fun creditedMatch(id: Long): AdminMatchDetail? = transaction { db ->
        db.prepareStatement(
            """
            SELECT m.id, m.account_id, a.username, m.played_at, m.opponent_icon_id, m.format,
                   m.seed, m.blue, m.red, m.result, m.mgp, m.transcript_hash
            FROM matches m
            JOIN accounts a ON a.id = m.account_id
            WHERE m.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toCreditedMatch() else null
            }
        }
    }

    /**
     * `GET /admin/auctions?status=…` — the house, newest first.
     *
     * In v1 because `AuctionRoutes.kt` calls the auction house "the one place in this game where
     * MGP moves between accounts with nothing checking what came from where", which makes it both
     * the likeliest support request and the one surface where a bug compounds.
     *
     * @param status one of `auction_lots`'s five states, or null for all of them. Not validated
     *   here: a status no lot has ever held returns nothing, which is the same answer a state that
     *   simply has no lots in it today would give.
     */
    fun auctions(status: String?, limit: Int = LOT_LIMIT): List<AdminAuctionLot> =
        transaction { db -> readLots(db, status = status, limit = limit) }

    /**
     * `GET /admin/audit` — the whole trail, or one account's.
     *
     * Keyset paging on `id`, not `OFFSET`. The table only ever grows at the head, so an offset page
     * 2 fetched after a write shows a row that was already on page 1 — and a trail that shows the
     * same action twice, or silently skips one, is a trail nobody can testify from.
     *
     * @param before the `id` the previous page ended on, from [AdminAuditPage.nextCursor].
     */
    fun audit(subject: Long?, before: Long?, limit: Int = AUDIT_PAGE): AdminAuditPage =
        transaction { db -> readAudit(db, subject, before, limit) }

    /**
     * The stored profile, decoded, or null when the account has never claimed a starter.
     *
     * [SaveJson] rather than a `Json` of this file's own, which is the argument `AccountStore`
     * makes above its own: a second instance "configured the same way" is two copies waiting to
     * drift the first time either is tuned, and this one reads documents that one wrote.
     */
    private fun readSave(db: Connection, accountId: Long): GameSave? =
        db.prepareStatement("SELECT save FROM characters WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    SaveJson.decodeFromString(GameSave.serializer(), rows.getString(1))
                } else {
                    null
                }
            }
        }

    /**
     * A player's history across all three tables, newest first.
     *
     * ### Three tables, because "a match" is three things
     *
     * `matches` is credited history a client submitted, `pve_matches` is a session this server
     * refereed whether or not it finished, `pvp_matches` is player against player.
     * `V18__stats_views.sql` counts all three separately for exactly this reason, and a history
     * that showed one of them would be a history that disagreed with the dashboard above it.
     *
     * ### What `result` carries, which is not the same thing per kind
     *
     * `matches.result` is a real outcome — `WIN`, `LOSE`, `DRAW`. The other two have a *status*:
     * `FINISHED`, `ABANDONED`, `FORFEITED`. Both are sent as stored rather than reconciled into one
     * vocabulary, because reconciling them would mean deciding that an abandoned match was a loss —
     * a judgement the schema deliberately does not make, and one an operator arbitrating a dispute
     * must not have made for them.
     *
     * The `UNION ALL` orders and limits **after** the union, so the newest twenty across all three
     * are the newest twenty and not seven from each.
     */
    // Every index is a JDBC position, and the account id is bound six times: once per branch, plus
    // twice more in the PvP branch to pick a side.
    @Suppress("MagicNumber")
    private fun readMatches(
        db: Connection,
        accountId: Long,
        limit: Int = RECENT_LIMIT,
    ): List<AdminMatchRow> = db.prepareStatement(
        """
        SELECT '$KIND_CREDITED' AS kind, m.id::text AS id, m.played_at AS at, m.result AS result,
               m.format AS format, m.mgp AS mgp, m.opponent_icon_id AS opponent
        FROM matches m
        WHERE m.account_id = ?
        UNION ALL
        SELECT '$KIND_PVE', p.id, coalesce(p.finished_at, p.created_at), p.status, p.format_id,
               coalesce((p.reward ->> 'mgp')::int, 0), p.opponent_icon
        FROM pve_matches p
        WHERE p.account_id = ?
        UNION ALL
        SELECT '$KIND_PVP', v.id, coalesce(v.finished_at, v.created_at), v.status, v.format,
               coalesce(
                   (v.payout -> CASE WHEN v.blue_account = ? THEN 'BLUE' ELSE 'RED' END
                             ->> 'mgp')::int,
                   0
               ),
               CASE WHEN v.blue_account = ? THEN red.username ELSE blue.username END
        FROM pvp_matches v
        JOIN accounts blue ON blue.id = v.blue_account
        JOIN accounts red ON red.id = v.red_account
        WHERE v.blue_account = ? OR v.red_account = ?
        ORDER BY at DESC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        for (position in 1..6) statement.setLong(position, accountId)
        statement.setInt(7, limit)
        statement.executeQuery().use { rows ->
            rows.collect {
                AdminMatchRow(
                    id = it.getString("id"),
                    kind = it.getString("kind"),
                    at = it.getTimestamp("at").instant(),
                    result = it.getString("result"),
                    format = it.getString("format"),
                    mgp = it.getInt("mgp"),
                    opponent = it.getString("opponent"),
                )
            }
        }
    }

    /**
     * Auction lots: everything in a state, or everything one account is involved in.
     *
     * The two callers want different slices of one query, and the slice is a `WHERE` rather than
     * two statements because fourteen columns and two joins would otherwise exist twice.
     *
     * [party] matches a lot this account is **selling or bidding on**, which is wider than the
     * player page's heading suggests and deliberately so: somebody writing in about the auction
     * house does not distinguish, and a screen that showed only their listings would hide the money
     * they have committed.
     */
    @Suppress("MagicNumber")
    private fun readLots(
        db: Connection,
        party: Long? = null,
        status: String? = null,
        limit: Int = LOT_LIMIT,
    ): List<AdminAuctionLot> = db.prepareStatement(
        """
        SELECT l.id, l.status, l.card_id, l.seller_account, sa.username AS seller_name,
               l.start_price, l.reserve_price, l.top_bid, l.top_bidder,
               ta.username AS bidder_name, l.bid_count, l.created_at, l.ends_at, l.sold_for
        FROM auction_lots l
        LEFT JOIN accounts sa ON sa.id = l.seller_account
        LEFT JOIN accounts ta ON ta.id = l.top_bidder
        WHERE (?::bigint IS NULL OR l.seller_account = ?::bigint OR l.top_bidder = ?::bigint)
          AND (?::text IS NULL OR l.status = ?::text)
        ORDER BY l.created_at DESC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        for (position in 1..3) {
            party?.let { statement.setLong(position, it) } ?: statement.setNull(position, BIGINT)
        }
        statement.setString(4, status)
        statement.setString(5, status)
        statement.setInt(6, limit)
        statement.executeQuery().use { rows -> rows.collect { it.toLot() } }
    }

    /** [audit]'s body, so [player] can read one account's trail inside its own transaction. */
    @Suppress("MagicNumber")
    private fun readAudit(
        db: Connection,
        subject: Long?,
        before: Long?,
        limit: Int,
    ): AdminAuditPage {
        val entries = db.prepareStatement(
            """
            SELECT e.id, e.at, ad.username AS admin, e.action, e.subject_account,
                   sa.username AS subject_name, e.reason, e.before::text AS before_json,
                   e.after::text AS after_json
            FROM admin_audit e
            JOIN admins ad ON ad.id = e.admin_id
            LEFT JOIN accounts sa ON sa.id = e.subject_account
            WHERE (?::bigint IS NULL OR e.subject_account = ?::bigint)
              AND (?::bigint IS NULL OR e.id < ?::bigint)
            ORDER BY e.id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            for (position in 1..2) {
                subject?.let { statement.setLong(position, it) }
                    ?: statement.setNull(position, BIGINT)
            }
            for (position in 3..4) {
                before?.let { statement.setLong(position, it) }
                    ?: statement.setNull(position, BIGINT)
            }
            statement.setInt(5, limit)
            statement.executeQuery().use { rows -> rows.collect { it.toAuditEntry() } }
        }

        // A full page means "there may be more", not "there is more". The alternative is reading
        // `limit + 1` rows and dropping one, which buys a hidden button at the cost of a wasted row
        // on every page; a last page that turns out to be empty is the cheaper wrong answer.
        return AdminAuditPage(
            entries = entries,
            nextCursor = entries.lastOrNull()?.id?.toString()?.takeIf { entries.size == limit },
        )
    }

    private fun ResultSet.toOverview() = AdminOverview(
        accounts = AdminOverviewAccounts(
            registered = getLong("accounts_registered"),
            verified = getLong("accounts_verified"),
            activeToday = getLong("accounts_active_today"),
            activeThisWeek = getLong("accounts_active_this_week"),
            newToday = getLong("accounts_new_today"),
        ),
        matches = AdminOverviewMatches(
            credited = getLong("matches_credited"),
            pve = getLong("matches_pve"),
            pvp = getLong("matches_pvp"),
            today = getLong("matches_today"),
        ),
        economy = AdminOverviewEconomy(
            // `mgp_total`, which is purses **plus** escrow: the money supply. `mgp_in_purses` is
            // the other figure the view offers and it is the wrong one for a single number, because
            // MGP sitting in a bid has not left the economy — it is committed, not spent, and a
            // total that omitted it would fall whenever somebody bid and rise when they lost.
            mgp = getLong("mgp_total"),
            lotsLive = getLong("lots_live"),
            mgpEscrowed = getLong("mgp_escrowed"),
        ),
        asOf = getTimestamp("as_of").instant(),
    )

    private fun ResultSet.toPlayerSummary() = AdminPlayerSummary(
        id = getLong("id"),
        username = getString("username"),
        email = getString("email"),
        emailVerified = getTimestamp("email_verified_at") != null,
        createdAt = getTimestamp("created_at").instant(),
        seenAt = getTimestamp("seen_at")?.instant(),
        mgp = getInt("mgp"),
        level = getInt("level"),
        bot = getBoolean("bot"),
    )

    private fun ResultSet.toMatchContext() = AdminMatchContext(
        startedAt = getTimestamp("created_at").instant(),
        finishedAt = getTimestamp("finished_at")?.instant(),
        blue = AdminParty(getLong("blue_id"), getString("blue_name")),
        red = getLong("red_id").takeUnless { wasNull() }
            ?.let { AdminParty(it, getString("red_name")) },
    )

    private fun ResultSet.toCreditedMatch() = AdminMatchDetail(
        id = getLong("id").toString(),
        kind = KIND_CREDITED,
        // The outcome, in the field the other two kinds use for a state. A credited match has no
        // lifecycle — it arrived settled — so "WIN" is the only thing here that says anything.
        status = getString("result"),
        format = getString("format"),
        // No `rules` column. The transcript carried them, and the transcript is not kept; an empty
        // list is the honest answer and an invented default would be a claim about a match.
        rules = emptyList(),
        seed = getInt("seed"),
        blue = AdminMatchSide(
            accountId = getLong("account_id"),
            name = getString("username"),
            score = getInt("blue"),
        ),
        red = AdminMatchSide(
            accountId = null,
            name = getString("opponent_icon_id"),
            score = getInt("red"),
        ),
        // One instant, used for both: `matches` records when the server accepted the submission and
        // not when the match was played, which is the only one of the two it can vouch for.
        startedAt = getTimestamp("played_at").instant(),
        finishedAt = getTimestamp("played_at").instant(),
        payout = AdminPayout(blue = getInt("mgp"), red = 0),
        hands = null,
        moves = emptyList(),
        transcriptHash = getString("transcript_hash"),
    )

    /**
     * One lot.
     *
     * ### Every nullable column is read into a local first, and that is not style
     *
     * `wasNull()` reports on the **last column read**, so `getInt(x).takeUnless { wasNull() }`
     * inside a constructor call is correct only as long as nothing is read between the two — and
     * argument evaluation order is exactly the thing a later edit reorders without noticing. Read
     * first, build second: the reads are then in a sequence a reader can check against the query.
     *
     * `topBidder` keys off `topBid` rather than off the bidder's own id. A bidder who deleted their
     * account leaves `top_bidder` NULL on a lot that still records what they bid — the schema
     * allows exactly that in `auction_lots_bidder` — and reporting no bidder there would hide a
     * standing bid rather than a missing person. Nobody having bid is the only case that is null.
     */
    private fun ResultSet.toLot(): AdminAuctionLot {
        val cardId = getInt("card_id")
        val sellerId = getLong("seller_account").takeUnless { wasNull() }
        val sellerName = getString("seller_name")
        val topBid = getInt("top_bid").takeUnless { wasNull() }
        val bidderId = getLong("top_bidder").takeUnless { wasNull() }
        val bidderName = getString("bidder_name")
        val soldFor = getInt("sold_for").takeUnless { wasNull() }
        return AdminAuctionLot(
            id = getString("id"),
            status = getString("status"),
            cardId = cardId,
            // The catalog, not a column. What a card is called is not the auction house's to
            // remember, and a copy in `auction_lots` would be a second answer to the same question.
            cardName = cards.byId[cardId]?.name ?: UNKNOWN_CARD,
            seller = AdminParty(sellerId, sellerName ?: GONE),
            startPrice = getInt("start_price"),
            reservePrice = getInt("reserve_price"),
            topBid = topBid,
            topBidder = topBid?.let { AdminParty(bidderId, bidderName ?: GONE) },
            bidCount = getInt("bid_count"),
            createdAt = getTimestamp("created_at").instant(),
            endsAt = getTimestamp("ends_at").instant(),
            soldFor = soldFor,
        )
    }

    private fun ResultSet.toAuditEntry() = AdminAuditEntry(
        id = getLong("id"),
        at = getTimestamp("at").instant(),
        admin = getString("admin"),
        action = getString("action"),
        subject = AdminAuditSubject(
            accountId = getLong("subject_account").takeUnless { wasNull() },
            name = getString("subject_name"),
        ),
        reason = getString("reason"),
        // As stored, and unparsed. The console renders the pair side by side without knowing what
        // any action's `before` looks like, which is what lets it display an action it has never
        // heard of — see its `AuditEntry`, where that opacity is argued as the point.
        before = getString("before_json"),
        after = getString("after_json"),
    )

    private fun <T> ResultSet.collect(read: (ResultSet) -> T): List<T> =
        buildList { while (next()) add(read(this@collect)) }

    /**
     * ISO 8601 in UTC, which is what every timestamp on this console's wire is.
     *
     * The console formats them for a French reader in one place, and a server that sent a
     * pre-formatted string would be deciding a locale from a container whose clock is UTC and whose
     * language is none. `Instant.toString` is the one representation that is unambiguous to parse.
     */
    private fun Timestamp.instant(): String = toInstant().toString()

    /**
     * Advances `totp_last_step`, or reports that this step is not in the future. See [signIn].
     */
    // The third parameter is `step` a second time — a JDBC position, not a number to name.
    @Suppress("MagicNumber")
    private fun recordStep(db: Connection, adminId: Long, step: Long): Boolean =
        db.prepareStatement(
            """
            UPDATE admins SET totp_last_step = ?
            WHERE id = ? AND (totp_last_step IS NULL OR totp_last_step < ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, step)
            statement.setLong(2, adminId)
            statement.setLong(3, step)
            statement.executeUpdate() == 1
        }

    /**
     * Inserts the session row and keeps the number of them bounded.
     *
     * The cap is [MAX_ADMIN_SESSIONS] and it is lower than the player's ten for two reasons: an
     * administrator has a desk rather than a pocket, and a forgotten row here is worth far more to
     * whoever finds it. The `OFFSET` form, the tie-break on `token_hash` and the ordering are
     * `AccountStore.openSession`'s, which explains why they are that way round.
     */
    @Suppress("MagicNumber")
    private fun insertSession(db: Connection, adminId: Long, tokenHash: String, expiresAt: Long) {
        db.prepareStatement(
            "INSERT INTO admin_sessions (token_hash, admin_id, expires_at) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.setLong(2, adminId)
            statement.setTimestamp(3, Timestamp(expiresAt))
            statement.executeUpdate()
        }
        db.prepareStatement(
            """
            DELETE FROM admin_sessions WHERE token_hash IN (
                SELECT token_hash FROM admin_sessions
                WHERE admin_id = ?
                ORDER BY created_at DESC, token_hash DESC
                OFFSET ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, adminId)
            statement.setInt(2, MAX_ADMIN_SESSIONS)
            statement.executeUpdate()
        }
    }

    /** Commits on success, rolls back on anything at all. `AccountStore.transaction`'s twin. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { db ->
        try {
            val result = block(db)
            db.commit()
            result
        } catch (failure: Throwable) {
            db.rollback()
            throw failure
        }
    }

    internal companion object {
        private const val UNIQUE_VIOLATION = "23505"

        /** `java.sql.Types.BIGINT`, named so the import is not one more line for one constant. */
        const val BIGINT = java.sql.Types.BIGINT

        const val MILLIS_PER_SECOND = 1_000.0

        /**
         * Five: a desktop, a laptop, and room for a browser profile that was reinstalled. Past
         * that, a row is a session nobody remembers opening.
         */
        const val MAX_ADMIN_SESSIONS = 5

        /**
         * What a player row is made of, written once because the search, the roster and the
         * detail page ask for the same nine things about one account and differ in their `WHERE`.
         *
         * The purse comes out of the save document rather than a column, because `characters.save`
         * is one document by V1's decision and nothing in this server reads a profile by column.
         * `coalesce` to zero covers the account that never claimed a starter: it has no row at all,
         * so the outer join produces NULL and a missing purse is genuinely no MGP rather than
         * unknown MGP. `bots` is joined for the label — never to filter.
         */
        // Internal rather than private: `AdminInsightStore.players` pages through the same nine
        // columns, and a second copy would be a second place to add the tenth.
        internal val PLAYER_COLUMNS =
            """
            a.id, a.username, a.email, a.email_verified_at, a.created_at, a.seen_at,
            coalesce((c.save ->> 'MGP')::int, 0) AS mgp,
            coalesce((c.save ->> 'LEVEL')::int, 0) AS level,
            (b.account_id IS NOT NULL) AS bot
            """.trimIndent()

        /**
         * Fifty search results. Enough that a prefix somebody typed shows what it caught, and few
         * enough that a one-letter query is a list rather than a table dump — and the ordering puts
         * an exact match first, so the fifty only matter when nothing matched exactly.
         */
        const val SEARCH_LIMIT = 50

        /** A screenful of history, and of a player's own audit trail. The full lists are routes. */
        const val RECENT_LIMIT = 20

        /** Lots, per page of the house and per player page. */
        const val LOT_LIMIT = 100

        /** One page of the trail. Paged by keyset, so this is a page size and not a ceiling. */
        const val AUDIT_PAGE = 50

        /**
         * A card the catalog has never heard of, which is a lot listed by a build that had a wider
         * catalog than this one. Shown rather than hidden: the operator can see the id beside it.
         */
        const val UNKNOWN_CARD = "?"

        /**
         * The name of an account that is gone, for a lot that outlived it.
         *
         * Not a sentence, and not French: the console owns the wording and renders a null
         * `accountId` in its own "compte supprimé" style. This is the fallback for the other half
         * of the pair — a row where even the username is unrecoverable — so that something renders.
         */
        const val GONE = "?"

        /**
         * Escapes the three characters `LIKE` reads as syntax, for a pattern built from typing.
         *
         * Without it a support operator pasting a username containing `_` gets a single-character
         * wildcard instead of an underscore — which matches more rows than it should and, for a `%`
         * at the front, scans the table. The backslash is escaped first, or it would escape the
         * escapes added after it.
         */
        fun String.escapedForLike(): String =
            replace("""\""", """\\""").replace("%", """\%""").replace("_", """\_""")
    }
}

/**
 * When a match happened and what its sides are called — the two facts the row types omit.
 *
 * [red] is null for a PvE session, whose opponent is an NPC and has no account. See
 * [AdminStore.matchContext], which is the only thing that builds one.
 */
data class AdminMatchContext(
    val startedAt: String,
    val finishedAt: String?,
    val blue: AdminParty,
    val red: AdminParty?,
)

/**
 * One administrator, as a sign-in needs to see them.
 *
 * [totpSecret] is **secret** and is on this object because verifying a code needs the key itself —
 * see `V19__admin.sql` for why there is no one-way form of it. It goes to [Totp] and to the one
 * response that shows a freshly issued one, and nowhere else.
 */
data class StoredAdmin(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val totpSecret: String?,
    /** True once a code computed from [totpSecret] has actually been entered. */
    val enrolled: Boolean,
    val disabled: Boolean,
)

/** Who a console session belongs to, and the two instants `GET /admin/me` reports. */
data class SignedInAdmin(
    val id: Long,
    val username: String,
    val signedInAt: Long,
    val expiresAt: Long,
)

/**
 * One row of `admin_audit`, as the server writes it.
 *
 * A data class rather than eight parameters, because the interesting calls fill three of them and
 * the rest are absent — and because an audit row is a record, which is the thing a data class is.
 *
 * @property action the server's own name for what was done. Deliberately not an enum: the table has
 *   no CHECK listing the set for the reason its own comment gives, and the constants that make up
 *   the set live beside the routes that write them.
 * @property subjectAccount the player this was done to, or null for an action about nobody.
 * @property operationId the `applied_operations` key of the effect, so a retried write that
 *   returned the first answer can be told from a second write that really happened.
 * @property before the state either side of the change, as JSON. Both null for a read or a sign-in.
 */
data class AuditEntry(
    val adminId: Long,
    val action: String,
    val subjectAccount: Long? = null,
    val operationId: String? = null,
    val reason: String? = null,
    val before: String? = null,
    val after: String? = null,
)

/** Signing in. Recorded because "who has been in the console, and when" is a question about it. */
const val SIGNED_IN = "SIGN_IN"

/** The first sign-in, which created the second factor. Distinct because it happens once. */
const val ENROLLED = "TOTP_ENROLLED"

/**
 * Creates the administrator named by the environment, if there is one and the name is free.
 *
 * ### Why this is at start-up and not a route
 *
 * Because the first administrator has to exist before anybody can sign in, and a route that creates
 * one would either be unauthenticated — a console anybody on the internet can enrol into — or
 * authenticated, which is the chicken and the egg. An environment variable and a restart is a
 * credential only somebody with the host has, which is the right bar for the first one.
 *
 * ### What it deliberately does not do
 *
 * It **never touches an administrator that already exists**: not the password, not the second
 * factor, not `disabled_at`. So leaving the variables set across a redeploy is inert rather than a
 * password reset on every boot, and a retired administrator does not come back because somebody
 * forgot to clear an environment file. The right move after the first boot is to remove them, and
 * `.env.prod.sample` says so.
 *
 * ### What reaches the log
 *
 * That an administrator was created, or that the name was taken. Not the name, and emphatically not
 * the password: the name is half of a credential for the one surface that can move balances, and
 * `LogSecrecyTest` exists because a rule stated in a comment holds only until somebody is debugging
 * on a Sunday. The operator already knows what they typed.
 *
 * @return true when a row was created.
 */
fun ensureFirstAdministrator(
    store: AdminStore,
    bootstrap: FirstAdministrator?,
    log: (String) -> Unit,
): Boolean {
    if (bootstrap == null) {
        // Worth a line only when it leaves the console unreachable. A deployment past its first
        // boot has no variables set and an administrator in the table, which is the steady state.
        if (store.count() == 0) {
            log(
                "No administrator exists and TTO_ADMIN_USERNAME is unset: " +
                    "the administration console cannot be signed in to",
            )
        }
        return false
    }

    val created = store.create(bootstrap.username, PasswordHasher.hash(bootstrap.password)) != null
    log(
        if (created) {
            "Created the first administrator. Their second factor is enrolled at first sign-in"
        } else {
            "An administrator with that name already exists; TTO_ADMIN_* is ignored"
        },
    )
    return created
}
