import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

/// Pages that make up the first-use tutorial flow.
enum TutorialPage {
  permissions,
  provider,
  providerSelection,
  providerConnection,
  modelInventory,
  primaryScenes,
  memoryScenes,
  completion,
}

/// Pages reachable through the dotted pagination footer, in order.
const List<TutorialPage> tutorialNavigationPages = <TutorialPage>[
  TutorialPage.permissions,
  TutorialPage.provider,
  TutorialPage.providerSelection,
  TutorialPage.providerConnection,
  TutorialPage.modelInventory,
  TutorialPage.primaryScenes,
  TutorialPage.memoryScenes,
];

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
    descriptionEn: 'Understands tasks, plans work, and calls tools. Prefer your strongest tool-capable model.',
  ),
  SceneDefinition(
    id: 'scene.compactor.context.chat',
    icon: LucideIcons.messagesSquare,
    title: 'Chat Compactor',
    descriptionZh: '在长对话中压缩历史上下文，平衡速度与总结准确度。',
    descriptionEn: 'Compresses long chat history while balancing speed and summary accuracy.',
  ),
  SceneDefinition(
    id: 'scene.memory.embedding',
    icon: LucideIcons.database,
    title: 'Memory Embed',
    descriptionZh: '把记忆转换为向量用于检索；若提供商有 embedding 模型，请优先选择。',
    descriptionEn: 'Creates vectors for memory search. Prefer an embedding model when available.',
  ),
  SceneDefinition(
    id: 'scene.memory.rollup',
    icon: LucideIcons.memoryStick,
    title: 'Memory Rollup',
    descriptionZh: '归纳长期记忆并去除重复信息，适合稳定、成本适中的文本模型。',
    descriptionEn: 'Consolidates long-term memory and removes duplicates. A reliable text model is ideal.',
  ),
];
