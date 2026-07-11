package app.keyholm.store

import app.keyholm.store.proto.DeniedNativeAppProto
import app.keyholm.store.proto.DeniedNativeAppsProto
import app.keyholm.webauthn.AssetLinkStatement
import app.keyholm.webauthn.CertFingerprint
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RpId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DeniedNativeAppMappingTest {
    @Test
    fun `round-trips through proto`() {
        val domain =
            DeniedNativeApps(
                denied =
                    mapOf(
                        DeniedNativeAppKey(
                            RpId("example.com"),
                            PackageName("com.example.app"),
                            CertFingerprint.of(setOf("AA:BB", "CC:DD")),
                        ) to DeniedNativeAppInfo(Instant.ofEpochMilli(1_700), 3),
                    ),
                accepted =
                    mapOf(
                        RpId("site.test") to
                            listOf(AssetLinkStatement(PackageName("com.a"), CertFingerprint.of(setOf("11:22", "33:44")))),
                    ),
            )

        assertThat(domain.toProto().toDomain()).isEqualTo(domain)
    }

    @Test
    fun `toDomain uppercases stored fingerprints`() {
        val proto =
            DeniedNativeAppsProto
                .newBuilder()
                .addDenied(
                    DeniedNativeAppProto
                        .newBuilder()
                        .setRpId("example.com")
                        .setPackageName("com.x")
                        .addSha256CertFingerprintsHex("aa:bb")
                        .addSha256CertFingerprintsHex("cc:dd")
                        .setLastDeniedAt(Instant.ofEpochMilli(1).toTimestamp())
                        .setDenialCount(1)
                        .build(),
                ).build()

        assertThat(
            proto
                .toDomain()
                .denied.keys
                .single()
                .certFingerprints,
        ).containsExactly(CertFingerprint.of("AA:BB"), CertFingerprint.of("CC:DD"))
    }
}
