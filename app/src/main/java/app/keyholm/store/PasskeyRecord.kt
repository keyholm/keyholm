package app.keyholm.store

import androidx.compose.runtime.Immutable
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.CredentialUser
import app.keyholm.webauthn.KeyAlias
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.WebAuthnAlgorithm
import com.google.protobuf.ByteString
import java.time.Instant

// @Immutable is required even with the ByteString stability config.
// Config/inference apparently only makes this "stable" via a runtime $stable field (@StabilityInferred)
// Strong skipping still compares by identity if it's captured in a remembered lambda.
// Proto DataStore emits a fresh equal PasskeyRecord per read
// Without this, the swipe-to-delete onDismiss lambda rebuilds every time
// and SwipeToDismissBox re-fires it (delete prompt shows 2-3x).
// @Immutablannotation makes captures compare by value.
@Immutable
data class PasskeyRecord(
    val credentialId: CredentialId,
    val rp: RelyingParty,
    val user: CredentialUser,
    val signCount: Int,
    val callingPackage: PackageName,
    val createdAt: Instant,
    val keystore: Keystore,
    val lifecycle: RecordLifecycle,
    val lastUsedAt: Instant,
    val likelyInvalid: Boolean,
) {
    data class Keystore(
        val coseAlgorithm: WebAuthnAlgorithm,
        val securityLevel: KeySecurityLevel,
        val publicKeySpki: ByteString,
        val prfSecurityLevel: KeySecurityLevel?,
    )

    val keyAlias: KeyAlias get() = credentialId.signingKeyAlias
    val hmacKeyAlias: KeyAlias get() = credentialId.hmacKeyAlias
    val hasPrf: Boolean get() = keystore.prfSecurityLevel != null
}

sealed interface RecordLifecycle {
    data object Active : RecordLifecycle

    data class PendingDelete(
        val at: Instant,
    ) : RecordLifecycle
}
