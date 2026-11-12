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
    fun `dates read as youtube words them`() {
        val now = java.time.Instant.parse("2026-11-10T12:00:00Z")
        fun ago(days: Long, hours: Long = 0) = agoText(now.minus(java.time.Duration.ofHours(days * 24 + hours)), now)
        assertEquals("6 hours ago", ago(0, 6))
        assertEquals("1 day ago", ago(1))
        assertEquals("3 weeks ago", ago(21))
        assertEquals("1 month ago", ago(30))
        assertEquals("11 months ago", ago(334))
        assertEquals("1 year ago", ago(365))
        assertEquals("4 years ago", ago(1461))
    }

    @Test
    fun `zulu dates newpipe leaves empty are read here`() {
        assertEquals("2 days ago", zuluAgoText("ezinsukwini ezingu-2 ezedlule."))
        assertEquals("1 day ago", zuluAgoText("osukwini olungu-1 olwedlule"))
        assertEquals("4 hours ago", zuluAgoText("Kusakazwe emahoreni angu-4 edlule"))
        assertEquals("2 weeks ago", zuluAgoText("amaviki angu-2 edlule"))
        assertNull(zuluAgoText("izolo"))
        assertNull(zuluAgoText("3 into engaziwa"))
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
    fun `lists keep the titles their uploaders wrote`() = runBlocking {
        live()
        // Charly Sinewan writes in Spanish and has English translations of his titles.
        val videos = youtube.channel("UCqZQXbQTF6VI_i5QWFaVxSQ").videos
        videos.take(8).forEach { println("${it.title} | ${it.views} | ${it.uploaded} | ${it.duration}") }
        assertTrue(videos.size > 5)
        assertTrue(videos.all { it.uploaded?.endsWith(" ago") == true && it.duration != null })
        // A members-only video has no view count.
        assertTrue(videos.count { it.views != null } > videos.size / 2)
        val found = youtube.search("charly sinewan venezuela")
        found.take(8).forEach { println("${it.title} | ${it.channel} | ${it.views} | ${it.uploaded}") }
        assertTrue(found.count { it.views != null && it.uploaded != null } > found.size / 2)
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
