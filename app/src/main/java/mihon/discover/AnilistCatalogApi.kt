package mihon.discover

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
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
    private val client = network.client.newBuilder().addInterceptor(AniListSharedRateInterceptor).build()
    private val requestMutex = Mutex()
    private var nextRequestAt = 0L
    private var cachedOptions: FilterOptions? = null

    data class FilterOptions(val genres: List<String>, val tags: List<AniListTag>)

    suspend fun filterOptions(): FilterOptions {
        cachedOptions?.let { return it }
        awaitPermit()
        val body = "{\"query\":${json.encodeToString(String.serializer(), OPTIONS_QUERY)}}"
        val request = Request.Builder().url(API_URL).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("AniList HTTP ${response.code}")
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            if ((root["errors"] as? JsonArray)?.isNotEmpty() == true) {
                throw IllegalStateException("AniList filter options unavailable")
            }
            val data = root["data"]?.jsonObject ?: throw IllegalStateException("AniList filter options unavailable")
            FilterOptions(
                data["GenreCollection"].orEmptyArray().mapNotNull { it.jsonPrimitive.contentOrNull },
                data["MediaTagCollection"].orEmptyArray().map { value ->
                    val tag = value.jsonObject
                    AniListTag(
                        id = tag["id"].int() ?: 0,
                        name = tag["name"].text().orEmpty(),
                        category = tag["category"].text().orEmpty(),
                        rank = 0,
                    )
                },
            ).also { cachedOptions = it }
        }
    }

    suspend fun browse(
        page: Int,
        query: String,
        sort: DiscoverSort,
        filters: CatalogueFilters = CatalogueFilters(),
        perPage: Int = 20,
    ): PageResult {
        val variables = buildString {
            append("{\"page\":$page,\"perPage\":${perPage.coerceIn(1, 50)}")
            if (query.isNotBlank()) {
                append(",\"search\":")
                append(json.encodeToString(String.serializer(), query))
            }
            append(",\"sort\":[\"")
            append(sort.sort)
            append("\"]")
            if (sort == DiscoverSort.NEW_RELEASES) append(",\"today\":${todayFuzzy()}")
            fun strings(name: String, values: Set<String>) {
                if (values.isNotEmpty()) {
                    val encoded = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(String.serializer()),
                        values.sorted(),
                    )
                    append(",\"$name\":$encoded")
                }
            }
            strings("formats", filters.formats)
            strings("statuses", filters.statuses)
            strings("countries", filters.countries)
            strings("genres", filters.genres)
            strings("excludedGenres", filters.excludedGenres)
            strings("tags", filters.tags)
            strings("excludedTags", filters.excludedTags)
            strings("tagCategories", filters.tagCategories)
            strings("origins", filters.origins)
            append(",\"minimumTagRank\":${filters.minimumTagRank.coerceIn(0, 100)}")
            filters.minScore?.let { append(",\"minScore\":${it.coerceIn(0, 100) - 1}") }
            filters.minPopularity?.let { append(",\"minPopularity\":${it.coerceAtLeast(0) - 1}") }
            filters.startYear?.let { append(",\"startAfter\":${(it - 1) * 10000 + 1231}") }
            filters.minChapters?.let { append(",\"chaptersAfter\":${it.coerceAtLeast(0) - 1}") }
            filters.maxChapters?.let { append(",\"chaptersBefore\":${it.coerceAtLeast(0) + 1}") }
            filters.minVolumes?.let { append(",\"volumesAfter\":${it.coerceAtLeast(0) - 1}") }
            filters.isLicensed?.let { append(",\"licensed\":$it") }
            if (filters.excludeAdult) append(",\"adult\":false")
            append('}')
        }
        return request(variables).let { it.copy(items = it.items.filter(filters::accepts)) }
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
                val response = client.newCall(request).await()
                response.use { value ->
                    val retryAfter = value.header("Retry-After")?.toLongOrNull()
                    val resetAt = value.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                    if (value.code == 429) {
                        postpone(retryAfter?.times(1000) ?: resetAt?.minus(now()) ?: 60_000)
                        throw RateLimitedException()
                    }
                    if (!value.isSuccessful) {
                        if (value.code in 500..599) throw IllegalStateException("AniList HTTP ${value.code}")
                        throw PermanentApiException("AniList HTTP ${value.code}")
                    }
                    val root = json.parseToJsonElement(value.body.string()).jsonObject
                    val errors = root["errors"] as? JsonArray
                    if (!errors.isNullOrEmpty()) {
                        val error = errors.first().jsonObject
                        val message = error["message"]?.jsonPrimitive?.content ?: "AniList error"
                        if (error["status"].int() == 429) {
                            postpone(retryAfter?.times(1000) ?: resetAt?.minus(now()) ?: 60_000)
                            throw RateLimitedException()
                        }
                        throw PermanentApiException(message)
                    }
                    if (value.header("X-RateLimit-Remaining") == "0" && resetAt != null) {
                        postpone(resetAt - now())
                    }
                    return parsePage(root, single)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PermanentApiException) {
                throw e
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
            format = item["format"].text(),
            countryOfOrigin = item["countryOfOrigin"].text(),
            meanScore = item["meanScore"].int(),
            voteCount = item["stats"]?.jsonObject?.get("scoreDistribution")?.let { distribution ->
                distribution.orEmptyArray().sumOf { it.jsonObject["amount"].int() ?: 0 }
            },
            favourites = item["favourites"].int(),
            tags = item["tags"].orEmptyArray().map { tag ->
                val value = tag.jsonObject
                AniListTag(
                    id = value["id"].int() ?: 0,
                    name = value["name"].text().orEmpty(),
                    category = value["category"].text().orEmpty(),
                    rank = value["rank"].int() ?: 0,
                    isSpoiler = value["isMediaSpoiler"]?.jsonPrimitive?.content == "true" ||
                        value["isGeneralSpoiler"]?.jsonPrimitive?.content == "true",
                )
            },
            volumes = item["volumes"].int(),
            endYear = item["endDate"]?.jsonObject?.get("year").int(),
            source = item["source"].text(),
            isLicensed = item["isLicensed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            isAdult = item["isAdult"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
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
    private class PermanentApiException(message: String) : Exception(message)

    private companion object {
        const val API_URL = "https://graphql.anilist.co"
        const val REQUEST_INTERVAL_MS = 2_400L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val OPTIONS_QUERY = """
            query { GenreCollection MediaTagCollection { id name category } }
        """
        const val FIELDS = """
            id title { userPreferred romaji english native } synonyms coverImage { large } description
            genres status popularity averageScore meanScore favourites stats { scoreDistribution { amount } }
            chapters volumes startDate { year } endDate { year } format countryOfOrigin source isLicensed isAdult
            tags { id name category rank isMediaSpoiler isGeneralSpoiler }
        """
        const val BROWSE_QUERY = """
            query (${'$'}page: Int!, ${'$'}perPage: Int!, ${'$'}search: String, ${'$'}sort: [MediaSort], ${'$'}today: FuzzyDateInt,
              ${'$'}formats: [MediaFormat], ${'$'}statuses: [MediaStatus], ${'$'}countries: [CountryCode],
              ${'$'}genres: [String], ${'$'}excludedGenres: [String], ${'$'}tags: [String],
              ${'$'}excludedTags: [String], ${'$'}tagCategories: [String], ${'$'}minimumTagRank: Int,
              ${'$'}origins: [MediaSource],
              ${'$'}minScore: Int, ${'$'}minPopularity: Int, ${'$'}startAfter: FuzzyDateInt,
              ${'$'}chaptersAfter: Int, ${'$'}chaptersBefore: Int, ${'$'}volumesAfter: Int,
              ${'$'}licensed: Boolean, ${'$'}adult: Boolean) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(type: MANGA, format_not_in: [NOVEL], isAdult: ${'$'}adult,
                  search: ${'$'}search, startDate_lesser: ${'$'}today, sort: ${'$'}sort,
                  format_in: ${'$'}formats, status_in: ${'$'}statuses, countryOfOrigin_in: ${'$'}countries,
                  genre_in: ${'$'}genres, genre_not_in: ${'$'}excludedGenres,
                  tag_in: ${'$'}tags, tag_not_in: ${'$'}excludedTags, tagCategory_in: ${'$'}tagCategories,
                  source_in: ${'$'}origins,
                  minimumTagRank: ${'$'}minimumTagRank, averageScore_greater: ${'$'}minScore,
                  popularity_greater: ${'$'}minPopularity, startDate_greater: ${'$'}startAfter,
                  chapters_greater: ${'$'}chaptersAfter, chapters_lesser: ${'$'}chaptersBefore,
                  volumes_greater: ${'$'}volumesAfter, isLicensed: ${'$'}licensed) { $FIELDS }
              }
            }
        """
        const val SINGLE_QUERY = """
            query (${'$'}id: Int!) { Media(id: ${'$'}id, type: MANGA) { $FIELDS } }
        """
    }
}
