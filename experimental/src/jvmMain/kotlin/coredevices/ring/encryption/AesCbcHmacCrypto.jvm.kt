package coredevices.ring.encryption

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC + HMAC-SHA256 (Encrypt-then-MAC).
 * Wire format: IV(16) || HMAC(32) || ciphertext.
 */
actual object AesCbcHmacCrypto {
    private const val IV_LENGTH = 16 // AES block size
    private const val HMAC_LENGTH = 32

    actual fun encrypt(plaintext: ByteArray, keyBase64: String): ByteArray {
        val keyBytes = Base64.getDecoder().decode(keyBase64)
        require(keyBytes.size == 32) { "AES-256 key must be 32 bytes, got ${keyBytes.size}" }

        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(deriveHmacKey(keyBytes), "HmacSHA256"))
        mac.update(iv)
        mac.update(ciphertext)

        return iv + mac.doFinal() + ciphertext
    }

    actual fun decrypt(ivAndCiphertext: ByteArray, keyBase64: String): ByteArray {
        require(ivAndCiphertext.size > IV_LENGTH + HMAC_LENGTH) {
            "Input too short to contain IV + HMAC + ciphertext"
        }
        val keyBytes = Base64.getDecoder().decode(keyBase64)
        require(keyBytes.size == 32) { "AES-256 key must be 32 bytes, got ${keyBytes.size}" }

        val iv = ivAndCiphertext.copyOfRange(0, IV_LENGTH)
        val storedHmac = ivAndCiphertext.copyOfRange(IV_LENGTH, IV_LENGTH + HMAC_LENGTH)
        val ciphertext = ivAndCiphertext.copyOfRange(IV_LENGTH + HMAC_LENGTH, ivAndCiphertext.size)

        // Encrypt-then-MAC: verify before decrypting
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(deriveHmacKey(keyBytes), "HmacSHA256"))
        mac.update(iv)
        mac.update(ciphertext)

        if (!MessageDigest.isEqual(storedHmac, mac.doFinal())) {
            throw TamperedException("Data integrity check failed — this recording may have been tampered with")
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    actual fun keyFingerprint(keyBase64: String): String {
        val keyBytes = Base64.getDecoder().decode(keyBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun deriveHmacKey(aesKey: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("hmac".toByteArray())
        md.update(aesKey)
        return md.digest()
    }
}
