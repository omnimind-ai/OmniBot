import 'package:flutter/material.dart';
import 'package:ui/features/task/pages/usage_statistics/widgets/activity_dashboard_card.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_root_toolbar.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';

/// Conversation activity and model token usage statistics.
///
/// This page intentionally depends only on conversation and token-usage data.
/// It remains independent from the legacy task-execution history feature.
class UsageStatisticsPage extends StatelessWidget {
  const UsageStatisticsPage({
    super.key,
    this.embedded = false,
    this.active = true,
  });

  final bool embedded;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: OmniRootToolbar(
        title: LegacyTextLocalizer.localize('轨迹'),
        primary: true,
        showBackButton: !embedded,
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: OmniContentWidth(
          child: SingleChildScrollView(
            padding: edgeToEdgeScrollPadding(
              context,
              const EdgeInsets.only(top: 10, bottom: 32),
            ),
            child: ActivityDashboardCard(active: active),
          ),
        ),
      ),
    );
  }
}
