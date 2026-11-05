package org.voltr.antisocial.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What stands between you and watching. Only OPEN lets you browse and start videos. */
@Serializable
enum class SessionState {
    @SerialName("open") OPEN,
    @SerialName("choose") CHOOSE,
    @SerialName("gap") GAP,
    @SerialName("no_time") NO_TIME,
}

@Serializable
data class Session(
    @SerialName("videos_left") val videosLeft: Int,
    @SerialName("samples_left") val samplesLeft: Int,
)

@Serializable
data class Status(
    @SerialName("budget_seconds") val budgetSeconds: Double,
    @SerialName("used_seconds") val usedSeconds: Long,
    @SerialName("remaining_seconds") val remainingSeconds: Long,
    @SerialName("can_watch") val canWatch: Boolean,
    @SerialName("resets_at") val resetsAt: Double,
    val state: SessionState,
    val session: Session? = null,
    @SerialName("next_session_at") val nextSessionAt: Double? = null,
    @SerialName("session_choices") val sessionChoices: List<Int>,
    @SerialName("sample_seconds") val sampleSeconds: Int,
    val now: Double,
)

@Serializable
data class Video(
    val id: String,
    val title: String,
    val channel: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    val duration: Int? = null,
    /** Set when the video comes from history. */
    val note: String? = null,
)

@Serializable
data class VideoDetails(
    val id: String,
    val title: String,
    val channel: String = "",
    @SerialName("channel_id") val channelId: String? = null,
    val duration: Int = 0,
)

@Serializable
data class ChannelRef(@SerialName("channel_id") val channelId: String, val name: String)

@Serializable
data class ChannelPage(val id: String, val name: String, val results: List<Video>)

@Serializable
data class PlaylistRef(val id: Int, val name: String, val count: Int)

@Serializable
data class Playlist(val id: Int, val name: String, val items: List<Video>)

@Serializable
private data class SearchResults(val results: List<Video>)

@Serializable
private data class Subscriptions(val subscriptions: List<ChannelRef>)

@Serializable
private data class Playlists(val playlists: List<PlaylistRef>)

@Serializable
private data class WatchedVideo(
    @SerialName("video_id") val videoId: String,
    val day: String,
    val title: String,
    val channel: String = "",
    @SerialName("watched_seconds") val watchedSeconds: Double = 0.0,
)

@Serializable
private data class Watched(val videos: List<WatchedVideo>)

@Serializable
private data class ErrorBody(val message: String? = null)

@Serializable
private data class NewSession(val videos: Int)

@Serializable
private data class NewSubscription(@SerialName("channel_id") val channelId: String, val name: String)

/** The server refused or failed. The message is safe to show. */
class ApiError(val code: Int, message: String) : Exception(message) {
    val refused get() = code == REFUSED

    companion object {
        /** No session or no time. Status says which, and what happens next. */
        const val REFUSED = 402
    }
}

/**
 * The HTTP API the web frontend uses. Every rule lives on the server; this only asks.
 *
 * Extraction can take yt-dlp up to 90 seconds, twice, so reads wait a long time.
 */
class Api(
    private val baseUrl: String = BuildConfig.SERVER_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base: HttpUrl = baseUrl.toHttpUrl()

    fun thumbnailUrl(videoId: String) = "$baseUrl/thumb/$videoId"
    fun masterPlaylistUrl(videoId: String) = "$baseUrl/media/$videoId/master.m3u8"

    suspend fun status(): Status = get(url("api", "status"))

    suspend fun startSession(videos: Int) {
        val body = json.encodeToString(NewSession.serializer(), NewSession(videos))
        send(Request.Builder().url(url("api", "session")).post(body.toRequestBody(JSON)))
    }

    suspend fun search(query: String): List<Video> =
        get<SearchResults>(url("api", "search").newBuilder().addQueryParameter("q", query).build()).results

    suspend fun channel(id: String): ChannelPage = get(url("api", "channel", id))

    suspend fun subscriptions(): List<ChannelRef> = get<Subscriptions>(url("api", "subscriptions")).subscriptions

    suspend fun subscribe(channelId: String, name: String) {
        val body = json.encodeToString(NewSubscription.serializer(), NewSubscription(channelId, name))
        send(Request.Builder().url(url("api", "subscriptions")).post(body.toRequestBody(JSON)))
    }

    suspend fun unsubscribe(channelId: String) {
        send(Request.Builder().url(url("api", "subscriptions", channelId)).delete())
    }

    suspend fun playlists(): List<PlaylistRef> = get<Playlists>(url("api", "playlists")).playlists

    suspend fun playlist(id: Int): Playlist = get(url("api", "playlists", id.toString()))

    suspend fun history(): List<Video> =
        get<Watched>(url("api", "history", "videos")).videos.map {
            Video(
                id = it.videoId,
                title = it.title,
                channel = it.channel,
                note = "${it.day} · ${minutesText(it.watchedSeconds.toLong())}",
            )
        }

    suspend fun video(id: String): VideoDetails = get(url("api", "video", id))

    private fun url(vararg segments: String): HttpUrl =
        segments.fold(base.newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build()

    private suspend inline fun <reified T> get(url: HttpUrl): T =
        json.decodeFromString(send(Request.Builder().url(url)))

    private suspend fun send(request: Request.Builder): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiError(response.code, errorMessage(response.code, body))
                body
            }
        } catch (e: IOException) {
            throw ApiError(0, "Can't reach the anti-social server.")
        }
    }

    private fun errorMessage(code: Int, body: String): String =
        runCatching { json.decodeFromString<ErrorBody>(body).message }.getOrNull()
            ?: "Request failed ($code)"

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
