package app.keyholm.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.webauthn.IdentityPreference
import app.keyholm.webauthn.NativeAppTrust
import app.keyholm.webauthn.TrustMode
import app.keyholm.webauthn.WebAuthnAlgorithm
import app.keyholm.webauthn.mode
import app.keyholm.webauthn.toTrust
import kotlinx.coroutines.CoroutineDispatcher

private const val SCHEME = "keyholm"
private const val CREATE = "create"
private const val PARAM_ALGORITHM = "algorithm"
private const val PARAM_ATTESTATION = "attestation"
private const val PARAM_IDENTITY = "identity"
private const val PARAM_AUTHENTICATORS = "authenticators"
private const val PARAM_INVALIDATE_ON_ENROLLMENT = "invalidate_on_biometric_enrollment"
private const val PARAM_NATIVE_APP_TRUST = "native_app_trust"
private const val TRUE = "true"
private const val FALSE = "false"

internal enum class AttestationOffer {
    NotRequested,
    Ask,
}

internal data class CreationOffer(
    val algorithms: List<WebAuthnAlgorithm>,
    val attestation: AttestationOffer,
    val identity: IdentityPreference,
    val authenticators: AuthenticatorPolicy,
    val invalidateOnBiometricEnrollment: Boolean,
    val nativeAppTrust: NativeAppTrust,
) {
    fun toUri(): Uri {
        val builder = Uri.Builder().scheme(SCHEME).authority(CREATE)
        algorithms.forEach { builder.appendQueryParameter(PARAM_ALGORITHM, it.name) }
        return builder
            .appendQueryParameter(PARAM_ATTESTATION, attestation.name)
            .appendQueryParameter(PARAM_IDENTITY, identity.name)
            .appendQueryParameter(PARAM_AUTHENTICATORS, authenticators.name)
            .appendQueryParameter(PARAM_INVALIDATE_ON_ENROLLMENT, if (invalidateOnBiometricEnrollment) TRUE else FALSE)
            .appendQueryParameter(PARAM_NATIVE_APP_TRUST, nativeAppTrust.mode().name)
            .build()
    }
}

internal suspend fun Intent.creationOffer(
    context: Context,
    dispatcher: CoroutineDispatcher,
): CreationOffer {
    val uri = checkNotNull(data) { "the create intent carries no offer URI" }
    val names = uri.getQueryParameters(PARAM_ALGORITHM)
    check(names.isNotEmpty()) { "$PARAM_ALGORITHM is missing" }
    return CreationOffer(
        algorithms = names.map { name -> named(PARAM_ALGORITHM, name, WebAuthnAlgorithm.entries) },
        attestation = uri.required(PARAM_ATTESTATION, AttestationOffer.entries),
        identity = uri.required(PARAM_IDENTITY, IdentityPreference.entries),
        authenticators = uri.required(PARAM_AUTHENTICATORS, AuthenticatorPolicy.entries),
        invalidateOnBiometricEnrollment = uri.requiredBoolean(PARAM_INVALIDATE_ON_ENROLLMENT),
        nativeAppTrust = uri.required(PARAM_NATIVE_APP_TRUST, TrustMode.entries).toTrust(context, dispatcher),
    )
}

private fun Uri.requiredBoolean(key: String): Boolean =
    when (val value = getQueryParameter(key) ?: error("$key is missing")) {
        TRUE -> true
        FALSE -> false
        else -> error("$key holds an unknown value $value")
    }

private fun <T : Enum<T>> Uri.required(
    key: String,
    entries: List<T>,
): T = named(key, getQueryParameter(key) ?: error("$key is missing"), entries)

internal fun <T : Enum<T>> named(
    key: String,
    name: String,
    entries: List<T>,
): T = entries.firstOrNull { it.name == name } ?: error("$key holds an unknown value $name")
