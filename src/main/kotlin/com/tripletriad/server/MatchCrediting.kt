package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.FormatCatalog
import com.tripletriad.data.MatchRewards
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.PveMatches
import com.tripletriad.model.MatchResult
import com.tripletriad.protocol.MatchReceipt
import com.tripletriad.protocol.MatchTranscript
import com.tripletriad.protocol.MatchVerdict
import com.tripletriad.protocol.RejectionReason
import com.tripletriad.protocol.RewardSummary
import com.tripletriad.protocol.TranscriptVerifier
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * Turning an accepted transcript into progression — the step that makes the server authoritative.
 *
 * ### The client no longer decides what it earned
 *
 * Before accounts, a match ended with the client applying `MatchRewards.credit` to its own save and
 * the server merely agreeing that the match was real. That is a strange arrangement on inspection:
 * the expensive property (an unforgeable match) was being bought and then spent on a profile
 * anybody could edit. Here the server replays the match, credits the reward **itself**, against the
 * save it holds, and sends back the profile it wrote.
 *
 * ### The same function the client used to call
 *
 * [MatchRewards.credit] is `:core`'s, not a server-side reimplementation, so the payouts, the drop
 * table and the achievement rules are the ones the game was built with. This file is arithmetic
 * about *which* profile and *which* random, and nothing about what a win is worth.
 */
object MatchCrediting {

    /**
     * Replays [transcript] against the stored profile of [accountId] and credits the result.
     *
     * Everything the deal reads comes from the **stored** save, so `MatchTranscript.ownedCards` is
     * not consulted at all — see [TranscriptVerifier.verify].
     *
     * @return the receipt to send back, whatever happened: a rejection, a credited match, or a
     *   duplicate. None of the three is an error, so none of them throws.
     */
    // Five exits, all of them guard clauses: no character, rejected, a seed that was not issued,
    // a duplicate, and credited. Folding them into one would nest the happy path four deep to save
    // a keyword. `LongMethod` for the same reason — the length is the list of things that can go
    // wrong with a submission, and each one is three lines and a sentence saying which it is.
    @Suppress("LongParameterList", "ReturnCount", "LongMethod")
    fun credit(
        transcript: MatchTranscript,
        accountId: Long,
        store: AccountStore,
        cards: CardCatalog,
        npcs: NpcCatalog,
        formats: FormatCatalog,
        now: Long,
    ): MatchReceipt {
        // ### Why the whole of this runs inside `creditMatch`
        //
        // It used to run against a `store.saveFor(accountId)` read in its own transaction, and the
        // result was written back in another. That is a read-modify-write with no lock between the
        // halves: a purchase or a `PUT /me/save` committing in the gap was erased by the write, and
        // both requests were answered `200`. `AccountStore.lockSave` was added to end exactly this
        // and named the offline queue as the case it was for — and the offline queue drains through
        // *here*, which was still doing it.
        //
        // The replay reads the owner's collection, so it belongs under the lock too: verifying
        // against a profile that has since changed is the same staleness one step earlier.
        //
        // The two locals below are what the closure has to report back out. A verdict is not a
        // failure the store can express — it is this file's business — so it is captured rather
        // than returned, and `Credited.NotCredited` is the store saying "your function declined".
        var verdict: MatchVerdict? = null
        var reward: RewardSummary? = null

        val paid = store.creditMatch(
            accountId = accountId,
            transcriptHash = fingerprint(transcript),
        ) { save ->
            val replayed = TranscriptVerifier.verify(transcript, cards, npcs, formats, owner = save)
            verdict = replayed
            if (replayed !is MatchVerdict.Accepted) return@creditMatch null

            // The format is resolved the same way the verifier resolves it — from the transcript's
            // own `formatId`, and then only because the verifier has already accepted it. See
            // `TranscriptVerifier`, which is where a format a client invented is refused.
            val format = formats[transcript.formatId]
                ?: error("verified a transcript in a format that does not exist")

            val npc = npcs.byIcon(transcript.opponentIconId, format.id)
                ?: error("verified a transcript against an opponent that does not exist")

            // Read off the server's own score, never off the client's claim — and derived here
            // rather than through `MatchResult.of`, which returns null for a sudden-death draw. A
            // transcript cannot express a rematch, so one that replays to a draw *is* a completed
            // drawn match; writing the mapping out says so, where a `!!` would only assume it.
            val result = when {
                replayed.blue > replayed.red -> MatchResult.WIN
                replayed.blue < replayed.red -> MatchResult.LOSE
                else -> MatchResult.DRAW
            }

            // The rules the match was played under are **re-derived from the seed**, not taken from
            // the transcript, which does not carry them. That is the same reason the score is
            // recomputed: `RULES_W` feeds achievements, so a client that could name its own rules
            // could name the ones that pay best.
            val rules = PveMatches.rulesFor(npc, format, Random(transcript.seed))

            // A generator of its own, and deliberately not the replay's. The replay's is consumed
            // to exactly the position the match ended at, which depends on how the match went — so
            // continuing it would make the MGP top-up and the drop roll depend on the number of AI
            // turns. Seeding from the transcript keeps the payout reproducible for an audit while
            // leaving it independent of the replay.
            val credited = MatchRewards.credit(
                save = save.startingMatch(againstNpc = true),
                npc = npc,
                result = result,
                rules = rules,
                at = now,
                random = Random(transcript.seed.inv()),
            )

            reward = RewardSummary(
                result = credited.reward.result,
                mgp = credited.reward.mgp,
                xp = credited.reward.xp,
                items = credited.reward.items,
                achievementIds = credited.reward.achievements.map { it.id },
            )

            Crediting(
                match = RecordedMatch(
                    opponentIconId = transcript.opponentIconId,
                    formatId = transcript.formatId,
                    seed = transcript.seed,
                    blue = replayed.blue,
                    red = replayed.red,
                    result = result,
                    mgp = credited.reward.mgp,
                    xp = credited.reward.xp,
                ),
                save = credited.save.copy(lastSave = now, saveNumber = save.saveNumber + 1),
            )
        }

        val player = when (paid) {
            // The closure declined. Either the replay rejected the transcript — in which case the
            // verdict it captured is the answer and there is nothing to pay — or the account has no
            // character, which registration makes unreachable and which is a broken invariant
            // rather than something to report to a player.
            Credited.NotCredited -> return MatchReceipt(
                verdict = verdict ?: error("account $accountId has no character"),
            )

            // The unique index refused it: this transcript has been credited before. The player's
            // real state is returned with the same verdict and a flag, because an offline queue
            // draining twice after a lost acknowledgement is careful behaviour, not cheating.
            Credited.Duplicate -> return MatchReceipt(
                verdict = requireNotNull(verdict) { "a duplicate was not replayed" },
                player = store.playerState(accountId),
                duplicate = true,
            )

            // The seed was never issued to this account, or has already been used. A **rejection**
            // and emphatically not a duplicate: the two were the same `null` until this change and
            // mean opposite things — one is a careful client, the other is a match played on a deal
            // the player auditioned. See `RejectionReason.UNKNOWN_SEED`.
            Credited.NoTicket -> return MatchReceipt(
                verdict = MatchVerdict.Rejected(
                    reason = RejectionReason.UNKNOWN_SEED,
                    detail = "seed ${transcript.seed} was not issued to this account, " +
                        "or has already been used",
                ),
                player = store.playerState(accountId),
            )

            is Credited.Paid -> paid.player
        }

        return MatchReceipt(
            // Both were set by the closure on the path that reaches here — it cannot have returned
            // a `Paid` without going all the way through. `requireNotNull` rather than `!!` so that
            // a future edit which breaks that says which of the two it broke.
            verdict = requireNotNull(verdict) { "credited a match without a verdict" },
            player = player,
            reward = requireNotNull(reward) { "credited a match without a reward" },
        )
    }

    /**
     * What makes two submissions "the same match".
     *
     * Over the fields that decide the game and nothing else: the seed, the opponent, the
     * format, the deck and the moves. Deliberately **not** the encoded JSON — a client that
     * reorders its fields or omits a default would produce different bytes for an identical match
     * and be paid twice for it.
     *
     * `ownedCards` is excluded for the same reason it is no longer trusted: it is the claimant's
     * statement about themselves, it plays no part in the replay any more, and including it would
     * let a resubmission with one card added past the duplicate check.
     */
    fun fingerprint(transcript: MatchTranscript): String {
        val canonical = buildString {
            append(transcript.version).append('|')
            append(transcript.seed).append('|')
            append(transcript.formatId).append('|')
            append(transcript.opponentIconId).append('|')
            transcript.deck.joinTo(this, ",")
            append('|')
            transcript.moves.joinTo(this, ",") { "${it.cardId}@${it.position}" }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)),
        )
    }
}
