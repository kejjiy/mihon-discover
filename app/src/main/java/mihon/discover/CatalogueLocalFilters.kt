package mihon.discover

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.first
import mihon.discover.recommendation.RecommendationStore
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository

/** Local Mihon signals shared by Explorer and the recommendation modes. */
@Inject
class CatalogueLocalFilters(
    private val mangaRepository: MangaRepository,
    private val trackRepository: TrackRepository,
    private val trackerManager: TrackerManager,
    private val recommendations: RecommendationStore,
    private val legacy: DiscoverStore,
) {
    data class Snapshot(
        val library: Set<Long> = emptySet(),
        val started: Set<Long> = emptySet(),
        val liked: Set<Long> = emptySet(),
        val disliked: Set<Long> = emptySet(),
    )

    suspend fun snapshot(filters: CatalogueFilters): Snapshot {
        if (!filters.hideKnownLibrary && !filters.onlyKnownLibrary && !filters.hideKnownStarted &&
            !filters.onlyLiked && !filters.excludeDisliked
        ) {
            return Snapshot()
        }
        val feedback = if (filters.onlyLiked || filters.excludeDisliked) recommendations.allFeedback() else emptyMap()
        if (!filters.hideKnownLibrary && !filters.onlyKnownLibrary && !filters.hideKnownStarted) {
            return Snapshot(
                liked = feedback.filterValues { it.vote > 0 }.keys,
                disliked = feedback.filterValues { it.vote < 0 }.keys,
            )
        }
        val tracks = trackRepository.getTracksAsFlow().first()
            .filter { it.trackerId == trackerManager.aniList.id && it.remoteId > 0 }
            .associate { it.mangaId to it.remoteId }
        val library = mutableSetOf<Long>()
        val started = mutableSetOf<Long>()
        mangaRepository.getLibraryManga().forEach { item ->
            val id = tracks[item.manga.id]
                ?: recommendations.linkedId(item.manga.source, item.manga.url)
                ?: legacy.linkedMediaId(item.manga.source, item.manga.url)
            if (id != null) {
                library += id
                if (item.readCount > 0) started += id
            }
        }
        return Snapshot(
            library = library,
            started = started,
            liked = feedback.filterValues { it.vote > 0 }.keys,
            disliked = feedback.filterValues { it.vote < 0 }.keys,
        )
    }

    fun accepts(id: Long, filters: CatalogueFilters, snapshot: Snapshot): Boolean =
        (!filters.hideKnownLibrary || id !in snapshot.library) &&
            (!filters.onlyKnownLibrary || id in snapshot.library) &&
            (!filters.hideKnownStarted || id !in snapshot.started) &&
            (!filters.onlyLiked || id in snapshot.liked) &&
            (!filters.excludeDisliked || id !in snapshot.disliked)
}
