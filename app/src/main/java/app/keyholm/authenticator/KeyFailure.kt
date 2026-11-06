package app.keyholm.authenticator

import java.security.ProviderException

sealed interface KeyFailure {
    data class SecureElementUnavailable(
        val message: String?,
    ) : KeyFailure

    data object KeyInvalidated : KeyFailure

    data object SecurityError : KeyFailure

    data class HardwareError(
        val cause: ProviderException,
    ) : KeyFailure
}

sealed interface KeyAccess<out T> {
    data class Success<T>(
        val value: T,
    ) : KeyAccess<T>

    data class Failure(
        val failure: KeyFailure,
    ) : KeyAccess<Nothing>
}
