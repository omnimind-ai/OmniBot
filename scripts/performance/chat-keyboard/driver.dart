// Host-only driver. The real Android input command supplies every measured tap.
import 'dart:convert';
import 'dart:io';

import 'package:vm_service/vm_service_io.dart';

const _package = 'cn.com.omnimind.bot.keyboardperf';

Future<void> main(List<String> args) async {
  final options = <String, String>{};
  for (var index = 0; index < args.length; index += 2) {
    if (index + 1 >= args.length || !args[index].startsWith('--')) {
      throw ArgumentError('Use --output DIR --device SERIAL --label LABEL');
    }
    options[args[index]] = args[index + 1];
  }
  try {
    final output = Directory(options['--output']!);
    final label = options['--label'] ?? 'manual';
    if (!RegExp(r'^[a-zA-Z0-9_-]+$').hasMatch(label)) {
      throw ArgumentError('Invalid result label');
    }
    final destination = File('${output.path}/$label-results.json');
    if (options['--params'] == null && destination.existsSync()) {
      throw StateError('Result already exists');
    }
    final metadata = jsonDecode(
      File('${output.path}/keyboard-perf.json').readAsStringSync(),
    );
    final installed = jsonDecode(
      File('${output.path}/installed-apk.json').readAsStringSync(),
    );
    final mode = options['--mode'] ?? 'profile';
    if (mode != 'profile' && mode != 'debug') {
      throw ArgumentError('Mode must be profile or debug');
    }
    final hashSuffix = mode == 'debug' ? '-debug' : '';
    final variant =
        options['--variant'] ??
        (label.startsWith('baseline') ? 'baseline' : 'after');
    if (variant != 'baseline' && variant != 'after') {
      throw ArgumentError('Variant must be baseline or after');
    }
    if (options['--params'] == null &&
        (installed['package'] != _package ||
            (installed['flutterBuildMode'] ??
                    (installed['flutterAotLibrary'] == true
                        ? 'profile'
                        : 'debug')) !=
                mode ||
            installed['sha256'] !=
                File('${output.path}/$variant/apk$hashSuffix-sha256.txt')
                    .readAsStringSync()
                    .trim())) {
      throw StateError(
        'Installed APK metadata does not match the requested variant',
      );
    }
    final connection = jsonDecode(
      File('${output.path}/vm-connection-private.json').readAsStringSync(),
    );
    final device = options['--device']!;
    if (device.isEmpty ||
        metadata['package'] != _package ||
        connection['package'] != _package ||
        connection['device'] != device) {
      throw StateError('Workspace/device is not the independent benchmark');
    }
    final address = Uri.parse(connection['ws'] as String);
    if (address.scheme != 'ws' || address.host != '127.0.0.1') {
      throw StateError('Only loopback VM forwarding is supported');
    }
    Future<String> adb(List<String> command) async {
      final process = await Process.run('adb', ['-s', device, ...command]);
      if (process.exitCode != 0) throw StateError('ADB operation failed');
      return process.stdout.toString();
    }

    final pid = (await adb(['shell', 'pidof', _package])).trim();
    if (pid != '${connection['pid']}') {
      throw StateError('Process changed; reconnect the VM service');
    }
    final vm = await vmServiceConnectUri(address.toString());
    final samples = <Map<String, Object?>>[];
    final prepared = <String, Object?>{};
    try {
      final state = await vm.getVM();
      final isolate = state.isolates!.firstWhere((i) => i.name == 'main');
      final details = await vm.getIsolate(isolate.id!);
      if (!(details.extensionRPCs ?? []).contains('ext.omnibot.keyboardPerf')) {
        throw StateError('The current process is missing the keyboard probe');
      }
      Future<Map<String, dynamic>> call(Map<String, String> parameters) async {
        final response = await vm
            .callServiceExtension(
              'ext.omnibot.keyboardPerf',
              isolateId: isolate.id,
              args: parameters,
            )
            .timeout(const Duration(seconds: 60));
        return Map<String, dynamic>.from(response.json!);
      }

      if (options['--params'] case final String parameters) {
        stdout.writeln(
          jsonEncode(
            await call(Map<String, String>.from(jsonDecode(parameters))),
          ),
        );
        return;
      }

      final count = int.parse(options['--samples'] ?? '10');
      if (count < 1 || count > 50) throw ArgumentError('Samples must be 1..50');
      final scenarios = (options['--scenarios'] ?? 'empty,history').split(',');
      final warmups = int.parse(options['--warmups'] ?? '2');
      for (final scenario in scenarios) {
        prepared[scenario] = await call({
          'op': 'prepareScenario',
          'scenario': scenario,
        });
        for (var sample = -warmups; sample < count; sample++) {
          await call({'op': 'hide'});
          // Refuse to tap if the user has switched away from the test app.
          final activities = await adb([
            'shell',
            'dumpsys',
            'activity',
            'activities',
          ]);
          final top = activities
              .split('\n')
              .where(
                (line) =>
                    line.contains('topResumedActivity=') ||
                    line.contains('mResumedActivity:'),
              );
          if (!top.any((line) => line.contains(_package))) {
            throw StateError('Independent benchmark is no longer foreground');
          }
          final capture = await call({'op': 'startCapture'});
          final tap = Map<String, dynamic>.from(capture['tap']);
          await adb(['shell', 'input', 'tap', '${tap['x']}', '${tap['y']}']);
          // Includes FrameTiming's delayed batch delivery after IME animation.
          await Future<void>.delayed(const Duration(milliseconds: 1600));
          final result = await call({'op': 'result'});
          if (result['valid'] != true ||
              result['focused'] != true ||
              (result['viewInsetsBottom'] as num) <= 0) {
            throw StateError(
              'Capture failed; do not report it as zero latency',
            );
          }
          final insets = (result['insets'] as List).cast<Map>();
          final finalBottom = result['viewInsetsBottom'] as num;
          final firstFinal = insets.firstWhere(
            (item) => ((item['bottom'] as num) - finalBottom).abs() < .1,
          );
          result['firstFinalInsetMs'] =
              ((firstFinal['atUs'] as num) -
                  (result['pointerDownReceivedUs'] as num)) /
              1000;
          samples.add({
            'scenario': scenario,
            'sample': sample,
            'warmup': sample < 0,
            ...result,
          });
          destination.writeAsStringSync(
            jsonEncode({
              'package': _package,
              'label': label,
              'variant': variant,
              'mode': mode,
              'installedApk': installed,
              'probeSha256': metadata['probeSha256'],
              'baselineCommit': metadata['baselineCommit'],
              'complete': false,
              'prepared': prepared,
              'samples': samples,
            }),
          );
          stdout.writeln(
            '$label $scenario ${sample < 0 ? "warmup" : "sample"} $sample complete',
          );
        }
      }
      await call({'op': 'home'});
      destination.writeAsStringSync(
        const JsonEncoder.withIndent('  ').convert({
          'package': _package,
          'label': label,
          'variant': variant,
          'mode': mode,
          'installedApk': installed,
          'probeSha256': metadata['probeSha256'],
          'baselineCommit': metadata['baselineCommit'],
          'complete': true,
          'prepared': prepared,
          'samples': samples,
        }),
      );
    } finally {
      await vm.dispose();
    }
  } catch (error) {
    // Socket exceptions may contain a credential-bearing VM service URL.
    stderr.writeln(
      error is StateError || error is ArgumentError
          ? 'Keyboard measurement failed: $error'
          : 'Keyboard measurement failed (${error.runtimeType}); no VM token printed.',
    );
    exitCode = 1;
  }
}
