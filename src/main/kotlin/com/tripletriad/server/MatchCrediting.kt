package com.tripletriad.server

import com.tripletriad.data.CardCatalog
import com.tripletriad.data.MatchRewards
import com.tripletriad.data.NpcCatalog
import com.tripletriad.data.PveMatches
import com.tripletriad.model.MatchResult
import com.tripletriad.protocol.MatchReceipt
import com.tripletriad.protocol.MatchTranscript
import com.tripletriad.protocol.MatchVerdict
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
    // Four exits, all of them guard clauses: no character, rejected, duplicate, credited. Folding
    // them into one would mean nesting the happy path three deep to save a keyword.
    @Suppress("LongParameterList", "ReturnCount")
    fun credit(
        transcript: MatchTranscript,
        accountId: Long,
        store: AccountStore,
        cards: CardCatalog,
        npcs: NpcCatalog,
        now: Long,
    ): MatchReceipt {
        val save = store.saveFor(accountId)
            ?: error("account $accountId has no character")

        val verdict = TranscriptVerifier.verify(transcript, cards, npcs, owner = save)
        if (verdict !is MatchVerdict.Accepted) return MatchReceipt(verdict = verdict)

        val npc = npcs.byIcon(transcript.opponentIconId, transcript.collection)
            ?: error("verified a transcript against an opponent that does not exist")

        // Read off the server's own score, never off the client's claim — and derived here rather
        // than through `MatchResult.of`, which returns null for a sudden-death draw. A transcript
        // cannot express a rematch, so one that replays to a draw *is* a completed drawn match;
        // writing the mapping out says so, where a `!!` would only assume it.
        val result = when {
            verdict.blue > verdict.red -> MatchResult.WIN
            verdict.blue < verdict.red -> MatchResult.LOSE
            else -> MatchResult.DRAW
        }

        // The rules the match was played under are **re-derived from the seed**, not taken from the
        // transcript, which does not carry them. That is the same reason the score is recomputed:
        // `RULES_W` feeds achievements, so a client that could name its own rules could name the
        // ones that pay best.
        val rules = PveMatches.rulesFor(npc, save.mode, Random(transcript.seed))

        // A generator of its own, and deliberately not the replay's. The replay's is consumed to
        // exactly the position the match ended at, which depends on how the match went — so
        // continuing it would make the MGP top-up and the drop roll depend on the number of AI
        // turns. Seeding from the transcript keeps the payout reproducible for an audit while
        // leaving it independent of the replay.
        val payout = Random(transcript.seed.inv())
        val credited = MatchRewards.credit(
            save = save.startingMatch(againstNpc = true),
            npc = npc,
            result = result,
            rules = rules,
            at = now,
            random = payout,
        )

        val recorded = RecordedMatch(
            opponentIconId = transcript.opponentIconId,
            collection = transcript.collection.prefix,
            seed = transcript.seed,
            blue = verdict.blue,
            red = verdict.red,
            result = result,
            mgp = credited.reward.mgp,
            xp = credited.reward.xp,
        )

        val player = store.creditMatch(
            accountId = accountId,
            transcriptHash = fingerprint(transcript),
            match = recorded,
            save = credited.save.copy(lastSave = now, saveNumber = save.saveNumber + 1),
        )

        // Null means the unique index refused it: this transcript has been credited before. The
        // player's real state is returned with the same verdict and a flag, because an offline
        // queue draining twice after a lost acknowledgement is careful behaviour, not cheating.
        if (player == null) {
            return MatchReceipt(
                verdict = verdict,
                player = store.playerState(accountId),
                duplicate = true,
            )
        }

        return MatchReceipt(
            verdict = verdict,
            player = player,
            reward = RewardSummary(
                result = credited.reward.result,
                mgp = credited.reward.mgp,
                xp = credited.reward.xp,
                items = credited.reward.items,
                achievementIds = credited.reward.achievements.map { it.id },
            ),
        )
    }

    /**
     * What makes two submissions "the same match".
     *
     * Over the fields that decide the game and nothing else: the seed, the opponent, the
     * collection, the deck and the moves. Deliberately **not** the encoded JSON — a client that
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
            append(transcript.collection.prefix).append('|')
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
