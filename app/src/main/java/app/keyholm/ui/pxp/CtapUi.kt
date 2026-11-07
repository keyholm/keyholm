package app.keyholm.ui.pxp

import app.keyholm.ctap.InternalFailure
import app.keyholm.ui.common.PromptRelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class CtapUi {
    val prompts = PromptRelay()
    val choices = ChoiceRelay()
    private val _failure = MutableStateFlow<InternalFailure?>(null)
    val failure: StateFlow<InternalFailure?> = _failure.asStateFlow()

    // Keeps the first failure.
    fun report(failure: InternalFailure) {
        _failure.compareAndSet(null, failure)
    }

    fun clear() {
        _failure.value = null
    }
}
