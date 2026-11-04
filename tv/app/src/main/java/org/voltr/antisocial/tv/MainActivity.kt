package org.voltr.antisocial.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val api = Api()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(api) }
    }
}

sealed interface Route {
    data object Search : Route
    data class SearchResults(val query: String) : Route
    data object Channels : Route
    data class Channel(val id: String) : Route
    data object Playlists : Route
    data class PlaylistPage(val id: Int) : Route
    data object History : Route
    data class Watch(val id: String, val listId: Int? = null) : Route
}

/** A place in the back stack. It keeps what it loaded and where focus was, so Back is instant. */
class Entry(val route: Route) {
    var data by mutableStateOf<Any?>(null)
    var focusIndex = 0
}

/** Budget state, kept fresh from the server for every screen. */
class Budget(private val api: Api, private val scope: CoroutineScope) {
    var status by mutableStateOf<Status?>(null)
        private set
    var serverDown by mutableStateOf(false)
        private set
    var unlockError by mutableStateOf<String?>(null)
        private set

    suspend fun refresh() {
        try {
            status = api.status()
            serverDown = false
        } catch (_: ApiError) {
            serverDown = true
        }
    }

    fun unlock() {
        scope.launch {
            unlockError = try {
                api.unlock()
                null
            } catch (e: ApiError) {
                e.message
            }
            refresh()
        }
    }
}

@Composable
private fun App(api: Api) {
    val scope = rememberCoroutineScope()
    val budget = remember { Budget(api, scope) }
    val stack = remember { mutableStateListOf(Entry(Route.Search)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                budget.refresh()
                delay(STATUS_POLL_MS)
            }
        }
    }

    val open: (Route) -> Unit = { stack.add(Entry(it)) }
    // Sections sit directly above home, so Back always leads home and then out of the app.
    val openSection: (Route) -> Unit = { route ->
        val home = stack.first()
        stack.clear()
        stack.add(home)
        if (route != Route.Search) stack.add(Entry(route))
    }
    // The home search box sends Up here; left to geometry, its width lands on the wrong tab.
    val searchTab = remember { FocusRequester() }
    val entry = stack.last()
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        val route = entry.route
        if (route is Route.Watch) {
            androidx.compose.runtime.key(entry) {
                PlayerScreen(
                    api = api,
                    budget = budget,
                    entry = entry,
                    route = route,
                    onBack = { stack.removeAt(stack.lastIndex) },
                    onNext = { next ->
                        stack.removeAt(stack.lastIndex)
                        open(next)
                    },
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // Outside key(entry): recreating the bar would drop focus from the tab just pressed.
                TopBar(route, budget, searchTab, openSection)
                Box(Modifier.fillMaxSize().padding(horizontal = Unit5 * 8)) {
                    androidx.compose.runtime.key(entry) {
                        when (route) {
                            Route.Search -> HomeScreen(budget, searchTab, open)
                            is Route.SearchResults -> SearchResultsScreen(api, budget, entry, route.query, open)
                            Route.Channels -> ChannelsScreen(api, budget, entry, open)
                            is Route.Channel -> ChannelScreen(api, budget, entry, route.id, open)
                            Route.Playlists -> PlaylistsScreen(api, budget, entry, open)
                            is Route.PlaylistPage -> PlaylistScreen(api, budget, entry, route.id, open)
                            Route.History -> HistoryScreen(api, budget, entry, open)
                            is Route.Watch -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(current: Route, budget: Budget, searchTab: FocusRequester, openSection: (Route) -> Unit) {
    val active = when (current) {
        Route.Search, is Route.SearchResults -> Route.Search
        Route.Channels, is Route.Channel -> Route.Channels
        Route.Playlists, is Route.PlaylistPage -> Route.Playlists
        Route.History -> Route.History
        is Route.Watch -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Unit5 * 8, vertical = Unit5 * 3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Unit5),
    ) {
        Label("anti-social", Type.strong, maxLines = 1)
        Spacer(Modifier.width(Unit5 * 4))
        listOf(
            Route.Search to "search",
            Route.Channels to "channels",
            Route.Playlists to "playlists",
            Route.History to "history",
        ).forEach { (route, name) ->
            Focusable({ openSection(route) }, if (route == Route.Search) Modifier.focusRequester(searchTab) else Modifier) {
                Label(
                    name,
                    if (route == active) Type.strong.copy(color = Palette.accent) else Type.muted,
                    Modifier.padding(horizontal = Unit5 * 2, vertical = Unit5),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        BudgetClock(budget.status, budget.serverDown)
    }
}

private const val STATUS_POLL_MS = 5_000L
