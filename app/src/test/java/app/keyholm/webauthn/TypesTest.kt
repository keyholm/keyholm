package app.keyholm.webauthn

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TypesTest {
    @Test
    fun `a credential id builds distinct signing and hmac aliases`() {
        val id = CredentialId("cred-1")

        assertThat(id.signingKeyAlias).isNotEqualTo(id.hmacKeyAlias)
        assertThat(id.signingKeyAlias).isEqualTo(KeyAlias("key_cred-1"))
        assertThat(id.hmacKeyAlias).isEqualTo(KeyAlias("hmac_cred-1"))
    }

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
