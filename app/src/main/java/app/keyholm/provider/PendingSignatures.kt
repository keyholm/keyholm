package app.keyholm.provider

import app.keyholm.webauthn.CredentialId
import java.security.Signature
import java.util.concurrent.ConcurrentHashMap

internal object PendingSignatures {
    private val signaturesByCredentialId = ConcurrentHashMap<CredentialId, Signature>()

    fun put(
        credentialId: CredentialId,
        signature: Signature,
    ) {
        signaturesByCredentialId[credentialId] = signature
    }

    fun take(credentialId: CredentialId): Signature? = signaturesByCredentialId.remove(credentialId)

    fun clear() {
        signaturesByCredentialId.clear()
    }
}
