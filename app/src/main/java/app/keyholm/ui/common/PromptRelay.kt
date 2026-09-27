package app.keyholm.ui.common

import androidx.biometric.AuthenticationRequest
import androidx.biometric.BiometricPrompt
import app.keyholm.keystore.AuthenticatorPolicy
import kotlinx.coroutines.flow.StateFlow

class PromptRequest(
    val title: String,
    val cryptoObject: BiometricPrompt.CryptoObject,
    val allowedAuthenticators: AuthenticatorPolicy,
    val content: AuthenticationRequest.BodyContent,
)

class ConfirmRequest(
    val title: String,
    val allowedAuthenticators: AuthenticatorPolicy,
    val content: AuthenticationRequest.BodyContent,
)

class PromptRelay {
    private val relay = Relay<PromptRequest, PromptResult>()
    val pending: StateFlow<PendingRequest<PromptRequest, PromptResult>?> = relay.pending
    private val confirmRelay = Relay<ConfirmRequest, Boolean>()
    val confirmations: StateFlow<PendingRequest<ConfirmRequest, Boolean>?> = confirmRelay.pending

    suspend fun authenticate(
        title: String,
        cryptoObject: BiometricPrompt.CryptoObject,
        allowedAuthenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): PromptResult = relay.ask(PromptRequest(title, cryptoObject, allowedAuthenticators, content))

    suspend fun confirm(
        title: String,
        allowedAuthenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): Boolean = confirmRelay.ask(ConfirmRequest(title, allowedAuthenticators, content))
}
