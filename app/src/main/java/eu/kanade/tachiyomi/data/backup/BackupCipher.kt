package eu.kanade.tachiyomi.data.backup

import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for backup payloads.
 *
 * Format (before the outer gzip layer):
 * ```
 * [ "ASBK1" ][ 16-byte salt ][ 12-byte IV ][ AES-256-GCM ciphertext + tag ]
 * ```
 * The magic header lets the decoder distinguish encrypted backups from legacy
 * plaintext protobuf backups without a separate file extension or metadata flag.
 */
object BackupCipher {
    private val MAGIC = byteArrayOf(0x41, 0x53, 0x42, 0x4B, 0x31) // "ASBK1"
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ITERATIONS = 210_000
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"

    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < MAGIC.size + SALT_SIZE + IV_SIZE) return false
        return MAGIC.indices.all { data[it] == MAGIC[it] }
    }

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "Backup password must not be empty" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return MAGIC + salt + iv + ciphertext
    }

    fun decrypt(payload: ByteArray, password: CharArray?): ByteArray {
        if (!isEncrypted(payload)) return payload
        if (password == null || password.isEmpty()) {
            throw IOException("Encrypted backup requires a password")
        }
        val saltStart = MAGIC.size
        val ivStart = saltStart + SALT_SIZE
        val dataStart = ivStart + IV_SIZE
        val salt = payload.copyOfRange(saltStart, ivStart)
        val iv = payload.copyOfRange(ivStart, dataStart)
        val ciphertext = payload.copyOfRange(dataStart, payload.size)
        val key = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // Do not leak crypto details; surface a stable user-facing message.
            throw IOException("Incorrect backup password", e)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
            val encoded = factory.generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
