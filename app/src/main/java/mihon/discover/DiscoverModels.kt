package mihon.discover

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

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
)

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
    data class Loading(val completed: Int, val total: Int) : SourceSearchState

    @Serializable
    data class Complete(val matches: List<SourceMatch>, val partial: Boolean) : SourceSearchState

    @Serializable
    data class Failed(val message: String) : SourceSearchState
}
