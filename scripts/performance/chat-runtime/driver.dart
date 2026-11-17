// Host driver: only connects to the verified independent synthetic app.
import 'dart:convert';
import 'dart:io';

import 'package:vm_service/vm_service.dart' show RPCError, VmService;
import 'package:vm_service/vm_service_io.dart';

const _package = 'cn.com.omnimind.bot.runtimeperf';

// Chrome CPU profile tree; raw VM samples are also retained without conversion.
Map<String, dynamic> cpuProfileJson(Map<String, dynamic> raw) {
  final functions = (raw['functions'] as List? ?? const []).cast<Map>();
  final samples = (raw['samples'] as List? ?? const []).cast<Map>().toList()
    ..sort((a, b) => (a['timestamp'] as int).compareTo(b['timestamp'] as int));
  final nodes = <Map<String, dynamic>>[
    {
      'id': 1,
      'callFrame': {
        'functionName': '(root)',
        'scriptId': '0',
        'url': '',
        'lineNumber': -1,
        'columnNumber': -1,
      },
      'hitCount': 0,
      'children': <int>[],
    },
  ];
  final positions = <String, int>{};
  final leafIds = <int>[];
  final deltas = <int>[];
  final origin = raw['timeOriginMicros'] as int;
  var previous = origin;
  for (final sample in samples) {
    var parent = 1;
    for (final index in (sample['stack'] as List).cast<int>().reversed) {
      final key = '$parent:$index';
      var id = positions[key];
      if (id == null) {
        final function = functions[index];
        final reference = function['function'] as Map? ?? const {};
        final location = reference['location'] as Map? ?? const {};
        final script = location['script'] as Map? ?? const {};
        id = nodes.length + 1;
        positions[key] = id;
        (nodes[parent - 1]['children'] as List<int>).add(id);
        nodes.add({
          'id': id,
          'callFrame': {
            'functionName': reference['name'] ?? '(unknown)',
            'scriptId': script['id'] ?? '0',
            'url': function['resolvedUrl'] ?? script['uri'] ?? '',
            'lineNumber': -1,
            'columnNumber': -1,
          },
          'hitCount': 0,
          'children': <int>[],
        });
      }
      parent = id;
    }
    nodes[parent - 1]['hitCount'] = (nodes[parent - 1]['hitCount'] as int) + 1;
    leafIds.add(parent);
    final timestamp = sample['timestamp'] as int;
    deltas.add(timestamp - previous);
    previous = timestamp;
  }
  return {
    'nodes': nodes,
    'startTime': origin,
    'endTime': origin + (raw['timeExtentMicros'] as int),
    'samples': leafIds,
    'timeDeltas': deltas,
  };
}

class _Diagnostics {
  _Diagnostics(this.vm, this.isolate, this.output, this.label, this.options);
  final VmService vm;
  final String isolate;
  final Directory output;
  final String label;
  final Map<String, String> options;
  final _status = <String, dynamic>{};
  List<String>? _previousStreams;
  String? _previousProfiler;
  bool _started = false;
  int? _origin;
  bool get cpu => options['--cpu-profile'] == 'true';
  bool get timeline => options['--timeline'] == 'true';
  bool get enabled => cpu || timeline;
  File _file(String suffix) => File('${output.path}/$label-$suffix.json');
  void _write(String suffix, dynamic value) =>
      _file(suffix).writeAsStringSync(jsonEncode(value));
  Map<String, Object?> _error(Object error) => {
    'status':
        error is RPCError &&
            (error.message.toLowerCase().contains('disabled') ||
                error.code == 100)
        ? 'disabled'
        : 'unavailable',
    'errorType': error.runtimeType.toString(),
    if (error is RPCError) 'rpcCode': error.code,
  };
  Future<void> start() async {
    if (!enabled) return;
    for (final suffix in [
      'diagnostics',
      if (cpu) 'cpu-samples',
      if (cpu) 'cpuprofile',
      if (timeline) 'timeline',
    ]) {
      if (_file(suffix).existsSync())
        throw StateError('Diagnostic result already exists');
    }
    _started = true;
    if (cpu) {
      try {
        final flags = (await vm.getFlagList()).flags ?? [];
        _status['profilerFlags'] = {
          for (final flag in flags)
            if (const {'profiler', 'profile_period'}.contains(flag.name))
              flag.name: flag.valueAsString,
        };
        if (options['--enable-profiler'] == 'true') {
          final value = flags
              .where((flag) => flag.name == 'profiler')
              .firstOrNull
              ?.valueAsString;
          if (value != 'true' && value != 'false') {
            _status['profilerEnable'] = {
              'status': 'unavailable',
              'reason': 'Original profiler flag could not be read; no flag was changed',
            };
          } else {
            _previousProfiler = value;
            final response = await vm.setFlag('profiler', 'true');
            _status['profilerEnable'] = {
              'status': response.type == 'Success' ? 'enabled' : 'unavailable',
              'originalValue': value,
              'requestedValue': 'true',
              'responseType': response.type,
            };
          }
        }
      } catch (error) {
        _status[options['--enable-profiler'] == 'true'
            ? 'profilerEnable'
            : 'profilerFlags'] = _error(
          error,
        );
      }
    }
    if (timeline) {
      try {
        final flags = await vm.getVMTimelineFlags();
        _previousStreams = List.of(flags.recordedStreams ?? []);
        final requested = (options['--timeline-streams'] ?? 'Dart,Embedder,GC')
            .split(',');
        final selected = requested
            .where((s) => (flags.availableStreams ?? []).contains(s))
            .toList();
        await vm.setVMTimelineFlags(selected);
        _status['timelineConfiguration'] = {
          'recorder': flags.recorderName,
          'selectedStreams': selected,
          'previousStreams': _previousStreams,
        };
      } catch (error) {
        _status['timelineConfiguration'] = _error(error);
      }
    }
    _origin = (await vm.getVMTimelineMicros()).timestamp!;
  }

  Future<void> finish() async {
    if (!enabled || !_started) return;
    try {
      if (_origin == null) return;
      final end = (await vm.getVMTimelineMicros()).timestamp!;
      final origin = int.parse(options['--time-origin-us'] ?? '$_origin');
      final extent = int.parse(
        options['--time-extent-us'] ?? '${end - origin}',
      );
      _status['timeOriginMicros'] = origin;
      _status['timeExtentMicros'] = extent;
      if (cpu) {
        try {
          final samples = await vm.getCpuSamples(isolate, origin, extent);
          final raw = samples.toJson();
          _write('cpu-samples', raw);
          _write('cpuprofile', cpuProfileJson(raw));
          _status['cpu'] = {
            'status': 'captured',
            'sampleCount': samples.sampleCount,
            'samplePeriodUs': samples.samplePeriod,
          };
        } catch (error) {
          _status['cpu'] = _error(error);
        }
      }
      if (timeline) {
        try {
          final trace = await vm.getVMTimeline(
            timeOriginMicros: origin,
            timeExtentMicros: extent,
          );
          _write('timeline', trace.toJson());
          _status['timeline'] = {
            'status': 'captured',
            'eventCount': trace.traceEvents?.length,
          };
        } catch (error) {
          _status['timeline'] = _error(error);
        }
      }
    } finally {
      if (_previousStreams != null) {
        try {
          await vm.setVMTimelineFlags(_previousStreams!);
          _status['timelineRestore'] = {'status': 'restored'};
        } catch (error) {
          _status['timelineRestore'] = _error(error);
        }
      }
      if (_previousProfiler != null) {
        try {
          final response = await vm.setFlag('profiler', _previousProfiler!);
          _status['profilerRestore'] = {
            'status': response.type == 'Success' ? 'restored' : 'unavailable',
            'requestedValue': _previousProfiler,
            'responseType': response.type,
          };
        } catch (error) {
          _status['profilerRestore'] = _error(error);
        }
      }
      _write('diagnostics', _status);
      stdout.writeln(
        jsonEncode({'diagnostics': _file('diagnostics').path, ..._status}),
      );
    }
  }
}

Future<void> main(List<String> args) async {
  final options = <String, String>{};
  try {
    for (var index = 0; index < args.length; index += 2) {
      if (index + 1 >= args.length || !args[index].startsWith('--')) {
        throw ArgumentError('Use --output DIR --device SERIAL --label LABEL');
      }
      options[args[index]] = args[index + 1];
    }
    final output = Directory(options['--output']!);
    final label = options['--label'] ?? 'manual';
    final variant =
        options['--variant'] ??
        (label.startsWith('baseline') ? 'baseline' : 'after');
    final mode = options['--mode'] ?? 'profile';
    if (!RegExp(r'^[a-zA-Z0-9_-]+$').hasMatch(label) ||
        !const {'baseline', 'after'}.contains(variant) ||
        !const {'profile', 'debug'}.contains(mode)) {
      throw ArgumentError('Invalid label, variant or build mode');
    }
    if (options['--enable-profiler'] == 'true' &&
        options['--cpu-profile'] != 'true') {
      throw ArgumentError('--enable-profiler true requires --cpu-profile true');
    }
    dynamic read(String path) =>
        jsonDecode(File('${output.path}/$path').readAsStringSync());
    final metadata = read('runtime-perf.json');
    final installed = read('installed-apk.json');
    final connection = read('vm-connection-private.json');
    final device = options['--device']!;
    if (metadata['package'] != _package ||
        installed['package'] != _package ||
        connection['package'] != _package ||
        connection['device'] != device ||
        installed['flutterBuildMode'] != mode ||
        installed['sha256'] !=
            File(
              '${output.path}/$variant/apk${mode == 'debug' ? '-debug' : ''}-sha256.txt',
            ).readAsStringSync().trim()) {
      throw StateError(
        'Workspace, APK or device does not match the isolated benchmark',
      );
    }
    final pid = await Process.run('adb', [
      '-s',
      device,
      'shell',
      'pidof',
      _package,
    ]);
    if (pid.exitCode != 0 ||
        pid.stdout.toString().trim() != '${connection['pid']}') {
      throw StateError('Independent process changed; reconnect the VM service');
    }
    final address = Uri.parse(connection['ws'] as String);
    if (address.scheme != 'ws' || address.host != '127.0.0.1') {
      throw StateError('Only loopback VM forwarding is supported');
    }
    final vm = await vmServiceConnectUri(address.toString());
    try {
      final isolate = (await vm.getVM()).isolates!.firstWhere(
        (i) => i.name == 'main',
      );
      final details = await vm.getIsolate(isolate.id!);
      if (!(details.extensionRPCs ?? []).contains('ext.omnibot.runtimePerf')) {
        throw StateError('Independent app is missing the runtime probe');
      }
      Future<Map<String, dynamic>> call(Map<String, String> parameters) async {
        final response = await vm
            .callServiceExtension(
              'ext.omnibot.runtimePerf',
              isolateId: isolate.id,
              args: parameters,
            )
            .timeout(const Duration(minutes: 10));
        return Map<String, dynamic>.from(response.json!);
      }

      final diagnostics = _Diagnostics(vm, isolate.id!, output, label, options);
      try {
        await diagnostics.start();
        final observeMs = int.parse(options['--observe-ms'] ?? '0');
        if (observeMs > 0) {
          if (observeMs > 600000)
            throw ArgumentError('Observe window exceeds 10 minutes');
          await call({'op': 'startCapture'});
          await Future<void>.delayed(Duration(milliseconds: observeMs));
          final response = await call({'op': 'result'});
          final file = File('${output.path}/$label-results.json');
          if (file.existsSync()) throw StateError('Result already exists');
          file.writeAsStringSync(jsonEncode(response));
          stdout.writeln(
            jsonEncode({'result': file.path, 'observedMs': observeMs}),
          );
          return;
        }
        if (options['--params'] case final String parameters) {
          final response = await call(
            Map<String, String>.from(jsonDecode(parameters)),
          );
          if (options['--wait-stream'] == 'true') {
            final deadline = DateTime.now().add(const Duration(minutes: 10));
            while (true) {
              final status = await call({'op': 'streamStatus'});
              if (status['fixtureRunning'] != true) {
                response['stream'] = status;
                response['capture'] = await call({'op': 'result'});
                if (status['fixtureError'] != null ||
                    (status['result'] as Map?)?['valid'] != true) {
                  throw StateError(
                    'Stream fixture is invalid; inspect streamStatus before using its measurements',
                  );
                }
                break;
              }
              if (DateTime.now().isAfter(deadline)) {
                await call({'op': 'streamStop'});
                throw StateError(
                  'Stream fixture exceeded the host observation limit',
                );
              }
              await Future<void>.delayed(const Duration(milliseconds: 200));
            }
          }
          if (options['--save'] == 'true') {
            final file = File('${output.path}/$label-results.json');
            if (file.existsSync()) throw StateError('Result already exists');
            file.writeAsStringSync(jsonEncode(response));
          }
          stdout.writeln(
            options['--save'] == 'true'
                ? jsonEncode({'result': '${output.path}/$label-results.json'})
                : jsonEncode(response),
          );
          return;
        }
        final destination = File('${output.path}/$label-results.json');
        if (destination.existsSync()) throw StateError('Result already exists');
        final count = int.parse(options['--samples'] ?? '5');
        final warmups = int.parse(options['--warmups'] ?? '1');
        if (count < 1 || count > 30 || warmups < 0 || warmups > 5) {
          throw ArgumentError('Samples must be 1..30, warmups 0..5');
        }
        final samples = <Map<String, dynamic>>[];
        final result = {
          'package': _package,
          'mode': mode,
          'variant': variant,
          'label': label,
          'baselineCommit': metadata['baselineCommit'],
          'probeSha256': metadata['probeSha256'],
          'installedApk': installed,
          'complete': false,
          'samples': samples,
        };
        for (final scenario
            in (options['--scenarios'] ?? 'short,long,tools').split(',')) {
          for (var sample = -warmups; sample < count; sample++) {
            final data = await call({
              'op': 'stress',
              'scenario': scenario,
              'events': options['--events'] ?? '1000',
              'batch': options['--batch'] ?? '50',
              'workers': options['--workers'] ?? '4',
              'historyRows': options['--history-rows'] ?? '0',
            });
            samples.add({'sample': sample, 'warmup': sample < 0, ...data});
            destination.writeAsStringSync(jsonEncode(result));
            stdout.writeln('$label $scenario sample $sample complete');
          }
        }
        result['complete'] = true;
        destination.writeAsStringSync(jsonEncode(result));
      } finally {
        await diagnostics.finish();
      }
    } finally {
      await vm.dispose();
    }
  } catch (error) {
    // Socket errors may embed the credential-bearing VM URL.
    stderr.writeln(
      error is StateError || error is ArgumentError
          ? 'Runtime measurement failed: $error'
          : 'Runtime measurement failed (${error.runtimeType}); no VM token printed.',
    );
    exitCode = 1;
  }
}
