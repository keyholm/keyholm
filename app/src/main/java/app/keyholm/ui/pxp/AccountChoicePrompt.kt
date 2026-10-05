package app.keyholm.ui.pxp

import app.keyholm.ctap.RelyingParty
import app.keyholm.store.PasskeyRecord

internal class AccountChoicePrompt(
    val rp: RelyingParty,
    val passkeys: List<PasskeyRecord>,
    val preferRpName: Boolean,
)

internal typealias AccountChooser = suspend (prompt: AccountChoicePrompt) -> PasskeyRecord?
