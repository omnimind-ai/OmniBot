import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

/// Pages that make up the first-use tutorial flow.
enum TutorialPage {
  system,
  development,
  tools,
  environmentProgress,
  permissions,
  provider,
  providerConnection,
  modelInventory,
  primaryScenes,
  memoryScenes,
  completion,
}

/// Pages reachable through the dotted pagination footer, in order.
const List<TutorialPage> tutorialNavigationPages = <TutorialPage>[
  TutorialPage.system,
  TutorialPage.development,
  TutorialPage.tools,
  TutorialPage.permissions,
  TutorialPage.provider,
  TutorialPage.providerConnection,
  TutorialPage.modelInventory,
  TutorialPage.primaryScenes,
  TutorialPage.memoryScenes,
];

/// A starter development-environment preset.
class EnvironmentPreset {
  const EnvironmentPreset({
    required this.id,
    required this.icon,
    required this.titleZh,
    required this.titleEn,
    required this.descriptionZh,
    required this.descriptionEn,
    required this.packageIds,
    required this.contents,
  });

  final String id;
  final IconData icon;
  final String titleZh;
  final String titleEn;
  final String descriptionZh;
  final String descriptionEn;
  final List<String> packageIds;
  final String contents;
}

/// An optional extra tool the user can add to the environment.
class OptionalTool {
  const OptionalTool({
    required this.id,
    required this.icon,
    required this.label,
    required this.descriptionZh,
    required this.descriptionEn,
  });

  final String id;
  final IconData icon;
  final String label;
  final String descriptionZh;
  final String descriptionEn;
}

/// A model provider the user can connect to.
class ProviderOption {
  const ProviderOption({
    required this.id,
    required this.label,
    required this.vendorKey,
    required this.baseUrl,
    required this.sourceType,
    required this.protocolType,
  });

  final String id;
  final String label;
  final String? vendorKey;
  final String baseUrl;
  final String sourceType;
  final String protocolType;
}

/// A background role that can be bound to a model.
class SceneDefinition {
  const SceneDefinition({
    required this.id,
    required this.icon,
    required this.title,
    required this.descriptionZh,
    required this.descriptionEn,
  });

  final String id;
  final IconData icon;
  final String title;
  final String descriptionZh;
  final String descriptionEn;
}

const List<EnvironmentPreset> environmentPresets = <EnvironmentPreset>[
  EnvironmentPreset(
    id: 'general',
    icon: LucideIcons.messageCircleMore,
    titleZh: '聊天 Agent 助手',
    titleEn: 'Chat Agent Assistant',
    descriptionZh: '适合日常对话、任务协作和工具调用，也保留常用开发能力。',
    descriptionEn:
        'For everyday chat, task collaboration, and tool use, with common development capabilities included.',
    packageIds: <String>['nodejs', 'npm', 'git', 'python', 'pip', 'uv'],
    contents: 'Node.js · npm · Python · pip · uv · Git',
  ),
  EnvironmentPreset(
    id: 'node',
    icon: LucideIcons.braces,
    titleZh: 'Node.js / Web',
    titleEn: 'Node.js / Web',
    descriptionZh: '面向前端、后端服务和 JavaScript / TypeScript 工程。',
    descriptionEn:
        'For frontend, backend services, and JavaScript or TypeScript projects.',
    packageIds: <String>['nodejs', 'npm', 'git'],
    contents: 'Node.js · npm · Git',
  ),
  EnvironmentPreset(
    id: 'python',
    icon: LucideIcons.code2,
    titleZh: 'Python',
    titleEn: 'Python',
    descriptionZh: '面向自动化、数据处理、脚本和 Python 项目。',
    descriptionEn:
        'For automation, data processing, scripts, and Python projects.',
    packageIds: <String>['python', 'pip', 'uv', 'git'],
    contents: 'Python · pip · uv · Git',
  ),
];

const List<OptionalTool> optionalTools = <OptionalTool>[
  OptionalTool(
    id: 'codex',
    icon: LucideIcons.bot,
    label: 'Codex CLI',
    descriptionZh: 'OpenAI 编程 Agent',
    descriptionEn: 'OpenAI coding agent',
  ),
  OptionalTool(
    id: 'claude_code',
    icon: LucideIcons.sparkles,
    label: 'Claude Code',
    descriptionZh: 'Anthropic 编程 Agent',
    descriptionEn: 'Anthropic coding agent',
  ),
  OptionalTool(
    id: 'opencode',
    icon: LucideIcons.squareTerminal,
    label: 'OpenCode',
    descriptionZh: '开源编程 Agent',
    descriptionEn: 'Open-source coding agent',
  ),
  OptionalTool(
    id: 'ssh_client',
    icon: LucideIcons.server,
    label: 'SSH',
    descriptionZh: '连接远程开发主机',
    descriptionEn: 'Connect to remote hosts',
  ),
];

const List<ProviderOption> providerOptions = <ProviderOption>[
  ProviderOption(
    id: 'deepseek',
    label: 'DeepSeek',
    vendorKey: 'deepseek',
    baseUrl: 'https://api.deepseek.com',
    sourceType: 'deepseek',
    protocolType: 'deepseek',
  ),
  ProviderOption(
    id: 'moonshot',
    label: 'Kimi',
    vendorKey: 'moonshot',
    baseUrl: 'https://api.moonshot.cn/v1',
    sourceType: 'moonshot',
    protocolType: 'openai_compatible',
  ),
  ProviderOption(
    id: 'mimo',
    label: 'Mimo',
    vendorKey: 'xiaomi',
    baseUrl: 'https://api.xiaomimimo.com/v1',
    sourceType: 'mimo',
    protocolType: 'openai_compatible',
  ),
  ProviderOption(
    id: 'openai',
    label: 'OpenAI',
    vendorKey: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    sourceType: 'custom',
    protocolType: 'openai_compatible',
  ),
  ProviderOption(
    id: 'anthropic',
    label: 'Anthropic',
    vendorKey: 'anthropic',
    baseUrl: 'https://api.anthropic.com/v1',
    sourceType: 'custom',
    protocolType: 'anthropic',
  ),
  ProviderOption(
    id: 'custom',
    label: 'Compatible API',
    vendorKey: null,
    baseUrl: '',
    sourceType: 'custom',
    protocolType: 'openai_compatible',
  ),
];

const List<SceneDefinition> sceneDefinitions = <SceneDefinition>[
  SceneDefinition(
    id: 'scene.dispatch.model',
    icon: LucideIcons.bot,
    title: 'Agent',
    descriptionZh: '理解任务、规划步骤并调用工具，建议选择能力最强的工具调用模型。',
    descriptionEn:
        'Understands tasks, plans work, and calls tools. Prefer your strongest tool-capable model.',
  ),
  SceneDefinition(
    id: 'scene.compactor.context.chat',
    icon: LucideIcons.messagesSquare,
    title: 'Chat Compactor',
    descriptionZh: '在长对话中压缩历史上下文，平衡速度与总结准确度。',
    descriptionEn:
        'Compresses long chat history while balancing speed and summary accuracy.',
  ),
  SceneDefinition(
    id: 'scene.memory.embedding',
    icon: LucideIcons.database,
    title: 'Memory Embed',
    descriptionZh: '把记忆转换为向量用于检索；若提供商有 embedding 模型，请优先选择。',
    descriptionEn:
        'Creates vectors for memory search. Prefer an embedding model when available.',
  ),
  SceneDefinition(
    id: 'scene.memory.rollup',
    icon: LucideIcons.memoryStick,
    title: 'Memory Rollup',
    descriptionZh: '归纳长期记忆并去除重复信息，适合稳定、成本适中的文本模型。',
    descriptionEn:
        'Consolidates long-term memory and removes duplicates. A reliable text model is ideal.',
  ),
];
