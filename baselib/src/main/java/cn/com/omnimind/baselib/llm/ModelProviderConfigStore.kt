package cn.com.omnimind.baselib.llm

import android.content.Context
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import cn.com.omnimind.baselib.util.OssIdentity
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.net.URI

object ModelProviderConfigStore {
    private const val TAG = "ModelProviderConfigStore"
    private const val DIRECT_REQUEST_URL_MARKER = "#"

    internal const val KEY_PROVIDER_BASE_URL = "model_provider_openai_base_url"
    internal const val KEY_PROVIDER_API_KEY = "model_provider_openai_api_key"
    private const val KEY_PROVIDER_PROFILES = "model_provider_profiles_v1"
    private const val KEY_EDITING_PROFILE_ID = "model_provider_editing_profile_id"
    private const val KEY_BUILTIN_OFFICIAL_PROFILES_SEEDED =
        "model_provider_builtin_official_profiles_seeded_v2"
    private const val KEY_DELETED_OFFICIAL_PROFILE_IDS =
        "model_provider_deleted_official_profile_ids_v1"

    internal const val MIGRATION_DONE_KEY = "model_provider_scene_config_flattened_v3"
    internal const val LEGACY_DEFAULT_PROFILE_ID = "legacy-default"

    private const val DEFAULT_PROFILE_ID = "profile-1"
    private const val DEFAULT_PROFILE_NAME = "Provider 1"
    private val canonicalEndpointSuffixes = listOf(
        "/v1/chat/completions",
        "/chat/completions",
        "/v1/responses",
        "/responses",
        "/v1/images/generations",
        "/images/generations",
        "/v1/models",
        "/models",
        "/v1/messages",
        "/messages"
    )
    private val canonicalVersionBaseSuffixes = listOf(
        "/v1",
        "/compatible-mode/v1"
    )

    private val gson = Gson()

    @Volatile
    private var secretStore: ModelProviderSecretStore? = null

    /**
     * Must run after MMKV initialization and before provider configuration is used.
     * Existing plaintext credentials are moved into Keystore-backed storage once.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (secretStore != null) {
            return
        }
        val encryptedStore = try {
            EncryptedModelProviderSecretStore(context)
        } catch (_: Exception) {
            null
        }
        secretStore = FailClosedModelProviderSecretStore(encryptedStore)
        try {
            ModelProviderMigration.ensureMigrated()
        } catch (_: Exception) {
            // Credential migration below still performs conservative plaintext cleanup.
        }
        val mmkv = try {
            MMKV.defaultMMKV()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            migratePlaintextSecrets(mmkv)
        } catch (_: Exception) {
            try {
                scrubPlaintextSecretsFailClosed(mmkv)
            } catch (_: Exception) {
                // Secure storage remains fail-closed; never swallow VM-fatal Errors.
            }
        }
        if (secretStore?.isAvailable() != true) {
            try {
                scrubPlaintextSecretsFailClosed(mmkv)
            } catch (_: Exception) {
                // Secure storage remains fail-closed; never swallow VM-fatal Errors.
            }
        }
    }

    private data class StoredModelProviderProfile(
        @field:SerializedName(value = "id", alternate = ["a"])
        val id: String? = null,
        @field:SerializedName(value = "name", alternate = ["b"])
        val name: String? = null,
        @field:SerializedName(value = "baseUrl", alternate = ["c"])
        val baseUrl: String? = null,
        @field:SerializedName(value = "apiKey", alternate = ["d"])
        val apiKey: String? = null,
        @field:SerializedName(value = "customHeaders", alternate = ["e"])
        val customHeaders: Map<String, String>? = null,
        @field:SerializedName(value = "sourceType", alternate = ["f"])
        val sourceType: String? = null,
        @field:SerializedName(value = "readOnly", alternate = ["g"])
        val readOnly: Boolean? = null,
        @field:SerializedName(value = "ready", alternate = ["h"])
        val ready: Boolean? = null,
        @field:SerializedName("statusText")
        val statusText: String? = null,
        @field:SerializedName(value = "protocolType", alternate = ["i"])
        val protocolType: String? = null,
        @field:SerializedName(value = "wireApi", alternate = ["j"])
        val wireApi: String? = null,
        @field:SerializedName("revision")
        val revision: Long? = null,
    )

    fun listProfiles(): List<ModelProviderProfile> {
        ModelProviderMigration.ensureMigrated()
        val mmkv = MMKV.defaultMMKV()
        val deletedOfficialProfileIds = readDeletedOfficialProfileIds(mmkv)
        val storedProfilesRaw = mmkv.decodeString(KEY_PROVIDER_PROFILES)
        val decodedProfiles = hydrateProfileSecrets(decodeProfilesJson(storedProfilesRaw))
        val storedProfiles = readActiveProfiles(
            mmkv = mmkv,
            deletedOfficialProfileIds = deletedOfficialProfileIds,
            profiles = decodedProfiles
        )
        val current = ensureBuiltinOfficialProfilesSeeded(
            mmkv,
            storedProfiles,
            deletedOfficialProfileIds
        )
        if (current.isNotEmpty()) {
            ensureEditingProfile(mmkv, current)
            return appendOfficialPlatformProfile(current)
        }
        val created = defaultProfiles(deletedOfficialProfileIds)
        persistProfilesFromReadPath(mmkv, created)
        mmkv.encode(KEY_EDITING_PROFILE_ID, created.first().id)
        return appendOfficialPlatformProfile(created)
    }

    /** Read the Provider editor's existing catalog; never refresh on session startup. */
    fun cachedModels(context: Context, profile: ModelProviderProfile): List<ProviderModelOption> = runCatching {
        val raw = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getString("flutter.cached_provider_models_with_base_v2", null)
            ?: return@runCatching emptyList()
        val bucket = JsonParser.parseString(raw).asJsonObject[profile.id]?.asJsonObject
            ?: return@runCatching emptyList()
        val base = bucket["apiBase"]?.asString.orEmpty()
        val revision = bucket["profileRevision"]?.asLong ?: 0L
        if (base != normalizeBaseUrl(profile.baseUrl).orEmpty() || revision != profile.revision) {
            return@runCatching emptyList()
        }
        bucket["models"]?.asJsonArray?.map { Gson().fromJson(it, ProviderModelOption::class.java) }
            ?.filter { !it.id.isNullOrBlank() }.orEmpty()
    }.getOrDefault(emptyList())

    fun getEditingProfileId(): String {
        val profiles = listProfiles()
        val mmkv = MMKV.defaultMMKV()
        return ensureEditingProfile(mmkv, profiles)
    }

    fun getEditingProfile(): ModelProviderProfile {
        val profiles = listProfiles()
        val editingId = getEditingProfileId()
        return profiles.firstOrNull { it.id == editingId } ?: profiles.first()
    }

    fun getProfile(profileId: String?): ModelProviderProfile? {
        if (profileId.isNullOrBlank()) return null
        return listProfiles().firstOrNull { it.id == profileId.trim() }
    }

    fun setEditingProfile(profileId: String): ModelProviderProfile {
        val normalizedId = profileId.trim()
        require(normalizedId.isNotEmpty()) { "profileId is empty" }
        val profiles = listProfiles()
        val target = profiles.firstOrNull { it.id == normalizedId }
            ?: throw IllegalArgumentException("profile not found: $normalizedId")
        val mmkv = MMKV.defaultMMKV()
        mmkv.encode(KEY_EDITING_PROFILE_ID, target.id)
        return target
    }

    fun replaceProfiles(
        profiles: List<ModelProviderProfile>,
        editingProfileId: String? = null
    ): List<ModelProviderProfile> {
        ModelProviderMigration.ensureMigrated()
        val mmkv = MMKV.defaultMMKV()
        val deletedOfficialProfileIds = readDeletedOfficialProfileIds(mmkv)
        readProfilesForUpdate(mmkv)

        val sanitized = buildList<ModelProviderProfile> {
            profiles
                .filterNot { OmniOfficialProvider.isOfficialProfile(it.id) }
                .filterNot { isDeletedOfficialProfile(it.id, deletedOfficialProfileIds) }
                .forEach { profile ->
                    val existing = toList()
                    val requestedId = profile.id.trim()
                    val normalizedId = when {
                        requestedId.isEmpty() -> generateProfileId(existing)
                        existing.any { it.id == requestedId } -> generateProfileId(existing)
                        else -> requestedId
                    }
                    add(
                        ModelProviderProfile(
                            id = normalizedId,
                            name = sanitizeProfileName(
                                raw = profile.name,
                                profiles = existing,
                                existingId = null
                            ),
                            baseUrl = normalizeBaseUrl(profile.baseUrl).orEmpty(),
                            apiKey = profile.apiKey.trim(),
                            customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
                                profile.customHeaders
                            ),
                            sourceType = normalizeSourceType(
                                sourceType = profile.sourceType,
                                profileId = normalizedId,
                                baseUrl = profile.baseUrl
                            ),
                            protocolType = normalizeProtocolType(profile.protocolType),
                            wireApi = normalizeWireApi(profile.wireApi)
                        )
                    )
                }
        }.ifEmpty { defaultProfiles(deletedOfficialProfileIds) }

        val resolvedEditingId = editingProfileId
            ?.trim()
            ?.takeIf { candidate -> sanitized.any { it.id == candidate } }
            ?: sanitized.first().id

        writeProfiles(mmkv, sanitized)
        mmkv.encode(KEY_EDITING_PROFILE_ID, resolvedEditingId)
        getProfile(resolvedEditingId)?.let { syncLegacyFlatConfig(mmkv, it) }

        return sanitized
    }

    fun saveProfile(
        id: String? = null,
        name: String,
        baseUrl: String,
        apiKey: String,
        customHeaders: Map<String, String> = emptyMap(),
        sourceType: String? = null,
        protocolType: String = "openai_compatible",
        wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS,
    ): ModelProviderProfile {
        ModelProviderMigration.ensureMigrated()
        require(!OmniOfficialProvider.isOfficialProfile(id)) {
            "official platform provider is read only"
        }
        val normalizedProtocolType = normalizeProtocolType(protocolType)
        val normalizedWireApi = resolveWireApiForSave(
            baseUrl = baseUrl,
            protocolType = normalizedProtocolType,
            wireApi = wireApi
        )
        val normalizedCustomHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(customHeaders)
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl).orEmpty()
        if (normalizedBaseUrl.isNotEmpty()) {
            ContentEndpointSecurity.requireSafe(
                rawUrl = stripDirectRequestUrlMarker(normalizedBaseUrl),
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
                allowInsecureTransport = true,
            )
        }
        val mmkv = MMKV.defaultMMKV()

        val deletedOfficialProfileIds = readDeletedOfficialProfileIds(mmkv)
        val current = readActiveProfiles(
            mmkv = mmkv,
            deletedOfficialProfileIds = deletedOfficialProfileIds,
            profiles = readProfilesForUpdate(mmkv)
        ).toMutableList().ifEmpty {
            defaultProfiles(deletedOfficialProfileIds).toMutableList()
        }
        val normalizedId = id?.trim()?.takeIf { it.isNotEmpty() } ?: generateProfileId(current)
        val currentIndex = current.indexOfFirst { it.id == normalizedId }
        val sanitizedName = sanitizeProfileName(
            raw = name,
            profiles = current,
            existingId = if (currentIndex >= 0) normalizedId else null
        )
        val existingProfile = current.getOrNull(currentIndex)
        val nextRevision = (existingProfile?.revision ?: 0L) + 1L
        val nextProfile = ModelProviderProfile(
            id = normalizedId,
            name = sanitizedName,
            baseUrl = normalizedBaseUrl,
            apiKey = apiKey.trim(),
            customHeaders = normalizedCustomHeaders,
            sourceType = resolveSourceTypeForSave(
                requestedSourceType = sourceType,
                profileId = normalizedId,
                baseUrl = baseUrl,
                existingSourceType = current.getOrNull(currentIndex)?.sourceType
            ),
            protocolType = normalizedProtocolType,
            wireApi = normalizedWireApi,
            revision = nextRevision,
        )

        if (currentIndex >= 0) {
            current[currentIndex] = nextProfile
        } else {
            current.add(nextProfile)
        }

        writeProfiles(mmkv, current)
        clearDeletedOfficialProfileIfNeeded(mmkv, normalizedId)
        mmkv.encode(KEY_EDITING_PROFILE_ID, nextProfile.id)
        syncLegacyFlatConfig(mmkv, nextProfile)
        return nextProfile
    }

    fun deleteProfile(profileId: String): List<ModelProviderProfile> {
        ModelProviderMigration.ensureMigrated()
        val mmkv = MMKV.defaultMMKV()
        val normalizedId = profileId.trim()
        require(!OmniOfficialProvider.isOfficialProfile(normalizedId)) {
            "official platform provider cannot be deleted"
        }
        val deletedOfficialProfileIds = readDeletedOfficialProfileIds(mmkv)
        val current = readActiveProfiles(
            mmkv = mmkv,
            deletedOfficialProfileIds = deletedOfficialProfileIds,
            profiles = readProfilesForUpdate(mmkv)
        ).toMutableList().ifEmpty {
            defaultProfiles(deletedOfficialProfileIds).toMutableList()
        }
        require(current.size > 1) { "at least one provider profile must remain" }
        val removed = current.removeAll { it.id == normalizedId }
        require(removed) { "profile not found: $normalizedId" }

        markDeletedOfficialProfileIfNeeded(mmkv, normalizedId)
        writeProfiles(mmkv, current)
        val editingId = mmkv.decodeString(KEY_EDITING_PROFILE_ID)?.trim().orEmpty()
        if (editingId == normalizedId || editingId.isEmpty()) {
            mmkv.encode(KEY_EDITING_PROFILE_ID, current.first().id)
            syncLegacyFlatConfig(mmkv, current.first())
        }
        return current
    }

    fun getConfig(): ModelProviderConfig {
        val profile = getEditingProfile()
        return ModelProviderConfig(
            id = profile.id,
            name = profile.name,
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(profile.customHeaders),
            source = "profile",
            providerType = profile.sourceType,
            readOnly = profile.readOnly,
            ready = profile.ready,
            statusText = profile.statusText,
            wireApi = profile.wireApi,
        )
    }

    fun saveConfig(
        baseUrl: String,
        apiKey: String,
        customHeaders: Map<String, String> = emptyMap(),
    ) {
        val current = getEditingProfile()
        require(!current.readOnly) { "builtin provider is read only" }
        saveProfile(
            id = current.id,
            name = current.name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            customHeaders = customHeaders,
            sourceType = current.sourceType,
            protocolType = current.protocolType,
            wireApi = current.wireApi,
        )
    }

    /** Compare provider destinations without exposing their values to callers. */
    fun sameCanonicalEndpoint(left: String, right: String): Boolean {
        return try {
            canonicalEndpoint(left) == canonicalEndpoint(right)
        } catch (_: Exception) {
            false
        }
    }

    fun clearConfig() {
        val current = getEditingProfile()
        require(!current.readOnly) { "builtin provider is read only" }
        saveProfile(
            id = current.id,
            name = current.name,
            baseUrl = "",
            apiKey = "",
            customHeaders = emptyMap(),
            sourceType = current.sourceType,
            protocolType = current.protocolType,
            wireApi = current.wireApi
        )
    }

    fun isValidBaseUrl(value: String): Boolean = normalizeBaseUrl(value) != null

    fun hasDirectRequestUrlMarker(value: String): Boolean {
        return value.trim().endsWith(DIRECT_REQUEST_URL_MARKER)
    }

    fun stripDirectRequestUrlMarker(value: String): String {
        var result = value.trim()
        if (result.endsWith(DIRECT_REQUEST_URL_MARKER)) {
            result = result.dropLast(DIRECT_REQUEST_URL_MARKER.length)
        }
        return result.replace(Regex("/+$"), "")
    }

    fun hasVersionedBasePath(value: String): Boolean {
        val normalized = stripDirectRequestUrlMarker(value).lowercase()
        return canonicalVersionBaseSuffixes.any { normalized.endsWith(it) }
    }

    fun normalizeBaseUrl(value: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return null
        }
        val hasDirectRequestUrl = hasDirectRequestUrlMarker(normalized)
        val candidate = if (hasDirectRequestUrl) {
            normalized.dropLast(DIRECT_REQUEST_URL_MARKER.length).trim()
        } else {
            normalized
        }
        if (candidate.isEmpty()) {
            return null
        }
        val uri = try {
            java.net.URI(candidate)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return null
        }

        var result = candidate.replace(Regex("/+$"), "")
        if (!hasDirectRequestUrl) {
            for (suffix in canonicalEndpointSuffixes) {
                if (result.endsWith(suffix, ignoreCase = true)) {
                    result = result.dropLast(suffix.length)
                    break
                }
            }
        }
        result = result.replace(Regex("/+$"), "")
        if (result.isEmpty()) {
            return null
        }
        return if (hasDirectRequestUrl) {
            result + DIRECT_REQUEST_URL_MARKER
        } else {
            result
        }
    }

    internal fun readConfig(mmkv: MMKV): ModelProviderConfig {
        val baseUrl = mmkv.decodeString(KEY_PROVIDER_BASE_URL)
            ?.trim()
            ?.let(::normalizeBaseUrl)
            .orEmpty()
        val apiKey = readLegacyApiKey(mmkv, KEY_PROVIDER_API_KEY)
        return ModelProviderConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            customHeaders = emptyMap(),
            source = "legacy"
        )
    }

    internal fun readConfigForScope(mmkv: MMKV, userId: String?): ModelProviderConfig {
        val baseUrl = readScopedString(mmkv, KEY_PROVIDER_BASE_URL, userId)
            ?.let(::normalizeBaseUrl)
            .orEmpty()
        val apiKey = readLegacyApiKey(mmkv, scopedKey(KEY_PROVIDER_API_KEY, userId))
        return ModelProviderConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            customHeaders = emptyMap(),
            source = "legacy_scope"
        )
    }

    internal fun scopedKey(key: String, userId: String?): String {
        return if (userId.isNullOrBlank()) key else "user_${userId}_$key"
    }

    internal fun readScopedString(mmkv: MMKV, key: String, userId: String?): String? {
        return mmkv.decodeString(scopedKey(key, userId))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun readLegacyApiKey(mmkv: MMKV, storageKey: String): String {
        return mmkv.decodeString(storageKey)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: secretStore?.readLegacy(storageKey).orEmpty()
    }

    private fun ensureEditingProfile(
        mmkv: MMKV,
        profiles: List<ModelProviderProfile>
    ): String {
        val currentId = mmkv.decodeString(KEY_EDITING_PROFILE_ID)?.trim().orEmpty()
        if (profiles.any { it.id == currentId }) {
            return currentId
        }
        val fallback = profiles.first().id
        mmkv.encode(KEY_EDITING_PROFILE_ID, fallback)
        return fallback
    }

    private fun sanitizeProfileName(
        raw: String,
        profiles: List<ModelProviderProfile>,
        existingId: String?
    ): String {
        val normalized = raw.trim()
        if (normalized.isNotEmpty()) {
            return normalized
        }
        val existingIndex = if (existingId == null) -1 else profiles.indexOfFirst { it.id == existingId }
        if (existingIndex >= 0) {
            return profiles[existingIndex].name
        }
        var nextIndex = 1
        val existingNames = profiles.map { it.name }.toSet()
        while (true) {
            val candidate = "Provider $nextIndex"
            if (!existingNames.contains(candidate)) {
                return candidate
            }
            nextIndex += 1
        }
    }

    private fun defaultProfiles(
        deletedOfficialProfileIds: Set<String> = emptySet()
    ): List<ModelProviderProfile> {
        return buildList {
            add(
                ModelProviderProfile(
                    id = DEFAULT_PROFILE_ID,
                    name = DEFAULT_PROFILE_NAME
                )
            )
            addAll(
                OfficialProviderRegistry.officialProfiles()
                    .filterNot { it.id in deletedOfficialProfileIds }
            )
        }
    }

    private fun canonicalEndpoint(rawUrl: String): String {
        val safe = ContentEndpointSecurity.requireSafe(
            rawUrl = stripDirectRequestUrlMarker(rawUrl),
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = true,
        )
        val uri = URI(safe).normalize()
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val port = if (uri.port >= 0) uri.port else if (scheme == "https" || scheme == "wss") 443 else 80
        val path = uri.rawPath?.takeIf(String::isNotEmpty) ?: "/"
        return buildString {
            append(scheme).append("://")
            if (host.contains(':')) append('[').append(host).append(']') else append(host)
            append(':').append(port).append(path)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    private fun appendOfficialPlatformProfile(
        profiles: List<ModelProviderProfile>
    ): List<ModelProviderProfile> {
        val official = PlatformAiProvisioner.officialProfileOrNull() ?: return profiles
        return profiles.filterNot { it.id == official.id } + official
    }

    private fun normalizeSourceType(
        sourceType: String?,
        profileId: String?,
        baseUrl: String?
    ): String {
        return OfficialProviderRegistry.normalizeSourceType(
            sourceType = sourceType,
            profileId = profileId,
            baseUrl = baseUrl
        )
    }

    private fun normalizeProtocolType(value: String?): String {
        return DeepSeekProvider.normalizeProtocolType(value)
    }

    private fun normalizeWireApi(value: String?): String {
        return OpenAiWireApi.normalize(value)
    }

    private fun resolveWireApiForSave(
        baseUrl: String,
        protocolType: String,
        wireApi: String?
    ): String {
        val normalizedWireApi = wireApi?.trim()?.lowercase().orEmpty()
        if (normalizedWireApi == OpenAiWireApi.RESPONSES ||
            normalizedWireApi == OpenAiWireApi.CHAT_COMPLETIONS
        ) {
            return normalizedWireApi
        }
        if (protocolType != "openai_compatible") {
            return OpenAiWireApi.CHAT_COMPLETIONS
        }
        val rawBaseUrl = stripDirectRequestUrlMarker(baseUrl).lowercase()
        return if (
            rawBaseUrl.endsWith("/v1/responses") ||
            rawBaseUrl.endsWith("/responses")
        ) {
            OpenAiWireApi.RESPONSES
        } else {
            OpenAiWireApi.CHAT_COMPLETIONS
        }
    }

    private fun resolveSourceTypeForSave(
        requestedSourceType: String?,
        profileId: String?,
        baseUrl: String,
        existingSourceType: String?
    ): String {
        val normalizedRequested = requestedSourceType?.trim()?.lowercase().orEmpty()
        if (normalizedRequested == "custom") {
            return "custom"
        }
        OfficialProviderRegistry.findByKey(normalizedRequested)?.let { return it.key }
        OfficialProviderRegistry.findByKey(existingSourceType)?.let { return it.key }
        OfficialProviderRegistry.findByProfileId(profileId)?.let { return it.key }
        OfficialProviderRegistry.findByBaseUrl(baseUrl)?.let { return it.key }
        return "custom"
    }

    private fun ensureBuiltinOfficialProfilesSeeded(
        mmkv: MMKV,
        profiles: List<ModelProviderProfile>,
        deletedOfficialProfileIds: Set<String>
    ): List<ModelProviderProfile> {
        if (profiles.isEmpty()) {
            return profiles
        }
        val officialProfiles = OfficialProviderRegistry.officialProfiles()
            .filterNot { it.id in deletedOfficialProfileIds }
        val missingProfiles = officialProfiles.filter { official ->
            profiles.none { it.id == official.id }
        }
        if (missingProfiles.isEmpty()) {
            mmkv.encode(KEY_BUILTIN_OFFICIAL_PROFILES_SEEDED, true)
            return profiles
        }
        if (mmkv.decodeBool(KEY_BUILTIN_OFFICIAL_PROFILES_SEEDED, false)) {
            val currentIds = profiles.map { it.id }.toSet()
            if (officialProfiles.all { it.id in currentIds }) {
                return profiles
            }
        }
        val next = buildList {
            profiles.forEach(::add)
            missingProfiles.forEach(::add)
        }
        persistProfilesFromReadPath(mmkv, next)
        mmkv.encode(KEY_BUILTIN_OFFICIAL_PROFILES_SEEDED, true)
        return next
    }

    internal fun filterDeletedOfficialProfiles(
        profiles: List<ModelProviderProfile>,
        deletedOfficialProfileIds: Set<String>
    ): List<ModelProviderProfile> {
        if (deletedOfficialProfileIds.isEmpty()) {
            return profiles
        }
        return profiles.filterNot { profile ->
            isDeletedOfficialProfile(profile.id, deletedOfficialProfileIds)
        }
    }

    private fun isDeletedOfficialProfile(
        profileId: String?,
        deletedOfficialProfileIds: Set<String>
    ): Boolean {
        val normalizedId = profileId?.trim().orEmpty()
        return normalizedId in deletedOfficialProfileIds &&
            OfficialProviderRegistry.findByProfileId(normalizedId) != null
    }

    private fun generateProfileId(profiles: List<ModelProviderProfile>): String {
        var nextIndex = profiles.size + 1
        while (true) {
            val candidate = "profile-$nextIndex"
            if (profiles.none { it.id == candidate }) {
                return candidate
            }
            nextIndex += 1
        }
    }

    internal fun decodeProfilesJson(raw: String?): List<ModelProviderProfile> {
        val normalizedRaw = raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return try {
            decodeProfilesJsonStrict(normalizedRaw)
        } catch (e: Exception) {
            OmniLog.w(TAG, "read provider profiles failed type=${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun decodeProfilesJsonStrict(normalizedRaw: String): List<ModelProviderProfile> {
        val root = JsonParser.parseString(normalizedRaw)
        require(root.isJsonArray) { "provider profiles must be a JSON array" }
        val type = object : TypeToken<List<StoredModelProviderProfile>>() {}.type
        val parsed: List<StoredModelProviderProfile> = gson.fromJson(root, type) ?: emptyList()
        val seen = LinkedHashSet<String>()
        return parsed.mapNotNull { profile ->
                val normalizedId = profile.id?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                if (!seen.add(normalizedId)) {
                    return@mapNotNull null
                }
                val normalizedBaseUrl = normalizeBaseUrl(profile.baseUrl.orEmpty()).orEmpty()
                // Profiles written before revision metadata existed start at
                // revision 1 so request snapshot protection remains effective.
                val revision = profile.revision?.coerceAtLeast(0L)
                    ?: if (normalizedBaseUrl.isNotEmpty()) 1L else 0L
                ModelProviderProfile(
                    id = normalizedId,
                    name = profile.name?.trim().orEmpty().ifEmpty { DEFAULT_PROFILE_NAME },
                    baseUrl = normalizedBaseUrl,
                    apiKey = profile.apiKey?.trim().orEmpty(),
                    customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
                        profile.customHeaders
                    ),
                    sourceType = normalizeSourceType(
                        sourceType = profile.sourceType,
                        profileId = normalizedId,
                        baseUrl = profile.baseUrl
                    ),
                    readOnly = profile.readOnly ?: false,
                    ready = profile.ready ?: true,
                    statusText = profile.statusText,
                    protocolType = normalizeProtocolType(profile.protocolType),
                    wireApi = normalizeWireApi(profile.wireApi),
                    revision = revision,
                )
            }
    }

    internal fun encodeProfilesJson(profiles: List<ModelProviderProfile>): String {
        val normalized = profiles.mapIndexedNotNull { index, profile ->
            val id = profile.id.trim().takeIf { it.isNotEmpty() }
                ?: return@mapIndexedNotNull null
            StoredModelProviderProfile(
                id = id,
                name = profile.name.trim().ifEmpty { "Provider ${index + 1}" },
                baseUrl = normalizeBaseUrl(profile.baseUrl).orEmpty(),
                apiKey = profile.apiKey.trim(),
                customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
                    profile.customHeaders
                ),
                sourceType = normalizeSourceType(
                    sourceType = profile.sourceType,
                    profileId = id,
                    baseUrl = profile.baseUrl
                ),
                readOnly = profile.readOnly,
                ready = profile.ready,
                statusText = profile.statusText,
                protocolType = normalizeProtocolType(profile.protocolType),
                wireApi = normalizeWireApi(profile.wireApi),
                revision = profile.revision,
            )
        }
        return gson.toJson(normalized)
    }

    /** Encodes only non-secret provider metadata for storage in MMKV. */
    internal fun encodeProfilesMetadataJson(profiles: List<ModelProviderProfile>): String {
        return encodeProfilesJson(
            profiles.map { profile ->
                profile.copy(apiKey = "", customHeaders = emptyMap())
            }
        )
    }

    internal fun mergeProfileSecrets(
        profile: ModelProviderProfile,
        secrets: ModelProviderSecrets?
    ): ModelProviderProfile {
        if (secrets == null) {
            return enforceCredentialTransport(profile)
        }
        val hydrated = profile.copy(
            apiKey = secrets.apiKey,
            customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
                secrets.customHeaders
            )
        )
        return enforceCredentialTransport(hydrated)
    }

    private fun hydrateProfileSecrets(
        profiles: List<ModelProviderProfile>
    ): List<ModelProviderProfile> {
        val store = requireSecretStore()
        return profiles.map { profile ->
            mergeProfileSecrets(profile, store.readProfile(profile.id))
        }
    }

    private fun requireSecretStore(): ModelProviderSecretStore {
        return checkNotNull(secretStore) {
            "ModelProviderConfigStore.initialize(context) must run before provider access"
        }
    }

    private fun migratePlaintextSecrets(mmkv: MMKV) {
        val store = requireSecretStore()
        val rawProfiles = mmkv.decodeString(KEY_PROVIDER_PROFILES)
        val decodedProfiles = rawProfiles
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::decodeProfilesJsonStrict)
            ?: emptyList()
        val safeProfiles = decodedProfiles.map { profile ->
            val legacySecrets = ModelProviderSecrets(
                apiKey = profile.apiKey,
                customHeaders = profile.customHeaders
            )
            val existingSecrets = store.readProfile(profile.id)
            val mergedSecrets = ModelProviderSecrets(
                apiKey = existingSecrets?.apiKey
                    ?.takeIf { it.isNotBlank() }
                    ?: legacySecrets.apiKey,
                customHeaders = existingSecrets?.customHeaders
                    ?.takeIf { it.isNotEmpty() }
                    ?: legacySecrets.customHeaders
            )
            val safeProfile = enforceCredentialTransport(
                profile.copy(
                    apiKey = mergedSecrets.apiKey,
                    customHeaders = mergedSecrets.customHeaders,
                )
            )
            store.writeProfile(
                safeProfile.id,
                ModelProviderSecrets(
                    apiKey = safeProfile.apiKey,
                    customHeaders = safeProfile.customHeaders,
                ),
            )
            safeProfile
        }
        if (rawProfilesHasSecretFields(rawProfiles) || safeProfiles != decodedProfiles) {
            check(mmkv.encode(KEY_PROVIDER_PROFILES, encodeProfilesMetadataJson(safeProfiles))) {
                "failed to remove plaintext model-provider credentials"
            }
        }

        val legacyApiKeyStorageKeys = mmkv.allKeys()
            ?.filter { key ->
                key == KEY_PROVIDER_API_KEY || key.endsWith("_$KEY_PROVIDER_API_KEY")
            }
            .orEmpty()
        legacyApiKeyStorageKeys.forEach { storageKey ->
            val plaintext = mmkv.decodeString(storageKey)?.trim().orEmpty()
            if (plaintext.isNotEmpty()) {
                store.writeLegacy(storageKey, plaintext)
                check(store.readLegacy(storageKey) == plaintext) {
                    "failed to verify encrypted legacy model-provider credential"
                }
            }
            mmkv.removeValueForKey(storageKey)
        }
    }

    /** Erases only known plaintext provider credential fields after secure storage fails. */
    private fun scrubPlaintextSecretsFailClosed(mmkv: MMKV) {
        val rawProfiles = try {
            mmkv.decodeString(KEY_PROVIDER_PROFILES)
        } catch (_: Exception) {
            mmkv.removeValueForKey(KEY_PROVIDER_PROFILES)
            null
        }
        if (!rawProfiles.isNullOrBlank()) {
            val metadataOnly = sanitizeProfilesMetadataJson(rawProfiles)
            if (metadataOnly == null || !mmkv.encode(KEY_PROVIDER_PROFILES, metadataOnly)) {
                mmkv.removeValueForKey(KEY_PROVIDER_PROFILES)
            }
        }
        mmkv.allKeys()
            ?.filter { key ->
                key == KEY_PROVIDER_API_KEY || key.endsWith("_$KEY_PROVIDER_API_KEY")
            }
            .orEmpty()
            .forEach(mmkv::removeValueForKey)
    }

    /** Invalid or uncertain input is never safe to retain in plaintext MMKV. */
    internal fun sanitizeProfilesMetadataJson(raw: String?): String? {
        val normalized = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return try {
            encodeProfilesMetadataJson(decodeProfilesJsonStrict(normalized))
        } catch (_: Exception) {
            null
        }
    }

    private fun rawProfilesHasSecretFields(raw: String?): Boolean {
        val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return try {
            val root = JsonParser.parseString(normalized)
            root.isJsonArray && root.asJsonArray.any { element ->
                element.isJsonObject && listOf("apiKey", "d", "customHeaders", "e").any {
                    fieldName -> element.asJsonObject.has(fieldName)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readProfilesForUpdate(mmkv: MMKV): List<ModelProviderProfile> {
        return hydrateProfileSecrets(
            decodeProfilesJson(mmkv.decodeString(KEY_PROVIDER_PROFILES))
        )
    }

    private fun readActiveProfiles(
        mmkv: MMKV,
        deletedOfficialProfileIds: Set<String>,
        profiles: List<ModelProviderProfile>
    ): List<ModelProviderProfile> {
        val activeProfiles = filterDeletedOfficialProfiles(profiles, deletedOfficialProfileIds)
        if (activeProfiles.size != profiles.size) {
            persistProfilesFromReadPath(mmkv, activeProfiles)
        }
        return activeProfiles
    }

    private fun writeProfiles(mmkv: MMKV, profiles: List<ModelProviderProfile>) {
        val store = requireSecretStore()
        check(store.isAvailable()) {
            "Secure model-provider credential storage is unavailable"
        }
        val safeProfiles = profiles.map(::enforceCredentialTransport)
        val previousMetadata = mmkv.decodeString(KEY_PROVIDER_PROFILES)
        val previousIds = decodeProfilesJson(previousMetadata).mapTo(LinkedHashSet()) { it.id }
        val touchedIds = previousIds + safeProfiles.map { it.id }
        val previousSecrets = touchedIds.associateWith(store::readProfile)
        try {
            safeProfiles.forEach { safeProfile ->
                store.writeProfile(
                    safeProfile.id,
                    ModelProviderSecrets(
                        apiKey = safeProfile.apiKey,
                        customHeaders = safeProfile.customHeaders
                    )
                )
            }
            val nextMetadata = encodeProfilesMetadataJson(safeProfiles)
            check(mmkv.encode(KEY_PROVIDER_PROFILES, nextMetadata) &&
                mmkv.decodeString(KEY_PROVIDER_PROFILES) == nextMetadata) {
                "failed to store model-provider metadata"
            }
            store.deleteProfilesExcept(safeProfiles.mapTo(HashSet()) { it.id })
        } catch (failure: Exception) {
            val rolledBack = try {
                touchedIds.forEach { id ->
                    val previous = previousSecrets[id]
                    if (previous == null) {
                        store.deleteProfile(id)
                    } else {
                        store.writeProfile(id, previous)
                    }
                }
                touchedIds.all { id -> store.readProfile(id) == previousSecrets[id] }
            } catch (_: Exception) {
                false
            }
            if (rolledBack && previousMetadata != null) {
                if (!mmkv.encode(KEY_PROVIDER_PROFILES, previousMetadata) ||
                    mmkv.decodeString(KEY_PROVIDER_PROFILES) != previousMetadata
                ) {
                    mmkv.removeValueForKey(KEY_PROVIDER_PROFILES)
                }
            } else {
                // Removing metadata is safer than binding new credentials to
                // an old provider endpoint after a partial commit.
                mmkv.removeValueForKey(KEY_PROVIDER_PROFILES)
            }
            throw failure
        }
    }

    /** Read-time seeding may persist non-secret metadata even when BYOK secrets are unavailable. */
    private fun persistProfilesFromReadPath(
        mmkv: MMKV,
        profiles: List<ModelProviderProfile>,
    ) {
        if (requireSecretStore().isAvailable()) {
            writeProfiles(mmkv, profiles)
            return
        }
        check(mmkv.encode(KEY_PROVIDER_PROFILES, encodeProfilesMetadataJson(profiles))) {
            "failed to store model-provider metadata"
        }
    }

    private fun enforceCredentialTransport(profile: ModelProviderProfile): ModelProviderProfile {
        val endpoint = stripDirectRequestUrlMarker(profile.baseUrl)
        val safeMetadata = try {
            ContentEndpointSecurity.requireSafe(
                rawUrl = endpoint,
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
                allowInsecureTransport = true,
            )
            true
        } catch (_: Exception) {
            false
        }
        if (!safeMetadata) {
            return profile.copy(baseUrl = "", apiKey = "", customHeaders = emptyMap())
        }
        return profile
    }

    internal fun decodeDeletedOfficialProfileIds(raw: String?): Set<String> {
        val normalizedRaw = raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptySet()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed: List<String> = gson.fromJson(normalizedRaw, type) ?: emptyList()
            parsed.mapNotNullTo(LinkedHashSet()) { value ->
                value.trim().takeIf { id ->
                    id.isNotEmpty() && OfficialProviderRegistry.findByProfileId(id) != null
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    internal fun encodeDeletedOfficialProfileIds(ids: Set<String>): String {
        val normalized = ids
            .map(String::trim)
            .filter { id -> id.isNotEmpty() && OfficialProviderRegistry.findByProfileId(id) != null }
            .distinct()
            .sorted()
        return gson.toJson(normalized)
    }

    private fun readDeletedOfficialProfileIds(mmkv: MMKV): Set<String> {
        return decodeDeletedOfficialProfileIds(mmkv.decodeString(KEY_DELETED_OFFICIAL_PROFILE_IDS))
    }

    private fun writeDeletedOfficialProfileIds(mmkv: MMKV, ids: Set<String>) {
        val encoded = encodeDeletedOfficialProfileIds(ids)
        check(
            mmkv.encode(KEY_DELETED_OFFICIAL_PROFILE_IDS, encoded) &&
                mmkv.decodeString(KEY_DELETED_OFFICIAL_PROFILE_IDS) == encoded
        ) { "failed to store deleted official provider ids" }
    }

    private fun markDeletedOfficialProfileIfNeeded(mmkv: MMKV, profileId: String) {
        if (OfficialProviderRegistry.findByProfileId(profileId) == null) {
            return
        }
        writeDeletedOfficialProfileIds(mmkv, readDeletedOfficialProfileIds(mmkv) + profileId)
    }

    private fun clearDeletedOfficialProfileIfNeeded(mmkv: MMKV, profileId: String) {
        if (OfficialProviderRegistry.findByProfileId(profileId) == null) {
            return
        }
        val current = readDeletedOfficialProfileIds(mmkv)
        if (profileId !in current) {
            return
        }
        writeDeletedOfficialProfileIds(mmkv, current - profileId)
    }

    private fun syncLegacyFlatConfig(mmkv: MMKV, profile: ModelProviderProfile) {
        val safeProfile = enforceCredentialTransport(profile)
        val mirrored = mmkv.encode(KEY_PROVIDER_BASE_URL, safeProfile.baseUrl) &&
            mmkv.decodeString(KEY_PROVIDER_BASE_URL) == safeProfile.baseUrl
        if (!mirrored) {
            mmkv.removeValueForKey(KEY_PROVIDER_BASE_URL)
        }
        // The legacy flat keys are migration inputs only. Runtime provider reads use
        // KEY_PROVIDER_PROFILES plus the profile-scoped encrypted secret store, so do
        // not perform a second secret write from this compatibility mirror.
        mmkv.removeValueForKey(KEY_PROVIDER_API_KEY)
    }

    internal object ModelProviderMigration {
        private const val PRIMARY_SCENE = "scene.dispatch.model"

        fun ensureMigrated() {
            val mmkv = MMKV.defaultMMKV()
            if (mmkv.decodeBool(MIGRATION_DONE_KEY, false)) {
                return
            }

            try {
                val storedProfilesRaw = mmkv.decodeString(KEY_PROVIDER_PROFILES)
                val existingProfiles = hydrateProfileSecrets(decodeProfilesJson(storedProfilesRaw))
                if (existingProfiles.isNotEmpty()) {
                    ensureEditingProfile(mmkv, existingProfiles)
                    syncLegacyFlatConfig(mmkv, existingProfiles.first())
                    return
                }

                val legacyUserId = OssIdentity.currentUserIdOrNull()
                val providerConfig = resolveEffectiveLegacyConfig(mmkv, legacyUserId)
                val initialProfiles = if (
                    providerConfig.baseUrl.isNotBlank() || providerConfig.apiKey.isNotBlank()
                ) {
                    listOf(
                        ModelProviderProfile(
                            id = LEGACY_DEFAULT_PROFILE_ID,
                            name = DEFAULT_PROFILE_NAME,
                            baseUrl = providerConfig.baseUrl,
                            apiKey = providerConfig.apiKey,
                            sourceType = normalizeSourceType(
                                sourceType = null,
                                profileId = LEGACY_DEFAULT_PROFILE_ID,
                                baseUrl = providerConfig.baseUrl
                            )
                        )
                    )
                } else {
                    defaultProfiles()
                }
                val initialProfile = initialProfiles.first()
                writeProfiles(mmkv, initialProfiles)
                mmkv.encode(KEY_EDITING_PROFILE_ID, initialProfile.id)
                syncLegacyFlatConfig(mmkv, initialProfile)

                val mergedOverrides = SceneModelOverrideStore.readLegacyOverrideMapForScope(mmkv, null)
                    .toMutableMap()
                if (!legacyUserId.isNullOrBlank()) {
                    mergedOverrides.putAll(
                        SceneModelOverrideStore.readLegacyOverrideMapForScope(mmkv, legacyUserId)
                    )
                }

                if (
                    (providerConfig.baseUrl.isNotBlank() || providerConfig.apiKey.isNotBlank()) &&
                    !mergedOverrides.containsKey(PRIMARY_SCENE)
                ) {
                    ModelSceneRegistry.getRuntimeProfile(PRIMARY_SCENE)?.model
                        ?.takeIf { SceneModelOverrideStore.isValidModelName(it) }
                        ?.let { mergedOverrides.putIfAbsent(PRIMARY_SCENE, it) }
                }

                if (mergedOverrides.isNotEmpty()) {
                    SceneModelOverrideStore.writeOverrideMap(mmkv, mergedOverrides)
                }
            } catch (e: Exception) {
                OmniLog.w(TAG, "migrate legacy provider config failed type=${e.javaClass.simpleName}")
            } finally {
                mmkv.encode(MIGRATION_DONE_KEY, true)
            }
        }

        private fun resolveEffectiveLegacyConfig(mmkv: MMKV, userId: String?): ModelProviderConfig {
            val candidates = buildList {
                if (!userId.isNullOrBlank()) {
                    add(readConfigForScope(mmkv, userId))
                }
                add(readConfigForScope(mmkv, null))
                add(readConfig(mmkv))
            }
            return candidates.firstOrNull { it.baseUrl.isNotBlank() || it.apiKey.isNotBlank() }
                ?: ModelProviderConfig()
        }
    }
}
