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
    fun `inputsForCreation is null without a prf extension`() {
        assertThat(PrfExtension.inputsForCreation(creation(null))).isNull()
    }

    @Test
    fun `inputsForCreation decodes a valid base64url first input`() {
        val inputs = PrfExtension.inputsForCreation(creation(PrfInputs(eval = PrfEval(first = "AQID"))))

        assertThat(inputs!!.first.bytes).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(inputs.second).isNull()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inputsForCreation throws on a malformed first input`() {
        PrfExtension.inputsForCreation(creation(PrfInputs(eval = PrfEval(first = "A"))))
    }

    @Test
    fun `inputsForAssertion is null without a prf extension`() {
        assertThat(PrfExtension.inputsForAssertion(request(null), CredentialId("cred-1"))).isNull()
    }

    @Test
    fun `inputsForAssertion prefers evalByCredential over eval`() {
        val prf =
            PrfInputs(
                eval = PrfEval(first = "AAAA"),
                evalByCredential = mapOf("cred-1" to PrfEval(first = "AQID")),
            )

        assertThat(PrfExtension.inputsForAssertion(request(prf), CredentialId("cred-1"))!!.first.bytes)
            .isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inputsForAssertion throws on a malformed input`() {
        PrfExtension.inputsForAssertion(request(PrfInputs(eval = PrfEval(first = "A"))), CredentialId("cred-1"))
    }
}
