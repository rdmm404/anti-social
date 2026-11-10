package org.voltr.antisocial.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TitleTest {
    @Test
    fun `titles lose the shouting`() {
        val cases = mapOf(
            "I BUILT A HOUSE IN 24 HOURS!!! 😱🔥" to "I built a house in 24 hours",
            "Why NASA is great (MUST WATCH)" to "Why NASA is great",
            "What happened??? #shorts #viral" to "What happened?",
            "how the CPU works" to "How the CPU works",
            "iPhone Duo Review" to "iPhone Duo Review",
            "🔥🔥🔥" to "🔥🔥🔥",
        )
        for ((raw, expected) in cases) assertEquals(expected, cleanTitle(raw), raw)
    }

    @Test
    fun `frames are generated ones`() {
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hq2.jpg", frameUrl("abcdefghijk"))
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

    @Test
    fun videos() {
        assertEquals("1 video", videosText(1))
        assertEquals("0 videos", videosText(0))
    }
}

private fun track(codecs: String, height: Int, fps: Int = 30, bitrate: Int = 1000, hdr: Boolean = false) =
    Track("https://x/$codecs/$height/$fps?a=1&b=2", "video/mp4", codecs, bitrate, 0L..99L, 100L..199L, height * 16 / 9, height, fps, hdr = hdr)

class TrackTest {
    @Test
    fun `h264 up to 1080p, then SDR AV1 up to 2160p, best frame rate per height`() {
        val chosen = chooseVideoTracks(
            listOf(
                track("avc1.4d401f", 720, 30),
                track("avc1.4d4020", 720, 60),
                track("vp09.00.40.08", 1080, 60),
                track("avc1.64002a", 1080, 60),
                track("av01.0.09M.08", 1080, 60),
                track("av01.0.13M.08", 2160, 60),
                track("av01.0.13M.10", 2160, 60, bitrate = 9500, hdr = true),
                track("vp09.00.51.08", 2160, 60),
                track("av01.0.16M.08", 4320, 60),
            ),
        )
        assertEquals(listOf("avc1.4d4020", "avc1.64002a", "av01.0.13M.08"), chosen.map { it.codecs })
        assertEquals(listOf("avc1.64002a"), chooseVideoTracks(chosen, maxHeight = 1080).drop(1).map { it.codecs })
    }

    @Test
    fun `the manifest lists every track with its ranges, escaped`() {
        val audio = Track("https://x/a?x=1&y=2", "audio/mp4", "mp4a.40.2", 130_000, 0L..599L, 600L..999L, sampleRate = 44_100)
        val streams = Streams(
            VideoDetails("abcdefghijk", "T", "C", null, 125),
            listOf(track("avc1.4d401f", 720), track("av01.0.13M.08", 2160)),
            audio,
        )
        val mpd = dashManifest(streams)
        assertTrue("mediaPresentationDuration=\"PT125S\"" in mpd)
        assertEquals(3, Regex("<AdaptationSet").findAll(mpd).count()) // H.264, AV1, audio
        assertTrue("<BaseURL>https://x/a?x=1&amp;y=2</BaseURL>" in mpd)
        assertTrue("""indexRange="600-999"""" in mpd && """<Initialization range="0-599"/>""" in mpd)
        assertTrue("""width="1280" height="720" frameRate="30"""" in mpd)
        assertFalse("&y" in mpd.replace("&amp;", ""))
    }
}

class StretchesTest {
    @Test
    fun `steady playback becomes one stretch`() {
        val s = WatchedStretches()
        for (i in 0..20) s.observe(i * 0.5, playing = true)
        assertEquals(listOf(listOf(0.0, 10.0)), s.take())
        assertTrue(s.isEmpty)
    }

    @Test
    fun `seeks and pauses aren't watching`() {
        val s = WatchedStretches()
        s.observe(0.0, true)
        s.observe(1.0, true)
        s.observe(60.0, true) // A seek forward.
        s.observe(61.0, true)
        s.observe(70.0, false) // Paused, then seeked while paused.
        s.observe(71.0, true)
        s.observe(30.0, true) // A seek back.
        assertEquals(listOf(listOf(0.0, 1.0), listOf(60.0, 61.0), listOf(70.0, 71.0)), s.take())
    }

    @Test
    fun `stretches merge and come back after a failure`() {
        val s = WatchedStretches()
        s.add(10.0, 20.0)
        s.add(0.0, 5.0)
        s.add(4.0, 11.0)
        val taken = s.take()
        assertEquals(listOf(listOf(0.0, 20.0)), taken)
        s.add(30.0, 31.0)
        s.putBack(taken)
        assertEquals(listOf(listOf(0.0, 20.0), listOf(30.0, 31.0)), s.take())
    }
}

private const val STATUS = """{"budget_seconds":600,"used_seconds":10,"remaining_seconds":590,"can_watch":true,
    "resets_at":2.0,"state":"open","session":{"videos_left":1,"samples_left":3},"next_session_at":null,
    "session_choices":[1,2,3],"sample_seconds":30,"checkin_seconds":15,"now":1000.0,"something_new":1}"""

private fun reply(allowed: Boolean, reason: String? = null, expiresAt: Double = 1060.0) =
    """{"allowed":$allowed,"reason":${reason?.let { "\"$it\"" }},"expires_at":$expiresAt,"status":$STATUS}"""

class WatchTest {
    private val details = VideoDetails("abcdefghijk", "Title", "Chan", null, 300)
    private var clock = 0L
    private val sent = mutableListOf<HttpRequestData>()
    private val replies = ArrayDeque<MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()

    private fun api(): Api {
        val engine = MockEngine { request ->
            sent += request
            replies.removeFirst()(this, request)
        }
        return Api("http://server", "tok", HttpClient(engine) { expectSuccess = false })
    }

    private fun json(body: String): MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

    private fun body(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test
    fun `an allowed check-in covers playing until its expiry`() = runTest {
        val watch = Watch(api(), details) { clock }
        assertFalse(watch.mayPlay())
        assertTrue(watch.due())
        replies += json(reply(true))
        assertIs<Verdict.Allowed>(watch.checkIn())
        assertEquals("Bearer tok", sent.single().headers[HttpHeaders.Authorization])
        assertEquals("/api/checkins", sent.single().url.encodedPath)
        assertEquals("abcdefghijk", body(sent.single())["video_id"]!!.jsonPrimitive.content)
        assertTrue(watch.mayPlay())
        assertFalse(watch.due())
        clock = 15_000
        assertTrue(watch.due())
        clock = 59_999
        assertTrue(watch.mayPlay())
        clock = 60_000
        assertFalse(watch.mayPlay())
    }

    @Test
    fun `stretches go with the next check-in, and come back when it fails`() = runTest {
        val watch = Watch(api(), details) { clock }
        watch.stretches.add(0.0, 10.0)
        replies += { respondError(HttpStatusCode.BadGateway) }
        replies += json(reply(true))
        assertIs<Verdict.Unreachable>(watch.checkIn())
        watch.stretches.add(10.0, 12.0)
        watch.checkIn()
        assertEquals(listOf(0.0, 12.0), body(sent.last())["watched"]!!.jsonArray.single().jsonArray.map { it.jsonPrimitive.content.toDouble() })
    }

    @Test
    fun `losing the server keeps the last expiry`() = runTest {
        val watch = Watch(api(), details) { clock }
        replies += json(reply(true))
        watch.checkIn()
        clock = 15_000
        replies += { throw java.io.IOException("offline") }
        assertIs<Verdict.Unreachable>(watch.checkIn())
        assertTrue(watch.mayPlay())
        clock = 60_000
        assertFalse(watch.mayPlay())
    }

    @Test
    fun `a refusal stops playback at once and says why`() = runTest {
        val watch = Watch(api(), details) { clock }
        replies += json(reply(true))
        watch.checkIn()
        replies += json(reply(false, "out_of_time", expiresAt = 1000.0))
        val verdict = assertIs<Verdict.Refused>(watch.checkIn())
        assertTrue(verdict.outOfTime)
        assertFalse(watch.mayPlay())
    }

    @Test
    fun `a revoked token signs the device out`() = runTest {
        val watch = Watch(api(), details) { clock }
        replies += { respond("""{"error":{"code":"unauthorized","message":"no"}}""", HttpStatusCode.Unauthorized) }
        assertIs<Verdict.SignedOut>(watch.checkIn())
        assertFalse(watch.mayPlay())
    }
}

class ApiTest {
    @Test
    fun `reads status, ignoring fields it doesn't know`() = runTest {
        val api = Api("http://server/", "tok", HttpClient(MockEngine { request ->
            assertEquals("http://server/api/status", request.url.toString())
            respond(STATUS, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val status = api.status()
        assertEquals(SessionState.OPEN, status.state)
        assertEquals(Session(1, 3), status.session)
    }

    @Test
    fun `refusals carry the server's message`() = runTest {
        val api = Api("http://server", "tok", HttpClient(MockEngine {
            respond("""{"error":{"code":"refused","message":"The next session isn't open yet."}}""", HttpStatusCode.Conflict)
        }))
        val error = runCatching { api.startSession(1) }.exceptionOrNull()
        assertIs<ApiError>(error)
        assertEquals(409, error.code)
        assertEquals("The next session isn't open yet.", error.message)
    }

    @Test
    fun `an unreachable server is a readable error`() = runTest {
        val api = Api("http://server", "tok", HttpClient(MockEngine { throw java.io.IOException("down") }))
        val error = assertIs<ApiError>(runCatching { api.status() }.exceptionOrNull())
        assertTrue(error.unreachable)
        assertEquals("Can't reach the anti-social server.", error.message)
    }

    @Test
    fun `channel ids are path segments, and history gets a note`() = runTest {
        val paths = mutableListOf<String>()
        val api = Api("http://server", "tok", HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.startsWith("/api/history")) {
                respond("""{"videos":[{"day":"2026-11-03","video_id":"abcdefghijk","title":"T","channel":"C","watched_seconds":90}]}""")
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }))
        api.unsubscribe("@some one")
        assertEquals("2026-11-03 · 2m", api.history().single().note)
        assertEquals(listOf("/api/subscriptions/@some%20one", "/api/history/videos"), paths)
    }
}

class OneAtATimeTest {
    @Test
    fun `a check-in waiting for its answer isn't doubled`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var requests = 0
        val api = Api("http://server", "tok", HttpClient(MockEngine {
            requests++
            gate.await()
            respond(
                """{"allowed":true,"expires_at":1060.0,"status":$STATUS}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }))
        val watch = Watch(api, VideoDetails("abcdefghijk", "T", "C", null, 1)) { 0L }
        val first = backgroundScope.async { watch.checkIn() }
        kotlinx.coroutines.yield()
        kotlinx.coroutines.yield()
        assertFalse(watch.due())
        assertIs<Verdict.Unknown>(watch.checkIn())
        gate.complete(Unit)
        assertIs<Verdict.Allowed>(first.await())
        assertEquals(1, requests)
    }
}
