package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes

data class ProviderSummary(
    val id: String,
    val name: String,
    val readOnly: Boolean,
    val configured: Boolean,
    val ready: Boolean,
    val status: String = "",
    val revision: Long = 0,
)

data class ProviderHeaderDraft(val id: Long, val name: String = "", val value: String = "") {
    override fun toString(): String = "ProviderHeaderDraft(id=$id, name=$name, value=<redacted>)"
}

data class ProviderModelRow(
    val id: String,
    val displayName: String,
    val manual: Boolean,
    val visibleInChat: Boolean,
    val group: String = "",
    val contextLimit: Int? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val reasoning: Boolean? = null,
    val toolCall: Boolean? = null,
    val attachment: Boolean? = null,
)

data class ModelProviderState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val fetching: Boolean = false,
    val profiles: List<ProviderSummary> = emptyList(),
    val editingId: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val requestUrlHint: String = "",
    val apiKey: String = "",
    val headers: List<ProviderHeaderDraft> = emptyList(),
    val sourceType: String = "custom",
    val protocolType: String = "openai_compatible",
    val wireApi: String = "chat_completions",
    val models: List<ProviderModelRow> = emptyList(),
    val modelFetchFailed: Boolean = false,
    val dirty: Boolean = false,
    @StringRes val notice: Int? = null,
) {
    val current: ProviderSummary? get() = profiles.firstOrNull { it.id == editingId }
    override fun toString(): String = "ModelProviderState(loaded=$loaded, editingId=$editingId, busy=$busy)"
}

data class ModelProviderActions(
    val refresh: () -> Unit,
    val setName: (String) -> Unit,
    val setBaseUrl: (String) -> Unit,
    val setApiKey: (String) -> Unit,
    val setSourceType: (String, String, String, String?, String?) -> Unit,
    val setWireApi: (String) -> Unit,
    val addHeader: () -> Unit,
    val setHeader: (Long, String?, String?) -> Unit,
    val removeHeader: (Long) -> Unit,
    val save: () -> Unit,
    val selectProfile: (String) -> Unit,
    val addProfile: (String) -> Unit,
    val deleteProfile: () -> Unit,
    val fetchModels: () -> Unit,
    val addModel: (String) -> Unit,
    val removeModel: (String) -> Unit,
    val setModelVisible: (String, Boolean) -> Unit,
    val hideAllRemote: () -> Unit,
    val dismissNotice: () -> Unit,
)
