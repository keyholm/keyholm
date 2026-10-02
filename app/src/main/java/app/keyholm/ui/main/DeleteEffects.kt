package app.keyholm.ui.main

import androidx.biometric.AuthenticationRequest
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.rpLabel
import kotlinx.coroutines.channels.Channel

private fun pendingDeleteMessage(
    batches: PendingDeleteBatches,
    preferRpName: Boolean,
): String {
    val lead =
        batches.passkeys.lastOrNull()?.let { "Deleted ${rpLabel(it.rp, preferRpName)} passkey" }
            ?: "Deleted ${rpLabel(batches.placeholders.last().rp, preferRpName)} placeholder"
    val othersCount = batches.passkeys.size + batches.placeholders.size - 1
    if (othersCount == 0) return lead
    val noun =
        when {
            batches.placeholders.isEmpty() -> "passkey"
            batches.passkeys.isEmpty() -> "placeholder"
            else -> "item"
        }
    return "$lead and $othersCount other $noun${if (othersCount == 1) "" else "s"}"
}

@Composable
internal fun PendingDeleteUndo(
    batches: PendingDeleteBatches,
    snackbarHostState: SnackbarHostState,
    preferRpName: Boolean,
    onUndo: () -> Unit,
) {
    LaunchedEffect(batches) {
        if (batches.passkeys.isEmpty() && batches.placeholders.isEmpty()) return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = pendingDeleteMessage(batches, preferRpName),
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }
}

internal class DeleteConfirmationRequest(
    val title: String,
    val content: AuthenticationRequest.BodyContent,
    val plan: suspend () -> DeletePlan,
    val onConfirmed: () -> Unit,
    val onDenied: () -> Unit,
)

internal typealias DeleteConfirmationRequester<R> = (record: R, onConfirmed: () -> Unit, onDenied: () -> Unit) -> Unit

@Composable
internal fun HandleDeleteConfirmations(
    confirmationQueue: Channel<DeleteConfirmationRequest>,
    cryptoPrompt: CryptoPrompt,
    reportError: (String) -> Unit,
) {
    LaunchedEffect(cryptoPrompt) {
        for (request in confirmationQueue) {
            val confirmed =
                when (val plan = request.plan()) {
                    DeletePlan.Orphaned -> {
                        true
                    }

                    is DeletePlan.Failed -> {
                        reportError(plan.message)
                        false
                    }

                    is DeletePlan.Confirm -> {
                        cryptoPrompt.confirm(
                            title = request.title,
                            cryptoObject = plan.cryptoObject,
                            allowedAuthenticators = plan.allowedAuthenticators,
                            content = request.content,
                        )
                    }
                }
            if (confirmed) request.onConfirmed() else request.onDenied()
        }
    }
}
