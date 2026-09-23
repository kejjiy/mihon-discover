package mihon.discover

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen() {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<DiscoverViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    LaunchedEffect(state.openMangaId) {
        state.openMangaId?.let {
            navigator.push(MangaScreen(it, fromSource = true))
            viewModel.consumeOpenManga()
        }
    }
    val selected = state.selected
    if (selected != null) {
        DiscoverDetail(
            media = selected,
            searchState = state.sourceState,
            onBack = viewModel::closeMedia,
            onRefresh = viewModel::refreshSources,
            onOpenMatch = viewModel::openMatch,
        )
        return
    }

    LaunchedEffect(gridState.canScrollForward, state.loading, state.hasNextPage) {
        if (!gridState.canScrollForward && !state.loading && state.items.isNotEmpty() && state.hasNextPage) {
            viewModel.loadNextPage()
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Catalogue") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                leadingIcon = { Icon(MaterialSymbols.Rounded.Search, null) },
                label = { Text("Search AniList") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiscoverSort.entries.forEach { sort ->
                    FilterChip(
                        selected = state.sort == sort,
                        onClick = { viewModel.setSort(sort) },
                        label = { Text(sort.label) },
                    )
                }
            }
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retryCatalogue) { Text("Retry") }
                }
            }
            if (state.items.isEmpty() && state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.items.isEmpty() && state.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No manga found") }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(130.dp),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.items, key = { it.id }) { media ->
                        CatalogueCard(media, onClick = { viewModel.openMedia(media) })
                    }
                    if (state.loading) item { Box(Modifier.padding(24.dp)) { CircularProgressIndicator() } }
                }
            }
        }
    }
}

@Composable
private fun CatalogueCard(media: AniListMedia, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        AsyncImage(
            model = media.coverUrl,
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Column(Modifier.padding(10.dp)) {
            Text(
                media.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            media.averageScore?.let { Text("AniList $it/100", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverDetail(
    media: AniListMedia,
    searchState: SourceSearchState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMatch: (SourceMatch) -> Unit,
) {
    var candidate by remember { mutableStateOf<SourceMatch?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(media.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Icon(MaterialSymbols.Rounded.Close, "Back") } },
                actions = {
                    TextButton(onClick = onRefresh) {
                        Icon(MaterialSymbols.Rounded.Refresh, "Refresh sources")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(
                        model = media.coverUrl,
                        contentDescription = media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(112.dp, 168.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(media.title, style = MaterialTheme.typography.headlineSmall)
                        media.averageScore?.let { Text("AniList $it/100") }
                        Text("Popularity: ${media.popularity ?: "—"}")
                        Text("Status: ${media.status?.replace('_', ' ') ?: "—"}")
                        Text("Chapters: ${media.chapters ?: "—"}")
                    }
                }
            }
            media.alternativeTitles.takeIf { it.isNotEmpty() }?.let { titles ->
                item { Text(titles.joinToString(" • "), style = MaterialTheme.typography.bodyMedium) }
            }
            media.description?.let { description -> item { Text(description.replace(Regex("<[^>]*>"), "")) } }
            if (media.genres.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        media.genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
            }
            item { HorizontalDivider() }
            item { Text("Available sources", style = MaterialTheme.typography.titleLarge) }
            when (searchState) {
                SourceSearchState.Idle -> item { CircularProgressIndicator() }
                is SourceSearchState.Loading -> {
                    item { Text("Searching sources ${searchState.completed}/${searchState.total}") }
                }
                is SourceSearchState.Failed -> {
                    item { Text(searchState.message, color = MaterialTheme.colorScheme.error) }
                }
                is SourceSearchState.Complete -> {
                    if (searchState.matches.isEmpty()) {
                        item {
                            Text(
                                if (searchState.partial) {
                                    "Search incomplete; no confirmed source yet."
                                } else {
                                    "No installed source has this title."
                                },
                            )
                        }
                    }
                    items(searchState.matches, key = { "${it.sourceId}:${it.url}" }) { match ->
                        SourceMatchRow(match, onClick = {
                            if (match.confidence == SourceMatch.Confidence.CANDIDATE) {
                                candidate = match
                            } else {
                                onOpenMatch(match)
                            }
                        })
                    }
                    if (searchState.partial) {
                        item {
                            Text(
                                "Some source searches were incomplete.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
    candidate?.let { match ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text("Possible match") },
            text = { Text("${match.title} in ${match.sourceName} is not an exact title match. Open it?") },
            confirmButton = {
                TextButton(onClick = {
                    candidate = null
                    onOpenMatch(match)
                }) { Text("Confirm and open") }
            },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceMatchRow(match: SourceMatch, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = match.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp, 68.dp),
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(match.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${match.sourceName} · ${match.language}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (match.confidence == SourceMatch.Confidence.EXACT) "Exact match" else "Possible match",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            match.chapterCount?.let { Text("$it ch.") }
        }
    }
}
