import 'package:flutter/services.dart';
import 'package:ui/services/assists_core_service.dart';

enum WebToolBackend {
  builtin('builtin'),
  deepseek('deepseek'),
  custom('custom');

  const WebToolBackend(this.wireName);
  final String wireName;

  static WebToolBackend fromWire(String? value) {
    final normalized = value?.trim().toLowerCase();
    return switch (normalized) {
      'deepseek' => WebToolBackend.deepseek,
      'custom' => WebToolBackend.custom,
      _ => WebToolBackend.builtin,
    };
  }
}

enum WebHttpMethod {
  post('POST'),
  get('GET');

  const WebHttpMethod(this.wireName);
  final String wireName;

  static WebHttpMethod fromWire(String? value) {
    return value?.trim().toUpperCase() == 'GET'
        ? WebHttpMethod.get
        : WebHttpMethod.post;
  }
}

class CustomWebSearchConfig {
  const CustomWebSearchConfig({
    this.method = WebHttpMethod.post,
    this.url = '',
    this.headersText = '',
    this.bodyTemplate = defaultBodyTemplate,
    this.resultsPath = defaultResultsPath,
    this.titleField = defaultTitleField,
    this.urlField = defaultUrlField,
    this.snippetField = defaultSnippetField,
    this.apiKey = '',
  });

  static const String defaultBodyTemplate =
      '{"query": {{query_json}}, "count": {{count}}}';
  static const String defaultResultsPath = 'results';
  static const String defaultTitleField = 'title';
  static const String defaultUrlField = 'url';
  static const String defaultSnippetField = 'snippet';

  final WebHttpMethod method;
  final String url;
  final String headersText;
  final String bodyTemplate;
  final String resultsPath;
  final String titleField;
  final String urlField;
  final String snippetField;
  final String apiKey;

  factory CustomWebSearchConfig.fromMap(Map<dynamic, dynamic>? raw) {
    if (raw == null) return const CustomWebSearchConfig();
    return CustomWebSearchConfig(
      method: WebHttpMethod.fromWire(raw['method']?.toString()),
      url: raw['url']?.toString() ?? '',
      headersText: raw['headersText']?.toString() ?? '',
      bodyTemplate: raw['bodyTemplate']?.toString().isNotEmpty == true
          ? raw['bodyTemplate'].toString()
          : defaultBodyTemplate,
      resultsPath: raw['resultsPath']?.toString().isNotEmpty == true
          ? raw['resultsPath'].toString()
          : defaultResultsPath,
      titleField: raw['titleField']?.toString().isNotEmpty == true
          ? raw['titleField'].toString()
          : defaultTitleField,
      urlField: raw['urlField']?.toString().isNotEmpty == true
          ? raw['urlField'].toString()
          : defaultUrlField,
      snippetField: raw['snippetField']?.toString().isNotEmpty == true
          ? raw['snippetField'].toString()
          : defaultSnippetField,
      apiKey: raw['apiKey']?.toString() ?? '',
    );
  }

  Map<String, dynamic> toMap() => {
    'method': method.wireName,
    'url': url,
    'headersText': headersText,
    'bodyTemplate': bodyTemplate,
    'resultsPath': resultsPath,
    'titleField': titleField,
    'urlField': urlField,
    'snippetField': snippetField,
    'apiKey': apiKey,
  };

  CustomWebSearchConfig copyWith({
    WebHttpMethod? method,
    String? url,
    String? headersText,
    String? bodyTemplate,
    String? resultsPath,
    String? titleField,
    String? urlField,
    String? snippetField,
    String? apiKey,
  }) => CustomWebSearchConfig(
    method: method ?? this.method,
    url: url ?? this.url,
    headersText: headersText ?? this.headersText,
    bodyTemplate: bodyTemplate ?? this.bodyTemplate,
    resultsPath: resultsPath ?? this.resultsPath,
    titleField: titleField ?? this.titleField,
    urlField: urlField ?? this.urlField,
    snippetField: snippetField ?? this.snippetField,
    apiKey: apiKey ?? this.apiKey,
  );
}

class CustomWebFetchConfig {
  const CustomWebFetchConfig({
    this.method = WebHttpMethod.post,
    this.url = '',
    this.headersText = '',
    this.bodyTemplate = defaultBodyTemplate,
    this.contentField = defaultContentField,
    this.titleField = defaultTitleField,
    this.apiKey = '',
  });

  static const String defaultBodyTemplate = '{"url": {{url_json}}}';
  static const String defaultContentField = 'content';
  static const String defaultTitleField = 'title';

  final WebHttpMethod method;
  final String url;
  final String headersText;
  final String bodyTemplate;
  final String contentField;
  final String titleField;
  final String apiKey;

  factory CustomWebFetchConfig.fromMap(Map<dynamic, dynamic>? raw) {
    if (raw == null) return const CustomWebFetchConfig();
    return CustomWebFetchConfig(
      method: WebHttpMethod.fromWire(raw['method']?.toString()),
      url: raw['url']?.toString() ?? '',
      headersText: raw['headersText']?.toString() ?? '',
      bodyTemplate: raw['bodyTemplate']?.toString().isNotEmpty == true
          ? raw['bodyTemplate'].toString()
          : defaultBodyTemplate,
      contentField: raw['contentField']?.toString() ?? defaultContentField,
      titleField: raw['titleField']?.toString() ?? defaultTitleField,
      apiKey: raw['apiKey']?.toString() ?? '',
    );
  }

  Map<String, dynamic> toMap() => {
    'method': method.wireName,
    'url': url,
    'headersText': headersText,
    'bodyTemplate': bodyTemplate,
    'contentField': contentField,
    'titleField': titleField,
    'apiKey': apiKey,
  };

  CustomWebFetchConfig copyWith({
    WebHttpMethod? method,
    String? url,
    String? headersText,
    String? bodyTemplate,
    String? contentField,
    String? titleField,
    String? apiKey,
  }) => CustomWebFetchConfig(
    method: method ?? this.method,
    url: url ?? this.url,
    headersText: headersText ?? this.headersText,
    bodyTemplate: bodyTemplate ?? this.bodyTemplate,
    contentField: contentField ?? this.contentField,
    titleField: titleField ?? this.titleField,
    apiKey: apiKey ?? this.apiKey,
  );
}

class WebToolsConfig {
  const WebToolsConfig({
    this.searchBackend = WebToolBackend.builtin,
    this.fetchBackend = WebToolBackend.builtin,
    this.deepSeekBaseUrl = defaultDeepSeekBaseUrl,
    this.deepSeekApiKey = '',
    this.hasProviderDeepSeekKey = false,
    this.providerDeepSeekBaseUrl = '',
    this.customSearch = const CustomWebSearchConfig(),
    this.customFetch = const CustomWebFetchConfig(),
  });

  static const String defaultDeepSeekBaseUrl = 'https://api.deepseek.com';

  final WebToolBackend searchBackend;
  final WebToolBackend fetchBackend;
  final String deepSeekBaseUrl;
  final String deepSeekApiKey;
  final bool hasProviderDeepSeekKey;
  final String providerDeepSeekBaseUrl;
  final CustomWebSearchConfig customSearch;
  final CustomWebFetchConfig customFetch;

  factory WebToolsConfig.fromMap(Map<dynamic, dynamic>? raw) {
    if (raw == null) return const WebToolsConfig();
    final baseUrl = raw['deepSeekBaseUrl']?.toString().trim();
    return WebToolsConfig(
      searchBackend: WebToolBackend.fromWire(raw['searchBackend']?.toString()),
      fetchBackend: WebToolBackend.fromWire(raw['fetchBackend']?.toString()),
      deepSeekBaseUrl: (baseUrl == null || baseUrl.isEmpty)
          ? defaultDeepSeekBaseUrl
          : baseUrl,
      deepSeekApiKey: raw['deepSeekApiKey']?.toString() ?? '',
      hasProviderDeepSeekKey: raw['hasProviderDeepSeekKey'] == true,
      providerDeepSeekBaseUrl:
          raw['providerDeepSeekBaseUrl']?.toString().trim() ?? '',
      customSearch: CustomWebSearchConfig.fromMap(
        raw['customSearch'] as Map<dynamic, dynamic>?,
      ),
      customFetch: CustomWebFetchConfig.fromMap(
        raw['customFetch'] as Map<dynamic, dynamic>?,
      ),
    );
  }

  WebToolsConfig copyWith({
    WebToolBackend? searchBackend,
    WebToolBackend? fetchBackend,
    String? deepSeekBaseUrl,
    String? deepSeekApiKey,
    bool? hasProviderDeepSeekKey,
    String? providerDeepSeekBaseUrl,
    CustomWebSearchConfig? customSearch,
    CustomWebFetchConfig? customFetch,
  }) => WebToolsConfig(
    searchBackend: searchBackend ?? this.searchBackend,
    fetchBackend: fetchBackend ?? this.fetchBackend,
    deepSeekBaseUrl: deepSeekBaseUrl ?? this.deepSeekBaseUrl,
    deepSeekApiKey: deepSeekApiKey ?? this.deepSeekApiKey,
    hasProviderDeepSeekKey:
        hasProviderDeepSeekKey ?? this.hasProviderDeepSeekKey,
    providerDeepSeekBaseUrl:
        providerDeepSeekBaseUrl ?? this.providerDeepSeekBaseUrl,
    customSearch: customSearch ?? this.customSearch,
    customFetch: customFetch ?? this.customFetch,
  );
}

class WebToolsConfigService {
  static Future<WebToolsConfig> getConfig() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<Map<dynamic, dynamic>>('getWebToolsConfig');
      return WebToolsConfig.fromMap(result);
    } on PlatformException {
      return const WebToolsConfig();
    }
  }

  static Future<WebToolsConfig> saveConfig(WebToolsConfig config) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('saveWebToolsConfig', {
          'searchBackend': config.searchBackend.wireName,
          'fetchBackend': config.fetchBackend.wireName,
          'deepSeekBaseUrl': config.deepSeekBaseUrl,
          'deepSeekApiKey': config.deepSeekApiKey,
          'customSearch': config.customSearch.toMap(),
          'customFetch': config.customFetch.toMap(),
        });
    return WebToolsConfig.fromMap(result);
  }
}
