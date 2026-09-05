import 'dart:convert';
import 'dart:io';

import 'package:archive/archive.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:xml/xml.dart';

class OmnibotOfficePreviewData {
  final String kindLabel;
  final String summary;
  final List<OmnibotOfficePreviewSection> sections;
  final bool truncated;

  const OmnibotOfficePreviewData({
    required this.kindLabel,
    required this.summary,
    required this.sections,
    this.truncated = false,
  });
}

class OmnibotOfficePreviewSection {
  final String title;
  final String? subtitle;
  final List<String> lines;
  final List<List<String>> tableRows;

  const OmnibotOfficePreviewSection({
    required this.title,
    this.subtitle,
    this.lines = const <String>[],
    this.tableRows = const <List<String>>[],
  });

  bool get hasTable => tableRows.isNotEmpty;
}

class OmnibotOfficePreviewService {
  static Future<OmnibotOfficePreviewData> loadPreview({
    required String path,
    required String previewKind,
  }) async {
    final bytes = await File(path).readAsBytes();
    final archive = ZipDecoder().decodeBytes(bytes, verify: false);
    try {
      return switch (previewKind) {
        'office_word' => _parseWordPreview(archive),
        'office_sheet' => _parseWorkbookPreview(archive),
        'office_slide' => _parseSlidePreview(archive),
        _ => throw StateError(
          LegacyTextLocalizer.isEnglish
              ? 'This Office file type is not supported'
              : '暂不支持该 Office 文件类型',
        ),
      };
    } on XmlParserException catch (error) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to parse Office file structure: ${error.message}'
            : 'Office 文件结构解析失败: ${error.message}',
      );
    } on FormatException catch (error) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to parse Office file content: ${error.message}'
            : 'Office 文件内容解析失败: ${error.message}',
      );
    } catch (error) {
      if (error is StateError) rethrow;
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'Office file preview failed: $error'
            : 'Office 文件预览失败: $error',
      );
    }
  }

  static OmnibotOfficePreviewData _parseWordPreview(Archive archive) {
    final document = _parseXmlEntry(archive, 'word/document.xml');
    final paragraphs = <String>[];

    for (final paragraph in _elementsByLocalName(document, 'p')) {
      final text = _normalizePreviewText(_collectParagraphText(paragraph));
      if (text.isEmpty) {
        continue;
      }
      paragraphs.add(text);
    }

    if (paragraphs.isEmpty) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'No previewable Word text content found'
            : '未找到可预览的 Word 文本内容',
      );
    }

    return OmnibotOfficePreviewData(
      kindLabel: LegacyTextLocalizer.isEnglish ? 'Word Preview' : 'Word 预览',
      summary: (LegacyTextLocalizer.isEnglish
          ? 'Extracted ${paragraphs.length} paragraphs in total'
          : '共提取 ${paragraphs.length} 段正文'),
      sections: <OmnibotOfficePreviewSection>[
        OmnibotOfficePreviewSection(
          title: LegacyTextLocalizer.isEnglish ? 'Body' : '正文',
          subtitle: LegacyTextLocalizer.isEnglish
              ? 'Scroll to view extracted document content'
              : '滚动查看文档提取内容',
          lines: paragraphs,
        ),
      ],
    );
  }

  static OmnibotOfficePreviewData _parseWorkbookPreview(Archive archive) {
    final workbook = _parseXmlEntry(archive, 'xl/workbook.xml');
    final workbookRels = _parseXmlEntry(archive, 'xl/_rels/workbook.xml.rels');
    final sharedStrings = _loadSharedStrings(archive);
    final relationshipTargets = <String, String>{};

    for (final relation in _elementsByLocalName(workbookRels, 'Relationship')) {
      final relationId = _attributeValue(relation, 'Id');
      final target = _attributeValue(relation, 'Target');
      if (relationId.isEmpty || target.isEmpty) {
        continue;
      }
      relationshipTargets[relationId] = target;
    }

    final sections = <OmnibotOfficePreviewSection>[];

    for (final sheet in _elementsByLocalName(workbook, 'sheet')) {
      final resolvedSheetName = _attributeValue(sheet, 'name');
      final sheetName = resolvedSheetName.isEmpty
          ? (LegacyTextLocalizer.isEnglish
                ? 'Sheet ${sections.length + 1}'
                : '工作表 ${sections.length + 1}')
          : resolvedSheetName;
      final relationId = _attributeValue(sheet, 'id');
      final target = relationshipTargets[relationId];
      if (target == null || target.isEmpty) {
        continue;
      }
      final normalizedTarget = target.startsWith('/')
          ? target.substring(1)
          : 'xl/$target';
      final parsedSection = _parseSheetSection(
        archive: archive,
        entryPath: normalizedTarget,
        sheetName: sheetName,
        sharedStrings: sharedStrings,
      );
      if (parsedSection != null) {
        sections.add(parsedSection);
      }
    }

    if (sections.isEmpty) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'No previewable Excel worksheet content found'
            : '未找到可预览的 Excel 工作表内容',
      );
    }

    return OmnibotOfficePreviewData(
      kindLabel: LegacyTextLocalizer.isEnglish ? 'Excel Preview' : 'Excel 预览',
      summary: (LegacyTextLocalizer.isEnglish
          ? 'Extracted ${sections.length} worksheets in total'
          : '共提取 ${sections.length} 个工作表'),
      sections: sections,
    );
  }

  static OmnibotOfficePreviewSection? _parseSheetSection({
    required Archive archive,
    required String entryPath,
    required String sheetName,
    required List<String> sharedStrings,
  }) {
    final sheetDocument = _tryParseXmlEntry(archive, entryPath);
    if (sheetDocument == null) {
      return null;
    }
    final sparseRows = <Map<int, String>>[];
    var maxColumnIndex = -1;

    for (final row in _elementsByLocalName(sheetDocument, 'row')) {
      final rowValues = <int, String>{};
      for (final cell in _directChildrenByLocalName(row, 'c')) {
        final reference = _attributeValue(cell, 'r');
        final columnIndex = _columnIndexFromCellReference(reference);
        if (columnIndex < 0) {
          continue;
        }
        final value = _extractSheetCellValue(cell, sharedStrings);
        if (value.isEmpty) {
          continue;
        }
        rowValues[columnIndex] = value;
        if (columnIndex > maxColumnIndex) {
          maxColumnIndex = columnIndex;
        }
      }
      if (rowValues.isNotEmpty) {
        sparseRows.add(rowValues);
      }
    }

    if (sparseRows.isEmpty) {
      return OmnibotOfficePreviewSection(
        title: sheetName,
        subtitle: LegacyTextLocalizer.isEnglish
            ? 'No cell content extracted'
            : '未提取到单元格内容',
        lines: <String>[
          LegacyTextLocalizer.isEnglish
              ? 'No previewable text in this worksheet'
              : '该工作表暂无可预览文本',
        ],
      );
    }

    final columnCount = maxColumnIndex + 1;
    final tableRows = sparseRows
        .map(
          (rowValues) => List<String>.generate(
            columnCount,
            (index) => rowValues[index] ?? '',
          ),
        )
        .toList(growable: false);

    return OmnibotOfficePreviewSection(
      title: sheetName,
      subtitle: (LegacyTextLocalizer.isEnglish
          ? 'Extracted ${tableRows.length} rows in total'
          : '共提取 ${tableRows.length} 行'),
      tableRows: tableRows,
    );
  }

  static OmnibotOfficePreviewData _parseSlidePreview(Archive archive) {
    final slideFiles =
        archive.files
            .where(
              (file) =>
                  file.isFile &&
                  RegExp(r'^ppt/slides/slide\d+\.xml$').hasMatch(file.name),
            )
            .toList()
          ..sort(
            (left, right) => _extractTrailingNumber(
              left.name,
            ).compareTo(_extractTrailingNumber(right.name)),
          );

    if (slideFiles.isEmpty) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'No previewable PowerPoint slides found'
            : '未找到可预览的 PowerPoint 页面',
      );
    }

    final sections = <OmnibotOfficePreviewSection>[];

    for (final slideFile in slideFiles) {
      final slideDocument = _parseXmlContent(slideFile.content as List<int>);
      final lines = <String>[];

      for (final paragraph in _elementsByLocalName(slideDocument, 'p')) {
        final line = _normalizePreviewText(_collectParagraphText(paragraph));
        if (line.isEmpty) {
          continue;
        }
        lines.add(line);
      }

      sections.add(
        OmnibotOfficePreviewSection(
          title: LegacyTextLocalizer.isEnglish
              ? 'Slide ${sections.length + 1}'
              : '第 ${sections.length + 1} 页',
          lines: lines.isEmpty
              ? <String>[
                  LegacyTextLocalizer.isEnglish
                      ? 'No extractable text on this slide'
                      : '该页没有可提取文本',
                ]
              : lines,
        ),
      );
    }

    return OmnibotOfficePreviewData(
      kindLabel: LegacyTextLocalizer.isEnglish
          ? 'PowerPoint Preview'
          : 'PowerPoint 预览',
      summary: (LegacyTextLocalizer.isEnglish
          ? 'Extracted ${sections.length} slides in total'
          : '共提取 ${sections.length} 页幻灯片'),
      sections: sections,
    );
  }

  static List<String> _loadSharedStrings(Archive archive) {
    final sharedStrings = _tryParseXmlEntry(archive, 'xl/sharedStrings.xml');
    if (sharedStrings == null) {
      return const <String>[];
    }

    return _elementsByLocalName(sharedStrings, 'si')
        .map((item) => _normalizePreviewText(_collectParagraphText(item)))
        .where((value) => value.isNotEmpty)
        .toList(growable: false);
  }

  static String _extractSheetCellValue(
    XmlElement cell,
    List<String> sharedStrings,
  ) {
    final type = _attributeValue(cell, 't');
    final value = _firstDescendantText(cell, 'v');
    if (type == 's') {
      final index = int.tryParse(value);
      if (index == null || index < 0 || index >= sharedStrings.length) {
        return '';
      }
      return sharedStrings[index];
    }
    if (type == 'inlineStr') {
      return _normalizePreviewText(_collectParagraphText(cell));
    }
    if (type == 'b') {
      return value == '1' ? 'TRUE' : 'FALSE';
    }
    if (value.isNotEmpty) {
      return _normalizePreviewText(value);
    }
    final formula = _firstDescendantText(cell, 'f');
    if (formula.isNotEmpty) {
      return '=$formula';
    }
    return _normalizePreviewText(_collectParagraphText(cell));
  }

  static XmlDocument _parseXmlEntry(Archive archive, String path) {
    final document = _tryParseXmlEntry(archive, path);
    if (document == null) {
      throw StateError(
        LegacyTextLocalizer.isEnglish ? 'File missing: $path' : '文件缺少 $path',
      );
    }
    return document;
  }

  static XmlDocument? _tryParseXmlEntry(Archive archive, String path) {
    for (final archiveFile in archive.files) {
      if (archiveFile.name == path) {
        return _parseXmlContent(archiveFile.content as List<int>);
      }
    }
    return null;
  }

  static XmlDocument _parseXmlContent(List<int> bytes) {
    return XmlDocument.parse(utf8.decode(bytes, allowMalformed: true));
  }

  static Iterable<XmlElement> _elementsByLocalName(
    XmlNode node,
    String localName,
  ) {
    return node.descendants.whereType<XmlElement>().where(
      (element) => element.name.local == localName,
    );
  }

  static Iterable<XmlElement> _directChildrenByLocalName(
    XmlElement node,
    String localName,
  ) {
    return node.childElements.where(
      (element) => element.name.local == localName,
    );
  }

  static String _collectParagraphText(XmlElement element) {
    final buffer = StringBuffer();
    for (final descendant in element.descendants.whereType<XmlElement>()) {
      switch (descendant.name.local) {
        case 't':
          buffer.write(descendant.innerText);
          break;
        case 'tab':
          buffer.write('    ');
          break;
        case 'br':
        case 'cr':
          buffer.write('\n');
          break;
      }
    }
    return buffer.toString();
  }

  static String _firstDescendantText(XmlElement element, String localName) {
    for (final descendant in element.descendants.whereType<XmlElement>()) {
      if (descendant.name.local == localName) {
        return descendant.innerText.trim();
      }
    }
    return '';
  }

  static String _attributeValue(XmlElement element, String localName) {
    for (final attribute in element.attributes) {
      if (attribute.name.local == localName) {
        return attribute.value.trim();
      }
    }
    return '';
  }

  static int _columnIndexFromCellReference(String reference) {
    if (reference.isEmpty) return -1;
    final letters = StringBuffer();
    for (final codeUnit in reference.codeUnits) {
      final char = String.fromCharCode(codeUnit);
      if (RegExp(r'[A-Za-z]').hasMatch(char)) {
        letters.write(char.toUpperCase());
      } else {
        break;
      }
    }
    if (letters.isEmpty) return -1;

    var result = 0;
    for (final codeUnit in letters.toString().codeUnits) {
      result = result * 26 + (codeUnit - 64);
    }
    return result - 1;
  }

  static int _extractTrailingNumber(String value) {
    final match = RegExp(r'(\d+)').firstMatch(value);
    return int.tryParse(match?.group(1) ?? '') ?? 0;
  }

  static String _normalizePreviewText(String value) {
    final normalizedLines = value
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .split('\n')
        .map((line) => line.replaceAll(RegExp(r'\s+'), ' ').trim())
        .where((line) => line.isNotEmpty)
        .toList(growable: false);
    return normalizedLines.join('\n');
  }
}
