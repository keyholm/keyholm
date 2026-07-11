package app.keyholm.store

import app.keyholm.webauthn.AssetLinkStatement
import app.keyholm.webauthn.CertFingerprint
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RpId
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

private fun key(
    rpId: String = "example.com",
    packageName: String = "com.example.app",
    fingerprints: Set<String> = setOf("AA:BB"),
) = DeniedNativeAppKey(RpId(rpId), PackageName(packageName), CertFingerprint.of(fingerprints))

private fun statement(
    packageName: String,
    fingerprints: Set<String>,
) = AssetLinkStatement(PackageName(packageName), CertFingerprint.of(fingerprints))

private fun List<AssetLinkStatement>.grants(
    packageName: String,
    fingerprints: Set<String>,
) = grants(PackageName(packageName), CertFingerprint.of(fingerprints))

class DeniedNativeAppsTest {
    @Test
    fun `recordDenial adds a new entry with count 1`() {
        val result = DeniedNativeApps().recordDenial(key(), at = Instant.ofEpochMilli(1_000))

        assertThat(result.denied).containsExactly(key(), DeniedNativeAppInfo(Instant.ofEpochMilli(1_000), 1))
    }

    @Test
    fun `recordDenial bumps the count and timestamp for the same key`() {
        val result =
            DeniedNativeApps()
                .recordDenial(key(), at = Instant.ofEpochMilli(1_000))
                .recordDenial(key(), at = Instant.ofEpochMilli(2_000))

        assertThat(result.denied).containsExactly(key(), DeniedNativeAppInfo(Instant.ofEpochMilli(2_000), 2))
    }

    @Test
    fun `recordDenial keeps only the 50 most recent entries`() {
        val result =
            (1..60).fold(DeniedNativeApps()) { acc, i ->
                acc.recordDenial(key(packageName = "app$i"), at = Instant.ofEpochMilli(i.toLong()))
            }

        assertThat(result.denied).hasSize(50)
        assertThat(result.denied.keys.map { it.packageName.value }).doesNotContain("app1")
        assertThat(result.denied.keys.map { it.packageName.value }).contains("app60")
    }

    @Test
    fun `recordDenial is a no-op when the app is already accepted`() {
        val accepted = DeniedNativeApps().approve(key())

        assertThat(accepted.recordDenial(key(), at = Instant.ofEpochMilli(9_000))).isEqualTo(accepted)
    }

    @Test
    fun `recordDenial keeps a multi-signer denial with all its fingerprints`() {
        val multi = key(fingerprints = setOf("AA:BB", "CC:DD"))

        val result = DeniedNativeApps().recordDenial(multi, at = Instant.ofEpochMilli(1_000))

        assertThat(
            result.denied.keys
                .single()
                .certFingerprints
                .map { it.colonHex },
        ).containsExactly("AA:BB", "CC:DD")
    }

    @Test
    fun `approve moves an entry from denied to accepted`() {
        val result = DeniedNativeApps().recordDenial(key(), at = Instant.ofEpochMilli(1_000)).approve(key())

        assertThat(result.denied).isEmpty()
        assertThat(result.accepted).containsExactly(
            RpId("example.com"),
            listOf(statement("com.example.app", setOf("AA:BB"))),
        )
    }

    @Test
    fun `approve keeps every fingerprint of a multi-signer app`() {
        val result = DeniedNativeApps().approve(key(fingerprints = setOf("AA:BB", "CC:DD")))

        assertThat(result.accepted.getValue(RpId("example.com"))).containsExactly(
            statement("com.example.app", setOf("AA:BB", "CC:DD")),
        )
    }

    @Test
    fun `approve merges a second fingerprint into the existing package statement`() {
        val result =
            DeniedNativeApps()
                .approve(key(fingerprints = setOf("AA:BB")))
                .approve(key(fingerprints = setOf("CC:DD")))

        assertThat(result.accepted.getValue(RpId("example.com"))).containsExactly(
            statement("com.example.app", setOf("AA:BB", "CC:DD")),
        )
    }

    @Test
    fun `dismiss drops a denied entry without accepting it`() {
        val result = DeniedNativeApps().recordDenial(key(), at = Instant.ofEpochMilli(1_000)).dismiss(key())

        assertThat(result.denied).isEmpty()
        assertThat(result.accepted).isEmpty()
    }

    @Test
    fun `approve is idempotent`() {
        val once = DeniedNativeApps().approve(key())

        assertThat(once.approve(key())).isEqualTo(once)
    }

    @Test
    fun `revoke moves an approved app back to denied`() {
        val result = DeniedNativeApps().approve(key()).revoke(key(), at = Instant.ofEpochMilli(5_000))

        assertThat(result.accepted).isEmpty()
        assertThat(result.denied).containsExactly(key(), DeniedNativeAppInfo(Instant.ofEpochMilli(5_000), 1))
    }

    @Test
    fun `revoke drops the whole exception's fingerprint set`() {
        val result =
            DeniedNativeApps()
                .approve(key(fingerprints = setOf("AA:BB")))
                .approve(key(fingerprints = setOf("CC:DD")))
                .revoke(key(fingerprints = setOf("AA:BB")), at = Instant.ofEpochMilli(1))

        assertThat(result.accepted.getValue(RpId("example.com"))).containsExactly(
            statement("com.example.app", setOf("CC:DD")),
        )
        assertThat(result.denied.keys).containsExactly(key(fingerprints = setOf("AA:BB")))
    }

    @Test
    fun `revoke is a no-op for an unknown app`() {
        val approved = DeniedNativeApps().approve(key())

        assertThat(approved.revoke(key(packageName = "com.other"), at = Instant.ofEpochMilli(1))).isEqualTo(approved)
    }

    @Test
    fun `exceptionKeys lists one key per approved statement`() {
        val apps =
            DeniedNativeApps()
                .approve(key(fingerprints = setOf("AA:BB")))
                .approve(key(fingerprints = setOf("CC:DD")))

        assertThat(apps.exceptionKeys()).containsExactly(
            key(fingerprints = setOf("AA:BB", "CC:DD")),
        )
    }

    @Test
    fun `grants is false for an empty statement list`() {
        assertThat(emptyList<AssetLinkStatement>().grants("app.example", setOf("AA:BB"))).isFalse()
    }

    @Test
    fun `grants is false for an empty fingerprint set`() {
        val statements = listOf(statement("app.example", setOf("AA:BB")))

        assertThat(statements.grants("app.example", emptySet())).isFalse()
    }

    @Test
    fun `grants is true when the statement covers every fingerprint`() {
        val statements = listOf(statement("app.example", setOf("AA:BB", "CC:DD")))

        assertThat(statements.grants("app.example", setOf("AA:BB", "CC:DD"))).isTrue()
        assertThat(statements.grants("app.example", setOf("aa:bb"))).isTrue()
    }

    @Test
    fun `grants is false when the statement misses a fingerprint`() {
        val statements = listOf(statement("app.example", setOf("AA:BB")))

        assertThat(statements.grants("app.example", setOf("AA:BB", "CC:DD"))).isFalse()
    }

    @Test
    fun `grants is false for a different package`() {
        val statements = listOf(statement("app.other", setOf("AA:BB")))

        assertThat(statements.grants("app.example", setOf("AA:BB"))).isFalse()
    }
}
