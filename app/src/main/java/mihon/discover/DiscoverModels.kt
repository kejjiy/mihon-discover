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
    val isAdult: Boolean? = null,
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
    /** The same predicate is used for cached recommendations and for AniList catalogue pages. */
    fun accepts(media: AniListMedia): Boolean {
        val visibleTags = media.tags.filterNot { it.isSpoiler }
        val rankedTags = visibleTags.filter { it.rank >= minimumTagRank }
        val tagNames = rankedTags.mapTo(mutableSetOf()) { it.name }
        val quality = RecommendationEngine().quality(media.meanScore ?: media.averageScore, media.voteCount)
        return (formats.isEmpty() || media.format in formats) &&
            (statuses.isEmpty() || media.status in statuses) &&
            (countries.isEmpty() || media.countryOfOrigin in countries) &&
            (
                genres.isEmpty() ||
                    if (requireAllGenres) media.genres.containsAll(genres) else media.genres.any { it in genres }
                ) &&
            media.genres.none { it in excludedGenres } &&
            (tags.isEmpty() || if (requireAllTags) tagNames.containsAll(tags) else tagNames.any { it in tags }) &&
            visibleTags.none { it.name in excludedTags } &&
            (tagCategories.isEmpty() || rankedTags.any { it.category in tagCategories }) &&
            (origins.isEmpty() || media.source in origins) &&
            (minScore == null || (media.averageScore ?: -1) >= minScore) &&
            (minPopularity == null || (media.popularity ?: -1) >= minPopularity) &&
            (minVotes == null || (media.voteCount ?: -1) >= minVotes) &&
            (minFavourites == null || (media.favourites ?: -1) >= minFavourites) &&
            (minQuality == null || (quality ?: -1.0) * 100 >= minQuality) &&
            (startYear == null || (media.startDate ?: -1) >= startYear) &&
            (endYear == null || (media.startDate ?: Int.MAX_VALUE) <= endYear) &&
            (minChapters == null || (media.chapters ?: -1) >= minChapters) &&
            (maxChapters == null || (media.chapters ?: Int.MAX_VALUE) <= maxChapters) &&
            (minVolumes == null || (media.volumes ?: -1) >= minVolumes) &&
            (!longStrip || visibleTags.any { it.name == "Long Strip" }) &&
            (!fullColor || visibleTags.any { it.name == "Full Color" }) &&
            (isLicensed == null || media.isLicensed == isLicensed) &&
            (!excludeAdult || media.isAdult != true) &&
            (!excludeMainRomance || RecommendationEngine().romanceEvidence(media.features()) < 0.8)
    }

    fun remoteOnly(): CatalogueFilters = copy(
        hideKnownLibrary = false,
        onlyKnownLibrary = false,
        hideKnownStarted = false,
        onlyLiked = false,
        excludeDisliked = false,
    )
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
