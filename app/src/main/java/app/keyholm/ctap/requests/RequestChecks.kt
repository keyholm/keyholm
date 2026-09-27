package app.keyholm.ctap.requests

import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.byteStringOrNull
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

internal fun credentialIds(descriptors: CBORObject?): Set<CredentialId> {
    if (descriptors == null || descriptors.type != CBORType.Array) return emptySet()
    return descriptors.values.mapNotNullTo(mutableSetOf()) { it.byteStringOrNull("id")?.let(CredentialId::of) }
}

// No PIN/UV auth protocol is supported, so a pinUvAuthParam can never be verified.
internal fun checkPinUvAuth(
    param: CBORObject?,
    protocol: CBORObject?,
): Ctap2Result<Unit> =
    when {
        param == null -> Ctap2Result.Ok(Unit)
        protocol == null -> Ctap2Result.Rejected(Ctap2Status.MISSING_PARAMETER)
        else -> Ctap2Result.Rejected(Ctap2Status.INVALID_PARAMETER)
    }
