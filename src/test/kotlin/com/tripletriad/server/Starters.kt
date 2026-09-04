package com.tripletriad.server

import com.tripletriad.protocol.CURRENT_VERSION
import com.tripletriad.protocol.ClaimStarterRequest
import com.tripletriad.protocol.PlayerState
import com.tripletriad.protocol.Session
import com.tripletriad.protocol.VERSION_HEADER
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

/**
 * Opening a starter box on a freshly registered account.
 *
 * Shared rather than copied into each fixture's own harness because it is not a detail of any one
 * test: **registration deals no cards**. `GameSave.new` used to seed five of block 1, which made a
 * registered account look playable and hid the fact that the box a player chose never reached the
 * server — see `StarterPack` and `ClaimStarterRequest`. It seeds nothing now, so every test that
 * needs a player who can field a deck has to do what the collection screen does, and this is that
 * one call.
 *
 * It returns a [Session] carrying the profile the claim wrote, because the one the registration
 * answered with is a profile from before the box was opened, and a fixture reading `player.save`
 * off it would be reading an empty collection.
 */
suspend fun ApplicationTestBuilder.openStarterBox(
    session: Session,
    starterId: String? = null,
): Session {
    val response = client.post("/me/starter") {
        contentType(ContentType.Application.Json)
        header(VERSION_HEADER, CURRENT_VERSION.toString())
        header(HttpHeaders.Authorization, "Bearer ${session.token}")
        setBody(
            starterJson.encodeToString(
                ClaimStarterRequest("starter-${session.player.save.username}", starterId),
            ),
        )
    }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    val player = starterJson.decodeFromString<PlayerState>(response.bodyAsText())
    return session.copy(player = player)
}

private val starterJson = Json { ignoreUnknownKeys = true }
