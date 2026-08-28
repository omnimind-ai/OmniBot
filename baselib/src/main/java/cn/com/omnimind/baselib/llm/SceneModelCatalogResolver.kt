package cn.com.omnimind.baselib.llm

object SceneModelCatalogResolver {
    fun listCatalogItems(): List<SceneCatalogItem> {
        val profiles = ModelProviderConfigStore.listProfiles().toMutableList()
        PlatformAiProvisioner.officialProfileOrNull()?.let { profiles.add(it) }
        val profilesById = profiles.associateBy { it.id }
        val bindings = SceneModelBindingStore.getBindingMap()
        return ModelSceneRegistry.listRuntimeProfiles()
            .map { profile ->
                val directBinding = bindings[profile.sceneId]
                val effectiveBinding = directBinding ?: inheritedBinding(profile, bindings)
                val boundProfile = effectiveBinding?.providerProfileId?.let(profilesById::get)
                val bindingApplied = effectiveBinding != null && boundProfile?.isConfigured() == true
                val bindingProfileMissing = directBinding != null && boundProfile == null
                SceneCatalogItem(
                    sceneId = profile.sceneId,
                    description = profile.description,
                    defaultModel = profile.model,
                    effectiveModel = if (bindingApplied) effectiveBinding.modelId else profile.model,
                    effectiveProviderProfileId = if (bindingApplied) boundProfile.id else null,
                    effectiveProviderProfileName = if (bindingApplied) boundProfile.name else null,
                    boundProviderProfileId = directBinding?.providerProfileId,
                    boundProviderProfileName = directBinding?.providerProfileId?.let(profilesById::get)?.name,
                    transport = if (bindingApplied) {
                        ModelSceneRegistry.SceneTransport.OPENAI_COMPATIBLE.wireValue
                    } else {
                        profile.transport.wireValue
                    },
                    configSource = profile.configSource.wireValue,
                    overrideApplied = directBinding != null && bindingApplied,
                    overrideModel = directBinding?.modelId,
                    providerConfigured = boundProfile?.isConfigured() == true,
                    bindingExists = directBinding != null,
                    bindingProfileMissing = bindingProfileMissing
                )
            }
    }

    private fun inheritedBinding(
        profile: ModelSceneRegistry.SceneRuntimeProfile,
        bindings: Map<String, SceneModelBindingEntry>
    ): SceneModelBindingEntry? {
        val visited = mutableSetOf<String>()
        var parentId = profile.inheritsModelFrom
        while (parentId != null && visited.add(parentId)) {
            bindings[parentId]?.let { return it }
            parentId = ModelSceneRegistry.getRuntimeProfile(parentId)?.inheritsModelFrom
        }
        return null
    }
}
