package app.keyholm.ui.main

import androidx.biometric.AuthenticationRequest
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.RpId
import kotlinx.coroutines.channels.Channel

internal class RowGenerations(
    val passkeys: MutableMap<CredentialId, Int>,
    val placeholders: MutableMap<Pair<RpId, String>, Int>,
)

private fun <K> MutableMap<K, Int>.bump(key: K) {
    this[key] = (this[key] ?: 0) + 1
}

private fun pendingDeleteMessage(batches: PendingDeleteBatches): String {
    val lead =
        batches.passkeys.lastOrNull()?.let { "Deleted ${it.user.name}" }
            ?: "Removed placeholder ${batches.placeholders.last().userName}"
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
    rowGenerations: RowGenerations,
    onUndo: () -> Unit,
) {
    LaunchedEffect(batches) {
        if (batches.passkeys.isEmpty() && batches.placeholders.isEmpty()) return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = pendingDeleteMessage(batches),
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) {
            // Force a fresh SwipeToDismissBoxState for every row in the batch: without this,
            // LazyColumn restores each row's old (fully-swiped) saved state under the same key
            // and immediately re-fires onDismiss, looping the delete right back on.
            batches.passkeys.forEach { rowGenerations.passkeys.bump(it.credentialId) }
            batches.placeholders.forEach { rowGenerations.placeholders.bump(it.rp.id to it.userName) }
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
