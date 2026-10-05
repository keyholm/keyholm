package app.keyholm.ui.pxp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyholm.store.PasskeyRecord
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
    onChoice: (PasskeyRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val icon = rememberAppIcon(badged = true)
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Sheet(onDismiss) {
        SheetHeader(icon, "Sign in to ${rpLabel(prompt.rp, prompt.preferRpName)}")
        SheetSection {
            prompt.passkeys.forEach { record ->
                item { rowShape ->
                    SheetRow(
                        title = userLabel(record.user.name, record.user.displayName),
                        supporting = "Last used ${dateFormat.format(Date.from(record.lastUsedAt))}",
                        icon = icon,
                        shape = rowShape,
                        onClick = { onChoice(record) },
                    )
                }
            }
        }
        Spacer(Modifier.height(56.dp))
    }
}
