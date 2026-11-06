package app.keyholm.ui.common

import androidx.biometric.AuthenticationRequest
import androidx.biometric.BiometricPrompt
import app.keyholm.ctap.PrfResults
import app.keyholm.ctap.PrfSalts
import app.keyholm.ctap.RelyingParty
import app.keyholm.ctap.RpId
import app.keyholm.keystore.AuthenticatorPolicy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val KEY = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
private val FIRST = ByteArray(32) { 1 }
private val SECOND = ByteArray(32) { 2 }

private fun mac(): Mac = Mac.getInstance("HmacSHA256").apply { init(KEY) }

private class RecordingAuthenticator(
    private val declineFrom: Int = Int.MAX_VALUE,
) : CryptoAuthenticator {
    val macs = mutableListOf<Mac>()

    override suspend fun authenticate(
        title: String,
        cryptoObject: BiometricPrompt.CryptoObject,
        allowedAuthenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): PromptResult {
        macs += checkNotNull(cryptoObject.mac) { "PrfPrompt always prompts with a Mac" }
        return if (macs.size >= declineFrom) PromptResult.Canceled else PromptResult.Success(cryptoObject)
    }
}

@RunWith(RobolectricTestRunner::class)
class PrfPromptTest {
    private val content = promptContent("", null, RelyingParty(RpId("example.org"), ""), false, "")

    private fun evaluate(
        authenticator: RecordingAuthenticator,
        salts: PrfSalts,
        provided: MutableList<Mac>,
    ): PrfResults? =
        runBlocking {
            val outcome =
                PrfPrompt(authenticator, Dispatchers.Unconfined).evaluate(
                    macFor = { Outcome.Success(mac().also { provided += it }) },
                    authenticatorsFor = { Outcome.Success(AuthenticatorPolicy.Biometric) },
                    salts = salts,
                    content = { content },
                )
            (outcome as Outcome.Success).value
        }

    @Test
    fun `two salts are each authorised on their own Mac`() {
        val authenticator = RecordingAuthenticator()
        val provided = mutableListOf<Mac>()

        val results = evaluate(authenticator, PrfSalts(FIRST, SECOND), provided)

        assertThat(authenticator.macs).hasSize(2)
        assertThat(authenticator.macs[0]).isNotSameInstanceAs(authenticator.macs[1])
        assertThat(authenticator.macs).containsExactlyElementsIn(provided).inOrder()
        assertThat(results?.first?.bytes).isEqualTo(mac().doFinal(FIRST))
        assertThat(results?.second?.bytes).isEqualTo(mac().doFinal(SECOND))
    }

    @Test
    fun `one salt prompts once`() {
        val authenticator = RecordingAuthenticator()
        val provided = mutableListOf<Mac>()

        val results = evaluate(authenticator, PrfSalts(FIRST, null), provided)

        assertThat(authenticator.macs).hasSize(1)
        assertThat(provided).hasSize(1)
        assertThat(results?.first?.bytes).isEqualTo(mac().doFinal(FIRST))
        assertThat(results?.second).isNull()
    }

    @Test
    fun `declining the second prompt returns no results`() {
        val authenticator = RecordingAuthenticator(declineFrom = 2)

        val results = evaluate(authenticator, PrfSalts(FIRST, SECOND), mutableListOf())

        assertThat(authenticator.macs).hasSize(2)
        assertThat(results).isNull()
    }
}
