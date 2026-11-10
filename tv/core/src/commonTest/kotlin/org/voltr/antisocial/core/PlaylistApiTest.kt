package org.voltr.antisocial.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistApiTest {
    @Test
    fun `playlists are created, filled, emptied and deleted`() = runTest {
        val seen = mutableListOf<String>()
        val replies = ArrayDeque(listOf("""{"id":7}""", "", "", ""))
        val api = Api("http://server", "tok", HttpClient(MockEngine { request ->
            val body = (request.body as? TextContent)?.text
            seen += listOfNotNull("${request.method.value} ${request.url.encodedPath}", body).joinToString(" ")
            val reply = replies.removeFirst()
            if (reply.isEmpty()) respond("", HttpStatusCode.NoContent)
            else respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false })

        assertEquals(7, api.createPlaylist("Later"))
        api.addToPlaylist(7, Video("abcdefghijk", "A title", "Chan", "UCx", 61, views = 5))
        api.removeFromPlaylist(7, "abcdefghijk")
        api.deletePlaylist(7)
        assertEquals(
            listOf(
                """POST /api/playlists {"name":"Later"}""",
                """POST /api/playlists/7/items {"id":"abcdefghijk","title":"A title","channel":"Chan","duration":61}""",
                "DELETE /api/playlists/7/items/abcdefghijk",
                "DELETE /api/playlists/7",
            ),
            seen,
        )
    }
}

class CountTest {
    @Test
    fun counts() {
        assertEquals("950", countText(950))
        assertEquals("1K", countText(1_000))
        assertEquals("1.2K", countText(1_250))
        assertEquals("12K", countText(12_500))
        assertEquals("3.4M", countText(3_400_000))
        assertEquals("2B", countText(2_000_000_000))
    }

    @Test
    fun `a video's line has what's known`() {
        assertEquals("1.2M views · 3 days ago", videoMeta(1_200_000, "3 days ago"))
        assertEquals("1 view · 2026-11-03 · 40 likes", videoMeta(1, "2026-11-03", 40))
        assertEquals("", videoMeta(null, null))
    }
}
