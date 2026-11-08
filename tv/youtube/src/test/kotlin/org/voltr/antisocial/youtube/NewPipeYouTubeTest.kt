package org.voltr.antisocial.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
    @Test
    fun `channel ids come from uploader urls`() {
        assertEquals("UCabcdefghijklmnopqrstuv", channelIdOf("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        assertEquals("UCabcdefghijklmnopqrstuv", channelIdOf("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv/videos?x=1"))
        assertNull(channelIdOf("https://www.youtube.com/@someone"))
        assertNull(channelIdOf(null))
    }

    @Test
    fun `hdr is 10-bit av1 or vp9 profile 2`() {
        assertTrue(isHdr("av01.0.13M.10.0.110.09.16.09.0"))
        assertFalse(isHdr("av01.0.13M.08"))
        assertTrue(isHdr("vp09.02.51.10"))
        assertFalse(isHdr("avc1.640028"))
    }
}

/** Reaches YouTube, so it only runs when asked: ./gradlew :youtube:test -Plive */
class LiveTest {
    private val youtube by lazy { NewPipeYouTube(av1 = false) }

    private fun live() = assumeTrue("live tests are off", System.getProperty("live") == "true")

    @Test
    fun `search finds plain videos`() = runBlocking {
        live()
        val results = youtube.search("3blue1brown fourier")
        println(results.take(3))
        assertTrue(results.size in 5..20)
        assertTrue(results.all { it.id.length == 11 && it.channelId?.startsWith("UC") == true })
    }

    @Test
    fun `a channel lists its videos`() = runBlocking {
        live()
        val page = youtube.channel("UCYO_jab_esuFRV4b17AJtAw") // 3Blue1Brown
        println(page.name + " " + page.videos.take(2))
        assertTrue(page.videos.isNotEmpty())
        assertEquals("@3blue1brown", youtube.channel("@3blue1brown").id)
    }

    @Test
    fun `streams are playable in ranges`() = runBlocking {
        live()
        val streams = youtube.streams("spUNpyF58BY")
        println(streams.details)
        println(streams.video.map { "${it.codecs} ${it.height}p${it.fps}" } + streams.audio.codecs)
        println(streams.headers)
        assertTrue(streams.video.isNotEmpty() && streams.video.all { it.height <= 1080 })
        // Fetch the first video track's index range the way the player will.
        val track = streams.video.last()
        val request = Request.Builder().url(track.url)
            .header("Range", "bytes=${track.index.first}-${track.index.last}")
            .apply { streams.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            println("${response.code} ${response.body.bytes().size} bytes")
            assertEquals(206, response.code)
        }
    }
}
