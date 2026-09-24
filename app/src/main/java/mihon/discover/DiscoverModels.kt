package mihon.discover

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import mihon.discover.recommendation.RecommendationEngine
import mihon.discover.recommendation.features

@Immutable
@Serializable
data class AniListMedia(
    val id: Long,
    val title: String,
    val alternativeTitles: List<String>,
    val coverUrl: String?,
    val description: String?,
    val genres: List<String>,
    val status: String?,
    val popularity: Int?,
    val averageScore: Int?,
    val chapters: Int?,
    val startDate: Int?,
    val format: String? = null,
    val countryOfOrigin: String? = null,
    val meanScore: Int? = null,
    val voteCount: Int? = null,
    val favourites: Int? = null,
    val tags: List<AniListTag> = emptyList(),
    val volumes: Int? = null,
    val endYear: Int? = null,
    val source: String? = null,
    val isLicensed: Boolean? = null,
)

@Immutable
@Serializable
data class AniListTag(
    val id: Int,
    val name: String,
    val category: String,
    val rank: Int,
    val isSpoiler: Boolean = false,
)

@Immutable
@Serializable
data class CatalogueFilters(
    val formats: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val countries: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val requireAllGenres: Boolean = false,
    val requireAllTags: Boolean = false,
    val excludedTags: Set<String> = emptySet(),
    val tagCategories: Set<String> = emptySet(),
    val minimumTagRank: Int = 18,
    val minScore: Int? = null,
    val minPopularity: Int? = null,
    val minVotes: Int? = null,
    val minFavourites: Int? = null,
    val minQuality: Int? = null,
    val startYear: Int? = null,
    val endYear: Int? = null,
    val minChapters: Int? = null,
    val maxChapters: Int? = null,
    val minVolumes: Int? = null,
    val origins: Set<String> = emptySet(),
    val longStrip: Boolean = false,
    val fullColor: Boolean = false,
    val isLicensed: Boolean? = null,
    val excludeMainRomance: Boolean = false,
    val hideKnownLibrary: Boolean = false,
    val onlyKnownLibrary: Boolean = false,
    val hideKnownStarted: Boolean = false,
    val onlyLiked: Boolean = false,
    val excludeDisliked: Boolean = false,
    val excludeAdult: Boolean = true,
) {
    fun accepts(media: AniListMedia): Boolean =
        (minVotes == null || (media.voteCount ?: 0) >= minVotes) &&
            (minFavourites == null || (media.favourites ?: 0) >= minFavourites) &&
            (
                minQuality == null ||
                    (
                        RecommendationEngine().quality(media.meanScore ?: media.averageScore, media.voteCount)
                            ?: 0.0
                        ) * 100 >= minQuality
                ) &&
            (endYear == null || (media.startDate ?: Int.MAX_VALUE) <= endYear) &&
            (!requireAllGenres || media.genres.containsAll(genres)) &&
            (!requireAllTags || media.tags.map { it.name }.containsAll(tags)) &&
            (!longStrip || media.tags.any { it.name == "Long Strip" }) &&
            (!fullColor || media.tags.any { it.name == "Full Color" }) &&
            (!excludeMainRomance || RecommendationEngine().romanceEvidence(media.features()) < 0.8)
}

enum class DiscoverSort(val label: String, val sort: String) {
    TRENDING("Trending", "TRENDING_DESC"),
    POPULAR("Popular", "POPULARITY_DESC"),
    TOP_RATED("Top rated", "SCORE_DESC"),
    NEW_RELEASES("New releases", "START_DATE_DESC"),
}

@Immutable
@Serializable
data class SourceMatch(
    val sourceId: Long,
    val sourceName: String,
    val language: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val chapterCount: Int? = null,
    val confidence: Confidence,
    val partial: Boolean = false,
) {
    @Serializable
    enum class Confidence { EXACT, CANDIDATE }
}

@Serializable
sealed interface SourceSearchState {
    @Serializable
    data object Idle : SourceSearchState

    @Serializable
    data class Loading(
        val completed: Int,
        val total: Int,
        val matches: List<SourceMatch> = emptyList(),
        val errors: List<String> = emptyList(),
    ) : SourceSearchState

    @Serializable
    data class Complete(
        val matches: List<SourceMatch>,
        val partial: Boolean,
        val errors: List<String> = emptyList(),
    ) : SourceSearchState

    @Serializable
    data class Failed(val message: String) : SourceSearchState
}
