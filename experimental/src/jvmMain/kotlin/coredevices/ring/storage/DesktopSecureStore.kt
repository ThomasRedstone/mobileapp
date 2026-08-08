package coredevices.ring.storage

import co.touchlab.kermit.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted key/value store for desktop, standing in for the Android Keystore / iOS Keychain.
 * There is no OS-level secret store to bind to, so the master key sits in a 0600 file beside the
 * data — this protects against other users on the machine, not against the user's own processes.
 */
internal class DesktopSecureStore(fileName: String) {
    private val logger = Logger.withTag("DesktopSecureStore")
    private val dir = File(System.getProperty("user.home"), DATA_DIR)
    private val storeFile = File(dir, fileName)
    private val keyFile = File(dir, MASTER_KEY_FILE)
    private val lock = Any()

    private val masterKey: SecretKeySpec by lazy {
        ensureDir()
        val bytes = if (keyFile.exists()) {
            keyFile.readBytes().also { require(it.size == 32) { "Corrupt master key file: ${keyFile.absolutePath}" } }
        } else {
            ByteArray(32).also {
                SecureRandom().nextBytes(it)
                keyFile.writeBytes(it)
                restrictPermissions(keyFile, "rw-------")
            }
        }
        SecretKeySpec(bytes, "AES")
    }

    fun get(key: String): String? = synchronized(lock) {
        val encoded = load().getProperty(key) ?: return null
        return try {
            decrypt(encoded)
        } catch (e: Exception) {
            logger.w(e) { "Dropping unreadable entry for $key" }
            remove(key)
            null
        }
    }

    fun put(key: String, value: String) = synchronized(lock) {
        val props = load()
        props.setProperty(key, encrypt(value))
        save(props)
    }

    fun remove(key: String) = synchronized(lock) {
        val props = load()
        if (props.remove(key) != null) save(props)
    }

    private fun load(): Properties {
        val props = Properties()
        if (storeFile.exists()) storeFile.inputStream().use { props.load(it) }
        return props
    }

    private fun save(props: Properties) {
        ensureDir()
        storeFile.outputStream().use { props.store(it, null) }
        restrictPermissions(storeFile, "rw-------")
    }

    private fun ensureDir() {
        dir.mkdirs()
        restrictPermissions(dir, "rwx------")
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        return Base64.getEncoder().encodeToString(byteArrayOf(iv.size.toByte()) + iv + ciphertext)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        val ivLength = combined[0].toInt() and 0xFF
        require(ivLength in 1 until combined.size - 1) { "Invalid IV length" }
        val iv = combined.copyOfRange(1, 1 + ivLength)
        val ciphertext = combined.copyOfRange(1 + ivLength, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun restrictPermissions(file: File, permissions: String) {
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
        } catch (e: UnsupportedOperationException) {
            logger.d { "No POSIX permissions on this filesystem; ${file.name} left at default access" }
        } catch (e: java.io.IOException) {
            logger.w(e) { "Could not restrict permissions on ${file.name}" }
        }
    }

    companion object {
        private const val DATA_DIR = ".local/share/coreapp"
        private const val MASTER_KEY_FILE = "secure-store.key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }
}
