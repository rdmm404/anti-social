package org.voltr.antisocial.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test
    fun `every kind of video link lands on the video`() {
        val links = listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&feature=share",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id&list=RDAMVM",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=abc123",
            "https://www.youtube.com/shorts/$id",
            "https://youtube.com/shorts/$id?feature=share",
            "https://www.youtube.com/live/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube-nocookie.com/embed/$id",
            "https://www.youtube.com/v/$id",
            "http://www.youtube.com/watch?v=$id",
            "WWW.YOUTUBE.COM/watch?v=$id",
            "youtu.be/$id",
        )
        for (link in links) assertEquals(Link.Video(id), parseYouTubeLink(link), link)
    }

    @Test
    fun `t and start set where it begins`() {
        assertEquals(Link.Video(id, 90), parseYouTubeLink("https://youtu.be/$id?t=90"))
        assertEquals(Link.Video(id, 90), parseYouTubeLink("https://youtu.be/$id?t=90s"))
        assertEquals(Link.Video(id, 3723), parseYouTubeLink("https://www.youtube.com/watch?v=$id&t=1h2m3s"))
        assertEquals(Link.Video(id, 125), parseYouTubeLink("https://www.youtube.com/watch?v=$id&t=2m5s"))
        assertEquals(Link.Video(id, 30), parseYouTubeLink("https://www.youtube.com/embed/$id?start=30"))
        assertEquals(Link.Video(id), parseYouTubeLink("https://youtu.be/$id?t=0"))
        assertEquals(Link.Video(id), parseYouTubeLink("https://youtu.be/$id?t=soon"))
    }

    @Test
    fun `playlists, channels and searches`() {
        assertEquals(Link.Playlist("PLabcdefghij"), parseYouTubeLink("https://www.youtube.com/playlist?list=PLabcdefghij"))
        assertEquals(Link.Channel("@veritasium"), parseYouTubeLink("https://www.youtube.com/@veritasium"))
        assertEquals(Link.Channel("@veritasium"), parseYouTubeLink("https://www.youtube.com/@veritasium/videos"))
        val uc = "UCHnyfMqiRRG1u-2MsSQLbXA"
        assertEquals(Link.Channel(uc), parseYouTubeLink("https://www.youtube.com/channel/$uc"))
        assertEquals(Link.Search("cats and dogs"), parseYouTubeLink("https://www.youtube.com/results?search_query=cats+and+dogs"))
    }

    @Test
    fun `other links lead nowhere`() {
        assertNull(parseYouTubeLink("https://example.com/watch?v=$id"))
        assertNull(parseYouTubeLink("https://www.youtube.com/"))
        assertNull(parseYouTubeLink("https://www.youtube.com/watch?v=short"))
        assertNull(parseYouTubeLink("https://www.youtube.com/feed/subscriptions"))
        assertNull(parseYouTubeLink("https://www.youtube.com/playlist?list=x"))
        assertNull(parseYouTubeLink("cats"))
        assertNull(parseYouTubeLink(""))
    }

    @Test
    fun `shared text finds its link, or is searched for`() {
        assertEquals(Link.Video(id), sharedLink("Look at this https://youtube.com/shorts/$id?si=x"))
        assertEquals(Link.Search("some words"), sharedLink("  some words "))
        assertEquals(Link.Search("https://example.com/x"), sharedLink("https://example.com/x"))
        assertNull(sharedLink("   "))
    }
}
