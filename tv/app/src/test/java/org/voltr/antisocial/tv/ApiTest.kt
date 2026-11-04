package org.voltr.antisocial.tv

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiTest {
    private val server = MockWebServer()
    private lateinit var api: Api

    @Before
    fun start() {
        server.start()
        api = Api(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun `reads the status the server sends today`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"budget_seconds":3600,"used_seconds":60,"remaining_seconds":3540,"can_watch":true,
                "resets_at":1.5,"unlocks_left":2,"budget_spent":false,"unlock_minutes":30,
                "unlock_wait_seconds":60,"unlock_starts_at":null,"now":1.0,"something_new":1}""",
            ),
        )
        val status = api.status()
        assertEquals(3540, status.remainingSeconds)
        assertTrue(status.canWatch)
        assertNull(status.unlockWaitLeft)
    }

    @Test
    fun `counts down a waiting unlock`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"budget_seconds":0,"used_seconds":0,"remaining_seconds":0,"can_watch":false,
                "resets_at":1.5,"unlocks_left":1,"budget_spent":true,"unlock_minutes":30,
                "unlock_wait_seconds":60,"unlock_starts_at":130.2,"now":100.0}""",
            ),
        )
        assertEquals(31L, api.status().unlockWaitLeft)
    }

    @Test
    fun `reports out of time as its own error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(402)
                .setBody("""{"error":"out_of_time","message":"You're out of time for today."}"""),
        )
        val error = runCatching { api.search("cats") }.exceptionOrNull() as ApiError
        assertTrue(error.outOfTime)
        assertEquals("You're out of time for today.", error.message)
    }

    @Test
    fun `shows the server's refusal when an unlock is refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"No unlocks left today."}"""))
        val error = runCatching { api.unlock() }.exceptionOrNull() as ApiError
        assertFalse(error.outOfTime)
        assertEquals("No unlocks left today.", error.message)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `an unreachable server is a readable error`() = runTest {
        server.shutdown()
        val error = runCatching { api.status() }.exceptionOrNull() as ApiError
        assertEquals(0, error.code)
        assertEquals("Can't reach the anti-social server.", error.message)
    }

    @Test
    fun `encodes search terms and reads optional fields`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"results":[{"id":"abcdefghijk","title":"T","channel":null,"channel_id":null,"duration":null}]}"""),
        )
        val results = api.search("a b&c")
        assertEquals("abcdefghijk", results.single().id)
        assertEquals(listOf("a b&c"), server.takeRequest().requestUrl!!.queryParameterValues("q"))
    }

    @Test
    fun `history becomes videos with a note`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"videos":[{"day":"2026-11-03","video_id":"abcdefghijk","title":"T","channel":"C",
                "charged_seconds":60.0,"unlocked_seconds":30.0}]}""",
            ),
        )
        assertEquals("2026-11-03 · 2m", api.history().single().note)
    }
}

class FormatTest {
    @Test
    fun durations() {
        assertEquals("0:05", durationText(5))
        assertEquals("1:05", durationText(65))
        assertEquals("1:01:40", durationText(3700))
    }

    @Test
    fun minutes() {
        assertEquals("2m", minutesText(90))
        assertEquals("1h", minutesText(3600))
        assertEquals("1h 1m", minutesText(3660))
    }
}
