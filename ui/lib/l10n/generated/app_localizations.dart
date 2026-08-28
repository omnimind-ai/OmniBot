import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_zh.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'generated/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('zh'),
  ];

  /// No description provided for @appName.
  ///
  /// In zh, this message translates to:
  /// **'小万'**
  String get appName;

  /// No description provided for @brandName.
  ///
  /// In zh, this message translates to:
  /// **'小万'**
  String get brandName;

  /// No description provided for @brandNameEnglish.
  ///
  /// In zh, this message translates to:
  /// **'Omnibot'**
  String get brandNameEnglish;

  /// No description provided for @commonLoading.
  ///
  /// In zh, this message translates to:
  /// **'加载中'**
  String get commonLoading;

  /// No description provided for @homeDrawerSearchHint.
  ///
  /// In zh, this message translates to:
  /// **'搜索全部对话'**
  String get homeDrawerSearchHint;

  /// No description provided for @homeDrawerClearSearch.
  ///
  /// In zh, this message translates to:
  /// **'清空搜索'**
  String get homeDrawerClearSearch;

  /// No description provided for @themeModeTitle.
  ///
  /// In zh, this message translates to:
  /// **'主题模式'**
  String get themeModeTitle;

  /// No description provided for @themeModeSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'切换浅色、深色或跟随系统外观'**
  String get themeModeSubtitle;

  /// No description provided for @themeModeLight.
  ///
  /// In zh, this message translates to:
  /// **'浅色'**
  String get themeModeLight;

  /// No description provided for @themeModeDark.
  ///
  /// In zh, this message translates to:
  /// **'深色'**
  String get themeModeDark;

  /// No description provided for @themeModeSystem.
  ///
  /// In zh, this message translates to:
  /// **'系统'**
  String get themeModeSystem;

  /// No description provided for @languageTitle.
  ///
  /// In zh, this message translates to:
  /// **'语言'**
  String get languageTitle;

  /// No description provided for @languageSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'设置应用界面、Agent 提示词与工具文案的显示语言'**
  String get languageSubtitle;

  /// No description provided for @languageFollowSystem.
  ///
  /// In zh, this message translates to:
  /// **'跟随系统'**
  String get languageFollowSystem;

  /// No description provided for @languageZhHans.
  ///
  /// In zh, this message translates to:
  /// **'简体中文'**
  String get languageZhHans;

  /// No description provided for @languageEnglish.
  ///
  /// In zh, this message translates to:
  /// **'English'**
  String get languageEnglish;

  /// No description provided for @settingsTitle.
  ///
  /// In zh, this message translates to:
  /// **'设置'**
  String get settingsTitle;

  /// No description provided for @settingsSectionModelMemory.
  ///
  /// In zh, this message translates to:
  /// **'模型与记忆'**
  String get settingsSectionModelMemory;

  /// No description provided for @settingsSectionServiceEnvironment.
  ///
  /// In zh, this message translates to:
  /// **'服务与环境'**
  String get settingsSectionServiceEnvironment;

  /// No description provided for @settingsSectionExperienceAppearance.
  ///
  /// In zh, this message translates to:
  /// **'体验与外观'**
  String get settingsSectionExperienceAppearance;

  /// No description provided for @settingsSectionPermissionInfo.
  ///
  /// In zh, this message translates to:
  /// **'权限与信息'**
  String get settingsSectionPermissionInfo;

  /// No description provided for @settingsModelProviderTitle.
  ///
  /// In zh, this message translates to:
  /// **'模型提供商'**
  String get settingsModelProviderTitle;

  /// No description provided for @settingsModelProviderSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'配置模型地址、密钥与模型列表'**
  String get settingsModelProviderSubtitle;

  /// No description provided for @settingsSceneModelTitle.
  ///
  /// In zh, this message translates to:
  /// **'场景模型配置'**
  String get settingsSceneModelTitle;

  /// No description provided for @settingsSceneModelSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'按场景绑定模型，未绑定场景使用默认模型'**
  String get settingsSceneModelSubtitle;

  /// No description provided for @settingsWorkspaceMemoryTitle.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 记忆配置'**
  String get settingsWorkspaceMemoryTitle;

  /// No description provided for @settingsWorkspaceMemoryLoading.
  ///
  /// In zh, this message translates to:
  /// **'加载中...'**
  String get settingsWorkspaceMemoryLoading;

  /// No description provided for @settingsWorkspaceMemoryEnabled.
  ///
  /// In zh, this message translates to:
  /// **'已启用 workspace 记忆（嵌入检索可用）'**
  String get settingsWorkspaceMemoryEnabled;

  /// No description provided for @settingsWorkspaceMemoryLexical.
  ///
  /// In zh, this message translates to:
  /// **'使用 workspace 记忆（当前为词法检索）'**
  String get settingsWorkspaceMemoryLexical;

  /// No description provided for @settingsMcpToolsTitle.
  ///
  /// In zh, this message translates to:
  /// **'MCP 工具'**
  String get settingsMcpToolsTitle;

  /// No description provided for @settingsMcpToolsSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'添加、启停和管理远端 MCP 服务'**
  String get settingsMcpToolsSubtitle;

  /// No description provided for @settingsLocalServiceTitle.
  ///
  /// In zh, this message translates to:
  /// **'本机服务'**
  String get settingsLocalServiceTitle;

  /// No description provided for @settingsLocalServiceSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'在局域网内访问小万 MCP 和 webchat 服务'**
  String get settingsLocalServiceSubtitle;

  /// No description provided for @settingsAlpineTitle.
  ///
  /// In zh, this message translates to:
  /// **'终端环境'**
  String get settingsAlpineTitle;

  /// No description provided for @settingsAlpineSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'选择并管理应用内 Alpine 或 Ubuntu 终端系统'**
  String get settingsAlpineSubtitle;

  /// No description provided for @settingsHideRecentsTitle.
  ///
  /// In zh, this message translates to:
  /// **'后台隐藏'**
  String get settingsHideRecentsTitle;

  /// No description provided for @settingsHideRecentsSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'开启后应用将从最近任务列表中隐藏'**
  String get settingsHideRecentsSubtitle;

  /// No description provided for @settingsRecentConversationsOnlyTitle.
  ///
  /// In zh, this message translates to:
  /// **'仅显示近7天会话'**
  String get settingsRecentConversationsOnlyTitle;

  /// No description provided for @settingsRecentConversationsOnlySubtitle.
  ///
  /// In zh, this message translates to:
  /// **'自动归档超过 7 天未更新的会话，减少侧边栏加载卡顿'**
  String get settingsRecentConversationsOnlySubtitle;

  /// No description provided for @settingsAlarmTitle.
  ///
  /// In zh, this message translates to:
  /// **'闹钟设置'**
  String get settingsAlarmTitle;

  /// No description provided for @settingsAlarmSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'配置默认铃声、本地 mp3 或 mp3 直链'**
  String get settingsAlarmSubtitle;

  /// No description provided for @settingsAppearanceTitle.
  ///
  /// In zh, this message translates to:
  /// **'外观设置'**
  String get settingsAppearanceTitle;

  /// No description provided for @settingsAppearanceSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'配置主题模式、语言、共享背景图、聊天字号和文本颜色'**
  String get settingsAppearanceSubtitle;

  /// No description provided for @settingsVibrationTitle.
  ///
  /// In zh, this message translates to:
  /// **'振动反馈'**
  String get settingsVibrationTitle;

  /// No description provided for @settingsVibrationSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'执行任务时，通过振动进行操作提醒'**
  String get settingsVibrationSubtitle;

  /// No description provided for @settingsIndependentSendButtonTitle.
  ///
  /// In zh, this message translates to:
  /// **'使用独立的发送按钮'**
  String get settingsIndependentSendButtonTitle;

  /// No description provided for @settingsIndependentSendButtonSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'开启后，聊天页键盘回车为换行；关闭后，回车直接发送'**
  String get settingsIndependentSendButtonSubtitle;

  /// No description provided for @settingsPredictiveBackTitle.
  ///
  /// In zh, this message translates to:
  /// **'预测性返回手势'**
  String get settingsPredictiveBackTitle;

  /// No description provided for @settingsPredictiveBackSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'开启后，返回手势可跟随手指预览上一页面或桌面；关闭则保持旧版返回行为'**
  String get settingsPredictiveBackSubtitle;

  /// No description provided for @settingsHabitualHandTitle.
  ///
  /// In zh, this message translates to:
  /// **'惯用手'**
  String get settingsHabitualHandTitle;

  /// No description provided for @settingsHabitualHandSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'影响聊天历史记录的侧滑菜单方向'**
  String get settingsHabitualHandSubtitle;

  /// No description provided for @settingsHabitualHandLeft.
  ///
  /// In zh, this message translates to:
  /// **'左手'**
  String get settingsHabitualHandLeft;

  /// No description provided for @settingsHabitualHandRight.
  ///
  /// In zh, this message translates to:
  /// **'右手'**
  String get settingsHabitualHandRight;

  /// No description provided for @settingsAboutTitle.
  ///
  /// In zh, this message translates to:
  /// **'关于小万'**
  String get settingsAboutTitle;

  /// No description provided for @settingsHideRecentsFailed.
  ///
  /// In zh, this message translates to:
  /// **'设置后台隐藏失败'**
  String get settingsHideRecentsFailed;

  /// No description provided for @settingsSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'设置失败'**
  String get settingsSaveFailed;

  /// No description provided for @settingsMcpEnabledToast.
  ///
  /// In zh, this message translates to:
  /// **'MCP 已开启：{endpoint}'**
  String settingsMcpEnabledToast(Object endpoint);

  /// No description provided for @settingsMcpDisabledToast.
  ///
  /// In zh, this message translates to:
  /// **'MCP 已关闭'**
  String get settingsMcpDisabledToast;

  /// No description provided for @settingsMcpToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'MCP 开关失败'**
  String get settingsMcpToggleFailed;

  /// No description provided for @settingsCopiedAddress.
  ///
  /// In zh, this message translates to:
  /// **'已复制访问地址'**
  String get settingsCopiedAddress;

  /// No description provided for @settingsCopiedToken.
  ///
  /// In zh, this message translates to:
  /// **'已复制 Token'**
  String get settingsCopiedToken;

  /// No description provided for @settingsTokenRefreshed.
  ///
  /// In zh, this message translates to:
  /// **'已刷新 Token'**
  String get settingsTokenRefreshed;

  /// No description provided for @settingsTokenRefreshFailed.
  ///
  /// In zh, this message translates to:
  /// **'刷新 Token 失败'**
  String get settingsTokenRefreshFailed;

  /// No description provided for @settingsMcpLocalService.
  ///
  /// In zh, this message translates to:
  /// **'本机服务'**
  String get settingsMcpLocalService;

  /// No description provided for @settingsMcpAddress.
  ///
  /// In zh, this message translates to:
  /// **'地址'**
  String get settingsMcpAddress;

  /// No description provided for @settingsMcpToken.
  ///
  /// In zh, this message translates to:
  /// **'Token'**
  String get settingsMcpToken;

  /// No description provided for @settingsNotGenerated.
  ///
  /// In zh, this message translates to:
  /// **'未生成'**
  String get settingsNotGenerated;

  /// No description provided for @settingsCopyAddress.
  ///
  /// In zh, this message translates to:
  /// **'复制地址'**
  String get settingsCopyAddress;

  /// No description provided for @settingsCopyToken.
  ///
  /// In zh, this message translates to:
  /// **'复制 Token'**
  String get settingsCopyToken;

  /// No description provided for @settingsRefreshToken.
  ///
  /// In zh, this message translates to:
  /// **'刷新 Token'**
  String get settingsRefreshToken;

  /// No description provided for @settingsMcpSecurityNotice.
  ///
  /// In zh, this message translates to:
  /// **'请在同一局域网内使用 Authorization: Bearer <Token> 调用本机 MCP 服务，避免将地址或 Token 暴露到公网。'**
  String get settingsMcpSecurityNotice;

  /// No description provided for @settingsInstalledAppsPermissionFailed.
  ///
  /// In zh, this message translates to:
  /// **'请求应用列表权限失败'**
  String get settingsInstalledAppsPermissionFailed;

  /// No description provided for @appearanceTitle.
  ///
  /// In zh, this message translates to:
  /// **'外观设置'**
  String get appearanceTitle;

  /// No description provided for @appearanceAutoSaving.
  ///
  /// In zh, this message translates to:
  /// **'正在自动保存…'**
  String get appearanceAutoSaving;

  /// No description provided for @appearanceAutosaveHint.
  ///
  /// In zh, this message translates to:
  /// **'更改会自动保存'**
  String get appearanceAutosaveHint;

  /// No description provided for @appearanceBackgroundSource.
  ///
  /// In zh, this message translates to:
  /// **'背景来源'**
  String get appearanceBackgroundSource;

  /// No description provided for @appearancePreview.
  ///
  /// In zh, this message translates to:
  /// **'效果预览'**
  String get appearancePreview;

  /// No description provided for @appearanceAdjustments.
  ///
  /// In zh, this message translates to:
  /// **'效果调整'**
  String get appearanceAdjustments;

  /// No description provided for @appearancePreviewChat.
  ///
  /// In zh, this message translates to:
  /// **'聊天'**
  String get appearancePreviewChat;

  /// No description provided for @appearancePreviewWorkspace.
  ///
  /// In zh, this message translates to:
  /// **'工作区'**
  String get appearancePreviewWorkspace;

  /// No description provided for @appearanceEnableBackground.
  ///
  /// In zh, this message translates to:
  /// **'启用背景图'**
  String get appearanceEnableBackground;

  /// No description provided for @appearanceEnableBackgroundSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'同时作用于聊天页和 Workspace 页面，并自动保存'**
  String get appearanceEnableBackgroundSubtitle;

  /// No description provided for @appearanceSourceLocal.
  ///
  /// In zh, this message translates to:
  /// **'本地图片'**
  String get appearanceSourceLocal;

  /// No description provided for @appearanceSourceRemote.
  ///
  /// In zh, this message translates to:
  /// **'图片直链'**
  String get appearanceSourceRemote;

  /// No description provided for @appearanceNoLocalImage.
  ///
  /// In zh, this message translates to:
  /// **'尚未选择本地图片'**
  String get appearanceNoLocalImage;

  /// No description provided for @appearancePickImage.
  ///
  /// In zh, this message translates to:
  /// **'选择图片'**
  String get appearancePickImage;

  /// No description provided for @appearanceRepickImage.
  ///
  /// In zh, this message translates to:
  /// **'重新选择'**
  String get appearanceRepickImage;

  /// No description provided for @appearanceRemoteImageUrl.
  ///
  /// In zh, this message translates to:
  /// **'图片直链'**
  String get appearanceRemoteImageUrl;

  /// No description provided for @appearanceRemoteImageUrlHint.
  ///
  /// In zh, this message translates to:
  /// **'https://example.com/background.jpg'**
  String get appearanceRemoteImageUrlHint;

  /// No description provided for @appearanceBackgroundBlur.
  ///
  /// In zh, this message translates to:
  /// **'背景柔化'**
  String get appearanceBackgroundBlur;

  /// No description provided for @appearanceBackgroundBlurSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'调节图片上方蒙版的柔化程度'**
  String get appearanceBackgroundBlurSubtitle;

  /// No description provided for @appearanceOverlayIntensity.
  ///
  /// In zh, this message translates to:
  /// **'蒙版强度'**
  String get appearanceOverlayIntensity;

  /// No description provided for @appearanceOverlayIntensitySubtitle.
  ///
  /// In zh, this message translates to:
  /// **'增强统一蒙版，让页面元素更干净'**
  String get appearanceOverlayIntensitySubtitle;

  /// No description provided for @appearanceOverlayBrightness.
  ///
  /// In zh, this message translates to:
  /// **'蒙版明暗'**
  String get appearanceOverlayBrightness;

  /// No description provided for @appearanceOverlayBrightnessSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'提亮或压暗蒙版，不会直接修改原图'**
  String get appearanceOverlayBrightnessSubtitle;

  /// No description provided for @appearanceChatTextSize.
  ///
  /// In zh, this message translates to:
  /// **'聊天文本大小'**
  String get appearanceChatTextSize;

  /// No description provided for @appearanceChatTextSizeSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'仅调整用户消息、AI 回复与思考区字号'**
  String get appearanceChatTextSizeSubtitle;

  /// No description provided for @appearanceTextColorTitle.
  ///
  /// In zh, this message translates to:
  /// **'聊天文本颜色'**
  String get appearanceTextColorTitle;

  /// No description provided for @appearanceTextColorSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'默认会自动跟随背景明暗，也可以改成固定颜色'**
  String get appearanceTextColorSubtitle;

  /// No description provided for @appearanceTextColorAuto.
  ///
  /// In zh, this message translates to:
  /// **'自动'**
  String get appearanceTextColorAuto;

  /// No description provided for @appearanceCustomColorLabel.
  ///
  /// In zh, this message translates to:
  /// **'自定义色号'**
  String get appearanceCustomColorLabel;

  /// No description provided for @appearanceCustomColorHint.
  ///
  /// In zh, this message translates to:
  /// **'#FFFFFF 或 #FF112233'**
  String get appearanceCustomColorHint;

  /// No description provided for @appearancePreviewTip.
  ///
  /// In zh, this message translates to:
  /// **'图片可直接在上方预览里拖动和双指缩放，预览会尽量贴近实际效果。'**
  String get appearancePreviewTip;

  /// No description provided for @appearanceColorWhite.
  ///
  /// In zh, this message translates to:
  /// **'白'**
  String get appearanceColorWhite;

  /// No description provided for @appearanceColorDarkGray.
  ///
  /// In zh, this message translates to:
  /// **'深灰'**
  String get appearanceColorDarkGray;

  /// No description provided for @appearanceColorLightBlue.
  ///
  /// In zh, this message translates to:
  /// **'浅蓝'**
  String get appearanceColorLightBlue;

  /// No description provided for @appearanceColorNavy.
  ///
  /// In zh, this message translates to:
  /// **'藏蓝'**
  String get appearanceColorNavy;

  /// No description provided for @appearanceColorTeal.
  ///
  /// In zh, this message translates to:
  /// **'青绿'**
  String get appearanceColorTeal;

  /// No description provided for @appearanceColorWarmYellow.
  ///
  /// In zh, this message translates to:
  /// **'暖黄'**
  String get appearanceColorWarmYellow;

  /// No description provided for @appearanceInvalidHttpUrl.
  ///
  /// In zh, this message translates to:
  /// **'请输入有效的 http(s) 图片直链'**
  String get appearanceInvalidHttpUrl;

  /// No description provided for @appearanceInvalidHexColor.
  ///
  /// In zh, this message translates to:
  /// **'请输入 #RRGGBB 或 #AARRGGBB'**
  String get appearanceInvalidHexColor;

  /// No description provided for @appearanceInvalidHexColorFormat.
  ///
  /// In zh, this message translates to:
  /// **'色号格式不正确'**
  String get appearanceInvalidHexColorFormat;

  /// No description provided for @appearancePickImageFailed.
  ///
  /// In zh, this message translates to:
  /// **'选择图片失败：{error}'**
  String appearancePickImageFailed(Object error);

  /// No description provided for @appearancePickLocalImageFirst.
  ///
  /// In zh, this message translates to:
  /// **'请先选择本地图片'**
  String get appearancePickLocalImageFirst;

  /// No description provided for @appearanceLocalImageMissing.
  ///
  /// In zh, this message translates to:
  /// **'本地图片不存在，请重新选择'**
  String get appearanceLocalImageMissing;

  /// No description provided for @appearanceAutosaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'自动保存失败：{error}'**
  String appearanceAutosaveFailed(Object error);

  /// No description provided for @chatToolCalling.
  ///
  /// In zh, this message translates to:
  /// **'正在调用工具'**
  String get chatToolCalling;

  /// No description provided for @chatFallbackReply.
  ///
  /// In zh, this message translates to:
  /// **'暂时无法生成回复，请重试。'**
  String get chatFallbackReply;

  /// No description provided for @chatPermissionRequired.
  ///
  /// In zh, this message translates to:
  /// **'执行任务前需要先开启权限'**
  String get chatPermissionRequired;

  /// No description provided for @chatPermissionRequiredWithNames.
  ///
  /// In zh, this message translates to:
  /// **'执行任务前，请先开启：{names}'**
  String chatPermissionRequiredWithNames(Object names);

  /// No description provided for @chatRecentTerminalOutputNotice.
  ///
  /// In zh, this message translates to:
  /// **'[只显示最近的部分终端输出]\n'**
  String get chatRecentTerminalOutputNotice;

  /// No description provided for @chatUserPrefix.
  ///
  /// In zh, this message translates to:
  /// **'用户: {text}\n'**
  String chatUserPrefix(Object text);

  /// No description provided for @permissionOverlay.
  ///
  /// In zh, this message translates to:
  /// **'悬浮窗权限'**
  String get permissionOverlay;

  /// No description provided for @permissionInstalledApps.
  ///
  /// In zh, this message translates to:
  /// **'应用列表读取权限'**
  String get permissionInstalledApps;

  /// No description provided for @permissionPublicStorage.
  ///
  /// In zh, this message translates to:
  /// **'公共文件访问'**
  String get permissionPublicStorage;

  /// No description provided for @browserOverlayTitle.
  ///
  /// In zh, this message translates to:
  /// **'Agent Browser'**
  String get browserOverlayTitle;

  /// No description provided for @browserOverlayClose.
  ///
  /// In zh, this message translates to:
  /// **'关闭浏览器窗口'**
  String get browserOverlayClose;

  /// No description provided for @browserOverlayUnsupported.
  ///
  /// In zh, this message translates to:
  /// **'当前平台暂不支持浏览器工具视图'**
  String get browserOverlayUnsupported;

  /// No description provided for @networkErrorMessage.
  ///
  /// In zh, this message translates to:
  /// **'抱歉，刚刚网络开小差了。再发一次试试？'**
  String get networkErrorMessage;

  /// No description provided for @rateLimitErrorMessage.
  ///
  /// In zh, this message translates to:
  /// **'小万忙不过来了，等会儿再试试吧'**
  String get rateLimitErrorMessage;

  /// No description provided for @chatHistoryArchivedTitle.
  ///
  /// In zh, this message translates to:
  /// **'归档对话'**
  String get chatHistoryArchivedTitle;

  /// No description provided for @chatHistoryTitle.
  ///
  /// In zh, this message translates to:
  /// **'聊天记录'**
  String get chatHistoryTitle;

  /// No description provided for @chatHistoryNoArchived.
  ///
  /// In zh, this message translates to:
  /// **'暂无归档对话'**
  String get chatHistoryNoArchived;

  /// No description provided for @chatHistoryEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无聊天记录'**
  String get chatHistoryEmpty;

  /// No description provided for @chatHistoryArchivedToast.
  ///
  /// In zh, this message translates to:
  /// **'已归档'**
  String get chatHistoryArchivedToast;

  /// No description provided for @chatHistoryUnarchivedToast.
  ///
  /// In zh, this message translates to:
  /// **'已移出归档'**
  String get chatHistoryUnarchivedToast;

  /// No description provided for @chatHistoryArchiveFailed.
  ///
  /// In zh, this message translates to:
  /// **'归档对话失败'**
  String get chatHistoryArchiveFailed;

  /// No description provided for @chatHistoryUnarchiveFailed.
  ///
  /// In zh, this message translates to:
  /// **'移出归档失败'**
  String get chatHistoryUnarchiveFailed;

  /// No description provided for @chatHistoryArchiveHint.
  ///
  /// In zh, this message translates to:
  /// **'左滑对话即可归档'**
  String get chatHistoryArchiveHint;

  /// No description provided for @homeDrawerArchive.
  ///
  /// In zh, this message translates to:
  /// **'归档对话'**
  String get homeDrawerArchive;

  /// No description provided for @homeDrawerNewChat.
  ///
  /// In zh, this message translates to:
  /// **'新对话'**
  String get homeDrawerNewChat;

  /// No description provided for @webchatNoChats.
  ///
  /// In zh, this message translates to:
  /// **'开始一个新的对话吧'**
  String get webchatNoChats;

  /// No description provided for @memoryCenterTitle.
  ///
  /// In zh, this message translates to:
  /// **'记忆中心'**
  String get memoryCenterTitle;

  /// No description provided for @memoryShortTermTitle.
  ///
  /// In zh, this message translates to:
  /// **'短期记忆'**
  String get memoryShortTermTitle;

  /// No description provided for @memoryLongTermTitle.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆'**
  String get memoryLongTermTitle;

  /// No description provided for @memoryNoShortTerm.
  ///
  /// In zh, this message translates to:
  /// **'还没有短期记忆'**
  String get memoryNoShortTerm;

  /// No description provided for @memoryNoShortTermDesc.
  ///
  /// In zh, this message translates to:
  /// **'会话中的过程性信息会沉淀到短期记忆，并在后续整理后转入长期记忆。'**
  String get memoryNoShortTermDesc;

  /// No description provided for @memoryFilteredNoShortTerm.
  ///
  /// In zh, this message translates to:
  /// **'当前筛选下还没有短期记忆'**
  String get memoryFilteredNoShortTerm;

  /// No description provided for @memoryFilteredNoShortTermDesc.
  ///
  /// In zh, this message translates to:
  /// **'稍后再来看看，新的短期记忆会逐步出现。'**
  String get memoryFilteredNoShortTermDesc;

  /// No description provided for @memoryNoLongTerm.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆还未初始化'**
  String get memoryNoLongTerm;

  /// No description provided for @memoryNoLongTermDesc.
  ///
  /// In zh, this message translates to:
  /// **'记忆能力启用后，你的跨会话长期记忆会在这里持续沉淀。'**
  String get memoryNoLongTermDesc;

  /// No description provided for @memoryDeleteConfirmTitle.
  ///
  /// In zh, this message translates to:
  /// **'确定删除吗？'**
  String get memoryDeleteConfirmTitle;

  /// No description provided for @memoryDeleteWarning.
  ///
  /// In zh, this message translates to:
  /// **'删除后该内容将不可找回'**
  String get memoryDeleteWarning;

  /// No description provided for @memoryEditDisabled.
  ///
  /// In zh, this message translates to:
  /// **'短期记忆暂不支持编辑'**
  String get memoryEditDisabled;

  /// No description provided for @memoryDeleteDisabled.
  ///
  /// In zh, this message translates to:
  /// **'短期记忆暂不支持删除'**
  String get memoryDeleteDisabled;

  /// No description provided for @memoryGreeting.
  ///
  /// In zh, this message translates to:
  /// **'你好呀，\n欢迎回来，我们会在这里慢慢整理你的记忆。'**
  String get memoryGreeting;

  /// No description provided for @memorySelectedCount.
  ///
  /// In zh, this message translates to:
  /// **'已选择{n}项'**
  String memorySelectedCount(Object n);

  /// No description provided for @memoryDeselectAll.
  ///
  /// In zh, this message translates to:
  /// **'全不选'**
  String get memoryDeselectAll;

  /// No description provided for @memoryEditTitle.
  ///
  /// In zh, this message translates to:
  /// **'编辑记忆'**
  String get memoryEditTitle;

  /// No description provided for @memoryIdLabel.
  ///
  /// In zh, this message translates to:
  /// **'记忆 ID'**
  String get memoryIdLabel;

  /// No description provided for @memoryMatchScore.
  ///
  /// In zh, this message translates to:
  /// **'匹配度'**
  String get memoryMatchScore;

  /// No description provided for @memoryAdditionalInfo.
  ///
  /// In zh, this message translates to:
  /// **'附加信息'**
  String get memoryAdditionalInfo;

  /// No description provided for @memoryAddLongTerm.
  ///
  /// In zh, this message translates to:
  /// **'新增长期记忆'**
  String get memoryAddLongTerm;

  /// No description provided for @memorySaveToLongTerm.
  ///
  /// In zh, this message translates to:
  /// **'保存到长期记忆'**
  String get memorySaveToLongTerm;

  /// No description provided for @memoryLongTermAdded.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆已新增'**
  String get memoryLongTermAdded;

  /// No description provided for @memoryEditLongTerm.
  ///
  /// In zh, this message translates to:
  /// **'编辑长期记忆'**
  String get memoryEditLongTerm;

  /// No description provided for @memorySaveChanges.
  ///
  /// In zh, this message translates to:
  /// **'保存修改'**
  String get memorySaveChanges;

  /// No description provided for @memoryDeleteLongTermConfirm.
  ///
  /// In zh, this message translates to:
  /// **'删除这条长期记忆？'**
  String get memoryDeleteLongTermConfirm;

  /// No description provided for @memoryLongTermDeleted.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆已删除'**
  String get memoryLongTermDeleted;

  /// No description provided for @memoryLongTermFailed.
  ///
  /// In zh, this message translates to:
  /// **'长期记忆操作失败：{error}'**
  String memoryLongTermFailed(Object error);

  /// No description provided for @memoryNoMemories.
  ///
  /// In zh, this message translates to:
  /// **'暂无记忆'**
  String get memoryNoMemories;

  /// No description provided for @memoryNoMemoriesDesc.
  ///
  /// In zh, this message translates to:
  /// **'快去探索，添加喜欢的内容吧'**
  String get memoryNoMemoriesDesc;

  /// No description provided for @pluginMarketTitle.
  ///
  /// In zh, this message translates to:
  /// **'插件市场'**
  String get pluginMarketTitle;

  /// No description provided for @pluginMarketEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无可用插件'**
  String get pluginMarketEmpty;

  /// No description provided for @pluginMarketEmptyDesc.
  ///
  /// In zh, this message translates to:
  /// **'官方插件接入后会显示在这里'**
  String get pluginMarketEmptyDesc;

  /// No description provided for @pluginInstall.
  ///
  /// In zh, this message translates to:
  /// **'安装'**
  String get pluginInstall;

  /// No description provided for @pluginUpdate.
  ///
  /// In zh, this message translates to:
  /// **'更新'**
  String get pluginUpdate;

  /// No description provided for @pluginUninstall.
  ///
  /// In zh, this message translates to:
  /// **'卸载'**
  String get pluginUninstall;

  /// No description provided for @pluginCancel.
  ///
  /// In zh, this message translates to:
  /// **'取消'**
  String get pluginCancel;

  /// No description provided for @pluginNoDescription.
  ///
  /// In zh, this message translates to:
  /// **'暂无描述'**
  String get pluginNoDescription;

  /// No description provided for @pluginIncompatible.
  ///
  /// In zh, this message translates to:
  /// **'此插件与当前版本不兼容'**
  String get pluginIncompatible;

  /// No description provided for @pluginLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载插件市场失败'**
  String get pluginLoadFailed;

  /// No description provided for @pluginInstallFailed.
  ///
  /// In zh, this message translates to:
  /// **'插件安装失败'**
  String get pluginInstallFailed;

  /// No description provided for @pluginUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'插件更新失败'**
  String get pluginUpdateFailed;

  /// No description provided for @pluginToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'插件启停失败'**
  String get pluginToggleFailed;

  /// No description provided for @pluginUninstallFailed.
  ///
  /// In zh, this message translates to:
  /// **'插件卸载失败'**
  String get pluginUninstallFailed;

  /// No description provided for @pluginUninstallTitle.
  ///
  /// In zh, this message translates to:
  /// **'卸载插件'**
  String get pluginUninstallTitle;

  /// No description provided for @pluginUninstallConfirmMsg.
  ///
  /// In zh, this message translates to:
  /// **'确认卸载\"{name}\"？'**
  String pluginUninstallConfirmMsg(Object name);

  /// No description provided for @pluginInstalledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已安装 {name}'**
  String pluginInstalledMsg(Object name);

  /// No description provided for @pluginUpdatedMsg.
  ///
  /// In zh, this message translates to:
  /// **'已更新 {name}'**
  String pluginUpdatedMsg(Object name);

  /// No description provided for @pluginEnabledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已启用 {name}'**
  String pluginEnabledMsg(Object name);

  /// No description provided for @pluginDisabledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已停用 {name}'**
  String pluginDisabledMsg(Object name);

  /// No description provided for @pluginUninstalledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已卸载 {name}'**
  String pluginUninstalledMsg(Object name);

  /// No description provided for @pluginKindBundledModule.
  ///
  /// In zh, this message translates to:
  /// **'内置模块'**
  String get pluginKindBundledModule;

  /// No description provided for @pluginKindRuntimeBundle.
  ///
  /// In zh, this message translates to:
  /// **'运行时包'**
  String get pluginKindRuntimeBundle;

  /// No description provided for @pluginKindCompanionApp.
  ///
  /// In zh, this message translates to:
  /// **'伴生应用'**
  String get pluginKindCompanionApp;

  /// No description provided for @pluginDetailTitle.
  ///
  /// In zh, this message translates to:
  /// **'插件详情'**
  String get pluginDetailTitle;

  /// No description provided for @pluginSearchHint.
  ///
  /// In zh, this message translates to:
  /// **'搜索插件名称、描述或能力'**
  String get pluginSearchHint;

  /// No description provided for @pluginSearchEmpty.
  ///
  /// In zh, this message translates to:
  /// **'未找到匹配的插件'**
  String get pluginSearchEmpty;

  /// No description provided for @pluginAboutTitle.
  ///
  /// In zh, this message translates to:
  /// **'关于'**
  String get pluginAboutTitle;

  /// No description provided for @pluginCapabilitiesTitle.
  ///
  /// In zh, this message translates to:
  /// **'能力'**
  String get pluginCapabilitiesTitle;

  /// No description provided for @pluginNoCapabilities.
  ///
  /// In zh, this message translates to:
  /// **'此插件未声明额外能力'**
  String get pluginNoCapabilities;

  /// No description provided for @pluginInformationTitle.
  ///
  /// In zh, this message translates to:
  /// **'信息'**
  String get pluginInformationTitle;

  /// No description provided for @pluginPublisherLabel.
  ///
  /// In zh, this message translates to:
  /// **'开发者'**
  String get pluginPublisherLabel;

  /// No description provided for @pluginVersionLabel.
  ///
  /// In zh, this message translates to:
  /// **'版本'**
  String get pluginVersionLabel;

  /// No description provided for @pluginTypeLabel.
  ///
  /// In zh, this message translates to:
  /// **'类型'**
  String get pluginTypeLabel;

  /// No description provided for @pluginDownloadSizeLabel.
  ///
  /// In zh, this message translates to:
  /// **'下载大小'**
  String get pluginDownloadSizeLabel;

  /// No description provided for @pluginInterfaceVersionLabel.
  ///
  /// In zh, this message translates to:
  /// **'接口版本'**
  String get pluginInterfaceVersionLabel;

  /// No description provided for @pluginStatusInstalled.
  ///
  /// In zh, this message translates to:
  /// **'已安装'**
  String get pluginStatusInstalled;

  /// No description provided for @pluginStatusEnabled.
  ///
  /// In zh, this message translates to:
  /// **'已启用'**
  String get pluginStatusEnabled;

  /// No description provided for @pluginStatusNotInstalled.
  ///
  /// In zh, this message translates to:
  /// **'未安装'**
  String get pluginStatusNotInstalled;

  /// No description provided for @pluginEnableTitle.
  ///
  /// In zh, this message translates to:
  /// **'启用插件'**
  String get pluginEnableTitle;

  /// No description provided for @pluginEnableDescription.
  ///
  /// In zh, this message translates to:
  /// **'允许 Agent 使用此插件提供的能力'**
  String get pluginEnableDescription;

  /// No description provided for @pluginRetry.
  ///
  /// In zh, this message translates to:
  /// **'重试'**
  String get pluginRetry;

  /// No description provided for @skillStoreTitle.
  ///
  /// In zh, this message translates to:
  /// **'技能仓库'**
  String get skillStoreTitle;

  /// No description provided for @skillBuiltin.
  ///
  /// In zh, this message translates to:
  /// **'内置'**
  String get skillBuiltin;

  /// No description provided for @skillOfficial.
  ///
  /// In zh, this message translates to:
  /// **'官方'**
  String get skillOfficial;

  /// No description provided for @skillUser.
  ///
  /// In zh, this message translates to:
  /// **'用户'**
  String get skillUser;

  /// No description provided for @skillInstalled.
  ///
  /// In zh, this message translates to:
  /// **'已安装'**
  String get skillInstalled;

  /// No description provided for @skillNotInstalled.
  ///
  /// In zh, this message translates to:
  /// **'未安装'**
  String get skillNotInstalled;

  /// No description provided for @skillEnabled.
  ///
  /// In zh, this message translates to:
  /// **'启用中'**
  String get skillEnabled;

  /// No description provided for @skillDisabled.
  ///
  /// In zh, this message translates to:
  /// **'已禁用'**
  String get skillDisabled;

  /// No description provided for @skillInstall.
  ///
  /// In zh, this message translates to:
  /// **'安装'**
  String get skillInstall;

  /// No description provided for @skillDelete.
  ///
  /// In zh, this message translates to:
  /// **'删除'**
  String get skillDelete;

  /// No description provided for @skillEmpty.
  ///
  /// In zh, this message translates to:
  /// **'暂无已接入的技能'**
  String get skillEmpty;

  /// No description provided for @skillNoDescription.
  ///
  /// In zh, this message translates to:
  /// **'暂无描述'**
  String get skillNoDescription;

  /// No description provided for @skillBuiltinRemovedDesc.
  ///
  /// In zh, this message translates to:
  /// **'该内置技能已从工作区移除，可随时重新安装。'**
  String get skillBuiltinRemovedDesc;

  /// No description provided for @skillDeleteTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除技能'**
  String get skillDeleteTitle;

  /// No description provided for @skillDeleteConfirmMsg.
  ///
  /// In zh, this message translates to:
  /// **'确认删除\"{name}\"？'**
  String skillDeleteConfirmMsg(Object name);

  /// No description provided for @skillDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除'**
  String get skillDeleted;

  /// No description provided for @skillDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除失败'**
  String get skillDeleteFailed;

  /// No description provided for @skillInstalledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已安装 {name}'**
  String skillInstalledMsg(Object name);

  /// No description provided for @skillInstallFailed.
  ///
  /// In zh, this message translates to:
  /// **'安装失败'**
  String get skillInstallFailed;

  /// No description provided for @skillEnabledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已启用 {name}'**
  String skillEnabledMsg(Object name);

  /// No description provided for @skillDisabledMsg.
  ///
  /// In zh, this message translates to:
  /// **'已禁用 {name}'**
  String skillDisabledMsg(Object name);

  /// No description provided for @skillToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'切换失败'**
  String get skillToggleFailed;

  /// No description provided for @skillSyncOfficialTooltip.
  ///
  /// In zh, this message translates to:
  /// **'安装/更新官方 Skills'**
  String get skillSyncOfficialTooltip;

  /// No description provided for @skillSyncOfficialSuccess.
  ///
  /// In zh, this message translates to:
  /// **'官方 Skills 已同步（{count} 个）'**
  String skillSyncOfficialSuccess(Object count);

  /// No description provided for @skillSyncOfficialFailed.
  ///
  /// In zh, this message translates to:
  /// **'同步官方 Skills 失败'**
  String get skillSyncOfficialFailed;

  /// No description provided for @skillLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载技能仓库失败'**
  String get skillLoadFailed;

  /// No description provided for @modelProviderConfigTitle.
  ///
  /// In zh, this message translates to:
  /// **'Provider 配置'**
  String get modelProviderConfigTitle;

  /// No description provided for @modelProviderConfigDesc.
  ///
  /// In zh, this message translates to:
  /// **'新增、切换并维护模型服务提供商的名称、地址与密钥。'**
  String get modelProviderConfigDesc;

  /// No description provided for @modelProviderName.
  ///
  /// In zh, this message translates to:
  /// **'Provider 名称'**
  String get modelProviderName;

  /// No description provided for @modelProviderNameHint.
  ///
  /// In zh, this message translates to:
  /// **'例如：DeepSeek'**
  String get modelProviderNameHint;

  /// No description provided for @modelProviderBaseUrlHint.
  ///
  /// In zh, this message translates to:
  /// **'末尾加 # 可禁用自动补全请求路径'**
  String get modelProviderBaseUrlHint;

  /// No description provided for @modelProviderApiKeyHint.
  ///
  /// In zh, this message translates to:
  /// **'未填写 API Key 时，会以无鉴权方式请求 Provider。'**
  String get modelProviderApiKeyHint;

  /// No description provided for @modelListTitle.
  ///
  /// In zh, this message translates to:
  /// **'模型列表'**
  String get modelListTitle;

  /// No description provided for @modelListDesc.
  ///
  /// In zh, this message translates to:
  /// **'支持手动补充模型，也可从当前 Provider 拉取远端模型清单。'**
  String get modelListDesc;

  /// No description provided for @modelListCount.
  ///
  /// In zh, this message translates to:
  /// **'共 {count} 个模型'**
  String modelListCount(Object count);

  /// No description provided for @modelAddPrompt.
  ///
  /// In zh, this message translates to:
  /// **'请添加模型！'**
  String get modelAddPrompt;

  /// No description provided for @modelBuiltinProvider.
  ///
  /// In zh, this message translates to:
  /// **'内置 Provider'**
  String get modelBuiltinProvider;

  /// No description provided for @modelIdEmpty.
  ///
  /// In zh, this message translates to:
  /// **'模型 ID 不能为空且不能以 scene. 开头'**
  String get modelIdEmpty;

  /// No description provided for @modelAlreadyExists.
  ///
  /// In zh, this message translates to:
  /// **'模型已存在'**
  String get modelAlreadyExists;

  /// No description provided for @modelAdded.
  ///
  /// In zh, this message translates to:
  /// **'已添加模型'**
  String get modelAdded;

  /// No description provided for @modelDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除模型'**
  String get modelDeleted;

  /// No description provided for @modelDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除模型失败'**
  String get modelDeleteFailed;

  /// No description provided for @modelIdHint.
  ///
  /// In zh, this message translates to:
  /// **'请输入模型 ID'**
  String get modelIdHint;

  /// No description provided for @modelAddProviderTitle.
  ///
  /// In zh, this message translates to:
  /// **'新增 Provider'**
  String get modelAddProviderTitle;

  /// No description provided for @modelAddButton.
  ///
  /// In zh, this message translates to:
  /// **'新增'**
  String get modelAddButton;

  /// No description provided for @modelProviderAdded.
  ///
  /// In zh, this message translates to:
  /// **'已新增 Provider'**
  String get modelProviderAdded;

  /// No description provided for @modelProviderAddFailed.
  ///
  /// In zh, this message translates to:
  /// **'新增 Provider 失败：{error}'**
  String modelProviderAddFailed(Object error);

  /// No description provided for @modelDeleteProviderTitle.
  ///
  /// In zh, this message translates to:
  /// **'删除 Provider'**
  String get modelDeleteProviderTitle;

  /// No description provided for @modelDeleteProviderMsg.
  ///
  /// In zh, this message translates to:
  /// **'确定删除\"{name}\"吗？场景绑定会保留，但需要重新选择可用 Provider。'**
  String modelDeleteProviderMsg(Object name);

  /// No description provided for @modelProviderDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除 Provider'**
  String get modelProviderDeleted;

  /// No description provided for @modelProviderDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除 Provider 失败：{error}'**
  String modelProviderDeleteFailed(Object error);

  /// No description provided for @modelProviderLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载模型提供商配置失败'**
  String get modelProviderLoadFailed;

  /// No description provided for @modelProviderSwitchFailed.
  ///
  /// In zh, this message translates to:
  /// **'切换 Provider 失败：{error}'**
  String modelProviderSwitchFailed(Object error);

  /// No description provided for @modelProviderBaseUrlRequired.
  ///
  /// In zh, this message translates to:
  /// **'请先填写 Base URL'**
  String get modelProviderBaseUrlRequired;

  /// No description provided for @modelProviderInvalidBaseUrl.
  ///
  /// In zh, this message translates to:
  /// **'请输入有效的 http(s) Base URL'**
  String get modelProviderInvalidBaseUrl;

  /// No description provided for @modelProviderFetchedModels.
  ///
  /// In zh, this message translates to:
  /// **'已获取 {count} 个模型'**
  String modelProviderFetchedModels(Object count);

  /// No description provided for @modelProviderFetchFailed.
  ///
  /// In zh, this message translates to:
  /// **'拉取模型列表失败：{error}'**
  String modelProviderFetchFailed(Object error);

  /// No description provided for @sceneModelMapping.
  ///
  /// In zh, this message translates to:
  /// **'场景映射'**
  String get sceneModelMapping;

  /// No description provided for @sceneModelMappingDesc.
  ///
  /// In zh, this message translates to:
  /// **'按场景绑定 Provider 与模型，未绑定的场景会继续使用默认模型。'**
  String get sceneModelMappingDesc;

  /// No description provided for @sceneModelRefreshList.
  ///
  /// In zh, this message translates to:
  /// **'刷新模型列表'**
  String get sceneModelRefreshList;

  /// No description provided for @sceneModelSearchHint.
  ///
  /// In zh, this message translates to:
  /// **'点击右侧按钮后，可按 Provider 搜索、折叠并选择模型；顶部搜索框固定不随列表滚动。'**
  String get sceneModelSearchHint;

  /// No description provided for @sceneModelNoScenes.
  ///
  /// In zh, this message translates to:
  /// **'暂无可配置场景'**
  String get sceneModelNoScenes;

  /// No description provided for @sceneModelLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载场景模型配置失败'**
  String get sceneModelLoadFailed;

  /// No description provided for @sceneModelPartialUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'部分模型已更新，但这些 Provider 刷新失败：{profiles}'**
  String sceneModelPartialUpdateFailed(Object profiles);

  /// No description provided for @sceneModelUpdatedModels.
  ///
  /// In zh, this message translates to:
  /// **'已更新 {count} 个模型'**
  String sceneModelUpdatedModels(Object count);

  /// No description provided for @sceneModelRefreshFailed.
  ///
  /// In zh, this message translates to:
  /// **'刷新模型列表失败：{error}'**
  String sceneModelRefreshFailed(Object error);

  /// No description provided for @sceneModelInvalidModelId.
  ///
  /// In zh, this message translates to:
  /// **'模型 ID 不能以 scene. 开头'**
  String get sceneModelInvalidModelId;

  /// No description provided for @sceneModelBoundToast.
  ///
  /// In zh, this message translates to:
  /// **'已将 {scene} 绑定到 {model}'**
  String sceneModelBoundToast(Object scene, Object model);

  /// No description provided for @sceneModelSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存 {scene} 配置失败：{error}'**
  String sceneModelSaveFailed(Object scene, Object error);

  /// No description provided for @sceneModelBindingCleared.
  ///
  /// In zh, this message translates to:
  /// **'已清除 {scene} 的绑定'**
  String sceneModelBindingCleared(Object scene);

  /// No description provided for @sceneModelDefaultRestored.
  ///
  /// In zh, this message translates to:
  /// **'{scene} 已恢复为默认模型'**
  String sceneModelDefaultRestored(Object scene);

  /// No description provided for @sceneModelClearFailed.
  ///
  /// In zh, this message translates to:
  /// **'清除 {scene} 配置失败：{error}'**
  String sceneModelClearFailed(Object scene, Object error);

  /// No description provided for @modelsNoAvailableModels.
  ///
  /// In zh, this message translates to:
  /// **'暂无可用模型'**
  String get modelsNoAvailableModels;

  /// No description provided for @alarmSaved.
  ///
  /// In zh, this message translates to:
  /// **'闹钟设置已保存'**
  String get alarmSaved;

  /// No description provided for @alarmRingtoneSource.
  ///
  /// In zh, this message translates to:
  /// **'铃声来源'**
  String get alarmRingtoneSource;

  /// No description provided for @alarmSystemDefault.
  ///
  /// In zh, this message translates to:
  /// **'系统默认铃声'**
  String get alarmSystemDefault;

  /// No description provided for @alarmSystemDefaultDesc.
  ///
  /// In zh, this message translates to:
  /// **'无需额外配置，兼容性最好'**
  String get alarmSystemDefaultDesc;

  /// No description provided for @alarmLocalMp3.
  ///
  /// In zh, this message translates to:
  /// **'本地 mp3'**
  String get alarmLocalMp3;

  /// No description provided for @alarmLocalMp3Desc.
  ///
  /// In zh, this message translates to:
  /// **'选择手机内 mp3 作为闹钟铃声'**
  String get alarmLocalMp3Desc;

  /// No description provided for @alarmMp3Url.
  ///
  /// In zh, this message translates to:
  /// **'mp3 直链'**
  String get alarmMp3Url;

  /// No description provided for @alarmMp3UrlDesc.
  ///
  /// In zh, this message translates to:
  /// **'使用 http(s) 直链播放在线 mp3'**
  String get alarmMp3UrlDesc;

  /// No description provided for @alarmAudioPermissionDenied.
  ///
  /// In zh, this message translates to:
  /// **'读取音频权限未授予'**
  String get alarmAudioPermissionDenied;

  /// No description provided for @alarmInvalidFilePath.
  ///
  /// In zh, this message translates to:
  /// **'文件路径无效，请重新选择'**
  String get alarmInvalidFilePath;

  /// No description provided for @alarmSelectLocalFirst.
  ///
  /// In zh, this message translates to:
  /// **'请先选择本地 mp3 文件'**
  String get alarmSelectLocalFirst;

  /// No description provided for @alarmEnterHttpsUrl.
  ///
  /// In zh, this message translates to:
  /// **'请输入 http(s) 开头的 mp3 直链'**
  String get alarmEnterHttpsUrl;

  /// No description provided for @alarmLocalFile.
  ///
  /// In zh, this message translates to:
  /// **'本地文件'**
  String get alarmLocalFile;

  /// No description provided for @alarmSelectMp3.
  ///
  /// In zh, this message translates to:
  /// **'选择 mp3 文件'**
  String get alarmSelectMp3;

  /// No description provided for @authorizePageTitle.
  ///
  /// In zh, this message translates to:
  /// **'应用权限授权'**
  String get authorizePageTitle;

  /// No description provided for @authorizeReceiveNotifications.
  ///
  /// In zh, this message translates to:
  /// **'接收消息通知'**
  String get authorizeReceiveNotifications;

  /// No description provided for @authorizeNotificationsDesc.
  ///
  /// In zh, this message translates to:
  /// **'打开后可以及时了解任务进展'**
  String get authorizeNotificationsDesc;

  /// No description provided for @storageUsageTitle.
  ///
  /// In zh, this message translates to:
  /// **'存储占用'**
  String get storageUsageTitle;

  /// No description provided for @storageUsageSubtitle.
  ///
  /// In zh, this message translates to:
  /// **'查看空间占用明细，支持分项清理'**
  String get storageUsageSubtitle;

  /// No description provided for @storageAnalyzeFailed.
  ///
  /// In zh, this message translates to:
  /// **'存储分析失败，请重试'**
  String get storageAnalyzeFailed;

  /// No description provided for @storageCategoryCleaned.
  ///
  /// In zh, this message translates to:
  /// **'已清理{name}，释放 {size}'**
  String storageCategoryCleaned(Object name, Object size);

  /// No description provided for @storageCleanFailed.
  ///
  /// In zh, this message translates to:
  /// **'清理失败，请稍后重试'**
  String get storageCleanFailed;

  /// No description provided for @storageCleanCategory.
  ///
  /// In zh, this message translates to:
  /// **'清理{name}'**
  String storageCleanCategory(Object name);

  /// No description provided for @storageCleanConfirmMsg.
  ///
  /// In zh, this message translates to:
  /// **'确认清理该分类数据吗？'**
  String get storageCleanConfirmMsg;

  /// No description provided for @storageCleanScope.
  ///
  /// In zh, this message translates to:
  /// **'清理范围'**
  String get storageCleanScope;

  /// No description provided for @storageCleanAll.
  ///
  /// In zh, this message translates to:
  /// **'全部'**
  String get storageCleanAll;

  /// No description provided for @storageClean7Days.
  ///
  /// In zh, this message translates to:
  /// **'7天前'**
  String get storageClean7Days;

  /// No description provided for @storageClean30Days.
  ///
  /// In zh, this message translates to:
  /// **'30天前'**
  String get storageClean30Days;

  /// No description provided for @storageStrategyName.
  ///
  /// In zh, this message translates to:
  /// **'执行策略：{name}'**
  String storageStrategyName(Object name);

  /// No description provided for @storageStrategyDone.
  ///
  /// In zh, this message translates to:
  /// **'策略执行完成，释放 {size}'**
  String storageStrategyDone(Object size);

  /// No description provided for @storageStrategyPartialDone.
  ///
  /// In zh, this message translates to:
  /// **'策略完成，释放 {size}，{count} 项未完全成功'**
  String storageStrategyPartialDone(Object count, Object size);

  /// No description provided for @storageStrategyFailed.
  ///
  /// In zh, this message translates to:
  /// **'策略执行失败，请稍后重试'**
  String get storageStrategyFailed;

  /// No description provided for @storageLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载失败'**
  String get storageLoadFailed;

  /// No description provided for @storageReanalyze.
  ///
  /// In zh, this message translates to:
  /// **'重新分析'**
  String get storageReanalyze;

  /// No description provided for @storageTotalUsage.
  ///
  /// In zh, this message translates to:
  /// **'总占用'**
  String get storageTotalUsage;

  /// No description provided for @storageAppSize.
  ///
  /// In zh, this message translates to:
  /// **'应用大小'**
  String get storageAppSize;

  /// No description provided for @storageUserData.
  ///
  /// In zh, this message translates to:
  /// **'用户数据'**
  String get storageUserData;

  /// No description provided for @storageCleanable.
  ///
  /// In zh, this message translates to:
  /// **'可清理'**
  String get storageCleanable;

  /// No description provided for @storageStatsSource.
  ///
  /// In zh, this message translates to:
  /// **'统计口径：{source}'**
  String storageStatsSource(Object source);

  /// No description provided for @storagePackageName.
  ///
  /// In zh, this message translates to:
  /// **'当前包名：{name}'**
  String storagePackageName(Object name);

  /// No description provided for @storageTrendFirst.
  ///
  /// In zh, this message translates to:
  /// **'这是首次分析，后续将展示占用变化趋势'**
  String get storageTrendFirst;

  /// No description provided for @storageSmartCleanup.
  ///
  /// In zh, this message translates to:
  /// **'智能清理策略'**
  String get storageSmartCleanup;

  /// No description provided for @storageExecute.
  ///
  /// In zh, this message translates to:
  /// **'执行'**
  String get storageExecute;

  /// No description provided for @storageUsageAnalysis.
  ///
  /// In zh, this message translates to:
  /// **'占用分析'**
  String get storageUsageAnalysis;

  /// No description provided for @storageClean.
  ///
  /// In zh, this message translates to:
  /// **'清理'**
  String get storageClean;

  /// No description provided for @storageRiskLow.
  ///
  /// In zh, this message translates to:
  /// **'低风险'**
  String get storageRiskLow;

  /// No description provided for @storageRiskCaution.
  ///
  /// In zh, this message translates to:
  /// **'谨慎'**
  String get storageRiskCaution;

  /// No description provided for @storageRiskHigh.
  ///
  /// In zh, this message translates to:
  /// **'高风险'**
  String get storageRiskHigh;

  /// No description provided for @storageReadOnly.
  ///
  /// In zh, this message translates to:
  /// **'只读'**
  String get storageReadOnly;

  /// No description provided for @storageSystemStats.
  ///
  /// In zh, this message translates to:
  /// **'系统统计（与系统设置更接近）'**
  String get storageSystemStats;

  /// No description provided for @storageDirectoryScan.
  ///
  /// In zh, this message translates to:
  /// **'目录扫描估算'**
  String get storageDirectoryScan;

  /// No description provided for @storageAdditionalInfo.
  ///
  /// In zh, this message translates to:
  /// **'附加信息'**
  String get storageAdditionalInfo;

  /// No description provided for @storageCatAppBinary.
  ///
  /// In zh, this message translates to:
  /// **'应用安装包'**
  String get storageCatAppBinary;

  /// No description provided for @storageCatAppBinaryDesc.
  ///
  /// In zh, this message translates to:
  /// **'应用安装文件占用（APK/AAB split）'**
  String get storageCatAppBinaryDesc;

  /// No description provided for @storageCatCache.
  ///
  /// In zh, this message translates to:
  /// **'缓存'**
  String get storageCatCache;

  /// No description provided for @storageCatCacheDesc.
  ///
  /// In zh, this message translates to:
  /// **'临时文件与图片缓存，可安全清理'**
  String get storageCatCacheDesc;

  /// No description provided for @storageCatCacheHint.
  ///
  /// In zh, this message translates to:
  /// **'清理后会在使用中自动重新生成'**
  String get storageCatCacheHint;

  /// No description provided for @storageCatConversation.
  ///
  /// In zh, this message translates to:
  /// **'会话历史'**
  String get storageCatConversation;

  /// No description provided for @storageCatConversationDesc.
  ///
  /// In zh, this message translates to:
  /// **'对话与工具执行历史（估算）'**
  String get storageCatConversationDesc;

  /// No description provided for @storageCatConversationHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除历史消息记录，且不可恢复'**
  String get storageCatConversationHint;

  /// No description provided for @storageCatDatabaseOther.
  ///
  /// In zh, this message translates to:
  /// **'数据库其他占用'**
  String get storageCatDatabaseOther;

  /// No description provided for @storageCatDatabaseOtherDesc.
  ///
  /// In zh, this message translates to:
  /// **'索引与系统表等数据库占用'**
  String get storageCatDatabaseOtherDesc;

  /// No description provided for @storageCatWorkspaceBrowser.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 浏览器产物'**
  String get storageCatWorkspaceBrowser;

  /// No description provided for @storageCatWorkspaceBrowserDesc.
  ///
  /// In zh, this message translates to:
  /// **'浏览器截图、下载文件和中间产物'**
  String get storageCatWorkspaceBrowserDesc;

  /// No description provided for @storageCatWorkspaceBrowserHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除浏览器工具相关的中间文件'**
  String get storageCatWorkspaceBrowserHint;

  /// No description provided for @storageCatWorkspaceOffloads.
  ///
  /// In zh, this message translates to:
  /// **'Workspace Offloads'**
  String get storageCatWorkspaceOffloads;

  /// No description provided for @storageCatWorkspaceOffloadsDesc.
  ///
  /// In zh, this message translates to:
  /// **'工具离线输出与临时文件'**
  String get storageCatWorkspaceOffloadsDesc;

  /// No description provided for @storageCatWorkspaceOffloadsHint.
  ///
  /// In zh, this message translates to:
  /// **'仅删除离线产物，不影响核心功能'**
  String get storageCatWorkspaceOffloadsHint;

  /// No description provided for @storageCatWorkspaceAttachments.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 附件'**
  String get storageCatWorkspaceAttachments;

  /// No description provided for @storageCatWorkspaceAttachmentsDesc.
  ///
  /// In zh, this message translates to:
  /// **'历史任务使用的附件文件'**
  String get storageCatWorkspaceAttachmentsDesc;

  /// No description provided for @storageCatWorkspaceAttachmentsHint.
  ///
  /// In zh, this message translates to:
  /// **'可能影响历史任务对附件的回看'**
  String get storageCatWorkspaceAttachmentsHint;

  /// No description provided for @storageCatWorkspaceShared.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 共享区'**
  String get storageCatWorkspaceShared;

  /// No description provided for @storageCatWorkspaceSharedDesc.
  ///
  /// In zh, this message translates to:
  /// **'跨任务共享的工作区文件'**
  String get storageCatWorkspaceSharedDesc;

  /// No description provided for @storageCatWorkspaceSharedHint.
  ///
  /// In zh, this message translates to:
  /// **'可能影响后续任务复用共享文件'**
  String get storageCatWorkspaceSharedHint;

  /// No description provided for @storageCatWorkspaceMemory.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 记忆数据'**
  String get storageCatWorkspaceMemory;

  /// No description provided for @storageCatWorkspaceMemoryDesc.
  ///
  /// In zh, this message translates to:
  /// **'长期/短期记忆与索引数据'**
  String get storageCatWorkspaceMemoryDesc;

  /// No description provided for @storageCatWorkspaceUserFiles.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 用户文件'**
  String get storageCatWorkspaceUserFiles;

  /// No description provided for @storageCatWorkspaceUserFilesDesc.
  ///
  /// In zh, this message translates to:
  /// **'用户主动保存到 workspace 的文件'**
  String get storageCatWorkspaceUserFilesDesc;

  /// No description provided for @storageCatTerminalLocal.
  ///
  /// In zh, this message translates to:
  /// **'终端运行时（local）'**
  String get storageCatTerminalLocal;

  /// No description provided for @storageCatTerminalLocalDesc.
  ///
  /// In zh, this message translates to:
  /// **'Alpine/Ubuntu 终端 local 运行目录'**
  String get storageCatTerminalLocalDesc;

  /// No description provided for @storageCatTerminalLocalHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除终端 local 目录，需重新初始化'**
  String get storageCatTerminalLocalHint;

  /// No description provided for @storageCatTerminalBootstrap.
  ///
  /// In zh, this message translates to:
  /// **'终端运行时（引导文件）'**
  String get storageCatTerminalBootstrap;

  /// No description provided for @storageCatTerminalBootstrapDesc.
  ///
  /// In zh, this message translates to:
  /// **'proot/lib/rootfs 引导文件'**
  String get storageCatTerminalBootstrapDesc;

  /// No description provided for @storageCatTerminalBootstrapHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除终端引导文件，需重新初始化'**
  String get storageCatTerminalBootstrapHint;

  /// No description provided for @storageCatSharedDrafts.
  ///
  /// In zh, this message translates to:
  /// **'共享草稿'**
  String get storageCatSharedDrafts;

  /// No description provided for @storageCatSharedDraftsDesc.
  ///
  /// In zh, this message translates to:
  /// **'外部分享导入的草稿缓存'**
  String get storageCatSharedDraftsDesc;

  /// No description provided for @storageCatSharedDraftsHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除未发送的草稿附件'**
  String get storageCatSharedDraftsHint;

  /// No description provided for @storageCatMcpInbox.
  ///
  /// In zh, this message translates to:
  /// **'MCP 收件箱'**
  String get storageCatMcpInbox;

  /// No description provided for @storageCatMcpInboxDesc.
  ///
  /// In zh, this message translates to:
  /// **'MCP 文件传输接收目录'**
  String get storageCatMcpInboxDesc;

  /// No description provided for @storageCatMcpInboxHint.
  ///
  /// In zh, this message translates to:
  /// **'会删除 MCP 收件箱中的文件'**
  String get storageCatMcpInboxHint;

  /// No description provided for @storageCatLegacyWorkspace.
  ///
  /// In zh, this message translates to:
  /// **'旧版遗留数据'**
  String get storageCatLegacyWorkspace;

  /// No description provided for @storageCatLegacyWorkspaceDesc.
  ///
  /// In zh, this message translates to:
  /// **'升级后可能残留的旧 workspace 目录'**
  String get storageCatLegacyWorkspaceDesc;

  /// No description provided for @storageCatLegacyWorkspaceHint.
  ///
  /// In zh, this message translates to:
  /// **'建议确认无用后再清理'**
  String get storageCatLegacyWorkspaceHint;

  /// No description provided for @storageCatOtherUserData.
  ///
  /// In zh, this message translates to:
  /// **'其他数据'**
  String get storageCatOtherUserData;

  /// No description provided for @storageCatOtherUserDataDesc.
  ///
  /// In zh, this message translates to:
  /// **'未命中分类规则的数据'**
  String get storageCatOtherUserDataDesc;

  /// No description provided for @storageStrategySafeQuick.
  ///
  /// In zh, this message translates to:
  /// **'安全快速清理'**
  String get storageStrategySafeQuick;

  /// No description provided for @storageStrategySafeQuickDesc.
  ///
  /// In zh, this message translates to:
  /// **'优先清理低风险缓存与临时产物'**
  String get storageStrategySafeQuickDesc;

  /// No description provided for @storageStrategyBalanceDeep.
  ///
  /// In zh, this message translates to:
  /// **'平衡深度清理'**
  String get storageStrategyBalanceDeep;

  /// No description provided for @storageStrategyBalanceDeepDesc.
  ///
  /// In zh, this message translates to:
  /// **'释放更多空间，保留核心用户数据与文件'**
  String get storageStrategyBalanceDeepDesc;

  /// No description provided for @storageStrategyFree1gb.
  ///
  /// In zh, this message translates to:
  /// **'目标释放 1GB'**
  String get storageStrategyFree1gb;

  /// No description provided for @storageStrategyFree1gbDesc.
  ///
  /// In zh, this message translates to:
  /// **'按高收益顺序清理，尽量达到 1GB 释放目标'**
  String get storageStrategyFree1gbDesc;

  /// No description provided for @storageHintConversation.
  ///
  /// In zh, this message translates to:
  /// **'如历史未释放，请重新进入页面执行「重新分析」'**
  String get storageHintConversation;

  /// No description provided for @storageHintTerminal.
  ///
  /// In zh, this message translates to:
  /// **'终端运行时被清理后，可在终端环境页重新初始化'**
  String get storageHintTerminal;

  /// No description provided for @storageHintGeneral.
  ///
  /// In zh, this message translates to:
  /// **'若清理失败，可稍后重试或重启应用后再次清理'**
  String get storageHintGeneral;

  /// No description provided for @storageHintNotCleanable.
  ///
  /// In zh, this message translates to:
  /// **'该分类当前不可清理'**
  String get storageHintNotCleanable;

  /// No description provided for @storageHintSkipped.
  ///
  /// In zh, this message translates to:
  /// **'该分类已跳过（可选项）'**
  String get storageHintSkipped;

  /// No description provided for @storageCleanPartialFailed.
  ///
  /// In zh, this message translates to:
  /// **'部分清理失败：{hint}'**
  String storageCleanPartialFailed(Object hint);

  /// No description provided for @storageCleanPartialFailedGeneric.
  ///
  /// In zh, this message translates to:
  /// **'部分文件清理失败，请稍后重试'**
  String get storageCleanPartialFailedGeneric;

  /// No description provided for @storageTrendVsLast.
  ///
  /// In zh, this message translates to:
  /// **'对比上次分析：总计 {total}，可清理 {cleanable}'**
  String storageTrendVsLast(Object cleanable, Object total);

  /// No description provided for @storageLastAnalyzed.
  ///
  /// In zh, this message translates to:
  /// **'上次分析时间：{time}'**
  String storageLastAnalyzed(Object time);

  /// No description provided for @aboutDescription.
  ///
  /// In zh, this message translates to:
  /// **'小万，是一款以智能对话为核心的手机AI助\n手，通过语义理解与持续学习能力，协助用户\n完成信息处理、决策辅助和日常管理。'**
  String get aboutDescription;

  /// No description provided for @aboutBetaProgramTitle.
  ///
  /// In zh, this message translates to:
  /// **'加入 beta 测试'**
  String get aboutBetaProgramTitle;

  /// No description provided for @aboutBetaProgramDescription.
  ///
  /// In zh, this message translates to:
  /// **'接收更快的四段版更新。'**
  String get aboutBetaProgramDescription;

  /// No description provided for @aboutBetaProgramToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'beta 测试设置更新失败'**
  String get aboutBetaProgramToggleFailed;

  /// No description provided for @aboutPreferencesSectionTitle.
  ///
  /// In zh, this message translates to:
  /// **'更新与测试'**
  String get aboutPreferencesSectionTitle;

  /// No description provided for @aboutApkSourceTitle.
  ///
  /// In zh, this message translates to:
  /// **'安装包下载源'**
  String get aboutApkSourceTitle;

  /// No description provided for @aboutApkSourceDescription.
  ///
  /// In zh, this message translates to:
  /// **'选择安装更新时使用的下载源。'**
  String get aboutApkSourceDescription;

  /// No description provided for @aboutApkSourceDisclaimer.
  ///
  /// In zh, this message translates to:
  /// **'使用本软件即表示您同意我们的隐私政策，并同意我们通过开源的更新 Worker 收集匿名使用信息，以持续改进软件。因使用本软件产生的任何损失或后果由您自行承担。'**
  String get aboutApkSourceDisclaimer;

  /// No description provided for @aboutApkSourceOptionCnb.
  ///
  /// In zh, this message translates to:
  /// **'Cloudflare R2'**
  String get aboutApkSourceOptionCnb;

  /// No description provided for @aboutApkSourceOptionCnbDescription.
  ///
  /// In zh, this message translates to:
  /// **'通过更新 Worker 分发'**
  String get aboutApkSourceOptionCnbDescription;

  /// No description provided for @aboutApkSourceOptionGithub.
  ///
  /// In zh, this message translates to:
  /// **'GitHub'**
  String get aboutApkSourceOptionGithub;

  /// No description provided for @aboutApkSourceOptionGithubDescription.
  ///
  /// In zh, this message translates to:
  /// **'官方 Release'**
  String get aboutApkSourceOptionGithubDescription;

  /// No description provided for @aboutApkSourceSwitchFailed.
  ///
  /// In zh, this message translates to:
  /// **'安装包下载源切换失败'**
  String get aboutApkSourceSwitchFailed;

  /// No description provided for @aboutUpdateHintDefault.
  ///
  /// In zh, this message translates to:
  /// **'检查更新获取最新版本'**
  String get aboutUpdateHintDefault;

  /// No description provided for @workspaceMemoryLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'加载 workspace 记忆配置失败'**
  String get workspaceMemoryLoadFailed;

  /// No description provided for @agentSoulSaved.
  ///
  /// In zh, this message translates to:
  /// **'Agent 灵魂设置已保存'**
  String get agentSoulSaved;

  /// No description provided for @agentSoulSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'Agent 灵魂设置保存失败'**
  String get agentSoulSaveFailed;

  /// No description provided for @chatPromptSaved.
  ///
  /// In zh, this message translates to:
  /// **'纯聊天系统提示词已保存'**
  String get chatPromptSaved;

  /// No description provided for @chatPromptSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'纯聊天系统提示词保存失败'**
  String get chatPromptSaveFailed;

  /// No description provided for @workspaceMemorySaved.
  ///
  /// In zh, this message translates to:
  /// **'MEMORY.md 已保存'**
  String get workspaceMemorySaved;

  /// No description provided for @workspaceMemorySaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'MEMORY.md 保存失败'**
  String get workspaceMemorySaveFailed;

  /// No description provided for @workspaceEmbeddingToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'记忆嵌入开关更新失败'**
  String get workspaceEmbeddingToggleFailed;

  /// No description provided for @workspaceRollupToggleFailed.
  ///
  /// In zh, this message translates to:
  /// **'夜间整理开关更新失败'**
  String get workspaceRollupToggleFailed;

  /// No description provided for @workspaceRollupDone.
  ///
  /// In zh, this message translates to:
  /// **'整理完成'**
  String get workspaceRollupDone;

  /// No description provided for @workspaceRollupFailed.
  ///
  /// In zh, this message translates to:
  /// **'立即整理失败'**
  String get workspaceRollupFailed;

  /// No description provided for @workspaceNone.
  ///
  /// In zh, this message translates to:
  /// **'暂无'**
  String get workspaceNone;

  /// No description provided for @workspaceMemoryTitle.
  ///
  /// In zh, this message translates to:
  /// **'Workspace 记忆'**
  String get workspaceMemoryTitle;

  /// No description provided for @workspaceMemoryCapability.
  ///
  /// In zh, this message translates to:
  /// **'记忆能力'**
  String get workspaceMemoryCapability;

  /// No description provided for @workspaceEmbeddingReady.
  ///
  /// In zh, this message translates to:
  /// **'已配置，可使用向量检索'**
  String get workspaceEmbeddingReady;

  /// No description provided for @workspaceEmbeddingNotReady.
  ///
  /// In zh, this message translates to:
  /// **'未配置，将自动降级为词法检索'**
  String get workspaceEmbeddingNotReady;

  /// No description provided for @workspaceGoToConfig.
  ///
  /// In zh, this message translates to:
  /// **'去场景模型配置记忆嵌入模型'**
  String get workspaceGoToConfig;

  /// No description provided for @workspaceNightlyRollup.
  ///
  /// In zh, this message translates to:
  /// **'夜间记忆整理（22:00）'**
  String get workspaceNightlyRollup;

  /// No description provided for @workspaceLastRun.
  ///
  /// In zh, this message translates to:
  /// **'最近运行：{time}'**
  String workspaceLastRun(Object time);

  /// No description provided for @workspaceNextRun.
  ///
  /// In zh, this message translates to:
  /// **'下次运行：{time}'**
  String workspaceNextRun(Object time);

  /// No description provided for @workspaceRollupNow.
  ///
  /// In zh, this message translates to:
  /// **'立即整理一次'**
  String get workspaceRollupNow;

  /// No description provided for @workspaceSettingsAndMemory.
  ///
  /// In zh, this message translates to:
  /// **'Agent 设置与记忆'**
  String get workspaceSettingsAndMemory;

  /// No description provided for @agentSoulSetting.
  ///
  /// In zh, this message translates to:
  /// **'Agent 灵魂'**
  String get agentSoulSetting;

  /// No description provided for @chatPromptSetting.
  ///
  /// In zh, this message translates to:
  /// **'纯聊天系统提示词'**
  String get chatPromptSetting;

  /// No description provided for @workspaceMemoryMd.
  ///
  /// In zh, this message translates to:
  /// **'MEMORY.md（长期记忆）'**
  String get workspaceMemoryMd;

  /// No description provided for @alpineNodeJs.
  ///
  /// In zh, this message translates to:
  /// **'Node.js 运行时'**
  String get alpineNodeJs;

  /// No description provided for @alpineNpm.
  ///
  /// In zh, this message translates to:
  /// **'Node.js 包管理器'**
  String get alpineNpm;

  /// No description provided for @alpineGit.
  ///
  /// In zh, this message translates to:
  /// **'Git 版本控制'**
  String get alpineGit;

  /// No description provided for @alpinePython.
  ///
  /// In zh, this message translates to:
  /// **'Python 解释器'**
  String get alpinePython;

  /// No description provided for @alpinePip.
  ///
  /// In zh, this message translates to:
  /// **'Python 项目与包工具'**
  String get alpinePip;

  /// No description provided for @alpinePipInstall.
  ///
  /// In zh, this message translates to:
  /// **'Python 包安装器'**
  String get alpinePipInstall;

  /// No description provided for @alpineCodex.
  ///
  /// In zh, this message translates to:
  /// **'OpenAI Codex CLI（供 ACP Agent 使用）'**
  String get alpineCodex;

  /// No description provided for @alpineClaudeCode.
  ///
  /// In zh, this message translates to:
  /// **'Anthropic Claude Code CLI（供 ACP Agent 使用）'**
  String get alpineClaudeCode;

  /// No description provided for @alpineOpenCode.
  ///
  /// In zh, this message translates to:
  /// **'OpenCode CLI（内置 ACP 支持）'**
  String get alpineOpenCode;

  /// No description provided for @alpineDeepSeekHarness.
  ///
  /// In zh, this message translates to:
  /// **'DeepSeek Harness（dsh）官方 ACP 运行组件'**
  String get alpineDeepSeekHarness;

  /// No description provided for @alpineSshClient.
  ///
  /// In zh, this message translates to:
  /// **'SSH 客户端'**
  String get alpineSshClient;

  /// No description provided for @alpineSshpass.
  ///
  /// In zh, this message translates to:
  /// **'SSH 密码辅助工具'**
  String get alpineSshpass;

  /// No description provided for @alpineOpenSshServer.
  ///
  /// In zh, this message translates to:
  /// **'OpenSSH 服务器'**
  String get alpineOpenSshServer;

  /// No description provided for @alpineDetectFailed.
  ///
  /// In zh, this message translates to:
  /// **'检测终端环境失败'**
  String get alpineDetectFailed;

  /// No description provided for @alpineBootTasksLoadFailed.
  ///
  /// In zh, this message translates to:
  /// **'读取自启动任务失败'**
  String get alpineBootTasksLoadFailed;

  /// No description provided for @alpineConfigOpenFailed.
  ///
  /// In zh, this message translates to:
  /// **'打开终端环境配置失败'**
  String get alpineConfigOpenFailed;

  /// No description provided for @alpineBootTaskAdded.
  ///
  /// In zh, this message translates to:
  /// **'已新增自启动任务'**
  String get alpineBootTaskAdded;

  /// No description provided for @alpineBootTaskUpdated.
  ///
  /// In zh, this message translates to:
  /// **'已更新自启动任务'**
  String get alpineBootTaskUpdated;

  /// No description provided for @alpineBootTaskSaveFailed.
  ///
  /// In zh, this message translates to:
  /// **'保存自启动任务失败'**
  String get alpineBootTaskSaveFailed;

  /// No description provided for @alpineBootEnabled.
  ///
  /// In zh, this message translates to:
  /// **'已开启应用启动时自启动'**
  String get alpineBootEnabled;

  /// No description provided for @alpineBootDisabled.
  ///
  /// In zh, this message translates to:
  /// **'已关闭自动启动'**
  String get alpineBootDisabled;

  /// No description provided for @alpineBootTaskUpdateFailed.
  ///
  /// In zh, this message translates to:
  /// **'更新任务失败'**
  String get alpineBootTaskUpdateFailed;

  /// No description provided for @alpineDeleteBootTask.
  ///
  /// In zh, this message translates to:
  /// **'删除自启动任务'**
  String get alpineDeleteBootTask;

  /// No description provided for @alpineDeleteBootTaskMsg.
  ///
  /// In zh, this message translates to:
  /// **'确认删除\"{name}\"吗？'**
  String alpineDeleteBootTaskMsg(Object name);

  /// No description provided for @alpineBootTaskDeleted.
  ///
  /// In zh, this message translates to:
  /// **'已删除自启动任务'**
  String get alpineBootTaskDeleted;

  /// No description provided for @alpineBootTaskDeleteFailed.
  ///
  /// In zh, this message translates to:
  /// **'删除任务失败'**
  String get alpineBootTaskDeleteFailed;

  /// No description provided for @alpineCommandSent.
  ///
  /// In zh, this message translates to:
  /// **'启动命令已发送'**
  String get alpineCommandSent;

  /// No description provided for @alpineStartFailed.
  ///
  /// In zh, this message translates to:
  /// **'启动任务失败'**
  String get alpineStartFailed;

  /// No description provided for @alpineDetecting.
  ///
  /// In zh, this message translates to:
  /// **'正在检测环境'**
  String get alpineDetecting;

  /// No description provided for @alpineStartConfig.
  ///
  /// In zh, this message translates to:
  /// **'开始配置（{count} 项）'**
  String alpineStartConfig(Object count);

  /// No description provided for @alpineAllReady.
  ///
  /// In zh, this message translates to:
  /// **'全部已就绪'**
  String get alpineAllReady;

  /// No description provided for @alpineDetectingDesc.
  ///
  /// In zh, this message translates to:
  /// **'正在后台检测当前终端系统内常见开发环境的版本信息。'**
  String get alpineDetectingDesc;

  /// No description provided for @alpineReadyCount.
  ///
  /// In zh, this message translates to:
  /// **'当前终端系统已就绪 {ready}/{total} 项，可直接勾选缺失项并进入 ReTerminal 自动配置。'**
  String alpineReadyCount(Object ready, Object total);

  /// No description provided for @alpineBootTasks.
  ///
  /// In zh, this message translates to:
  /// **'自启动任务'**
  String get alpineBootTasks;

  /// No description provided for @alpineBootTasksDesc.
  ///
  /// In zh, this message translates to:
  /// **'打开 Omnibot 时会在后台检查已启用的任务，并在对应 ReTerminal 会话内启动命令，适合常驻服务。'**
  String get alpineBootTasksDesc;

  /// No description provided for @alpineAddTask.
  ///
  /// In zh, this message translates to:
  /// **'新增任务'**
  String get alpineAddTask;

  /// No description provided for @alpineOpenTerminal.
  ///
  /// In zh, this message translates to:
  /// **'打开终端'**
  String get alpineOpenTerminal;

  /// No description provided for @alpineNoTasksDesc.
  ///
  /// In zh, this message translates to:
  /// **'暂无任务。你可以添加例如 `python app.py`、`node server.js`、`./start.sh` 之类的常驻命令。'**
  String get alpineNoTasksDesc;

  /// No description provided for @alpineBootOnAppOpen.
  ///
  /// In zh, this message translates to:
  /// **'开机打开 app 后启动'**
  String get alpineBootOnAppOpen;

  /// No description provided for @alpineNotEnabled.
  ///
  /// In zh, this message translates to:
  /// **'未启用'**
  String get alpineNotEnabled;

  /// No description provided for @alpineRunning.
  ///
  /// In zh, this message translates to:
  /// **'已在运行'**
  String get alpineRunning;

  /// No description provided for @alpineStartNow.
  ///
  /// In zh, this message translates to:
  /// **'立即启动'**
  String get alpineStartNow;

  /// No description provided for @alpineEdit.
  ///
  /// In zh, this message translates to:
  /// **'编辑'**
  String get alpineEdit;

  /// No description provided for @alpineVersionDetected.
  ///
  /// In zh, this message translates to:
  /// **'已检测到可用版本'**
  String get alpineVersionDetected;

  /// No description provided for @alpineVersionNotFound.
  ///
  /// In zh, this message translates to:
  /// **'未检测到'**
  String get alpineVersionNotFound;

  /// No description provided for @alpineTaskNameHint.
  ///
  /// In zh, this message translates to:
  /// **'请输入任务名称'**
  String get alpineTaskNameHint;

  /// No description provided for @alpineCommandHint.
  ///
  /// In zh, this message translates to:
  /// **'请输入启动命令'**
  String get alpineCommandHint;

  /// No description provided for @alpineEditBootTask.
  ///
  /// In zh, this message translates to:
  /// **'编辑自启动任务'**
  String get alpineEditBootTask;

  /// No description provided for @alpineAddBootTask.
  ///
  /// In zh, this message translates to:
  /// **'新增自启动任务'**
  String get alpineAddBootTask;

  /// No description provided for @alpineTaskName.
  ///
  /// In zh, this message translates to:
  /// **'任务名称'**
  String get alpineTaskName;

  /// No description provided for @alpineTaskNameExample.
  ///
  /// In zh, this message translates to:
  /// **'例如：本地 API 服务'**
  String get alpineTaskNameExample;

  /// No description provided for @alpineStartCommand.
  ///
  /// In zh, this message translates to:
  /// **'启动命令'**
  String get alpineStartCommand;

  /// No description provided for @alpineCommandExample.
  ///
  /// In zh, this message translates to:
  /// **'例如：python app.py 或 pnpm start'**
  String get alpineCommandExample;

  /// No description provided for @alpineWorkDir.
  ///
  /// In zh, this message translates to:
  /// **'工作目录'**
  String get alpineWorkDir;

  /// No description provided for @alpineBootAutoStart.
  ///
  /// In zh, this message translates to:
  /// **'打开小万时自动启动'**
  String get alpineBootAutoStart;

  /// No description provided for @alpineDevEnv.
  ///
  /// In zh, this message translates to:
  /// **'开发环境'**
  String get alpineDevEnv;

  /// No description provided for @alpineAiAgent.
  ///
  /// In zh, this message translates to:
  /// **'AI Agent'**
  String get alpineAiAgent;

  /// No description provided for @alpineEnvConfig.
  ///
  /// In zh, this message translates to:
  /// **'环境配置'**
  String get alpineEnvConfig;

  /// No description provided for @alpineWorkDirValue.
  ///
  /// In zh, this message translates to:
  /// **'工作目录：{dir}'**
  String alpineWorkDirValue(Object dir);

  /// No description provided for @workspaceEmbeddingRetrieval.
  ///
  /// In zh, this message translates to:
  /// **'记忆嵌入检索'**
  String get workspaceEmbeddingRetrieval;

  /// No description provided for @chatHistoryStartConversation.
  ///
  /// In zh, this message translates to:
  /// **'开始对话'**
  String get chatHistoryStartConversation;

  /// No description provided for @homeDrawerSearching.
  ///
  /// In zh, this message translates to:
  /// **'正在搜索对话内容…'**
  String get homeDrawerSearching;

  /// No description provided for @homeDrawerNoResults.
  ///
  /// In zh, this message translates to:
  /// **'没有找到相关对话'**
  String get homeDrawerNoResults;

  /// No description provided for @homeDrawerSearchHint2.
  ///
  /// In zh, this message translates to:
  /// **'试试更短的关键词，或换一种说法'**
  String get homeDrawerSearchHint2;

  /// No description provided for @homeDrawerSearchResults.
  ///
  /// In zh, this message translates to:
  /// **'搜索结果'**
  String get homeDrawerSearchResults;

  /// No description provided for @homeDrawerResultCount.
  ///
  /// In zh, this message translates to:
  /// **'条'**
  String get homeDrawerResultCount;

  /// No description provided for @homeDrawerScheduled.
  ///
  /// In zh, this message translates to:
  /// **'定时'**
  String get homeDrawerScheduled;

  /// No description provided for @homeDrawerScheduledTasks.
  ///
  /// In zh, this message translates to:
  /// **'定时任务'**
  String get homeDrawerScheduledTasks;

  /// No description provided for @homeDrawerPinnedConversations.
  ///
  /// In zh, this message translates to:
  /// **'置顶会话'**
  String get homeDrawerPinnedConversations;

  /// No description provided for @homeDrawerAgentSection.
  ///
  /// In zh, this message translates to:
  /// **'Agent'**
  String get homeDrawerAgentSection;

  /// No description provided for @homeDrawerOmniAiSection.
  ///
  /// In zh, this message translates to:
  /// **'小万'**
  String get homeDrawerOmniAiSection;

  /// No description provided for @homeDrawerChatOnlySection.
  ///
  /// In zh, this message translates to:
  /// **'纯聊天'**
  String get homeDrawerChatOnlySection;

  /// No description provided for @homeDrawerAgentNoProject.
  ///
  /// In zh, this message translates to:
  /// **'其他'**
  String get homeDrawerAgentNoProject;

  /// No description provided for @homeDrawerGreeting.
  ///
  /// In zh, this message translates to:
  /// **'你好！'**
  String get homeDrawerGreeting;

  /// No description provided for @homeDrawerWelcome.
  ///
  /// In zh, this message translates to:
  /// **'欢迎使用小万'**
  String get homeDrawerWelcome;

  /// No description provided for @homeDrawerDawnGreeting.
  ///
  /// In zh, this message translates to:
  /// **'凌晨啦'**
  String get homeDrawerDawnGreeting;

  /// No description provided for @homeDrawerDawnSub.
  ///
  /// In zh, this message translates to:
  /// **'还没休息吗？'**
  String get homeDrawerDawnSub;

  /// No description provided for @homeDrawerDawnGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'天还没亮'**
  String get homeDrawerDawnGreeting2;

  /// No description provided for @homeDrawerDawnSub2.
  ///
  /// In zh, this message translates to:
  /// **'早起的你辛苦啦～'**
  String get homeDrawerDawnSub2;

  /// No description provided for @homeDrawerDawnGreeting3.
  ///
  /// In zh, this message translates to:
  /// **'深夜的时光很静'**
  String get homeDrawerDawnGreeting3;

  /// No description provided for @homeDrawerDawnSub3.
  ///
  /// In zh, this message translates to:
  /// **'但也要记得给身体留些休息呀～'**
  String get homeDrawerDawnSub3;

  /// No description provided for @homeDrawerMorningGreeting.
  ///
  /// In zh, this message translates to:
  /// **'早安！'**
  String get homeDrawerMorningGreeting;

  /// No description provided for @homeDrawerMorningSub.
  ///
  /// In zh, this message translates to:
  /// **'开启元气一天'**
  String get homeDrawerMorningSub;

  /// No description provided for @homeDrawerMorningGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'早呀！'**
  String get homeDrawerMorningGreeting2;

  /// No description provided for @homeDrawerMorningSub2.
  ///
  /// In zh, this message translates to:
  /// **'新的一天开始啦'**
  String get homeDrawerMorningSub2;

  /// No description provided for @homeDrawerForenoonGreeting.
  ///
  /// In zh, this message translates to:
  /// **'上午好！'**
  String get homeDrawerForenoonGreeting;

  /// No description provided for @homeDrawerForenoonSub.
  ///
  /// In zh, this message translates to:
  /// **'再忙也别忘了活动下肩膀'**
  String get homeDrawerForenoonSub;

  /// No description provided for @homeDrawerForenoonGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'上午的效率超棒！'**
  String get homeDrawerForenoonGreeting2;

  /// No description provided for @homeDrawerForenoonSub2.
  ///
  /// In zh, this message translates to:
  /// **'继续加油'**
  String get homeDrawerForenoonSub2;

  /// No description provided for @homeDrawerLunchGreeting.
  ///
  /// In zh, this message translates to:
  /// **'午饭时间到！'**
  String get homeDrawerLunchGreeting;

  /// No description provided for @homeDrawerLunchSub.
  ///
  /// In zh, this message translates to:
  /// **'好好吃饭，别凑合'**
  String get homeDrawerLunchSub;

  /// No description provided for @homeDrawerLunchGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'午安～'**
  String get homeDrawerLunchGreeting2;

  /// No description provided for @homeDrawerLunchSub2.
  ///
  /// In zh, this message translates to:
  /// **'吃完记得歇会儿'**
  String get homeDrawerLunchSub2;

  /// No description provided for @homeDrawerLunchGreeting3.
  ///
  /// In zh, this message translates to:
  /// **'午餐不知道吃什么？'**
  String get homeDrawerLunchGreeting3;

  /// No description provided for @homeDrawerLunchSub3.
  ///
  /// In zh, this message translates to:
  /// **'让小万帮你推荐吧！'**
  String get homeDrawerLunchSub3;

  /// No description provided for @homeDrawerAfternoonGreeting.
  ///
  /// In zh, this message translates to:
  /// **'喝杯茶提提神'**
  String get homeDrawerAfternoonGreeting;

  /// No description provided for @homeDrawerAfternoonSub.
  ///
  /// In zh, this message translates to:
  /// **'剩下的任务也能轻松搞定～'**
  String get homeDrawerAfternoonSub;

  /// No description provided for @homeDrawerAfternoonGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'工作间隙看看窗外'**
  String get homeDrawerAfternoonGreeting2;

  /// No description provided for @homeDrawerAfternoonSub2.
  ///
  /// In zh, this message translates to:
  /// **'让眼睛歇一歇～'**
  String get homeDrawerAfternoonSub2;

  /// No description provided for @homeDrawerEveningGreeting.
  ///
  /// In zh, this message translates to:
  /// **'回家路上慢点'**
  String get homeDrawerEveningGreeting;

  /// No description provided for @homeDrawerEveningSub.
  ///
  /// In zh, this message translates to:
  /// **'今晚好好放松～'**
  String get homeDrawerEveningSub;

  /// No description provided for @homeDrawerEveningGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'傍晚了'**
  String get homeDrawerEveningGreeting2;

  /// No description provided for @homeDrawerEveningSub2.
  ///
  /// In zh, this message translates to:
  /// **'吹来的晚风很舒服呀！～'**
  String get homeDrawerEveningSub2;

  /// No description provided for @homeDrawerEveningGreeting3.
  ///
  /// In zh, this message translates to:
  /// **'忙了一天'**
  String get homeDrawerEveningGreeting3;

  /// No description provided for @homeDrawerEveningSub3.
  ///
  /// In zh, this message translates to:
  /// **'吃顿好的犒劳自己～'**
  String get homeDrawerEveningSub3;

  /// No description provided for @homeDrawerNightGreeting.
  ///
  /// In zh, this message translates to:
  /// **'晚上好！'**
  String get homeDrawerNightGreeting;

  /// No description provided for @homeDrawerNightSub.
  ///
  /// In zh, this message translates to:
  /// **'享受属于自己的时光吧～'**
  String get homeDrawerNightSub;

  /// No description provided for @homeDrawerNightGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'夜色渐浓'**
  String get homeDrawerNightGreeting2;

  /// No description provided for @homeDrawerNightSub2.
  ///
  /// In zh, this message translates to:
  /// **'准备下早点休息啦～'**
  String get homeDrawerNightSub2;

  /// No description provided for @homeDrawerNightGreeting3.
  ///
  /// In zh, this message translates to:
  /// **'该休息了'**
  String get homeDrawerNightGreeting3;

  /// No description provided for @homeDrawerNightSub3.
  ///
  /// In zh, this message translates to:
  /// **'让小万帮你定个闹钟吧！'**
  String get homeDrawerNightSub3;

  /// No description provided for @homeDrawerLateNightGreeting.
  ///
  /// In zh, this message translates to:
  /// **'放下手机早点睡'**
  String get homeDrawerLateNightGreeting;

  /// No description provided for @homeDrawerLateNightSub.
  ///
  /// In zh, this message translates to:
  /// **'明天才能元气满满～'**
  String get homeDrawerLateNightSub;

  /// No description provided for @homeDrawerLateNightGreeting2.
  ///
  /// In zh, this message translates to:
  /// **'深夜了'**
  String get homeDrawerLateNightGreeting2;

  /// No description provided for @homeDrawerLateNightSub2.
  ///
  /// In zh, this message translates to:
  /// **'好好和今天说晚安～'**
  String get homeDrawerLateNightSub2;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'zh'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'zh':
      return AppLocalizationsZh();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
