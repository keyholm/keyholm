package app.keyholm.ui.pxp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyholm.iconpack.IconPack
import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.RpIcon
import app.keyholm.ui.common.Sheet
import app.keyholm.ui.common.SheetHeader
import app.keyholm.ui.common.SheetRow
import app.keyholm.ui.common.SheetSection
import app.keyholm.ui.common.rememberAppIcon
import app.keyholm.ui.common.rpLabel
import app.keyholm.ui.common.userLabel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AccountPickerSheet(
    prompt: AccountChoicePrompt,
    iconPack: IconPack?,
    onChoice: (PasskeyRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val icon = rememberAppIcon(badged = true)
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Sheet(onDismiss) {
        SheetHeader(icon, "Pick a user to sign in to ${rpLabel(prompt.rp, prompt.preferRpName)} as:")
        SheetSection {
            prompt.passkeys.forEach { record ->
                item { rowShape ->
                    SheetRow(
                        title = userLabel(record.user.name, record.user.displayName),
                        supporting = "Last used ${dateFormat.format(Date.from(record.lastUsedAt))}",
                        shape = rowShape,
                        onClick = { onChoice(record) },
                    ) {
                        RpIcon(iconPack, prompt.rp, prompt.preferRpName, size = 24.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(56.dp))
    }
}
