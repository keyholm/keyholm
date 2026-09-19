package app.keyholm.webauthn

import app.keyholm.util.B64
import app.keyholm.util.sha256
import com.upokecenter.cbor.CBOREncodeOptions
import com.upokecenter.cbor.CBORObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.util.UUID
import javax.crypto.Mac

private const val BYTE_MASK = 0xff
private const val BITS_PER_BYTE = 8
private const val TWO_BYTES_SHIFT = 16
private const val THREE_BYTES_SHIFT = 24
private const val ED25519_RAW_KEY_BYTES = 32
private const val ML_DSA_65_RAW_KEY_BYTES = 1952
private const val ML_DSA_87_RAW_KEY_BYTES = 2592
private const val AAGUID_BYTES = 16
private const val COSE_CRV_ED25519 = 6
private const val COSE_KTY_AKP = 7
private const val FIELD_SIZE_BYTES = 32

// COSE_Key labels for an EC2/OKP key (RFC 9053 §7.1) and an AKP key (RFC 9964 §6).
private const val COSE_LABEL_ALG = 3
private const val COSE_LABEL_AKP_PUB = -1
private const val COSE_LABEL_X_COORD = -2
private const val COSE_LABEL_Y_COORD = -3

private val CANONICAL = CBOREncodeOptions("ctap2canonical=true")

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
                .Add(COSE_LABEL_ALG, WebAuthn.ALG_ES256)
                .Add(-1, 1) // crv = P-256
                .Add(COSE_LABEL_X_COORD, ecPub.w.affineX.toFixed32())
                .Add(COSE_LABEL_Y_COORD, ecPub.w.affineY.toFixed32())
                .EncodeToBytes(CANONICAL)
                .let(::CoseKey)
        }

        WebAuthnAlgorithm.ED25519 -> {
            // X.509 SubjectPublicKeyInfo for Ed25519 is a fixed 12-byte prefix
            // followed by the raw 32-byte public key.
            val raw = pub.encoded.copyOfRange(pub.encoded.size - ED25519_RAW_KEY_BYTES, pub.encoded.size)
            CBORObject
                .NewMap()
                .Add(1, 1) // kty = OKP
                .Add(COSE_LABEL_ALG, WebAuthn.ALG_EDDSA)
                .Add(-1, COSE_CRV_ED25519) // crv = Ed25519
                .Add(COSE_LABEL_X_COORD, raw)
                .EncodeToBytes(CANONICAL)
                .let(::CoseKey)
        }

        WebAuthnAlgorithm.ML_DSA_65 -> {
            akpCoseKey(pub, WebAuthn.ALG_ML_DSA_65, ML_DSA_65_RAW_KEY_BYTES)
        }

        WebAuthnAlgorithm.ML_DSA_87 -> {
            akpCoseKey(pub, WebAuthn.ALG_ML_DSA_87, ML_DSA_87_RAW_KEY_BYTES)
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
        .EncodeToBytes(CANONICAL)
        .let(::CoseKey)

private fun attestedCredentialData(
    aaguid: Aaguid,
    credentialId: CredentialId,
    cose: CoseKey,
): ByteArray {
    val credentialIdBytes = credentialId.bytes()
    val out = ByteArrayOutputStream()
    out.write(aaguid.bytes)
    out.write((credentialIdBytes.size ushr BITS_PER_BYTE) and BYTE_MASK)
    out.write(credentialIdBytes.size and BYTE_MASK)
    out.write(credentialIdBytes)
    out.write(cose.bytes)
    return out.toByteArray()
}

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

enum class WebAuthnAlgorithm(
    val coseAlg: Int,
    val displayName: String,
    val family: AlgorithmFamily,
) {
    ES256(WebAuthn.ALG_ES256, "ES256", AlgorithmFamily.ES256),
    ED25519(WebAuthn.ALG_EDDSA, "Ed25519", AlgorithmFamily.ED25519),
    ML_DSA_87(WebAuthn.ALG_ML_DSA_87, "ML-DSA-87", AlgorithmFamily.ML_DSA),
    ML_DSA_65(WebAuthn.ALG_ML_DSA_65, "ML-DSA-65", AlgorithmFamily.ML_DSA),
    ;

    companion object {
        fun fromCoseAlg(coseAlg: Int): WebAuthnAlgorithm? = entries.firstOrNull { it.coseAlg == coseAlg }
    }
}

enum class AlgorithmFamily(
    val displayName: String,
) {
    ES256("ES256"),
    ED25519("Ed25519"),
    ML_DSA("ML-DSA"),
    ;

    val algorithms: List<WebAuthnAlgorithm> get() = WebAuthnAlgorithm.entries.filter { it.family == this }

    companion object {
        fun named(name: String): AlgorithmFamily = entries.firstOrNull { it.name == name } ?: error("unknown algorithm-family token $name")

        fun fromStrings(strings: Set<String>): Set<AlgorithmFamily> = strings.mapTo(mutableSetOf(), ::named)

        fun toStrings(families: Set<AlgorithmFamily>): Set<String> = families.map { it.name }.toSet()
    }
}

enum class MlDsaSupport { OFF, STRONGEST_ONLY, PREFER_STRONGEST, FIRST_OFFERED }

sealed interface AlgorithmPreference {
    data object FirstOffered : AlgorithmPreference

    data object AlwaysAsk : AlgorithmPreference

    data class Prefer(
        val family: AlgorithmFamily,
    ) : AlgorithmPreference
}

enum class ClientDataType(
    val json: String,
) {
    Create("webauthn.create"),
    Get("webauthn.get"),
}

enum class IdentityPreference { ENABLED, DISABLED, ALWAYS_ASK }

object WebAuthn {
    private const val FLAG_UP = 0x01
    private const val FLAG_UV = 0x04
    private const val FLAG_AT = 0x40

    // COSE algorithm identifiers (RFC 9053 §7.1, RFC 9964 §7.1)
    const val ALG_ES256 = -7
    const val ALG_EDDSA = -8
    const val ALG_ML_DSA_65 = -49
    const val ALG_ML_DSA_87 = -50

    fun supportsAttachment(creationOptions: CreationOptions): Boolean {
        val attachment = creationOptions.authenticatorSelection?.authenticatorAttachment
        return attachment.isNullOrEmpty() || attachment == PLATFORM_ATTACHMENT
    }

    val KEYHOLM_AAGUID: Aaguid =
        run {
            val uuid = UUID.fromString("69840def-9dcf-4632-bf87-6ac2e451b4f5")
            ByteBuffer
                .allocate(AAGUID_BYTES)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
                .let(::Aaguid)
        }

    val ZERO_AAGUID: Aaguid = Aaguid(ByteArray(AAGUID_BYTES))

    fun assertionAuthData(
        rpId: RpId,
        signCount: Int,
    ): AuthenticatorData = authenticatorData(rpId, FLAG_UP or FLAG_UV, signCount)

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

    fun registrationAuthData(
        rpId: RpId,
        credentialId: CredentialId,
        pub: PublicKey,
        aaguid: Aaguid,
        algorithm: WebAuthnAlgorithm,
    ): AuthenticatorData {
        val acd = attestedCredentialData(aaguid, credentialId, coseKey(pub, algorithm))
        return authenticatorData(rpId, FLAG_UP or FLAG_UV or FLAG_AT, 0, acd)
    }

    val PLACEHOLDER_CLIENT_DATA_JSON: ClientDataJson =
        ClientDataJson("{\"clientDataJSON\":\"provided_by_caller\"}".toByteArray(Charsets.UTF_8))

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
        val results: PrfExtension.Results,
    ) : RegistrationPrf
}

object PrfExtension {
    private const val SALT_PREFIX = "WebAuthn PRF"

    data class Salts(
        val first: PrfSalt,
        val second: PrfSalt?,
    )

    data class Results(
        val first: PrfOutput,
        val second: PrfOutput?,
    )

    fun requestedAtCreation(creationOptions: CreationOptions): Boolean = creationOptions.extensions?.prf != null

    fun saltsForAssertion(
        requestOptions: RequestOptions,
        credentialId: CredentialId,
    ): Salts? {
        val prf = requestOptions.extensions?.prf ?: return null
        val eval = prf.evalByCredential?.get(credentialId.b64) ?: prf.eval ?: return null
        return parseEval(eval)
    }

    fun saltsForCreation(creationOptions: CreationOptions): Salts? {
        val eval = creationOptions.extensions?.prf?.eval ?: return null
        return parseEval(eval)
    }

    private fun parseEval(eval: PrfEval): Salts? {
        val first = eval.first?.takeIf { it.isNotEmpty() } ?: return null
        val second = eval.second?.takeIf { it.isNotEmpty() }
        return Salts(PrfSalt(B64.dec(first)), second?.let { PrfSalt(B64.dec(it)) })
    }

    private fun saltedInput(salt: PrfSalt): ByteArray = sha256(SALT_PREFIX.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + salt.bytes)

    fun evaluate(
        mac: Mac,
        salts: Salts,
    ): Results =
        Results(
            first = PrfOutput(mac.doFinal(saltedInput(salts.first))),
            second = salts.second?.let { PrfOutput(mac.doFinal(saltedInput(it))) },
        )
}

object AlgorithmNegotiation {
    private fun requestedAlgorithms(creationOptions: CreationOptions): List<WebAuthnAlgorithm>? {
        if (creationOptions.pubKeyCredParams.isEmpty()) return null
        return creationOptions.pubKeyCredParams.mapNotNull { WebAuthnAlgorithm.fromCoseAlg(it.alg) }
    }

    private fun candidates(
        requested: List<WebAuthnAlgorithm>?,
        enabled: Set<AlgorithmFamily>,
        mlDsa: MlDsaSupport,
    ): List<WebAuthnAlgorithm> {
        val mlDsaOrder =
            when (mlDsa) {
                MlDsaSupport.OFF -> {
                    emptyList()
                }

                MlDsaSupport.STRONGEST_ONLY -> {
                    listOf(WebAuthnAlgorithm.ML_DSA_87)
                }

                MlDsaSupport.PREFER_STRONGEST -> {
                    AlgorithmFamily.ML_DSA.algorithms
                }

                MlDsaSupport.FIRST_OFFERED -> {
                    requested?.filter { it.family == AlgorithmFamily.ML_DSA } ?: AlgorithmFamily.ML_DSA.algorithms
                }
            }
        return WebAuthnAlgorithm.entries
            .filter { it.family != AlgorithmFamily.ML_DSA && it.family in enabled } + mlDsaOrder
    }

    fun allowedAlgorithms(
        enabled: Set<AlgorithmFamily>,
        mlDsa: MlDsaSupport,
    ): List<WebAuthnAlgorithm> = candidates(requested = null, enabled = enabled, mlDsa = mlDsa)

    private fun matchingAlgorithms(
        creationOptions: CreationOptions,
        enabled: Set<AlgorithmFamily>,
        mlDsa: MlDsaSupport,
    ): List<WebAuthnAlgorithm> {
        val requested = requestedAlgorithms(creationOptions)
        val candidates = candidates(requested, enabled, mlDsa)
        val matching = if (requested == null) candidates else candidates.filter { it in requested }
        // The ML-DSA setting already picked the parameter set, so a family is never offered twice.
        return matching.distinctBy { it.family }
    }

    fun offeredAlgorithms(
        creationOptions: CreationOptions,
        enabled: Set<AlgorithmFamily>,
        preferred: AlgorithmPreference,
        fallback: AlgorithmPreference,
        mlDsa: MlDsaSupport,
    ): List<WebAuthnAlgorithm> {
        val matching = matchingAlgorithms(creationOptions, enabled, mlDsa)
        return when (preferred) {
            AlgorithmPreference.AlwaysAsk -> {
                matching
            }

            AlgorithmPreference.FirstOffered -> {
                listOfNotNull(matching.firstOrNull())
            }

            is AlgorithmPreference.Prefer -> {
                firstOf(matching, preferred.family) ?: applyFallback(matching, fallback)
            }
        }
    }

    private fun firstOf(
        matching: List<WebAuthnAlgorithm>,
        family: AlgorithmFamily,
    ): List<WebAuthnAlgorithm>? =
        matching
            .firstOrNull {
                it.family ==
                    family
            }?.let { listOf(it) }

    // The fallback only decides between algorithms left over once the preferred one turned out to be unavailable.
    private fun applyFallback(
        matching: List<WebAuthnAlgorithm>,
        fallback: AlgorithmPreference,
    ): List<WebAuthnAlgorithm> {
        if (matching.size < 2) return matching
        return when (fallback) {
            AlgorithmPreference.AlwaysAsk -> {
                matching
            }

            AlgorithmPreference.FirstOffered -> {
                listOf(matching.first())
            }

            is AlgorithmPreference.Prefer -> {
                firstOf(matching, fallback.family) ?: listOf(matching.first())
            }
        }
    }
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
private data class AttestationResponse(
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
    val response: AttestationResponse,
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

object CredentialResponseJson {
    fun registrationResponseJson(
        credentialId: CredentialId,
        clientDataJSON: ClientDataJson,
        attestationObject: AttestationObject,
        authData: AuthenticatorData,
        spkiPublicKey: SpkiPublicKey,
        alg: Int,
        credPropsRequested: Boolean = false,
        prf: RegistrationPrf,
    ): String {
        val id = credentialId.b64
        val response =
            AttestationResponse(
                clientDataJSON = B64.enc(clientDataJSON.bytes),
                attestationObject = B64.enc(attestationObject.bytes),
                authenticatorData = B64.enc(authData.bytes),
                transports = listOf(INTERNAL_TRANSPORT),
                publicKeyAlgorithm = alg,
                publicKey = B64.enc(spkiPublicKey.bytes),
            )
        val clientExtensionResults =
            RegistrationExtensionResults(
                credProps = if (credPropsRequested) CredPropsResult(rk = true) else null,
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
                response = response,
                clientExtensionResults = clientExtensionResults,
            ),
        )
    }

    fun assertionResponseJson(
        credentialId: CredentialId,
        clientDataJSON: ClientDataJson,
        authData: AuthenticatorData,
        derSignature: DerSignature,
        userHandle: UserHandle,
        prfResults: PrfExtension.Results? = null,
    ): String {
        val id = credentialId.b64
        val response =
            AssertionResponsePayload(
                clientDataJSON = B64.enc(clientDataJSON.bytes),
                authenticatorData = B64.enc(authData.bytes),
                signature = B64.enc(derSignature.bytes),
                userHandle = userHandle.b64,
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
                response = response,
                clientExtensionResults = clientExtensionResults,
            ),
        )
    }
}
