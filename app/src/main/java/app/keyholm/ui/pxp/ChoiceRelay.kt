package app.keyholm.ui.pxp

import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.Relay
import app.keyholm.ui.createpasskey.CreationChoice
import app.keyholm.ui.createpasskey.CreationOptionsPrompt

internal class ChoiceRelay {
    val creationOptions = Relay<CreationOptionsPrompt, CreationChoice?>()
    val accountChoice = Relay<AccountChoicePrompt, PasskeyRecord?>()
}
