package mihon.discover

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogueFiltersTest {
    private val media = AniListMedia(
        id = 1,
        title = "Example",
        alternativeTitles = emptyList(),
        coverUrl = null,
        description = null,
        genres = listOf("Action", "Fantasy"),
        status = "RELEASING",
        popularity = 500,
        averageScore = 80,
        chapters = 20,
        startDate = 2022,
        meanScore = 78,
        voteCount = 1000,
        tags = listOf(AniListTag(1, "Adventure", "Theme", 90)),
    )

    @Test
    fun `all genres and tags require every requested value`() {
        assertTrue(CatalogueFilters(genres = setOf("Action", "Fantasy"), requireAllGenres = true).accepts(media))
        assertFalse(CatalogueFilters(genres = setOf("Action", "Romance"), requireAllGenres = true).accepts(media))
        assertFalse(CatalogueFilters(tags = setOf("Adventure", "Mystery"), requireAllTags = true).accepts(media))
    }

    @Test
    fun `unknown vote count fails minimum reliability thresholds`() {
        assertTrue(CatalogueFilters(minVotes = 500, minQuality = 70).accepts(media))
        assertFalse(CatalogueFilters(minVotes = 1500).accepts(media))
        assertFalse(CatalogueFilters(minVotes = 1).accepts(media.copy(voteCount = null)))
    }

    @Test
    fun `secondary romance does not trigger primary romance exclusion`() {
        assertTrue(CatalogueFilters(excludeMainRomance = true).accepts(media.copy(genres = media.genres + "Romance")))
        assertFalse(
            CatalogueFilters(excludeMainRomance = true).accepts(
                media.copy(
                    genres = listOf("Romance"),
                    tags = listOf(AniListTag(2, "Love Triangle", "Theme-Romance", 95)),
                ),
            ),
        )
    }
}
