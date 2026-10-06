package mihon.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class SyncProtocolTest {
    @Test
    fun `Node and Android use the same encryption identity binding code and ECDH derivation`() {
        val fixture = syncJson.parseToJsonElement(File("../fixtures/crypto.json").readText()).jsonObject
        val key = unb64(fixture.getValue("key").jsonPrimitive.content)
        val aad = fixture.getValue("aad").jsonPrimitive.content
        val envelope = fixture.getValue("envelope").jsonObject
        assertEquals(fixture.getValue("plaintext"), syncJson.parseToJsonElement(SyncCrypto.decrypt(key, envelope, aad)))
        assertEquals(fixture.getValue("code").jsonPrimitive.content, SyncCrypto.code(key))
        assertThrows(Exception::class.java) { SyncCrypto.decrypt(key, envelope, aad + "x") }
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(
            PKCS8EncodedKeySpec(unb64(fixture.getValue("privateKey").jsonPrimitive.content)),
        )
        val publicKey = factory.generatePublic(
            X509EncodedKeySpec(unb64(fixture.getValue("publicKey").jsonPrimitive.content)),
        )
        assertEquals(
            fixture.getValue("sharedKey").jsonPrimitive.content,
            b64(
                SyncCrypto.sharedKey(
                    KeyPair(publicKey, privateKey),
                    fixture.getValue("remotePublicKey").jsonPrimitive.content,
                    "initiator",
                    "responder",
                ),
            ),
        )
    }

    @Test
    fun `fresh public keys produce equal secrets on both sides`() {
        val a = SyncCrypto.keyPair()
        val b = SyncCrypto.keyPair()
        assertArrayEquals(
            SyncCrypto.sharedKey(a, SyncCrypto.publicKey(b), "a", "b"),
            SyncCrypto.sharedKey(b, SyncCrypto.publicKey(a), "a", "b"),
        )
    }

    @Test
    fun `merge never loses progress and is symmetric and idempotent`() {
        val old = SyncRecord(
            "chapter",
            "chapter",
            JsonObject(
                mapOf(
                    "read" to JsonPrimitive(true),
                    "lastPageRead" to JsonPrimitive(40),
                    "bookmark" to JsonPrimitive(true),
                    "readAt" to JsonPrimitive(10),
                ),
            ),
            1,
            "a",
        )
        val remote = old.copy(
            value = JsonObject(
                mapOf(
                    "read" to JsonPrimitive(false),
                    "lastPageRead" to JsonPrimitive(3),
                    "bookmark" to JsonPrimitive(false),
                    "readAt" to JsonPrimitive(20),
                ),
            ),
            modifiedAt = 2,
            deviceId = "b",
        )
        val merged = mergeRecords(listOf(old), listOf(remote))
        assertEquals(JsonPrimitive(40), merged.single().value["lastPageRead"])
        assertEquals(JsonPrimitive(true), merged.single().value["read"])
        assertEquals(JsonPrimitive(false), merged.single().value["bookmark"])
        assertEquals(merged, mergeRecords(listOf(remote), listOf(old)))
        assertEquals(merged, mergeRecords(merged, merged))
    }

    @Test
    fun `source identities preserve 64 bit precision`() {
        assertEquals("m:OTIyMzM3MjAzNjg1NDc3NTAwMAovbWFuZ2E=", mangaKey("9223372036854775000", "/manga"))
    }

    @Test
    fun `malformed personal data and duplicate keys are rejected before repository changes`() {
        val feedback = SyncRecord(
            "f:42",
            "feedback",
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(42),
                    "vote" to JsonPrimitive(1),
                    "hidden" to JsonPrimitive(false),
                ),
            ),
            1,
            "device-a",
        )
        validateSnapshot(SyncSnapshot(records = listOf(feedback)))
        assertThrows(Exception::class.java) { validateSnapshot(SyncSnapshot(records = listOf(feedback, feedback))) }
        val wrongId = feedback.copy(
            value = JsonObject(
                feedback.value.toMutableMap().apply {
                    put("id", JsonPrimitive("42"))
                },
            ),
        )
        assertThrows(Exception::class.java) { validateSnapshot(SyncSnapshot(records = listOf(wrongId))) }
    }
}
