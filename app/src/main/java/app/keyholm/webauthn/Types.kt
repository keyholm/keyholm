package app.keyholm.webauthn

import app.keyholm.util.B64
import kotlinx.serialization.Serializable

@JvmInline
value class ClientDataHash(
    val bytes: ByteArray,
)

@JvmInline
value class ClientDataJson(
    val bytes: ByteArray,
)

@JvmInline
value class AuthenticatorData(
    val bytes: ByteArray,
)

@JvmInline
value class AttestationObject(
    val bytes: ByteArray,
)

@JvmInline
value class SigningInput(
    val bytes: ByteArray,
)

@JvmInline
value class CoseKey(
    val bytes: ByteArray,
)

@JvmInline
value class SpkiPublicKey(
    val bytes: ByteArray,
)

@JvmInline
value class DerSignature(
    val bytes: ByteArray,
)

@JvmInline
value class Aaguid(
    val bytes: ByteArray,
)

@JvmInline
value class PrfSalt(
    val bytes: ByteArray,
)

@JvmInline
value class PrfOutput(
    val bytes: ByteArray,
)

@Serializable
@JvmInline
value class RpId(
    val value: String,
)

@Serializable
@JvmInline
value class PackageName(
    val value: String,
)

@JvmInline
value class KeyAlias(
    val value: String,
)

@Serializable
@JvmInline
value class UserHandle(
    val b64: String,
) {
    fun bytes(): ByteArray = B64.dec(b64)

    companion object {
        fun of(bytes: ByteArray) = UserHandle(B64.enc(bytes))
    }
}

@Serializable
@JvmInline
value class CredentialId(
    val b64: String,
) {
    val signingKeyAlias: KeyAlias get() = KeyAlias(SIGNING_ALIAS_PREFIX + b64)
    val hmacKeyAlias: KeyAlias get() = KeyAlias(HMAC_ALIAS_PREFIX + b64)

    fun bytes(): ByteArray = B64.dec(b64)

    companion object {
        private const val SIGNING_ALIAS_PREFIX = "key_"
        private const val HMAC_ALIAS_PREFIX = "hmac_"

        fun of(bytes: ByteArray) = CredentialId(B64.enc(bytes))
    }
}

@JvmInline
value class CertFingerprint private constructor(
    val colonHex: String,
) {
    companion object {
        fun of(raw: String) = CertFingerprint(raw.uppercase())

        fun of(raw: Iterable<String>): Set<CertFingerprint> = raw.mapTo(mutableSetOf(), ::of)
    }
}
