package app.keyholm.webauthn

import app.keyholm.ui.common.ErrorMessages
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

private val RP_ID = RpId("example.com")
private val PACKAGE_NAME = PackageName("app.example")
private val FINGERPRINTS = CertFingerprint.of(setOf("AA:BB"))

private fun Caller.decide(
    trust: NativeAppTrust,
    grant: GrantSource = GrantSource.None,
    fingerprints: Set<CertFingerprint> = FINGERPRINTS,
) = applyNativeAppTrust(trust, DeniedNativeAppRef(RP_ID, PACKAGE_NAME, fingerprints), grant)

class CallerTrustTest {
    private val nativeApp = Caller.Normal("android:apk-key-hash:abc")

    @Test
    fun `web caller is allowed regardless of native app trust setting`() {
        val caller = Caller.Privileged.Origin("https://example.com")

        for (trust in listOf(NativeAppTrust.DenyAll, NativeAppTrust.AllowAll, NativeAppTrust.Community(emptyMap()))) {
            assertThat(caller.decide(trust)).isEqualTo(TrustDecision.Allowed(caller))
        }
    }

    @Test
    fun `untrusted caller is denied with the unverified message regardless of trust or grants`() {
        for (trust in listOf(NativeAppTrust.DenyAll, NativeAppTrust.AllowAll, NativeAppTrust.Community(emptyMap()))) {
            assertThat(
                Caller.Untrusted.decide(trust, grant = GrantSource.User),
            ).isEqualTo(TrustDecision.Denied.Other(ErrorMessages.CALLER_UNVERIFIED))
        }
    }

    @Test
    fun `native app caller is allowed when trust is allow all`() {
        assertThat(nativeApp.decide(NativeAppTrust.AllowAll)).isEqualTo(TrustDecision.Allowed(nativeApp))
    }

    @Test
    fun `native app caller is denied when trust is deny all`() {
        assertThat(nativeApp.decide(NativeAppTrust.DenyAll)).isEqualTo(
            TrustDecision.Denied.NativeApp(
                ErrorMessages.NATIVE_APP_TRUST_DENIED,
                DeniedNativeAppRef(RP_ID, PACKAGE_NAME, FINGERPRINTS),
            ),
        )
    }

    @Test
    fun `native app caller is allowed when the community grant is present`() {
        assertThat(nativeApp.decide(NativeAppTrust.Community(emptyMap()), grant = GrantSource.Community))
            .isEqualTo(TrustDecision.Allowed(nativeApp))
    }

    @Test
    fun `native app caller is denied when trust is community and the grant is absent`() {
        assertThat(nativeApp.decide(NativeAppTrust.Community(emptyMap()))).isEqualTo(
            TrustDecision.Denied.NativeApp(
                ErrorMessages.CALLER_UNVERIFIED,
                DeniedNativeAppRef(RP_ID, PACKAGE_NAME, FINGERPRINTS),
            ),
        )
    }

    @Test
    fun `user-accepted grant allows the caller even when trust is deny all`() {
        assertThat(nativeApp.decide(NativeAppTrust.DenyAll, grant = GrantSource.User))
            .isEqualTo(TrustDecision.Allowed(nativeApp))
    }

    @Test
    fun `user-accepted grant allows the caller when the community grant is absent`() {
        assertThat(nativeApp.decide(NativeAppTrust.Community(emptyMap()), grant = GrantSource.User))
            .isEqualTo(TrustDecision.Allowed(nativeApp))
    }

    @Test
    fun `a community grant does not survive deny all`() {
        assertThat(nativeApp.decide(NativeAppTrust.DenyAll, grant = GrantSource.Community)).isEqualTo(
            TrustDecision.Denied.NativeApp(
                ErrorMessages.NATIVE_APP_TRUST_DENIED,
                DeniedNativeAppRef(RP_ID, PACKAGE_NAME, FINGERPRINTS),
            ),
        )
    }

    @Test
    fun `the denied ref carries every signer fingerprint`() {
        val multi = CertFingerprint.of(setOf("AA:BB", "CC:DD"))

        val decision = nativeApp.decide(NativeAppTrust.DenyAll, fingerprints = multi)

        assertThat((decision as TrustDecision.Denied.NativeApp).ref)
            .isEqualTo(DeniedNativeAppRef(RP_ID, PACKAGE_NAME, multi))
    }
}
