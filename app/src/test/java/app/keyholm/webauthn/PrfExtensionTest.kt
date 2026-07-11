package app.keyholm.webauthn

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrfExtensionTest {
    private fun creation(prf: PrfInputs?) =
        CreationOptions(
            rp = RpEntity("example.com", "Example"),
            user = UserEntity("AA", "alexm", "Alex M"),
            challenge = "AA",
            extensions = prf?.let { RequestExtensions(prf = it) },
        )

    private fun request(prf: PrfInputs?) =
        RequestOptions(
            rpId = "example.com",
            challenge = "AA",
            extensions = prf?.let { RequestExtensions(prf = it) },
        )

    @Test
    fun `saltsForCreation is null without a prf extension`() {
        assertThat(PrfExtension.saltsForCreation(creation(null))).isNull()
    }

    @Test
    fun `saltsForCreation decodes a valid base64url first salt`() {
        val salts = PrfExtension.saltsForCreation(creation(PrfInputs(eval = PrfEval(first = "AQID"))))

        assertThat(salts!!.first.bytes).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(salts.second).isNull()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `saltsForCreation throws on a malformed first salt`() {
        PrfExtension.saltsForCreation(creation(PrfInputs(eval = PrfEval(first = "A"))))
    }

    @Test
    fun `saltsForAssertion is null without a prf extension`() {
        assertThat(PrfExtension.saltsForAssertion(request(null), CredentialId("cred-1"))).isNull()
    }

    @Test
    fun `saltsForAssertion prefers evalByCredential over eval`() {
        val prf =
            PrfInputs(
                eval = PrfEval(first = "AAAA"),
                evalByCredential = mapOf("cred-1" to PrfEval(first = "AQID")),
            )

        assertThat(PrfExtension.saltsForAssertion(request(prf), CredentialId("cred-1"))!!.first.bytes)
            .isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `saltsForAssertion throws on a malformed salt`() {
        PrfExtension.saltsForAssertion(request(PrfInputs(eval = PrfEval(first = "A"))), CredentialId("cred-1"))
    }
}
