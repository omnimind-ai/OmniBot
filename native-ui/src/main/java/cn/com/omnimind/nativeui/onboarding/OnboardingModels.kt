package cn.com.omnimind.nativeui.onboarding

import androidx.compose.runtime.Immutable

/*
 * First-use onboarding rules (batch 5f-1), ported from
 * `welcome/pages/onboarding/onboarding_definitions.dart`,
 * `onboarding_flow_controller.dart` and the progress and scene rules of
 * `onboarding_environment_controller.dart` / `onboarding_provider_controller.dart`.
 * Pure: the app owns the terminal, Provider and permission calls.
 */

/** Dart `TutorialPage`, in flow order. */
enum class OnboardingPage {
    System, Development, Tools, EnvironmentProgress, Permissions,
    Provider, ProviderConnection, ModelInventory, PrimaryScenes, MemoryScenes, Completion,
}

/** Dart `tutorialNavigationPages`: the pages the dotted footer reaches. */
val ONBOARDING_NAVIGATION_PAGES = listOf(
    OnboardingPage.System, OnboardingPage.Development, OnboardingPage.Tools, OnboardingPage.Permissions,
    OnboardingPage.Provider, OnboardingPage.ProviderConnection, OnboardingPage.ModelInventory,
    OnboardingPage.PrimaryScenes, OnboardingPage.MemoryScenes,
)

/**
 * Dart `OnboardingFlowController` as an immutable value so it saves across
 * process death: the current page, the back stack and the visited
 * navigation pages.
 */
@Immutable
data class OnboardingFlow(
    val page: OnboardingPage = OnboardingPage.System,
    val history: List<OnboardingPage> = emptyList(),
    val visited: Set<OnboardingPage> = setOf(OnboardingPage.System),
    /** 1 forward, -1 back: the slide direction of the page change. */
    val direction: Int = 1,
) {
    val hasHistory: Boolean get() = history.isNotEmpty()
    val onNavigationPage: Boolean get() = page in ONBOARDING_NAVIGATION_PAGES
    val navigationIndex: Int get() = ONBOARDING_NAVIGATION_PAGES.indexOf(page)

    fun goTo(target: OnboardingPage): OnboardingFlow {
        if (page == target) return this
        return copy(
            page = target,
            history = history + page,
            visited = if (target in ONBOARDING_NAVIGATION_PAGES) visited + target else visited,
            direction = 1,
        )
    }

    /** Null when there is nothing to pop. */
    fun goBack(): OnboardingFlow? =
        if (history.isEmpty()) null else copy(page = history.last(), history = history.dropLast(1), direction = -1)

    /** Jumps to a visited footer page, trimming the back stack to keep back consistent. */
    fun jumpToVisited(target: OnboardingPage): OnboardingFlow {
        if (target == page || target !in visited) return this
        val index = history.lastIndexOf(target)
        if (index < 0) return goTo(target)
        val backwards = ONBOARDING_NAVIGATION_PAGES.indexOf(target) < navigationIndex
        return copy(page = target, history = history.subList(0, index), direction = if (backwards) -1 else 1)
    }
}

@Immutable
data class EnvironmentPreset(
    val id: String,
    val titleZh: String,
    val titleEn: String,
    val descriptionZh: String,
    val descriptionEn: String,
    val packageIds: List<String>,
    val contents: String,
)

/** Dart `environmentPresets`. */
val ENVIRONMENT_PRESETS = listOf(
    EnvironmentPreset(
        "general", "聊天 Agent 助手", "Chat Agent Assistant",
        "适合日常对话、任务协作和工具调用，也保留常用开发能力。",
        "For everyday chat, task collaboration, and tool use, with common development capabilities included.",
        listOf("nodejs", "npm", "git", "python", "pip", "uv"), "Node.js · npm · Python · pip · uv · Git",
    ),
    EnvironmentPreset(
        "node", "Node.js / Web", "Node.js / Web",
        "面向前端、后端服务和 JavaScript / TypeScript 工程。",
        "For frontend, backend services, and JavaScript or TypeScript projects.",
        listOf("nodejs", "npm", "git"), "Node.js · npm · Git",
    ),
    EnvironmentPreset(
        "python", "Python", "Python",
        "面向自动化、数据处理、脚本和 Python 项目。",
        "For automation, data processing, scripts, and Python projects.",
        listOf("python", "pip", "uv", "git"), "Python · pip · uv · Git",
    ),
)

@Immutable
data class OptionalTool(val id: String, val label: String, val descriptionZh: String, val descriptionEn: String)

/** Dart `optionalTools`. */
val OPTIONAL_TOOLS = listOf(
    OptionalTool("codex", "Codex CLI", "OpenAI 编程 Agent", "OpenAI coding agent"),
    OptionalTool("claude_code", "Claude Code", "Anthropic 编程 Agent", "Anthropic coding agent"),
    OptionalTool("opencode", "OpenCode", "开源编程 Agent", "Open-source coding agent"),
    OptionalTool("ssh_client", "SSH", "连接远程开发主机", "Connect to remote hosts"),
)

/** Dart `selectedPackageIds`: the preset's packages then the optional tools, deduplicated in order. */
fun onboardingPackageIds(presetId: String, optionalToolIds: Set<String>): List<String> {
    val preset = ENVIRONMENT_PRESETS.firstOrNull { it.id == presetId } ?: ENVIRONMENT_PRESETS.first()
    return LinkedHashSet(preset.packageIds + optionalToolIds).toList()
}

@Immutable
data class ProviderOption(
    val id: String,
    val label: String,
    val vendorKey: String?,
    val baseUrl: String,
    val sourceType: String,
    val protocolType: String,
)

/** Dart `providerOptions`; `custom` is last and needs no API key. */
val PROVIDER_OPTIONS = listOf(
    ProviderOption("deepseek", "DeepSeek", "deepseek", "https://api.deepseek.com", "deepseek", "deepseek"),
    ProviderOption("moonshot", "Kimi", "moonshot", "https://api.moonshot.cn/v1", "moonshot", "openai_compatible"),
    ProviderOption("mimo", "Mimo", "xiaomi", "https://api.xiaomimimo.com/v1", "mimo", "openai_compatible"),
    ProviderOption("openai", "OpenAI", "openai", "https://api.openai.com/v1", "custom", "openai_compatible"),
    ProviderOption("anthropic", "Anthropic", "anthropic", "https://api.anthropic.com/v1", "custom", "anthropic"),
    ProviderOption("custom", "Compatible API", null, "", "custom", "openai_compatible"),
)

@Immutable
data class OnboardingScene(val id: String, val title: String, val descriptionZh: String, val descriptionEn: String) {
    val memory: Boolean get() = id.startsWith("scene.memory.")
}

/** Dart `sceneDefinitions`: two primary scenes, then two memory scenes. */
val ONBOARDING_SCENES = listOf(
    OnboardingScene("scene.dispatch.model", "Agent",
        "理解任务、规划步骤并调用工具，建议选择能力最强的工具调用模型。",
        "Understands tasks, plans work, and calls tools. Prefer your strongest tool-capable model."),
    OnboardingScene("scene.compactor.context.chat", "Chat Compactor",
        "在长对话中压缩历史上下文，平衡速度与总结准确度。",
        "Compresses long chat history while balancing speed and summary accuracy."),
    OnboardingScene("scene.memory.embedding", "Memory Embed",
        "把记忆转换为向量用于检索；若提供商有 embedding 模型，请优先选择。",
        "Creates vectors for memory search. Prefer an embedding model when available."),
    OnboardingScene("scene.memory.rollup", "Memory Rollup",
        "归纳长期记忆并去除重复信息，适合稳定、成本适中的文本模型。",
        "Consolidates long-term memory and removes duplicates. A reliable text model is ideal."),
)

/** Dart `_looksLikeEmbeddingModel`. */
fun looksLikeEmbeddingModel(modelId: String): Boolean {
    val lower = modelId.lowercase()
    return "embed" in lower || "bge-" in lower || "text-embedding" in lower
}

/**
 * Dart `_applyDefaultSceneSelections`: keep a binding whose model is still
 * offered; otherwise the first non-embedding model, and for the embedding
 * scene the first embedding-looking model.
 */
fun defaultSceneSelections(models: List<String>, bindings: Map<String, String> = emptyMap()): Map<String, String> {
    if (models.isEmpty()) return emptyMap()
    val general = models.firstOrNull { !looksLikeEmbeddingModel(it) } ?: models.first()
    val embedding = models.firstOrNull(::looksLikeEmbeddingModel) ?: general
    return ONBOARDING_SCENES.associate { scene ->
        val bound = bindings[scene.id]?.takeIf { it in models }
        scene.id to (bound ?: if (scene.id == "scene.memory.embedding") embedding else general)
    }
}

/**
 * Dart `phaseIndex`: the install phase (0 prepare, 1 system, 2 tools, 3
 * verify, 4 done) from the stage text and the known progress, whichever is
 * further.
 */
fun environmentPhase(stage: String, knownProgress: Float, ready: Boolean): Int {
    val text = stage.trim()
    if (ready || "配置完成" in text || "均已就绪" in text || "所选开发工具已就绪" in text) return 4
    val byText = when {
        "验证" in text || "安装完成" in text || "校验完成" in text -> 3
        "所选开发工具" in text || "Agent CLI 包" in text -> 2
        listOf("workspace", "终端", "Linux", "Alpine", "Ubuntu", "运行资源").any { it in text } -> 1
        else -> 0
    }
    val byProgress = when {
        knownProgress >= 0.90f -> 3
        knownProgress >= 0.54f -> 2
        knownProgress >= 0.10f -> 1
        else -> 0
    }
    return maxOf(byText, byProgress)
}

private val PHASE_FLOOR = floatArrayOf(0.03f, 0.10f, 0.54f, 0.90f, 0.99f)
private val PHASE_CEILING = floatArrayOf(0.09f, 0.50f, 0.89f, 0.98f, 0.99f)

/**
 * One 350 ms tick of Dart `_startProgressTracking`: move quickly towards the
 * native progress or the phase floor, then creep towards the phase ceiling,
 * never backwards and never to 100% before the install reports success.
 */
fun nextEnvironmentProgress(current: Float, nativeProgress: Float, phase: Int): Float {
    val index = phase.coerceIn(0, 4)
    val ceiling = PHASE_CEILING[index].coerceIn(current, 0.99f)
    val target = maxOf(nativeProgress, PHASE_FLOOR[index])
    val next = if (current + 0.001f < target) {
        current + ((target - current) * 0.24f).coerceIn(0.008f, 0.035f)
    } else {
        val remaining = ceiling - current
        if (remaining <= 0f) current else current + (remaining * 0.018f).coerceIn(0.0006f, 0.004f)
    }
    return next.coerceIn(current, ceiling)
}

/** Why the connection form was refused (Dart `configure`). */
enum class ProviderFormError { MissingName, InvalidBaseUrl, MissingApiKey }

/** [validBaseUrl] is the app's `ModelProviderConfigStore.isValidBaseUrl`. */
fun validateProviderForm(
    option: ProviderOption,
    name: String,
    baseUrl: String,
    apiKey: String,
    validBaseUrl: (String) -> Boolean,
): ProviderFormError? = when {
    name.isBlank() -> ProviderFormError.MissingName
    !validBaseUrl(baseUrl.trim()) -> ProviderFormError.InvalidBaseUrl
    option.id != "custom" && apiKey.isBlank() -> ProviderFormError.MissingApiKey
    else -> null
}

/**
 * Dart `_tutorialNextAction`: where the footer arrow leads, or null while the
 * page still needs input. Tools skips the install. Provider skips the model
 * setup, or, once a Provider is connected, continues to its models (Dart
 * always skipped, so returning to a configured Provider needed a detour
 * through the connection form). The chat tour joins in 5f-1c.
 */
fun onboardingNextPage(page: OnboardingPage, connected: Boolean, hasModels: Boolean): OnboardingPage? = when (page) {
    OnboardingPage.System -> OnboardingPage.Development
    OnboardingPage.Development -> OnboardingPage.Tools
    OnboardingPage.Tools -> OnboardingPage.Permissions
    OnboardingPage.Permissions -> OnboardingPage.Provider
    OnboardingPage.Provider -> if (connected) OnboardingPage.ModelInventory else OnboardingPage.Completion
    OnboardingPage.ProviderConnection -> OnboardingPage.ModelInventory.takeIf { connected }
    OnboardingPage.ModelInventory -> OnboardingPage.PrimaryScenes.takeIf { hasModels }
    OnboardingPage.PrimaryScenes -> OnboardingPage.MemoryScenes.takeIf { hasModels }
    else -> null
}

/** What onboarding needs to know about a stored Provider profile. */
@Immutable
data class OnboardingProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val sourceType: String,
    /** A built-in profile seeded with an endpoint but no key (`*-official`). */
    val builtIn: Boolean,
)

/**
 * The profile onboarding resumes with. Dart picked any profile with a Base
 * URL, and every install seeds keyless built-in profiles (DeepSeek, Kimi,
 * Mimo…), so a fresh install opened the Provider page "connected" to a
 * DeepSeek profile without a key whose model fetch could only fail (fixed
 * in 5f-1b). Only a profile the user can actually call counts: a key, or a
 * compatible endpoint of their own that needs none.
 */
fun resumableOnboardingProfile(profiles: List<OnboardingProfile>, editingId: String?): OnboardingProfile? {
    val usable = profiles.filter { it.baseUrl.isNotBlank() && (it.apiKey.isNotBlank() || !it.builtIn && it.sourceType == "custom") }
    return usable.firstOrNull { it.id == editingId } ?: usable.firstOrNull()
}

/**
 * Dart `_applyExistingProfile`: the option a stored profile belongs to — the
 * same source type, else the same endpoint, else the compatible API.
 * [sameEndpoint] is the app's canonical endpoint comparison.
 */
fun providerOptionFor(profile: OnboardingProfile, sameEndpoint: (String, String) -> Boolean): ProviderOption =
    PROVIDER_OPTIONS.firstOrNull { option ->
        option.id != "custom" && (
            option.sourceType != "custom" && option.sourceType == profile.sourceType ||
                option.baseUrl.isNotBlank() && sameEndpoint(option.baseUrl, profile.baseUrl)
            )
    } ?: PROVIDER_OPTIONS.last()

/**
 * Dart `configure`: the profile a connection overwrites — one of the same
 * source type for a vendor option, else one at the same endpoint.
 */
fun profileToOverwrite(
    option: ProviderOption,
    baseUrl: String,
    profiles: List<OnboardingProfile>,
    sameEndpoint: (String, String) -> Boolean,
): OnboardingProfile? = profiles.firstOrNull { profile ->
    if (option.sourceType != "custom") profile.sourceType == option.sourceType
    else profile.baseUrl.isNotBlank() && sameEndpoint(profile.baseUrl, baseUrl)
}

private val ENVIRONMENT_STAGE_EN = linkedMapOf(
    "开始准备内嵌终端环境" to "Starting the local terminal environment",
    "正在准备 workspace 和运行目录" to "Preparing the workspace and runtime directories",
    "正在初始化宿主终端运行时" to "Initializing the terminal runtime",
    "正在校验终端环境运行资源" to "Checking runtime resources",
    "正在安装终端环境运行资源" to "Installing runtime resources",
    "宿主终端环境校验完成" to "Runtime resources verified",
    "正在检查所选开发工具" to "Checking selected development tools",
    "正在安装所选开发工具" to "Installing selected development tools",
    "正在验证所选开发工具" to "Verifying selected development tools",
    "开发环境配置完成" to "Development environment ready",
    "所选开发工具已就绪" to "Selected development tools are ready",
    "终端环境准备已取消" to "Setup was cancelled; downloads so far are kept",
)

/**
 * Dart `_localizedStage`: the installer reports Chinese stage text; English
 * shows the known stages translated and anything else as is. Null asks the
 * page for its own "the terminal is ready, tools are next" line.
 */
fun localizedEnvironmentStage(stage: String, english: Boolean): String? {
    if ("基础 Agent CLI 包尚未完成预装" in stage) return null
    if (!english) return stage
    return ENVIRONMENT_STAGE_EN.entries.firstOrNull { it.key in stage }?.value ?: stage
}

/** [OnboardingFlow] as one string for `SavedStateHandle`: `page|history|visited`. */
fun OnboardingFlow.encode(): String =
    listOf(listOf(page), history, visited.toList()).joinToString("|") { pages -> pages.joinToString(",") { it.name } }

/** Inverse of [encode]; an unreadable value starts over rather than failing. */
fun decodeOnboardingFlow(value: String?): OnboardingFlow {
    val parts = value?.split('|') ?: return OnboardingFlow()
    if (parts.size != 3) return OnboardingFlow()
    fun pages(text: String): List<OnboardingPage>? {
        val names = text.split(',').filter { it.isNotEmpty() }
        val pages = names.mapNotNull { name -> OnboardingPage.entries.firstOrNull { it.name == name } }
        return pages.takeIf { it.size == names.size }
    }
    val page = pages(parts[0])?.singleOrNull() ?: return OnboardingFlow()
    val history = pages(parts[1]) ?: return OnboardingFlow()
    val visited = pages(parts[2])?.toSet() ?: return OnboardingFlow()
    return OnboardingFlow(page, history, visited + OnboardingPage.System)
}

/**
 * Scene picks after the model list changed: the defaults for [models], with
 * the scenes the user chose by hand ([picked]) kept. Dart re-derived every
 * scene and so dropped hand-made picks when a model was added; keeping all
 * current picks instead would leave a newly added embedding model unused.
 */
fun rebalancedSceneSelections(models: List<String>, current: Map<String, String>, picked: Set<String>): Map<String, String> =
    defaultSceneSelections(models) + current.filter { (scene, model) -> scene in picked && model in models }
