package app.keyholm.settings

import android.content.Context
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.webauthn.CommunityAssetLinks
import app.keyholm.webauthn.IdentityPreference
import app.keyholm.webauthn.NativeAppTrust
import app.keyholm.webauthn.TrustMode
import kotlinx.coroutines.CoroutineDispatcher

private const val TRUST_DENY_ALL = "DENY_ALL"
private const val TRUST_ALLOW_ALL = "ALLOW_ALL"
private const val TRUST_COMMUNITY = "COMMUNITY"
private const val IDENTITY_ENABLED = "ENABLED"
private const val IDENTITY_DISABLED = "DISABLED"
private const val IDENTITY_ALWAYS_ASK = "ALWAYS_ASK"
private const val AUTHENTICATORS_BIOMETRIC = "BIOMETRIC"
private const val AUTHENTICATORS_DEVICE_CREDENTIAL = "DEVICE_CREDENTIAL"
private const val AUTHENTICATORS_EITHER = "EITHER"

internal fun encodeAuthenticatorPolicy(policy: AuthenticatorPolicy): String =
    when (policy) {
        AuthenticatorPolicy.Biometric -> AUTHENTICATORS_BIOMETRIC
        AuthenticatorPolicy.DeviceCredential -> AUTHENTICATORS_DEVICE_CREDENTIAL
        AuthenticatorPolicy.Either -> AUTHENTICATORS_EITHER
    }

internal fun decodeAuthenticatorPolicy(stored: String?): AuthenticatorPolicy =
    when (stored) {
        null -> AuthenticatorPolicy.Either
        AUTHENTICATORS_BIOMETRIC -> AuthenticatorPolicy.Biometric
        AUTHENTICATORS_DEVICE_CREDENTIAL -> AuthenticatorPolicy.DeviceCredential
        AUTHENTICATORS_EITHER -> AuthenticatorPolicy.Either
        else -> error("unknown create-authenticators token $stored")
    }

internal fun encodeNativeAppTrust(mode: TrustMode): String =
    when (mode) {
        TrustMode.DenyAll -> TRUST_DENY_ALL
        TrustMode.AllowAll -> TRUST_ALLOW_ALL
        TrustMode.Community -> TRUST_COMMUNITY
    }

internal suspend fun decodeNativeAppTrust(
    stored: String?,
    context: Context,
    dispatcher: CoroutineDispatcher,
): NativeAppTrust =
    when (stored) {
        null, TRUST_COMMUNITY -> NativeAppTrust.Community(CommunityAssetLinks.load(context, dispatcher))
        TRUST_DENY_ALL -> NativeAppTrust.DenyAll
        TRUST_ALLOW_ALL -> NativeAppTrust.AllowAll
        else -> error("unknown native-app-trust token $stored")
    }

internal fun encodeIdentityPreference(preference: IdentityPreference): String =
    when (preference) {
        IdentityPreference.ENABLED -> IDENTITY_ENABLED
        IdentityPreference.DISABLED -> IDENTITY_DISABLED
        IdentityPreference.ALWAYS_ASK -> IDENTITY_ALWAYS_ASK
    }

internal fun decodeIdentityPreference(stored: String?): IdentityPreference =
    when (stored) {
        null, IDENTITY_ENABLED -> IdentityPreference.ENABLED
        IDENTITY_DISABLED -> IdentityPreference.DISABLED
        IDENTITY_ALWAYS_ASK -> IdentityPreference.ALWAYS_ASK
        else -> error("unknown identity-preference token $stored")
    }
