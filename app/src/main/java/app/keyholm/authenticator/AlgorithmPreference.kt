package app.keyholm.authenticator

import app.keyholm.ctap.AlgorithmFamily

sealed interface AlgorithmPreference {
    data object FirstOffered : AlgorithmPreference

    data object AlwaysAsk : AlgorithmPreference

    data class Prefer(
        val family: AlgorithmFamily,
    ) : AlgorithmPreference
}
