package app.keyholm.ctap.requests

import app.keyholm.ctap.ClientDataHash
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.RpId
import app.keyholm.ctap.byteStringOrNull
import app.keyholm.ctap.textOrNull
import app.keyholm.ctap.then
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

private const val GA_RP_ID = 1
private const val GA_CLIENT_DATA_HASH = 2
private const val GA_ALLOW_LIST = 3
private const val GA_EXTENSIONS = 4
private const val GA_OPTIONS = 5
private const val GA_PIN_UV_AUTH_PARAM = 6
private const val GA_PIN_UV_AUTH_PROTOCOL = 7

internal data class GetAssertionRequest(
    val rpId: RpId,
    val clientDataHash: ClientDataHash,
    val allowIds: Set<CredentialId>?,
    val prf: AssertionPrf?,
)

internal fun parseGetAssertion(body: ByteArray): Ctap2Result<GetAssertionRequest> {
    val map = runCatching { CBORObject.DecodeFromBytes(body) }.getOrNull()
    if (map == null || map.type != CBORType.Map) return Ctap2Result.Rejected(Ctap2Status.INVALID_CBOR)
    val rpId = map.textOrNull(GA_RP_ID)
    val clientDataHash = map.byteStringOrNull(GA_CLIENT_DATA_HASH)
    if (rpId == null || clientDataHash == null) return Ctap2Result.Rejected(Ctap2Status.MISSING_PARAMETER)
    return checkPinUvAuth(map.GetOrDefault(GA_PIN_UV_AUTH_PARAM, null), map.GetOrDefault(GA_PIN_UV_AUTH_PROTOCOL, null))
        .then { optionFlags(map.GetOrDefault(GA_OPTIONS, null)) }
        .then { options ->
            if (options.rk != null) {
                Ctap2Result.Rejected(Ctap2Status.UNSUPPORTED_OPTION)
            } else {
                parseAssertionPrf(map.GetOrDefault(GA_EXTENSIONS, null)).then { prf ->
                    Ctap2Result.Ok(
                        GetAssertionRequest(
                            RpId(rpId),
                            ClientDataHash(clientDataHash),
                            allowIds(map.GetOrDefault(GA_ALLOW_LIST, null)),
                            prf,
                        ),
                    )
                }
            }
        }
}

private fun allowIds(descriptors: CBORObject?): Set<CredentialId>? {
    if (descriptors == null || descriptors.type != CBORType.Array || descriptors.values.isEmpty()) return null
    return credentialIds(descriptors)
}
