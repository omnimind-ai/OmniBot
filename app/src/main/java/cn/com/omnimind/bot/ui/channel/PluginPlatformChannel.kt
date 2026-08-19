package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfigStore
import cn.com.omnimind.baselib.llm.OfficialVlmOperationRouteResolver
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.OmniPluginState
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginBridgeRuntime
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

class PluginPlatformChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var channel: MethodChannel? = null

    fun onCreate(context: Context) {
        this.context = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(::handleMethodCall)
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val safeContext = context
        if (safeContext == null) {
            result.error("PLUGIN_CONTEXT_ERROR", "Plugin platform is not initialized", null)
            return
        }
        val host = OmniPluginHost.get(safeContext)
        scope.launch {
            runCatching {
                when (call.method) {
                    "list" -> host.list().map(::stateToMap)
                    "install" -> stateToMap(host.install(call.requirePluginId()))
                    "update" -> stateToMap(host.update(call.requirePluginId()))
                    "setEnabled" -> stateToMap(
                        host.setEnabled(
                            pluginId = call.requirePluginId(),
                            enabled = call.argument<Boolean>("enabled")
                                ?: throw IllegalArgumentException("enabled is required")
                        )
                    )
                    "getVlmReadiness" -> vlmReadiness()
                    "sandboxInvoke" -> {
                        val pluginId = call.requirePluginId()
                        SandboxPluginBridgeRuntime(safeContext).invoke(
                            pluginId = pluginId,
                            method = call.argument<String>("method")?.trim().orEmpty(),
                            params = call.argument<Map<*, *>>("params") ?: emptyMap<Any?, Any?>(),
                        )
                    }
                    "uninstall" -> {
                        val pluginId = call.requirePluginId()
                        host.uninstall(pluginId)
                        true
                    }
                    else -> throw NotImplementedError(call.method)
                }
            }.onSuccess(result::success).onFailure { error ->
                if (error is NotImplementedError) {
                    result.notImplemented()
                } else {
                    result.error(
                        "PLUGIN_PLATFORM_CALL_FAILED",
                        error.message ?: error.javaClass.simpleName,
                        null
                    )
                }
            }
        }
    }

    private fun MethodCall.requirePluginId(): String {
        return argument<String>("pluginId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("pluginId is required")
    }

    private suspend fun requireEnabled(host: OmniPluginHost, pluginId: String) {
        val state = host.list().firstOrNull { it.descriptor.id == pluginId }
            ?: throw IllegalArgumentException("Unknown plugin: $pluginId")
        require(state.installed && state.enabled) {
            "Plugin must be installed and enabled: $pluginId"
        }
    }

    private fun stateToMap(state: OmniPluginState): Map<String, Any?> {
        val descriptor = state.descriptor
        return mapOf(
            "id" to descriptor.id,
            "name" to descriptor.name,
            "version" to descriptor.version,
            "interfaceVersion" to descriptor.interfaceVersion,
            "description" to descriptor.description,
            "publisher" to descriptor.publisher,
            "kind" to descriptor.kind.wireName,
            "downloadSizeBytes" to descriptor.downloadSizeBytes,
            "capabilities" to descriptor.capabilities,
            "required" to descriptor.required,
            "settingsSchema" to descriptor.settingsSchema.toPlatformValue(),
            "presentation" to descriptor.presentation.toPlatformValue(),
            "installed" to state.installed,
            "enabled" to state.enabled,
            "compatible" to state.compatible,
            "errorMessage" to state.errorMessage
        )
    }

    private fun vlmReadiness(): Map<String, Any?> {
        val binding = SceneModelBindingStore.getBinding(SceneOperationConfigStore.SCENE_ID)
        val boundProfile = binding
            ?.providerProfileId
            ?.let(ModelProviderConfigStore::getProfile)
            ?.takeIf { it.isConfigured() }
        val officialConfig = OfficialVlmOperationConfigStore.getConfig()
        val configured = boundProfile != null || officialConfig.isConfigured()
        return mapOf(
            "debugBuild" to BuildConfig.DEBUG,
            "providerConfigured" to configured,
            "providerName" to when {
                boundProfile != null -> boundProfile.name
                officialConfig.isConfigured() -> OfficialVlmOperationRouteResolver.PROFILE_NAME
                else -> ""
            },
            "model" to when {
                boundProfile != null -> binding.modelId
                officialConfig.isConfigured() -> officialConfig.model
                else -> ""
            },
        )
    }

    private fun JsonElement.toPlatformValue(): Any? {
        return when (this) {
            JsonNull -> null
            is JsonObject -> entries.associate { (key, value) -> key to value.toPlatformValue() }
            is JsonArray -> map { it.toPlatformValue() }
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                doubleOrNull != null -> doubleOrNull
                else -> content
            }
        }
    }

    private companion object {
        const val CHANNEL_NAME = "cn.com.omnimind.bot/PluginPlatform"
    }
}
