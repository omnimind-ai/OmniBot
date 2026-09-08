import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/workspace_mount_service.dart';

void main() {
  test(
    'async mount inspection preserves relative and broken link behavior',
    () async {
      final root = await Directory.systemTemp.createTemp('workspace-mount-');
      addTearDown(() => root.delete(recursive: true));
      await Directory('${root.path}/source').create();
      for (final target in ['source', 'missing']) {
        final link = await Link('${root.path}/mount-$target').create(target);
        final expected = WorkspaceMountService.describeMountEntrySync(
          link.path,
          rootPath: root.path,
        )!;
        final actual = (await WorkspaceMountService.describeMountEntry(
          link.path,
          rootPath: root.path,
        ))!;
        expect(actual.sourcePath, expected.sourcePath);
        expect(actual.sourceExists, target == 'source');
        expect(actual.sourceIsDirectory, target == 'source');
        expect(actual.shellPath, expected.shellPath);
        expect(actual.isBroken, expected.isBroken);
      }
      final file = await File('${root.path}/plain.txt').create();
      expect(
        await WorkspaceMountService.describeMountEntry(
          file.path,
          rootPath: root.path,
        ),
        isNull,
      );
    },
  );
}
