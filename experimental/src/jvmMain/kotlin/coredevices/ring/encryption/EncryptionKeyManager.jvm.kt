package coredevices.ring.encryption

import PlatformUiContext
import co.touchlab.kermit.Logger
import coredevices.ring.storage.DesktopSecureStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

actual class EncryptionKeyManager {
    private val logger = Logger.withTag("EncryptionKeyManager")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = DesktopSecureStore("encryption-keys.properties")

    actual fun generateKey(): KeyResult {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        return KeyResult(
            keyBase64 = Base64.getEncoder().encodeToString(keyBytes),
            fingerprint = sha256Hex(keyBytes),
        )
    }

    actual suspend fun saveKeyLocally(key: String, email: String) {
        val fingerprint = AesCbcHmacCrypto.keyFingerprint(key)
        val entries = loadEntries().toMutableList()
        entries.removeAll { it.email == email }
        entries.add(StoredKeyEntry(email = email, keyBase64 = key, fingerprint = fingerprint))
        store.put(LOCAL_KEY_ENTRIES, json.encodeToString(ListSerializer(StoredKeyEntry.serializer()), entries))

        logger.i { "Key saved locally for $email (${entries.size} total entries)" }
    }

    actual suspend fun getLocalKey(email: String?): String? {
        val entries = loadEntries()
        return if (email != null) {
            entries.find { it.email == email }?.keyBase64
        } else {
            entries.firstOrNull()?.keyBase64
        }
    }

    actual suspend fun getStoredKeyEntries(): List<StoredKeyEntry> = loadEntries()

    actual suspend fun saveToCloudKeychain(uiContext: PlatformUiContext, key: String) {
        throw Exception("Desktop has no cloud keychain. Copy the key from the Backup menu and store it yourself.")
    }

    actual suspend fun readFromCloudKeychain(uiContext: PlatformUiContext): String? {
        throw Exception("Desktop has no cloud keychain. Paste the key from another device, or generate a new one.")
    }

    private fun loadEntries(): List<StoredKeyEntry> {
        val raw = store.get(LOCAL_KEY_ENTRIES) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(StoredKeyEntry.serializer()), raw)
        } catch (e: Exception) {
            logger.e(e) { "Failed to load key entries" }
            emptyList()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val LOCAL_KEY_ENTRIES = "local_key_entries"
    }
}
