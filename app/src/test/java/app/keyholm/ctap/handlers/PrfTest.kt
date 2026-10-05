package app.keyholm.ctap.handlers

import app.keyholm.ctap.PrfOutput
import app.keyholm.ctap.PrfResults
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PrfTest {
    @Test
    fun `creation output reports enabled and the results`() {
        val output =
            prfOutput(
                enabled = true,
                results =
                    PrfResults(
                        PrfOutput(
                            ByteArray(32) {
                                1
                            },
                        ),
                        PrfOutput(ByteArray(32) { 2 }),
                    ),
            )["prf"]

        assertThat(output["enabled"].AsBoolean()).isTrue()
        assertThat(output["results"]["first"].GetByteString()).isEqualTo(ByteArray(32) { 1 })
        assertThat(output["results"]["second"].GetByteString()).isEqualTo(ByteArray(32) { 2 })
    }

    @Test
    fun `assertion output carries only the results`() {
        val output = prfOutput(enabled = false, results = PrfResults(PrfOutput(ByteArray(32) { 1 }), null))["prf"]

        assertThat(output.ContainsKey("enabled")).isFalse()
        assertThat(output["results"].ContainsKey("second")).isFalse()
    }
}
