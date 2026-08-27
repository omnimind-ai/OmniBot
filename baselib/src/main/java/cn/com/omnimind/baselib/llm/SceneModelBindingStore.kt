package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV

object SceneModelBindingStore {
    private const val TAG = "SceneModelBindingStore"
    private const val KEY_SCENE_BINDING_MAP = "scene_model_binding_map_v1"
    private const val MIGRATION_DONE_KEY = "scene_model_binding_map_migrated_v1"
    private const val LEGACY_SCENE_OVERRIDE_MAP = "scene_model_override_map"

    private val gson = Gson()
    private val allowedScenes = setOf(
        "scene.dispatch.model",
        "scene.vlm.operation.primary",
        "scene.compactor.context.chat",
        "scene.memory.embedding",
        "scene.memory.rollup"
    )

    fun getBindingEntries(): List<SceneModelBindingEntry> {
        return getBindingMap()
            .values
            .sortedBy { it.sceneId }
    }

    fun getBindingMap(): Map<String, SceneModelBindingEntry> {
        ensureMigrated()
        val mmkv = MMKV.defaultMMKV() ?: return emptyMap()
        return readBindingMap(mmkv).toSortedMap()
    }

    fun getBinding(sceneId: String): SceneModelBindingEntry? {
        return getBindingMap()[sceneId.trim()]
    }

    fun saveBinding(sceneId: String, providerProfileId: String, modelId: String) {
        require(isValidSceneId(sceneId)) { "不支持的 sceneId: $sceneId" }
        require(providerProfileId.trim().isNotEmpty()) { "providerProfileId is empty" }
        require(isValidModelName(modelId)) { "非法模型名: $modelId" }
        val mmkv = MMKV.defaultMMKV() ?: return
        val current = readBindingMap(mmkv).toMutableMap()
        current[sceneId.trim()] = SceneModelBindingEntry(
            sceneId = sceneId.trim(),
            providerProfileId = providerProfileId.trim(),
            modelId = modelId.trim()
        )
        writeBindingMap(mmkv, current)
    }

    fun clearBinding(sceneId: String) {
        val mmkv = MMKV.defaultMMKV() ?: return
        val current = readBindingMap(mmkv).toMutableMap()
        current.remove(sceneId.trim())
        writeBindingMap(mmkv, current)
    }

    fun replaceBindings(entries: List<SceneModelBindingEntry>) {
        ensureMigrated()
        val mmkv = MMKV.defaultMMKV() ?: return
        val map = linkedMapOf<String, SceneModelBindingEntry>()
        entries.forEach { entry ->
            val normalizedSceneId = entry.sceneId.trim()
            val normalizedProfileId = entry.providerProfileId.trim()
            val normalizedModelId = entry.modelId.trim()
            if (!isValidSceneId(normalizedSceneId)) {
                return@forEach
            }
            if (normalizedProfileId.isEmpty() || !isValidModelName(normalizedModelId)) {
                return@forEach
            }
            map[normalizedSceneId] = SceneModelBindingEntry(
                sceneId = normalizedSceneId,
                providerProfileId = normalizedProfileId,
                modelId = normalizedModelId
            )
        }
        writeBindingMap(mmkv, map)
    }

    fun isValidSceneId(sceneId: String): Boolean {
        return sceneId.trim() in allowedScenes
    }

    fun isValidModelName(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isNotEmpty() && !normalized.startsWith("scene.")
    }

    private fun ensureMigrated() {
        val mmkv = MMKV.defaultMMKV() ?: return
        if (mmkv.decodeBool(MIGRATION_DONE_KEY, false)) {
            return
        }

        try {
            val current = readBindingMap(mmkv)
            if (current.isEmpty()) {
                val defaultProfileId = ModelProviderConfigStore.getEditingProfileId()
                val legacyOverrides = readLegacyOverrideMap(mmkv)
                if (defaultProfileId.isNotBlank() && legacyOverrides.isNotEmpty()) {
                    val migrated = legacyOverrides.mapValues { (sceneId, modelId) ->
                        SceneModelBindingEntry(
                            sceneId = sceneId,
                            providerProfileId = defaultProfileId,
                            modelId = modelId
                        )
                    }
                    writeBindingMap(mmkv, migrated)
                }
            }
        } catch (t: Throwable) {
            OmniLog.w(TAG, "migrate legacy scene bindings failed: ${t.message}")
        } finally {
            mmkv.encode(MIGRATION_DONE_KEY, true)
        }
    }

    private fun readBindingMap(mmkv: MMKV): Map<String, SceneModelBindingEntry> {
        val raw = mmkv.decodeString(KEY_SCENE_BINDING_MAP)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, SceneModelBindingEntry>>() {}.type
            val parsed: Map<String, SceneModelBindingEntry> = gson.fromJson(raw, type) ?: emptyMap()
            parsed.mapNotNull { (sceneId, binding) ->
                val normalizedSceneId = sceneId.trim().takeIf { isValidSceneId(it) }
                    ?: return@mapNotNull null
                val normalizedProfileId = binding.providerProfileId.trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val normalizedModelId = binding.modelId.trim()
                    .takeIf { isValidModelName(it) }
                    ?: return@mapNotNull null
                normalizedSceneId to SceneModelBindingEntry(
                    sceneId = normalizedSceneId,
                    providerProfileId = normalizedProfileId,
                    modelId = normalizedModelId
                )
            }.toMap()
        } catch (t: Throwable) {
            OmniLog.w(TAG, "read scene binding map failed: ${t.message}")
            emptyMap()
        }
    }

    private fun writeBindingMap(mmkv: MMKV, map: Map<String, SceneModelBindingEntry>) {
        val normalized = map.entries.mapNotNull { (sceneId, binding) ->
            val normalizedSceneId = sceneId.trim().takeIf { isValidSceneId(it) }
                ?: return@mapNotNull null
            val normalizedProfileId = binding.providerProfileId.trim()
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val normalizedModelId = binding.modelId.trim()
                .takeIf { isValidModelName(it) }
                ?: return@mapNotNull null
            normalizedSceneId to SceneModelBindingEntry(
                sceneId = normalizedSceneId,
                providerProfileId = normalizedProfileId,
                modelId = normalizedModelId
            )
        }.toMap()
        mmkv.encode(KEY_SCENE_BINDING_MAP, gson.toJson(normalized))
    }

    private fun readLegacyOverrideMap(mmkv: MMKV): Map<String, String> {
        val raw = mmkv.decodeString(LEGACY_SCENE_OVERRIDE_MAP)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val parsed: Map<String, String> = gson.fromJson(raw, type) ?: emptyMap()
            parsed.mapNotNull { (sceneId, modelId) ->
                val normalizedSceneId = sceneId.trim().takeIf { isValidSceneId(it) }
                    ?: return@mapNotNull null
                val normalizedModelId = modelId.trim().takeIf { isValidModelName(it) }
                    ?: return@mapNotNull null
                normalizedSceneId to normalizedModelId
            }.toMap()
        } catch (t: Throwable) {
            OmniLog.w(TAG, "read legacy scene override map failed: ${t.message}")
            emptyMap()
        }
    }
}
