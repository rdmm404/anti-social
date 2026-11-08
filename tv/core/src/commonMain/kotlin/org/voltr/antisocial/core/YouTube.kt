package org.voltr.antisocial.core

/**
 * Everything the apps get from YouTube itself, from the device's own connection (ADR 0001).
 * Android implements this with NewPipeExtractor in the youtube module.
 */
interface YouTube {
    /** Videos only: no Shorts, live streams or premieres. */
    suspend fun search(query: String): List<Video>

    /** The channel's newest videos. [id] is a UC… id or an @handle. */
    suspend fun channel(id: String): ChannelPage

    suspend fun streams(videoId: String): Streams
}

/** YouTube wouldn't give us this. The message is safe to show. */
class YouTubeError(message: String, cause: Throwable? = null) : Exception(message, cause)

data class VideoDetails(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String?,
    val durationSeconds: Long,
)

/**
 * One MP4 track that can be fetched in byte ranges: the init segment, then a sidx index that
 * maps the rest. Media3 reads the index itself, so seeking fetches only what it lands on.
 */
data class Track(
    val url: String,
    /** "video/mp4" or "audio/mp4". */
    val mimeType: String,
    /** RFC 6381, such as "avc1.640028" or "mp4a.40.2". */
    val codecs: String,
    val bitrate: Int,
    val init: LongRange,
    val index: LongRange,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    /** Audio only. */
    val sampleRate: Int = 0,
    val channels: Int = 2,
    val hdr: Boolean = false,
)

data class Streams(
    val details: VideoDetails,
    /** Lowest first, one per height. */
    val video: List<Track>,
    val audio: Track,
    /** Headers YouTube expects when fetching these tracks, such as the client's user agent. */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * The tracks to offer, from everything YouTube lists for a video.
 *
 * H.264 plays everywhere, so it's used up to 1080p, the highest YouTube encodes it. Above
 * that only AV1 fits, and only on devices that can decode it in hardware. Within a height
 * the highest frame rate wins. HDR is left out: it looks wrong on SDR screens.
 */
fun chooseVideoTracks(candidates: List<Track>, maxHeight: Int = 2160): List<Track> =
    candidates
        .filter { it.height in 1..maxHeight && !it.hdr }
        .filter { if (it.height <= MAX_H264_HEIGHT) it.codecs.startsWith("avc1") else it.codecs.startsWith("av01") }
        .groupBy { it.height }
        .map { (_, sameHeight) -> sameHeight.maxWith(compareBy<Track> { it.fps }.thenBy { it.bitrate }) }
        .sortedBy { it.height }

const val MAX_H264_HEIGHT = 1080
