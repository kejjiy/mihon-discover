package mihon.discover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.discover.recommendation.RecommendationEngine
import mihon.discover.recommendation.RecommendationStore
import mihon.discover.recommendation.RecommendationViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(initialSimilarId: Long? = null) {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<DiscoverViewModel>()
    val recommendations = metroViewModel<RecommendationViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recommendationState by recommendations.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    var showingRecommendations by remember { mutableStateOf(initialSimilarId != null) }
    var showFilters by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let {
                scope.launch {
                    profileMessage = runCatching {
                        val data = recommendations.exportPersonalData()
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer ->
                                writer.write(data)
                            }
                                ?: error("Impossible d’écrire la sauvegarde")
                        }
                        "Profil exporté"
                    }.getOrElse { error -> error.message ?: "Export impossible" }
                }
            }
        }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                profileMessage = runCatching {
                    val data = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                            reader.readText()
                        }
                            ?: error("Impossible de lire la sauvegarde")
                    }
                    recommendations.importPersonalData(data)
                    "Profil importé"
                }.getOrElse { error -> error.message ?: "Import impossible" }
            }
        }
    }

    LaunchedEffect(initialSimilarId) {
        initialSimilarId?.let(recommendations::similarById)
    }

    LaunchedEffect(state.openMangaId) {
        state.openMangaId?.let {
            navigator.push(MangaScreen(it, fromSource = true))
            viewModel.consumeOpenManga()
        }
    }
    val selected = state.selected
    if (selected != null) {
        LaunchedEffect(selected.id) { recommendations.select(selected) }
        DiscoverDetail(
            media = selected,
            searchState = state.sourceState,
            onBack = viewModel::closeMedia,
            onRefresh = viewModel::refreshSources,
            onOpenMatch = viewModel::openMatch,
            feedback = recommendationState.feedback,
            onVote = recommendations::vote,
            onStatus = recommendations::setReadingStatus,
            onHide = recommendations::hideSelected,
            onSimilar = {
                recommendations.similar(selected)
                viewModel.closeMedia()
                showingRecommendations = true
            },
            associationError = state.associationError,
        )
        return
    }

    LaunchedEffect(gridState.canScrollForward, state.loading, state.hasNextPage) {
        if (!gridState.canScrollForward && !state.loading && state.items.isNotEmpty() && state.hasNextPage) {
            viewModel.loadNextPage()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catalogue") },
                navigationIcon = {
                    if (initialSimilarId != null) TextButton(onClick = { navigator.pop() }) { Text("Retour") }
                },
                actions = { TextButton(onClick = { showProfile = true }) { Text("Profil") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showingRecommendations,
                    onClick = { showingRecommendations = false },
                    label = { Text("Explorer") },
                )
                FilterChip(
                    selected = showingRecommendations && recommendationState.mode == RecommendationEngine.Mode.PERSONAL,
                    onClick = {
                        showingRecommendations = true
                        recommendations.setMode(RecommendationEngine.Mode.PERSONAL)
                    },
                    label = { Text("Pour toi") },
                )
                FilterChip(
                    selected = showingRecommendations && recommendationState.mode == RecommendationEngine.Mode.EXPLORE,
                    onClick = {
                        showingRecommendations = true
                        recommendations.setMode(RecommendationEngine.Mode.EXPLORE)
                    },
                    label = { Text("Découverte") },
                )
            }
            if (showingRecommendations) {
                RecommendationPane(
                    state = recommendationState,
                    onRefresh = { recommendations.refresh(force = true) },
                    onPolicy = recommendations::setRomancePolicy,
                    onOpen = viewModel::openMedia,
                )
                return@Column
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                leadingIcon = { Icon(MaterialSymbols.Rounded.Search, null) },
                label = { Text("Search AniList") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiscoverSort.entries.forEach { sort ->
                    FilterChip(
                        selected = state.sort == sort,
                        onClick = { viewModel.setSort(sort) },
                        label = { Text(sort.label) },
                    )
                }
                AssistChip(onClick = {
                    viewModel.loadPresets()
                    showFilters = true
                }, label = { Text("Filtres") })
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (state.partial) {
                                "Recherche partielle : aucun résultat dans les pages examinées"
                            } else {
                                "No manga found"
                            },
                        )
                        if (state.hasNextPage) {
                            TextButton(onClick = viewModel::loadNextPage) {
                                Text("Chercher davantage")
                            }
                        }
                    }
                }
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
                    if (state.partial) {
                        item {
                            TextButton(onClick = viewModel::loadNextPage) { Text("Chercher davantage") }
                        }
                    }
                }
            }
        }
    }
    if (showFilters) {
        CatalogueFilterDialog(
            current = state.filters,
            presets = state.presets,
            options = state.filterOptions,
            onApply = {
                viewModel.setFilters(it)
                showFilters = false
            },
            onSave = viewModel::savePreset,
            onDismiss = { showFilters = false },
        )
    }
    if (showProfile) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showProfile = false },
            title = { Text("Profil Discover") },
            text = {
                Column {
                    Text("Vos avis et préférences restent dans la base locale Discover.")
                    TextButton(onClick = { exportLauncher.launch("mihon-discover-profile.json") }) {
                        Text("Exporter le profil")
                    }
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Text("Importer le profil")
                    }
                    TextButton(onClick = recommendations::clearMetadataCache) { Text("Vider le cache AniList") }
                    TextButton(onClick = { confirmErase = true }) { Text("Effacer mes avis et associations") }
                    profileMessage?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { showProfile = false }) { Text("Fermer") } },
        )
    }
    if (confirmErase) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Effacer les préférences Discover ?") },
            text = { Text("Les avis, filtres enregistrés et associations personnelles seront supprimés.") },
            confirmButton = {
                TextButton(onClick = {
                    recommendations.clearPersonalData()
                    confirmErase = false
                    showProfile = false
                }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun RecommendationPane(
    state: RecommendationViewModel.State,
    onRefresh: () -> Unit,
    onPolicy: (RecommendationEngine.RomancePolicy) -> Unit,
    onOpen: (AniListMedia) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            RecommendationEngine.RomancePolicy.entries.forEach { policy ->
                FilterChip(
                    selected = state.romancePolicy == policy,
                    onClick = { onPolicy(policy) },
                    label = {
                        Text(
                            when (policy) {
                                RecommendationEngine.RomancePolicy.TOLERATE -> "Romance tolérée"
                                RecommendationEngine.RomancePolicy.PENALIZE -> "Romance pénalisée"
                                RecommendationEngine.RomancePolicy.EXCLUDE_MAIN -> "Romance principale exclue"
                            },
                        )
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.mode ==
                    RecommendationEngine.Mode.SIMILAR
                ) {
                    "Œuvres similaires"
                } else {
                    "Classement local"
                },
                Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("Actualiser") }
        }
        state.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune recommandation disponible")
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.partial) item { Text("Résultats partiels : AniList n’a pas fourni toutes les pages.") }
                items(state.items, key = { it.media.id }) { item ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(item.media) }) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(
                                model = item.media.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp, 92.dp),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.media.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Affinité ${item.ranking.score.toInt()}/100 · AniList ${item.media.averageScore ?: "—"}/100",
                                )
                                item.ranking.reasons.take(2).forEach { reason ->
                                    Text(reason, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
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
    feedback: RecommendationStore.Feedback,
    onVote: (Int) -> Unit,
    onStatus: (String) -> Unit,
    onHide: () -> Unit,
    onSimilar: () -> Unit,
    associationError: String?,
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
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = feedback.vote > 0, onClick = {
                        onVote(
                            if (feedback.vote >
                                0
                            ) {
                                0
                            } else {
                                1
                            },
                        )
                    }, label = { Text("J’aime") })
                    FilterChip(selected = feedback.vote < 0, onClick = {
                        onVote(
                            if (feedback.vote <
                                0
                            ) {
                                0
                            } else {
                                -1
                            },
                        )
                    }, label = { Text("Je n’aime pas") })
                    TextButton(onClick = onSimilar) { Text("Œuvres similaires") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    listOf("TO_READ", "READING", "COMPLETED", "PAUSED", "ABANDONED").forEach { status ->
                        FilterChip(selected = feedback.status == status, onClick = {
                            onStatus(status)
                        }, label = { Text(status) })
                    }
                    TextButton(onClick = onHide) { Text("Masquer") }
                }
            }
            if (media.tags.isNotEmpty()) {
                item {
                    Text(
                        "Tags : " + media.tags.filterNot {
                            it.isSpoiler
                        }.take(12).joinToString { "${it.name} ${it.rank}%" },
                    )
                }
            }
            item { Text("Votes : ${media.voteCount ?: "inconnu"} · Qualité corrigée selon les votes") }
            media.description?.let { description -> item { Text(description.replace(Regex("<[^>]*>"), "")) } }
            if (media.genres.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        media.genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
            }
            item { HorizontalDivider() }
            associationError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Text("Available sources", style = MaterialTheme.typography.titleLarge) }
            when (searchState) {
                SourceSearchState.Idle -> item { CircularProgressIndicator() }
                is SourceSearchState.Loading -> {
                    item { Text("Searching sources ${searchState.completed}/${searchState.total}") }
                    items(searchState.matches, key = { "${it.sourceId}:${it.url}" }) { match ->
                        SourceMatchRow(match, onClick = {
                            if (match.confidence == SourceMatch.Confidence.CANDIDATE) {
                                candidate = match
                            } else {
                                onOpenMatch(match)
                            }
                        })
                    }
                    searchState.errors.forEach { error ->
                        item { Text(error, color = MaterialTheme.colorScheme.error) }
                    }
                }
                is SourceSearchState.Failed -> {
                    item { Text(searchState.message, color = MaterialTheme.colorScheme.error) }
                }
                is SourceSearchState.Complete -> {
                    searchState.errors.forEach { error ->
                        item { Text(error, color = MaterialTheme.colorScheme.error) }
                    }
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
