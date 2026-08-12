package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationMutationResult
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationSnapshot
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStatus
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStore
import cn.com.omnimind.assists.openclaw.OpenClawCredentialMutation
import cn.com.omnimind.assists.openclaw.OpenClawDeviceIdentity
import cn.com.omnimind.bot.util.AssistsUtil
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter receives only secret-free state. Endpoint, credential, user id and consent are committed
 * as one native transaction, and all mutating calls return stable status codes only.
 */
class OpenClawCredentialChannel {
    private var channel: MethodChannel? = null

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).apply {
            setMethodCallHandler { call, result ->
                try {
                    handle(call, result)
                } catch (_: Exception) {
                    result.success(statusPayload(OpenClawConfigurationStatus.STORAGE_UNAVAILABLE))
                }
            }
        }
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getConfiguration" -> result.success(
                snapshotPayload(OpenClawConfigurationStore.snapshot()),
            )
            "prepareDestination" -> {
                val plan = OpenClawConfigurationStore.prepareDestination(
                    call.argument<String>("baseUrl").orEmpty(),
                )
                result.success(
                    if (plan == null) {
                        statusPayload(OpenClawConfigurationStatus.INVALID_ENDPOINT)
                    } else {
                        mapOf(
                            "success" to true,
                            "status" to OpenClawConfigurationStatus.SUCCESS.name.lowercase(),
                            "requestId" to plan.requestId,
                            "baseUrl" to plan.baseUrl,
                            "canonicalOrigin" to plan.canonicalOrigin,
                            "expectedGeneration" to plan.expectedGeneration,
                        )
                    },
                )
            }
            "saveConfirmedConfiguration" -> {
                val mutation = when (call.argument<String>("credentialAction")) {
                    "keep" -> OpenClawCredentialMutation.KEEP
                    "replace" -> OpenClawCredentialMutation.REPLACE
                    "clear" -> OpenClawCredentialMutation.CLEAR
                    else -> {
                        result.success(statusPayload(OpenClawConfigurationStatus.INVALID_ARGUMENT))
                        return
                    }
                }
                val saved = AssistsUtil.Core.saveOpenClawConfiguration(
                    requestId = call.argument<String>("requestId").orEmpty(),
                    expectedGeneration = call.argument<Number>("expectedGeneration")
                        ?.toLong() ?: -1L,
                    confirmedOrigin = call.argument<String>("confirmedOrigin").orEmpty(),
                    rawBaseUrl = call.argument<String>("baseUrl").orEmpty(),
                    userId = call.argument<String>("userId").orEmpty(),
                    credentialMutation = mutation,
                    replacementToken = call.argument<String>("replacementToken"),
                    enable = call.argument<Boolean>("enable") == true,
                )
                result.success(mutationPayload(saved))
            }
            "disable" -> result.success(
                mutationPayload(AssistsUtil.Core.disableOpenClaw()),
            )
            "migrateLegacyInactive" -> {
                val migrated = OpenClawConfigurationStore.migrateLegacyInactive(
                    rawBaseUrl = call.argument<String>("baseUrl").orEmpty(),
                    legacyGatewayToken = call.argument<String>("gatewayToken"),
                    userId = call.argument<String>("userId").orEmpty(),
                )
                result.success(mutationPayload(migrated))
            }
            "isAuthorized" -> {
                val candidate = TaskParams.OpenClawConfig(
                    baseUrl = call.argument<String>("baseUrl").orEmpty(),
                    userId = call.argument<String>("userId"),
                    sessionKey = null,
                    generation = call.argument<Number>("generation")?.toLong() ?: -1L,
                    canonicalOrigin = call.argument<String>("canonicalOrigin").orEmpty(),
                )
                result.success(OpenClawConfigurationStore.isAuthorized(candidate))
            }
            "hasExistingIdentity" -> result.success(
                OpenClawDeviceIdentity.hasExistingIdentity(),
            )
            "resetDeviceIdentity" -> {
                val reset = AssistsUtil.Core.resetOpenClawDeviceIdentity()
                result.success(
                    mapOf(
                        "success" to reset.success,
                        "status" to reset.status.name.lowercase(),
                    ),
                )
            }
            else -> result.notImplemented()
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private fun mutationPayload(
        mutation: OpenClawConfigurationMutationResult,
    ): Map<String, Any?> = mapOf(
        "success" to mutation.success,
        "status" to mutation.status.name.lowercase(),
        "configuration" to mutation.snapshot?.let(::snapshotPayload),
    )

    private fun snapshotPayload(snapshot: OpenClawConfigurationSnapshot): Map<String, Any> =
        mapOf(
            "configured" to snapshot.configured,
            "enabled" to snapshot.enabled,
            "baseUrl" to snapshot.baseUrl,
            "userId" to snapshot.userId,
            "generation" to snapshot.generation,
            "canonicalOrigin" to snapshot.allowedOrigin,
            "consentVersion" to snapshot.consentVersion,
            "hasGatewayToken" to snapshot.hasGatewayToken,
        )

    private fun statusPayload(status: OpenClawConfigurationStatus): Map<String, Any> = mapOf(
        "success" to false,
        "status" to status.name.lowercase(),
    )

    private companion object {
        const val CHANNEL = "cn.com.omnimind.bot/openclaw_credential"
    }
}
