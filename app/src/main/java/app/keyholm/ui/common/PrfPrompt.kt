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
private const val TWO_SALTS_NOTE = "This is the first of two secrets. A second prompt will appear."

internal class PrfPrompt(
    private val prompt: CryptoAuthenticator,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun evaluate(
        mac: Mac,
        nextMac: suspend () -> Outcome<Mac>,
        salts: PrfExtension.Salts,
        authenticators: AuthenticatorPolicy,
        content: (note: String?) -> AuthenticationRequest.BodyContent,
    ): PrfExtension.Results? {
        val secondSalt = salts.second
        val firstTitle = if (secondSalt == null) PRF_PROMPT_TITLE else "$PRF_PROMPT_TITLE (1 of 2)"
        val first =
            output(mac, salts.first, firstTitle, authenticators, content(secondSalt?.let { TWO_SALTS_NOTE }))
                ?: return null
        if (secondSalt == null) return PrfExtension.Results(first, null)
        val second =
            when (val next = nextMac()) {
                is Outcome.Success -> output(next.value, secondSalt, "$PRF_PROMPT_TITLE (2 of 2)", authenticators, content(null))
                is Outcome.Failure -> null
            }
        return second?.let { PrfExtension.Results(first, it) }
    }

    private suspend fun output(
        mac: Mac,
        salt: ByteArray,
        title: String,
        authenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): PrfOutput? {
        val authorized =
            prompt.authenticate(
                title = title,
                cryptoObject = BiometricPrompt.CryptoObject(mac),
                allowedAuthenticators = authenticators,
                content = content,
            ) as? PromptResult.Success
        return authorized?.crypto?.mac?.let { withContext(dispatcher) { PrfOutput(it.doFinal(salt)) } }
    }
}
