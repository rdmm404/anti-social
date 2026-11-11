package org.voltr.antisocial.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.ApiError
import org.voltr.antisocial.core.Comment
import org.voltr.antisocial.core.SessionState
import org.voltr.antisocial.core.Status
import org.voltr.antisocial.core.TextPart
import org.voltr.antisocial.core.Verdict
import org.voltr.antisocial.core.Video
import org.voltr.antisocial.core.VideoDetails
import org.voltr.antisocial.core.YouTubeError
import org.voltr.antisocial.core.countText
import org.voltr.antisocial.core.durationText
import org.voltr.antisocial.core.frameUrl
import org.voltr.antisocial.core.linkParts
import org.voltr.antisocial.core.parseYouTubeLink
import org.voltr.antisocial.core.videoMeta
import org.voltr.antisocial.core.videosText
import org.voltr.antisocial.playback.Ending
import org.voltr.antisocial.playback.SPEEDS
import org.voltr.antisocial.playback.Watching
import org.voltr.antisocial.playback.WatchingState

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_HIDE_MS = 3_000L
private const val DOUBLE_TAP_MS = 300L

/** How long the seek's "+10s" stays up, and how long a further tap on that side keeps seeking. */
private const val SEEK_FLASH_MS = 700L

/** The share of the video's width, on each side, where a double tap seeks. The middle doesn't. */
private const val SEEK_ZONE = 0.35f

/** How far down a swipe has to pull the video to leave it. */
private val SWIPE_AWAY = 72.dp

private fun speedText(speed: Float) = "${speed.toString().removeSuffix(".0")}x"

/** A choice the overlay is offering, in place of its controls. */
private enum class Picker { Speed, Quality }

/**
 * A video's page. The video itself lives in [PlaybackController], so this page only shows it:
 * turning the phone, locking it or leaving the page doesn't stop it.
 */
@Composable
fun WatchScreen(
    services: Services,
    entry: Entry,
    route: Route.Watch,
    navigator: Navigator,
    fullScreen: Boolean,
) {
    val opening by services.playback.current.collectAsState()
    LaunchedEffect(route.id) { services.playback.open(route.id, route.startSeconds) }
    val current = opening?.takeIf { it.id == route.id }
    val watching = (current as? Opening.Ready)?.watching
    if (watching == null) {
        OpeningPage(services, route, navigator, current as? Opening.Failed, fullScreen)
        return
    }
    val state by watching.state.collectAsState()
    if (fullScreen) {
        val activity = LocalContext.current as MainActivity
        var pull by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Player(
                services, watching, state, navigator, route, fullScreen = true,
                Modifier.fillMaxSize().offset { IntOffset(0, pull.roundToInt()) },
                onPull = { pull = it },
                // As on YouTube: a swipe down in full screen leaves full screen, not the video.
                onSwipeDown = { activity.requestFullScreen(false) },
            )
        }
    } else {
        VideoPage(services, entry, route, navigator, watching, state)
    }
}

/** While YouTube answers: the frame, and whatever the list already said about the video. */
@Composable
private fun OpeningPage(services: Services, route: Route.Watch, navigator: Navigator, failed: Opening.Failed?, fullScreen: Boolean) {
    val frame = @Composable { modifier: Modifier ->
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            AsyncImage(frameUrl(route.id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            if (failed == null) Label("loading…", Type.body)
        }
    }
    if (fullScreen) {
        frame(Modifier.fillMaxSize())
        return
    }
    val preview = route.preview
    Column(Modifier.fillMaxSize()) {
        frame(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.padding(horizontal = Unit5 * 3, vertical = Unit5 * 2), verticalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
            if (failed != null) {
                Panel {
                    Label(failed.message, Type.problem)
                    Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                        ActionButton("Try again", services.playback::reload, primary = true)
                        ActionButton("Back", { services.playback.close(); navigator.back() })
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Unit5)) {
                if (preview != null) {
                    Label(preview.title, Type.h2)
                    videoMeta(preview.views, preview.uploaded).takeIf { it.isNotEmpty() }?.let { Label(it, Type.muted) }
                } else {
                    Bone(0.8f, 24.dp)
                    Bone(0.4f, 19.dp)
                }
            }
            val channel = preview?.channel?.takeIf { it.isNotEmpty() }
            if (channel != null) {
                Box(Modifier.height(Touch), contentAlignment = Alignment.CenterStart) {
                    Label(channel, Type.link, maxLines = 1)
                }
            } else {
                Bone(0.5f, Touch)
            }
        }
    }
}

/** A grey bar where text will be. */
@Composable
private fun Bone(fraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth(fraction).height(height).padding(vertical = 3.dp).background(Palette.raised, Corner))
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(watching: Watching, modifier: Modifier) {
    val state by watching.state.collectAsState()
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = {
            it.player = watching.exo
            it.keepScreenOn = state.playing
        },
        onRelease = { it.player = null },
        modifier = modifier.background(Color.Black),
    )
}

/** The video with its controls, and whatever is holding it. */
@Composable
private fun Player(
    services: Services,
    watching: Watching,
    state: WatchingState,
    navigator: Navigator,
    route: Route.Watch,
    fullScreen: Boolean,
    modifier: Modifier,
    onPull: (Float) -> Unit,
    onSwipeDown: () -> Unit,
) {
    val activity = LocalContext.current as MainActivity
    var controls by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var touched by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    // Which side the last tap landed on: -1 left, 1 right, 0 the middle, where nothing seeks.
    var lastSide by remember { mutableIntStateOf(0) }
    var controlsBeforeTap by remember { mutableStateOf(true) }
    // The seconds sought so far by taps in a row, signed, shown on the side they went.
    var sought by remember { mutableIntStateOf(0) }
    var lastSeek by remember { mutableLongStateOf(0L) }
    val seek: (Int) -> Unit = { side ->
        watching.seekBy(side * SEEK_STEP_MS)
        val step = side * (SEEK_STEP_MS / 1000).toInt()
        sought = if (sought * side > 0) sought + step else step
        lastSeek = SystemClock.uptimeMillis()
        touched++
    }
    LaunchedEffect(lastSeek) {
        if (sought == 0) return@LaunchedEffect
        delay(SEEK_FLASH_MS)
        sought = 0
    }
    val swipeAway = with(LocalDensity.current) { SWIPE_AWAY.toPx() }
    var pulled by remember { mutableFloatStateOf(0f) }
    // Pausing brings the controls up once; a tap can still put them away.
    LaunchedEffect(state.playing) { if (!state.playing) controls = true }
    LaunchedEffect(touched, state.playing, controls, picker) {
        if (!controls || !state.playing || picker != null) return@LaunchedEffect
        delay(CONTROLS_HIDE_MS)
        controls = false
    }
    Box(
        modifier.background(Color.Black).pointerInput(swipeAway) {
            // A swipe down leaves the video. It follows the finger, and lets go if it didn't go far.
            detectVerticalDragGestures(
                onDragStart = { pulled = 0f },
                onDragEnd = {
                    if (pulled > swipeAway) onSwipeDown()
                    pulled = 0f
                    onPull(0f)
                },
                onDragCancel = {
                    pulled = 0f
                    onPull(0f)
                },
                onVerticalDrag = { _, amount ->
                    pulled = (pulled + amount).coerceAtLeast(0f)
                    onPull(pulled)
                },
            )
        },
    ) {
        VideoSurface(watching, Modifier.fillMaxSize())
        val hold = state.held
        val blocking = state.ending != Ending.None || (hold != null && hold != Verdict.SignedOut)
        if (blocking && fullScreen) {
            // Full screen has no page below, so what's holding the video covers it.
            Box(Modifier.fillMaxSize().background(Palette.scrim).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 480.dp).padding(Unit5 * 4), verticalArrangement = Arrangement.spacedBy(Unit5 * 3)) {
                    HoldPanel(services, watching, state, navigator, route, null)
                }
            }
        } else if (blocking) {
            Box(Modifier.fillMaxSize().background(Palette.scrim), contentAlignment = Alignment.Center) {
                Label(holdLine(state), Type.muted)
            }
        } else {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    // The first tap answers at once. A second one soon after on the same side is
                    // a double tap, which seeks by ten seconds that way, and each further tap
                    // there seeks again. The middle of the video never seeks.
                    detectTapGestures { offset ->
                        val now = SystemClock.uptimeMillis()
                        val side = when {
                            offset.x < size.width * SEEK_ZONE -> -1
                            offset.x > size.width * (1 - SEEK_ZONE) -> 1
                            else -> 0
                        }
                        val doubleTap = side == lastSide && now - lastTap < DOUBLE_TAP_MS
                        val another = sought * side > 0 && now - lastSeek < SEEK_FLASH_MS
                        if (side != 0 && another) {
                            seek(side)
                        } else if (side != 0 && doubleTap) {
                            // The first tap was half of this, so what it did to the controls is undone.
                            controls = controlsBeforeTap
                            lastTap = 0L
                            seek(side)
                        } else {
                            controlsBeforeTap = controls
                            if (picker != null) picker = null else controls = !controls
                            lastTap = now
                            lastSide = side
                        }
                        touched++
                    }
                },
            )
            val open = picker
            if (open != null) {
                Choices(open, watching, state) {
                    picker = null
                    touched++
                }
            } else if (controls) {
                Controls(
                    services, watching, state, fullScreen,
                    onTouch = { touched++ },
                    onSeek = seek,
                    onPick = { picker = it },
                    onFullScreen = { activity.requestFullScreen(!fullScreen) },
                )
            }
            if (sought != 0 && open == null) {
                val edges = if (fullScreen) Modifier.safeDrawingPadding() else Modifier
                Label(
                    if (sought > 0) "+${sought}s" else "−${-sought}s",
                    Type.strong,
                    Modifier
                        .align(if (sought > 0) Alignment.CenterEnd else Alignment.CenterStart)
                        .then(edges)
                        .padding(horizontal = Unit5 * 4)
                        .background(Palette.scrim, Corner)
                        .padding(horizontal = Unit5 * 3, vertical = Unit5 * 2),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun holdLine(state: WatchingState): String = when {
    state.ending == Ending.Finished -> "That was the video."
    state.ending == Ending.Failed -> "Playback failed."
    (state.held as? Verdict.Refused)?.outOfTime == true -> "No time left today"
    state.held is Verdict.Refused -> "Waiting for a session"
    state.held is Verdict.Unreachable -> "Can't reach the anti-social server"
    else -> "Checking in…"
}

@Composable
private fun BoxScope.Controls(
    services: Services,
    watching: Watching,
    state: WatchingState,
    fullScreen: Boolean,
    onTouch: () -> Unit,
    onSeek: (Int) -> Unit,
    onPick: (Picker) -> Unit,
    onFullScreen: () -> Unit,
) {
    Box(Modifier.matchParentSize().background(Color(0x99000000)))
    val edges = if (fullScreen) Modifier.safeDrawingPadding() else Modifier
    // The session, quietly, and the settings for this video.
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().then(edges).padding(start = Unit5 * 3, end = Unit5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BudgetClock(services.budget, Modifier.weight(1f))
        LinkButton(speedText(state.speed), { onPick(Picker.Speed) }, style = Type.body)
        LinkButton(state.qualities.getOrNull(state.quality)?.label ?: "auto", { onPick(Picker.Quality) }, style = Type.body)
    }
    Row(
        Modifier.align(Alignment.Center),
        horizontalArrangement = Arrangement.spacedBy(Unit5 * 4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinkButton("-10s", { onSeek(-1) }, style = Type.strong)
        ActionButton(if (state.playing) "pause" else "play", { watching.toggle(); onTouch() }, primary = true)
        LinkButton("+10s", { onSeek(1) }, style = Type.strong)
    }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .then(edges)
            .padding(horizontal = Unit5 * 3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("${durationText(state.positionMs / 1000)} / ${durationText(state.durationMs / 1000)}", Type.body, maxLines = 1)
            if (state.buffering) Label("  loading…", Type.muted, maxLines = 1)
            Spacer(Modifier.weight(1f))
            LinkButton(if (fullScreen) "exit full screen" else "full screen", onFullScreen, style = Type.body)
        }
        SeekBar(state.positionMs, state.durationMs, onSeek = { watching.seekTo(it); onTouch() })
    }
}

/** Speed or quality, every option at once, on as many lines as they need. Tapping beside them keeps what was there. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.Choices(picker: Picker, watching: Watching, state: WatchingState, onDone: () -> Unit) {
    Box(Modifier.matchParentSize().background(Palette.scrim))
    Column(
        Modifier.align(Alignment.Center).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = Unit5 * 2),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(if (picker == Picker.Speed) "Speed" else "Quality", Type.strong)
        FlowRow(
            Modifier.padding(horizontal = Unit5 * 3),
            horizontalArrangement = Arrangement.spacedBy(Unit5 * 2, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
        ) {
            when (picker) {
                Picker.Speed -> SPEEDS.forEach { speed ->
                    ActionButton(speedText(speed), { watching.setSpeed(speed); onDone() }, primary = speed == state.speed)
                }
                Picker.Quality -> state.qualities.forEachIndexed { index, quality ->
                    ActionButton(quality.label, { watching.setQuality(index); onDone() }, primary = index == state.quality)
                }
            }
        }
    }
}

/** Tap or drag anywhere along it to go there. */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(Touch * 0.6f)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                detectTapGestures { onSeek((it.x / width * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragging?.let { onSeek((it * duration).toLong()) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, _ -> dragging = (change.position.x / width).coerceIn(0f, 1f) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(Unit5).background(Palette.raised, Corner)) {
            Box(Modifier.fillMaxWidth(fraction).height(Unit5).background(Palette.accent, Corner))
        }
    }
}

/** What holds the video, or how it ended, with what can be done about it. */
@Composable
private fun HoldPanel(
    services: Services,
    watching: Watching,
    state: WatchingState,
    navigator: Navigator,
    route: Route.Watch,
    next: Video?,
) {
    val budget = services.budget
    val status by budget.status.collectAsState()
    val hold = state.held
    when {
        state.ending == Ending.Failed -> Panel {
            Label(state.failure ?: "Playback failed.", Type.problem)
            Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                ActionButton("Reload", services.playback::reload, primary = true)
                ActionButton("Back", navigator::back)
            }
        }
        // The end of a video is the end. There is no next video unless you pick it.
        state.ending == Ending.Finished -> Panel {
            Label("That was the video.", Type.h2)
            status?.let { Label(afterVideoText(it), Type.muted) }
            if (next != null && status?.state == SessionState.OPEN) {
                ActionButton("Next: ${next.title}", { navigator.open(Route.Watch(next.id, route.listId, preview = next)) }, primary = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                ActionButton("Back", navigator::back)
                ActionButton("Watch again", watching::restart)
            }
        }
        hold is Verdict.Refused -> SessionGate(budget)
        hold is Verdict.Unreachable -> Panel {
            Label("Can't reach the anti-social server", Type.h2)
            Label("The video carries on once it's back.", Type.muted)
            ActionButton("Try now", watching::retryCheckIn, primary = true)
        }
        hold == Verdict.Unknown -> Panel { Label("Checking in…", Type.muted) }
    }
}

private fun afterVideoText(status: Status): String = when (status.state) {
    SessionState.OPEN -> "${videosText(status.session?.videosLeft ?: 0)} left in this session."
    SessionState.GAP -> "That's the session. The next one opens at ${status.nextSessionAt?.let(::timeOfDay).orEmpty()}"
    SessionState.NO_TIME -> "That's all your time today. It resets at ${timeOfDay(status.resetsAt)}"
    SessionState.CHOOSE -> "That's the session."
}

/** Shares the video as a YouTube link, to anything but this app. */
private fun shareVideo(context: Context, id: String, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$id")
        .putExtra(Intent.EXTRA_SUBJECT, title)
    val chooser = Intent.createChooser(send, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    context.startActivity(chooser)
}

/** Portrait: the video on top, the page below it. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoPage(
    services: Services,
    entry: Entry,
    route: Route.Watch,
    navigator: Navigator,
    watching: Watching,
    state: WatchingState,
) {
    val video = watching.streams.details
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var next by remember { mutableStateOf<Video?>(null) }
    var nextList by remember { mutableStateOf<String?>(null) }
    var subscribed by remember { mutableStateOf<Boolean?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(route.listId) {
        val listId = route.listId ?: return@LaunchedEffect
        // A missing next video only means no link to it, so a failure stays quiet.
        val playlist = runCatching { services.api.playlist(listId) }.getOrNull() ?: return@LaunchedEffect
        val index = playlist.items.indexOfFirst { it.id == route.id }
        if (index >= 0) {
            next = playlist.items.getOrNull(index + 1)
            nextList = playlist.name
        }
    }
    LaunchedEffect(video.channelId) {
        val channelId = video.channelId ?: return@LaunchedEffect
        subscribed = runCatching { services.api.subscriptions().any { it.channelId == channelId } }.getOrNull()
    }

    var pull by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize().offset { IntOffset(0, pull.roundToInt()) }) {
        Player(
            services, watching, state, navigator, route, fullScreen = false,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            onPull = { pull = it },
            // The video carries on in the mini-player.
            onSwipeDown = navigator::back,
        )
        LazyColumn(
            state = entry.list,
            contentPadding = PaddingValues(start = Unit5 * 3, end = Unit5 * 3, top = Unit5 * 2, bottom = Unit5 * 8),
            verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
            modifier = Modifier.fillMaxSize(),
        ) {
            val holding = state.ending != Ending.None || (state.held != null && state.held != Verdict.SignedOut)
            if (holding) item { HoldPanel(services, watching, state, navigator, route, next) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Unit5)) {
                    Label(video.title, Type.h2)
                    videoMeta(video.views, video.uploaded, video.likes).takeIf { it.isNotEmpty() }?.let { Label(it, Type.muted) }
                }
            }
            // The channel on the title's left edge, then everything there is to do, in one row.
            item {
                val channelId = video.channelId
                Column {
                    if (channelId != null) {
                        Tappable({ navigator.open(Route.Channel(channelId)) }, Modifier.heightIn(min = Touch)) {
                            Label(video.channel.ifEmpty { "Channel" }, Type.link, maxLines = 1)
                        }
                    } else {
                        Box(Modifier.heightIn(min = Touch), contentAlignment = Alignment.CenterStart) {
                            Label(video.channel, Type.muted, maxLines = 1)
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Unit5 * 2),
                        verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
                    ) {
                        if (channelId != null) subscribed?.let { SubscribeButton(services, channelId, video.channel, it) }
                        ActionButton("+ playlist", { adding = !adding })
                        ActionButton("share", { shareVideo(context, video.id, video.title) })
                    }
                }
            }
            if (adding) {
                item {
                    AddToPlaylist(
                        services,
                        Video(video.id, video.title, video.channel, video.channelId, video.durationSeconds.toInt()),
                    ) { adding = false }
                }
            }
            next?.let { nextVideo ->
                item {
                    LinkButton("Next in ${nextList.orEmpty()}: ${nextVideo.title}", {
                        navigator.open(Route.Watch(nextVideo.id, route.listId, preview = nextVideo))
                    })
                }
            }
            if (video.description.isNotBlank()) {
                item {
                    Section("Description") {
                        LinkedText(video.description, video, navigator) { seconds ->
                            watching.seekTo(seconds * 1000L)
                            watching.play()
                            scope.launch { entry.list.animateScrollToItem(0) }
                        }
                    }
                }
            }
            item {
                Section("Comments") {
                    Comments(services, video, navigator) { seconds ->
                        watching.seekTo(seconds * 1000L)
                        watching.play()
                        scope.launch { entry.list.animateScrollToItem(0) }
                    }
                }
            }
        }
    }
}

/**
 * The open video, in a bar above the bottom navigation while you look elsewhere. Tapping it
 * returns to its page; ✕ closes it.
 */
@Composable
fun MiniPlayer(services: Services, navigator: Navigator) {
    val opening by services.playback.current.collectAsState()
    val current = opening ?: return
    val last = navigator.lastWatch?.takeIf { it.id == current.id }
    val watching = (current as? Opening.Ready)?.watching
    val state = watching?.state?.collectAsState()?.value
    val title = watching?.streams?.details?.title ?: last?.preview?.title ?: "Opening a video…"
    val below = when {
        current is Opening.Failed -> current.message
        state == null -> "loading…"
        state.ending != Ending.None || (state.held != null && state.held != Verdict.SignedOut) -> holdLine(state)
        else -> watching.streams.details.channel
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.raised))
        Tappable(
            { navigator.open(last?.copy(startSeconds = null) ?: Route.Watch(current.id)) },
            Modifier.fillMaxWidth().height(56.dp),
            fill = Palette.surface,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).background(Color.Black)) {
                    if (watching != null) {
                        VideoSurface(watching, Modifier.fillMaxSize())
                    } else {
                        AsyncImage(frameUrl(current.id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = Unit5 * 2)) {
                    Label(title, Type.strong, maxLines = 1)
                    Label(below, if (current is Opening.Failed) Type.problem else Type.muted, maxLines = 1)
                }
                if (watching != null && state != null && state.ending == Ending.None) {
                    LinkButton(if (state.playing) "pause" else "play", watching::toggle, style = Type.strong)
                }
                LinkButton("✕", services.playback::close, Modifier.width(Touch), style = Type.strong)
            }
        }
    }
}


/** A panel that opens when its heading is tapped. Its content only loads once it's open. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Panel {
        Tappable({ open = !open }, Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = Unit5)) {
                Label(title, Type.h3, Modifier.weight(1f))
                Label(if (open) "hide" else "show", Type.link)
            }
        }
        if (open) content()
    }
}

/** Text with its links opening and its timestamps seeking, as the web app's linkified did. */
@Composable
private fun LinkedText(text: String, video: VideoDetails, navigator: Navigator, onSeek: (Int) -> Unit) {
    val context = LocalContext.current
    val styles = TextLinkStyles(SpanStyle(color = Palette.accent))
    val annotated = remember(text) {
        buildAnnotatedString {
            for (part in linkParts(text, video.durationSeconds)) {
                when (part) {
                    is TextPart.Plain -> append(part.text)
                    is TextPart.Url -> withLink(
                        LinkAnnotation.Clickable("url", styles) {
                            // A link to YouTube stays in the app.
                            val link = parseYouTubeLink(part.text)
                            if (link != null) navigator.openLink(link) else openCustomTab(context, part.text)
                        },
                    ) { append(part.text) }
                    is TextPart.Time -> withLink(LinkAnnotation.Clickable("time", styles) { onSeek(part.seconds) }) {
                        append(part.text)
                    }
                }
            }
        }
    }
    BasicText(annotated, style = Type.body)
}

@Composable
private fun Comments(services: Services, video: VideoDetails, navigator: Navigator, onSeek: (Int) -> Unit) {
    var comments by remember { mutableStateOf<List<Comment>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        try {
            comments = services.youtube.comments(video.id)
            problem = null
        } catch (e: YouTubeError) {
            problem = e.message
        } catch (e: ApiError) {
            problem = e.message
        }
    }
    val list = comments
    when {
        problem != null -> {
            Label(problem!!, Type.problem)
            ActionButton("Try again", { attempt++ })
        }
        list == null -> Label("Loading comments…", Type.muted)
        list.isEmpty() -> Label("No comments.", Type.muted)
        else -> Column(verticalArrangement = Arrangement.spacedBy(Unit5 * 3)) {
            list.forEach { comment ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                        Label(comment.author, Type.strong, maxLines = 1)
                        val meta = listOfNotNull(
                            "pinned".takeIf { comment.pinned },
                            comment.`when`.takeIf { it.isNotBlank() },
                            comment.likes.takeIf { it > 0 }?.let { "${countText(it)} likes" },
                        ).joinToString(" · ")
                        Label(meta, Type.muted, maxLines = 1)
                    }
                    LinkedText(comment.text, video, navigator, onSeek)
                }
            }
        }
    }
}
