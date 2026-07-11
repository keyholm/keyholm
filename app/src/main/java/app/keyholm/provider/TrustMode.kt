package app.keyholm.provider

import android.content.Context
import app.keyholm.webauthn.CommunityAssetLinks
import app.keyholm.webauthn.NativeAppTrust

internal enum class TrustMode {
    DenyAll,
    AllowAll,
    Community,
}

internal fun NativeAppTrust.mode(): TrustMode =
    when (this) {
        NativeAppTrust.DenyAll -> TrustMode.DenyAll
        NativeAppTrust.AllowAll -> TrustMode.AllowAll
        is NativeAppTrust.Community -> TrustMode.Community
    }

internal fun TrustMode.toTrust(context: Context): NativeAppTrust =
    when (this) {
        TrustMode.DenyAll -> NativeAppTrust.DenyAll
        TrustMode.AllowAll -> NativeAppTrust.AllowAll
        TrustMode.Community -> NativeAppTrust.Community(CommunityAssetLinks.load(context))
    }
