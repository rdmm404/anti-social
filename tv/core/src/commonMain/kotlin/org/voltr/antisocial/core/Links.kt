package org.voltr.antisocial.core

import io.ktor.http.Url

/** Where a YouTube link leads in the app. Ported from app_route in src/anti_social/youtube.py. */
sealed interface Link {
    data class Video(val id: String, val startSeconds: Int? = null) : Link
    data class Playlist(val id: String) : Link
    data class Channel(val id: String) : Link
    data class Search(val query: String) : Link
}

private val YOUTUBE_HOSTS = setOf(
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtu.be",
    "www.youtube-nocookie.com",
)
private val ID_PATHS = setOf("shorts", "live", "embed", "v")
private val VIDEO_ID = Regex("^[\\w-]{11}$")
private val CHANNEL_ID = Regex("^(UC[\\w-]{20,}|@[\\w.-]+)$")
private val PLAYLIST_ID = Regex("^[\\w-]{10,}$")
private val START = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?$")
private val ANY_LINK = Regex("""https?://\S+""")

/**
 * Where a YouTube URL lands, or null when it isn't one or leads nowhere the app goes. A Short
 * is a video like any other. A link pasted without its scheme counts too.
 */
fun parseYouTubeLink(text: String): Link? {
    val trimmed = text.trim()
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    val url = runCatching { Url(withScheme) }.getOrNull() ?: return null
    if (url.host.lowercase() !in YOUTUBE_HOSTS) return null
    val parts = url.rawSegments.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val first = parts.first()
    val query = url.parameters

    val videoId = when {
        first == "watch" -> query["v"]
        first in ID_PATHS && parts.size > 1 -> parts[1]
        parts.size == 1 -> first // youtu.be/<id>
        else -> null
    }
    if (videoId != null && VIDEO_ID.matches(videoId)) {
        return Link.Video(videoId, startSeconds(query["t"] ?: query["start"] ?: ""))
    }
    if (first == "results") query["search_query"]?.takeIf { it.isNotBlank() }?.let { return Link.Search(it) }
    if (first == "playlist") query["list"]?.takeIf { PLAYLIST_ID.matches(it) }?.let { return Link.Playlist(it) }
    val channel = if (first == "channel") parts.last() else first
    if ((first == "channel" || first.startsWith("@")) && CHANNEL_ID.matches(channel)) return Link.Channel(channel)
    return null
}

/**
 * Where something shared with the app goes. Apps put the link anywhere in the text, often
 * after a title. Text without a YouTube link is searched for.
 */
fun sharedLink(text: String): Link? {
    ANY_LINK.find(text)?.let { match -> parseYouTubeLink(match.value)?.let { return it } }
    val words = text.trim().take(200)
    return if (words.isEmpty()) null else Link.Search(words)
}

/** YouTube's start times: 90, 90s or 1h2m3s. Zero means from the start. */
internal fun startSeconds(value: String): Int? {
    if (value.isEmpty()) return null
    val match = START.matchEntire(value) ?: return null
    val (h, m, s) = match.destructured
    val total = (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    return total.takeIf { it > 0 }
}
