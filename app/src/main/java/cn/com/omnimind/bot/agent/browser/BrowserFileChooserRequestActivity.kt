package cn.com.omnimind.bot.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object BrowserFileChooserCoordinator {
    private const val EXTRA_REQUEST_ID = "browser_file_chooser_request_id"
    private const val EXTRA_ALLOW_MULTIPLE = "browser_file_chooser_allow_multiple"
    private const val EXTRA_MIME_TYPES = "browser_file_chooser_mime_types"

    private val callbacks =
        ConcurrentHashMap<String, (Array<Uri>?) -> Unit>()

    fun requestFiles(
        context: Context,
        allowMultiple: Boolean,
        mimeTypes: Array<String>?,
        callback: (Array<Uri>?) -> Unit
    ) {
        val requestId = UUID.randomUUID().toString()
        callbacks[requestId] = callback
        val intent = Intent(context, BrowserFileChooserRequestActivity::class.java)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_ALLOW_MULTIPLE, allowMultiple)
            .putExtra(EXTRA_MIME_TYPES, mimeTypes)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun complete(
        requestId: String,
        uris: Array<Uri>?
    ) {
        callbacks.remove(requestId)?.invoke(uris)
    }

    fun cancel(requestId: String) {
        callbacks.remove(requestId)?.invoke(null)
    }

    fun requestIdExtra(): String = EXTRA_REQUEST_ID

    fun allowMultipleExtra(): String = EXTRA_ALLOW_MULTIPLE

    fun mimeTypesExtra(): String = EXTRA_MIME_TYPES
}

class BrowserFileChooserRequestActivity : ComponentActivity() {
    private var requestId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(cn.com.omnimind.baselib.R.style.Theme_OmnibotApp_Permission)
        requestId = intent.getStringExtra(BrowserFileChooserCoordinator.requestIdExtra()).orEmpty()
        if (requestId.isBlank()) {
            finishWithCancel()
            return
        }
        if (savedInstanceState == null) {
            val allowMultiple = intent.getBooleanExtra(BrowserFileChooserCoordinator.allowMultipleExtra(), false)
            val mimeTypes = intent.getStringArrayExtra(BrowserFileChooserCoordinator.mimeTypesExtra())
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mimeTypes.isNullOrEmpty()) "*/*" else mimeTypes.first()
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                },
                1001
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001) {
            finishWithCancel()
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            finishWithCancel()
            return
        }
        val uris = mutableListOf<Uri>()
        data.data?.let(uris::add)
        val clipData = data.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index)?.uri?.let(uris::add)
            }
        }
        BrowserFileChooserCoordinator.complete(requestId, uris.distinct().toTypedArray())
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (isFinishing && requestId.isNotBlank()) {
            BrowserFileChooserCoordinator.cancel(requestId)
        }
        super.onDestroy()
    }

    private fun finishWithCancel() {
        BrowserFileChooserCoordinator.cancel(requestId)
        finish()
        overridePendingTransition(0, 0)
    }
}
