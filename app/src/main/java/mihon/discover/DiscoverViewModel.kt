package mihon.discover

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import mihon.discover.recommendation.RecommendationStore
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.source.local.isLocal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DiscoverViewModel(
    private val api: AnilistCatalogApi,
    private val store: DiscoverStore,
    private val recommendationStore: RecommendationStore,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaRepository: MangaRepository,
    private val trackRepository: TrackRepository,
    private val trackerManager: TrackerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var catalogueJob: Job? = null
    private var sourceJob: Job? = null

    init {
        viewModelScope.launch {
            _state
                .debounce { if (it.query == it.lastLoadedQuery) 0 else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged { old, new ->
                    old.query == new.query && old.sort == new.sort && old.filters == new.filters
                }
                .collect { loadFirstPage() }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun setSort(sort: DiscoverSort) = _state.update { it.copy(sort = sort) }
    fun setFilters(filters: CatalogueFilters) = _state.update { it.copy(filters = filters) }
    fun savePreset(name: String, filters: CatalogueFilters) {
        viewModelScope.launchIO {
            recommendationStore.savePreset(name, filters)
            _state.update { it.copy(presets = recommendationStore.presets()) }
        }
    }

    fun loadPresets() {
        viewModelScope.launchIO {
            _state.update { it.copy(presets = recommendationStore.presets()) }
            try {
                _state.update { it.copy(filterOptions = api.filterOptions()) }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
    }
    fun retryCatalogue() = loadFirstPage()
    fun loadNextPage() {
        val snapshot = state.value
        if (snapshot.loading || !snapshot.hasNextPage) return
        loadPage(snapshot.nextPage, append = true)
    }

    private fun loadFirstPage() = loadPage(1, append = false)
    private fun loadPage(page: Int, append: Boolean) {
        catalogueJob?.cancel()
        catalogueJob = viewModelScope.launchIO {
            val before = state.value
            _state.update { it.copy(loading = true, error = null, lastLoadedQuery = before.query) }
            runCatching {
                val local = localFilterSnapshot(before.filters)
                val found = mutableListOf<AniListMedia>()
                var currentPage = page
                var hasNext = true
                var scanned = 0
                do {
                    val result = api.browse(currentPage, before.query, before.sort, before.filters)
                    found += result.items.filter { media ->
                        (!before.filters.hideKnownLibrary || media.id !in local.library) &&
                            (!before.filters.onlyKnownLibrary || media.id in local.library) &&
                            (!before.filters.hideKnownStarted || media.id !in local.started) &&
                            (!before.filters.onlyLiked || recommendationStore.feedback(media.id).vote > 0) &&
                            (!before.filters.excludeDisliked || recommendationStore.feedback(media.id).vote >= 0)
                    }
                    hasNext = result.hasNextPage
                    currentPage++
                    scanned++
                } while (hasNext && scanned < 5 && found.size < 20)
                Triple(found, currentPage, hasNext)
            }
                .onSuccess { result ->
                    result.first.forEach {
                        store.putMedia(it)
                        recommendationStore.putMedia(it)
                    }
                    _state.update {
                        it.copy(
                            items = (if (append) it.items else emptyList()) + result.first,
                            nextPage = result.second,
                            hasNextPage = result.third,
                            partial = result.third && result.first.size < 20,
                            loading = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.update {
                        it.copy(
                            loading = false,
                            error =
                            error.message ?: "Unable to load AniList",
                        )
                    }
                }
        }
    }

    private data class LocalFilterSnapshot(val library: Set<Long>, val started: Set<Long>)

    private suspend fun localFilterSnapshot(filters: CatalogueFilters): LocalFilterSnapshot {
        if (!filters.hideKnownLibrary && !filters.onlyKnownLibrary && !filters.hideKnownStarted) {
            return LocalFilterSnapshot(emptySet(), emptySet())
        }
        val tracks = trackRepository.getTracksAsFlow().first()
            .filter { it.trackerId == trackerManager.aniList.id }
            .associate { it.mangaId to it.remoteId }
        val library = mutableSetOf<Long>()
        val started = mutableSetOf<Long>()
        mangaRepository.getLibraryManga().forEach { item ->
            val id = tracks[item.manga.id]
                ?: recommendationStore.linkedId(item.manga.source, item.manga.url)
                ?: store.linkedMediaId(item.manga.source, item.manga.url)
            if (id != null) {
                library += id
                if (item.readCount > 0) started += id
            }
        }
        return LocalFilterSnapshot(library, started)
    }

    fun openMedia(media: AniListMedia) {
        _state.update { it.copy(selected = media, sourceState = SourceSearchState.Idle) }
        searchSources(media, refresh = false)
    }

    fun closeMedia() {
        sourceJob?.cancel()
        _state.update { it.copy(selected = null, sourceState = SourceSearchState.Idle) }
    }

    fun refreshSources() {
        state.value.selected?.let { searchSources(it, refresh = true) }
    }

    private fun searchSources(media: AniListMedia, refresh: Boolean) {
        sourceJob?.cancel()
        sourceJob = viewModelScope.launchIO {
            val enabledLanguages = sourcePreferences.enabledLanguages.get()
            val disabled = sourcePreferences.disabledSources.get()
            val sources = sourceManager.getAll()
                .filterNot { it.isLocal() }
                .filter { it.lang in enabledLanguages && it.id.toString() !in disabled }
                .sortedBy { it.name.lowercase() }
            if (!refresh) {
                store.getMatches(media.id, sources.mapTo(mutableSetOf()) { it.id })?.let { cached ->
                    _state.update { it.copy(sourceState = cached) }
                    return@launchIO
                }
            } else {
                store.clearMatches(media.id)
            }
            _state.update { it.copy(sourceState = SourceSearchState.Loading(0, sources.size)) }
            val semaphore = Semaphore(MAX_PARALLEL_SOURCE_REQUESTS)
            val completed = AtomicInteger(0)
            val results = sources.map { source ->
                async {
                    semaphore.withPermit {
                        val result = findInSource(source, media)
                        _state.update {
                            val previous = it.sourceState as? SourceSearchState.Loading
                            it.copy(
                                sourceState = SourceSearchState.Loading(
                                    completed = completed.incrementAndGet(),
                                    total = sources.size,
                                    matches = previous?.matches.orEmpty() + result.matches,
                                    errors = previous?.errors.orEmpty() + result.errors,
                                ),
                            )
                        }
                        result
                    }
                }
            }.awaitAll()
            val matches = results.flatMap { it.matches }
            val complete = SourceSearchState.Complete(
                matches = matches.sortedWith(compareBy<SourceMatch> { it.confidence }.thenBy { it.sourceName }),
                partial = results.any { it.partial },
                errors = results.flatMap { it.errors },
            )
            store.putMatches(media.id, sources.mapTo(mutableSetOf()) { it.id }, complete)
            _state.update { it.copy(sourceState = complete) }
        }
    }

    private data class SourceResult(
        val matches: List<SourceMatch>,
        val partial: Boolean,
        val errors: List<String>,
    )

    private suspend fun findInSource(source: Source, media: AniListMedia): SourceResult {
        val variants = DiscoverTitleMatcher.variants(media)
        val results = linkedMapOf<String, SourceMatch>()
        var partial = false
        val errors = mutableListOf<String>()
        variants.forEach { query ->
            for (page in 1..MAX_SOURCE_PAGES) {
                val response = try {
                    withTimeoutOrNull(SOURCE_TIMEOUT) { source.getSearchManga(page, query, source.getFilterList()) }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    partial = true
                    errors += "${source.name}: ${e.message ?: "recherche indisponible"}"
                    break
                } ?: run {
                    partial = true
                    errors += "${source.name}: délai de recherche dépassé"
                    break
                }
                response.mangas.forEach { manga ->
                    val confidence = DiscoverTitleMatcher.confidence(manga.title, variants)
                    val chapterCount = if (confidence == SourceMatch.Confidence.EXACT) {
                        try {
                            withTimeoutOrNull(SOURCE_TIMEOUT) {
                                source.getMangaUpdate(
                                    manga,
                                    emptyList(),
                                    fetchDetails = false,
                                    fetchChapters = true,
                                ).chapters.size
                            }
                        } catch (_: Exception) {
                            currentCoroutineContext().ensureActive()
                            null
                        }
                    } else {
                        null
                    }
                    results.putIfAbsent(
                        manga.url,
                        SourceMatch(
                            source.id,
                            source.name,
                            source.lang,
                            manga.url,
                            manga.title,
                            manga.thumbnail_url,
                            chapterCount = chapterCount,
                            confidence = confidence,
                        ),
                    )
                }
                if (!response.hasNextPage) break
                if (page == MAX_SOURCE_PAGES) partial = true
            }
            if (results.values.count { it.confidence == SourceMatch.Confidence.EXACT } ==
                1
            ) {
                return SourceResult(results.values.toList(), partial, errors)
            }
        }
        if (variants.size == DiscoverTitleMatcher.MAX_VARIANTS) partial = true
        return SourceResult(results.values.map { it.copy(partial = partial) }, partial, errors)
    }

    fun openMatch(match: SourceMatch) {
        viewModelScope.launchIO {
            val source = sourceManager.get(match.sourceId) ?: return@launchIO
            val selected = state.value.selected ?: return@launchIO
            val existingId = recommendationStore.linkedId(source.id, match.url)
            if (existingId != null && existingId != selected.id) {
                _state.update { it.copy(associationError = "Ce manga est déjà associé à une autre œuvre AniList.") }
                return@launchIO
            }
            val local = mangaRepository.getMangaByUrlAndSourceId(match.url, source.id)
                ?: networkToLocalManga(
                    SManga.create().apply {
                        url = match.url
                        title = match.title
                        thumbnail_url = match.thumbnailUrl
                    }.toDomainManga(source.id),
                )
            if (state.value.selected?.id != selected.id) return@launchIO
            val linked = recommendationStore.link(
                source.id,
                match.url,
                selected.id,
                match.confidence == SourceMatch.Confidence.CANDIDATE,
            )
            if (!linked) {
                _state.update { current ->
                    current.copy(associationError = "Ce manga est déjà associé à une autre œuvre AniList.")
                }
                return@launchIO
            }
            store.linkManga(source.id, match.url, selected.id)
            _state.update { it.copy(openMangaId = local.id) }
        }
    }

    fun consumeOpenManga() = _state.update { it.copy(openMangaId = null) }

    @Immutable
    data class State(
        val query: String = "",
        val lastLoadedQuery: String = "__initial__",
        val sort: DiscoverSort = DiscoverSort.TRENDING,
        val filters: CatalogueFilters = CatalogueFilters(),
        val presets: Map<String, CatalogueFilters> = emptyMap(),
        val filterOptions: AnilistCatalogApi.FilterOptions? = null,
        val items: List<AniListMedia> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val nextPage: Int = 1,
        val hasNextPage: Boolean = true,
        val partial: Boolean = false,
        val selected: AniListMedia? = null,
        val sourceState: SourceSearchState = SourceSearchState.Idle,
        val openMangaId: Long? = null,
        val associationError: String? = null,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val MAX_PARALLEL_SOURCE_REQUESTS = 4
        const val MAX_SOURCE_PAGES = 2
        val SOURCE_TIMEOUT = 15.seconds
    }
}
