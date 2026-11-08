package org.voltr.antisocial.tv

import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.SessionState
import org.voltr.antisocial.core.Status
import org.voltr.antisocial.core.Streams
import org.voltr.antisocial.core.Verdict
import org.voltr.antisocial.core.Video
import org.voltr.antisocial.core.Watch
import org.voltr.antisocial.core.durationText
import org.voltr.antisocial.core.videosText
import org.voltr.antisocial.playback.Quality
import org.voltr.antisocial.playback.applyQuality
import org.voltr.antisocial.playback.buildPlayer
import org.voltr.antisocial.playback.isStaleStream
import org.voltr.antisocial.playback.qualitiesOf

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_HIDE_MS = 4_000L
private const val POSITION_POLL_MS = 500L
// Holding a key only moves the target, and the jump happens once the key has settled.
private const val SEEK_SETTLE_MS = 600L
private val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

private enum class Ending { None, Finished, Failed }

private fun Context.positions() = getSharedPreferences("positions", Context.MODE_PRIVATE)

private fun speedText(speed: Float) = "${speed.toString().removeSuffix(".0")}x"

@Composable
fun PlayerScreen(
    services: Services,
    entry: Entry,
    route: Route.Watch,
    onBack: () -> Unit,
    onNext: (Route.Watch) -> Unit,
    onChannel: (String) -> Unit,
) {
    val budget = services.budget
    val load = rememberLoad(entry, budget) { services.youtube.streams(route.id) }
    var nextVideo by remember { mutableStateOf<Video?>(null) }
    LaunchedEffect(route.listId) {
        val listId = route.listId ?: return@LaunchedEffect
        // A missing next video only means no link at the end, so a failure stays quiet.
        val items = runCatching { services.api.playlist(listId).items }.getOrNull().orEmpty()
        nextVideo = items.getOrNull(items.indexOfFirst { it.id == route.id } + 1)
            ?.takeIf { items.any { item -> item.id == route.id } }
    }
    BackHandler(onBack = onBack)

    when (val state = load.state) {
        Load.Loading -> Message { Label("Loading video…", Type.muted) }
        is Load.Failed -> Message {
            Label(state.message, Type.problem)
            Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                FocusedAction("Try again", load.retry, primary = true)
                ActionButton("Back", onBack)
            }
        }
        is Load.Ready -> androidx.compose.runtime.key(state.value) {
            VideoPlayer(services, state.value, route, nextVideo, onBack, onNext, onChannel, reload = {
                entry.data = null
                load.retry()
            })
        }
    }
}

@Composable
private fun Message(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Unit5 * 8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Unit5 * 4, Alignment.CenterVertically),
    ) {
        content()
    }
}

@Composable
private fun FocusedAction(text: String, onClick: () -> Unit, primary: Boolean = false) {
    val focus = remember { FocusRequester() }
    ActionButton(text, onClick, Modifier.focusRequester(focus), primary)
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * Plays only while check-ins allow it (ADR 0001). The first check-in happens before anything
 * plays. The player only exists while the app is visible, so leaving the app stops it.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    services: Services,
    streams: Streams,
    route: Route.Watch,
    next: Video?,
    onBack: () -> Unit,
    onNext: (Route.Watch) -> Unit,
    onChannel: (String) -> Unit,
    reload: () -> Unit,
) {
    val budget = services.budget
    val video = streams.details
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { context.positions() }
    val watch = remember { Watch(services.api, video) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var resumeAt by remember { mutableLongStateOf(saved.getLong(video.id, 0L)) }
    var speed by remember { mutableStateOf(1f) }
    var qualities by remember { mutableStateOf(listOf(Quality("auto", null))) }
    var quality by remember { mutableIntStateOf(0) }
    var ending by remember { mutableStateOf(Ending.None) }
    var failure by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(resumeAt) }
    var durationMs by remember { mutableLongStateOf(video.durationSeconds * 1000L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var seekInput by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(0) }
    // Why playback is held, when it is. Null while check-ins allow playing.
    var held by remember { mutableStateOf<Verdict?>(Verdict.Unknown) }
    // Whether the person wants it playing, whatever the check-ins say right now.
    var wanted by remember { mutableStateOf(true) }

    /** Check in, and play or hold by the answer. */
    suspend fun checkIn() {
        when (val verdict = watch.checkIn()) {
            is Verdict.Allowed -> {
                budget.update(verdict.status)
                held = null
                if (wanted && ending == Ending.None) player?.play()
            }
            is Verdict.Refused -> {
                budget.update(verdict.status)
                held = verdict
                player?.pause()
            }
            is Verdict.SignedOut -> {
                held = verdict
                player?.pause()
                budget.refresh()
            }
            // Playing goes on until the last check-in's expiry; the loop below stops it then.
            is Verdict.Unreachable -> if (!watch.mayPlay()) held = verdict
            Verdict.Unknown -> Unit
        }
    }

    LifecycleStartEffect(video.id) {
        val created = buildPlayer(context, streams).apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    buffering = state == Player.STATE_BUFFERING
                    if (duration > 0) durationMs = duration
                    if (state == Player.STATE_ENDED) {
                        ending = Ending.Finished
                        saved.edit().remove(video.id).apply()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    ending = Ending.Failed
                    failure = if (error.isStaleStream()) {
                        "YouTube stopped accepting this stream. Reloading usually fixes it."
                    } else {
                        "Playback failed: ${error.errorCodeName}"
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    qualities = qualitiesOf(tracks)
                }
            })
            if (resumeAt > 5_000) seekTo(resumeAt)
            setPlaybackSpeed(speed)
            // Loading is free; playing waits for a check-in.
            playWhenReady = false
            prepare()
        }
        player = created
        scope.launch { checkIn() }
        onStopOrDispose {
            resumeAt = created.currentPosition
            watch.stretches.observe(created.currentPosition / 1000.0, created.isPlaying)
            if (ending != Ending.Finished) saved.edit().putLong(video.id, resumeAt).apply()
            created.release()
            player = null
            // Report the last few seconds, even though the screen is going away.
            if (!watch.stretches.isEmpty) services.scope.launch { watch.checkIn() }
        }
    }

    // Watches the playhead, checks in on time, and stops playing once nothing allows it.
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        var sinceSave = 0L
        while (true) {
            if (seekTarget == null) position = current.currentPosition
            watch.stretches.observe(current.currentPosition / 1000.0, current.isPlaying)
            if (watch.due() && (current.isPlaying || !watch.stretches.isEmpty || held != null)) checkIn()
            if (current.isPlaying && !watch.mayPlay()) {
                current.pause()
                held = watch.verdict
            }
            sinceSave += POSITION_POLL_MS
            if (sinceSave >= 5_000 && ending == Ending.None) {
                saved.edit().putLong(video.id, current.currentPosition).apply()
                sinceSave = 0
            }
            delay(POSITION_POLL_MS)
        }
    }

    // A session opened from the gate, or the day reset: ask again and carry on from the same spot.
    LaunchedEffect(held is Verdict.Refused, budget.status?.state) {
        if (held is Verdict.Refused && budget.status?.state == SessionState.OPEN) checkIn()
    }

    LaunchedEffect(seekInput) {
        val target = seekTarget ?: return@LaunchedEffect
        delay(SEEK_SETTLE_MS)
        player?.seekTo(target)
        watch.stretches.lostTrack()
        position = target
        seekTarget = null
    }

    LaunchedEffect(activity, playing, ending) {
        if (!playing || ending != Ending.None) {
            controls = true
            return@LaunchedEffect
        }
        delay(CONTROLS_HIDE_MS)
        controls = false
    }

    val seekBar = remember { FocusRequester() }
    fun seekBy(delta: Long) {
        val from = seekTarget ?: position
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        seekTarget = (from + delta).coerceIn(0, upper)
        seekInput++
        controls = true
        activity++
    }

    fun togglePlaying() {
        val current = player ?: return
        wanted = !current.isPlaying
        if (!wanted) {
            current.pause()
        } else if (watch.mayPlay()) {
            current.play()
        } else {
            scope.launch { checkIn() }
        }
        controls = true
        activity++
    }

    BackHandler(enabled = controls && playing) { controls = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlaying()
                        true
                    }
                    Key.MediaFastForward -> {
                        seekBy(SEEK_STEP_MS * 3)
                        true
                    }
                    Key.MediaRewind -> {
                        seekBy(-SEEK_STEP_MS * 3)
                        true
                    }
                    else -> {
                        activity++
                        // The first press only brings the controls back, except on the seek bar's keys.
                        if (!controls && event.key != Key.DirectionLeft && event.key != Key.DirectionRight &&
                            event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.Back
                        ) {
                            controls = true
                            true
                        } else {
                            false
                        }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    isFocusable = false
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        val hold = held
        when {
            ending == Ending.Failed -> Overlay {
                Label(failure ?: "Playback failed.", Type.problem)
                Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    FocusedAction("Reload", reload, primary = true)
                    ActionButton("Back", onBack)
                }
            }
            // The end of a video is the end. There is no next video unless you pick it.
            ending == Ending.Finished -> Overlay {
                val status = budget.status
                val open = status?.state == SessionState.OPEN
                Label("That was the video.", Type.h2)
                status?.let { Label(afterVideoText(it), Type.muted) }
                Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    FocusedAction("Back", onBack, primary = true)
                    if (next != null && open) {
                        ActionButton("Next: ${next.title}", { onNext(Route.Watch(next.id, route.listId)) })
                    }
                }
            }
            hold is Verdict.Refused -> Overlay {
                SessionGate(budget)
                BackFromGate(budget, onBack)
            }
            hold is Verdict.Unreachable -> Overlay {
                Label("Can't reach the anti-social server", Type.h2)
                Label("The video carries on once it's back.", Type.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    FocusedAction("Try now", { scope.launch { checkIn() } }, primary = true)
                    ActionButton("Back", onBack)
                }
            }
            hold == Verdict.Unknown -> Overlay { Label("Checking in…", Type.muted) }
        }

        if (ending == Ending.None && (hold == null || hold is Verdict.SignedOut)) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (controls) Modifier.background(Palette.scrim) else Modifier)
                    .padding(horizontal = Unit5 * 8, vertical = Unit5 * 4),
                verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
            ) {
                if (controls) {
                    Label(video.title, Type.h2, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 3)) {
                        Label(video.channel, Type.muted, Modifier.weight(1f), maxLines = 1)
                        when {
                            buffering -> Label("loading…", Type.muted, maxLines = 1)
                            !playing -> Label("paused", Type.muted, maxLines = 1)
                        }
                        BudgetClock(budget.status, budget.serverDown)
                    }
                }
                // Always composed, even hidden, because it holds focus and takes the seek keys.
                SeekBar(
                    position = seekTarget ?: position,
                    duration = durationMs,
                    visible = controls,
                    focus = seekBar,
                    onSeek = ::seekBy,
                    onToggle = ::togglePlaying,
                )
                if (controls) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                        ActionButton(speedText(speed), {
                            speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
                            player?.setPlaybackSpeed(speed)
                            activity++
                        })
                        ActionButton(qualities.getOrNull(quality)?.label ?: "auto", {
                            quality = (quality + 1) % qualities.size
                            player?.applyQuality(qualities[quality].override)
                            activity++
                        })
                        // The way to a channel's page, to subscribe or see what else it made.
                        video.channelId?.let { channelId ->
                            ActionButton(video.channel.ifEmpty { "Channel" }, { onChannel(channelId) })
                        }
                    }
                }
            }
            LaunchedEffect(controls) { if (!controls) seekBar.requestFocus() }
            LaunchedEffect(Unit) { seekBar.requestFocus() }
        }
    }
}

private fun afterVideoText(status: Status): String = when (status.state) {
    SessionState.OPEN -> "${videosText(status.session?.videosLeft ?: 0)} left in this session."
    SessionState.GAP -> "That's the session. The next one opens at ${status.nextSessionAt?.let(::timeOfDay).orEmpty()}"
    SessionState.NO_TIME -> "That's all your time today. It resets at ${timeOfDay(status.resetsAt)}"
    SessionState.CHOOSE -> "That's the session."
}

@Composable
private fun BackFromGate(budget: Budget, onBack: () -> Unit) {
    // The gate focuses its first choice when it's asking the question.
    if (budget.status?.state == SessionState.CHOOSE) {
        ActionButton("Back", onBack)
    } else {
        FocusedAction("Back", onBack)
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).padding(Unit5 * 8),
            verticalArrangement = Arrangement.spacedBy(Unit5 * 4),
        ) {
            content()
        }
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    visible: Boolean,
    focus: FocusRequester,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onSeek(-SEEK_STEP_MS)
                        true
                    }
                    Key.DirectionRight -> {
                        onSeek(SEEK_STEP_MS)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        onToggle()
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Unit5),
    ) {
        if (visible) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Unit5 * 2)
                    .background(Palette.raised, Corner)
                    .border(1.dp, if (focused) Palette.accent else Color.Transparent, Corner),
            ) {
                Box(Modifier.fillMaxWidth(fraction).height(Unit5 * 2).background(Palette.accent, Corner))
            }
            Row {
                Label(durationText(position / 1000), Type.body, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Label(durationText(duration / 1000), Type.muted, maxLines = 1)
            }
        }
    }
}
