package cn.com.omnimind.baselib.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class SecretReadResult(
    val succeeded: Boolean,
    val value: String?,
)

/** Keystore-backed storage for small AI credentials. Never falls back to plaintext. */
object AppSecretStore {
    private const val FILE_NAME = "omni_app_credentials"
    private val preferencesOwner = LazySecretPreferences()

    /** Registers the application context without opening the optional TTS credential file. */
    fun prepare(context: Context) {
        val applicationContext = context.applicationContext
        preferencesOwner.prepare {
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    /** Explicit initialization still opens and validates the file immediately. */
    @Synchronized
    fun initialize(context: Context): Boolean = runCatching {
        prepare(context)
        preferencesOwner.getOrNull() != null
    }.getOrDefault(false)

    fun isAvailable(): Boolean = preferencesOwner.getOrNull() != null

    @Synchronized
    fun read(key: String): String? = readWithStatus(key).value

    /** Distinguishes an absent value from a Keystore/encrypted-preferences read failure. */
    @Synchronized
    fun readWithStatus(key: String): SecretReadResult {
        val safeKey = validateKey(key) ?: return SecretReadResult(false, null)
        val store = preferencesOwner.getOrNull() ?: return SecretReadResult(false, null)
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
        val store = preferencesOwner.getOrNull() ?: return false
        if (value.isEmpty()) return delete(safeKey)
        return runCatching {
            store.edit().putString(safeKey, value).commit() &&
                store.getString(safeKey, null) == value
        }.getOrDefault(false)
    }

    @Synchronized
    fun delete(key: String): Boolean {
        val safeKey = validateKey(key) ?: return false
        val store = preferencesOwner.getOrNull() ?: return false
        return runCatching {
            store.edit().remove(safeKey).commit() && !store.contains(safeKey)
        }.getOrDefault(false)
    }

    @Synchronized
    fun deletePrefix(prefix: String): Boolean {
        val safePrefix = validateKey(prefix) ?: return false
        val store = preferencesOwner.getOrNull() ?: return false
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

/** Opens at most once, publishing preferences only after every stored value validates. */
internal class LazySecretPreferences {
    private var open: (() -> SharedPreferences)? = null
    private var initializationAttempted = false
    private var preferences: SharedPreferences? = null

    @Synchronized
    fun prepare(open: () -> SharedPreferences) {
        if (this.open == null && !initializationAttempted) {
            this.open = open
        }
    }

    @Synchronized
    fun getOrNull(): SharedPreferences? {
        if (initializationAttempted) return preferences
        // An access before App.prepare must not consume the one real attempt.
        val opener = open ?: return null
        initializationAttempted = true
        preferences = runCatching { opener().also { it.all } }.getOrNull()
        open = null
        return preferences
    }
}
