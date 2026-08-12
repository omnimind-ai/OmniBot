package cn.com.omnimind.baselib.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class SecretReadResult(
    val succeeded: Boolean,
    val value: String?,
)

/** Keystore-backed storage for small app credentials. Never falls back to plaintext. */
object AppSecretStore {
    private const val FILE_NAME = "omni_app_credentials"

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var initializationAttempted = false

    @Synchronized
    fun initialize(context: Context): Boolean {
        if (initializationAttempted) return preferences != null
        initializationAttempted = true
        preferences = runCatching {
            val applicationContext = context.applicationContext
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { it.all }
        }.getOrNull()
        return preferences != null
    }

    fun isAvailable(): Boolean = preferences != null

    @Synchronized
    fun read(key: String): String? {
        return readWithStatus(key).value
    }

    /** Distinguishes an absent value from a Keystore/encrypted-preferences read failure. */
    @Synchronized
    fun readWithStatus(key: String): SecretReadResult {
        val safeKey = validateKey(key) ?: return SecretReadResult(false, null)
        val store = preferences ?: return SecretReadResult(false, null)
        return runCatching {
            store.getString(safeKey, null)?.takeIf(String::isNotEmpty)
        }.fold(
            onSuccess = { SecretReadResult(true, it) },
            onFailure = { SecretReadResult(false, null) },
        )
    }

    /** Writes and verifies the value before reporting success. */
    @Synchronized
    fun write(key: String, value: String): Boolean {
        val safeKey = validateKey(key) ?: return false
        val store = preferences ?: return false
        if (value.isEmpty()) return delete(safeKey)
        return runCatching {
            store.edit().putString(safeKey, value).commit() &&
                store.getString(safeKey, null) == value
        }.getOrDefault(false)
    }

    @Synchronized
    fun delete(key: String): Boolean {
        val safeKey = validateKey(key) ?: return false
        val store = preferences ?: return false
        return runCatching {
            store.edit().remove(safeKey).commit() && !store.contains(safeKey)
        }.getOrDefault(false)
    }

    @Synchronized
    fun deletePrefix(prefix: String): Boolean {
        val safePrefix = validateKey(prefix) ?: return false
        val store = preferences ?: return false
        return runCatching {
            val keys = store.all.keys.filter { it.startsWith(safePrefix) }
            if (keys.isEmpty()) return@runCatching true
            val editor = store.edit()
            keys.forEach(editor::remove)
            editor.commit() && keys.none(store::contains)
        }.getOrDefault(false)
    }

    private fun validateKey(key: String): String? = key.trim()
        .takeIf { it.isNotEmpty() && it.length <= 512 }
}
