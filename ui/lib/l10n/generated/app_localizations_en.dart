// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appName => 'Omnibot';

  @override
  String get brandName => 'Omnibot';

  @override
  String get brandNameEnglish => 'Omnibot';

  @override
  String get commonLoading => 'Loading';

  @override
  String get homeDrawerSearchHint => 'Search';

  @override
  String get homeDrawerClearSearch => 'Clear search';

  @override
  String get themeModeTitle => 'Theme Mode';

  @override
  String get themeModeSubtitle =>
      'Switch between light, dark, or system appearance';

  @override
  String get themeModeLight => 'Light';

  @override
  String get themeModeDark => 'Dark';

  @override
  String get themeModeSystem => 'System';

  @override
  String get languageTitle => 'Language';

  @override
  String get languageSubtitle =>
      'Choose the display language for the app UI, agent prompts, and tool text';

  @override
  String get languageFollowSystem => 'System';

  @override
  String get languageZhHans => '简体中文';

  @override
  String get languageEnglish => 'English';

  @override
  String get settingsTitle => 'Settings';

  @override
  String get settingsSectionModelMemory => 'Models & Memory';

  @override
  String get settingsSectionServiceEnvironment => 'Services & Environment';

  @override
  String get settingsSectionExperienceAppearance => 'Experience & Appearance';

  @override
  String get settingsSectionPermissionInfo => 'Permissions & Info';

  @override
  String get settingsModelProviderTitle => 'Model Providers';

  @override
  String get settingsModelProviderSubtitle =>
      'Configure model endpoints, API keys, and model lists';

  @override
  String get settingsSceneModelTitle => 'Scene Model Config';

  @override
  String get settingsSceneModelSubtitle =>
      'Bind models by scene and use the default model for unbound scenes';

  @override
  String get settingsWorkspaceMemoryTitle => 'Workspace Memory';

  @override
  String get settingsWorkspaceMemoryLoading => 'Loading...';

  @override
  String get settingsWorkspaceMemoryEnabled =>
      'Workspace memory enabled (embedding retrieval available)';

  @override
  String get settingsWorkspaceMemoryLexical =>
      'Use workspace memory (currently lexical retrieval)';

  @override
  String get settingsMcpToolsTitle => 'MCP Tools';

  @override
  String get settingsMcpToolsSubtitle =>
      'Add, enable, and manage remote MCP services';

  @override
  String get settingsLocalServiceTitle => 'Local Service';

  @override
  String get settingsLocalServiceSubtitle =>
      'Access Omnibot MCP and webchat over your local network';

  @override
  String get settingsAlpineTitle => 'Terminal Environment';

  @override
  String get settingsAlpineSubtitle =>
      'Choose and manage the built-in Alpine or Ubuntu terminal system';

  @override
  String get settingsHideRecentsTitle => 'Hide from Recents';

  @override
  String get settingsHideRecentsSubtitle =>
      'Hide the app from the recent tasks list when enabled';

  @override
  String get settingsRecentConversationsOnlyTitle =>
      'Show Only Recent 7-Day Conversations';

  @override
  String get settingsRecentConversationsOnlySubtitle =>
      'Automatically archive conversations not updated for over 7 days to speed up the sidebar';

  @override
  String get settingsAlarmTitle => 'Alarm Settings';

  @override
  String get settingsAlarmSubtitle =>
      'Configure the default ringtone, a local mp3, or an mp3 URL';

  @override
  String get settingsAppearanceTitle => 'Appearance';

  @override
  String get settingsAppearanceSubtitle =>
      'Configure theme mode, language, shared background, chat font size, and text color';

  @override
  String get settingsVibrationTitle => 'Vibration Feedback';

  @override
  String get settingsVibrationSubtitle =>
      'Use vibration to signal task progress while executing';

  @override
  String get settingsIndependentSendButtonTitle => 'Independent Send Button';

  @override
  String get settingsIndependentSendButtonSubtitle =>
      'When enabled, Enter creates a new line; when disabled, Enter sends the message directly';

  @override
  String get settingsPredictiveBackTitle => 'Predictive Back Gesture';

  @override
  String get settingsPredictiveBackSubtitle =>
      'When enabled, the back gesture follows your finger to preview the previous page or home screen; when disabled, the legacy back behavior is kept';

  @override
  String get settingsHabitualHandTitle => 'Dominant Hand';

  @override
  String get settingsHabitualHandSubtitle =>
      'Changes the swipe direction for chat history menus';

  @override
  String get settingsHabitualHandLeft => 'Left';

  @override
  String get settingsHabitualHandRight => 'Right';

  @override
  String get settingsAboutTitle => 'About Omnibot';

  @override
  String get settingsHideRecentsFailed => 'Failed to update hide-from-recents';

  @override
  String get settingsSaveFailed => 'Failed to save settings';

  @override
  String settingsMcpEnabledToast(Object endpoint) {
    return 'MCP enabled: $endpoint';
  }

  @override
  String get settingsMcpDisabledToast => 'MCP disabled';

  @override
  String get settingsMcpToggleFailed => 'Failed to toggle MCP';

  @override
  String get settingsCopiedAddress => 'Address copied';

  @override
  String get settingsCopiedToken => 'Token copied';

  @override
  String get settingsTokenRefreshed => 'Token refreshed';

  @override
  String get settingsTokenRefreshFailed => 'Failed to refresh token';

  @override
  String get settingsMcpLocalService => 'Local Service';

  @override
  String get settingsMcpAddress => 'Address';

  @override
  String get settingsMcpToken => 'Token';

  @override
  String get settingsNotGenerated => 'Not generated';

  @override
  String get settingsCopyAddress => 'Copy Address';

  @override
  String get settingsCopyToken => 'Copy Token';

  @override
  String get settingsRefreshToken => 'Refresh Token';

  @override
  String get settingsMcpSecurityNotice =>
      'Use the local MCP service on the same LAN with Authorization: Bearer <Token>, and avoid exposing the address or token to the public internet.';

  @override
  String get settingsInstalledAppsPermissionFailed =>
      'Failed to request installed apps permission';

  @override
  String get appearanceTitle => 'Appearance';

  @override
  String get appearanceAutoSaving => 'Saving changes…';

  @override
  String get appearanceAutosaveHint => 'Changes are saved automatically';

  @override
  String get appearanceBackgroundSource => 'Background Source';

  @override
  String get appearancePreview => 'Preview';

  @override
  String get appearanceAdjustments => 'Adjustments';

  @override
  String get appearancePreviewChat => 'Chat';

  @override
  String get appearancePreviewWorkspace => 'Workspace';

  @override
  String get appearanceEnableBackground => 'Enable background image';

  @override
  String get appearanceEnableBackgroundSubtitle =>
      'Apply it to both Chat and Workspace pages and save automatically';

  @override
  String get appearanceSourceLocal => 'Local Image';

  @override
  String get appearanceSourceRemote => 'Image URL';

  @override
  String get appearanceNoLocalImage => 'No local image selected yet';

  @override
  String get appearancePickImage => 'Choose Image';

  @override
  String get appearanceRepickImage => 'Choose Again';

  @override
  String get appearanceRemoteImageUrl => 'Image URL';

  @override
  String get appearanceRemoteImageUrlHint =>
      'https://example.com/background.jpg';

  @override
  String get appearanceBackgroundBlur => 'Background Blur';

  @override
  String get appearanceBackgroundBlurSubtitle =>
      'Adjust the blur of the overlay above the image';

  @override
  String get appearanceOverlayIntensity => 'Overlay Strength';

  @override
  String get appearanceOverlayIntensitySubtitle =>
      'Increase the unified overlay to make the UI cleaner';

  @override
  String get appearanceOverlayBrightness => 'Overlay Brightness';

  @override
  String get appearanceOverlayBrightnessSubtitle =>
      'Brighten or darken the overlay without modifying the image itself';

  @override
  String get appearanceChatTextSize => 'Chat Text Size';

  @override
  String get appearanceChatTextSizeSubtitle =>
      'Only affects user messages, AI replies, and the thinking panel';

  @override
  String get appearanceTextColorTitle => 'Chat Text Color';

  @override
  String get appearanceTextColorSubtitle =>
      'By default it adapts to the background, or you can pin a custom color';

  @override
  String get appearanceTextColorAuto => 'Auto';

  @override
  String get appearanceCustomColorLabel => 'Custom Color';

  @override
  String get appearanceCustomColorHint => '#FFFFFF or #FF112233';

  @override
  String get appearancePreviewTip =>
      'You can drag the image and pinch to zoom in the preview above. The preview stays close to the actual effect.';

  @override
  String get appearanceColorWhite => 'White';

  @override
  String get appearanceColorDarkGray => 'Dark Gray';

  @override
  String get appearanceColorLightBlue => 'Light Blue';

  @override
  String get appearanceColorNavy => 'Navy';

  @override
  String get appearanceColorTeal => 'Teal';

  @override
  String get appearanceColorWarmYellow => 'Warm Yellow';

  @override
  String get appearanceInvalidHttpUrl => 'Enter a valid http(s) image URL';

  @override
  String get appearanceInvalidHexColor => 'Enter #RRGGBB or #AARRGGBB';

  @override
  String get appearanceInvalidHexColorFormat => 'Invalid color code';

  @override
  String appearancePickImageFailed(Object error) {
    return 'Failed to pick image: $error';
  }

  @override
  String get appearancePickLocalImageFirst => 'Select a local image first';

  @override
  String get appearanceLocalImageMissing =>
      'The local image no longer exists. Please choose it again';

  @override
  String appearanceAutosaveFailed(Object error) {
    return 'Auto-save failed: $error';
  }

  @override
  String get chatToolCalling => 'Calling tool';

  @override
  String get chatFallbackReply =>
      'I can\'t generate a reply right now. Please try again.';

  @override
  String get chatPermissionRequired =>
      'Permissions must be enabled before running tasks';

  @override
  String chatPermissionRequiredWithNames(Object names) {
    return 'Enable these permissions before running tasks: $names';
  }

  @override
  String get chatRecentTerminalOutputNotice =>
      '[Only the most recent terminal output is shown]\n';

  @override
  String chatUserPrefix(Object text) {
    return 'User: $text\n';
  }

  @override
  String get permissionOverlay => 'Overlay';

  @override
  String get permissionInstalledApps => 'Installed Apps Access';

  @override
  String get permissionPublicStorage => 'Public Storage Access';

  @override
  String get browserOverlayTitle => 'Agent Browser';

  @override
  String get browserOverlayClose => 'Close browser window';

  @override
  String get browserOverlayUnsupported =>
      'Browser tool view is not supported on this platform yet';

  @override
  String get networkErrorMessage =>
      'Sorry, the network stumbled just now. Please try sending it again.';

  @override
  String get rateLimitErrorMessage =>
      'Omnibot is busy right now. Please try again in a moment.';

  @override
  String get chatHistoryArchivedTitle => 'Archived Conversations';

  @override
  String get chatHistoryTitle => 'Chat History';

  @override
  String get chatHistoryNoArchived => 'No archived conversations';

  @override
  String get chatHistoryEmpty => 'No conversations yet';

  @override
  String get chatHistoryArchivedToast => 'Archived';

  @override
  String get chatHistoryUnarchivedToast => 'Moved out of archive';

  @override
  String get chatHistoryArchiveFailed => 'Couldn\'t archive the conversation';

  @override
  String get chatHistoryUnarchiveFailed => 'Couldn\'t restore the conversation';

  @override
  String get chatHistoryArchiveHint =>
      'Swipe left on a conversation to archive it';

  @override
  String get homeDrawerArchive => 'Archive';

  @override
  String get homeDrawerNewChat => 'New conversation';

  @override
  String get webchatNoChats => 'Start a new conversation';

  @override
  String get memoryCenterTitle => 'Memory Center';

  @override
  String get memoryShortTermTitle => 'Short-term Memory';

  @override
  String get memoryLongTermTitle => 'Long-term Memory';

  @override
  String get memoryNoShortTerm => 'No short-term memory yet';

  @override
  String get memoryNoShortTermDesc =>
      'Process information from conversations settles into short-term memory and later gets organized into long-term memory.';

  @override
  String get memoryFilteredNoShortTerm =>
      'No short-term memory under current filter';

  @override
  String get memoryFilteredNoShortTermDesc =>
      'Check back later, new short-term memories will appear gradually.';

  @override
  String get memoryNoLongTerm => 'Long-term memory not yet initialized';

  @override
  String get memoryNoLongTermDesc =>
      'Once memory capability is enabled, your cross-session long-term memories will accumulate here.';

  @override
  String get memoryDeleteConfirmTitle => 'Are you sure you want to delete?';

  @override
  String get memoryDeleteWarning => 'This action cannot be undone';

  @override
  String get memoryEditDisabled => 'Editing short-term memory is not supported';

  @override
  String get memoryDeleteDisabled =>
      'Deleting short-term memory is not supported';

  @override
  String get memoryGreeting =>
      'Hi there,\nWe\'ll keep your memories together here.';

  @override
  String memorySelectedCount(Object n) {
    return '$n selected';
  }

  @override
  String get memoryDeselectAll => 'Deselect all';

  @override
  String get memoryEditTitle => 'Edit Memory';

  @override
  String get memoryIdLabel => 'Memory ID';

  @override
  String get memoryMatchScore => 'Match Score';

  @override
  String get memoryAdditionalInfo => 'Additional Info';

  @override
  String get memoryAddLongTerm => 'Add Long-term Memory';

  @override
  String get memorySaveToLongTerm => 'Save to Long-term Memory';

  @override
  String get memoryLongTermAdded => 'Long-term memory added';

  @override
  String get memoryEditLongTerm => 'Edit Long-term Memory';

  @override
  String get memorySaveChanges => 'Save changes';

  @override
  String get memoryDeleteLongTermConfirm => 'Delete this long-term memory?';

  @override
  String get memoryLongTermDeleted => 'Long-term memory deleted';

  @override
  String memoryLongTermFailed(Object error) {
    return 'Long-term memory operation failed: $error';
  }

  @override
  String get memoryNoMemories => 'No memories';

  @override
  String get memoryNoMemoriesDesc => 'Start exploring and add content you like';

  @override
  String get pluginMarketTitle => 'Plugin Market';

  @override
  String get pluginMarketEmpty => 'No plugins available';

  @override
  String get pluginMarketEmptyDesc =>
      'Official plugins will appear here once connected';

  @override
  String get pluginInstall => 'Install';

  @override
  String get pluginUpdate => 'Update';

  @override
  String get pluginUninstall => 'Uninstall';

  @override
  String get pluginCancel => 'Cancel';

  @override
  String get pluginNoDescription => 'No description';

  @override
  String get pluginIncompatible =>
      'This plugin is incompatible with the current version';

  @override
  String get pluginLoadFailed => 'Failed to load plugin market';

  @override
  String get pluginInstallFailed => 'Failed to install plugin';

  @override
  String get pluginUpdateFailed => 'Failed to update plugin';

  @override
  String get pluginToggleFailed => 'Failed to toggle plugin';

  @override
  String get pluginUninstallFailed => 'Failed to uninstall plugin';

  @override
  String get pluginUninstallTitle => 'Uninstall Plugin';

  @override
  String pluginUninstallConfirmMsg(Object name) {
    return 'Uninstall \"$name\"?';
  }

  @override
  String pluginInstalledMsg(Object name) {
    return 'Installed $name';
  }

  @override
  String pluginUpdatedMsg(Object name) {
    return 'Updated $name';
  }

  @override
  String pluginEnabledMsg(Object name) {
    return 'Enabled $name';
  }

  @override
  String pluginDisabledMsg(Object name) {
    return 'Disabled $name';
  }

  @override
  String pluginUninstalledMsg(Object name) {
    return 'Uninstalled $name';
  }

  @override
  String get pluginKindBundledModule => 'Bundled module';

  @override
  String get pluginKindRuntimeBundle => 'Runtime bundle';

  @override
  String get pluginKindCompanionApp => 'Companion app';

  @override
  String get pluginDetailTitle => 'Plugin Details';

  @override
  String get pluginSearchHint =>
      'Search plugins, descriptions, or capabilities';

  @override
  String get pluginSearchEmpty => 'No matching plugins';

  @override
  String get pluginAboutTitle => 'About';

  @override
  String get pluginCapabilitiesTitle => 'Capabilities';

  @override
  String get pluginNoCapabilities =>
      'This plugin declares no additional capabilities';

  @override
  String get pluginInformationTitle => 'Information';

  @override
  String get pluginPublisherLabel => 'Developer';

  @override
  String get pluginVersionLabel => 'Version';

  @override
  String get pluginTypeLabel => 'Type';

  @override
  String get pluginDownloadSizeLabel => 'Download size';

  @override
  String get pluginInterfaceVersionLabel => 'Interface version';

  @override
  String get pluginStatusInstalled => 'Installed';

  @override
  String get pluginStatusEnabled => 'Enabled';

  @override
  String get pluginStatusNotInstalled => 'Not installed';

  @override
  String get pluginEnableTitle => 'Enable plugin';

  @override
  String get pluginEnableDescription =>
      'Allow the Agent to use capabilities from this plugin';

  @override
  String get pluginRetry => 'Retry';

  @override
  String get skillStoreTitle => 'Skill Store';

  @override
  String get skillBuiltin => 'Built-in';

  @override
  String get skillOfficial => 'Official';

  @override
  String get skillUser => 'User';

  @override
  String get skillInstalled => 'Installed';

  @override
  String get skillNotInstalled => 'Not installed';

  @override
  String get skillEnabled => 'Enabled';

  @override
  String get skillDisabled => 'Disabled';

  @override
  String get skillInstall => 'Install';

  @override
  String get skillDelete => 'Delete';

  @override
  String get skillEmpty => 'No skills available';

  @override
  String get skillNoDescription => 'No description';

  @override
  String get skillBuiltinRemovedDesc =>
      'This built-in skill has been removed from the workspace. You can reinstall it anytime.';

  @override
  String get skillDeleteTitle => 'Delete Skill';

  @override
  String skillDeleteConfirmMsg(Object name) {
    return 'Delete \"$name\"?';
  }

  @override
  String get skillDeleted => 'Deleted';

  @override
  String get skillDeleteFailed => 'Failed to delete';

  @override
  String skillInstalledMsg(Object name) {
    return 'Installed $name';
  }

  @override
  String get skillInstallFailed => 'Failed to install';

  @override
  String skillEnabledMsg(Object name) {
    return 'Enabled $name';
  }

  @override
  String skillDisabledMsg(Object name) {
    return 'Disabled $name';
  }

  @override
  String get skillToggleFailed => 'Failed to toggle';

  @override
  String get skillSyncOfficialTooltip => 'Install/update official skills';

  @override
  String skillSyncOfficialSuccess(Object count) {
    return 'Official skills synced ($count)';
  }

  @override
  String get skillSyncOfficialFailed => 'Failed to sync official skills';

  @override
  String get skillLoadFailed => 'Failed to load skills';

  @override
  String get modelProviderConfigTitle => 'Provider Configuration';

  @override
  String get modelProviderConfigDesc =>
      'Add, switch, and maintain model service provider names, addresses, and keys.';

  @override
  String get modelProviderName => 'Provider Name';

  @override
  String get modelProviderNameHint => 'e.g., DeepSeek';

  @override
  String get modelProviderBaseUrlHint =>
      'Append # to disable auto-complete request path';

  @override
  String get modelProviderApiKeyHint =>
      'Requests will be made without authentication when API Key is not filled in.';

  @override
  String get modelListTitle => 'Model List';

  @override
  String get modelListDesc =>
      'Supports manually adding models or fetching the remote model list from the current Provider.';

  @override
  String modelListCount(Object count) {
    return '$count models in total';
  }

  @override
  String get modelAddPrompt => 'Please add a model!';

  @override
  String get modelBuiltinProvider => 'Built-in Provider';

  @override
  String get modelIdEmpty =>
      'Model ID cannot be empty and cannot start with \'scene.\'';

  @override
  String get modelAlreadyExists => 'Model already exists';

  @override
  String get modelAdded => 'Model added';

  @override
  String get modelDeleted => 'Model deleted';

  @override
  String get modelDeleteFailed => 'Failed to delete model';

  @override
  String get modelIdHint => 'Enter model ID';

  @override
  String get modelAddProviderTitle => 'Add Provider';

  @override
  String get modelAddButton => 'Add';

  @override
  String get modelProviderAdded => 'Provider added';

  @override
  String modelProviderAddFailed(Object error) {
    return 'Failed to add Provider: $error';
  }

  @override
  String get modelDeleteProviderTitle => 'Delete Provider';

  @override
  String modelDeleteProviderMsg(Object name) {
    return 'Delete \"$name\"? Scene bindings will be preserved, but you need to reselect an available Provider.';
  }

  @override
  String get modelProviderDeleted => 'Provider deleted';

  @override
  String modelProviderDeleteFailed(Object error) {
    return 'Failed to delete Provider: $error';
  }

  @override
  String get modelProviderLoadFailed =>
      'Failed to load model provider settings';

  @override
  String modelProviderSwitchFailed(Object error) {
    return 'Failed to switch providers: $error';
  }

  @override
  String get modelProviderBaseUrlRequired => 'Enter a Base URL first';

  @override
  String get modelProviderInvalidBaseUrl => 'Enter a valid http(s) Base URL';

  @override
  String modelProviderFetchedModels(Object count) {
    return 'Fetched $count models';
  }

  @override
  String modelProviderFetchFailed(Object error) {
    return 'Failed to fetch the model list: $error';
  }

  @override
  String get sceneModelMapping => 'Scene Mapping';

  @override
  String get sceneModelMappingDesc =>
      'Bind Providers and models by scene. Unbound scenes will continue using the default model.';

  @override
  String get sceneModelRefreshList => 'Refresh model list';

  @override
  String get sceneModelSearchHint =>
      'Click the button on the right to search, collapse, and select models by Provider; the top search bar stays fixed.';

  @override
  String get sceneModelNoScenes => 'No configurable scenes';

  @override
  String get sceneModelLoadFailed => 'Failed to load scene model settings';

  @override
  String sceneModelPartialUpdateFailed(Object profiles) {
    return 'Updated some models, but these providers failed: $profiles';
  }

  @override
  String sceneModelUpdatedModels(Object count) {
    return 'Updated $count models';
  }

  @override
  String sceneModelRefreshFailed(Object error) {
    return 'Failed to refresh the model list: $error';
  }

  @override
  String get sceneModelInvalidModelId => 'Model ID can\'t start with scene.';

  @override
  String sceneModelBoundToast(Object scene, Object model) {
    return '$scene is now using $model';
  }

  @override
  String sceneModelSaveFailed(Object scene, Object error) {
    return 'Failed to save $scene: $error';
  }

  @override
  String sceneModelBindingCleared(Object scene) {
    return 'Cleared the binding for $scene';
  }

  @override
  String sceneModelDefaultRestored(Object scene) {
    return '$scene is back to the default model';
  }

  @override
  String sceneModelClearFailed(Object scene, Object error) {
    return 'Failed to clear $scene: $error';
  }

  @override
  String get modelsNoAvailableModels => 'No models available';

  @override
  String get alarmSaved => 'Alarm settings saved';

  @override
  String get alarmRingtoneSource => 'Ringtone Source';

  @override
  String get alarmSystemDefault => 'System Default';

  @override
  String get alarmSystemDefaultDesc =>
      'No extra configuration needed, best compatibility';

  @override
  String get alarmLocalMp3 => 'Local MP3';

  @override
  String get alarmLocalMp3Desc =>
      'Select an MP3 file on your phone as the alarm ringtone';

  @override
  String get alarmMp3Url => 'MP3 URL';

  @override
  String get alarmMp3UrlDesc => 'Use an HTTP(S) URL to play an online MP3';

  @override
  String get alarmAudioPermissionDenied => 'Audio read permission not granted';

  @override
  String get alarmInvalidFilePath => 'Invalid file path, please select again';

  @override
  String get alarmSelectLocalFirst => 'Please select a local MP3 file first';

  @override
  String get alarmEnterHttpsUrl => 'Please enter an HTTP(S) MP3 URL';

  @override
  String get alarmLocalFile => 'Local File';

  @override
  String get alarmSelectMp3 => 'Select MP3 File';

  @override
  String get authorizePageTitle => 'App Permission Authorization';

  @override
  String get authorizeReceiveNotifications => 'Receive message notifications';

  @override
  String get authorizeNotificationsDesc =>
      'Enable this to get task progress updates in time';

  @override
  String get storageUsageTitle => 'Storage Usage';

  @override
  String get storageUsageSubtitle =>
      'View storage usage details and clean up by category';

  @override
  String get storageAnalyzeFailed =>
      'Storage analysis failed, please try again';

  @override
  String storageCategoryCleaned(Object name, Object size) {
    return 'Cleaned $name, freed $size';
  }

  @override
  String get storageCleanFailed => 'Cleanup failed, please try again later';

  @override
  String storageCleanCategory(Object name) {
    return 'Clean $name';
  }

  @override
  String get storageCleanConfirmMsg => 'Confirm cleanup of this category?';

  @override
  String get storageCleanScope => 'Cleanup Scope';

  @override
  String get storageCleanAll => 'All';

  @override
  String get storageClean7Days => '7 days ago';

  @override
  String get storageClean30Days => '30 days ago';

  @override
  String storageStrategyName(Object name) {
    return 'Strategy: $name';
  }

  @override
  String storageStrategyDone(Object size) {
    return 'Strategy completed, freed $size';
  }

  @override
  String storageStrategyPartialDone(Object count, Object size) {
    return 'Strategy completed, freed $size, $count items not fully successful';
  }

  @override
  String get storageStrategyFailed => 'Strategy failed, please try again later';

  @override
  String get storageLoadFailed => 'Failed to load';

  @override
  String get storageReanalyze => 'Reanalyze';

  @override
  String get storageTotalUsage => 'Total Usage';

  @override
  String get storageAppSize => 'App Size';

  @override
  String get storageUserData => 'User Data';

  @override
  String get storageCleanable => 'Cleanable';

  @override
  String storageStatsSource(Object source) {
    return 'Statistics source: $source';
  }

  @override
  String storagePackageName(Object name) {
    return 'Current package: $name';
  }

  @override
  String get storageTrendFirst =>
      'This is the first analysis. Usage trends will be shown in future analyses.';

  @override
  String get storageSmartCleanup => 'Smart Cleanup';

  @override
  String get storageExecute => 'Execute';

  @override
  String get storageUsageAnalysis => 'Usage Analysis';

  @override
  String get storageClean => 'Clean';

  @override
  String get storageRiskLow => 'Low Risk';

  @override
  String get storageRiskCaution => 'Caution';

  @override
  String get storageRiskHigh => 'High Risk';

  @override
  String get storageReadOnly => 'Read Only';

  @override
  String get storageSystemStats =>
      'System statistics (closer to system settings)';

  @override
  String get storageDirectoryScan => 'Directory scan estimate';

  @override
  String get storageAdditionalInfo => 'Additional Info';

  @override
  String get storageCatAppBinary => 'App Binary';

  @override
  String get storageCatAppBinaryDesc => 'Installed app files (APK/AAB split)';

  @override
  String get storageCatCache => 'Cache';

  @override
  String get storageCatCacheDesc =>
      'Temporary files and image cache, safe to clean';

  @override
  String get storageCatCacheHint =>
      'Will regenerate automatically during use after cleanup';

  @override
  String get storageCatConversation => 'Conversation History';

  @override
  String get storageCatConversationDesc =>
      'Chat and tool execution history (estimated)';

  @override
  String get storageCatConversationHint =>
      'Will delete historical message records and cannot be recovered';

  @override
  String get storageCatDatabaseOther => 'Other Database';

  @override
  String get storageCatDatabaseOtherDesc => 'Indexes and system tables';

  @override
  String get storageCatWorkspaceBrowser => 'Workspace Browser Artifacts';

  @override
  String get storageCatWorkspaceBrowserDesc =>
      'Browser screenshots, downloads, and intermediate files';

  @override
  String get storageCatWorkspaceBrowserHint =>
      'Will delete browser tool intermediate files';

  @override
  String get storageCatWorkspaceOffloads => 'Workspace Offloads';

  @override
  String get storageCatWorkspaceOffloadsDesc =>
      'Tool offline outputs and temporary files';

  @override
  String get storageCatWorkspaceOffloadsHint =>
      'Only deletes offline artifacts, does not affect core functionality';

  @override
  String get storageCatWorkspaceAttachments => 'Workspace Attachments';

  @override
  String get storageCatWorkspaceAttachmentsDesc =>
      'Attachment files used by historical tasks';

  @override
  String get storageCatWorkspaceAttachmentsHint =>
      'May affect viewing attachments in historical tasks';

  @override
  String get storageCatWorkspaceShared => 'Workspace Shared';

  @override
  String get storageCatWorkspaceSharedDesc =>
      'Shared workspace files across tasks';

  @override
  String get storageCatWorkspaceSharedHint =>
      'May affect subsequent tasks reusing shared files';

  @override
  String get storageCatWorkspaceMemory => 'Workspace Memory Data';

  @override
  String get storageCatWorkspaceMemoryDesc =>
      'Long/short-term memory and index data';

  @override
  String get storageCatWorkspaceUserFiles => 'Workspace User Files';

  @override
  String get storageCatWorkspaceUserFilesDesc =>
      'Files manually saved to workspace by user';

  @override
  String get storageCatTerminalLocal => 'Terminal Runtime (local)';

  @override
  String get storageCatTerminalLocalDesc =>
      'Alpine/Ubuntu terminal local runtime directory';

  @override
  String get storageCatTerminalLocalHint =>
      'Will delete terminal local directory, needs re-initialization';

  @override
  String get storageCatTerminalBootstrap => 'Terminal Runtime (bootstrap)';

  @override
  String get storageCatTerminalBootstrapDesc =>
      'proot/lib/rootfs bootstrap files';

  @override
  String get storageCatTerminalBootstrapHint =>
      'Will delete terminal bootstrap files, needs re-initialization';

  @override
  String get storageCatSharedDrafts => 'Shared Drafts';

  @override
  String get storageCatSharedDraftsDesc =>
      'Draft cache from external sharing imports';

  @override
  String get storageCatSharedDraftsHint =>
      'Will delete unsent draft attachments';

  @override
  String get storageCatMcpInbox => 'MCP Inbox';

  @override
  String get storageCatMcpInboxDesc => 'MCP file transfer receive directory';

  @override
  String get storageCatMcpInboxHint => 'Will delete files in MCP inbox';

  @override
  String get storageCatLegacyWorkspace => 'Legacy Data';

  @override
  String get storageCatLegacyWorkspaceDesc =>
      'Old workspace directories possibly left after upgrade';

  @override
  String get storageCatLegacyWorkspaceHint =>
      'Confirm it is no longer needed before cleanup';

  @override
  String get storageCatOtherUserData => 'Other Data';

  @override
  String get storageCatOtherUserDataDesc =>
      'Data not matched to any category rule';

  @override
  String get storageStrategySafeQuick => 'Safe Quick Cleanup';

  @override
  String get storageStrategySafeQuickDesc =>
      'Prioritize cleaning low-risk cache and temporary artifacts';

  @override
  String get storageStrategyBalanceDeep => 'Balanced Deep Cleanup';

  @override
  String get storageStrategyBalanceDeepDesc =>
      'Free more space while keeping core user data and files';

  @override
  String get storageStrategyFree1gb => 'Target Free 1GB';

  @override
  String get storageStrategyFree1gbDesc =>
      'Clean in high-value order, aiming for 1GB release target';

  @override
  String get storageHintConversation =>
      'If history is not released, re-enter the page and run \"Reanalyze\"';

  @override
  String get storageHintTerminal =>
      'After terminal runtime is cleaned, you can re-initialize it from the Terminal Environment page';

  @override
  String get storageHintGeneral =>
      'If cleanup fails, try again later or restart the app';

  @override
  String get storageHintNotCleanable =>
      'This category is currently not cleanable';

  @override
  String get storageHintSkipped => 'This category was skipped (optional)';

  @override
  String storageCleanPartialFailed(Object hint) {
    return 'Some cleanup failed: $hint';
  }

  @override
  String get storageCleanPartialFailedGeneric =>
      'Some files failed to clean up, please try again later';

  @override
  String storageTrendVsLast(Object cleanable, Object total) {
    return 'Vs last analysis: total $total, cleanable $cleanable';
  }

  @override
  String storageLastAnalyzed(Object time) {
    return 'Last analyzed: $time';
  }

  @override
  String get aboutDescription =>
      'Omnibot is an AI assistant app centered on\nintelligent conversation, using semantic understanding\nand continuous learning to help with information\nprocessing, decision support, and daily management.';

  @override
  String get aboutBetaProgramTitle => 'Join beta testing';

  @override
  String get aboutBetaProgramDescription =>
      'Get faster four-part beta updates.';

  @override
  String get aboutBetaProgramToggleFailed =>
      'Failed to update beta testing preference';

  @override
  String get aboutPreferencesSectionTitle => 'Update & Testing';

  @override
  String get aboutApkSourceTitle => 'APK Download Source';

  @override
  String get aboutApkSourceDescription =>
      'Choose the source used for update installs.';

  @override
  String get aboutApkSourceDisclaimer =>
      'By using this app, you agree to our Privacy Policy and consent to the collection of anonymous usage information through the open-source update Worker to help improve the software. You are solely responsible for any loss or consequence arising from your use of the app.';

  @override
  String get aboutApkSourceOptionCnb => 'Cloudflare R2';

  @override
  String get aboutApkSourceOptionCnbDescription => 'Served by update worker';

  @override
  String get aboutApkSourceOptionGithub => 'GitHub';

  @override
  String get aboutApkSourceOptionGithubDescription => 'Official release source';

  @override
  String get aboutApkSourceSwitchFailed =>
      'Failed to switch APK download source';

  @override
  String get aboutUpdateHintDefault =>
      'Check for updates to get the latest version';

  @override
  String get workspaceMemoryLoadFailed =>
      'Failed to load workspace memory config';

  @override
  String get agentSoulSaved => 'Agent soul setting saved';

  @override
  String get agentSoulSaveFailed => 'Failed to save Agent soul setting';

  @override
  String get chatPromptSaved => 'Chat-only system prompt saved';

  @override
  String get chatPromptSaveFailed => 'Failed to save chat-only system prompt';

  @override
  String get workspaceMemorySaved => 'MEMORY.md saved';

  @override
  String get workspaceMemorySaveFailed => 'Failed to save MEMORY.md';

  @override
  String get workspaceEmbeddingToggleFailed =>
      'Failed to update memory embedding toggle';

  @override
  String get workspaceRollupToggleFailed =>
      'Failed to update nightly rollup toggle';

  @override
  String get workspaceRollupDone => 'Rollup completed';

  @override
  String get workspaceRollupFailed => 'Rollup failed';

  @override
  String get workspaceNone => 'None';

  @override
  String get workspaceMemoryTitle => 'Workspace Memory';

  @override
  String get workspaceMemoryCapability => 'Memory Capability';

  @override
  String get workspaceEmbeddingReady =>
      'Configured, vector retrieval available';

  @override
  String get workspaceEmbeddingNotReady =>
      'Not configured, will fall back to lexical retrieval';

  @override
  String get workspaceGoToConfig =>
      'Go to scene model config to set up embedding model';

  @override
  String get workspaceNightlyRollup => 'Nightly Memory Rollup (22:00)';

  @override
  String workspaceLastRun(Object time) {
    return 'Last run: $time';
  }

  @override
  String workspaceNextRun(Object time) {
    return 'Next run: $time';
  }

  @override
  String get workspaceRollupNow => 'Rollup now';

  @override
  String get workspaceSettingsAndMemory => 'Agent Settings and Memory';

  @override
  String get agentSoulSetting => 'Agent Soul';

  @override
  String get chatPromptSetting => 'Chat-only system prompt';

  @override
  String get workspaceMemoryMd => 'MEMORY.md (Long-term Memory)';

  @override
  String get alpineNodeJs => 'Node.js Runtime';

  @override
  String get alpineNpm => 'Node.js Package Manager';

  @override
  String get alpineGit => 'Git Version Control';

  @override
  String get alpinePython => 'Python Interpreter';

  @override
  String get alpinePip => 'Python Projects & Packages';

  @override
  String get alpinePipInstall => 'Python Package Installer';

  @override
  String get alpineCodex => 'OpenAI Codex CLI for ACP Agents';

  @override
  String get alpineClaudeCode => 'Anthropic Claude Code CLI for ACP Agents';

  @override
  String get alpineOpenCode => 'OpenCode CLI with built-in ACP support';

  @override
  String get alpineDeepSeekHarness =>
      'DeepSeek Harness (dsh) official ACP runtime';

  @override
  String get alpineSshClient => 'SSH Client';

  @override
  String get alpineSshpass => 'SSH Password Helper';

  @override
  String get alpineOpenSshServer => 'OpenSSH Server';

  @override
  String get alpineDetectFailed => 'Failed to detect terminal environment';

  @override
  String get alpineBootTasksLoadFailed => 'Failed to load boot tasks';

  @override
  String get alpineConfigOpenFailed =>
      'Failed to open terminal environment config';

  @override
  String get alpineBootTaskAdded => 'Boot task added';

  @override
  String get alpineBootTaskUpdated => 'Boot task updated';

  @override
  String get alpineBootTaskSaveFailed => 'Failed to save boot task';

  @override
  String get alpineBootEnabled => 'Enabled auto-start on app launch';

  @override
  String get alpineBootDisabled => 'Disabled auto-start';

  @override
  String get alpineBootTaskUpdateFailed => 'Failed to update task';

  @override
  String get alpineDeleteBootTask => 'Delete Boot Task';

  @override
  String alpineDeleteBootTaskMsg(Object name) {
    return 'Delete \"$name\"?';
  }

  @override
  String get alpineBootTaskDeleted => 'Boot task deleted';

  @override
  String get alpineBootTaskDeleteFailed => 'Failed to delete task';

  @override
  String get alpineCommandSent => 'Start command sent';

  @override
  String get alpineStartFailed => 'Failed to start task';

  @override
  String get alpineDetecting => 'Detecting environment';

  @override
  String alpineStartConfig(Object count) {
    return 'Start configuration ($count items)';
  }

  @override
  String get alpineAllReady => 'All ready';

  @override
  String get alpineDetectingDesc =>
      'Detecting version info of common development tools in the selected terminal system.';

  @override
  String alpineReadyCount(Object ready, Object total) {
    return '$ready/$total items ready in the selected terminal system. Check missing items and auto-configure in ReTerminal.';
  }

  @override
  String get alpineBootTasks => 'Boot Tasks';

  @override
  String get alpineBootTasksDesc =>
      'When Omnibot opens, enabled tasks are checked in the background and commands are started in the corresponding ReTerminal session. Suitable for persistent services.';

  @override
  String get alpineAddTask => 'Add Task';

  @override
  String get alpineOpenTerminal => 'Open Terminal';

  @override
  String get alpineNoTasksDesc =>
      'No tasks. You can add persistent commands like `python app.py`, `node server.js`, or `./start.sh`.';

  @override
  String get alpineBootOnAppOpen => 'Start after app opens on boot';

  @override
  String get alpineNotEnabled => 'Not enabled';

  @override
  String get alpineRunning => 'Running';

  @override
  String get alpineStartNow => 'Start Now';

  @override
  String get alpineEdit => 'Edit';

  @override
  String get alpineVersionDetected => 'Version detected';

  @override
  String get alpineVersionNotFound => 'Not detected';

  @override
  String get alpineTaskNameHint => 'Enter task name';

  @override
  String get alpineCommandHint => 'Enter start command';

  @override
  String get alpineEditBootTask => 'Edit Boot Task';

  @override
  String get alpineAddBootTask => 'Add Boot Task';

  @override
  String get alpineTaskName => 'Task Name';

  @override
  String get alpineTaskNameExample => 'e.g., Local API service';

  @override
  String get alpineStartCommand => 'Start Command';

  @override
  String get alpineCommandExample => 'e.g., python app.py or pnpm start';

  @override
  String get alpineWorkDir => 'Working Directory';

  @override
  String get alpineBootAutoStart => 'Auto-start when Omnibot opens';

  @override
  String get alpineDevEnv => 'Dev Environment';

  @override
  String get alpineAiAgent => 'AI Agent';

  @override
  String get alpineEnvConfig => 'Environment Config';

  @override
  String alpineWorkDirValue(Object dir) {
    return 'Working directory: $dir';
  }

  @override
  String get workspaceEmbeddingRetrieval => 'Memory Embedding Retrieval';

  @override
  String get chatHistoryStartConversation => 'Start a conversation';

  @override
  String get homeDrawerSearching => 'Searching conversations...';

  @override
  String get homeDrawerNoResults => 'No matching conversations found';

  @override
  String get homeDrawerSearchHint2 =>
      'Try shorter keywords or rephrase your search';

  @override
  String get homeDrawerSearchResults => 'Search results';

  @override
  String get homeDrawerResultCount => 'results';

  @override
  String get homeDrawerScheduled => 'Scheduled';

  @override
  String get homeDrawerScheduledTasks => 'Scheduled tasks';

  @override
  String get homeDrawerPinnedConversations => 'Pinned conversations';

  @override
  String get homeDrawerAgentSection => 'Agent';

  @override
  String get homeDrawerOmniAiSection => 'OmniAi';

  @override
  String get homeDrawerChatOnlySection => 'Pure chat';

  @override
  String get homeDrawerAgentNoProject => 'Other';

  @override
  String get homeDrawerGreeting => 'Hello!';

  @override
  String get homeDrawerWelcome => 'Welcome to Omnibot';

  @override
  String get homeDrawerDawnGreeting => 'Late night';

  @override
  String get homeDrawerDawnSub => 'Still awake?';

  @override
  String get homeDrawerDawnGreeting2 => 'Before dawn';

  @override
  String get homeDrawerDawnSub2 => 'Early bird, take care!';

  @override
  String get homeDrawerDawnGreeting3 => 'Quiet midnight';

  @override
  String get homeDrawerDawnSub3 => 'Remember to get some rest.';

  @override
  String get homeDrawerMorningGreeting => 'Good morning!';

  @override
  String get homeDrawerMorningSub => 'Start your day with energy';

  @override
  String get homeDrawerMorningGreeting2 => 'Morning!';

  @override
  String get homeDrawerMorningSub2 => 'A new day has begun';

  @override
  String get homeDrawerForenoonGreeting => 'Good forenoon!';

  @override
  String get homeDrawerForenoonSub => 'Take a quick shoulder stretch';

  @override
  String get homeDrawerForenoonGreeting2 => 'Great momentum!';

  @override
  String get homeDrawerForenoonSub2 => 'Keep it going';

  @override
  String get homeDrawerLunchGreeting => 'Lunch time!';

  @override
  String get homeDrawerLunchSub => 'Have a proper meal';

  @override
  String get homeDrawerLunchGreeting2 => 'Good noon~';

  @override
  String get homeDrawerLunchSub2 => 'Take a short break after lunch';

  @override
  String get homeDrawerLunchGreeting3 => 'Not sure what to eat?';

  @override
  String get homeDrawerLunchSub3 => 'Let Omnibot recommend for you';

  @override
  String get homeDrawerAfternoonGreeting => 'Time for a tea break';

  @override
  String get homeDrawerAfternoonSub => 'You\'ve got this!';

  @override
  String get homeDrawerAfternoonGreeting2 => 'Look away for a bit';

  @override
  String get homeDrawerAfternoonSub2 => 'Refresh your eyes for a moment';

  @override
  String get homeDrawerEveningGreeting => 'Take it easy on the way home';

  @override
  String get homeDrawerEveningSub => 'Relax tonight';

  @override
  String get homeDrawerEveningGreeting2 => 'Evening breeze';

  @override
  String get homeDrawerEveningSub2 => 'Feels nice, doesn\'t it?';

  @override
  String get homeDrawerEveningGreeting3 => 'Long day today';

  @override
  String get homeDrawerEveningSub3 => 'Treat yourself to a good meal';

  @override
  String get homeDrawerNightGreeting => 'Good evening!';

  @override
  String get homeDrawerNightSub => 'Enjoy your own time';

  @override
  String get homeDrawerNightGreeting2 => 'Night is settling in';

  @override
  String get homeDrawerNightSub2 => 'Get ready to rest earlier';

  @override
  String get homeDrawerNightGreeting3 => 'Time to rest';

  @override
  String get homeDrawerNightSub3 => 'Let Omnibot set an alarm for you';

  @override
  String get homeDrawerLateNightGreeting =>
      'Put the phone down and sleep earlier';

  @override
  String get homeDrawerLateNightSub => 'Recharge for tomorrow';

  @override
  String get homeDrawerLateNightGreeting2 => 'It is late';

  @override
  String get homeDrawerLateNightSub2 => 'Say good night to today';
}
