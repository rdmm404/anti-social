package org.voltr.antisocial.phone

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
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
    data class Watch(val id: String, val listId: Int? = null, val startSeconds: Int? = null) : Route
}

/** A place in the back stack. It keeps what it loaded and where it was scrolled, so Back is instant. */
class Entry(val route: Route) {
    var data by mutableStateOf<Any?>(null)
    val list = LazyListState()
}

/** The back stack. Sections sit directly above home, so Back always leads home and then out. */
class Navigator(val stack: SnapshotStateList<Entry>) {
    val top get() = stack.last()

    fun open(route: Route) {
        // One video page at a time: opening another replaces it, wherever it was.
        if (route is Route.Watch) stack.removeAll { it.route is Route.Watch }
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

    /** Asked by the player's full screen button: turn sideways, or back to following the phone. */
    fun lockLandscape(lock: Boolean) {
        requestedOrientation = if (lock) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
    val entry = navigator.top
    val route = entry.route
    BackHandler(enabled = navigator.stack.size > 1) { navigator.back() }

    // The video lives as long as its page is in the stack, and pauses while another page covers it.
    val watchRoute = navigator.stack.lastOrNull { it.route is Route.Watch }?.route as Route.Watch?
    LaunchedEffect(watchRoute?.id) { if (watchRoute == null) services.playback.close() }
    LaunchedEffect(route is Route.Watch) { if (route !is Route.Watch) services.playback.pause() }
    val fullScreen = route is Route.Watch && landscape && !inPip
    LaunchedEffect(fullScreen) { setFullScreen(fullScreen) }

    if (route is Route.Watch) {
        key(entry) {
            WatchScreen(services, entry, route, navigator, inPip, fullScreen)
        }
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(services, route, navigator)
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

@Composable
private fun TopBar(services: Services, current: Route, navigator: Navigator) {
    val context = LocalContext.current
    val active = when (current) {
        Route.Search, is Route.SearchResults -> Route.Search
        Route.Channels, is Route.Channel -> Route.Channels
        is Route.Playlists, is Route.PlaylistPage -> Route.Playlists()
        Route.History -> Route.History
        is Route.Watch -> null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Unit5 * 3)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("anti-social", Type.strong, maxLines = 1)
            Spacer(Modifier.width(Unit5 * 2))
            BudgetClock(services.budget, Modifier.weight(1f))
            // The server's settings page: limits, devices and the week, in the browser's session.
            LinkButton("settings", { openCustomTab(context, BuildConfig.SERVER_URL) }, style = Type.muted)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Unit5),
        ) {
            listOf(
                Route.Search to "search",
                Route.Channels to "channels",
                Route.Playlists() to "playlists",
                Route.History to "history",
            ).forEach { (route, name) ->
                LinkButton(
                    name,
                    { navigator.openSection(route) },
                    style = if (route == active) Type.strong.copy(color = Palette.accent) else Type.muted,
                )
            }
        }
    }
}
