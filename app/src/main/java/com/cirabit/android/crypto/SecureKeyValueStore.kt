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
class SecureKeyValueStore(
    context: Context,
    prefsName: String
) {
    companion object {
        private const val TAG = "SecureKeyValueStore"
        private const val KEYSET_PREFS = "cirabit_tink_keysets"
        private const val KEYSET_NAME_PREFIX = "cirabit_aead_"
        private const val MASTER_KEY_URI = "android-keystore://cirabit_tink_master_key"
    }

    private val storage: SharedPreferences =
        context.getSharedPreferences("${prefsName}_tink_store", Context.MODE_PRIVATE)

    private val aead: Aead = run {
        AeadConfig.register()
        val keysetManager = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME_PREFIX + prefsName, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        keysetManager.keysetHandle.getPrimitive(Aead::class.java)
    }

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
            val ciphertext = aead.encrypt(value.toByteArray(Charsets.UTF_8), associatedData)
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
            val plaintext = aead.decrypt(ciphertext, associatedData)
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
