package app.keyholm.iconpack

import android.net.Uri
import app.keyholm.ui.common.ErrorMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

class IconPackController(
    private val storage: IconPackStorage,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val _pack = MutableStateFlow<IconPack?>(null)
    val pack: StateFlow<IconPack?> = _pack.asStateFlow()

    init {
        scope.launch {
            when (val result = storage.load()) {
                IconPackLoad.None -> Unit
                is IconPackLoad.Loaded -> _pack.value = result.pack
                IconPackLoad.Damaged -> onError(ErrorMessages.ICON_PACK_LOAD_FAILED)
            }
        }
    }

    fun import(uri: Uri) {
        scope.launch {
            when (val result = storage.import(uri)) {
                is IconPackImport.Installed -> _pack.getAndUpdate { result.pack }?.close()
                IconPackImport.Invalid -> onError(ErrorMessages.ICON_PACK_NOT_A_PACK)
                IconPackImport.InstallFailed -> onError(ErrorMessages.ICON_PACK_INSTALL_FAILED)
            }
        }
    }

    override fun close() {
        _pack.value?.close()
    }

    fun remove() {
        scope.launch {
            storage
                .remove()
                .onSuccess { _pack.getAndUpdate { null }?.close() }
                .onFailure { onError(ErrorMessages.ICON_PACK_REMOVE_FAILED) }
        }
    }
}
