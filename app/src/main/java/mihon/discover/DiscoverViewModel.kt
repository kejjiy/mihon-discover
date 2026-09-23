package mihon.discover

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DiscoverViewModel(
    private val api: AnilistCatalogApi,
    private val store: DiscoverStore,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
) : ViewModel() {
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var catalogueJob: Job? = null
    private var sourceJob: Job? = null

    init {
        viewModelScope.launch {
            _state
                .debounce { if (it.query == it.lastLoadedQuery) 0 else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged { old, new -> old.query == new.query && old.sort == new.sort }
                .collect { loadFirstPage() }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun setSort(sort: DiscoverSort) = _state.update { it.copy(sort = sort) }
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
            runCatching { api.browse(page, before.query, before.sort) }
                .onSuccess { result ->
                    result.items.forEach(store::putMedia)
                    _state.update {
                        it.copy(
                            items = (if (append) it.items else emptyList()) + result.items,
                            nextPage = page + 1,
                            hasNextPage = result.hasNextPage,
                            loading = false,
                        )
                    }
                }
                .onFailure { error ->
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
            val matches = sources.map { source ->
                async {
                    semaphore.withPermit {
                        val result = findInSource(source, media)
                        _state.update {
                            it.copy(sourceState = SourceSearchState.Loading(completed.incrementAndGet(), sources.size))
                        }
                        result
                    }
                }
            }.awaitAll().flatten()
            val complete = SourceSearchState.Complete(
                matches = matches.sortedWith(compareBy<SourceMatch> { it.confidence }.thenBy { it.sourceName }),
                partial = matches.any { it.partial },
            )
            store.putMatches(media.id, sources.mapTo(mutableSetOf()) { it.id }, complete)
            _state.update { it.copy(sourceState = complete) }
        }
    }

    private suspend fun findInSource(source: Source, media: AniListMedia): List<SourceMatch> {
        val variants = DiscoverTitleMatcher.variants(media)
        val results = linkedMapOf<String, SourceMatch>()
        var partial = false
        variants.forEach { query ->
            for (page in 1..MAX_SOURCE_PAGES) {
                val response = try {
                    withTimeoutOrNull(SOURCE_TIMEOUT) { source.getSearchManga(page, query, source.getFilterList()) }
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    partial = true
                    break
                } ?: run {
                    partial = true
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
                return results.values.toList()
            }
        }
        return results.values.map { it.copy(partial = partial) }
    }

    fun openMatch(match: SourceMatch) {
        viewModelScope.launchIO {
            val source = sourceManager.get(match.sourceId) ?: return@launchIO
            val page =
                withTimeoutOrNull(SOURCE_TIMEOUT) { source.getSearchManga(1, match.title, source.getFilterList()) }
                    ?: return@launchIO
            val remote = page.mangas.firstOrNull { it.url == match.url } ?: return@launchIO
            val local = networkToLocalManga(remote.toDomainManga(source.id))
            state.value.selected?.let { store.linkManga(source.id, remote.url, it.id) }
            _state.update { it.copy(openMangaId = local.id) }
        }
    }

    fun consumeOpenManga() = _state.update { it.copy(openMangaId = null) }

    @Immutable
    data class State(
        val query: String = "",
        val lastLoadedQuery: String = "__initial__",
        val sort: DiscoverSort = DiscoverSort.TRENDING,
        val items: List<AniListMedia> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val nextPage: Int = 1,
        val hasNextPage: Boolean = true,
        val selected: AniListMedia? = null,
        val sourceState: SourceSearchState = SourceSearchState.Idle,
        val openMangaId: Long? = null,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val MAX_PARALLEL_SOURCE_REQUESTS = 4
        const val MAX_SOURCE_PAGES = 2
        val SOURCE_TIMEOUT = 15.seconds
    }
}
