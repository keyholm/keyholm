package app.keyholm.ui.createpasskey

import app.keyholm.webauthn.RpId
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StoredRpNameTest {
    private val rpId = RpId("auth.acme.test")

    @Test
    fun `an informative name is kept`() {
        assertThat(storedRpName(rpId, "Acme")).isEqualTo("Acme")
    }

    @Test
    fun `a blank name is dropped`() {
        assertThat(storedRpName(rpId, "  ")).isEmpty()
    }

    @Test
    fun `a name equal to the id is dropped`() {
        assertThat(storedRpName(rpId, "auth.acme.test")).isEmpty()
    }

    @Test
    fun `an identity proxy default is dropped`() {
        assertThat(storedRpName(rpId, "keycloak")).isEmpty()
    }
}
