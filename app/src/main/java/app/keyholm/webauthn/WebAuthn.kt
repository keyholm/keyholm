package app.keyholm.webauthn

import app.keyholm.ctap.AuthenticatorData
import app.keyholm.ctap.ClientDataHash
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.PrfResults
import app.keyholm.ctap.PrfSalts
import app.keyholm.ctap.UserHandle
import app.keyholm.ctap.WebAuthnAlgorithm
import app.keyholm.util.B64
import app.keyholm.util.sha256
import com.upokecenter.cbor.CBOREncodeOptions
import com.upokecenter.cbor.CBORObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val CANONICAL = CBOREncodeOptions("ctap2canonical=true")

private val json = Json { explicitNulls = false }

@Serializable
private data class ClientDataJsonPayload(
    val type: String,
    val challenge: String,
    val origin: String,
    val crossOrigin: Boolean,
)

private fun clientDataJson(
    type: ClientDataType,
    challengeB64Url: String,
    origin: String,
): ClientDataJson =
    json
        .encodeToString(ClientDataJsonPayload(type.json, challengeB64Url, origin, crossOrigin = false))
        .toByteArray(Charsets.UTF_8)
        .let(::ClientDataJson)

enum class ClientDataType(
    val json: String,
) {
    Create("webauthn.create"),
    Get("webauthn.get"),
}

object WebAuthn {
    val PLACEHOLDER_CLIENT_DATA_JSON: ClientDataJson =
        ClientDataJson("""{"clientDataJSON":"provided_by_caller"}""".toByteArray(Charsets.UTF_8))

    class ClientData(
        val hash: ClientDataHash,
        val json: ClientDataJson,
    )

    fun clientData(
        type: ClientDataType,
        challengeB64Url: String,
        caller: Caller.Trusted,
    ): ClientData {
        fun assemble(origin: String): ClientData {
            val json = clientDataJson(type, challengeB64Url, origin)
            return ClientData(ClientDataHash(sha256(json.bytes)), json)
        }
        return when (caller) {
            is Caller.Privileged.Client -> ClientData(caller.hash, PLACEHOLDER_CLIENT_DATA_JSON)
            is Caller.Privileged.Origin -> assemble(caller.origin)
            is Caller.Normal -> assemble(caller.origin)
        }
    }
}

sealed interface RegistrationPrf {
    data object NotRequested : RegistrationPrf

    data object Requested : RegistrationPrf

    data class Evaluated(
        val results: PrfResults,
    ) : RegistrationPrf
}

object PrfExtension {
    private const val SALT_PREFIX = "WebAuthn PRF"

    data class Inputs(
        val first: PrfInput,
        val second: PrfInput?,
    )

    fun requestedAtCreation(creationOptions: CreationOptions): Boolean = creationOptions.extensions?.prf != null

    fun inputsForAssertion(
        requestOptions: RequestOptions,
        credentialId: CredentialId,
    ): Inputs? {
        val prf = requestOptions.extensions?.prf ?: return null
        val eval = prf.evalByCredential?.get(credentialId.b64) ?: prf.eval ?: return null
        return parseEval(eval)
    }

    fun inputsForCreation(creationOptions: CreationOptions): Inputs? {
        val eval = creationOptions.extensions?.prf?.eval ?: return null
        return parseEval(eval)
    }

    private fun parseEval(eval: PrfEval): Inputs? {
        val first = eval.first?.takeIf { it.isNotEmpty() } ?: return null
        val second = eval.second?.takeIf { it.isNotEmpty() }
        return Inputs(PrfInput(B64.dec(first)), second?.let { PrfInput(B64.dec(it)) })
    }

    private fun salt(input: PrfInput): ByteArray = sha256(SALT_PREFIX.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + input.bytes)

    fun salts(inputs: Inputs): PrfSalts = PrfSalts(salt(inputs.first), inputs.second?.let(::salt))
}

fun CreationOptions.requestedAlgorithms(): List<WebAuthnAlgorithm>? {
    if (pubKeyCredParams.isEmpty()) return null
    return pubKeyCredParams.mapNotNull { WebAuthnAlgorithm.fromCoseAlg(it.alg) }
}

private const val NONE_FMT = "none"
private const val ANDROID_KEY_FMT = "android-key"

object AttestationObjects {
    fun attestationObjectNone(authData: AuthenticatorData): AttestationObject =
        CBORObject
            .NewMap()
            .Add("fmt", NONE_FMT)
            .Add("attStmt", CBORObject.NewMap())
            .Add("authData", authData.bytes)
            .EncodeToBytes(CANONICAL)
            .let(::AttestationObject)

    fun attestationObjectAndroidKey(
        authData: AuthenticatorData,
        signature: DerSignature,
        x5c: List<ByteArray>,
        alg: Int,
    ): AttestationObject {
        val x5cArray = CBORObject.NewArray()
        x5c.forEach { x5cArray.Add(it) }
        val attStmt =
            CBORObject
                .NewMap()
                .Add("alg", alg)
                .Add("sig", signature.bytes)
                .Add("x5c", x5cArray)
        return CBORObject
            .NewMap()
            .Add("fmt", ANDROID_KEY_FMT)
            .Add("attStmt", attStmt)
            .Add("authData", authData.bytes)
            .EncodeToBytes(CANONICAL)
            .let(::AttestationObject)
    }
}

@Serializable
private data class CredPropsResult(
    val rk: Boolean,
)

@Serializable
private data class PrfResultsJson(
    val first: String,
    val second: String? = null,
)

@Serializable
private data class RegistrationPrfResult(
    val enabled: Boolean,
    val results: PrfResultsJson? = null,
)

@Serializable
private data class RegistrationExtensionResults(
    val credProps: CredPropsResult? = null,
    val prf: RegistrationPrfResult? = null,
)

@Serializable
private data class AssertionPrfResult(
    val results: PrfResultsJson,
)

@Serializable
private data class AssertionExtensionResults(
    val prf: AssertionPrfResult? = null,
)

@Serializable
private data class AttestationResponsePayload(
    val clientDataJSON: String,
    val attestationObject: String,
    val authenticatorData: String,
    val transports: List<String>,
    val publicKeyAlgorithm: Int,
    val publicKey: String,
)

@Serializable
private data class RegistrationResult(
    val id: String,
    val rawId: String,
    val type: String,
    val authenticatorAttachment: String,
    val response: AttestationResponsePayload,
    val clientExtensionResults: RegistrationExtensionResults,
)

@Serializable
private data class AssertionResponsePayload(
    val clientDataJSON: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String,
)

@Serializable
private data class AssertionResult(
    val id: String,
    val rawId: String,
    val type: String,
    val authenticatorAttachment: String,
    val response: AssertionResponsePayload,
    val clientExtensionResults: AssertionExtensionResults,
)

private const val PUBLIC_KEY_REGISTRATION_TYPE = "public-key"
private const val PLATFORM_ATTACHMENT = "platform"
private const val INTERNAL_TRANSPORT = "internal"

data class AttestationResponse(
    val clientDataJSON: ClientDataJson,
    val attestationObject: AttestationObject,
    val authData: AuthenticatorData,
    val spkiPublicKey: SpkiPublicKey,
    val algorithm: WebAuthnAlgorithm,
)

data class AssertionResponse(
    val clientDataJSON: ClientDataJson,
    val authData: AuthenticatorData,
    val derSignature: DerSignature,
    val userHandle: UserHandle,
)

object CredentialResponseJson {
    fun registrationResponseJson(
        credentialId: CredentialId,
        response: AttestationResponse,
        credPropsRequested: Boolean = false,
        discoverable: Boolean,
        prf: RegistrationPrf,
    ): String {
        val id = credentialId.b64
        val payload =
            AttestationResponsePayload(
                clientDataJSON = B64.enc(response.clientDataJSON.bytes),
                attestationObject = B64.enc(response.attestationObject.bytes),
                authenticatorData = B64.enc(response.authData.bytes),
                transports = listOf(INTERNAL_TRANSPORT),
                publicKeyAlgorithm = response.algorithm.coseAlg,
                publicKey = B64.enc(response.spkiPublicKey.bytes),
            )
        val clientExtensionResults =
            RegistrationExtensionResults(
                credProps = if (credPropsRequested) CredPropsResult(rk = discoverable) else null,
                prf =
                    when (prf) {
                        RegistrationPrf.NotRequested -> {
                            null
                        }

                        RegistrationPrf.Requested -> {
                            RegistrationPrfResult(enabled = true, results = null)
                        }

                        is RegistrationPrf.Evaluated -> {
                            RegistrationPrfResult(
                                enabled = true,
                                results =
                                    PrfResultsJson(
                                        B64.enc(prf.results.first.bytes),
                                        prf.results.second?.let { B64.enc(it.bytes) },
                                    ),
                            )
                        }
                    },
            )
        return json.encodeToString(
            RegistrationResult(
                id = id,
                rawId = id,
                type = PUBLIC_KEY_REGISTRATION_TYPE,
                authenticatorAttachment = PLATFORM_ATTACHMENT,
                response = payload,
                clientExtensionResults = clientExtensionResults,
            ),
        )
    }

    fun assertionResponseJson(
        credentialId: CredentialId,
        response: AssertionResponse,
        prfResults: PrfResults? = null,
    ): String {
        val id = credentialId.b64
        val payload =
            AssertionResponsePayload(
                clientDataJSON = B64.enc(response.clientDataJSON.bytes),
                authenticatorData = B64.enc(response.authData.bytes),
                signature = B64.enc(response.derSignature.bytes),
                userHandle = response.userHandle.b64,
            )
        val clientExtensionResults =
            AssertionExtensionResults(
                prf =
                    prfResults?.let {
                        AssertionPrfResult(
                            PrfResultsJson(B64.enc(it.first.bytes), it.second?.let { second -> B64.enc(second.bytes) }),
                        )
                    },
            )
        return json.encodeToString(
            AssertionResult(
                id = id,
                rawId = id,
                type = PUBLIC_KEY_REGISTRATION_TYPE,
                authenticatorAttachment = PLATFORM_ATTACHMENT,
                response = payload,
                clientExtensionResults = clientExtensionResults,
            ),
        )
    }
}
