package cn.com.omnimind.assists.openclaw

import android.content.Context
import android.content.SharedPreferences

internal data class OpenClawDenyTombstone(
    val denied: Boolean,
    val generation: Long,
)

/** Non-secret persistent second gate used when encrypted configuration storage cannot be trusted. */
internal interface OpenClawDenyTombstoneStore {
    /** Null means unreadable/unavailable and callers must fail closed. */
    fun read(): OpenClawDenyTombstone?

    /** Persists deny=true and verifies it by reading the committed values back. */
    fun markDenied(generation: Long): Boolean

    /** Persists deny=false only for the generation that was successfully committed to Keystore. */
    fun clearDenied(committedGeneration: Long): Boolean
}

internal object AndroidOpenClawDenyTombstoneStore : OpenClawDenyTombstoneStore {
    @Volatile
    private var preferences: SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context): Boolean {
        if (preferences != null) return read() != null
        val initialized = try {
            context.applicationContext.getSharedPreferences(
                FILE_NAME,
                Context.MODE_PRIVATE,
            )
        } catch (_: Exception) {
            null
        }
        preferences = initialized
        if (initialized == null) return false
        val verified = try {
            if (!initialized.contains(KEY_DENIED) || !initialized.contains(KEY_GENERATION)) {
                val existingGeneration = initialized.getLong(KEY_GENERATION, 0L)
                    .coerceAtLeast(0L)
                initialized.edit()
                    .putBoolean(KEY_DENIED, true)
                    .putLong(KEY_GENERATION, existingGeneration)
                    .commit() &&
                    initialized.getBoolean(KEY_DENIED, false) &&
                    initialized.getLong(KEY_GENERATION, -1L) == existingGeneration
            } else {
                read() != null
            }
        } catch (_: Exception) {
            false
        }
        if (!verified) preferences = null
        return verified
    }

    @Synchronized
    override fun read(): OpenClawDenyTombstone? {
        val store = preferences ?: return null
        return try {
            if (!store.contains(KEY_DENIED) || !store.contains(KEY_GENERATION)) return null
            OpenClawDenyTombstone(
                // Missing state is an upgrade/fresh-install state and is deliberately denied.
                denied = store.getBoolean(KEY_DENIED, true),
                generation = store.getLong(KEY_GENERATION, 0L).coerceAtLeast(0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun markDenied(generation: Long): Boolean {
        val store = preferences ?: return false
        val safeGeneration = generation.coerceAtLeast(0L)
        return try {
            val existingGeneration = store.getLong(KEY_GENERATION, 0L).coerceAtLeast(0L)
            val committedGeneration = maxOf(existingGeneration, safeGeneration)
            store.edit()
                .putBoolean(KEY_DENIED, true)
                .putLong(KEY_GENERATION, committedGeneration)
                .commit() &&
                store.getBoolean(KEY_DENIED, false) &&
                store.getLong(KEY_GENERATION, -1L) == committedGeneration
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    override fun clearDenied(committedGeneration: Long): Boolean {
        val store = preferences ?: return false
        val safeGeneration = committedGeneration.coerceAtLeast(0L)
        return try {
            val currentGeneration = store.getLong(KEY_GENERATION, 0L).coerceAtLeast(0L)
            if (currentGeneration > safeGeneration) return false
            store.edit()
                .putBoolean(KEY_DENIED, false)
                .putLong(KEY_GENERATION, safeGeneration)
                .commit() &&
                !store.getBoolean(KEY_DENIED, true) &&
                store.getLong(KEY_GENERATION, -1L) == safeGeneration
        } catch (_: Exception) {
            false
        }
    }

    private const val FILE_NAME = "omnibot_openclaw_gate"
    private const val KEY_DENIED = "deny"
    private const val KEY_GENERATION = "generation"
}
