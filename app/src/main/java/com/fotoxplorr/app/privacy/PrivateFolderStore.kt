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
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * One folder's lock exactly as stored: the PBKDF2 salt and hash of its password, Base64. Never the
 * password. The encrypted backup carries these inside its encrypted manifest so a restore can put
 * the same locks back (the old passwords then keep working); nothing else reads them.
 */
data class FolderLockRecord(val folderKey: String, val saltBase64: String, val hashBase64: String)

/** What [PrivateFolderStore.restoreLocks] did: locks put in place, locks left as the phone had them, records refused. */
class LockImport(val added: Int, val kept: Int, val rejected: Int)

class PrivateFolderStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lockedFoldersState = MutableStateFlow(loadFolderNames())
    private val unlockedFoldersState = MutableStateFlow<Set<String>>(emptySet())
    private val attempts = ConcurrentHashMap<String, AttemptState>()

    init {
        peers.add(this)
    }

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
                Unit
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

    /** Every lock this phone holds, for the encrypted backup. Records are copied; the store is not changed. */
    fun lockRecords(): List<FolderLockRecord> = lockedFoldersState.value.sorted().mapNotNull { key ->
        val salt = preferences.getString(keySalt(key), null) ?: return@mapNotNull null
        val hash = preferences.getString(keyHash(key), null) ?: return@mapNotNull null
        FolderLockRecord(key, salt, hash)
    }

    /**
     * Puts back locks from an encrypted backup, so a restored protected photo is behind the same
     * password it was before. ADDITIVE: a folder that is already locked here keeps the lock the
     * phone has (the person's current password wins and nothing is replaced); only folders without
     * a lock gain one. The folder starts locked, not unlocked. A record whose key is empty or whose
     * salt or hash is not the size this store writes is refused rather than stored.
     *
     * Written with `commit()`, not `apply()`: the restore applies its locks BEFORE it writes the first
     * protected photo, and the lock must be on disk by then or a crash would leave the photo visible.
     * Blocking; call it off the main thread.
     */
    @Synchronized
    fun restoreLocks(records: List<FolderLockRecord>): LockImport {
        var added = 0
        var kept = 0
        var rejected = 0
        val editor = preferences.edit()
        val gained = LinkedHashSet<String>()
        for (record in records) {
            if (record.folderKey.isBlank() || record.folderKey.length > MAX_FOLDER_KEY ||
                !hasLength(record.saltBase64, SALT_BYTES) || !hasLength(record.hashBase64, KEY_BITS / 8)
            ) {
                rejected++
                continue
            }
            if (record.folderKey in lockedFoldersState.value || record.folderKey in gained) {
                kept++
                continue
            }
            editor.putString(keySalt(record.folderKey), record.saltBase64).putString(keyHash(record.folderKey), record.hashBase64)
            gained += record.folderKey
            added++
        }
        if (gained.isNotEmpty()) {
            // A write that did not land is not a lock: say so, and let the restore stop before any photo is written.
            if (!editor.commit()) return LockImport(added = 0, kept = kept, rejected = rejected + added)
            // The activity holds its own instance and its own in-memory set: a lock only it does not know
            // about would leave the restored photos visible until the next launch.
            synchronized(peers) { peers.toList() }.forEach { it.lockedFoldersState.value = it.loadFolderNames() }
        }
        return LockImport(added, kept, rejected)
    }

    private fun hasLength(base64: String, bytes: Int): Boolean =
        runCatching { decode(base64).size == bytes }.getOrDefault(false)

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
        /** Live instances, weakly held, so one that is garbage collected is simply gone. */
        val peers: MutableSet<PrivateFolderStore> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        const val PREFERENCES_NAME = "foto_xplorr_private_folders"
        const val HASH_PREFIX = "hash:"
        const val SALT_PREFIX = "salt:"
        const val MIN_PASSWORD_LENGTH = 6
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val ITERATIONS = 210_000
        const val MAX_FOLDER_KEY = 2048
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
