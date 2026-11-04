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
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** What a screen is showing while it talks to the server. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String, val unreachable: Boolean) : Load<Nothing>
    data object OutOfTime : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit)

/**
 * Loads once per back stack entry, so coming Back doesn't search YouTube again. A screen
 * stopped by something outside its control loads itself once that clears: time opening up
 * after an unlock, or the server coming back after the TV wakes.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoad(entry: Entry, budget: Budget, load: suspend () -> T): Loader<T> {
    val state = (entry.data as? Load<T>) ?: Load.Loading
    var attempt by remember { mutableIntStateOf(0) }
    val canWatch = budget.status?.canWatch == true
    LaunchedEffect(canWatch, budget.serverDown) {
        val current = entry.data
        val cleared = (current is Load.OutOfTime && canWatch) ||
            (current is Load.Failed && current.unreachable && !budget.serverDown)
        if (cleared) attempt++
    }
    LaunchedEffect(entry, attempt) {
        if (entry.data is Load.Ready<*>) return@LaunchedEffect
        entry.data = Load.Loading
        entry.data = try {
            Load.Ready(load())
        } catch (e: ApiError) {
            if (e.outOfTime) Load.OutOfTime else Load.Failed(e.message ?: "Something went wrong.", e.code == 0)
        }
    }
    return Loader(state) { attempt++ }
}

/** Renders loading, failure and out-of-time the same way on every screen. */
@Composable
private fun <T> Loaded(loader: Loader<T>, budget: Budget, label: String, content: @Composable (T) -> Unit) {
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
        Load.OutOfTime -> OutOfTimePanel(budget, Modifier.padding(top = Unit5 * 4))
        is Load.Ready -> content(state.value)
    }
}

@Composable
fun HomeScreen(budget: Budget, searchTab: FocusRequester, open: (Route) -> Unit) {
    val status = budget.status
    Column(verticalArrangement = Arrangement.spacedBy(Unit5 * 4), modifier = Modifier.fillMaxWidth().padding(top = Unit5 * 4)) {
        if (status != null && !status.canWatch) {
            OutOfTimePanel(budget)
            return@Column
        }
        SearchBox(searchTab) { query -> open(Route.SearchResults(query)) }
        if (status != null) {
            Panel {
                Label("${minutesText(status.remainingSeconds)} left today", Type.h1)
                BudgetMeter(status)
                Label("Resets at ${timeOfDay(status.resetsAt)}.", Type.muted)
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
fun SearchResultsScreen(api: Api, budget: Budget, entry: Entry, query: String, open: (Route) -> Unit) {
    val state = rememberLoad(entry, budget) { api.search(query) }
    Column(Modifier.fillMaxSize()) {
        Label(query, Type.h2, Modifier.padding(vertical = Unit5 * 2), maxLines = 1)
        Loaded(state, budget, "Searching…") { VideoGrid(api, it, entry, open) }
    }
}

@Composable
fun HistoryScreen(api: Api, budget: Budget, entry: Entry, open: (Route) -> Unit) {
    val state = rememberLoad(entry, budget) { api.history() }
    Column(Modifier.fillMaxSize()) {
        Label("History", Type.h2, Modifier.padding(vertical = Unit5 * 2))
        Loaded(state, budget, "Loading…") { VideoGrid(api, it, entry, open, empty = "Nothing watched yet.") }
    }
}

@Composable
fun ChannelsScreen(api: Api, budget: Budget, entry: Entry, open: (Route) -> Unit) {
    val state = rememberLoad(entry, budget) { api.subscriptions() }
    Column(Modifier.fillMaxSize()) {
        Label("Channels", Type.h2, Modifier.padding(vertical = Unit5 * 2))
        Loaded(state, budget, "Loading…") { channels ->
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
fun ChannelScreen(api: Api, budget: Budget, entry: Entry, id: String, open: (Route) -> Unit) {
    class Page(val channel: ChannelPage, val subscribed: Boolean)

    val state = rememberLoad(entry, budget) {
        Page(api.channel(id), api.subscriptions().any { it.channelId == id })
    }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Loaded(state, budget, "Loading channel…") { page ->
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
            VideoGrid(api, page.channel.results, entry, open)
        }
    }
}

@Composable
fun PlaylistsScreen(api: Api, budget: Budget, entry: Entry, open: (Route) -> Unit) {
    val state = rememberLoad(entry, budget) { api.playlists() }
    Column(Modifier.fillMaxSize()) {
        Label("Playlists", Type.h2, Modifier.padding(vertical = Unit5 * 2))
        Loaded(state, budget, "Loading…") { playlists ->
            TextGrid(
                items = playlists,
                entry = entry,
                empty = "No playlists yet. Make them in the web app.",
                text = { it.name },
                detail = { "${it.count} ${if (it.count == 1) "video" else "videos"}" },
                onClick = { open(Route.PlaylistPage(it.id)) },
            )
        }
    }
}

@Composable
fun PlaylistScreen(api: Api, budget: Budget, entry: Entry, id: Int, open: (Route) -> Unit) {
    val state = rememberLoad(entry, budget) { api.playlist(id) }
    Column(Modifier.fillMaxSize()) {
        Loaded(state, budget, "Loading…") { playlist ->
            Label(playlist.name, Type.h2, Modifier.padding(vertical = Unit5 * 2), maxLines = 1)
            VideoGrid(api, playlist.items, entry, open, listId = id, empty = "Nothing here yet.")
        }
    }
}

@Composable
private fun VideoGrid(
    api: Api,
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
                VideoRow(video, api.thumbnailUrl(video.id))
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
