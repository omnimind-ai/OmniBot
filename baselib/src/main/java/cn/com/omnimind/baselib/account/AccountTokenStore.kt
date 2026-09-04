package cn.com.omnimind.baselib.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface AccountTokenStore {
    fun read(): AccountTokens?

    fun write(tokens: AccountTokens): Boolean

    fun clear(): Boolean
}

/**
 * Stores account credentials in an encrypted SharedPreferences file whose key
 * is held by Android Keystore. Model-provider API keys are deliberately not
 * stored here.
 */
class EncryptedAccountTokenStore(context: Context) : AccountTokenStore {
    private val applicationContext = context.applicationContext

    /**
     * Keystore-backed preferences can fail transiently while the process is
     * being restored or the user is switching away from the app. Do not make
     * that one attempt a process-lifetime state. The next operation retries
     * opening the same encrypted file.
     */
    private var preferences: SharedPreferences? = null

    @Synchronized
    override fun read(): AccountTokens? {
        val store = preferencesOrNull()
            ?: throw AccountCredentialStorageException()
        return try {
            val accessToken = store.getString(KEY_ACCESS_TOKEN, null)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val accessExpiresAt = store.getString(KEY_ACCESS_EXPIRES_AT, null)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val refreshToken = store.getString(KEY_REFRESH_TOKEN, null)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val refreshExpiresAt = store.getString(KEY_REFRESH_EXPIRES_AT, null)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            AccountTokens(
                accessToken = accessToken,
                accessExpiresAt = accessExpiresAt,
                refreshToken = refreshToken,
                refreshExpiresAt = refreshExpiresAt,
            )
        } catch (error: Exception) {
            preferences = null
            throw AccountCredentialStorageException(cause = error)
        }
    }

    @Synchronized
    override fun write(tokens: AccountTokens): Boolean {
        if (!tokens.hasAllFields()) return false
        val store = preferencesOrNull() ?: return false
        val written = try {
            store.edit()
                .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
                .putString(KEY_ACCESS_EXPIRES_AT, tokens.accessExpiresAt)
                .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
                .putString(KEY_REFRESH_EXPIRES_AT, tokens.refreshExpiresAt)
                .commit() && read() == tokens
        } catch (_: Exception) {
            preferences = null
            false
        }
        return written
    }

    @Synchronized
    override fun clear(): Boolean {
        val store = preferencesOrNull() ?: return false
        val cleared = try {
            store.edit().clear().commit() &&
                listOf(
                    KEY_ACCESS_TOKEN,
                    KEY_ACCESS_EXPIRES_AT,
                    KEY_REFRESH_TOKEN,
                    KEY_REFRESH_EXPIRES_AT,
                ).none(store::contains)
        } catch (_: Exception) {
            preferences = null
            false
        }
        return cleared
    }

    private fun preferencesOrNull(): SharedPreferences? {
        preferences?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also {
                // Force the encrypted file/key to be opened now, while still
                // allowing a later lifecycle operation to retry after a
                // transient Keystore failure.
                it.all
                preferences = it
            }
        } catch (_: Exception) {
            preferences = null
            null
        }
    }

    companion object {
        const val FILE_NAME = "omni_account_tokens"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
    }
}

private fun AccountTokens.hasAllFields(): Boolean =
    accessToken.isNotBlank() && accessExpiresAt.isNotBlank() &&
        refreshToken.isNotBlank() && refreshExpiresAt.isNotBlank()
