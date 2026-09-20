package app.keyholm.ui.main.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import app.keyholm.store.MigrationExportEntry
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.migrationExportUri
import app.keyholm.ui.common.Section
import app.keyholm.ui.main.ImportReview
import app.keyholm.ui.main.MainUiState
import app.keyholm.ui.main.MainViewModel
import app.keyholm.ui.main.Stored
import app.keyholm.util.encodeQrCode
import kotlinx.serialization.json.Json

private const val NO_ACCOUNTS_TO_EXPORT = "No accounts on this device to export."

private fun List<PasskeyRecord>.toMigrationExportEntries(): List<MigrationExportEntry> =
    map {
        MigrationExportEntry(
            rpId = it.rp.id,
            userName = it.user.name,
            displayName = it.user.displayName,
            createdAt = it.createdAt.toEpochMilli(),
        )
    }

private fun exportMigrationList(
    context: Context,
    passkeys: List<PasskeyRecord>,
    reportError: (String) -> Unit,
) {
    if (passkeys.isEmpty()) {
        reportError(NO_ACCOUNTS_TO_EXPORT)
        return
    }
    val entries = passkeys.toMigrationExportEntries()
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/json"
            putExtra(Intent.EXTRA_TEXT, Json.encodeToString(entries))
        }
    context.startActivity(Intent.createChooser(intent, "Export migration list"))
}

private fun importMigrationList(
    context: Context,
    viewModel: MainViewModel,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text =
        clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
    val entries =
        text
            ?.let { runCatching { Json.decodeFromString<List<MigrationExportEntry>>(it) }.getOrNull() }
            .orEmpty()
    if (entries.isEmpty()) {
        viewModel.reportError("No import list found.")
        return
    }
    viewModel.imports.request(ImportReview.InApp(entries))
}

private const val QR_SIZE_PX = 768
private const val TOO_MANY_ACCOUNTS_FOR_QR = "Too many accounts for a QR code, use Export instead."

@Composable
private fun QrExportDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan on your other device") },
        text = { Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MigrationItem(
    label: String,
    icon: ImageVector,
    shape: Shape,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
    ) {
        Text(label)
    }
}

private fun qrExportBitmap(
    passkeys: List<PasskeyRecord>,
    reportError: (String) -> Unit,
): Bitmap? {
    if (passkeys.isEmpty()) {
        reportError(NO_ACCOUNTS_TO_EXPORT)
        return null
    }
    val bitmap = encodeQrCode(migrationExportUri(passkeys.toMigrationExportEntries()), QR_SIZE_PX)
    if (bitmap == null) {
        reportError(TOO_MANY_ACCOUNTS_FOR_QR)
    }
    return bitmap
}

@Composable
internal fun AccountMigrationSection(
    uiState: MainUiState.Ready,
    viewModel: MainViewModel,
    context: Context,
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    Section(
        title = SECTION_TITLE_ACCOUNT_MIGRATION,
        description = "Keep track of accounts needing key recreation when migrating to another device.",
    ) {
        val passkeys = uiState.passkeys
        if (passkeys is Stored.Available) {
            item { shape ->
                MigrationItem("Export QR code", Icons.Default.QrCode, shape) {
                    qrBitmap = qrExportBitmap(passkeys.value, viewModel.reportError)
                }
            }
            item { shape ->
                MigrationItem("Export", Icons.Default.Share, shape) {
                    exportMigrationList(context, passkeys.value, viewModel.reportError)
                }
            }
        }
        item { shape ->
            MigrationItem("Import from clipboard", Icons.Default.Download, shape) {
                importMigrationList(context, viewModel)
            }
        }
    }
    qrBitmap?.let { bitmap ->
        QrExportDialog(bitmap, onDismiss = { qrBitmap = null })
    }
}
