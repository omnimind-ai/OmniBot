import 'package:ui/app_bootstrap.dart';
import 'package:ui/config/jackery_ces_config.dart';

Future<void> main(List<String> args) async {
  if (!JackeryCesConfig.enabled) {
    throw StateError('Build this entry point with JACKERY_CES=true.');
  }
  await bootstrapMain(args);
}
