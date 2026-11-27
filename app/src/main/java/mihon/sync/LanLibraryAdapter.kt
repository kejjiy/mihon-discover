package mihon.sync

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mihon.discover.recommendation.RecommendationStore
import tachiyomi.domain.category.model.NewCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.time.Instant

internal fun JsonObject.string(key: String, default: String = "") = this[key]?.jsonPrimitive?.content ?: default
internal fun JsonObject.number(key: String, default: Long = 0) = this[key]?.jsonPrimitive?.longOrNull ?: default
internal fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true

/** Adapts public repositories; it never shares credentials, extension APKs or Android paths. */
@Inject
@SingleIn(AppScope::class)
class LanLibraryAdapter(
    private val context: Context,
    private val mangas: MangaRepository,
    private val chapters: ChapterRepository,
    private val categories: CategoryRepository,
    private val history: HistoryRepository,
    private val discover: RecommendationStore,
    private val sources: SourceManager,
    private val downloads: DownloadManager,
) {
    private val prefs = context.getSharedPreferences("lan_sync", Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).commit()
    }
    private val journalFile = File(context.filesDir, "lan-sync-journal.json")
    private val lock = Mutex()
    private var journal = if (journalFile.exists()) {
        syncJson.decodeFromString<SyncSnapshot>(journalFile.readText()).records.associateBy { it.key }
    } else {
        emptyMap()
    }
    private val pageLists = LinkedHashMap<String, List<Page>>()

    private fun save(records: List<SyncRecord>) {
        val temp = File(journalFile.path + ".tmp")
        temp.writeText(syncJson.encodeToString(SyncSnapshot(records = records)))
        check(temp.renameTo(journalFile)) { "Impossible d'enregistrer le journal" }
        journal = records.associateBy { it.key }
    }

    suspend fun snapshot(): SyncSnapshot = lock.withLock { collectSnapshot().also { save(it.records) } }

    private suspend fun collectSnapshot(): SyncSnapshot {
        val result = mutableListOf<SyncRecord>()
        fun record(key: String, kind: String, value: JsonObject) {
            val old = journal[key]
            result += if (old?.value == value) {
                old
            } else {
                SyncRecord(
                    key,
                    kind,
                    value,
                    maxOf(System.currentTimeMillis(), (old?.modifiedAt ?: 0) + 1),
                    deviceId,
                )
            }
        }
        val remembered = journal.values.filter { it.kind == "manga" }.mapNotNull {
            mangas.getMangaByUrlAndSourceId(it.value.string("url"), it.value.string("sourceId").toLong())
        }
        // History also contains sessions stopped on page zero, outside the library.
        val historyMangas = history.getHistory("").first().map { mangas.getMangaById(it.mangaId) }
        val library = (mangas.getFavorites() + mangas.getReadMangaNotInLibrary() + historyMangas + remembered)
            .distinctBy { it.id }
        library.forEach { manga ->
            val key = mangaKey(manga.source.toString(), manga.url)
            record(
                key,
                "manga",
                buildJsonObject {
                    put("sourceId", manga.source.toString())
                    put("url", manga.url)
                    put("title", manga.title)
                    put("author", manga.author ?: "")
                    put("artist", manga.artist ?: "")
                    put("description", manga.description ?: "")
                    put("thumbnailUrl", manga.thumbnailUrl ?: "")
                    put("genre", JsonArray(manga.genre.orEmpty().map(::JsonPrimitive)))
                    put("favorite", manga.favorite)
                    put("notes", manga.notes)
                    put("status", manga.status)
                    put("viewerFlags", manga.viewerFlags.toString())
                    put("chapterFlags", manga.chapterFlags.toString())
                    put(
                        "categories",
                        JsonArray(
                            categories.getCategoriesByMangaId(manga.id).filterNot {
                                it.isSystemCategory
                            }.map { it.name }.sorted().map(::JsonPrimitive),
                        ),
                    )
                },
            )
            val recent = history.getHistoryByMangaId(manga.id).associateBy { it.chapterId }
            chapters.getChapterByMangaId(manga.id).forEach { chapter ->
                record(
                    chapterKey(key, chapter.url),
                    "chapter",
                    buildJsonObject {
                        put("mangaKey", key)
                        put("url", chapter.url)
                        put("name", chapter.name)
                        put("chapterNumber", chapter.chapterNumber)
                        put("scanlator", chapter.scanlator ?: "")
                        put("sourceOrder", chapter.sourceOrder)
                        put("dateUpload", chapter.dateUpload?.toEpochMilliseconds() ?: 0L)
                        put("read", chapter.read)
                        put("bookmark", chapter.bookmark)
                        put("lastPageRead", chapter.lastPageRead)
                        put("readAt", recent[chapter.id]?.readAt?.toEpochMilliseconds() ?: 0L)
                    },
                )
            }
        }
        val personal = syncJson.parseToJsonElement(discover.exportPersonalData()).jsonObject
        personal.getValue("feedback").jsonArray.forEach { item ->
            val value = JsonObject(item.jsonObject.toMutableMap().apply { putIfAbsent("status", JsonNull) })
            record("f:${value.number("id")}", "feedback", value)
        }
        personal.getValue("links").jsonArray.forEach { item ->
            val value = item.jsonObject.toMutableMap()
            val source = value.getValue("sourceId").jsonPrimitive.content
            value["sourceId"] = JsonPrimitive(source)
            record("l:${mangaKey(source, value.getValue("url").jsonPrimitive.content)}", "link", JsonObject(value))
        }
        personal.getValue("options").jsonObject.forEach { (name, value) ->
            record(
                "o:$name",
                "option",
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                },
            )
        }
        // Preserve deletion markers so clearing personal Discover data does not resurrect it.
        val present = result.mapTo(mutableSetOf()) { it.key }
        journal.values.filter { it.kind in setOf("feedback", "link", "option") && it.key !in present }.forEach { old ->
            result += if (old.value.bool("deleted")) {
                old
            } else {
                old.copy(
                    value = JsonObject(old.value.toMutableMap().apply { put("deleted", JsonPrimitive(true)) }),
                    modifiedAt = maxOf(System.currentTimeMillis(), old.modifiedAt + 1),
                    deviceId = deviceId,
                )
            }
        }
        return SyncSnapshot(records = result.sortedBy { it.key })
    }

    suspend fun merge(incoming: SyncSnapshot): SyncSnapshot = lock.withLock {
        validateSnapshot(incoming)
        require(incoming.version == SYNC_VERSION)
        require(incoming.records.map { it.key }.distinct().size == incoming.records.size)
        val now = System.currentTimeMillis()
        val mergedAt = Instant.fromEpochMilliseconds(now)
        incoming.records.forEach {
            require(it.kind in setOf("manga", "chapter", "feedback", "link", "option"))
            require(it.modifiedAt <= now + 300_000)
            if (it.kind == "manga") require(it.key == mangaKey(it.value.string("sourceId"), it.value.string("url")))
            if (it.kind == "chapter") {
                require(it.key == chapterKey(it.value.string("mangaKey"), it.value.string("url")))
                require(it.value.number("lastPageRead") >= 0)
            }
        }
        val local = collectSnapshot()
        File(context.filesDir, "lan-sync-before-merge.json").writeText(syncJson.encodeToString(local))
        val records = mergeRecords(local.records, incoming.records)
        val categoryMap = categories.getAll().associateBy { it.name }.toMutableMap()
        val mangaMap = mutableMapOf<String, Manga>()
        records.filter { it.kind == "manga" }.forEach { record ->
            val v = record.value
            val source = v.string("sourceId").toLong()
            var manga = mangas.getMangaByUrlAndSourceId(v.string("url"), source)
            if (manga == null) {
                manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = source,
                            url = v.string("url"),
                            title = v.string("title"),
                            initialized = true,
                        ),
                    ),
                ).single()
            }
            check(
                mangas.updateRemote(
                    MangaRemoteUpdate(
                        id = manga.id,
                        title = v.string("title"),
                        author = v.string("author"),
                        artist = v.string("artist"),
                        description = v.string("description"),
                        genre = v["genre"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                        status = v.number("status"),
                        thumbnailUrl = v.string("thumbnailUrl"),
                        updateStrategy = manga.updateStrategy,
                        memo = manga.memo,
                        initialized = manga.initialized,
                        coverLastModified = manga.coverLastModified,
                    ),
                ),
            )
            check(
                mangas.update(
                    MangaUpdate(manga.id) {
                        favoriteAt = if (v.bool("favorite")) manga.favoriteAt ?: mergedAt else null
                        notes = v.string("notes")
                        viewerFlags = v.string("viewerFlags", "0").toLong()
                        chapterFlags = v.string("chapterFlags", "0").toLong()
                    },
                ),
            )
            val categoryIds = v["categories"]?.jsonArray.orEmpty().map { item ->
                val name = item.jsonPrimitive.content.take(200)
                if (categoryMap[name] == null) {
                    categories.insert(NewCategory(name, flags = 0))
                    categories.getAll().forEach { categoryMap[it.name] = it }
                }
                categoryMap.getValue(name).id
            }
            mangas.setMangaCategories(manga.id, categoryIds)
            mangaMap[record.key] = manga
        }
        val chaptersByManga = records.filter { it.kind == "chapter" }.groupBy { it.value.string("mangaKey") }
        for ((key, chapterRecords) in chaptersByManga) {
            val manga = mangaMap[key] ?: continue
            val stored = chapters.getChapterByMangaId(manga.id).associateBy { it.url }.toMutableMap()
            val missing = chapterRecords.map { it.value }.filterNot { it.string("url") in stored }.map { value ->
                Chapter.create().copy(
                    mangaId = manga.id,
                    url = value.string("url"),
                    name = value.string("name"),
                    chapterNumber = value["chapterNumber"]?.jsonPrimitive?.doubleOrNull ?: -1.0,
                    scanlator = value.string("scanlator").ifEmpty { null },
                    sourceOrder = value.number("sourceOrder"),
                    dateUpload = value.number("dateUpload").takeUnless { it == 0L }
                        ?.let(Instant::fromEpochMilliseconds),
                    dateFetch = mergedAt,
                )
            }
            if (missing.isNotEmpty()) {
                val inserted = chapters.updateFromRemote(emptyList(), missing, emptyList())
                inserted.associateByTo(stored) { it.url }
            }

            for (record in chapterRecords) {
                val value = record.value
                val url = value.string("url")
                val chapter = stored[url] ?: checkNotNull(chapters.getChapterByUrlAndMangaId(url, manga.id))
                chapters.update(
                    ChapterUpdate(chapter.id) {
                        read = value.bool("read")
                        bookmark = value.bool("bookmark")
                        lastPageRead = value.number("lastPageRead").toInt()
                    },
                )
                val readAt = value.number("readAt")
                if (readAt > 0) {
                    history.upsertHistory(HistoryUpdate(chapter.id, Instant.fromEpochMilliseconds(readAt), 0))
                }
            }
        }
        val personal = buildJsonObject {
            put("version", 1)
            put(
                "feedback",
                JsonArray(
                    records.filter {
                        it.kind == "feedback" && !it.value.bool("deleted")
                    }.map { it.value },
                ),
            )
            put(
                "links",
                JsonArray(
                    records.filter { it.kind == "link" && !it.value.bool("deleted") }.map {
                        JsonObject(
                            it.value.toMutableMap().apply {
                                put("sourceId", JsonPrimitive(it.value.string("sourceId").toLong()))
                            },
                        )
                    },
                ),
            )
            put(
                "options",
                JsonObject(
                    records.filter { it.kind == "option" && !it.value.bool("deleted") }.associate {
                        it.value.string("name") to it.value.getValue("value")
                    },
                ),
            )
        }
        discover.importPersonalData(personal.toString())
        save(records)
        SyncSnapshot(records = records)
    }

    /** Page content is fetched only after pairing and an explicit action in the Windows reader. */
    suspend fun pages(payload: JsonObject): JsonObject = lock.withLock {
        val (manga, chapter) = findChapter(payload)
        val source = sources.get(manga.source) ?: error("Source absente du téléphone")
        val list = if (downloads.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.title,
                manga.source,
            )
        ) {
            downloads.buildPageList(source, manga, chapter)
        } else {
            source.getPageList(chapter.toSChapter())
        }
        require(list.isNotEmpty() && list.size <= 3000)
        val token = UUID.randomUUID().toString()
        if (pageLists.size >= 4) pageLists.remove(pageLists.keys.first())
        pageLists[token] = list
        buildJsonObject {
            put("token", token)
            put("count", list.size)
        }
    }

    suspend fun page(payload: JsonObject): JsonObject = lock.withLock {
        val (manga, _) = findChapter(payload)
        val page =
            pageLists[payload.string("token")]?.getOrNull(payload.number("index").toInt())
                ?: error("Pages expirées, rouvre le chapitre")
        val source = sources.get(manga.source)
        val bytes = if (page.uri != null) {
            context.contentResolver.openInputStream(page.uri!!)!!.use(::limitedImage)
        } else {
            require(source is HttpSource) { "Cette source ne fournit pas d'images au lecteur Windows" }
            if (page.imageUrl == null) page.imageUrl = source.getImageUrl(page)
            source.getImage(page).use { response ->
                require(response.isSuccessful) { "La source a refusé l'image" }
                response.body.byteStream().use(::limitedImage)
            }
        }
        buildJsonObject { put("data", b64(bytes)) }
    }

    private fun limitedImage(input: InputStream): ByteArray {
        val bytes = input.readBytesLimited(8 * 1024 * 1024)
        require(bytes.isNotEmpty()) { "Image vide" }
        return bytes
    }

    private suspend fun findChapter(payload: JsonObject): Pair<Manga, Chapter> {
        val record = journal[payload.string("chapterKey")] ?: error("Chapitre absent de la bibliothèque synchronisée")
        require(record.kind == "chapter")
        val parent = journal[record.value.string("mangaKey")] ?: error("Manga absent")
        val manga =
            mangas.getMangaByUrlAndSourceId(parent.value.string("url"), parent.value.string("sourceId").toLong())
                ?: error("Manga absent")
        val chapter =
            chapters.getChapterByUrlAndMangaId(record.value.string("url"), manga.id) ?: error("Chapitre absent")
        return manga to chapter
    }
}

private fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Image trop volumineuse (8 Mo maximum)" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
