package org.voltr.antisocial.tv

import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_HIDE_MS = 4_000L
private const val POSITION_POLL_MS = 500L
// Seeking loads the segments it lands on, and loading is what's charged. Holding a key
// only moves the target, and the jump happens once the key has settled.
private const val SEEK_SETTLE_MS = 600L
private val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

private enum class Ending { None, Finished, OutOfTime, Failed }

private fun Context.positions() = getSharedPreferences("positions", Context.MODE_PRIVATE)

private fun speedText(speed: Float) = "${speed.toString().removeSuffix(".0")}x"

@Composable
fun PlayerScreen(
    api: Api,
    budget: Budget,
    entry: Entry,
    route: Route.Watch,
    onBack: () -> Unit,
    onNext: (Route.Watch) -> Unit,
) {
    val load = rememberLoad(entry, budget) { api.video(route.id) }
    var nextVideo by remember { mutableStateOf<Video?>(null) }
    LaunchedEffect(route.listId) {
        val listId = route.listId ?: return@LaunchedEffect
        // A missing next video only means no link at the end, so a failure stays quiet.
        val items = runCatching { api.playlist(listId).items }.getOrNull().orEmpty()
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
        Load.OutOfTime -> Message {
            OutOfTimePanel(budget, Modifier.widthIn(max = 640.dp))
        }
        is Load.Ready -> VideoPlayer(api, budget, state.value, route, nextVideo, onBack, onNext)
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

/** The player only exists while the app is visible, so leaving the app stops the spending. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    api: Api,
    budget: Budget,
    video: VideoDetails,
    route: Route.Watch,
    next: Video?,
    onBack: () -> Unit,
    onNext: (Route.Watch) -> Unit,
) {
    val context = LocalContext.current
    val saved = remember { context.positions() }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var resumeAt by remember { mutableLongStateOf(saved.getLong(video.id, 0L)) }
    var speed by remember { mutableStateOf(1f) }
    var qualities by remember { mutableStateOf(listOf(Quality("auto", null))) }
    var quality by remember { mutableIntStateOf(0) }
    var ending by remember { mutableStateOf(Ending.None) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(resumeAt) }
    var durationMs by remember { mutableLongStateOf(video.duration * 1000L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var seekInput by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(0) }

    LifecycleStartEffect(video.id) {
        val created = buildPlayer(context, api.masterPlaylistUrl(video.id)).apply {
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
                    ending = if (error.isOutOfTime()) Ending.OutOfTime else Ending.Failed
                }

                override fun onTracksChanged(tracks: Tracks) {
                    qualities = qualitiesOf(tracks)
                }
            })
            if (resumeAt > 5_000) seekTo(resumeAt)
            setPlaybackSpeed(speed)
            playWhenReady = ending == Ending.None
            prepare()
        }
        player = created
        onStopOrDispose {
            resumeAt = created.currentPosition
            if (ending != Ending.Finished) saved.edit().putLong(video.id, resumeAt).apply()
            created.release()
            player = null
        }
    }

    // Time returned (an unlock's wait ended, or the day reset), so carry on from the same spot.
    val canWatch = budget.status?.canWatch == true
    LaunchedEffect(ending, canWatch) {
        val current = player
        if (ending == Ending.OutOfTime && canWatch && current != null) {
            ending = Ending.None
            current.prepare()
            current.play()
        }
    }

    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        var sinceSave = 0L
        while (true) {
            if (seekTarget == null) position = current.currentPosition
            sinceSave += POSITION_POLL_MS
            if (sinceSave >= 5_000 && ending == Ending.None) {
                saved.edit().putLong(video.id, current.currentPosition).apply()
                sinceSave = 0
            }
            delay(POSITION_POLL_MS)
        }
    }

    LaunchedEffect(seekInput) {
        val target = seekTarget ?: return@LaunchedEffect
        delay(SEEK_SETTLE_MS)
        player?.seekTo(target)
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
        player?.let { if (it.isPlaying) it.pause() else it.play() }
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

        when (ending) {
            Ending.OutOfTime -> Overlay {
                OutOfTimePanel(budget)
                BackFromOutOfTime(budget, onBack)
            }
            Ending.Failed -> Overlay {
                Label("Playback failed. YouTube may have refused this stream.", Type.problem)
                Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    FocusedAction("Try again", {
                        ending = Ending.None
                        player?.prepare()
                        player?.play()
                    }, primary = true)
                    ActionButton("Back", onBack)
                }
            }
            // The end of a video is the end. There is no next video unless you pick it.
            Ending.Finished -> Overlay {
                Label("That was the video.", Type.h2)
                Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    FocusedAction("Back", onBack, primary = true)
                    if (next != null) {
                        ActionButton("Next: ${next.title}", { onNext(Route.Watch(next.id, route.listId)) })
                    }
                }
            }
            Ending.None -> Unit
        }

        if (ending == Ending.None) {
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
                            player?.let { applyQuality(it, qualities[quality].override) }
                            activity++
                        })
                    }
                }
            }
            LaunchedEffect(controls) { if (!controls) seekBar.requestFocus() }
            LaunchedEffect(Unit) { seekBar.requestFocus() }
        }
    }
}

@OptIn(UnstableApi::class)
private fun applyQuality(player: ExoPlayer, override: TrackSelectionOverride?) {
    val builder = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
    if (override != null) builder.addOverride(override)
    player.trackSelectionParameters = builder.build()
}

@Composable
private fun BackFromOutOfTime(budget: Budget, onBack: () -> Unit) {
    val status = budget.status
    // The panel focuses its unlock button when it has one.
    if (status == null || status.unlocksLeft == 0 || status.unlockWaitLeft != null) {
        FocusedAction("Back", onBack)
    } else {
        ActionButton("Back", onBack)
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
