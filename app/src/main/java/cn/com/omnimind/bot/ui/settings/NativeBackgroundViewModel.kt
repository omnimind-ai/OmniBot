package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.preferences.AppBackgroundRepository
import cn.com.omnimind.nativeui.settings.BackgroundConfig
import cn.com.omnimind.nativeui.settings.BackgroundNotice
import cn.com.omnimind.nativeui.settings.BackgroundSettingsActions
import cn.com.omnimind.nativeui.settings.BackgroundSettingsState
import cn.com.omnimind.nativeui.settings.BackgroundSource
import cn.com.omnimind.nativeui.settings.BackgroundTextColorMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Activity-scoped draft and preview. AppBackgroundRepository is the only settings/file writer. */
internal class NativeBackgroundViewModel(
    private val repository: AppBackgroundRepository,
    private val loader: BackgroundPreviewLoader,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackgroundSettingsState())
    val state = mutableState.asStateFlow()
    private val saveMutex = Mutex()
    private var observation: Job? = null
    private var pendingSave: Job? = null
    private var imageJob: Job? = null
    private var imageKey = ""
    private var currentBitmap: Bitmap? = null
    private val retiredBitmaps = mutableSetOf<Bitmap>()
    private var lastDispatched: BackgroundConfig? = null

    init { observe() }

    val actions = BackgroundSettingsActions(
        setEnabled = { update { current -> current.copy(enabled = it) } },
        setSource = { source -> update { current ->
            current.copy(enabled = true, sourceType = source,
                localImagePath = if (source == BackgroundSource.Local) current.localImagePath else "",
                remoteImageUrl = if (source == BackgroundSource.Remote) current.remoteImageUrl else "")
        } },
        setRemoteUrl = { url -> update { it.copy(remoteImageUrl = url.trim()) } },
        setBlur = { value -> update { it.copy(blurSigma = value.coerceIn(0f, 24f)) } },
        setFrost = { value -> update { it.copy(frostOpacity = value.coerceIn(0f, .55f)) } },
        setBrightness = { value -> update { it.copy(brightness = value.coerceIn(.5f, 1.5f)) } },
        setChatTextSize = { value -> update { it.copy(chatTextSize = value.coerceIn(12f, 22f)) } },
        setTextColor = { mode, hex -> update { it.copy(chatTextColorMode = mode,
            chatTextHexColor = if (mode == BackgroundTextColorMode.Auto) "" else hex.trim()) } },
        setViewport = { x, y, scale -> update { it.copy(focalX = x.coerceIn(-1f, 1f),
            focalY = y.coerceIn(-1f, 1f), imageScale = scale.coerceIn(1f, 3f)) } },
        importImage = ::importImage,
        flush = ::flush,
        clearNotice = { mutableState.update { it.copy(notice = null) } },
    )

    private fun observe() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            try {
                repository.snapshots.collect { raw ->
                    val incoming = BackgroundConfig.fromMap(raw)
                    var nextConfig: BackgroundConfig? = null
                    mutableState.update { current ->
                        when {
                            !current.loaded || current.config == current.committed || current.config == incoming -> {
                                nextConfig = incoming
                                current.copy(loaded = true, config = incoming, committed = incoming)
                            }
                            incoming == lastDispatched -> current.copy(loaded = true, committed = incoming)
                            else -> {
                                // An external writer won while this page had a draft. Never replay stale fields.
                                pendingSave?.cancel()
                                nextConfig = incoming
                                current.copy(loaded = true, config = incoming, committed = incoming,
                                    notice = BackgroundNotice.ChangedElsewhere)
                            }
                        }
                    }
                    nextConfig?.let(::loadImageIfNeeded)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = BackgroundNotice.SaveFailed) }
            }
        }
    }

    fun refresh() {
        observe()
        viewModelScope.launch {
            try {
                val incoming = BackgroundConfig.fromMap(withContext(Dispatchers.IO) { repository.read() })
                if (state.value.config == state.value.committed) {
                    mutableState.update { it.copy(loaded = true, config = incoming, committed = incoming) }
                    loadImageIfNeeded(incoming)
                } else if (incoming != state.value.committed && incoming != lastDispatched) {
                    pendingSave?.cancel()
                    mutableState.update { it.copy(loaded = true, config = incoming, committed = incoming,
                        notice = BackgroundNotice.ChangedElsewhere) }
                    loadImageIfNeeded(incoming)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = BackgroundNotice.SaveFailed) }
            }
        }
    }

    private fun update(transform: (BackgroundConfig) -> BackgroundConfig) {
        if (!state.value.loaded || state.value.importing) return
        val next = transform(state.value.config)
        if (next == state.value.config) return
        mutableState.update { it.copy(config = next, notice = null) }
        loadImageIfNeeded(next)
        scheduleSave(next)
    }

    private fun scheduleSave(config: BackgroundConfig, immediate: Boolean = false) {
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            if (!immediate) delay(220)
            if (!isValid(config)) return@launch
            saveMutex.withLock {
                // After entering the store, finish a committed write even if the page closes.
                withContext(NonCancellable) {
                    lastDispatched = config
                    mutableState.update { it.copy(saving = true) }
                    try {
                        val saved = BackgroundConfig.fromMap(repository.save(config.toMap()))
                        mutableState.update { current ->
                            current.copy(committed = saved,
                                config = if (current.config == config) saved else current.config,
                                notice = null)
                        }
                    } catch (_: Exception) {
                        mutableState.update { it.copy(notice = BackgroundNotice.SaveFailed) }
                    } finally {
                        mutableState.update { it.copy(saving = false) }
                    }
                }
            }
        }
    }

    fun flush() {
        val current = state.value
        if (current.config != current.committed) scheduleSave(current.config, immediate = true)
    }

    private fun importImage(uri: Uri) {
        if (!state.value.loaded || state.value.importing) return
        pendingSave?.cancel()
        viewModelScope.launch {
            mutableState.update { it.copy(importing = true, notice = null) }
            saveMutex.withLock {
                withContext(NonCancellable) {
                    var imported: String? = null
                    try {
                        imported = repository.importFromUri(uri)
                        val previous = state.value.config
                        val next = previous.copy(enabled = true, sourceType = BackgroundSource.Local,
                            localImagePath = imported, remoteImageUrl = "")
                        lastDispatched = next
                        val saved = BackgroundConfig.fromMap(repository.save(next.toMap()))
                        mutableState.update { it.copy(config = saved, committed = saved) }
                        loadImageIfNeeded(saved)
                        imported = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableState.update { it.copy(notice = BackgroundNotice.ImportFailed) }
                    } finally {
                        imported?.let { runCatching { repository.deleteManagedImage(it) } }
                        mutableState.update { it.copy(importing = false) }
                    }
                }
            }
        }
    }

    private fun loadImageIfNeeded(config: BackgroundConfig) {
        val key = if (config.isActive && isValid(config)) config.imageKey else ""
        if (key == imageKey) return
        imageKey = key
        imageJob?.cancel()
        retireCurrentBitmap()
        mutableState.update { it.copy(previewImage = null, imageLoading = key.isNotBlank(),
            imageLoadFailed = false, sampledLuminance = .72f) }
        if (key.isBlank()) return
        imageJob = viewModelScope.launch {
            delay(220) // URL fields and source switching can change on every keystroke.
            try {
                val image = loader.load(config.sourceType.stored, config.localImagePath, config.remoteImageUrl)
                if (imageKey != key) {
                    image?.bitmap?.recycle()
                    return@launch
                }
                currentBitmap = image?.bitmap
                mutableState.update { it.copy(previewImage = image?.bitmap?.asImageBitmap(),
                    sampledLuminance = image?.sampledLuminance?.toFloat() ?: .72f,
                    imageLoading = false, imageLoadFailed = image == null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (imageKey == key) mutableState.update { it.copy(imageLoading = false, imageLoadFailed = true) }
            }
        }
    }

    private fun isValid(config: BackgroundConfig): Boolean {
        if (config.sourceType == BackgroundSource.Local && config.localImagePath.isBlank()) return false
        if (config.sourceType == BackgroundSource.Remote && !isHttpUrl(config.remoteImageUrl)) return false
        if (config.chatTextColorMode == BackgroundTextColorMode.Custom && !isHexColor(config.chatTextHexColor)) return false
        return true
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value.trim())
        uri.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun isHexColor(value: String): Boolean = Regex("^#(?:[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$").matches(value.trim())

    private fun retireCurrentBitmap() {
        val old = currentBitmap ?: return
        currentBitmap = null
        retiredBitmaps += old
        viewModelScope.launch {
            // Let Compose release the previous ImageBitmap before freeing its backing memory.
            delay(1_500)
            retiredBitmaps.remove(old)
            old.recycle()
        }
    }

    override fun onCleared() {
        currentBitmap?.recycle()
        retiredBitmaps.forEach { it.recycle() }
        retiredBitmaps.clear()
        super.onCleared()
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = AppBackgroundRepository.get(context)
        private val loader = BackgroundPreviewLoader(context)
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativeBackgroundViewModel::class.java))
            return NativeBackgroundViewModel(repository, loader) as T
        }
    }
}
