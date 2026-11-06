package app.keyholm.ui.common

import androidx.biometric.AuthenticationRequest
import androidx.biometric.BiometricPrompt
import app.keyholm.authenticator.KeyAccess
import app.keyholm.ctap.CredentialUser
import app.keyholm.ctap.PrfOutput
import app.keyholm.ctap.PrfResults
import app.keyholm.ctap.PrfSalts
import app.keyholm.ctap.RelyingParty
import app.keyholm.keystore.AuthenticatorPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.crypto.Mac

internal const val PRF_PROMPT_TITLE = "Unlock shared secret"
private const val TWO_SALTS_NOTE = "This is the first of two secrets. A second prompt will appear."

internal fun prfPromptContent(
    requester: String,
    declineAction: String,
    rp: RelyingParty,
    user: CredentialUser,
    preferRpName: Boolean,
): (note: String?) -> AuthenticationRequest.BodyContent =
    { note ->
        promptContent(
            description =
                "$requester wants access to the secret for this passkey.\nCancel to decline and $declineAction." +
                    note?.let { "\n$it" }.orEmpty(),
            lastUsedAt = null,
            rp = rp,
            preferRpName = preferRpName,
            userLabel = userLabel(user.name, user.displayName),
        )
    }

internal class PrfPrompt(
    private val prompt: CryptoAuthenticator,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun evaluate(
        macFor: suspend () -> KeyAccess<Mac>,
        authenticatorsFor: suspend () -> KeyAccess<AuthenticatorPolicy>,
        salts: PrfSalts,
        content: (note: String?) -> AuthenticationRequest.BodyContent,
    ): KeyAccess<PrfResults?> {
        val mac =
            when (val r = macFor()) {
                is KeyAccess.Failure -> return r
                is KeyAccess.Success -> r.value
            }
        val authenticators =
            when (val r = authenticatorsFor()) {
                is KeyAccess.Failure -> return r
                is KeyAccess.Success -> r.value
            }
        val secondSalt = salts.second
        val firstTitle = if (secondSalt == null) PRF_PROMPT_TITLE else "$PRF_PROMPT_TITLE (1 of 2)"
        val first =
            output(mac, salts.first, firstTitle, authenticators, content(secondSalt?.let { TWO_SALTS_NOTE }))
                ?: return KeyAccess.Success(null)
        if (secondSalt == null) return KeyAccess.Success(PrfResults(first, null))
        val second =
            when (val next = macFor()) {
                is KeyAccess.Success -> output(next.value, secondSalt, "$PRF_PROMPT_TITLE (2 of 2)", authenticators, content(null))
                is KeyAccess.Failure -> null
            }
        return KeyAccess.Success(second?.let { PrfResults(first, it) })
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
