package app.keyholm.ctap

import app.keyholm.authenticator.KeyFailure

sealed interface InternalFailure {
    data object Read : InternalFailure

    data object Write : InternalFailure

    data class Creating(
        val failure: KeyFailure,
    ) : InternalFailure

    data class Signing(
        val failure: KeyFailure,
    ) : InternalFailure
}
