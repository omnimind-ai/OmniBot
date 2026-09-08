import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/models/remote_mcp_server.dart';
import 'package:ui/services/remote_mcp_config_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class RemoteMcpServersPage extends StatefulWidget {
  const RemoteMcpServersPage({super.key});

  @override
  State<RemoteMcpServersPage> createState() => _RemoteMcpServersPageState();
}

class _RemoteMcpServersPageState extends State<RemoteMcpServersPage> {
  bool _loading = true;
  final Set<String> _busyIds = {};
  List<RemoteMcpServer> _servers = [];

  @override
  void initState() {
    super.initState();
    _loadServers();
  }

  Future<void> _loadServers() async {
    setState(() => _loading = true);
    try {
      final servers = await RemoteMcpConfigService.listServers();
      if (!mounted) return;
      setState(() {
        _servers = servers;
        _loading = false;
      });
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() => _loading = false);
      showToast(
        e.message ??
            (Localizations.localeOf(context).languageCode == 'en'
                ? 'Failed to load MCP tools'
                : '加载 MCP 工具失败'),
        type: ToastType.error,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _loading = false);
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Failed to load MCP tools'
            : '加载 MCP 工具失败',
        type: ToastType.error,
      );
    }
  }

  Future<void> _toggleServer(RemoteMcpServer server, bool enabled) async {
    _setBusy(server.id, true);
    try {
      final updated = await RemoteMcpConfigService.setServerEnabled(
        server.id,
        enabled,
      );
      if (!mounted) return;
      setState(() {
        _servers = _servers.map((item) {
          if (item.id != server.id) return item;
          return updated ?? item.copyWith(enabled: enabled);
        }).toList();
      });
    } catch (e) {
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Toggle failed'
            : '切换失败',
        type: ToastType.error,
      );
    } finally {
      _setBusy(server.id, false);
    }
  }

  Future<void> _refreshTools(RemoteMcpServer server) async {
    _setBusy(server.id, true);
    try {
      final updated = await RemoteMcpConfigService.refreshServerTools(
        server.id,
      );
      if (!mounted) return;
      setState(() {
        _servers = _servers.map((item) {
          if (item.id != server.id) return item;
          return updated ?? item;
        }).toList();
      });
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Tool list refreshed'
            : '工具列表已刷新',
      );
    } on PlatformException catch (e) {
      await _reloadServersSilently();
      showToast(
        e.message ??
            (Localizations.localeOf(context).languageCode == 'en'
                ? 'Refresh failed'
                : '刷新失败'),
        type: ToastType.error,
      );
    } catch (_) {
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Refresh failed'
            : '刷新失败',
        type: ToastType.error,
      );
    } finally {
      _setBusy(server.id, false);
    }
  }

  Future<void> _reloadServersSilently() async {
    try {
      final servers = await RemoteMcpConfigService.listServers();
      if (!mounted) return;
      setState(() => _servers = servers);
    } catch (_) {
      // Preserve the operation error already shown to the user.
    }
  }

  Future<void> _deleteServer(RemoteMcpServer server) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          Localizations.localeOf(context).languageCode == 'en'
              ? 'Delete MCP Service'
              : '删除 MCP 服务',
        ),
        content: Text(
          Localizations.localeOf(context).languageCode == 'en'
              ? 'Confirm deleting "${server.name}"?'
              : '确认删除“${server.name}”？',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(
              Localizations.localeOf(context).languageCode == 'en'
                  ? 'Delete'
                  : '删除',
            ),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    _setBusy(server.id, true);
    try {
      await RemoteMcpConfigService.deleteServer(server.id);
      if (!mounted) return;
      setState(() {
        _servers.removeWhere((item) => item.id == server.id);
      });
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Deleted'
            : '已删除',
      );
    } catch (e) {
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Delete failed'
            : '删除失败',
        type: ToastType.error,
      );
    } finally {
      _setBusy(server.id, false);
    }
  }

  Future<void> _showServerEditor({RemoteMcpServer? server}) async {
    final saved = await showModalBottomSheet<RemoteMcpServer>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.isDarkTheme
          ? context.omniPalette.surfacePrimary
          : Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _RemoteMcpServerEditorSheet(server: server),
    );

    if (saved == null) return;
    try {
      final result = await RemoteMcpConfigService.upsertServer(saved);
      if (result == null || !mounted) return;
      setState(() {
        final index = _servers.indexWhere((item) => item.id == result.id);
        if (index == -1) {
          _servers = [result, ..._servers];
        } else {
          _servers[index] = result;
        }
      });
      showToast(
        Localizations.localeOf(context).languageCode == 'en' ? 'Saved' : '已保存',
      );
    } catch (e) {
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Save failed'
            : '保存失败',
        type: ToastType.error,
      );
    }
  }

  void _setBusy(String id, bool busy) {
    if (!mounted) return;
    setState(() {
      if (busy) {
        _busyIds.add(id);
      } else {
        _busyIds.remove(id);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: Localizations.localeOf(context).languageCode == 'en'
            ? 'MCP Tools'
            : 'MCP 工具',
        primary: true,
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _showServerEditor(),
        child: const Icon(LucideIcons.plus),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _servers.isEmpty
          ? _buildEmpty()
          : RefreshIndicator(
              onRefresh: _loadServers,
              child: ListView(
                padding: edgeToEdgeScrollPadding(
                  context,
                  const EdgeInsets.fromLTRB(18, 12, 18, 24),
                ),
                children: [
                  SettingsSectionTitle(
                    label: Localizations.localeOf(context).languageCode == 'en'
                        ? 'Remote Services'
                        : '远端服务',
                  ),
                  for (int index = 0; index < _servers.length; index++) ...[
                    _buildServerCard(_servers[index]),
                    if (index != _servers.length - 1) const Divider(height: 24),
                  ],
                ],
              ),
            ),
    );
  }

  Widget _buildEmpty() {
    final palette = context.omniPalette;
    return ListView(
      padding: edgeToEdgeScrollPadding(context, const EdgeInsets.all(24)),
      children: [
        const SizedBox(height: 120),
        Icon(
          LucideIcons.puzzle,
          size: 48,
          color: context.isDarkTheme ? palette.textTertiary : AppColors.text50,
        ),
        const SizedBox(height: 12),
        Center(
          child: Text(
            Localizations.localeOf(context).languageCode == 'en'
                ? 'No remote MCP services'
                : '暂无远端 MCP 服务',
            style: TextStyle(
              fontSize: 16,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildServerCard(RemoteMcpServer server) {
    final busy = _busyIds.contains(server.id);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    server.name,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    server.endpointUrl,
                    style: TextStyle(
                      fontSize: 12,
                      color: context.omniPalette.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            Switch(
              value: server.enabled,
              onChanged: busy ? null : (value) => _toggleServer(server, value),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _MetaChip(label: _healthLabel(server.lastHealth)),
            _MetaChip(
              label: Localizations.localeOf(context).languageCode == 'en'
                  ? 'Tools ${server.toolCount}'
                  : '工具 ${server.toolCount}',
            ),
            if ((server.lastError ?? '').isNotEmpty)
              _MetaChip(
                label: server.lastError!,
                color: AppColors.alertRed.withValues(alpha: 0.08),
                textColor: AppColors.alertRed,
              ),
          ],
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            TextButton(
              onPressed: busy ? null : () => _refreshTools(server),
              child: Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Refresh tools'
                    : '刷新工具',
              ),
            ),
            TextButton(
              onPressed: busy ? null : () => _showServerEditor(server: server),
              child: Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Edit'
                    : '编辑',
              ),
            ),
            const Spacer(),
            TextButton(
              onPressed: busy ? null : () => _deleteServer(server),
              child: Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Delete'
                    : '删除',
              ),
            ),
          ],
        ),
      ],
    );
  }

  String _healthLabel(String health) {
    switch (health) {
      case 'healthy':
        return Localizations.localeOf(context).languageCode == 'en'
            ? 'Connected'
            : '连接正常';
      case 'error':
        return Localizations.localeOf(context).languageCode == 'en'
            ? 'Connection error'
            : '连接异常';
      default:
        return Localizations.localeOf(context).languageCode == 'en'
            ? 'Unknown'
            : '状态未知';
    }
  }
}

class _InputField extends StatelessWidget {
  final TextEditingController controller;
  final FocusNode? focusNode;
  final String label;
  final String? hint;

  const _InputField({
    required this.controller,
    this.focusNode,
    required this.label,
    this.hint,
  });

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      focusNode: focusNode,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }
}

class _RemoteMcpServerEditorSheet extends StatefulWidget {
  const _RemoteMcpServerEditorSheet({this.server});

  final RemoteMcpServer? server;

  @override
  State<_RemoteMcpServerEditorSheet> createState() =>
      _RemoteMcpServerEditorSheetState();
}

class _RemoteMcpServerEditorSheetState
    extends State<_RemoteMcpServerEditorSheet> {
  late final TextEditingController _nameController;
  late final TextEditingController _endpointController;
  late final TextEditingController _tokenController;
  final FocusNode _nameFocusNode = FocusNode();
  late bool _enabled;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.server?.name ?? '');
    _endpointController = TextEditingController(
      text: widget.server?.endpointUrl ?? '',
    );
    _tokenController = TextEditingController(
      text: widget.server?.bearerToken ?? '',
    );
    _enabled = widget.server?.enabled ?? true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _nameFocusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _nameFocusNode.dispose();
    _nameController.dispose();
    _endpointController.dispose();
    _tokenController.dispose();
    super.dispose();
  }

  void _close([RemoteMcpServer? value]) {
    FocusScope.of(context).unfocus();
    Navigator.of(context).pop(value);
  }

  void _submit() {
    final name = _nameController.text.trim();
    final endpoint = _endpointController.text.trim();
    if (name.isEmpty || endpoint.isEmpty) {
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Please enter both name and address'
            : '请填写名称和地址',
        type: ToastType.error,
      );
      return;
    }
    _close(
      (widget.server ??
              const RemoteMcpServer(
                id: '',
                name: '',
                endpointUrl: '',
                bearerToken: '',
                enabled: true,
                lastHealth: 'unknown',
                toolCount: 0,
              ))
          .copyWith(
            name: name,
            endpointUrl: endpoint,
            bearerToken: _tokenController.text.trim(),
            enabled: _enabled,
          ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 20,
        bottom: MediaQuery.of(context).viewInsets.bottom + 20,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            widget.server == null
                ? (Localizations.localeOf(context).languageCode == 'en'
                      ? 'Add MCP Service'
                      : '添加 MCP 服务')
                : (Localizations.localeOf(context).languageCode == 'en'
                      ? 'Edit MCP Service'
                      : '编辑 MCP 服务'),
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 16),
          _InputField(
            controller: _nameController,
            focusNode: _nameFocusNode,
            label: Localizations.localeOf(context).languageCode == 'en'
                ? 'Name'
                : '名称',
          ),
          const SizedBox(height: 12),
          _InputField(
            controller: _endpointController,
            label: 'Endpoint URL',
            hint: 'https://example.com/mcp',
          ),
          const SizedBox(height: 12),
          _InputField(
            controller: _tokenController,
            label: Localizations.localeOf(context).languageCode == 'en'
                ? 'Bearer Token (Optional)'
                : 'Bearer Token（可选）',
          ),
          const SizedBox(height: 12),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            value: _enabled,
            onChanged: (value) => setState(() => _enabled = value),
            title: Text(
              Localizations.localeOf(context).languageCode == 'en'
                  ? 'Enabled'
                  : '启用',
            ),
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _submit,
              child: Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Save'
                    : '保存',
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _MetaChip extends StatelessWidget {
  final String label;
  final Color? color;
  final Color? textColor;

  const _MetaChip({required this.label, this.color, this.textColor});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color:
            color ??
            (context.isDarkTheme
                ? palette.surfaceSecondary
                : AppColors.background),
        borderRadius: BorderRadius.circular(999),
        border: color == null && context.isDarkTheme
            ? Border.all(color: palette.borderSubtle)
            : null,
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          color:
              textColor ??
              (context.isDarkTheme ? palette.textSecondary : AppColors.text70),
        ),
      ),
    );
  }
}
