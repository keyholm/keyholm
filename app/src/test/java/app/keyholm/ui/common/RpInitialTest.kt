package app.keyholm.ui.common

import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.RpId
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RpInitialTest {
    private fun initial(
        rpId: String,
        rpName: String = "",
        preferRpName: Boolean = false,
    ) = rpInitial(RelyingParty(RpId(rpId), rpName), preferRpName)

    @Test
    fun `the id's first label supplies the letter`() {
        assertThat(initial("example.com")).isEqualTo('E')
    }

    @Test
    fun `a generic subdomain is skipped`() {
        assertThat(initial("accounts.google.com")).isEqualTo('G')
        assertThat(initial("www.login.github.com")).isEqualTo('G')
    }

    @Test
    fun `an all-generic host still yields a letter`() {
        assertThat(initial("login.sso")).isEqualTo('L')
    }

    @Test
    fun `preferring the rp name uses its first word`() {
        assertThat(initial("auth.acme.test", rpName = "Wile Coyote Ltd", preferRpName = true)).isEqualTo('W')
    }

    @Test
    fun `preferring the rp name falls back to the id when the name says nothing`() {
        assertThat(initial("auth.acme.test", rpName = "keycloak", preferRpName = true)).isEqualTo('A')
        assertThat(initial("auth.acme.test", rpName = "", preferRpName = true)).isEqualTo('A')
    }

    @Test
    fun `preferring the rp name falls back to the id when its first word has no letter`() {
        assertThat(initial("auth.acme.test", rpName = "🔑 Wile Coyote", preferRpName = true)).isEqualTo('A')
    }

    @Test
    fun `the rp name is ignored unless preferred`() {
        assertThat(initial("auth.acme.test", rpName = "Wile Coyote Ltd")).isEqualTo('A')
    }

    @Test
    fun `leading punctuation is skipped`() {
        assertThat(initial("acme.test", rpName = "\"Acme\"", preferRpName = true)).isEqualTo('A')
    }
}
