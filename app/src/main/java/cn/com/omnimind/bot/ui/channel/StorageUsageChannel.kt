package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.storage.StorageUsageRepository
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Flutter adapter; storage analysis and cleanup live in StorageUsageRepository. */
class StorageUsageChannel {
    private var context: Context? = null
    private var channel: MethodChannel? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun onCreate(context: Context) { this.context = context.applicationContext }

    fun setChannel(engine: FlutterEngine) {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, "cn.com.omnimind.bot/StorageUsage")
        channel?.setMethodCallHandler(::handle)
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
        context = null
        scope.cancel()
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        if (call.method !in setOf("getStorageUsageSummary", "clearStorageUsageCategory", "applyStorageCleanupStrategy")) {
            result.notImplemented()
            return
        }
        val errorCode = when (call.method) {
            "clearStorageUsageCategory" -> "STORAGE_CLEAR_FAILED"
            "applyStorageCleanupStrategy" -> "STORAGE_STRATEGY_FAILED"
            else -> "STORAGE_ANALYZE_FAILED"
        }
        val owner = context?.let(StorageUsageRepository::get)
        if (owner == null) {
            result.error(errorCode, "Storage owner is not initialized", null)
            return
        }
        scope.launch {
            try {
                when (call.method) {
                    "getStorageUsageSummary" -> result.success(owner.summary())
                    "clearStorageUsageCategory" -> result.success(owner.clear(
                        call.argument<String>("categoryId")?.trim().orEmpty(),
                        call.argument<Number>("olderThanDays")?.toInt(),
                    ))
                    "applyStorageCleanupStrategy" -> result.success(owner.applyStrategy(
                        call.argument<String>("strategyId")?.trim().orEmpty(),
                        call.argument<Number>("olderThanDays")?.toInt(),
                        call.argument<Number>("targetReleaseBytes")?.toLong() ?: 0L,
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("StorageUsageChannel", "Storage operation failed", error)
                result.error(errorCode, error.message, null)
            }
        }
    }
}
