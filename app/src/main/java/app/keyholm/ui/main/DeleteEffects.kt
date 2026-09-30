package app.keyholm.ui.main

import androidx.biometric.AuthenticationRequest
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.keyholm.ui.common.CryptoPrompt
import kotlinx.coroutines.channels.Channel

@Composable
internal fun <T> HandleDeleteUndo(
    pendingDeleteBatch: List<T>,
    snackbarHostState: SnackbarHostState,
    message: (List<T>) -> String,
    onUndone: (List<T>) -> Unit,
    onUndo: () -> Unit,
) {
    LaunchedEffect(pendingDeleteBatch) {
        if (pendingDeleteBatch.isEmpty()) return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = message(pendingDeleteBatch),
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) {
            // Force a fresh SwipeToDismissBoxState for every row in the batch: without this,
            // LazyColumn restores each row's old (fully-swiped) saved state under the same key
            // and immediately re-fires onDismiss, looping the delete right back on.
            onUndone(pendingDeleteBatch)
            onUndo()
        }
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
