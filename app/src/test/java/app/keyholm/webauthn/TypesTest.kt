package app.keyholm.webauthn

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TypesTest {
    @Test
    fun `cert fingerprints compare and hash case-insensitively`() {
        assertThat(CertFingerprint.of("aa:bb")).isEqualTo(CertFingerprint.of("AA:BB"))
        assertThat(setOf(CertFingerprint.of("aa:bb"))).contains(CertFingerprint.of("Aa:Bb"))
        assertThat(CertFingerprint.of(setOf("aa:bb", "AA:BB"))).hasSize(1)
    }

    @Test
    fun `cert fingerprints normalise to uppercase`() {
        assertThat(CertFingerprint.of("aa:bb").colonHex).isEqualTo("AA:BB")
    }
}
