package cn.com.omnimind.bot.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.McpFileInbox
import cn.com.omnimind.bot.share.SharedOpenPreferenceStore
import cn.com.omnimind.bot.share.SharedOpenDraftStore
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class McpFileReceiverActivity : ComponentActivity() {
    companion object {
        private const val TAG = "McpFileReceiver"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }

        val sharedText = extractSharedText(intent)
        val uris = extractUris(intent)
        val mimeTypeHint = intent.type
        if (uris.isEmpty() && sharedText.isNullOrBlank()) {
            OmniLog.w(TAG, "No share content found in intent: ${intent.action}")
            Toast.makeText(this, "未找到可分享的内容", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (uris.isEmpty()) {
            handleDraftShare(
                sharedText = sharedText,
                imageUris = emptyList(),
                mimeTypeHint = mimeTypeHint,
            )
            return
        }

        val imageOpenMode = SharedOpenPreferenceStore.getImageOpenMode(this)
        val fileOpenMode = SharedOpenPreferenceStore.getFileOpenMode(this)
        val imageUris = uris.filter { uri -> isImageUri(uri, mimeTypeHint) }
        val fileUris = uris.filterNot { uri -> isImageUri(uri, mimeTypeHint) }

        val draftImageUris = if (imageOpenMode == SharedOpenPreferenceStore.MODE_WORKSPACE) {
            emptyList()
        } else {
            imageUris
        }
        val workspaceUris = buildList {
            if (imageOpenMode == SharedOpenPreferenceStore.MODE_WORKSPACE) {
                addAll(imageUris)
            }
            if (fileOpenMode == SharedOpenPreferenceStore.MODE_WORKSPACE) {
                addAll(fileUris)
            }
        }
        val fileTransferUris = if (fileOpenMode == SharedOpenPreferenceStore.MODE_WORKSPACE) {
            emptyList()
        } else {
            fileUris
        }

        if (draftImageUris.isNotEmpty() || workspaceUris.isNotEmpty()) {
            handleDraftAndOptionalFileTransfer(
                sharedText = sharedText,
                imageUris = draftImageUris,
                workspaceUris = workspaceUris,
                fileTransferUris = fileTransferUris,
                mimeTypeHint = mimeTypeHint,
            )
            return
        }

        handleFileTransfer(fileTransferUris, mimeTypeHint)
    }

    private fun handleDraftShare(
        sharedText: String?,
        imageUris: List<Uri>,
        mimeTypeHint: String?,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val draft = SharedOpenDraftStore.store(
                context = this@McpFileReceiverActivity,
                text = sharedText,
                imageUris = imageUris,
                mimeTypeHint = mimeTypeHint,
            )
            withContext(Dispatchers.Main) {
                if (draft != null) {
                    val route =
                        "/home/chat?conversationId=new&mode=normal&requestKey=${Uri.encode(draft.requestKey)}"
                    TaskCompletionNavigator.navigateToMainRoute(
                        context = this@McpFileReceiverActivity,
                        route = route,
                        needClear = false,
                    )
                    Toast.makeText(
                        this@McpFileReceiverActivity,
                        "已填入新对话，请确认后发送",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        this@McpFileReceiverActivity,
                        "分享内容处理失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                finish()
            }
        }
    }

    private fun handleWorkspaceDraftShare(
        sharedText: String?,
        uris: List<Uri>,
        mimeTypeHint: String?,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val draft = SharedOpenDraftStore.storeWorkspaceDraft(
                context = this@McpFileReceiverActivity,
                text = sharedText,
                uris = uris,
                mimeTypeHint = mimeTypeHint,
            )
            withContext(Dispatchers.Main) {
                if (draft != null) {
                    val route =
                        "/home/chat?conversationId=new&mode=normal&requestKey=${Uri.encode(draft.requestKey)}"
                    TaskCompletionNavigator.navigateToMainRoute(
                        context = this@McpFileReceiverActivity,
                        route = route,
                        needClear = false,
                    )
                    Toast.makeText(
                        this@McpFileReceiverActivity,
                        "已添加到 Workspace，请确认后发送",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        this@McpFileReceiverActivity,
                        "分享内容处理失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                finish()
            }
        }
    }

    private fun handleDraftAndOptionalFileTransfer(
        sharedText: String?,
        imageUris: List<Uri>,
        workspaceUris: List<Uri>,
        fileTransferUris: List<Uri>,
        mimeTypeHint: String?,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val receivedFileCount = storeFileTransfers(fileTransferUris, mimeTypeHint)
            val draft = SharedOpenDraftStore.storeMixedDraft(
                context = this@McpFileReceiverActivity,
                text = sharedText,
                imageUris = imageUris,
                workspaceUris = workspaceUris,
                mimeTypeHint = mimeTypeHint,
            )
            withContext(Dispatchers.Main) {
                if (draft != null) {
                    val route =
                        "/home/chat?conversationId=new&mode=normal&requestKey=${Uri.encode(draft.requestKey)}"
                    TaskCompletionNavigator.navigateToMainRoute(
                        context = this@McpFileReceiverActivity,
                        route = route,
                        needClear = false,
                    )
                    val message = when {
                        workspaceUris.isNotEmpty() && receivedFileCount > 0 ->
                            "已添加到 Workspace，并接收 ${receivedFileCount} 个文件"
                        workspaceUris.isNotEmpty() -> "已添加到 Workspace，请确认后发送"
                        receivedFileCount > 0 -> "已填入新对话，并接收 ${receivedFileCount} 个文件"
                        else -> "已填入新对话，请确认后发送"
                    }
                    Toast.makeText(
                        this@McpFileReceiverActivity,
                        message,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (receivedFileCount > 0) {
                    Toast.makeText(
                        this@McpFileReceiverActivity,
                        if (receivedFileCount == 1) "文件接收成功" else "已接收 ${receivedFileCount} 个文件",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        this@McpFileReceiverActivity,
                        "分享内容处理失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                finish()
            }
        }
    }

    private fun handleFileTransfer(uris: List<Uri>, mimeTypeHint: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val receivedFileCount = storeFileTransfers(uris, mimeTypeHint)
            withContext(Dispatchers.Main) {
                if (receivedFileCount > 0) {
                    val message = if (receivedFileCount == 1) {
                        "文件接收成功"
                    } else {
                        "已接收 ${receivedFileCount} 个文件"
                    }
                    Toast.makeText(this@McpFileReceiverActivity, message, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@McpFileReceiverActivity, "文件接收失败", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
    }

    private fun storeFileTransfers(uris: List<Uri>, mimeTypeHint: String?): Int {
        if (uris.isEmpty()) return 0
        val records = uris.mapNotNull { uri ->
            McpFileInbox.storeFromUri(this@McpFileReceiverActivity, uri, mimeTypeHint)
        }
        if (records.isEmpty()) return 0
        val fileNames = records.map { it.fileName }.distinct()

        return records.size
    }

    private fun extractUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_SEND -> extractSendUri(intent)?.let { listOf(it) } ?: extractClipUris(intent)
            Intent.ACTION_SEND_MULTIPLE -> extractSendUris(intent).ifEmpty { extractClipUris(intent) }
            Intent.ACTION_VIEW -> intent.data?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun extractSendUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractSendUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
        }
    }

    private fun extractClipUris(intent: Intent): List<Uri> {
        val clipData = intent.clipData ?: return emptyList()
        val uris = ArrayList<Uri>(clipData.itemCount)
        for (index in 0 until clipData.itemCount) {
            clipData.getItemAt(index)?.uri?.let { uris.add(it) }
        }
        return uris
    }

    private fun extractSharedText(intent: Intent): String? {
        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.trim()
            ?.ifEmpty { null }
    }

    private fun isImageUri(uri: Uri, mimeTypeHint: String?): Boolean {
        val resolvedMimeType = contentResolver.getType(uri)
            ?: mimeTypeHint?.takeIf { !it.equals("*/*", ignoreCase = true) }
            ?: guessMimeTypeFromUri(uri)
        return resolvedMimeType?.startsWith("image/", ignoreCase = true) == true
    }

    private fun guessMimeTypeFromUri(uri: Uri): String? {
        val lastSegment = uri.lastPathSegment ?: return null
        val extension = MimeTypeMap.getFileExtensionFromUrl(lastSegment)
            ?.lowercase()
            ?.ifEmpty { null }
            ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
}
