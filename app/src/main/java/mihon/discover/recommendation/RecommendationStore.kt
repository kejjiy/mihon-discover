package mihon.discover.recommendation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.discover.AniListMedia
import mihon.discover.CatalogueFilters

/** Private database. Its schema versions never affect Mihon's own SQLDelight migrations. */
@Inject
@SingleIn(AppScope::class)
class RecommendationStore(context: Context, private val json: Json) {
    private val appContext = context.applicationContext
    private val database = Helper(appContext)

    data class Feedback(val vote: Int, val status: String?, val hidden: Boolean)

    @Serializable
    private data class PersonalBackup(
        val version: Int,
        val feedback: List<FeedbackRecord>,
        val links: List<LinkRecord>,
        val options: Map<String, String>,
    )

    @Serializable
    private data class FeedbackRecord(val id: Long, val vote: Int, val status: String?, val hidden: Boolean)

    @Serializable
    private data class LinkRecord(val sourceId: Long, val url: String, val mediaId: Long, val confirmed: Boolean)

    fun putMedia(media: AniListMedia) {
        database.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO media (id, payload, updated_at) VALUES (?, ?, ?)",
            arrayOf<Any?>(media.id, json.encodeToString(AniListMedia.serializer(), media), System.currentTimeMillis()),
        )
    }

    fun getMedia(id: Long): AniListMedia? = database.readableDatabase.rawQuery(
        "SELECT payload FROM media WHERE id = ?",
        arrayOf(id.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            runCatching {
                json.decodeFromString(AniListMedia.serializer(), cursor.getString(0))
            }.getOrNull()
        }
    }

    fun isFresh(id: Long): Boolean = database.readableDatabase.rawQuery(
        "SELECT updated_at FROM media WHERE id = ?",
        arrayOf(id.toString()),
    ).use { it.moveToFirst() && System.currentTimeMillis() - it.getLong(0) < 86_400_000L }

    fun pruneMedia() {
        database.writableDatabase.execSQL(
            """
            DELETE FROM media WHERE id NOT IN (SELECT media_id FROM links)
              AND id NOT IN (SELECT media_id FROM feedback)
              AND id NOT IN (SELECT id FROM media ORDER BY updated_at DESC LIMIT 2000)
            """.trimIndent(),
        )
    }

    fun recentMedia(limit: Int = 500): List<AniListMedia> = database.readableDatabase.rawQuery(
        "SELECT payload FROM media ORDER BY updated_at DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 2000).toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                runCatching { json.decodeFromString(AniListMedia.serializer(), cursor.getString(0)) }
                    .getOrNull()?.let(::add)
            }
        }
    }

    fun link(sourceId: Long, url: String, mediaId: Long, confirmed: Boolean): Boolean {
        val existing = linkedId(sourceId, url)
        if (existing != null && existing != mediaId) return false
        val db = database.writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO links (source_id, url, media_id, confirmed) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(sourceId, url, mediaId, if (confirmed) 1 else 0),
        )
        return true
    }

    fun linkedId(sourceId: Long, url: String): Long? = database.readableDatabase.rawQuery(
        "SELECT media_id FROM links WHERE source_id = ? AND url = ?",
        arrayOf(sourceId.toString(), url),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun feedback(id: Long): Feedback = database.readableDatabase.rawQuery(
        "SELECT vote, status, hidden FROM feedback WHERE media_id = ?",
        arrayOf(id.toString()),
    ).use {
        if (it.moveToFirst()) {
            Feedback(it.getInt(0), it.getString(1), it.getInt(2) != 0)
        } else {
            Feedback(0, null, false)
        }
    }

    fun allFeedback(): Map<Long, Feedback> = database.readableDatabase.rawQuery(
        "SELECT media_id, vote, status, hidden FROM feedback",
        emptyArray(),
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                put(cursor.getLong(0), Feedback(cursor.getInt(1), cursor.getString(2), cursor.getInt(3) != 0))
            }
        }
    }

    fun setFeedback(id: Long, vote: Int? = null, status: String? = null, hidden: Boolean? = null) {
        val previous = feedback(id)
        val next = Feedback(vote ?: previous.vote, status ?: previous.status, hidden ?: previous.hidden)
        database.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO feedback (media_id, vote, status, hidden) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(id, next.vote.coerceIn(-1, 1), next.status, if (next.hidden) 1 else 0),
        )
    }

    fun setRomancePolicy(value: String) = setOption("romance_policy", value)
    fun romancePolicy(): RecommendationEngine.RomancePolicy = runCatching {
        RecommendationEngine.RomancePolicy.valueOf(option("romance_policy") ?: "PENALIZE")
    }.getOrDefault(RecommendationEngine.RomancePolicy.PENALIZE)

    fun setOption(key: String, value: String) {
        database.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO options (key, value) VALUES (?, ?)",
            arrayOf(key, value),
        )
    }

    fun option(key: String): String? = database.readableDatabase.rawQuery(
        "SELECT value FROM options WHERE key = ?",
        arrayOf(key),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun savePreset(name: String, filters: CatalogueFilters) {
        val safeName = name.trim().take(40)
        if (safeName.isNotEmpty()) {
            setOption(
                "preset:$safeName",
                json.encodeToString(CatalogueFilters.serializer(), filters),
            )
        }
    }

    fun presets(): Map<String, CatalogueFilters> = database.readableDatabase.rawQuery(
        "SELECT key, value FROM options WHERE key LIKE 'preset:%' ORDER BY key",
        emptyArray(),
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val filter = runCatching {
                    json.decodeFromString(CatalogueFilters.serializer(), cursor.getString(1))
                }.getOrNull()
                if (filter != null) put(cursor.getString(0).removePrefix("preset:"), filter)
            }
        }
    }

    fun migrateLegacyCache() {
        if (option("legacy_migrated") == "true") return
        val old = appContext.getSharedPreferences("discover_catalogue", Context.MODE_PRIVATE).all
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            old.forEach { (key, value) ->
                when {
                    key.startsWith("media:") && !key.endsWith(":at") && value is String -> {
                        val id = key.substringAfter(':').toLongOrNull() ?: return@forEach
                        db.execSQL(
                            "INSERT OR IGNORE INTO media (id, payload, updated_at) VALUES (?, ?, ?)",
                            arrayOf<Any?>(id, value, (old["$key:at"] as? Long) ?: System.currentTimeMillis()),
                        )
                    }
                    key.startsWith("link:") && value is Long -> {
                        val source = key.substringAfter("link:").substringBefore(':').toLongOrNull() ?: return@forEach
                        val url = key.substringAfter("link:$source:")
                        db.execSQL(
                            "INSERT OR IGNORE INTO links (source_id, url, media_id, confirmed) VALUES (?, ?, ?, 0)",
                            arrayOf<Any?>(source, url, value),
                        )
                    }
                }
            }
            db.execSQL("INSERT OR REPLACE INTO options (key, value) VALUES ('legacy_migrated', 'true')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun exportPersonalData(): String {
        val votes = allFeedback().map { (id, item) ->
            FeedbackRecord(id, item.vote, item.status, item.hidden)
        }
        val links = database.readableDatabase.rawQuery(
            "SELECT source_id, url, media_id, confirmed FROM links",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(LinkRecord(cursor.getLong(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3) != 0))
                }
            }
        }
        val options = database.readableDatabase.rawQuery(
            "SELECT key, value FROM options WHERE key = 'romance_policy' OR key LIKE 'preset:%'",
            emptyArray(),
        ).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        return json.encodeToString(PersonalBackup.serializer(), PersonalBackup(1, votes, links, options))
    }

    fun importPersonalData(content: String) {
        val backup = json.decodeFromString(PersonalBackup.serializer(), content)
        require(backup.version == 1) { "Version de sauvegarde Discover inconnue" }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM feedback")
            db.execSQL("DELETE FROM links")
            db.execSQL("DELETE FROM options WHERE key = 'romance_policy' OR key LIKE 'preset:%'")
            backup.feedback.forEach {
                db.execSQL(
                    "INSERT INTO feedback (media_id, vote, status, hidden) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(it.id, it.vote.coerceIn(-1, 1), it.status, if (it.hidden) 1 else 0),
                )
            }
            backup.links.forEach {
                db.execSQL(
                    "INSERT INTO links (source_id, url, media_id, confirmed) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(it.sourceId, it.url, it.mediaId, if (it.confirmed) 1 else 0),
                )
            }
            backup.options.forEach { (key, value) ->
                if (key == "romance_policy" || key.startsWith("preset:")) {
                    db.execSQL("INSERT INTO options (key, value) VALUES (?, ?)", arrayOf(key, value))
                }
            }
            db.execSQL("INSERT OR REPLACE INTO options (key, value) VALUES ('legacy_migrated', 'true')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearMetadataCache() {
        database.writableDatabase.execSQL("DELETE FROM media")
        database.writableDatabase.execSQL("DELETE FROM options WHERE key = 'recommendation_catalogue_at'")
    }

    fun clearPersonalData() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM feedback")
            db.execSQL("DELETE FROM links")
            db.execSQL("DELETE FROM options WHERE key = 'romance_policy' OR key LIKE 'preset:%'")
            db.execSQL("INSERT OR REPLACE INTO options (key, value) VALUES ('legacy_migrated', 'true')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val preferences = appContext.getSharedPreferences("discover_catalogue", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("link:") }.forEach(editor::remove)
        editor.apply()
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "discover.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE media (id INTEGER PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE links (source_id INTEGER NOT NULL, url TEXT NOT NULL, media_id INTEGER NOT NULL, confirmed INTEGER NOT NULL, PRIMARY KEY(source_id, url))",
            )
            db.execSQL("CREATE INDEX links_media_id ON links(media_id)")
            db.execSQL(
                "CREATE TABLE feedback (media_id INTEGER PRIMARY KEY, vote INTEGER NOT NULL DEFAULT 0, status TEXT, hidden INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE TABLE options (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
