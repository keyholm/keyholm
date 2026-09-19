package app.keyholm.webauthn

sealed interface NativeAppTrust {
    data object DenyAll : NativeAppTrust

    data object AllowAll : NativeAppTrust

    data class Community(
        val assetLinksByDomain: Map<RpId, List<AssetLinkStatement>>,
    ) : NativeAppTrust
}
