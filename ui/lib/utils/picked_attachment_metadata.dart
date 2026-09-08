import 'package:android_file_picker/android_file_picker.dart';
import 'package:file_picker/file_picker.dart';

/// Keep picker sources intact; the native ACP boundary copies document URIs.
/// Size is optional metadata and must not cause file I/O during selection.
({String path, int? size})? pickedAttachmentMetadata(PlatformFile file) {
  final localPath = file.path;
  final uri = file is AndroidPlatformFile
      ? file.safHandle?.uri ?? file.uri
      : file.uri;
  final source = localPath != null && localPath.isNotEmpty
      ? localPath
      : uri.scheme == 'content'
      ? uri.toString()
      : null;
  if (source == null || source.isEmpty) return null;
  final size = file.lengthSync();
  return (path: source, size: size != null && size > 0 ? size : null);
}
