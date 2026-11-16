package app.tono.ui.search

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tono.R
import app.tono.data.SearchFilters
import app.tono.data.Song
import app.tono.data.formatDuration
import app.tono.ui.components.Card
import app.tono.ui.components.CountBadge
import app.tono.ui.components.Dot
import app.tono.ui.components.IconButton44
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.Radius12
import app.tono.ui.components.Radius18
import app.tono.ui.components.SongRow
import app.tono.ui.components.TabPill
import app.tono.ui.components.TonoIcon
import app.tono.ui.theme.Tono

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onOpenSong: (Song) -> Unit,
    onCutSong: (Song) -> Unit,
    onOpenFilters: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val c = Tono.colors

    LaunchedEffect(vm) { vm.open.collect { onCutSong(it) } }

    // Once there are results (or a search is running), the big header folds away so the list gets the room.
    val showHero = ui.tab == SourceTab.Device || ui.results is ResultsState.Idle

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = showHero, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 20.dp).height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("tono", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Find a sound", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    Text("Search YouTube Music or pick a file.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            }
        }

        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = if (showHero) 24.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (ui.tab) {
                SourceTab.YouTube -> SearchField(ui.query, vm::setQuery, vm::search, vm::clearQuery)
                SourceTab.Device -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceTab.entries.forEach { tab -> TabPill(tab.label, ui.tab == tab) { vm.setTab(tab) } }
            }
            if (ui.tab == SourceTab.YouTube) FilterStrip(filters, onOpenFilters, vm::removeFilter)
        }

        when (ui.tab) {
            SourceTab.YouTube -> if (ui.results is ResultsState.Idle && recent.isNotEmpty()) {
                RecentSearches(recent, vm::searchFor, vm::removeRecent, vm::clearRecent)
            } else {
                Results(ui.results, onOpenSong, onCutSong, vm::loadMore, vm::search)
            }
            SourceTab.Device -> DevicePicker(vm::openLocal)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val c = Tono.colors
    val focus = LocalFocusManager.current
    InputShell {
        TonoIcon(R.drawable.ic_search, tint = c.muted, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search songs", style = MaterialTheme.typography.bodyLarge, color = c.muted)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search songs" },
            )
        }
        if (query.isNotEmpty()) IconButton44(R.drawable.ic_close, "Clear search", onClear, tint = c.muted, iconSize = 18.dp, shape = Radius12)
    }
}


@Composable
private fun InputShell(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Tono.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(Radius18)
            .background(c.surface)
            .border(1.dp, c.line, Radius18)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun FilterStrip(filters: SearchFilters, onOpenFilters: () -> Unit, onRemove: (app.tono.data.ActiveChip) -> Unit) {
    val c = Tono.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .height(40.dp)
                .clip(Radius12)
                .background(c.surface)
                .border(1.dp, c.line, Radius12)
                .clickable(role = Role.Button, onClick = onOpenFilters)
                .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TonoIcon(R.drawable.ic_filters, size = 18.dp)
            Text("Filters", style = MaterialTheme.typography.labelMedium)
            if (filters.activeCount > 0) CountBadge(filters.activeCount)
        }
        filters.activeChips.forEach { chip ->
            Row(
                Modifier
                    .height(40.dp)
                    .clip(Radius12)
                    .background(c.accentSoft)
                    .clickable(role = Role.Button, onClickLabel = "Remove ${chip.label} filter") { onRemove(chip) }
                    .padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(chip.label, style = MaterialTheme.typography.labelMedium, color = c.accent)
                TonoIcon(R.drawable.ic_close, size = 14.dp, tint = c.accent)
            }
        }
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun RecentSearches(recent: List<String>, onSearch: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    val c = Tono.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("RECENT", style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.weight(1f).semantics { heading() })
        androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear", style = MaterialTheme.typography.labelMedium, color = c.accent) }
    }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
        items(recent.size, key = { recent[it] }) { i ->
            val q = recent[i]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Radius12)
                    .clickable(onClickLabel = "Search $q") { onSearch(q) }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TonoIcon(R.drawable.ic_history, size = 20.dp, tint = c.muted)
                Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                IconButton44(R.drawable.ic_close, "Remove $q from recent", { onRemove(q) }, tint = c.muted, iconSize = 16.dp, shape = Radius12)
            }
        }
    }
}

@Composable
private fun Results(state: ResultsState, onOpen: (Song) -> Unit, onCut: (Song) -> Unit, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    val c = Tono.colors
    val count = (state as? ResultsState.Loaded)?.songs?.size
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("RESULTS", style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.weight(1f).semantics { heading() })
        if (count != null) Text(if (count == 1) "1 song" else "$count songs", style = MaterialTheme.typography.bodySmall, color = c.muted)
    }

    when (state) {
        ResultsState.Idle -> Message("Search for a song to turn into a ringtone, notification or alarm.")
        ResultsState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = c.accent)
        }
        is ResultsState.Failed -> Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Message(state.message, horizontalPadding = 0)
            PrimaryButton("Try again", onRetry, Modifier.fillMaxWidth())
        }
        is ResultsState.Loaded -> {
            if (state.songs.isEmpty() && !state.loadingMore) {
                Message("Nothing matched. Try other words, or loosen your filters.")
                return
            }
            val listState = rememberLazyListState()
            val nearEnd by remember {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= listState.layoutInfo.totalItemsCount - 4
                }
            }
            LaunchedEffect(listState) {
                snapshotFlow { nearEnd }.collect { if (it) onLoadMore() }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(state.songs, key = { _, s -> s.url }) { i, song ->
                    SongRow(
                        title = song.title,
                        subtitle = "${song.artist} · ${formatDuration(song.durationSec)}",
                        highlighted = false,
                        actionLabel = "Cut ${song.title}",
                        onAction = { onCut(song) },
                        thumbnailUrl = song.thumbnailUrl,
                        onOpen = { onOpen(song) },
                    )
                }
                if (state.loadingMore) item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}


@Composable
private fun DevicePicker(onPicked: (android.net.Uri) -> Unit) {
    val c = Tono.colors
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).clip(app.tono.ui.components.Radius14).background(c.accentSoft), contentAlignment = Alignment.Center) {
                        TonoIcon(R.drawable.ic_folder, tint = c.accent, size = 22.dp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Use a file on this phone", style = MaterialTheme.typography.titleSmall)
                        Text("MP3, M4A, OGG, WAV, FLAC…", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
                PrimaryButton("Choose audio file", { launcher.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Message(text: String, horizontalPadding: Int = 24) {
    Text(
        text,
        Modifier.padding(horizontal = horizontalPadding.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = Tono.colors.muted,
    )
}
