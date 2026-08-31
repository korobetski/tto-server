package com.tripletriad.server

import com.tripletriad.data.PveMatches
import com.tripletriad.model.CardColor
import com.tripletriad.model.GameSave
import com.tripletriad.model.MatchAi
import com.tripletriad.protocol.MatchTranscript
import com.tripletriad.protocol.TranscriptMove
import kotlin.random.Random

/**
 * The client's half of the protocol, as a test fixture.
 *
 * Plays a real match with `:core` — the same engine the server replays with — and writes down what
 * the player did. Hand-writing nine moves instead would only ever prove that the endpoint parses
 * JSON; a transcript that came out of the engine is the only kind whose acceptance means anything.
 *
 * ### The move choice is load-bearing
 *
 * First card in hand, first empty cell. It must **not** consult [random], because that generator is
 * shared with the opponent's moves: drawing from it on the player's turn shifts every subsequent
 * value and the replay desynchronises — the invariant documented on `TranscriptVerifier`. A test
 * helper that picked moves at random would fail intermittently and look like a server bug.
 */
object Transcripts {

    /**
     * Plays [profile]'s first complete deck against a shipped opponent and transcribes it.
     *
     * @param profile the save the match is dealt from. For a submission this must be the profile
     *   the **server** holds, because that is what it will replay against.
     */
    fun honest(profile: GameSave, seed: Int, opponent: Int = 0): MatchTranscript {
        // The server's own format catalogue, which is what the verifier will resolve too — a
        // fixture that dealt under a different pool, or against an opponent from another format,
        // would produce transcripts the server rejects.
        val format = requireNotNull(Catalogs.formats.default) { "no format is authored" }
        // Which opponent, by index. Defaults to the first, and is a parameter for one test: the
        // same seed played against a *different* opponent is a different match, which is how
        // `SeedTicketTest` tells "this ticket is spent" apart from "this transcript is a repeat".
        val opponent = Catalogs.npcs.playing(format.id)[opponent]
        val deck = PveMatches.playerDeck(profile, Catalogs.cards.byId)

        val random = Random(seed)
        val match = PveMatches.assemble(profile, opponent, Catalogs.cards, format, random)
        val ai = MatchAi()
        var state = match.setup.state
        val moves = mutableListOf<TranscriptMove>()

        while (!state.isFinished) {
            state = if (state.currentPlayer == CardColor.BLUE) {
                val card = state.currentHand.first()
                val position = state.playablePositions().first()
                moves += TranscriptMove(card.id, position)
                state.play(card, position)
            } else {
                ai.play(state, random)
            }
        }

        return MatchTranscript(
            seed = seed,
            formatId = format.id,
            opponentIconId = opponent.iconId,
            deck = deck,
            ownedCards = profile.cards,
            moves = moves,
        )
    }
}
