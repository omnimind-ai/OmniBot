import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/services/agent_runtime_service.dart';

typedef NaLocalTimeResolver = Future<Map<String, dynamic>> Function({
  String? localTime,
  String? at,
  required String timezone,
});

const _weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
const _modes = {
  'once': '只运行一次',
  'interval': '按间隔',
  'daily': '每天',
  'weekly': '每周',
  'monthly': '每月',
  'event': '应用事件',
  'cron': 'Cron（高级）',
};
Map<String, dynamic> _map(dynamic value) =>
    value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};

/// Form draft only. Execution, scheduling and permissions stay server-owned.
class NaRoutineDraft {
  NaRoutineDraft({this.original, required String timezone}) {
    final routine = original ?? {};
    final schedule = _map(routine['schedule']);
    this.timezone = schedule['timezone']?.toString() ?? timezone;
    title = routine['title']?.toString() ?? '';
    prompt = routine['prompt']?.toString() ?? '';
    continuity = routine['continuity'] as bool? ?? (original == null);
    deliver = routine['deliver']?.toString() ?? 'chat';
    maxRunsPerDay = (routine['maxRunsPerDay'] ?? 24).toString();
    checks = List<String>.from(routine['checks'] as List? ?? []);
    mode = routine['trigger'] == 'event' ? 'event' : 'once';
    interval = (schedule['everyMinutes'] ?? routine['intervalMinutes'] ?? 60)
        .toString();
    if (routine['trigger'] != 'event') {
      if (schedule['type'] == 'interval' ||
          routine['intervalMinutes'] != null) {
        mode = 'interval';
      }
      if (schedule['type'] == 'interval') {
        weekdays = List<int>.from(schedule['weekdays'] as List? ?? []);
        final window = _map(schedule['window']);
        windowStart = window['start']?.toString() ?? '';
        windowEnd = window['end']?.toString() ?? '';
      }
      if (schedule['time'] is String) {
        mode = schedule['weekday'] == null ? 'daily' : 'weekly';
        time = schedule['time'] as String;
        weekday = [
          'Sun',
          'Mon',
          'Tue',
          'Wed',
          'Thu',
          'Fri',
          'Sat',
        ].indexOf(schedule['weekday']?.toString() ?? 'Mon');
      }
      if (schedule['type'] == 'cron') {
        mode = 'cron';
        cron = schedule['expression']?.toString() ?? '';
        final parts = cron.trim().split(RegExp(r'\s+'));
        if (parts.length == 5) {
          final minute = int.tryParse(parts[0]), hour = int.tryParse(parts[1]);
          if (minute != null &&
              minute >= 0 &&
              minute < 60 &&
              hour != null &&
              hour >= 0 &&
              hour < 24 &&
              parts[3] == '*') {
            time = '${_pad(hour)}:${_pad(minute)}';
            if (parts[2] == '*' && parts[4] == '*') {
              mode = 'daily';
            } else if (parts[2] == '*' &&
                RegExp(r'^[0-6]$').hasMatch(parts[4])) {
              mode = 'weekly';
              weekday = int.parse(parts[4]);
            } else if (parts[4] == '*' &&
                (int.tryParse(parts[2]) ?? 0) >= 1 &&
                (int.tryParse(parts[2]) ?? 32) <= 31) {
              mode = 'monthly';
              monthDay = parts[2];
            }
          }
        }
      }
    }
  }

  final Map<String, dynamic>? original;
  late String title, prompt, timezone, mode, interval, maxRunsPerDay, deliver;
  late bool continuity;
  late List<String> checks;
  String localTime = '', explicitAt = '', time = '09:00';
  String monthDay = '1', cron = '0 9 * * *', windowStart = '', windowEnd = '';
  int weekday = 1;
  List<int> weekdays = [];
  String? _initialTiming;

  String get initialAt {
    final schedule = _map(original?['schedule']);
    return (schedule['type'] == 'once'
                ? schedule['at']
                : original?['nextRunAt'])
            ?.toString() ??
        DateTime.now().toUtc().add(const Duration(hours: 1)).toIso8601String();
  }

  void acceptInitialLocalTime(String value) {
    localTime = value;
    _initialTiming = _timingKey;
  }

  String get _timingKey => jsonEncode({
    'mode': mode,
    'timezone': timezone,
    'localTime': localTime,
    'explicitAt': explicitAt,
    'time': time,
    'weekday': weekday,
    'monthDay': monthDay,
    'interval': interval,
    'cron': cron,
    'weekdays': [...weekdays]..sort(),
    'windowStart': windowStart,
    'windowEnd': windowEnd,
  });

  Future<Map<String, dynamic>> payload(NaLocalTimeResolver resolve) async {
    if (title.trim().isEmpty || prompt.trim().isEmpty) {
      throw const FormatException('请填写职责名称和检查内容。');
    }
    final max = int.tryParse(maxRunsPerDay);
    if (max == null || max < 1 || max > 1440) {
      throw const FormatException('每日上限需为 1 至 1440 次。');
    }
    final body = <String, dynamic>{
      'title': title.trim(),
      'prompt': prompt.trim(),
    };
    if (original == null || _timingKey != _initialTiming) {
      if (mode == 'event') {
        body['trigger'] = 'event';
      } else {
        body['trigger'] = 'schedule';
        if (mode == 'once') {
          String at;
          if (explicitAt.trim().isNotEmpty) {
            final explicit = explicitAt.trim();
            final match = RegExp(
              r'^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:\d{2})$',
            ).firstMatch(explicit);
            final parsed = DateTime.tryParse(explicit);
            if (match == null || parsed == null) {
              throw const FormatException('高级时间需带 Z 或明确时区偏移。');
            }
            final offset = match.group(7)!;
            if (offset != 'Z' &&
                (int.parse(offset.substring(1, 3)) > 23 ||
                    int.parse(offset.substring(4)) > 59)) {
              throw const FormatException('时区偏移无效。');
            }
            final parts = [
              for (var i = 1; i <= 6; i++) int.parse(match.group(i) ?? '0'),
            ];
            final calendar = DateTime.utc(
              parts[0],
              parts[1],
              parts[2],
              parts[3],
              parts[4],
              parts[5],
            );
            if (calendar.year != parts[0] ||
                calendar.month != parts[1] ||
                calendar.day != parts[2] ||
                calendar.hour != parts[3] ||
                calendar.minute != parts[4] ||
                calendar.second != parts[5]) {
              throw const FormatException('日期或时间无效。');
            }
            at = parsed.toUtc().toIso8601String();
          } else {
            at =
                (await resolve(localTime: localTime, timezone: timezone))['at']
                    as String;
          }
          body['schedule'] = {'type': 'once', 'at': at};
        } else if (mode == 'interval') {
          final every = num.tryParse(interval);
          if (every == null || !every.isFinite || every < 1 || every > 525600) {
            throw const FormatException('间隔需为 1 至 525600 分钟。');
          }
          Map<String, String>? window;
          if (windowStart.isNotEmpty || windowEnd.isNotEmpty) {
            if (!_validTime(windowStart) ||
                !_validTime(windowEnd) ||
                windowStart.compareTo(windowEnd) >= 0) {
              throw const FormatException('执行时段需完整填写，结束时间须晚于开始时间。');
            }
            window = {'start': windowStart, 'end': windowEnd};
          }
          body['schedule'] = {
            'type': 'interval',
            'everyMinutes': every,
            'timezone': timezone,
            if (weekdays.isNotEmpty) 'weekdays': [...weekdays]..sort(),
            'window': ?window,
          };
        } else {
          if (mode != 'cron' && !_validTime(time)) {
            throw const FormatException('请选择有效的执行时间。');
          }
          final parts = time.split(':');
          final day = int.tryParse(monthDay);
          if (mode == 'monthly' && (day == null || day < 1 || day > 31)) {
            throw const FormatException('每月日期需为 1 至 31。');
          }
          final expression = mode == 'cron'
              ? cron.trim()
              : '${int.parse(parts[1])} ${int.parse(parts[0])} ${mode == 'monthly' ? day : '*'} * ${mode == 'weekly' ? weekday : '*'}';
          if (expression.split(RegExp(r'\s+')).length != 5 ||
              RegExp(r'[@?]').hasMatch(expression) ||
              expression.length > 200) {
            throw const FormatException('Cron 需为五个字段：分、时、日、月、星期。');
          }
          body['schedule'] = {
            'type': 'cron',
            'expression': expression,
            'timezone': timezone,
          };
        }
      }
    }
    final values = <String, dynamic>{
      'continuity': continuity,
      'deliver': deliver,
      'maxRunsPerDay': max,
      'checks': [...checks]..sort(),
    };
    final previous = {
      'continuity': original?['continuity'] ?? false,
      'deliver': original?['deliver'] ?? 'chat',
      'maxRunsPerDay': original?['maxRunsPerDay'] ?? 24,
      'checks': List<String>.from(original?['checks'] as List? ?? [])..sort(),
    };
    for (final entry in values.entries) {
      if (original == null ||
          jsonEncode(entry.value) != jsonEncode(previous[entry.key])) {
        body[entry.key] = entry.value;
      }
    }
    return body;
  }
}

String _pad(int n) => n.toString().padLeft(2, '0');
bool _validTime(String value) =>
    RegExp(r'^([01]\d|2[0-3]):[0-5]\d$').hasMatch(value);

String naRoutineStatus(Map<String, dynamic> routine) {
  if (routine['enabled'] != true) return '已暂停';
  if (routine['waitingReview'] != null || routine['heldReason'] == 'review') {
    return '等待你审核应用操作';
  }
  if (routine['heldReason'] == 'daily_cap') return '已达每日运行上限';
  if (routine['lastCheckFailureCause'] != null) return '应用读取未完成，不代表没有新内容';
  if (routine['heldReason'] == 'check_unavailable') return '等待可用的检查结果或读取授权';
  if (routine['heldReason'] == 'boot_hold') return '启动后等待首次检查';
  if (routine['heldReason'] != null) return '暂缓执行：${routine['heldReason']}';
  if (routine['nextAllowedAt'] != null ||
      routine['nextCheckAllowedAt'] != null) {
    return '暂缓，等待下次重试';
  }
  if (routine['scheduleDone'] == true) return '本次计划已完成';
  return routine['trigger'] == 'event' ? '等待应用事件' : '计划已启用';
}

Future<Map<String, dynamic>?> showNaRoutineEditor(
  BuildContext context, {
  Map<String, dynamic>? routine,
  required String timezone,
  NaLocalTimeResolver resolveLocalTime = AgentRuntimeService.requestNaLocalTime,
  bool Function()? isCurrent,
}) async {
  final draft = NaRoutineDraft(original: routine, timezone: timezone);
  final local = await resolveLocalTime(
    at: draft.initialAt,
    timezone: draft.timezone,
  );
  draft.acceptInitialLocalTime(local['localTime'] as String);
  if (!context.mounted || isCurrent?.call() == false) return null;
  return showDialog<Map<String, dynamic>>(
    context: context,
    builder: (_) =>
        NaRoutineEditor(draft: draft, resolveLocalTime: resolveLocalTime),
  );
}

class NaRoutineEditor extends StatefulWidget {
  const NaRoutineEditor({
    super.key,
    required this.draft,
    required this.resolveLocalTime,
    this.isCurrent,
  });
  final NaRoutineDraft draft;
  final NaLocalTimeResolver resolveLocalTime;
  final bool Function()? isCurrent;

  @override
  State<NaRoutineEditor> createState() => _NaRoutineEditorState();
}

class _NaRoutineEditorState extends State<NaRoutineEditor> {
  final _form = GlobalKey<FormState>();
  bool _saving = false;
  String? _error;
  NaRoutineDraft get draft => widget.draft;

  Future<void> _save() async {
    if (_saving) return;
    if (widget.isCurrent?.call() == false) {
      Navigator.of(context).pop();
      return;
    }
    if (!(_form.currentState?.validate() ?? false)) return;
    _form.currentState!.save();
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final result = await draft.payload(widget.resolveLocalTime);
      if (mounted) {
        Navigator.of(context)
            .pop(widget.isCurrent?.call() == false ? null : result);
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _error = error is FormatException
              ? error.message
              : error is PlatformException
              ? (error.message ?? '日期或时区无效。')
              : '日期或时区无效，请检查后重试。';
        });
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Widget _text(
    String label,
    String value,
    ValueChanged<String> change, {
    int lines = 1,
    bool required = false,
    TextInputType? keyboard,
    String? hint,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: TextFormField(
      key: ValueKey(label),
      initialValue: value,
      maxLines: lines,
      keyboardType: keyboard,
      decoration: InputDecoration(
        labelText: label,
        helperText: hint,
        helperMaxLines: 4,
      ),
      onChanged: change,
      onSaved: (value) => change(value ?? ''),
      validator: required
          ? (value) =>
                value == null || value.trim().isEmpty ? '请填写$label' : null
          : null,
    ),
  );

  Widget _select<T>(
    String label,
    T value,
    List<DropdownMenuItem<T>> items,
    ValueChanged<T> change,
  ) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: DropdownButtonFormField<T>(
      key: ValueKey('$label:$value'),
      initialValue: value,
      isExpanded: true,
      decoration: InputDecoration(labelText: label),
      items: items,
      onChanged: _saving
          ? null
          : (value) {
              if (value != null) setState(() => change(value));
            },
    ),
  );

  Future<void> _pickTime(String current, ValueChanged<String> change) async {
    final parts = current.split(':');
    final result = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(
        hour: int.tryParse(parts.first) ?? 9,
        minute: int.tryParse(parts.length > 1 ? parts[1] : '') ?? 0,
      ),
    );
    if (result != null && mounted) {
      setState(() => change('${_pad(result.hour)}:${_pad(result.minute)}'));
    }
  }

  Widget _timeButton(
    String label,
    String current,
    ValueChanged<String> change,
  ) => ListTile(
    contentPadding: EdgeInsets.zero,
    title: Text(label),
    trailing: TextButton(
      onPressed: _saving ? null : () => _pickTime(current, change),
      child: Text(current.isEmpty ? '选择时间' : current),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final local = DateTime.tryParse(draft.localTime);
    return AlertDialog(
      title: Text(draft.original == null ? '新增职责' : '编辑职责'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Form(
            key: _form,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _text(
                  '职责名称',
                  draft.title,
                  (v) => draft.title = v,
                  required: true,
                ),
                _text(
                  '检查内容与提醒条件',
                  draft.prompt,
                  (v) => draft.prompt = v,
                  lines: 3,
                  required: true,
                ),
                _select(
                  '执行方式',
                  draft.mode,
                  _modes.entries
                      .map(
                        (e) => DropdownMenuItem(
                          value: e.key,
                          child: Text(e.value),
                        ),
                      )
                      .toList(),
                  (v) => draft.mode = v,
                ),
                if (draft.mode != 'event')
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Text('计划时区：${draft.timezone}'),
                  ),
                if (draft.mode == 'once') ...[
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('执行日期'),
                    trailing: TextButton(
                      onPressed: _saving
                          ? null
                          : () async {
                              final result = await showDatePicker(
                                context: context,
                                initialDate: local ?? DateTime.now(),
                                firstDate: DateTime(2000),
                                lastDate: DateTime(2200),
                              );
                              if (result != null && mounted) {
                                setState(() {
                                  draft.localTime =
                                      '${result.year}-${_pad(result.month)}-${_pad(result.day)}T${draft.localTime.split('T').last}';
                                });
                              }
                            },
                      child: Text(draft.localTime.split('T').first),
                    ),
                  ),
                  _timeButton(
                    '执行时间',
                    draft.localTime.split('T').last,
                    (v) => draft.localTime =
                        '${draft.localTime.split('T').first}T$v',
                  ),
                ],
                if (draft.mode == 'interval')
                  _text(
                    '重复间隔（分钟）',
                    draft.interval,
                    (v) => draft.interval = v,
                    keyboard: TextInputType.number,
                  ),
                if (['daily', 'weekly', 'monthly'].contains(draft.mode))
                  _timeButton('执行时间', draft.time, (v) => draft.time = v),
                if (draft.mode == 'weekly')
                  _select(
                    '星期',
                    draft.weekday,
                    List.generate(
                      7,
                      (i) =>
                          DropdownMenuItem(value: i, child: Text(_weekdays[i])),
                    ),
                    (v) => draft.weekday = v,
                  ),
                if (draft.mode == 'monthly')
                  _text(
                    '每月几号',
                    draft.monthDay,
                    (v) => draft.monthDay = v,
                    keyboard: TextInputType.number,
                    hint: '所选日期在当月不存在时跳过该月。',
                  ),
                if (draft.mode == 'cron')
                  _text(
                    'Cron 表达式',
                    draft.cron,
                    (v) => draft.cron = v,
                    hint: '五个字段：分 时 日 月 星期，例如 0 9 * * 1-5。',
                  ),
                if (draft.mode == 'event')
                  const Text('保存后，在“连接应用”的应用事件中绑定此职责；仅收到匹配事件时触发。'),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('持续跟进'),
                  subtitle: const Text('检查有新变化时继续处理，没有变化时保持安静。'),
                  value: draft.continuity,
                  onChanged: _saving
                      ? null
                      : (v) => setState(() => draft.continuity = v),
                ),
                _select('结果送达', draft.deliver, const [
                  DropdownMenuItem(value: 'chat', child: Text('原会话')),
                  DropdownMenuItem(value: 'brief', child: Text('每日简报')),
                ], (v) => draft.deliver = v),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: const Text('高级设置'),
                  children: [
                    _text(
                      '计划时区',
                      draft.timezone,
                      (v) => draft.timezone = v,
                      hint: '默认使用 Na 服务资料中的 IANA 时区。',
                    ),
                    _text(
                      '每日最多运行次数',
                      draft.maxRunsPerDay,
                      (v) => draft.maxRunsPerDay = v,
                      keyboard: TextInputType.number,
                    ),
                    if (draft.mode == 'once')
                      _text(
                        '明确偏移的执行时间（可选）',
                        draft.explicitAt,
                        (v) => draft.explicitAt = v,
                        hint: '夏令时重叠时可填 2026-11-01T01:30:00-05:00；填写后优先使用。',
                      ),
                    if (draft.mode == 'interval') ...[
                      const Text('仅在这些星期执行（不选则每天）'),
                      Wrap(
                        spacing: 6,
                        children: List.generate(
                          7,
                          (i) => FilterChip(
                            label: Text(_weekdays[i]),
                            selected: draft.weekdays.contains(i),
                            onSelected: (v) => setState(() {
                              v
                                  ? draft.weekdays.add(i)
                                  : draft.weekdays.remove(i);
                            }),
                          ),
                        ),
                      ),
                      _timeButton(
                        '每日开始时间（可选）',
                        draft.windowStart,
                        (v) => draft.windowStart = v,
                      ),
                      _timeButton(
                        '每日结束时间（可选）',
                        draft.windowEnd,
                        (v) => draft.windowEnd = v,
                      ),
                      if (draft.windowStart.isNotEmpty ||
                          draft.windowEnd.isNotEmpty)
                        TextButton(
                          onPressed: () => setState(() {
                            draft.windowStart = '';
                            draft.windowEnd = '';
                          }),
                          child: const Text('清除执行时段'),
                        ),
                    ],
                    const Align(
                      alignment: Alignment.centerLeft,
                      child: Text('先检查变化，再决定是否需要 AI 跟进'),
                    ),
                    for (final check in {
                      'mail': '邮件',
                      'calendar': '日历',
                      'runs': '任务进展',
                    }.entries)
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(check.value),
                        value: draft.checks.contains(check.key),
                        onChanged: (v) => setState(() {
                          v == true
                              ? draft.checks.add(check.key)
                              : draft.checks.remove(check.key);
                        }),
                      ),
                    const Text(
                      '邮件和日历需先手动运行一次，再为对应读取操作保存重复授权。未获得授权或读取失败会显示等待或失败，不会当作“没有新内容”。',
                    ),
                  ],
                ),
                if (draft.original != null)
                  const Padding(
                    padding: EdgeInsets.only(top: 12),
                    child: Text('修改职责或计划会使原重复授权失效，需要重新审核后保存授权。'),
                  ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      _error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _saving ? null : _save,
          child: Text(_saving ? '正在保存…' : '保存职责'),
        ),
      ],
    );
  }
}
