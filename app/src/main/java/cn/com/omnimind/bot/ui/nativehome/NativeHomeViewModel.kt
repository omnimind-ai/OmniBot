package cn.com.omnimind.bot.ui.nativehome

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.McpServerState
import cn.com.omnimind.nativeui.ConversationSummary
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.LocalServiceDetails
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.WebQuickAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class NativeHomeViewModel(
    private val repository: NativeHomeRepository,
    private val webActions: NativeWebActionRepository,
    private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(repository.readPreferences())
    val state = mutableState.asStateFlow()
    private var historyJob: Job? = null
    private var serviceJob: Job? = null
    private var webJob: Job? = null

    init { observeHistory() }

    private fun observeHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            try {
                repository.snapshots.collect { snapshot ->
                    mutableState.update {
                        snapshot.copy(
                            localServiceEnabled = it.localServiceEnabled,
                            localServiceBusy = it.localServiceBusy,
                            localService = it.localService,
                            busyConversationIds = it.busyConversationIds,
                            webActions = it.webActions,
                            busyWebAction = it.busyWebAction,
                            pendingDestination = it.pendingDestination,
                            error = it.error,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            }
        }
    }

    fun refresh() {
        mutableState.update { it.copy(error = null) }
        if (historyJob?.isActive != true) observeHistory()
        repository.refresh()
        if (serviceJob?.isActive != true) runServiceOperation { repository.localServiceState() }
        if (webJob?.isActive != true) {
            webJob = viewModelScope.launch {
                try {
                    val actions = webActions.list()
                    mutableState.update { it.copy(webActions = actions) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Discovery failure must not remove known actions or invent a stopped state.
                    reportError(error)
                }
            }
        }
    }

    fun setLocalServiceEnabled(enabled: Boolean) = runServiceOperation { repository.setLocalServiceEnabled(enabled) }
    fun refreshLocalServiceToken() = runServiceOperation { repository.refreshLocalServiceToken() }

    private fun runServiceOperation(operation: suspend () -> McpServerState) {
        if (serviceJob?.isActive == true) return
        mutableState.update { it.copy(localServiceBusy = true) }
        serviceJob = viewModelScope.launch {
            try {
                val actual = operation()
                val endpoint = actual.host?.takeIf(String::isNotBlank)?.let { "http://$it:${actual.port}" }.orEmpty()
                mutableState.update {
                    it.copy(localServiceEnabled = actual.enabled, localService = LocalServiceDetails(endpoint, actual.token), error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            } finally {
                mutableState.update { it.copy(localServiceBusy = false) }
            }
        }
    }

    fun setArchived(conversation: ConversationSummary, archived: Boolean) {
        if (conversation.id in mutableState.value.busyConversationIds) return
        mutableState.update { it.copy(busyConversationIds = it.busyConversationIds + conversation.id, error = null) }
        viewModelScope.launch {
            try {
                repository.setArchived(conversation.id, archived)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            } finally {
                mutableState.update { it.copy(busyConversationIds = it.busyConversationIds - conversation.id) }
            }
        }
    }

    fun setSectionExpanded(key: String, expanded: Boolean) {
        viewModelScope.launch {
            try {
                repository.setSectionExpanded(key, expanded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            }
        }
    }

    fun invokeWebAction(action: WebQuickAction, stop: Boolean) {
        if (mutableState.value.busyWebAction != null) return
        webJob?.cancel()
        mutableState.update { it.copy(busyWebAction = action.key, error = null) }
        webJob = viewModelScope.launch {
            try {
                val result = webActions.invoke(action, stop)
                when {
                    stop && !result.stopped -> showMessage(R.string.omni_web_stop_failed)
                    stop || result.code == "OPENED" -> Unit
                    result.code == "RUNTIME_MISSING" -> mutableState.update {
                        it.copy(pendingDestination = LegacyDestination.TerminalPackage(result.packageId))
                    }
                    result.code == "PROVIDER_REQUIRED" || result.code == "MODEL_REQUIRED" -> {
                        showMessage(R.string.omni_web_provider_required)
                        mutableState.update { it.copy(pendingDestination = LegacyDestination.Page.ModelProviders) }
                    }
                    result.code == "UNSUPPORTED_PROVIDER" -> showMessage(R.string.omni_web_unsupported_provider)
                    result.code == "URL_TIMEOUT" -> showMessage(R.string.omni_web_timeout)
                    result.code == "STOP_FAILED" -> showMessage(R.string.omni_web_stop_failed)
                    result.code == "BROWSER_UNAVAILABLE" -> showMessage(R.string.omni_web_browser_unavailable)
                    else -> showMessage(R.string.omni_web_open_failed)
                }
                val refreshed = webActions.list()
                mutableState.update { it.copy(webActions = refreshed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            } finally {
                mutableState.update { it.copy(busyWebAction = null) }
            }
        }
    }

    fun consumeDestination() { mutableState.update { it.copy(pendingDestination = null) } }

    private fun showMessage(resource: Int) {
        val preferences = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val locale = resolveNativeHomeLocale(preferences.getString("flutter.language_option", "system"))
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) }
        mutableState.update { it.copy(error = context.createConfigurationContext(configuration).getString(resource)) }
    }

    private fun reportError(error: Exception) {
        OmniLog.e("NativeHome", "Native home operation failed", error)
        mutableState.update { it.copy(loading = false) }
        showMessage(R.string.omni_operation_failed)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativeHomeViewModel::class.java))
            val application = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return NativeHomeViewModel(NativeHomeRepository(application), NativeWebActionRepository(application), application) as T
        }
    }
}
