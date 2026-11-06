package app.keyholm.ctap

// COSE algorithm identifiers
const val ALG_ES256 = -7
const val ALG_EDDSA = -8
const val ALG_ML_DSA_65 = -49
const val ALG_ML_DSA_87 = -50

enum class WebAuthnAlgorithm(
    val coseAlg: Int,
    val displayName: String,
    val family: AlgorithmFamily,
) {
    ES256(ALG_ES256, "ES256", AlgorithmFamily.ES256),
    ED25519(ALG_EDDSA, "Ed25519", AlgorithmFamily.ED25519),
    ML_DSA_87(ALG_ML_DSA_87, "ML-DSA-87", AlgorithmFamily.ML_DSA),
    ML_DSA_65(ALG_ML_DSA_65, "ML-DSA-65", AlgorithmFamily.ML_DSA),
    ;

    companion object {
        fun fromCoseAlg(coseAlg: Int): WebAuthnAlgorithm? = entries.firstOrNull { it.coseAlg == coseAlg }
    }
}

enum class AlgorithmFamily(
    val displayName: String,
) {
    ES256("ES256"),
    ED25519("Ed25519"),
    ML_DSA("ML-DSA"),
    ;

    val algorithms: List<WebAuthnAlgorithm> get() = WebAuthnAlgorithm.entries.filter { it.family == this }

    companion object {
        fun named(name: String): AlgorithmFamily = entries.firstOrNull { it.name == name } ?: error("unknown algorithm-family token $name")

        fun fromStrings(strings: Set<String>): Set<AlgorithmFamily> = strings.mapTo(mutableSetOf(), ::named)

        fun toStrings(families: Set<AlgorithmFamily>): Set<String> = families.map { it.name }.toSet()
    }
}
