package org.voltr.antisocial.phone

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.YouTubeError
import org.voltr.antisocial.playback.Positions
import org.voltr.antisocial.playback.Watching

/** The video that's open, from asking YouTube for it to watching it. */
sealed interface Opening {
    val id: String

    data class Loading(override val id: String) : Opening
    data class Failed(override val id: String, val message: String) : Opening
    data class Ready(val watching: Watching) : Opening {
        override val id get() = watching.videoId
    }
}

/**
 * Opens and closes videos. One at a time: opening another closes the first. Playback runs in
 * [PlaybackService] so it carries on with the screen locked, in the background and in
 * picture-in-picture; screens only observe [current].
 */
class PlaybackController(private val services: Services) {
    private val context = services.context
    private val positions = Positions(context)
    private val _current = MutableStateFlow<Opening?>(null)
    val current: StateFlow<Opening?> = _current.asStateFlow()
    private var loading: Job? = null

    /** Opens [id], unless it's already open. [startSeconds] comes from a link's t=. */
    fun open(id: String, startSeconds: Int? = null) {
        val now = _current.value
        if (now != null && now.id == id && now !is Opening.Failed) {
            if (startSeconds != null) (now as? Opening.Ready)?.watching?.seekTo(startSeconds * 1000L)
            return
        }
        close()
        _current.value = Opening.Loading(id)
        loading = services.scope.launch {
            _current.value = try {
                val streams = services.youtube.streams(id)
                val watching = Watching(
                    context, services.api, services.budget, streams, positions, services.scope,
                    startMs = startSeconds?.let { it * 1000L },
                )
                Opening.Ready(watching)
            } catch (e: YouTubeError) {
                Opening.Failed(id, e.message ?: "YouTube wouldn't give us this.")
            }
            if (_current.value is Opening.Ready) {
                context.startService(Intent(context, PlaybackService::class.java))
            }
        }
    }

    /** Fetches the streams again, after a failure or a stale stream. */
    fun reload() {
        val id = _current.value?.id ?: return
        close()
        open(id)
    }

    fun pause() {
        (_current.value as? Opening.Ready)?.watching?.pause()
    }

    /** Closes the video: its spot is saved and its last seconds reported. */
    fun close() {
        loading?.cancel()
        loading = null
        (_current.value as? Opening.Ready)?.watching?.release()
        _current.value = null
    }
}

/**
 * Owns the media session for the open video: its notification, lock screen controls and the
 * controls picture-in-picture shows. The check-in loop itself runs in [Watching].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val back = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        scope.launch {
            services.playback.current.collect { opening ->
                val watching = (opening as? Opening.Ready)?.watching
                val current = session
                when {
                    watching == null -> {
                        current?.let {
                            removeSession(it)
                            it.release()
                        }
                        session = null
                        if (opening == null) stopSelf()
                    }
                    current == null -> session = MediaSession.Builder(this@PlaybackService, watching.gated)
                        .setSessionActivity(back)
                        .build()
                        .also { addSession(it) }
                    else -> current.player = watching.gated
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away closes the video, as closing it would. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        services.playback.close()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }
}
