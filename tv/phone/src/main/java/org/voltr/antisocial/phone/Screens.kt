package org.voltr.antisocial.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.ApiError
import org.voltr.antisocial.core.Budget
import org.voltr.antisocial.core.ChannelPage
import org.voltr.antisocial.core.Video
import org.voltr.antisocial.core.YouTubeError
import org.voltr.antisocial.core.durationText
import org.voltr.antisocial.core.frameUrl
import org.voltr.antisocial.core.minutesText
import org.voltr.antisocial.core.parseYouTubeLink
import org.voltr.antisocial.core.videoMeta
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
 * that couldn't reach the server loads itself once it's back. A [fresh] screen (the account's
 * own lists, which change elsewhere) loads again each time it's shown, keeping what it had
 * on screen meanwhile.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoad(entry: Entry, budget: Budget, fresh: Boolean = false, load: suspend () -> T): Loader<T> {
    val state = (entry.data as? Load<T>) ?: Load.Loading
    var attempt by remember { mutableIntStateOf(0) }
    val serverDown by budget.serverDown.collectAsState()
    LaunchedEffect(serverDown) {
        val current = entry.data
        if (current is Load.Failed && current.unreachable && !serverDown) attempt++
    }
    LaunchedEffect(entry, attempt) {
        val shown = entry.data is Load.Ready<*>
        if (shown && !fresh) return@LaunchedEffect
        if (!shown) entry.data = Load.Loading
        val loaded = try {
            Load.Ready(load())
        } catch (e: ApiError) {
            Load.Failed(e.message ?: "Something went wrong.", e.unreachable)
        } catch (e: YouTubeError) {
            Load.Failed(e.message ?: "YouTube wouldn't give us this.", false)
        }
        // A refresh that fails keeps the list already on screen.
        if (loaded is Load.Ready || !shown) entry.data = loaded
    }
    return Loader(state) { attempt++ }
}

/** Renders loading and failure the same way on every screen. */
@Composable
fun <T> Loaded(loader: Loader<T>, label: String, content: @Composable (T) -> Unit) {
    when (val state = loader.state) {
        Load.Loading -> Label(label, Type.muted, Modifier.padding(top = Unit5 * 3))
        is Load.Failed -> Column(
            Modifier.padding(top = Unit5 * 3),
            verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
        ) {
            Label(state.message, Type.problem)
            ActionButton("Try again", loader.retry)
        }
        is Load.Ready -> content(state.value)
    }
}

@Composable
fun PageTitle(text: String) {
    Label(text, Type.h2, Modifier.padding(vertical = Unit5 * 2), maxLines = 2)
}

@Composable
fun HomeScreen(services: Services, navigator: Navigator) {
    val status by services.budget.status.collectAsState()
    Column(
        Modifier.fillMaxWidth().padding(top = Unit5 * 2),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 3),
    ) {
        SearchField(onSearch = { query ->
            // A pasted link goes where it points, not to a search for its letters.
            val link = parseYouTubeLink(query)
            if (link != null) navigator.openLink(link) else navigator.open(Route.SearchResults(query))
        })
        val current = status ?: return@Column
        val session = current.session ?: return@Column
        Panel {
            Label("${videosText(session.videosLeft)} left", Type.h1)
            BudgetMeter(current)
            Label("${minutesText(current.remainingSeconds)} left today. Resets at ${timeOfDay(current.resetsAt)}", Type.muted)
            if (session.samplesLeft > 0) {
                Label(
                    "Leave a video within ${current.sampleSeconds} seconds and it won't count (${session.samplesLeft} left).",
                    Type.muted,
                )
            }
        }
    }
}

/** A one-line text field that submits on the keyboard's action key. */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    action: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSubmit: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Type.body,
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = action),
        keyboardActions = KeyboardActions(onAny = {
            keyboard?.hide()
            onSubmit()
        }),
        modifier = modifier.heightIn(min = Touch),
        decorationBox = { field ->
            Box(
                Modifier.fillMaxWidth().background(Palette.raised, Corner).padding(horizontal = Unit5 * 3, vertical = Unit5 * 3),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Label(placeholder, Type.muted, maxLines = 1)
                field()
            }
        },
    )
}

@Composable
private fun SearchField(onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    TextInput(
        text,
        { text = it },
        "Search, or paste a YouTube link",
        Modifier.fillMaxWidth(),
        action = ImeAction.Search,
    ) {
        val query = text.trim()
        if (query.isNotEmpty()) onSearch(query)
    }
}

@Composable
fun SearchResultsScreen(services: Services, entry: Entry, query: String, navigator: Navigator) {
    val state = rememberLoad(entry, services.budget) { services.youtube.search(query) }
    Column(Modifier.fillMaxSize()) {
        PageTitle(query)
        Loaded(state, "Searching…") { VideoList(it, entry, navigator) }
    }
}

@Composable
fun HistoryScreen(services: Services, entry: Entry, navigator: Navigator) {
    val state = rememberLoad(entry, services.budget, fresh = true) { services.api.history() }
    Column(Modifier.fillMaxSize()) {
        PageTitle("History")
        Loaded(state, "Loading…") { VideoList(it, entry, navigator, empty = "Nothing watched yet.") }
    }
}

@Composable
fun ChannelsScreen(services: Services, entry: Entry, navigator: Navigator) {
    val state = rememberLoad(entry, services.budget, fresh = true) { services.api.subscriptions() }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Channels")
        Loaded(state, "Loading…") { channels ->
            if (channels.isEmpty()) {
                Label("No channels yet. Open a channel and subscribe.", Type.muted)
                return@Loaded
            }
            LazyColumn(state = entry.list, contentPadding = PaddingValues(bottom = Unit5 * 8)) {
                items(channels, key = { it.channelId }) { channel ->
                    TextRow(channel.name, null) { navigator.open(Route.Channel(channel.channelId)) }
                }
            }
        }
    }
}

/** Subscribing and unsubscribing, wherever a channel shows. */
@Composable
fun SubscribeButton(services: Services, channelId: String, name: String, initial: Boolean) {
    val scope = rememberCoroutineScope()
    var subscribed by remember(channelId) { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
        ActionButton(if (subscribed) "Unsubscribe" else "Subscribe", {
            scope.launch {
                try {
                    if (subscribed) services.api.unsubscribe(channelId) else services.api.subscribe(channelId, name)
                    subscribed = !subscribed
                    problem = null
                } catch (e: ApiError) {
                    problem = e.message
                }
            }
        }, primary = !subscribed)
        problem?.let { Label(it, Type.problem, maxLines = 2) }
    }
}

@Composable
fun ChannelScreen(services: Services, entry: Entry, id: String, navigator: Navigator) {
    class Page(val channel: ChannelPage, val subscribed: Boolean)

    val state = rememberLoad(entry, services.budget) {
        coroutineScope {
            val channel = async { services.youtube.channel(id) }
            val subscribed = async { services.api.subscriptions().any { it.channelId == id } }
            Page(channel.await(), subscribed.await())
        }
    }
    Column(Modifier.fillMaxSize()) {
        Loaded(state, "Loading channel…") { page ->
            VideoList(page.channel.videos, entry, navigator) {
                item {
                    Column(Modifier.padding(bottom = Unit5 * 2), verticalArrangement = Arrangement.spacedBy(Unit5)) {
                        PageTitle(page.channel.name)
                        SubscribeButton(services, id, page.channel.name, page.subscribed)
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(services: Services, entry: Entry, id: Int, navigator: Navigator) {
    val state = rememberLoad(entry, services.budget) { services.api.playlist(id) }
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Loaded(state, "Loading…") { playlist ->
            var items by remember(playlist) { mutableStateOf(playlist.items) }
            VideoList(
                items,
                entry,
                navigator,
                listId = id,
                empty = "Nothing here yet. Add videos from their page.",
                onRemove = { video ->
                    scope.launch {
                        try {
                            services.api.removeFromPlaylist(id, video.id)
                            items = items - video
                            entry.data = Load.Ready(playlist.copy(items = items))
                            problem = null
                        } catch (e: ApiError) {
                            problem = e.message
                        }
                    }
                },
            ) {
                item {
                    Column(Modifier.padding(bottom = Unit5 * 2), verticalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                        PageTitle(playlist.name)
                        if (confirming) {
                            Label("Delete \"${playlist.name}\" and its list of videos?", Type.body)
                            Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                                ActionButton("Delete", {
                                    scope.launch {
                                        try {
                                            services.api.deletePlaylist(id)
                                            navigator.back()
                                        } catch (e: ApiError) {
                                            problem = e.message
                                        }
                                    }
                                }, primary = true)
                                ActionButton("Keep it", { confirming = false })
                            }
                        } else {
                            ActionButton("Delete playlist", { confirming = true })
                        }
                        problem?.let { Label(it, Type.problem) }
                    }
                }
            }
        }
    }
}

/** A row of text: a channel or a playlist. */
@Composable
fun TextRow(text: String, detail: String?, onClick: () -> Unit) {
    Tappable(onClick, Modifier.fillMaxWidth().heightIn(min = Touch)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Unit5 * 2, horizontal = Unit5),
            horizontalArrangement = Arrangement.spacedBy(Unit5 * 2),
        ) {
            Label(text, Type.strong, Modifier.weight(1f), maxLines = 1)
            detail?.let { Label(it, Type.muted, maxLines = 1) }
        }
    }
}

/**
 * Videos one per row, with an optional [header] above them that scrolls away with the list.
 * [onRemove] adds a remove button to each row, for a playlist's own page.
 */
@Composable
fun VideoList(
    videos: List<Video>,
    entry: Entry,
    navigator: Navigator,
    listId: Int? = null,
    empty: String = "Nothing found.",
    onRemove: ((Video) -> Unit)? = null,
    header: LazyListScope.() -> Unit = {},
) {
    LazyColumn(
        state = entry.list,
        contentPadding = PaddingValues(bottom = Unit5 * 8),
        verticalArrangement = Arrangement.spacedBy(Unit5),
        modifier = Modifier.fillMaxSize(),
    ) {
        header()
        if (videos.isEmpty()) item { Label(empty, Type.muted, Modifier.padding(top = Unit5 * 2)) }
        items(videos, key = { it.id }) { video ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tappable({ navigator.open(Route.Watch(video.id, listId)) }, Modifier.weight(1f)) {
                    VideoRow(video)
                }
                onRemove?.let { remove -> LinkButton("remove", { remove(video) }, style = Type.muted) }
            }
        }
    }
}

@Composable
fun VideoRow(video: Video) {
    Row(Modifier.padding(vertical = Unit5), horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
        Box(Modifier.width(144.dp).aspectRatio(16f / 9f).background(Palette.raised, Corner)) {
            AsyncImage(frameUrl(video.id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            video.duration?.let {
                Label(
                    durationText(it.toLong()),
                    Type.body,
                    Modifier.align(Alignment.BottomEnd).background(Palette.background, Corner).padding(horizontal = Unit5),
                    maxLines = 1,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(video.title, Type.strong, maxLines = 2)
            video.channel?.takeIf { it.isNotEmpty() }?.let { Label(it, Type.muted, maxLines = 1) }
            videoMeta(video.views, video.uploaded).takeIf { it.isNotEmpty() }?.let { Label(it, Type.muted, maxLines = 1) }
            video.note?.let { Label(it, Type.muted, maxLines = 1) }
        }
    }
}
