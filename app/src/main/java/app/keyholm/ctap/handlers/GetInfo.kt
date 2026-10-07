package app.keyholm.ctap.handlers

import android.content.Context
import app.keyholm.authenticator.AlgorithmNegotiation
import app.keyholm.authenticator.availableFamilies
import app.keyholm.authenticator.availableMlDsaSupport
import app.keyholm.ctap.CANONICAL_CBOR
import app.keyholm.ctap.KEYHOLM_AAGUID
import app.keyholm.ctap.PUBLIC_KEY_TYPE
import app.keyholm.ctap.ZERO_AAGUID
import app.keyholm.pxp.ctap.MAX_CTAP_MESSAGE_BYTES
import app.keyholm.settings.Settings
import com.upokecenter.cbor.CBORObject

private const val GET_INFO_VERSIONS = 1
private const val GET_INFO_AAGUID = 3
private const val GET_INFO_OPTIONS = 4
private const val GET_INFO_MAX_MSG_SIZE = 5
private const val GET_INFO_MAX_CREDENTIAL_ID_LENGTH = 8
private const val GET_INFO_TRANSPORTS = 9
private const val GET_INFO_ALGORITHMS = 0x0A
private const val GET_INFO_FIRMWARE_VERSION = 0x0E
private const val GET_INFO_ATTESTATION_FORMATS = 0x16

internal fun authenticatorInfo(
    context: Context,
    settings: Settings,
    identifyAsKeyholm: Boolean,
): ByteArray {
    val enabled = availableFamilies(context, settings.enabledFamilies)
    val mlDsa = availableMlDsaSupport(context, settings.mlDsaSupport)
    val algorithms = CBORObject.NewArray()
    AlgorithmNegotiation
        .allowedAlgorithms(enabled, mlDsa)
        .forEach { algorithms.Add(CBORObject.NewMap().Add("alg", it.coseAlg).Add("type", PUBLIC_KEY_TYPE)) }

    val options =
        CBORObject
            .NewMap()
            .Add("rk", true)
            .Add("uv", true)
            .Add("alwaysUv", true)

    return CBORObject
        .NewMap()
        .Add(
            GET_INFO_VERSIONS,
            CBORObject
                .NewArray()
                .Add("FIDO_2_0")
                .Add("FIDO_2_1")
                .Add("FIDO_2_3"),
        ).Add(GET_INFO_AAGUID, (if (identifyAsKeyholm) KEYHOLM_AAGUID else ZERO_AAGUID).toByteArray())
        .apply {
            if (identifyAsKeyholm) {
                Add(GET_INFO_FIRMWARE_VERSION, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
            }
        }.Add(GET_INFO_OPTIONS, options)
        .Add(GET_INFO_MAX_MSG_SIZE, MAX_CTAP_MESSAGE_BYTES)
        .Add(GET_INFO_MAX_CREDENTIAL_ID_LENGTH, CREDENTIAL_ID_BYTES)
        .Add(GET_INFO_TRANSPORTS, CBORObject.NewArray().Add("hybrid"))
        .Add(GET_INFO_ALGORITHMS, algorithms)
        .Add(GET_INFO_ATTESTATION_FORMATS, CBORObject.NewArray().Add(ATTESTATION_ANDROID_KEY))
        .EncodeToBytes(CANONICAL_CBOR)
}
