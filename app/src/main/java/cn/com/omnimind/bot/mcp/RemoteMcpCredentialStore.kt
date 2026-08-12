package cn.com.omnimind.bot.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal interface RemoteMcpCredentialStore {
    fun verifyAvailable()

    fun readToken(serverId: String): String?

    fun writeToken(serverId: String, token: String)

    fun deleteToken(serverId: String)

    fun deleteTokensExcept(serverIds: Set<String>)
}

internal class EncryptedRemoteMcpCredentialStore(context: Context) : RemoteMcpCredentialStore {
    private val applicationContext = context.applicationContext

    private val preferences: SharedPreferences by lazy {
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

    override fun verifyAvailable() {
        preferences.all
    }

    @Synchronized
    override fun readToken(serverId: String): String? {
        return preferences.getString(storageKey(serverId), null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    @Synchronized
    override fun writeToken(serverId: String, token: String) {
        val normalized = token.trim()
        if (normalized.isEmpty()) {
            deleteToken(serverId)
            return
        }
        check(preferences.edit().putString(storageKey(serverId), normalized).commit()) {
            "failed to store encrypted remote MCP credential"
        }
    }

    @Synchronized
    override fun deleteToken(serverId: String) {
        check(preferences.edit().remove(storageKey(serverId)).commit()) {
            "failed to delete encrypted remote MCP credential"
        }
    }

    @Synchronized
    override fun deleteTokensExcept(serverIds: Set<String>) {
        val retained = serverIds.mapTo(HashSet(), ::storageKey)
        val stale = preferences.all.keys.filter { it.startsWith(TOKEN_PREFIX) && it !in retained }
        if (stale.isEmpty()) return
        val editor = preferences.edit()
        stale.forEach(editor::remove)
        check(editor.commit()) { "failed to prune encrypted remote MCP credentials" }
    }

    private fun storageKey(serverId: String): String {
        val encoded = Base64.encodeToString(
            serverId.trim().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
        return "$TOKEN_PREFIX$encoded"
    }

    private companion object {
        const val FILE_NAME = "omni_remote_mcp_credentials"
        const val TOKEN_PREFIX = "bearer_"
    }
}
