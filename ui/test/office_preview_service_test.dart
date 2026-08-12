import 'dart:convert';
import 'dart:io';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/widgets.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/office_preview_service.dart';

void main() {
  late Directory tempDir;

  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    tempDir = Directory.systemTemp.createTempSync('office-preview-test-');
  });

  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
    tempDir.deleteSync(recursive: true);
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
