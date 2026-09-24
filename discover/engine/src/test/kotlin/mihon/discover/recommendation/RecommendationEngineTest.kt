package mihon.discover.recommendation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommendationEngineTest {
    private val engine = RecommendationEngine()

    private fun features(
        id: Long,
        genres: Set<String> = setOf("Action"),
        tags: Map<String, Int> = mapOf("Adventure" to 90),
        mean: Int? = 80,
        votes: Int? = 500,
    ) = RecommendationEngine.Features(id, genres, tags, mean, votes, 2020)

    @Test
    fun `small high score loses confidence to large strong score`() {
        val scarce = engine.quality(100, 1)!!
        val supported = engine.quality(85, 10_000)!!
        assertTrue(supported > scarce)
        assertEquals(null, engine.quality(90, null))
    }

    @Test
    fun `shared prominent tags outrank shared generic genre`() {
        val anchor = features(1)
        val sameTag = features(2, genres = setOf("Fantasy"))
        val onlyGenre = features(3, tags = mapOf("Cooking" to 90))
        assertTrue(engine.similarity(anchor, sameTag) > engine.similarity(anchor, onlyGenre))
    }

    @Test
    fun `romance as secondary genre is not excluded as primary`() {
        val anchor = features(1)
        val secondary = features(2, genres = setOf("Action", "Romance"))
        val primary = features(
            3,
            genres = setOf("Romance"),
            tags = mapOf("Love Triangle" to 90, "Dating" to 85),
        )
        val result = engine.rank(
            listOf(secondary, primary),
            listOf(RecommendationEngine.Seed(anchor, 4.0)),
            RecommendationEngine.Mode.PERSONAL,
            RecommendationEngine.RomancePolicy.EXCLUDE_MAIN,
        )
        assertTrue(result.any { it.id == 2L })
        assertFalse(result.any { it.id == 3L })
    }

    @Test
    fun `negative seed and explicit exclusions suppress familiar items`() {
        val liked = features(1)
        val disliked = features(2, tags = mapOf("Horror" to 95))
        val good = features(3)
        val bad = features(4, tags = mapOf("Horror" to 95))
        val excluded = features(5)
        val ranked = engine.rank(
            listOf(good, bad, excluded, liked),
            listOf(RecommendationEngine.Seed(liked, 4.0), RecommendationEngine.Seed(disliked, -4.0)),
            RecommendationEngine.Mode.PERSONAL,
            excluded = setOf(5),
        )
        assertEquals(3L, ranked.first().id)
        assertTrue(ranked.none { it.id in setOf(1L, 2L, 5L) })
    }

    @Test
    fun `explanations name real shared tags and show romance penalty`() {
        val seed = features(1, tags = mapOf("Adventure" to 90, "Dating" to 80), genres = setOf("Romance"))
        val candidate = features(2, tags = mapOf("Adventure" to 90, "Dating" to 80), genres = setOf("Romance"))
        val ranked = engine.rank(
            listOf(candidate),
            listOf(RecommendationEngine.Seed(seed, 4.0)),
            RecommendationEngine.Mode.SIMILAR,
        ).single()
        assertTrue(ranked.reasons.any { "Adventure" in it })
        assertTrue(ranked.reasons.any { "Romance" in it })
    }

    @Test
    fun `exploration brings a different theme into the first results`() {
        val seed = features(1)
        val related = listOf(features(2), features(3), features(4))
        val different = features(5, genres = setOf("Mystery"), tags = mapOf("Psychological" to 90))
        val candidates = related + different
        val personal = engine.rank(
            candidates,
            listOf(RecommendationEngine.Seed(seed, 4.0)),
            RecommendationEngine.Mode.PERSONAL,
        )
        val explore = engine.rank(
            candidates,
            listOf(RecommendationEngine.Seed(seed, 4.0)),
            RecommendationEngine.Mode.EXPLORE,
        )
        assertFalse(personal.take(2).any { it.id == different.id })
        assertTrue(explore.take(2).any { it.id == different.id })
    }

    @Test
    fun `missing vote count is not treated as a trusted rating`() {
        val seed = features(1)
        val unknown = features(2, mean = 100, votes = null)
        val known = features(3, mean = 85, votes = 5000)
        val ranked = engine.rank(
            listOf(unknown, known),
            listOf(RecommendationEngine.Seed(seed, 4.0)),
            RecommendationEngine.Mode.SIMILAR,
        )
        assertEquals(known.id, ranked.first().id)
        assertFalse(ranked.first().reasons.any { "100 votes" in it })
    }

    @Test
    fun `two different high romance tags count even when ranks tie`() {
        val work = features(
            1,
            genres = setOf("Romance"),
            tags = mapOf("Love Triangle" to 80, "Dating" to 80),
        )
        assertTrue(engine.romanceEvidence(work) >= 0.8)
    }

    @Test
    fun `library only mode can rank an already known work`() {
        val known = features(1)
        val seed = RecommendationEngine.Seed(known, 4.0)
        assertTrue(engine.rank(listOf(known), listOf(seed), RecommendationEngine.Mode.PERSONAL).isEmpty())
        assertEquals(
            known.id,
            engine.rank(
                listOf(known),
                listOf(seed),
                RecommendationEngine.Mode.PERSONAL,
                excludeKnown = false,
            ).single().id,
        )
    }
}
