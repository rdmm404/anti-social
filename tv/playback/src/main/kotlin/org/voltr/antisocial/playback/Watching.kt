package org.voltr.antisocial.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.Api
import org.voltr.antisocial.core.Budget
import org.voltr.antisocial.core.PlayerControl
import org.voltr.antisocial.core.Streams
import org.voltr.antisocial.core.Verdict
import org.voltr.antisocial.core.Viewing
import org.voltr.antisocial.core.Watch

enum class Ending { None, Finished, Failed }

/** Everything a screen shows about the video that's open. */
data class WatchingState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val ending: Ending = Ending.None,
    val failure: String? = null,
    /** Why playback is held, when it is. Null while check-ins allow playing. */
    val held: Verdict? = Verdict.Unknown,
    val speed: Float = 1f,
    val qualities: List<Quality> = listOf(Quality("auto", null)),
    val quality: Int = 0,
)

val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

/** Where each video was left, so opening it again carries on from there. */
class Positions(context: Context) {
    private val prefs = context.getSharedPreferences("positions", Context.MODE_PRIVATE)
    fun get(videoId: String): Long = prefs.getLong(videoId, 0L)
    fun put(videoId: String, ms: Long) = prefs.edit().putLong(videoId, ms).apply()
    fun forget(videoId: String) = prefs.edit().remove(videoId).apply()
}

/**
 * One open video: its ExoPlayer and its check-in loop, owned by whatever outlives the screens
 * (on the phone, a MediaSessionService). Screens only observe [state] and call the controls.
 * Everything here runs on the main thread, which is the player's.
 */
@OptIn(UnstableApi::class)
class Watching(
    context: Context,
    api: Api,
    private val budget: Budget,
    val streams: Streams,
    private val positions: Positions,
    private val scope: CoroutineScope,
    /** Where to start, when a link says so. Otherwise where it was left. */
    startMs: Long? = null,
) {
    val videoId = streams.details.id
    val exo: ExoPlayer = buildPlayer(context, streams)

    private val control = object : PlayerControl {
        override val positionSeconds get() = exo.currentPosition / 1000.0
        override val isPlaying get() = exo.isPlaying
        override fun play() = exo.play()
        override fun pause() = exo.pause()
    }
    private val viewing = Viewing(Watch(api, streams.details), control, budget)

    private val _state = MutableStateFlow(WatchingState(durationMs = streams.details.durationSeconds * 1000))
    val state: StateFlow<WatchingState> = _state.asStateFlow()

    /**
     * The player a MediaSession, a notification or picture-in-picture gets. Its play and pause
     * go through the check-ins like the screen's.
     */
    val gated: Player = object : ForwardingPlayer(exo) {
        override fun play() = this@Watching.play()
        override fun pause() = this@Watching.pause()
        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()
    }

    private var loop: Job? = null
    private var released = false

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        exo.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        exo.setHandleAudioBecomingNoisy(true)
        // Keeps the CPU and Wi-Fi awake with the screen off.
        exo.setWakeMode(C.WAKE_MODE_NETWORK)
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = _state.update { it.copy(playing = isPlaying) }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update {
                    it.copy(
                        buffering = playbackState == Player.STATE_BUFFERING,
                        durationMs = if (exo.duration > 0) exo.duration else it.durationMs,
                    )
                }
                if (playbackState == Player.STATE_ENDED) {
                    viewing.ended = true
                    positions.forget(videoId)
                    _state.update { it.copy(ending = Ending.Finished) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                viewing.ended = true
                val message = if (error.isStaleStream()) {
                    "YouTube stopped accepting this stream. Reloading usually fixes it."
                } else {
                    "Playback failed: ${error.errorCodeName}"
                }
                _state.update { it.copy(ending = Ending.Failed, failure = message) }
            }

            override fun onTracksChanged(tracks: Tracks) = _state.update { it.copy(qualities = qualitiesOf(tracks)) }
        })
        val start = startMs ?: positions.get(videoId).takeIf { it > 5_000 }
        if (start != null) exo.seekTo(start)
        _state.update { it.copy(positionMs = exo.currentPosition) }
        // Loading is free; playing waits for a check-in.
        exo.playWhenReady = false
        exo.prepare()
        loop = scope.launch {
            launch { viewing.held.collect { held -> _state.update { it.copy(held = held) } } }
            launch { budget.status.collect { viewing.statusChanged(it?.state) } }
            viewing.checkIn()
            var sinceSave = 0L
            while (true) {
                viewing.tick()
                _state.update { it.copy(positionMs = exo.currentPosition) }
                sinceSave += POLL_MS
                if (sinceSave >= SAVE_MS && _state.value.ending == Ending.None) {
                    positions.put(videoId, exo.currentPosition)
                    sinceSave = 0
                }
                delay(POLL_MS)
            }
        }
    }

    fun play() {
        if (released) return
        scope.launch { viewing.play() }
    }

    fun pause() {
        if (!released) viewing.pause()
    }

    fun toggle() = if (exo.isPlaying) pause() else play()

    fun seekTo(ms: Long) {
        val upper = _state.value.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = ms.coerceIn(0, upper)
        exo.seekTo(target)
        viewing.seeked()
        _state.update { it.copy(positionMs = target) }
    }

    fun seekBy(deltaMs: Long) = seekTo(exo.currentPosition + deltaMs)

    /** Watch it again from the start, after it finished. */
    fun restart() {
        viewing.ended = false
        _state.update { it.copy(ending = Ending.None) }
        seekTo(0)
        play()
    }

    fun retryCheckIn() {
        scope.launch { viewing.checkIn() }
    }

    fun setSpeed(speed: Float) {
        exo.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setQuality(index: Int) {
        val choice = _state.value.qualities.getOrNull(index) ?: return
        exo.applyQuality(choice.override)
        _state.update { it.copy(quality = index) }
    }

    /** Saves the spot, reports the last seconds, and frees the player. */
    fun release() {
        if (released) return
        released = true
        loop?.cancel()
        if (_state.value.ending == Ending.None) positions.put(videoId, exo.currentPosition)
        // The last check-in outlives this, on the owner's scope; the player goes once it's sent.
        scope.launch {
            try {
                viewing.close()
            } finally {
                exo.release()
            }
        }
    }

    private companion object {
        const val POLL_MS = 500L
        const val SAVE_MS = 5_000L
    }
}
