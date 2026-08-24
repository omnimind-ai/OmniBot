// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Chinese (`zh`).
class AppLocalizationsZh extends AppLocalizations {
  AppLocalizationsZh([String locale = 'zh']) : super(locale);

  @override
  String get appName => '小万';

  @override
  String get brandName => '小万';

  @override
  String get brandNameEnglish => 'Omnibot';

  @override
  String get commonLoading => '加载中';

  @override
  String get homeDrawerSearchHint => '搜索全部对话';

  @override
  String get homeDrawerClearSearch => '清空搜索';

  @override
  String get themeModeTitle => '主题模式';

  @override
  String get themeModeSubtitle => '切换浅色、深色或跟随系统外观';

  @override
  String get themeModeLight => '浅色';

  @override
  String get themeModeDark => '深色';

  @override
  String get themeModeSystem => '系统';

  @override
  String get languageTitle => '语言';

  @override
  String get languageSubtitle => '设置应用界面、Agent 提示词与工具文案的显示语言';

  @override
  String get languageFollowSystem => '跟随系统';

  @override
  String get languageZhHans => '简体中文';

  @override
  String get languageEnglish => 'English';

  @override
  String get settingsTitle => '设置';

  @override
  String get settingsSectionModelMemory => '模型与记忆';

  @override
  String get settingsSectionServiceEnvironment => '服务与环境';

  @override
  String get settingsSectionExperienceAppearance => '体验与外观';

  @override
  String get settingsSectionPermissionInfo => '权限与信息';

  @override
  String get settingsModelProviderTitle => '模型提供商';

  @override
  String get settingsModelProviderSubtitle => '配置模型地址、密钥与模型列表';

  @override
  String get settingsSceneModelTitle => '场景模型配置';

  @override
  String get settingsSceneModelSubtitle => '按场景绑定模型，未绑定场景使用默认模型';

  @override
  String get settingsWorkspaceMemoryTitle => 'Workspace 记忆配置';

  @override
  String get settingsWorkspaceMemoryLoading => '加载中...';

  @override
  String get settingsWorkspaceMemoryEnabled => '已启用 workspace 记忆（嵌入检索可用）';

  @override
  String get settingsWorkspaceMemoryLexical => '使用 workspace 记忆（当前为词法检索）';

  @override
  String get settingsMcpToolsTitle => 'MCP 工具';

  @override
  String get settingsMcpToolsSubtitle => '添加、启停和管理远端 MCP 服务';

  @override
  String get settingsLocalServiceTitle => '本机服务';

  @override
  String get settingsLocalServiceSubtitle => '在局域网内访问小万 MCP 和 webchat 服务';

  @override
  String get settingsAlpineTitle => '终端环境';

  @override
  String get settingsAlpineSubtitle => '选择并管理应用内 Alpine 或 Ubuntu 终端系统';

  @override
  String get settingsHideRecentsTitle => '后台隐藏';

  @override
  String get settingsHideRecentsSubtitle => '开启后应用将从最近任务列表中隐藏';

  @override
  String get settingsRecentConversationsOnlyTitle => '仅显示近7天会话';

  @override
  String get settingsRecentConversationsOnlySubtitle =>
      '自动归档超过 7 天未更新的会话，减少侧边栏加载卡顿';

  @override
  String get settingsAlarmTitle => '闹钟设置';

  @override
  String get settingsAlarmSubtitle => '配置默认铃声、本地 mp3 或 mp3 直链';

  @override
  String get settingsAppearanceTitle => '外观设置';

  @override
  String get settingsAppearanceSubtitle => '配置主题模式、语言、共享背景图、聊天字号和文本颜色';

  @override
  String get settingsVibrationTitle => '振动反馈';

  @override
  String get settingsVibrationSubtitle => '执行任务时，通过振动进行操作提醒';

  @override
  String get settingsIndependentSendButtonTitle => '使用独立的发送按钮';

  @override
  String get settingsIndependentSendButtonSubtitle =>
      '开启后，聊天页键盘回车为换行；关闭后，回车直接发送';

  @override
  String get settingsPredictiveBackTitle => '预测性返回手势';

  @override
  String get settingsPredictiveBackSubtitle =>
      '开启后，返回手势可跟随手指预览上一页面或桌面；关闭则保持旧版返回行为';

  @override
  String get settingsHabitualHandTitle => '惯用手';

  @override
  String get settingsHabitualHandSubtitle => '影响聊天历史记录的侧滑菜单方向';

  @override
  String get settingsHabitualHandLeft => '左手';

  @override
  String get settingsHabitualHandRight => '右手';

  @override
  String get settingsAboutTitle => '关于小万';

  @override
  String get settingsHideRecentsFailed => '设置后台隐藏失败';

  @override
  String get settingsSaveFailed => '设置失败';

  @override
  String settingsMcpEnabledToast(Object endpoint) {
    return 'MCP 已开启：$endpoint';
  }

  @override
  String get settingsMcpDisabledToast => 'MCP 已关闭';

  @override
  String get settingsMcpToggleFailed => 'MCP 开关失败';

  @override
  String get settingsCopiedAddress => '已复制访问地址';

  @override
  String get settingsCopiedToken => '已复制 Token';

  @override
  String get settingsTokenRefreshed => '已刷新 Token';

  @override
  String get settingsTokenRefreshFailed => '刷新 Token 失败';

  @override
  String get settingsMcpLocalService => '本机服务';

  @override
  String get settingsMcpAddress => '地址';

  @override
  String get settingsMcpToken => 'Token';

  @override
  String get settingsNotGenerated => '未生成';

  @override
  String get settingsCopyAddress => '复制地址';

  @override
  String get settingsCopyToken => '复制 Token';

  @override
  String get settingsRefreshToken => '刷新 Token';

  @override
  String get settingsMcpSecurityNotice =>
      '请在同一局域网内使用 Authorization: Bearer <Token> 调用本机 MCP 服务，避免将地址或 Token 暴露到公网。';

  @override
  String get settingsInstalledAppsPermissionFailed => '请求应用列表权限失败';

  @override
  String get appearanceTitle => '外观设置';

  @override
  String get appearanceAutoSaving => '正在自动保存…';

  @override
  String get appearanceAutosaveHint => '更改会自动保存';

  @override
  String get appearanceBackgroundSource => '背景来源';

  @override
  String get appearancePreview => '效果预览';

  @override
  String get appearanceAdjustments => '效果调整';

  @override
  String get appearancePreviewChat => '聊天';

  @override
  String get appearancePreviewWorkspace => '工作区';

  @override
  String get appearanceEnableBackground => '启用背景图';

  @override
  String get appearanceEnableBackgroundSubtitle =>
      '同时作用于聊天页和 Workspace 页面，并自动保存';

  @override
  String get appearanceSourceLocal => '本地图片';

  @override
  String get appearanceSourceRemote => '图片直链';

  @override
  String get appearanceNoLocalImage => '尚未选择本地图片';

  @override
  String get appearancePickImage => '选择图片';

  @override
  String get appearanceRepickImage => '重新选择';

  @override
  String get appearanceRemoteImageUrl => '图片直链';

  @override
  String get appearanceRemoteImageUrlHint =>
      'https://example.com/background.jpg';

  @override
  String get appearanceBackgroundBlur => '背景柔化';

  @override
  String get appearanceBackgroundBlurSubtitle => '调节图片上方蒙版的柔化程度';

  @override
  String get appearanceOverlayIntensity => '蒙版强度';

  @override
  String get appearanceOverlayIntensitySubtitle => '增强统一蒙版，让页面元素更干净';

  @override
  String get appearanceOverlayBrightness => '蒙版明暗';

  @override
  String get appearanceOverlayBrightnessSubtitle => '提亮或压暗蒙版，不会直接修改原图';

  @override
  String get appearanceChatTextSize => '聊天文本大小';

  @override
  String get appearanceChatTextSizeSubtitle => '仅调整用户消息、AI 回复与思考区字号';

  @override
  String get appearanceTextColorTitle => '聊天文本颜色';

  @override
  String get appearanceTextColorSubtitle => '默认会自动跟随背景明暗，也可以改成固定颜色';

  @override
  String get appearanceTextColorAuto => '自动';

  @override
  String get appearanceCustomColorLabel => '自定义色号';

  @override
  String get appearanceCustomColorHint => '#FFFFFF 或 #FF112233';

  @override
  String get appearancePreviewTip => '图片可直接在上方预览里拖动和双指缩放，预览会尽量贴近实际效果。';

  @override
  String get appearanceColorWhite => '白';

  @override
  String get appearanceColorDarkGray => '深灰';

  @override
  String get appearanceColorLightBlue => '浅蓝';

  @override
  String get appearanceColorNavy => '藏蓝';

  @override
  String get appearanceColorTeal => '青绿';

  @override
  String get appearanceColorWarmYellow => '暖黄';

  @override
  String get appearanceInvalidHttpUrl => '请输入有效的 http(s) 图片直链';

  @override
  String get appearanceInvalidHexColor => '请输入 #RRGGBB 或 #AARRGGBB';

  @override
  String get appearanceInvalidHexColorFormat => '色号格式不正确';

  @override
  String appearancePickImageFailed(Object error) {
    return '选择图片失败：$error';
  }

  @override
  String get appearancePickLocalImageFirst => '请先选择本地图片';

  @override
  String get appearanceLocalImageMissing => '本地图片不存在，请重新选择';

  @override
  String appearanceAutosaveFailed(Object error) {
    return '自动保存失败：$error';
  }

  @override
  String get chatToolCalling => '正在调用工具';

  @override
  String get chatFallbackReply => '暂时无法生成回复，请重试。';

  @override
  String get chatPermissionRequired => '执行任务前需要先开启权限';

  @override
  String chatPermissionRequiredWithNames(Object names) {
    return '执行任务前，请先开启：$names';
  }

  @override
  String get chatRecentTerminalOutputNotice => '[只显示最近的部分终端输出]\n';

  @override
  String chatUserPrefix(Object text) {
    return '用户: $text\n';
  }

  @override
  String get permissionOverlay => '悬浮窗权限';

  @override
  String get permissionInstalledApps => '应用列表读取权限';

  @override
  String get permissionPublicStorage => '公共文件访问';

  @override
  String get browserOverlayTitle => 'Agent Browser';

  @override
  String get browserOverlayClose => '关闭浏览器窗口';

  @override
  String get browserOverlayUnsupported => '当前平台暂不支持浏览器工具视图';

  @override
  String get networkErrorMessage => '抱歉，刚刚网络开小差了。再发一次试试？';

  @override
  String get rateLimitErrorMessage => '小万忙不过来了，等会儿再试试吧';

  @override
  String get chatHistoryArchivedTitle => '归档对话';

  @override
  String get chatHistoryTitle => '聊天记录';

  @override
  String get chatHistoryNoArchived => '暂无归档对话';

  @override
  String get chatHistoryEmpty => '暂无聊天记录';

  @override
  String get chatHistoryArchivedToast => '已归档';

  @override
  String get chatHistoryUnarchivedToast => '已移出归档';

  @override
  String get chatHistoryArchiveFailed => '归档对话失败';

  @override
  String get chatHistoryUnarchiveFailed => '移出归档失败';

  @override
  String get chatHistoryArchiveHint => '左滑对话即可归档';

  @override
  String get homeDrawerArchive => '归档对话';

  @override
  String get homeDrawerNewChat => '新对话';

  @override
  String get webchatNoChats => '开始一个新的对话吧';

  @override
  String get memoryCenterTitle => '记忆中心';

  @override
  String get memoryShortTermTitle => '短期记忆';

  @override
  String get memoryLongTermTitle => '长期记忆';

  @override
  String get memoryNoShortTerm => '还没有短期记忆';

  @override
  String get memoryNoShortTermDesc => '会话中的过程性信息会沉淀到短期记忆，并在后续整理后转入长期记忆。';

  @override
  String get memoryFilteredNoShortTerm => '当前筛选下还没有短期记忆';

  @override
  String get memoryFilteredNoShortTermDesc => '稍后再来看看，新的短期记忆会逐步出现。';

  @override
  String get memoryNoLongTerm => '长期记忆还未初始化';

  @override
  String get memoryNoLongTermDesc => '记忆能力启用后，你的跨会话长期记忆会在这里持续沉淀。';

  @override
  String get memoryDeleteConfirmTitle => '确定删除吗？';

  @override
  String get memoryDeleteWarning => '删除后该内容将不可找回';

  @override
  String get memoryEditDisabled => '短期记忆暂不支持编辑';

  @override
  String get memoryDeleteDisabled => '短期记忆暂不支持删除';

  @override
  String get memoryGreeting => '你好呀，\n欢迎回来，我们会在这里慢慢整理你的记忆。';

  @override
  String memorySelectedCount(Object n) {
    return '已选择$n项';
  }

  @override
  String get memoryDeselectAll => '全不选';

  @override
  String get memoryEditTitle => '编辑记忆';

  @override
  String get memoryIdLabel => '记忆 ID';

  @override
  String get memoryMatchScore => '匹配度';

  @override
  String get memoryAdditionalInfo => '附加信息';

  @override
  String get memoryAddLongTerm => '新增长期记忆';

  @override
  String get memorySaveToLongTerm => '保存到长期记忆';

  @override
  String get memoryLongTermAdded => '长期记忆已新增';

  @override
  String get memoryEditLongTerm => '编辑长期记忆';

  @override
  String get memorySaveChanges => '保存修改';

  @override
  String get memoryDeleteLongTermConfirm => '删除这条长期记忆？';

  @override
  String get memoryLongTermDeleted => '长期记忆已删除';

  @override
  String memoryLongTermFailed(Object error) {
    return '长期记忆操作失败：$error';
  }

  @override
  String get memoryNoMemories => '暂无记忆';

  @override
  String get memoryNoMemoriesDesc => '快去探索，添加喜欢的内容吧';

  @override
  String get pluginMarketTitle => '插件市场';

  @override
  String get pluginMarketEmpty => '暂无可用插件';

  @override
  String get pluginMarketEmptyDesc => '官方插件接入后会显示在这里';

  @override
  String get pluginInstall => '安装';

  @override
  String get pluginUpdate => '更新';

  @override
  String get pluginUninstall => '卸载';

  @override
  String get pluginCancel => '取消';

  @override
  String get pluginNoDescription => '暂无描述';

  @override
  String get pluginIncompatible => '此插件与当前版本不兼容';

  @override
  String get pluginLoadFailed => '加载插件市场失败';

  @override
  String get pluginInstallFailed => '插件安装失败';

  @override
  String get pluginUpdateFailed => '插件更新失败';

  @override
  String get pluginToggleFailed => '插件启停失败';

  @override
  String get pluginUninstallFailed => '插件卸载失败';

  @override
  String get pluginUninstallTitle => '卸载插件';

  @override
  String pluginUninstallConfirmMsg(Object name) {
    return '确认卸载\"$name\"？';
  }

  @override
  String pluginInstalledMsg(Object name) {
    return '已安装 $name';
  }

  @override
  String pluginUpdatedMsg(Object name) {
    return '已更新 $name';
  }

  @override
  String pluginEnabledMsg(Object name) {
    return '已启用 $name';
  }

  @override
  String pluginDisabledMsg(Object name) {
    return '已停用 $name';
  }

  @override
  String pluginUninstalledMsg(Object name) {
    return '已卸载 $name';
  }

  @override
  String get pluginKindBundledModule => '内置模块';

  @override
  String get pluginKindRuntimeBundle => '运行时包';

  @override
  String get pluginKindCompanionApp => '伴生应用';

  @override
  String get pluginDetailTitle => '插件详情';

  @override
  String get pluginSearchHint => '搜索插件名称、描述或能力';

  @override
  String get pluginSearchEmpty => '未找到匹配的插件';

  @override
  String get pluginAboutTitle => '关于';

  @override
  String get pluginCapabilitiesTitle => '能力';

  @override
  String get pluginNoCapabilities => '此插件未声明额外能力';

  @override
  String get pluginInformationTitle => '信息';

  @override
  String get pluginPublisherLabel => '开发者';

  @override
  String get pluginVersionLabel => '版本';

  @override
  String get pluginTypeLabel => '类型';

  @override
  String get pluginDownloadSizeLabel => '下载大小';

  @override
  String get pluginInterfaceVersionLabel => '接口版本';

  @override
  String get pluginStatusInstalled => '已安装';

  @override
  String get pluginStatusEnabled => '已启用';

  @override
  String get pluginStatusNotInstalled => '未安装';

  @override
  String get pluginEnableTitle => '启用插件';

  @override
  String get pluginEnableDescription => '允许 Agent 使用此插件提供的能力';

  @override
  String get pluginRetry => '重试';

  @override
  String get skillStoreTitle => '技能仓库';

  @override
  String get skillBuiltin => '内置';

  @override
  String get skillOfficial => '官方';

  @override
  String get skillUser => '用户';

  @override
  String get skillInstalled => '已安装';

  @override
  String get skillNotInstalled => '未安装';

  @override
  String get skillEnabled => '启用中';

  @override
  String get skillDisabled => '已禁用';

  @override
  String get skillInstall => '安装';

  @override
  String get skillDelete => '删除';

  @override
  String get skillEmpty => '暂无已接入的技能';

  @override
  String get skillNoDescription => '暂无描述';

  @override
  String get skillBuiltinRemovedDesc => '该内置技能已从工作区移除，可随时重新安装。';

  @override
  String get skillDeleteTitle => '删除技能';

  @override
  String skillDeleteConfirmMsg(Object name) {
    return '确认删除\"$name\"？';
  }

  @override
  String get skillDeleted => '已删除';

  @override
  String get skillDeleteFailed => '删除失败';

  @override
  String skillInstalledMsg(Object name) {
    return '已安装 $name';
  }

  @override
  String get skillInstallFailed => '安装失败';

  @override
  String skillEnabledMsg(Object name) {
    return '已启用 $name';
  }

  @override
  String skillDisabledMsg(Object name) {
    return '已禁用 $name';
  }

  @override
  String get skillToggleFailed => '切换失败';

  @override
  String get skillSyncOfficialTooltip => '安装/更新官方 Skills';

  @override
  String skillSyncOfficialSuccess(Object count) {
    return '官方 Skills 已同步（$count 个）';
  }

  @override
  String get skillSyncOfficialFailed => '同步官方 Skills 失败';

  @override
  String get skillLoadFailed => '加载技能仓库失败';

  @override
  String get modelProviderConfigTitle => 'Provider 配置';

  @override
  String get modelProviderConfigDesc => '新增、切换并维护模型服务提供商的名称、地址与密钥。';

  @override
  String get modelProviderName => 'Provider 名称';

  @override
  String get modelProviderNameHint => '例如：DeepSeek';

  @override
  String get modelProviderBaseUrlHint => '末尾加 # 可禁用自动补全请求路径';

  @override
  String get modelProviderApiKeyHint => '未填写 API Key 时，会以无鉴权方式请求 Provider。';

  @override
  String get modelListTitle => '模型列表';

  @override
  String get modelListDesc => '支持手动补充模型，也可从当前 Provider 拉取远端模型清单。';

  @override
  String modelListCount(Object count) {
    return '共 $count 个模型';
  }

  @override
  String get modelAddPrompt => '请添加模型！';

  @override
  String get modelBuiltinProvider => '内置 Provider';

  @override
  String get modelIdEmpty => '模型 ID 不能为空且不能以 scene. 开头';

  @override
  String get modelAlreadyExists => '模型已存在';

  @override
  String get modelAdded => '已添加模型';

  @override
  String get modelDeleted => '已删除模型';

  @override
  String get modelDeleteFailed => '删除模型失败';

  @override
  String get modelIdHint => '请输入模型 ID';

  @override
  String get modelAddProviderTitle => '新增 Provider';

  @override
  String get modelAddButton => '新增';

  @override
  String get modelProviderAdded => '已新增 Provider';

  @override
  String modelProviderAddFailed(Object error) {
    return '新增 Provider 失败：$error';
  }

  @override
  String get modelDeleteProviderTitle => '删除 Provider';

  @override
  String modelDeleteProviderMsg(Object name) {
    return '确定删除\"$name\"吗？场景绑定会保留，但需要重新选择可用 Provider。';
  }

  @override
  String get modelProviderDeleted => '已删除 Provider';

  @override
  String modelProviderDeleteFailed(Object error) {
    return '删除 Provider 失败：$error';
  }

  @override
  String get modelProviderLoadFailed => '加载模型提供商配置失败';

  @override
  String modelProviderSwitchFailed(Object error) {
    return '切换 Provider 失败：$error';
  }

  @override
  String get modelProviderBaseUrlRequired => '请先填写 Base URL';

  @override
  String get modelProviderInvalidBaseUrl => '请输入有效的 http(s) Base URL';

  @override
  String modelProviderFetchedModels(Object count) {
    return '已获取 $count 个模型';
  }

  @override
  String modelProviderFetchFailed(Object error) {
    return '拉取模型列表失败：$error';
  }

  @override
  String get sceneModelMapping => '场景映射';

  @override
  String get sceneModelMappingDesc => '按场景绑定 Provider 与模型，未绑定的场景会继续使用默认模型。';

  @override
  String get sceneModelRefreshList => '刷新模型列表';

  @override
  String get sceneModelSearchHint =>
      '点击右侧按钮后，可按 Provider 搜索、折叠并选择模型；顶部搜索框固定不随列表滚动。';

  @override
  String get sceneModelNoScenes => '暂无可配置场景';

  @override
  String get sceneModelLoadFailed => '加载场景模型配置失败';

  @override
  String sceneModelPartialUpdateFailed(Object profiles) {
    return '部分模型已更新，但这些 Provider 刷新失败：$profiles';
  }

  @override
  String sceneModelUpdatedModels(Object count) {
    return '已更新 $count 个模型';
  }

  @override
  String sceneModelRefreshFailed(Object error) {
    return '刷新模型列表失败：$error';
  }

  @override
  String get sceneModelInvalidModelId => '模型 ID 不能以 scene. 开头';

  @override
  String sceneModelBoundToast(Object scene, Object model) {
    return '已将 $scene 绑定到 $model';
  }

  @override
  String sceneModelSaveFailed(Object scene, Object error) {
    return '保存 $scene 配置失败：$error';
  }

  @override
  String sceneModelBindingCleared(Object scene) {
    return '已清除 $scene 的绑定';
  }

  @override
  String sceneModelDefaultRestored(Object scene) {
    return '$scene 已恢复为默认模型';
  }

  @override
  String sceneModelClearFailed(Object scene, Object error) {
    return '清除 $scene 配置失败：$error';
  }

  @override
  String get modelsNoAvailableModels => '暂无可用模型';

  @override
  String get alarmSaved => '闹钟设置已保存';

  @override
  String get alarmRingtoneSource => '铃声来源';

  @override
  String get alarmSystemDefault => '系统默认铃声';

  @override
  String get alarmSystemDefaultDesc => '无需额外配置，兼容性最好';

  @override
  String get alarmLocalMp3 => '本地 mp3';

  @override
  String get alarmLocalMp3Desc => '选择手机内 mp3 作为闹钟铃声';

  @override
  String get alarmMp3Url => 'mp3 直链';

  @override
  String get alarmMp3UrlDesc => '使用 http(s) 直链播放在线 mp3';

  @override
  String get alarmAudioPermissionDenied => '读取音频权限未授予';

  @override
  String get alarmInvalidFilePath => '文件路径无效，请重新选择';

  @override
  String get alarmSelectLocalFirst => '请先选择本地 mp3 文件';

  @override
  String get alarmEnterHttpsUrl => '请输入 http(s) 开头的 mp3 直链';

  @override
  String get alarmLocalFile => '本地文件';

  @override
  String get alarmSelectMp3 => '选择 mp3 文件';

  @override
  String get authorizePageTitle => '应用权限授权';

  @override
  String get authorizeReceiveNotifications => '接收消息通知';

  @override
  String get authorizeNotificationsDesc => '打开后可以及时了解任务进展';

  @override
  String get storageUsageTitle => '存储占用';

  @override
  String get storageUsageSubtitle => '查看空间占用明细，支持分项清理';

  @override
  String get storageAnalyzeFailed => '存储分析失败，请重试';

  @override
  String storageCategoryCleaned(Object name, Object size) {
    return '已清理$name，释放 $size';
  }

  @override
  String get storageCleanFailed => '清理失败，请稍后重试';

  @override
  String storageCleanCategory(Object name) {
    return '清理$name';
  }

  @override
  String get storageCleanConfirmMsg => '确认清理该分类数据吗？';

  @override
  String get storageCleanScope => '清理范围';

  @override
  String get storageCleanAll => '全部';

  @override
  String get storageClean7Days => '7天前';

  @override
  String get storageClean30Days => '30天前';

  @override
  String storageStrategyName(Object name) {
    return '执行策略：$name';
  }

  @override
  String storageStrategyDone(Object size) {
    return '策略执行完成，释放 $size';
  }

  @override
  String storageStrategyPartialDone(Object count, Object size) {
    return '策略完成，释放 $size，$count 项未完全成功';
  }

  @override
  String get storageStrategyFailed => '策略执行失败，请稍后重试';

  @override
  String get storageLoadFailed => '加载失败';

  @override
  String get storageReanalyze => '重新分析';

  @override
  String get storageTotalUsage => '总占用';

  @override
  String get storageAppSize => '应用大小';

  @override
  String get storageUserData => '用户数据';

  @override
  String get storageCleanable => '可清理';

  @override
  String storageStatsSource(Object source) {
    return '统计口径：$source';
  }

  @override
  String storagePackageName(Object name) {
    return '当前包名：$name';
  }

  @override
  String get storageTrendFirst => '这是首次分析，后续将展示占用变化趋势';

  @override
  String get storageSmartCleanup => '智能清理策略';

  @override
  String get storageExecute => '执行';

  @override
  String get storageUsageAnalysis => '占用分析';

  @override
  String get storageClean => '清理';

  @override
  String get storageRiskLow => '低风险';

  @override
  String get storageRiskCaution => '谨慎';

  @override
  String get storageRiskHigh => '高风险';

  @override
  String get storageReadOnly => '只读';

  @override
  String get storageSystemStats => '系统统计（与系统设置更接近）';

  @override
  String get storageDirectoryScan => '目录扫描估算';

  @override
  String get storageAdditionalInfo => '附加信息';

  @override
  String get storageCatAppBinary => '应用安装包';

  @override
  String get storageCatAppBinaryDesc => '应用安装文件占用（APK/AAB split）';

  @override
  String get storageCatCache => '缓存';

  @override
  String get storageCatCacheDesc => '临时文件与图片缓存，可安全清理';

  @override
  String get storageCatCacheHint => '清理后会在使用中自动重新生成';

  @override
  String get storageCatConversation => '会话历史';

  @override
  String get storageCatConversationDesc => '对话与工具执行历史（估算）';

  @override
  String get storageCatConversationHint => '会删除历史消息记录，且不可恢复';

  @override
  String get storageCatDatabaseOther => '数据库其他占用';

  @override
  String get storageCatDatabaseOtherDesc => '索引与系统表等数据库占用';

  @override
  String get storageCatWorkspaceBrowser => 'Workspace 浏览器产物';

  @override
  String get storageCatWorkspaceBrowserDesc => '浏览器截图、下载文件和中间产物';

  @override
  String get storageCatWorkspaceBrowserHint => '会删除浏览器工具相关的中间文件';

  @override
  String get storageCatWorkspaceOffloads => 'Workspace Offloads';

  @override
  String get storageCatWorkspaceOffloadsDesc => '工具离线输出与临时文件';

  @override
  String get storageCatWorkspaceOffloadsHint => '仅删除离线产物，不影响核心功能';

  @override
  String get storageCatWorkspaceAttachments => 'Workspace 附件';

  @override
  String get storageCatWorkspaceAttachmentsDesc => '历史任务使用的附件文件';

  @override
  String get storageCatWorkspaceAttachmentsHint => '可能影响历史任务对附件的回看';

  @override
  String get storageCatWorkspaceShared => 'Workspace 共享区';

  @override
  String get storageCatWorkspaceSharedDesc => '跨任务共享的工作区文件';

  @override
  String get storageCatWorkspaceSharedHint => '可能影响后续任务复用共享文件';

  @override
  String get storageCatWorkspaceMemory => 'Workspace 记忆数据';

  @override
  String get storageCatWorkspaceMemoryDesc => '长期/短期记忆与索引数据';

  @override
  String get storageCatWorkspaceUserFiles => 'Workspace 用户文件';

  @override
  String get storageCatWorkspaceUserFilesDesc => '用户主动保存到 workspace 的文件';

  @override
  String get storageCatTerminalLocal => '终端运行时（local）';

  @override
  String get storageCatTerminalLocalDesc => 'Alpine/Ubuntu 终端 local 运行目录';

  @override
  String get storageCatTerminalLocalHint => '会删除终端 local 目录，需重新初始化';

  @override
  String get storageCatTerminalBootstrap => '终端运行时（引导文件）';

  @override
  String get storageCatTerminalBootstrapDesc => 'proot/lib/rootfs 引导文件';

  @override
  String get storageCatTerminalBootstrapHint => '会删除终端引导文件，需重新初始化';

  @override
  String get storageCatSharedDrafts => '共享草稿';

  @override
  String get storageCatSharedDraftsDesc => '外部分享导入的草稿缓存';

  @override
  String get storageCatSharedDraftsHint => '会删除未发送的草稿附件';

  @override
  String get storageCatMcpInbox => 'MCP 收件箱';

  @override
  String get storageCatMcpInboxDesc => 'MCP 文件传输接收目录';

  @override
  String get storageCatMcpInboxHint => '会删除 MCP 收件箱中的文件';

  @override
  String get storageCatLegacyWorkspace => '旧版遗留数据';

  @override
  String get storageCatLegacyWorkspaceDesc => '升级后可能残留的旧 workspace 目录';

  @override
  String get storageCatLegacyWorkspaceHint => '建议确认无用后再清理';

  @override
  String get storageCatOtherUserData => '其他数据';

  @override
  String get storageCatOtherUserDataDesc => '未命中分类规则的数据';

  @override
  String get storageStrategySafeQuick => '安全快速清理';

  @override
  String get storageStrategySafeQuickDesc => '优先清理低风险缓存与临时产物';

  @override
  String get storageStrategyBalanceDeep => '平衡深度清理';

  @override
  String get storageStrategyBalanceDeepDesc => '释放更多空间，保留核心用户数据与文件';

  @override
  String get storageStrategyFree1gb => '目标释放 1GB';

  @override
  String get storageStrategyFree1gbDesc => '按高收益顺序清理，尽量达到 1GB 释放目标';

  @override
  String get storageHintConversation => '如历史未释放，请重新进入页面执行「重新分析」';

  @override
  String get storageHintTerminal => '终端运行时被清理后，可在终端环境页重新初始化';

  @override
  String get storageHintGeneral => '若清理失败，可稍后重试或重启应用后再次清理';

  @override
  String get storageHintNotCleanable => '该分类当前不可清理';

  @override
  String get storageHintSkipped => '该分类已跳过（可选项）';

  @override
  String storageCleanPartialFailed(Object hint) {
    return '部分清理失败：$hint';
  }

  @override
  String get storageCleanPartialFailedGeneric => '部分文件清理失败，请稍后重试';

  @override
  String storageTrendVsLast(Object cleanable, Object total) {
    return '对比上次分析：总计 $total，可清理 $cleanable';
  }

  @override
  String storageLastAnalyzed(Object time) {
    return '上次分析时间：$time';
  }

  @override
  String get aboutDescription =>
      '小万，是一款以智能对话为核心的手机AI助\n手，通过语义理解与持续学习能力，协助用户\n完成信息处理、决策辅助和日常管理。';

  @override
  String get aboutBetaProgramTitle => '加入 beta 测试';

  @override
  String get aboutBetaProgramDescription => '接收更快的四段版更新。';

  @override
  String get aboutBetaProgramToggleFailed => 'beta 测试设置更新失败';

  @override
  String get aboutPreferencesSectionTitle => '更新与测试';

  @override
  String get aboutApkSourceTitle => '安装包下载源';

  @override
  String get aboutApkSourceDescription => '选择安装更新时使用的下载源。';

  @override
  String get aboutApkSourceDisclaimer =>
      '使用本软件即表示您同意我们的隐私政策，并同意我们通过开源的更新 Worker 收集匿名使用信息，以持续改进软件。因使用本软件产生的任何损失或后果由您自行承担。';

  @override
  String get aboutApkSourceOptionCnb => 'Cloudflare R2';

  @override
  String get aboutApkSourceOptionCnbDescription => '通过更新 Worker 分发';

  @override
  String get aboutApkSourceOptionGithub => 'GitHub';

  @override
  String get aboutApkSourceOptionGithubDescription => '官方 Release';

  @override
  String get aboutApkSourceSwitchFailed => '安装包下载源切换失败';

  @override
  String get aboutUpdateHintDefault => '检查更新获取最新版本';

  @override
  String get workspaceMemoryLoadFailed => '加载 workspace 记忆配置失败';

  @override
  String get agentSoulSaved => 'Agent 灵魂设置已保存';

  @override
  String get agentSoulSaveFailed => 'Agent 灵魂设置保存失败';

  @override
  String get chatPromptSaved => '纯聊天系统提示词已保存';

  @override
  String get chatPromptSaveFailed => '纯聊天系统提示词保存失败';

  @override
  String get workspaceMemorySaved => 'MEMORY.md 已保存';

  @override
  String get workspaceMemorySaveFailed => 'MEMORY.md 保存失败';

  @override
  String get workspaceEmbeddingToggleFailed => '记忆嵌入开关更新失败';

  @override
  String get workspaceRollupToggleFailed => '夜间整理开关更新失败';

  @override
  String get workspaceRollupDone => '整理完成';

  @override
  String get workspaceRollupFailed => '立即整理失败';

  @override
  String get workspaceNone => '暂无';

  @override
  String get workspaceMemoryTitle => 'Workspace 记忆';

  @override
  String get workspaceMemoryCapability => '记忆能力';

  @override
  String get workspaceEmbeddingReady => '已配置，可使用向量检索';

  @override
  String get workspaceEmbeddingNotReady => '未配置，将自动降级为词法检索';

  @override
  String get workspaceGoToConfig => '去场景模型配置记忆嵌入模型';

  @override
  String get workspaceNightlyRollup => '夜间记忆整理（22:00）';

  @override
  String workspaceLastRun(Object time) {
    return '最近运行：$time';
  }

  @override
  String workspaceNextRun(Object time) {
    return '下次运行：$time';
  }

  @override
  String get workspaceRollupNow => '立即整理一次';

  @override
  String get workspaceSettingsAndMemory => 'Agent 设置与记忆';

  @override
  String get agentSoulSetting => 'Agent 灵魂';

  @override
  String get chatPromptSetting => '纯聊天系统提示词';

  @override
  String get workspaceMemoryMd => 'MEMORY.md（长期记忆）';

  @override
  String get alpineNodeJs => 'Node.js 运行时';

  @override
  String get alpineNpm => 'Node.js 包管理器';

  @override
  String get alpineGit => 'Git 版本控制';

  @override
  String get alpinePython => 'Python 解释器';

  @override
  String get alpinePip => 'Python 项目与包工具';

  @override
  String get alpinePipInstall => 'Python 包安装器';

  @override
  String get alpineCodex => 'OpenAI Codex CLI（供 ACP Agent 使用）';

  @override
  String get alpineClaudeCode => 'Anthropic Claude Code CLI（供 ACP Agent 使用）';

  @override
  String get alpineOpenCode => 'OpenCode CLI（内置 ACP 支持）';

  @override
  String get alpineDeepSeekHarness =>
      'DeepSeek Harness（dsh）ACP 运行组件，安装时跟随 npm next 最新发布线';

  @override
  String get alpineSshClient => 'SSH 客户端';

  @override
  String get alpineSshpass => 'SSH 密码辅助工具';

  @override
  String get alpineOpenSshServer => 'OpenSSH 服务器';

  @override
  String get alpineDetectFailed => '检测终端环境失败';

  @override
  String get alpineBootTasksLoadFailed => '读取自启动任务失败';

  @override
  String get alpineConfigOpenFailed => '打开终端环境配置失败';

  @override
  String get alpineBootTaskAdded => '已新增自启动任务';

  @override
  String get alpineBootTaskUpdated => '已更新自启动任务';

  @override
  String get alpineBootTaskSaveFailed => '保存自启动任务失败';

  @override
  String get alpineBootEnabled => '已开启应用启动时自启动';

  @override
  String get alpineBootDisabled => '已关闭自动启动';

  @override
  String get alpineBootTaskUpdateFailed => '更新任务失败';

  @override
  String get alpineDeleteBootTask => '删除自启动任务';

  @override
  String alpineDeleteBootTaskMsg(Object name) {
    return '确认删除\"$name\"吗？';
  }

  @override
  String get alpineBootTaskDeleted => '已删除自启动任务';

  @override
  String get alpineBootTaskDeleteFailed => '删除任务失败';

  @override
  String get alpineCommandSent => '启动命令已发送';

  @override
  String get alpineStartFailed => '启动任务失败';

  @override
  String get alpineDetecting => '正在检测环境';

  @override
  String alpineStartConfig(Object count) {
    return '开始配置（$count 项）';
  }

  @override
  String get alpineAllReady => '全部已就绪';

  @override
  String get alpineDetectingDesc => '正在后台检测当前终端系统内常见开发环境的版本信息。';

  @override
  String alpineReadyCount(Object ready, Object total) {
    return '当前终端系统已就绪 $ready/$total 项，可直接勾选缺失项并进入 ReTerminal 自动配置。';
  }

  @override
  String get alpineBootTasks => '自启动任务';

  @override
  String get alpineBootTasksDesc =>
      '打开 Omnibot 时会在后台检查已启用的任务，并在对应 ReTerminal 会话内启动命令，适合常驻服务。';

  @override
  String get alpineAddTask => '新增任务';

  @override
  String get alpineOpenTerminal => '打开终端';

  @override
  String get alpineNoTasksDesc =>
      '暂无任务。你可以添加例如 `python app.py`、`node server.js`、`./start.sh` 之类的常驻命令。';

  @override
  String get alpineBootOnAppOpen => '开机打开 app 后启动';

  @override
  String get alpineNotEnabled => '未启用';

  @override
  String get alpineRunning => '已在运行';

  @override
  String get alpineStartNow => '立即启动';

  @override
  String get alpineEdit => '编辑';

  @override
  String get alpineVersionDetected => '已检测到可用版本';

  @override
  String get alpineVersionNotFound => '未检测到';

  @override
  String get alpineTaskNameHint => '请输入任务名称';

  @override
  String get alpineCommandHint => '请输入启动命令';

  @override
  String get alpineEditBootTask => '编辑自启动任务';

  @override
  String get alpineAddBootTask => '新增自启动任务';

  @override
  String get alpineTaskName => '任务名称';

  @override
  String get alpineTaskNameExample => '例如：本地 API 服务';

  @override
  String get alpineStartCommand => '启动命令';

  @override
  String get alpineCommandExample => '例如：python app.py 或 pnpm start';

  @override
  String get alpineWorkDir => '工作目录';

  @override
  String get alpineBootAutoStart => '打开小万时自动启动';

  @override
  String get alpineDevEnv => '开发环境';

  @override
  String get alpineAiAgent => 'AI Agent';

  @override
  String get alpineEnvConfig => '环境配置';

  @override
  String alpineWorkDirValue(Object dir) {
    return '工作目录：$dir';
  }

  @override
  String get workspaceEmbeddingRetrieval => '记忆嵌入检索';

  @override
  String get chatHistoryStartConversation => '开始对话';

  @override
  String get homeDrawerSearching => '正在搜索对话内容…';

  @override
  String get homeDrawerNoResults => '没有找到相关对话';

  @override
  String get homeDrawerSearchHint2 => '试试更短的关键词，或换一种说法';

  @override
  String get homeDrawerSearchResults => '搜索结果';

  @override
  String get homeDrawerResultCount => '条';

  @override
  String get homeDrawerScheduled => '定时';

  @override
  String get homeDrawerScheduledTasks => '定时任务';

  @override
  String get homeDrawerPinnedConversations => '置顶会话';

  @override
  String get homeDrawerAgentSection => 'Agent';

  @override
  String get homeDrawerOmniAiSection => '小万';

  @override
  String get homeDrawerChatOnlySection => '纯聊天';

  @override
  String get homeDrawerAgentNoProject => '其他';

  @override
  String get homeDrawerGreeting => '你好！';

  @override
  String get homeDrawerWelcome => '欢迎使用小万';

  @override
  String get homeDrawerDawnGreeting => '凌晨啦';

  @override
  String get homeDrawerDawnSub => '还没休息吗？';

  @override
  String get homeDrawerDawnGreeting2 => '天还没亮';

  @override
  String get homeDrawerDawnSub2 => '早起的你辛苦啦～';

  @override
  String get homeDrawerDawnGreeting3 => '深夜的时光很静';

  @override
  String get homeDrawerDawnSub3 => '但也要记得给身体留些休息呀～';

  @override
  String get homeDrawerMorningGreeting => '早安！';

  @override
  String get homeDrawerMorningSub => '开启元气一天';

  @override
  String get homeDrawerMorningGreeting2 => '早呀！';

  @override
  String get homeDrawerMorningSub2 => '新的一天开始啦';

  @override
  String get homeDrawerForenoonGreeting => '上午好！';

  @override
  String get homeDrawerForenoonSub => '再忙也别忘了活动下肩膀';

  @override
  String get homeDrawerForenoonGreeting2 => '上午的效率超棒！';

  @override
  String get homeDrawerForenoonSub2 => '继续加油';

  @override
  String get homeDrawerLunchGreeting => '午饭时间到！';

  @override
  String get homeDrawerLunchSub => '好好吃饭，别凑合';

  @override
  String get homeDrawerLunchGreeting2 => '午安～';

  @override
  String get homeDrawerLunchSub2 => '吃完记得歇会儿';

  @override
  String get homeDrawerLunchGreeting3 => '午餐不知道吃什么？';

  @override
  String get homeDrawerLunchSub3 => '让小万帮你推荐吧！';

  @override
  String get homeDrawerAfternoonGreeting => '喝杯茶提提神';

  @override
  String get homeDrawerAfternoonSub => '剩下的任务也能轻松搞定～';

  @override
  String get homeDrawerAfternoonGreeting2 => '工作间隙看看窗外';

  @override
  String get homeDrawerAfternoonSub2 => '让眼睛歇一歇～';

  @override
  String get homeDrawerEveningGreeting => '回家路上慢点';

  @override
  String get homeDrawerEveningSub => '今晚好好放松～';

  @override
  String get homeDrawerEveningGreeting2 => '傍晚了';

  @override
  String get homeDrawerEveningSub2 => '吹来的晚风很舒服呀！～';

  @override
  String get homeDrawerEveningGreeting3 => '忙了一天';

  @override
  String get homeDrawerEveningSub3 => '吃顿好的犒劳自己～';

  @override
  String get homeDrawerNightGreeting => '晚上好！';

  @override
  String get homeDrawerNightSub => '享受属于自己的时光吧～';

  @override
  String get homeDrawerNightGreeting2 => '夜色渐浓';

  @override
  String get homeDrawerNightSub2 => '准备下早点休息啦～';

  @override
  String get homeDrawerNightGreeting3 => '该休息了';

  @override
  String get homeDrawerNightSub3 => '让小万帮你定个闹钟吧！';

  @override
  String get homeDrawerLateNightGreeting => '放下手机早点睡';

  @override
  String get homeDrawerLateNightSub => '明天才能元气满满～';

  @override
  String get homeDrawerLateNightGreeting2 => '深夜了';

  @override
  String get homeDrawerLateNightSub2 => '好好和今天说晚安～';
}
