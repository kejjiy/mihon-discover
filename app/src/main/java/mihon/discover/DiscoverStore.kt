package mihon.discover

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * A small persistent cache for AniList metadata and source matches.
 *
 * The cache deliberately records only completed searches. Cancellations and failures are not
 * persisted, so a temporary extension or network failure can never be mistaken for no result.
 */
@Inject
@SingleIn(AppScope::class)
class DiscoverStore(
    context: Context,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences("discover_catalogue", Context.MODE_PRIVATE)

    fun getMedia(id: Long): AniListMedia? = read("media:$id", META_TTL)
    fun putMedia(media: AniListMedia) = write("media:${media.id}", media)

    fun getMatches(mediaId: Long, sourceIds: Set<Long>): SourceSearchState.Complete? {
        val searched = read<List<Long>>("sources:$mediaId", POSITIVE_TTL)?.toSet() ?: return null
        if (!searched.containsAll(sourceIds)) return null
        return read("matches:$mediaId", POSITIVE_TTL) ?: read("empty:$mediaId", NEGATIVE_TTL)
    }

    fun putMatches(mediaId: Long, sourceIds: Set<Long>, value: SourceSearchState.Complete) {
        val key = if (value.matches.isEmpty() && !value.partial) "empty:$mediaId" else "matches:$mediaId"
        write(key, value)
        write("sources:$mediaId", sourceIds.toList())
    }

    fun clearMatches(mediaId: Long) {
        prefs.edit().remove("matches:$mediaId").remove("empty:$mediaId").remove("sources:$mediaId").apply()
    }

    fun linkManga(sourceId: Long, url: String, mediaId: Long) {
        prefs.edit().putLong("link:$sourceId:$url", mediaId).apply()
    }

    private inline fun <reified T> read(key: String, ttl: kotlin.time.Duration): T? {
        val encoded = prefs.getString(key, null) ?: return null
        val timestamp = prefs.getLong("$key:at", 0L)
        if (Clock.System.now().toEpochMilliseconds() - timestamp > ttl.inWholeMilliseconds) {
            prefs.edit().remove(key).remove("$key:at").apply()
            return null
        }
        return runCatching { json.decodeFromString<T>(encoded) }.getOrNull()
    }

    private inline fun <reified T> write(key: String, value: T) {
        prefs.edit()
            .putString(key, json.encodeToString(value))
            .putLong("$key:at", Clock.System.now().toEpochMilliseconds())
            .apply()
    }

    private companion object {
        val META_TTL = 24.hours
        val POSITIVE_TTL = 7.days
        val NEGATIVE_TTL = 6.hours
    }
}
