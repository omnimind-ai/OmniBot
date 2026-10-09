import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/my/pages/feedback/feedback_page.dart';
import 'package:ui/services/feedback_service.dart';
import 'package:ui/theme/app_theme.dart';

class FormService extends FeedbackService {
  List<FeedbackAttachment> picked = [];
  Map<String, Object?>? submitted;
  FeedbackFailure? failure;
  int calls = 0;

  @override
  Future<List<FeedbackAttachment>> pickAttachments() async => picked;

  @override
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
    calls++;
    submitted = {
      'title': title,
      'description': description,
      'contact': contact,
      'accountEmail': accountEmail,
      'locale': languageCode,
      'attachments': attachments,
    };
    if (failure != null) throw FeedbackException(failure!);
    return 'feedback-reference-123';
  }
}

const titleKey = ValueKey('feedback-title');
const descriptionKey = ValueKey('feedback-description');
const contactKey = ValueKey('feedback-contact');
const addKey = ValueKey('feedback-add-attachment');
const submitKey = ValueKey('feedback-submit');

Future<void> tapVisible(WidgetTester tester, Key key) async {
  await tester.ensureVisible(find.byKey(key));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(key));
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const account = MethodChannel('cn.com.omnimind.bot/account');
  const device = MethodChannel('device_info');
  late FormService service;
  setUp(() {
    service = FormService();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      account,
      (call) async => 'user@example.com',
    );
    messenger.setMockMethodCallHandler(
      device,
      (call) async => {'versionName': '0.6.3.3'},
    );
  });
  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(account, null);
    messenger.setMockMethodCallHandler(device, null);
  });

  Future<void> open(WidgetTester tester, {String language = 'zh'}) async {
    tester.view.physicalSize = const Size(393, 852);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        locale: Locale(language),
        supportedLocales: const [Locale('zh'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: FeedbackPage(service: service),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> fill(WidgetTester tester) async {
    await tester.enterText(find.byKey(titleKey), '应用问题');
    await tester.enterText(find.byKey(descriptionKey), '这是问题的具体描述');
  }

  testWidgets(
    'native form prefills email and preserves it when contact is edited',
    (tester) async {
      await open(tester);
      expect(find.text('我要反馈'), findsOneWidget);
      expect(
        tester.widget<TextFormField>(find.byKey(contactKey)).controller!.text,
        'user@example.com',
      );
      await fill(tester);
      await tester.enterText(find.byKey(contactKey), '微信:sample');
      await tapVisible(tester, submitKey);
      expect(service.submitted!['accountEmail'], 'user@example.com');
      expect(service.submitted!['contact'], '微信:sample');
      expect(find.text('反馈已收到'), findsOneWidget);
      expect(find.text('feedback-reference-123'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('required field validation prevents empty submissions', (
    tester,
  ) async {
    await open(tester);
    await tapVisible(tester, submitKey);
    expect(service.calls, 0);
    expect(find.text('请填写问题标题'), findsOneWidget);
    expect(find.text('请填写具体描述'), findsOneWidget);
  });

  testWidgets(
    'failure preserves entries and allows retry then another feedback',
    (tester) async {
      service.failure = FeedbackFailure.network;
      await open(tester);
      await fill(tester);
      await tapVisible(tester, submitKey);
      expect(find.textContaining('填写的内容已保留'), findsOneWidget);
      expect(
        tester.widget<TextFormField>(find.byKey(titleKey)).controller!.text,
        '应用问题',
      );
      service.failure = null;
      await tapVisible(tester, submitKey);
      expect(service.calls, 2);
      await tester.tap(find.text('再提交一条'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextFormField>(find.byKey(titleKey)).controller!.text,
        isEmpty,
      );
      expect(
        tester.widget<TextFormField>(find.byKey(contactKey)).controller!.text,
        'user@example.com',
      );
    },
  );

  testWidgets(
    'attachments can be selected deduplicated and removed before submission',
    (tester) async {
      final file = FeedbackAttachment(
        name: 'screenshot.png',
        size: 3,
        identifier: 'content://sample/1',
        readBytes: () => Stream.value([1, 2, 3]),
      );
      service.picked = [file, file];
      await open(tester);
      await fill(tester);
      await tapVisible(tester, addKey);
      expect(find.text('screenshot.png'), findsOneWidget);
      await tester.ensureVisible(find.byTooltip('移除附件'));
      await tester.tap(find.byTooltip('移除附件'));
      await tester.pumpAndSettle();
      expect(find.text('screenshot.png'), findsNothing);
      await tapVisible(tester, addKey);
      await tapVisible(tester, submitKey);
      expect(service.submitted!['attachments'], [file]);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('an oversized attachment shows a limit without losing the form', (
    tester,
  ) async {
    service.picked = [
      FeedbackAttachment(
        name: 'large.mp4',
        size: FeedbackService.maxFileSize + 1,
        identifier: 'content://sample/2',
        readBytes: () => const Stream.empty(),
      ),
    ];
    await open(tester);
    await fill(tester);
    await tapVisible(tester, addKey);
    expect(find.text('每个附件不能超过 10 MB。'), findsOneWidget);
    expect(find.text('large.mp4'), findsNothing);
    expect(
      tester.widget<TextFormField>(find.byKey(titleKey)).controller!.text,
      '应用问题',
    );
  });

  testWidgets(
    'signed-out users get an English native form with optional contact',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(account, (call) async => '');
      await open(tester, language: 'en');
      expect(find.text('Send feedback'), findsOneWidget);
      expect(
        tester.widget<TextFormField>(find.byKey(contactKey)).controller!.text,
        isEmpty,
      );
      await fill(tester);
      await tapVisible(tester, submitKey);
      expect(service.submitted!['accountEmail'], '');
      expect(service.submitted!['contact'], '');
      expect(service.submitted!['locale'], 'en');
      expect(find.text('Feedback received'), findsOneWidget);
    },
  );
}
