import 'package:android_file_picker/android_file_picker.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/utils/picked_attachment_metadata.dart';

void main() {
  test('uses cached path and native size without accessing the file', () {
    final file = AndroidPlatformFile.fromMap({
      'name': 'report.pdf',
      'path': '/nonexistent/report.pdf',
      'size': 42,
      'safHandle': {'uri': 'content://documents/42', 'access': 'read'},
    });
    expect(pickedAttachmentMetadata(file), (
      path: '/nonexistent/report.pdf',
      size: 42,
    ));
  });

  test('preserves content URI when the picker has no cached path', () {
    final file = AndroidPlatformFile(
      name: 'report.pdf',
      uri: Uri.parse('content://documents/42'),
    );
    expect(file.path, isNull);
    expect(pickedAttachmentMetadata(file), (
      path: 'content://documents/42',
      size: null,
    ));
  });

  test('uses SAF handle when native payload has no local path', () {
    final file = AndroidPlatformFile.fromMap({
      'name': 'report.pdf',
      'uri': 'content://documents/42',
      'safHandle': {'uri': 'content://documents/42', 'access': 'read'},
    });
    expect(pickedAttachmentMetadata(file), (
      path: 'content://documents/42',
      size: null,
    ));
  });

  test('skips empty sources and keeps unknown sizes optional', () {
    expect(
      pickedAttachmentMetadata(
        AndroidPlatformFile(name: 'empty', uri: Uri.file('')),
      ),
      isNull,
    );
    expect(
      pickedAttachmentMetadata(
        AndroidPlatformFile(
          name: 'empty',
          uri: Uri.file('/empty'),
          bytesLength: 0,
        ),
      ),
      (path: '/empty', size: null),
    );
  });
}
