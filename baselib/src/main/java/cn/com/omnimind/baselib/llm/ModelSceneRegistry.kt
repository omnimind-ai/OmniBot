package cn.com.omnimind.baselib.llm

import android.content.Context
import cn.com.omnimind.baselib.R
import cn.com.omnimind.baselib.i18n.AppLanguageMode
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 场景配置注册表：sceneId -> (model + prompt) 的统一映射
 * 
 * 每个场景绑定：
 * - model: 使用的模型名称
 * - prompt: 提示词模板（可选）
 * - description: 场景描述
 *
 * 配置优先级（从高到低）：
 * 1. 内置 JSON 文件（res/raw/model_scenes_default.json，本地优先）
 * 2. 远程配置（仅补缺本地不存在的场景）
 */
object ModelSceneRegistry {
    private const val TAG = "ModelSceneRegistry"
    private const val SCENE_PREFIX = "scene."

    private val gson = Gson()
    private var applicationContext: Context? = null

    enum class SceneTransport(val wireValue: String) {
        CONVERSATION_CHAT("conversation_chat"),
        OPENAI_COMPATIBLE("openai_compatible");

        companion object {
            fun fromRaw(raw: Any?): SceneTransport? {
                val normalized = (raw as? String)?.trim()?.lowercase().orEmpty()
                return entries.firstOrNull { it.wireValue == normalized }
            }
        }
    }

    enum class ResponseParser(val wireValue: String) {
        TEXT_CONTENT("text_content");

        companion object {
            fun fromRaw(raw: Any?): ResponseParser? {
                val normalized = (raw as? String)?.trim()?.lowercase().orEmpty()
                return entries.firstOrNull { it.wireValue == normalized }
            }
        }
    }

    enum class SceneSource(val wireValue: String) {
        BUILTIN("builtin"),
        REMOTE_FILL("remote_fill"),
        USER_OVERRIDE("user_override")
    }

    data class SceneInfo(
        val model: String,
        val prompt: String? = null,
        val description: String? = null,
        val promptI18n: Map<String, String> = emptyMap(),
        val descriptionI18n: Map<String, String> = emptyMap(),
        val transport: SceneTransport? = null,
        val responseParser: ResponseParser? = null,
        val inheritsModelFrom: String? = null,
        val overrideGroup: String? = null
    )

    data class SceneRuntimeProfile(
        val sceneId: String,
        val model: String,
        val prompt: String? = null,
        val description: String? = null,
        val transport: SceneTransport,
        val responseParser: ResponseParser,
        val overrideGroup: String? = null,
        val configSource: SceneSource,
        val modelSource: SceneSource,
        val inheritsModelFrom: String? = null
    )

    /**
     * 初始化（必须在 Application.onCreate 中调用）
     * 开源版仅使用本地内置配置
     */
    fun init(context: Context) {
        applicationContext = context.applicationContext
        OmniLog.d(TAG, "ModelSceneRegistry 已初始化（builtin only）")
    }

    /**
     * 保留字段仅用于兼容旧接口，开源版不会启用远程场景配置。
     */
    private var remoteSceneMap: Map<String, SceneInfo>? = null

    /**
     * 内置场景配置映射表（从 JSON 加载，本地优先）
     */
    private val builtinSceneMap: Map<String, SceneInfo> by lazy {
        loadBuiltinScenes()
    }

    /**
     * 从 res/raw/model_scenes_default.json 加载内置配置
     */
    private fun loadBuiltinScenes(): Map<String, SceneInfo> {
        return try {
            val context = applicationContext
            if (context == null) {
                OmniLog.e(TAG, "❌ ApplicationContext 未初始化，无法加载内置配置")
                return emptyMap()
            }

            // Keep a compile-time resource reference so Release resource
            // shrinking can prove that the built-in scene catalog is used.
            val inputStream =
                context.resources.openRawResource(R.raw.model_scenes_default)
            val json = inputStream.bufferedReader().use { it.readText() }
            
            // 解析 JSON
            val rawMap = gson.fromJson<Map<String, Map<String, Any?>>>(
                json,
                object : TypeToken<Map<String, Map<String, Any?>>>() {}.type
            )

            // 转换为 SceneInfo
            val sceneMap = mutableMapOf<String, SceneInfo>()
            rawMap.forEach { (sceneId, config) ->
                val sceneInfo = parseSceneInfo(sceneId, config)
                if (sceneInfo != null) {
                    sceneMap[sceneId] = sceneInfo
                } else {
                    OmniLog.w(TAG, "内置场景 $sceneId 缺少有效 model 字段，跳过")
                }
            }

            OmniLog.i(TAG, "✅ 内置配置加载成功，共 ${sceneMap.size} 个场景")
            sceneMap

        } catch (e: Exception) {
            OmniLog.e(TAG, "❌ 加载内置配置失败，将使用空配置", e)
            emptyMap()
        }
    }

    /**
     * 更新远程配置
     * @param remoteConfig 远程配置的 Map，key 为 sceneId，value 为包含 model/prompt/description 的 Map
     * 
     */
    @Synchronized
    fun updateFromRemoteConfig(remoteConfig: Map<String, Map<String, Any?>>?) {
        // OSS: ignore remote scene config and keep builtin-only behavior.
        remoteSceneMap = null
        OmniLog.i(TAG, "忽略远程场景配置，开源版仅使用内置配置")
    }

    /**
     * 清除远程配置，恢复使用内置 JSON 配置
     */
    @Synchronized
    fun clearRemoteConfig() {
        remoteSceneMap = null
        OmniLog.i(TAG, "已清理远程场景状态（开源版仅使用内置配置）")
    }

    /**
     * 检查是否使用远程配置
     */
    fun isUsingRemoteConfig(): Boolean {
        return false
    }

    /**
     * 获取场景完整信息
     * 查找策略：
     * 1. 内置配置优先
     * 2. 远程配置补缺
     */
    fun get(sceneId: String): SceneInfo? {
        val profile = getRuntimeProfile(sceneId) ?: return null
        return SceneInfo(
            model = profile.model,
            prompt = profile.prompt,
            description = profile.description,
            promptI18n = emptyMap(),
            descriptionI18n = emptyMap(),
            transport = profile.transport,
            responseParser = profile.responseParser,
            inheritsModelFrom = profile.inheritsModelFrom,
            overrideGroup = profile.overrideGroup
        )
    }

    fun isSceneId(sceneIdOrModel: String?): Boolean {
        return sceneIdOrModel?.trim()?.startsWith(SCENE_PREFIX) == true
    }

    fun getRuntimeProfile(sceneId: String): SceneRuntimeProfile? {
        return resolveRuntimeProfile(
            sceneId = sceneId,
            builtinScenes = builtinSceneMap,
            remoteScenes = null
        )
    }

    fun listRuntimeProfiles(): List<SceneRuntimeProfile> {
        val allSceneIds = builtinSceneMap.keys.sorted()
        return allSceneIds.mapNotNull(::getRuntimeProfile)
    }

    internal fun resolveRuntimeProfileForTesting(
        sceneId: String,
        builtinScenes: Map<String, SceneInfo>,
        remoteScenes: Map<String, SceneInfo>? = null
    ): SceneRuntimeProfile? {
        return resolveRuntimeProfile(
            sceneId = sceneId,
            builtinScenes = builtinScenes,
            remoteScenes = remoteScenes
        )
    }

    private data class SceneLookup(
        val sceneInfo: SceneInfo,
        val source: SceneSource
    )

    private fun resolveRuntimeProfile(
        sceneId: String,
        builtinScenes: Map<String, SceneInfo>,
        remoteScenes: Map<String, SceneInfo>?
    ): SceneRuntimeProfile? {
        return resolveRuntimeProfileInternal(
            sceneId = sceneId,
            builtinScenes = builtinScenes,
            remoteScenes = remoteScenes,
            visited = mutableSetOf()
        )
    }

    private fun resolveRuntimeProfileInternal(
        sceneId: String,
        builtinScenes: Map<String, SceneInfo>,
        remoteScenes: Map<String, SceneInfo>?,
        visited: MutableSet<String>
    ): SceneRuntimeProfile? {
        if (!visited.add(sceneId)) {
            OmniLog.w(TAG, "检测到 scene 继承循环: ${visited.joinToString(" -> ")} -> $sceneId")
            return null
        }

        val lookup = findScene(sceneId, builtinScenes, remoteScenes)
        if (lookup == null) {
            visited.remove(sceneId)
            return null
        }

        val baseScene = lookup.sceneInfo
        val parentProfile = baseScene.inheritsModelFrom
            ?.takeIf { it.isNotBlank() }
            ?.let {
                resolveRuntimeProfileInternal(
                    sceneId = it,
                    builtinScenes = builtinScenes,
                    remoteScenes = remoteScenes,
                    visited = visited
                )
            }

        val transport = baseScene.transport
            ?: parentProfile?.transport
            ?: defaultTransportForScene(sceneId)
        val parser = baseScene.responseParser
            ?: parentProfile?.responseParser
            ?: defaultParserForScene(sceneId)
        val overrideGroup = baseScene.overrideGroup
            ?: parentProfile?.overrideGroup
            ?: defaultOverrideGroupForScene(sceneId)
        val inheritedModel = parentProfile?.model
        val effectiveModelBeforeOverride = if (!baseScene.inheritsModelFrom.isNullOrBlank() && !inheritedModel.isNullOrBlank()) {
            inheritedModel
        } else {
            baseScene.model
        }
        val configSource = lookup.source
        val inheritedModelSource = parentProfile?.modelSource
        val baseModelSource = if (!baseScene.inheritsModelFrom.isNullOrBlank() && inheritedModelSource != null) {
            inheritedModelSource
        } else {
            lookup.source
        }

        visited.remove(sceneId)
        return SceneRuntimeProfile(
            sceneId = sceneId,
            model = effectiveModelBeforeOverride,
            prompt = resolveLocalizedValue(
                single = baseScene.prompt,
                localized = baseScene.promptI18n
            ),
            description = resolveLocalizedValue(
                single = baseScene.description,
                localized = baseScene.descriptionI18n
            ),
            transport = transport,
            responseParser = parser,
            overrideGroup = overrideGroup,
            configSource = configSource,
            modelSource = baseModelSource,
            inheritsModelFrom = baseScene.inheritsModelFrom
        )
    }

    private fun findScene(
        sceneId: String,
        builtinScenes: Map<String, SceneInfo>,
        remoteScenes: Map<String, SceneInfo>?
    ): SceneLookup? {
        builtinScenes[sceneId]?.let {
            return SceneLookup(it, SceneSource.BUILTIN)
        }
        remoteScenes?.get(sceneId)?.let {
            return SceneLookup(it, SceneSource.REMOTE_FILL)
        }
        return null
    }

    private fun parseSceneInfo(sceneId: String, config: Map<String, Any?>): SceneInfo? {
        val rawModel = (config["model"] as? String)?.trim().orEmpty()
        val model = rawModel.takeIf { it.isNotEmpty() } ?: return null
        return SceneInfo(
            model = model,
            prompt = config["prompt"] as? String,
            description = config["description"] as? String,
            promptI18n = readLocalizedMap(config["prompt_i18n"]),
            descriptionI18n = readLocalizedMap(config["description_i18n"]),
            transport = SceneTransport.fromRaw(config["transport"]),
            responseParser = ResponseParser.fromRaw(config["response_parser"]),
            inheritsModelFrom = (config["inherits_model_from"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            overrideGroup = (config["override_group"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun readLocalizedMap(raw: Any?): Map<String, String> {
        val value = raw as? Map<*, *> ?: return emptyMap()
        return value.entries.mapNotNull { (key, item) ->
            val normalizedKey = key?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val normalizedValue = item?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            normalizedKey to normalizedValue
        }.toMap()
    }

    private fun resolveLocalizedValue(
        single: String?,
        localized: Map<String, String>
    ): String? {
        if (localized.isEmpty()) {
            return single
        }
        val context = applicationContext
        val locale = if (context != null) {
            AppLocaleManager.resolvePromptLocale(context)
        } else {
            AppLocaleManager.resolvePromptLocale(
                mode = AppLanguageMode.SYSTEM,
                systemLocale = java.util.Locale.getDefault()
            )
        }
        return localized[locale.tag]
            ?: localized[locale.locale.language]
            ?: localized["en-US"]
            ?: localized["zh-CN"]
            ?: single
    }

    private fun defaultTransportForScene(sceneId: String): SceneTransport {
        return when (sceneId) {
            "scene.dispatch.model",
            "scene.compactor.context.chat",
            "scene.memory.embedding",
            "scene.memory.rollup" -> SceneTransport.OPENAI_COMPATIBLE
            else -> SceneTransport.OPENAI_COMPATIBLE
        }
    }

    private fun defaultParserForScene(sceneId: String): ResponseParser {
        return when (sceneId) {
            "scene.compactor.context.chat",
            "scene.memory.embedding",
            "scene.memory.rollup",
            "scene.dispatch.model" -> ResponseParser.TEXT_CONTENT
            else -> ResponseParser.TEXT_CONTENT
        }
    }

    private fun defaultOverrideGroupForScene(@Suppress("UNUSED_PARAMETER") sceneId: String): String? =
        null

    /**
     * 解析场景 ID 为模型名称
     * - 如果入参是已知 sceneId：返回配置里的真实 model
     * - 强制要求使用 scene.xxx 格式，不允许直接传入裸模型名
     */
    fun resolveModel(sceneIdOrModel: String?): String {
        if (sceneIdOrModel.isNullOrBlank()) return ""
        val input = sceneIdOrModel.trim()
        
        // 强制校验：必须以 scene. 开头
        if (!input.startsWith(SCENE_PREFIX)) {
            val errorMsg = """
                ❌ Invalid model format: '$input'
                
                You MUST use scene.xxx format instead of raw model names.
                
                Examples:
                  ✅ scene.dispatch.model
                  ❌ qwen-plus (raw model name - NOT allowed)
            
            Available scenes: check ModelSceneRegistry (BUILTIN JSON config)
            """.trimIndent()
            
            OmniLog.e(TAG, errorMsg)
            throw IllegalArgumentException(errorMsg)
        }
        
        val profile = getRuntimeProfile(input)
        if (profile != null) return profile.model

        // 如果是 scene. 开头但未配置，抛出错误
        // 列出所有实际可用的场景（远程 + 内置）
        val allAvailableScenes = builtinSceneMap.keys
        val errorMsg = """
            ❌ Unknown scene ID: '$input'
            
            This scene is not configured in ModelSceneRegistry.
            
            Please check:
            1. Spelling is correct
            2. Scene is defined in ModelSceneRegistry (BUILTIN JSON config)
            
            Available scenes (${allAvailableScenes.size} total):
            ${allAvailableScenes.sorted().joinToString("\n") { "  - $it" }}
        """.trimIndent()
        
        OmniLog.e(TAG, errorMsg)
        throw IllegalArgumentException(errorMsg)
    }

    /**
     * 获取场景的提示词模板
     */
    fun getPrompt(sceneId: String): String? {
        return getRuntimeProfile(sceneId)?.prompt
    }

    /**
     * 渲染提示词模板（替换占位符）
     * 
     * @param template 模板字符串，包含 {{KEY}} 格式的占位符
     * @param replacements 替换映射，key 不需要包含 {{}}
     * @return 渲染后的字符串
     */
    fun renderPrompt(template: String, replacements: Map<String, String>): String {
        var rendered = template
        replacements.forEach { (key, value) ->
            rendered = rendered.replace("{{$key}}", value)
        }
        return rendered
    }

    /**
     * 简化版：单个占位符替换
     */
    fun renderPrompt(template: String, prompt: String): String {
        return renderPrompt(template, mapOf("PROMPT" to prompt))
    }

    /**
     * 获取场景描述
     */
    fun getDescription(sceneId: String): String? {
        return getRuntimeProfile(sceneId)?.description
    }
}
