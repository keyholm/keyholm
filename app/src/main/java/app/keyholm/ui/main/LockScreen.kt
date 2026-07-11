package app.keyholm.ui.main

import androidx.biometric.AuthenticationRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.ui.common.AuthenticatorsResolution
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.rememberAppIcon
import app.keyholm.ui.common.resolveCreateAuthenticators
import kotlinx.coroutines.launch

private const val LOCKED_TITLE = "Keyholm is locked"
private const val UNLOCK_PROMPT_TITLE = "Unlock Keyholm"
private const val UNLOCK_PROMPT_DESCRIPTION = "Confirm your identity to open Keyholm."
private const val UNLOCK_BUTTON = "Unlock"
private val APP_ICON_SIZE = 72.dp

@Composable
internal fun LockScreen(
    viewModel: MainViewModel,
    cryptoPrompt: CryptoPrompt,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val icon = rememberAppIcon()
    var unavailable by remember { mutableStateOf<String?>(null) }

    val unlock = {
        scope.launch {
            when (val authenticators = resolveCreateAuthenticators(context, AuthenticatorPolicy.Either)) {
                is AuthenticatorsResolution.Unavailable -> {
                    unavailable = authenticators.message
                }

                is AuthenticatorsResolution.Ready -> {
                    viewModel.unlockWith {
                        cryptoPrompt.confirm(
                            title = UNLOCK_PROMPT_TITLE,
                            allowedAuthenticators = authenticators.value,
                            content = AuthenticationRequest.BodyContent.PlainText(UNLOCK_PROMPT_DESCRIPTION),
                        )
                    }
                }
            }
        }
    }

    // The activity has to be resumed before the prompt will be accepted, and the lock screen
    // appears while it is still going to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.withResumed {}
        unlock()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(APP_ICON_SIZE))
        Text(
            LOCKED_TITLE,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        unavailable?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(onClick = { unlock() }, modifier = Modifier.padding(top = 24.dp)) {
            Text(UNLOCK_BUTTON)
        }
    }
}
