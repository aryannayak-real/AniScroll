package eu.kanade.tachiyomi.data.backup

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived, in-memory handoff for backup passwords between the UI and the
 * WorkManager jobs. Passwords must never be placed in [androidx.work.Data] or
 * persisted to disk; WorkManager only receives an opaque one-shot token.
 */
object BackupPasswordStore {
    private const val TTL_MS = 10 * 60 * 1000L

    private data class Entry(
        val password: CharArray,
        val createdAt: Long,
    )

    private val passwords = ConcurrentHashMap<String, Entry>()

    fun register(password: CharArray): String {
        purgeExpired()
        val token = UUID.randomUUID().toString()
        passwords[token] = Entry(password.copyOf(), System.currentTimeMillis())
        password.fill('\u0000')
        return token
    }

    fun take(token: String?): CharArray? {
        if (token.isNullOrBlank()) return null
        purgeExpired()
        return passwords.remove(token)?.password
    }

    fun discard(password: CharArray?) {
        password?.fill('\u0000')
    }

    /**
     * Drops tokens that were never consumed (for example because WorkManager
     * discarded the request with `ExistingWorkPolicy.KEEP`) so passwords cannot
     * linger in memory indefinitely.
     */
    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        passwords.entries.forEach { (token, entry) ->
            if (entry.createdAt < cutoff && passwords.remove(token, entry)) {
                entry.password.fill('\u0000')
            }
        }
    }
}
