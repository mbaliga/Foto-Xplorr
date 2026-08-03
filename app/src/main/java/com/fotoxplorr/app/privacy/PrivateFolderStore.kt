package com.fotoxplorr.app.privacy

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PrivateFolderStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lockedFoldersState = MutableStateFlow(loadFolderNames())
    private val unlockedFoldersState = MutableStateFlow<Set<String>>(emptySet())

    fun observeLockedFolders(): StateFlow<Set<String>> = lockedFoldersState.asStateFlow()
    fun observeUnlockedFolders(): StateFlow<Set<String>> = unlockedFoldersState.asStateFlow()

    fun protect(folderName: String, password: CharArray): Result<Unit> = runCatching {
        require(folderName.isNotBlank()) { "Folder name is required" }
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "Password must contain at least $MIN_PASSWORD_LENGTH characters"
        }

        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(password, salt)
        password.fill('\u0000')

        preferences.edit()
            .putString(keySalt(folderName), encode(salt))
            .putString(keyHash(folderName), encode(hash))
            .apply()

        lockedFoldersState.value = lockedFoldersState.value + folderName
        unlockedFoldersState.value = unlockedFoldersState.value + folderName
    }

    fun unlock(folderName: String, password: CharArray): Boolean {
        val salt = preferences.getString(keySalt(folderName), null)?.let(::decode)
        val expected = preferences.getString(keyHash(folderName), null)?.let(::decode)
        if (salt == null || expected == null) {
            password.fill('\u0000')
            return false
        }

        val actual = derive(password, salt)
        password.fill('\u0000')
        val matches = constantTimeEquals(expected, actual)
        actual.fill(0)
        if (matches) unlockedFoldersState.value = unlockedFoldersState.value + folderName
        return matches
    }

    fun lock(folderName: String) {
        unlockedFoldersState.value = unlockedFoldersState.value - folderName
    }

    fun lockAll() {
        unlockedFoldersState.value = emptySet()
    }

    fun removeProtection(folderName: String, password: CharArray): Boolean {
        if (!unlock(folderName, password)) return false
        preferences.edit()
            .remove(keySalt(folderName))
            .remove(keyHash(folderName))
            .apply()
        lockedFoldersState.value = lockedFoldersState.value - folderName
        unlockedFoldersState.value = unlockedFoldersState.value - folderName
        return true
    }

    private fun loadFolderNames(): Set<String> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(HASH_PREFIX) }
        .map { it.removePrefix(HASH_PREFIX) }
        .toSet()

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_private_folders"
        const val HASH_PREFIX = "hash:"
        const val SALT_PREFIX = "salt:"
        const val MIN_PASSWORD_LENGTH = 6
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val ITERATIONS = 210_000

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
