package com.tripletriad.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every value a JSON payload carries, as text — primitives, and the keys they arrived under.
 *
 * ### What this replaces, and why it had to
 *
 * The two secrecy tests — `PveFlowTest.theOpponentsHandNeverReachesThePlayer` and
 * `PvpFlowTest.neitherPlayerIsSentTheOthersHand` — read the **encoded body** rather than the
 * decoded object, and that part was always right: "the field is null" and "the number is nowhere
 * in what we sent" are different claims, and only the second one survives somebody carrying a card
 * in a field the model does not know about.
 *
 * They made that claim by searching the body for `"$card"`, which searches for *text* and not for
 * a value. It failed on CI: card 317 was reported as having reached the player, and where it had
 * reached them was `"matchId":"ahzl317pqodikgy80jsnqe"` — twenty-two characters of base-36 noise
 * that happened to spell a card id. An identifier is 22 draws from a 36-character alphabet, so a
 * given three-digit run lands in one about once in two thousand; across the dozen cards the two
 * tests check, that is a red build every few hundred runs, for a reason with nothing to do with
 * the claim. It cost a release.
 *
 * Parsing generically keeps everything the raw search bought — every leaf is visited, named or
 * not, so an unknown field is still inspected — and compares whole values, so an id matches only
 * where it is actually one. Keys are flattened in beside them, because `{"317": …}` would be a
 * leak just the same.
 *
 * What it deliberately does **not** do is look inside strings. A card id spelled into the middle
 * of an identifier is not that card being disclosed, and a test that cannot tell the difference is
 * a test that will be ignored the third time it goes red.
 */
internal fun valuesIn(payload: String): Set<String> =
    Json.parseToJsonElement(payload).flatten().toSet()

private fun JsonElement.flatten(): Sequence<String> = when (this) {
    is JsonPrimitive -> sequenceOf(content)
    is JsonArray -> asSequence().flatMap { it.flatten() }
    is JsonObject -> entries.asSequence()
        .flatMap { (key, value) -> sequenceOf(key) + value.flatten() }
}
