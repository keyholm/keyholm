package app.keyholm.webauthn

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

private val ES256 = WebAuthnAlgorithm.ES256
private val ED25519 = WebAuthnAlgorithm.ED25519
private val ML_DSA_65 = WebAuthnAlgorithm.ML_DSA_65
private val ML_DSA_87 = WebAuthnAlgorithm.ML_DSA_87

private fun options(vararg requested: WebAuthnAlgorithm) =
    CreationOptions(
        rp = RpEntity("keyholm.app", "Keyholm"),
        user = UserEntity("AA", "alice", "Alice"),
        challenge = "AA",
        pubKeyCredParams = requested.map { CredentialParameters(alg = it.coseAlg) },
    )

class AlgorithmNegotiationTest {
    @Test
    fun `preferred algorithm wins and the fallback is ignored`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ED25519, ML_DSA_87),
                setOf(ES256.family, ED25519.family),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.Prefer(ML_DSA_87.family),
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ES256)
    }

    @Test
    fun `fallback picks its specific algorithm when the preferred one is disabled`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ED25519, ML_DSA_87),
                setOf(ED25519.family),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.Prefer(ML_DSA_87.family),
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ML_DSA_87)
    }

    @Test
    fun `fallback picks its specific algorithm when the relying party doesn't request the preferred one`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ED25519, ML_DSA_87),
                setOf(ES256.family, ED25519.family),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.Prefer(ML_DSA_87.family),
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ML_DSA_87)
    }

    @Test
    fun `first-offered fallback takes the first of the remaining algorithms`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ED25519, ML_DSA_87),
                setOf(ED25519.family),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ED25519)
    }

    @Test
    fun `always-ask fallback offers both remaining algorithms in order`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ED25519, ML_DSA_87),
                setOf(ED25519.family),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.AlwaysAsk,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ED25519, ML_DSA_87).inOrder()
    }

    @Test
    fun `fallback naming an unavailable algorithm degrades to first offered`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ED25519, ML_DSA_87),
                setOf(ED25519.family),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.Prefer(ES256.family),
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ED25519)
    }

    @Test
    fun `fallback is ignored when only one algorithm is left`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ED25519, ML_DSA_87),
                emptySet(),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.AlwaysAsk,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ML_DSA_87)
    }

    @Test
    fun `nothing is offered when no enabled algorithm is requested`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ED25519),
                setOf(ES256.family),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.AlwaysAsk,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).isEmpty()
    }

    @Test
    fun `a first-offered preference ignores the fallback`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ED25519, ML_DSA_87),
                setOf(ES256.family, ED25519.family),
                AlgorithmPreference.FirstOffered,
                AlgorithmPreference.Prefer(ML_DSA_87.family),
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ES256)
    }

    @Test
    fun `an always-ask preference offers every offered algorithm`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ED25519, ML_DSA_87),
                setOf(ES256.family),
                AlgorithmPreference.AlwaysAsk,
                AlgorithmPreference.Prefer(ED25519.family),
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ES256, ML_DSA_87).inOrder()
    }

    @Test
    fun `empty pubKeyCredParams treats every offerable algorithm as requested`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(),
                setOf(ED25519.family),
                AlgorithmPreference.Prefer(ES256.family),
                AlgorithmPreference.AlwaysAsk,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ED25519, ML_DSA_87).inOrder()
    }

    @Test
    fun `prefer-strongest takes ML-DSA-87 when the relying party accepts both parameter sets`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ML_DSA_65, ML_DSA_87),
                setOf(ES256.family),
                AlgorithmPreference.FirstOffered,
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ML_DSA_87)
    }

    @Test
    fun `prefer-strongest takes ML-DSA-65 when that is the only parameter set requested`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ML_DSA_65),
                setOf(ES256.family),
                AlgorithmPreference.FirstOffered,
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ML_DSA_65)
    }

    @Test
    fun `first-offered follows the relying party's order within ML-DSA`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ML_DSA_65, ML_DSA_87),
                setOf(ES256.family),
                AlgorithmPreference.FirstOffered,
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.FIRST_OFFERED,
            )

        assertThat(offered).containsExactly(ML_DSA_65)
    }

    @Test
    fun `always-ask offers ML-DSA once even when both parameter sets match`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ML_DSA_65, ML_DSA_87),
                setOf(ES256.family),
                AlgorithmPreference.AlwaysAsk,
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ML_DSA_87)
    }

    @Test
    fun `always-ask offers the parameter set the ML-DSA setting picked`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ML_DSA_65, ML_DSA_87),
                setOf(ES256.family),
                AlgorithmPreference.AlwaysAsk,
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.FIRST_OFFERED,
            )

        assertThat(offered).containsExactly(ML_DSA_65)
    }

    @Test
    fun `strongest-only offers nothing when the relying party takes ML-DSA-65 alone`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ML_DSA_65),
                setOf(ES256.family),
                AlgorithmPreference.FirstOffered,
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.STRONGEST_ONLY,
            )

        assertThat(offered).isEmpty()
    }

    @Test
    fun `an off setting offers no ML-DSA at all`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ML_DSA_65, ML_DSA_87),
                setOf(ES256.family),
                AlgorithmPreference.FirstOffered,
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.OFF,
            )

        assertThat(offered).isEmpty()
    }

    @Test
    fun `ML-DSA is offered without sitting in the enabled set`() {
        val offered =
            AlgorithmNegotiation.offeredAlgorithms(
                options(ES256, ML_DSA_87),
                setOf(ES256.family),
                AlgorithmPreference.AlwaysAsk,
                AlgorithmPreference.FirstOffered,
                MlDsaSupport.PREFER_STRONGEST,
            )

        assertThat(offered).containsExactly(ES256, ML_DSA_87).inOrder()
    }

    @Test
    fun `strongest-only keeps ML-DSA-65 out of the pinnable algorithms`() {
        val allowed = AlgorithmNegotiation.allowedAlgorithms(setOf(ES256.family), MlDsaSupport.STRONGEST_ONLY)

        assertThat(allowed).containsExactly(ES256, ML_DSA_87).inOrder()
    }

    @Test
    fun `an off setting keeps ML-DSA out of the pinnable algorithms`() {
        val allowed = AlgorithmNegotiation.allowedAlgorithms(setOf(ES256.family, ED25519.family), MlDsaSupport.OFF)

        assertThat(allowed).containsExactly(ES256, ED25519).inOrder()
    }
}
