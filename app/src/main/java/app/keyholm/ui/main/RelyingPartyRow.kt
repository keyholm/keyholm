package app.keyholm.ui.main

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import app.keyholm.iconpack.IconPack
import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.RpIcon
import app.keyholm.ui.common.rpLabel
import kotlinx.coroutines.CoroutineScope

internal fun renameRpWithUndo(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    viewModel: MainViewModel,
    record: PasskeyRecord,
    name: String,
) {
    viewModel.setRpName(record, name)
    showUndoSnackbar(scope, snackbarHostState, "Renamed RP to $name") { viewModel.setRpName(record, record.rp.name) }
}

@Composable
internal fun RelyingPartyRow(
    record: PasskeyRecord,
    preferRpName: Boolean,
    iconPack: IconPack?,
    shape: Shape,
    onSetRpName: (String) -> Unit,
) {
    val (rp, setSaved) = rememberOptimistic(record.rp)
    EditableDetailRow(
        "Relying party",
        rpLabel(rp, preferRpName),
        shape,
        leadingContent = { RpIcon(iconPack, rp, preferRpName) },
    ) { cursor, close ->
        RpNameField(
            rp,
            preferRpName,
            cursor,
            onDone = {
                setSaved(rp.copy(name = it))
                onSetRpName(it)
                close()
            },
            onCancel = close,
        )
    }
}
