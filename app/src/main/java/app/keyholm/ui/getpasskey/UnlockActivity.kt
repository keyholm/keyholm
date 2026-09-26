package app.keyholm.ui.getpasskey

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.AuthenticationRequest
import androidx.compose.runtime.mutableStateOf
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.provider.GetCredentialEntries
import app.keyholm.ui.common.AuthenticatorsResolution
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.WorkingIndicator
import app.keyholm.ui.common.resolveCreateAuthenticators
import app.keyholm.ui.theme.KeyholmTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UNLOCK_TITLE = "Unlock Keyholm"
private const val UNLOCK_DESCRIPTION = "Confirm your identity to see your passkeys for this site."

class UnlockActivity internal constructor(
    private val dispatcher: CoroutineDispatcher,
) : FragmentActivity() {
    constructor() : this(Dispatchers.IO)

    private val cryptoPrompt = CryptoPrompt(this)
    private val working = mutableStateOf(false)
    private val entries by lazy { GetCredentialEntries(applicationContext, dispatcher) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        if (request == null) {
            cancel()
            return
        }
        setContent {
            KeyholmTheme {
                WorkingIndicator(working.value)
            }
        }
        lifecycleScope.launch {
            // We have to be foreground otherwise the prompt never shows
            lifecycle.withResumed {}
            val authenticators = withContext(dispatcher) { resolveCreateAuthenticators(this@UnlockActivity, AuthenticatorPolicy.Either) }
            val unlocked =
                when (authenticators) {
                    is AuthenticatorsResolution.Unavailable -> {
                        false
                    }

                    is AuthenticatorsResolution.Ready -> {
                        cryptoPrompt.confirm(
                            title = UNLOCK_TITLE,
                            allowedAuthenticators = authenticators.value,
                            content = AuthenticationRequest.BodyContent.PlainText(UNLOCK_DESCRIPTION),
                        )
                    }
                }
            val unlockedEntries =
                if (unlocked) {
                    working.value = true
                    try {
                        entries.of(request)
                    } finally {
                        working.value = false
                    }
                } else {
                    null
                }
            if (unlockedEntries == null) {
                cancel()
                return@launch
            }
            val data = Intent()
            PendingIntentHandler.setBeginGetCredentialResponse(data, BeginGetCredentialResponse(unlockedEntries))
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
