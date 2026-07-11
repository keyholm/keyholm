package app.keyholm.ui.common

internal sealed interface Outcome<out T> {
    data class Success<T>(
        val value: T,
    ) : Outcome<T>

    data class Failure(
        val toastMessage: String? = null,
    ) : Outcome<Nothing>
}
