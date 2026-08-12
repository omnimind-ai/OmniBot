package cn.com.omnimind.bot.ui.channel

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine

/**
 * 用来管理flutter通道
 */
class ChannelManager {

    private var specialPermissionChannel: SpecialPermissionChannel = SpecialPermissionChannel()
    private var assistsCoreChannel: AssistsCoreChannel = AssistsCoreChannel()
    private var httpChannel: HttpChannel = HttpChannel()
    private var cacheChannel: CacheChannel = CacheChannel()
    private var voicePlaybackChannel: VoicePlaybackChannel = VoicePlaybackChannel()
    private var speechTranscriptionChannel: SpeechTranscriptionChannel =
        SpeechTranscriptionChannel()
    private var deviceInfoChannel: DeviceInfoChannel = DeviceInfoChannel()
    private var appStateChannel: AppStateChannel = AppStateChannel()
    private var fileSaveChannel: FileSaveChannel = FileSaveChannel()
    private var pdfPreviewChannel: PdfPreviewChannel = PdfPreviewChannel()
    private var hideFromRecentsChannel: HideFromRecentsChannel = HideFromRecentsChannel()
    private var appUpdateChannel: AppUpdateChannel = AppUpdateChannel()
    private var privacyConsentChannel: PrivacyConsentChannel = PrivacyConsentChannel()
    private var openClawCredentialChannel: OpenClawCredentialChannel = OpenClawCredentialChannel()

    private var uiRouterChannel: UIRouterChannel = UIRouterChannel()

    private var mcpServerChannel: McpServerChannel = McpServerChannel()
    private var remoteMcpConfigChannel: RemoteMcpConfigChannel = RemoteMcpConfigChannel()
    private var overlayChannel: OverlayChannel = OverlayChannel()
    private var browserSessionChannel: BrowserSessionChannel = BrowserSessionChannel()
    private var storageUsageChannel: StorageUsageChannel = StorageUsageChannel()
    private var agentRuntimeChannel: AgentRuntimeChannel = AgentRuntimeChannel()
    private var accountChannel: AccountChannel = AccountChannel()
    fun getUIRouterChannel(): UIRouterChannel {
        return uiRouterChannel
    }
    fun getAssistsCoreChannel(): AssistsCoreChannel {
        return assistsCoreChannel
    }

    fun configureFlutterEngine(flutterEngine: FlutterEngine
    ) {
        specialPermissionChannel.setChannel(flutterEngine)
        assistsCoreChannel.setChannel( flutterEngine)
        httpChannel.setChannel(flutterEngine)
        cacheChannel.setChannel(flutterEngine);
        voicePlaybackChannel.setChannel(flutterEngine)
        speechTranscriptionChannel.setChannel(flutterEngine)
        deviceInfoChannel.setChannel(flutterEngine)
        appStateChannel.setChannel(flutterEngine)
        fileSaveChannel.setChannel(flutterEngine)
        pdfPreviewChannel.setChannel(flutterEngine)
        hideFromRecentsChannel.setChannel(flutterEngine)
        appUpdateChannel.setChannel(flutterEngine)
        privacyConsentChannel.setChannel(flutterEngine)
        openClawCredentialChannel.setChannel(flutterEngine)
        uiRouterChannel.setChannel(flutterEngine)
        mcpServerChannel.setChannel(flutterEngine)
        remoteMcpConfigChannel.setChannel(flutterEngine)
        overlayChannel.setChannel(flutterEngine)
        browserSessionChannel.setChannel(flutterEngine)
        storageUsageChannel.setChannel(flutterEngine)
        agentRuntimeChannel.setChannel(flutterEngine)
        accountChannel.setChannel(flutterEngine)
    }

    fun onCreate(context: Context) {
        specialPermissionChannel.onCreate(context)
        assistsCoreChannel.onCreate(context)
        voicePlaybackChannel.onCreate(context)
        speechTranscriptionChannel.onCreate(context)
        deviceInfoChannel.onCreate(context)
        appStateChannel.onCreate(context)
        fileSaveChannel.onCreate(context)
        pdfPreviewChannel.onCreate(context)
        hideFromRecentsChannel.onCreate(context)
        appUpdateChannel.onCreate(context)
        privacyConsentChannel.onCreate(context)
        mcpServerChannel.onCreate(context)
        remoteMcpConfigChannel.onCreate()
        overlayChannel.onCreate(context)
        storageUsageChannel.onCreate(context)
        agentRuntimeChannel.onCreate(context)
    }

    fun clearChannel() {
        specialPermissionChannel.clear()
        assistsCoreChannel.clear()
        voicePlaybackChannel.clear()
        speechTranscriptionChannel.clear()
        deviceInfoChannel.clear()
        appStateChannel.clear()
        fileSaveChannel.clear()
        pdfPreviewChannel.clear()
        hideFromRecentsChannel.clear()
        appUpdateChannel.clear()
        privacyConsentChannel.clear()
        openClawCredentialChannel.clear()
        uiRouterChannel.clear()
        cacheChannel.clear()
        httpChannel.clear()
        mcpServerChannel.clear()
        remoteMcpConfigChannel.clear()
        overlayChannel.clear()
        browserSessionChannel.clear()
        storageUsageChannel.clear()
        agentRuntimeChannel.clear()
        accountChannel.clear()
    }


}
