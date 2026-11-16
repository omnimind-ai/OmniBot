import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/web_tools_config_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/settings_section_title.dart';
import 'package:ui/widgets/settings_ui.dart';

class WebToolsSettingPage extends StatefulWidget {
  const WebToolsSettingPage({super.key});

  @override
  State<WebToolsSettingPage> createState() => _WebToolsSettingPageState();
}

class _WebToolsSettingPageState extends State<WebToolsSettingPage>
    with FlClashPageLoadMixin {
  bool _loading = true;
  bool _saving = false;
  bool _hasProviderDeepSeekKey = false;
  String _providerDeepSeekBaseUrl = '';

  WebToolBackend _searchBackend = WebToolBackend.builtin;
  WebToolBackend _fetchBackend = WebToolBackend.builtin;
  WebHttpMethod _customSearchMethod = WebHttpMethod.post;
  WebHttpMethod _customFetchMethod = WebHttpMethod.post;

  bool _obscureDeepSeekKey = true;
  bool _obscureCustomSearchKey = true;
  bool _obscureCustomFetchKey = true;
  bool _showCustomSearchAdvanced = false;
  bool _showCustomFetchAdvanced = false;

  final _deepSeekBaseUrlController = TextEditingController(
    text: WebToolsConfig.defaultDeepSeekBaseUrl,
  );
  final _deepSeekApiKeyController = TextEditingController();

  final _customSearchUrlController = TextEditingController();
  final _customSearchApiKeyController = TextEditingController();
  final _customSearchBodyController = TextEditingController(
    text: CustomWebSearchConfig.defaultBodyTemplate,
  );
  final _customSearchResultsPathController = TextEditingController(
    text: CustomWebSearchConfig.defaultResultsPath,
  );
  final _customSearchTitleFieldController = TextEditingController(
    text: CustomWebSearchConfig.defaultTitleField,
  );
  final _customSearchUrlFieldController = TextEditingController(
    text: CustomWebSearchConfig.defaultUrlField,
  );
  final _customSearchSnippetFieldController = TextEditingController(
    text: CustomWebSearchConfig.defaultSnippetField,
  );
  final _customSearchHeadersController = TextEditingController();

  final _customFetchUrlController = TextEditingController();
  final _customFetchApiKeyController = TextEditingController();
  final _customFetchBodyController = TextEditingController(
    text: CustomWebFetchConfig.defaultBodyTemplate,
  );
  final _customFetchContentFieldController = TextEditingController(
    text: CustomWebFetchConfig.defaultContentField,
  );
  final _customFetchTitleFieldController = TextEditingController(
    text: CustomWebFetchConfig.defaultTitleField,
  );
  final _customFetchHeadersController = TextEditingController();

  bool get _isEnglish => Localizations.localeOf(context).languageCode != 'zh';
  String _text(String zh, String en) => _isEnglish ? en : zh;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _deepSeekBaseUrlController.dispose();
    _deepSeekApiKeyController.dispose();
    _customSearchUrlController.dispose();
    _customSearchApiKeyController.dispose();
    _customSearchBodyController.dispose();
    _customSearchResultsPathController.dispose();
    _customSearchTitleFieldController.dispose();
    _customSearchUrlFieldController.dispose();
    _customSearchSnippetFieldController.dispose();
    _customSearchHeadersController.dispose();
    _customFetchUrlController.dispose();
    _customFetchApiKeyController.dispose();
    _customFetchBodyController.dispose();
    _customFetchContentFieldController.dispose();
    _customFetchTitleFieldController.dispose();
    _customFetchHeadersController.dispose();
    super.dispose();
  }

  Future<void> _load() => runPageLoad(#webToolsLoad, () async {
    if (!await waitForPageReady() || !mounted) return;
    setState(() => _loading = true);
    try {
      final config = await WebToolsConfigService.getConfig();
      if (!await waitForPageReady() ||
          !mounted ||
          !pageLoadIsCurrent(#webToolsLoad)) {
        return;
      }
      _applyConfig(config);
    } catch (_) {
      if (mounted) {
        showToast(
          _text('无法读取网页工具设置', 'Failed to load web tools settings'),
          type: ToastType.error,
        );
      }
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  });

  void _applyConfig(WebToolsConfig config) {
    setState(() {
      _searchBackend = config.searchBackend;
      _fetchBackend = config.fetchBackend;
      _hasProviderDeepSeekKey = config.hasProviderDeepSeekKey;
      _providerDeepSeekBaseUrl = config.providerDeepSeekBaseUrl;
      _deepSeekBaseUrlController.text = config.deepSeekBaseUrl;
      _deepSeekApiKeyController.text = config.deepSeekApiKey;

      _customSearchMethod = config.customSearch.method;
      _customSearchUrlController.text = config.customSearch.url;
      _customSearchApiKeyController.text = config.customSearch.apiKey;
      _customSearchBodyController.text = config.customSearch.bodyTemplate;
      _customSearchResultsPathController.text = config.customSearch.resultsPath;
      _customSearchTitleFieldController.text = config.customSearch.titleField;
      _customSearchUrlFieldController.text = config.customSearch.urlField;
      _customSearchSnippetFieldController.text =
          config.customSearch.snippetField;
      _customSearchHeadersController.text = config.customSearch.headersText;

      _customFetchMethod = config.customFetch.method;
      _customFetchUrlController.text = config.customFetch.url;
      _customFetchApiKeyController.text = config.customFetch.apiKey;
      _customFetchBodyController.text = config.customFetch.bodyTemplate;
      _customFetchContentFieldController.text = config.customFetch.contentField;
      _customFetchTitleFieldController.text = config.customFetch.titleField;
      _customFetchHeadersController.text = config.customFetch.headersText;
    });
  }

  Future<void> _save() async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      final next = WebToolsConfig(
        searchBackend: _searchBackend,
        fetchBackend: _fetchBackend,
        deepSeekBaseUrl: _deepSeekBaseUrlController.text.trim(),
        deepSeekApiKey: _deepSeekApiKeyController.text.trim(),
        customSearch: CustomWebSearchConfig(
          method: _customSearchMethod,
          url: _customSearchUrlController.text.trim(),
          headersText: _customSearchHeadersController.text.trim(),
          bodyTemplate: _customSearchBodyController.text.trim(),
          resultsPath: _customSearchResultsPathController.text.trim(),
          titleField: _customSearchTitleFieldController.text.trim(),
          urlField: _customSearchUrlFieldController.text.trim(),
          snippetField: _customSearchSnippetFieldController.text.trim(),
          apiKey: _customSearchApiKeyController.text.trim(),
        ),
        customFetch: CustomWebFetchConfig(
          method: _customFetchMethod,
          url: _customFetchUrlController.text.trim(),
          headersText: _customFetchHeadersController.text.trim(),
          bodyTemplate: _customFetchBodyController.text.trim(),
          contentField: _customFetchContentFieldController.text.trim(),
          titleField: _customFetchTitleFieldController.text.trim(),
          apiKey: _customFetchApiKeyController.text.trim(),
        ),
      );
      final saved = await WebToolsConfigService.saveConfig(next);
      if (!mounted) return;
      _applyConfig(saved);
      showToast(_text('已保存', 'Saved'), type: ToastType.success);
    } on PlatformException catch (e) {
      if (!mounted) return;
      final message = e.message?.trim();
      showToast(
        (message != null && message.isNotEmpty)
            ? message
            : _text('保存失败', 'Failed to save'),
        type: ToastType.error,
      );
    } catch (_) {
      if (!mounted) return;
      showToast(_text('保存失败', 'Failed to save'), type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  String _searchBackendLabel(WebToolBackend backend) => switch (backend) {
    WebToolBackend.builtin => _text('内置默认 (DuckDuckGo)', 'Built-in (DuckDuckGo)'),
    WebToolBackend.deepseek => 'DeepSeek',
    WebToolBackend.custom => _text('自定义接口', 'Custom API'),
  };

  String _fetchBackendLabel(WebToolBackend backend) => switch (backend) {
    WebToolBackend.builtin => _text('内置默认 (静态抓取)', 'Built-in (Static)'),
    WebToolBackend.deepseek => 'DeepSeek',
    WebToolBackend.custom => _text('自定义接口', 'Custom API'),
  };

  List<OmniSettingsOption<WebToolBackend>> _backendOptions({
    required bool isSearch,
  }) => [
    for (final b in WebToolBackend.values)
      OmniSettingsOption(
        value: b,
        label: isSearch ? _searchBackendLabel(b) : _fetchBackendLabel(b),
      ),
  ];

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final usesDeepSeek =
        _searchBackend == WebToolBackend.deepseek ||
        _fetchBackend == WebToolBackend.deepseek;
    final usesRemote =
        _searchBackend != WebToolBackend.builtin ||
        _fetchBackend != WebToolBackend.builtin;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text('网页搜索与读取', 'Web Search & Fetch'),
        primary: true,
      ),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : ListView(
                  keyboardDismissBehavior:
                      ScrollViewKeyboardDismissBehavior.onDrag,
                  padding: edgeToEdgeScrollPadding(
                    context,
                    const EdgeInsets.fromLTRB(22, 12, 22, 32),
                  ),
                  children: [
                    OmniSettingsGroup(
                      title: _text('服务', 'Services'),
                      translateTitle: false,
                      dividerIndent: 18,
                      footer: Text(
                        usesRemote
                            ? _text(
                                '远端网页读取失败时会自动回退到本地静态抓取；同一网页翻页复用当前运行缓存，不重复请求。',
                                'Remote fetch automatically falls back to local static fetching on failure. Pagination reuses the in-run snapshot.',
                              )
                            : _text(
                                '内置方案免配置开箱即用；切换为 DeepSeek 或自定义接口可获得更佳的检索质量与 Markdown 正文提取。',
                                'Built-in mode works out of the box. Switch to DeepSeek or a custom endpoint for better search quality and Markdown extraction.',
                              ),
                      ),
                      children: [
                        OmniSettingsTile(
                          icon: LucideIcons.search,
                          title: _text('网页搜索 (web_search)', 'Web search (web_search)'),
                          translateText: false,
                          trailing: OmniSettingsDropdown<WebToolBackend>(
                            dropdownKey: const Key('web-tools-search-backend'),
                            value: _searchBackend,
                            selectedLabel: _searchBackendLabel(_searchBackend),
                            options: _backendOptions(isSearch: true),
                            maxWidth: 180,
                            onChanged: (value) {
                              if (value != null) {
                                setState(() => _searchBackend = value);
                              }
                            },
                          ),
                        ),
                        OmniSettingsTile(
                          icon: LucideIcons.fileText,
                          title: _text('网页读取 (web_fetch)', 'Web fetch (web_fetch)'),
                          translateText: false,
                          trailing: OmniSettingsDropdown<WebToolBackend>(
                            dropdownKey: const Key('web-tools-fetch-backend'),
                            value: _fetchBackend,
                            selectedLabel: _fetchBackendLabel(_fetchBackend),
                            options: _backendOptions(isSearch: false),
                            maxWidth: 180,
                            onChanged: (value) {
                              if (value != null) {
                                setState(() => _fetchBackend = value);
                              }
                            },
                          ),
                        ),
                      ],
                    ),
                    if (usesDeepSeek) ...[
                      const SizedBox(height: 22),
                      _buildDeepSeekSection(context),
                    ],
                    if (_searchBackend == WebToolBackend.custom) ...[
                      const SizedBox(height: 22),
                      _buildCustomSearchSection(context),
                    ],
                    if (_fetchBackend == WebToolBackend.custom) ...[
                      const SizedBox(height: 22),
                      _buildCustomFetchSection(context),
                    ],
                    const SizedBox(height: 24),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        key: const Key('web-tools-save-button'),
                        onPressed: _saving ? null : _save,
                        style: FilledButton.styleFrom(
                          backgroundColor: palette.accentPrimary,
                          foregroundColor: Colors.white,
                          minimumSize: const Size.fromHeight(46),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                        ),
                        child: _saving
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white,
                                ),
                              )
                            : Text(_text('保存', 'Save')),
                      ),
                    ),
                  ],
                ),
        ),
      ),
    );
  }

  Widget _buildDeepSeekSection(BuildContext context) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: 'DeepSeek',
          subtitle: _hasProviderDeepSeekKey
              ? _text(
                  '已自动关联「模型提供商」中的 DeepSeek API Key；也可在此填写独立密钥覆盖。',
                  'Automatically linked to the DeepSeek API Key in Model Providers; you can still override it here.',
                )
              : _text(
                  '调用 DeepSeek /v1/tools/search 与 /v1/tools/fetch 接口；留空时将尝试复用 DeepSeek 模型提供商的密钥。',
                  'Calls DeepSeek /v1/tools/search and /v1/tools/fetch. Leave API Key blank to reuse your DeepSeek provider key.',
                ),
        ),
        OmniSettingsCard(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_hasProviderDeepSeekKey) ...[
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: OmniSettingsStatusBadge(
                    label: _providerDeepSeekBaseUrl.isNotEmpty
                        ? _text(
                            '已关联 DeepSeek 提供商（$_providerDeepSeekBaseUrl）',
                            'Linked to DeepSeek provider ($_providerDeepSeekBaseUrl)',
                          )
                        : _text(
                            '已关联 DeepSeek 模型提供商',
                            'Linked to DeepSeek model provider',
                          ),
                    color: const Color(0xFF2F8F4E),
                  ),
                ),
              ],
              TextField(
                key: const Key('web-tools-deepseek-base-url'),
                controller: _deepSeekBaseUrlController,
                keyboardType: TextInputType.url,
                style: context.omniInputTextStyle,
                decoration: omniSettingsInputDecoration(
                  context,
                  labelText: _text('接口地址 (Base URL)', 'Base URL'),
                  hintText: WebToolsConfig.defaultDeepSeekBaseUrl,
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const Key('web-tools-deepseek-api-key'),
                controller: _deepSeekApiKeyController,
                obscureText: _obscureDeepSeekKey,
                style: context.omniInputTextStyle,
                decoration: omniSettingsInputDecoration(
                  context,
                  labelText: _text('API Key（选填）', 'API Key (optional)'),
                  hintText: _hasProviderDeepSeekKey
                      ? _text('留空则复用 DeepSeek 提供商密钥', 'Leave blank to reuse provider key')
                      : 'sk-xxxx',
                  suffixIcon: IconButton(
                    splashRadius: 18,
                    onPressed: () => setState(
                      () => _obscureDeepSeekKey = !_obscureDeepSeekKey,
                    ),
                    icon: Icon(
                      _obscureDeepSeekKey
                          ? LucideIcons.eyeOff
                          : LucideIcons.eye,
                      color: palette.textTertiary,
                      size: 18,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildCustomSearchSection(BuildContext context) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: _text('自定义搜索', 'Custom Search'),
          subtitle: _text(
            '支持 {{query}}、{{query_json}} 与 {{count}} 占位符；填写 API Key 时默认附加 Bearer 鉴权头。',
            'Supports {{query}}, {{query_json}}, and {{count}} placeholders. Sends Bearer Auth when API Key is provided.',
          ),
        ),
        OmniSettingsGroup(
          dividerIndent: 18,
          children: [
            OmniSettingsTile(
              title: _text('请求方式', 'HTTP Method'),
              translateText: false,
              trailing: OmniSettingsDropdown<WebHttpMethod>(
                value: _customSearchMethod,
                selectedLabel: _customSearchMethod.wireName,
                options: const [
                  OmniSettingsOption(value: WebHttpMethod.post, label: 'POST'),
                  OmniSettingsOption(value: WebHttpMethod.get, label: 'GET'),
                ],
                onChanged: (method) {
                  if (method != null) {
                    setState(() => _customSearchMethod = method);
                  }
                },
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        OmniSettingsCard(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: _customSearchUrlController,
                keyboardType: TextInputType.url,
                style: context.omniInputTextStyle,
                decoration: omniSettingsInputDecoration(
                  context,
                  labelText: _text('接口地址 (URL)', 'Endpoint URL'),
                  hintText: 'https://api.example.com/search',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _customSearchApiKeyController,
                obscureText: _obscureCustomSearchKey,
                style: context.omniInputTextStyle,
                decoration: omniSettingsInputDecoration(
                  context,
                  labelText: _text('API Key（选填）', 'API Key (optional)'),
                  suffixIcon: IconButton(
                    splashRadius: 18,
                    onPressed: () => setState(
                      () => _obscureCustomSearchKey = !_obscureCustomSearchKey,
                    ),
                    icon: Icon(
                      _obscureCustomSearchKey
                          ? LucideIcons.eyeOff
                          : LucideIcons.eye,
                      color: palette.textTertiary,
                      size: 18,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              InkWell(
                borderRadius: BorderRadius.circular(8),
                onTap: () => setState(
                  () => _showCustomSearchAdvanced = !_showCustomSearchAdvanced,
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          _text('请求模板与响应字段', 'Request Template & Field Mapping'),
                          style: TextStyle(
                            fontSize: 13.5,
                            fontWeight: FontWeight.w500,
                            color: palette.accentPrimary,
                          ),
                        ),
                      ),
                      Icon(
                        _showCustomSearchAdvanced
                            ? LucideIcons.chevronUp
                            : LucideIcons.chevronDown,
                        size: 16,
                        color: palette.accentPrimary,
                      ),
                    ],
                  ),
                ),
              ),
              if (_showCustomSearchAdvanced) ...[
                const SizedBox(height: 10),
                if (_customSearchMethod == WebHttpMethod.post) ...[
                  TextField(
                    controller: _customSearchBodyController,
                    maxLines: 3,
                    minLines: 2,
                    style: context.omniInputTextStyle,
                    decoration: omniSettingsInputDecoration(
                      context,
                      labelText: _text('请求体模板 (JSON)', 'Request Body Template (JSON)'),
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
                TextField(
                  controller: _customSearchResultsPathController,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text('结果数组路径', 'Results Array Path'),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _customSearchTitleFieldController,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text('标题字段', 'Title Field'),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _customSearchUrlFieldController,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text('链接字段', 'URL Field'),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _customSearchSnippetFieldController,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text('摘要字段', 'Snippet Field'),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _customSearchHeadersController,
                  maxLines: 4,
                  minLines: 2,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text(
                      '自定义请求头（选填，每行 Header: Value）',
                      'Custom Headers (optional, Header: Value per line)',
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildCustomFetchSection(BuildContext context) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: _text('自定义读取', 'Custom Fetch'),
          subtitle: _text(
            '支持 {{url}}、{{raw_url}} 与 {{url_json}} 占位符；远端读取失败时自动回退到本地静态抓取。',
            'Supports {{url}}, {{raw_url}}, and {{url_json}} placeholders. Falls back to static fetch on failure.',
          ),
        ),
        OmniSettingsGroup(
          dividerIndent: 18,
          children: [
            OmniSettingsTile(
              title: _text('请求方式', 'HTTP Method'),
              translateText: false,
              trailing: OmniSettingsDropdown<WebHttpMethod>(
                value: _customFetchMethod,
                selectedLabel: _customFetchMethod.wireName,
                options: const [
                  OmniSettingsOption(value: WebHttpMethod.post, label: 'POST'),
                  OmniSettingsOption(value: WebHttpMethod.get, label: 'GET'),
                ],
                onChanged: (method) {
                  if (method != null) {
                    setState(() => _customFetchMethod = method);
                  }
                },
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        OmniSettingsCard(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: _customFetchUrlController,
                keyboardType: TextInputType.url,
                style: context.omniInputTextStyle,
                decoration: omniSettingsInputDecoration(
                  context,
                  labelText: _text('接口地址 (URL)', 'Endpoint URL'),
                  hintText: 'https://api.example.com/fetch',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _customFetchApiKeyController,
                obscureText: _obscureCustomFetchKey,
                style: context.omniInputTextStyle,
                decoration: omniSettingsInputDecoration(
                  context,
                  labelText: _text('API Key（选填）', 'API Key (optional)'),
                  suffixIcon: IconButton(
                    splashRadius: 18,
                    onPressed: () => setState(
                      () => _obscureCustomFetchKey = !_obscureCustomFetchKey,
                    ),
                    icon: Icon(
                      _obscureCustomFetchKey
                          ? LucideIcons.eyeOff
                          : LucideIcons.eye,
                      color: palette.textTertiary,
                      size: 18,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              InkWell(
                borderRadius: BorderRadius.circular(8),
                onTap: () => setState(
                  () => _showCustomFetchAdvanced = !_showCustomFetchAdvanced,
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          _text('请求模板与响应字段', 'Request Template & Field Mapping'),
                          style: TextStyle(
                            fontSize: 13.5,
                            fontWeight: FontWeight.w500,
                            color: palette.accentPrimary,
                          ),
                        ),
                      ),
                      Icon(
                        _showCustomFetchAdvanced
                            ? LucideIcons.chevronUp
                            : LucideIcons.chevronDown,
                        size: 16,
                        color: palette.accentPrimary,
                      ),
                    ],
                  ),
                ),
              ),
              if (_showCustomFetchAdvanced) ...[
                const SizedBox(height: 10),
                if (_customFetchMethod == WebHttpMethod.post) ...[
                  TextField(
                    controller: _customFetchBodyController,
                    maxLines: 3,
                    minLines: 2,
                    style: context.omniInputTextStyle,
                    decoration: omniSettingsInputDecoration(
                      context,
                      labelText: _text('请求体模板 (JSON)', 'Request Body Template (JSON)'),
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
                TextField(
                  controller: _customFetchContentFieldController,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text(
                      '正文字段路径（留空则直接取响应文本）',
                      'Content Field Path (blank = raw response text)',
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _customFetchTitleFieldController,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text('标题字段', 'Title Field'),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _customFetchHeadersController,
                  maxLines: 4,
                  minLines: 2,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text(
                      '自定义请求头（选填，每行 Header: Value）',
                      'Custom Headers (optional, Header: Value per line)',
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}
