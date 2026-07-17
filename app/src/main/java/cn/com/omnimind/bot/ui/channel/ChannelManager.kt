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
    private var deviceInfoChannel: DeviceInfoChannel = DeviceInfoChannel()
    private var appStateChannel: AppStateChannel = AppStateChannel()
    private var fileSaveChannel: FileSaveChannel = FileSaveChannel()
    private var pdfPreviewChannel: PdfPreviewChannel = PdfPreviewChannel()
    private var hideFromRecentsChannel: HideFromRecentsChannel = HideFromRecentsChannel()
    private var appUpdateChannel: AppUpdateChannel = AppUpdateChannel()

    private var uiRouterChannel: UIRouterChannel = UIRouterChannel()

    private var mcpServerChannel: McpServerChannel = McpServerChannel()
    private var remoteMcpConfigChannel: RemoteMcpConfigChannel = RemoteMcpConfigChannel()
    private var overlayChannel: OverlayChannel = OverlayChannel()
    private var browserSessionChannel: BrowserSessionChannel = BrowserSessionChannel()
    private var storageUsageChannel: StorageUsageChannel = StorageUsageChannel()
    private var codexAppServerChannel: CodexAppServerChannel = CodexAppServerChannel()
    private var alpineFileSystemChannel: AlpineFileSystemChannel = AlpineFileSystemChannel()
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
        deviceInfoChannel.setChannel(flutterEngine)
        appStateChannel.setChannel(flutterEngine)
        fileSaveChannel.setChannel(flutterEngine)
        pdfPreviewChannel.setChannel(flutterEngine)
        hideFromRecentsChannel.setChannel(flutterEngine)
        appUpdateChannel.setChannel(flutterEngine)
        uiRouterChannel.setChannel(flutterEngine)
        mcpServerChannel.setChannel(flutterEngine)
        remoteMcpConfigChannel.setChannel(flutterEngine)
        overlayChannel.setChannel(flutterEngine)
        browserSessionChannel.setChannel(flutterEngine)
        storageUsageChannel.setChannel(flutterEngine)
        codexAppServerChannel.setChannel(flutterEngine)
        alpineFileSystemChannel.setChannel(flutterEngine)
    }

    fun onCreate(context: Context) {
        specialPermissionChannel.onCreate(context)
        assistsCoreChannel.onCreate(context)
        voicePlaybackChannel.onCreate(context)
        deviceInfoChannel.onCreate(context)
        appStateChannel.onCreate(context)
        fileSaveChannel.onCreate(context)
        pdfPreviewChannel.onCreate(context)
        hideFromRecentsChannel.onCreate(context)
        appUpdateChannel.onCreate(context)
        mcpServerChannel.onCreate(context)
        remoteMcpConfigChannel.onCreate()
        overlayChannel.onCreate(context)
        storageUsageChannel.onCreate(context)
        codexAppServerChannel.onCreate(context)
        alpineFileSystemChannel.onCreate(context)
    }

    fun clearChannel() {
        specialPermissionChannel.clear()
        assistsCoreChannel.clear()
        voicePlaybackChannel.clear()
        deviceInfoChannel.clear()
        appStateChannel.clear()
        fileSaveChannel.clear()
        pdfPreviewChannel.clear()
        hideFromRecentsChannel.clear()
        appUpdateChannel.clear()
        uiRouterChannel.clear()
        cacheChannel.clear()
        httpChannel.clear()
        mcpServerChannel.clear()
        remoteMcpConfigChannel.clear()
        overlayChannel.clear()
        browserSessionChannel.clear()
        storageUsageChannel.clear()
        codexAppServerChannel.clear()
        alpineFileSystemChannel.clear()
    }


}
