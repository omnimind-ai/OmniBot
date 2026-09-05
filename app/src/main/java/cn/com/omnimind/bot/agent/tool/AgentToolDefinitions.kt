package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.shizuku.ShizukuBackend
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object AgentToolDefinitions {

    private fun currentLocale(): PromptLocale = AppLocaleManager.currentPromptLocale()

    fun decorateToolDefinition(
        definition: JsonObject,
        locale: PromptLocale = currentLocale(),
        terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine
    ): JsonObject {
        val function = definition["function"] as? JsonObject ?: return definition
        val decorated = buildJsonObject {
            definition.forEach { (key, value) ->
                if (key != "function") {
                    put(key, value)
                }
            }
            put(
                "function",
                buildJsonObject {
                function.forEach { (key, value) ->
                    when (key) {
                        // `postToolRule` was an OmniBot-specific model
                        // scheduling protocol (for example, making a tool
                        // occupy a whole tool-call batch). ACP already owns
                        // prompt completion and lets the model issue further
                        // tool calls after it receives results, so this
                        // private field must not enter any runtime catalog.
                        "postToolRule" -> Unit

                        "description" -> put("description", value)

                            "parameters" -> put("parameters", value)

                            else -> put(key, value)
                        }
                    }
                }
            )
        }
        return resolveTerminalDistribution(
            localizeJsonObject(decorated, locale),
            terminalDistribution
        ) as JsonObject
    }

    private fun resolveTerminalDistribution(
        value: JsonElement,
        terminalDistribution: TerminalDistribution.Spec
    ): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.mapValues { (_, element) ->
                resolveTerminalDistribution(element, terminalDistribution)
            }
        )
        is JsonArray -> JsonArray(
            value.map { element ->
                resolveTerminalDistribution(element, terminalDistribution)
            }
        )
        is JsonPrimitive -> if (value.isString) {
            JsonPrimitive(AgentTerminalDistributionText.resolve(value.content, terminalDistribution))
        } else {
            value
        }
    }

    private fun localizeJsonObject(
        value: JsonObject,
        locale: PromptLocale
    ): JsonObject {
        if (locale == PromptLocale.ZH_CN) {
            return value
        }
        return JsonObject(
            value.mapValues { (_, element) ->
                localizeJsonElement(element, locale)
            }
        )
    }

    private fun localizeJsonElement(
        value: JsonElement,
        locale: PromptLocale
    ): JsonElement {
        if (locale == PromptLocale.ZH_CN) {
            return value
        }
        return when (value) {
            is JsonObject -> localizeJsonObject(value, locale)
            is JsonArray -> JsonArray(value.map { localizeJsonElement(it, locale) })
            is JsonPrimitive -> if (value.isString) {
                JsonPrimitive(localizeLeaf(value.content, locale))
            } else {
                value
            }
        }
    }

    private fun localizeLeaf(
        text: String,
        locale: PromptLocale
    ): String {
        if (locale == PromptLocale.ZH_CN || text.isBlank()) {
            return text
        }
        return englishStringMap[text] ?: text
    }

    private val englishStringMap: Map<String, String> = mapOf(
        "查询已安装应用" to "Query Installed Apps",
        "视觉执行" to "Vision Task",
        "操作 Android GUI" to "Operate Android GUI",
        "执行 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 命令" to "Run {{OMNIBOT_TERMINAL_DISTRIBUTION}} Command",
        "启动 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话" to "Start {{OMNIBOT_TERMINAL_DISTRIBUTION}} Session",
        "执行 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话命令" to "Run {{OMNIBOT_TERMINAL_DISTRIBUTION}} Session Command",
        "读取 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话输出" to "Read {{OMNIBOT_TERMINAL_DISTRIBUTION}} Session Output",
        "结束 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话" to "Stop {{OMNIBOT_TERMINAL_DISTRIBUTION}} Session",
        "浏览器操作" to "Browser Action",
        "读取文件" to "Read File",
        "写入文件" to "Write File",
        "编辑文件" to "Edit File",
        "列出文件" to "List Files",
        "搜索文件" to "Search Files",
        "查看文件信息" to "Inspect File",
        "移动文件" to "Move File",
        "列出 Skills" to "List Skills",
        "读取 Skill" to "Read Skill",
        "创建定时任务" to "Create Scheduled Task",
        "查看定时任务" to "List Scheduled Tasks",
        "修改定时任务" to "Update Scheduled Task",
        "删除定时任务" to "Delete Scheduled Task",
        "创建提醒闹钟" to "Create Reminder Alarm",
        "查看提醒闹钟" to "List Reminder Alarms",
        "删除提醒闹钟" to "Delete Reminder Alarm",
        "查看日历列表" to "List Calendars",
        "创建日程" to "Create Calendar Event",
        "查询日程" to "List Calendar Events",
        "修改日程" to "Update Calendar Event",
        "删除日程" to "Delete Calendar Event",
        "音乐播放控制" to "Music Playback Control",
        "检索记忆" to "Search Memory",
        "写入当日记忆" to "Write Daily Memory",
        "沉淀长期记忆" to "Upsert Long-Term Memory",
        "整理当日记忆" to "Roll Up Daily Memory",
        "分派子任务" to "Dispatch Subtasks",
        "查询设备已安装应用列表。需要应用包名或确认应用是否已安装时优先调用。" to
            "Query the list of apps installed on the device. Prefer this when you need an app package name or need to confirm whether an app is installed.",
        "通过 Android GUI 插件的 VLM 流程操作当前界面。只要用户要求操作手机或 App（例如下单咖啡、购物、联系人、设置、导航、打开应用），必须立即调用此工具，并把完整用户目标原样放入 goal；不要用 terminal/browser 代替，也不要直接回复已完成。若 OmniFlow 操作模块未启用，调用后会提示用户到插件市场手动启用。" to
            "Use this immediately whenever the user asks you to operate a phone or Android app (for example ordering coffee, shopping, contacts, settings, navigation, or opening an app). Pass the complete user goal in goal; do not use terminal/browser instead and do not claim completion in plain text. If the OmniFlow operation module is disabled, this call tells the user to enable it manually from Plugin Market.",
        "要在 Android GUI 中完成的具体目标。" to
            "The concrete goal to complete in the Android GUI.",
        "可选关键词，可匹配应用名或包名。" to
            "Optional keyword filter. Matches app names or package names.",
        "可选，返回数量上限。省略时返回执行环境提供的完整结果。" to
            "Optional maximum number of results to return. When omitted, returns the complete result provided by the execution environment.",
        "通过应用内置的 {{OMNIBOT_TERMINAL_DISTRIBUTION}}（proot）环境执行一次性的非交互命令。这是默认首选的 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 工具，适合文件处理、脚本、网络诊断、git、python、包管理等绝大多数 CLI 任务；不用于手机界面操作，也不用于交互式 TUI。只有明确需要跨多轮保留 cwd、环境或后台进程时，才改用 terminal_session_*。" to
            "Run a one-shot non-interactive command inside the app's built-in {{OMNIBOT_TERMINAL_DISTRIBUTION}} (proot) environment. This is the default {{OMNIBOT_TERMINAL_DISTRIBUTION}} tool for most CLI work such as file operations, scripts, network diagnostics, git, Python, and package management. It is not for phone UI actions or interactive TUIs. Only switch to `terminal_session_*` when you truly need to preserve cwd, environment, or background state across turns.",
        "terminal_execute 应单独占据当前 tool_calls。该工具会固定在 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 中以 executionMode=proot（prootDistro={{OMNIBOT_TERMINAL_DISTRIBUTION_ID}}）执行，传入其他 executionMode 或 distro 会被忽略。若执行失败，可在下一轮基于 stdout/stderr/errorMessage 自行决定是否再次显式调用 terminal_execute；不要在同一个 tool_calls 中串联其他结果依赖型工具。" to
            "`terminal_execute` should occupy the current `tool_calls` by itself. It always runs in {{OMNIBOT_TERMINAL_DISTRIBUTION}} with `executionMode=proot` and `prootDistro={{OMNIBOT_TERMINAL_DISTRIBUTION_ID}}`; other execution modes or distros are ignored. If execution fails, inspect stdout, stderr, or errorMessage in the next turn and decide whether to call it again explicitly. Do not chain other result-dependent tools in the same `tool_calls`.",
        "要执行的单次 shell 命令，必须非交互。" to
            "Single shell command to execute. It must be non-interactive.",
        "可选。兼容字段，当前固定在 proot {{OMNIBOT_TERMINAL_DISTRIBUTION}} 执行，传入 termux 也会被自动忽略。" to
            "Optional compatibility field. Execution is currently always in proot {{OMNIBOT_TERMINAL_DISTRIBUTION}}, and `termux` is ignored.",
        "可选。兼容字段，当前固定使用 {{OMNIBOT_TERMINAL_DISTRIBUTION_ID}}（{{OMNIBOT_TERMINAL_DISTRIBUTION}}），传入其他 distro 会被自动忽略。" to
            "Optional compatibility field. `{{OMNIBOT_TERMINAL_DISTRIBUTION_ID}}` ({{OMNIBOT_TERMINAL_DISTRIBUTION}}) is always used right now, and other distros are ignored.",
        "可选工作目录，建议使用绝对路径。" to
            "Optional working directory. Prefer an absolute path.",
        "可选：等待结果的超时时间（秒）。省略时由执行环境决定。" to
            "Optional timeout in seconds while waiting for the result. The execution environment decides when omitted.",
        "启动一个可复用的 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话，仅用于确实需要在后续多轮中保留 cwd、shell 环境、中间文件状态或后台进程的任务。返回的 sessionId 由底层 ReTerminal 原生生成并持久托管，后续必须显式传给 terminal_session_exec/read/stop。不要为了运行单条命令、检查工具是否存在、读取单个文件或执行一次性脚本而使用它，这些场景应优先用 terminal_execute。" to
            "Start a reusable {{OMNIBOT_TERMINAL_DISTRIBUTION}} session. Use it only when later turns truly need to preserve cwd, shell environment, intermediate file state, or background processes. The returned sessionId is generated and managed by the native ReTerminal layer and must be passed explicitly to `terminal_session_exec`, `terminal_session_read`, and `terminal_session_stop`. Do not use it for one-off commands, tool existence checks, reading a single file, or one-shot scripts; prefer `terminal_execute` for those.",
        "启动后等待 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话结果，再决定是否继续向该 session 发送命令。" to
            "Wait for the {{OMNIBOT_TERMINAL_DISTRIBUTION}} session result before deciding whether to send more commands.",
        "可选，会话名称。未传时自动生成。" to
            "Optional session name. Generated automatically when omitted.",
        "可选，会话初始工作目录。默认使用当前 workspace cwd。" to
            "Optional initial working directory for the session. Defaults to the current workspace cwd.",
        "向已有 {{OMNIBOT_TERMINAL_DISTRIBUTION}} session 发送一条非交互命令，并等待终端真实报告该命令完成。只在你明确想复用同一个 session 的 cwd、环境变量、后台任务或中间状态时使用。长时间运行的命令保持在当前 ACP tool call 中，用户需要停止时取消当前 ACP turn 或调用 terminal_session_stop。" to
            "Send a non-interactive command to an existing {{OMNIBOT_TERMINAL_DISTRIBUTION}} session and wait for the terminal to report completion. Use this only when you explicitly want to reuse the same session's cwd, environment variables, background jobs, or intermediate state. Long-running commands remain in the current ACP tool call; cancel the current ACP turn or call `terminal_session_stop` when the user wants to stop.",
        "执行后等待结果，再判断是否继续读取日志、再次执行或结束 session。" to
            "Wait for the result after execution, then decide whether to read logs, run another command, or stop the session.",
        "terminal_session_start 返回的 sessionId。" to
            "The sessionId returned by `terminal_session_start`.",
        "要执行的单次非交互 shell 命令。" to
            "Single non-interactive shell command to execute.",
        "可选，本次命令执行前要切换到的目录。" to
            "Optional directory to switch into before running this command.",
        "可选：等待该命令完成的超时时间（秒）。省略时由执行环境决定。" to
            "Optional timeout in seconds while waiting for this command to finish. The execution environment decides when omitted.",
        "读取 {{OMNIBOT_TERMINAL_DISTRIBUTION}} session 最近一次命令日志或最近的 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 输出。默认应把它视为读取该 session 最新尾部输出，而不是重新查看最早的历史。只在已经启动并复用了 terminal_session_* 的前提下使用。" to
            "Read the latest command log or most recent {{OMNIBOT_TERMINAL_DISTRIBUTION}} output from a {{OMNIBOT_TERMINAL_DISTRIBUTION}} session. Treat it as reading the newest tail output for that session, not replaying the oldest history. Use it only after you have already started and are reusing `terminal_session_*`.",
        "读取结果后再决定是否继续执行命令。" to
            "After reading the result, decide whether to run more commands.",
        "可选：最多返回多少字符。省略时返回执行环境允许的内容。" to
            "Optional maximum number of characters to return. When omitted, return what the execution environment permits.",
        "停止已有 {{OMNIBOT_TERMINAL_DISTRIBUTION}} session，并清理对应 tmux 会话。完成状态化 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 任务后再调用。" to
            "Stop an existing {{OMNIBOT_TERMINAL_DISTRIBUTION}} session and clean up the corresponding tmux session. Call this after the stateful {{OMNIBOT_TERMINAL_DISTRIBUTION}} task is complete.",
        "{{OMNIBOT_TERMINAL_DISTRIBUTION}} session id。" to
            "{{OMNIBOT_TERMINAL_DISTRIBUTION}} session id.",
        "结束后等待工具结果，再回复用户。" to
            "Wait for the tool result after stopping the session before replying to the user.",
        "控制离屏浏览器。不要用它打开 App deep link、omnibot:// 非 browser 资源或应用内路由。浏览器只支持访问 http(s) 页面，以及 omnibot://browser/... 资源文件。使用 navigate 打开页面，screenshot 查看当前视口截图（传 read_image=true 可让模型直接看到截图内容），click/type/hover 与元素交互，get_text/get_readable 抽取内容，scroll 导航长页面，scroll_and_collect 在一次调用中滚动并收集无限列表内容，find_elements 发现可交互元素，get_page_info 获取页面元信息，get_backbone 获取 DOM 骨架，execute_js 执行脚本，fetch 复用当前页面 session 下载资源并返回 omnibot://browser/... 产物，new_tab/close_tab/list_tabs 管理标签页，go_back/go_forward 浏览器前进后退，press_key 模拟键盘按键，wait_for_selector 等待元素出现，get_cookies 返回 cookie 摘要与可复用的 offload env 脚本路径，set_user_agent 兼容 desktop_safari/mobile_safari 入参但实际切换 Android Chrome 风格桌面/移动 UA。结果可能包含 riskChallengeDetected、riskChallengeKind、recommendedNextAction、throttleDelayMs；若 riskChallengeDetected=true，应停止自动交互/刷新并请用户手动接管。" to
            "Control an off-screen browser. Do not use it for app deep links, non-browser `omnibot://` resources, or in-app routes. The browser supports http(s) pages and `omnibot://browser/...` resources. Use navigate to open pages, screenshot to capture the current viewport (set read_image=true if the model should inspect the screenshot directly), click/type/hover for interaction, get_text/get_readable for extraction, scroll for long-page navigation, scroll_and_collect to collect infinite-list content in one call, find_elements to discover interactable elements, get_page_info for metadata, get_backbone for a DOM skeleton, execute_js for scripting, fetch to download resources with the current page session and return `omnibot://browser/...` artifacts, new_tab/close_tab/list_tabs for tab management, go_back/go_forward for navigation history, press_key to simulate keys, wait_for_selector to wait for elements, get_cookies for cookie summaries plus a reusable offload env script path, and set_user_agent to accept desktop_safari/mobile_safari for compatibility while actually switching Android Chrome-style desktop/mobile UAs. Results may include riskChallengeDetected, riskChallengeKind, recommendedNextAction, and throttleDelayMs; when riskChallengeDetected=true, stop automated interaction/reload attempts and ask the user to take over manually.",
        "本次工具调用要做什么的简洁摘要，5-10 个字，展示给用户。" to
            "A concise summary of what this tool call is doing. Keep it to about 5-10 words and show it to the user.",
        "浏览器动作。" to "Browser action.",
        "navigate 打开的 URL，或 fetch 下载的资源 URL。" to
            "URL to open with navigate, or the resource URL to download with fetch.",
        "CSS selector。适用于 click/type/get_text/scroll/hover/find_elements。" to
            "CSS selector. Used by click, type, get_text, scroll, hover, and find_elements.",
        "type 动作要输入的文本。" to "Text to input for the type action.",
        "execute_js 动作要执行的 JavaScript 代码。" to
            "JavaScript code to execute for the execute_js action.",
        "点击或输入目标的 X 坐标，可替代 selector。" to
            "X coordinate of the click or input target. Can be used instead of selector.",
        "点击或输入目标的 Y 坐标，可替代 selector。" to
            "Y coordinate of the click or input target. Can be used instead of selector.",
        "scroll 或 scroll_and_collect 使用的滚动像素量；需要时显式提供。" to
            "Scroll amount for scroll or scroll_and_collect; provide it explicitly when needed.",
        "滚动方向。" to "Scroll direction.",
        "目标标签页 ID；不传时默认使用最近活跃标签页。" to
            "Target tab ID. Uses the most recently active tab by default.",
        "scroll_and_collect 的内容项 selector；不传时自动探测。" to
            "Item selector for scroll_and_collect. Auto-detected when omitted.",
        "scroll_and_collect 的滚动次数；需要时显式提供。" to
            "Number of scrolls for scroll_and_collect; provide it explicitly when needed.",
        "get_backbone 的最大深度；需要时显式提供。" to
            "Maximum depth for get_backbone; provide it explicitly when needed.",
        "要切换到的 UA profile；枚举名保持兼容，实际使用 Android Chrome 风格桌面/移动 UA。" to
            "UA profile to switch to. Enum names remain compatible; the actual user agents are Android Chrome-style desktop/mobile UAs.",
        "get_cookies 的 cookie 名过滤关键词。可传空格分隔字符串，兼容数组字符串输入。fuzzy=true 时要求所有关键词都包含在 cookie 名中；fuzzy=false 时要求精确命中任一 cookie 名。" to
            "Keyword filter for cookie names in get_cookies. Accepts a space-separated string and also tolerates array-like string input. When fuzzy=true, every keyword must appear in the cookie name; when fuzzy=false, an exact match of any cookie name is required.",
        "get_cookies 的关键词匹配模式，默认 true。" to
            "Keyword matching mode for get_cookies. Default true.",
        "仅 screenshot 时生效。设为 true 时，截图会以 base64 图片嵌入工具结果，供模型直接分析页面内容。默认 false。" to
            "Only applies to screenshot. When true, the screenshot is embedded as a base64 image in the tool result so the model can analyze the page content directly. Default false.",
        "press_key 动作要模拟的按键名，例如 Enter、Escape、Tab、ArrowDown。" to
            "Key name to simulate for the press_key action, such as Enter, Escape, Tab, or ArrowDown.",
        "wait_for_selector 的可选超时毫秒数；省略时由浏览器执行环境决定。" to
            "Optional timeout in milliseconds for wait_for_selector. The browser execution environment decides when omitted.",
        "读取 workspace 或 Omnibot 白名单目录中的文件内容。" to
            "Read file contents from the workspace or Omnibot allowlisted directories.",
        "文件路径，可使用相对 workspace 路径或 omnibot:// uri。" to
            "File path. May use a workspace-relative path or an `omnibot://` URI.",
        "可选：最多读取字符数。省略时返回执行环境允许的内容。" to
            "Optional maximum number of characters to read. When omitted, return what the execution environment permits.",
        "可选，从指定字符偏移开始读取。" to
            "Optional character offset to start reading from.",
        "可选，从第几行开始读取，1-based。" to
            "Optional starting line number, 1-based.",
        "可选，读取多少行。" to "Optional number of lines to read.",
        "创建或覆盖 workspace 内文件。新建文件优先使用此工具。" to
            "Create or overwrite a file inside the workspace. Prefer this tool for new files.",
        "写入后等待结果，再决定是否继续读取或修改。" to
            "Wait for the result after writing, then decide whether to keep reading or editing.",
        "目标文件路径。" to "Target file path.",
        "要写入的完整文本内容。" to "Full text content to write.",
        "是否追加写入，默认 false。" to "Whether to append instead of overwrite. Default false.",
        "对已有文件做精确字符串替换。修改现有文件优先使用此工具。" to
            "Perform exact string replacement inside an existing file. Prefer this tool when modifying existing files.",
        "编辑后等待结果，再判断是否继续读取验证。" to
            "Wait for the result after editing, then decide whether to read again for verification.",
        "要替换的原始文本。" to "Original text to replace.",
        "替换后的文本。" to "Replacement text.",
        "是否替换全部匹配，默认 false。" to "Whether to replace all matches. Default false.",
        "列出某个目录下的文件和子目录。" to
            "List files and subdirectories under a directory.",
        "目录路径。默认当前 workspace。" to
            "Directory path. Defaults to the current workspace.",
        "是否递归列出。默认 false。" to "Whether to list recursively. Default false.",
        "可选，递归时最大深度。省略时由执行环境决定。" to
            "Optional maximum recursion depth. The execution environment decides when omitted.",
        "可选，最多返回多少项。省略时返回执行环境提供的完整结果。" to
            "Optional maximum number of items to return. When omitted, returns the complete result provided by the execution environment.",
        "在目录中递归搜索文件名或文本内容。" to
            "Recursively search file names or text contents in a directory.",
        "搜索起始目录，默认当前 workspace。" to
            "Search root directory. Defaults to the current workspace.",
        "要搜索的关键词。" to "Keyword to search for.",
        "是否区分大小写，默认 false。" to "Whether the search is case-sensitive. Default false.",
        "查看文件或目录的元信息。" to
            "Inspect metadata for a file or directory.",
        "目标路径。" to "Target path.",
        "移动或重命名 workspace 中的文件。" to
            "Move or rename a file inside the workspace.",
        "移动后等待结果，再决定是否继续读取。" to
            "Wait for the result after moving, then decide whether to continue reading.",
        "源路径。" to "Source path.",
        "是否覆盖目标文件，默认 false。" to
            "Whether to overwrite the destination file. Default false.",
        "列出当前可用的 skills 索引，包括 id、名称、路径和能力目录。用户询问有哪些 skills、某类 skill 是否已安装，或你想先查目录再决定读取 SKILL.md 时优先调用。" to
            "List the currently available skills index, including each skill's id, name, path, and capability directories. Prefer this when the user asks what skills are installed, whether a category of skill exists, or when you want to inspect the catalog before deciding whether to read a SKILL.md file.",
        "可选关键词，匹配 skill id、名称、描述或路径。" to
            "Optional keyword filter matching skill id, name, description, or path.",
        "按 skill id、名称或路径读取某个已安装 skill 的 SKILL.md 正文和相关目录信息。当你知道某个 skill 可能相关，但本轮只掌握索引信息时调用。" to
            "Read the SKILL.md body and related directory information for an installed skill by skill id, name, or path. Use this when a skill looks relevant but you currently know only its index metadata.",
        "读取 skill 后等待结果，再根据返回的正文、scripts、references、assets 路径决定下一步。" to
            "Wait for the result after reading the skill, then decide the next step based on the returned body plus any scripts, references, or asset paths.",
        "skill 的 id、名称、SKILL.md 路径或 skill 根目录路径。建议先用 skills_list 查看。" to
            "Skill id, skill name, SKILL.md path, or the skill root directory path. Prefer checking with skills_list first.",
        "可选：最多返回多少字符的正文。省略时返回执行环境允许的内容。" to
            "Optional maximum number of body characters to return. When omitted, return what the execution environment permits.",
        "创建新的定时任务。执行后等待工具结果，再决定是否回复用户。" to
            "Create a new scheduled task. Wait for the tool result before deciding how to reply to the user.",
        "创建完成后不要在同一轮继续调用其他工具；请等待工具结果，并通过 response 输出最终答复。" to
            "After creating the task, do not call more tools in the same turn. Wait for the tool result and then provide the final response.",
        "查看当前已有的定时任务列表。执行后等待工具结果。" to
            "View the current list of scheduled tasks. Wait for the tool result after calling it.",
        "查看结果后再决定是否需要修改、删除或向用户总结。" to
            "Review the result first, then decide whether to update, delete, or summarize for the user.",
        "修改已有定时任务的时间、标题、每日重复或启停状态。" to
            "Update the time, title, daily repeat rule, or enabled state of an existing scheduled task.",
        "修改完成后不要同轮回复，等待工具结果。" to
            "Do not reply in the same turn after updating. Wait for the tool result.",
        "删除已有定时任务。执行后等待工具结果。" to
            "Delete an existing scheduled task. Wait for the tool result after calling it.",
        "删除完成后等待工具结果，再输出最终回复。" to
            "Wait for the tool result after deleting, then produce the final reply.",
        "创建提醒闹钟。exact_alarm 模式使用 AlarmManager 精确提醒；clock_app 模式调用系统闹钟应用创建闹钟；若用户未明确指定，优先使用 exact_alarm。用于单纯提醒，不执行自动化任务。" to
            "Create a reminder alarm. The exact_alarm mode uses AlarmManager for precise reminders, while clock_app uses the system clock app to create an alarm. If the user does not specify a mode, prefer exact_alarm. This is for reminders only and does not execute automation tasks.",
        "创建后等待工具结果，再决定是否继续。" to
            "Wait for the tool result after creation before deciding what to do next.",
        "闹钟模式：exact_alarm=应用内精确提醒；clock_app=系统闹钟。" to
            "Alarm mode: exact_alarm = precise in-app reminder; clock_app = system alarm.",
        "提醒标题。" to "Reminder title.",
        "触发时间，ISO-8601 格式，例如 2026-03-17T21:30:00+08:00。" to
            "Trigger time in ISO-8601 format, for example 2026-03-17T21:30:00+08:00.",
        "可选提醒内容。" to "Optional reminder content.",
        "可选 IANA 时区，未传默认系统时区。" to
            "Optional IANA timezone. Uses the system timezone when omitted.",
        "仅 exact_alarm 模式生效，是否在待机时也精确触发。默认 true。" to
            "Only applies in exact_alarm mode. Whether the reminder should remain precise while idle. Default true.",
        "仅 clock_app 模式生效，是否尝试跳过系统闹钟界面。默认 false。" to
            "Only applies in clock_app mode. Whether to try skipping the system alarm UI. Default false.",
        "查看由本应用创建并托管的 exact_alarm 提醒闹钟列表。" to
            "List exact_alarm reminders created and managed by this app.",
        "查看结果后再决定是否删除或继续创建。" to
            "Review the result before deciding whether to delete or create more reminders.",
        "按 alarmId 删除本应用创建并托管的 exact_alarm 提醒闹钟。" to
            "Delete an exact_alarm reminder created and managed by this app by alarmId.",
        "删除后等待工具结果，再向用户确认。" to
            "Wait for the tool result after deleting, then confirm with the user.",
        "闹钟 ID。" to "Alarm ID.",
        "查询设备日历账户列表，可用于选择 calendarId。" to
            "Query the device's calendar accounts so the agent can choose a calendarId.",
        "查看结果后再决定新建或管理日程。" to
            "Review the result before deciding whether to create or manage events.",
        "是否仅返回可写日历。默认 true。" to
            "Whether to return only writable calendars. Default true.",
        "是否仅返回可见日历。默认 true。" to
            "Whether to return only visible calendars. Default true.",
        "创建日历事件。用于管理日程，不触发自动化任务。" to
            "Create a calendar event. This manages schedules and does not trigger automation tasks.",
        "创建后等待工具结果，再向用户确认。" to
            "Wait for the tool result after creating, then confirm with the user.",
        "开始时间，ISO-8601。" to "Start time in ISO-8601 format.",
        "结束时间，ISO-8601。" to "End time in ISO-8601 format.",
        "可选，目标日历 ID。" to "Optional target calendar ID.",
        "提醒分钟列表，例如 [10, 30]。" to
            "Reminder minute offsets, for example [10, 30].",
        "按时间范围、关键字、calendarId 查询日历事件。" to
            "Query calendar events by time range, keyword, and calendarId.",
        "查看结果后再决定是否更新或删除。" to
            "Review the result first, then decide whether to update or delete.",
        "可选，查询起始时间，ISO-8601。" to "Optional query start time in ISO-8601 format.",
        "可选，查询结束时间，ISO-8601。" to "Optional query end time in ISO-8601 format.",
        "可选关键词，匹配标题或地点。" to
            "Optional keyword matching title or location.",
        "可选返回上限；省略时返回该时间范围内的完整结果。" to
            "Optional maximum number of results to return. When omitted, return the complete result in the requested time range.",
        "按 eventId 修改日历事件。" to "Update a calendar event by eventId.",
        "修改后等待工具结果，再向用户同步。" to
            "Wait for the tool result after updating, then sync the result back to the user.",
        "事件 ID。" to "Event ID.",
        "按 eventId 删除日历事件。" to "Delete a calendar event by eventId.",
        "控制安卓系统级音乐播放。action=play 且提供 source 时，会由应用前台媒体会话播放本地文件、omnibot workspace/public 文件、file/content Uri 或 http(s) 直链音频；play 不提供 source 时，退化为向系统当前播放器发送播放媒体键。pause/resume/stop/next/previous 会优先控制当前由本应用托管的音频播放，若没有本地会话则退化为发送系统媒体键；seek 和 status 仅针对本应用托管的播放会话。" to
            "Control Android system-level music playback. When action=play and source is provided, the app's foreground media session plays local files, Omnibot workspace/public files, file/content URIs, or direct http(s) audio links. If play is called without a source, it falls back to sending a play media key to the current system player. pause/resume/stop/next/previous prefer the playback session hosted by this app and fall back to system media keys when no local session exists. seek and status only apply to playback sessions hosted by this app.",
        "执行后等待工具结果，再决定是否继续调整播放。" to
            "Wait for the tool result after execution before deciding whether to keep adjusting playback.",
        "要执行的播放控制动作。" to "Playback control action to perform.",
        "仅 play 时可选。支持 omnibot://、/workspace、/storage、相对 workspace 路径、file://、content://、http(s) 直链。留空表示只向系统发送播放媒体键。" to
            "Optional for play only. Supports omnibot://, /workspace, /storage, workspace-relative paths, file://, content://, and direct http(s) links. Leave empty to send only the play media key to the system.",
        "仅 play 时可选，前台通知与系统媒体会话里显示的标题。" to
            "Optional for play only. Title shown in the foreground notification and the system media session.",
        "仅 play 时可选，是否循环播放。默认 false。" to
            "Optional for play only. Whether to loop playback. Default false.",
        "仅 seek 时使用，目标播放秒数。" to
            "Used only for seek. Target playback position in seconds.",
        "在 workspace 记忆中检索与当前问题相关的长期/短期记忆。优先使用向量召回，配置缺失时自动降级词法检索。" to
            "Search long-term and short-term workspace memory relevant to the current question. Prefer vector retrieval and automatically fall back to lexical retrieval when configuration is missing.",
        "读取结果后再决定是否写入新的短期或长期记忆。" to
            "Review the result first, then decide whether to write new short-term or long-term memory.",
        "检索语句。" to "Search query.",
        "可选，返回条数上限。省略时返回执行环境提供的完整结果。" to
            "Optional maximum number of hits to return. When omitted, returns the complete result provided by the execution environment.",
        "将当轮过程性信息写入 `.omnibot/memory/short-memories/YY-MM-DD.md`。" to
            "Write short-term process information from this turn into `.omnibot/memory/short-memories/YY-MM-DD.md`.",
        "写入成功后再继续执行其他步骤。" to
            "Continue with later steps only after the write succeeds.",
        "要写入的短期记忆文本。" to "Short-term memory text to write.",
        "将稳定偏好、长期约束、身份事实写入 `.omnibot/memory/MEMORY.md`。" to
            "Write stable preferences, long-term constraints, and identity facts into `.omnibot/memory/MEMORY.md`.",
        "要沉淀的长期记忆内容。" to "Long-term memory content to preserve.",
        "写入后等待工具结果，再向用户确认。" to
            "Wait for the tool result after writing, then confirm with the user.",
        "整理某一天短期记忆并按策略沉淀到长期记忆。默认整理今天。" to
            "Roll up one day's short-term memory and promote selected items into long-term memory according to policy. Defaults to today.",
        "整理后等待工具结果，再决定是否补充长期记忆。" to
            "Wait for the tool result after the rollup, then decide whether to add more long-term memory.",
        "可选日期，格式 YYYY-MM-DD。" to "Optional date in YYYY-MM-DD format.",
        "仅当用户明确要求分派或并行时，把多个相互独立的小任务交给具有隔离上下文的 subagent，并返回聚合结果。" to
            "Only when the user explicitly asks to delegate or parallelize, dispatch independent subtasks to subagents with isolated contexts and return the aggregated result.",
        "分派后等待工具结果，再汇总给用户。" to
            "Wait for the tool result after dispatching, then summarize it for the user.",
        "要分派的子任务列表。每项都要包含自足的 instruction；profileId 为可选任务提示。" to
            "List of subtasks to delegate. Each item must contain a self-contained instruction; profileId is optional task guidance.",
        "子任务的完整、自足指令，不要依赖主会话中未写入此处的上下文。" to
            "Complete, self-contained instructions for the subtask. Do not rely on main-conversation context that is not included here.",
        "可选任务提示：general、explorer、memory-curator 或 planner。所有 profile 都继承当前 harness 已提供的能力。" to
            "Optional task guidance: general, explorer, memory-curator, or planner. Every profile inherits the capabilities provided by the current harness.",
        "子任务省略 profileId 时使用的可选任务提示，默认 general。" to
            "Optional task guidance used when a subtask omits profileId. Defaults to general.",
        "并发度，可选；省略时按本次子任务数并行执行。" to
            "Optional concurrency. When omitted, all subtasks in this request run in parallel.",
        "结果聚合要求，可选。" to "Optional instructions for result aggregation.",
        "创建新的定时任务。`targetKind=subagent` 为唯一支持的执行类型；`subagentPrompt` 必须写成任务触发时要立即执行的动作，不要重复填写“每天几点提醒我/定时去做”这类调度描述。" to
            "Create a new scheduled task. `targetKind=subagent` is the only supported execution type; `subagentPrompt` must describe the concrete action to execute at trigger time instead of repeating scheduling phrasing such as daily at a given time or remind me to do it.",
        "修改已有定时任务的时间、标题、每日重复或启停状态。`targetKind=subagent` 为唯一支持的执行类型；更新后的 `subagentPrompt` 仍应描述触发时真正执行的动作，而不是再次描述调度本身。" to
            "Update an existing scheduled task's time, title, daily repeat, or enabled state. `targetKind=subagent` is the only supported execution type; the updated `subagentPrompt` should still describe the real action to execute at trigger time rather than restating the schedule itself.",
        "subagent 被触发时要立即执行的任务说明。不要把“每天/几点/定时/提醒/闹钟/创建任务”等调度话术写进去，而要写成到点后此刻真正要完成的动作。" to
            "The task instructions that the subagent should execute immediately when triggered. Do not include scheduling phrases such as daily, at a specific time, scheduled, remind me, alarm, or create a task. Describe the real action that should be carried out at execution time."
    )

    val toolSearchTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "tools_search")
            put("displayName", "查找可用工具")
            put("toolType", "builtin")
            put(
                "description",
                "按用户目标搜索当前 Agent 可用的内置工具、插件工具和 MCP 工具。工具不在当前列表时，先调用此工具；读取返回的工具名称和说明后，再调用具体工具。不要猜测工具名。"
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "要完成的目标或要查找的能力，例如读取文件、设置闹钟、联网搜索、操作手机或查询某个 MCP。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选：限制返回工具数量；不填写则返回全部匹配工具。")
                    }
                }
                putJsonArray("required") {
                    add("query")
                }
                put("additionalProperties", false)
            }
        }
    }

    val contextTimeNowTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "context_time_now")
            put("displayName", "查询当前时间")
            put("toolType", "builtin")
            put("description", "返回设备当前的精确本地时间、UTC 时间、时区、星期和毫秒时间戳。询问‘现在几点’或需要精确时分秒时调用。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
    }

    val contextAppsQueryTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "context_apps_query")
            put("displayName", "查询已安装应用")
            put("toolType", "builtin")
            put("description", "查询设备已安装应用列表。需要应用包名或确认应用是否已安装时优先调用。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "可选关键词，可匹配应用名或包名；查询多个应用时用空格或逗号分隔，结果按关键词输入顺序返回。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选，返回数量上限。省略时返回执行环境提供的完整结果。")
                    }
                }
            }
        }
    }

    val vlmTaskTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "vlm_task")
            put("displayName", "操作 Android GUI")
            put("toolType", "builtin")
            put(
                "description",
                "手机或 Android App 操作必须使用此工具：例如下单咖啡、购物、联系人、设置、导航、打开应用等。把用户完整目标放入 goal；不要用 terminal/browser 代替。若 OmniFlow 操作模块未启用，调用后会提示用户手动启用，不会执行手机操作。"
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("goal") {
                        put("type", "string")
                        put("description", "要在 Android GUI 中完成的具体目标。")
                    }
                }
                putJsonArray("required") {
                    add("goal")
                }
                put("additionalProperties", false)
            }
        }
    }

    val terminalExecuteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_execute")
            put("displayName", "执行 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 命令")
            put("toolType", "terminal")
            put(
                "description",
                "通过应用内置的 {{OMNIBOT_TERMINAL_DISTRIBUTION}}（proot）环境执行一次性的非交互命令。这是默认首选的 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 工具，适合文件处理、脚本、网络诊断、git、python、包管理等绝大多数 CLI 任务；不用于手机界面操作，也不用于交互式 TUI。只有明确需要跨多轮保留 cwd、环境或后台进程时，才改用 terminal_session_*。"
            )
            put(
                "postToolRule",
                "terminal_execute 应单独占据当前 tool_calls。该工具会固定在 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 中以 executionMode=proot（prootDistro={{OMNIBOT_TERMINAL_DISTRIBUTION_ID}}）执行，传入其他 executionMode 或 distro 会被忽略。若执行失败，可在下一轮基于 stdout/stderr/errorMessage 自行决定是否再次显式调用 terminal_execute；不要在同一个 tool_calls 中串联其他结果依赖型工具。"
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "要执行的单次 shell 命令，必须非交互。")
                    }
                    putJsonObject("executionMode") {
                        put("type", "string")
                        put("description", "可选。兼容字段，当前固定在 proot {{OMNIBOT_TERMINAL_DISTRIBUTION}} 执行，传入 termux 也会被自动忽略。")
                        putJsonArray("enum") {
                            add("proot")
                            add("termux")
                        }
                    }
                    putJsonObject("prootDistro") {
                        put("type", "string")
                        put("description", "可选。兼容字段，当前固定使用 {{OMNIBOT_TERMINAL_DISTRIBUTION_ID}}（{{OMNIBOT_TERMINAL_DISTRIBUTION}}），传入其他 distro 会被自动忽略。")
                    }
                    putJsonObject("workingDirectory") {
                        put("type", "string")
                        put("description", "可选工作目录，建议使用绝对路径。")
                    }
                    putJsonObject("timeoutSeconds") {
                        put("type", "integer")
                        put("description", "可选：等待结果的超时时间（秒）。省略时由执行环境决定。")
                    }
                }
                putJsonArray("required") {
                    add("command")
                }
            }
        }
    }

    fun androidPrivilegedActionTool(
        visibleActions: List<String>,
        backend: ShizukuBackend,
        locale: PromptLocale = currentLocale()
    ): JsonObject {
        val text: (String, String) -> String = { zh, en ->
            if (locale == PromptLocale.ZH_CN) zh else en
        }
        val backendLabel = when (backend) {
            ShizukuBackend.ROOT -> text("root/Sui", "root/Sui")
            ShizukuBackend.ADB -> text("adb shell", "adb shell")
            ShizukuBackend.NONE -> text("未授权", "not granted")
        }
        val actionList = visibleActions.joinToString(", ")

        return decorateToolDefinition(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "android_privileged_action")
                put("displayName", text("安卓高级动作", "Android Privileged Action"))
                put("toolType", "privileged")
                put(
                    "description",
                    text(
                        "通过 Shizuku 执行安卓高权限动作。这条能力链路独立于 `terminal_execute`：既保留受控 typed action，也支持 `action=shell.exec` 的一次性任意 shell。若确实需要保留 cwd、环境变量或 shell 状态，请改用 `android_privileged_session_*`。当前后端：$backendLabel。当前可见 action：$actionList。需要授权时由 ACP 客户端统一请求确认。",
                        "Run Android privileged actions through Shizuku. This path stays separate from `terminal_execute`: it keeps the typed allowlisted actions and also supports one-shot arbitrary shell via `action=shell.exec`. When you truly need persistent cwd, environment, or shell state, switch to `android_privileged_session_*`. Current backend: $backendLabel. Currently visible actions: $actionList. Permission is requested through the standard ACP client boundary when needed."
                    )
                )
                put(
                    "postToolRule",
                    text(
                        "调用后先等待工具结果；如果返回需要确认，不要自行假设用户同意。",
                        "Wait for the tool result before deciding the next step. If it asks for confirmation, do not assume user consent."
                    )
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            put(
                                "description",
                                text(
                                    "要执行的受控高级动作标识。",
                                    "The controlled privileged action to run."
                                )
                            )
                            putJsonArray("enum") {
                                visibleActions.forEach { add(it) }
                            }
                        }
                        putJsonObject("arguments") {
                            put("type", "object")
                            // Keep the nested action arguments explicit. The
                            // handler already consumes this stable contract;
                            // leaving the object without properties forces the
                            // model to guess that launch_activity needs
                            // packageName/activityName rather than command.
                            putJsonObject("properties") {
                                putJsonObject("packageName") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        text("目标应用包名，例如 com.example.app。", "Target application package name, for example com.example.app.")
                                    )
                                }
                                putJsonObject("activityName") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        text("可选的完整 Activity 组件名；不传则启动应用的 Launcher。", "Optional fully-qualified Activity component; omit it to launch the app's Launcher activity.")
                                    )
                                }
                                putJsonObject("permission") {
                                    put("type", "string")
                                    put("description", text("要授予或撤销的 Android 权限名。", "Android permission name to grant or revoke."))
                                }
                                putJsonObject("mode") {
                                    put("type", "string")
                                    put("description", text("AppOps 模式。", "AppOps mode."))
                                }
                                putJsonObject("op") {
                                    put("type", "string")
                                    put("description", text("AppOps 操作名。", "AppOps operation name."))
                                }
                                putJsonObject("namespace") {
                                    put("type", "string")
                                    put("description", text("允许的 Android settings 命名空间。", "Allowed Android settings namespace."))
                                }
                                putJsonObject("key") {
                                    put("type", "string")
                                    put("description", text("settings key 或设备按键名。", "Settings key or device key name."))
                                }
                                putJsonObject("value") {
                                    put("type", "string")
                                    put("description", text("要写入 settings 的值。", "Value to write to settings."))
                                }
                                putJsonObject("enabled") {
                                    put("type", "boolean")
                                    put("description", text("是否启用该设备功能。", "Whether to enable the device feature."))
                                }
                                putJsonObject("text") {
                                    put("type", "string")
                                    put("description", text("要输入到设备的文本。", "Text to input on the device."))
                                }
                                putJsonObject("name") {
                                    put("type", "string")
                                    put("description", text("要读取的属性名。", "Property name to read."))
                                }
                                putJsonObject("prop") {
                                    put("type", "string")
                                    put("description", text("兼容的属性名字段。", "Compatibility property-name field."))
                                }
                                putJsonObject("service") {
                                    put("type", "string")
                                    put("description", text("要读取的 dumpsys 服务名。", "dumpsys service name to read."))
                                }
                                putJsonObject("buffer") {
                                    put("type", "string")
                                    put("description", text("logcat 缓冲区，默认 main。", "logcat buffer, default main."))
                                }
                                putJsonObject("lines") {
                                    put("type", "integer")
                                    put("description", text("返回的日志行数。", "Number of log lines to return."))
                                }
                                putJsonObject("command") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        text("仅当 action=shell.exec 时填写；launch_activity 不使用 command。", "Use only when action=shell.exec; launch_activity does not use command.")
                                    )
                                }
                                putJsonObject("timeoutSeconds") {
                                    put("type", "integer")
                        put("description", text("可选：shell.exec 超时秒数。省略时由执行环境决定。", "Optional shell.exec timeout in seconds. The execution environment decides when omitted."))
                                }
                                putJsonObject("workingDirectory") {
                                    put("type", "string")
                                    put("description", text("shell.exec 的工作目录。", "Working directory for shell.exec."))
                                }
                                putJsonObject("environment") {
                                    put("type", "object")
                                    put("description", text("shell.exec 的环境变量。", "Environment variables for shell.exec."))
                                    putJsonObject("additionalProperties") {
                                        put("type", "string")
                                    }
                                }
                                putJsonObject("confirmed") {
                                    put("type", "boolean")
                                    put("description", text("仅用于已完成用户确认的高风险动作。", "Use only after the user has confirmed a high-risk action."))
                                }
                            }
                            put("additionalProperties", false)
                            put(
                                "description",
                                text(
                                    "动作参数对象。typed action 只传该 action 需要的字段；当 `action=shell.exec` 时，在这里传入 `command`、可选 `timeoutSeconds`、`workingDirectory`、`environment`。需要授权时由 ACP 客户端统一请求确认。",
                                    "Arguments object for the selected action. For typed actions, only include the fields that action needs. When `action=shell.exec`, provide `command`, optional `timeoutSeconds`, `workingDirectory`, and `environment`. Permission is requested through the standard ACP client boundary when needed."
                                )
                            )
                        }
                    }
                    putJsonArray("required") {
                        add("action")
                        add("arguments")
                    }
                }
            }
        }, locale)
    }

    fun androidPrivilegedSessionStartTool(
        backend: ShizukuBackend,
        locale: PromptLocale = currentLocale()
    ): JsonObject {
        val text: (String, String) -> String = { zh, en ->
            if (locale == PromptLocale.ZH_CN) zh else en
        }
        val backendLabel = when (backend) {
            ShizukuBackend.ROOT -> text("root/Sui", "root/Sui")
            ShizukuBackend.ADB -> text("adb shell", "adb shell")
            ShizukuBackend.NONE -> text("未授权", "not granted")
        }
        return decorateToolDefinition(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "android_privileged_session_start")
                put("displayName", text("启动高权限会话", "Start Privileged Session"))
                put("toolType", "privileged")
                put(
                    "description",
                    text(
                        "启动一个可复用的 Shizuku 高权限 shell 会话，仅用于确实需要跨多轮保留 cwd、环境变量或 shell 状态的任务。当前后端：$backendLabel。需要确认时由 ACP 客户端统一请求权限。",
                        "Start a reusable Shizuku privileged shell session. Use it only when a task truly needs persistent cwd, environment variables, or shell state across turns. Current backend: $backendLabel. When confirmation is needed, the standard ACP client boundary requests permission."
                    )
                )
                put(
                    "postToolRule",
                    text(
                        "启动后先等待工具结果；如果返回需要确认，不要自行假设用户同意。",
                        "Wait for the tool result after starting. If it asks for confirmation, do not assume user consent."
                    )
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionName") {
                            put("type", "string")
                            put("description", text("可选，会话名称。未传时自动生成。", "Optional session name. Generated automatically when omitted."))
                        }
                        putJsonObject("workingDirectory") {
                            put("type", "string")
                            put("description", text("可选，会话初始工作目录。建议使用 Android 设备上的绝对路径。", "Optional initial working directory. Prefer an absolute path on the Android device filesystem."))
                        }
                        putJsonObject("environment") {
                            put("type", "object")
                            put("description", text("可选，启动时要注入的环境变量映射。", "Optional environment variables to inject when the session starts."))
                            putJsonObject("additionalProperties") {
                                put("type", "string")
                            }
                        }
                    }
                }
            }
        }, locale)
    }

    fun androidPrivilegedSessionExecTool(
        backend: ShizukuBackend,
        locale: PromptLocale = currentLocale()
    ): JsonObject {
        val text: (String, String) -> String = { zh, en ->
            if (locale == PromptLocale.ZH_CN) zh else en
        }
        val backendLabel = when (backend) {
            ShizukuBackend.ROOT -> text("root/Sui", "root/Sui")
            ShizukuBackend.ADB -> text("adb shell", "adb shell")
            ShizukuBackend.NONE -> text("未授权", "not granted")
        }
        return decorateToolDefinition(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "android_privileged_session_exec")
                put("displayName", text("执行高权限命令", "Run Privileged Command"))
                put("toolType", "privileged")
                put(
                    "description",
                    text(
                        "向已有的 Shizuku 高权限 shell 会话发送一条命令，并等待该命令完成。当前后端：$backendLabel。每次需要确认时由 ACP 客户端统一请求权限。",
                        "Send a command to an existing Shizuku privileged shell session and wait for that command to finish. Current backend: $backendLabel. When confirmation is needed, permission is requested through the standard ACP client boundary."
                    )
                )
                put(
                    "postToolRule",
                    text(
                        "执行后先等待工具结果，再决定是否继续读取输出、再次执行或结束会话。",
                        "Wait for the tool result after execution before deciding whether to read output, run another command, or stop the session."
                    )
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionId") {
                            put("type", "string")
                            put("description", text("`android_privileged_session_start` 返回的 sessionId。", "The sessionId returned by `android_privileged_session_start`."))
                        }
                        putJsonObject("command") {
                            put("type", "string")
                            put("description", text("要执行的 shell 命令。", "The shell command to execute."))
                        }
                        putJsonObject("timeoutSeconds") {
                            put("type", "integer")
                            put("description", text("可选：等待该命令完成的超时时间（秒）。省略时由执行环境决定。", "Optional timeout in seconds while waiting for the command to finish. The execution environment decides when omitted."))
                        }
                    }
                    putJsonArray("required") {
                        add("sessionId")
                        add("command")
                    }
                }
            }
        }, locale)
    }

    fun androidPrivilegedSessionReadTool(
        backend: ShizukuBackend,
        locale: PromptLocale = currentLocale()
    ): JsonObject {
        val text: (String, String) -> String = { zh, en ->
            if (locale == PromptLocale.ZH_CN) zh else en
        }
        val backendLabel = when (backend) {
            ShizukuBackend.ROOT -> text("root/Sui", "root/Sui")
            ShizukuBackend.ADB -> text("adb shell", "adb shell")
            ShizukuBackend.NONE -> text("未授权", "not granted")
        }
        return decorateToolDefinition(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "android_privileged_session_read")
                put("displayName", text("读取高权限输出", "Read Privileged Output"))
                put("toolType", "privileged")
                put(
                    "description",
                    text(
                        "读取 Shizuku 高权限 shell 会话最近的 transcript 尾部。当前后端：$backendLabel。该操作不会再次请求确认。",
                        "Read the latest transcript tail from a Shizuku privileged shell session. Current backend: $backendLabel. This operation does not require another confirmation."
                    )
                )
                put(
                    "postToolRule",
                    text(
                        "读取结果后再决定是否继续发送命令或结束会话。",
                        "After reading the result, decide whether to send another command or stop the session."
                    )
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionId") {
                            put("type", "string")
                            put("description", text("`android_privileged_session_start` 返回的 sessionId。", "The sessionId returned by `android_privileged_session_start`."))
                        }
                    }
                    putJsonArray("required") {
                        add("sessionId")
                    }
                }
            }
        }, locale)
    }

    fun androidPrivilegedSessionStopTool(
        backend: ShizukuBackend,
        locale: PromptLocale = currentLocale()
    ): JsonObject {
        val text: (String, String) -> String = { zh, en ->
            if (locale == PromptLocale.ZH_CN) zh else en
        }
        val backendLabel = when (backend) {
            ShizukuBackend.ROOT -> text("root/Sui", "root/Sui")
            ShizukuBackend.ADB -> text("adb shell", "adb shell")
            ShizukuBackend.NONE -> text("未授权", "not granted")
        }
        return decorateToolDefinition(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "android_privileged_session_stop")
                put("displayName", text("结束高权限会话", "Stop Privileged Session"))
                put("toolType", "privileged")
                put(
                    "description",
                    text(
                        "结束已有的 Shizuku 高权限 shell 会话并清理状态。当前后端：$backendLabel。该操作不会再次请求确认。",
                        "Stop an existing Shizuku privileged shell session and clean up its state. Current backend: $backendLabel. This operation does not require another confirmation."
                    )
                )
                put(
                    "postToolRule",
                    text(
                        "结束后先等待工具结果，再决定是否回复用户。",
                        "Wait for the tool result after stopping the session before replying to the user."
                    )
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionId") {
                            put("type", "string")
                            put("description", text("`android_privileged_session_start` 返回的 sessionId。", "The sessionId returned by `android_privileged_session_start`."))
                        }
                    }
                    putJsonArray("required") {
                        add("sessionId")
                    }
                }
            }
        }, locale)
    }

    val terminalSessionStartTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_session_start")
            put("displayName", "启动 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话")
            put("toolType", "terminal")
            put("description", "启动一个可复用的 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话，仅用于确实需要在后续多轮中保留 cwd、shell 环境、中间文件状态或后台进程的任务。返回的 sessionId 由底层 ReTerminal 原生生成并持久托管，后续必须显式传给 terminal_session_exec/read/stop。不要为了运行单条命令、检查工具是否存在、读取单个文件或执行一次性脚本而使用它，这些场景应优先用 terminal_execute。")
            put("postToolRule", "启动后等待 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话结果，再决定是否继续向该 session 发送命令。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionName") {
                        put("type", "string")
                        put("description", "可选，会话名称。未传时自动生成。")
                    }
                    putJsonObject("workingDirectory") {
                        put("type", "string")
                        put("description", "可选，会话初始工作目录。默认使用当前 workspace cwd。")
                    }
                }
            }
        }
    }

    val terminalSessionExecTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_session_exec")
            put("displayName", "执行 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话命令")
            put("toolType", "terminal")
            put("description", "向已有 {{OMNIBOT_TERMINAL_DISTRIBUTION}} session 发送一条非交互命令，并等待终端真实报告该命令完成。只在你明确想复用同一个 session 的 cwd、环境变量、后台任务或中间状态时使用。长时间运行的命令保持在当前 ACP tool call 中，用户需要停止时取消当前 ACP turn 或调用 terminal_session_stop。")
            put("postToolRule", "执行后等待结果，再判断是否继续读取日志、再次执行或结束 session。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionId") {
                        put("type", "string")
                        put("description", "terminal_session_start 返回的 sessionId。")
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "要执行的单次非交互 shell 命令。")
                    }
                    putJsonObject("workingDirectory") {
                        put("type", "string")
                        put("description", "可选，本次命令执行前要切换到的目录。")
                    }
                }
                putJsonArray("required") {
                    add("sessionId")
                    add("command")
                }
            }
        }
    }

    val terminalSessionReadTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_session_read")
            put("displayName", "读取 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话输出")
            put("toolType", "terminal")
            put("description", "读取 {{OMNIBOT_TERMINAL_DISTRIBUTION}} session 最近一次命令日志或最近的 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 输出。默认应把它视为读取该 session 最新尾部输出，而不是重新查看最早的历史。只在已经启动并复用了 terminal_session_* 的前提下使用。")
            put("postToolRule", "读取结果后再决定是否继续执行命令。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionId") {
                        put("type", "string")
                        put("description", "{{OMNIBOT_TERMINAL_DISTRIBUTION}} session id。")
                    }
                }
                putJsonArray("required") {
                    add("sessionId")
                }
            }
        }
    }

    val terminalSessionStopTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "terminal_session_stop")
            put("displayName", "结束 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 会话")
            put("toolType", "terminal")
            put("description", "停止已有 {{OMNIBOT_TERMINAL_DISTRIBUTION}} session，并清理对应 tmux 会话。完成状态化 {{OMNIBOT_TERMINAL_DISTRIBUTION}} 任务后再调用。")
            put("postToolRule", "结束后等待工具结果，再回复用户。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sessionId") {
                        put("type", "string")
                        put("description", "{{OMNIBOT_TERMINAL_DISTRIBUTION}} session id。")
                    }
                }
                putJsonArray("required") {
                    add("sessionId")
                }
            }
        }
    }

    val browserUseTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "browser_use")
            put("displayName", "浏览器操作")
            put("toolType", "browser")
            put(
                "description",
                "控制离屏浏览器。不要用它打开 App deep link、omnibot:// 非 browser 资源或应用内路由。浏览器只支持访问 http(s) 页面，以及 omnibot://browser/... 资源文件。使用 navigate 打开页面，screenshot 查看当前视口截图（传 read_image=true 可让模型直接看到截图内容），click/type/hover 与元素交互，get_text/get_readable 抽取内容，scroll 导航长页面，scroll_and_collect 在一次调用中滚动并收集无限列表内容，find_elements 发现可交互元素，get_page_info 获取页面元信息，get_backbone 获取 DOM 骨架，execute_js 执行脚本，fetch 复用当前页面 session 下载资源并返回 omnibot://browser/... 产物，new_tab/close_tab/list_tabs 管理标签页，go_back/go_forward 浏览器前进后退，press_key 模拟键盘按键，wait_for_selector 等待元素出现，get_cookies 返回 cookie 摘要与可复用的 offload env 脚本路径，set_user_agent 兼容 desktop_safari/mobile_safari 入参但实际切换 Android Chrome 风格桌面/移动 UA。结果可能包含 riskChallengeDetected、riskChallengeKind、recommendedNextAction、throttleDelayMs；若 riskChallengeDetected=true，应停止自动交互/刷新并请用户手动接管。"
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "浏览器动作。")
                        putJsonArray("enum") {
                            add("navigate")
                            add("screenshot")
                            add("click")
                            add("type")
                            add("get_text")
                            add("scroll")
                            add("get_page_info")
                            add("execute_js")
                            add("find_elements")
                            add("hover")
                            add("get_readable")
                            add("set_user_agent")
                            add("get_backbone")
                            add("fetch")
                            add("new_tab")
                            add("close_tab")
                            add("list_tabs")
                            add("get_cookies")
                            add("scroll_and_collect")
                            add("go_back")
                            add("go_forward")
                            add("press_key")
                            add("wait_for_selector")
                        }
                    }
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "navigate 打开的 URL，或 fetch 下载的资源 URL。")
                    }
                    putJsonObject("selector") {
                        put("type", "string")
                        put("description", "CSS selector。适用于 click/type/get_text/scroll/hover/find_elements。")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "type 动作要输入的文本。")
                    }
                    putJsonObject("script") {
                        put("type", "string")
                        put("description", "execute_js 动作要执行的 JavaScript 代码。")
                    }
                    putJsonObject("coordinate_x") {
                        put("type", "integer")
                        put("description", "点击或输入目标的 X 坐标，可替代 selector。")
                    }
                    putJsonObject("coordinate_y") {
                        put("type", "integer")
                        put("description", "点击或输入目标的 Y 坐标，可替代 selector。")
                    }
                    putJsonObject("amount") {
                        put("type", "integer")
                        put("description", "scroll 或 scroll_and_collect 使用的滚动像素量；需要时显式提供。")
                    }
                    putJsonObject("direction") {
                        put("type", "string")
                        put("description", "滚动方向。")
                        putJsonArray("enum") {
                            add("up")
                            add("down")
                        }
                    }
                    putJsonObject("tab_id") {
                        put("type", "integer")
                        put("description", "目标标签页 ID；不传时默认使用最近活跃标签页。")
                    }
                    putJsonObject("item_selector") {
                        put("type", "string")
                        put("description", "scroll_and_collect 的内容项 selector；不传时自动探测。")
                    }
                    putJsonObject("scroll_count") {
                        put("type", "integer")
                        put("description", "scroll_and_collect 的滚动次数；需要时显式提供。")
                    }
                    putJsonObject("max_depth") {
                        put("type", "integer")
                        put("description", "get_backbone 的最大深度；需要时显式提供。")
                    }
                    putJsonObject("user_agent") {
                        put("type", "string")
                        put("description", "要切换到的 UA profile；枚举名保持兼容，实际使用 Android Chrome 风格桌面/移动 UA。")
                        putJsonArray("enum") {
                            add("desktop_safari")
                            add("mobile_safari")
                        }
                    }
                    putJsonObject("keywords") {
                        put("type", "string")
                        put(
                            "description",
                            "get_cookies 的 cookie 名过滤关键词。可传空格分隔字符串，兼容数组字符串输入。fuzzy=true 时要求所有关键词都包含在 cookie 名中；fuzzy=false 时要求精确命中任一 cookie 名。"
                        )
                    }
                    putJsonObject("fuzzy") {
                        put("type", "boolean")
                        put("description", "get_cookies 的关键词匹配模式，默认 true。")
                    }
                    putJsonObject("read_image") {
                        put("type", "boolean")
                        put("description", "仅 screenshot 时生效。设为 true 时，截图会以 base64 图片嵌入工具结果，供模型直接分析页面内容。默认 false。")
                    }
                    putJsonObject("key") {
                        put("type", "string")
                        put("description", "press_key 动作要模拟的按键名，例如 Enter、Escape、Tab、ArrowDown。")
                    }
                    putJsonObject("timeout_ms") {
                        put("type", "integer")
                        put("description", "wait_for_selector 的可选超时毫秒数；省略时由浏览器执行环境决定。")
                    }
                }
                putJsonArray("required") {
                    add("action")
                }
            }
        }
    }

    val fileReadTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_read")
            put("displayName", "读取文件")
            put("toolType", "workspace")
            put("description", "读取 workspace 或 Omnibot 白名单目录中的文件内容。自动支持图片/截图，图片会返回元数据与可视预览。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "文件路径，可使用相对 workspace 路径或 omnibot:// uri。")
                    }
                    putJsonObject("offset") {
                        put("type", "integer")
                        put("description", "可选，从指定字符偏移开始读取。")
                    }
                    putJsonObject("lineStart") {
                        put("type", "integer")
                        put("description", "可选，从第几行开始读取，1-based。")
                    }
                    putJsonObject("lineCount") {
                        put("type", "integer")
                        put("description", "可选，读取多少行。")
                    }
                }
                putJsonArray("required") {
                    add("path")
                }
            }
        }
    }

    val fileWriteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_write")
            put("displayName", "写入文件")
            put("toolType", "workspace")
            put("description", "创建或覆盖 workspace 内文件。新建文件优先使用此工具。")
            put("postToolRule", "写入后等待结果，再决定是否继续读取或修改。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "目标文件路径。")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "要写入的完整文本内容。")
                    }
                    putJsonObject("append") {
                        put("type", "boolean")
                        put("description", "是否追加写入，默认 false。")
                    }
                }
                putJsonArray("required") {
                    add("path")
                    add("content")
                }
            }
        }
    }

    val imageGenerateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "image_generate")
            put("displayName", "Generate Image")
            put("toolType", "workspace")
            put(
                "description",
                "Generate one image with the configured OpenAI-compatible image provider and save it as a workspace file. Prefer this over file_write for AI-created raster artwork."
            )
            put("postToolRule", "Wait for the generated file result before reading, editing, or packaging it.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("prompt") {
                        put("type", "string")
                        put("description", "Image generation prompt.")
                    }
                    putJsonObject("outputPath") {
                        put("type", "string")
                        put("description", "Workspace path or omnibot:// URI for the generated image, usually ending in .png or .webp.")
                    }
                    putJsonObject("model") {
                        put("type", "string")
                        put("description", "Image model id. Defaults to gpt-image-2.")
                    }
                    putJsonObject("size") {
                        put("type", "string")
                        put("description", "Output size. Defaults to 1024x1024.")
                        putJsonArray("enum") {
                            add("auto")
                            add("1024x1024")
                            add("1024x1536")
                            add("1536x1024")
                        }
                    }
                    putJsonObject("quality") {
                        put("type", "string")
                        put("description", "Generation quality. Defaults to auto.")
                        putJsonArray("enum") {
                            add("auto")
                            add("low")
                            add("medium")
                            add("high")
                        }
                    }
                    putJsonObject("format") {
                        put("type", "string")
                        put("description", "Output file format. Defaults to png.")
                        putJsonArray("enum") {
                            add("png")
                            add("webp")
                            add("jpeg")
                        }
                    }
                    putJsonObject("background") {
                        put("type", "string")
                        put("description", "Background handling for models that support it.")
                        putJsonArray("enum") {
                            add("auto")
                            add("transparent")
                            add("opaque")
                        }
                    }
                    putJsonObject("providerProfileId") {
                        put("type", "string")
                        put("description", "Optional provider profile id. Defaults to the currently selected model provider profile.")
                    }
                }
                putJsonArray("required") {
                    add("prompt")
                    add("outputPath")
                }
            }
        }
    }

    val fileEditTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_edit")
            put("displayName", "编辑文件")
            put("toolType", "workspace")
            put("description", "对已有文件做精确字符串替换。修改现有文件优先使用此工具。")
            put("postToolRule", "编辑后等待结果，再判断是否继续读取验证。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "目标文件路径。")
                    }
                    putJsonObject("oldText") {
                        put("type", "string")
                        put("description", "要替换的原始文本。")
                    }
                    putJsonObject("newText") {
                        put("type", "string")
                        put("description", "替换后的文本。")
                    }
                    putJsonObject("replaceAll") {
                        put("type", "boolean")
                        put("description", "是否替换全部匹配，默认 false。")
                    }
                }
                putJsonArray("required") {
                    add("path")
                    add("oldText")
                    add("newText")
                }
            }
        }
    }

    val fileListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_list")
            put("displayName", "列出文件")
            put("toolType", "workspace")
            put("description", "列出某个目录下的文件和子目录。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "目录路径。默认当前 workspace。")
                    }
                    putJsonObject("recursive") {
                        put("type", "boolean")
                        put("description", "是否递归列出。默认 false。")
                    }
                    putJsonObject("maxDepth") {
                        put("type", "integer")
                        put("description", "可选，递归时最大深度。省略时由执行环境决定。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选，最多返回多少项。省略时返回执行环境提供的完整结果。")
                    }
                }
            }
        }
    }

    val fileSearchTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_search")
            put("displayName", "搜索文件")
            put("toolType", "workspace")
            put("description", "在目录中递归搜索文件名或文本内容。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "搜索起始目录，默认当前 workspace。")
                    }
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "要搜索的关键词。")
                    }
                    putJsonObject("caseSensitive") {
                        put("type", "boolean")
                        put("description", "是否区分大小写，默认 false。")
                    }
                    putJsonObject("maxResults") {
                        put("type", "integer")
                        put("description", "可选，最多返回多少项。省略时返回执行环境提供的完整结果。")
                    }
                }
                putJsonArray("required") {
                    add("query")
                }
            }
        }
    }

    val fileStatTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_stat")
            put("displayName", "查看文件信息")
            put("toolType", "workspace")
            put("description", "查看文件或目录的元信息。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "目标路径。")
                    }
                }
                putJsonArray("required") {
                    add("path")
                }
            }
        }
    }

    val fileMoveTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "file_move")
            put("displayName", "移动文件")
            put("toolType", "workspace")
            put("description", "移动或重命名 workspace 中的文件。")
            put("postToolRule", "移动后等待结果，再决定是否继续读取。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sourcePath") {
                        put("type", "string")
                        put("description", "源路径。")
                    }
                    putJsonObject("targetPath") {
                        put("type", "string")
                        put("description", "目标路径。")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "是否覆盖目标文件，默认 false。")
                    }
                }
                putJsonArray("required") {
                    add("sourcePath")
                    add("targetPath")
                }
            }
        }
    }

    val skillsListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "skills_list")
            put("displayName", "列出 Skills")
            put("toolType", "skill")
            put("description", "列出当前可用的 skills 索引，包括 id、名称、路径和能力目录。用户询问有哪些 skills、某类 skill 是否已安装，或你想先查目录再决定读取 SKILL.md 时优先调用。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "可选关键词，匹配 skill id、名称、描述或路径。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选，最多返回多少项。省略时返回执行环境提供的完整结果。")
                    }
                }
            }
        }
    }

    val skillsReadTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "skills_read")
            put("displayName", "读取 Skill")
            put("toolType", "skill")
            put("description", "按 skill id、名称或路径读取某个已安装 skill 的 SKILL.md 正文和相关目录信息。当你知道某个 skill 可能相关，但本轮只掌握索引信息时调用。")
            put("postToolRule", "读取 skill 后等待结果，再根据返回的正文、scripts、references、assets 路径决定下一步。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("skillId") {
                        put("type", "string")
                        put("description", "skill 的 id、名称、SKILL.md 路径或 skill 根目录路径。建议先用 skills_list 查看。")
                    }
                }
                putJsonArray("required") {
                    add("skillId")
                }
            }
        }
    }

    val scheduleTaskCreateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "schedule_task_create")
            put("displayName", "创建定时任务")
            put("toolType", "schedule")
            put("description", "创建新的定时任务。`targetKind=subagent` 为唯一支持的执行类型；`subagentPrompt` 必须写成任务触发时要立即执行的动作，不要重复填写“每天几点提醒我/定时去做”这类调度描述。")
            put("postToolRule", "创建完成后不要在同一轮继续调用其他工具；请等待工具结果，并通过 response 输出最终答复。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("targetKind") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("subagent")
                        }
                    }
                    putJsonObject("subagentConversationId") { put("type", "string") }
                    putJsonObject("subagentPrompt") {
                        put("type", "string")
                        put(
                            "description",
                            "subagent 被触发时要立即执行的任务说明。不要把“每天/几点/定时/提醒/闹钟/创建任务”等调度话术写进去，而要写成到点后此刻真正要完成的动作。"
                        )
                    }
                    putJsonObject("notificationEnabled") { put("type", "boolean") }
                    putJsonObject("scheduleType") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("fixed_time")
                            add("countdown")
                        }
                    }
                    putJsonObject("fixedTime") { put("type", "string") }
                    putJsonObject("countdownMinutes") { put("type", "integer") }
                    putJsonObject("repeatDaily") { put("type", "boolean") }
                    putJsonObject("enabled") { put("type", "boolean") }
                }
                putJsonArray("required") {
                    add("title")
                    add("targetKind")
                    add("scheduleType")
                    add("repeatDaily")
                }
            }
        }
    }

    val scheduleTaskListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "schedule_task_list")
            put("displayName", "查看定时任务")
            put("toolType", "schedule")
            put("description", "查看当前已有的定时任务列表。执行后等待工具结果。")
            put("postToolRule", "查看结果后再决定是否需要修改、删除或向用户总结。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
    }

    val scheduleTaskUpdateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "schedule_task_update")
            put("displayName", "修改定时任务")
            put("toolType", "schedule")
            put("description", "修改已有定时任务的时间、标题、每日重复或启停状态。`targetKind=subagent` 为唯一支持的执行类型；更新后的 `subagentPrompt` 仍应描述触发时真正执行的动作，而不是再次描述调度本身。")
            put("postToolRule", "修改完成后不要同轮回复，等待工具结果。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("taskId") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("targetKind") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("subagent")
                        }
                    }
                    putJsonObject("fixedTime") { put("type", "string") }
                    putJsonObject("countdownMinutes") { put("type", "integer") }
                    putJsonObject("repeatDaily") { put("type", "boolean") }
                    putJsonObject("enabled") { put("type", "boolean") }
                    putJsonObject("subagentConversationId") { put("type", "string") }
                    putJsonObject("subagentPrompt") {
                        put("type", "string")
                        put(
                            "description",
                            "subagent 被触发时要立即执行的任务说明。不要把“每天/几点/定时/提醒/闹钟/创建任务”等调度话术写进去，而要写成到点后此刻真正要完成的动作。"
                        )
                    }
                    putJsonObject("notificationEnabled") { put("type", "boolean") }
                }
                putJsonArray("required") {
                    add("taskId")
                }
            }
        }
    }

    val scheduleTaskDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "schedule_task_delete")
            put("displayName", "删除定时任务")
            put("toolType", "schedule")
            put("description", "删除已有定时任务。执行后等待工具结果。")
            put("postToolRule", "删除完成后等待工具结果，再输出最终回复。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("taskId") { put("type", "string") }
                }
                putJsonArray("required") {
                    add("taskId")
                }
            }
        }
    }

    val alarmReminderCreateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "alarm_reminder_create")
            put("displayName", "创建提醒闹钟")
            put("toolType", "alarm")
            put(
                "description",
                "创建提醒闹钟。exact_alarm 模式使用 AlarmManager 精确提醒；clock_app 模式调用系统闹钟应用创建闹钟；若用户未明确指定，优先使用 exact_alarm。用于单纯提醒，不执行自动化任务。"
            )
            put("postToolRule", "创建后等待工具结果，再决定是否继续。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("mode") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("exact_alarm")
                            add("clock_app")
                        }
                        put("description", "闹钟模式：exact_alarm=应用内精确提醒；clock_app=系统闹钟。")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "提醒标题。")
                    }
                    putJsonObject("triggerAt") {
                        put("type", "string")
                        put("description", "触发时间，ISO-8601 格式，例如 2026-03-17T21:30:00+08:00。")
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "可选提醒内容。")
                    }
                    putJsonObject("timezone") {
                        put("type", "string")
                        put("description", "可选 IANA 时区，未传默认系统时区。")
                    }
                    putJsonObject("allowWhileIdle") {
                        put("type", "boolean")
                        put("description", "仅 exact_alarm 模式生效，是否在待机时也精确触发。默认 true。")
                    }
                    putJsonObject("skipUi") {
                        put("type", "boolean")
                        put("description", "仅 clock_app 模式生效，是否尝试跳过系统闹钟界面。默认 false。")
                    }
                }
                putJsonArray("required") {
                    add("mode")
                    add("title")
                    add("triggerAt")
                }
            }
        }
    }

    val alarmReminderListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "alarm_reminder_list")
            put("displayName", "查看提醒闹钟")
            put("toolType", "alarm")
            put("description", "查看由本应用创建并托管的 exact_alarm 提醒闹钟列表。")
            put("postToolRule", "查看结果后再决定是否删除或继续创建。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
    }

    val alarmReminderDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "alarm_reminder_delete")
            put("displayName", "删除提醒闹钟")
            put("toolType", "alarm")
            put("description", "按 alarmId 删除本应用创建并托管的 exact_alarm 提醒闹钟。")
            put("postToolRule", "删除后等待工具结果，再向用户确认。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("alarmId") {
                        put("type", "string")
                        put("description", "闹钟 ID。")
                    }
                }
                putJsonArray("required") {
                    add("alarmId")
                }
            }
        }
    }

    val calendarListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_list")
            put("displayName", "查看日历列表")
            put("toolType", "calendar")
            put("description", "查询设备日历账户列表，可用于选择 calendarId。")
            put("postToolRule", "查看结果后再决定新建或管理日程。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("writableOnly") {
                        put("type", "boolean")
                        put("description", "是否仅返回可写日历。默认 true。")
                    }
                    putJsonObject("visibleOnly") {
                        put("type", "boolean")
                        put("description", "是否仅返回可见日历。默认 true。")
                    }
                }
            }
        }
    }

    val calendarEventCreateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_event_create")
            put("displayName", "创建日程")
            put("toolType", "calendar")
            put("description", "创建日历事件。用于管理日程，不触发自动化任务。")
            put("postToolRule", "创建后等待工具结果，再向用户确认。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("startAt") {
                        put("type", "string")
                        put("description", "开始时间，ISO-8601。")
                    }
                    putJsonObject("endAt") {
                        put("type", "string")
                        put("description", "结束时间，ISO-8601。")
                    }
                    putJsonObject("calendarId") {
                        put("type", "string")
                        put("description", "可选，目标日历 ID。")
                    }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("location") { put("type", "string") }
                    putJsonObject("timezone") {
                        put("type", "string")
                        put("description", "可选 IANA 时区，未传默认系统时区。")
                    }
                    putJsonObject("allDay") { put("type", "boolean") }
                    putJsonObject("reminderMinutes") {
                        put("type", "array")
                        put("description", "提醒分钟列表，例如 [10, 30]。")
                        putJsonObject("items") {
                            put("type", "integer")
                        }
                    }
                }
                putJsonArray("required") {
                    add("title")
                    add("startAt")
                    add("endAt")
                }
            }
        }
    }

    val calendarEventListTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_event_list")
            put("displayName", "查询日程")
            put("toolType", "calendar")
            put("description", "按时间范围、关键字、calendarId 查询日历事件。")
            put("postToolRule", "查看结果后再决定是否更新或删除。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("calendarId") { put("type", "string") }
                    putJsonObject("startAt") {
                        put("type", "string")
                        put("description", "可选，查询起始时间，ISO-8601。")
                    }
                    putJsonObject("endAt") {
                        put("type", "string")
                        put("description", "可选，查询结束时间，ISO-8601。")
                    }
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "可选关键词，匹配标题或地点。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选返回上限；省略时返回该时间范围内的完整结果。")
                    }
                }
            }
        }
    }

    val calendarEventUpdateTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_event_update")
            put("displayName", "修改日程")
            put("toolType", "calendar")
            put("description", "按 eventId 修改日历事件。")
            put("postToolRule", "修改后等待工具结果，再向用户同步。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("eventId") {
                        put("type", "string")
                        put("description", "事件 ID。")
                    }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("startAt") { put("type", "string") }
                    putJsonObject("endAt") { put("type", "string") }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("location") { put("type", "string") }
                    putJsonObject("timezone") { put("type", "string") }
                    putJsonObject("allDay") { put("type", "boolean") }
                    putJsonObject("reminderMinutes") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "integer")
                        }
                    }
                }
                putJsonArray("required") {
                    add("eventId")
                }
            }
        }
    }

    val calendarEventDeleteTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "calendar_event_delete")
            put("displayName", "删除日程")
            put("toolType", "calendar")
            put("description", "按 eventId 删除日历事件。")
            put("postToolRule", "删除后等待工具结果，再向用户确认。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("eventId") { put("type", "string") }
                }
                putJsonArray("required") {
                    add("eventId")
                }
            }
        }
    }

    val musicPlaybackControlTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "music_playback_control")
            put("displayName", "音乐播放控制")
            put("toolType", "music")
            put(
                "description",
                "控制安卓系统级音乐播放。action=play 且提供 source 时，会由应用前台媒体会话播放本地文件、omnibot workspace/public 文件、file/content Uri 或 http(s) 直链音频；play 不提供 source 时，退化为向系统当前播放器发送播放媒体键。pause/resume/stop/next/previous 会优先控制当前由本应用托管的音频播放，若没有本地会话则退化为发送系统媒体键；seek 和 status 仅针对本应用托管的播放会话。"
            )
            put("postToolRule", "执行后等待工具结果，再决定是否继续调整播放。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "要执行的播放控制动作。")
                        putJsonArray("enum") {
                            add("play")
                            add("pause")
                            add("resume")
                            add("stop")
                            add("seek")
                            add("status")
                            add("next")
                            add("previous")
                        }
                    }
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "仅 play 时可选。支持 omnibot://、/workspace、/storage、相对 workspace 路径、file://、content://、http(s) 直链。留空表示只向系统发送播放媒体键。")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "仅 play 时可选，前台通知与系统媒体会话里显示的标题。")
                    }
                    putJsonObject("loop") {
                        put("type", "boolean")
                        put("description", "仅 play 时可选，是否循环播放。默认 false。")
                    }
                    putJsonObject("positionSeconds") {
                        put("type", "integer")
                        put("description", "仅 seek 时使用，目标播放秒数。")
                    }
                }
                putJsonArray("required") {
                    add("action")
                }
            }
        }
    }

    val memorySearchTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_search")
            put("displayName", "检索记忆")
            put("toolType", "memory")
            put("description", "在 workspace 记忆中检索与当前问题相关的长期/短期记忆。优先使用向量召回，配置缺失时自动降级词法检索。写入新记忆前可先检索，避免重复沉淀。")
            put("postToolRule", "读取结果后再决定是否写入新的短期或长期记忆。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "检索语句。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "可选，返回条数上限。省略时返回执行环境提供的完整结果。")
                    }
                }
                putJsonArray("required") {
                    add("query")
                }
            }
        }
    }

    val memoryWriteDailyTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_write_daily")
            put("displayName", "写入当日记忆")
            put("toolType", "memory")
            put("description", "把需要跨会话保留的用户偏好、关键决定、任务进度或外部标识写入当日短期记忆 `.omnibot/memory/short-memories/YY-MM-DD.md`。仅在任务或用户明确需要持久化该事实时调用。")
            put("postToolRule", "写入成功后再继续执行其他步骤。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "要写入的短期记忆文本。")
                    }
                }
                putJsonArray("required") {
                    add("text")
                }
            }
        }
    }

    val memoryUpsertLongTermTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_upsert_longterm")
            put("displayName", "沉淀长期记忆")
            put("toolType", "memory")
            put("description", "把跨会话稳定、可复用的结论(稳定偏好、长期约束、身份事实)写入 `.omnibot/memory/MEMORY.md`。仅用于有长期价值的信息；一次性过程细节请改用 `memory_write_daily` 写短期。")
            put("postToolRule", "写入后等待工具结果，再向用户确认。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "要沉淀的长期记忆内容。")
                    }
                }
                putJsonArray("required") {
                    add("text")
                }
            }
        }
    }

    val memoryRollupDayTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_rollup_day")
            put("displayName", "整理当日记忆")
            put("toolType", "memory")
            put("description", "整理某一天短期记忆并按策略沉淀到长期记忆。默认整理今天。")
            put("postToolRule", "整理后等待工具结果，再决定是否补充长期记忆。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("date") {
                        put("type", "string")
                        put("description", "可选日期，格式 YYYY-MM-DD。")
                    }
                }
            }
        }
    }

    val memoryLoadTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "memory_load")
            put("displayName", "加载长期记忆")
            put("toolType", "memory")
            put("description", "按 slug 加载完整的长期记忆条目正文。slug 来自 memory_search 返回的长期记忆命中。")
            put("postToolRule", "读取后再决定是否需要进一步检索或写入。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("slug") {
                        put("type", "string")
                        put("description", "长期记忆条目 slug，例如 `langchain-7c2b8e90`。")
                    }
                }
                putJsonArray("required") {
                    add("slug")
                }
            }
        }
    }

    val subagentDispatchTool: JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "subagent_dispatch")
            put("displayName", "分派子任务")
            put("toolType", "subagent")
            put(
                "description",
                "仅当用户明确要求分派或并行时，把多个相互独立的小任务交给具有隔离上下文的 subagent，并返回聚合结果。"
            )
            put("postToolRule", "分派后等待工具结果，再汇总给用户。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("tasks") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("instruction") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "子任务的完整、自足指令，不要依赖主会话中未写入此处的上下文。"
                                    )
                                }
                                putJsonObject("profileId") {
                                    put("type", "string")
                                    putJsonArray("enum") {
                                        add("general")
                                        add("explorer")
                                        add("memory-curator")
                                        add("planner")
                                    }
                                    put(
                                        "description",
                                        "可选任务提示：general、explorer、memory-curator 或 planner。所有 profile 都继承当前 harness 已提供的能力。"
                                    )
                                }
                            }
                            putJsonArray("required") {
                                add("instruction")
                            }
                        }
                        put(
                            "description",
                            "要分派的子任务列表。每项都要包含自足的 instruction；profileId 为可选任务提示。"
                        )
                    }
                    putJsonObject("defaultProfileId") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("general")
                            add("explorer")
                            add("memory-curator")
                            add("planner")
                        }
                        put(
                            "description",
                            "子任务省略 profileId 时使用的可选任务提示，默认 general。"
                        )
                    }
                    putJsonObject("concurrency") {
                        put("type", "integer")
                        put("description", "可选并发度；省略时按本次子任务数并行执行。")
                    }
                    putJsonObject("mergeInstruction") {
                        put("type", "string")
                        put("description", "结果聚合要求，可选。")
                    }
                }
                putJsonArray("required") {
                    add("tasks")
                }
            }
        }
    }

    private val builtinToolDefinitions: List<JsonObject> = listOf(
        contextTimeNowTool,
        contextAppsQueryTool,
        vlmTaskTool,
        terminalExecuteTool,
        terminalSessionStartTool,
        terminalSessionExecTool,
        terminalSessionReadTool,
        terminalSessionStopTool,
        browserUseTool,
        fileReadTool,
        fileWriteTool,
        imageGenerateTool,
        fileEditTool,
        fileListTool,
        fileSearchTool,
        fileStatTool,
        fileMoveTool,
        skillsListTool,
        skillsReadTool
    )

    private val scheduleToolDefinitions: List<JsonObject> = listOf(
        scheduleTaskCreateTool,
        scheduleTaskListTool,
        scheduleTaskUpdateTool,
        scheduleTaskDeleteTool
    )

    private val alarmToolDefinitions: List<JsonObject> = listOf(
        alarmReminderCreateTool,
        alarmReminderListTool,
        alarmReminderDeleteTool
    )

    private val calendarToolDefinitions: List<JsonObject> = listOf(
        calendarListTool,
        calendarEventCreateTool,
        calendarEventListTool,
        calendarEventUpdateTool,
        calendarEventDeleteTool
    )

    private val musicToolDefinitions: List<JsonObject> = listOf(
        musicPlaybackControlTool
    )

    private val memoryToolDefinitions: List<JsonObject> = listOf(
        memorySearchTool,
        memoryWriteDailyTool,
        memoryUpsertLongTermTool,
        memoryRollupDayTool,
        memoryLoadTool
    )

    private val subagentToolDefinitions: List<JsonObject> = listOf(
        subagentDispatchTool
    )

    fun builtinTools(
        locale: PromptLocale = currentLocale(),
        terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine,
        includeVlmTool: Boolean = true,
    ): List<JsonObject> =
        builtinToolDefinitions
            .filter { includeVlmTool || it !== vlmTaskTool }
            .map { decorateToolDefinition(it, locale, terminalDistribution) }

    fun scheduleTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        scheduleToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun alarmTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        alarmToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun calendarTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        calendarToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun musicTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        musicToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun memoryTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        memoryToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun subagentTools(locale: PromptLocale = currentLocale()): List<JsonObject> =
        subagentToolDefinitions.map { decorateToolDefinition(it, locale) }

    fun staticTools(
        locale: PromptLocale = currentLocale(),
        terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine,
        includeVlmTool: Boolean = true,
    ): List<JsonObject> =
        builtinTools(locale, terminalDistribution, includeVlmTool) +
        scheduleTools(locale) + alarmTools(locale) + calendarTools(locale) + musicTools(locale)

    fun reservedToolNames(): Set<String> {
        val locale = PromptLocale.EN_US
        val definitions = buildList {
            addAll(staticTools(locale))
            addAll(memoryTools(locale))
            addAll(subagentTools(locale))
            add(androidPrivilegedActionTool(emptyList(), ShizukuBackend.NONE, locale))
            add(androidPrivilegedSessionStartTool(ShizukuBackend.NONE, locale))
            add(androidPrivilegedSessionExecTool(ShizukuBackend.NONE, locale))
            add(androidPrivilegedSessionReadTool(ShizukuBackend.NONE, locale))
            add(androidPrivilegedSessionStopTool(ShizukuBackend.NONE, locale))
        }
        return definitions.mapNotNullTo(linkedSetOf()) { definition ->
            (definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
        }
    }
}
