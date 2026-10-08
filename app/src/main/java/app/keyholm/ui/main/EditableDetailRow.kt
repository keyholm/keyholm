package app.keyholm.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun showUndoSnackbar(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    message: String,
    onUndo: () -> Unit,
) {
    snackbarHostState.currentSnackbarData?.dismiss()
    scope.launch {
        val result = snackbarHostState.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }
}

@Composable
internal fun <T> rememberOptimistic(value: T): Pair<T, (T) -> Unit> {
    var saved by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(value) { saved = null }
    return (saved ?: value) to { new: T -> saved = new }
}

@Composable
internal fun EditableDetailRow(
    label: String,
    value: String,
    shape: Shape,
    leadingContent: (@Composable () -> Unit)? = null,
    field: @Composable (cursor: Int?, close: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<Int?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    ListItem(
        selected = false,
        onClick = { if (!editing) copyToClipboard(context, label, value) },
        supportingContent = {
            ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                if (editing) {
                    field(cursor) { editing = false }
                } else {
                    Text(
                        value,
                        onTextLayout = { layout = it },
                        modifier =
                            Modifier.pointerInput(Unit) {
                                detectTapGestures { position ->
                                    cursor = layout?.getOffsetForPosition(position)
                                    editing = true
                                }
                            },
                    )
                }
            }
        },
        leadingContent = leadingContent,
        trailingContent = {
            if (editing) {
                IconButton(onClick = { editing = false }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel rename")
                }
            } else {
                IconButton(onClick = {
                    cursor = null
                    editing = true
                }) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename")
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(label)
    }
}

@Composable
internal fun InlineTextField(
    state: TextFieldState,
    inputTransformation: InputTransformation,
    clampOffset: (Int) -> Int,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(state) {
        snapshotFlow { state.selection.let { it to TextRange(clampOffset(it.start), clampOffset(it.end)) } }
            .collect { (selection, clamped) -> if (clamped != selection) state.edit { this.selection = clamped } }
    }
    BackHandler(onBack = onCancel)
    BasicTextField(
        state = state,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        focused = true
                    } else if (focused) {
                        focused = false
                        onCancel()
                    }
                },
        inputTransformation = inputTransformation,
        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = { onDone() },
    )
}
