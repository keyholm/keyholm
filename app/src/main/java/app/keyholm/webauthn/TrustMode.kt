package app.keyholm.webauthn

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher

enum class TrustMode {
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

internal suspend fun TrustMode.toTrust(
    context: Context,
    dispatcher: CoroutineDispatcher,
): NativeAppTrust =
    when (this) {
        TrustMode.DenyAll -> NativeAppTrust.DenyAll
        TrustMode.AllowAll -> NativeAppTrust.AllowAll
        TrustMode.Community -> NativeAppTrust.Community(CommunityAssetLinks.load(context, dispatcher))
    }
