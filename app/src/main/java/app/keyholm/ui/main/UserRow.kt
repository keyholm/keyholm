package app.keyholm.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextRange
import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.userLabel
import app.keyholm.webauthn.CredentialUser
import kotlinx.coroutines.CoroutineScope

private const val SEPARATOR = " ("
private const val SUFFIX = ")"

internal fun renameUserWithUndo(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    viewModel: MainViewModel,
    record: PasskeyRecord,
    user: CredentialUser,
) {
    viewModel.setUser(record, user.name, user.displayName)
    showUndoSnackbar(scope, snackbarHostState, "Renamed user to ${userLabel(user.name, user.displayName)}") {
        viewModel.setUser(record, record.user.name, record.user.displayName)
    }
}

@Composable
internal fun UserRow(
    record: PasskeyRecord,
    shape: Shape,
    onSetUser: (CredentialUser) -> Unit,
) {
    val (user, setSaved) = rememberOptimistic(record.user)
    EditableDetailRow("User", userLabel(user.name, user.displayName), shape) { cursor, close ->
        UserNameField(
            user.name,
            user.displayName,
            cursor,
            onDone = { userName, displayName ->
                val updated = record.user.copy(name = userName, displayName = displayName)
                setSaved(updated)
                onSetUser(updated)
                close()
            },
            onCancel = close,
        )
    }
}

private fun editableOffset(
    offset: Int,
    separator: Int,
    last: Int,
): Int {
    val inRange = offset.coerceIn(0, last)
    return if (inRange > separator && inRange < separator + SEPARATOR.length) separator else inRange
}

@OptIn(ExperimentalFoundationApi::class)
private fun separatorShift(
    changes: TextFieldBuffer.ChangeList,
    separator: Int,
    fixedEnd: Int,
): Int? {
    var shift = 0
    for (i in 0 until changes.changeCount) {
        val original = changes.getOriginalRange(i)
        when {
            original.max <= separator -> shift += changes.getRange(i).length - original.length
            original.min < separator + SEPARATOR.length || original.max > fixedEnd -> return null
        }
    }
    return shift
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserNameField(
    userName: String,
    displayName: String,
    cursor: Int?,
    onDone: (userName: String, displayName: String) -> Unit,
    onCancel: () -> Unit,
) {
    val state = rememberTextFieldState(userName + SEPARATOR + displayName + SUFFIX, TextRange(cursor ?: userName.length))
    var separator by remember { mutableIntStateOf(userName.length) }
    InlineTextField(
        state,
        inputTransformation =
            InputTransformation {
                val shift = separatorShift(changes, separator, originalText.length - SUFFIX.length)
                if (shift != null) separator += shift else revertAllChanges()
            },
        clampOffset = { editableOffset(it, separator, state.text.length - SUFFIX.length) },
        onDone = {
            val text = state.text
            val newUserName = text.substring(0, separator).trim()
            val newDisplayName = text.substring(separator + SEPARATOR.length, text.length - SUFFIX.length).trim()
            if (newUserName == userName && newDisplayName == displayName) onCancel() else onDone(newUserName, newDisplayName)
        },
        onCancel = onCancel,
    )
}
