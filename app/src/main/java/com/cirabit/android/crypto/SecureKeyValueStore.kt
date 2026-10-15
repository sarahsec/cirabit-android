package com.cirabit.android.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Encrypted key-value storage backed by Tink AEAD.
 *
 * Values are encrypted with key-specific associated data and persisted in regular SharedPreferences.
 * This avoids direct reliance on EncryptedSharedPreferences while keeping data at-rest encrypted.
 */
class SecureKeyValueStore private constructor(
    private val storage: SharedPreferences,
    /** Null only for test-only plaintext storage (see [forTesting]). */
    private val aead: Aead?
) {
    companion object {
        private const val TAG = "SecureKeyValueStore"
        private const val KEYSET_PREFS = "cirabit_tink_keysets"
        private const val KEYSET_NAME_PREFIX = "cirabit_aead_"
        private const val MASTER_KEY_URI = "android-keystore://cirabit_tink_master_key"

        private fun buildAead(context: Context, prefsName: String): Aead {
            AeadConfig.register()
            val keysetManager = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME_PREFIX + prefsName, KEYSET_PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
            return keysetManager.keysetHandle.getPrimitive(Aead::class.java)
        }

        /** Test-only: wraps plain SharedPreferences without encryption. */
        internal fun forTesting(prefs: SharedPreferences): SecureKeyValueStore =
            SecureKeyValueStore(prefs, null)
    }

    constructor(context: Context, prefsName: String) : this(
        context.getSharedPreferences("${prefsName}_tink_store", Context.MODE_PRIVATE),
        buildAead(context, prefsName)
    )

    /**
     * Batched edit that is written in a single SharedPreferences transaction.
     * Use [Editor.commit] when the caller needs a durable, atomic write.
     */
    inner class Editor internal constructor() {
        private val delegate = storage.edit()

        fun putString(key: String, value: String?): Editor {
            if (value == null) {
                delegate.remove(key)
            } else {
                val encrypted = encryptString(key, value)
                    ?: throw IllegalStateException("Failed to encrypt value for key '$key'")
                delegate.putString(key, encrypted)
            }
            return this
        }

        fun putStringSet(key: String, value: Set<String>): Editor = putString(key, packStringSet(value))

        fun remove(key: String): Editor {
            delegate.remove(key)
            return this
        }

        fun clear(): Editor {
            delegate.clear()
            return this
        }

        fun commit(): Boolean = delegate.commit()

        fun apply() = delegate.apply()
    }

    fun edit(): Editor = Editor()

    fun getString(key: String, defaultValue: String? = null): String? {
        val encrypted = storage.getString(key, null) ?: return defaultValue
        return decryptString(key, encrypted) ?: defaultValue
    }

    fun putString(key: String, value: String?) {
        if (value == null) {
            remove(key)
            return
        }
        val encrypted = encryptString(key, value) ?: return
        storage.edit().putString(key, encrypted).apply()
    }

    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String> {
        val packed = getString(key, null) ?: return defaultValue
        return unpackStringSet(packed) ?: defaultValue
    }

    fun putStringSet(key: String, value: Set<String>) {
        putString(key, packStringSet(value))
    }

    fun contains(key: String): Boolean = storage.contains(key)

    fun remove(key: String) {
        storage.edit().remove(key).apply()
    }

    fun clear() {
        storage.edit().clear().apply()
    }

    private fun encryptString(key: String, value: String): String? {
        return try {
            val associatedData = key.toByteArray(Charsets.UTF_8)
            val ciphertext = (aead ?: return value).encrypt(value.toByteArray(Charsets.UTF_8), associatedData)
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt value for key '$key': ${e.message}")
            null
        }
    }

    private fun decryptString(key: String, encodedCiphertext: String): String? {
        return try {
            val associatedData = key.toByteArray(Charsets.UTF_8)
            val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
            val plaintext = (aead ?: return encodedCiphertext).decrypt(ciphertext, associatedData)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt value for key '$key': ${e.message}")
            null
        }
    }

    private fun packStringSet(values: Set<String>): String {
        return values.joinToString(separator = ",") { item ->
            Base64.encodeToString(item.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun unpackStringSet(packed: String): Set<String>? {
        return try {
            if (packed.isEmpty()) return emptySet()
            packed.split(',')
                .map { encoded ->
                    String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
                }
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode encrypted string set: ${e.message}")
            null
        }
    }
}
