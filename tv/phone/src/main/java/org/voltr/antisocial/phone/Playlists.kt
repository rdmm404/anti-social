package org.voltr.antisocial.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.ApiError
import org.voltr.antisocial.core.Link
import org.voltr.antisocial.core.PlaylistRef
import org.voltr.antisocial.core.Video
import org.voltr.antisocial.core.YouTubeError
import org.voltr.antisocial.core.parseYouTubeLink

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Adding(val name: String, val done: Int, val total: Int) : ImportState
    data class Done(val playlistId: Int, val name: String, val count: Int) : ImportState
    data class Failed(val message: String) : ImportState
}

private val LIST_PARAM = Regex("""[?&]list=([\w-]{10,})""")

/** The playlist id in a YouTube link: a playlist page, or a video played from a list. */
fun playlistIdIn(text: String): String? =
    (parseYouTubeLink(text) as? Link.Playlist)?.id ?: LIST_PARAM.find(text)?.groupValues?.get(1)

/**
 * Imports a YouTube playlist: read on this phone, then created and filled through the server,
 * so it shows on every device. Runs on the app's scope, so leaving the page doesn't cut it short.
 */
class Importer(private val services: Services) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun start(link: String) {
        if (_state.value is ImportState.Reading || _state.value is ImportState.Adding) return
        val id = playlistIdIn(link) ?: run {
            _state.value = ImportState.Failed("That isn't a link to a YouTube playlist.")
            return
        }
        services.scope.launch {
            _state.value = ImportState.Reading
            try {
                val playlist = services.youtube.playlist(id)
                if (playlist.videos.isEmpty()) {
                    _state.value = ImportState.Failed("That playlist has no videos the app can play.")
                    return@launch
                }
                val name = playlist.name.ifBlank { "Imported" }
                val created = services.api.createPlaylist(name)
                playlist.videos.forEachIndexed { index, video ->
                    _state.value = ImportState.Adding(name, index, playlist.videos.size)
                    services.api.addToPlaylist(created, video)
                }
                _state.value = ImportState.Done(created, name, playlist.videos.size)
            } catch (e: YouTubeError) {
                _state.value = ImportState.Failed(e.message ?: "YouTube wouldn't give us this playlist.")
            } catch (e: ApiError) {
                _state.value = ImportState.Failed(e.message ?: "The server refused the import.")
            }
        }
    }

    fun dismiss() {
        if (_state.value is ImportState.Done || _state.value is ImportState.Failed) _state.value = ImportState.Idle
    }
}

@Composable
fun PlaylistsScreen(services: Services, entry: Entry, importing: String?, navigator: Navigator) {
    val state = rememberLoad(entry, services.budget, fresh = true) { services.api.playlists() }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf(importing?.let { "https://www.youtube.com/playlist?list=$it" } ?: "") }
    var problem by remember { mutableStateOf<String?>(null) }
    val import by services.importer.state.collectAsState()
    // A finished import shows in the list at once.
    LaunchedEffect(import) { if (import is ImportState.Done) state.retry() }

    fun create() {
        val wanted = name.trim()
        if (wanted.isEmpty()) return
        scope.launch {
            try {
                val id = services.api.createPlaylist(wanted)
                name = ""
                problem = null
                navigator.open(Route.PlaylistPage(id))
            } catch (e: ApiError) {
                problem = e.message
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = entry.list, contentPadding = PaddingValues(bottom = Unit5 * 8)) {
            item {
                Column(Modifier.padding(bottom = Unit5 * 3), verticalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    PageTitle("Playlists")
                    Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2), verticalAlignment = Alignment.CenterVertically) {
                        TextInput(name, { name = it }, "New playlist name", Modifier.weight(1f), onSubmit = ::create)
                        ActionButton("Create", ::create, primary = true)
                    }
                    problem?.let { Label(it, Type.problem) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2), verticalAlignment = Alignment.CenterVertically) {
                        TextInput(
                            link, { link = it }, "YouTube playlist link", Modifier.weight(1f),
                            keyboardType = KeyboardType.Uri,
                        ) { services.importer.start(link) }
                        ActionButton("Import", { services.importer.start(link) })
                    }
                    ImportProgress(import, services.importer, navigator)
                }
            }
            when (val current = state.state) {
                Load.Loading -> item { Label("Loading…", Type.muted) }
                is Load.Failed -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                        Label(current.message, Type.problem)
                        ActionButton("Try again", state.retry)
                    }
                }
                is Load.Ready -> {
                    if (current.value.isEmpty()) item { Label("No playlists yet.", Type.muted) }
                    items(current.value, key = { it.id }) { playlist ->
                        TextRow(playlist.name, "${playlist.count} ${if (playlist.count == 1) "video" else "videos"}") {
                            navigator.open(Route.PlaylistPage(playlist.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportProgress(state: ImportState, importer: Importer, navigator: Navigator) {
    when (state) {
        ImportState.Idle -> Unit
        ImportState.Reading -> Label("Reading the playlist from YouTube…", Type.muted)
        is ImportState.Adding -> Label("Adding to \"${state.name}\": ${state.done} of ${state.total}", Type.muted)
        is ImportState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Imported \"${state.name}\" with ${state.count} videos.", Type.body, Modifier.weight(1f))
            LinkButton("open", {
                importer.dismiss()
                navigator.open(Route.PlaylistPage(state.playlistId))
            })
        }
        is ImportState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Label(state.message, Type.problem, Modifier.weight(1f))
            LinkButton("ok", importer::dismiss, style = Type.muted)
        }
    }
}

/** Adding the open video to a playlist, or to a new one. */
@Composable
fun AddToPlaylist(services: Services, video: Video, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<PlaylistRef>?>(null) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        playlists = try {
            services.api.playlists()
        } catch (e: ApiError) {
            message = (e.message ?: "Can't load playlists.") to true
            emptyList()
        }
    }

    fun add(id: Int, playlistName: String) {
        scope.launch {
            message = try {
                services.api.addToPlaylist(id, video)
                "Added to \"$playlistName\"." to false
            } catch (e: ApiError) {
                (e.message ?: "Couldn't add it.") to true
            }
        }
    }

    fun createAndAdd() {
        val wanted = name.trim()
        if (wanted.isEmpty()) return
        scope.launch {
            message = try {
                val id = services.api.createPlaylist(wanted)
                services.api.addToPlaylist(id, video)
                name = ""
                playlists = services.api.playlists()
                "Added to \"$wanted\"." to false
            } catch (e: ApiError) {
                (e.message ?: "Couldn't create it.") to true
            }
        }
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Add to a playlist", Type.h3, Modifier.weight(1f))
            LinkButton("close", onDone, style = Type.muted)
        }
        when (val list = playlists) {
            null -> Label("Loading…", Type.muted)
            else -> list.forEach { playlist ->
                TextRow(playlist.name, "${playlist.count}") { add(playlist.id, playlist.name) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Unit5 * 2), verticalAlignment = Alignment.CenterVertically) {
            TextInput(name, { name = it }, "New playlist", Modifier.weight(1f), onSubmit = ::createAndAdd)
            ActionButton("Create and add", ::createAndAdd, primary = true)
        }
        message?.let { (text, problem) -> Label(text, if (problem) Type.problem else Type.body) }
    }
}
