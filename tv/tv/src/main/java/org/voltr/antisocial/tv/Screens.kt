package org.voltr.antisocial.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.ApiError
import org.voltr.antisocial.core.ChannelPage
import org.voltr.antisocial.core.Video
import org.voltr.antisocial.core.YouTubeError
import org.voltr.antisocial.core.durationText
import org.voltr.antisocial.core.frameUrl
import org.voltr.antisocial.core.minutesText
import org.voltr.antisocial.core.videosText

/** What a screen is showing while it talks to the server or YouTube. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String, val unreachable: Boolean) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit)

/**
 * Loads once per back stack entry, so coming Back doesn't search YouTube again. A screen
 * that couldn't reach the server loads itself once it's back, as after the TV wakes.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoad(entry: Entry, budget: Budget, load: suspend () -> T): Loader<T> {
    val state = (entry.data as? Load<T>) ?: Load.Loading
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(budget.serverDown) {
        val current = entry.data
        if (current is Load.Failed && current.unreachable && !budget.serverDown) attempt++
    }
    LaunchedEffect(entry, attempt) {
        if (entry.data is Load.Ready<*>) return@LaunchedEffect
        entry.data = Load.Loading
        entry.data = try {
            Load.Ready(load())
        } catch (e: ApiError) {
            Load.Failed(e.message ?: "Something went wrong.", e.unreachable)
        } catch (e: YouTubeError) {
            Load.Failed(e.message ?: "YouTube wouldn't give us this.", false)
        }
    }
    return Loader(state) { attempt++ }
}

/** Renders loading, failure and refusal the same way on every screen. */
@Composable
private fun <T> Loaded(loader: Loader<T>, label: String, content: @Composable (T) -> Unit) {
    when (val state = loader.state) {
        Load.Loading -> Label(label, Type.muted, Modifier.padding(top = Unit5 * 4))
        is Load.Failed -> Column(
            Modifier.padding(top = Unit5 * 4),
            verticalArrangement = Arrangement.spacedBy(Unit5 * 3),
        ) {
            Label(state.message, Type.problem)
            val focus = remember { FocusRequester() }
            ActionButton("Try again", loader.retry, Modifier.focusRequester(focus))
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        is Load.Ready -> content(state.value)
    }
}

@Composable
fun HomeScreen(budget: Budget, searchTab: FocusRequester, open: (Route) -> Unit) {
    val status = budget.status
    Column(verticalArrangement = Arrangement.spacedBy(Unit5 * 4), modifier = Modifier.fillMaxWidth().padding(top = Unit5 * 4)) {
        SearchBox(searchTab) { query -> open(Route.SearchResults(query)) }
        val session = status?.session ?: return@Column
        Panel {
            Label("${videosText(session.videosLeft)} left", Type.h1)
            BudgetMeter(status)
            Label("${minutesText(status.remainingSeconds)} left today. Resets at ${timeOfDay(status.resetsAt)}", Type.muted)
            if (session.samplesLeft > 0) {
                Label(
                    "Leave a video within ${status.sampleSeconds} seconds and it won't count (${session.samplesLeft} left).",
                    Type.muted,
                )
            }
        }
    }
}

/**
 * Landing on a text field would throw the on-screen keyboard over everything, so the box is a
 * button until it's pressed. Back or submitting leaves the field and returns to the button.
 */
@Composable
private fun SearchBox(tab: FocusRequester, onSearch: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val button = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun stopEditing() {
        keyboard?.hide()
        editing = false
    }

    if (editing) {
        BackHandler { stopEditing() }
        // Leaving the app or the field ends editing, so coming back lands on the button again
        // rather than on an empty field that has lost focus to the tabs.
        LifecycleStartEffect(Unit) { onStopOrDispose { editing = false } }
        var hadFocus by remember { mutableStateOf(false) }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = Type.body,
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                val query = text.trim()
                stopEditing()
                if (query.isNotEmpty()) onSearch(query)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .onFocusChanged {
                    if (hadFocus && !it.isFocused) stopEditing()
                    hadFocus = it.isFocused
                }
                .background(Palette.raised, Corner)
                .padding(Unit5 * 3),
        )
        LaunchedEffect(Unit) {
            field.requestFocus()
            keyboard?.show()
        }
    } else {
        Focusable(
            { editing = true },
            Modifier.fillMaxWidth().focusProperties { up = tab }.focusRequester(button),
            fill = Palette.surface,
        ) {
            Label(
                text.ifEmpty { "What did you come here for?" },
                if (text.isEmpty()) Type.muted else Type.body,
                Modifier.padding(Unit5 * 3),
                maxLines = 1,
            )
        }
        LaunchedEffect(Unit) { button.requestFocus() }
    }
}

@Composable
fun SearchResultsScreen(services: Services, entry: Entry, query: String, open: (Route) -> Unit) {
    val state = rememberLoad(entry, services.budget) { services.youtube.search(query) }
    Column(Modifier.fillMaxSize()) {
        Label(query, Type.h2, Modifier.padding(vertical = Unit5 * 2), maxLines = 1)
        Loaded(state, "Searching…") { VideoGrid(it, entry, open) }
    }
}

@Composable
fun HistoryScreen(services: Services, entry: Entry, open: (Route) -> Unit) {
    val state = rememberLoad(entry, services.budget) { services.api.history() }
    Column(Modifier.fillMaxSize()) {
        Label("History", Type.h2, Modifier.padding(vertical = Unit5 * 2))
        Loaded(state, "Loading…") { VideoGrid(it, entry, open, empty = "Nothing watched yet.") }
    }
}

@Composable
fun ChannelsScreen(services: Services, entry: Entry, open: (Route) -> Unit) {
    val state = rememberLoad(entry, services.budget) { services.api.subscriptions() }
    Column(Modifier.fillMaxSize()) {
        Label("Channels", Type.h2, Modifier.padding(vertical = Unit5 * 2))
        Loaded(state, "Loading…") { channels ->
            TextGrid(
                items = channels,
                entry = entry,
                empty = "No channels yet. Open a channel and subscribe.",
                text = { it.name },
                detail = { null },
                onClick = { open(Route.Channel(it.channelId)) },
            )
        }
    }
}

@Composable
fun ChannelScreen(services: Services, entry: Entry, id: String, open: (Route) -> Unit) {
    class Page(val channel: ChannelPage, val subscribed: Boolean)

    val api = services.api
    val state = rememberLoad(entry, services.budget) {
        coroutineScope {
            val channel = async { services.youtube.channel(id) }
            val subscribed = async { api.subscriptions().any { it.channelId == id } }
            Page(channel.await(), subscribed.await())
        }
    }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Loaded(state, "Loading channel…") { page ->
            var subscribed by remember { mutableStateOf(page.subscribed) }
            var problem by remember { mutableStateOf<String?>(null) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = Unit5 * 2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Unit5 * 4),
            ) {
                // On the left, above the cards: D-pad Up only reaches what overlaps horizontally.
                ActionButton(if (subscribed) "Unsubscribe" else "Subscribe", {
                    scope.launch {
                        try {
                            if (subscribed) api.unsubscribe(id) else api.subscribe(id, page.channel.name)
                            subscribed = !subscribed
                            problem = null
                        } catch (e: ApiError) {
                            problem = e.message
                        }
                    }
                })
                Label(page.channel.name, Type.h2, Modifier.weight(1f), maxLines = 1)
                if (problem != null) Label(problem!!, Type.problem, maxLines = 1)
            }
            VideoGrid(page.channel.videos, entry, open)
        }
    }
}

@Composable
fun PlaylistsScreen(services: Services, entry: Entry, open: (Route) -> Unit) {
    val state = rememberLoad(entry, services.budget) { services.api.playlists() }
    Column(Modifier.fillMaxSize()) {
        Label("Playlists", Type.h2, Modifier.padding(vertical = Unit5 * 2))
        Loaded(state, "Loading…") { playlists ->
            TextGrid(
                items = playlists,
                entry = entry,
                empty = "No playlists yet.",
                text = { it.name },
                detail = { "${it.count} ${if (it.count == 1) "video" else "videos"}" },
                onClick = { open(Route.PlaylistPage(it.id)) },
            )
        }
    }
}

@Composable
fun PlaylistScreen(services: Services, entry: Entry, id: Int, open: (Route) -> Unit) {
    val state = rememberLoad(entry, services.budget) { services.api.playlist(id) }
    Column(Modifier.fillMaxSize()) {
        Loaded(state, "Loading…") { playlist ->
            Label(playlist.name, Type.h2, Modifier.padding(vertical = Unit5 * 2), maxLines = 1)
            VideoGrid(playlist.items, entry, open, listId = id, empty = "Nothing here yet.")
        }
    }
}

@Composable
private fun VideoGrid(
    videos: List<Video>,
    entry: Entry,
    open: (Route) -> Unit,
    listId: Int? = null,
    empty: String = "Nothing found.",
) {
    if (videos.isEmpty()) {
        Label(empty, Type.muted, Modifier.padding(top = Unit5 * 2))
        return
    }
    val restored = remember { FocusRequester() }
    val start = remember { entry.focusIndex.coerceIn(0, videos.lastIndex) }
    val grid = rememberLazyGridState(start)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = grid,
        contentPadding = PaddingValues(bottom = Unit5 * 8),
        horizontalArrangement = Arrangement.spacedBy(Unit5 * 2),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
            Focusable(
                { open(Route.Watch(video.id, listId)) },
                Modifier
                    .then(if (index == start) Modifier.focusRequester(restored) else Modifier)
                    .onFocusChanged { if (it.isFocused) entry.focusIndex = index },
            ) {
                VideoRow(video, frameUrl(video.id))
            }
        }
    }
    LaunchedEffect(Unit) { restored.requestFocus() }
}

@Composable
private fun VideoRow(video: Video, thumbnail: String) {
    Row(Modifier.padding(Unit5), horizontalArrangement = Arrangement.spacedBy(Unit5 * 3)) {
        Box(Modifier.width(145.dp).aspectRatio(16f / 9f).background(Palette.raised, Corner)) {
            AsyncImage(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            video.duration?.let {
                Label(
                    durationText(it.toLong()),
                    Type.body,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .background(Palette.background, Corner)
                        .padding(horizontal = Unit5),
                    maxLines = 1,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Unit5)) {
            Label(video.title, Type.strong, maxLines = 2)
            video.channel?.takeIf { it.isNotEmpty() }?.let { Label(it, Type.muted, maxLines = 1) }
            video.note?.let { Label(it, Type.muted, maxLines = 1) }
        }
    }
}

@Composable
private fun <T> TextGrid(
    items: List<T>,
    entry: Entry,
    empty: String,
    text: (T) -> String,
    detail: (T) -> String?,
    onClick: (T) -> Unit,
) {
    if (items.isEmpty()) {
        Label(empty, Type.muted, Modifier.padding(top = Unit5 * 2))
        return
    }
    val restored = remember { FocusRequester() }
    val start = remember { entry.focusIndex.coerceIn(0, items.lastIndex) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = rememberLazyGridState(start),
        contentPadding = PaddingValues(bottom = Unit5 * 8),
        horizontalArrangement = Arrangement.spacedBy(Unit5 * 2),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items) { index, item ->
            Focusable(
                { onClick(item) },
                Modifier
                    .then(if (index == start) Modifier.focusRequester(restored) else Modifier)
                    .onFocusChanged { if (it.isFocused) entry.focusIndex = index },
                fill = Palette.surface,
            ) {
                Column(Modifier.padding(Unit5 * 3)) {
                    Label(text(item), Type.strong, maxLines = 1)
                    detail(item)?.let { Label(it, Type.muted, maxLines = 1) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { restored.requestFocus() }
}
