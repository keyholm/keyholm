package app.keyholm.ui.common

import androidx.biometric.AuthenticationRequest
import androidx.biometric.BiometricPrompt
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.webauthn.PrfExtension
import app.keyholm.webauthn.PrfOutput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.crypto.Mac

internal const val PRF_PROMPT_TITLE = "Unlock shared secret"

internal class PrfPrompt(
    private val prompt: CryptoAuthenticator,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun evaluate(
        mac: Mac,
        nextMac: suspend () -> Outcome<Mac>,
        salts: PrfExtension.Salts,
        authenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): PrfExtension.Results? {
        val first = output(mac, salts.first, authenticators, content) ?: return null
        val secondSalt = salts.second ?: return PrfExtension.Results(first, null)
        val second =
            when (val next = nextMac()) {
                is Outcome.Success -> output(next.value, secondSalt, authenticators, content)
                is Outcome.Failure -> null
            }
        return second?.let { PrfExtension.Results(first, it) }
    }

    private suspend fun output(
        mac: Mac,
        salt: ByteArray,
        authenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): PrfOutput? {
        val authorized =
            prompt.authenticate(
                title = PRF_PROMPT_TITLE,
                cryptoObject = BiometricPrompt.CryptoObject(mac),
                allowedAuthenticators = authenticators,
                content = content,
            ) as? PromptResult.Success
        return authorized?.crypto?.mac?.let { withContext(dispatcher) { PrfOutput(it.doFinal(salt)) } }
    }
}
