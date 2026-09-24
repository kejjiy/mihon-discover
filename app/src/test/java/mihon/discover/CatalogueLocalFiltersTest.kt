package mihon.discover

import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.mockk
import mihon.discover.recommendation.RecommendationStore
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository

class CatalogueLocalFiltersTest {
    private val local = CatalogueLocalFilters(
        mockk<MangaRepository>(),
        mockk<TrackRepository>(),
        mockk<TrackerManager>(),
        mockk<RecommendationStore>(),
        mockk<DiscoverStore>(),
    )

    @Test
    fun `local library and feedback rules are shared by catalogue and recommendations`() {
        val filters = CatalogueFilters(hideKnownLibrary = true, onlyLiked = true, excludeDisliked = true)
        val snapshot = CatalogueLocalFilters.Snapshot(
            library = setOf(1),
            liked = setOf(1, 2),
            disliked = setOf(3),
        )
        assertFalse(local.accepts(1, filters, snapshot))
        assertTrue(local.accepts(2, filters, snapshot))
        assertFalse(local.accepts(3, filters, snapshot))
    }

    @Test
    fun `started filter removes only identified started works`() {
        val filters = CatalogueFilters(hideKnownStarted = true)
        val snapshot = CatalogueLocalFilters.Snapshot(started = setOf(10))
        assertFalse(local.accepts(10, filters, snapshot))
        assertTrue(local.accepts(11, filters, snapshot))
    }
}
