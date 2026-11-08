package org.voltr.antisocial.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What stands between the account and watching. Only OPEN lets you browse and start videos. */
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

/** Where the account stands. Times are Unix seconds. */
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
    @SerialName("checkin_seconds") val checkinSeconds: Int = 15,
    val now: Double,
)

/** A video in a list: search results, a channel, a playlist or history. */
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
data class ChannelRef(@SerialName("channel_id") val channelId: String, val name: String)

data class ChannelPage(val id: String, val name: String, val videos: List<Video>)

@Serializable
data class PlaylistRef(val id: Int, val name: String, val count: Int)

@Serializable
data class Playlist(val id: Int, val name: String, val items: List<Video>)

/** A stretch of a video, in seconds from its start. */
typealias Stretch = List<Double>

/** The server's answer to a check-in. */
@Serializable
data class CheckinReply(
    val allowed: Boolean,
    /** Why not: "out_of_time" or "no_session". */
    val reason: String? = null,
    /** Unix seconds, by the server's clock. */
    @SerialName("expires_at") val expiresAt: Double,
    val status: Status,
)
