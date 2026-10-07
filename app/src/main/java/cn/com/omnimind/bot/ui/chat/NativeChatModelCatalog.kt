package cn.com.omnimind.bot.ui.chat

import android.content.Context
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.model.ProviderEditorRepository
import cn.com.omnimind.bot.model.SceneModelSettingsRepository

/**
 * The model list `/model` offers on the native composer (batch 5d-1c).
 *
 * Every local Harness runs on the app's dispatch binding (Dart
 * `_usesSharedProviderModel`), so the catalog is the bound Provider's
 * discovered and manual models, and choosing one rebinds
 * `scene.dispatch.model` (Dart `_selectAgentModel`). Ported from
 * `_loadSharedProviderModelIds`; the editing-Provider fallback for builds
 * that never wrote a binding is not ported, so a missing binding shows an
 * empty catalog instead of guessing a Provider.
 */
internal class NativeChatModelCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val scenes = SceneModelSettingsRepository(appContext)
    private val providers = ProviderEditorRepository(appContext)

    data class Catalog(val providerProfileId: String?, val models: List<String>, val selected: String?)

    fun load(): Catalog {
        val binding = SceneModelBindingStore.getBinding(DISPATCH_SCENE)
        val profile = binding?.providerProfileId?.let(ModelProviderConfigStore::getProfile)
            ?: return Catalog(null, emptyList(), null)
        val discovered = ModelProviderConfigStore.cachedModels(appContext, profile).map { it.id }
        val models = mergeCatalog(discovered, providers.manualIds(profile.id), binding.modelId)
        return Catalog(profile.id, models, binding.modelId.trim().ifEmpty { null })
    }

    /**
     * Rebinds the dispatch model. Through [SceneModelSettingsRepository], so a
     * same-Provider change keeps the live session as in the settings page.
     */
    suspend fun select(catalog: Catalog, modelId: String): String? {
        val providerId = catalog.providerProfileId ?: return null
        val match = catalog.models.firstOrNull { it.equals(modelId.trim(), ignoreCase = true) } ?: return null
        scenes.saveBinding(DISPATCH_SCENE, providerId, match)
        return match
    }

    companion object {
        const val DISPATCH_SCENE = "scene.dispatch.model"

        /** Discovered then manual ids, trimmed and deduplicated; a cold catalog keeps the bound model. */
        fun mergeCatalog(discovered: List<String>, manual: List<String>, boundModel: String?): List<String> {
            val ids = LinkedHashSet<String>()
            (discovered + manual).map(String::trim).filterTo(ids) { it.isNotEmpty() }
            if (ids.isEmpty()) boundModel?.trim()?.takeIf { it.isNotEmpty() }?.let(ids::add)
            return ids.toList()
        }
    }
}
