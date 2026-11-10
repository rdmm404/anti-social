package org.voltr.antisocial.phone

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.Link
import org.voltr.antisocial.core.SessionState
import org.voltr.antisocial.core.Video
import org.voltr.antisocial.core.parseYouTubeLink
import org.voltr.antisocial.core.sharedLink

sealed interface Route {
    data object Search : Route
    data class SearchResults(val query: String) : Route
    data object Channels : Route
    data class Channel(val id: String) : Route
    /** [importing] is a YouTube playlist id to offer to import, from a link. */
    data class Playlists(val importing: String? = null) : Route
    data class PlaylistPage(val id: Int) : Route
    data object History : Route
    /** [preview] is what the list already knew about the video, shown while it opens. */
    data class Watch(
        val id: String,
        val listId: Int? = null,
        val startSeconds: Int? = null,
        val preview: Video? = null,
    ) : Route
}

/** A place in the back stack. It keeps what it loaded and where it was scrolled, so Back is instant. */
class Entry(val route: Route) {
    var data by mutableStateOf<Any?>(null)
    val list = LazyListState()
}

/** The back stack. Sections sit directly above home, so Back always leads home and then out. */
class Navigator(val stack: SnapshotStateList<Entry>) {
    val top get() = stack.last()

    /** The video page opened last, so the mini-player can lead back to it. */
    var lastWatch by mutableStateOf<Route.Watch?>(null)
        private set

    fun open(route: Route) {
        // One video page at a time: opening another replaces it, wherever it was.
        if (route is Route.Watch) {
            stack.removeAll { it.route is Route.Watch }
            lastWatch = route
        }
        stack.add(Entry(route))
    }

    fun openSection(route: Route) {
        val home = stack.first()
        stack.clear()
        stack.add(home)
        if (route != Route.Search) stack.add(Entry(route))
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun openLink(link: Link) = when (link) {
        is Link.Video -> open(Route.Watch(link.id, startSeconds = link.startSeconds))
        is Link.Channel -> open(Route.Channel(link.id))
        is Link.Playlist -> open(Route.Playlists(importing = link.id))
        is Link.Search -> open(Route.SearchResults(link.query))
    }
}

class MainActivity : ComponentActivity() {
    private val services by lazy { applicationContext.services }
    private val navigator = Navigator(mutableStateListOf(Entry(Route.Search)))
    private var inPip by mutableStateOf(false)
    private var landscape by mutableStateOf(false)

    /** The way the full screen button turned the screen, until the phone itself agrees: true sideways, false upright. */
    private var turning: Boolean? = null
    private val tilt by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val sideways = degrees in 60..120 || degrees in 240..300
                val upright = degrees <= 30 || degrees >= 330
                when (turning) {
                    true -> if (sideways) followSensor()
                    false -> if (upright) followSensor()
                    null -> disable()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15+ draws behind the system bars regardless, so the content pads itself. The app
        // is always dark, so the bars keep light icons even when the phone is in light mode.
        val bars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (savedInstanceState == null) handle(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (services.signedIn.value) services.budget.refresh()
                    delay(STATUS_POLL_MS)
                }
            }
        }
        setContent {
            Root(services, navigator, inPip, landscape, ::setFullScreen)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        tilt.disable()
        super.onDestroy()
    }

    /** A YouTube link tapped elsewhere, or text shared with the app. */
    private fun handle(intent: Intent?) {
        val link = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(::parseYouTubeLink)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::sharedLink)
            else -> null
        } ?: return
        navigator.openLink(link)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /** Full screen hides the system bars; the player asks for it in landscape. */
    private fun setFullScreen(full: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (full) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * The player's full screen button. It turns the screen whichever way the phone is held, and
     * holds it there until the phone is turned to match, then follows the phone again: turning
     * back upright leaves full screen, as on YouTube. With auto-rotate off it holds until asked again.
     */
    fun requestFullScreen(full: Boolean) {
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val autoRotate = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
        if (autoRotate && tilt.canDetectOrientation()) {
            turning = full
            tilt.enable()
        } else {
            turning = null
        }
    }

    /** Back to following the phone, as every page but the video's does. */
    fun followSensor() {
        turning = null
        tilt.disable()
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** Leaving the app while a video plays shrinks it to picture-in-picture. */
    fun allowPip(playing: Boolean) {
        setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setAutoEnterEnabled(playing)
                .setSeamlessResizeEnabled(true)
                .build(),
        )
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // Closing the window stops the activity without resuming it: that's stopping the video.
        if (!isInPictureInPictureMode) {
            lifecycleScope.launch {
                delay(500)
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) services.playback.pause()
            }
        }
    }

    private companion object {
        const val STATUS_POLL_MS = 5_000L
    }
}

/** Pairing comes first: without a device token the server answers nothing. */
@Composable
private fun Root(
    services: Services,
    navigator: Navigator,
    inPip: Boolean,
    landscape: Boolean,
    setFullScreen: (Boolean) -> Unit,
) {
    val signedIn by services.signedIn.collectAsState()
    val signedOut by services.budget.signedOut.collectAsState()
    Box(Modifier.fillMaxSize().background(Palette.background)) {
        if (!signedIn || signedOut) {
            LaunchedEffect(signedOut) { if (signedOut) services.signOut() }
            SignInScreen(services)
        } else {
            App(services, navigator, inPip, landscape, setFullScreen)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun App(
    services: Services,
    navigator: Navigator,
    inPip: Boolean,
    landscape: Boolean,
    setFullScreen: (Boolean) -> Unit,
) {
    val budget = services.budget
    val status by budget.status.collectAsState()
    val activity = LocalContext.current as MainActivity
    val entry = navigator.top
    val route = entry.route
    val onWatch = route is Route.Watch
    BackHandler(enabled = navigator.stack.size > 1) { navigator.back() }

    // The video plays on wherever you go, in the mini-player, until it's closed or another opens.
    val opening by services.playback.current.collectAsState()
    val watching = (opening as? Opening.Ready)?.watching
    if (watching != null) {
        key(watching) {
            val state by watching.state.collectAsState()
            LaunchedEffect(state.playing, state.held) { activity.allowPip(state.playing && state.held == null) }
        }
    } else {
        LaunchedEffect(Unit) { activity.allowPip(false) }
    }
    LaunchedEffect(onWatch) { if (!onWatch) activity.followSensor() }
    val fullScreen = onWatch && landscape && !inPip
    LaunchedEffect(fullScreen) { setFullScreen(fullScreen) }

    if (inPip) {
        if (watching != null) {
            VideoSurface(watching, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
        }
        return
    }
    if (fullScreen) {
        key(entry) { WatchScreen(services, entry, route as Route.Watch, navigator, fullScreen = true) }
        return
    }

    // The keyboard covers the bottom bar, as it would on YouTube, rather than pushing it up.
    val typing = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (!onWatch) TopBar(services, navigator)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (route is Route.Watch) {
                key(entry) { WatchScreen(services, entry, route, navigator, fullScreen = false) }
            } else {
                val state = status?.state
                // Browsing only exists inside a session, and the page returns when one opens.
                val gated = route != Route.History && state != null && state != SessionState.OPEN
                Box(Modifier.fillMaxSize().padding(horizontal = Unit5 * 3)) {
                    when {
                        gated -> SessionGate(budget, Modifier.padding(top = Unit5 * 2))
                        // Nothing yet, rather than a search box that may be about to vanish.
                        route == Route.Search && state == null -> Unit
                        else -> key(entry) {
                            when (route) {
                                Route.Search -> HomeScreen(services, navigator)
                                is Route.SearchResults -> SearchResultsScreen(services, entry, route.query, navigator)
                                Route.Channels -> ChannelsScreen(services, entry, navigator)
                                is Route.Channel -> ChannelScreen(services, entry, route.id, navigator)
                                is Route.Playlists -> PlaylistsScreen(services, entry, route.importing, navigator)
                                is Route.PlaylistPage -> PlaylistScreen(services, entry, route.id, navigator)
                                Route.History -> HistoryScreen(services, entry, navigator)
                                is Route.Watch -> Unit
                            }
                        }
                    }
                }
            }
        }
        if (!typing) {
            if (!onWatch && opening != null) MiniPlayer(services, navigator)
            BottomNav(route, navigator)
        }
    }
}

/** The icon, the time left in the middle, and the server's settings page. */
@Composable
private fun TopBar(services: Services, navigator: Navigator) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().height(Touch).padding(horizontal = Unit5 * 2)) {
        Tappable({ navigator.openSection(Route.Search) }, Modifier.align(Alignment.CenterStart).size(Touch), contentAlignment = Alignment.Center) {
            Image(painterResource(R.mipmap.ic_launcher), contentDescription = "anti-social", modifier = Modifier.size(28.dp).clip(Corner))
        }
        BudgetClock(services.budget, Modifier.align(Alignment.Center))
        // Limits, devices and the week, in the browser's session.
        LinkButton("settings", { openCustomTab(context, BuildConfig.SERVER_URL) }, Modifier.align(Alignment.CenterEnd), style = Type.muted)
    }
}

/** The four sections, at the bottom where a thumb reaches them. */
@Composable
private fun BottomNav(current: Route, navigator: Navigator) {
    val active = when (current) {
        Route.Search, is Route.SearchResults -> Route.Search
        Route.Channels, is Route.Channel -> Route.Channels
        is Route.Playlists, is Route.PlaylistPage -> Route.Playlists()
        Route.History -> Route.History
        is Route.Watch -> null
    }
    Column(Modifier.fillMaxWidth().background(Palette.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.raised))
        Row(Modifier.fillMaxWidth()) {
            listOf(
                Route.Search to "search",
                Route.Channels to "channels",
                Route.Playlists() to "playlists",
                Route.History to "history",
            ).forEach { (route, name) ->
                Tappable(
                    { navigator.openSection(route) },
                    Modifier.weight(1f).height(Touch + Unit5 * 2),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(name, if (route == active) Type.strong.copy(color = Palette.accent) else Type.muted, maxLines = 1)
                }
            }
        }
    }
}
