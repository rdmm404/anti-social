package org.voltr.antisocial.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The server refused or failed. The message is safe to show. */
class ApiError(val code: Int, message: String) : Exception(message) {
    val unreachable get() = code == 0
    val signedOut get() = code == 401
}

/** A failed request's body: `{"error": {"code": "refused", "message": "..."}}`. */
@Serializable
private data class ErrorReply(val error: ErrorBody? = null)

@Serializable
private data class ErrorBody(val code: String? = null, val message: String? = null)

@Serializable
private data class NewSession(val videos: Int)

@Serializable
private data class NewSubscription(@SerialName("channel_id") val channelId: String, val name: String)

@Serializable
private data class SubscriptionsReply(val subscriptions: List<ChannelRef>)

@Serializable
private data class PlaylistsReply(val playlists: List<PlaylistRef>)

@Serializable
private data class WatchedVideo(
    @SerialName("video_id") val videoId: String,
    val day: String,
    val title: String,
    val channel: String = "",
    @SerialName("watched_seconds") val watchedSeconds: Double = 0.0,
)

@Serializable
private data class WatchedReply(val videos: List<WatchedVideo>)

@Serializable
private data class NewPlaylist(val name: String)

@Serializable
private data class CreatedPlaylist(val id: Int)

/** What a playlist keeps of a video. */
@Serializable
private data class PlaylistItem(val id: String, val title: String, val channel: String, val duration: Int? = null)

@Serializable
private data class NewPairing(val name: String)

@Serializable
private data class PairingClaim(val secret: String)

@Serializable
private data class PairingReply(val state: String, val token: String? = null)

/** A pairing this device started: the code it shows, and the secret it claims its token with. */
@Serializable
data class PairingStarted(
    val code: String,
    val secret: String,
    @SerialName("expires_at") val expiresAt: Double,
    @SerialName("poll_every") val pollEvery: Int = 3,
)

@Serializable
data class Checkin(
    @SerialName("video_id") val videoId: String,
    val title: String,
    val channel: String,
    val watched: List<Stretch>,
)

/**
 * The anti-social server's API. It keeps the budget, sessions, settings, subscriptions,
 * playlists and history. Every rule lives there; this only asks.
 */
class Api(
    baseUrl: String,
    /** The device token, or null before this device has signed in. */
    var token: String?,
    private val http: HttpClient = defaultHttpClient(),
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun status(): Status = get("api", "status")

    /** Where a signed-in device approves a code. The TV shows it as a QR code. */
    fun pairUrl(code: String? = null): String =
        URLBuilder(base).apply { appendPathSegments("pair"); code?.let { parameters.append("code", it) } }.buildString()

    /** Asks to join an account. Needs no token: it's how a device gets one (ADR 0004). */
    suspend fun startPairing(name: String): PairingStarted =
        send(HttpMethod.Post, "api", "pairings", body = json.encodeToString(NewPairing.serializer(), NewPairing(name)))
            .let { json.decodeFromString(it) }

    /**
     * This device's own token once its code is approved, or null while it waits. Throws an
     * [ApiError] with code 404 once the pairing has expired.
     */
    suspend fun claimPairing(secret: String): String? =
        send(HttpMethod.Post, "api", "pairings", "claim", body = json.encodeToString(PairingClaim.serializer(), PairingClaim(secret)))
            .let { json.decodeFromString<PairingReply>(it) }
            .token

    suspend fun startSession(videos: Int): Status =
        send(HttpMethod.Post, "api", "session", body = json.encodeToString(NewSession.serializer(), NewSession(videos)))
            .let { json.decodeFromString(it) }

    suspend fun checkin(checkin: Checkin): CheckinReply =
        send(HttpMethod.Post, "api", "checkins", body = json.encodeToString(Checkin.serializer(), checkin))
            .let { json.decodeFromString(it) }

    suspend fun subscriptions(): List<ChannelRef> = get<SubscriptionsReply>("api", "subscriptions").subscriptions

    suspend fun subscribe(channelId: String, name: String) {
        send(
            HttpMethod.Post, "api", "subscriptions",
            body = json.encodeToString(NewSubscription.serializer(), NewSubscription(channelId, name)),
        )
    }

    suspend fun unsubscribe(channelId: String) {
        send(HttpMethod.Delete, "api", "subscriptions", channelId)
    }

    suspend fun playlists(): List<PlaylistRef> = get<PlaylistsReply>("api", "playlists").playlists

    suspend fun playlist(id: Int): Playlist = get("api", "playlists", id.toString())

    /** Creates an empty playlist and returns its id. */
    suspend fun createPlaylist(name: String): Int =
        send(HttpMethod.Post, "api", "playlists", body = json.encodeToString(NewPlaylist.serializer(), NewPlaylist(name)))
            .let { json.decodeFromString<CreatedPlaylist>(it).id }

    suspend fun deletePlaylist(id: Int) {
        send(HttpMethod.Delete, "api", "playlists", id.toString())
    }

    suspend fun addToPlaylist(id: Int, video: Video) {
        val item = PlaylistItem(video.id, video.title, video.channel.orEmpty(), video.duration)
        send(HttpMethod.Post, "api", "playlists", id.toString(), "items", body = json.encodeToString(PlaylistItem.serializer(), item))
    }

    suspend fun removeFromPlaylist(id: Int, videoId: String) {
        send(HttpMethod.Delete, "api", "playlists", id.toString(), "items", videoId)
    }

    suspend fun history(): List<Video> =
        get<WatchedReply>("api", "history", "videos").videos.map {
            Video(
                id = it.videoId,
                title = it.title,
                channel = it.channel,
                note = "${it.day} · ${minutesText(it.watchedSeconds.toLong())}",
            )
        }

    private suspend inline fun <reified T> get(vararg path: String): T =
        json.decodeFromString(send(HttpMethod.Get, *path))

    private suspend fun send(method: HttpMethod, vararg path: String, body: String? = null): String {
        val url = URLBuilder(base).apply { appendPathSegments(*path) }.buildString()
        val response = try {
            http.request(url) {
                this.method = method
                authenticate()
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiError(0, "Can't reach the anti-social server.")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw ApiError(response.status.value, errorMessage(response.status.value, text))
        return text
    }

    private fun HttpRequestBuilder.authenticate() {
        token?.let { bearerAuth(it) }
    }

    private fun errorMessage(code: Int, body: String): String =
        runCatching { json.decodeFromString<ErrorReply>(body).error?.message }.getOrNull()
            ?: "Request failed ($code)"
}

/** Check-ins are small and frequent, so a dead connection should fail fast. */
fun defaultHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
}
