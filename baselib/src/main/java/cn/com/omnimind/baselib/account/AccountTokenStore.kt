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

    private val preferences: SharedPreferences? = try {
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
    } catch (_: Exception) {
        null
    }

    @Volatile
    private var storageUnavailable: Boolean = preferences == null

    @Synchronized
    override fun read(): AccountTokens? {
        if (storageUnavailable) return null
        val store = preferences ?: return failClosedRead()
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
        } catch (_: Exception) {
            failClosedRead()
        }
    }

    @Synchronized
    override fun write(tokens: AccountTokens): Boolean {
        if (storageUnavailable || !tokens.hasAllFields()) return false
        val store = preferences ?: return false
        val written = try {
            store.edit()
                .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
                .putString(KEY_ACCESS_EXPIRES_AT, tokens.accessExpiresAt)
                .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
                .putString(KEY_REFRESH_EXPIRES_AT, tokens.refreshExpiresAt)
                .commit() && read() == tokens
        } catch (_: Exception) {
            false
        }
        if (!written) storageUnavailable = true
        return written
    }

    @Synchronized
    override fun clear(): Boolean {
        val store = preferences ?: return false
        val cleared = try {
            store.edit().clear().commit() &&
                listOf(
                    KEY_ACCESS_TOKEN,
                    KEY_ACCESS_EXPIRES_AT,
                    KEY_REFRESH_TOKEN,
                    KEY_REFRESH_EXPIRES_AT,
                ).none(store::contains)
        } catch (_: Exception) {
            false
        }
        // A verified clear can recover a preferences file containing only damaged
        // account-token entries without touching any unrelated app data.
        storageUnavailable = !cleared
        return cleared
    }

    private fun failClosedRead(): AccountTokens? {
        storageUnavailable = true
        return null
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
