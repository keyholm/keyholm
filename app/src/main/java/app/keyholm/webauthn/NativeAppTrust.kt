package app.keyholm.webauthn

import app.keyholm.ctap.RpId

sealed interface NativeAppTrust {
    data object DenyAll : NativeAppTrust

    data object AllowAll : NativeAppTrust

    data class Community(
        val assetLinksByDomain: Map<RpId, List<AssetLinkStatement>>,
    ) : NativeAppTrust
}
