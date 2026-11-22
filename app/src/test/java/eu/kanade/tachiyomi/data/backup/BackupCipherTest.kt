package eu.kanade.tachiyomi.data.backup

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class BackupCipherTest {

    @Test
    fun `plaintext payload passes through unchanged`() {
        val payload = "legacy backup".toByteArray()
        assertFalse(BackupCipher.isEncrypted(payload))
        assertArrayEquals(payload, BackupCipher.decrypt(payload, null))
    }

    @Test
    fun `encrypted payload round trips with the correct password`() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val encrypted = BackupCipher.encrypt(payload, "correct horse battery staple".toCharArray())

        assertTrue(BackupCipher.isEncrypted(encrypted))
        assertArrayEquals(payload, BackupCipher.decrypt(encrypted, "correct horse battery staple".toCharArray()))
    }

    @Test
    fun `encrypted payload requires a password`() {
        val encrypted = BackupCipher.encrypt(byteArrayOf(1, 2, 3), "pw".toCharArray())
        assertThrows(IOException::class.java) {
            BackupCipher.decrypt(encrypted, null)
        }
    }

    @Test
    fun `wrong password fails authentication`() {
        val encrypted = BackupCipher.encrypt(byteArrayOf(1, 2, 3), "right".toCharArray())
        assertThrows(IOException::class.java) {
            BackupCipher.decrypt(encrypted, "wrong".toCharArray())
        }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val encrypted = BackupCipher.encrypt(byteArrayOf(1, 2, 3), "pw".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last() + 1).toByte()
        assertThrows(IOException::class.java) {
            BackupCipher.decrypt(encrypted, "pw".toCharArray())
        }
    }
}
