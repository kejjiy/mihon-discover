package eu.kanade.tachiyomi.ui.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoverTitleMatcherTest {

    @Test
    fun `normalizes punctuation accents and spacing without losing numbers`() {
        assertEquals("one piece 2", DiscoverTitleMatcher.normalize("  ONE—PIÈCE: 2  "))
    }

    @Test
    fun `uses alternate titles and caps variants`() {
        val media = AniListMedia(
            id = 1,
            title = "My Manga",
            alternativeTitles = listOf("Mi Manga", "私のマンガ") + (1..10).map { "Variant $it" },
            coverUrl = null,
            description = null,
            genres = emptyList(),
            status = null,
            popularity = null,
            averageScore = null,
            chapters = null,
            startDate = null,
        )

        val variants = DiscoverTitleMatcher.variants(media)
        assertTrue("mi manga" in variants)
        assertEquals(DiscoverTitleMatcher.MAX_VARIANTS, variants.size)
    }
}
