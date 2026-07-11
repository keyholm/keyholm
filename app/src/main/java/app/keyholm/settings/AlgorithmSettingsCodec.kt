package app.keyholm.settings

import app.keyholm.webauthn.AlgorithmFamily
import app.keyholm.webauthn.AlgorithmPreference
import app.keyholm.webauthn.MlDsaSupport

private const val ALGORITHM_FIRST_OFFERED = "FIRST_OFFERED"
private const val ALGORITHM_ALWAYS_ASK = "ALWAYS_ASK"
private const val ML_DSA_OFF = "OFF"
private const val ML_DSA_STRONGEST_ONLY = "STRONGEST_ONLY"
private const val ML_DSA_PREFER_STRONGEST = "PREFER_STRONGEST"
private const val ML_DSA_FIRST_OFFERED = "FIRST_OFFERED"

val DEFAULT_ENABLED_FAMILIES: Set<AlgorithmFamily> = setOf(AlgorithmFamily.ES256)

internal fun encodeAlgorithmPreference(preference: AlgorithmPreference): String =
    when (preference) {
        AlgorithmPreference.FirstOffered -> ALGORITHM_FIRST_OFFERED
        AlgorithmPreference.AlwaysAsk -> ALGORITHM_ALWAYS_ASK
        is AlgorithmPreference.Prefer -> preference.family.name
    }

internal fun decodeAlgorithmPreference(stored: String?): AlgorithmPreference =
    when (stored) {
        null, ALGORITHM_FIRST_OFFERED -> AlgorithmPreference.FirstOffered
        ALGORITHM_ALWAYS_ASK -> AlgorithmPreference.AlwaysAsk
        else -> AlgorithmPreference.Prefer(AlgorithmFamily.named(stored))
    }

internal fun decodeEnabledFamilies(stored: Set<String>?): Set<AlgorithmFamily> {
    if (stored == null) return DEFAULT_ENABLED_FAMILIES
    val families = AlgorithmFamily.fromStrings(stored)
    check(families.isNotEmpty()) { "stored enabled-families is empty" }
    return families
}

internal fun encodeMlDsaSupport(support: MlDsaSupport): String =
    when (support) {
        MlDsaSupport.OFF -> ML_DSA_OFF
        MlDsaSupport.STRONGEST_ONLY -> ML_DSA_STRONGEST_ONLY
        MlDsaSupport.PREFER_STRONGEST -> ML_DSA_PREFER_STRONGEST
        MlDsaSupport.FIRST_OFFERED -> ML_DSA_FIRST_OFFERED
    }

internal fun decodeMlDsaSupport(stored: String?): MlDsaSupport =
    when (stored) {
        null -> MlDsaSupport.OFF
        ML_DSA_OFF -> MlDsaSupport.OFF
        ML_DSA_STRONGEST_ONLY -> MlDsaSupport.STRONGEST_ONLY
        ML_DSA_PREFER_STRONGEST -> MlDsaSupport.PREFER_STRONGEST
        ML_DSA_FIRST_OFFERED -> MlDsaSupport.FIRST_OFFERED
        else -> error("unknown ML-DSA support token $stored")
    }
