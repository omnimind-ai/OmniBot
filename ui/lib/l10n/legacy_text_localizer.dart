import 'dart:ui';

import 'package:ui/services/storage_service.dart';

typedef _TextRewriter = String Function(RegExpMatch match);

class LegacyTextLocalizer {
  static Locale? _activeLocale;

  static final Map<String, String> _exactEn = <String, String>{
    '宠物': 'Pet',
    '小万': 'Omnibot',
    '小万（OmniAi）': 'OmniAi',
    '默认的桌面悬浮窗宠物': 'Default desktop floating pet',
    '自定义宠物': 'Custom Pet',
    '已选': 'Selected',
    '选择': 'Select',
    '刷新': 'Refresh',
    '唤起宠物': 'Show Pet',
    '收起宠物': 'Hide Pet',
    '宠物列表已刷新': 'Pet list refreshed',
    '选择宠物失败': 'Failed to select pet',
    '导入 Codex 宠物包': 'Import Codex Pet Package',
    '支持 .codex-pet.zip（pet.json + spritesheet.webp）':
        'Supports .codex-pet.zip (pet.json + spritesheet.webp)',
    '上传压缩包': 'Upload Package',
    '宠物已导入并选中': 'Pet imported and selected',
    '导入宠物失败': 'Failed to import pet',
    'Codex 动态宠物 · 7 类动作': 'Codex animated pet · 7 action types',
    '收起宠物失败': 'Failed to hide pet',
    '唤起宠物失败，请确认悬浮窗权限已开启':
        'Failed to show pet. Please make sure overlay permission is enabled',
    '设置': 'Settings',
    '外观设置': 'Appearance',
    '主题模式': 'Theme Mode',
    '本机服务': 'Local Service',
    '地址': 'Address',
    'Token': 'Token',
    '防止任务运行时屏幕休眠': 'Prevent Screen Sleep During Tasks',
    '任务运行期间保持屏幕常亮，适用于小万（OmniAi）、Agent 和纯聊天':
        'Keep the screen awake while OmniAi, Agent, or chat tasks run',
    '任务完成通知': 'Task Completion Notifications',
    '小万（OmniAi）、Agent 和纯聊天完成后推送提醒':
        'Notify when OmniAi, Agent, or chat responses finish',
    '需要开启通知权限': 'Notification permission is required',
    '未生成': 'Not generated',
    '复制地址': 'Copy Address',
    '复制 Token': 'Copy Token',
    '刷新 Token': 'Refresh Token',
    '加载中': 'Loading',
    '加载中...': 'Loading...',
    '模型与记忆': 'Models & Memory',
    '服务与环境': 'Services & Environment',
    '体验与外观': 'Experience & Appearance',
    '权限与信息': 'Permissions & Info',
    '模型提供商': 'Model Providers',
    '场景模型配置': 'Scene Model Config',
    'Workspace 记忆配置': 'Workspace Memory',
    'MCP 工具': 'MCP Tools',
    'Alpine 环境': 'Terminal Environment',
    '终端环境': 'Terminal Environment',
    '本地 Alpine 与远程 PC Bridge':
        'Local terminal environment and remote PC Bridge',
    '本地终端环境与远程 PC Bridge': 'Local terminal environment and remote PC Bridge',
    'Agent 模式': 'Agent Mode',
    '管理 ACP Agent、可用状态与统一模型绑定':
        'Manage ACP Agents, availability, and unified model bindings',
    '远程 PC Bridge': 'Remote PC Bridge',
    '仅配置远程 ACP 连接': 'Configure only the remote ACP connection',
    '清除失败': 'Failed to clear',
    '隐藏': 'Hide',
    '显示': 'Show',
    '高级设置': 'Advanced Settings',
    '微信': 'WeChat',
    '会话': 'Sessions',
    '未启用': 'Not enabled',
    '已连接': 'Connected',
    '连接中': 'Connecting',
    '未连接': 'Disconnected',
    '已复制': 'Copied',
    '后台隐藏': 'Hide from Recents',
    '闹钟设置': 'Alarm Settings',
    '振动反馈': 'Vibration Feedback',
    '杂项': 'Misc',
    '首页设置': 'Home Settings',
    '启动时': 'On Startup',
    '恢复上次关闭对话': 'Resume Last Chat',
    '新对话': 'New Chat',
    '选择应用启动后打开的对话': 'Choose which chat opens when the app starts',
    '首页问候': 'Home Greeting',
    '显示问候语': 'Show Greeting',
    '管理聊天首页问候语和快捷指令': 'Manage the chat home greeting and quick prompts',
    '关闭后，聊天首页不再显示问候语和快捷指令':
        'When disabled, the chat home will hide the greeting and quick prompts',
    '快捷指令': 'Quick Prompts',
    '新增快捷指令': 'Add Quick Prompt',
    '编辑快捷指令': 'Edit Quick Prompt',
    '删除快捷指令': 'Delete Quick Prompt',
    '删除后该快捷指令将不再显示在首页问候语下方。':
        'This quick prompt will no longer appear under the home greeting.',
    '暂无快捷指令': 'No quick prompts',
    '指令名称': 'Prompt Name',
    '填充文本': 'Text to Fill',
    '请填写完整信息': 'Please fill in all fields',
    '固定': 'Pinned',
    '固定到首页': 'Pin to Home',
    '取消固定': 'Unpin',
    '最多固定两个快捷指令': 'Pin up to two quick prompts',
    '首页、后台隐藏、闹钟、振动与打开方式':
        'Home, Hide from Recents, alarms, vibration, and open behavior',
    '后台隐藏、闹钟、振动与打开方式':
        'Hide from Recents, alarms, vibration, and open behavior',
    '使用小万打开': 'Open with Omnibot',
    '使用小万打开 - 图片': 'Open with Omnibot - Images',
    '使用小万打开 - 文件': 'Open with Omnibot - Files',
    '分别设置图片和文件的打开方式': 'Configure image and file open behavior separately',
    '图片': 'Images',
    '文件': 'Files',
    '复制到 workspace 并在提示词中发送文件路径':
        'Copy to workspace and send file paths in the prompt',
    '添加到 workspace，附加到对话，并在提示词中发送文件路径':
        'Add to workspace, attach to chat, and send the file path in the prompt',
    '存入 workspace 并发送路径': 'Save to workspace and send path',
    '图片填入对话，其他文件走文件传输':
        'Attach images to chat; send other files through file transfer',
    '附加到对话输入框': 'Attach to chat input',
    '照片等文件会直接附加到对话输入框':
        'Photos and similar files are attached directly to the chat input',
    '启动文件服务器生成局域网链接': 'Start file server and create a LAN link',
    '生成局域网文件链接': 'Create a LAN file link',
    '非照片文件会启动文件服务器并生成局域网链接':
        'Non-photo files start the file server and create a LAN link',
    '默认': 'Default',
    '关于小万': 'About Omnibot',
    '我要反馈': 'Send feedback',
    '无法打开反馈页面': 'Unable to open the feedback page',
    '背景来源': 'Background Source',
    '效果预览': 'Preview',
    '效果调整': 'Adjustments',
    '聊天': 'Chat',
    '工作区': 'Workspace',
    '启用背景图': 'Enable background image',
    '本地图片': 'Local Image',
    '图片直链': 'Image URL',
    '选择图片': 'Choose Image',
    '重新选择': 'Choose Again',
    '背景柔化': 'Background Blur',
    '蒙版强度': 'Overlay Strength',
    '蒙版明暗': 'Overlay Brightness',
    '聊天文本大小': 'Chat Text Size',
    '聊天文本颜色': 'Chat Text Color',
    '自动': 'Auto',
    '手动': 'Manual',
    '自定义色号': 'Custom Color',
    '关闭浏览器窗口': 'Close browser window',
    '当前平台暂不支持浏览器工具视图':
        'Browser tool view is not supported on this platform yet',
    '悬浮窗权限': 'Overlay',
    '应用列表读取权限': 'Installed Apps Access',
    'Shizuku 权限': 'Shizuku Permission',
    '公共文件访问': 'Public Storage Access',
    '正在调用工具': 'Calling tool',
    '暂时无法生成回复，请重试。': "I can't generate a reply right now. Please try again.",
    '[只显示最近的部分终端输出]\n': '[Only the most recent terminal output is shown]\n',
    '搜索全部对话': 'Search',
    '清空搜索': 'Clear search',
    '抱歉，刚刚网络开小差了。再发一次试试？':
        'Sorry, the network stumbled just now. Please try sending it again.',
    '小万忙不过来了，等会儿再试试吧':
        'Omnibot is busy right now. Please try again in a moment.',
    '设置后台隐藏失败': 'Failed to update hide-from-recents',
    '设置失败': 'Failed to save settings',
    'MCP 已关闭': 'MCP disabled',
    'MCP 开关失败': 'Failed to toggle MCP',
    '已复制访问地址': 'Address copied',
    '已复制 Token': 'Token copied',
    '已刷新 Token': 'Token refreshed',
    '刷新 Token 失败': 'Failed to refresh token',
    '请求应用列表权限失败': 'Failed to request installed apps permission',
    '请输入有效的 http(s) 图片直链': 'Enter a valid http(s) image URL',
    '请输入 #RRGGBB 或 #AARRGGBB': 'Enter #RRGGBB or #AARRGGBB',
    '色号格式不正确': 'Invalid color code',
    '请先选择本地图片': 'Select a local image first',
    '本地图片不存在，请重新选择': 'The local image no longer exists. Please choose it again',
    '尚未选择本地图片': 'No local image selected yet',
    '正在自动保存…': 'Saving changes…',
    '更改会自动保存': 'Changes are saved automatically',
    '正在调用内嵌 Alpine 终端执行命令':
        'Running a command in the embedded terminal environment',
    '正在执行内嵌 Alpine 终端命令':
        'Executing a command in the embedded terminal environment',
    '终端输出更新中': 'Updating terminal output',
    '🎉Hi，我是小万，我会做很多事，让我展示给你下！':
        '🎉Hi, I\'m Omnibot. I can do many things, let me show you!',
    'Hi，我是小万': 'Hi, I\'m Omnibot',
    '你的 AI 助手，随时准备就绪': 'Your AI assistant, always ready',
    '换一换': 'Shuffle',
    '小万正在思考...': 'Omnibot is thinking...',
    '总结中': 'Summarizing',
    '总结如下': 'Summary',
    '全选': 'Select all',
    '复制': 'Copy',
    '取消': 'Cancel',
    '确认': 'Confirm',
    '确定': 'OK',
    '请稍候...': 'Please wait...',
    '保存并发送': 'Save & Send',
    '未设置模型': 'No model set',
    '发现新版本': 'New version available',
    '打开终端': 'Open terminal',
    '管理终端环境变量': 'Manage terminal environment variables',
    '打开当前会话浏览器': 'Open browser for current session',
    '当前会话还没有可用的浏览器会话': 'No browser session available',
    '纯聊天': 'Chat Only',
    '普通': 'Normal',
    '今天': 'Today',
    '昨天': 'Yesterday',
    '执行中': 'Executing',
    '准备中': 'Pending',
    '执行成功': 'Succeeded',
    '执行失败': 'Failed',
    '已取消': 'Cancelled',
    '等待执行': 'Pending',
    '已暂停': 'Paused',
    '系统': 'System',
    '总结': 'Summary',
    '未知': 'Unknown',
    '识图': 'Image Recognition',
    '未知类型': 'Unknown type',
    '正在回复...': 'Replying...',
    '永不': 'Never',
    '每日': 'Daily',
    '每周': 'Weekly',
    '每月': 'Monthly',
    '每年': 'Yearly',
    '时间': 'Time',
    '日期': 'Date',
    '重复': 'Repeat',
    '任务选项': 'Task options',
    '请选择一个任务': 'Please select a task',
    '请选择你想执行的任务': 'Please select a task to execute',
    '请选择一个应用程序': 'Please select an application',
    '未设置': 'Not set',
    '已过期': 'Expired',
    '即将执行': 'Starting soon',
    '执行': 'Execute',
    '任务': 'Task',
    '好，我来帮你完成': 'OK, I\'ll help you complete it',
    '用户操作': 'User action',
    '短期记忆': 'Short-term Memory',
    '长期记忆': 'Long-term Memory',
    '删除': 'Delete',
    '已删除': 'Deleted',
    '全部': 'All',
    '删除成功': 'Deleted',
    '删除失败': 'Failed to delete',
    '修改失败': 'Modify failed',
    '修改成功': 'Modified successfully',
    '桌面': 'Desktop',
    '内存中': 'Local Memory',
    '云内存中': 'Cloud Memory',
    '记忆中心': 'Memory Center',
    '技能仓库': 'Skill Store',
    '轨迹': 'Trajectory',
    '聊天记录': 'Chat History',
    '已归档': 'Archived',
    '归档对话': 'Archived Conversations',
    '新增': 'Add',
    '安装': 'Install',
    '未安装': 'Not installed',
    '已安装': 'Installed',
    '已禁用': 'Disabled',
    '启用中': 'Enabled',
    '内置': 'Built-in',
    '用户': 'User',
    '保存成功': 'Saved successfully',
    '记忆': 'Memory',
    '记忆能力': 'Memory Capability',
    '文档内容': 'Document Content',
    '权限说明': 'Permission Notes',
    '授权应用': 'Authorized Apps',
    '编辑': 'Edit',
    '编辑你的消息': 'Edit your message',
    '创建': 'Create',
    '任务名称': 'Task Name',
    '启动命令': 'Start Command',
    '工作目录': 'Working Directory',
    '应用权限授权': 'App Permission Authorization',
    '存储占用': 'Storage Usage',
    '清理': 'Clean',
    '重新分析': 'Reanalyze',
    '加载失败': 'Failed to load',
    '场景映射': 'Scene Mapping',
    '已安装模型': 'Installed Models',
    '自启动任务': 'Boot Tasks',
    '暂无': 'None',
    '暂无记忆': 'No memories',
    '暂无可配置场景': 'No configurable scenes',
    '暂无已接入的技能': 'No skills available',
    '暂无归档对话': 'No archived conversations',
    '暂无聊天记录': 'No conversations yet',
    '未命名对话': 'Untitled conversation',
    '折叠全部日期': 'Collapse all dates',
    '展开全部日期': 'Expand all dates',
    '还没有短期记忆': 'No short-term memory yet',
    '确定删除吗？': 'Are you sure you want to delete?',
    '删除后该内容将不可找回': 'This action cannot be undone',
    '立即整理一次': 'Rollup now',
    '夜间记忆整理（22:00）': 'Nightly Memory Rollup (22:00)',
    '最近执行': 'Last executed',
    '暂无总结内容': 'No summary content',
    '检查更新失败': 'Failed to check for updates',
    '已是最新版': 'Already up to date',
    '检查 GitHub Release 获取最新版本':
        'Checking GitHub Release for the latest version',
    '检查中...': 'Checking...',
    '查看新版本': 'View new version',
    '检查更新': 'Check for updates',
    '已关闭思考': 'Thinking disabled',
    '请求日志': 'Request logs',
    '运行日志': 'Runtime logs',
    '使用手册': 'User guide',
    '概览': 'Overview',
    '最近记录': 'Recent logs',
    '最近 10 条 AI 请求，按时间倒序展示。':
        'The latest 10 AI requests are shown in reverse chronological order.',
    '点击条目展开查看请求与响应正文。':
        'Tap an entry to expand the request and response payloads.',
    '最近 200 条错误和崩溃日志，按时间倒序展示。':
        'The latest 200 error and crash logs are shown in reverse chronological order.',
    '含堆栈的条目可展开查看。': 'Entries with stack traces can be expanded for details.',
    '导出运行日志': 'Export runtime logs',
    '已复制全部运行日志': 'All runtime logs copied',
    '导出运行日志失败': 'Failed to export runtime logs',
    '崩溃': 'Crash',
    '总数': 'Total',
    '最近一条': 'Latest entry',
    '基础信息': 'Basic info',
    '载荷': 'Payloads',
    '保存中...': 'Saving...',
    '未选择文件': 'No file selected',
    '远程地址': 'Remote URL',
    '后台运行权限': 'Background run permission',
    '应用列表读取': 'Installed apps access',
    '已开启': 'Enabled',
    '去开启': 'Enable',
    '清除缓存': 'Clear cache',
    '查看并配置悬浮窗、后台运行、Shizuku 等权限':
        'Review overlay, background operation, Shizuku, and related permissions',
    '权限检查中...': 'Checking permissions...',
    '继续任务': 'Continue task',
    '继续任务仅要求': 'Continue requires only',
    'Termux 终端能力': 'Termux terminal capability',
    '可选，允许 Agent 通过 Termux 执行终端命令':
        'Optional: allow the Agent to run terminal commands via Termux',
    '可选': 'Optional',
    '让小万带你执行一次任务吧！': 'Let Omnibot walk you through one task!',
    '其中 Termux 终端能力为可选项，未开启也不影响基础功能':
        'Termux capability is optional; leaving it off will not affect basic features',
    '未绑定': 'Unbound',
    '恢复默认': 'Restore default',
    '点击右侧按钮选择模型，也可按模型名称、ID 或服务商搜索。':
        'Tap the button on the right to select a model, or search by model name, ID, or provider.',
    '使用内置模型服务': 'Use Built-in Model Service',
    '内置模型服务': 'Built-in Model Service',
    '内置模型服务已启用': 'Built-in model service enabled',
    '自定义模型已启用': 'Custom model enabled',
    '已启用内置模型服务': 'Built-in model service enabled',
    '已切换为自定义模型': 'Switched to custom model',
    '没有匹配的模型': 'No matching models',
    '没有匹配的选项': 'No matching options',
    '搜索模型 ID': 'Search model ID',
    '搜索模型或服务商': 'Search models or providers',
    '请先在模型提供商页配置 Provider':
        'Please configure a provider in Model Providers first',
    '该 Provider 暂无可选模型': 'No selectable models for this provider',
    '请先配置ai服务商和模型': 'Please configure an AI provider and model first',
    '已进入仅聊天模式': 'Entered chat-only mode',
    '已退出仅聊天模式': 'Exited chat-only mode',
    '搜索技能名称或描述': 'Search skill name or description',
    '未找到匹配的技能': 'No matching skills found',
    '流式': 'Streaming',
    '非流式': 'Non-streaming',
    '请求地址': 'Request URL',
    '请求方法': 'Request Method',
    '错误信息': 'Error',
    '请求 JSON': 'Request JSON',
    '响应 JSON': 'Response JSON',
    '重试': 'Retry',
    '加载请求日志失败': 'Failed to load request logs',
    '最近还没有 AI 请求日志': 'No AI request logs yet',
    '加载运行日志失败': 'Failed to load runtime logs',
    '暂无运行日志': 'No runtime logs yet',
    '清除': 'Clear',
    'AI 请求': 'AI Request',
    '次对话': 'conversations',
    '天连续': 'day streak',
    '无对话': 'No conversations',
    '暂无 Token 消耗数据': 'No token usage data yet',
    '本地': 'Local',
    '云端': 'Cloud',
    '无消耗': 'No usage',
    '长期记忆未就绪': 'Long-term memory is not ready',
    '完成记忆初始化后，这里会展示跨会话沉淀的偏好与事实。':
        'After memory initialization, cross-session preferences and facts will appear here.',
    '长期记忆暂时不可用': 'Long-term memory is temporarily unavailable',
    '长期记忆还是空的': 'Long-term memory is still empty',
    '当 Agent 主动写入长期偏好后，这里会逐渐丰富起来。':
        'After the Agent writes long-term preferences, this section will gradually fill up.',
    '新增长期记忆': 'Add long-term memory',
    '刷新长期记忆': 'Refresh long-term memory',
    '刚刚': 'Just now',
    '思考完成': 'Thinking complete',
    '正在思考': 'Thinking',
    '用时': 'Elapsed',
    '准备执行任务...': 'Preparing to execute task...',
    '取消任务': 'Cancel task',
    '任务已取消': 'Task canceled',
    '停止工具': 'Stop tool',
    '正在停止工具': 'Stopping tool',
    '停止工具调用失败，请稍后重试': 'Failed to stop tool call. Please try again later.',
    '工具调用': 'Tool call',
    '超时': 'Timeout',
    '中断': 'Interrupted',
    '成功': 'Success',
    '失败': 'Failed',
    '运行中': 'Running',
    '浏览中': 'Browsing',
    '响应中': 'Responding',
    '处理中': 'Processing',
    '终端': 'Terminal',
    '浏览器': 'Browser',
    '定时': 'Scheduled',
    '提醒': 'Reminder',
    '日历': 'Calendar',
    '子任务': 'Subtask',
    '工具': 'Tool',
    '[更早记录已省略]': '[Earlier records omitted]',
    '等待龙虾烹饪': 'Waiting for OpenClaw processing',
    // Onboarding
    '配置你的 AI 助手': 'Configure Your AI Assistant',
    '选择一种方式开始使用小万': 'Choose a way to start using the assistant',
    '云 AI 服务': 'Cloud AI Service',
    '连接 OpenAI、Anthropic 或兼容的 API 服务':
        'Connect to OpenAI, Anthropic, or compatible APIs',
    '继续': 'Continue',
    '跳过，稍后在设置中配置': 'Skip, configure later in Settings',
    '云 AI 服务配置': 'Cloud AI Setup',
    '配置云端 AI 服务商，使用更强大的模型能力':
        'Configure a cloud AI provider for more powerful models',
    '协议类型': 'Protocol Type',
    '名称': 'Name',
    '例如：我的 OpenAI': 'e.g., My OpenAI',
    '测试连接': 'Test Connection',
    '连接成功': 'Connected',
    '发现': 'found',
    '个模型': 'models',
    '连接失败': 'Connection failed',
    '保存': 'Save',
    '保存失败': 'Save failed',
    '下一步': 'Next',
    '暂停': 'Pause',
    '继续下载': 'Resume',
    '下载完成': 'Download Complete',
    '完成': 'Done',
    '返回': 'Back',
    '开始体验': 'Get Started',
    'API Key（可选）': 'API Key (optional)',
    '推荐': 'Recommended',
    '选择方式': 'Choose Method',
    '特性': 'Features',
    '初次使用教程': 'First-use Tutorial',
    '重新查看应用首次启动时的引导页面': 'Replay the first-launch onboarding page',
    '正在下载中，请稍候...': 'Downloading, please wait...',
    '开始下载...': 'Download started...',
    '图片已保存到相册': 'Image saved to gallery',
    '文件已保存到下载目录': 'File saved to Downloads',
    '下载失败': 'Download failed',
    '网页浏览': 'Web Browser',
    '页面加载失败': 'Page load failed',
    '未知错误': 'Unknown error',
    '重新加载': 'Reload',
    'OpenClaw Base URL 为空，请先配置': 'OpenClaw Base URL is empty, please configure first',
    'OpenClaw 连接成功，Gateway 协议正常': 'OpenClaw connected successfully, Gateway protocol OK',
    'OpenClaw 已连接但未收到 challenge，请检查 Gateway 版本': 'OpenClaw connected but no challenge received, check Gateway version',
    'OpenClaw 连接超时，请检查服务地址和网络': 'OpenClaw connection timeout, check service address and network',
    'Workspace 挂载': 'Workspace mounts',
    '把设备目录连接到工作区，终端和聊天可直接访问。移除挂载不会删除原文件。': 'Link device directories to the workspace for direct access in terminal and chat. Unmounting does not delete files.',
    '新增挂载': 'Add mount',
    '还没有挂载任何宿主目录。': 'No mounted directories yet.',
    '选择宿主目录': 'Select a host directory',
    '挂载名称': 'Mount name',
    '会显示为 `/workspace/<名称>`': 'Will appear as `/workspace/<name>`',
    '挂载': 'Mount',
    '卸载挂载': 'Unmount directory',
    '卸载': 'Unmount',
    '取消下载': 'Cancel',
    '正在取消下载…': 'Canceling download…',
    '切换终端系统失败': 'Failed to switch terminal system',
    '终端系统': 'Terminal system',
    '选择终端与 Agent 共用的 Linux 系统。': 'Choose the Linux system shared by terminal and Agent.',
    '正在准备终端系统…': 'Preparing terminal system…',
    '检测未完成，请重新检测以确认组件状态。': 'Detection incomplete, please retest to confirm component status.',
    '异常': 'broken',
    '已挂载': 'mounted',
    '未确认': 'Not confirmed',
    '运行中': 'Running',
    '空闲': 'Idle',
    '修复环境': 'Repair environment',
    '正在修复终端环境…': 'Repairing terminal environment…',
    '终端环境已修复': 'Terminal environment repaired',
    '修复终端环境失败': 'Failed to repair terminal environment',
    '系统安装失败': 'System installation failed',
    '重试安装': 'Retry',
    '关闭': 'Dismiss',
    '另一个终端环境准备任务正在运行，请稍后再试。':
        'Another terminal environment setup task is already running. Please try again later.',
    '另一个终端环境正在运行，请稍后重试。':
        'Another terminal environment setup task is already running. Please try again later.',
    '终端环境准备已取消，已保留下载进度。':
        'Terminal environment setup was canceled. Download progress has been kept.',
    '检查内嵌终端环境失败': 'Failed to check the embedded terminal environment.',
    '开始准备内嵌终端环境': 'Preparing embedded terminal environment',
    '准备开始': 'Starting setup',
    '内嵌终端环境和基础 Agent CLI 包均已就绪。':
        'Embedded terminal environment and base Agent CLI packages are ready.',
    '内嵌终端环境已就绪。': 'Embedded terminal environment is ready.',
    '内嵌终端环境已就绪，基础 Agent CLI 包尚未完成预装。':
        'Embedded terminal environment is ready; base Agent CLI packages are not preinstalled yet.',
    '内嵌终端环境初始化失败。': 'Failed to initialize embedded terminal environment.',
    '当前设备 ABI 不受支持，内嵌终端环境仅支持 arm64-v8a。':
        'Unsupported device ABI. The embedded terminal environment requires arm64-v8a.',
    '正在准备 workspace 和运行目录': 'Preparing workspace and runtime directories',
    '正在初始化宿主终端运行时': 'Initializing host terminal runtime',
    '正在校验终端环境运行资源': 'Checking terminal runtime resources',
    '终端环境运行资源已就绪。': 'Terminal runtime resources are ready.',
    '安装终端环境运行资源失败。': 'Failed to install terminal runtime resources.',
    '无法定位终端 rootfs 目录。': 'Unable to locate terminal rootfs directory.',
    '正在安装 Alpine 离线运行资源': 'Installing offline Alpine runtime resources',
    '正在解压 Alpine 离线运行资源': 'Extracting offline Alpine runtime resources',
    '已找到可用的 Ubuntu 运行资源。': 'Found verified Ubuntu runtime archive.',
    '正在解压 Ubuntu 运行资源': 'Extracting Ubuntu runtime resources',
    '正在准备 Ubuntu 官方下载': 'Preparing official Ubuntu download',
    '正在获取 Ubuntu 下载信息': 'Fetching Ubuntu download metadata',
    'Ubuntu 校验失败，正在重新完整下载':
        'Ubuntu verification failed; restarting full download',
    'Ubuntu 运行资源校验失败，请检查网络后重试。':
        'Ubuntu runtime verification failed. Check your network and try again.',
    'Ubuntu 下载响应为空。': 'Ubuntu download response was empty.',
    'Ubuntu 下载内容超过清单声明大小。':
        'Ubuntu download exceeded the declared archive size.',
    '环境检测失败，无法确认组件状态。':
        'Environment check failed; unable to confirm component status.',
    '环境配置失败，请稍后重试。':
        'Environment setup failed. Please try again later.',
    '正在检查基础 Agent CLI 包': 'Checking base Agent CLI packages',
    '基础 Agent CLI 包已就绪。': 'Base Agent CLI packages are ready.',
    '基础 Agent CLI 包检查异常，准备重新安装。':
        'Base Agent CLI package check failed; reinstalling.',
    '正在安装基础 Agent CLI 包（git、python、node 等）':
        'Installing base Agent CLI packages (git, python, node, etc.)',
    '基础 Agent CLI 包安装完成。': 'Base Agent CLI packages installed.',
    '内嵌终端环境已初始化，但基础 Agent CLI 包安装后仍未通过校验。':
        'Embedded terminal initialized, but base Agent CLI packages failed verification.',
    '正在检查所选开发工具': 'Checking selected development tools',
    '所选开发工具已就绪': 'Selected development tools are ready',
    '正在验证所选开发工具': 'Verifying selected development tools',
    '开发环境配置完成': 'Development environment setup complete',
    '所选组件均已安装。': 'All selected components are already installed.',
  };

  static final List<(RegExp, _TextRewriter)>
  _regexEn = <(RegExp, _TextRewriter)>[
    (RegExp(r'^MCP 已开启：(.+)$'), (match) => 'MCP enabled: ${match.group(1)!}'),
    (
      RegExp(r'^会话 (\d+) · 任务 (\d+)$'),
      (match) => 'Sessions ${match.group(1)!} · Tasks ${match.group(2)!}',
    ),
    (RegExp(r'^已连接 · (.+)$'), (match) => 'Connected · ${match.group(1)!}'),
    (
      RegExp(r'^选择图片失败：(.+)$'),
      (match) => 'Failed to pick image: ${match.group(1)!}',
    ),
    (
      RegExp(r'^自动保存失败：(.+)$'),
      (match) => 'Auto-save failed: ${match.group(1)!}',
    ),
    (
      RegExp(r'^已拉取 (\d+) 个模型$'),
      (match) => 'Fetched ${match.group(1)!} models',
    ),
    (
      RegExp(r'^暂时无法生成回复，请重试。(.*)$'),
      (match) {
        final extra = match.group(1)?.trim() ?? '';
        if (extra.isEmpty) {
          return "I can't generate a reply right now. Please try again.";
        }
        return "I can't generate a reply right now. Please try again. $extra";
      },
    ),
    (
      RegExp(r'^执行任务前，请先开启：(.+)$'),
      (match) =>
          'Enable these permissions before running tasks: ${match.group(1)!}',
    ),
    (
      RegExp(r'^执行任务前需要先开启权限$'),
      (_) => 'Permissions must be enabled before running tasks',
    ),
    (RegExp(r'^用户: (.+)\n$'), (match) => 'User: ${match.group(1)!}\n'),
    (
      RegExp(r'^继续任务仅要求：(.+)$'),
      (match) => 'Continue requires only: ${match.group(1)!}',
    ),
    (RegExp(r'^默认：(.+)$'), (match) => 'Default: ${match.group(1)!}'),
    (
      RegExp(r'^恢复默认（(.+)）$'),
      (match) => 'Restore default (${match.group(1)!})',
    ),
    (RegExp(r'^(.+) 已清除绑定$'), (match) => '${match.group(1)!} binding cleared'),
    (
      RegExp(r'^(.+) 已恢复默认模型$'),
      (match) => '${match.group(1)!} restored to default model',
    ),
    (RegExp(r'^已切换到 (.+)$'), (match) => 'Switched to ${match.group(1)!}'),
    (
      RegExp(r'^已设置思考强度为 (.+)$'),
      (match) => 'Reasoning effort set to ${match.group(1)!}',
    ),
    (
      RegExp(r'^Agent 模型已切换到 (.+)$'),
      (match) => 'Agent model switched to ${match.group(1)!}',
    ),
    (
      RegExp(r'^更新 Agent 模型失败：(.+)$'),
      (match) => 'Failed to update Agent model: ${match.group(1)!}',
    ),
    (RegExp(r'^(.+)已复制$'), (match) => '${match.group(1)!} copied'),
    (RegExp(r'^(\d+) 条消息$'), (match) => '${match.group(1)!} messages'),
    (
      RegExp(r'^(.+) · (\d+) 条消息$'),
      (match) => '${match.group(1)!} · ${match.group(2)!} messages',
    ),
    (RegExp(r'^匹配 (\d+)%$'), (match) => 'Match ${match.group(1)!}%'),
    (RegExp(r'^(\d+) 分钟前$'), (match) => '${match.group(1)!} min ago'),
    (RegExp(r'^(\d+) 小时前$'), (match) => '${match.group(1)!} hr ago'),
    (RegExp(r'^(\d+) 天前$'), (match) => '${match.group(1)!} days ago'),
    (RegExp(r'^(\d+) 秒$'), (match) => '${match.group(1)!}s'),
    (
      RegExp(r'^(\d+) 分 (\d+) 秒$'),
      (match) => '${match.group(1)!}m ${match.group(2)!}s',
    ),
    (
      RegExp(r'^(\d+) 次对话 · (\d+)\/(\d+)$'),
      (match) =>
          '${match.group(1)!} conversations · ${match.group(2)!}/${match.group(3)!}',
    ),
    (
      RegExp(r'^无对话 · (\d+)\/(\d+)$'),
      (match) => 'No conversations · ${match.group(1)!}/${match.group(2)!}',
    ),
    (RegExp(r'^本地 (.+)%$'), (match) => 'Local ${match.group(1)!}%'),
    (RegExp(r'^云端 (.+)%$'), (match) => 'Cloud ${match.group(1)!}%'),
    (
      RegExp(r'^本地 (.+) · 云端 (.+)$'),
      (match) => 'Local ${match.group(1)!} · Cloud ${match.group(2)!}',
    ),
    (
      RegExp(r'^正在搜索\s*(.+?)\s*技能$'),
      (match) => 'Searching ${match.group(1)!} skill',
    ),
    (RegExp(r'^打开\s*(.+?)\s*应用$'), (match) => 'Opening ${match.group(1)!} app'),
    (RegExp(r'^正在打开(.+)$'), (match) => 'Opening ${match.group(1)!}'),
    (
      RegExp(r'^文件已下载：(.+)$'),
      (match) => 'File downloaded: ${match.group(1)!}',
    ),
    (
      RegExp(r'^OpenClaw 连接失败：(.+)$'),
      (match) => 'OpenClaw connection failed: ${match.group(1)!}',
    ),
    (
      RegExp(r'^确认卸载 `/workspace/(.+)` 吗？原始宿主目录和里面的文件都不会被删除。$'),
      (match) => 'Unmount `/workspace/${match.group(1)!}`? The original host directory and its files will not be deleted.',
    ),
    (
      RegExp(r'^原始目录不存在或当前不可读：(.+)$'),
      (match) => 'Original directory does not exist or is currently unreadable: ${match.group(1)!}',
    ),
    (
      RegExp(r'^已挂载到 /workspace/(.+)$'),
      (match) => 'Mounted as /workspace/${match.group(1)!}',
    ),
    (
      RegExp(r'^已卸载 /workspace/(.+)$'),
      (match) => 'Unmounted /workspace/${match.group(1)!}',
    ),
    (
      RegExp(r'^正在准备 (Ubuntu|Alpine)…$'),
      (match) => 'Preparing ${match.group(1)!}…',
    ),
    (
      RegExp(r'^终端系统已切换为 (Ubuntu|Alpine)$'),
      (match) => 'Terminal system switched to ${match.group(1)!}',
    ),
    (
      RegExp(r'^正在准备 (Ubuntu|Alpine) 终端环境$'),
      (match) => 'Preparing ${match.group(1)!} terminal environment',
    ),
    (
      RegExp(r'^(Ubuntu|Alpine) 终端环境准备失败。$'),
      (match) => 'Failed to prepare ${match.group(1)!} terminal environment.',
    ),
    (
      RegExp(r'^正在验证 (Ubuntu|Alpine) 终端环境$'),
      (match) => 'Verifying ${match.group(1)!} terminal environment',
    ),
    (
      RegExp(r'^(Ubuntu|Alpine) 终端环境验证失败：([\s\S]+)$'),
      (match) =>
          '${match.group(1)!} terminal environment verification failed: ${match.group(2)!}',
    ),
    (
      RegExp(r'^(Ubuntu|Alpine) 终端环境已就绪。$'),
      (match) => '${match.group(1)!} terminal environment is ready.',
    ),
    (
      RegExp(r'^(Ubuntu|Alpine) 系统已安装。$'),
      (match) => '${match.group(1)!} system is already installed.',
    ),
    (
      RegExp(r'^解压后的 (Ubuntu|Alpine) rootfs 不完整。$'),
      (match) => 'Extracted ${match.group(1)!} rootfs is incomplete.',
    ),
    (
      RegExp(r'^正在下载 Ubuntu 运行资源 (.+) / (.+)$'),
      (match) =>
          'Downloading Ubuntu runtime ${match.group(1)!} / ${match.group(2)!}',
    ),
    (
      RegExp(r'^下载 Ubuntu 运行资源失败（HTTP (\d+)）。$'),
      (match) => 'Failed to download Ubuntu runtime (HTTP ${match.group(1)!}).',
    ),
    (
      RegExp(r'^存储空间不足：Ubuntu 初始化至少还需要 (.+)，当前可用 (.+)。$'),
      (match) =>
          'Insufficient storage: Ubuntu setup needs at least ${match.group(1)!}, ${match.group(2)!} available.',
    ),
    (
      RegExp(r'^环境检测失败，无法确认组件状态：([\s\S]+)$'),
      (match) =>
          'Environment check failed; unable to confirm component status: ${match.group(1)!}',
    ),
    (
      RegExp(r'^环境配置失败：([\s\S]+)$'),
      (match) => 'Environment setup failed: ${match.group(1)!}',
    ),
    (
      RegExp(r'^检测到基础 Agent CLI 包缺失：(.+)$'),
      (match) => 'Missing base Agent CLI packages: ${match.group(1)!}',
    ),
    (
      RegExp(r'^正在安装所选开发工具：(.+)$'),
      (match) => 'Installing selected tools: ${match.group(1)!}',
    ),
    (
      RegExp(r'^环境配置完成：(.+)$'),
      (match) => 'Environment setup complete: ${match.group(1)!}',
    ),
    (
      RegExp(r'^内嵌终端环境已初始化，但基础 Agent CLI 包安装失败：([\s\S]+)$'),
      (match) =>
          'Embedded terminal initialized, but installing base Agent CLI packages failed: ${match.group(1)!}',
    ),
    (
      RegExp(r'^内嵌终端环境已初始化，但基础 Agent CLI 包仍缺失：(.+)$'),
      (match) =>
          'Embedded terminal initialized, but base Agent CLI packages are still missing: ${match.group(1)!}',
    ),
  ];

  static final Map<String, String> _exactPt = <String, String>{
    '设置': 'Configurações',
    '刷新': 'Atualizar',
    '取消': 'Cancelar',
    '删除': 'Excluir',
    '保存': 'Salvar',
    '确认': 'Confirmar',
    '加载中': 'Carregando',
    '加载中...': 'Carregando...',
    '终端环境': 'Ambiente de Terminal',
    'Alpine 环境': 'Ambiente de Terminal',
    '终端系统': 'Sistema do terminal',
    '选择终端与 Agent 共用的 Linux 系统。':
        'Escolha o sistema Linux compartilhado pelo terminal e pelo Agent.',
    '正在准备终端系统…': 'Preparando sistema do terminal…',
    '取消下载': 'Cancelar',
    '正在取消下载…': 'Cancelando download…',
    '切换终端系统失败': 'Falha ao alternar o sistema do terminal',
    '检测未完成，请重新检测以确认组件状态。':
        'Detecção incompleta. Atualize para confirmar o status dos componentes.',
    'Workspace 挂载': 'Montagens do Workspace',
    '把设备目录连接到工作区，终端和聊天可直接访问。移除挂载不会删除原文件。':
        'Conecte diretórios do dispositivo ao workspace para acesso direto no terminal e no chat. Remover a montagem não exclui os arquivos originais.',
    '新增挂载': 'Adicionar montagem',
    '还没有挂载任何宿主目录。': 'Nenhum diretório montado ainda.',
    '选择宿主目录': 'Selecionar diretório do dispositivo',
    '挂载名称': 'Nome da montagem',
    '会显示为 `/workspace/<名称>`': 'Aparecerá como `/workspace/<nome>`',
    '挂载': 'Montar',
    '卸载挂载': 'Desmontar diretório',
    '卸载': 'Desmontar',
    '异常': 'inválido',
    '已挂载': 'montado',
    '未确认': 'Não confirmado',
    '运行中': 'Em execução',
    '空闲': 'Inativo',
    '修复环境': 'Reparar ambiente',
    '正在修复终端环境…': 'Reparando ambiente de terminal…',
    '终端环境已修复': 'Ambiente de terminal reparado',
    '修复终端环境失败': 'Falha ao reparar o ambiente de terminal',
    '系统安装失败': 'Falha na instalação do sistema',
    '重试安装': 'Tentar novamente',
    '关闭': 'Fechar',
    '另一个终端环境准备任务正在运行，请稍后再试。':
        'Outra tarefa de preparação do terminal já está em execução. Tente novamente em instantes.',
    '另一个终端环境正在运行，请稍后重试。':
        'Outro ambiente de terminal já está em execução. Tente novamente em instantes.',
    '终端环境准备已取消，已保留下载进度。':
        'A preparação do ambiente de terminal foi cancelada. O progresso do download foi mantido.',
    '检查内嵌终端环境失败': 'Falha ao verificar o ambiente de terminal integrado.',
    '开始准备内嵌终端环境': 'Iniciando preparação do ambiente de terminal',
    '准备开始': 'Iniciando configuração',
    '内嵌终端环境和基础 Agent CLI 包均已就绪。':
        'O ambiente de terminal integrado e os pacotes base de CLI do Agent estão prontos.',
    '内嵌终端环境已就绪。': 'O ambiente de terminal integrado está pronto.',
    '内嵌终端环境已就绪，基础 Agent CLI 包尚未完成预装。':
        'O ambiente de terminal está pronto; os pacotes base de CLI ainda não foram pré-instalados.',
    '内嵌终端环境初始化失败。': 'Falha ao inicializar o ambiente de terminal integrado.',
    '当前设备 ABI 不受支持，内嵌终端环境仅支持 arm64-v8a。':
        'ABI do dispositivo não suportada. O terminal integrado requer arm64-v8a.',
    '正在准备 workspace 和运行目录':
        'Preparando diretórios de workspace e execução',
    '正在初始化宿主终端运行时': 'Inicializando runtime do terminal no dispositivo',
    '正在校验终端环境运行资源': 'Verificando recursos do ambiente de terminal',
    '终端环境运行资源已就绪。': 'Recursos do ambiente de terminal prontos.',
    '安装终端环境运行资源失败。':
        'Falha ao instalar os recursos do ambiente de terminal.',
    '无法定位终端 rootfs 目录。':
        'Não foi possível localizar o diretório rootfs do terminal.',
    '正在安装 Alpine 离线运行资源': 'Instalando recursos offline do Alpine',
    '正在解压 Alpine 离线运行资源': 'Extraindo recursos offline do Alpine',
    '已找到可用的 Ubuntu 运行资源。': 'Arquivo verificado do Ubuntu encontrado.',
    '正在解压 Ubuntu 运行资源': 'Extraindo recursos do Ubuntu',
    '正在准备 Ubuntu 官方下载': 'Preparando download oficial do Ubuntu',
    '正在获取 Ubuntu 下载信息': 'Obtendo informações de download do Ubuntu',
    'Ubuntu 校验失败，正在重新完整下载':
        'Falha na verificação do Ubuntu; reiniciando o download completo',
    'Ubuntu 运行资源校验失败，请检查网络后重试。':
        'Falha na verificação do Ubuntu. Verifique sua conexão e tente novamente.',
    'Ubuntu 下载响应为空。': 'A resposta do download do Ubuntu estava vazia.',
    'Ubuntu 下载内容超过清单声明大小。':
        'O download do Ubuntu excedeu o tamanho esperado.',
    '环境检测失败，无法确认组件状态。':
        'Falha na verificação do ambiente; não foi possível confirmar o status dos componentes.',
    '环境配置失败，请稍后重试。':
        'Falha na configuração do ambiente. Tente novamente mais tarde.',
    '正在检查基础 Agent CLI 包': 'Verificando pacotes base de CLI do Agent',
    '基础 Agent CLI 包已就绪。': 'Pacotes base de CLI do Agent prontos.',
    '基础 Agent CLI 包检查异常，准备重新安装。':
        'Falha ao verificar pacotes base de CLI; reinstalando.',
    '正在安装基础 Agent CLI 包（git、python、node 等）':
        'Instalando pacotes base de CLI (git, python, node, etc.)',
    '基础 Agent CLI 包安装完成。': 'Pacotes base de CLI instalados.',
    '内嵌终端环境已初始化，但基础 Agent CLI 包安装后仍未通过校验。':
        'Terminal integrado inicializado, mas os pacotes base de CLI falharam na verificação.',
    '正在检查所选开发工具': 'Verificando ferramentas de desenvolvimento selecionadas',
    '所选开发工具已就绪': 'As ferramentas selecionadas estão prontas',
    '正在验证所选开发工具': 'Validando ferramentas de desenvolvimento selecionadas',
    '开发环境配置完成': 'Configuração do ambiente de desenvolvimento concluída',
    '所选组件均已安装。': 'Todos os componentes selecionados já estão instalados.',
  };

  static final List<(RegExp, _TextRewriter)> _regexPt =
      <(RegExp, _TextRewriter)>[
        (
          RegExp(r'^确认卸载 `/workspace/(.+)` 吗？原始宿主目录和里面的文件都不会被删除。$'),
          (match) =>
              'Desmontar `/workspace/${match.group(1)!}`? O diretório original e seus arquivos não serão excluídos.',
        ),
        (
          RegExp(r'^原始目录不存在或当前不可读：(.+)$'),
          (match) =>
              'O diretório original não existe ou não pode ser lido no momento: ${match.group(1)!}',
        ),
        (
          RegExp(r'^已挂载到 /workspace/(.+)$'),
          (match) => 'Montado em /workspace/${match.group(1)!}',
        ),
        (
          RegExp(r'^已卸载 /workspace/(.+)$'),
          (match) => 'Desmontado /workspace/${match.group(1)!}',
        ),
        (
          RegExp(r'^正在准备 (Ubuntu|Alpine)…$'),
          (match) => 'Preparando ${match.group(1)!}…',
        ),
        (
          RegExp(r'^终端系统已切换为 (Ubuntu|Alpine)$'),
          (match) => 'Sistema do terminal alterado para ${match.group(1)!}',
        ),
        (
          RegExp(r'^正在准备 (Ubuntu|Alpine) 终端环境$'),
          (match) => 'Preparando ambiente de terminal ${match.group(1)!}',
        ),
        (
          RegExp(r'^(Ubuntu|Alpine) 终端环境准备失败。$'),
          (match) =>
              'Falha ao preparar o ambiente de terminal ${match.group(1)!}.',
        ),
        (
          RegExp(r'^正在验证 (Ubuntu|Alpine) 终端环境$'),
          (match) => 'Verificando ambiente de terminal ${match.group(1)!}',
        ),
        (
          RegExp(r'^(Ubuntu|Alpine) 终端环境验证失败：([\s\S]+)$'),
          (match) =>
              'Falha na verificação do ambiente ${match.group(1)!}: ${match.group(2)!}',
        ),
        (
          RegExp(r'^(Ubuntu|Alpine) 终端环境已就绪。$'),
          (match) => 'O ambiente de terminal ${match.group(1)!} está pronto.',
        ),
        (
          RegExp(r'^(Ubuntu|Alpine) 系统已安装。$'),
          (match) => 'O sistema ${match.group(1)!} já está instalado.',
        ),
        (
          RegExp(r'^解压后的 (Ubuntu|Alpine) rootfs 不完整。$'),
          (match) => 'O rootfs extraído do ${match.group(1)!} está incompleto.',
        ),
        (
          RegExp(r'^正在下载 Ubuntu 运行资源 (.+) / (.+)$'),
          (match) =>
              'Baixando recursos do Ubuntu ${match.group(1)!} / ${match.group(2)!}',
        ),
        (
          RegExp(r'^下载 Ubuntu 运行资源失败（HTTP (\d+)）。$'),
          (match) =>
              'Falha ao baixar recursos do Ubuntu (HTTP ${match.group(1)!}).',
        ),
        (
          RegExp(r'^存储空间不足：Ubuntu 初始化至少还需要 (.+)，当前可用 (.+)。$'),
          (match) =>
              'Espaço insuficiente: o Ubuntu requer pelo menos ${match.group(1)!}; disponível: ${match.group(2)!}.',
        ),
        (
          RegExp(r'^环境检测失败，无法确认组件状态：([\s\S]+)$'),
          (match) =>
              'Falha na verificação do ambiente; não foi possível confirmar o status dos componentes: ${match.group(1)!}',
        ),
        (
          RegExp(r'^环境配置失败：([\s\S]+)$'),
          (match) => 'Falha na configuração do ambiente: ${match.group(1)!}',
        ),
        (
          RegExp(r'^检测到基础 Agent CLI 包缺失：(.+)$'),
          (match) => 'Pacotes base de CLI do Agent ausentes: ${match.group(1)!}',
        ),
        (
          RegExp(r'^正在安装所选开发工具：(.+)$'),
          (match) => 'Instalando ferramentas selecionadas: ${match.group(1)!}',
        ),
        (
          RegExp(r'^环境配置完成：(.+)$'),
          (match) =>
              'Configuração do ambiente concluída: ${match.group(1)!}',
        ),
        (
          RegExp(r'^内嵌终端环境已初始化，但基础 Agent CLI 包安装失败：([\s\S]+)$'),
          (match) =>
              'Terminal integrado inicializado, mas falhou ao instalar pacotes base de CLI: ${match.group(1)!}',
        ),
        (
          RegExp(r'^内嵌终端环境已初始化，但基础 Agent CLI 包仍缺失：(.+)$'),
          (match) =>
              'Terminal integrado inicializado, mas ainda faltam pacotes base de CLI: ${match.group(1)!}',
        ),
      ];

  static void setResolvedLocale(Locale locale) {
    _activeLocale = locale;
  }

  static void clearResolvedLocale() {
    _activeLocale = null;
  }

  static Locale get _resolvedLocale {
    final activeLocale = _activeLocale;
    if (activeLocale != null) {
      return activeLocale;
    }
    try {
      return StorageService.getResolvedLocale();
    } catch (_) {
      return PlatformDispatcher.instance.locale;
    }
  }

  static bool get isEnglish => _resolvedLocale.languageCode != 'zh';

  static String localize(String text, {Locale? locale}) {
    final targetLocale = locale ?? _resolvedLocale;
    final lang = targetLocale.languageCode.toLowerCase();
    if (lang == 'zh') {
      return text;
    }
    if (lang == 'pt') {
      final exactPt = _exactPt[text];
      if (exactPt != null) {
        return exactPt;
      }
      for (final (pattern, rewrite) in _regexPt) {
        final match = pattern.firstMatch(text);
        if (match != null) {
          return rewrite(match);
        }
      }
    }
    final exact = _exactEn[text];
    if (exact != null) {
      return exact;
    }
    for (final (pattern, rewrite) in _regexEn) {
      final match = pattern.firstMatch(text);
      if (match != null) {
        return rewrite(match);
      }
    }
    return text;
  }
}
