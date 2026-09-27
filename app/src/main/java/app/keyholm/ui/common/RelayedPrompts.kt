package app.keyholm.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RelayedPrompts(
    cryptoPrompt: CryptoPrompt,
    prompts: PromptRelay,
) {
    val activity = LocalActivity.current
    val prompt by rememberUpdatedState(cryptoPrompt.forView(LocalView.current))
    LaunchedEffect(prompts) {
        prompts.pending.collectLatest { pending ->
            if (pending == null) return@collectLatest
            val result =
                prompt.authenticate(
                    pending.request.title,
                    pending.request.cryptoObject,
                    pending.request.allowedAuthenticators,
                    pending.request.content,
                )
            // Left pending on rotation so the recreated Activity shows it again.
            if (result != PromptResult.Interrupted || activity?.isChangingConfigurations != true) {
                pending.response.complete(result)
            }
        }
    }
    LaunchedEffect(prompts) {
        prompts.confirmations.collectLatest { pending ->
            if (pending == null) return@collectLatest
            val confirmed =
                prompt.confirm(
                    title = pending.request.title,
                    allowedAuthenticators = pending.request.allowedAuthenticators,
                    content = pending.request.content,
                )
            pending.response.complete(confirmed)
        }
    }
}
