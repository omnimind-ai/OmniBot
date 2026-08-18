import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/theme_context.dart';

/// ACP Agent 品牌图标。
///
/// 已知的内置 Agent（小万 / Codex / Claude Code / OpenCode / DeepSeek Harness）渲染各自来自 Lobe Icons
/// (https://icons.lobehub.com) 的原始品牌 SVG；未识别的自定义 Agent 回退到默认
/// 机器人图标 [Icons.smart_toy_outlined]。
class AgentBrandIcon extends StatelessWidget {
  const AgentBrandIcon({
    super.key,
    required this.agentId,
    this.size = 20,
    this.fallbackColor,
    this.tint,
  });

  /// [AcpAgentProfile.id]（例如 `codex-acp`、`deepseek-harness-acp`）。
  final String agentId;

  /// 图标绘制尺寸。
  final double size;

  /// 回退图标（自定义 Agent）的着色；默认取主题主色。
  final Color? fallbackColor;

  /// 可选的统一着色。菜单等需要表达选中态时使用；未提供时保留品牌色。
  final Color? tint;

  static const Map<String, _AgentBrand> _brands = {
    'xiaowan-acp': _AgentBrand('assets/home/avatar.svg'),
    'codex-acp': _AgentBrand('assets/agents/codex.svg'),
    'codex-remote': _AgentBrand('assets/agents/codex.svg'),
    'claude-code-acp': _AgentBrand(
      'assets/agents/claude_code.svg',
      brandColor: Color(0xFFD97757),
    ),
    'opencode-acp': _AgentBrand('assets/agents/opencode.svg'),
    'deepseek-harness-acp': _AgentBrand(
      'assets/provider_icons/deepseek.svg',
      brandColor: Color(0xFF4D6BFE),
    ),
  };

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final brand = _brands[agentId.trim()];
    if (brand == null) {
      return Icon(
        Icons.smart_toy_outlined,
        size: size,
        color: tint ?? fallbackColor ?? palette.accentPrimary,
      );
    }
    // 单色品牌图标使用 currentColor，这里按品牌色或主题文字色着色，
    // 使其在深浅色主题下都清晰可见。
    final effectiveTint = tint ?? brand.brandColor ?? palette.textPrimary;
    return SvgPicture.asset(
      brand.asset,
      width: size,
      height: size,
      colorFilter: ColorFilter.mode(effectiveTint, BlendMode.srcIn),
    );
  }
}

class _AgentBrand {
  const _AgentBrand(this.asset, {this.brandColor});

  final String asset;

  /// 固定品牌色（如 Claude 的珊瑚橘）；为 null 时跟随主题文字色。
  final Color? brandColor;
}
