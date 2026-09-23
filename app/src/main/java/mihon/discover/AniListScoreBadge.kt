package mihon.discover

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.Json
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Self-contained Mihon-screen integration for score data discovered from this fork's catalogue.
 */
@Composable
fun AniListScoreBadge(
    manga: Manga,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val score = remember(manga.source, manga.url) {
        DiscoverCache.scoreFor(context, manga.source, manga.url)
    }
    score?.let {
        Text(
            text = "AniList $it/100",
            style = MaterialTheme.typography.labelLarge,
            modifier = modifier,
        )
    }
}

internal object DiscoverCache {
    private const val PREFS_NAME = "discover_catalogue"
    private const val MISSING_ID = -1L

    fun scoreFor(context: Context, sourceId: Long, url: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getLong("link:$sourceId:$url", MISSING_ID)
        if (id == MISSING_ID) return null

        val key = "media:$id"
        val cachedAt = prefs.getLong("$key:at", 0L)
        if (Clock.System.now().toEpochMilliseconds() - cachedAt > 24.hours.inWholeMilliseconds) return null

        val encoded = prefs.getString(key, null) ?: return null
        return runCatching {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }.decodeFromString<AniListMedia>(encoded).averageScore
        }.getOrNull()
    }
}
