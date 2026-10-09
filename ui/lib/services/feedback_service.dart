import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:ui/services/account_service.dart';

class FeedbackAttachment {
  const FeedbackAttachment({
    required this.name,
    required this.size,
    required this.identifier,
    required this.readBytes,
  });

  final String name;
  final int size;
  final String identifier;
  final Stream<List<int>> Function() readBytes;
}

enum FeedbackFailure {
  invalidFields,
  fileCount,
  fileSize,
  totalSize,
  unreadableFile,
  rateLimited,
  network,
}

class FeedbackException implements Exception {
  const FeedbackException(this.reason);
  final FeedbackFailure reason;
}

/// Submits the app's own feedback form directly to the website backend.
class FeedbackService {
  FeedbackService({Dio? client})
    : _client =
          client ??
          Dio(
            BaseOptions(
              connectTimeout: const Duration(seconds: 20),
              sendTimeout: const Duration(minutes: 3),
              receiveTimeout: const Duration(seconds: 30),
              followRedirects: false,
            ),
          );

  static const apiUrl = String.fromEnvironment(
    'OMNIBOT_FEEDBACK_API_URL',
    defaultValue: 'https://omnibot.omnimind.com.cn/api/feedback',
  );
  static const maxFiles = 5;
  static const maxFileSize = 10 * 1024 * 1024;
  static const maxTotalSize = 25 * 1024 * 1024;
  final Dio _client;

  static Future<String> loadAccountEmail() async {
    try {
      return await AccountService.getFeedbackEmail().timeout(
        const Duration(seconds: 5),
      );
    } catch (_) {
      return '';
    }
  }

  static void validateAttachments(List<FeedbackAttachment> files) {
    if (files.length > maxFiles) {
      throw const FeedbackException(FeedbackFailure.fileCount);
    }
    if (files.any((file) => file.size < 0 || file.size > maxFileSize)) {
      throw const FeedbackException(FeedbackFailure.fileSize);
    }
    if (files.fold<int>(0, (sum, file) => sum + file.size) > maxTotalSize) {
      throw const FeedbackException(FeedbackFailure.totalSize);
    }
  }

  Future<List<FeedbackAttachment>> pickAttachments() async {
    try {
      final files = await FilePicker.pickFiles(type: FileType.any);
      final attachments = await Future.wait(
        files.map(
          (file) async => FeedbackAttachment(
            name: file.name,
            size: await file.length(),
            identifier: file.uri.toString(),
            readBytes: file.readAsByteStream,
          ),
        ),
      );
      validateAttachments(attachments);
      return attachments;
    } on FeedbackException {
      rethrow;
    } catch (_) {
      throw const FeedbackException(FeedbackFailure.unreadableFile);
    }
  }

  Future<String> submit({
    required String title,
    required String description,
    required String contact,
    required String accountEmail,
    required String languageCode,
    Map<String, dynamic>? versionInfo,
    List<FeedbackAttachment> attachments = const [],
    CancelToken? cancelToken,
  }) async {
    title = title.trim();
    description = description.trim();
    contact = contact.trim();
    if (title.isEmpty ||
        title.length > 200 ||
        description.isEmpty ||
        description.length > 10000 ||
        contact.length > 300) {
      throw const FeedbackException(FeedbackFailure.invalidFields);
    }
    validateAttachments(attachments);
    final body = FormData.fromMap({
      'title': title,
      'description': description,
      'contact': contact,
      'source': 'android',
      'locale': languageCode == 'en' ? 'en' : 'zh',
      if (accountEmail.trim().isNotEmpty) 'accountEmail': accountEmail.trim(),
      if (versionInfo?['versionName'] != null)
        'versionName': versionInfo!['versionName'].toString(),
      'deviceInfo': '{"platform":"android"}',
    });
    for (final file in attachments) {
      body.files.add(
        MapEntry(
          'attachments',
          MultipartFile.fromStream(
            file.readBytes,
            file.size,
            filename: file.name,
          ),
        ),
      );
    }
    try {
      final response = await _client.post<Object?>(
        apiUrl,
        data: body,
        cancelToken: cancelToken,
        options: Options(headers: {'accept': 'application/json'}),
      );
      final data = response.data;
      if (response.statusCode != 201 ||
          data is! Map ||
          data['ok'] != true ||
          data['id'] is! String ||
          (data['id'] as String).isEmpty) {
        throw const FeedbackException(FeedbackFailure.network);
      }
      return data['id'] as String;
    } on DioException catch (error) {
      throw FeedbackException(switch (error.response?.statusCode) {
        429 => FeedbackFailure.rateLimited,
        413 => FeedbackFailure.totalSize,
        400 => FeedbackFailure.invalidFields,
        _ => FeedbackFailure.network,
      });
    }
  }

  void close() => _client.close(force: true);
}
