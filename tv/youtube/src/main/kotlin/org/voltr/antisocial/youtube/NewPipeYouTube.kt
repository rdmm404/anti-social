package org.voltr.antisocial.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.voltr.antisocial.core.ChannelPage
import org.voltr.antisocial.core.Comment
import org.voltr.antisocial.core.Streams
import org.voltr.antisocial.core.Track
import org.voltr.antisocial.core.Video
import org.voltr.antisocial.core.VideoDetails
import org.voltr.antisocial.core.YouTube
import org.voltr.antisocial.core.YouTubeError
import org.voltr.antisocial.core.YouTubePlaylist
import org.voltr.antisocial.core.chooseVideoTracks
import org.voltr.antisocial.core.cleanTitle
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

private val VIDEO_ID = Regex("^[\\w-]{11}$")
private val CHANNEL_ID = Regex("^(UC[\\w-]{20,}|@[\\w.-]+)$")
private val PLAYLIST_ID = Regex("^[\\w-]{10,}$")
private const val SEARCH_RESULTS = 20
private const val COMMENT_LIMIT = 50

/**
 * The language lists are asked for. YouTube swaps a title for its translation into the language
 * of the request, and has no way to ask for the original, so lists are asked for in Zulu, which
 * next to nobody translates into (PipePipe's trick). The video page needs none of this: its
 * title is always the original. Dates come back in Zulu too, so they're written out again here.
 */
private val ORIGINAL_TITLES = Localization("zu")

/**
 * YouTube through NewPipeExtractor, which tracks YouTube's changes the way yt-dlp does.
 * Everything that knows how YouTube works lives in this module, so it's fixed in one place.
 */
class NewPipeYouTube(
    /** Whether the device decodes AV1 in hardware. Without it, nothing above 1080p is offered. */
    private val av1: Boolean,
) : YouTube {
    init {
        initialize()
    }

    override suspend fun search(query: String): List<Video> = extract {
        val handler = ServiceList.YouTube.searchQHFactory.fromQuery(
            query.take(200),
            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
            "",
        )
        val extractor = ServiceList.YouTube.getSearchExtractor(handler).apply {
            forceLocalization(ORIGINAL_TITLES)
            fetchPage()
        }
        SearchInfo.getInfo(extractor).relatedItems.toVideos().take(SEARCH_RESULTS)
    }

    override suspend fun channel(id: String): ChannelPage = extract {
        if (!CHANNEL_ID.matches(id)) throw YouTubeError("Not a channel id.")
        val url = if (id.startsWith("@")) "https://www.youtube.com/$id" else "https://www.youtube.com/channel/$id"
        // The channel's first answer already holds its videos tab, so it's the one to ask in Zulu.
        val info = ChannelInfo.getInfo(ServiceList.YouTube.getChannelExtractor(url).apply {
            forceLocalization(ORIGINAL_TITLES)
            fetchPage()
        })
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
            ?: return@extract ChannelPage(id, info.name, emptyList())
        val extractor = ServiceList.YouTube.getChannelTabExtractor(videosTab).apply {
            forceLocalization(ORIGINAL_TITLES)
            fetchPage()
        }
        val tab = ChannelTabInfo.getInfo(extractor)
        ChannelPage(id, info.name, tab.relatedItems.toVideos(fallbackChannel = info.name, fallbackChannelId = channelIdOf(info.url)))
    }

    override suspend fun streams(videoId: String): Streams = extract {
        if (!VIDEO_ID.matches(videoId)) throw YouTubeError("Not a video id.")
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        if (info.streamType != StreamType.VIDEO_STREAM) throw YouTubeError("Live streams aren't supported.")

        val video = chooseVideoTracks(
            info.videoOnlyStreams.mapNotNull { it.toTrack() },
            maxHeight = if (av1) 2160 else 1080,
        )
        val audio = chooseAudio(info.audioStreams)
        if (video.isEmpty() || audio == null) throw YouTubeError("No playable format for this video.")

        Streams(
            details = VideoDetails(
                id = videoId,
                title = cleanTitle(info.name),
                channel = info.uploaderName.orEmpty(),
                channelId = channelIdOf(info.uploaderUrl),
                durationSeconds = info.duration,
                description = plainText(info.description),
                views = info.viewCount.takeIf { it >= 0 },
                likes = info.likeCount.takeIf { it >= 0 },
                uploaded = info.uploadDate?.let(::isoDate),
            ),
            video = video,
            audio = audio,
            headers = headersFor(audio.url),
        )
    }

    override suspend fun comments(videoId: String): List<Comment> = extract {
        if (!VIDEO_ID.matches(videoId)) throw YouTubeError("Not a video id.")
        val info = CommentsInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        if (info.isCommentsDisabled) return@extract emptyList()
        info.relatedItems.filterIsInstance<CommentsInfoItem>().take(COMMENT_LIMIT).map {
            Comment(
                author = it.uploaderName.orEmpty(),
                text = plainText(it.commentText),
                `when` = it.textualUploadDate.orEmpty(),
                likes = it.likeCount.toLong().coerceAtLeast(0),
                pinned = it.isPinned,
            )
        }
    }

    override suspend fun playlist(id: String, limit: Int): YouTubePlaylist = extract {
        if (!PLAYLIST_ID.matches(id)) throw YouTubeError("Not a playlist id.")
        val url = "https://www.youtube.com/playlist?list=$id"
        val extractor = ServiceList.YouTube.getPlaylistExtractor(url).apply {
            forceLocalization(ORIGINAL_TITLES)
            fetchPage()
        }
        val info = PlaylistInfo.getInfo(extractor)
        val items = info.relatedItems.toMutableList()
        var page = info.nextPage
        while (page != null && items.size < limit) {
            val more = extractor.getPage(page)
            items += more.items
            page = if (more.hasNextPage()) more.nextPage else null
        }
        // A playlist keeps its Shorts: they play as regular videos.
        val videos = items.toVideos(allowShorts = true).distinctBy { it.id }.take(limit)
        YouTubePlaylist(cleanTitle(info.name), videos)
    }

    private suspend fun <T> extract(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: YouTubeError) {
            throw e
        } catch (e: AgeRestrictedContentException) {
            throw YouTubeError("YouTube only shows this video to signed-in adults.", e)
        } catch (e: GeographicRestrictionException) {
            throw YouTubeError("This video isn't available in this country.", e)
        } catch (e: PrivateContentException) {
            throw YouTubeError("This video is private.", e)
        } catch (e: PaidContentException) {
            throw YouTubeError("This video is for paying members only.", e)
        } catch (e: ContentNotAvailableException) {
            throw YouTubeError("YouTube says this isn't available.", e)
        } catch (e: ReCaptchaException) {
            throw YouTubeError("YouTube is asking this connection to prove it's human. Try again later.", e)
        } catch (e: IOException) {
            throw YouTubeError("Can't reach YouTube.", e)
        } catch (e: Exception) {
            throw YouTubeError("YouTube wouldn't give us this (${e.javaClass.simpleName}).", e)
        } catch (e: LinkageError) {
            // A Java API this Android lacks: a bug to fix here, but no reason to crash the app.
            throw YouTubeError("This device is missing something the YouTube code needs (${e.javaClass.simpleName}).", e)
        }
    }

    companion object {
        @Volatile private var initialized = false

        private fun initialize() {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                val locale = Locale.getDefault()
                NewPipe.init(
                    OkHttpDownloader(),
                    Localization(locale.language.ifEmpty { "en" }, locale.country.ifEmpty { null }),
                    ContentCountry(locale.country.ifEmpty { "US" }),
                )
                initialized = true
            }
        }
    }
}

/** "https://www.youtube.com/channel/UC…" -> "UC…" */
internal fun channelIdOf(url: String?): String? =
    url?.substringAfter("/channel/", "")?.substringBefore('/')?.substringBefore('?')?.takeIf { it.isNotEmpty() }

internal fun List<InfoItem>.toVideos(
    fallbackChannel: String? = null,
    fallbackChannelId: String? = null,
    allowShorts: Boolean = false,
): List<Video> =
    filterIsInstance<StreamInfoItem>()
        .filter { it.streamType == StreamType.VIDEO_STREAM || (allowShorts && it.streamType == StreamType.NONE) }
        .filter { allowShorts || (!it.isShortFormContent && !it.url.contains("/shorts/")) }
        .mapNotNull { item ->
            val id = videoIdOf(item.url) ?: return@mapNotNull null
            Video(
                id = id,
                title = cleanTitle(item.name),
                channel = item.uploaderName ?: fallbackChannel,
                channelId = channelIdOf(item.uploaderUrl) ?: fallbackChannelId,
                duration = item.duration.takeIf { it > 0 }?.toInt(),
                views = item.viewCount.takeIf { it >= 0 },
                uploaded = item.uploadDate?.let { runCatching { agoText(it.instant) }.getOrNull() },
            )
        }

/** The id in a watch?v= or /shorts/ URL. */
internal fun videoIdOf(url: String): String? {
    val id = if (url.contains("/shorts/")) url.substringAfter("/shorts/") else url.substringAfter("v=", "")
    return id.take(11).takeIf { VIDEO_ID.matches(it) }
}

/**
 * "3 weeks ago", from the moment YouTube's own "3 weeks ago" was read as. That moment is only
 * as exact as YouTube's words were, so this rounds the same way they did.
 */
internal fun agoText(then: Instant, now: Instant = Instant.now()): String {
    val minutes = Duration.between(then, now).toMinutes().coerceAtLeast(0)
    val days = minutes / (60 * 24)
    val (count, unit) = when {
        minutes < 1 -> return "just now"
        minutes < 60 -> minutes to "minute"
        days < 1 -> minutes / 60 to "hour"
        days < 7 -> days to "day"
        days < 28 -> days / 7 to "week"
        days < 350 -> (days / 30.4).roundToLong().coerceAtLeast(1) to "month"
        else -> (days / 365.0).roundToLong().coerceAtLeast(1) to "year"
    }
    return "$count $unit${if (count == 1L) "" else "s"} ago"
}

private fun isoDate(date: DateWrapper): String? =
    runCatching { date.instant.atZone(ZoneId.systemDefault()).toLocalDate().toString() }.getOrNull()

private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val ANCHOR = Regex("""<a\s[^>]*?href="([^"]*)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG = Regex("""<[^>]+>""")
private val TIMESTAMP = Regex("""^(?:\d{1,2}:)?\d{1,2}:\d{2}$""")
private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|nbsp);""")

/**
 * A description or comment as plain text. YouTube's HTML shortens links and wraps timestamps,
 * hashtags and mentions in links: links get their full address back, the rest keep their words,
 * so the app can make its own links of them.
 */
internal fun plainText(description: Description?): String {
    if (description == null) return ""
    if (description.type() != Description.Type.HTML) return description.content().orEmpty().trim()
    var text = description.content().orEmpty().replace(BREAK, "\n")
    text = ANCHOR.replace(text) { match ->
        val href = unescape(match.groupValues[1])
        val words = unescape(match.groupValues[2].replace(TAG, "")).trim()
        val keepWords = TIMESTAMP.matches(words) || words.startsWith("#") || words.startsWith("@") ||
            !href.startsWith("http")
        // Escaped again, since every entity is undone below.
        (if (keepWords) words else href).replace("&", "&amp;")
    }
    return unescape(text.replace(TAG, "")).trim()
}

private fun unescape(text: String): String = ENTITY.replace(text) { match ->
    when (val name = match.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        else -> {
            val code = if (name.startsWith("#x")) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: match.value
        }
    }
}

/** Only MP4 tracks with an index can be played in ranges; the rest are left out. */
internal fun VideoStream.toTrack(): Track? {
    val itag = itagItem ?: return null
    if (!isVideoOnly || deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP || !isUrl) return null
    if (format?.mimeType != "video/mp4" || itag.indexEnd <= 0 || itag.initEnd <= 0) return null
    val codecs = itag.codec ?: return null
    return Track(
        url = content,
        mimeType = "video/mp4",
        codecs = codecs,
        bitrate = itag.bitrate,
        init = itag.initStart.toLong()..itag.initEnd.toLong(),
        index = itag.indexStart.toLong()..itag.indexEnd.toLong(),
        width = itag.width,
        height = itag.height,
        fps = itag.fps.coerceAtLeast(0),
        hdr = isHdr(codecs),
    )
}

/** 10-bit AV1 and VP9 profile 2 are how YouTube serves HDR. */
internal fun isHdr(codecs: String): Boolean =
    (codecs.startsWith("av01") && codecs.split('.').getOrNull(3) == "10") || codecs.startsWith("vp09.02")

/** AAC, in the original language if the video has dubs, without dynamic range compression. */
internal fun chooseAudio(streams: List<AudioStream>): Track? =
    streams
        .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        .filter { it.itagItem?.let { itag -> itag.indexEnd > 0 && itag.codec?.startsWith("mp4a.40.2") == true } == true }
        .filter { it.itagItem?.isDrc != true }
        .sortedWith(
            compareByDescending<AudioStream> { it.audioTrackType == AudioTrackType.ORIGINAL || it.audioTrackType == null }
                .thenByDescending { it.averageBitrate },
        )
        .firstOrNull()
        ?.let { stream ->
            val itag = stream.itagItem!!
            Track(
                url = stream.content,
                mimeType = "audio/mp4",
                codecs = itag.codec,
                bitrate = itag.bitrate,
                init = itag.initStart.toLong()..itag.initEnd.toLong(),
                index = itag.indexStart.toLong()..itag.indexEnd.toLong(),
                sampleRate = itag.sampleRate.coerceAtLeast(0),
                channels = itag.audioChannels.takeIf { it > 0 } ?: 2,
            )
        }

/** YouTube checks that stream requests come from the client the URLs were made for. */
internal fun headersFor(url: String): Map<String, String> = when {
    YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
        mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(NewPipe.getPreferredLocalization()))
    YoutubeParsingHelper.isWebStreamingUrl(url) ->
        YoutubeParsingHelper.getOriginReferrerHeaders("https://www.youtube.com").mapValues { it.value.first() }
    else -> emptyMap()
}

