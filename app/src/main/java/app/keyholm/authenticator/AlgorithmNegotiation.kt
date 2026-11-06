package app.keyholm.authenticator

import app.keyholm.ctap.AlgorithmFamily
import app.keyholm.ctap.WebAuthnAlgorithm

object AlgorithmNegotiation {
    private fun candidates(
        requested: List<WebAuthnAlgorithm>?,
        enabled: Set<AlgorithmFamily>,
        mlDsa: MlDsaSupport,
    ): List<WebAuthnAlgorithm> {
        val mlDsaOrder =
            when (mlDsa) {
                MlDsaSupport.OFF -> {
                    emptyList()
                }

                MlDsaSupport.STRONGEST_ONLY -> {
                    listOf(WebAuthnAlgorithm.ML_DSA_87)
                }

                MlDsaSupport.PREFER_STRONGEST -> {
                    AlgorithmFamily.ML_DSA.algorithms
                }

                MlDsaSupport.FIRST_OFFERED -> {
                    requested?.filter { it.family == AlgorithmFamily.ML_DSA } ?: AlgorithmFamily.ML_DSA.algorithms
                }
            }
        return WebAuthnAlgorithm.entries
            .filter { it.family != AlgorithmFamily.ML_DSA && it.family in enabled } + mlDsaOrder
    }

    fun allowedAlgorithms(
        enabled: Set<AlgorithmFamily>,
        mlDsa: MlDsaSupport,
    ): List<WebAuthnAlgorithm> = candidates(requested = null, enabled = enabled, mlDsa = mlDsa)

    private fun matchingAlgorithms(
        requested: List<WebAuthnAlgorithm>?,
        enabled: Set<AlgorithmFamily>,
        mlDsa: MlDsaSupport,
    ): List<WebAuthnAlgorithm> {
        val candidates = candidates(requested, enabled, mlDsa)
        if (requested == null) return candidates.distinctBy { it.family }
        // The ML-DSA setting already picked the parameter set, so a family is never offered twice.
        return candidates
            .filter { it in requested }
            .distinctBy { it.family }
            .sortedBy { algorithm -> requested.indexOfFirst { it.family == algorithm.family } }
    }

    fun offeredAlgorithms(
        requested: List<WebAuthnAlgorithm>?,
        enabled: Set<AlgorithmFamily>,
        preferred: AlgorithmPreference,
        fallback: AlgorithmPreference,
        mlDsa: MlDsaSupport,
    ): List<WebAuthnAlgorithm> {
        val matching = matchingAlgorithms(requested, enabled, mlDsa)
        return when (preferred) {
            AlgorithmPreference.AlwaysAsk -> {
                matching
            }

            AlgorithmPreference.FirstOffered -> {
                listOfNotNull(matching.firstOrNull())
            }

            is AlgorithmPreference.Prefer -> {
                firstOf(matching, preferred.family) ?: applyFallback(matching, fallback)
            }
        }
    }

    private fun firstOf(
        matching: List<WebAuthnAlgorithm>,
        family: AlgorithmFamily,
    ): List<WebAuthnAlgorithm>? =
        matching
            .firstOrNull {
                it.family ==
                    family
            }?.let { listOf(it) }

    // The fallback only decides between algorithms left over once the preferred one turned out to be unavailable.
    private fun applyFallback(
        matching: List<WebAuthnAlgorithm>,
        fallback: AlgorithmPreference,
    ): List<WebAuthnAlgorithm> {
        if (matching.size < 2) return matching
        return when (fallback) {
            AlgorithmPreference.AlwaysAsk -> {
                matching
            }

            AlgorithmPreference.FirstOffered -> {
                listOf(matching.first())
            }

            is AlgorithmPreference.Prefer -> {
                firstOf(matching, fallback.family) ?: listOf(matching.first())
            }
        }
    }
}
