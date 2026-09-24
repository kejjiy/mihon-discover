package mihon.discover.recommendation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.discover.AniListMedia
import mihon.discover.AnilistCatalogApi
import mihon.discover.CatalogueFilters
import mihon.discover.DiscoverSort
import mihon.discover.DiscoverStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class RecommendationViewModel(
    private val api: AnilistCatalogApi,
    private val mediaStore: DiscoverStore,
    private val store: RecommendationStore,
    private val mangaRepository: MangaRepository,
    private val historyRepository: HistoryRepository,
    private val trackRepository: TrackRepository,
    private val trackerManager: TrackerManager,
) : ViewModel() {
    data class Item(val media: AniListMedia, val ranking: RecommendationEngine.Ranked)
    data class State(
        val mode: RecommendationEngine.Mode = RecommendationEngine.Mode.PERSONAL,
        val items: List<Item> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val partial: Boolean = false,
        val selected: AniListMedia? = null,
        val feedback: RecommendationStore.Feedback = RecommendationStore.Feedback(0, null, false),
        val romancePolicy: RecommendationEngine.RomancePolicy = RecommendationEngine.RomancePolicy.PENALIZE,
    )

    private val engine = RecommendationEngine()
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var job: Job? = null

    fun setMode(mode: RecommendationEngine.Mode) {
        mutableState.update { it.copy(mode = mode, selected = null) }
        refresh()
    }

    fun similar(media: AniListMedia) {
        mutableState.update { it.copy(mode = RecommendationEngine.Mode.SIMILAR, selected = media) }
        refresh()
    }

    fun similarById(id: Long) {
        viewModelScope.launchIO {
            val media = store.getMedia(id) ?: mediaStore.getMedia(id) ?: api.media(id) ?: return@launchIO
            store.putMedia(media)
            similar(media)
        }
    }

    fun select(media: AniListMedia) {
        viewModelScope.launchIO {
            mutableState.update { it.copy(selected = media, feedback = store.feedback(media.id)) }
        }
    }

    fun vote(value: Int) {
        val media = state.value.selected ?: return
        viewModelScope.launchIO {
            store.setFeedback(media.id, vote = value)
            mutableState.update { it.copy(feedback = store.feedback(media.id)) }
            refresh()
        }
    }

    fun setReadingStatus(status: String) {
        val media = state.value.selected ?: return
        viewModelScope.launchIO {
            store.setFeedback(media.id, status = status)
            mutableState.update { it.copy(feedback = store.feedback(media.id)) }
            refresh()
        }
    }

    fun hideSelected() {
        val media = state.value.selected ?: return
        viewModelScope.launchIO {
            store.setFeedback(media.id, hidden = true)
            mutableState.update { it.copy(feedback = store.feedback(media.id)) }
            refresh()
        }
    }

    fun setRomancePolicy(value: RecommendationEngine.RomancePolicy) {
        viewModelScope.launchIO {
            store.setRomancePolicy(value.name)
            mutableState.update { it.copy(romancePolicy = value) }
            refresh()
        }
    }

    suspend fun exportPersonalData(): String = withContext(Dispatchers.IO) {
        store.migrateLegacyCache()
        store.exportPersonalData()
    }

    suspend fun importPersonalData(content: String) = withContext(Dispatchers.IO) {
        store.importPersonalData(content)
        refresh(force = true)
    }

    fun clearMetadataCache() {
        viewModelScope.launchIO {
            store.clearMetadataCache()
            refresh(force = true)
        }
    }

    fun clearPersonalData() {
        viewModelScope.launchIO {
            store.clearPersonalData()
            refresh()
        }
    }

    fun refresh(force: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launchIO {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                store.migrateLegacyCache()
                val snapshot = state.value
                val seeds = if (snapshot.mode == RecommendationEngine.Mode.SIMILAR) {
                    snapshot.selected?.let { listOf(RecommendationEngine.Seed(it.features(), 4.0)) }.orEmpty()
                } else {
                    localSeeds()
                }
                val candidates = store.recentMedia().associateByTo(linkedMapOf()) { it.id }
                var partial = false
                val searches = buildList {
                    add(DiscoverSort.TRENDING to CatalogueFilters())
                    add(DiscoverSort.POPULAR to CatalogueFilters())
                    add(DiscoverSort.TOP_RATED to CatalogueFilters())
                    add(DiscoverSort.NEW_RELEASES to CatalogueFilters())
                    if (snapshot.mode != RecommendationEngine.Mode.SIMILAR) {
                        seeds.filter { it.weight > 0 }
                            .flatMap { it.features.tags.entries }
                            .groupBy { it.key }
                            .mapValues { (_, tags) -> tags.sumOf { it.value } }
                            .entries.sortedByDescending { it.value }.take(3)
                            .forEach { add(DiscoverSort.POPULAR to CatalogueFilters(tags = setOf(it.key))) }
                    } else {
                        snapshot.selected?.tags?.filterNot { it.isSpoiler }?.sortedByDescending { it.rank }
                            ?.take(3)?.forEach { add(DiscoverSort.POPULAR to CatalogueFilters(tags = setOf(it.name))) }
                    }
                }
                val lastRefresh = store.option("recommendation_catalogue_at")?.toLongOrNull() ?: 0L
                if (force || candidates.size < 50 || System.currentTimeMillis() - lastRefresh > 86_400_000L) {
                    for ((sort, filters) in searches) {
                        try {
                            val page = api.browse(1, "", sort, filters, perPage = 50)
                            page.items.forEach { media ->
                                candidates[media.id] = media
                                store.putMedia(media)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            partial = true
                        }
                    }
                    store.pruneMedia()
                    if (!partial) store.setOption("recommendation_catalogue_at", System.currentTimeMillis().toString())
                }
                val feedback = store.allFeedback()
                val excluded = feedback.filterValues { it.hidden || it.vote < 0 }.keys
                val ranked = engine.rank(
                    candidates.values.map { it.features() },
                    seeds,
                    snapshot.mode,
                    store.romancePolicy(),
                    excluded,
                )
                mutableState.update {
                    it.copy(
                        items = ranked.mapNotNull { rank -> candidates[rank.id]?.let { media -> Item(media, rank) } },
                        romancePolicy = store.romancePolicy(),
                        partial = partial,
                        loading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "Recommandations indisponibles", loading = false) }
            }
        }
    }

    private suspend fun localSeeds(): List<RecommendationEngine.Seed> {
        val library = mangaRepository.getLibraryManga()
        val tracks = trackRepository.getTracksAsFlow().first().groupBy { it.mangaId }
        val history = historyRepository.getHistory("").first().groupBy { it.mangaId }
        val result = mutableListOf<RecommendationEngine.Seed>()
        library.forEach { entry ->
            val manga = entry.manga
            val aniListTrack = tracks[manga.id]?.firstOrNull { it.trackerId == trackerManager.aniList.id }
            val id = aniListTrack?.remoteId?.takeIf { it > 0 }
                ?: store.linkedId(manga.source, manga.url)
                ?: mediaStore.linkedMediaId(manga.source, manga.url)
            val media = id?.let { store.getMedia(it) ?: mediaStore.getMedia(it) }
            val features = media?.features() ?: RecommendationEngine.Features(
                id = -manga.id,
                genres = manga.genre.orEmpty().toSet(),
                tags = emptyMap(),
                meanScore = null,
                voteCount = null,
                startYear = null,
                title = manga.title,
            )
            val feedback = id?.let(store::feedback)
            val explicit = feedback?.vote ?: 0
            val trackerScore = tracks[manga.id].orEmpty().mapNotNull { track ->
                if (track.score <= 0) return@mapNotNull null
                val tracker = trackerManager.get(track.trackerId) ?: return@mapNotNull null
                ((tracker.get10PointScore(track) - 5.0) * 0.6).coerceIn(-3.0, 3.0)
            }.takeIf { it.isNotEmpty() }?.average()
            val implicit = when {
                entry.readCount <= 0 -> 0.15
                entry.totalChapters > 0 && entry.readCount >= entry.totalChapters -> 1.0
                else ->
                    0.25 +
                        0.75 * (entry.readCount.toDouble() / entry.totalChapters.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            }
            val readHistory = history[manga.id].orEmpty()
            val latestRead = readHistory.maxOfOrNull { it.readAt?.time ?: 0L } ?: 0L
            val recent = latestRead > System.currentTimeMillis() - 90L * 86_400_000L
            val historyWeight = if (recent && readHistory.isNotEmpty()) 0.35 else 0.0
            val aniListStatus = aniListTrack?.status
            val weight = when {
                explicit > 0 -> 4.0
                explicit < 0 -> -4.0
                aniListStatus == Anilist.DROPPED -> -1.5
                trackerScore != null -> trackerScore
                feedback?.status == "ABANDONED" -> -1.0
                feedback?.status == "COMPLETED" -> 1.0
                aniListStatus == Anilist.COMPLETED -> 1.0
                else -> implicit + historyWeight
            }
            result += RecommendationEngine.Seed(features, weight)
        }
        mangaRepository.getReadMangaNotInLibrary().forEach { manga ->
            result += RecommendationEngine.Seed(
                RecommendationEngine.Features(
                    -manga.id,
                    manga.genre.orEmpty().toSet(),
                    emptyMap(),
                    null,
                    null,
                    null,
                    title = manga.title,
                ),
                0.5,
            )
        }
        store.allFeedback().forEach { (id, feedback) ->
            if (library.any { entry ->
                    store.linkedId(entry.manga.source, entry.manga.url) == id ||
                        mediaStore.linkedMediaId(entry.manga.source, entry.manga.url) == id
                }
            ) {
                return@forEach
            }
            val media = store.getMedia(id) ?: mediaStore.getMedia(id) ?: return@forEach
            val weight = when {
                feedback.vote > 0 -> 4.0
                feedback.vote < 0 -> -4.0
                feedback.status == "ABANDONED" -> -1.0
                feedback.status == "COMPLETED" -> 1.0
                else -> 0.0
            }
            if (weight != 0.0) result += RecommendationEngine.Seed(media.features(), weight)
        }
        return result
    }
}

internal fun AniListMedia.features() = RecommendationEngine.Features(
    id = id,
    genres = genres.toSet(),
    tags = tags.filterNot { it.isSpoiler }.associate { it.name to it.rank },
    meanScore = meanScore ?: averageScore,
    voteCount = voteCount,
    startYear = startDate,
    romanceTags = tags.filter { !it.isSpoiler && it.category == "Theme-Romance" }
        .associate { it.name to it.rank },
    title = title,
)
