package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.account.AccountApiException
import cn.com.omnimind.baselib.account.AccountCredentialStorageException
import cn.com.omnimind.baselib.account.AccountDeviceSession
import cn.com.omnimind.baselib.account.AccountException
import cn.com.omnimind.baselib.account.AccountNotAuthenticatedException
import cn.com.omnimind.baselib.account.AccountNotConfiguredException
import cn.com.omnimind.baselib.account.AccountProtocolException
import cn.com.omnimind.baselib.account.AccountUser
import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiSettings
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.PlatformUsageEntry
import cn.com.omnimind.baselib.account.PlatformGatewayNotConfiguredException
import cn.com.omnimind.baselib.account.PlatformModelsUnavailableException
import cn.com.omnimind.baselib.account.RegistrationCodeRequest
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.PlatformAiProvisioningStatus
import cn.com.omnimind.baselib.util.OmniLog
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountChannel {
    companion object {
        private const val TAG = "AccountChannel"
        private const val CHANNEL_NAME = "cn.com.omnimind.bot/account"
    }

    private var channel: MethodChannel? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(::handleMethodCall)
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getSessionState" -> launch(result) {
                mapOf(
                    "configured" to OmniAccount.isConfigured(),
                    "signedIn" to (OmniAccount.isConfigured() && OmniAccount.repository().isSignedIn()),
                )
            }

            "getAiRoutingState" -> launch(result) {
                var access = OmniAccount.currentAiRequestAccess()
                val provisioning = if (access.usesPlatform) {
                    PlatformAiProvisioner.synchronize()
                } else {
                    PlatformAiProvisioner.status()
                }
                access = OmniAccount.currentAiRequestAccess()
                val provisioningReason = if (access.usesPlatform && !provisioning.ready) {
                    provisioning.statusText
                } else {
                    null
                }
                mapOf(
                    "mode" to access.mode?.wireValue,
                    "ready" to (
                        access.unavailableReason.isNullOrBlank() &&
                            provisioningReason.isNullOrBlank()
                        ),
                    "usesPlatform" to access.usesPlatform,
                    "unavailableReason" to (access.unavailableReason ?: provisioningReason),
                )
            }

            "requestRegistrationCode" -> launch(result) {
                OmniAccount.repository()
                    .requestRegistrationCode(call.requiredString("email"))
                    .toPayload()
            }

            "requestPasswordResetCode" -> launch(result) {
                OmniAccount.repository()
                    .requestPasswordResetCode(call.requiredString("email"))
                    .toPayload()
            }

            "resetPassword" -> launch(result) {
                OmniAccount.repository().resetPassword(
                    email = call.requiredString("email"),
                    newPassword = call.requiredString("newPassword", trim = false),
                    verificationRequestId = call.requiredString("verificationRequestId"),
                    verificationCode = call.requiredString("verificationCode"),
                )
                null
            }

            "register" -> launch(result) {
                OmniAccount.repository().register(
                    email = call.requiredString("email"),
                    password = call.requiredString("password", trim = false),
                    verificationRequestId = call.requiredString("verificationRequestId"),
                    verificationCode = call.requiredString("verificationCode"),
                ).toPayload()
            }

            "login" -> launch(result) {
                val repository = OmniAccount.repository()
                val session = repository.login(
                    email = call.requiredString("email"),
                    password = call.requiredString("password", trim = false),
                )
                PlatformAiProvisioner.synchronize(forceRefresh = true)
                session.user.toPayload()
            }

            "logout" -> launch(result) {
                bestEffortDeactivatePlatformProvider()
                OmniAccount.repository().logout()
                null
            }

            "getOverview" -> launch(result) {
                val repository = OmniAccount.repository()
                val user = repository.currentUser()
                val settings = repository.getAiSettings()
                val provisioning = PlatformAiProvisioner.synchronize(settings)
                mapOf(
                    "user" to user.toPayload(),
                    "settings" to settings.toPayload(provisioning),
                )
            }

            "updateAiMode" -> launch(result) {
                val mode = when (call.requiredString("mode").lowercase()) {
                    AiAccessMode.PLATFORM.wireValue -> AiAccessMode.PLATFORM
                    AiAccessMode.BYOK.wireValue -> AiAccessMode.BYOK
                    else -> throw IllegalArgumentException("mode must be platform or byok")
                }
                val settings = OmniAccount.repository().updateAiSettings(mode)
                val provisioning = PlatformAiProvisioner.synchronize(settings)
                settings.toPayload(provisioning)
            }

            "changePassword" -> launch(result) {
                OmniAccount.repository().changePassword(
                    currentPassword = call.requiredString("currentPassword", trim = false),
                    newPassword = call.requiredString("newPassword", trim = false),
                )
                null
            }

            "listSessions" -> launch(result) {
                OmniAccount.repository().listSessions().map { it.toPayload() }
            }

            "revokeSession" -> launch(result) {
                OmniAccount.repository().revokeSession(call.requiredString("sessionId"))
                null
            }

            "revokeOtherSessions" -> launch(result) {
                mapOf("revoked" to OmniAccount.repository().revokeOtherSessions())
            }

            "listPlatformUsage" -> launch(result) {
                val limit = call.argument<Number>("limit")?.toInt() ?: 20
                OmniAccount.repository().listPlatformUsage(limit).map { it.toPayload() }
            }

            "deleteAccount" -> launch(result) {
                OmniAccount.repository().deleteAccount(
                    call.requiredString("currentPassword", trim = false)
                )
                // The server has already deleted the account and the repository
                // has cleared credentials. A best-effort local UI-binding cleanup
                // must not turn that confirmed success into a misleading error.
                bestEffortDeactivatePlatformProvider()
                null
            }

            else -> result.notImplemented()
        }
    }

    private fun launch(
        result: MethodChannel.Result,
        block: suspend () -> Any?,
    ) {
        scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { block() }
                result.success(payload)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                NativeChannelErrorPrivacy.deliverAccount(result, TAG, error)
            }
        }
    }

    private suspend fun bestEffortDeactivatePlatformProvider() {
        try {
            PlatformAiProvisioner.deactivate()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NativeChannelErrorPrivacy.record(TAG, "PLATFORM_PROVIDER_DEACTIVATE_FAILED", error)
        }
    }

    private fun MethodCall.requiredString(name: String, trim: Boolean = true): String {
        val raw = argument<String>(name).orEmpty()
        val value = if (trim) raw.trim() else raw
        if (value.isEmpty()) throw IllegalArgumentException("$name is required")
        return value
    }

    private fun RegistrationCodeRequest.toPayload(): Map<String, Any> = mapOf(
        "requestId" to requestId,
        "expiresInSeconds" to expiresInSeconds,
    )

    private fun AccountUser.toPayload(): Map<String, Any> = mapOf(
        "id" to id,
        "email" to email,
        "role" to role,
        "status" to status,
        "emailVerifiedAt" to emailVerifiedAt,
        "createdAt" to createdAt,
    )

    private fun AccountDeviceSession.toPayload(): Map<String, Any> = mapOf(
        "id" to id,
        "expiresAt" to expiresAt,
        "createdAt" to createdAt,
        "lastUsedAt" to lastUsedAt,
        "current" to current,
    )

    private fun PlatformUsageEntry.toPayload(): Map<String, Any> = mapOf(
        "model" to model,
        "promptTokens" to promptTokens,
        "completionTokens" to completionTokens,
        "totalTokens" to totalTokens,
        "quotaUsed" to quotaUsed,
        "createdAt" to createdAt,
    )

    private fun AiSettings.toPayload(
        provisioning: PlatformAiProvisioningStatus = PlatformAiProvisioner.status(),
    ): Map<String, Any> = mapOf(
        "mode" to effectiveMode.wireValue,
        "keyStorage" to keyStorage,
        "platformAvailable" to platformAvailable,
        "platformUnavailableReason" to platformUnavailableReason.orEmpty(),
        "platform" to mapOf(
            "platformEnabled" to platform.enabled,
            "balanceQuota" to platform.balance,
            "weeklyLimitQuota" to platform.weeklyLimit,
            "weeklyUsedQuota" to platform.weeklyUsed,
            "weeklyPeriodStart" to platform.weeklyPeriodStart.orEmpty(),
            "unit" to platform.unit,
        ),
        "updatedAt" to updatedAt,
        "officialProviderReady" to (
            effectiveMode != AiAccessMode.PLATFORM || provisioning.ready
            ),
        "officialProviderStatus" to provisioning.statusText,
    )
}

internal data class NativeChannelFailure(
    val code: String,
    val message: String,
    val details: Any? = null,
)

/**
 * Keeps native-channel failures useful without forwarding exception text, stack traces,
 * URLs, filesystem paths, response bodies, credentials, or other caller-controlled data.
 */
internal object NativeChannelErrorPrivacy {
    private val stableMessages = mapOf(
        "AGENT_RUNTIME_CALL_FAILED" to "Agent runtime operation failed.",
        "ANDROID_ID_ERROR" to "Failed to get Android ID.",
        "BROWSER_SESSION_CALL_FAILED" to "Browser session operation failed.",
        "CHECK_FAILED" to "Unable to check for updates.",
        "DELETE_ALL_MESSAGES_ERROR" to "Unable to delete messages.",
        "DELETE_MESSAGE_BY_ID_ERROR" to "Unable to delete the message.",
        "DEVICE_INFO_ERROR" to "Failed to get device info.",
        "GET_APP_ICONS_ERROR" to "Unable to read app icons.",
        "GET_APP_ICON_ERROR" to "Unable to read the app icon.",
        "GET_MESSAGES_BY_PAGE_ERROR" to "Unable to read messages.",
        "GET_MESSAGE_BY_ID_ERROR" to "Unable to read the message.",
        "HIDE_FROM_RECENTS_FAILED" to "Unable to update recent-app visibility.",
        "HIDE_PET_FAILED" to "Unable to hide the pet overlay.",
        "INIT_FAILED" to "Unable to start the requested file operation.",
        "INSERT_APP_ICON_ERROR" to "Unable to save the app icon.",
        "INSERT_MESSAGE_ERROR" to "Unable to save the message.",
        "INSTALL_FAILED" to "Unable to install the update.",
        "INVALID_PARAMETERS" to "The network request parameters are invalid.",
        "IP_ADDRESS_ERROR" to "Failed to get the IP address.",
        "MCP_ERROR" to "The local MCP operation failed.",
        "NETWORK_ERROR" to "The network request failed.",
        "OPEN_FAILED" to "Unable to open the file.",
        "PDF_INFO_FAILED" to "Unable to read PDF information.",
        "PDF_RENDER_FAILED" to "Unable to render the PDF page.",
        "PLATFORM_PROVIDER_DEACTIVATE_FAILED" to "Platform provider cleanup failed.",
        "SAVE_DIRECT_FAILED" to "Direct file save failed.",
        "SAVE_FAILED" to "Unable to save the file.",
        "SET_PET_IMAGE_FAILED" to "Unable to update the pet image.",
        "SHARE_FAILED" to "Unable to share the requested content.",
        "SHOW_MESSAGE_FAILED" to "Unable to show the overlay message.",
        "SHOW_PET_FAILED" to "Unable to show the pet overlay.",
        "UPDATE_MESSAGE_ERROR" to "Unable to update the message.",
        "VERSION_ERROR" to "Failed to get the app version.",
        "QUICK_LOG_WIDGET_REFRESH_FAILED" to "Quick-log widget refresh failed.",
        "WORKSPACE_DEFAULT_REFRESH_FAILED" to "Workspace default refresh failed.",
    )

    private val accountApiCodes = setOf(
        "account_service_unavailable",
        "cannot_revoke_current_session",
        "current_password_invalid",
        "email_already_registered",
        "internal_error",
        "invalid_access_token",
        "invalid_ai_mode",
        "invalid_credentials",
        "invalid_email",
        "invalid_limit",
        "invalid_password",
        "invalid_purpose",
        "invalid_refresh_token",
        "invalid_request",
        "invalid_session_id",
        "invalid_verification_code",
        "missing_access_token",
        "password_reset_failed",
        "password_reuse",
        "platform_ai_unavailable",
        "session_not_found",
        "too_many_requests",
        "user_disabled",
        "verification_unavailable",
    )

    private val accountMessages = mapOf(
        "ACCOUNT_CREDENTIAL_STORAGE_UNAVAILABLE" to "Secure account storage is unavailable.",
        "ACCOUNT_ERROR" to "The account operation failed.",
        "ACCOUNT_NOT_CONFIGURED" to "The account service is not configured.",
        "ACCOUNT_PROTOCOL_ERROR" to "The account service returned an invalid response.",
        "ACCOUNT_UNEXPECTED_ERROR" to "The account service is temporarily unavailable.",
        "INVALID_ARGUMENT" to "The account request is invalid.",
        "NOT_AUTHENTICATED" to "Sign in before using this account operation.",
        "PLATFORM_GATEWAY_NOT_CONFIGURED" to "The platform AI gateway is not configured.",
        "PLATFORM_MODELS_UNAVAILABLE" to "Official AI models are temporarily unavailable.",
        "account_service_unavailable" to "The account service is temporarily unavailable.",
        "cannot_revoke_current_session" to "The current session cannot be revoked here.",
        "current_password_invalid" to "The current password is incorrect.",
        "email_already_registered" to "The email address is already registered.",
        "internal_error" to "The account service is temporarily unavailable.",
        "invalid_access_token" to "The sign-in session has expired.",
        "invalid_ai_mode" to "The selected AI mode is invalid.",
        "invalid_credentials" to "The email address or password is incorrect.",
        "invalid_email" to "The email address is invalid.",
        "invalid_limit" to "The requested result limit is invalid.",
        "invalid_password" to "The password does not meet the security requirements.",
        "invalid_purpose" to "The verification purpose is invalid.",
        "invalid_refresh_token" to "The sign-in session has expired.",
        "invalid_request" to "The account request is invalid.",
        "invalid_session_id" to "The selected session is invalid.",
        "invalid_verification_code" to "The verification code is invalid or expired.",
        "missing_access_token" to "Sign in before using this account operation.",
        "password_reset_failed" to "The password reset request could not be verified.",
        "password_reuse" to "The new password must be different.",
        "platform_ai_unavailable" to "Platform AI is temporarily unavailable.",
        "session_not_found" to "The selected session no longer exists.",
        "too_many_requests" to "Too many attempts were made. Try again later.",
        "user_disabled" to "This account is unavailable.",
        "verification_unavailable" to "The verification code is no longer available.",
    )

    internal fun deliver(
        result: MethodChannel.Result,
        tag: String,
        requestedCode: String,
        error: Exception,
        reporter: (String) -> Unit = { message -> OmniLog.e(tag, message) },
    ) {
        val failure = stableFailure(requestedCode, error, reporter)
        result.error(failure.code, failure.message, failure.details)
    }

    internal fun record(
        tag: String,
        requestedCode: String,
        error: Exception,
        reporter: (String) -> Unit = { message -> OmniLog.e(tag, message) },
    ) {
        stableFailure(requestedCode, error, reporter)
    }

    internal fun stableFailure(
        requestedCode: String,
        error: Exception,
        reporter: (String) -> Unit,
    ): NativeChannelFailure {
        propagateCancellation(error)
        val safeCode = requestedCode.takeIf(stableMessages::containsKey)
            ?: "NATIVE_OPERATION_FAILED"
        reporter("channel_failure code=$safeCode")
        return NativeChannelFailure(
            code = safeCode,
            message = stableMessages[safeCode] ?: "The native operation failed.",
        )
    }

    internal fun deliverAccount(
        result: MethodChannel.Result,
        tag: String,
        error: Exception,
        reporter: (String) -> Unit = { message -> OmniLog.e(tag, message) },
    ) {
        val failure = accountFailure(error, reporter)
        result.error(failure.code, failure.message, failure.details)
    }

    internal fun accountFailure(
        error: Exception,
        reporter: (String) -> Unit,
    ): NativeChannelFailure {
        propagateCancellation(error)
        val failure = when (error) {
            is IllegalArgumentException -> NativeChannelFailure(
                "INVALID_ARGUMENT",
                accountMessages.getValue("INVALID_ARGUMENT"),
            )
            is AccountNotConfiguredException -> NativeChannelFailure(
                "ACCOUNT_NOT_CONFIGURED",
                accountMessages.getValue("ACCOUNT_NOT_CONFIGURED"),
            )
            is AccountNotAuthenticatedException -> NativeChannelFailure(
                "NOT_AUTHENTICATED",
                accountMessages.getValue("NOT_AUTHENTICATED"),
            )
            is AccountCredentialStorageException -> NativeChannelFailure(
                "ACCOUNT_CREDENTIAL_STORAGE_UNAVAILABLE",
                accountMessages.getValue("ACCOUNT_CREDENTIAL_STORAGE_UNAVAILABLE"),
            )
            is PlatformGatewayNotConfiguredException -> NativeChannelFailure(
                "PLATFORM_GATEWAY_NOT_CONFIGURED",
                accountMessages.getValue("PLATFORM_GATEWAY_NOT_CONFIGURED"),
            )
            is PlatformModelsUnavailableException -> NativeChannelFailure(
                "PLATFORM_MODELS_UNAVAILABLE",
                accountMessages.getValue("PLATFORM_MODELS_UNAVAILABLE"),
            )
            is AccountApiException -> accountApiFailure(error)
            is AccountProtocolException -> NativeChannelFailure(
                "ACCOUNT_PROTOCOL_ERROR",
                accountMessages.getValue("ACCOUNT_PROTOCOL_ERROR"),
            )
            is AccountException -> NativeChannelFailure(
                "ACCOUNT_ERROR",
                accountMessages.getValue("ACCOUNT_ERROR"),
            )
            else -> NativeChannelFailure(
                "ACCOUNT_UNEXPECTED_ERROR",
                accountMessages.getValue("ACCOUNT_UNEXPECTED_ERROR"),
            )
        }
        reporter("channel_failure code=${failure.code}")
        return failure
    }

    private fun accountApiFailure(error: AccountApiException): NativeChannelFailure {
        val safeStatus = error.statusCode.takeIf { it in 100..599 }
        if (safeStatus == 404 && error.errorCode.isNullOrBlank()) {
            return NativeChannelFailure(
                "ACCOUNT_FEATURE_UNAVAILABLE",
                "This account feature requires a newer account service.",
                mapOf("statusCode" to safeStatus),
            )
        }
        val safeCode = error.errorCode?.takeIf(accountApiCodes::contains)
            ?: safeStatus?.let { "ACCOUNT_HTTP_$it" }
            ?: "ACCOUNT_ERROR"
        val safeMessage = accountMessages[safeCode] ?: "The account request failed."
        val safeDetails = safeStatus?.let { mapOf("statusCode" to it) }
        return NativeChannelFailure(safeCode, safeMessage, safeDetails)
    }

    private fun propagateCancellation(error: Exception) {
        if (error is CancellationException) throw error
    }
}
