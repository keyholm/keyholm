package app.keyholm.webauthn

import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.text.Normalizer

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class PrfEval(
    val first: String? = null,
    val second: String? = null,
)

@Serializable
data class PrfInputs(
    val eval: PrfEval? = null,
    val evalByCredential: Map<String, PrfEval>? = null,
)

@Serializable
data class RequestExtensions(
    val credProps: Boolean? = null,
    val prf: PrfInputs? = null,
)

@Serializable
data class RpEntity(
    val id: String,
    val name: String,
)

@Serializable
data class UserEntity(
    val id: String,
    val name: String,
    val displayName: String,
)

@Serializable
data class CredentialParameters(
    val type: String = "public-key",
    val alg: Int,
)

@Serializable
data class CredentialDescriptor(
    val type: String = "public-key",
    val id: String,
)

@Serializable
data class AuthenticatorSelection(
    val authenticatorAttachment: String? = null,
)

@Serializable
data class CreationOptions(
    val rp: RpEntity,
    val user: UserEntity,
    val challenge: String,
    val pubKeyCredParams: List<CredentialParameters> = emptyList(),
    val excludeCredentials: List<CredentialDescriptor> = emptyList(),
    val authenticatorSelection: AuthenticatorSelection? = null,
    val attestation: String = "none",
    val extensions: RequestExtensions? = null,
)

@Serializable
data class RequestOptions(
    val rpId: String,
    val challenge: String,
    val allowCredentials: List<CredentialDescriptor> = emptyList(),
    val extensions: RequestExtensions? = null,
)

// WebAuthn 5.4.7
fun attestationRequested(attestationOption: String): Boolean = attestationOption == "direct"

class InvalidOptionsException(
    val detail: String,
) : SerializationException(detail)

private const val BIDI_OVERRIDES = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"
private val SPACE_RUN = Regex(" {2,}")

private fun Char.breaksLayout() = category == CharCategory.SPACE_SEPARATOR || category == CharCategory.CONTROL || this in BIDI_OVERRIDES

// RFC 8266 2.3, which WebAuthn 5.4.1 says to enforce
private fun nickname(value: String): String {
    val spaced = value.map { if (it.breaksLayout()) ' ' else it }.joinToString("")
    return Normalizer.normalize(spaced.trim(' ').replace(SPACE_RUN, " "), Normalizer.Form.NFKC)
}

private fun requiredNickname(
    field: String,
    value: String,
): String =
    nickname(value).ifEmpty {
        throw InvalidOptionsException("$field must not be empty")
    }

fun parseCreationOptions(text: String): CreationOptions {
    val options = json.decodeFromString<CreationOptions>(text)
    return options.copy(
        rp = options.rp.copy(name = requiredNickname("rp.name", options.rp.name)),
        user =
            options.user.copy(
                name = requiredNickname("user.name", options.user.name),
                displayName = nickname(options.user.displayName),
            ),
    )
}

fun parseRequestOptions(text: String): RequestOptions = json.decodeFromString(text)

internal fun parseRequestOptionsOrLog(
    requestJson: String,
    log: Logger,
): RequestOptions? =
    try {
        parseRequestOptions(requestJson)
    } catch (e: SerializationException) {
        log.e(e) { "couldn't parse the request JSON" }
        null
    }
