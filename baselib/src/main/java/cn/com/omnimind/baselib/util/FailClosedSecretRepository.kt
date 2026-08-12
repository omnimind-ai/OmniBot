package cn.com.omnimind.baselib.util

/**
 * Minimal storage contract used by [FailClosedSecretRepository].
 *
 * Implementations must never fall back to plaintext persistence.
 */
interface SecureSecretStore {
    fun isAvailable(): Boolean

    fun read(key: String): String?

    fun write(key: String, value: String): Boolean

    fun delete(key: String): Boolean
}

/** A snapshot of secrets left by an older, less secure storage implementation. */
data class LegacySecretSnapshot(
    val existed: Boolean,
    val candidates: List<String>,
)

interface LegacySecretStore {
    fun snapshot(): LegacySecretSnapshot

    /** Must erase every old plaintext or weakly encrypted representation. */
    fun erase()
}

/** Adapter for the process-wide Android Keystore-backed [AppSecretStore]. */
object AppSecretStoreBackend : SecureSecretStore {
    override fun isAvailable(): Boolean = AppSecretStore.isAvailable()

    override fun read(key: String): String? {
        val result = AppSecretStore.readWithStatus(key)
        check(result.succeeded) { "Secure secret store read failed" }
        return result.value
    }

    override fun write(key: String, value: String): Boolean = AppSecretStore.write(key, value)

    override fun delete(key: String): Boolean = AppSecretStore.delete(key)
}

/**
 * Resolves one small secret without ever accepting a plaintext fallback.
 *
 * Legacy data is erased after the first resolution attempt, including when migration fails.
 * A corrupt legacy or secure value fails closed instead of silently creating a replacement
 * identity. Newly generated values are usable only after a verified secure-store round trip.
 */
class FailClosedSecretRepository(
    private val key: String,
    private val secureStore: SecureSecretStore,
    private val legacyStore: LegacySecretStore,
    private val isValid: (String) -> Boolean,
) {
    @Synchronized
    fun loadExisting(): String? = resolve(create = null)

    @Synchronized
    fun loadOrCreate(create: () -> String): String? = resolve(create)

    private fun resolve(create: (() -> String)?): String? {
        if (!secureStore.isAvailable()) {
            eraseLegacy()
            runCatching { secureStore.delete(key) }
            return null
        }

        val securedRead = runCatching { secureStore.read(key) }
        if (securedRead.isFailure) {
            eraseLegacy()
            runCatching { secureStore.delete(key) }
            return null
        }
        val secured = securedRead.getOrNull()
        if (secured != null) {
            eraseLegacy()
            if (isValid(secured)) return secured
            runCatching { secureStore.delete(key) }
            return null
        }

        val legacy = runCatching { legacyStore.snapshot() }
            .getOrElse { LegacySecretSnapshot(existed = true, candidates = emptyList()) }
        return try {
            val candidate = legacy.candidates.firstOrNull(isValid)
            when {
                candidate != null -> candidate.takeIf(::persistVerified)
                legacy.existed -> null
                create != null -> runCatching(create)
                    .getOrNull()
                    ?.takeIf(isValid)
                    ?.takeIf(::persistVerified)
                else -> null
            }
        } finally {
            eraseLegacy()
        }
    }

    /** Replaces the secret only when the new value survives a verified secure-store round trip. */
    @Synchronized
    fun replace(value: String): Boolean {
        eraseLegacy()
        if (!secureStore.isAvailable() || !isValid(value)) return false
        return persistVerified(value)
    }

    @Synchronized
    fun clear(): Boolean {
        eraseLegacy()
        return runCatching { secureStore.delete(key) }.getOrDefault(false)
    }

    private fun persistVerified(value: String): Boolean {
        val persisted = runCatching {
            secureStore.write(key, value) && secureStore.read(key) == value
        }.getOrDefault(false)
        if (!persisted) {
            runCatching { secureStore.delete(key) }
        }
        return persisted
    }

    private fun eraseLegacy() {
        runCatching { legacyStore.erase() }
    }
}
