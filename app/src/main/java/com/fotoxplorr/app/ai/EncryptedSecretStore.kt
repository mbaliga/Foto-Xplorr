package com.fotoxplorr.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores provider credentials encrypted by a non-exportable Android Keystore key. */
class EncryptedSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun put(id: String, value: CharArray) {
        require(id.isNotBlank())
        val bytes = String(value).toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(bytes)
            preferences.edit()
                .putString(ivKey(id), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(valueKey(id), Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
        } finally {
            bytes.fill(0)
            value.fill('\u0000')
        }
    }

    @Synchronized
    fun get(id: String): CharArray? {
        val iv = preferences.getString(ivKey(id), null)?.decodeBase64() ?: return null
        val encrypted = preferences.getString(valueKey(id), null)?.decodeBase64() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(encrypted)
            try {
                String(plaintext, StandardCharsets.UTF_8).toCharArray()
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Throwable) {
            null
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    @Synchronized
    fun contains(id: String): Boolean =
        preferences.contains(ivKey(id)) && preferences.contains(valueKey(id))

    @Synchronized
    fun remove(id: String) {
        preferences.edit().remove(ivKey(id)).remove(valueKey(id)).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private fun ivKey(id: String) = "secret_iv:$id"
    private fun valueKey(id: String) = "secret_value:$id"

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_encrypted_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "foto_xplorr_provider_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
