package app.keyholm.webauthn

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WebAuthnOptionsTest {
    @Test
    fun `parseCreationOptions rejects truncated json`() {
        assertThrows<SerializationException> { parseCreationOptions("""{"rp":{"id":"example.com"}""") }
    }

    @Test
    fun `parseCreationOptions rejects a non-object top level`() {
        assertThrows<SerializationException> { parseCreationOptions("[]") }
    }

    @Test
    fun `parseCreationOptions rejects a missing rp`() {
        assertThrows<SerializationException> { parseCreationOptions("""{"user":{"id":"AA"},"challenge":"AA"}""") }
    }

    @Test
    fun `parseCreationOptions rejects the wrong type for challenge`() {
        assertThrows<SerializationException> {
            parseCreationOptions("""{"rp":{"id":"e","name":"Example"},"user":{"id":"AA"},"challenge":42}""")
        }
    }

    @Test
    fun `parseCreationOptions rejects a missing rp name`() {
        assertThrows<SerializationException> {
            parseCreationOptions(
                """{"rp":{"id":"e"},"user":{"id":"AA","name":"alexm","displayName":""},"challenge":"AA"}""",
            )
        }
    }

    @Test
    fun `parseCreationOptions rejects a missing user name`() {
        assertThrows<SerializationException> {
            parseCreationOptions("""{"rp":{"id":"e","name":"Example"},"user":{"id":"AA","displayName":"Alex M"},"challenge":"AA"}""")
        }
    }

    @Test
    fun `parseCreationOptions rejects a blank user name`() {
        assertThrows<SerializationException> {
            parseCreationOptions(
                """{"rp":{"id":"e","name":"Example"},"user":{"id":"AA","name":" ","displayName":"Alex M"},"challenge":"AA"}""",
            )
        }
    }

    @Test
    fun `parseCreationOptions keeps an empty display name`() {
        val options =
            parseCreationOptions(
                """{"rp":{"id":"e","name":"Example"},"user":{"id":"AA","name":"alexm","displayName":""},"challenge":"AA"}""",
            )

        assertThat(options.user.displayName).isEmpty()
    }

    @Test
    fun `parseCreationOptions ignores unknown keys`() {
        val options =
            parseCreationOptions(
                """{"rp":{"id":"example.com","name":"Example"},"user":{"id":"AA","name":"alexm","displayName":""},""" +
                    """"challenge":"AA","future":true}""",
            )

        assertThat(options.rp.id).isEqualTo("example.com")
    }

    @Test
    fun `parseRequestOptions rejects truncated json`() {
        assertThrows<SerializationException> { parseRequestOptions("{") }
    }

    @Test
    fun `parseRequestOptions rejects a missing rpId`() {
        assertThrows<SerializationException> { parseRequestOptions("""{"challenge":"AA"}""") }
    }

    @Test
    fun `parseRequestOptions rejects a non-array allowCredentials`() {
        assertThrows<SerializationException> {
            parseRequestOptions("""{"rpId":"e","challenge":"AA","allowCredentials":"nope"}""")
        }
    }

    @Test
    fun `attestationRequested is true only for direct`() {
        assertThat(attestationRequested("direct")).isTrue()
        assertThat(attestationRequested("none")).isFalse()
        assertThat(attestationRequested("indirect")).isFalse()
        assertThat(attestationRequested("enterprise")).isFalse()
    }

    @Test
    fun `attestationRequested treats an unknown value as none`() {
        assertThat(attestationRequested("")).isFalse()
        assertThat(attestationRequested("Direct")).isFalse()
        assertThat(attestationRequested("some-future-value")).isFalse()
    }

    @Test
    fun `parseCreationOptions defaults attestation to none`() {
        val options =
            parseCreationOptions(
                """{"rp":{"id":"e","name":"Example"},"user":{"id":"AA","name":"alexm","displayName":""},"challenge":"AA"}""",
            )

        assertThat(options.attestation).isEqualTo("none")
        assertThat(attestationRequested(options.attestation)).isFalse()
    }

    @Test
    fun `parseCreationOptions keeps an unrecognised attestation value instead of rejecting it`() {
        val options =
            parseCreationOptions(
                """{"rp":{"id":"e","name":"Example"},"user":{"id":"AA","name":"alexm","displayName":""},""" +
                    """"challenge":"AA","attestation":"some-future-value"}""",
            )

        assertThat(options.attestation).isEqualTo("some-future-value")
        assertThat(attestationRequested(options.attestation)).isFalse()
    }

    private fun creationOptions(
        rpName: String = "Example",
        userName: String = "alexm",
        displayName: String = "",
    ) = parseCreationOptions(
        """{"rp":{"id":"example.com","name":"$rpName"},""" +
            """"user":{"id":"AA","name":"$userName","displayName":"$displayName"},"challenge":"AA"}""",
    )

    @Test
    fun `parseCreationOptions folds control characters in display strings to a space`() {
        val options = creationOptions(userName = "alex\\nGoogle wants access", displayName = "a\\tb")

        assertThat(options.user.name).isEqualTo("alex Google wants access")
        assertThat(options.user.displayName).isEqualTo("a b")
    }

    @Test
    fun `parseCreationOptions folds bidirectional overrides to a space`() {
        val options = creationOptions(rpName = "alex\u202Emoc.live")

        assertThat(options.rp.name).isEqualTo("alex moc.live")
    }

    @Test
    fun `parseCreationOptions maps non-ascii spaces, trims and collapses runs`() {
        val options = creationOptions(userName = "  Alex\u00A0\u3000M   ", displayName = "St    Peter")

        assertThat(options.user.name).isEqualTo("Alex M")
        assertThat(options.user.displayName).isEqualTo("St Peter")
    }

    @Test
    fun `parseCreationOptions applies NFKC`() {
        val options = creationOptions(userName = "\uFF21\uFF4C\uFF45\uFF58")

        assertThat(options.user.name).isEqualTo("Alex")
    }

    @Test
    fun `parseCreationOptions keeps a zero-width joiner so emoji sequences survive`() {
        val options = creationOptions(displayName = "fam \uD83D\uDC69\u200D\uD83D\uDCBB")

        assertThat(options.user.displayName).isEqualTo("fam \uD83D\uDC69\u200D\uD83D\uDCBB")
    }

    @Test
    fun `parseCreationOptions rejects a name that normalises to empty`() {
        assertThrows<SerializationException> { creationOptions(userName = "\u00A0 \u3000") }
    }
}
