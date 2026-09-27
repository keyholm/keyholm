package app.keyholm.ctap.requests

import app.keyholm.ctap.ClientDataHash
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.CredentialUser
import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.PUBLIC_KEY_TYPE
import app.keyholm.ctap.RelyingParty
import app.keyholm.ctap.RpId
import app.keyholm.ctap.UserHandle
import app.keyholm.ctap.WebAuthnAlgorithm
import app.keyholm.ctap.byteStringOrNull
import app.keyholm.ctap.textOrNull
import app.keyholm.ctap.then
import app.keyholm.provider.AttestationOffer
import app.keyholm.ui.createpasskey.RegistrantInfo
import app.keyholm.webauthn.PackageName
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

private const val MC_CLIENT_DATA_HASH = 1
private const val MC_RP = 2
private const val MC_USER = 3
private const val MC_PUB_KEY_CRED_PARAMS = 4
private const val MC_EXCLUDE_LIST = 5
private const val MC_OPTIONS = 7
private const val MC_PIN_UV_AUTH_PARAM = 8
private const val MC_PIN_UV_AUTH_PROTOCOL = 9
private const val MC_ATTESTATION_FORMATS = 0x0B

private const val ATTESTATION_NONE = "none"

internal data class MakeCredentialRequest(
    val clientDataHash: ClientDataHash,
    val info: RegistrantInfo,
    val algorithms: List<WebAuthnAlgorithm>,
    val excludeIds: Set<CredentialId>,
    val attestation: AttestationOffer,
)

internal fun parseMakeCredential(body: ByteArray): Ctap2Result<MakeCredentialRequest> {
    val map = runCatching { CBORObject.DecodeFromBytes(body) }.getOrNull()
    if (map == null || map.type != CBORType.Map) return Ctap2Result.Rejected(Ctap2Status.INVALID_CBOR)
    return fromMap(map)
}

private fun fromMap(map: CBORObject): Ctap2Result<MakeCredentialRequest> {
    val rawRp = map.GetOrDefault(MC_RP, null) ?: return Ctap2Result.Rejected(Ctap2Status.MISSING_PARAMETER)
    val rpId = rawRp.textOrNull("id") ?: return Ctap2Result.Rejected(Ctap2Status.MISSING_PARAMETER)
    val rp = RelyingParty(RpId(rpId), rawRp.textOrNull("name").orEmpty())
    val user = map.GetOrDefault(MC_USER, null)
    val clientDataHash = map.byteStringOrNull(MC_CLIENT_DATA_HASH)
    val userHandle = user?.byteStringOrNull("id")
    val params = map.GetOrDefault(MC_PUB_KEY_CRED_PARAMS, null)

    if (clientDataHash == null || userHandle == null || params == null) {
        return Ctap2Result.Rejected(Ctap2Status.MISSING_PARAMETER)
    }
    return checkPinUvAuth(map.GetOrDefault(MC_PIN_UV_AUTH_PARAM, null), map.GetOrDefault(MC_PIN_UV_AUTH_PROTOCOL, null))
        .then { algorithms(params) }
        .then { algorithms ->
            optionFlags(map.GetOrDefault(MC_OPTIONS, null)).then { options ->
                if (options.up == false) {
                    Ctap2Result.Rejected(Ctap2Status.INVALID_OPTION)
                } else {
                    Ctap2Result.Ok(
                        MakeCredentialRequest(
                            clientDataHash = ClientDataHash(clientDataHash),
                            info =
                                RegistrantInfo(
                                    rp = rp,
                                    user =
                                        CredentialUser(
                                            handle = UserHandle.of(userHandle),
                                            name = user.textOrNull("name").orEmpty(),
                                            displayName = user.textOrNull("displayName").orEmpty(),
                                        ),
                                    callingPackage = PackageName(""),
                                    credPropsRequested = false,
                                    prfRequested = false,
                                    discoverable = options.rk == true,
                                ),
                            algorithms = algorithms,
                            excludeIds = credentialIds(map.GetOrDefault(MC_EXCLUDE_LIST, null)),
                            attestation =
                                if (strings(map.GetOrDefault(MC_ATTESTATION_FORMATS, null)) == listOf(ATTESTATION_NONE)) {
                                    AttestationOffer.NotRequested
                                } else {
                                    AttestationOffer.Ask
                                },
                        ),
                    )
                }
            }
        }
}

private fun algorithms(params: CBORObject): Ctap2Result<List<WebAuthnAlgorithm>> {
    if (params.type != CBORType.Array) return Ctap2Result.Rejected(Ctap2Status.CBOR_UNEXPECTED_TYPE)
    val entries = params.values
    return when {
        entries.any { it.type != CBORType.Map || !it.ContainsKey("type") || !it.ContainsKey("alg") } -> {
            Ctap2Result.Rejected(Ctap2Status.INVALID_CBOR)
        }

        entries.any { it["type"].type != CBORType.TextString || it["alg"].type != CBORType.Integer } -> {
            Ctap2Result.Rejected(Ctap2Status.CBOR_UNEXPECTED_TYPE)
        }

        else -> {
            Ctap2Result.Ok(
                entries
                    .filter { it["type"].AsString() == PUBLIC_KEY_TYPE && it["alg"].CanValueFitInInt32() }
                    .mapNotNull { WebAuthnAlgorithm.fromCoseAlg(it["alg"].AsInt32Value()) },
            )
        }
    }
}

private fun strings(array: CBORObject?): List<String> {
    if (array == null || array.type != CBORType.Array) return emptyList()
    return array.values.filter { it.type == CBORType.TextString }.map { it.AsString() }
}
