package org.voltr.antisocial.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OPEN = """{"budget_seconds":600,"used_seconds":10,"remaining_seconds":590,"can_watch":true,
    "resets_at":2.0,"state":"open","session":{"videos_left":1,"samples_left":3},"session_choices":[1,2,3],
    "sample_seconds":30,"checkin_seconds":15,"now":1000.0}"""
private val CHOOSE = OPEN.replace("\"open\"", "\"choose\"")

private fun answer(allowed: Boolean, status: String = OPEN, reason: String? = null) =
    """{"allowed":$allowed,"reason":${reason?.let { "\"$it\"" }},"expires_at":1060.0,"status":$status}"""

private class FakePlayer : PlayerControl {
    override var positionSeconds = 0.0
    override var isPlaying = false
    var plays = 0
    override fun play() {
        isPlaying = true
        plays++
    }

    override fun pause() {
        isPlaying = false
    }
}

class ViewingTest {
    private var clock = 0L
    private val replies = ArrayDeque<MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    private val sent = mutableListOf<HttpRequestData>()
    private val player = FakePlayer()

    private fun json(body: String): MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

    private fun TestScope.viewing(): Viewing {
        val api = Api("http://server", "tok", HttpClient(MockEngine { sent += it; replies.removeFirst()(this, it) }) { expectSuccess = false })
        return Viewing(Watch(api, VideoDetails("abcdefghijk", "T", "C", null, 300)) { clock }, player, Budget(api, this))
    }

    @Test
    fun `nothing plays before the first check-in allows it`() = runTest {
        val viewing = viewing()
        assertIs<Verdict.Unknown>(viewing.held.value)
        // play() asks the server first, since nothing allowed playing yet.
        replies += json(answer(false, CHOOSE, "no_session"))
        viewing.play()
        assertEquals(0, player.plays)
        assertEquals(1, sent.size)
        assertIs<Verdict.Refused>(viewing.held.value)
    }

    @Test
    fun `an allowed check-in starts playback, and a refusal stops it`() = runTest {
        val viewing = viewing()
        replies += json(answer(true))
        viewing.checkIn()
        assertNull(viewing.held.value)
        assertTrue(player.isPlaying)

        player.positionSeconds = 1.0
        viewing.tick()
        clock = 15_000
        player.positionSeconds = 2.0
        replies += json(answer(false, CHOOSE, "no_session"))
        viewing.tick()
        assertIs<Verdict.Refused>(viewing.held.value)
        assertFalse(player.isPlaying)
    }

    @Test
    fun `a paused video stays paused when the next check-in allows it`() = runTest {
        val viewing = viewing()
        replies += json(answer(true))
        viewing.checkIn()
        viewing.pause()
        clock = 15_000
        replies += json(answer(true))
        viewing.checkIn()
        assertFalse(player.isPlaying)
    }

    @Test
    fun `an expired allowance pauses playback even without an answer`() = runTest {
        val viewing = viewing()
        replies += json(answer(true))
        viewing.checkIn()
        clock = 61_000
        replies += { throw java.io.IOException("offline") }
        viewing.tick()
        assertFalse(player.isPlaying)
        assertIs<Verdict.Unreachable>(viewing.held.value)
    }

    @Test
    fun `opening a session after a refusal carries on`() = runTest {
        val viewing = viewing()
        replies += json(answer(false, CHOOSE, "no_session"))
        viewing.checkIn()
        assertFalse(player.isPlaying)
        viewing.statusChanged(SessionState.CHOOSE)
        assertEquals(1, sent.size)
        replies += json(answer(true))
        viewing.statusChanged(SessionState.OPEN)
        assertTrue(player.isPlaying)
    }

    @Test
    fun `closing reports the last seconds`() = runTest {
        val viewing = viewing()
        replies += json(answer(true))
        viewing.checkIn()
        player.positionSeconds = 1.0
        viewing.tick()
        player.positionSeconds = 2.5
        replies += json(answer(true))
        viewing.close()
        assertEquals(2, sent.size)
        assertFalse(player.isPlaying)
    }
}
