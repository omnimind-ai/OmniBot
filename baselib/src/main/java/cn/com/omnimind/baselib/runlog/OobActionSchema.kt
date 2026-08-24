package cn.com.omnimind.baselib.runlog

/**
 * Generated from schemas/oob/oob_canonical_actions.v1.json.
 *
 * Do not edit action/tool names or argument fields here. Update the schema and
 * run `python3 scripts/generate-oob-action-schema.py`.
 */
object OobActionSchema {
    const val SCHEMA_VERSION = "oob.canonical_actions.v1"
    const val ROOT_TOOL = "tool"
    const val ROOT_ARGS = "args"

    const val TOOL_CLICK = "click"
    const val TOOL_LONG_PRESS = "long_press"
    const val TOOL_INPUT_TEXT = "input_text"
    const val TOOL_SWIPE = "swipe"
    const val TOOL_OPEN_APP = "open_app"
    const val TOOL_PRESS_KEY = "press_key"
    const val TOOL_WAIT = "wait"
    const val TOOL_GET_STATE = "get_state"
    const val TOOL_FINISHED = "finished"
    const val TOOL_INFO = "info"
    const val TOOL_FEEDBACK = "feedback"
    const val TOOL_ABORT = "abort"
    const val TOOL_REQUIRE_USER_CHOICE = "require_user_choice"
    const val TOOL_REQUIRE_USER_CONFIRMATION = "require_user_confirmation"

    const val ARG_TARGET_DESCRIPTION = "target_description"
    const val ARG_NODE_ID = "node_id"
    const val ARG_NODE_RESOURCE_ID = "node_resource_id"
    const val ARG_X = "x"
    const val ARG_Y = "y"
    const val ARG_DURATION_MS = "duration_ms"
    const val ARG_TEXT = "text"
    const val ARG_DIRECTION = "direction"
    const val ARG_DISTANCE = "distance"
    const val ARG_X1 = "x1"
    const val ARG_Y1 = "y1"
    const val ARG_X2 = "x2"
    const val ARG_Y2 = "y2"
    const val ARG_PACKAGE_NAME = "package_name"
    const val ARG_KEY = "key"
    const val ARG_REASON = "reason"
    const val ARG_CONTENT = "content"
    const val ARG_VALUE = "value"
    const val ARG_OPTIONS = "options"
    const val ARG_PROMPT = "prompt"

    enum class Type {
        STRING,
        NUMBER,
        INTEGER,
        BOOLEAN,
        OBJECT,
        STRING_ARRAY,
    }

    enum class Kind {
        ACTION,
        OBSERVE,
        DECISION,
    }

    data class LocalizedText(
        val zhCn: String,
        val enUs: String,
    )

    data class ArgSpec(
        val name: String,
        val type: Type,
        val required: Boolean = false,
        val persisted: Boolean = true,
        val description: LocalizedText = LocalizedText("", ""),
        val enumValues: List<String> = emptyList(),
        val minimum: Number? = null,
        val maximum: Number? = null,
        val additionalProperties: Boolean = false,
    )

    data class ToolSpec(
        val name: String,
        val kind: Kind,
        val uiLabel: LocalizedText,
        val description: LocalizedText,
        val promptGuide: LocalizedText,
        val argsTemplate: Map<String, Any?> = emptyMap(),
        val args: List<ArgSpec> = emptyList(),
        val modelVisible: Boolean = true,
        val replayable: Boolean = true,
        val editorVisible: Boolean = true,
        val recordable: Boolean = false,
        val coordinateAction: Boolean = false,
        val pointTargetAction: Boolean = false,
        val routeAction: Boolean = false,
    )

    val tools: List<ToolSpec> = listOf(
        ToolSpec(
            name = "click",
            kind = Kind.ACTION,
            uiLabel = LocalizedText(zhCn = "点击", enUs = "Click"),
            description = LocalizedText(zhCn = "点击一个可见目标；x、y 必须提供，target_description 可选。", enUs = "Tap a visible target; x and y are required, while target_description is optional."),
            promptGuide = LocalizedText(zhCn = "- click(target_description?, x, y): 点击可见目标；x/y 是 required 的 0..1000 相对坐标，target_description 用于补充 grounding。", enUs = "- click(target_description?, x, y): Tap a visible target; x/y are required 0..1000 relative coordinates, and target_description is an optional grounding hint."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。要点击的目标描述，用于补充 grounding。", enUs = "Optional. Description of the target to tap, used as a grounding hint."),
                ),
ArgSpec(
                    name = "node_id",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。live XML 中已确认的无障碍节点 id。", enUs = "Optional. Confirmed accessibility node id from live XML."),
                ),
ArgSpec(
                    name = "node_resource_id",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs = "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer."),
                ),
ArgSpec(
                    name = "x",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "点击位置的 required 0..1000 相对 X 坐标；执行前会解码为屏幕绝对像素。", enUs = "Required 0..1000 relative X coordinate of the tap target; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "y",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "点击位置的 required 0..1000 相对 Y 坐标；执行前会解码为屏幕绝对像素。", enUs = "Required 0..1000 relative Y coordinate of the tap target; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = true,
            coordinateAction = true,
            pointTargetAction = true,
            routeAction = false,
        ),
        ToolSpec(
            name = "long_press",
            kind = Kind.ACTION,
            uiLabel = LocalizedText(zhCn = "长按", enUs = "Long press"),
            description = LocalizedText(zhCn = "长按一个目标。", enUs = "Long-press a target."),
            promptGuide = LocalizedText(zhCn = "- long_press(target_description?, x, y): 长按目标；x/y 是 required 的 0..1000 相对坐标，target_description 用于补充 grounding。", enUs = "- long_press(target_description?, x, y): Long-press a target; x/y are required 0..1000 relative coordinates, and target_description is an optional grounding hint."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。要长按的目标描述，用于补充 grounding。", enUs = "Optional. Description of the target to long-press, used as a grounding hint."),
                ),
ArgSpec(
                    name = "node_id",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。live XML 中已确认的无障碍节点 id。", enUs = "Optional. Confirmed accessibility node id from live XML."),
                ),
ArgSpec(
                    name = "node_resource_id",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs = "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer."),
                ),
ArgSpec(
                    name = "x",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "长按位置的 0..1000 相对 X 坐标；执行前会解码为屏幕绝对像素。", enUs = "0..1000 relative X coordinate of the long press; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "y",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "长按位置的 0..1000 相对 Y 坐标；执行前会解码为屏幕绝对像素。", enUs = "0..1000 relative Y coordinate of the long press; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "duration_ms",
                    type = Type.INTEGER,
                    description = LocalizedText(zhCn = "长按时长，单位毫秒。", enUs = "Long-press duration in milliseconds."),
                    minimum = 0,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = true,
            coordinateAction = true,
            pointTargetAction = true,
            routeAction = false,
        ),
        ToolSpec(
            name = "input_text",
            kind = Kind.ACTION,
            uiLabel = LocalizedText(zhCn = "输入文本", enUs = "Input text"),
            description = LocalizedText(zhCn = "向一个可见输入目标输入文本；text、x、y 必须提供，target_description 可选。", enUs = "Type text into a visible input target; text, x, and y are required, while target_description is optional."),
            promptGuide = LocalizedText(zhCn = "- input_text(target_description?, text, x, y): 向输入框输入；text、x/y 是 required，target_description 用于补充 grounding。", enUs = "- input_text(target_description?, text, x, y): Type into an input field; text and x/y are required, and target_description is an optional grounding hint."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。目标输入框描述，用于补充 grounding。", enUs = "Optional. Description of the input target, used as a grounding hint."),
                ),
ArgSpec(
                    name = "text",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要输入的文本内容。", enUs = "Text content to type."),
                ),
ArgSpec(
                    name = "node_id",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。live XML 中已确认的无障碍节点 id。", enUs = "Optional. Confirmed accessibility node id from live XML."),
                ),
ArgSpec(
                    name = "node_resource_id",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs = "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer."),
                ),
ArgSpec(
                    name = "x",
                    type = Type.NUMBER,
                    required = true,
                    persisted = false,
                    description = LocalizedText(zhCn = "目标输入框中心的 required 0..1000 相对 X 坐标；执行前会解码为屏幕绝对像素。", enUs = "Required 0..1000 relative X coordinate of the input target center; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "y",
                    type = Type.NUMBER,
                    required = true,
                    persisted = false,
                    description = LocalizedText(zhCn = "目标输入框中心的 required 0..1000 相对 Y 坐标；执行前会解码为屏幕绝对像素。", enUs = "Required 0..1000 relative Y coordinate of the input target center; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = true,
            coordinateAction = true,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "swipe",
            kind = Kind.ACTION,
            uiLabel = LocalizedText(zhCn = "滑动", enUs = "Swipe"),
            description = LocalizedText(zhCn = "在屏幕或可滚动区域内按方向滑动。", enUs = "Swipe in a direction on the screen or inside a scrollable region."),
            promptGuide = LocalizedText(zhCn = "- swipe(target_description?, direction, x1, y1, x2, y2, duration_ms?): 在屏幕或指定可滚动区域内滑动；direction 和 x1/y1/x2/y2 必须提供，target_description 用于补充 grounding。", enUs = "- swipe(target_description?, direction, x1, y1, x2, y2, duration_ms?): Swipe on the screen or a target scrollable region; direction and x1/y1/x2/y2 are required, and target_description is an optional grounding hint."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。本次滑动想浏览或定位的目标描述，用于补充 grounding。", enUs = "Optional. Description of what this swipe action is trying to browse or locate, used as a grounding hint."),
                ),
ArgSpec(
                    name = "direction",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "滑动方向。", enUs = "Swipe direction."),
                    enumValues = listOf("up", "down", "left", "right"),
                ),
ArgSpec(
                    name = "x",
                    type = Type.NUMBER,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。滑动锚点的 0..1000 相对 X 坐标；不提供时使用安全兜底区域。", enUs = "Optional 0..1000 relative swipe anchor X coordinate; when omitted, the runtime uses a safe fallback region."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "y",
                    type = Type.NUMBER,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。滑动锚点的 0..1000 相对 Y 坐标；不提供时使用安全兜底区域。", enUs = "Optional 0..1000 relative swipe anchor Y coordinate; when omitted, the runtime uses a safe fallback region."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "distance",
                    type = Type.NUMBER,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。滑动距离，使用 0..1000 相对坐标尺度。", enUs = "Optional swipe distance using the 0..1000 relative coordinate scale."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "x1",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "滑动起点的 0..1000 相对 X 坐标；执行前会解码为屏幕绝对像素。", enUs = "Start 0..1000 relative X coordinate; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "y1",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "滑动起点的 0..1000 相对 Y 坐标；执行前会解码为屏幕绝对像素。", enUs = "Start 0..1000 relative Y coordinate; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "x2",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "滑动终点的 0..1000 相对 X 坐标；执行前会解码为屏幕绝对像素。", enUs = "End 0..1000 relative X coordinate; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "y2",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "滑动终点的 0..1000 相对 Y 坐标；执行前会解码为屏幕绝对像素。", enUs = "End 0..1000 relative Y coordinate; decoded to absolute screen pixels before execution."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "duration_ms",
                    type = Type.INTEGER,
                    description = LocalizedText(zhCn = "滑动时长，单位毫秒。", enUs = "Swipe duration in milliseconds."),
                    minimum = 0,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = true,
            coordinateAction = true,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "open_app",
            kind = Kind.ACTION,
            uiLabel = LocalizedText(zhCn = "打开应用", enUs = "Open app"),
            description = LocalizedText(zhCn = "打开指定应用。", enUs = "Open a specific app."),
            promptGuide = LocalizedText(zhCn = "- open_app(package_name): 打开指定应用。", enUs = "- open_app(package_name): Open a specific app."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "package_name",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "目标应用的 Android package name。", enUs = "Android package name of the target app."),
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = true,
        ),
        ToolSpec(
            name = "press_key",
            kind = Kind.ACTION,
            uiLabel = LocalizedText(zhCn = "按系统键", enUs = "Press key"),
            description = LocalizedText(zhCn = "按一个系统导航键；Enter 可选定目标输入框。", enUs = "Press one system navigation key; Enter may target a specific input field."),
            promptGuide = LocalizedText(zhCn = "- press_key(key, target_description?, x?, y?): 按系统键或文本编辑键；key 可以是 back、home、enter、select_all 或 copy。", enUs = "- press_key(key, target_description?, x?, y?): Press a system or text-editing key; key may be back, home, enter, select_all, or copy."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "key",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "系统键名称。", enUs = "System key name."),
                    enumValues = listOf("back", "home", "enter", "select_all", "copy"),
                ),
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。Enter 目标输入框描述。", enUs = "Optional description of the input field targeted by Enter."),
                ),
ArgSpec(
                    name = "node_resource_id",
                    type = Type.STRING,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。Enter 目标输入框的 Android resource-id。", enUs = "Optional Android resource-id of the input field targeted by Enter."),
                ),
ArgSpec(
                    name = "x",
                    type = Type.NUMBER,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。Enter 目标输入框中心的 0..1000 相对 X 坐标。", enUs = "Optional 0..1000 relative X coordinate of the input field targeted by Enter."),
                    minimum = 0,
                    maximum = 1000,
                ),
ArgSpec(
                    name = "y",
                    type = Type.NUMBER,
                    persisted = false,
                    description = LocalizedText(zhCn = "可选。Enter 目标输入框中心的 0..1000 相对 Y 坐标。", enUs = "Optional 0..1000 relative Y coordinate of the input field targeted by Enter."),
                    minimum = 0,
                    maximum = 1000,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = false,
            coordinateAction = true,
            pointTargetAction = false,
            routeAction = true,
        ),
        ToolSpec(
            name = "wait",
            kind = Kind.ACTION,
            uiLabel = LocalizedText(zhCn = "等待", enUs = "Wait"),
            description = LocalizedText(zhCn = "等待页面加载、动画或外部状态变化。", enUs = "Wait for page loading, animation, or external state changes."),
            promptGuide = LocalizedText(zhCn = "- wait(duration_ms): 只在页面明确处于加载、动画或等待外部状态变化时使用。", enUs = "- wait(duration_ms): Use only when the page is clearly loading, animating, or waiting for an external state change."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "duration_ms",
                    type = Type.INTEGER,
                    required = true,
                    description = LocalizedText(zhCn = "等待时长，单位毫秒。", enUs = "Wait duration in milliseconds."),
                    minimum = 0,
                ),
            ),
            modelVisible = false,
            replayable = true,
            editorVisible = true,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "get_state",
            kind = Kind.OBSERVE,
            uiLabel = LocalizedText(zhCn = "刷新状态", enUs = "Get state"),
            description = LocalizedText(zhCn = "不执行 UI 操作，只重新获取当前页面状态、包名和 Accessibility tree。", enUs = "Do not perform a UI action; refresh the current page state, package name, and Accessibility tree."),
            promptGuide = LocalizedText(zhCn = "- 内部状态刷新：运行时每轮自动读取当前页面状态；该动作不暴露给模型或前端，不点击、不滑动、不输入。", enUs = "- Internal state refresh: the runtime reads the current page state automatically each turn; this action is not exposed to the model or frontend and does not tap, swipe, or type."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "reason",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "为什么需要重新获取状态，例如上一步操作失败、页面无变化或当前页面不确定。", enUs = "Why state refresh is needed, such as previous action failed, page did not change, or current page is uncertain."),
                ),
            ),
            modelVisible = false,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "finished",
            kind = Kind.DECISION,
            uiLabel = LocalizedText(zhCn = "完成", enUs = "Finished"),
            description = LocalizedText(zhCn = "仅当当前页面或上一轮工具结果直接证明用户目标已经完成时结束。", enUs = "End only when the current page or previous tool result directly proves the user's goal is complete."),
            promptGuide = LocalizedText(zhCn = "- finished(content?): 仅在当前页面或上一轮工具结果直接证明目标完成时调用；不确定就继续执行下一步。", enUs = "- finished(content?): Call only when the current page or previous tool result directly proves completion; if uncertain, continue with the next action."),
            argsTemplate = mapOf("content" to "Done"),
            args = listOf(
ArgSpec(
                    name = "content",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "给用户的最终完成说明，可为空。", enUs = "Final completion note for the user. May be empty."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "info",
            kind = Kind.DECISION,
            uiLabel = LocalizedText(zhCn = "询问用户", enUs = "Info"),
            description = LocalizedText(zhCn = "向用户询问或请求手动协助。", enUs = "Ask the user a question or request manual help."),
            promptGuide = LocalizedText(zhCn = "- info(value): 询问用户或请求用户协助。", enUs = "- info(value): Ask the user for information or manual assistance."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "value",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "你要问用户的问题或需要用户执行的说明。", enUs = "Question to ask the user or instructions for the user to perform."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "feedback",
            kind = Kind.DECISION,
            uiLabel = LocalizedText(zhCn = "反馈", enUs = "Feedback"),
            description = LocalizedText(zhCn = "反馈当前上下文与目标不匹配。", enUs = "Report that the current context does not match the goal."),
            promptGuide = LocalizedText(zhCn = "- feedback(value): 请求上层重新规划。", enUs = "- feedback(value): Ask the upper layer to re-plan."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "value",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "反馈原因。", enUs = "Reason for the feedback."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "abort",
            kind = Kind.DECISION,
            uiLabel = LocalizedText(zhCn = "终止", enUs = "Abort"),
            description = LocalizedText(zhCn = "任务无法继续时终止。", enUs = "Abort when the task cannot continue."),
            promptGuide = LocalizedText(zhCn = "- abort(value?): 在任务无法继续时终止。", enUs = "- abort(value?): Abort when the task cannot continue."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "value",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "终止任务的原因。", enUs = "Reason for aborting the task."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "require_user_choice",
            kind = Kind.DECISION,
            uiLabel = LocalizedText(zhCn = "用户选择", enUs = "User choice"),
            description = LocalizedText(zhCn = "让用户在若干选项中选择一个。", enUs = "Ask the user to choose one option from a list."),
            promptGuide = LocalizedText(zhCn = "- require_user_choice(options, prompt): 让用户做互斥选择。", enUs = "- require_user_choice(options, prompt): Ask the user to make a mutually exclusive choice."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "options",
                    type = Type.STRING_ARRAY,
                    required = true,
                    description = LocalizedText(zhCn = "可供用户选择的选项列表。", enUs = "List of options the user can choose from."),
                ),
ArgSpec(
                    name = "prompt",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要求用户做选择的提示文案。", enUs = "Prompt shown to the user when asking for a choice."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "require_user_confirmation",
            kind = Kind.DECISION,
            uiLabel = LocalizedText(zhCn = "用户确认", enUs = "User confirmation"),
            description = LocalizedText(zhCn = "让用户确认当前状态后继续。", enUs = "Ask the user to confirm the current state before continuing."),
            promptGuide = LocalizedText(zhCn = "- require_user_confirmation(prompt): 让用户确认后继续。", enUs = "- require_user_confirmation(prompt): Ask the user to confirm before continuing."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "prompt",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要求用户确认的提示文案。", enUs = "Prompt asking the user for confirmation."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
    )

    val modelVisibleTools: List<ToolSpec> = tools.filter { it.modelVisible }
    val actionToolNames: Set<String> = tools.filter { it.kind == Kind.ACTION }.mapTo(linkedSetOf()) { it.name }
    val decisionToolNames: Set<String> = tools.filter { it.kind == Kind.DECISION }.mapTo(linkedSetOf()) { it.name }
    val replayableToolNames: Set<String> = tools.filter { it.replayable }.mapTo(linkedSetOf()) { it.name }
    val editorVisibleTools: List<ToolSpec> = tools.filter { it.editorVisible }
    val recordableToolNames: Set<String> = tools.filter { it.recordable }.mapTo(linkedSetOf()) { it.name }
    val coordinateToolNames: Set<String> = tools.filter { it.coordinateAction }.mapTo(linkedSetOf()) { it.name }
    val pointTargetToolNames: Set<String> = tools.filter { it.pointTargetAction }.mapTo(linkedSetOf()) { it.name }
    val routeToolNames: Set<String> = tools.filter { it.routeAction }.mapTo(linkedSetOf()) { it.name }
    private val toolsByName: Map<String, ToolSpec> = tools.associateBy { it.name }

    fun tool(name: String): ToolSpec? = toolsByName[normalizeToolName(name)]

    fun normalizeToolName(raw: String): String =
        raw.trim().lowercase()

    fun canonicalToolName(raw: String): String? {
        val normalized = normalizeToolName(raw)
        return normalized.takeIf { toolsByName.containsKey(it) }
    }

    fun argNames(toolName: String): Set<String> =
        tool(toolName)?.args?.mapTo(linkedSetOf()) { it.name } ?: emptySet()

    fun requiredArgNames(toolName: String): List<String> =
        tool(toolName)?.args?.filter { it.required }?.map { it.name } ?: emptyList()

    fun persistedArgs(toolName: String): List<ArgSpec> =
        tool(toolName)?.args?.filter { it.persisted } ?: emptyList()

    fun argsTemplate(toolName: String): Map<String, Any?> =
        tool(toolName)?.argsTemplate ?: emptyMap()

    fun supportsAdditionalProperties(toolName: String): Boolean =
        tool(toolName)?.args?.any { it.additionalProperties } == true
}
