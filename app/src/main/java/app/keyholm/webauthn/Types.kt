package app.keyholm.webauthn

import kotlinx.serialization.Serializable

@JvmInline
value class ClientDataJson(
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
value class SpkiPublicKey(
    val bytes: ByteArray,
)

@JvmInline
value class DerSignature(
    val bytes: ByteArray,
)

@JvmInline
value class PrfInput(
    val bytes: ByteArray,
)

@Serializable
@JvmInline
value class PackageName(
    val value: String,
)

@JvmInline
value class CertFingerprint private constructor(
    val colonHex: String,
) {
    companion object {
        fun of(raw: String) = CertFingerprint(raw.uppercase())

        fun of(raw: Iterable<String>): Set<CertFingerprint> = raw.mapTo(mutableSetOf(), ::of)
    }
}
