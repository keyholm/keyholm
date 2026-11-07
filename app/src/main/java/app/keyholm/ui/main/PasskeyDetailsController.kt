package app.keyholm.ui.main

import android.content.Context
import android.content.Intent
import app.keyholm.authenticator.AssertionKeyMaterial
import app.keyholm.authenticator.KeyAccess
import app.keyholm.ctap.CredentialId
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.PasskeyKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.util.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AttestationInfo(
    val pemCerts: List<String>,
)

sealed interface KeyAuthenticators {
    val mainKey: AuthenticatorPolicy

    data class SigningOnly(
        override val mainKey: AuthenticatorPolicy,
    ) : KeyAuthenticators

    data class WithPrf(
        override val mainKey: AuthenticatorPolicy,
        val prfKey: AuthenticatorPolicy,
    ) : KeyAuthenticators
}

sealed interface PasskeyDetails {
    data object Closed : PasskeyDetails

    data class Open(
        val credentialId: CredentialId,
        val attestation: Loadable<AttestationInfo>,
        val authenticators: Loadable<KeyAuthenticators>,
    ) : PasskeyDetails
}

class PasskeyDetailsController(
    private val context: Context,
    passkeyRepo: PasskeyRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val log = logger()
    private val keys = AssertionKeyMaterial(passkeyRepo, dispatcher)
    private val details = MutableStateFlow<PasskeyDetails>(PasskeyDetails.Closed)
    val state: StateFlow<PasskeyDetails> = details.asStateFlow()

    fun load(record: PasskeyRecord) {
        details.value = PasskeyDetails.Open(record.credentialId, Loadable.Loading, Loadable.Loading)
        scope.launch {
            val pem =
                withContext(dispatcher) {
                    runCatching { PasskeyKeyManager().certificateChainPem(record.credentialId) }
                }
            val attestation: Loadable<AttestationInfo> =
                pem.fold(
                    onSuccess = { Loadable.Loaded(AttestationInfo(it)) },
                    onFailure = { e ->
                        log.e(e) { "couldn't read the certificate chain" }
                        Loadable.Failed
                    },
                )
            updateOpen(record.credentialId) { it.copy(attestation = attestation) }
        }
        scope.launch {
            val authenticators =
                when (val mainKey = keys.authenticatorsFor(record)) {
                    is KeyAccess.Failure -> Loadable.Failed
                    is KeyAccess.Success -> authenticatorsFor(record, mainKey.value)
                }
            updateOpen(record.credentialId) { it.copy(authenticators = authenticators) }
        }
    }

    private suspend fun authenticatorsFor(
        record: PasskeyRecord,
        mainKey: AuthenticatorPolicy,
    ): Loadable<KeyAuthenticators> {
        if (!record.hasPrf) return Loadable.Loaded(KeyAuthenticators.SigningOnly(mainKey))
        return when (val prfKey = keys.hmacAuthenticatorsFor(record)) {
            is KeyAccess.Failure -> Loadable.Failed
            is KeyAccess.Success -> Loadable.Loaded(KeyAuthenticators.WithPrf(mainKey, prfKey.value))
        }
    }

    private fun updateOpen(
        credentialId: CredentialId,
        transform: (PasskeyDetails.Open) -> PasskeyDetails.Open,
    ) {
        details.update { current ->
            if (current is PasskeyDetails.Open && current.credentialId == credentialId) transform(current) else current
        }
    }

    fun clear() {
        details.value = PasskeyDetails.Closed
    }

    suspend fun openPasskeyCallerIntent(record: PasskeyRecord): Intent = openPasskeyCallerIntent(context, record, dispatcher, log)
}
