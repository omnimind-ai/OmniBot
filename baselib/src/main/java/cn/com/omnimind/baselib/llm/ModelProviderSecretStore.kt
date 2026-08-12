package cn.com.omnimind.baselib.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal data class ModelProviderSecrets(
    val apiKey: String = "",
    val customHeaders: Map<String, String> = emptyMap()
)

internal interface ModelProviderSecretStore {
    fun isAvailable(): Boolean

    fun readProfile(profileId: String): ModelProviderSecrets?

    fun writeProfile(profileId: String, secrets: ModelProviderSecrets)

    fun deleteProfile(profileId: String)

    fun deleteProfilesExcept(profileIds: Set<String>)

    fun readLegacy(storageKey: String): String?

    fun writeLegacy(storageKey: String, apiKey: String)

    fun deleteLegacy(storageKey: String)
}

/**
 * Keeps user-supplied model-provider credentials in an encrypted preferences
 * file whose master key is held by Android Keystore.
 *
 * Provider metadata remains in MMKV, but API keys and custom header values do
 * not. The encrypted preferences file is also excluded from Android backup and
 * device transfer by the app module's backup rules.
 */
internal class EncryptedModelProviderSecretStore(context: Context) : ModelProviderSecretStore {
    private val applicationContext = context.applicationContext
    private val gson = Gson()

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun isAvailable(): Boolean = try {
        preferences.all
        true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    override fun readProfile(profileId: String): ModelProviderSecrets? {
        val encodedId = encodeKeyPart(profileId)
        val apiKeyStorageKey = "$PROFILE_API_KEY_PREFIX$encodedId"
        val headersStorageKey = "$PROFILE_HEADERS_PREFIX$encodedId"
        if (!preferences.contains(apiKeyStorageKey) && !preferences.contains(headersStorageKey)) {
            return null
        }
        val apiKey = preferences.getString(apiKeyStorageKey, null)?.trim().orEmpty()
        val headers = decodeHeaders(preferences.getString(headersStorageKey, null))
        return ModelProviderSecrets(apiKey = apiKey, customHeaders = headers)
    }

    @Synchronized
    override fun writeProfile(profileId: String, secrets: ModelProviderSecrets) {
        val encodedId = encodeKeyPart(profileId)
        val apiKeyStorageKey = "$PROFILE_API_KEY_PREFIX$encodedId"
        val headersStorageKey = "$PROFILE_HEADERS_PREFIX$encodedId"
        val apiKey = secrets.apiKey.trim()
        val headers = ProviderCustomHeaderUtils.sanitizeCustomHeaders(secrets.customHeaders)
        val editor = preferences.edit()
        if (apiKey.isEmpty()) {
            editor.remove(apiKeyStorageKey)
        } else {
            editor.putString(apiKeyStorageKey, apiKey)
        }
        if (headers.isEmpty()) {
            editor.remove(headersStorageKey)
        } else {
            editor.putString(headersStorageKey, gson.toJson(headers))
        }
        check(editor.commit()) { "failed to store encrypted model-provider credentials" }
        val expected = ModelProviderSecrets(apiKey, headers)
            .takeUnless { it.apiKey.isEmpty() && it.customHeaders.isEmpty() }
        check(readProfile(profileId) == expected) {
            "failed to verify encrypted model-provider credentials"
        }
    }

    @Synchronized
    override fun deleteProfile(profileId: String) {
        val encodedId = encodeKeyPart(profileId)
        check(
            preferences.edit()
                .remove("$PROFILE_API_KEY_PREFIX$encodedId")
                .remove("$PROFILE_HEADERS_PREFIX$encodedId")
                .commit()
        ) { "failed to delete encrypted model-provider credentials" }
        check(readProfile(profileId) == null) {
            "failed to verify encrypted model-provider credential deletion"
        }
    }

    @Synchronized
    override fun deleteProfilesExcept(profileIds: Set<String>) {
        val retainedIds = profileIds.mapTo(HashSet(), ::encodeKeyPart)
        val keysToDelete = preferences.all.keys.filter { key ->
            when {
                key.startsWith(PROFILE_API_KEY_PREFIX) ->
                    key.removePrefix(PROFILE_API_KEY_PREFIX) !in retainedIds
                key.startsWith(PROFILE_HEADERS_PREFIX) ->
                    key.removePrefix(PROFILE_HEADERS_PREFIX) !in retainedIds
                else -> false
            }
        }
        if (keysToDelete.isEmpty()) {
            return
        }
        val editor = preferences.edit()
        keysToDelete.forEach(editor::remove)
        check(editor.commit()) { "failed to prune encrypted model-provider credentials" }
        check(keysToDelete.none(preferences::contains)) {
            "failed to verify encrypted model-provider credential pruning"
        }
    }

    @Synchronized
    override fun readLegacy(storageKey: String): String? {
        return preferences.getString("$LEGACY_API_KEY_PREFIX${encodeKeyPart(storageKey)}", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    override fun writeLegacy(storageKey: String, apiKey: String) {
        val key = "$LEGACY_API_KEY_PREFIX${encodeKeyPart(storageKey)}"
        val normalized = apiKey.trim()
        val editor = preferences.edit()
        if (normalized.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, normalized)
        }
        check(editor.commit()) { "failed to store encrypted legacy model-provider credential" }
        check(readLegacy(storageKey) == normalized.takeIf(String::isNotEmpty)) {
            "failed to verify encrypted legacy model-provider credential"
        }
    }

    @Synchronized
    override fun deleteLegacy(storageKey: String) {
        val key = "$LEGACY_API_KEY_PREFIX${encodeKeyPart(storageKey)}"
        check(
            preferences.edit()
                .remove(key)
                .commit()
        ) { "failed to delete encrypted legacy model-provider credential" }
        check(!preferences.contains(key)) {
            "failed to verify encrypted legacy model-provider credential deletion"
        }
    }

    private fun decodeHeaders(raw: String?): Map<String, String> {
        val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val decoded: Map<String, String> = gson.fromJson(normalized, type) ?: emptyMap()
            ProviderCustomHeaderUtils.sanitizeCustomHeaders(decoded)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encodeKeyPart(value: String): String {
        return Base64.encodeToString(
            value.trim().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
        )
    }

    companion object {
        const val FILE_NAME = "omni_model_provider_secrets"

        private const val PROFILE_API_KEY_PREFIX = "profile_api_key_"
        private const val PROFILE_HEADERS_PREFIX = "profile_headers_"
        private const val LEGACY_API_KEY_PREFIX = "legacy_api_key_"
    }
}

/**
 * Converts any Keystore/encrypted-preferences exception into a process-local
 * unavailable state. Reads then return no credentials, while writes/deletes
 * fail explicitly so callers cannot report a successful BYOK mutation.
 */
internal class FailClosedModelProviderSecretStore(
    private val delegate: ModelProviderSecretStore?,
) : ModelProviderSecretStore {
    @Volatile
    private var unavailable: Boolean = delegate?.let { store ->
        try {
            !store.isAvailable()
        } catch (_: Exception) {
            true
        }
    } ?: true

    override fun isAvailable(): Boolean = !unavailable

    @Synchronized
    override fun readProfile(profileId: String): ModelProviderSecrets? = readSafely {
        it.readProfile(profileId)
    }

    @Synchronized
    override fun writeProfile(profileId: String, secrets: ModelProviderSecrets) {
        writeSafely { it.writeProfile(profileId, secrets) }
    }

    @Synchronized
    override fun deleteProfile(profileId: String) {
        writeSafely { it.deleteProfile(profileId) }
    }

    @Synchronized
    override fun deleteProfilesExcept(profileIds: Set<String>) {
        writeSafely { it.deleteProfilesExcept(profileIds) }
    }

    @Synchronized
    override fun readLegacy(storageKey: String): String? = readSafely {
        it.readLegacy(storageKey)
    }

    @Synchronized
    override fun writeLegacy(storageKey: String, apiKey: String) {
        writeSafely { it.writeLegacy(storageKey, apiKey) }
    }

    @Synchronized
    override fun deleteLegacy(storageKey: String) {
        writeSafely { it.deleteLegacy(storageKey) }
    }

    private fun <T> readSafely(block: (ModelProviderSecretStore) -> T): T? {
        if (unavailable) return null
        val current = delegate ?: return failClosedRead()
        return try {
            block(current)
        } catch (_: Exception) {
            failClosedRead()
        }
    }

    private fun writeSafely(block: (ModelProviderSecretStore) -> Unit) {
        check(!unavailable) { UNAVAILABLE_MESSAGE }
        val current = delegate ?: error(UNAVAILABLE_MESSAGE)
        try {
            block(current)
        } catch (_: Exception) {
            unavailable = true
            throw IllegalStateException(UNAVAILABLE_MESSAGE)
        }
    }

    private fun <T> failClosedRead(): T? {
        unavailable = true
        return null
    }

    private companion object {
        const val UNAVAILABLE_MESSAGE = "Secure model-provider credential storage is unavailable"
    }
}
