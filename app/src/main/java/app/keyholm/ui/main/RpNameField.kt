package app.keyholm.ui.main

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextRange
import app.keyholm.webauthn.RelyingParty

@Composable
internal fun RpNameField(
    rp: RelyingParty,
    preferRpName: Boolean,
    cursor: Int?,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val initial = rp.name
    val prefix = if (preferRpName) "" else "${rp.id.value} ("
    val suffix = if (preferRpName) " (${rp.id.value})" else ")"
    val state = rememberTextFieldState(prefix + initial + suffix, TextRange(cursor ?: (prefix.length + initial.length)))
    InlineTextField(
        state,
        inputTransformation =
            InputTransformation {
                val text = asCharSequence()
                if (length < prefix.length + suffix.length || !text.startsWith(prefix) || !text.endsWith(suffix)) {
                    revertAllChanges()
                }
            },
        clampOffset = { it.coerceIn(prefix.length, state.text.length - suffix.length) },
        onDone = {
            val text = state.text.substring(prefix.length, state.text.length - suffix.length).trim()
            if (text == initial) onCancel() else onDone(text)
        },
        onCancel = onCancel,
    )
}
