package mihon.discover

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Public AniList GraphQL client. It never uses the tracker OAuth token. */
@Inject
@SingleIn(AppScope::class)
class AnilistCatalogApi(
    private val network: NetworkHelper,
    private val json: Json,
) {
    private val requestMutex = Mutex()
    private var nextRequestAt = 0L

    suspend fun browse(page: Int, query: String, sort: DiscoverSort): PageResult {
        val variables = buildString {
            append("{\"page\":$page")
            if (query.isNotBlank()) {
                append(",\"search\":")
                append(json.encodeToString(String.serializer(), query))
            }
            append(",\"sort\":[\"")
            append(sort.sort)
            append("\"]")
            if (sort == DiscoverSort.NEW_RELEASES) append(",\"today\":${todayFuzzy()}")
            append('}')
        }
        return request(variables)
    }

    suspend fun media(id: Long): AniListMedia? {
        val result = request("{\"id\":$id}", single = true)
        return result.items.firstOrNull()
    }

    private suspend fun request(variables: String, single: Boolean = false): PageResult {
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            try {
                awaitPermit()
                val query = if (single) SINGLE_QUERY else BROWSE_QUERY
                val payload = "{\"query\":${json.encodeToString(String.serializer(), query)},\"variables\":$variables}"
                val request = Request.Builder()
                    .url(API_URL)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val response = network.client.newCall(request).await()
                response.use { value ->
                    val retryAfter = value.header("Retry-After")?.toLongOrNull()
                    val resetAt = value.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                    if (value.code == 429) {
                        postpone(retryAfter?.times(1000) ?: resetAt?.minus(now()) ?: 60_000)
                        throw RateLimitedException()
                    }
                    if (!value.isSuccessful) throw IllegalStateException("AniList HTTP ${value.code}")
                    val root = json.parseToJsonElement(value.body.string()).jsonObject
                    val errors = root["errors"] as? JsonArray
                    if (!errors.isNullOrEmpty()) {
                        throw IllegalStateException(
                            errors.first().jsonObject["message"]?.jsonPrimitive?.content ?: "AniList error",
                        )
                    }
                    return parsePage(root, single)
                }
            } catch (e: Throwable) {
                lastFailure = e
                if (attempt < 2) delay((750L * (attempt + 1)).milliseconds)
            }
        }
        throw lastFailure ?: IllegalStateException("AniList request failed")
    }

    private suspend fun awaitPermit() = requestMutex.withLock {
        val wait = max(0, nextRequestAt - now())
        if (wait > 0) delay(wait.milliseconds)
        // 25 requests/minute is deliberately below AniList's public limit.
        nextRequestAt = now() + REQUEST_INTERVAL_MS
    }

    private suspend fun postpone(duration: Long) = requestMutex.withLock {
        nextRequestAt = max(nextRequestAt, now() + duration.coerceAtLeast(1_000))
    }

    private fun parsePage(root: JsonObject, single: Boolean): PageResult {
        val data = root["data"]!!.jsonObject
        val page = if (single) null else data["Page"]!!.jsonObject
        val media = if (single) listOfNotNull(data["Media"]) else page!!["media"]!!.jsonArray
        val hasNext = page?.get("pageInfo")?.jsonObject?.get("hasNextPage")?.jsonPrimitive?.content == "true"
        return PageResult(media.map { parseMedia(it.jsonObject) }, hasNext)
    }

    private fun parseMedia(item: JsonObject): AniListMedia {
        val titles = item["title"]!!.jsonObject
        val preferred = titles["userPreferred"].text().orEmpty()
        val alternatives = listOfNotNull(titles["romaji"].text(), titles["english"].text(), titles["native"].text()) +
            item["synonyms"].orEmptyArray().mapNotNull { it.jsonPrimitive.contentOrNull }
        return AniListMedia(
            id = item["id"]!!.jsonPrimitive.content.toLong(),
            title = preferred,
            alternativeTitles = alternatives.filter { it.isNotBlank() && it != preferred }.distinct(),
            coverUrl = item["coverImage"]?.jsonObject?.get("large").text(),
            description = item["description"].text(),
            genres = item["genres"].orEmptyArray().mapNotNull { it.jsonPrimitive.contentOrNull },
            status = item["status"].text(),
            popularity = item["popularity"].int(),
            averageScore = item["averageScore"].int(),
            chapters = item["chapters"].int(),
            startDate = item["startDate"]?.jsonObject?.get("year").int(),
        )
    }

    private fun JsonElement?.text() = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.int() = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.orEmptyArray() = this as? JsonArray ?: JsonArray(emptyList())
    private fun todayFuzzy(): Int {
        val date = java.time.LocalDate.now()
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }
    private fun now() = Clock.System.now().toEpochMilliseconds()

    data class PageResult(val items: List<AniListMedia>, val hasNextPage: Boolean)
    private class RateLimitedException : Exception()

    private companion object {
        const val API_URL = "https://graphql.anilist.co"
        const val REQUEST_INTERVAL_MS = 2_400L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val FIELDS = """
            id title { userPreferred romaji english native } synonyms coverImage { large } description
            genres status popularity averageScore chapters startDate { year }
        """
        const val BROWSE_QUERY = """
            query (${'$'}page: Int!, ${'$'}search: String, ${'$'}sort: [MediaSort], ${'$'}today: FuzzyDateInt) {
              Page(page: ${'$'}page, perPage: 20) {
                pageInfo { hasNextPage }
                media(type: MANGA, format_not_in: [NOVEL], isAdult: false,
                  search: ${'$'}search, startDate_lesser: ${'$'}today, sort: ${'$'}sort) { $FIELDS }
              }
            }
        """
        const val SINGLE_QUERY = """
            query (${'$'}id: Int!) { Media(id: ${'$'}id, type: MANGA) { $FIELDS } }
        """
    }
}
