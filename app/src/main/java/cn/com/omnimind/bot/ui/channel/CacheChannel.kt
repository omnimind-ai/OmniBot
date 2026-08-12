package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.database.DatabaseHelper
import com.tencent.mmkv.MMKV
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 历史缓存数据
 */
class CacheChannel {
    var TAG = "[CacheChannel]"
    private val EVENT_CHANNEL = "cn.com.omnimind.bot/CacheDataEvent" // Flutter 事件通道
    private var channel: MethodChannel? = null

    private var mainJob: CoroutineScope = CoroutineScope(Dispatchers.Main)

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        )
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "doMMKVEncodeString" -> {
                    call.argument<String>("value")?.let {
                        MMKV.defaultMMKV().encode(
                            call.argument<String>("key"),
                            it
                        )
                        result.success(true)
                        return@setMethodCallHandler
                    }

                    result.error(
                        "NATIVE_CACHE_ERROR",
                        "未找到对应的值类型,请检查是否为空或者类型格式",
                        null
                    )
                }

                "doMMKVEncodeInt" -> {
                    call.argument<Number>("value")?.toLong()?.let {
                        MMKV.defaultMMKV().encode(
                            call.argument<String>("key"),
                            it
                        )
                        result.success(true)
                        return@setMethodCallHandler

                    }
                    result.error(
                        "NATIVE_CACHE_ERROR",
                        "未找到对应的值类型,请检查是否为空或者类型格式",
                        null
                    )

                }

                "doMMKVEncodeBool" -> {
                    call.argument<Boolean>("value")?.let {
                        MMKV.defaultMMKV().encode(
                            call.argument<String>("key"),
                            it
                        )
                        result.success(true)
                        return@setMethodCallHandler

                    }
                    result.error(
                        "NATIVE_CACHE_ERROR",
                        "未找到对应的值类型,请检查是否为空或者类型格式",
                        null
                    )
                }

                "doMMKVEncodeDouble" -> {
                    call.argument<Double>("value")?.let {
                        MMKV.defaultMMKV().encode(
                            call.argument<String>("key"),
                            it
                        )
                        result.success(true)
                        return@setMethodCallHandler

                    }
                    result.error(
                        "NATIVE_CACHE_ERROR",
                        "未找到对应的值类型,请检查是否为空或者类型格式",
                        null
                    )
                }

                "doMMKVDecodeString" -> {
                    result.success(
                        MMKV.defaultMMKV()?.decodeString(
                            call.argument<String>("key") ?: "",
                            call.argument<String>("defaultValue") ?: ""
                        )
                    )
                }

                "doMMKVDecodeBoole" -> {
                    result.success(
                        MMKV.defaultMMKV()?.decodeBool(
                            call.argument<String>("key") ?: "",
                            call.argument<Boolean>("defaultValue") ?: false
                        )
                    )
                }

                "doMMKVDecodeInt" -> {
                    result.success(
                        MMKV.defaultMMKV()?.decodeLong(
                            call.argument<String>("key") ?: "",
                            call.argument<Number>("defaultValue")?.toLong() ?: 0L
                        )
                    )
                }

                "doMMKVDecodeDouble" -> {
                    result.success(
                        MMKV.defaultMMKV()?.decodeDouble(
                            call.argument<String>("key") ?: "",
                            call.argument<Double>("defaultValue") ?: 0.0
                        )
                    )
                }


                // AppIcons相关方法
                "getAppIconByPackageName" -> {
                    mainJob.launch {
                        try {
                            val packageName = call.argument<String>("packageName") ?: ""
                            val appIcon = withContext(Dispatchers.IO) {
                                DatabaseHelper.getAppIconByPackageName(packageName)
                            }
                            result.success(appIcon?.let {
                                mapOf(
                                    "id" to it.id,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "icon_base64" to it.icon_base64,
                                    "icon_path" to it.icon_path,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "GET_APP_ICON_ERROR", e)
                        }
                    }
                }

                "getAppIconsByPackageNames" -> {
                    mainJob.launch {
                        try {
                            val packageNames = call.argument<List<String>>("packageNames") ?: emptyList()
                            val appIcons = withContext(Dispatchers.IO) {
                                DatabaseHelper.getAppIconsByPackageNames(packageNames)
                            }
                            result.success(appIcons.map {
                                mapOf(
                                    "id" to it.id,
                                    "appName" to it.appName,
                                    "packageName" to it.packageName,
                                    "icon_base64" to it.icon_base64,
                                    "icon_path" to it.icon_path,
                                    "createdAt" to it.createdAt,
                                    "updatedAt" to it.updatedAt
                                )
                            })
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "GET_APP_ICONS_ERROR", e)
                        }
                    }
                }

                // 新增插入方法
                "insertAppIcon" -> {
                    mainJob.launch {
                        try {
                            val appName = call.argument<String>("appName") ?: ""
                            val packageName = call.argument<String>("packageName") ?: ""
                            val iconBase64 = call.argument<String>("icon_base64") ?: ""
                            val iconPath = call.argument<String>("icon_path") ?: ""
                            val success = withContext(Dispatchers.IO) {
                                DatabaseHelper.insertAppIcon(appName, packageName, iconBase64)
                            }
                            result.success(success)
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "INSERT_APP_ICON_ERROR", e)
                        }
                    }
                }

                // Message相关方法
                "insertMessage" -> {
                    mainJob.launch {
                        try {
                            val messageId = call.argument<String>("messageId") ?: ""
                            val type = call.argument<Int>("type") ?: 1
                            val user = call.argument<Int>("user") ?: 1
                            val content = call.argument<String>("content") ?: ""

                            val message = cn.com.omnimind.baselib.database.Message(
                                id = 0,
                                messageId = messageId,
                                type = type,
                                user = user,
                                content = content,
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )

                            val id = withContext(Dispatchers.IO) {
                                DatabaseHelper.insertMessage(message)
                            }
                            result.success(id)
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "INSERT_MESSAGE_ERROR", e)
                        }
                    }
                }

                "updateMessage" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()
                            val messageId = call.argument<String>("messageId") ?: ""
                            val type = call.argument<Int>("type") ?: 1
                            val user = call.argument<Int>("user") ?: 1
                            val content = call.argument<String>("content") ?: ""
                            val createdAt =
                                call.argument<Long>("createdAt") ?: System.currentTimeMillis()

                            val message = cn.com.omnimind.baselib.database.Message(
                                id = id,
                                messageId = messageId,
                                type = type,
                                user = user,
                                content = content,
                                createdAt = createdAt,
                                updatedAt = System.currentTimeMillis()
                            )

                            withContext(Dispatchers.IO) {
                                DatabaseHelper.updateMessage(message)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "UPDATE_MESSAGE_ERROR", e)
                        }
                    }
                }

                "getMessageById" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<Number>("id")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()

                            val message = withContext(Dispatchers.IO) {
                                DatabaseHelper.getMessageById(id)
                            }
                            if (message != null) {
                                result.success(
                                    mapOf(
                                        "id" to message.id,
                                        "messageId" to message.messageId,
                                        "type" to message.type,
                                        "user" to message.user,
                                        "content" to message.content,
                                        "createdAt" to message.createdAt,
                                        "updatedAt" to message.updatedAt
                                    )
                                )
                            } else {
                                result.success(null)
                            }
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "GET_MESSAGE_BY_ID_ERROR", e)
                        }
                    }
                }

                "getMessagesByPage" -> {
                    mainJob.launch {
                        try {
                            val pageNum = call.argument<Int>("page") ?: 0
                            val pageSize = call.argument<Int>("pageSize") ?: 20

                            val pagedResult = withContext(Dispatchers.IO) {
                                DatabaseHelper.getMessagesByPage(pageNum, pageSize)
                            }
                            result.success(
                                mapOf(
                                    "messageList" to pagedResult.messageList.map {
                                        mapOf(
                                            "id" to it.id,
                                            "messageId" to it.messageId,
                                            "type" to it.type,
                                            "user" to it.user,
                                            "content" to it.content,
                                            "createdAt" to it.createdAt,
                                            "updatedAt" to it.updatedAt
                                        )
                                    },
                                    "hasMore" to pagedResult.hasMore
                                )
                            )
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "GET_MESSAGES_BY_PAGE_ERROR", e)
                        }
                    }
                }

                "deleteMessageById" -> {
                    mainJob.launch {
                        try {
                            val idNum = call.argument<String>("ids")
                                ?: return@launch result.error("ARG_ERROR", "id 不能为空", null)
                            val id = idNum.toLong()

                            withContext(Dispatchers.IO) {
                                DatabaseHelper.deleteMessageById(id)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "DELETE_MESSAGE_BY_ID_ERROR", e)
                        }
                    }
                }

                "deleteAllMessages" -> {
                    mainJob.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                DatabaseHelper.deleteAllMessages()
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            NativeChannelErrorPrivacy.deliver(result, TAG, "DELETE_ALL_MESSAGES_ERROR", e)
                        }
                    }
                }

                "getPagedConversations" -> {
                    result.error(
                        "NOT_IMPLEMENTED",
                        "getPagedConversations is not implemented in CacheChannel",
                        null
                    )
                }

                "getPagedMessages" -> {
                    result.error(
                        "NOT_IMPLEMENTED",
                        "getPagedMessages is not implemented in CacheChannel",
                        null
                    )
                }

                else -> result.notImplemented()
            }
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
