import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/office_preview_service.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

void main() {
  late Directory tempDir;

  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh', 'CN'));
    tempDir = Directory.systemTemp.createTempSync('office-preview-test-');
  });

  tearDown(() {
    tempDir.deleteSync(recursive: true);
  });

  test('long Word paragraphs and the final paragraph remain complete', () async {
    final paragraphs = List.generate(30, (i) => '$i:${'正文' * 200}:END-$i');
    final path = await _writeArchiveFile(tempDir, 'long.docx', {
      'word/document.xml':
          '<document><body>${paragraphs.map((p) => '<p><r><t>$p</t></r></p>').join()}</body></document>',
    });
    final preview = await OmnibotOfficePreviewService.loadPreview(
      path: path,
      previewKind: 'office_word',
    );
    expect(preview.sections.single.lines, paragraphs);
    expect(preview.truncated, isFalse);
  });

  test('every worksheet row column and long cell remains complete', () async {
    final cell = '${'数据' * 80}:END';
    final path = await _writeArchiveFile(tempDir, 'long.xlsx', {
      'xl/workbook.xml':
          '<workbook><sheets>${List.generate(5, (i) => '<sheet name="Sheet$i" id="r$i"/>').join()}</sheets></workbook>',
      'xl/_rels/workbook.xml.rels':
          '<Relationships>${List.generate(5, (i) => '<Relationship Id="r$i" Target="worksheets/sheet$i.xml"/>').join()}</Relationships>',
      for (var i = 0; i < 5; i++)
        'xl/worksheets/sheet$i.xml':
            '<worksheet><sheetData>${List.generate(25, (r) => '<row>${List.generate(12, (c) => '<c r="${String.fromCharCode(65 + c)}${r + 1}" t="inlineStr"><is><t>$cell</t></is></c>').join()}</row>').join()}</sheetData></worksheet>',
    });
    final preview = await OmnibotOfficePreviewService.loadPreview(
      path: path,
      previewKind: 'office_sheet',
    );
    expect(preview.sections, hasLength(5));
    for (final section in preview.sections) {
      expect(section.tableRows, hasLength(25));
      expect(section.tableRows.last, List.filled(12, cell));
    }
    expect(preview.truncated, isFalse);
  });

  test('every slide and long text line remains complete', () async {
    final lines = List.generate(12, (i) => '$i:${'幻灯片' * 100}:END-$i');
    final path = await _writeArchiveFile(tempDir, 'long.pptx', {
      for (var i = 1; i <= 10; i++)
        'ppt/slides/slide$i.xml':
            '<sld>${lines.map((p) => '<p><r><t>$p</t></r></p>').join()}</sld>',
    });
    final preview = await OmnibotOfficePreviewService.loadPreview(
      path: path,
      previewKind: 'office_slide',
    );
    expect(preview.sections, hasLength(10));
    expect(preview.sections.last.lines, lines);
    expect(preview.truncated, isFalse);
  });

  test('parses docx body text into preview sections', () async {
    final path = await _writeArchiveFile(tempDir, 'demo.docx', <String, String>{
      'word/document.xml': '''
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
    <w:p><w:r><w:t>第二段</w:t></w:r></w:p>
  </w:body>
</w:document>
''',
    });

    final preview = await OmnibotOfficePreviewService.loadPreview(
      path: path,
      previewKind: 'office_word',
    );

    expect(preview.kindLabel, 'Word 预览');
    expect(preview.sections.single.lines, <String>['第一段', '第二段']);
  });

  test('parses xlsx sheet cells into preview table', () async {
    final path = await _writeArchiveFile(tempDir, 'demo.xlsx', <String, String>{
      'xl/workbook.xml': '''
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Sheet1" sheetId="1" r:id="rId1" />
  </sheets>
</workbook>
''',
      'xl/_rels/workbook.xml.rels': '''
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml" />
</Relationships>
''',
      'xl/sharedStrings.xml': '''
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <si><t>标题</t></si>
  <si><t>数值</t></si>
</sst>
''',
      'xl/worksheets/sheet1.xml': '''
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1">
      <c r="A1" t="s"><v>0</v></c>
      <c r="B1" t="s"><v>1</v></c>
    </row>
    <row r="2">
      <c r="A2"><v>42</v></c>
      <c r="B2"><v>84</v></c>
    </row>
  </sheetData>
</worksheet>
''',
    });

    final preview = await OmnibotOfficePreviewService.loadPreview(
      path: path,
      previewKind: 'office_sheet',
    );

    expect(preview.kindLabel, 'Excel 预览');
    expect(preview.sections.single.title, 'Sheet1');
    expect(preview.sections.single.tableRows, <List<String>>[
      <String>['标题', '数值'],
      <String>['42', '84'],
    ]);
  });

  test('parses pptx slide text into preview sections', () async {
    final path = await _writeArchiveFile(tempDir, 'demo.pptx', <String, String>{
      'ppt/slides/slide1.xml': '''
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:sp>
        <p:txBody>
          <a:p><a:r><a:t>第一页标题</a:t></a:r></a:p>
          <a:p><a:r><a:t>第一页说明</a:t></a:r></a:p>
        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>
''',
    });

    final preview = await OmnibotOfficePreviewService.loadPreview(
      path: path,
      previewKind: 'office_slide',
    );

    expect(preview.kindLabel, 'PowerPoint 预览');
    expect(preview.sections.single.lines, <String>['第一页标题', '第一页说明']);
  });
}

Future<String> _writeArchiveFile(
  Directory directory,
  String fileName,
  Map<String, String> entries,
) async {
  final archive = Archive();
  entries.forEach((entryPath, content) {
    final bytes = utf8.encode(content);
    archive.addFile(ArchiveFile(entryPath, bytes.length, bytes));
  });
  final encoded = ZipEncoder().encode(archive);

  final file = File('${directory.path}/$fileName');
  await file.writeAsBytes(encoded, flush: true);
  return file.path;
}
