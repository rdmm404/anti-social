package org.voltr.antisocial.tv

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun statusBody(state: String, session: String = "null", nextAt: String = "null", remaining: Int = 3540) =
        """{"budget_seconds":3600,"used_seconds":60,"remaining_seconds":$remaining,"can_watch":true,
        "resets_at":1.5,"state":"$state","session":$session,"next_session_at":$nextAt,
        "session_choices":[1,2,3],"sample_seconds":30,"now":1.0,"something_new":1}"""

    @Test
    fun `reads an open session`() = runTest {
        server.enqueue(MockResponse().setBody(statusBody("open", """{"videos_left":2,"samples_left":3}""")))
        val status = api.status()
        assertEquals(SessionState.OPEN, status.state)
        assertEquals(Session(videosLeft = 2, samplesLeft = 3), status.session)
        assertEquals(listOf(1, 2, 3), status.sessionChoices)
        assertEquals(30, status.sampleSeconds)
        assertEquals("2 videos · 59m", clockText(status))
    }

    @Test
    fun `reads the states with no session`() = runTest {
        server.enqueue(MockResponse().setBody(statusBody("choose")))
        assertEquals(SessionState.CHOOSE, api.status().state)

        server.enqueue(MockResponse().setBody(statusBody("no_time", remaining = 0)))
        assertEquals(SessionState.NO_TIME, api.status().state)

        server.enqueue(MockResponse().setBody(statusBody("gap", """{"videos_left":0,"samples_left":2}""", "9.5")))
        val gap = api.status()
        assertEquals(SessionState.GAP, gap.state)
        assertEquals(9.5, gap.nextSessionAt!!, 0.0)
        assertEquals("59m left", clockText(gap))
    }

    @Test
    fun `starts a session with the chosen number of videos`() = runTest {
        server.enqueue(MockResponse().setBody(statusBody("open", """{"videos_left":2,"samples_left":3}""")))
        api.startSession(2)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/session", request.path)
        assertEquals("""{"videos":2}""", request.body.readUtf8())
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    }

    @Test
    fun `shows the server's reason when a session is refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"The next session isn't open yet."}"""))
        val error = runCatching { api.startSession(1) }.exceptionOrNull() as ApiError
        assertFalse(error.refused)
        assertEquals("The next session isn't open yet.", error.message)
    }

    @Test
    fun `both kinds of 402 are refusals`() = runTest {
        for (body in listOf(
            """{"error":"out_of_time","message":"You're out of time for today."}""",
            """{"error":"no_session","message":"This needs a session with a video left."}""",
        )) {
            server.enqueue(MockResponse().setResponseCode(402).setBody(body))
            val error = runCatching { api.search("cats") }.exceptionOrNull() as ApiError
            assertTrue(error.refused)
        }
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
                "watched_seconds":90}]}""",
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
    fun videos() {
        assertEquals("1 video", videosText(1))
        assertEquals("0 videos", videosText(0))
        assertEquals("3 videos", videosText(3))
    }

    @Test
    fun minutes() {
        assertEquals("2m", minutesText(90))
        assertEquals("1h", minutesText(3600))
        assertEquals("1h 1m", minutesText(3660))
    }
}
