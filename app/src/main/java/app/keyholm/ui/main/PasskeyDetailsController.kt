package app.keyholm.ui.main

import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.HmacKeyManager
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.util.logger
import app.keyholm.webauthn.CredentialId
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
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val log = logger()
    private val details = MutableStateFlow<PasskeyDetails>(PasskeyDetails.Closed)
    val state: StateFlow<PasskeyDetails> = details.asStateFlow()

    fun load(record: PasskeyRecord) {
        details.value = PasskeyDetails.Open(record.credentialId, Loadable.Loading, Loadable.Loading)
        scope.launch {
            val pem =
                withContext(dispatcher) {
                    runCatching { SecureKeyManager().certificateChainPem(record.keyAlias) }
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
            val read =
                withContext(dispatcher) {
                    runCatching {
                        val keyManager = SecureKeyManager()
                        val mainKey =
                            keyManager.allowedAuthenticatorsFor(record.keyAlias, record.keystore.coseAlgorithm)
                        if (record.hasPrf) {
                            KeyAuthenticators.WithPrf(mainKey, HmacKeyManager().allowedAuthenticatorsForHmac(record.hmacKeyAlias))
                        } else {
                            KeyAuthenticators.SigningOnly(mainKey)
                        }
                    }
                }
            val authenticators: Loadable<KeyAuthenticators> =
                read.fold(
                    onSuccess = { Loadable.Loaded(it) },
                    onFailure = { e ->
                        log.e(e) { "couldn't read the key authenticators" }
                        Loadable.Failed
                    },
                )
            updateOpen(record.credentialId) { it.copy(authenticators = authenticators) }
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
}
