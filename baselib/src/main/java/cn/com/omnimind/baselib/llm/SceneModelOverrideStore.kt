package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV

object SceneModelOverrideStore {
    private const val TAG = "SceneModelOverrideStore"
    private const val KEY_SCENE_OVERRIDE_MAP = "scene_model_override_map"
    private val allowedScenes = setOf(
        "scene.dispatch.model",
        "scene.vlm.operation.primary",
        "scene.compactor.context.chat",
        "scene.memory.embedding",
        "scene.memory.rollup"
    )
    private val gson = Gson()

    fun getOverrideEntries(): List<SceneModelOverrideEntry> {
        return SceneModelBindingStore.getBindingEntries()
            .map { SceneModelOverrideEntry(sceneId = it.sceneId, model = it.modelId) }
    }

    fun getOverrideMap(): Map<String, String> {
        return SceneModelBindingStore.getBindingEntries()
            .associate { it.sceneId to it.modelId }
            .toSortedMap()
    }

    fun getOverrideModel(sceneId: String): String? {
        return SceneModelBindingStore.getBinding(sceneId)?.modelId
    }

    fun saveOverride(sceneId: String, model: String) {
        val profileId = ModelProviderConfigStore.getEditingProfileId()
        SceneModelBindingStore.saveBinding(
            sceneId = sceneId,
            providerProfileId = profileId,
            modelId = model
        )
    }

    fun clearOverride(sceneId: String) {
        SceneModelBindingStore.clearBinding(sceneId)
    }

    fun isValidModelName(value: String): Boolean {
        return SceneModelBindingStore.isValidModelName(value)
    }

    internal fun readOverrideMap(mmkv: MMKV): Map<String, String> {
        val raw = mmkv.decodeString(KEY_SCENE_OVERRIDE_MAP)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return parseOverrideMap(raw)
    }

    internal fun readLegacyOverrideMapForScope(mmkv: MMKV, userId: String?): Map<String, String> {
        val raw = mmkv.decodeString(ModelProviderConfigStore.scopedKey(KEY_SCENE_OVERRIDE_MAP, userId))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return parseOverrideMap(raw)
    }

    internal fun writeOverrideMap(mmkv: MMKV, map: Map<String, String>) {
        val normalized = map.entries
            .mapNotNull { (sceneId, model) ->
                val normalizedSceneId = sceneId.trim()
                    .takeIf { it in allowedScenes }
                    ?: return@mapNotNull null
                val normalizedModel = model.trim()
                    .takeIf { isValidModelName(it) }
                    ?: return@mapNotNull null
                normalizedSceneId to normalizedModel
            }
            .toMap()
        mmkv.encode(KEY_SCENE_OVERRIDE_MAP, gson.toJson(normalized))
    }

    private fun parseOverrideMap(raw: String): Map<String, String> {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val parsed: Map<String, String> = gson.fromJson(raw, type) ?: emptyMap()
            parsed.entries
                .mapNotNull { (sceneId, model) ->
                    val normalizedSceneId = sceneId.trim()
                        .takeIf { it in allowedScenes }
                        ?: return@mapNotNull null
                    val normalizedModel = model.trim()
                        .takeIf { isValidModelName(it) }
                        ?: return@mapNotNull null
                    normalizedSceneId to normalizedModel
                }
                .toMap()
        } catch (t: Throwable) {
            OmniLog.w(TAG, "read override map failed: ${t.message}")
            emptyMap()
        }
    }
}
