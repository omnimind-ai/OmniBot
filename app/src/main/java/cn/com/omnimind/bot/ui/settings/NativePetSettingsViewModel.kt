package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.preferences.PetAppearanceRepository
import cn.com.omnimind.bot.preferences.PetAppearanceState
import cn.com.omnimind.nativeui.settings.PetAppearanceItem
import cn.com.omnimind.nativeui.settings.PetNotice
import cn.com.omnimind.nativeui.settings.PetSettingsActions
import cn.com.omnimind.nativeui.settings.PetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/** Activity-scoped presentation state; the repository owns files, selection and overlay refresh. */
internal class NativePetSettingsViewModel(private val repository: PetAppearanceRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(PetSettingsState())
    val state = mutableState.asStateFlow()
    private val previews = LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(64, .75f, true)
    private val loading = mutableSetOf<String>()
    private var refreshRunning = false

    val actions = PetSettingsActions(
        refresh = ::refresh,
        select = ::select,
        importPackage = ::importPackage,
        loadPreview = ::loadPreview,
        clearNotice = { mutableState.update { it.copy(notice = null) } },
    )

    init { refresh() }

    fun refresh() {
        if (refreshRunning || state.value.busy) return
        refreshRunning = true
        viewModelScope.launch {
            try {
                applySnapshot(repository.state(), clearImages = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = PetNotice.ReadFailed) }
            } finally { refreshRunning = false }
        }
    }

    private fun select(id: String) = mutate(PetNotice.SelectionFailed) { repository.select(id) }
    private fun importPackage(uri: Uri) = mutate(PetNotice.ImportFailed) { repository.import(uri) }

    private fun mutate(failure: PetNotice, operation: suspend () -> PetAppearanceState) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { applySnapshot(operation()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = failure) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun applySnapshot(snapshot: PetAppearanceState, clearImages: Boolean = false) {
        val options = snapshot.options.map { item ->
            PetAppearanceItem(item.id, item.name, item.description, item.previewPath,
                item.playbackPath, item.builtIn, item.animationLabel)
        }
        val keys = options.map { it.id to it.previewPath }
        val previous = state.value.options.map { it.id to it.previewPath }
        if (clearImages || keys != previous) previews.clear()
        mutableState.update { it.copy(loaded = true, selectedId = snapshot.selectedId,
            options = options, previewImages = previews.toMap(),
            previewRevision = if (clearImages || keys != previous) it.previewRevision + 1 else it.previewRevision) }
    }

    private fun loadPreview(id: String) {
        val item = state.value.options.firstOrNull { it.id == id && !it.builtIn && it.previewPath.isNotBlank() }
            ?: return
        if (id in previews || !loading.add(id)) return
        val revision = state.value.previewRevision
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(item.previewPath, bounds)
                    val longest = maxOf(bounds.outWidth, bounds.outHeight)
                    if (longest <= 0) return@withContext null
                    var sample = 1
                    while (longest / sample > 160 && sample < (1 shl 20)) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(item.previewPath, options)?.asImageBitmap()
                }
                if (image != null && revision == state.value.previewRevision &&
                    state.value.options.any { it.id == id && it.previewPath == item.previewPath }) {
                    previews[id] = image
                    if (previews.size > 64) previews.remove(previews.keys.first())
                    mutableState.update { it.copy(previewImages = previews.toMap()) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The option remains selectable when its preview cannot be decoded.
            } finally { loading.remove(id) }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = PetAppearanceRepository.get(context)
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativePetSettingsViewModel::class.java))
            return NativePetSettingsViewModel(repository) as T
        }
    }
}
