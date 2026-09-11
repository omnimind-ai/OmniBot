enum AgentSlashSubmitKind {
  none,
  openModelPicker,
  selectModel,
  startReview,
  startInit,
  togglePlan,
  startPlan,
  unsupported,
}

class AgentSlashSubmitIntent {
  const AgentSlashSubmitIntent(this.kind, {this.value});

  final AgentSlashSubmitKind kind;
  final String? value;
}

AgentSlashSubmitIntent resolveAgentSlashSubmitIntent(String messageText) {
  final trimmed = messageText.trim();
  if (!trimmed.startsWith('/')) {
    return const AgentSlashSubmitIntent(AgentSlashSubmitKind.none);
  }

  final normalized = trimmed.toLowerCase();
  if (normalized == '/model') {
    return const AgentSlashSubmitIntent(AgentSlashSubmitKind.openModelPicker);
  }
  if (normalized.startsWith('/model ')) {
    final modelId = trimmed.substring('/model'.length).trim();
    if (modelId.isEmpty) {
      return const AgentSlashSubmitIntent(AgentSlashSubmitKind.openModelPicker);
    }
    return AgentSlashSubmitIntent(
      AgentSlashSubmitKind.selectModel,
      value: modelId,
    );
  }

  if (normalized == '/review') {
    return const AgentSlashSubmitIntent(AgentSlashSubmitKind.startReview);
  }
  if (normalized == '/init') {
    return const AgentSlashSubmitIntent(AgentSlashSubmitKind.startInit);
  }
  if (normalized == '/plan') {
    return const AgentSlashSubmitIntent(AgentSlashSubmitKind.togglePlan);
  }
  if (normalized.startsWith('/plan ')) {
    final prompt = trimmed.substring('/plan'.length).trim();
    if (prompt.isEmpty) {
      return const AgentSlashSubmitIntent(AgentSlashSubmitKind.togglePlan);
    }
    return AgentSlashSubmitIntent(
      AgentSlashSubmitKind.startPlan,
      value: prompt,
    );
  }

  return const AgentSlashSubmitIntent(AgentSlashSubmitKind.unsupported);
}

/// A UI shortcut may select only a value advertised by the active ACP session.
String? advertisedPlanMode(Iterable<String> modes) {
  for (final mode in modes) {
    if (mode.trim().toLowerCase() == 'plan') return mode;
  }
  return null;
}
