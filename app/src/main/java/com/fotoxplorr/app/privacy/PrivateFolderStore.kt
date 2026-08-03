package com.fotoxplorr.app.privacy

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PrivateFolderStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lockedFoldersState = MutableStateFlow(loadFolderNames())
    private val unlockedFoldersState = MutableStateFlow<Set<String>>(emptySet())
    private val attempts = ConcurrentHashMap<String, AttemptState>()

    fun observeLockedFolders(): StateFlow<Set<String>> = lockedFoldersState.asStateFlow()
    fun observeUnlockedFolders(): StateFlow<Set<String>> = unlockedFoldersState.asStateFlow()

    suspend fun protect(folderKey: String, password: CharArray): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            require(folderKey.isNotBlank()) { "Folder key is required" }
            require(password.size >= MIN_PASSWORD_LENGTH) {
                "Password must contain at least $MIN_PASSWORD_LENGTH characters"
            }

            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val hash = try {
                derive(password, salt)
            } finally {
                password.fill('\u0000')
            }

            try {
                preferences.edit()
                    .putString(keySalt(folderKey), encode(salt))
                    .putString(keyHash(folderKey), encode(hash))
                    .apply()

                lockedFoldersState.value = lockedFoldersState.value + folderKey
                unlockedFoldersState.value = unlockedFoldersState.value + folderKey
                attempts.remove(folderKey)
            } finally {
                salt.fill(0)
                hash.fill(0)
            }
        }.onFailure { password.fill('\u0000') }
    }

    suspend fun unlock(folderKey: String, password: CharArray): Boolean = withContext(Dispatchers.Default) {
        val now = SystemClock.elapsedRealtime()
        val attempt = attempts[folderKey]
        if (attempt != null && attempt.lockedUntilMillis > now) {
            password.fill('\u0000')
            return@withContext false
        }

        val salt = preferences.getString(keySalt(folderKey), null)?.let(::decode)
        val expected = preferences.getString(keyHash(folderKey), null)?.let(::decode)
        if (salt == null || expected == null) {
            password.fill('\u0000')
            registerFailure(folderKey, now)
            return@withContext false
        }

        val actual = try {
            derive(password, salt)
        } finally {
            password.fill('\u0000')
            salt.fill(0)
        }

        val matches = try {
            constantTimeEquals(expected, actual)
        } finally {
            expected.fill(0)
            actual.fill(0)
        }

        if (matches) {
            attempts.remove(folderKey)
            unlockedFoldersState.value = unlockedFoldersState.value + folderKey
        } else {
            registerFailure(folderKey, now)
        }
        matches
    }

    fun lock(folderKey: String) {
        unlockedFoldersState.value = unlockedFoldersState.value - folderKey
    }

    fun lockAll() {
        unlockedFoldersState.value = emptySet()
    }

    suspend fun removeProtection(folderKey: String, password: CharArray): Boolean {
        if (!unlock(folderKey, password)) return false
        return withContext(Dispatchers.Default) {
            preferences.edit()
                .remove(keySalt(folderKey))
                .remove(keyHash(folderKey))
                .apply()
            attempts.remove(folderKey)
            lockedFoldersState.value = lockedFoldersState.value - folderKey
            unlockedFoldersState.value = unlockedFoldersState.value - folderKey
            true
        }
    }

    private fun registerFailure(folderKey: String, now: Long) {
        attempts.compute(folderKey) { _, previous ->
            val nextCount = (previous?.failures ?: 0) + 1
            if (nextCount >= MAX_FAILURES) {
                AttemptState(0, now + LOCKOUT_MILLIS)
            } else {
                AttemptState(nextCount, 0L)
            }
        }
    }

    private fun loadFolderNames(): Set<String> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(HASH_PREFIX) }
        .map { it.removePrefix(HASH_PREFIX) }
        .toSet()

    private data class AttemptState(
        val failures: Int,
        val lockedUntilMillis: Long,
    )

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_private_folders"
        const val HASH_PREFIX = "hash:"
        const val SALT_PREFIX = "salt:"
        const val MIN_PASSWORD_LENGTH = 6
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val ITERATIONS = 210_000
        const val MAX_FAILURES = 3
        const val LOCKOUT_MILLIS = 30_000L

        fun keyHash(folder: String) = "$HASH_PREFIX$folder"
        fun keySalt(folder: String) = "$SALT_PREFIX$folder"

        fun derive(password: CharArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .encoded
            } finally {
                spec.clearPassword()
            }
        }

        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

        fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
            if (left.size != right.size) return false
            var difference = 0
            for (index in left.indices) {
                difference = difference or (left[index].toInt() xor right[index].toInt())
            }
            return difference == 0
        }
    }
}
