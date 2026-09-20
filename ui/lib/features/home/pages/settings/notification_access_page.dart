import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/widgets/common_app_bar.dart';

class NotificationAccessPage extends StatefulWidget {
  const NotificationAccessPage({super.key});
  @override
  State<NotificationAccessPage> createState() => _NotificationAccessPageState();
}

class _NotificationAccessPageState extends State<NotificationAccessPage>
    with WidgetsBindingObserver {
  static const channel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );
  Map<dynamic, dynamic>? _settings;
  String? _error;
  bool _busy = false;
  bool get _english => Localizations.localeOf(context).languageCode == 'en';
  String _text(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _call('getNotificationAccessSettings');
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _call('getNotificationAccessSettings');
    }
  }

  Future<void> _call(String method, [Map<String, dynamic>? arguments]) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final data = await channel.invokeMapMethod<dynamic, dynamic>(
        method,
        arguments,
      );
      if (mounted) setState(() => _settings = data);
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final granted = _settings?['granted'] == true;
    final apps = (_settings?['apps'] as List?) ?? const [];
    return Scaffold(
      appBar: CommonAppBar(
        primary: true,
        title: _text('通知访问', 'Notification access'),
        onBackPressed: () => Navigator.of(context).pop(),
      ),
      body: ListView(
        padding: const EdgeInsets.all(18),
        children: [
          Text(
            _text(
              '允许小万按需读取你选择的应用通知，例如外卖配送进度。仅查询通知栏中仍存在的通知，不保存通知正文，不自动订阅。查询到的内容会提供给当前模型。',
              'Let Xiaowan read notifications from selected apps on request, such as delivery updates. Only current notifications are queried; bodies are not stored and there is no automatic subscription. Queried content is shared with the current model.',
            ),
          ),
          const SizedBox(height: 16),
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(_text('系统通知访问权限', 'System notification access')),
            subtitle: Text(
              granted ? _text('已授权', 'Granted') : _text('未授权', 'Not granted'),
            ),
            trailing: TextButton(
              onPressed: _busy
                  ? null
                  : () => _call('openNotificationAccessSettings'),
              child: Text(_text('设置', 'Settings')),
            ),
          ),
          if (granted && _settings?['connected'] != true)
            Text(
              _text(
                '正在等待系统连接通知服务，请稍后刷新。',
                'Waiting for Android to connect the listener. Refresh shortly.',
              ),
            ),
          if (_error != null)
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          if (_busy) const LinearProgressIndicator(),
          const SizedBox(height: 16),
          Text(_text('允许读取的应用（默认全部关闭）', 'Allowed apps (all off by default)')),
          Text(
            _text(
              '全部操作仅适用于当前已安装应用，包含系统消息应用；新安装应用默认关闭。',
              'Bulk selection includes currently installed system and messaging apps. New apps remain off.',
            ),
          ),
          Wrap(
            spacing: 12,
            children: [
              TextButton(
                onPressed: _busy || apps.isEmpty
                    ? null
                    : () => _call('setAllNotificationAppsAllowed', {
                        'allowed': true,
                      }),
                child: Text(_text('全部开启', 'Enable all')),
              ),
              TextButton(
                onPressed: _busy || apps.isEmpty
                    ? null
                    : () => _call('setAllNotificationAppsAllowed', {
                        'allowed': false,
                      }),
                child: Text(_text('全部关闭', 'Disable all')),
              ),
            ],
          ),
          for (final raw in apps)
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(raw['name'] as String),
              subtitle: Text(raw['applicationId'] as String),
              value: raw['allowed'] == true,
              onChanged: _busy
                  ? null
                  : (value) => _call('setNotificationAppAllowed', {
                      'applicationId': raw['applicationId'],
                      'allowed': value,
                    }),
            ),
          TextButton(
            onPressed: _busy
                ? null
                : () => _call('getNotificationAccessSettings'),
            child: Text(_text('刷新', 'Refresh')),
          ),
        ],
      ),
    );
  }
}
