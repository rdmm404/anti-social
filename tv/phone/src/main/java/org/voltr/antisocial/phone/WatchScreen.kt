package org.voltr.antisocial.phone

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.countText
import org.voltr.antisocial.core.ApiError
import org.voltr.antisocial.core.Comment
import org.voltr.antisocial.core.SessionState
import org.voltr.antisocial.core.Status
import org.voltr.antisocial.core.TextPart
import org.voltr.antisocial.core.Verdict
import org.voltr.antisocial.core.Video
import org.voltr.antisocial.core.VideoDetails
import org.voltr.antisocial.core.YouTubeError
import org.voltr.antisocial.core.durationText
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

private fun speedText(speed: Float) = "${speed.toString().removeSuffix(".0")}x"

/**
 * A video's page. The video itself lives in [PlaybackController], so this page only shows it:
 * turning the phone, locking it or leaving the app doesn't stop it.
 */
@Composable
fun WatchScreen(
    services: Services,
    entry: Entry,
    route: Route.Watch,
    navigator: Navigator,
    inPip: Boolean,
    fullScreen: Boolean,
) {
    val opening by services.playback.current.collectAsState()
    LaunchedEffect(route.id) { services.playback.open(route.id, route.startSeconds) }
    val current = opening?.takeIf { it.id == route.id }

    when (current) {
        null, is Opening.Loading -> Message(fullScreen) { Label("Loading video…", Type.muted) }
        is Opening.Failed -> Message(fullScreen) {
            Label(current.message, Type.problem)
            Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                ActionButton("Try again", services.playback::reload, primary = true)
                ActionButton("Back", navigator::back)
            }
        }
        is Opening.Ready -> {
            val watching = current.watching
            val state by watching.state.collectAsState()
            val activity = LocalContext.current as MainActivity
            LaunchedEffect(state.playing, state.held) { activity.allowPip(state.playing && state.held == null) }
            when {
                inPip -> VideoSurface(watching, state, Modifier.fillMaxSize())
                fullScreen -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Player(services, watching, state, navigator, route, fullScreen = true, Modifier.fillMaxSize())
                }
                else -> VideoPage(services, entry, route, navigator, watching, state)
            }
        }
    }
}

@Composable
private fun Message(fullScreen: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().then(if (fullScreen) Modifier else Modifier.safeDrawingPadding()).padding(Unit5 * 4),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(watching: Watching, state: WatchingState, modifier: Modifier) {
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
) {
    val activity = LocalContext.current as MainActivity
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    LaunchedEffect(touched, state.playing) {
        if (!state.playing) {
            controls = true
            return@LaunchedEffect
        }
        delay(CONTROLS_HIDE_MS)
        controls = false
    }
    Box(modifier.background(Color.Black)) {
        VideoSurface(watching, state, Modifier.fillMaxSize())
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
                    detectTapGestures(
                        onTap = {
                            controls = !controls
                            touched++
                        },
                        onDoubleTap = { offset ->
                            watching.seekBy(if (offset.x < size.width / 2) -SEEK_STEP_MS else SEEK_STEP_MS)
                            touched++
                        },
                    )
                },
            )
            if (controls) {
                Controls(watching, state, fullScreen, onTouch = { touched++ }, onFullScreen = { activity.lockLandscape(!fullScreen) })
            }
        }
    }
}

private fun holdLine(state: WatchingState): String = when {
    state.ending == Ending.Finished -> "That was the video."
    state.ending == Ending.Failed -> "Playback failed."
    state.held is Verdict.Refused -> "Waiting for a session"
    state.held is Verdict.Unreachable -> "Can't reach the anti-social server"
    else -> "Checking in…"
}

@Composable
private fun BoxScope.Controls(
    watching: Watching,
    state: WatchingState,
    fullScreen: Boolean,
    onTouch: () -> Unit,
    onFullScreen: () -> Unit,
) {
    Box(Modifier.matchParentSize().background(Color(0x99000000)))
    Row(
        Modifier.align(Alignment.Center),
        horizontalArrangement = Arrangement.spacedBy(Unit5 * 4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinkButton("-10s", { watching.seekBy(-SEEK_STEP_MS); onTouch() }, style = Type.strong)
        ActionButton(if (state.playing) "pause" else "play", { watching.toggle(); onTouch() }, primary = true)
        LinkButton("+10s", { watching.seekBy(SEEK_STEP_MS); onTouch() }, style = Type.strong)
    }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .then(if (fullScreen) Modifier.safeDrawingPadding() else Modifier)
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
                ActionButton("Next: ${next.title}", { navigator.open(Route.Watch(next.id, route.listId)) }, primary = true)
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

/** Portrait: the video on top, the page below it. */
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

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Player(services, watching, state, navigator, route, fullScreen = false, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        LazyColumn(
            state = entry.list,
            contentPadding = PaddingValues(start = Unit5 * 3, end = Unit5 * 3, top = Unit5 * 2, bottom = Unit5 * 12),
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
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    val channelId = video.channelId
                    if (channelId != null) {
                        LinkButton(video.channel.ifEmpty { "Channel" }, { navigator.open(Route.Channel(channelId)) }, Modifier.weight(1f))
                        subscribed?.let { SubscribeButton(services, channelId, video.channel, it) }
                    } else {
                        Label(video.channel, Type.muted, Modifier.weight(1f))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(speedText(state.speed), {
                        watching.setSpeed(SPEEDS[(SPEEDS.indexOf(state.speed) + 1) % SPEEDS.size])
                    })
                    ActionButton(state.qualities.getOrNull(state.quality)?.label ?: "auto", {
                        watching.setQuality((state.quality + 1) % state.qualities.size)
                    })
                    ActionButton("+ playlist", { adding = !adding })
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
            item { BudgetClock(services.budget) }
            next?.let { nextVideo ->
                item {
                    LinkButton("Next in ${nextList.orEmpty()}: ${nextVideo.title}", {
                        navigator.open(Route.Watch(nextVideo.id, route.listId))
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
