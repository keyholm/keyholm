package app.keyholm.ctap

import app.keyholm.util.sha256
import com.upokecenter.cbor.CBORObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import kotlin.uuid.Uuid

private const val BYTE_MASK = 0xff
private const val BITS_PER_BYTE = 8
private const val TWO_BYTES_SHIFT = 16
private const val THREE_BYTES_SHIFT = 24
private const val ED25519_RAW_KEY_BYTES = 32
private const val ML_DSA_65_RAW_KEY_BYTES = 1952
private const val ML_DSA_87_RAW_KEY_BYTES = 2592
private const val COSE_CRV_ED25519 = 6
private const val COSE_KTY_AKP = 7
private const val FIELD_SIZE_BYTES = 32

// COSE_Key labels for an EC2/OKP key and an AKP key.
private const val COSE_LABEL_ALG = 3
private const val COSE_LABEL_AKP_PUB = -1
private const val COSE_LABEL_X_COORD = -2
private const val COSE_LABEL_Y_COORD = -3

private const val FLAG_UP = 0x01
private const val FLAG_UV = 0x04
private const val FLAG_AT = 0x40

val KEYHOLM_AAGUID: Uuid = Uuid.parse("69840def-9dcf-4632-bf87-6ac2e451b4f5")

val ZERO_AAGUID: Uuid = Uuid.NIL

@JvmInline
value class AuthenticatorData(
    val bytes: ByteArray,
) {
    companion object {
        fun forAssertion(
            rpId: RpId,
            signCount: Int,
        ): AuthenticatorData = authenticatorData(rpId, FLAG_UP or FLAG_UV, signCount)

        fun forRegistration(
            rpId: RpId,
            credentialId: CredentialId,
            pub: PublicKey,
            aaguid: Uuid,
            algorithm: WebAuthnAlgorithm,
        ): AuthenticatorData {
            val acd = attestedCredentialData(aaguid, credentialId, coseKey(pub, algorithm))
            return authenticatorData(rpId, FLAG_UP or FLAG_UV or FLAG_AT, 0, acd)
        }
    }
}

private fun authenticatorData(
    rpId: RpId,
    flags: Int,
    signCount: Int,
    attestedCredData: ByteArray? = null,
): AuthenticatorData {
    val out = ByteArrayOutputStream()
    out.write(sha256(rpId.value.toByteArray(Charsets.UTF_8)))
    out.write(flags)
    out.write((signCount ushr THREE_BYTES_SHIFT) and BYTE_MASK)
    out.write((signCount ushr TWO_BYTES_SHIFT) and BYTE_MASK)
    out.write((signCount ushr BITS_PER_BYTE) and BYTE_MASK)
    out.write(signCount and BYTE_MASK)
    if (attestedCredData != null) out.write(attestedCredData)
    return AuthenticatorData(out.toByteArray())
}

private fun attestedCredentialData(
    aaguid: Uuid,
    credentialId: CredentialId,
    cose: CoseKey,
): ByteArray {
    val credentialIdBytes = credentialId.bytes()
    val out = ByteArrayOutputStream()
    out.write(aaguid.toByteArray())
    out.write((credentialIdBytes.size ushr BITS_PER_BYTE) and BYTE_MASK)
    out.write(credentialIdBytes.size and BYTE_MASK)
    out.write(credentialIdBytes)
    out.write(cose.bytes)
    return out.toByteArray()
}

private fun BigInteger.toFixed32(): ByteArray {
    val raw = toByteArray()
    return when {
        raw.size == FIELD_SIZE_BYTES -> raw
        raw.size == FIELD_SIZE_BYTES + 1 && raw[0].toInt() == 0 -> raw.copyOfRange(1, FIELD_SIZE_BYTES + 1)
        raw.size < FIELD_SIZE_BYTES -> ByteArray(FIELD_SIZE_BYTES - raw.size) + raw
        else -> raw.copyOfRange(raw.size - FIELD_SIZE_BYTES, raw.size)
    }
}

private fun coseKey(
    pub: PublicKey,
    algorithm: WebAuthnAlgorithm,
): CoseKey =
    when (algorithm) {
        WebAuthnAlgorithm.ES256 -> {
            val ecPub = pub as ECPublicKey
            CBORObject
                .NewMap()
                .Add(1, 2) // kty = EC2
                .Add(COSE_LABEL_ALG, ALG_ES256)
                .Add(-1, 1) // crv = P-256
                .Add(COSE_LABEL_X_COORD, ecPub.w.affineX.toFixed32())
                .Add(COSE_LABEL_Y_COORD, ecPub.w.affineY.toFixed32())
                .EncodeToBytes(CANONICAL_CBOR)
                .let(::CoseKey)
        }

        WebAuthnAlgorithm.ED25519 -> {
            // X.509 SubjectPublicKeyInfo for Ed25519 is a fixed 12-byte prefix
            // followed by the raw 32-byte public key.
            val raw = pub.encoded.copyOfRange(pub.encoded.size - ED25519_RAW_KEY_BYTES, pub.encoded.size)
            CBORObject
                .NewMap()
                .Add(1, 1) // kty = OKP
                .Add(COSE_LABEL_ALG, ALG_EDDSA)
                .Add(-1, COSE_CRV_ED25519) // crv = Ed25519
                .Add(COSE_LABEL_X_COORD, raw)
                .EncodeToBytes(CANONICAL_CBOR)
                .let(::CoseKey)
        }

        WebAuthnAlgorithm.ML_DSA_65 -> {
            akpCoseKey(pub, ALG_ML_DSA_65, ML_DSA_65_RAW_KEY_BYTES)
        }

        WebAuthnAlgorithm.ML_DSA_87 -> {
            akpCoseKey(pub, ALG_ML_DSA_87, ML_DSA_87_RAW_KEY_BYTES)
        }
    }

// X.509 SubjectPublicKeyInfo for ML-DSA ends with the raw public key of the parameter set.
private fun akpCoseKey(
    pub: PublicKey,
    coseAlg: Int,
    rawKeyBytes: Int,
): CoseKey =
    CBORObject
        .NewMap()
        .Add(1, COSE_KTY_AKP) // kty = AKP
        .Add(COSE_LABEL_ALG, coseAlg)
        .Add(COSE_LABEL_AKP_PUB, pub.encoded.copyOfRange(pub.encoded.size - rawKeyBytes, pub.encoded.size))
        .EncodeToBytes(CANONICAL_CBOR)
        .let(::CoseKey)
