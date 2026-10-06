package mihon.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val SYNC_VERSION = 1
const val SYNC_GROUP = "239.255.77.77"
const val SYNC_PORT = 41783
const val MAX_FRAME = 16 * 1024 * 1024
val syncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class SyncRecord(
    val key: String,
    val kind: String,
    val value: JsonObject,
    val modifiedAt: Long,
    val deviceId: String,
)

@Serializable
data class SyncSnapshot(val version: Int = SYNC_VERSION, val records: List<SyncRecord>)

/** Stable identities use source IDs as strings: many extension IDs exceed JS's safe integers. */
fun mangaKey(source: String, url: String): String = "m:" + b64("$source\n$url".toByteArray())
fun chapterKey(manga: String, url: String): String = "c:" + b64("$manga\n$url".toByteArray())

/** Validate the complete incoming payload before any repository is changed. */
fun validateSnapshot(snapshot: SyncSnapshot, now: Long = System.currentTimeMillis()) {
    require(snapshot.version == SYNC_VERSION && snapshot.records.size <= 100_000)
    require(snapshot.records.map { it.key }.distinct().size == snapshot.records.size)
    snapshot.records.forEach { record ->
        require(record.key.length <= 8192 && record.deviceId.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        require(record.modifiedAt in 0..now + 300_000)
        val value = record.value
        fun text(field: String, limit: Int = 100_000, required: Boolean = false): String {
            val item = value[field]
            if (item == null && !required) return ""
            require(item is JsonPrimitive && item.isString && item.content.length <= limit) {
                "Champ invalide : $field"
            }
            return item.content
        }
        fun number(field: String, required: Boolean = false): Long {
            val item = value[field]
            if (item == null && !required) return 0
            require(item is JsonPrimitive && !item.isString && item.longOrNull != null) { "Champ invalide : $field" }
            return item.longOrNull!!
        }
        fun boolean(field: String, required: Boolean = false) {
            val item = value[field]
            if (item == null && !required) return
            require(item is JsonPrimitive && !item.isString && item.booleanOrNull != null) { "Champ invalide : $field" }
        }
        fun strings(field: String) {
            val item = value[field] ?: return
            require(
                item is JsonArray && item.size <= 100 &&
                    item.all { it is JsonPrimitive && it.isString && it.content.length <= 200 },
            )
        }
        boolean("deleted")
        when (record.kind) {
            "manga" -> {
                val source = text("sourceId", 32, true)
                require(source.toLongOrNull()?.toString() == source)
                val url = text("url", 4096, true)
                require(url.isNotEmpty() && record.key == mangaKey(source, url))
                text("title", 2000, true)
                text("notes")
                text("author")
                text("artist")
                text("description")
                text("thumbnailUrl")
                boolean("favorite", true)
                number("status")
                strings("genre")
                strings("categories")
                listOf("viewerFlags", "chapterFlags").forEach { field ->
                    if (value[field] != null) require(text(field, 32).toLongOrNull() != null)
                }
            }
            "chapter" -> {
                require(record.key == chapterKey(text("mangaKey", 8192, true), text("url", 4096, true)))
                require(number("lastPageRead", true) in 0..1_000_000)
                require(number("readAt") >= 0)
                boolean("read", true)
                boolean("bookmark", true)
                text("name", 2000)
                text("scanlator", 2000)
                number("sourceOrder")
                number("dateUpload")
                if (value["chapterNumber"] !=
                    null
                ) {
                    require(
                        value["chapterNumber"] is JsonPrimitive &&
                            !value.getValue("chapterNumber").jsonPrimitive.isString &&
                            value.getValue("chapterNumber").jsonPrimitive.content.toDoubleOrNull()?.isFinite() == true,
                    )
                }
            }
            "feedback" -> {
                val id = number("id", true)
                require(id in 1..2_147_483_647 && record.key == "f:$id" && number("vote", true) in -1..1)
                boolean("hidden", true)
                if (value["status"] != null && value["status"] != JsonNull) text("status", 100)
            }
            "link" -> {
                val source = text("sourceId", 32, true)
                require(source.toLongOrNull()?.toString() == source && number("mediaId", true) in 1..2_147_483_647)
                require(record.key == "l:${mangaKey(source, text("url", 4096, true))}")
                boolean("confirmed", true)
            }
            "option" -> {
                val name = text("name", 200, true)
                require((name == "romance_policy" || name.startsWith("preset:")) && record.key == "o:$name")
                text("value", 100_000, true)
            }
            else -> error("Type de donnée inconnu")
        }
    }
}

fun mergeRecords(local: List<SyncRecord>, incoming: List<SyncRecord>): List<SyncRecord> {
    require(incoming.size <= 100_000) { "Bibliothèque trop volumineuse" }
    val merged = local.associateBy { it.key }.toMutableMap()
    incoming.forEach { remote ->
        require(remote.key.length <= 8192 && remote.modifiedAt >= 0 && remote.deviceId.length <= 128)
        val old = merged[remote.key]
        if (old == null) {
            merged[remote.key] = remote
        } else {
            require(old.kind == remote.kind) { "Type de donnée incohérent" }
            val winner = if (remote.modifiedAt > old.modifiedAt ||
                (remote.modifiedAt == old.modifiedAt && remote.deviceId > old.deviceId)
            ) {
                remote
            } else {
                old
            }
            val value = winner.value.toMutableMap()
            // Reading is monotonic: an older device must never erase completed reading.
            if (remote.kind == "chapter") {
                value["read"] = JsonPrimitive(
                    old.value["read"]?.jsonPrimitive?.booleanOrNull == true ||
                        remote.value["read"]?.jsonPrimitive?.booleanOrNull == true,
                )
                value["lastPageRead"] = JsonPrimitive(
                    maxOf(
                        old.value["lastPageRead"]?.jsonPrimitive?.longOrNull ?: 0,
                        remote.value["lastPageRead"]?.jsonPrimitive?.longOrNull ?: 0,
                    ),
                )
                value["readAt"] = JsonPrimitive(
                    maxOf(
                        old.value["readAt"]?.jsonPrimitive?.longOrNull ?: 0,
                        remote.value["readAt"]?.jsonPrimitive?.longOrNull ?: 0,
                    ),
                )
            }
            merged[remote.key] = winner.copy(value = JsonObject(value))
        }
    }
    return merged.values.sortedBy { it.key }
}

fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)

object SyncCrypto {
    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun publicKey(pair: KeyPair): String = b64(pair.public.encoded)

    fun sharedKey(pair: KeyPair, remotePublic: String, initiator: String, responder: String): ByteArray {
        val remote = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(remotePublic)))
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(pair.private)
            doPhase(remote, true)
            generateSecret()
        }
        // HKDF-SHA256, transcript-bound to both identities and fresh public keys.
        val transcript = "mihon-discover-sync-v1\n$initiator\n$responder"
        val salt = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())
        val prk = hmac(salt, secret)
        return hmac(prk, "session\u0001".toByteArray())
    }

    fun code(key: ByteArray): String {
        val bytes = hmac(key, "compare-code-v1".toByteArray())
        val number = ((bytes[0].toLong() and 255) shl 24) or
            ((bytes[1].toLong() and 255) shl 16) or
            ((bytes[2].toLong() and 255) shl 8) or (bytes[3].toLong() and 255)
        return (number % 1_000_000).toString().padStart(6, '0')
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    fun encrypt(key: ByteArray, plaintext: String, aad: String): JsonObject {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray())
        return JsonObject(
            mapOf(
                "iv" to JsonPrimitive(b64(iv)),
                "data" to JsonPrimitive(b64(cipher.doFinal(plaintext.toByteArray()))),
            ),
        )
    }

    fun decrypt(key: ByteArray, envelope: JsonObject, aad: String): String {
        val iv = unb64(envelope.getValue("iv").jsonPrimitive.content)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(unb64(envelope.getValue("data").jsonPrimitive.content)).toString(Charsets.UTF_8)
    }
}
