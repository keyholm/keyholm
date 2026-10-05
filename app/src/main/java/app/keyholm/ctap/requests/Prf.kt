package app.keyholm.ctap.requests

import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.PrfSalts
import app.keyholm.ctap.then
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

// Chromium's "prf" extension for hybrid authenticators; the client has already hashed the salts.
internal const val EXTENSION_PRF = "prf"
private const val PRF_EVAL = "eval"
private const val PRF_EVAL_BY_CREDENTIAL = "evalByCredential"
internal const val PRF_FIRST = "first"
internal const val PRF_SECOND = "second"
private const val PRF_SALT_BYTES = 32

internal class CreationPrf(
    val eval: PrfSalts?,
)

internal class AssertionPrf(
    val eval: PrfSalts?,
    val byCredential: Map<CredentialId, PrfSalts>,
) {
    fun saltsFor(credentialId: CredentialId): PrfSalts? = byCredential[credentialId] ?: eval
}

internal fun parseCreationPrf(extensions: CBORObject?): Ctap2Result<CreationPrf?> =
    prfExtension(extensions).then { prf ->
        if (prf == null) {
            Ctap2Result.Ok(null)
        } else {
            optionalSalts(prf.GetOrDefault(PRF_EVAL, null)).then { Ctap2Result.Ok(CreationPrf(it)) }
        }
    }

internal fun parseAssertionPrf(extensions: CBORObject?): Ctap2Result<AssertionPrf?> =
    prfExtension(extensions).then { prf ->
        if (prf == null) {
            Ctap2Result.Ok(null)
        } else {
            optionalSalts(prf.GetOrDefault(PRF_EVAL, null)).then { eval ->
                byCredential(prf.GetOrDefault(PRF_EVAL_BY_CREDENTIAL, null)).then { Ctap2Result.Ok(AssertionPrf(eval, it)) }
            }
        }
    }

private fun prfExtension(extensions: CBORObject?): Ctap2Result<CBORObject?> {
    if (extensions == null) return Ctap2Result.Ok(null)
    if (extensions.type != CBORType.Map) return Ctap2Result.Rejected(Ctap2Status.CBOR_UNEXPECTED_TYPE)
    val prf = extensions.GetOrDefault(EXTENSION_PRF, null)
    return when {
        prf == null -> Ctap2Result.Ok(null)
        prf.type != CBORType.Map -> Ctap2Result.Rejected(Ctap2Status.CBOR_UNEXPECTED_TYPE)
        else -> Ctap2Result.Ok(prf)
    }
}

private fun optionalSalts(raw: CBORObject?): Ctap2Result<PrfSalts?> = if (raw == null) Ctap2Result.Ok(null) else salts(raw)

private fun salts(raw: CBORObject): Ctap2Result<PrfSalts> {
    if (raw.type != CBORType.Map) return Ctap2Result.Rejected(Ctap2Status.CBOR_UNEXPECTED_TYPE)
    val first = raw.GetOrDefault(PRF_FIRST, null)
    val second = raw.GetOrDefault(PRF_SECOND, null)
    return when {
        first == null -> Ctap2Result.Rejected(Ctap2Status.MISSING_PARAMETER)
        !isSalt(first) || (second != null && !isSalt(second)) -> Ctap2Result.Rejected(Ctap2Status.INVALID_PARAMETER)
        else -> Ctap2Result.Ok(PrfSalts(first.GetByteString(), second?.GetByteString()))
    }
}

private fun isSalt(value: CBORObject): Boolean = value.type == CBORType.ByteString && value.GetByteString().size == PRF_SALT_BYTES

private fun byCredential(raw: CBORObject?): Ctap2Result<Map<CredentialId, PrfSalts>> =
    when {
        raw == null -> {
            Ctap2Result.Ok(emptyMap())
        }

        raw.type != CBORType.Map -> {
            Ctap2Result.Rejected(Ctap2Status.CBOR_UNEXPECTED_TYPE)
        }

        raw.keys.any { it.type != CBORType.ByteString || it.GetByteString().isEmpty() } -> {
            Ctap2Result.Rejected(Ctap2Status.INVALID_PARAMETER)
        }

        else -> {
            raw.keys.fold<CBORObject, Ctap2Result<Map<CredentialId, PrfSalts>>>(Ctap2Result.Ok(emptyMap())) { parsed, key ->
                parsed.then { map -> salts(raw[key]).then { Ctap2Result.Ok(map + (CredentialId.of(key.GetByteString()) to it)) } }
            }
        }
    }
